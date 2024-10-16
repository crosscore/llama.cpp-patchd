// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/ModelSplitter.kt
package com.example.llama

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.*
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class ModelSplitter(private val context: Context) {
    companion object {
        private const val BUFFER_SIZE = 8192
        private const val KEY_ALIAS = "ModelMergeKey"
        private const val TRANSFORMATION = "AES/CTR/NoPadding"
        private const val IV_SIZE = 16  // 128 bits
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val keyEntry = keyStore.getEntry(KEY_ALIAS, null)
        return if (keyEntry != null && keyEntry is KeyStore.SecretKeyEntry) {
            keyEntry.secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CTR)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }

    fun splitModelFlow(
        inputFile: File,
        outputDir: File,
        partSizeBytes: Long
    ): Flow<Float> = flow {
        try {
            val totalSize = inputFile.length()
            val inputStream = FileInputStream(inputFile)
            val secretKey = getSecretKey()

            var bytesProcessed = 0L
            var partIndex = 1

            while (bytesProcessed < totalSize) {
                val remaining = totalSize - bytesProcessed
                val currentPartSize = minOf(partSizeBytes, remaining)
                val outputFile = File(outputDir, "${inputFile.name}.part$partIndex")

                val iv = ByteArray(IV_SIZE)
                SecureRandom().nextBytes(iv)
                val ivSpec = javax.crypto.spec.IvParameterSpec(iv)

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

                val cipherOutputStream = CipherOutputStream(FileOutputStream(outputFile), cipher)

                cipherOutputStream.write(iv)

                val buffer = ByteArray(BUFFER_SIZE)
                var bytesReadTotal = 0L

                while (bytesReadTotal < currentPartSize) {
                    val bytesToRead = minOf(BUFFER_SIZE.toLong(), currentPartSize - bytesReadTotal).toInt()
                    val bytesRead = inputStream.read(buffer, 0, bytesToRead)
                    if (bytesRead == -1) break
                    cipherOutputStream.write(buffer, 0, bytesRead)
                    bytesReadTotal += bytesRead
                    bytesProcessed += bytesRead
                    val progress = bytesProcessed.toFloat() / totalSize
                    emit(progress)
                }

                cipherOutputStream.flush()
                cipherOutputStream.close()
                partIndex++
            }

            inputStream.close()
            emit(1f)
        } catch (e: Exception) {
            throw e
        }
    }.flowOn(Dispatchers.IO)

    fun mergeModelFlow(
        inputFiles: List<File>,
        outputFile: File
    ): Flow<Float> = flow {
        try {
            val totalSize = inputFiles.sumOf { it.length() }
            var bytesProcessed = 0L

            val outputStream = FileOutputStream(outputFile)
            val secretKey = getSecretKey()

            inputFiles.sortedBy { it.name }.forEach { inputFile ->
                val fileInputStream = FileInputStream(inputFile)

                val iv = ByteArray(IV_SIZE)
                val ivBytesRead = fileInputStream.read(iv)
                if (ivBytesRead != IV_SIZE) {
                    throw IllegalStateException("Invalid IV size in part file: ${inputFile.name}")
                }
                val ivSpec = javax.crypto.spec.IvParameterSpec(iv)

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                val cipherInputStream = CipherInputStream(fileInputStream, cipher)

                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int

                while (cipherInputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesProcessed += bytesRead
                    val progress = bytesProcessed.toFloat() / totalSize
                    emit(progress)
                }

                cipherInputStream.close()
            }

            outputStream.flush()
            outputStream.close()
            emit(1f)
        } catch (e: Exception) {
            throw e
        }
    }.flowOn(Dispatchers.IO)
}
