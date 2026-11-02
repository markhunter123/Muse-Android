package io.github.markhunter123.muse

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.markhunter123.muse.core.capability.AgentScreen
import io.github.markhunter123.muse.core.data.SettingsRepository
import io.github.markhunter123.muse.core.platform.AppLanguage
import io.github.markhunter123.muse.core.platform.Notifications
import io.github.markhunter123.muse.core.trigger.TriggerEvents
import io.github.markhunter123.muse.core.trigger.TriggerHub
import io.github.markhunter123.muse.service.FloatingBallService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DshApp : Application() {
    @Inject lateinit var triggers: dagger.Lazy<TriggerHub>
    @Inject lateinit var settings: dagger.Lazy<SettingsRepository>
    @Inject lateinit var secrets: dagger.Lazy<io.github.markhunter123.muse.core.data.SecretStore>
    @Inject lateinit var agentScreen: dagger.Lazy<AgentScreen>
    @Inject lateinit var updater: dagger.Lazy<io.github.markhunter123.muse.core.update.AppUpdater>

    override fun onCreate() {
        super.onCreate()
        // Every process (the :engine one posts its notification too): the in-app language choice.
        AppLanguage.applyToProcess(this)
        if (!isMainProcess()) return
        Notifications.createChannels(this)
        importDebugKey()
        TriggerEvents.init(this)
        // Known update, post-install cleanup, the daily 自动检查更新 (network work runs in the background).
        runCatching { updater.get().start() }.onFailure { android.util.Log.e("DshApp", "updater start failed", it) }
        // Startup work must never crash the process (a bad stored rule used to kill every app start).
        val guard = CoroutineExceptionHandler { _, e -> android.util.Log.e("DshApp", "startup task failed", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + guard).launch {
            // Import :engine crash records (ApplicationExitInfo / tombstones) from earlier runs.
            runCatching { io.github.markhunter123.muse.core.engine.EngineDiagnostics.collectExitInfo(this@DshApp) }
            runCatching { triggers.get().sync() }.onFailure { android.util.Log.e("DshApp", "automation sync failed", it) }
            if (settings.get().current().floatingBall) runCatching { FloatingBallService.setEnabled(this@DshApp, true) }
            runCatching { agentScreen.get().restoreLeftoverMutes() }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android 10–12: a configuration change resets the process locale to the system's.
        AppLanguage.applyToProcess(this)
    }

    /** Debug builds only: device tests provision the key with `run-as <pkg> sh -c 'cat > files/debug.key'`. */
    private fun importDebugKey() {
        if (!BuildConfig.DEBUG) return
        val f = java.io.File(filesDir, "debug.key")
        if (!f.exists()) return
        f.readText().trim().takeIf { it.isNotEmpty() }?.let { secrets.get().write(it) }
        f.delete()
    }

    private fun isMainProcess() = getProcessName() == packageName
}
