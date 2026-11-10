package io.github.markhunter123.muse.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
import io.github.markhunter123.muse.core.platform.ShellAccess
import io.github.markhunter123.muse.core.platform.ShellPairing
import io.github.markhunter123.muse.core.platform.ShellServerController
import io.github.markhunter123.muse.core.platform.ShellState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShellAccessEntryPoint {
    fun shellAccess(): ShellAccess
    fun shellServerController(): ShellServerController
    fun shellPairing(): ShellPairing
}

fun shellAccessOf(context: Context): ShellAccess =
    EntryPointAccessors.fromApplication(context.applicationContext, ShellAccessEntryPoint::class.java).shellAccess()

fun shellServerControllerOf(context: Context): ShellServerController =
    EntryPointAccessors.fromApplication(context.applicationContext, ShellAccessEntryPoint::class.java).shellServerController()

fun shellPairingOf(context: Context): ShellPairing =
    EntryPointAccessors.fromApplication(context.applicationContext, ShellAccessEntryPoint::class.java).shellPairing()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The live shell state; also re-checks on every resume (the server can stop while the app is away). */
@Composable
fun rememberShellState(): Pair<ShellAccess, ShellState> {
    val context = LocalContext.current
    val access = remember { shellAccessOf(context) }
    val state by access.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access.refresh() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return access to state
}

/** Short status label for rows and Doctor; a too-old server says which API it has. */
fun shellStateLabel(context: Context, s: ShellState): String = when (s) {
    ShellState.NotPaired -> context.getString(R.string.shizuku_missing)
    is ShellState.PairedNotRunning -> context.getString(R.string.shizuku_stopped)
    is ShellState.RunningNoPermission -> context.getString(R.string.shizuku_unauthorized)
    is ShellState.Unsupported -> context.getString(R.string.shizuku_unsupported_v, s.version, ShellAccess.MIN_VERSION)
    ShellState.Ready -> context.getString(R.string.shizuku_ready)
}

/** The next step for the state in one line (Doctor rows, the card's caption); null when ready. */
fun shellStateHint(context: Context, s: ShellState): String? = when (s) {
    ShellState.NotPaired -> context.getString(if (Build.VERSION.SDK_INT < 30) R.string.shz_hint_missing_root else R.string.shz_hint_missing)
    is ShellState.PairedNotRunning -> if (s.reason.isEmpty()) context.getString(R.string.shz_hint_start) else failureText(context, s.reason)
    is ShellState.RunningNoPermission -> context.getString(if (s.denied) R.string.shz_hint_denied else R.string.shz_hint_permission)
    is ShellState.Unsupported -> context.getString(R.string.shz_hint_old)
    ShellState.Ready -> null
}

/** 已知失败原因映射本地化文案（含"重开无线调试"指引，[ShellServerController] 的常量对齐）；没见过的保持原样。 */
private fun failureText(context: Context, reason: String): String = when {
    reason == ShellServerController.REASON_NO_PORT -> context.getString(R.string.shell_fail_no_port)
    reason == ShellServerController.REASON_BINDER_TIMEOUT -> context.getString(R.string.shell_fail_binder)
    reason.startsWith(ShellServerController.REASON_CONNECT_PREFIX) ->
        context.getString(R.string.shell_fail_connect, reason.removePrefix(ShellServerController.REASON_CONNECT_PREFIX))
    else -> context.getString(R.string.shell_server_last_failure, reason)
}

/** 配对步骤按 SDK 分流：Android 10 及以下没有无线调试，指路应用内启动（root / 手动命令）。 */
fun pairingSteps(sdk: Int): List<Int> =
    if (sdk < 30) listOf(R.string.shz_step1_root, R.string.shz_step2_root, R.string.shz_step3_root)
    else listOf(R.string.shz_step1, R.string.shz_step2, R.string.shz_step3)

/**
 * The one-tap fix for the current state (Doctor rows), or null when there is nothing to open:
 * pairing and granting are in-app now, so only "the server won't start" has an external page
 * (developer options, where wireless debugging lives).
 */
fun shellFixIntent(access: ShellAccess, s: ShellState): Intent? = when (s) {
    is ShellState.PairedNotRunning -> access.developerOptionsIntent()
    else -> null
}

/** The card's actions, kept off the composables; no state machine any more — each button is direct. */
class ShellGuideFlow(
    private val access: ShellAccess,
    private val controller: ShellServerController,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    /** 启动按钮的忙碌态（防连点）；结果由 access.state 自己反映，这里不弹窗。 */
    var starting by mutableStateOf(false)
        private set

    fun developerOptions() { runCatching { context.startActivity(access.developerOptionsIntent()) } }
    fun requestPermission() { access.requestPermission() }

    /** 启动嵌入式服务：无线调试自动连接，开了 root 自启再兜底（同 shell 权限页的决策序）。 */
    fun start() {
        if (starting) return
        starting = true
        scope.launch { runCatching { controller.ensureRunning() }; starting = false }
    }
}

