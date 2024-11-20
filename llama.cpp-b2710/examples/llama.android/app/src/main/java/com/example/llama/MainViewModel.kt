// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/MainViewModel.kt
package com.example.llama

import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(
    private val llm: Llm = Llm.instance()
) : ViewModel() {

    private val tag: String? = this::class.simpleName

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

    var maxTokens by mutableIntStateOf(1024)
        private set

    var seed by mutableIntStateOf(42)
        private set

    var contextSize by mutableIntStateOf(2048)
        private set

    var numThreads by mutableIntStateOf(4)
        private set

    // 会話履歴の有効/無効状態
    var isHistoryEnabled by mutableStateOf(true)
        private set

    // Model loading state
    var isLoading by mutableStateOf(false)
        private set

    var loadingModelName by mutableStateOf<String?>(null)
        private set

    var loadingProgress by mutableStateOf<Float?>(null)
        private set

    private var sendJob: Job? = null

    // Callback for when model operations complete
    var onModelOperationCompleted: (() -> Unit)? = null

    override fun onCleared() {
        super.onCleared()
        voskViewModel = null
        viewModelScope.launch {
            try {
                llm.unload()
            } catch (exc: IllegalStateException) {
                log(exc.message ?: "Unknown error")
            }
        }
    }

    fun updateMaxTokens(newMaxTokens: String) {
        val requestedTokens = newMaxTokens.toIntOrNull() ?: 1024
        maxTokens = minOf(requestedTokens, contextSize)
    }

    fun updateSeed(newSeed: String) {
        seed = newSeed.toIntOrNull() ?: 42
    }

    fun updateContextSize(newContextSize: String) {
        val requestedSize = newContextSize.toIntOrNull() ?: 2048
        // 2048を超えないようにする
        contextSize = minOf(requestedSize, 2048)
        // MaxTokensも調整する必要がある場合
        maxTokens = minOf(maxTokens, contextSize)
    }

    fun updateNumThreads(newNumThreads: String) {
        numThreads = newNumThreads.toIntOrNull() ?: 4
    }

    // For SystemPrompt
    var systemPrompt by mutableStateOf(
        """あなたはUserからの質問や要望に対して日本語で簡潔に回答するAssistantです。""".trimMargin()
    )
        private set

    var showSystemPromptDialog by mutableStateOf(false)
        private set

    fun updateSystemPrompt(newPrompt: String) {
        systemPrompt = newPrompt
    }

    fun toggleSystemPromptDialog() {
        showSystemPromptDialog = !showSystemPromptDialog
    }

    // 会話履歴のトグル関数
    fun toggleHistory() {
        isHistoryEnabled = !isHistoryEnabled
        if (!isHistoryEnabled) {
            // 履歴を無効にした時は会話をクリア
            clear()
        }
        log("Chat history ${if (isHistoryEnabled) "enabled" else "disabled"}")
    }

    // Vosk関連の状態変数を追加
    var isRecording by mutableStateOf(false)
        private set

    var currentVoiceTranscript by mutableStateOf("")
        private set

    var voiceRecognitionError by mutableStateOf<String?>(null)
        private set

    // 音声認識の結果を処理するコールバック
    private val onVoiceRecognitionResult: (String) -> Unit = { result ->
        message = result
        currentVoiceTranscript = ""  // 認識結果をメッセージ欄に移動したらクリア
    }

    // VoskViewModelへの参照（遅延初期化）
    private var voskViewModel: VoskViewModel? = null

    // VoskViewModelの初期化メソッド
    fun initializeVosk(factory: VoskViewModel.Factory) {
        if (voskViewModel == null) {
            voskViewModel = factory.create(VoskViewModel::class.java)
        }
    }

    // 音声認識の開始
    fun startVoiceRecording() {
        voskViewModel?.let { vosk ->
            if (!isRecording) {
                isRecording = true
                vosk.startRecording()
                // 現在の認識結果を監視
                viewModelScope.launch {
                    snapshotFlow { vosk.currentTranscript }
                        .collect { transcript ->
                            currentVoiceTranscript = transcript
                        }
                }
                // エラーを監視
                viewModelScope.launch {
                    snapshotFlow { vosk.errorMessage }
                        .collect { error ->
                            voiceRecognitionError = error
                        }
                }
            }
        }
    }

    // 音声認識の停止
    fun stopVoiceRecording() {
        voskViewModel?.let { vosk ->
            if (isRecording) {
                vosk.stopRecording()
                isRecording = false
                // 最終的な認識結果がある場合はメッセージ欄に設定
                if (vosk.currentTranscript.isNotBlank()) {
                    message = vosk.currentTranscript
                }
            }
        }
    }

    // エラーのクリア
    fun clearVoiceError() {
        voiceRecognitionError = null
        voskViewModel?.clearError()
    }

    fun send() {
        val text = message.trim()
        if (text.isEmpty()) return
        message = ""

        val formattedPrompt = if (isHistoryEnabled) {
            buildString {
                append("System:")
                appendLine(systemPrompt)
                messages.forEach { (user, assistant) ->
                    append("User:")
                    appendLine(user)
                    append("Assistant:")
                    appendLine(assistant)
                }
                append("User:")
                appendLine(text)
                append("Assistant:")
            }
        } else {
            buildString {
                append("System:")
                appendLine(systemPrompt)
                append("User:")
                appendLine(text)
                append("Assistant:")
            }
        }

        Log.d(tag, "--- Sending prompt ---\n$formattedPrompt")
        messages = messages + Pair(text, "")
        val currentIndex = messages.lastIndex

        sendJob = viewModelScope.launch {
            val responseBuilder = StringBuilder()
            try {
                llm.send(formattedPrompt, maxTokens, seed, contextSize, numThreads)
                    .onCompletion { cause ->
                        if (cause == null) {
                            messages = messages.toMutableList().apply {
                                this[currentIndex] = this[currentIndex].copy(second = responseBuilder.toString())
                            }
                        }
                    }
                    .catch { e ->
                        when (e) {
                            is Llm.MaxTokensReachedException -> {
                                responseBuilder.append(" [Max Tokens Limit Reached]")
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
        loadingModelName = pathToModel.split("/").last()
        loadingProgress = 0f

        viewModelScope.launch {
            try {
                sendJob?.cancel()
                llm.load(pathToModel, seed, contextSize, numThreads)
                    .collect { progress ->
                        loadingProgress = progress
                    }
                currentModelPath = pathToModel
                Log.i(tag, "Loaded $pathToModel") // Only Logcat
            } catch (exc: IllegalStateException) {
                Log.e(tag, "load() failed", exc)
                log(exc.message ?: "Unknown error")
            } finally {
                isLoading = false
                loadingModelName = null
                loadingProgress = null
            }
        }
    }

    fun updateMessage(newMessage: String) {
        message = newMessage
    }

    fun clear() {
        messages = listOf()
        if (!isHistoryEnabled) {
            // 履歴が無効の場合はKVキャッシュもクリア
            viewModelScope.launch {
                try {
                    llm.clearKVCache()
                } catch (e: Exception) {
                    Log.e(tag, "Failed to clear KV cache", e)
                }
            }
        }
    }

    fun log(message: String) {
        Log.i(tag, message) // only Logcat
    }

    fun toggleMemoryInfo() {
        showMemoryInfo = !showMemoryInfo
    }

    fun toggleModelPath() {
        showModelPath = !showModelPath
    }

    fun getAllMessages(): String {
        return messages.joinToString("\n") { (user, llm) ->
            "User: $user\nAssistant: $llm"
        }
    }
}
