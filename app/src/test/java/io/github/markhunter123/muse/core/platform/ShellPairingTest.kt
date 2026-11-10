package io.github.markhunter123.muse.core.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.server.muse.AdbResult
import moe.shizuku.server.muse.KeyStorage
import moe.shizuku.server.muse.MuseAdb
import moe.shizuku.server.muse.PairFailure
import moe.shizuku.server.muse.PairResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Final review F7：AndroidKeyStore 抛异常时配对降级成 KEYSTORE 失败并给文案，不崩 UI、不误报别的失败。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class ShellPairingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** 模拟厂商 keystore 损坏：任何读写都抛。 */
    private class ThrowingKeyStorage : KeyStorage {
        override fun save(bytes: ByteArray): Unit = throw IllegalStateException("keystore broken")
        override fun load(): ByteArray? = throw IllegalStateException("keystore broken")
        override fun clear(): Unit = throw IllegalStateException("keystore broken")
    }

    private class FakeMuseAdb : MuseAdb {
        var paired = 0
        override suspend fun discoverConnectPort(timeoutMs: Long): Int? = 5555
        override suspend fun discoverPairingPort(timeoutMs: Long): Int? = 12345
        override suspend fun connectAndStart(key: AdbKey, port: Int, starterCmd: String): AdbResult = AdbResult(true, null)
        override suspend fun pair(key: AdbKey, pairingPort: Int, code: String): PairResult { paired++; return PairResult(true, null) }
    }

    /** keystore 坏：submitCode 走 KEYSTORE 失败，绝不触到 pair。 */
    @Test fun `broken keystore degrades to keystore failure instead of crashing`() = runTest {
        val adb = FakeMuseAdb()
        val pairing = ShellPairing(adb, ThrowingKeyStorage(), controller())
        pairing.start()
        pairing.submitCode("123456")
        assertEquals(PairingStep.Failed(PairFailure.KEYSTORE), pairing.step.value)
        assertEquals(0, adb.paired)
    }

    /** controller 只在配对成功后才被触到；这里塞空的 key storage 保持构造完整。 */
    private class MemKeyStorage : KeyStorage {
        var bytes: ByteArray? = null
        override fun save(bytes: ByteArray) { this.bytes = bytes }
        override fun load(): ByteArray? = bytes
        override fun clear() { bytes = null }
    }

    private fun controller(): ShellServerController =
        ShellServerController(app, io.github.markhunter123.muse.core.data.SettingsRepository(app),
            ShellAccess(app, MemKeyStorage()), AdbLauncher(FakeMuseAdb()), MemKeyStorage())
}
