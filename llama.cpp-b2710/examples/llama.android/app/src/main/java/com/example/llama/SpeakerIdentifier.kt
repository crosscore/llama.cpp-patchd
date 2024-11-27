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
    private var speakerRecognizer: Recognizer? = null
    private var model: Model? = null

    private val contextRef = WeakReference(application)

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

    private val speakerProfiles = mutableMapOf<String, SpeakerProfile>()

    companion object {
        private const val SIMILARITY_THRESHOLD = 0.7f
        private const val SAMPLE_RATE = 16000f

        @Volatile
        private var instance: SpeakerIdentifier? = null

        fun getInstance(context: Context): SpeakerIdentifier {
            return instance ?: synchronized(this) {
                instance ?: SpeakerIdentifier(context.applicationContext as Application).also {
                    instance = it
                }
            }
        }

        fun cleanup() {
            synchronized(this) {
                instance?.release()
                instance = null
            }
        }
    }

    @Synchronized
    fun initModel(): Boolean {
        return try {
            val context = contextRef.get() ?: throw IllegalStateException("Context has been garbage collected")
            val modelDir = context.getExternalFilesDir(null)
                ?: throw IllegalStateException("External storage is not available")

            val modelPath = File(modelDir, VoskRecognizer.VOSK_MODEL_NAME).absolutePath
            val speakerModelPath = File(modelDir, VoskRecognizer.SPEAKER_MODEL_NAME).absolutePath

            if (!File(speakerModelPath).exists()) {
                Log.e(tag, "Speaker model not found at $speakerModelPath")
                return false
            }

            // 既存のリソースを解放
            releaseResources()

            // 専用の音声認識モデルと認識器を初期化
            model = Model(modelPath)
            speakerModel = SpeakerModel(speakerModelPath)
            speakerRecognizer = Recognizer(model!!, SAMPLE_RATE, speakerModel!!)

            Log.i(tag, "Speaker identification model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize speaker model", e)
            false
        }
    }

    @Synchronized
    fun extractEmbedding(audioData: ShortArray): FloatArray? {
        return try {
            speakerRecognizer?.let { rec ->
                synchronized(rec) {
                    // 音声データを正規化
                    val normalizedData = normalizeAudioData(audioData)

                    // 音声データを処理
                    rec.acceptWaveForm(normalizedData, normalizedData.size)
                    val result = rec.finalResult

                    // 結果をパース
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
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract embedding", e)
            null
        }
    }

    private fun normalizeAudioData(audioData: ShortArray): ShortArray {
        var sum = 0.0
        audioData.forEach { sum += it }
        val mean = sum / audioData.size

        var maxAmp = 1.0
        audioData.forEach {
            val amp = kotlin.math.abs(it - mean)
            if (amp > maxAmp) maxAmp = amp
        }

        return ShortArray(audioData.size) { i ->
            ((audioData[i] - mean) * (32767.0 / maxAmp)).toInt().toShort()
        }
    }

    @Synchronized
    fun registerSpeaker(id: String, name: String, embedding: FloatArray) {
        val profile = SpeakerProfile(id, name, embedding)
        speakerProfiles[id] = profile
        Log.i(tag, "Registered new speaker profile: $id ($name)")
    }

    @Synchronized
    fun identifySpeaker(embedding: FloatArray): Pair<String, Float>? {
        if (speakerProfiles.isEmpty()) {
            Log.w(tag, "No speaker profiles registered")
            return null
        }

        var bestMatch: Pair<String, Float>? = null
        speakerProfiles.forEach { (id, profile) ->
            val similarity = cosineSimilarity(embedding, profile.embedding)
            if (similarity > SIMILARITY_THRESHOLD &&
                (bestMatch == null || similarity > bestMatch!!.second)) {
                bestMatch = Pair(id, similarity)
            }
        }

        bestMatch?.let { (id, score) ->
            Log.i(tag, "Identified speaker: $id (score: $score)")
        }

        return bestMatch
    }

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

    @Synchronized
    private fun releaseResources() {
        try {
            speakerRecognizer?.close()
            speakerRecognizer = null
            speakerModel?.close()
            speakerModel = null
            model?.close()
            model = null
        } catch (e: Exception) {
            Log.e(tag, "Error releasing resources", e)
        }
    }

    @Synchronized
    fun release() {
        releaseResources()
        speakerProfiles.clear()
        contextRef.clear()
        Log.i(tag, "Speaker identifier resources released")
    }
}
