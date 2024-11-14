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
import java.nio.ByteBuffer
import java.nio.ShortBuffer

class AudioRecorder private constructor(private val context: Context) {
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
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 録音開始とデータのストリーミング
     * @return ShortArrayのFlow
     */
    fun startRecording(): Flow<ShortArray> = flow {
        if (!hasPermission()) {
            throw SecurityException("Recording permission not granted")
        }

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
        } catch (e: Exception) {
            Log.e(tag, "Error in audio recording", e)
            throw e
        } finally {
            releaseAudioRecord()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * AudioRecordの初期化
     */
    @Throws(IllegalStateException::class)
    private fun initializeAudioRecord() {
        if (audioRecord != null) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            releaseAudioRecord()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        audioRecord?.startRecording()
        isRecording = true
        Log.i(tag, "Audio recording started")
    }

    /**
     * 録音停止
     */
    fun stopRecording() {
        isRecording = false
        releaseAudioRecord()
        Log.i(tag, "Audio recording stopped")
    }

    /**
     * AudioRecordのリソース解放
     */
    private fun releaseAudioRecord() {
        audioRecord?.let { record ->
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    record.stop()
                } catch (e: IllegalStateException) {
                    Log.e(tag, "Error stopping AudioRecord", e)
                }
            }
            record.release()
        }
        audioRecord = null
    }

    /**
     * 録音中かどうかの確認
     */
    fun isRecording(): Boolean {
        return isRecording
    }

    /**
     * ByteArray形式のデータをShortArray形式に変換
     */
    fun convertBytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).asShortBuffer().get(shorts)
        return shorts
    }

    /**
     * ShortArray形式のデータをByteArray形式に変換
     */
    fun convertShortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        val buffer = ByteBuffer.wrap(bytes)
        val shortBuffer = buffer.asShortBuffer()
        shortBuffer.put(shorts)
        return bytes
    }
}
