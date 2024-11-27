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

    data class SessionInfo(
        val sessionId: String,
        val timestamp: Long,
        val entries: List<ConversationEntry>
    )

    fun getAllSessions(): List<SessionInfo> {
        try {
            val sessionDirs = conversationDir.listFiles { file -> file.isDirectory }
                ?.sortedByDescending { it.name } ?: return emptyList()

            return sessionDirs.mapNotNull { sessionDir ->
                val conversationFile = File(sessionDir, "conversation.json")
                if (conversationFile.exists() && conversationFile.length() > 0) {
                    try {
                        val fileContent = conversationFile.readText()
                        val jsonArray = JSONArray(fileContent)
                        val entries = List(jsonArray.length()) { i ->
                            val json = jsonArray.getJSONObject(i)
                            ConversationEntry(
                                speakerId = json.getString("speakerId"),
                                speakerName = json.getString("speakerName"),
                                message = json.getString("message"),
                                timestamp = json.getLong("timestamp"),
                                confidence = json.getDouble("confidence").toFloat()
                            )
                        }

                        // セッションIDからタイムスタンプを抽出
                        val timestamp = sessionDir.name.substringBefore("_").toLongOrNull()?.let {
                            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).parse(it.toString())?.time
                        } ?: System.currentTimeMillis()

                        SessionInfo(
                            sessionId = sessionDir.name,
                            timestamp = timestamp,
                            entries = entries
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "Error reading session ${sessionDir.name}", e)
                        null
                    }
                } else null
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get sessions", e)
            return emptyList()
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

    // すべてのセッションから最新の会話履歴を取得
    fun getRecentEntries(limit: Int = 5): List<ConversationEntry> {
        try {
            Log.d(tag, "Reading conversation history from: ${conversationDir.absolutePath}")

            // すべてのセッションディレクトリを取得し、名前で降順ソート（最新のものから）
            val sessionDirs = conversationDir.listFiles { file ->
                file.isDirectory
            }?.sortedByDescending { it.name } ?: return emptyList()

            Log.d(tag, "Found ${sessionDirs.size} session directories")

            // 全セッションの会話履歴を統合
            val allEntries = mutableListOf<ConversationEntry>()

            for (sessionDir in sessionDirs) {
                val conversationFile = File(sessionDir, "conversation.json")
                if (conversationFile.exists() && conversationFile.length() > 0) {
                    try {
                        Log.d(tag, "Reading from session: ${sessionDir.name}")
                        val fileContent = conversationFile.readText()
                        val jsonArray = JSONArray(fileContent)

                        for (i in 0 until jsonArray.length()) {
                            val json = jsonArray.getJSONObject(i)
                            allEntries.add(
                                ConversationEntry(
                                    speakerId = json.getString("speakerId"),
                                    speakerName = json.getString("speakerName"),
                                    message = json.getString("message"),
                                    timestamp = json.getLong("timestamp"),
                                    confidence = json.getDouble("confidence").toFloat()
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error reading session ${sessionDir.name}", e)
                    }
                }
            }

            // タイムスタンプでソートし、指定された数だけ返す
            return allEntries
                .sortedBy { it.timestamp }
                .takeLast(limit)
                .also {
                    Log.d(tag, "Returning ${it.size} entries from all sessions")
                }

        } catch (e: Exception) {
            Log.e(tag, "Failed to get recent entries", e)
            return emptyList()
        }
    }

    /**
     * セッションを削除する
     * @param sessionId 削除対象のセッションID
     * @return 削除が成功したかどうか
     */
    fun deleteSession(sessionId: String): Boolean {
        return try {
            // 現在のセッションは削除できないようにする
            if (currentSessionDir?.name == sessionId) {
                Log.w(tag, "Cannot delete current session")
                return false
            }

            val sessionDir = File(conversationDir, sessionId)
            if (!sessionDir.exists() || !sessionDir.isDirectory) {
                Log.w(tag, "Session directory not found: $sessionId")
                return false
            }

            val success = sessionDir.deleteRecursively()
            if (success) {
                Log.i(tag, "Successfully deleted session: $sessionId")
            } else {
                Log.e(tag, "Failed to delete session: $sessionId")
            }
            success
        } catch (e: Exception) {
            Log.e(tag, "Error deleting session $sessionId", e)
            false
        }
    }

    /**
     * 現在のセッションIDを取得する
     * @return 現在のセッションID。セッションが開始されていない場合はnull
     */
    fun getCurrentSessionId(): String? {
        return currentSessionDir?.name
    }
}
