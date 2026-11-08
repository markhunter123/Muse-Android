package moe.shizuku.server.muse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// MUSE: 计划 Task 10 —— blob（AdbKey 内部已用 AndroidKeyStore 加密过一层）再经本类
// AES-GCM（Key 别名 muse_adb_key）加密后写入 noBackupFilesDir，双层加密是计划的有意设计。
// Keystore 路径 JVM 测不了，真机验证归 Task 18 清单。
class KeystoreKeyStorage(context: Context) : KeyStorage {

    private val file = File(context.noBackupFilesDir, FILE_NAME)

    private val key: SecretKey by lazy {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        existing ?: run {
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            generator.generateKey()
        }
    }

    override fun save(bytes: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        // 存储格式：iv || ciphertext
        file.writeBytes(cipher.iv + cipher.doFinal(bytes))
    }

    override fun load(): ByteArray? {
        if (!file.exists()) return null
        val data = file.readBytes()
        if (data.size <= IV_SIZE_IN_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_IN_BITS, data, 0, IV_SIZE_IN_BYTES))
        return try {
            cipher.doFinal(data, IV_SIZE_IN_BYTES, data.size - IV_SIZE_IN_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    override fun clear() {
        file.delete()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "muse_adb_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FILE_NAME = "muse_adb_key.bin"
        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BITS = 8 * 16
    }
}
