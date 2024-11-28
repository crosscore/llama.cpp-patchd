// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/VoskRecognizer.kt
/**
 * Voskエンジンを使用した音声認識処理の実装クラス
 *
 * このクラスは以下の主要な機能を提供します：
 * - 音声認識モデルの初期化と管理
 * - リアルタイムの音声認識処理
 * - 話者識別との連携
 * - 認識結果の永続化
 *
 * 技術的な特徴：
 * - Singleton パターンによる実装
 * - Vosk音声認識エンジンとの統合
 * - 16kHz、16bitモノラル音声入力対応
 * - 日本語モデル「vosk-model-small-ja-0.22」使用
 *
 * 音声認識の処理フロー：
 * 1. 音声データのバッファリング
 * 2. 話者識別処理の実行
 * 3. 音声認識結果の生成
 * 4. 結果の永続化と通知
 *
 * @property context アプリケーションコンテキスト
 * @property onPartialResult 中間認識結果のコールバック
 * @property onResult 最終認識結果のコールバック
 * @property onError エラー発生時のコールバック
 *
 * 使用するモデル：
 * - 音声認識: vosk-model-small-ja-0.22
 * - 話者識別: vosk-model-spk-0.4
 *
 * @see SpeechService
 * @see Model
 * @see Recognizer
 * @see SpeakerIdentifier
 */

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

    // 話者識別用
    private val audioBuffer = mutableListOf<Short>()
    private val speakerBufferSize = 16000 * 2  // 2秒分に短縮
    private val switchingThreshold = 0.3f      // 話者切り替わり用の閾値
    private var lastSpeakerId: String? = null
    private var lastConfidence: Float = 0f
    private var lastIdentificationTime = System.currentTimeMillis()
    private val identificationTimeout = 10000L  // 10秒

    // SpeakerStorageのインスタンス
    private val speakerStorage by lazy {
        SpeakerStorage.getInstance(context)
    }

    // ConversationHistoryStorageのインスタンス
    private val conversationStorage by lazy {
        ConversationHistoryStorage.getInstance(context)
    }

    // 現在の話者識別の信頼度
    private var currentSpeakerConfidence: Float? = null

    // 現在の話者ID
    private var currentSpeakerId: String? = null

    // 会話履歴の更新メソッド
    private fun updateRecentConversations() {
        // conversationStorageから最新のエントリを取得
        conversationStorage.getRecentEntries().also { entries ->
            // ViewModelなどに通知するためのコールバックがあれば実行
            onConversationsUpdated?.invoke(entries)
        }
    }

    // 会話履歴更新時のコールバック
    private var onConversationsUpdated: ((List<ConversationHistoryStorage.ConversationEntry>) -> Unit)? = null

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            onPartialResult?.invoke(hypothesis)
        }

        override fun onResult(hypothesis: String) {
            onResultHandler(hypothesis)
        }

        override fun onFinalResult(hypothesis: String) {
            onResultHandler(hypothesis)
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
                // 新しいセッションを開始
                conversationStorage.startNewSession()

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
            if (audioData.isEmpty() || audioData.size < 16000) {
                Log.w(tag, "Insufficient audio data for speaker identification")
                return
            }

            checkIdentificationTimeout()

            val processedAudio = preprocessAudioData(audioData)

            speakerIdentifier?.let { identifier ->
                val embedding = identifier.extractEmbedding(processedAudio)
                embedding?.let { emb ->
                    identifier.identifySpeaker(emb)?.let { (speakerId, score) ->
                        val shouldSwitch = when {
                            speakerId != lastSpeakerId -> true
                            speakerId == lastSpeakerId && (lastConfidence - score) > switchingThreshold -> false
                            else -> speakerId == currentSpeakerId
                        }

                        if (shouldSwitch) {
                            currentSpeakerId = speakerId
                            currentSpeakerConfidence = score
                            onSpeakerIdentified?.invoke(speakerId, score)
                            Log.d(tag, "Speaker switched to: $speakerId with confidence: $score")

                            // 保留中のメッセージがあれば処理
                            processPendingMessages()
                        }
                        lastSpeakerId = speakerId
                        lastConfidence = score
                        lastIdentificationTime = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in speaker identification", e)
            onError?.invoke(e)
        }
    }

    // 保留中のメッセージを処理
    private fun processPendingMessages() {
        pendingMessages.forEach { message ->
            saveSpeechEntry(message.text, message.timestamp)
        }
        pendingMessages.clear()
    }

    // 会話エントリーの保存
    private fun saveSpeechEntry(text: String, timestamp: Long = System.currentTimeMillis()) {
        val speakerMetadata = speakerStorage.getAllSpeakerMetadata()
            .find { it.id == currentSpeakerId }

        // 会話エントリーを追加
        val entry = ConversationHistoryStorage.ConversationEntry(
            speakerId = currentSpeakerId ?: "unknown",
            speakerName = speakerMetadata?.name ?: "Unknown Speaker",
            message = text,
            timestamp = timestamp,
            confidence = currentSpeakerConfidence ?: 0f
        )

        conversationStorage.addEntry(entry)
        updateRecentConversations()

        Log.d(tag, "Saved speech entry: $entry")
    }

    private fun checkIdentificationTimeout() {
        if (System.currentTimeMillis() - lastIdentificationTime > identificationTimeout) {
            lastSpeakerId = null
            lastConfidence = 0f
            Log.d(tag, "Speaker identification state reset due to timeout")
        }
    }

    // 保留中のメッセージを保持するキュー
    private data class PendingMessage(
        val text: String,
        val timestamp: Long
    )
    private val pendingMessages = mutableListOf<PendingMessage>()

    private fun onResultHandler(hypothesis: String) {
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text")

            if (text.isNotBlank()) {
                onResult?.invoke(hypothesis)

                // 現在の話者が未設定の場合は、メッセージを一時保留
                if (currentSpeakerId == null) {
                    pendingMessages.add(PendingMessage(text, System.currentTimeMillis()))
                    Log.d(tag, "Message queued for pending speaker identification: $text")
                    return
                }

                // 話者が設定済みの場合は直接保存
                saveSpeechEntry(text)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing recognition result", e)
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
                    // SpeakerStorage にデータを保存
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

                    Log.i(tag, "Successfully registered speaker: $name (ID: $id)")
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
