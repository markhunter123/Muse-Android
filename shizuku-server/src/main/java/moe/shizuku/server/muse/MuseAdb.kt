package moe.shizuku.server.muse

import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyStore
import java.security.Key

// MUSE: 计划 Task 9/10 —— app 侧只 import 这一个入口（Task 11 再补 discover/pair/connect）。
// KeyStorage 是密钥 blob 的存取口（blob 本体已由 AdbKey 内部用 AndroidKeyStore 加密）。
interface KeyStorage {

    fun save(bytes: ByteArray)

    fun load(): ByteArray?

    fun clear()
}

object MuseAdb {

    // 显示在配对设备"允许 USB 调试"授权弹窗里的名字（上游写死 "shizuku"，这里跟着品牌走）
    private const val KEY_NAME = "muse"

    fun loadKey(storage: KeyStorage, encryptionKey: Key? = null): AdbKey? {
        if (storage.load() == null) return null
        return AdbKey(storage.asAdbKeyStore(), KEY_NAME, encryptionKey)
    }

    fun createKey(storage: KeyStorage, encryptionKey: Key? = null): AdbKey {
        return AdbKey(storage.asAdbKeyStore(), KEY_NAME, encryptionKey)
    }

    private fun KeyStorage.asAdbKeyStore(): AdbKeyStore = object : AdbKeyStore {
        override fun put(bytes: ByteArray) = save(bytes)
        override fun get(): ByteArray? = load()
    }
}
