package com.example.llama

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val tag = "AudioRecorder"

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2

        @Volatile
        private var instance: AudioRecorder? = null

        fun getInstance(context: Context): AudioRecorder {
            return instance ?: synchronized(this) {
                instance ?: AudioRecorder(context).also { instance = it }
            }
        }
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val bufferSize: Int = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    /**
     * 録音の権限チェック
     */
    fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    @Throws(SecurityException::class, IllegalStateException::class)
    private fun initializeAudioRecord() {
        if (!hasPermission()) {
            throw SecurityException("Recording permission not granted")
        }

        try {
            // 既存のAudioRecordインスタンスがある場合は解放
            releaseAudioRecord()

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).apply {
                if (state != AudioRecord.STATE_INITIALIZED) {
                    release()
                    throw IllegalStateException("AudioRecord initialization failed")
                }
            }

            Log.d(tag, "AudioRecord initialized successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize AudioRecord", e)
            throw e
        }
    }

    fun startRecording(): Flow<ShortArray> = flow {
        try {
            synchronized(this) {
                if (isRecording.get()) {
                    Log.w(tag, "Recording is already in progress")
                    return@flow
                }
                initializeAudioRecord()
                audioRecord?.startRecording()
                isRecording.set(true)
                Log.i(tag, "Audio recording started")
            }

            val buffer = ShortArray(bufferSize / 2)
            while (isRecording.get()) {
                val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                when {
                    readResult > 0 -> {
                        emit(buffer.copyOf(readResult))
                    }
                    readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(tag, "Error reading audio data: Invalid operation")
                        throw IOException("Error reading audio data: Invalid operation")
                    }
                    readResult == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(tag, "Error reading audio data: Bad value")
                        throw IOException("Error reading audio data: Bad value")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error during recording", e)
            throw e
        } finally {
            synchronized(this) {
                releaseAudioRecord()
            }
        }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    fun stopRecording() {
        if (isRecording.get()) {
            isRecording.set(false)
            releaseAudioRecord()
            Log.i(tag, "Audio recording stopped")
        }
    }

    @Synchronized
    private fun releaseAudioRecord() {
        try {
            audioRecord?.let { record ->
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.stop()
                }
                record.release()
                audioRecord = null
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing AudioRecord", e)
        }
    }
}
