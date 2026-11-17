package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import moe.shizuku.server.muse.AdbEndpoint

// MUSE: 上游此处用 androidx.lifecycle.Observer<Int>，该接口在 lifecycle 2.11 里已移除，改用等价的 Kotlin 函数类型；
// 回调从裸端口改为 host+port（MUSE: AdbEndpoint）——部分厂商 ROM 的 adbd 只绑 wlan IP，回环不可达。
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: (AdbEndpoint) -> Unit
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            nsdManager.stopServiceDiscovery(listener)
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer(AdbEndpoint("", -1))
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val host = resolvedService.host?.hostAddress ?: return
        if (running && NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { host == it.hostAddress }
                }
            && isPortAvailable(resolvedService.host, resolvedService.port)
        ) {
            serviceName = resolvedService.serviceName
            observer(AdbEndpoint(host, resolvedService.port))
        }
    }

    // MUSE: 探活必须覆盖两类绑定姿势——绑定失败（端口被占）= 有东西在听 = 端口可用。
    // 原生 Android 的 adbd 绑 0.0.0.0，回环探活就够；MagicOS 10 实测 adbd 的连接端口只绑 wlan IP
    // （回环 refused），而回环专属监听也存在——所以公告主机或回环任一被占用都算活服务，
    // 只探其一会把另一种姿势误判成失效公告、永远发现不到。两个都绑得上才是真失效。
    private fun isPortAvailable(host: InetAddress, port: Int) =
        isBindable(host, port) && isBindable(InetAddress.getByName(LOOPBACK), port)

    private fun isBindable(host: InetAddress, port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress(host, port), 1)
            true
        }
    } catch (e: IOException) {
        false
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val LOOPBACK = "127.0.0.1"
        const val TAG = "AdbMdns"
    }
}
