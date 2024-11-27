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

    init {
        initializeModel()
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

        // 話者識別のコールバック
        voskRecognizer.onSpeakerIdentified = { speakerId, confidence ->
            currentSpeakerId = speakerId
            currentSpeakerConfidence = confidence
            Log.i(tag, "Speaker identified: $speakerId (confidence: $confidence)")
        }
    }

    // 録音データの一時保存用
    private var temporaryRecording = mutableListOf<Short>()

    // 録音モードを companion object の中で定義
    companion object {
        enum class RecordingMode {
            Recognition,
            Registration
        }
    }

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

    override fun onCleared() {
        super.onCleared()
        stopRecording()
        voskRecognizer.release()
        speakerIdentifier.release()
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
}
