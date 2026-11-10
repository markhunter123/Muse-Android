package io.github.markhunter123.muse.core.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.markhunter123.muse.core.data.SettingsRepository
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.server.muse.AdbResult
import moe.shizuku.server.muse.KeyStorage
import moe.shizuku.server.muse.MuseAdb
import moe.shizuku.server.muse.PairResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Task 14: 开机自启试一次（tryAutoStart）的决策——已配对才动作、失败记 pref 等前台重试、启动预算比 ensureRunning 小。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class ShellServerControllerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private class FakeMuseAdb : MuseAdb {
        val calls = mutableListOf<String>()
        override suspend fun discoverConnectPort(timeoutMs: Long): Int? { calls += "discover:$timeoutMs"; return 5555 }
        override suspend fun discoverPairingPort(timeoutMs: Long): Int? { calls += "pairing:$timeoutMs"; return null }
        override suspend fun connectAndStart(key: AdbKey, port: Int, starterCmd: String): AdbResult {
            calls += "connect:$port:$starterCmd"; return AdbResult(true, null)
        }
        override suspend fun pair(key: AdbKey, pairingPort: Int, code: String): PairResult =
            PairResult(false, null)
    }

    private class MemKeyStorage : KeyStorage {
        var bytes: ByteArray? = null
        override fun save(bytes: ByteArray) { this.bytes = bytes }
        override fun load(): ByteArray? = bytes
        override fun clear() { bytes = null }
    }

    private fun softwareAesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun controller(adb: FakeMuseAdb, storage: MemKeyStorage): ShellServerController =
        ShellServerController(app, SettingsRepository(app), ShellAccess(app, storage), AdbLauncher(adb), storage)

    private fun bootPrefs() = app.getSharedPreferences("shell_boot", Context.MODE_PRIVATE)

    /** Robolectric 的 nativeLibraryDir 是 null，starter 命令一律显式传。 */
    private val starter = "/x/libmuse_starter.so --apk=/x/app.apk"

    @Test fun `no key is silent false, no launch attempt, nothing recorded`() = runTest {
        val adb = FakeMuseAdb()
        val c = controller(adb, MemKeyStorage())
        assertFalse(c.tryAutoStart(key = null, pollBinder = { true }, starterCmd = starter))
        assertTrue(adb.calls.isEmpty())
        assertFalse(bootPrefs().getBoolean("retry_pending", false))
    }

    @Test fun `binder comes up so true, retry flag stays clear`() = runTest {
        val adb = FakeMuseAdb()
        val storage = MemKeyStorage().apply { save(ByteArray(16)) }
        val c = controller(adb, storage)
        var polls = 0
        assertTrue(c.tryAutoStart(key = testKey(storage), pollBinder = { polls++; true }, starterCmd = starter))
        assertTrue(polls >= 1)
        assertTrue(adb.calls.first().startsWith("discover:"))
        assertTrue(adb.calls.any { it.startsWith("connect:5555:") })
        assertFalse(bootPrefs().getBoolean("retry_pending", false))
    }

    @Test fun `binder never comes up so false, retry flag set, small poll budget`() = runTest {
        val adb = FakeMuseAdb()
        val storage = MemKeyStorage().apply { save(ByteArray(16)) }
        val c = controller(adb, storage)
        var polls = 0
        assertFalse(c.tryAutoStart(key = testKey(storage), pollBinder = { polls++; false }, starterCmd = starter))
        assertEquals(5, polls)
        assertTrue(bootPrefs().getBoolean("retry_pending", true))
    }

    @Test fun `discover timeout within boot budget`() = runTest {
        val adb = FakeMuseAdb()
        val storage = MemKeyStorage().apply { save(ByteArray(16)) }
        val c = controller(adb, storage)
        c.tryAutoStart(key = testKey(storage), pollBinder = { true }, starterCmd = starter)
        assertEquals("discover:10000", adb.calls.first())
    }

    private fun testKey(storage: MemKeyStorage): AdbKey =
        moe.shizuku.server.muse.MuseAdbKeys.createKey(storage, softwareAesKey())

    /** 回前台自动重连：已配对且未就绪才试（覆盖安装杀掉 server 后的自动拉起）。 */
    @Test fun `on foreground, a paired but not ready server is restarted`() = runTest {
        val adb = FakeMuseAdb()
        val storage = MemKeyStorage().apply { save(ByteArray(16)) }
        val c = controller(adb, storage)
        assertTrue(c.onForeground(key = testKey(storage), pollBinder = { true }, starterCmd = starter))
        assertTrue(adb.calls.any { it.startsWith("connect:5555:") })
    }

    @Test fun `on foreground, an unpaired server is left alone`() = runTest {
        val adb = FakeMuseAdb()
        val c = controller(adb, MemKeyStorage())
        assertFalse(c.onForeground(key = null, pollBinder = { true }, starterCmd = starter))
        assertTrue(adb.calls.isEmpty())
    }
}
