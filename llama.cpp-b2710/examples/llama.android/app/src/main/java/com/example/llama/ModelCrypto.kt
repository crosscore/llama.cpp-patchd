
// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/ModelCrypto.kt
package com.example.llama

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ModelCrypto {
    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 128
        private const val BUFFER_SIZE = 8192
        private const val KEY_ALIAS = "ModelEncryptionKey"
    }

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateSecretKey(): SecretKey {
        return if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.getKey(KEY_ALIAS, null) as SecretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .setUserAuthenticationRequired(false)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }

    fun encryptModel(
        inputStream: InputStream,
        outputStream: OutputStream,
        totalSize: Long,
        onProgress: (Float) -> Unit
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val iv = cipher.iv
        outputStream.write(iv)

        inputStream.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var bytesProcessed = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val encryptedBytes = cipher.update(buffer, 0, bytesRead)
                    if (encryptedBytes != null) {
                        output.write(encryptedBytes)
                    }
                    bytesProcessed += bytesRead
                    val progress = bytesProcessed.toFloat() / totalSize
                    onProgress(progress)
                }

                val finalBytes = cipher.doFinal()
                if (finalBytes != null) {
                    output.write(finalBytes)
                }
                onProgress(1f) // 暗号化完了を通知
            }
        }
    }

    fun encryptModelFlow(
        inputStream: InputStream,
        outputStream: OutputStream,
        totalSize: Long
    ): Flow<Float> = flow {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val iv = cipher.iv
        outputStream.write(iv)

        inputStream.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var bytesProcessed = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val encryptedBytes = cipher.update(buffer, 0, bytesRead)
                    if (encryptedBytes != null) {
                        output.write(encryptedBytes)
                    }
                    bytesProcessed += bytesRead
                    val progress = bytesProcessed.toFloat() / totalSize
                    emit(progress) // 進捗をFlowで送信
                }

                val finalBytes = cipher.doFinal()
                if (finalBytes != null) {
                    output.write(finalBytes)
                }
                emit(1f) // 暗号化完了を通知
            }
        }
    }

    fun decryptModel(
        inputStream: InputStream,
        outputStream: OutputStream,
        totalSize: Long,
        onProgress: (Float) -> Unit
    ) {
        val iv = ByteArray(IV_SIZE)
        if (inputStream.read(iv) != IV_SIZE) {
            throw IllegalStateException("Invalid IV size")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(TAG_SIZE, iv))

        inputStream.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var bytesProcessed = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val decryptedBytes = cipher.update(buffer, 0, bytesRead)
                    if (decryptedBytes != null) {
                        output.write(decryptedBytes)
                    }
                    bytesProcessed += bytesRead
                    val progress = bytesProcessed.toFloat() / totalSize
                    onProgress(progress)
                }

                val finalBytes = cipher.doFinal()
                if (finalBytes != null) {
                    output.write(finalBytes)
                }
                onProgress(1f) // 復号化完了を通知
            }
        }
    }
}
