package com.example.llama

import android.app.Application
import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import org.json.JSONObject
import java.lang.ref.WeakReference

class SpeakerIdentifier private constructor(application: Application) {
    private val tag = "SpeakerIdentifier"
    private var speakerModel: SpeakerModel? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    // WeakReferenceを使用してApplicationコンテキストを保持
    private val contextRef = WeakReference(application)

    // スピーカープロファイルを保存するためのデータクラス
    data class SpeakerProfile(
        val id: String,
        val name: String,
        val embedding: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SpeakerProfile
            return id == other.id && embedding.contentEquals(other.embedding)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + embedding.contentHashCode()
            return result
        }
    }

    // 登録済みのスピーカープロファイル
    private val speakerProfiles = mutableMapOf<String, SpeakerProfile>()

    companion object {
        const val SPEAKER_MODEL_NAME = "vosk-model-spk-0.4"
        private const val SIMILARITY_THRESHOLD = 0.7f
        private const val SAMPLE_RATE = 16000f

        @Volatile
        private var instance: SpeakerIdentifier? = null

        fun getInstance(context: Context): SpeakerIdentifier {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        // コンテキストがApplicationでない場合はApplicationコンテキストを取得
                        val applicationContext = context.applicationContext
                        if (applicationContext !is Application) {
                            throw IllegalArgumentException("Context must be an Application context")
                        }
                        instance = SpeakerIdentifier(applicationContext)
                    }
                }
            }
            return instance!!
        }

        fun cleanup() {
            synchronized(this) {
                instance?.release()
                instance = null
            }
        }
    }

    /**
     * 話者識別モデルの初期化
     */
    fun initModel(): Boolean {
        return try {
            val context = contextRef.get() ?: throw IllegalStateException("Context has been garbage collected")
            val modelDir = context.getExternalFilesDir(null)
                ?: throw IllegalStateException("External storage is not available")

            // 音声認識モデルのパス
            val modelPath = File(modelDir, "vosk-model-small-ja-0.22").absolutePath
            // 話者識別モデルのパス
            val speakerModelPath = File(modelDir, SPEAKER_MODEL_NAME).absolutePath

            if (!File(speakerModelPath).exists()) {
                Log.e(tag, "Speaker model not found at $speakerModelPath")
                return false
            }

            // 音声認識モデルの初期化
            model = Model(modelPath)
            // 話者識別モデルの初期化
            speakerModel = SpeakerModel(speakerModelPath)
            // 認識器の初期化（話者識別モデル付き）
            recognizer = Recognizer(model!!, SAMPLE_RATE, speakerModel!!)

            Log.i(tag, "Speaker identification model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize speaker model", e)
            false
        }
    }

    /**
     * 音声データから話者の特徴ベクトルを抽出
     */
    fun extractEmbedding(audioData: ShortArray): FloatArray? {
        return try {
            recognizer?.let { rec ->
                // 音声データを供給
                rec.acceptWaveForm(audioData, audioData.size)
                // 結果を取得（JSON形式）
                val result = rec.finalResult
                // JSONから話者ベクトルを抽出
                val json = JSONObject(result)
                if (json.has("spk")) {
                    val spkArray = json.getJSONArray("spk")
                    FloatArray(spkArray.length()) { i ->
                        spkArray.getDouble(i).toFloat()
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract embedding", e)
            null
        }
    }

    /**
     * 新しい話者プロファイルの登録
     */
    fun registerSpeaker(id: String, name: String, embedding: FloatArray) {
        val profile = SpeakerProfile(id, name, embedding)
        speakerProfiles[id] = profile
        Log.i(tag, "Registered new speaker profile: $id ($name)")
    }

    /**
     * 話者の識別
     */
    fun identifySpeaker(embedding: FloatArray): Pair<String, Float>? {
        if (speakerProfiles.isEmpty()) {
            Log.w(tag, "No speaker profiles registered")
            return null
        }

        var bestMatch: Pair<String, Float>? = null
        speakerProfiles.forEach { (id, profile) ->
            val similarity = cosineSimilarity(embedding, profile.embedding)
            if (similarity > SIMILARITY_THRESHOLD && (bestMatch == null || similarity > bestMatch!!.second)) {
                bestMatch = Pair(id, similarity)
            }
        }

        bestMatch?.let { (id, score) ->
            Log.i(tag, "Identified speaker: $id (score: $score)")
        }

        return bestMatch
    }

    /**
     * コサイン類似度の計算
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA > 0 && normB > 0) {
            dotProduct / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        } else {
            0f
        }
    }

    /**
     * リソースの解放
     */
    fun release() {
        try {
            recognizer?.close()
            recognizer = null
            speakerModel?.close()
            speakerModel = null
            model?.close()
            model = null
            speakerProfiles.clear()
            contextRef.clear()
            Log.i(tag, "Speaker identifier resources released")
        } catch (e: Exception) {
            Log.e(tag, "Error releasing speaker identifier resources", e)
        }
    }
}
