package io.github.markhunter123.muse.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The wake-word model (vosk-model-small-cn-0.22, ~42 MB) in filesDir: downloaded from alphacephei.com
 * and unzipped on first enable, its state watched by the settings row. The download runs in this
 * singleton's own scope, so leaving the settings page does not cancel it.
 *
 * alphacephei.com publishes no checksum for the model, so the download is verified by size only: a
 * 30 MB floor catches an HTML error page or a truncated reply, but it is not an integrity proof —
 * the sha256 bar of core/update/UpdateFetch is deliberately not reached for here.
 */
@Singleton
class WakeModel @Inject constructor(@ApplicationContext context: Context) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val got: Long, val total: Long) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    /** The zip unpacks with a single top directory named after the model (fixed upstream naming). */
    private val base = File(context.filesDir, MODEL_DIR)
    private val dir get() = File(base, MODEL_NAME)
    private val cache = context.cacheDir

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(if (ready()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The recognizer needs am/final.mdl; anything less is a broken or half-unzipped model. */
    fun ready(): Boolean = File(dir, "am/final.mdl").exists()

    /** The directory a vosk [org.vosk.Model] can load from, or null while no usable model is in place. */
    fun dirForRecognizer(): File? = if (ready()) dir else null

    /** Starts the download unless it is already running or done; retrying a failed one is a call away. */
    fun ensureDownload() {
        if (ready() || job?.isActive == true) return
        job = scope.launch {
            try {
                _state.value = State.Downloading(0, 0)
                val zip = File(cache, "$MODEL_NAME.zip")
                download(zip)
                unzip(zip)
                zip.delete()
                _state.value = if (ready()) State.Ready else State.Failed("model files missing after unzip")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                base.deleteRecursively()
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Streams the zip into cache; a Content-Length below the floor fails before any bytes are read. */
    private fun download(out: File) {
        val c = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.connect()
            val total = c.contentLengthLong
            if (total in 1 until MIN_BYTES) throw IOException("response too small: $total bytes")
            val tmp = File(cache, out.name + ".part")
            var got = 0L
            tmp.outputStream().use { o ->
                c.inputStream.use { i ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        got += n
                        _state.value = State.Downloading(got, total)
                    }
                }
            }
            if (got < MIN_BYTES) throw IOException("download truncated: $got bytes")
            if (!tmp.renameTo(out)) throw IOException("rename failed")
        } finally {
            c.disconnect()
        }
    }

    /** Unzips [zip] into [base]; entries may not escape it (zip-slip). */
    private fun unzip(zip: File) {
        base.deleteRecursively()
        base.mkdirs()
        val root = base.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(zip.inputStream().buffered())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(base, e.name)
                if (!f.canonicalPath.startsWith(root)) throw IOException("bad entry: ${e.name}")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                }
                z.closeEntry()
            }
        }
    }

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        private const val MODEL_DIR = "wake-model"
        private const val MODEL_NAME = "vosk-model-small-cn-0.22"
        /** The real zip is ~42 MB; anything below is an error page or a truncated reply. */
        private const val MIN_BYTES = 30_000_000L
    }
}
