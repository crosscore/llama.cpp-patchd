package com.example.llama

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ConversationHistoryStorage private constructor(context: Context) {
    private val tag = "ConversationHistoryStorage"
    private val appContext = context.applicationContext

    private val baseDir: File by lazy {
        appContext.getExternalFilesDir(null)?.also { dir ->
            Log.d(tag, "Base directory initialized: ${dir.absolutePath}")
        } ?: throw IllegalStateException("External storage is not available")
    }

    private val conversationDir: File by lazy {
        File(baseDir, "conversation_history").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Log.d(tag, "Created conversation history directory: ${dir.absolutePath}")
            }
        }
    }

    data class ConversationEntry(
        val speakerId: String,
        val speakerName: String,
        val message: String,
        val timestamp: Long,
        val confidence: Float
    )

    private var currentSessionDir: File? = null
    private var currentSessionFile: File? = null

    companion object {
        @Volatile
        private var instance: ConversationHistoryStorage? = null

        fun getInstance(context: Context): ConversationHistoryStorage {
            return instance ?: synchronized(this) {
                instance ?: ConversationHistoryStorage(context).also { instance = it }
            }
        }
    }

    fun startNewSession() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentSessionDir = File(conversationDir, timestamp).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Log.d(tag, "Created new session directory: ${dir.absolutePath}")
            }
        }
        currentSessionFile = File(currentSessionDir, "conversation.json").also { file ->
            if (!file.exists()) {
                file.createNewFile()
                file.writeText("[]")
                Log.d(tag, "Created new conversation file: ${file.absolutePath}")
            }
        }
    }

    fun addEntry(entry: ConversationEntry) {
        if (currentSessionFile == null) {
            startNewSession()
        }

        try {
            val file = currentSessionFile ?: throw IllegalStateException("No active session")
            val currentContent = if (file.exists() && file.length() > 0) {
                JSONArray(file.readText())
            } else {
                JSONArray()
            }

            val entryJson = JSONObject().apply {
                put("speakerId", entry.speakerId)
                put("speakerName", entry.speakerName)
                put("message", entry.message)
                put("timestamp", entry.timestamp)
                put("confidence", entry.confidence)
            }

            currentContent.put(entryJson)
            file.writeText(currentContent.toString(2))
            Log.d(tag, "Added new conversation entry: $entry")
        } catch (e: Exception) {
            Log.e(tag, "Failed to add conversation entry", e)
            throw e
        }
    }

    fun getRecentEntries(limit: Int = 5): List<ConversationEntry> {
        return try {
            Log.d(tag, "Current session path: ${getCurrentSessionPath()}")

            currentSessionFile?.let { file ->
                if (!file.exists()) {
                    Log.d(tag, "Session file does not exist")
                    return emptyList()
                }

                if (file.length() == 0L) {
                    Log.d(tag, "Session file is empty")
                    return emptyList()
                }

                val fileContent = file.readText()
                Log.d(tag, "File content: $fileContent")

                val jsonArray = JSONArray(fileContent)
                val entries = mutableListOf<ConversationEntry>()

                for (i in (jsonArray.length() - 1) downTo maxOf(0, jsonArray.length() - limit)) {
                    val json = jsonArray.getJSONObject(i)
                    entries.add(
                        ConversationEntry(
                            speakerId = json.getString("speakerId"),
                            speakerName = json.getString("speakerName"),
                            message = json.getString("message"),
                            timestamp = json.getLong("timestamp"),
                            confidence = json.getDouble("confidence").toFloat()
                        )
                    )
                }

                Log.d(tag, "Loaded ${entries.size} entries")
                entries.reversed()
            } ?: run {
                Log.d(tag, "Current session file is null")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get recent entries", e)
            emptyList()
        }
    }

    private fun getCurrentSessionPath(): String? {
        return currentSessionFile?.absolutePath
    }
}
