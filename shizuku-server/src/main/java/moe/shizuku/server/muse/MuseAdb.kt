package moe.shizuku.server.muse

import android.content.Context
import android.net.nsd.NsdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyStore
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import java.security.Key

// MUSE: 计划 Task 9/10/11 —— app 侧只 import 这一个包的入口。
// MuseAdb 是接口（Task 11 的决策序单测注入假体）；MuseAdbKeys 无状态，JVM 单测直接用。

/** 一次无线连接尝试的结果；[log] 是 adbd 输出/异常摘要，进诊断。 */
data class AdbResult(val success: Boolean, val log: String?)

/** 配对失败三类，文案在设置页分开（Task 12）。 */
enum class PairFailure { WRONG_CODE, TIMEOUT, DISCOVERY }

data class PairResult(val success: Boolean, val reason: PairFailure?)

interface MuseAdb {

    /** 现场发现无线调试的连接端口（每次现发现不缓存——重开无线调试会换端口）。 */
    suspend fun discoverConnectPort(timeoutMs: Long): Int?

    /** 现场发现配对服务的端口（6 位码配对用）。 */
    suspend fun discoverPairingPort(timeoutMs: Long): Int?

    /** 连接并执行 starter 命令（starter fork 后父进程即退，shell 不会阻塞）。 */
    suspend fun connectAndStart(key: AdbKey, port: Int, starterCmd: String): AdbResult

    /** 6 位码配对。 */
    suspend fun pair(key: AdbKey, pairingPort: Int, code: String): PairResult
}

class MuseAdbClient(private val context: Context) : MuseAdb {

    override suspend fun discoverConnectPort(timeoutMs: Long): Int? =
        discoverPort(AdbMdns.TLS_CONNECT, timeoutMs)

    override suspend fun discoverPairingPort(timeoutMs: Long): Int? =
        discoverPort(AdbMdns.TLS_PAIRING, timeoutMs)

    private suspend fun discoverPort(serviceType: String, timeoutMs: Long): Int? =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val mdns = AdbMdns(context, serviceType) { port ->
                        // -1 = 服务丢失，忽略；0 是无效端口
                        if (port > 0 && cont.isActive) cont.resume(port, null)
                    }
                    cont.invokeOnCancellation { mdns.stop() }
                    mdns.start()
                }
            }
        }

    override suspend fun connectAndStart(key: AdbKey, port: Int, starterCmd: String): AdbResult =
        withContext(Dispatchers.IO) {
            val log = StringBuilder()
            runCatching {
                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    client.shellCommand(starterCmd) { bytes -> log.append(String(bytes)) }
                }
            }.fold(
                onSuccess = { AdbResult(true, log.toString().ifEmpty { null }) },
                onFailure = { e -> AdbResult(false, "${e.javaClass.simpleName}: ${e.message}") },
            )
        }

    override suspend fun pair(key: AdbKey, pairingPort: Int, code: String): PairResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // start() 返回 false = 交换失败（码错为主）；异常 = 网络/超时层
                AdbPairingClient("127.0.0.1", pairingPort, code, key).use { it.start() }
            }.fold(
                onSuccess = { ok -> PairResult(ok, if (ok) null else PairFailure.WRONG_CODE) },
                onFailure = { PairResult(false, PairFailure.TIMEOUT) },
            )
        }
}

/** RSA 密钥对的读写（blob 本体由 AdbKey 内部用 AndroidKeyStore 加密）。无状态，JVM 可测。 */
object MuseAdbKeys {

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
