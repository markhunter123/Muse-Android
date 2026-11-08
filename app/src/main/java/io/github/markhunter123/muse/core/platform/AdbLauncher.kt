package io.github.markhunter123.muse.core.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.server.muse.MuseAdb
import javax.inject.Inject

/** 无线调试自动连接一次尝试的结果；失败变体都带机器可读原因（Task 13 的 ShellProbe.lastFailure 消费）。 */
sealed interface AdbLaunchOutcome {
    /** 没有已存密钥（未配对），不该走无线路径。 */
    data object NoKey : AdbLaunchOutcome
    /** mDNS 没找到无线调试端口（多半没开无线调试 / 刚重开还在刷新）。 */
    data object NoPort : AdbLaunchOutcome
    /** 连接或执行 starter 失败；[log] 是 adbd 输出/异常摘要。 */
    data class ConnectFailed(val log: String?) : AdbLaunchOutcome
    /** starter 已执行且 binder 起来了。 */
    data object Started : AdbLaunchOutcome
    /** starter 执行了但 binder 在预算内没应答（首次 dexopt 慢或起不来）。 */
    data object BinderTimeout : AdbLaunchOutcome
}

/**
 * 无线调试路径的启动器（spec 决策序第 2 步）：mDNS 现发现端口 → AdbClient 连接认证 →
 * shell 执行 starter → 轮询 binder。端口每次现发现不缓存（重开无线调试会换端口）。
 * 决策序单测在 [AdbLauncherTest]：假 MuseAdb 注入，null 密钥短路、失败顺序都可编排。
 */
class AdbLauncher @javax.inject.Inject constructor(private val museAdb: MuseAdb) {

    /**
     * [key] 传 null 表示没配对（调用方已把 API≥30 的判断做完）；[pollBinder] 每秒被问一次
     * "binder 活了吗"；[pollCount] 覆盖首次启动的 dexopt 预算（spec：容忍 10–30 秒）。
     */
    suspend fun launch(
        key: AdbKey?,
        starterCmd: String,
        pollBinder: suspend () -> Boolean,
        pollCount: Int = 30,
        discoverTimeoutMs: Long = 5_000,
    ): AdbLaunchOutcome = withContext(Dispatchers.IO) {
        if (key == null) return@withContext AdbLaunchOutcome.NoKey
        val port = museAdb.discoverConnectPort(discoverTimeoutMs)
            ?: return@withContext AdbLaunchOutcome.NoPort
        val result = museAdb.connectAndStart(key, port, starterCmd)
        if (!result.success) return@withContext AdbLaunchOutcome.ConnectFailed(result.log)
        repeat(pollCount) { i ->
            if (pollBinder()) return@withContext AdbLaunchOutcome.Started
            if (i < pollCount - 1) delay(POLL_INTERVAL_MS)
        }
        AdbLaunchOutcome.BinderTimeout
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}
