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
    private var isRecording = false
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

    /**
     * AudioRecordの初期化（権限チェック付き）
     */
    @Throws(SecurityException::class, IllegalStateException::class)
    private fun initializeAudioRecord() {
        if (!hasPermission()) {
            throw SecurityException("Recording permission not granted")
        }

        if (audioRecord != null) {
            return
        }

        try {
            audioRecord = if (ActivityCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } else {
                throw SecurityException("Recording permission not granted")
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                releaseAudioRecord()
                throw IllegalStateException("AudioRecord initialization failed")
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.i(tag, "Audio recording started")
        } catch (e: SecurityException) {
            Log.e(tag, "Permission denied for audio recording", e)
            throw e
        } catch (e: IllegalStateException) {
            Log.e(tag, "Failed to initialize AudioRecord", e)
            throw e
        }
    }

    /**
     * 録音開始とデータのストリーミング
     */
    fun startRecording(): Flow<ShortArray> = flow {
        try {
            initializeAudioRecord()

            val buffer = ShortArray(bufferSize / 2)
            var readResult: Int

            while (isRecording) {
                readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1

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
        } finally {
            releaseAudioRecord()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 録音停止
     */
    fun stopRecording() {
        isRecording = false
        releaseAudioRecord()
        Log.i(tag, "Audio recording stopped")
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.let { record ->
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }
}
