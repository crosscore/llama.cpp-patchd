package com.example.llama

import android.app.Application
import android.content.Context
import android.util.Log
import org.json.JSONArray
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

    companion object {
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

    fun identifySpeaker(embedding: FloatArray): Pair<String, Float>? {
        try {
            val context = contextRef.get() ?: return null
            val storage = SpeakerStorage.getInstance(context)

            // 全ての登録済み話者のメタデータを取得
            val allSpeakers = storage.getAllSpeakerMetadata()
            if (allSpeakers.isEmpty()) {
                Log.w(tag, "No registered speakers found in storage")
                return null
            }

            var bestMatch: Triple<String, String, Float>? = null

            // 各話者のembedding.jsonを読み込んで類似度を計算
            allSpeakers.forEach { metadata ->
                try {
                    val embeddingFile = File(metadata.embeddingPath)
                    if (embeddingFile.exists()) {
                        val jsonArray = JSONArray(embeddingFile.readText())
                        val storedEmbedding = FloatArray(jsonArray.length()) { i ->
                            jsonArray.getDouble(i).toFloat()
                        }

                        val similarity = cosineSimilarity(embedding, storedEmbedding)
                        Log.d(tag, "Similarity with ${metadata.name} (${metadata.id}): $similarity")

                        if (bestMatch == null || similarity > bestMatch!!.third) {
                            bestMatch = Triple(metadata.id, metadata.name, similarity)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing embedding for speaker ${metadata.id}", e)
                }
            }

            return bestMatch?.let { (id, name, score) ->
                Log.i(tag, "Selected speaker: $name (ID: $id) with score: $score")
                Pair(id, score)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in speaker identification", e)
            return null
        }
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
        contextRef.clear()
        Log.i(tag, "Speaker identifier resources released")
    }
}
