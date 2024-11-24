package com.example.llama

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class SpeakerStorage private constructor(context: Context) {
    private val tag = "SpeakerStorage"
    private val appContext = context.applicationContext
    private val baseDir: File
        get() = appContext.getExternalFilesDir(null)
            ?: throw IllegalStateException("External storage is not available")

    private val speakerDataDir: File
        get() = File(baseDir, "speaker_data").also {
            if (!it.exists()) it.mkdirs()
        }

    private val recordingsDir: File
        get() = File(speakerDataDir, "recordings").also {
            if (!it.exists()) it.mkdirs()
        }

    private val metadataFile: File
        get() = File(speakerDataDir, "metadata.json")

    data class SpeakerMetadata(
        val id: String,
        val name: String,
        val registrationDate: Date,
        val samplePath: String,
        val embeddingPath: String
    )

    companion object {
        @Volatile
        private var instance: SpeakerStorage? = null

        fun getInstance(context: Context): SpeakerStorage {
            return instance ?: synchronized(this) {
                instance ?: SpeakerStorage(context).also { instance = it }
            }
        }
    }

    /**
     * 新しい話者の録音データを保存
     */
    fun saveSpeakerRecording(speakerId: String, audioData: ShortArray): File {
        val speakerDir = File(recordingsDir, speakerId).also {
            if (!it.exists()) it.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val recordingFile = File(speakerDir, "sample_$timestamp.raw")

        try {
            FileOutputStream(recordingFile).use { fos ->
                // ShortArrayをバイト配列に変換して保存
                val byteBuffer = ByteArray(audioData.size * 2)
                for (i in audioData.indices) {
                    byteBuffer[i * 2] = (audioData[i].toInt() and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = (audioData[i].toInt() shr 8 and 0xFF).toByte()
                }
                fos.write(byteBuffer)
            }
            Log.i(tag, "Saved recording for speaker $speakerId: ${recordingFile.path}")
            return recordingFile
        } catch (e: IOException) {
            Log.e(tag, "Failed to save recording for speaker $speakerId", e)
            throw e
        }
    }

    /**
     * 話者の特徴ベクトルを保存
     */
    fun saveSpeakerEmbedding(speakerId: String, embedding: FloatArray): File {
        val speakerDir = File(recordingsDir, speakerId)
        val embeddingFile = File(speakerDir, "embedding.json")

        try {
            val jsonArray = JSONArray().apply {
                embedding.forEach { put(it) }
            }
            embeddingFile.writeText(jsonArray.toString())
            Log.i(tag, "Saved embedding for speaker $speakerId: ${embeddingFile.path}")
            return embeddingFile
        } catch (e: IOException) {
            Log.e(tag, "Failed to save embedding for speaker $speakerId", e)
            throw e
        }
    }

    /**
     * 話者のメタデータを保存
     */
    fun saveSpeakerMetadata(metadata: SpeakerMetadata) {
        try {
            val existingMetadata = if (metadataFile.exists()) {
                JSONArray(metadataFile.readText())
            } else {
                JSONArray()
            }

            // 既存のメタデータを更新または新規追加
            var updated = false
            for (i in 0 until existingMetadata.length()) {
                val item = existingMetadata.getJSONObject(i)
                if (item.getString("id") == metadata.id) {
                    existingMetadata.put(i, createMetadataJson(metadata))
                    updated = true
                    break
                }
            }

            if (!updated) {
                existingMetadata.put(createMetadataJson(metadata))
            }

            metadataFile.writeText(existingMetadata.toString(2))
            Log.i(tag, "Saved metadata for speaker ${metadata.id}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save metadata for speaker ${metadata.id}", e)
            throw e
        }
    }

    /**
     * 全ての話者メタデータを取得
     */
    fun getAllSpeakerMetadata(): List<SpeakerMetadata> {
        return try {
            if (!metadataFile.exists()) return emptyList()

            val jsonArray = JSONArray(metadataFile.readText())
            List(jsonArray.length()) { i ->
                val json = jsonArray.getJSONObject(i)
                SpeakerMetadata(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    registrationDate = Date(json.getLong("registrationDate")),
                    samplePath = json.getString("samplePath"),
                    embeddingPath = json.getString("embeddingPath")
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to read speaker metadata", e)
            emptyList()
        }
    }

    /**
     * 特定の話者のメタデータを取得
     */
    fun getSpeakerMetadata(speakerId: String): SpeakerMetadata? {
        return getAllSpeakerMetadata().find { it.id == speakerId }
    }

    /**
     * 話者の録音データを読み込み
     */
    fun loadSpeakerRecording(speakerId: String): ShortArray? {
        val metadata = getSpeakerMetadata(speakerId) ?: return null
        val recordingFile = File(metadata.samplePath)

        return try {
            val bytes = recordingFile.readBytes()
            ShortArray(bytes.size / 2) { i ->
                ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8 or
                    (bytes[i * 2].toInt() and 0xFF)).toShort()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load recording for speaker $speakerId", e)
            null
        }
    }

    /**
     * 話者の特徴ベクトルを読み込み
     */
    fun loadSpeakerEmbedding(speakerId: String): FloatArray? {
        val metadata = getSpeakerMetadata(speakerId) ?: return null
        val embeddingFile = File(metadata.embeddingPath)

        return try {
            val jsonArray = JSONArray(embeddingFile.readText())
            FloatArray(jsonArray.length()) { i ->
                jsonArray.getDouble(i).toFloat()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load embedding for speaker $speakerId", e)
            null
        }
    }

    private fun createMetadataJson(metadata: SpeakerMetadata): JSONObject {
        return JSONObject().apply {
            put("id", metadata.id)
            put("name", metadata.name)
            put("registrationDate", metadata.registrationDate.time)
            put("samplePath", metadata.samplePath)
            put("embeddingPath", metadata.embeddingPath)
        }
    }

    /**
     * 話者データの削除
     */
    fun deleteSpeaker(speakerId: String): Boolean {
        return try {
            // メタデータから削除
            val existingMetadata = if (metadataFile.exists()) {
                JSONArray(metadataFile.readText())
            } else {
                return false
            }

            var indexToRemove = -1
            for (i in 0 until existingMetadata.length()) {
                val item = existingMetadata.getJSONObject(i)
                if (item.getString("id") == speakerId) {
                    indexToRemove = i
                    break
                }
            }

            if (indexToRemove >= 0) {
                // メタデータJSONから削除
                val newMetadata = JSONArray()
                for (i in 0 until existingMetadata.length()) {
                    if (i != indexToRemove) {
                        newMetadata.put(existingMetadata.get(i))
                    }
                }
                metadataFile.writeText(newMetadata.toString(2))

                // 話者のディレクトリを削除
                val speakerDir = File(recordingsDir, speakerId)
                if (speakerDir.exists()) {
                    speakerDir.deleteRecursively()
                }

                Log.i(tag, "Successfully deleted speaker $speakerId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to delete speaker $speakerId", e)
            false
        }
    }
}
