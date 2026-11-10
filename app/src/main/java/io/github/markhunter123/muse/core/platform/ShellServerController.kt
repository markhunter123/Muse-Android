package io.github.markhunter123.muse.core.platform

import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.markhunter123.muse.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.server.muse.KeyStorage
import moe.shizuku.server.muse.MuseAdbKeys
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/** 一次启动尝试对 UI 呈现的阶段（设置页的 shell 权限卡读取）。 */
enum class ConnectionStatus { IDLE, CONNECTING, STARTING, FAILED }

/** 三条启动命令的纯拼接（[ShellServerCommandsTest] 钉住格式）：root 包一层 su，手动是给用户复制的 adb 行。 */
object ShellServerCommands {

    /** starter 的调用形态：libmuse_starter.so 自带 --apk 参数，内部 CLASSPATH 指向 Muse 的 APK 拉起 app_process。 */
    fun starterCommand(apkPath: String, nativeLibDir: String, packageName: String): String =
        "$nativeLibDir/libmuse_starter.so --apk=$apkPath --package=$packageName"

    /** root 路径：su -c 执行 starter。展示用；实际 exec 走数组形式（见 [ShellServerController]）。 */
    fun rootCommand(starter: String, apkPath: String, packageName: String): String =
        "su -c '$starter --apk=$apkPath --package=$packageName'"

    /** 手动路径：用户在电脑上执行的 adb shell 行（UI 一键复制）。 */
    fun manualCommand(starter: String, apkPath: String, packageName: String): String =
        "adb shell $starter --apk=$apkPath --package=$packageName"
}

/**
 * 嵌入式 shell server 的启动器（spec 决策序）：binder 已活就直接用；Android 11+ 且已配对时
 * 走无线调试自动连接（[AdbLauncher]）；设置里开了 root 自启（shellRoot）再 exec
 * libmuse_starter.so 兜底。手动路径只产出命令字符串给 UI 复制。
 * 状态判定一律以 [ShellAccess]（binder 是否应答）为准，这里不维护第二份状态机。
 */
