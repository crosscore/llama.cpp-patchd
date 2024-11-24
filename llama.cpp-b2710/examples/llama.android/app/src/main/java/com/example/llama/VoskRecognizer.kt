package com.example.llama

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException

class VoskRecognizer private constructor(private val context: Context) {
    private val tag = "VoskRecognizer"

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
            // 部分的な認識結果
            onPartialResult?.invoke(hypothesis)
        }

        override fun onResult(hypothesis: String) {
            // 最終的な認識結果
            onResult?.invoke(hypothesis)

            // 話者識別の実行
            if (audioBuffer.size >= speakerBufferSize) {
                performSpeakerIdentification(audioBuffer.toShortArray())
                audioBuffer.clear()
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
     * @param modelName モデルのディレクトリ名（例: "vosk-model-small-ja-0.22"）
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
     * 音声データの追加と話者識別
     */
    fun addAudioData(audioData: ShortArray) {
        audioBuffer.addAll(audioData.toList())

        // バッファが一定サイズを超えたら話者識別を実行
        if (audioBuffer.size >= speakerBufferSize) {
            performSpeakerIdentification(audioBuffer.toShortArray())
            audioBuffer.clear()
        }
    }

    /**
     * 話者識別の実行
     */
    private fun performSpeakerIdentification(audioData: ShortArray) {
        speakerIdentifier?.let { identifier ->
            val embedding = identifier.extractEmbedding(audioData)
            embedding?.let { emb ->
                identifier.identifySpeaker(emb)?.let { (speakerId, score) ->
                    onSpeakerIdentified?.invoke(speakerId, score)
                }
            }
        }
    }

    /**
     * 新しい話者の登録
     */
    fun registerSpeaker(id: String, name: String, audioData: ShortArray): Boolean {
        return try {
            speakerIdentifier?.let { identifier ->
                val embedding = identifier.extractEmbedding(audioData)
                embedding?.let { emb ->
                    identifier.registerSpeaker(id, name, emb)
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

        // デフォルトのモデル名
        const val DEFAULT_MODEL_NAME = "vosk-model-small-ja-0.22"
        // 話者識別モデル名
        const val SPEAKER_MODEL_NAME = "vosk-model-spk-0.4"
    }
}
