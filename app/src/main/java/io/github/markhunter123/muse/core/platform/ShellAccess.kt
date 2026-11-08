package io.github.markhunter123.muse.core.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.shizuku.server.muse.KeyStorage
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where shell access stands right now. The embedded server (or an official Shizuku server still
 * running, during coexistence) pushes its binder to us; "running" means that binder answers.
 */
sealed interface ShellState {
    /** No paired ADB key yet — the user has never paired (root does not need pairing). */
    data object NotPaired : ShellState
    /** Paired but the server is not running; [reason] is the machine-readable cause ("" when nothing failed yet, e.g. right after boot). */
    data class PairedNotRunning(override val reason: String) : ShellState
    /** Server running, Muse not authorized. [denied]: the user chose "deny" before (official-server coexistence only; the embedded server auto-grants itself). */
    data class RunningNoPermission(val denied: Boolean) : ShellState
    data object Ready : ShellState
    /** Server older than the API Muse needs (user services need v11+). */
    data class Unsupported(val version: Int) : ShellState

    val ready get() = this == Ready
    /** Short model-facing reason, e.g. for tool descriptions and results. */
    val reason get() = when (this) {
        NotPaired -> "shell is not paired"
        is PairedNotRunning -> reason
        is RunningNoPermission -> "Muse is not authorized for shell access"
        Ready -> "shell is ready"
        is Unsupported -> "the shell server is too old (API $version)"
    }
    /** What the user has to do next, in words (diagnostics; the UI has localized ones in ShellGuide). */
    val hint get() = when (this) {
        NotPaired -> "pair once in Settings → Phone permissions → shell access (root needs no pairing)"
        is PairedNotRunning -> if (reason.isEmpty()) "shell server not running — start it from the shell access page (wireless debugging or root)"
            else "shell server not running: $reason — retry from the shell access page"
        is RunningNoPermission -> if (denied) "denied before: enable Muse under Authorized apps in Shizuku" else "tap Authorize in Muse (the server shows its prompt)"
        Ready -> "nothing to do"
        is Unsupported -> "update Muse (the embedded server ships with it) or, during coexistence, the official Shizuku app"
    }

    companion object {
        /**
         * The state for one [ShellProbe]: running = the binder answers with a version; a binder that
         * stops answering mid-check counts as not running. Without a paired key it is "not paired".
         */
        fun classify(p: ShellProbe): ShellState {
            if (p.binderAlive && p.serverVersion != null) {
                if (p.serverVersion < ShellAccess.MIN_VERSION) return ShellState.Unsupported(p.serverVersion)
                when (p.granted) {
                    true -> return ShellState.Ready
                    false -> return ShellState.RunningNoPermission(p.denied)
                    null -> {}
                }
            }
            return if (p.paired) ShellState.PairedNotRunning(p.lastFailure ?: "") else ShellState.NotPaired
        }
    }
}

/**
 * One look at the shell server; [ShellState.classify] turns it into a [ShellState]. Kept apart from
 * the Shizuku calls so the mapping is testable: the binder answering (pingBinder) is what "running"
 * means, and the paired key tells "not paired" from "paired but stopped".
 */
data class ShellProbe(
    val binderAlive: Boolean,
    /** Server API version; null when asking failed (the binder died between the calls). */
    val serverVersion: Int?,
    /** Whether Muse holds the Shizuku permission; null when asking failed. */
    val granted: Boolean?,
    /** The user chose "deny" before (the server will not prompt again). */
    val denied: Boolean,
    /** A paired ADB key exists in storage (it worked here before). */
    val paired: Boolean,
    /** The last auto-start failure (machine-readable); null when nothing failed yet. */
    val lastFailure: String?,
)

/**
 * The single source of truth for shell access: a [StateFlow] driven by Shizuku's binder received/dead
 * and permission-result callbacks and app-foreground refreshes — no polling. The embedded server and
 * a still-running official one both count (coexistence); pairing state comes from [KeyStorage].
 */
