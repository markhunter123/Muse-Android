package io.github.markhunter123.muse.feature.voice

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.markhunter123.muse.feature.conversation.ComposerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Speech → composer text: the system recognizer streams partial results into the draft, and the
 * final text is sent after a short countdown ([VoiceMachine]) unless the user edits or cancels.
 *
 * Recognizer choice: Tencent Cloud one-shot ASR when [tencentCredentials] is configured (record
 * locally, one signed request, no partials), else the on-device recognizer (API 31+) when the
 * system has it, else a recognition service other than Muse's own stub (when Muse is the default
 * assistant, the system default recognizer *is* that stub), else the RECOGNIZE_SPEECH activity of
 * some app, else the keyboard.
 */
@Stable
class VoiceInput internal constructor(private val context: Context, private val composer: ComposerState, private val scope: CoroutineScope) {
    private val machine = VoiceMachine()
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set
    val active get() = state is VoiceState.Listening || state is VoiceState.Countdown

    internal var onSend: () -> Unit = {}
    internal var requestPermission: () -> Unit = {}
    internal var launchActivity: (Intent) -> Boolean = { false }
    /** Configured Tencent Cloud credentials: set, the one-shot ASR path takes priority over system recognizers. */
    internal var tencentCredentials: (() -> TencentAsr.Credentials?)? = null

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var draft = ""
    private var timer: Job? = null
    private var tencentJob: Job? = null
    @Volatile private var tencentStop = false
    private val tencentActive get() = tencentJob != null

    fun toggle() = if (state is VoiceState.Listening) stop() else start()

