// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/ModelCrypto.kt
package com.example.llama

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import java.io.FilterOutputStream
import java.io.FilterInputStream

class ModelCrypto {
    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS7Padding"
        private const val KEY_SIZE = 256
        private const val IV_SIZE = 16 // AES/CBC の場合、IV サイズは 16 バイト
        private const val BUFFER_SIZE = 8192
        private const val KEY_ALIAS = "ModelEncryptionKey"
    }

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateSecretKey(): SecretKey {
        val existingKey = if (keyStore.containsAlias(KEY_ALIAS)) {
            try {
                keyStore.getKey(KEY_ALIAS, null) as SecretKey
            } catch (e: Exception) {
                // 鍵の取得に失敗した場合、鍵を削除して再生成
                keyStore.deleteEntry(KEY_ALIAS)
                null
            }
        } else {
            null
        }

        if (existingKey != null) {
            // 既存の鍵が指定した暗号化モードで使用可能か確認
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, existingKey)
                existingKey
            } catch (e: Exception) {
                // 鍵が互換性がない場合、削除して再生成
                keyStore.deleteEntry(KEY_ALIAS)
                generateSecretKey()
            }
        } else {
            return generateSecretKey()
        }
    }

    private fun generateSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setKeySize(KEY_SIZE)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    // NonClosingOutputStream クラスを追加
    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun close() {
            // 基になるストリームを閉じない
            flush()
        }
    }

    // NonClosingInputStream クラスを追加
    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() {
            // 基になるストリームを閉じない
        }
    }

    fun encryptModelFlow(
        inputStream: InputStream,
        outputStream: OutputStream,
        totalSize: Long
    ): Flow<Float> = flow {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getOrCreateSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        outputStream.write(iv) // IV を出力ストリームに書き込む
        outputStream.flush() // IV を確実に書き込む

        val cipherOutputStream = CipherOutputStream(NonClosingOutputStream(outputStream), cipher)
        cipherOutputStream.use { cos ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var bytesProcessed = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                cos.write(buffer, 0, bytesRead)
                bytesProcessed += bytesRead
                val progress = bytesProcessed.toFloat() / totalSize
                emit(progress)
            }
            cos.flush()
        }

        outputStream.flush()
        emit(1f)
    }.flowOn(Dispatchers.IO)

    fun decryptModelFlow(
        inputStream: InputStream,
        outputStream: OutputStream,
        totalSize: Long
    ): Flow<Float> = flow {
        val iv = ByteArray(IV_SIZE)
        val bytesRead = inputStream.read(iv)
        if (bytesRead != IV_SIZE) {
            throw IllegalStateException("Invalid IV size, bytesRead: $bytesRead")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getOrCreateSecretKey()
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        val cipherInputStream = CipherInputStream(NonClosingInputStream(inputStream), cipher)
        cipherInputStream.use { cis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesReadInner: Int
            var bytesProcessed = 0L

            while (cis.read(buffer).also { bytesReadInner = it } != -1) {
                outputStream.write(buffer, 0, bytesReadInner)
                bytesProcessed += bytesReadInner
                val progress = bytesProcessed.toFloat() / totalSize
                emit(progress)
            }
        }

        outputStream.flush()
        emit(1f)
    }.flowOn(Dispatchers.IO)
}