@Singleton
class ShellAccess @Inject constructor(@ApplicationContext private val context: Context, private val keyStorage: KeyStorage) {
    private val _state = MutableStateFlow(compute())
    val state: StateFlow<ShellState> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("shell_access", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Sessions whose user tapped 不用了: no more shell cards there (tools use their fallback). */
    private val declined = ConcurrentHashMap.newKeySet<String>()

    /** 最近一次无线自动连接失败的机器可读原因；成功即清空。probe() 读它，[ShellServerController] 写它。 */
    @Volatile
    var lastFailure: String? = null

    init {
        migratePrefs()
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { code, _ -> if (code == REQUEST_CODE) refresh() }
        // Permission can be revoked inside the official Shizuku app without any callback to us.
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { refresh() }
            })
        }
    }

    /** 老用户升级：was_ready 读出来写进 legacy_was_ready 并删旧 key（paired 现在来自密钥文件，
     *  wasReady 只剩"老用户提示可卸载官方 App"这一层语义，Task 17 用）；boot tip 随官方路径淘汰。 */
    private fun migratePrefs() {
        if (prefs.contains(KEY_WAS_READY)) {
            if (prefs.getBoolean(KEY_WAS_READY, false)) prefs.edit().putBoolean(KEY_LEGACY_WAS_READY, true).apply()
            prefs.edit().remove(KEY_WAS_READY).apply()
        }
        prefs.edit().remove(KEY_BOOT_TIP).apply()
    }

    /** 旧用户标记（曾用官方 Shizuku 且成功过）；迁移提示（Task 17）读它。 */
    val legacyWasReady: Boolean get() = prefs.getBoolean(KEY_LEGACY_WAS_READY, false)

    /** Re-evaluates now (cheap: a ping, a permission check and a key-storage lookup). */
    fun refresh(): ShellState {
        val s = compute()
        _state.value = s
        return s
    }

    private fun compute(): ShellState = ShellState.classify(probe())

    private fun probe(): ShellProbe {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        // A failed call (the binder died meanwhile) reads as unknown, never as "too old" or "not authorized".
        val version = if (!alive) null else runCatching { if (Shizuku.isPreV11()) 10 else Shizuku.getVersion() }.getOrNull()
        val granted = if (version == null || version < MIN_VERSION) null
            else runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrNull()
        val denied = granted == false && runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        val paired = runCatching { keyStorage.load() != null }.getOrDefault(false)
        return ShellProbe(alive, version, granted, denied, paired, lastFailure)
    }

    /** Server API version while the binder answers, else null (diagnostics). */
    fun serverVersion(): Int? = if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) null
        else runCatching { if (Shizuku.isPreV11()) 10 else Shizuku.getVersion() }.getOrNull()

    /** One line for 诊断信息: state, pairing, server API and the next step. */
    fun describe(s: ShellState = state.value): String =
        "${s.reason}; paired ${runCatching { keyStorage.load() != null }.getOrDefault(false)}; server API ${serverVersion() ?: "-"} (needs $MIN_VERSION+); next: ${s.hint}"

    /** Shows the server's permission dialog (no-op unless the server is running). */
    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    // ---- per-session decline (shared by the composer card and the capability layer) -------------

    fun decline(sessionId: String) { if (sessionId.isNotEmpty()) declined += sessionId }
    fun isDeclined(sessionId: String) = sessionId in declined

    // ---- intents ------------------------------------------------------------------------------

    fun developerOptionsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 打开官方 Shizuku 管理器（共存期用；Task 16 拆官方路径时删除）。 */
    fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)

    /** 官方管理器的应用商店页；解析不到商店时为 null（调用方回退 GitHub releases）。 */
    fun playStoreIntent(): Intent? = runCatching {
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { context.packageManager.queryIntentActivities(it, 0).isNotEmpty() }
    }.getOrNull()

    companion object {
        const val REQUEST_CODE = 7
        const val MIN_VERSION = 11
        private const val KEY_WAS_READY = "was_ready"
        private const val KEY_LEGACY_WAS_READY = "legacy_was_ready"
        private const val KEY_BOOT_TIP = "boot_tip_dismissed"

        // ---- 官方路径残留（launchIntent/playStoreIntent/PACKAGE/RELEASES_URL；Task 15/16 拆除） ----
        const val PACKAGE = "moe.shizuku.privileged.api"
        const val RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"
    }
}
