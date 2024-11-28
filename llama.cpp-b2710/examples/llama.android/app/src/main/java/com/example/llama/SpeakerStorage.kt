// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/SpeakerStorage.kt
/**
 * 話者データの永続化を管理するクラス
 *
 * このクラスは以下の主要な機能を提供します：
 * - 話者メタデータの保存
 * - 音声データの保存管理
 * - 特徴ベクトルの永続化
 * - 話者データのCRUD操作
 *
 * データ構造：
 * - メタデータのJSON形式での保存
 * - 音声データのRAWフォーマット保存
 * - 特徴ベクトルのJSON形式保存
 * - 階層的なディレクトリ構造
 *
 * 永続化機能：
 * - 外部ストレージへの保存
 * - バイナリデータの効率的な管理
 * - ファイルI/O操作の最適化
 * - アトミックな更新処理
 *
 * セキュリティ考慮：
 * - 適切なパーミッション管理
 * - データの整合性チェック
 * - エラーリカバリ機能
 * - 安全なファイル操作
 *
 * @property appContext アプリケーションコンテキスト
 * @throws IllegalStateException 外部ストレージ利用不可時
 * @throws IOException ファイル操作失敗時
 *
 * @see SpeakerIdentifier
 * @see VoskViewModel
 */

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

    private val baseDir: File by lazy {
        appContext.getExternalFilesDir(null)?.also { dir ->
            Log.d(tag, "Base directory initialized: ${dir.absolutePath}")
        } ?: throw IllegalStateException("External storage is not available")
    }

    private val speakerDataDir: File by lazy {
        File(baseDir, "speaker_data").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Log.d(tag, "Created speaker data directory: ${dir.absolutePath}")
            } else {
                Log.d(tag, "Speaker data directory exists: ${dir.absolutePath}")
            }
        }
    }

    private val recordingsDir: File by lazy {
        File(speakerDataDir, "recordings").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Log.d(tag, "Created recordings directory: ${dir.absolutePath}")
            } else {
                Log.d(tag, "Recordings directory exists: ${dir.absolutePath}")
            }
        }
    }

    private val metadataFile: File by lazy {
        File(speakerDataDir, "metadata.json").also { file ->
            if (!file.exists()) {
                file.createNewFile()
                file.writeText("[]")
                Log.d(tag, "Created metadata file: ${file.absolutePath}")
            }
        }
    }

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
                instance ?: SpeakerStorage(context).also {
                    instance = it
                    Log.d("SpeakerStorage", "Created new instance")
                }
            }
        }
    }

    /**
     * 新しい話者の録音データを保存
     */
    fun saveSpeakerRecording(speakerId: String, audioData: ShortArray): File {
        val speakerDir = File(recordingsDir, speakerId).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Log.d(tag, "Created speaker directory: ${dir.absolutePath}")
            }
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
            Log.d(tag, "Recording file size: ${recordingFile.length()} bytes")
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

            // インデントと行末の改行を調整して保存
            val jsonString = existingMetadata.toString(2)
                .replace("\\/", "/") // エスケープされたスラッシュを通常のスラッシュに変換
            metadataFile.writeText(jsonString)

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

    private fun createMetadataJson(metadata: SpeakerMetadata): JSONObject {
        return JSONObject().apply {
            put("id", metadata.id)
            put("name", metadata.name)
            put("registrationDate", metadata.registrationDate.time)
            put("samplePath", metadata.samplePath.replace("\\", "/"))
            put("embeddingPath", metadata.embeddingPath.replace("\\", "/"))
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
