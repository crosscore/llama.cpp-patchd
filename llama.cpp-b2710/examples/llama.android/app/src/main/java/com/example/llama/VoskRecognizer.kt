package com.example.llama

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.util.Date

class VoskRecognizer private constructor(private val context: Context) {
    private val tag = "VoskRecognizer"

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var speakerIdentifier: SpeakerIdentifier? = null

    // モデルのダウンロード先ディレクトリ
    private val modelDir: File
        get() = context.getExternalFilesDir(null) ?: throw IllegalStateException("External storage is not available")

    // Recognition callbacks
    var onPartialResult: ((String) -> Unit)? = null
    var onResult: ((String) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null
    var onSpeakerIdentified: ((String, Float) -> Unit)? = null

    // 音声バッファ（話者識別用）
    private val audioBuffer = mutableListOf<Short>()
    private val speakerBufferSize = 16000 * 5 // 5秒分のオーディオデータ

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            onPartialResult?.invoke(hypothesis)
        }

        override fun onResult(hypothesis: String) {
            try {
                val json = JSONObject(hypothesis)
                val text = json.optString("text")

                if (text.isNotBlank()) {
                    onResult?.invoke(hypothesis)

                    // 登録済みの話者を取得
                    val registeredSpeakers = speakerStorage.getAllSpeakerMetadata()

                    // 話者名とIDの決定
                    val (speakerId, speakerName) = when {
                        // 登録済み話者が存在しない場合
                        registeredSpeakers.isEmpty() -> {
                            Pair("unknown", "Unknown Speaker")
                        }
                        // 登録済み話者が存在する場合、最も類似度が高い話者を選択
                        else -> {
                            val audioData = audioBuffer.toShortArray()
                            val embedding = speakerIdentifier?.extractEmbedding(audioData)

                            if (embedding != null) {
                                var bestScore = -1f
                                var bestSpeaker: SpeakerStorage.SpeakerMetadata? = null

                                registeredSpeakers.forEach { speaker ->
                                    speakerIdentifier?.let { identifier ->
                                        identifier.identifySpeaker(embedding)?.let { (id, score) ->
                                            if (score > bestScore) {
                                                bestScore = score
                                                bestSpeaker = speaker
                                            }
                                        }
                                    }
                                }

                                bestSpeaker?.let {
                                    Pair(it.id, it.name)
                                } ?: Pair("unknown", "Unknown Speaker")
                            } else {
                                // デフォルトとして最初の登録話者を使用
                                val defaultSpeaker = registeredSpeakers.first()
                                Pair(defaultSpeaker.id, defaultSpeaker.name)
                            }
                        }
                    }

                    // 会話エントリーを追加
                    conversationStorage.addEntry(
                        ConversationHistoryStorage.ConversationEntry(
                            speakerId = speakerId,
                            speakerName = speakerName,
                            message = text,
                            timestamp = System.currentTimeMillis(),
                            confidence = currentSpeakerConfidence ?: 0f
                        )
                    )
                    // 最新の会話履歴を更新
                    updateRecentConversations()

                    // 話者識別用のバッファをクリア
                    if (audioBuffer.size >= speakerBufferSize) {
                        audioBuffer.clear()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error processing recognition result", e)
                onError?.invoke(e)
            }
        }

        override fun onFinalResult(hypothesis: String) {
            // 最終結果（onResultと同様の処理）
            onResult?.invoke(hypothesis)
        }

        override fun onError(exception: Exception) {
            Log.e(tag, "Recognition error", exception)
            onError?.invoke(exception)
        }

        override fun onTimeout() {
            Log.w(tag, "Recognition timeout")
        }
    }

    /**
     * 初期化処理
     */
    fun initialize() {
        speakerIdentifier = SpeakerIdentifier.getInstance(context)
        if (speakerIdentifier?.initModel() != true) {
            Log.e(tag, "Failed to initialize speaker identification")
            onError?.invoke(IllegalStateException("Failed to initialize speaker identification"))
        }
    }

    /**
     * モデルの初期化
     * @param modelName モデルのディレクトリ名
     * @return 初期化が成功したかどうか
     */
    fun initModel(modelName: String): Boolean {
        try {
            val modelPath = File(modelDir, modelName).absolutePath
            Log.i(tag, "Loading model from $modelPath")

            // モデルディレクトリの存在チェック
            val modelFile = File(modelPath)
            if (!modelFile.exists() || !modelFile.isDirectory) {
                Log.e(tag, "Model directory not found at $modelPath")
                onError?.invoke(IOException("Model directory not found"))
                return false
            }

            // モデルの初期化
            model = Model(modelPath)

            // 認識器の初期化
            recognizer = Recognizer(model, 16000.0f)

            // 話者識別の初期化
            initialize()

            Log.i(tag, "Model loaded successfully")
            return true
        } catch (e: IOException) {
            Log.e(tag, "Failed to load model", e)
            onError?.invoke(e)
            return false
        }
    }

    /**
     * 音声認識の開始
     */
    fun startListening() {
        if (speechService != null) {
            Log.w(tag, "Speech service is already running")
            return
        }

        recognizer?.let { rec ->
            try {
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(recognitionListener)
                Log.i(tag, "Started listening")
            } catch (e: IOException) {
                Log.e(tag, "Failed to start listening", e)
                onError?.invoke(e)
            }
        } ?: run {
            Log.e(tag, "Recognizer is not initialized")
            onError?.invoke(IllegalStateException("Recognizer is not initialized"))
        }
    }

    /**
     * 音声データの追加（話者識別用のバッファリングのみ）
     */
    fun addAudioData(audioData: ShortArray) {
        try {
            // バッファサイズの確認
            if (audioData.isEmpty()) {
                Log.w(tag, "Empty audio data received")
                return
            }

            // 話者識別用のバッファ処理のみを行う
            audioBuffer.addAll(audioData.toList())
            if (audioBuffer.size >= speakerBufferSize) {
                val bufferData = audioBuffer.toShortArray()
                audioBuffer.clear()
                performSpeakerIdentification(bufferData)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in addAudioData", e)
            onError?.invoke(e)
        }
    }

    /**
     * 音声認識の停止
     */
    fun stopListening() {
        speechService?.let { service ->
            service.stop()
            service.shutdown()
            speechService = null
            Log.i(tag, "Stopped listening")
        }
    }

    /**
     * 話者識別の実行
     */
    private fun performSpeakerIdentification(audioData: ShortArray) {
        try {
            // 音声データの検証
            if (audioData.isEmpty()) {
                Log.w(tag, "Empty audio data, skipping speaker identification")
                return
            }

            // 音声データの長さチェック
            if (audioData.size < 16000) { // 最低1秒分のデータ
                Log.w(tag, "Audio data too short for speaker identification")
                return
            }

            // DC オフセットの除去とノーマライズ
            val processedAudio = preprocessAudioData(audioData)

            speakerIdentifier?.let { identifier ->
                val embedding = identifier.extractEmbedding(processedAudio)
                embedding?.let { emb ->
                    identifier.identifySpeaker(emb)?.let { (speakerId, score) ->
                        // スコアの閾値チェックを追加
                        if (score > 0.7f) {  // 70%以上の信頼度
                            onSpeakerIdentified?.invoke(speakerId, score)
                        } else {
                            Log.d(tag, "Speaker identification score too low: $score")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in speaker identification", e)
            onError?.invoke(e)
        }
    }

    // 音声データの前処理
    private fun preprocessAudioData(audioData: ShortArray): ShortArray {
        var sum = 0.0
        // DC オフセットを計算
        audioData.forEach { sample ->
            sum += sample
        }
        val dcOffset = (sum / audioData.size).toInt()

        // 最大振幅を見つける
        var maxAmplitude = 1 // ゼロ除算を防ぐ
        audioData.forEach { sample ->
            val amplitude = kotlin.math.abs(sample - dcOffset)
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
            }
        }

        // DCオフセットを除去し、正規化
        return ShortArray(audioData.size) { i ->
            val normalizedSample = ((audioData[i] - dcOffset).toFloat() * 32767 / maxAmplitude).toInt()
            normalizedSample.toShort()
        }
    }

    /**
     * 話者登録の実際の処理を行う
     * 音声データから特徴ベクトルを抽出し、ストレージに保存する
     * @param id 登録する話者のID
     * @param name 登録する話者の名前
     * @param audioData 話者の音声データ
     * @return 登録が成功したかどうか
    */
    fun registerSpeaker(id: String, name: String, audioData: ShortArray): Boolean {
        return try {
            speakerIdentifier?.let { identifier ->
                // 特徴ベクトルの抽出
                val embedding = identifier.extractEmbedding(audioData)
                embedding?.let { emb ->
                    // SpeakerIdentifier に登録
                    identifier.registerSpeaker(id, name, emb)

                    // SpeakerStorage にも保存
                    val storage = SpeakerStorage.getInstance(context)

                    // 録音データを保存
                    val recordingFile = storage.saveSpeakerRecording(id, audioData)

                    // 特徴ベクトルを保存
                    val embeddingFile = storage.saveSpeakerEmbedding(id, emb)

                    // メタデータを保存
                    val metadata = SpeakerStorage.SpeakerMetadata(
                        id = id,
                        name = name,
                        registrationDate = Date(),
                        samplePath = recordingFile.absolutePath,
                        embeddingPath = embeddingFile.absolutePath
                    )
                    storage.saveSpeakerMetadata(metadata)

                    true
                } ?: false
            } ?: false
        } catch (e: Exception) {
            Log.e(tag, "Failed to register speaker", e)
            false
        }
    }

    /**
     * リソースの解放
     */
    fun release() {
        try {
            coroutineScope.cancel()
            speechService?.shutdown()
            speechService = null
            recognizer?.close()
            recognizer = null
            model?.close()
            model = null
            speakerIdentifier?.release()
            speakerIdentifier = null
            audioBuffer.clear()
            Log.i(tag, "Resources released")
        } catch (e: Exception) {
            Log.e(tag, "Error releasing resources", e)
            onError?.invoke(e)
        }
    }

    /**
     * モデルディレクトリの存在確認
     * @param modelName モデルのディレクトリ名
     * @return モデルが存在するかどうか
     */
    fun isModelAvailable(modelName: String): Boolean {
        val modelPath = File(modelDir, modelName)
        return modelPath.exists() && modelPath.isDirectory
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: VoskRecognizer? = null

        fun getInstance(context: Context): VoskRecognizer {
            return instance ?: synchronized(this) {
                instance ?: VoskRecognizer(context).also { instance = it }
            }
        }
        // VOSKのモデルのフォルダ名
        const val VOSK_MODEL_NAME = "vosk-model-small-ja-0.22"
        // 話者識別モデルのフォルダ名
        const val SPEAKER_MODEL_NAME = "vosk-model-spk-0.4"
    }
}