@Composable
fun rememberShellGuideFlow(access: ShellAccess): ShellGuideFlow {
    val context = LocalContext.current
    val controller = remember { shellServerControllerOf(context) }
    val scope = rememberCoroutineScope()
    return remember(access) { ShellGuideFlow(access, controller, scope, context) }
}

/** Title + explanation (+ the 3-step mini guide) for the state. [toolTitle]: the waiting tool, JIT only. */
@Composable
fun ColumnScope.ShellGuideBody(s: ShellState, toolTitle: String?, flow: ShellGuideFlow) {
    val c = Dsh.colors
    val title = when (s) {
        ShellState.NotPaired -> stringResource(R.string.shz_title_install)
        is ShellState.PairedNotRunning -> stringResource(R.string.shz_title_stopped)
        is ShellState.RunningNoPermission -> stringResource(R.string.shz_title_permission)
        is ShellState.Unsupported -> stringResource(R.string.shz_title_old)
        ShellState.Ready -> stringResource(R.string.shz_title_ready)
    }
    Text(title, style = DshType.s14m.copy(lineHeight = DshType.base16.lineHeight), color = c.labelPrimary)
    if (toolTitle != null && !s.ready) Text(stringResource(R.string.shz_jit_body, toolTitle), style = DshType.xs13, color = c.labelSecondary)
    val body = when (s) {
        ShellState.NotPaired -> stringResource(R.string.shz_body_install)
        is ShellState.PairedNotRunning -> stringResource(R.string.shz_body_stopped)
        is ShellState.RunningNoPermission -> stringResource(if (s.denied) R.string.shz_body_denied else R.string.shz_body_permission)
        is ShellState.Unsupported -> stringResource(R.string.shz_body_old)
        ShellState.Ready -> stringResource(if (toolTitle != null) R.string.shz_body_resuming else R.string.shz_body_ready)
    }
    Text(body, style = DshType.xs13, color = if (toolTitle != null) c.labelTertiary else c.labelSecondary)
    if (s == ShellState.NotPaired) {
        pairingSteps(Build.VERSION.SDK_INT).forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text("${i + 1}", Modifier.width(16.dp), style = DshType.xs13m, color = c.labelTertiary)
                Text(stringResource(step), style = DshType.xs13, color = c.labelSecondary)
            }
        }
    }
}

/** Buttons for the state; [onDecline] (不用了) is shown when the caller has a fallback to go to. */
@Composable
fun ShellGuideActions(s: ShellState, flow: ShellGuideFlow, onDecline: (() -> Unit)?, enabled: Boolean = true, size: BtnSize = BtnSize.Md) {
    if (onDecline != null && !s.ready) DButton(stringResource(R.string.shz_decline), onDecline, variant = BtnVariant.Outline, enabled = enabled, size = size)
    when (s) {
        ShellState.NotPaired ->
            DButton(stringResource(R.string.shz_dev_options), flow::developerOptions, variant = BtnVariant.Outline, enabled = enabled, size = size)
        is ShellState.PairedNotRunning -> {
            DButton(stringResource(R.string.shz_dev_options), flow::developerOptions, variant = BtnVariant.Outline, enabled = enabled, size = size)
            DButton(stringResource(if (flow.starting) R.string.shell_server_starting else R.string.shell_server_start), flow::start,
                enabled = enabled && !flow.starting, size = size)
        }
        is ShellState.RunningNoPermission ->
            DButton(stringResource(R.string.shz_grant), flow::requestPermission, enabled = enabled, size = size)
        ShellState.Ready, is ShellState.Unsupported -> {}
    }
}

/** Standalone shell card for 设置 → 手机权限 and the onboarding 增强能力 step: status and guidance. */
@Composable
fun ShellAccessCard(modifier: Modifier = Modifier, onDecline: (() -> Unit)? = null) {
    val c = Dsh.colors
    val (access, s) = rememberShellState()
    val flow = rememberShellGuideFlow(access)
    SettingsCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateDot(when (s) { ShellState.Ready -> DotKind.Done; is ShellState.Unsupported -> DotKind.Error; ShellState.NotPaired -> DotKind.Idle; else -> DotKind.Warn })
                Text(stringResource(R.string.shell_server_title), Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
                Tag(shellStateLabel(LocalContext.current, s), when (s) { ShellState.Ready -> Tone.Success; is ShellState.Unsupported -> Tone.Warn; else -> Tone.Neutral })
            }
            ShellGuideBody(s, null, flow)
        }
        if (!s.ready) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ShellGuideActions(s, flow, onDecline, size = BtnSize.Sm)
        }
    }
}
