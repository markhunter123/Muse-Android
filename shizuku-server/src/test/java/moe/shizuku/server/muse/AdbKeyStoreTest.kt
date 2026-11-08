package moe.shizuku.server.muse

import moe.shizuku.manager.adb.AdbKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.Key
import javax.crypto.KeyGenerator

// MUSE: JVM 单测没有 AndroidKeyStore，注入软件 AES key 走 AdbKey 的测试参数。
private class InMemoryKeyStorage : KeyStorage {
    private var blob: ByteArray? = null

    override fun save(bytes: ByteArray) {
        blob = bytes
    }

    override fun load(): ByteArray? = blob

    override fun clear() {
        blob = null
    }
}

private fun softwareAesKey(): Key =
    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

class AdbKeyStoreTest {

    @Test
    fun `key roundtrip keeps signing identity`() {
        val storage = InMemoryKeyStorage()
        val aesKey = softwareAesKey()
        val k1 = MuseAdbKeys.createKey(storage, aesKey)
        val k2 = MuseAdbKeys.loadKey(storage, aesKey)!!
        val payload = ByteArray(20) { it.toByte() }
        assertArrayEquals(k1.sign(payload), k2.sign(payload))
    }

    @Test
    fun `load returns null after clear`() {
        val storage = InMemoryKeyStorage()
        MuseAdbKeys.createKey(storage, softwareAesKey())
        storage.clear()
        assertNull(MuseAdbKeys.loadKey(storage, softwareAesKey()))
    }
}
