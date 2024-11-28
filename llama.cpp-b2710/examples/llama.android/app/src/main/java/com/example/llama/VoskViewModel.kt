// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/VoskViewModel.kt
/**
 * 音声認識機能のビジネスロジックを管理するViewModelクラス
 *
 * このViewModelは以下の主要な機能を提供します：
 * - 音声認識の開始/停止の制御
 * - 話者識別の管理
 * - 認識結果のテキスト管理
 * - 会話履歴の保存と管理
 * - エラー状態の管理
 *
 * このクラスは以下のコンポーネントと連携します：
 * - VoskRecognizer: 音声認識エンジン
 * - AudioRecorder: 音声入力の制御
 * - SpeakerIdentifier: 話者識別
 * - ConversationHistoryStorage: 会話履歴の永続化
 *
 * 主な使用シナリオ：
 * 1. 音声認識による会話のテキスト化
 * 2. 複数話者の識別と管理
 * 3. 会話履歴の記録と表示
 *
 * @property appContext アプリケーションコンテキスト
 * @property onRecognitionResult 音声認識結果を受け取るコールバック
 *
 * @see VoskRecognizer
 * @see AudioRecorder
 * @see SpeakerIdentifier
 * @see ConversationHistoryStorage
 */

package com.example.llama

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class VoskViewModel(
    application: Application,
    var onRecognitionResult: (String) -> Unit
) : ViewModel() {
    private val appContext = application
    private val voskRecognizer = VoskRecognizer.getInstance(appContext)
    private val audioRecorder = AudioRecorder.getInstance(appContext)
    private val speakerIdentifier = SpeakerIdentifier.getInstance(appContext)
    private val tag = "VoskViewModel"

    // 音声認識の状態
    private val _isRecording = MutableStateFlow(false)
    var isRecording by mutableStateOf(false)
        private set

    private var recordingJob: Job? = null

    private var isModelInitialized by mutableStateOf(false)

    // 音声認識の結果
    var currentTranscript by mutableStateOf("")
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // 話者識別の状態と結果
    var currentSpeakerId by mutableStateOf<String?>(null)
        private set

    var currentSpeakerConfidence by mutableStateOf<Float?>(null)
        private set

    private var registeredSpeakers by mutableStateOf(emptyList<String>())

    // 話者登録状態を追加
    var registrationState by mutableStateOf<RegistrationState>(RegistrationState.Idle)
        private set

    sealed class RegistrationState {
        data object Idle : RegistrationState()
        data object Recording : RegistrationState()
        data object Processing : RegistrationState()
        data class Success(val speakerId: String) : RegistrationState()
        data class Error(val message: String) : RegistrationState()
    }

    // 会話履歴の管理
    private val conversationStorage by lazy {
        ConversationHistoryStorage.getInstance(appContext)
    }

    // 最新の会話履歴
    private var recentConversations by mutableStateOf<List<ConversationHistoryStorage.ConversationEntry>>(emptyList())

    var allSessions by mutableStateOf<List<ConversationHistoryStorage.SessionInfo>>(emptyList())
        private set

    fun loadAllSessions() {
        viewModelScope.launch {
            try {
                allSessions = conversationStorage.getAllSessions()
                Log.d(tag, "Loaded ${allSessions.size} sessions")
            } catch (e: Exception) {
                Log.e(tag, "Failed to load sessions", e)
            }
        }
    }

    // 会話履歴の更新を明示的に行うメソッドを追加
    private fun loadConversationHistory() {
        viewModelScope.launch {
            try {
                Log.d(tag, "Loading conversation history...")
                val entries = conversationStorage.getRecentEntries(MAX_CONVERSATION_HISTORY)
                Log.d(tag, "Loaded ${entries.size} entries from storage")
                recentConversations = entries
                Log.d(tag, "Current conversations: ${recentConversations.size}")
            } catch (e: Exception) {
                Log.e(tag, "Failed to load conversation history", e)
            }
        }
    }

    // 会話履歴ダイアログを開く前に呼び出すメソッド
    fun prepareConversationHistory() {
        Log.d(tag, "Preparing conversation history")
        if (recentConversations.isEmpty()) {
            loadConversationHistory()
        }
    }

    // セッション作成フラグ
    private var isSessionCreated = false

    init {
        initializeModel()
        loadConversationHistory()
    }

    // 会話履歴の取得件数を定数として定義
    companion object {
        private const val MAX_CONVERSATION_HISTORY = 20 // 最新20件を保持

        // 録音モードのenum classを追加
        enum class RecordingMode {
            Recognition,  // 音声認識用
            Registration // 話者登録用
        }
    }

    fun getApplication(): Application {
        return appContext
    }

    private fun initializeModel() {
        viewModelScope.launch {
            try {
                if (!isModelInitialized) {
                    val modelName = VoskRecognizer.VOSK_MODEL_NAME
                    if (voskRecognizer.isModelAvailable(modelName)) {
                        isModelInitialized = voskRecognizer.initModel(modelName)
                        if (isModelInitialized) {
                            Log.i(tag, "Speech recognition model initialized successfully")
                            setupRecognitionCallbacks()
                            // 話者識別モデルの初期化
                            if (speakerIdentifier.initModel()) {
                                Log.i(tag, "Speaker identification model initialized successfully")
                            } else {
                                errorMessage = "Failed to initialize speaker model"
                            }
                        } else {
                            errorMessage = "Failed to initialize speech recognition model"
                        }
                    } else {
                        errorMessage = "Speech recognition model not found"
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error initializing models", e)
                errorMessage = "Error: ${e.message}"
            }
        }
    }

    private fun setupRecognitionCallbacks() {
        voskRecognizer.onPartialResult = { hypothesis ->
            try {
                JSONObject(hypothesis).optString("partial").let { partial ->
                    currentTranscript = partial
                }
            } catch (e: Exception) {
                Log.e(tag, "Error parsing partial result", e)
            }
        }

        voskRecognizer.onResult = { result ->
            try {
                val json = JSONObject(result)
                json.optString("text").let { text ->
                    if (text.isNotBlank()) {
                        currentTranscript = text
                        onRecognitionResult(text)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error parsing final result", e)
            }
        }

        voskRecognizer.onError = { exception ->
            Log.e(tag, "Recognition error", exception)
            errorMessage = "Recognition error: ${exception.message}"
            stopRecording()
        }

        voskRecognizer.onSpeakerIdentified = { speakerId, confidence ->
            currentSpeakerId = speakerId
            currentSpeakerConfidence = confidence
            Log.i(tag, "Speaker identified: $speakerId (confidence: $confidence)")
        }
    }

    // 録音データの一時保存用
    private var temporaryRecording = mutableListOf<Short>()

    // 話者データのストレージ
    private val speakerStorage = SpeakerStorage.getInstance(application)

    // 録音開始（モード指定）
    fun startRecording(mode: RecordingMode = RecordingMode.Recognition) {
        if (!audioRecorder.hasPermission()) {
            errorMessage = "Recording permission not granted"
            return
        }

        viewModelScope.launch {
            try {
                if (_isRecording.value) {
                    Log.w(tag, "Recording is already in progress")
                    return@launch
                }

                // Recognition モードでかつセッションが未作成の場合のみ、新しいセッションを作成
                if (mode == RecordingMode.Recognition && !isSessionCreated) {
                    conversationStorage.startNewSession()
                    isSessionCreated = true
                    Log.d(tag, "New conversation session created")
                }

                currentTranscript = ""
                errorMessage = null
                temporaryRecording.clear()

                when (mode) {
                    RecordingMode.Recognition -> {
                        voskRecognizer.startListening()
                    }
                    RecordingMode.Registration -> {
                        registrationState = RegistrationState.Recording
                    }
                }

                _isRecording.value = true
                isRecording = true

                recordingJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        audioRecorder.startRecording()
                            .collect { audioData ->
                                when (mode) {
                                    RecordingMode.Recognition -> {
                                        voskRecognizer.addAudioData(audioData)
                                    }
                                    RecordingMode.Registration -> {
                                        temporaryRecording.addAll(audioData.toList())
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        when (e) {
                            is CancellationException -> {
                                Log.d(tag, "Recording cancelled normally")
                            }
                            else -> {
                                Log.e(tag, "Error in recording", e)
                                errorMessage = "Recording error: ${e.message}"
                                stopRecording()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start recording", e)
                errorMessage = "Error: ${e.message}"
                stopRecording()
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            try {
                recordingJob?.cancelAndJoin()
            } catch (e: Exception) {
                Log.d(tag, "Recording job cancelled", e)
            } finally {
                if (_isRecording.value) {
                    _isRecording.value = false
                    isRecording = false
                    audioRecorder.stopRecording()
                    voskRecognizer.stopListening()

                    // 登録モード時は録音完了状態にする
                    if (registrationState is RegistrationState.Recording) {
                        registrationState = RegistrationState.Idle
                    }
                }
                recordingJob = null
            }
        }
    }

    // ViewModel破棄時にセッションフラグをリセット
    override fun onCleared() {
        super.onCleared()
        stopRecording()
        voskRecognizer.release()
        speakerIdentifier.release()
        isSessionCreated = false
    }

    // 録音データの取得
    private fun getRecordedAudioData(): ShortArray {
        return temporaryRecording.toShortArray()
    }

    /**
     * UIから呼び出される話者登録処理
     * 登録状態の管理とVoskRecognizerを使用した実際の登録を行う
    */
    fun registerSpeaker(speakerId: String, speakerName: String) {
        viewModelScope.launch {
            try {
                registrationState = RegistrationState.Processing
                Log.d(tag, "Starting speaker registration for ID: $speakerId")

                val audioData = getRecordedAudioData()
                if (audioData.isEmpty()) {
                    registrationState = RegistrationState.Error("No audio data available")
                    return@launch
                }

                val success = voskRecognizer.registerSpeaker(speakerId, speakerName, audioData)
                if (success) {
                    registrationState = RegistrationState.Success(speakerId)
                    refreshRegisteredSpeakers()
                } else {
                    registrationState = RegistrationState.Error("Registration failed")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error registering speaker", e)
                registrationState = RegistrationState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * デバッグ用の録音データ保存機能
     */
    fun debugSaveAudioData(speakerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val audioData = getRecordedAudioData()
                if (audioData.isNotEmpty()) {
                    val storage = SpeakerStorage.getInstance(appContext)
                    val file = storage.saveSpeakerRecording(speakerId, audioData)
                    Log.d(tag, "Debug: Saved audio data to: ${file.absolutePath}")
                    Log.d(tag, "Debug: File exists: ${file.exists()}")
                    Log.d(tag, "Debug: File size: ${file.length()} bytes")
                } else {
                    Log.w(tag, "Debug: No audio data available to save")
                }
            } catch (e: Exception) {
                Log.e(tag, "Debug: Failed to save audio data", e)
            }
        }
    }

    // 録音状態の管理を改善
    fun startRegistrationRecording() {
        registrationState = RegistrationState.Recording
        startRecording(RecordingMode.Registration)
    }

    // 登録済み話者リストの更新
    private fun refreshRegisteredSpeakers() {
        viewModelScope.launch {
            registeredSpeakers = speakerStorage.getAllSpeakerMetadata().map { it.id }
        }
    }

    fun clearError() {
        errorMessage = null
    }

    // Factory for creating VoskViewModel with Application Context
    class Factory(
        private val application: Application,
        private val onRecognitionResult: (String) -> Unit
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(VoskViewModel::class.java)) {
                return VoskViewModel(application, onRecognitionResult) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    /**
     * セッションを削除する
     * @param sessionId 削除対象のセッションID
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val isDeleted = conversationStorage.deleteSession(sessionId)
                if (isDeleted) {
                    // 削除成功後、セッション一覧を更新
                    loadAllSessions()
                } else {
                    // 削除失敗時のエラーメッセージを設定
                    errorMessage = "セッションの削除に失敗しました"
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to delete session", e)
                errorMessage = "エラー: ${e.message}"
            }
        }
    }

    /**
     * 現在のセッションかどうかを判定する
     * @param sessionId 判定対象のセッションID
     * @return 現在のセッションの場合はtrue
     */
    fun isCurrentSession(sessionId: String): Boolean {
        return conversationStorage.getCurrentSessionId() == sessionId
    }
}
