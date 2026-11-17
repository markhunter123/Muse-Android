package moe.shizuku.server.muse

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyStore
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import java.net.ConnectException
import java.security.Key

// MUSE: 计划 Task 9/10/11 —— app 侧只 import 这一个包的入口。
// MuseAdb 是接口（Task 11 的决策序单测注入假体）；MuseAdbKeys 无状态，JVM 单测直接用。

/** 一次无线连接尝试的结果；[log] 是 adbd 输出/异常摘要，进诊断。 */
data class AdbResult(val success: Boolean, val log: String?)

/** 配对失败四类，文案在设置页分开（Task 12；KEYSTORE 是 Final review 补的密钥库失败）。 */
enum class PairFailure { WRONG_CODE, TIMEOUT, DISCOVERY, KEYSTORE }

/** [detail] 带底层异常摘要：MagicOS 的 logd 过滤应用日志，排障靠 UI 把它露出来。 */
data class PairResult(val success: Boolean, val reason: PairFailure?, val detail: String? = null)

/**
 * mDNS 现场发现的 adbd 服务地址。原生 Android 的 adbd 绑 0.0.0.0，本机连 127.0.0.1 就通；
 * 部分厂商 ROM（实测 MagicOS 10）的 adbd 只绑 wlan IP、回环直接 refused——所以主机地址必须
 * 跟着 mDNS 公告走，不能像上游 Shizuku 那样写死 127.0.0.1。
 */
data class AdbEndpoint(val host: String, val port: Int)

interface MuseAdb {

    /** 现场发现无线调试的连接服务（每次现发现不缓存——重开无线调试会换端口）。 */
    suspend fun discoverConnectService(timeoutMs: Long): AdbEndpoint?

    /** 现场发现配对服务（6 位码配对用）。 */
    suspend fun discoverPairingService(timeoutMs: Long): AdbEndpoint?

    /** 连接并执行 starter 命令（starter fork 后父进程即退，shell 不会阻塞）。 */
    suspend fun connectAndStart(key: AdbKey, service: AdbEndpoint, starterCmd: String): AdbResult

    /** 6 位码配对。 */
    suspend fun pair(key: AdbKey, service: AdbEndpoint, code: String): PairResult
}

class MuseAdbClient(private val context: Context) : MuseAdb {

    override suspend fun discoverConnectService(timeoutMs: Long): AdbEndpoint? =
        discoverService(AdbMdns.TLS_CONNECT, timeoutMs)

    override suspend fun discoverPairingService(timeoutMs: Long): AdbEndpoint? =
        discoverService(AdbMdns.TLS_PAIRING, timeoutMs)

    private suspend fun discoverService(serviceType: String, timeoutMs: Long): AdbEndpoint? =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val mdns = AdbMdns(context, serviceType) { endpoint ->
                        // port<=0 = 服务丢失，忽略
                        if (endpoint.port > 0 && cont.isActive) cont.resume(endpoint, null)
                    }
                    cont.invokeOnCancellation { mdns.stop() }
                    mdns.start()
                }
            }
        }

    override suspend fun connectAndStart(key: AdbKey, service: AdbEndpoint, starterCmd: String): AdbResult =
        withContext(Dispatchers.IO) {
            val log = StringBuilder()
            runCatching {
                onReachableHost(service) { host ->
                    AdbClient(host, service.port, key).use { client ->
                        client.connect()
                        client.shellCommand(starterCmd) { bytes -> log.append(String(bytes)) }
                    }
                }
            }.fold(
                onSuccess = { AdbResult(true, log.toString().ifEmpty { null }) },
                onFailure = { e -> AdbResult(false, "${e.javaClass.simpleName}: ${e.message}") },
            )
        }

    override suspend fun pair(key: AdbKey, service: AdbEndpoint, code: String): PairResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // start() 返回 false = 交换失败（码错为主）；异常 = 网络/超时层
                onReachableHost(service) { host ->
                    AdbPairingClient(host, service.port, code, key).use { it.start() }
                }
            }.fold(
                onSuccess = { ok -> PairResult(ok, if (ok) null else PairFailure.WRONG_CODE) },
                onFailure = { e ->
                    // 统一映射成 TIMEOUT 之前，把真实异常留档（logcat + UI detail，MagicOS 上 logcat 可能被滤）
                    Log.w(TAG, "pairing to ${service.host}:${service.port} failed", e)
                    PairResult(false, PairFailure.TIMEOUT, "${e.javaClass.simpleName}: ${e.message}")
                },
            )
        }

    /**
     * 依次试 mDNS 公告的主机、本机回环，第一个连上的为准。只有"到不了"（ConnectException，即该地址
     * 没监听）才值得换下一个地址；交换层失败（TLS/协议）重试只是重复一次同样的失败。
     */
    private fun <T> onReachableHost(service: AdbEndpoint, attempt: (host: String) -> T): T {
        var last: ConnectException? = null
        for (host in listOf(service.host, LOOPBACK).distinct()) {
            try {
                return attempt(host)
            } catch (e: ConnectException) {
                Log.w(TAG, "connect to $host:${service.port} refused")
                last = e
            }
        }
        throw last ?: IllegalStateException("no host to try for $service")
    }

    private companion object {
        const val TAG = "MuseAdb"
        const val LOOPBACK = "127.0.0.1"
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