@Singleton
class ShellServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val shell: ShellAccess,
    private val launcher: AdbLauncher,
    private val keyStorage: KeyStorage,
) {
    private val _connection = MutableStateFlow(ConnectionStatus.IDLE)
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    private val prefs = context.getSharedPreferences("shell_boot", Context.MODE_PRIVATE)

    init {
        // 回前台自动重连（spec 触发点：冷启/回前台）：已配对且 binder 未就绪就试一次，
        // 覆盖安装杀掉 server、开机自启失败都靠这里拉起。ProcessLifecycleOwner 在单测环境可能未初始化——跳过钩子即可。
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        CoroutineScope(Dispatchers.IO).launch { onForeground() }
                    }
                })
            }
        }
    }

    /** 确保 server 在跑：binder → 无线调试自动连接 → root 兜底（shellRoot 门禁）。自动路径用。 */
    suspend fun ensureRunning(): ShellState = withContext(Dispatchers.IO) {
        val current = shell.refresh()
        if (current.ready) return@withContext current
        if (Build.VERSION.SDK_INT >= 30) {
            val key = runCatching { MuseAdbKeys.loadKey(keyStorage) }.getOrNull()
            if (key == null) return@withContext if (settings.current().shellRoot) startRoot() else shell.refresh()
            val starterCmd = starterCommand()
            _connection.value = ConnectionStatus.CONNECTING
            when (val outcome = launcher.launch(key, starterCmd, ::pollBinder)) {
                AdbLaunchOutcome.Started -> {
                    shell.lastFailure = null
                    _connection.value = ConnectionStatus.IDLE
                    return@withContext shell.refresh()
                }
                AdbLaunchOutcome.NoKey -> {}
                else -> {
                    shell.lastFailure = failureReason(outcome)
                    _connection.value = ConnectionStatus.FAILED
                }
            }
        }
        // root 兜底：spec 说 root 不静默探测——shellRoot 开关即用户的显式授权
        if (settings.current().shellRoot) startRoot() else shell.refresh()
    }

    /** 设置页按钮的显式启动：点击本身就是意图，不走 shellRoot 门禁。 */
    suspend fun startViaRoot(): ShellState = withContext(Dispatchers.IO) {
        if (shell.refresh().ready) return@withContext shell.state.value
        startRoot()
    }

    /**
     * 回前台自动重连（spec 触发点：冷启/回前台）：已配对且 binder 未就绪才试（[tryAutoStart] 的门禁壳）。
     * key/pollBinder/starterCmd 是测试注入口，真机走默认值。
     */
    suspend fun onForeground(
        pollBinder: suspend () -> Boolean = ::pollBinder,
        key: AdbKey? = resolveKey(),
        starterCmd: String = starterCommand(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (shell.refresh().ready) return@withContext false
        if (key == null) return@withContext false
        tryAutoStart(pollBinder, key, starterCmd)
    }

    /**
     * 开机自启试一次（[ShellBootReceiver] 调用）：已配对才走无线路径，失败静默记 pref，下次回前台重试。
     * 预算比 ensureRunning 小（receiver goAsync 内 ~20s 上限）；connection 状态不打扰 UI。
     * key/pollBinder/starterCmd 是测试注入口，真机走默认值。
     */
    suspend fun tryAutoStart(
        pollBinder: suspend () -> Boolean = ::pollBinder,
        key: AdbKey? = resolveKey(),
        starterCmd: String = starterCommand(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (shell.refresh().ready) return@withContext true
        if (key == null) return@withContext false
        val started = launcher.launch(
            key, starterCmd, pollBinder,
            pollCount = BOOT_POLL_COUNT, discoverTimeoutMs = BOOT_DISCOVER_TIMEOUT_MS,
        ) is AdbLaunchOutcome.Started
        prefs.edit().putBoolean(KEY_BOOT_RETRY, !started).apply()
        started
    }

    /** AndroidKeyStore 可能抛异常（厂商 bug、keystore 损坏）：吞掉降级成"没配对"，不崩启动链。 */
    private fun resolveKey(): AdbKey? =
        if (Build.VERSION.SDK_INT >= 30) runCatching { MuseAdbKeys.loadKey(keyStorage) }.getOrNull() else null

    private suspend fun pollBinder(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun starterCommand(): String {
        val info = context.applicationInfo
        return ShellServerCommands.starterCommand(info.sourceDir, info.nativeLibraryDir, context.packageName)
    }

    private fun failureReason(outcome: AdbLaunchOutcome): String = when (outcome) {
        is AdbLaunchOutcome.ConnectFailed -> REASON_CONNECT_PREFIX + outcome.log
        AdbLaunchOutcome.NoPort -> REASON_NO_PORT
        AdbLaunchOutcome.BinderTimeout -> REASON_BINDER_TIMEOUT
        else -> "unreachable"
    }

    private suspend fun startRoot(): ShellState {
        _connection.value = ConnectionStatus.CONNECTING
        val info = context.applicationInfo
        val inner = ShellServerCommands.starterCommand(info.sourceDir, info.nativeLibraryDir, context.packageName)
        // su -c 的命令体是单个参数：Runtime.exec 的字符串重载按空格切参、引号会被当字面量，必须走数组。
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", inner))
            p.inputStream.use { it.readBytes() }
            p.errorStream.use { it.readBytes() }
            p.waitFor()
        }
        _connection.value = ConnectionStatus.STARTING

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (pollBinder()) {
                _connection.value = ConnectionStatus.IDLE
                return shell.refresh()
            }
            delay(1_000)
        }
        _connection.value = ConnectionStatus.FAILED
        return shell.refresh()
    }

    /** 给 UI 复制的手动启动命令（adb shell …）。 */
    fun manualCommand(): String {
        val info = context.applicationInfo
        return ShellServerCommands.manualCommand("${info.nativeLibraryDir}/libmuse_starter.so", info.sourceDir, context.packageName)
    }

    companion object {
        /** 失败原因常量（[io.github.markhunter123.muse.feature.settings.ShellGuide.shellStateHint] 按此映射本地化文案）。 */
        const val REASON_NO_PORT = "wireless debugging port not found"
        const val REASON_BINDER_TIMEOUT = "starter ran but binder did not come up"
        const val REASON_CONNECT_PREFIX = "adb connect failed: "

        const val START_TIMEOUT_MS = 30_000L
        /** 开机自启预算（receiver goAsync 内）：mDNS 发现 10s + binder 轮询 5×1s，总预算 ~20s 内。 */
        const val BOOT_DISCOVER_TIMEOUT_MS = 10_000L
        const val BOOT_POLL_COUNT = 5
        const val KEY_BOOT_RETRY = "retry_pending"
    }
}