    fun start() {
        cancel()
        draft = composer.text
        val tencent = tencentCredentials?.invoke()
        if (tencent != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermission(); return }
            dispatch(VoiceEvent.Start)
            startTencent(tencent)
            return
        }
        val service = serviceRecognizer()
        val canOnDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        if (!canOnDevice && service == null) {
            // No in-app recognizer: a recognizer activity needs no microphone permission of ours.
            if (launchActivity(recognizeIntent())) dispatch(VoiceEvent.Start) else dispatch(VoiceEvent.Start).also { dispatch(VoiceEvent.Error(VoiceError.NoRecognizer)) }
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermission(); return }
        dispatch(VoiceEvent.Start)
        listen(onDevice = canOnDevice, service)
    }

    /** Stops taking audio: the recognizer then delivers the final result (nothing heard yet: just cancels). */
    fun stop() {
        val s = state as? VoiceState.Listening ?: return
        if (tencentActive) { tencentStop = true; return }
        val r = recognizer
        if (r != null && s.text.isNotEmpty()) r.stopListening() else cancel()
    }

    /** Keeps whatever is in the composer and sends nothing. */
    fun cancel() {
        tencentJob?.cancel()
        tencentJob = null
        tencentStop = false
        if (state != VoiceState.Idle) dispatch(VoiceEvent.Cancel)
    }

    /** The user typed in the composer while we were writing to it. */
    fun onUserEdit() = cancel()

    internal fun onPermission(granted: Boolean) {
        if (granted) start() else { dispatch(VoiceEvent.Start); dispatch(VoiceEvent.Error(VoiceError.Permission)) }
    }

    internal fun onActivityResult(resultCode: Int, data: Intent?) {
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        // The recognizer activity covering the panel stopped it (and so cancelled): listen again to take the result.
        if (resultCode == Activity.RESULT_OK && text.isNotBlank()) { dispatch(VoiceEvent.Start); dispatch(VoiceEvent.Final(text)) } else cancel()
    }

    internal fun release() { cancel(); destroyRecognizer() }

    private fun listen(onDevice: Boolean, service: ComponentName?) {
        destroyRecognizer()
        this.onDevice = onDevice
        val r = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context, service)
        }.getOrNull() ?: return dispatch(VoiceEvent.Error(VoiceError.NoRecognizer))
        recognizer = r
        r.setRecognitionListener(Listener(r, service))
        runCatching { r.startListening(recognizeIntent()) }.onFailure { dispatch(VoiceEvent.Error(VoiceError.Other)) }
    }

    private inner class Listener(private val r: SpeechRecognizer, private val service: ComponentName?) : RecognitionListener {
        private val current get() = recognizer === r
        override fun onReadyForSpeech(params: Bundle?) { if (current) dispatch(VoiceEvent.Ready) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (current) dispatch(VoiceEvent.Level(rmsdB)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) { if (current) dispatch(VoiceEvent.Partial(best(partialResults))) }
        override fun onResults(results: Bundle?) { if (current) dispatch(VoiceEvent.Final(best(results))) }
        override fun onError(error: Int) {
            if (!current) return
            val heard = (state as? VoiceState.Listening)?.text.orEmpty()
            // The on-device model may lack the language (or fail outright): retry once with the service recognizer.
            if (onDevice && heard.isEmpty() && service != null && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return listen(onDevice = false, service)
            dispatch(VoiceEvent.Error(VoiceMachine.errorOf(error)))
        }
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun dispatch(e: VoiceEvent) {
        val effect = machine.on(e)
        state = machine.state
        when (val s = state) {
            is VoiceState.Listening -> if (s.text.isNotEmpty()) composer.text = VoiceMachine.join(draft, s.text)
            is VoiceState.Countdown -> if (e !is VoiceEvent.Tick) { composer.text = VoiceMachine.join(draft, s.text); runTimer() }
            is VoiceState.Failed -> if (e !is VoiceEvent.Tick) runTimer()
            VoiceState.Idle -> {}
        }
        when (effect) {
            is VoiceEffect.Send -> onSend()
            VoiceEffect.Release -> destroyRecognizer()
            null -> {}
        }
        if (!active && state !is VoiceState.Failed) { timer?.cancel(); timer = null }
    }

    /** Countdown ticks, or clearing an error after a few seconds. */
    private fun runTimer() {
        timer?.cancel()
        timer = scope.launch {
            if (state is VoiceState.Failed) { delay(ERROR_SHOWN_MS); if (state is VoiceState.Failed) { machine.on(VoiceEvent.Cancel); state = machine.state }; return@launch }
            var last = SystemClock.uptimeMillis()
            while (state is VoiceState.Countdown) {
                delay(TICK_MS)
                val now = SystemClock.uptimeMillis()
                dispatch(VoiceEvent.Tick(now - last))
                last = now
            }
        }
    }

    // ── Tencent one-shot ASR (Muse voice-runtime port): record PCM locally, then one signed request ──

    private fun startTencent(creds: TencentAsr.Credentials) {
        tencentStop = false
        tencentJob = scope.launch {
            val wav = try {
                withContext(Dispatchers.IO) { recordWav() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dispatch(VoiceEvent.Error(VoiceError.Audio))
                return@launch
            }
            if (wav == null || !isActive) return@launch
            val engine = TencentAsr.engineTypeForLanguages(listOf(Locale.getDefault().language))
            val text = try {
                withContext(Dispatchers.IO) { TencentAsr.transcribe(creds, wav, engine) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val network = e is TencentAsr.TencentAsrError && e.code in setOf("NetworkError", "TimeoutError")
                dispatch(VoiceEvent.Error(if (network) VoiceError.Network else VoiceError.Other))
                return@launch
            }
            dispatch(VoiceEvent.Final(text))
        }
    }

    /**
     * Records until [stop] asks ([tencentStop]), the [TencentAsr.MAX_SECONDS] cap hits, or the job is
     * cancelled; null when cancelled. All machine transitions are funneled to the main dispatcher —
     * [dispatch] mutates plain state.
     */
    private suspend fun recordWav(): ByteArray? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 8192))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("AudioRecord unavailable") }
        val pcm = ByteArrayOutputStream()
        // 100 ms of 16 kHz / 16-bit mono.
        val chunk = ByteArray(SAMPLE_RATE / 10 * 2)
        val deadline = SystemClock.elapsedRealtime() + TencentAsr.MAX_SECONDS * 1000L
        var lastLevel = 0L
        try {
            rec.startRecording()
            withContext(Dispatchers.Main) { dispatch(VoiceEvent.Ready) }
            while (!tencentStop && SystemClock.elapsedRealtime() < deadline) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                pcm.write(chunk, 0, n)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevel >= 80) {
                    lastLevel = now
                    withContext(Dispatchers.Main) { dispatch(VoiceEvent.Level(tencentLevel(chunk, n))) }
                }
                coroutineContext.ensureActive()
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        coroutineContext.ensureActive()
        return TencentAsr.wavFromPcm(pcm.toByteArray(), SAMPLE_RATE)
    }

    /** PCM RMS in the -2..10 dB window [VoiceMachine.level] maps to the halo's 0..1. */
    private fun tencentLevel(chunk: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val v = (((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort()).toInt()
            sum += v.toDouble() * v
            i += 2
            count++
        }
        if (count == 0) return -2f
        val rms = sqrt(sum / count)
        val db = if (rms < 1.0) -50.0 else 20 * log10(rms / 32768.0)
        return ((db + 45.0) / 45.0 * 12.0 - 2.0).toFloat().coerceIn(-2f, 10f)
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel(); r.destroy() }
    }

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    /** The system default recognition service unless it is our own stub, else a known good one, else any. */
    private fun serviceRecognizer(): ComponentName? {
        val pm = context.packageManager
        val all = runCatching { pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0) }.getOrDefault(emptyList())
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }.filter { it.packageName != context.packageName }
        if (all.isEmpty()) return null
        val default = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull()
            ?.let(ComponentName::unflattenFromString)
        return all.firstOrNull { it == default } ?: PREFERRED.firstNotNullOfOrNull { p -> all.firstOrNull { it.packageName == p } } ?: all.first()
    }

    companion object {
        private const val TICK_MS = 50L
        private const val ERROR_SHOWN_MS = 4000L
        private const val SAMPLE_RATE = 16_000
        private val PREFERRED = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
    }
}

/** A [VoiceInput] writing into [composer]; [onSend] sends the composer as the Send button would. */
@Composable
fun rememberVoiceInput(composer: ComposerState, onSend: () -> Unit, credentials: () -> TencentAsr.Credentials? = { null }): VoiceInput {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember(composer) { VoiceInput(context, composer, scope) }
    val send by rememberUpdatedState(onSend)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { voice.onPermission(it) }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { voice.onActivityResult(it.resultCode, it.data) }
    voice.onSend = { send() }
    voice.tencentCredentials = credentials
    voice.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    voice.launchActivity = { i -> i.resolveActivity(context.packageManager) != null && runCatching { activity.launch(i) }.isSuccess }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(voice, lifecycle) {
        // Leaving the screen stops listening and any pending auto-send; the text stays in the draft.
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) voice.cancel() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); voice.release() }
    }
    return voice
}
