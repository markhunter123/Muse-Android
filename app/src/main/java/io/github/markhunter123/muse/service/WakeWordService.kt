package io.github.markhunter123.muse.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import io.github.markhunter123.muse.R
import io.github.markhunter123.muse.core.data.SettingsRepository
import io.github.markhunter123.muse.core.platform.ActivityStarts
import io.github.markhunter123.muse.core.platform.Notifications
import io.github.markhunter123.muse.feature.quick.QuickActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.KaldiRecognizer
import org.vosk.Model
import javax.inject.Inject
import kotlin.math.max

/**
 * 语音唤醒 (Vosk, fully on-device): while the floating ball shows, an AudioRecord loop feeds a
 * grammar-restricted KaldiRecognizer watching for 「你好 缪斯」; a hit opens the quick panel
 * already listening ([QuickActivity.EXTRA_VOICE], the ball's long-press path).
 *
 * Started by the settings toggle and by [FloatingBallService]'s start; it watches the settings
 * every cycle and stops itself unless both 语音唤醒 and 悬浮球 are on. The mic pauses while Muse's
 * own UI is in front (the panel may hold the recognizer) and after a wake until the panel is gone
 * again. The model comes from [WakeModel]: until it is ready the loop just idles — it never loads
 * a recognizer, so nothing listens and no battery is spent on a half-set feature.
 */
@AndroidEntryPoint
class WakeWordService : Service() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var wakeModel: WakeModel

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = true
    @Volatile private var inFront = false
    private val appInFront = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { inFront = true }
        override fun onStop(owner: LifecycleOwner) { inFront = false }
    }

    override fun onCreate() {
        super.onCreate()
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(getString(R.string.wake_running)).setOngoing(true).setSilent(true)
            .build()
        // The microphone FGS type exists from API 30; Android 10 takes the plain form.
        if (Build.VERSION.SDK_INT >= 30) startForeground(Notifications.ID_WAKE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(Notifications.ID_WAKE, notification)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appInFront)
        scope.launch { listen() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appInFront)
        scope.cancel()
        super.onDestroy()
    }

    /** The mic loop: wait for the model → listen → wake → hand the mic to the panel → wait for it to go → repeat. */
    private suspend fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        var model: Model? = null
        try {
            while (running) {
                val s = settings.current()
                if (!s.wakeWord || !s.floatingBall) { stopSelf(); return }
                // A model that arrived (or vanished) mid-cycle reloads on the next listen.
                if (inFront || wakeModel.dirForRecognizer() == null) { model?.release(); model = null; delay(1000); continue }
                // The recognizer loads the model once (a couple of seconds); keep it across listens.
                model = model ?: runCatching { Model(wakeModel.dirForRecognizer()!!.absolutePath) }.getOrNull()
                if (model == null) { delay(5000); continue }
                if (listenOnce(model)) {
                    openVoice()
                    // The panel's recognizer holds the mic now; re-arm once its UI is gone again.
                    delay(2000)
                    while (running && inFront) delay(500)
                }
            }
        } finally {
            model?.release()
        }
    }

    /** One listen with [model]; true when the phrase came through. Returns early (false) on stop or Muse coming to front. */
    private fun listenOnce(model: Model): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = KaldiRecognizer(model, SAMPLE_RATE.toFloat(), """["$PHRASE"]""")
        var audio: AudioRecord? = null
        try {
            // A permission revoked mid-run (or a busy mic) lands here; no record, no listen, try again next cycle.
            audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, SAMPLE_RATE * 2)).also { if (it.state != AudioRecord.STATE_INITIALIZED) return false; it.startRecording() }
            val buf = ByteArray(SAMPLE_RATE / 10 * 2) // 100 ms of 16-bit mono
            while (running && !inFront) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (!rec.acceptWaveform(buf, n)) continue
                val text = runCatching { Json.parseToJsonElement(rec.result).jsonObject["text"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
                if (PHRASE.replace(" ", "") in text.replace(" ", "")) return true
            }
            return false
        } catch (e: SecurityException) {
            return false
        } finally {
            runCatching { audio?.stop() }
            audio?.release()
            rec.close()
        }
    }

    private fun openVoice() = ActivityStarts.start(this, Intent(this, QuickActivity::class.java).putExtra(QuickActivity.EXTRA_VOICE, true))

    companion object {
        private const val SAMPLE_RATE = 16_000
        /** The only phrase the grammar knows; result text is matched with spaces stripped. */
        const val PHRASE = "你好 缪斯"

        /** The settings toggle and FloatingBallService's start are the two callers; no mic permission, no listener. */
        fun setEnabled(context: Context, on: Boolean) {
            val i = Intent(context, WakeWordService::class.java)
            val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (on && mic) context.startForegroundService(i) else context.stopService(i)
        }
    }
}
