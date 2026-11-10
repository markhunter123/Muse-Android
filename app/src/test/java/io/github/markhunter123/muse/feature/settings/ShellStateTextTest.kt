package io.github.markhunter123.muse.feature.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.markhunter123.muse.R
import io.github.markhunter123.muse.core.platform.ShellServerController
import io.github.markhunter123.muse.core.platform.ShellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** #42: the status UI (card tag, Doctor row) tells the Shizuku states apart and gives each its next step. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class ShellStateTextTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `labels and hints differ per state`() {
        val notReady = listOf(ShellState.NotPaired, ShellState.PairedNotRunning(""), ShellState.RunningNoPermission(false), ShellState.Unsupported(10))
        val labels = (notReady + ShellState.Ready).map { shellStateLabel(app, it) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(shellStateLabel(app, ShellState.Unsupported(10)).contains("10"))
        val hints = (notReady + ShellState.PairedNotRunning("wadb port not found") + ShellState.RunningNoPermission(true)).map { shellStateHint(app, it) }
        assertTrue(hints.all { !it.isNullOrBlank() })
        assertEquals(hints.size, hints.toSet().size)
        assertNull(shellStateHint(app, ShellState.Ready))
    }

    /** 已知失败原因给本地化文案（含"重开无线调试"指引），没见过的保持原样。 */
    @Test fun `known failure reasons get localized text with guidance, unknown ones stay raw`() {
        assertEquals(app.getString(R.string.shell_fail_no_port), shellStateHint(app, ShellState.PairedNotRunning(ShellServerController.REASON_NO_PORT)))
        assertEquals(app.getString(R.string.shell_fail_binder), shellStateHint(app, ShellState.PairedNotRunning(ShellServerController.REASON_BINDER_TIMEOUT)))
        assertEquals(app.getString(R.string.shell_fail_connect, "10.0.0.2:5555 refused"),
            shellStateHint(app, ShellState.PairedNotRunning("adb connect failed: 10.0.0.2:5555 refused")))
        assertEquals(app.getString(R.string.shell_server_last_failure, "something new"),
            shellStateHint(app, ShellState.PairedNotRunning("something new")))
    }

    /** Android 10 没有无线调试：提示指路启动而不是配对。 */
    @Config(application = Application::class, sdk = [29])
    @Test fun `android 10 hint names the start path, not pairing`() {
        assertEquals(app.getString(R.string.shz_hint_missing_root), shellStateHint(app, ShellState.NotPaired))
    }

    @Test fun `pairing steps are per sdk - root and manual below 30, wireless debugging at 30 and above`() {
        assertEquals(listOf(R.string.shz_step1_root, R.string.shz_step2_root, R.string.shz_step3_root), pairingSteps(29))
        assertEquals(listOf(R.string.shz_step1, R.string.shz_step2, R.string.shz_step3), pairingSteps(30))
        assertEquals(listOf(R.string.shz_step1, R.string.shz_step2, R.string.shz_step3), pairingSteps(36))
    }
}
