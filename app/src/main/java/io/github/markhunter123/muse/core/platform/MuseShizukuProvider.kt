package io.github.markhunter123.muse.core.platform

import android.os.Bundle
import android.os.Parcelable
import moe.shizuku.api.BinderContainer
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * MUSE: 接管版 ShizukuProvider。
 *
 * 背景：用户服务（starter 进程）要把自己的 binder 送回 server，fork 里走的是
 * App 这个 provider。实测本机（华为 ROM，SDK 36）有两个坑：
 *
 * 1. 库的 ShizukuProvider.onCreate 会跑 Sui.init——向 ActivityManager 发 magic
 *    transaction（code 1599296841）探测 Sui 框架；App 进程（zygote 派生）里华为
 *    对它返回了非空 binder，垃圾 binder 被 onBinderReceived 缓存成 Shizuku
 *    binder，isSui=true 后 call() 对所有 method 短路，server 的推送从此被忽略。
 * 2. Sui 短路后的表现和 handleGetBinder 失败无法区分，所以干脆自己实现
 *    getBinder / sendBinder 两个 method，完全绕开 super。
 *
 * muse.dbg 是诊断字段，adb 侧探针可读；留着无害。
 */
class MuseShizukuProvider : ShizukuProvider() {

    /** 跳过库里的 Sui 初始化（见类注释），其余什么都不做。 */
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_GET_BINDER -> ownGetBinder()
        METHOD_SEND_BINDER -> ownSendBinder(extras)
        else -> super.call(method, arg, extras)
    }

    private fun ownGetBinder(): Bundle {
        val binder = Shizuku.getBinder()
        val reply = Bundle()
        if (binder != null && binder.pingBinder()) {
            reply.putParcelable(EXTRA_BINDER, BinderContainer(binder))
        }
        reply.putString("muse.dbg", "own-getBinder binder=$binder ping=${binder?.pingBinder()}")
        return reply
    }

    private fun ownSendBinder(extras: Bundle?): Bundle {
        val reply = Bundle()
        try {
            // 必须先设 classLoader——bundle 里的 BinderContainer 是自定义 Parcelable，
            // 没有它反序列化直接 BadParcelableException（库里是靠 call() 开头统一设置的，
            // 我们绕开了 super 就得自己设）
            extras?.classLoader = BinderContainer::class.java.classLoader
            @Suppress("DEPRECATION")
            val container = extras?.getParcelable<Parcelable>(EXTRA_BINDER) as? BinderContainer
            val binder = container?.binder
            if (binder != null) {
                // 与库里 handleSendBinder 一致：缓存 + 触发 attachApplication
                Shizuku.onBinderReceived(binder, getContext()?.packageName ?: "")
            }
            reply.putString(
                "muse.dbg",
                "own-sendBinder got=${binder != null} afterPing=${Shizuku.pingBinder()}"
            )
        } catch (t: Throwable) {
            reply.putString("muse.dbg", "own-sendBinder EX=$t")
        }
        return reply
    }

    private companion object {
        const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        const val METHOD_GET_BINDER = "getBinder"
        const val METHOD_SEND_BINDER = "sendBinder"
    }
}
