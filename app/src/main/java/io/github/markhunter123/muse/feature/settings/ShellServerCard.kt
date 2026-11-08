package io.github.markhunter123.muse.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.markhunter123.muse.R
import io.github.markhunter123.muse.core.designsystem.BtnSize
import io.github.markhunter123.muse.core.designsystem.BtnVariant
import io.github.markhunter123.muse.core.designsystem.DButton
import io.github.markhunter123.muse.core.designsystem.DIcon
import io.github.markhunter123.muse.core.designsystem.DotKind
import io.github.markhunter123.muse.core.designsystem.Dsh
import io.github.markhunter123.muse.core.designsystem.DshType
import io.github.markhunter123.muse.core.designsystem.SettingsCard
import io.github.markhunter123.muse.core.designsystem.StateDot
import io.github.markhunter123.muse.core.designsystem.Tag
import io.github.markhunter123.muse.core.designsystem.Tone
import io.github.markhunter123.muse.core.platform.ConnectionStatus
import io.github.markhunter123.muse.core.platform.ShellServerController
import kotlinx.coroutines.launch

/** 内嵌 shell server 卡（手机权限页，实验）：root 一键启动 + 复制 adb 手动命令；[onOpen] 进完整 shell 权限页（配对）。 */
@Composable
fun ShellServerCard(modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val c = Dsh.colors
    val context = LocalContext.current
    val access = remember { shellAccessOf(context) }
    val controller = remember { shellServerControllerOf(context) }
    val s by access.state.collectAsStateWithLifecycle()
    val connection by controller.connection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var starting by remember { mutableStateOf(false) }

    SettingsCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.then(if (onOpen != null) Modifier.clickable { onOpen.invoke() } else Modifier),
            ) {
                StateDot(if (s.ready) DotKind.Done else DotKind.Idle)
                Text(stringResource(R.string.shell_server_title), Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
                Tag(shellStateLabel(context, s), if (s.ready) Tone.Success else Tone.Neutral)
                if (onOpen != null) DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary)
            }
            val connText = when (connection) {
                ConnectionStatus.CONNECTING, ConnectionStatus.STARTING -> stringResource(R.string.shell_server_starting)
                ConnectionStatus.FAILED -> stringResource(R.string.shell_server_start_failed)
                ConnectionStatus.IDLE -> ""
            }
            if (connText.isNotEmpty()) Text(connText, style = DshType.xxs12, color = c.labelTertiary)
            Text(stringResource(R.string.shell_server_root_hint), style = DshType.xxs12, color = c.labelTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DButton(stringResource(R.string.shell_server_start), {
                    starting = true
                    scope.launch { controller.startViaRoot(); starting = false }
                }, enabled = !starting && !s.ready, size = BtnSize.Sm)
                DButton(stringResource(R.string.shell_server_manual_copy), {
                    shellCopyManualCommand(context, controller)
                }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            }
            Text(stringResource(R.string.shell_server_manual_hint), style = DshType.xxs12, color = c.labelTertiary)
        }
    }
}

/** 复制 adb shell 手动启动命令到剪贴板（卡片和 shell 权限页共用）。 */
fun shellCopyManualCommand(context: Context, controller: ShellServerController) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("muse", controller.manualCommand()))
}
