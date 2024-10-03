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
import kotlinx.coroutines.launch

class MainViewModel(private val llm: Llm = Llm.instance()) : ViewModel() {

    private val tag: String? = this::class.simpleName

    var messages by mutableStateOf(listOf("Initializing..."))
        private set

    var message by mutableStateOf("")
        private set

    var showMemoryInfo by mutableStateOf(false)
        private set

    var showModelPath by mutableStateOf(false)
        private set

    var currentModelPath: String? by mutableStateOf(null)
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
                messages += exc.message!!
            }
        }
    }

    // 値を更新する関数
    fun updateSeed(newSeed: String) {
        seed = newSeed.toIntOrNull() ?: 42
    }

    fun updateContextSize(newContextSize: String) {
        contextSize = newContextSize.toIntOrNull() ?: 512
    }

    fun updateNumThreads(newNumThreads: String) {
        numThreads = newNumThreads.toIntOrNull() ?: 4
    }

    var maxTokens by mutableIntStateOf(16)
        private set

    fun updateMaxTokens(newMaxTokens: String) {
        maxTokens = newMaxTokens.toIntOrNull() ?: 16
    }

    fun send() {
        val text = message.trim()
        if (text.isEmpty()) return
        message = ""

        messages += "User: $text"
        messages += "LLM: "

        sendJob = viewModelScope.launch {
            val responseBuilder = StringBuilder()
            try {
                llm.send(text, maxTokens)
                    .catch { e ->
                        Log.e(tag, "send() failed", e)
                        messages = messages.dropLast(1) + ("LLM: " + e.message!!)
                    }
                    .collect { token ->
                        responseBuilder.append(token)
                        messages = messages.dropLast(1) + ("LLM: $responseBuilder")
                    }
                // 2. LLMの最終トークン出力後に完了を示す文字列を追加
                messages += "[Output Completed]"
            } catch (e: CancellationException) {
                Log.i(tag, "send() canceled")
                messages += "Operation canceled."
            } catch (e: Exception) {
                Log.e(tag, "send() failed", e)
                messages += "Error: ${e.message ?: "Unknown error"}"
            }
        }
    }

    fun load(pathToModel: String) {
        if (isLoading) {
            messages += "Model is already loading. Please wait."
            return
        }

        isLoading = true
        viewModelScope.launch {
            try {
                sendJob?.cancel()
                llm.load(pathToModel, seed, contextSize, numThreads)
                currentModelPath = pathToModel
                messages += "Loaded $pathToModel"
            } catch (exc: IllegalStateException) {
                Log.e(tag, "load() failed", exc)
                messages += exc.message!!
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
        messages += message
    }

    fun toggleMemoryInfo() {
        showMemoryInfo = !showMemoryInfo
    }

    fun toggleModelPath() {
        showModelPath = !showModelPath
    }
}
