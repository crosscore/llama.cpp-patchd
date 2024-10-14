// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/MainViewModel.kt
package com.example.llama

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainViewModel(private val llm: Llm = Llm.instance()) : ViewModel() {

    private val tag: String? = this::class.simpleName

    // ユーザーとLLMのメッセージのペアを保持
    var messages by mutableStateOf(listOf<Pair<String, String>>())
        private set

    var message by mutableStateOf("")
        private set

    var showMemoryInfo by mutableStateOf(false)
        private set

    var showModelPath by mutableStateOf(false)
        private set

    var currentModelPath: String? by mutableStateOf(null)
        private set

    var maxTokens by mutableIntStateOf(32)
        private set

    var seed by mutableIntStateOf(42)
        private set

    var contextSize by mutableIntStateOf(512)
        private set

    var numThreads by mutableIntStateOf(4)
        private set

    // モデルのロード状態を公開
    var isLoading by mutableStateOf(false)
        private set

    var loadingModelName by mutableStateOf<String?>(null)
        private set

    val encryptionProgress = mutableStateMapOf<String, Float>()
    val decryptionProgress = mutableStateMapOf<String, Float>()

    private var sendJob: Job? = null

    // モデル操作完了時のコールバック
    var onModelOperationCompleted: (() -> Unit)? = null

    override fun onCleared() {
        super.onCleared()

        viewModelScope.launch {
            try {
                llm.unload()
            } catch (exc: IllegalStateException) {
                log(exc.message ?: "Unknown error")
            }
        }
    }

    fun updateMaxTokens(newMaxTokens: String) {
        maxTokens = newMaxTokens.toIntOrNull() ?: 32
    }

    fun updateSeed(newSeed: String) {
        seed = newSeed.toIntOrNull() ?: 42
    }

    fun updateContextSize(newContextSize: String) {
        contextSize = newContextSize.toIntOrNull() ?: 512
    }

    fun updateNumThreads(newNumThreads: String) {
        numThreads = newNumThreads.toIntOrNull() ?: 4
    }

    fun send() {
        val text = message.trim()
        if (text.isEmpty()) return
        message = ""

        messages = messages + Pair(text, "")
        val currentIndex = messages.lastIndex

        sendJob = viewModelScope.launch {
            val responseBuilder = StringBuilder()
            try {
                llm.send(text, maxTokens, seed, contextSize, numThreads)
                    .onCompletion { cause ->
                        if (cause == null) {
                            responseBuilder.append("[Output Completed]")
                            messages = messages.toMutableList().apply {
                                this[currentIndex] = this[currentIndex].copy(second = responseBuilder.toString())
                            }
                        }
                    }
                    .catch { e ->
                        when (e) {
                            is Llm.MaxTokensReachedException -> {
                                responseBuilder.append("[Max Tokens Limit Reached]")
                                messages = messages.toMutableList().apply {
                                    this[currentIndex] = this[currentIndex].copy(second = responseBuilder.toString())
                                }
                            }
                            else -> {
                                Log.e(tag, "send() failed", e)
                                messages = messages.toMutableList().apply {
                                    this[currentIndex] = this[currentIndex].copy(second = "Error: ${e.message ?: "Unknown error"}")
                                }
                            }
                        }
                    }
                    .collect { token ->
                        responseBuilder.append(token)
                        messages = messages.toMutableList().apply {
                            this[currentIndex] = this[currentIndex].copy(second = responseBuilder.toString())
                        }
                    }
            } catch (e: CancellationException) {
                Log.i(tag, "send() canceled")
                messages = messages.toMutableList().apply {
                    this[currentIndex] = this[currentIndex].copy(second = "Operation canceled.")
                }
            } catch (e: Exception) {
                Log.e(tag, "send() failed", e)
                messages = messages.toMutableList().apply {
                    this[currentIndex] = this[currentIndex].copy(second = "Error: ${e.message ?: "Unknown error"}")
                }
            }
        }
    }

    fun encryptModel(model: Downloadable) {
        viewModelScope.launch {
            try {
                val inputFile: File = model.file

                // 既存の暗号化ファイルが存在する場合、ユニークな名前を生成
                val directory = inputFile.parentFile ?: throw IllegalStateException("Parent directory is null")
                val uniqueEncryptedFile = generateUniqueEncryptedFile(directory, inputFile.name)

                val totalSize = inputFile.length()
                encryptionProgress[model.name] = 0f
                var lastLoggedPercent = 0

                ModelCrypto().encryptModelFlow(
                    inputStream = FileInputStream(inputFile),
                    outputStream = FileOutputStream(uniqueEncryptedFile),
                    totalSize = totalSize
                )
                    .flowOn(Dispatchers.IO)
                    .collect { progress ->
                        encryptionProgress[model.name] = progress

                        val currentPercent = (progress * 100).toInt()
                        if (currentPercent > lastLoggedPercent) {
                            lastLoggedPercent = currentPercent
                            Log.d("EncryptionProgress", "Progress for ${model.name}: ${currentPercent}%")
                        }
                    }
                encryptionProgress.remove(model.name)
                log("Model encrypted: ${uniqueEncryptedFile.absolutePath}")

                // モデルリストを更新するためにコールバックを呼び出す
                onModelOperationCompleted?.invoke()
            } catch (e: Exception) {
                encryptionProgress.remove(model.name)
                log("Encryption failed: ${e.message}")
                Log.e("EncryptionError", "Error during encryption", e)
            }
        }
    }

    // ユニークな暗号化ファイル名を生成するヘルパー関数
    private fun generateUniqueEncryptedFile(directory: File, originalName: String): File {
        // 既に .enc 拡張子が付いている場合はベースネームから .enc を削除
        val baseName = if (originalName.endsWith(".enc")) {
            originalName.removeSuffix(".enc")
        } else {
            originalName
        }
        var index = 1
        var newFile = File(directory, "$baseName.enc")
        while (newFile.exists()) {
            newFile = File(directory, "${baseName}_$index.enc")
            index++
        }
        return newFile
    }

    fun decryptModel(model: Downloadable) {
        viewModelScope.launch {
            try {
                val inputFile: File = model.file
                val nameWithoutEncExtension = inputFile.name.removeSuffix(".enc")

                // ファイル名と拡張子を分離
                val file = File(nameWithoutEncExtension)
                val baseName = file.nameWithoutExtension
                val extension = if (file.extension.isNotEmpty()) ".${file.extension}" else ""

                // 既存の復号化ファイルが存在する場合、ユニークな名前を生成
                val directory = inputFile.parentFile ?: throw IllegalStateException("Parent directory is null")
                val decryptedFile = generateUniqueDecryptedFile(directory, baseName, extension)
                val totalSize = inputFile.length() - 16 // IVサイズを除く

                decryptionProgress[model.name] = 0f
                var lastLoggedPercent = 0

                ModelCrypto().decryptModelFlow(
                    inputStream = FileInputStream(inputFile),
                    outputStream = FileOutputStream(decryptedFile),
                    totalSize = totalSize
                )
                    .flowOn(Dispatchers.IO)
                    .collect { progress ->
                        decryptionProgress[model.name] = progress

                        val currentPercent = (progress * 100).toInt()
                        if (currentPercent > lastLoggedPercent) {
                            lastLoggedPercent = currentPercent
                            Log.d("DecryptionProgress", "Progress for ${model.name}: ${currentPercent}%")
                        }
                    }
                decryptionProgress.remove(model.name)
                log("Model decrypted: ${decryptedFile.absolutePath}")

                // モデルリストを更新するためにコールバックを呼び出す
                onModelOperationCompleted?.invoke()
            } catch (e: Exception) {
                decryptionProgress.remove(model.name)
                log("Decryption failed: ${e.message}")
                Log.e("DecryptionError", "Error during decryption", e)
            }
        }
    }

    private fun generateUniqueDecryptedFile(directory: File, baseName: String, extension: String): File {
        var index = 1
        var newFile = File(directory, "$baseName$extension")
        while (newFile.exists()) {
            newFile = File(directory, "${baseName}_$index$extension")
            index++
        }
        return newFile
    }

    fun load(pathToModel: String) {
        if (isLoading) {
            log("Model is already loading. Please wait.")
            return
        }

        isLoading = true
        loadingModelName = File(pathToModel).name

        viewModelScope.launch {
            try {
                sendJob?.cancel()
                llm.load(pathToModel, seed, contextSize, numThreads)
                currentModelPath = pathToModel
                log("Loaded $pathToModel")
            } catch (exc: IllegalStateException) {
                Log.e(tag, "load() failed", exc)
                log(exc.message ?: "Unknown error")
            } finally {
                isLoading = false
                loadingModelName = null
            }
        }
    }

    fun updateMessage(newMessage: String) {
        message = newMessage
    }

    fun clear() {
        messages = listOf()
    }

    fun log(message: String) {
        messages = messages + Pair("[System]", message)
    }

    fun toggleMemoryInfo() {
        showMemoryInfo = !showMemoryInfo
    }

    fun toggleModelPath() {
        showModelPath = !showModelPath
    }

    fun getAllMessages(): String {
        return messages.joinToString("\n") { (user, llm) ->
            "User: $user\nLLM: $llm"
        }
    }
}


