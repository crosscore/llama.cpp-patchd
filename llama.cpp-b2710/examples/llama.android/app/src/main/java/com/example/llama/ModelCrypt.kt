// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/ModelCrypt.kt
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }

    fun encryptModel(inputStream: InputStream, outputStream: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val iv = cipher.iv
        outputStream.write(iv)

        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val encryptedBytes = cipher.update(buffer, 0, bytesRead)
            outputStream.write(encryptedBytes)
        }

        val finalBytes = cipher.doFinal()
        outputStream.write(finalBytes)

        inputStream.close()
        outputStream.close()
    }

    fun decryptModel(inputStream: InputStream, outputStream: OutputStream) {
        val iv = ByteArray(IV_SIZE)
        inputStream.read(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(TAG_SIZE, iv))

        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val decryptedBytes = cipher.update(buffer, 0, bytesRead)
            outputStream.write(decryptedBytes)
        }

        val finalBytes = cipher.doFinal()
        outputStream.write(finalBytes)

        inputStream.close()
        outputStream.close()
    }
}
