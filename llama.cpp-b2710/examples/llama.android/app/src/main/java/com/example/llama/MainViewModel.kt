// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/MainViewModel.kt
package com.example.llama

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

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

    private var isLoading by mutableStateOf(false)

    private var sendJob: Job? = null

    override fun onCleared() {
        super.onCleared()

        viewModelScope.launch {
            try {
                llm.unload()
            } catch (exc: IllegalStateException) {
                // エラーメッセージをログに追加
                log(exc.message ?: "Unknown error")
            }
        }
    }

    // 値を更新する関数
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

    fun load(pathToModel: String) {
        if (isLoading) {
            log("Model is already loading. Please wait.")
            return
        }

        isLoading = true
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
        // システムメッセージとしてログを追加
        messages = messages + Pair("[System]", message)
    }

    fun toggleMemoryInfo() {
        showMemoryInfo = !showMemoryInfo
    }

    fun toggleModelPath() {
        showModelPath = !showModelPath
    }

    // 全てのメッセージを文字列として取得
    fun getAllMessages(): String {
        return messages.joinToString("\n") { (user, llm) ->
            "User: $user\nLLM: $llm"
        }
    }
}
