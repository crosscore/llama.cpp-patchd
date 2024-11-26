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

    var maxTokens by mutableIntStateOf(256)
        private set

    var seed by mutableIntStateOf(42)
        private set

    var contextSize by mutableIntStateOf(1024)
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

    // VoskViewModel関連
    private var _voskViewModel: VoskViewModel? = null
    val voskViewModel: VoskViewModel?
        get() = _voskViewModel

    // VoskViewModelの初期化
    fun initializeVosk(factory: VoskViewModel.Factory) {
        if (_voskViewModel == null) {
            _voskViewModel = factory.create(VoskViewModel::class.java)
            // 音声認識結果のコールバックを設定
            _voskViewModel?.onRecognitionResult = { text ->
                updateMessage(text)
            }
        }
    }

    // 音声認識の状態を公開
    val isRecording: Boolean
        get() = _voskViewModel?.isRecording ?: false

    val currentVoiceTranscript: String
        get() = _voskViewModel?.currentTranscript ?: ""

    val voiceRecognitionError: String?
        get() = _voskViewModel?.errorMessage

    // 音声認識関連のメソッド
    fun startVoiceRecording() {
        _voskViewModel?.startRecording()
    }

    fun stopVoiceRecording() {
        _voskViewModel?.stopRecording()
    }

    fun clearVoiceError() {
        _voskViewModel?.clearError()
    }

    override fun onCleared() {
        super.onCleared()
        _voskViewModel = null
        viewModelScope.launch {
            try {
                llm.unload()
            } catch (exc: IllegalStateException) {
                log(exc.message ?: "Unknown error")
            }
        }
    }

    fun updateMaxTokens(newMaxTokens: String) {
        val requestedTokens = newMaxTokens.toIntOrNull() ?: 256
        maxTokens = minOf(requestedTokens, contextSize)
    }

    fun updateSeed(newSeed: String) {
        seed = newSeed.toIntOrNull() ?: 42
    }

    fun updateContextSize(newContextSize: String) {
        val requestedSize = newContextSize.toIntOrNull() ?: 1024
        // 2048を超えないようにする
        contextSize = minOf(requestedSize, 2048)
        maxTokens = minOf(maxTokens, contextSize)
    }

    fun updateNumThreads(newNumThreads: String) {
        numThreads = newNumThreads.toIntOrNull() ?: 4
    }

    // For SystemPrompt
    var systemPrompt by mutableStateOf(
        """あなたは親切なアシスタントです。ユーザーの質問に簡潔に回答します。""".trimMargin()
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

    fun send() {
        val text = message.trim()
        if (text.isEmpty()) return
        message = ""

        // 履歴を含むプロンプトを構築
        val formattedPrompt = buildString {
            // システムプロンプトを追加する場合
            // append("<|system|>")
            // append(systemPrompt)
            // append("<|endofsystem|>\n")

            // 履歴が有効な場合は過去の会話を追加
            if (isHistoryEnabled && messages.isNotEmpty()) {
                messages.forEach { (userMessage, assistantResponse) ->
                    append("<|user|>")
                    append(userMessage)
                    append("<|endofuser|>\n")
                    append("<|assistant|>")
                    append(assistantResponse)
                    append("<|endofassistant|>\n")
                }
            }

            // 新しいメッセージを追加
            append("<|user|>")
            append(text)
            append("<|endofuser|>\n")
            append("<|assistant|>")
        }

        Log.d(tag, "--- Sending prompt with${if (!isHistoryEnabled) "out" else ""} history ---\n$formattedPrompt")
        messages = messages + Pair(text, "")
        val currentIndex = messages.lastIndex

        sendJob?.cancel() // 既存のジョブがあれば確実にキャンセル
        sendJob = viewModelScope.launch {
            val responseBuilder = StringBuilder()
            var isConversationEnded = false // 会話終了フラグ

            try {
                llm.send(formattedPrompt, maxTokens, seed, contextSize, numThreads)
                    .onCompletion { cause ->
                        if (cause == null && !isConversationEnded) {
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
                        if (isConversationEnded) {
                            return@collect
                        }

                        when (token) {
                            "<CONVERSATION_END>" -> {
                                isConversationEnded = true
                                log("Conversation ended with double newline")
                                messages = messages.toMutableList().apply {
                                    this[currentIndex] = this[currentIndex].copy(second = responseBuilder.toString())
                                }
                                sendJob?.cancel() // 明示的にジョブをキャンセル
                            }
                            else -> {
                                responseBuilder.append(token)
                                messages = messages.toMutableList().apply {
                                    this[currentIndex] = this[currentIndex].copy(second = responseBuilder.toString())
                                }
                            }
                        }
                    }
            } catch (e: CancellationException) {
                Log.i(tag, "send() canceled")
                if (!isConversationEnded) {
                    messages = messages.toMutableList().apply {
                        this[currentIndex] = this[currentIndex].copy(second =
                        if (responseBuilder.isNotEmpty()) responseBuilder.toString()
                        else "Operation canceled."
                        )
                    }
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
