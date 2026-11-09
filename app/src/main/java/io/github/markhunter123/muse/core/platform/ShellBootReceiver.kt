package io.github.markhunter123.muse.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** BOOT_COMPLETED → 试一次嵌入式 shell server 自启（已配对才动作）；失败静默，[ShellServerController] 下次回前台重试。 */
@AndroidEntryPoint
class ShellBootReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: ShellServerController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { controller.tryAutoStart() } finally { pending.finish() }
        }
    }
}
