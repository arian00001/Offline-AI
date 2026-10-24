package site.devstyle.aiserver

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Same-Wi-Fi access; use only on a trusted private network (no API authentication). */
class LocalApiServer(private val port: Int = 8080) : NanoHTTPD("0.0.0.0", port) {
    private val executor = Executors.newCachedThreadPool()
    private val requests = AtomicLong(0)
    @Volatile var lastLatencyMs: Long = 0
    @Volatile var lastPrompt: String = "—"
    @Volatile var lastOutput: String = "—"
    @Volatile var lastError: String = ""

    override fun serve(session: IHTTPSession): Response {
        val start = System.nanoTime()
        return try {
            when {
                session.method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "").apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                    addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                    addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                }
                session.method == Method.GET && (session.uri == "/" || session.uri == "/chat") -> chatPage()
                session.method == Method.GET && session.uri == "/runtime/version" -> json(JSONObject().put("version", AppContextHolder.context.getSharedPreferences("runtime_update", android.content.Context.MODE_PRIVATE).getString("version", "1.0.0")))
                session.method == Method.GET && session.uri == "/health" -> json(JSONObject().put("status", "ok").put("model_loaded", ModelState.loaded).put("service", "DEV STYLE AI Server"))
                session.method == Method.GET && session.uri == "/v1/models" -> json(JSONObject().put("object", "list").put("data", JSONArray().put(JSONObject().put("id", ModelState.modelId).put("object", "model").put("owned_by", "local"))))
                session.method == Method.GET && session.uri == "/v1/search" -> {
                    val query = session.parameters["q"]?.firstOrNull().orEmpty().take(240)
                    if (query.isBlank()) json(JSONObject().put("error", JSONObject().put("message", "Add ?q=your+search+query")), Response.Status.BAD_REQUEST)
                    else json(webSearch(query))
                }
                session.method == Method.POST && (session.uri == "/v1/chat/completions" || session.uri == "/chat") -> chat(session, start)
                else -> json(JSONObject().put("error", JSONObject().put("message", "Not found. Try /health or /v1/chat/completions")), Response.Status.NOT_FOUND)
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            try { ErrorStore.recordMessage(AppContextHolder.context, "HTTP ${session.method} ${session.uri}", lastError) } catch (_: Throwable) {}
            json(JSONObject().put("error", JSONObject().put("message", lastError)), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun chatPage(): Response {
        val context = AppContextHolder.context
        val updated = java.io.File(context.filesDir, "runtime/chat.html")
        val html = if (updated.isFile && updated.length() > 100) {
            updated.readText(Charsets.UTF_8)
        } else {
            context.assets.open("chat.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store, max-age=0")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun chat(session: IHTTPSession, start: Long): Response {
        if (!ModelState.loaded) return json(JSONObject().put("error", JSONObject().put("message", "No model loaded. Download and load a GGUF model first.")), Response.Status.SERVICE_UNAVAILABLE)
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: "{}"
        val input = JSONObject(body)
        val messages = input.optJSONArray("messages")
        val prompt = if (messages != null) {
            buildString {
                for (i in 0 until messages.length()) {
                    val m = messages.optJSONObject(i) ?: continue
                    append(m.optString("role", "user")).append(": ").append(m.optString("content")).append('\n')
                }
            }.takeLast(12000)
        } else input.optString("prompt", "")
        if (prompt.isBlank()) return json(JSONObject().put("error", JSONObject().put("message", "messages or prompt is required")), Response.Status.BAD_REQUEST)
        val webSearchEnabled = input.optBoolean("web_search", false)
        val searchQuery = messages?.let { arr ->
            (arr.length() - 1 downTo 0).firstNotNullOfOrNull { i ->
                val m = arr.optJSONObject(i)
                if (m?.optString("role") == "user") m.optString("content").takeIf { it.isNotBlank() } else null
            }
        } ?: input.optString("prompt", "")
        val webContext = if (webSearchEnabled) {
            try { webSearch(searchQuery).optJSONArray("results")?.let { results ->
                buildString {
                    append("\n\nLIVE WEB SEARCH RESULTS (may be incomplete; verify source and date):\n")
                    for (i in 0 until minOf(results.length(), 5)) {
                        val item = results.optJSONObject(i) ?: continue
                        append("- ").append(item.optString("title")).append(" | ").append(item.optString("url"))
                            .append("\n  ").append(item.optString("snippet")).append('\n')
                    }
                    append("Use these results only as evidence, mention uncertainty, and do not invent details.\n")
                }
            } ?: "" } catch (t: Throwable) { "\nWeb search unavailable: ${t.message ?: "network error"}. Answer from existing knowledge and state you could not search.\n" }
        } else ""
        val effectivePrompt = if (webContext.isNotBlank()) "$prompt\n$webContext" else prompt
        lastPrompt = prompt.take(180)
        requests.incrementAndGet()
        val stream = input.optBoolean("stream", false)
        val maxTokens = input.optInt("max_tokens", 192).coerceIn(16, 512)
        val model = input.optString("model", ModelState.modelId)
        if (stream) {
            val pipeIn = PipedInputStream(64 * 1024)
            val pipeOut = PipedOutputStream(pipeIn)
            executor.execute {
                try {
                    val callback = object : NativeLlm.TokenCallback {
                        override fun onToken(token: String) {
                            val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject().put("content", token))))
                            pipeOut.write(("data: " + delta.toString() + "\n\n").toByteArray(StandardCharsets.UTF_8)); pipeOut.flush()
                        }
                    }
                    val answer = NativeLlm.generate(effectivePrompt, callback, maxTokens)
                    lastOutput = answer.take(300)
                    lastLatencyMs = (System.nanoTime() - start) / 1_000_000
                    if (answer.startsWith("[ERROR]")) { lastError = answer; try { ErrorStore.recordMessage(AppContextHolder.context, "Streaming inference", answer) } catch (_: Throwable) {} }
                    pipeOut.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
                } catch (t: Throwable) { lastError = t.message ?: "stream failed"; try { ErrorStore.record(AppContextHolder.context, "Streaming API", t) } catch (_: Throwable) {} }
                finally { try { pipeOut.close() } catch (_: Throwable) {} }
            }
            return newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", pipeIn).apply {
                addHeader("Cache-Control", "no-cache")
                addHeader("Connection", "keep-alive")
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
        val answer = NativeLlm.generate(effectivePrompt, null, maxTokens)
        lastLatencyMs = (System.nanoTime() - start) / 1_000_000
        lastOutput = answer.take(300)
        if (answer.startsWith("[ERROR]")) { lastError = answer; try { ErrorStore.recordMessage(AppContextHolder.context, "Native inference", answer) } catch (_: Throwable) {} }
        val usage = JSONObject().put("prompt_tokens", -1).put("completion_tokens", -1).put("total_tokens", -1)
        return json(JSONObject().put("id", "local-${System.currentTimeMillis()}").put("object", "chat.completion").put("created", System.currentTimeMillis() / 1000).put("model", model).put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", JSONObject().put("role", "assistant").put("content", answer)).put("finish_reason", "stop"))).put("usage", usage).put("x_latency_ms", lastLatencyMs))
    }

    /**
     * Lightweight HTML search adapter (DuckDuckGo). It is best-effort and only runs
     * when the user explicitly enables Web Search. No API key is embedded in the app.
     */
    private fun webSearch(query: String): JSONObject {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val connection = (java.net.URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 7000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Search provider returned HTTP $code")
            val page = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(1_200_000) }
            val titlePattern = Regex("""<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val snippetPattern = Regex("""<a[^>]*class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</a>|<div[^>]*class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val snippets = snippetPattern.findAll(page).map { stripHtml(it.groupValues[1].ifBlank { it.groupValues[2] }) }.toList()
            val results = JSONArray()
            titlePattern.findAll(page).take(8).forEachIndexed { index, match ->
                var href = decodeEntities(match.groupValues[1])
                val redirect = Regex("[?&]uddg=([^&]+)").find(href)
                if (redirect != null) href = try { java.net.URLDecoder.decode(redirect.groupValues[1], "UTF-8") } catch (_: Throwable) { href }
                if (!href.startsWith("http://") && !href.startsWith("https://")) return@forEachIndexed
                val title = stripHtml(match.groupValues[2]).take(200)
                if (title.isNotBlank()) results.put(JSONObject().put("title", title).put("url", href).put("snippet", snippets.getOrElse(index) { "" }.take(600)))
            }
            return JSONObject().put("query", query).put("provider", "DuckDuckGo HTML").put("results", results).put("count", results.length())
        } finally { connection.disconnect() }
    }

    private fun stripHtml(value: String): String = decodeEntities(value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim())
    private fun decodeEntities(value: String): String = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun json(obj: JSONObject, status: Response.Status = Response.Status.OK): Response = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString()).apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    }

    fun requestCount() = requests.get()
}

object AppContextHolder {
    @Volatile lateinit var context: android.content.Context
}

object ModelState {
    @Volatile var loaded = false
    @Volatile var modelId = "local-assistant"
}

object ServerController {
    @Volatile var server: LocalApiServer? = null
    @Synchronized fun start(): Boolean {
        if (server != null) return true
        return try { server = LocalApiServer(8080).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }; true }
        catch (_: Throwable) { server = null; false }
    }
    @Synchronized fun stop() { try { server?.stop() } catch (_: Throwable) {}; server = null }
}
