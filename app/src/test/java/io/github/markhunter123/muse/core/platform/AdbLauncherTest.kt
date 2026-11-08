package io.github.markhunter123.muse.core.platform

import io.github.markhunter123.muse.core.platform.AdbLaunchOutcome.BinderTimeout
import io.github.markhunter123.muse.core.platform.AdbLaunchOutcome.ConnectFailed
import io.github.markhunter123.muse.core.platform.AdbLaunchOutcome.NoKey
import io.github.markhunter123.muse.core.platform.AdbLaunchOutcome.NoPort
import io.github.markhunter123.muse.core.platform.AdbLaunchOutcome.Started
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.server.muse.AdbResult
import moe.shizuku.server.muse.KeyStorage
import moe.shizuku.server.muse.MuseAdb
import moe.shizuku.server.muse.MuseAdbKeys
import moe.shizuku.server.muse.PairResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/** JVM 单测没有 AndroidKeyStore：AdbKey 用软件 AES key 走注入参数（Task 10 的测试缝）。 */
private class InMemoryKeyStorage : KeyStorage {
    private var blob: ByteArray? = null
    override fun save(bytes: ByteArray) { blob = bytes }
    override fun load(): ByteArray? = blob
    override fun clear() { blob = null }
}

private fun testKey(): AdbKey {
    val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return MuseAdbKeys.createKey(InMemoryKeyStorage(), aes)
}

/** 假 MuseAdb：按顺序记录调用，端口和结果可编排。 */
private class FakeMuseAdb(
    private var port: Int? = 4321,
    private var connectResult: AdbResult = AdbResult(true, null),
) : MuseAdb {
    val calls = mutableListOf<String>()
    override suspend fun discoverConnectPort(timeoutMs: Long): Int? { calls += "discoverConnect"; return port }
    override suspend fun discoverPairingPort(timeoutMs: Long): Int? { calls += "discoverPairing"; return port }
    override suspend fun connectAndStart(key: AdbKey, p: Int, starterCmd: String): AdbResult {
        calls += "connect:$p:$starterCmd"
        return connectResult
    }
    override suspend fun pair(key: AdbKey, pairingPort: Int, code: String): PairResult {
        calls += "pair"
        return PairResult(true, null)
    }
}

class AdbLauncherTest {

    @Test
    fun `no key short-circuits before any discovery`() = runTest {
        val adb = FakeMuseAdb()
        val outcome = AdbLauncher(adb).launch(null, "starter", { true })
        assertEquals(NoKey, outcome)
        assertTrue(adb.calls.isEmpty())
    }

    @Test
    fun `no discovered port skips connect`() = runTest {
        val adb = FakeMuseAdb(port = null)
        val outcome = AdbLauncher(adb).launch(testKey(), "starter", { true })
        assertEquals(NoPort, outcome)
        assertEquals(listOf("discoverConnect"), adb.calls)
    }

    @Test
    fun `connect failure carries the log`() = runTest {
        val adb = FakeMuseAdb(connectResult = AdbResult(false, "auth failed"))
        val outcome = AdbLauncher(adb).launch(testKey(), "starter", { true })
        assertEquals(ConnectFailed("auth failed"), outcome)
        assertEquals(listOf("discoverConnect", "connect:4321:starter"), adb.calls)
    }

    @Test
    fun `connect then live binder is Started`() = runTest {
        val adb = FakeMuseAdb()
        val outcome = AdbLauncher(adb).launch(testKey(), "starter", { true })
        assertEquals(Started, outcome)
        assertEquals(listOf("discoverConnect", "connect:4321:starter"), adb.calls)
    }

    @Test
    fun `dead binder within budget is BinderTimeout`() = runTest {
        val adb = FakeMuseAdb()
        var polls = 0
        val outcome = AdbLauncher(adb).launch(testKey(), "starter", { polls++; false }, pollCount = 3)
        assertEquals(BinderTimeout, outcome)
        assertEquals(3, polls)
    }

    @Test
    fun `starter command reaches adbd verbatim`() = runTest {
        val adb = FakeMuseAdb()
        AdbLauncher(adb).launch(testKey(), "/data/lib/libmuse_starter.so --apk=/data/app/muse.apk", { true })
        assertEquals("/data/lib/libmuse_starter.so --apk=/data/app/muse.apk", adb.calls.last().removePrefix("connect:4321:"))
    }
}
