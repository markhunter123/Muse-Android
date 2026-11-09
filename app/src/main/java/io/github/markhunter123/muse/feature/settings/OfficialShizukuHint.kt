package io.github.markhunter123.muse.feature.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.markhunter123.muse.R
import io.github.markhunter123.muse.core.designsystem.DotKind
import io.github.markhunter123.muse.core.designsystem.SettingsCard
import io.github.markhunter123.muse.core.designsystem.SettingsRow
import io.github.markhunter123.muse.core.designsystem.StateDot
import kotlinx.coroutines.flow.map

/**
 * The one-time 设置 → 手机权限 hint for users who migrate from the official Shizuku app: once
 * Muse's own shell service is working and the official app is still installed, tell them once
 * (flag in DataStore) that it can be uninstalled. Pure decision, unit-tested; the card lives in
 * [OfficialShizukuHintCard].
 */
object OfficialShizukuHint {
    /** The official manager the migration hint is about (was ShellAccess.PACKAGE before Task 16). */
    const val OFFICIAL_PACKAGE = "moe.shizuku.privileged.api"

    fun needed(officialInstalled: Boolean, ready: Boolean, shown: Boolean): Boolean =
        officialInstalled && ready && !shown
}

private val Context.hintStore by preferencesDataStore(name = "official_shizuku_hint")
private val SHOWN = booleanPreferencesKey("official_shizuku_hint_shown")

/** The migration hint card in the 手机权限 section, under the shell cards; invisible everywhere else. */
@Composable
fun OfficialShizukuHintCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (_, s) = rememberShellState()
    val shown by context.hintStore.data.map { it[SHOWN] ?: false }.collectAsStateWithLifecycle(initialValue = true)
    var installed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        installed = runCatching { context.packageManager.getLaunchIntentForPackage(OfficialShizukuHint.OFFICIAL_PACKAGE) != null }.getOrDefault(false)
    }
    // One-time = the DataStore flag; once it has appeared it stays visible for the rest of the visit.
    var keepVisible by remember { mutableStateOf(false) }
    val show = keepVisible || OfficialShizukuHint.needed(installed, s.ready, shown)
    LaunchedEffect(show) {
        if (show) {
            keepVisible = true
            context.hintStore.edit { it[SHOWN] = true }
        }
    }
    if (!show) return
    SettingsCard(modifier) {
        SettingsRow(stringResource(R.string.shz_migration_title), stringResource(R.string.shz_migration_body), divider = false,
            leading = { StateDot(DotKind.Done) })
    }
}
