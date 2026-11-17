package io.github.markhunter123.muse.core.platform

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Activity starts from a service (the floating ball and its capsule). A plain startActivity from
 * a service is a background activity start: AOSP lets it through for an app with a visible overlay
 * window, but HyperOS / MIUI ("后台弹出界面") and Android 14+ can still drop it silently, so the tap
 * would do nothing. A PendingIntent sent with the sender's background-start privilege
 * ([sendOptions]) is the supported path; a refused send falls back to startActivity.
 */
object ActivityStarts {
    /** Our own request code for these one-shot sends (distinct from notification PendingIntents, whose extras it must not touch). */
    private const val REQUEST = 0x0BA11

    /** Grants the sender's background-activity-start privilege to the PendingIntent (Android 14+ requires the opt-in). */
    fun sendOptions(): Bundle? = if (Build.VERSION.SDK_INT >= 34)
        @Suppress("DEPRECATION")
        ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null

    fun start(context: Context, intent: Intent) {
        val i = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // MUSE: MagicOS 对后台服务的 PendingIntent.send 假成功——静默丢弃启动且无异常，fallback
        // 永远不触发。改为 startActivity 优先（app 有可见悬浮窗 + 球刚收到用户触摸，符合 BAL
        // 条件，MagicOS 放行这条）；被拒（抛异常）再退回带后台权限的 PendingIntent。
        // MagicOS 还会对 startActivity 本身假成功，所以调用方必须校验"真的到前台了吗"
        //（FloatingBallService.launchUi），没到再走 [viaPendingIntent]。
        val started = runCatching { context.startActivity(i) }
        if (started.isSuccess) return
        Log.w("ActivityStarts", "startActivity refused; trying PendingIntent", started.exceptionOrNull())
        val sent = runCatching { viaPendingIntent(context, intent) }
        if (sent.isFailure) Log.w("ActivityStarts", "PendingIntent start refused", sent.exceptionOrNull())
    }

    /** The AOSP-sanctioned background path: a send carrying the sender's background-start privilege.
     *  MUSE: targetSdk 35+ 还要求**创建方**在 getActivity 时就 opt-in（API 34+ 的第 5 参 options），
     *  否则 Android 15/16 会把这个 PendingIntent 当普通后台启动丢弃——发送方的 opt-in 形同虚设。 */
    fun viaPendingIntent(context: Context, intent: Intent): Boolean =
        runCatching {
            val creator = if (Build.VERSION.SDK_INT >= 34)
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle() else null
            PendingIntent.getActivity(context, REQUEST, Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, creator)
                .send(context, 0, null, null, null, null, sendOptions())
        }.isSuccess
}
