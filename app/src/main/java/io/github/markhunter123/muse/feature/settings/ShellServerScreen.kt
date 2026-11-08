package io.github.markhunter123.muse.feature.settings

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.markhunter123.muse.R
import io.github.markhunter123.muse.core.designsystem.BtnSize
import io.github.markhunter123.muse.core.designsystem.BtnVariant
import io.github.markhunter123.muse.core.designsystem.DButton
import io.github.markhunter123.muse.core.designsystem.DotKind
import io.github.markhunter123.muse.core.designsystem.Dsh
import io.github.markhunter123.muse.core.designsystem.DshType
import io.github.markhunter123.muse.core.designsystem.SettingsCard
import io.github.markhunter123.muse.core.designsystem.StateDot
import io.github.markhunter123.muse.core.designsystem.Tag
import io.github.markhunter123.muse.core.designsystem.Tone
import io.github.markhunter123.muse.core.platform.ConnectionStatus
import io.github.markhunter123.muse.core.platform.PairingStep
import io.github.markhunter123.muse.core.platform.ShellState
import moe.shizuku.server.muse.PairFailure
import kotlinx.coroutines.launch

/** shell 权限页（手机权限 → shell 权限卡进入）：状态行、无线调试检测、6 位码配对；Android 10 只剩 root/手动（Review Focus 4）。 */
@Composable
fun ShellServerScreen(onBack: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val access = remember { shellAccessOf(context) }
    val controller = remember { shellServerControllerOf(context) }
    val pairing = remember { shellPairingOf(context) }
    val s by access.state.collectAsStateWithLifecycle()
    val connection by controller.connection.collectAsStateWithLifecycle()
    val step by pairing.step.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 无线调试开关随回前台刷新（用户去开发者选项开了再回来）
    var wadbTick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) wadbTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val wadbOn = remember(wadbTick) { wadbEnabled(context) }

    SettingsPage(stringResource(R.string.shell_server_title), onBack) {
        // 状态行 + 最近一次失败
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateDot(if (s.ready) DotKind.Done else DotKind.Idle)
                    Text(stringResource(R.string.shell_server_title), Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
                    Tag(shellStateLabel(context, s), if (s.ready) Tone.Success else Tone.Neutral)
                }
                val connText = when (connection) {
                    ConnectionStatus.CONNECTING, ConnectionStatus.STARTING -> stringResource(R.string.shell_server_starting)
                    ConnectionStatus.FAILED -> stringResource(R.string.shell_server_start_failed)
                    ConnectionStatus.IDLE -> ""
                }
                if (connText.isNotEmpty()) Text(connText, style = DshType.xxs12, color = c.labelTertiary)
                (s as? ShellState.PairedNotRunning)?.takeIf { it.reason.isNotEmpty() }?.let {
                    Text(
                        stringResource(R.string.shell_server_last_failure, it.reason),
                        style = DshType.xxs12, color = c.labelTertiary,
                    )
                }
            }
        }

        // 无线调试 + 6 位码配对（Android 10 没有无线调试配对，整块隐藏）
        if (Build.VERSION.SDK_INT >= 30) {
            SettingsCard(Modifier.padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StateDot(if (wadbOn) DotKind.Done else DotKind.Idle)
                        Text(stringResource(R.string.shell_wadb_title), Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
                        Tag(
                            stringResource(if (wadbOn) R.string.shell_wadb_on else R.string.shell_wadb_off),
                            if (wadbOn) Tone.Success else Tone.Neutral,
                        )
                    }
                    if (!wadbOn) Text(stringResource(R.string.shell_wadb_hint), style = DshType.xxs12, color = c.labelTertiary)
                    when (val st = step) {
                        PairingStep.Idle -> DButton(stringResource(R.string.shell_pairing_start), {
                            scope.launch { pairing.start() }
                        }, size = BtnSize.Sm)
                        PairingStep.Discovering -> Text(stringResource(R.string.shell_pairing_discovering), style = DshType.xxs12, color = c.labelTertiary)
                        PairingStep.WaitingCode -> {
                            Text(stringResource(R.string.shell_pairing_waiting), style = DshType.xxs12, color = c.labelTertiary)
                            var code by remember { mutableStateOf("") }
                            OutlinedTextField(
                                value = code,
                                onValueChange = { v -> if (v.length <= 6 && v.all { it in '0'..'9' }) code = v },
                                label = { Text(stringResource(R.string.shell_pairing_code_hint)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DButton(stringResource(R.string.shell_pairing_submit), {
                                scope.launch { pairing.submitCode(code); code = "" }
                            }, enabled = code.length == 6, size = BtnSize.Sm)
                        }
                        PairingStep.Paired -> Text(stringResource(R.string.shell_pairing_paired), style = DshType.xs13, color = c.labelSecondary)
                        is PairingStep.Failed -> {
                            Text(
                                stringResource(
                                    when (st.reason) {
                                        PairFailure.WRONG_CODE -> R.string.shell_pairing_fail_wrong
                                        PairFailure.TIMEOUT -> R.string.shell_pairing_fail_timeout
                                        PairFailure.DISCOVERY -> R.string.shell_pairing_fail_discovery
                                    }
                                ),
                                style = DshType.xxs12, color = c.labelTertiary,
                            )
                            DButton(stringResource(R.string.shell_pairing_again), {
                                scope.launch { pairing.restart() }
                            }, size = BtnSize.Sm)
                        }
                    }
                }
            }
        }

        // root 启动 + 手动命令（Android 10 的唯一路径）
        SettingsCard(Modifier.padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.shell_server_root_hint), style = DshType.xxs12, color = c.labelTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var starting by remember { mutableStateOf(false) }
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
}

/** Settings.Global 的 adb_wifi_enabled（全局设置无需权限；旧版本或读不到按未开启处理）。 */
private fun wadbEnabled(context: Context): Boolean =
    runCatching { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
