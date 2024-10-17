// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/ModelCrypto.kt
package com.example.llama

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import android.util.Log

class ModelCrypto {
    companion object {
        private const val TRANSFORMATION = "AES/CTR/NoPadding"
        private const val KEY_SIZE = 32 // 256 bits
        private const val IV_SIZE = 16  // 128 bits
        private const val BUFFER_SIZE = 8192
        private const val TAG = "ModelCrypto"
    }

    // 鍵を生成または取得。簡単のためにハードコードされた鍵を使用
    private fun getSecretKey(): SecretKey {
        // 注意: 以下の鍵は例示目的
        val keyBytes = ByteArray(KEY_SIZE) { 0x00 }
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptModelFlow(
        inputStream: InputStream,
        outputStream: OutputStream,
        totalSize: Long
    ): Flow<Float> = flow {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()

            // ランダムなIVを生成
            val iv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

            // IVを出力ストリームに書き込む
            outputStream.write(iv)
            outputStream.flush()

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var bytesProcessed = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val encryptedBytes = cipher.update(buffer, 0, bytesRead)
                if (encryptedBytes != null) {
                    outputStream.write(encryptedBytes)
                }
                bytesProcessed += bytesRead
                val progress = bytesProcessed.toFloat() / totalSize
                emit(progress)
            }

            val finalBytes = cipher.doFinal()
            if (finalBytes != null) {
                outputStream.write(finalBytes)
            }
            outputStream.flush()
            emit(1f)

            Log.d(TAG, "Encryption completed. Total bytes processed: $bytesProcessed")
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed: ${e.message}", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    fun decryptModelFlow(
        inputStream: InputStream,
        outputStream: OutputStream,
        totalSize: Long
    ): Flow<Float> = flow {
        try {
            // IVをinputStreamから読み取る
            val iv = ByteArray(IV_SIZE)
            val ivBytesRead = inputStream.read(iv)
            if (ivBytesRead != IV_SIZE) {
                throw IllegalStateException("Invalid IV size, bytesRead: $ivBytesRead")
            }
            val ivSpec = IvParameterSpec(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var bytesProcessed = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val decryptedBytes = cipher.update(buffer, 0, bytesRead)
                if (decryptedBytes != null) {
                    outputStream.write(decryptedBytes)
                }
                bytesProcessed += bytesRead
                val progress = bytesProcessed.toFloat() / (totalSize - IV_SIZE)
                emit(progress)
            }

            val finalBytes = cipher.doFinal()
            if (finalBytes != null) {
                outputStream.write(finalBytes)
            }
            outputStream.flush()
            emit(1f)

            Log.d(TAG, "Decryption completed. Total bytes processed: $bytesProcessed")
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)
}
