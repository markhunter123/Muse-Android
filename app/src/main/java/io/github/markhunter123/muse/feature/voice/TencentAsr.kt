package io.github.markhunter123.muse.feature.voice

import io.github.markhunter123.muse.core.data.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tencent Cloud one-shot recognition — the `SentenceRecognition` API 3.0 action, signed with
 * TC3-HMAC-SHA256. Port of Muse's packages/voice-runtime `tencent-asr.ts` (same payload, same
 * signature chain): one HTTPS POST through HttpURLConnection, so no new dependency.
 *
 * API: https://cloud.tencent.com/document/product/1093/35646
 * Signature: https://cloud.tencent.com/document/api/1093/35640
 */
object TencentAsr {
    private const val ENDPOINT = "https://asr.tencentcloudapi.com"
    private const val HOST = "asr.tencentcloudapi.com"
    private const val SERVICE = "asr"
    private const val ACTION = "SentenceRecognition"
    private const val VERSION = "2019-06-14"
    private const val TIMEOUT_MS = 60_000

    /** Audio duration the one-shot API accepts, in seconds (60 s of 16 kHz/16-bit mono ≈ 2.56 MB base64). */
    const val MAX_SECONDS = 60
    private const val MAX_PCM_BYTES = MAX_SECONDS * 32_000

    class Credentials(val secretId: String, val secretKey: String)

    /** Carries the vendor's error code so callers can classify a failure without matching on prose. */
    class TencentAsrError(val code: String, message: String) : Exception(message)

