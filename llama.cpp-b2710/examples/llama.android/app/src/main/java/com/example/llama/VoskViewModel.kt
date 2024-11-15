package com.example.llama

import android.content.Context
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
    private val context: Context,
    private val onRecognitionResult: (String) -> Unit
) : ViewModel() {
    private val tag = "VoskViewModel"

    private val voskRecognizer = VoskRecognizer.getInstance(context)
    private val audioRecorder = AudioRecorder.getInstance(context)

    // 音声認識の状態
    private var isRecording by mutableStateOf(false)

    private var isModelInitialized by mutableStateOf(false)

    var currentTranscript by mutableStateOf("")
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

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
                            Log.i(tag, "Model initialized successfully")
                            setupRecognitionCallbacks()
                        } else {
                            errorMessage = "Failed to initialize model"
                        }
                    } else {
                        errorMessage = "Model not found"
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error initializing model", e)
                errorMessage = "Error: ${e.message}"
            }
        }
    }

    private fun setupRecognitionCallbacks() {
        voskRecognizer.onPartialResult = { hypothesis ->
            val any = try {
                val json = JSONObject(hypothesis)
                json.optString("partial")?.let { partial ->
                    currentTranscript = partial
                }
            } catch (e: Exception) {
                Log.e(tag, "Error parsing partial result", e)
            }
        }

        voskRecognizer.onResult = { result ->
            try {
                val json = JSONObject(result)
                json.optString("text")?.let { text ->
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
    }

    fun startRecording() {
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
                currentTranscript = ""
                errorMessage = null

                voskRecognizer.startListening()

                audioRecorder.startRecording()
                    .onEach { audioData ->
                        // 音声データをVoskに送信
                        // 必要に応じてここでデータ変換を行う
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
            voskRecognizer.stopListening()
        }
    }

    fun clearError() {
        errorMessage = null
    }

    override fun onCleared() {
        super.onCleared()
        stopRecording()
        voskRecognizer.release()
    }

    // Factory for creating VoskViewModel with Context
    class Factory(
        private val context: Context,
        private val onRecognitionResult: (String) -> Unit
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(VoskViewModel::class.java)) {
                return VoskViewModel(context, onRecognitionResult) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
