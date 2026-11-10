package io.github.markhunter123.muse.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.shizuku.server.muse.KeyStorage
import moe.shizuku.server.muse.MuseAdb
import moe.shizuku.server.muse.MuseAdbKeys
import moe.shizuku.server.muse.PairFailure
import javax.inject.Inject
import javax.inject.Singleton

/** 设置页"shell 权限"配对流程的阶段（Task 12 的 UI 消费）。 */
sealed interface PairingStep {
    data object Idle : PairingStep
    /** 正在 mDNS 现场发现配对服务。 */
    data object Discovering : PairingStep
    /** 配对服务已找到，等用户输入设备弹窗里的 6 位码。 */
    data object WaitingCode : PairingStep
    data object Paired : PairingStep
    /** 三类失败文案分开（spec 错误处理）。 */
    data class Failed(val reason: PairFailure) : PairingStep
}

/**
 * 一次性配对（spec）：发现配对服务 → 用户输码 → AdbPairingClient 完成 → 密钥落 Keystore
 * 加密存储 → 成功后立刻走一遍启动流程（[ShellServerController.ensureRunning]）。
 */
@Singleton
class ShellPairing @Inject constructor(
    private val museAdb: MuseAdb,
    private val keyStorage: KeyStorage,
    private val controller: ShellServerController,
) {
    private val _step = MutableStateFlow<PairingStep>(PairingStep.Idle)
    val step: StateFlow<PairingStep> = _step.asStateFlow()

    private var pairingPort = 0

    /** 开始配对：现场发现 _adb-tls-pairing 服务；找到后进入等码。 */
    suspend fun start() {
        _step.value = PairingStep.Discovering
        val port = museAdb.discoverPairingPort(PAIRING_DISCOVER_TIMEOUT_MS)
        if (port == null) {
            _step.value = PairingStep.Failed(PairFailure.DISCOVERY)
            return
        }
        pairingPort = port
        _step.value = PairingStep.WaitingCode
    }

    /** 用户提交 6 位码：配对 → 存密钥 → 立即连接。AndroidKeyStore 可能抛异常（厂商 bug/损坏），降级成 KEYSTORE 失败不崩 UI。 */
    suspend fun submitCode(code: String) {
        val key = runCatching { MuseAdbKeys.createKey(keyStorage) }.getOrElse {
            _step.value = PairingStep.Failed(PairFailure.KEYSTORE)
            return
        }
        val result = museAdb.pair(key, pairingPort, code)
        if (!result.success) {
            _step.value = PairingStep.Failed(result.reason ?: PairFailure.TIMEOUT)
            return
        }
        _step.value = PairingStep.Paired
        controller.ensureRunning()
    }

    fun reset() {
        _step.value = PairingStep.Idle
    }

    /** 重新配对：先删旧密钥再走一遍发现（spec：重新生成密钥前先删旧的）。 */
    suspend fun restart() {
        keyStorage.clear()
        start()
    }

    private companion object {
        const val PAIRING_DISCOVER_TIMEOUT_MS = 10_000L
    }
}