    /**
     * Recognizes one WAV clip (16 kHz / 16-bit / mono) and returns its trimmed text. Blocking;
     * callers run it off the main thread. Cancellation surfaces as [kotlinx.coroutines.CancellationException],
     * never as a recognition failure.
     */
    fun transcribe(credentials: Credentials, wav: ByteArray, engineType: String): String {
        require(wav.size <= MAX_PCM_BYTES) { "audio longer than $MAX_SECONDS s" }
        val payload = buildJsonObject {
            put("EngSerViceType", engineType)
            put("SourceType", 1)
            put("VoiceFormat", "wav")
            // 0 keeps the service's punctuation (2 would strip it); 1 lands numbers as digits.
            put("Data", Base64.getEncoder().encodeToString(wav))
            put("DataLen", wav.size)
            put("FilterPunc", 0)
            put("ConvertNumMode", 1)
        }.toString()
        val timestamp = System.currentTimeMillis() / 1000
        val (canonicalRequest, signedHeaders) = buildCanonicalRequest(
            listOf(
                "content-type" to "application/json; charset=utf-8",
                "host" to HOST,
                "x-tc-action" to ACTION.lowercase(Locale.US),
            ),
            payload,
        )
        val authorization = authorizationHeader(credentials, SERVICE, timestamp, canonicalRequest, signedHeaders)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-TC-Action", ACTION)
            setRequestProperty("X-TC-Version", VERSION)
            setRequestProperty("X-TC-Timestamp", timestamp.toString())
            setRequestProperty("Authorization", authorization)
        }
        val body: String = try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        } catch (e: Exception) {
            throw TencentAsrError("NetworkError", e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
        return parse(body, conn.responseCode)
    }

    private fun parse(body: String, status: Int): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw TencentAsrError("InvalidResponse", "Unexpected ASR response (HTTP $status)") }
        val response = root["Response"]?.jsonObject
        val error = response?.get("Error")?.jsonObject
        if (error != null) {
            throw TencentAsrError(
                error["Code"]?.jsonPrimitive?.content ?: "Unknown",
                error["Message"]?.jsonPrimitive?.content ?: "Unknown ASR error",
            )
        }
        val result = response?.get("Result")?.jsonPrimitive?.content
            ?: throw TencentAsrError("InvalidResponse", "ASR response missing Result (HTTP $status)")
        return result.trim()
    }

    // ── TC3-HMAC-SHA256 (pure functions, unit-tested against Tencent's published vector) ──

    /**
     * The TC3 canonical request: headers lowercased and sorted (the signature covers them in that
     * form), each as `name:value\n`, then the blank line, the signed header list and the payload hash.
     */
    internal fun buildCanonicalRequest(headers: List<Pair<String, String>>, payload: String): Pair<String, String> {
        val sorted = headers.map { (name, value) -> name.lowercase(Locale.US) to value.trim() }
            .sortedBy { (name, _) -> name }
        val canonicalHeaders = sorted.joinToString("") { "${it.first}:${it.second}\n" }
        val signedHeaders = sorted.joinToString(";") { it.first }
        val canonicalRequest = listOf("POST", "/", "", canonicalHeaders, signedHeaders, sha256Hex(payload)).joinToString("\n")
        return canonicalRequest to signedHeaders
    }

    internal fun authorizationHeader(
        credentials: Credentials,
        service: String,
        timestamp: Long,
        canonicalRequest: String,
        signedHeaders: String,
    ): String {
        val scope = credentialScope(timestamp, service)
        val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$scope\n${sha256Hex(canonicalRequest)}"
        // The documented key derivation: date, then service, then the fixed `tc3_request` terminator.
        val kDate = hmacSha256("TC3${credentials.secretKey}".toByteArray(), tc3Date(timestamp))
        val kService = hmacSha256(kDate, service)
        val kSigning = hmacSha256(kService, "tc3_request")
        val signature = hex(hmacSha256(kSigning, stringToSign))
        return "TC3-HMAC-SHA256 Credential=${credentials.secretId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    /** `YYYY-MM-DD` in UTC — the signature scope's date, not the local one. */
    internal fun tc3Date(timestamp: Long): String =
        java.time.Instant.ofEpochSecond(timestamp).toString().slice(0..9)

    internal fun credentialScope(timestamp: Long, service: String): String = "${tc3Date(timestamp)}/$service/tc3_request"

    internal fun sha256Hex(data: String): String = hex(
        MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8)),
    )

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // ── engine choice + audio wrapping ──

    /** Non-phone engine ids offered by `SentenceRecognition`, by language code. */
    private val ENGINE_BY_LANGUAGE = mapOf("zh" to "16k_zh", "en" to "16k_en", "ja" to "16k_ja", "ko" to "16k_ko")

    /**
     * `16k_zh-PY` recognizes Mandarin, English and Cantonese in one pass — what a bilingual speaker
     * actually produces. A single selected language uses its own engine; anything else falls back
     * to Mandarin (same rules as Muse's `engineTypeForLanguages`).
     */
    fun engineTypeForLanguages(languages: List<String>): String {
        if (languages.size > 1) return "16k_zh-PY"
        return ENGINE_BY_LANGUAGE[languages.firstOrNull()] ?: "16k_zh"
    }

    /** Wraps raw PCM (16 kHz / 16-bit / mono) in a minimal 44-byte-header WAV container. */
    fun wavFromPcm(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(44 + pcm.size)
        fun le32(offset: Int, value: Int) {
            out[offset] = value.toByte(); out[offset + 1] = (value shr 8).toByte(); out[offset + 2] = (value shr 16).toByte(); out[offset + 3] = (value shr 24).toByte()
        }
        fun le16(offset: Int, value: Int) { out[offset] = value.toByte(); out[offset + 1] = (value shr 8).toByte() }
        "RIFF".toByteArray().copyInto(out, 0)
        le32(4, 36 + pcm.size)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        le32(16, 16)
        le16(20, 1) // PCM
        le16(22, channels)
        le32(24, sampleRate)
        le32(28, byteRate)
        le16(32, blockAlign)
        le16(34, bitsPerSample)
        "data".toByteArray().copyInto(out, 36)
        le32(40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }
}

/**
 * The Tencent Cloud credential pair for voice input, in [SecretStore] named slots (AES-GCM,
 * noBackupFilesDir — device-only, like the provider keys). Half a pair cannot sign anything,
 * so it reads as none (the same rule Muse's voice credential store applies).
 */
@Singleton
class TencentAsrCredentialsStore @Inject constructor(private val secrets: SecretStore) {
    fun read(): TencentAsr.Credentials? {
        val id = secrets.read(ID) ?: return null
        val key = secrets.read(KEY) ?: return null
        if (id.isBlank() || key.isBlank()) return null
        return TencentAsr.Credentials(id, key)
    }

    fun write(id: String, key: String) {
        secrets.write(ID, id.trim())
        secrets.write(KEY, key.trim())
    }

    fun clear() {
        secrets.clear(ID)
        secrets.clear(KEY)
    }

    /** Which halves are stored; a pair the UI shows as configured only when both are. */
    fun status(): Pair<Boolean, Boolean> = (secrets.read(ID)?.isNotBlank() == true) to (secrets.read(KEY)?.isNotBlank() == true)

    companion object {
        const val ID = "voice-tencent-id"
        const val KEY = "voice-tencent-key"
    }
}
