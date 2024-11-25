package com.example.llama

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    var isRecording by mutableStateOf(false)
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

    var registeredSpeakers by mutableStateOf(emptyList<String>())
        private set

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

    private fun initializeModel() {
        viewModelScope.launch {
            try {
                if (!isModelInitialized) {
                    val modelName = VoskRecognizer.DEFAULT_MODEL_NAME
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

    private var currentRecordingMode = RecordingMode.Recognition

    // 話者データのストレージ
    private val speakerStorage = SpeakerStorage.getInstance(application)

    // 録音開始（モード指定）
    fun startRecording(mode: RecordingMode = RecordingMode.Recognition) {
        if (!audioRecorder.hasPermission()) {
            errorMessage = "Recording permission not granted"
            return
        }

        if (!isModelInitialized) {
            errorMessage = "Model not initialized"
            return
        }

        viewModelScope.launch {
            try {
                isRecording = true
                currentRecordingMode = mode
                currentTranscript = ""
                errorMessage = null
                temporaryRecording.clear()

                if (mode == RecordingMode.Recognition) {
                    voskRecognizer.startListening()
                }

                audioRecorder.startRecording()
                    .onEach { audioData ->
                        when (currentRecordingMode) {
                            RecordingMode.Recognition -> {
                                voskRecognizer.addAudioData(audioData)
                            }
                            RecordingMode.Registration -> {
                                temporaryRecording.addAll(audioData.toList())
                            }
                        }
                    }
                    .catch { e ->
                        Log.e(tag, "Error in audio recording", e)
                        errorMessage = "Recording error: ${e.message}"
                        stopRecording()
                    }
                    .launchIn(this)

            } catch (e: Exception) {
                Log.e(tag, "Error starting recording", e)
                errorMessage = "Error: ${e.message}"
                stopRecording()
            }
        }
    }

    fun stopRecording() {
        if (isRecording) {
            isRecording = false
            audioRecorder.stopRecording()

            if (currentRecordingMode == RecordingMode.Recognition) {
                voskRecognizer.stopListening()
            }
        }
    }

    // 録音データの取得
    private fun getRecordedAudioData(): ShortArray {
        return temporaryRecording.toShortArray()
    }

    // 新しい話者の登録処理を改善
    fun registerSpeaker(speakerId: String, speakerName: String): Boolean {
        return try {
            registrationState = RegistrationState.Processing

            val audioData = getRecordedAudioData()
            if (audioData.isEmpty()) {
                registrationState = RegistrationState.Error("No audio data available for registration")
                return false
            }

            // VoskRecognizer を使って話者を登録
            val success = voskRecognizer.registerSpeaker(speakerId, speakerName, audioData)

            if (success) {
                registrationState = RegistrationState.Success(speakerId)
                refreshRegisteredSpeakers()
                true
            } else {
                registrationState = RegistrationState.Error("Failed to register speaker")
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error registering speaker", e)
            registrationState = RegistrationState.Error(e.message ?: "Unknown error")
            false
        }
    }

    // 録音状態の管理を改善
    fun startRegistrationRecording() {
        registrationState = RegistrationState.Recording
        startRecording(RecordingMode.Registration)
    }

    fun stopRegistrationRecording() {
        stopRecording()
        registrationState = RegistrationState.Idle
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

    override fun onCleared() {
        super.onCleared()
        stopRecording()
        voskRecognizer.release()
        speakerIdentifier.release()
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
