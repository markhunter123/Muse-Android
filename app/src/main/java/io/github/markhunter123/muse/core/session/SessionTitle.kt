package io.github.markhunter123.muse.core.session

import io.github.markhunter123.muse.core.data.SecretStore
import io.github.markhunter123.muse.core.provider.HARNESS_USER_AGENT
import io.github.markhunter123.muse.core.provider.ModelChoice
import io.github.markhunter123.muse.core.provider.appRequestHeaders
import io.github.markhunter123.muse.core.provider.appUserAgent
import io.github.markhunter123.muse.core.provider.headerRowsOf
import io.github.markhunter123.muse.core.provider.resolveHeaderRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URL

/*
 * 自动会话标题: the engine never names a conversation (no `session/title` anywhere upstream), so
 * the first message showed forever as its own truncation. The app fills the gap: after the user
 * sends a conversation's first message, one small chat request to the active model asks for a
 * short title, and `SessionStore.rename` applies it while the turn still runs.
 *
 * The request goes to the same route the engine serves the conversation on (SettingsRepository
 * choice): a third-party provider with its stored key, headers and session header, or official
 * DeepSeek with the API key. A DeepSeek account login's credential lives only inside the engine
 * (AccountRepository mirrors a safe view), so those conversations keep the truncation — as does
 * any failed or empty answer, silently: a title is never worth a notice.
 */

object SessionTitle {
    /** Official DeepSeek chat base (no `/v1`; paths append to the origin, as the doctor and engine spell it). */
    private const val DEEPSEEK_BASE = "https://api.deepseek.com"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 45_000
    /** Whole-answer budget: thinking models reason a while before the title arrives. */
    private const val DEADLINE_MS = 30_000
    private const val MAX_TOKENS = 512

    /** Title request: short, the message's own language, the answer is the title and nothing else. */
    private const val PROMPT = "你是会话标题生成器。根据用户的这条消息，给这段对话起一个标题：" +
        "不超过16个字；用消息的主要语言；概括主题或意图，不要逐字照抄；" +
        "直接输出标题本身，不要引号、句号、前缀或任何解释。"

    /**
     * One title for [firstMessage] on the conversation's route, or null (keep the truncation)
     * when no app-side credential can serve it or the request fails. Runs on Dispatchers.IO.
     */
    suspend fun generate(choice: ModelChoice, secrets: SecretStore, version: String, firstMessage: String): String? {
        if (firstMessage.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val (baseUrl, key, headers) = routeOf(choice, secrets, version) ?: return@withContext null
            request(baseUrl, key, choice.model, headers, firstMessage, lowEffort = choice.isDeepSeek)
        }
    }

    /** Endpoint, credential and headers of [choice]; null when nothing app-side can authenticate it. */
    private fun routeOf(choice: ModelChoice, secrets: SecretStore, version: String): Triple<String, String?, Map<String, String>>? {
        val p = choice.provider
        if (p != null) {
            val headers = appRequestHeaders(resolveHeaderRows(headerRowsOf(p), p) { secrets.read(it) }, p.sessionHeader, appUserAgent(version))
            return Triple(p.baseUrl, p.takeIf { it.hasKey }?.let { secrets.read(it.secretName) }, headers)
        }
        // Account logins keep their token inside the engine; only a stored API key can call DeepSeek from the app.
        val key = secrets.read() ?: return null
        return Triple(DEEPSEEK_BASE, key, emptyMap())
    }

    /** The chat request (streaming, like the engine sends; a server that ignores `stream` is read as one JSON object). */
    private fun body(model: String, firstMessage: String, lowEffort: Boolean): String = buildJsonObject {
        put("model", model)
        put("stream", true)
        // Room for a thinking model's reasoning before the title (deepseek-flash thinks by default);
        // low effort keeps the official route's wait short (third-party gateways vary, so it is DeepSeek-only).
        put("max_tokens", MAX_TOKENS)
        if (lowEffort) put("reasoning_effort", "low")
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", PROMPT) }
            addJsonObject { put("role", "user"); put("content", firstMessage.take(500)) }
        }
    }.toString()

    /** One streaming chat answer read for its text; any failure is null, never a throw. */
    private fun request(baseUrl: String, key: String?, model: String, headers: Map<String, String>, firstMessage: String, lowEffort: Boolean): String? = try {
        val c = URL(baseUrl.trim().trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = READ_TIMEOUT_MS
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("accept", "text/event-stream")
            c.setRequestProperty("user-agent", HARNESS_USER_AGENT)
            // The provider's extra headers (and app User-Agent) last, so they override the harness one.
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (!key.isNullOrBlank()) c.setRequestProperty("authorization", "Bearer ${key.trim()}")
            c.outputStream.use { it.write(body(model, firstMessage, lowEffort).toByteArray()) }
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { readTitle(it.lineSequence()) }
        } finally { c.disconnect() }
    } catch (_: Throwable) {
        null
    }

    /**
     * Collects `delta.content` across the SSE stream (or one JSON answer) until a finish reason
     * or [DONE]; a deadline bounds a thinking model's reasoning, and a partial title still counts.
     */
    private fun readTitle(lines: Sequence<String>): String? {
        val title = StringBuilder()
        val plain = StringBuilder()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        fun content(o: JsonObject) {
            (o["choices"] as? JsonArray)?.forEach { ch ->
                val msg = ((ch as? JsonObject)?.get("delta") ?: (ch as? JsonObject)?.get("message")) as? JsonObject ?: return@forEach
                (msg["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { title.append(it) }
            }
        }
        /** true when this frame ends the answer (a finish reason is set, or an error came instead). */
        fun frame(payload: String): Boolean {
            val o = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return false
            if (o["error"] != null && o["error"] !is JsonNull) return true
            content(o)
            return (o["choices"] as? JsonArray)?.any { ch ->
                ((ch as? JsonObject)?.get("finish_reason") as? JsonPrimitive)?.takeIf { it !is JsonNull } != null
            } == true
        }
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("data:")) {
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                if (frame(payload)) break
            } else if (line.isNotEmpty() && !line.startsWith(":") && !line.startsWith("event:") && !line.startsWith("id:") && !line.startsWith("retry:")) {
                plain.append(line).append('\n')
                if (plain.length > 64_000) break
            }
            if (System.currentTimeMillis() > deadline) break
        }
        if (title.isEmpty() && plain.isNotBlank()) frame(plain.toString())
        return tidy(title.toString())
    }

    /** The answer's first line as a title: labels, quotes, wrapping and end dots off, length-capped. */
    private fun tidy(raw: String): String? {
        val line = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val t = line.trim()
            .removePrefix("标题：").removePrefix("标题:").removePrefix("Title:").removePrefix("title:").trim()
            .removeSurrounding("**")
            .trim('"', '\'', '“', '”', '„', '«', '»', '「', '」', '『', '』', '《', '》', '（', '）', '(', ')')
            .trimEnd('。', '.', '！', '!', '？', '?', '，', ',')
        return t.takeIf { it.length >= 2 }?.take(40)
    }
}
