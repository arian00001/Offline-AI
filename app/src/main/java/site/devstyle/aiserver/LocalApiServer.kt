package site.devstyle.aiserver

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class LocalApiServer(private val port: Int = 8080) : NanoHTTPD("127.0.0.1", port) {
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
                session.method == Method.GET && session.uri == "/health" -> json(JSONObject().put("status", "ok").put("model_loaded", ModelState.loaded).put("service", "DEV STYLE AI Server"))
                session.method == Method.GET && session.uri == "/v1/models" -> json(JSONObject().put("object", "list").put("data", JSONArray().put(JSONObject().put("id", ModelState.modelId).put("object", "model").put("owned_by", "local"))))
                session.method == Method.POST && (session.uri == "/v1/chat/completions" || session.uri == "/chat") -> chat(session, start)
                else -> json(JSONObject().put("error", JSONObject().put("message", "Not found. Try /health or /v1/chat/completions")), Response.Status.NOT_FOUND)
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            try { ErrorStore.recordMessage(AppContextHolder.context, "HTTP ${session.method} ${session.uri}", lastError) } catch (_: Throwable) {}
            json(JSONObject().put("error", JSONObject().put("message", lastError)), Response.Status.INTERNAL_ERROR)
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
        lastPrompt = prompt.take(180)
        requests.incrementAndGet()
        val stream = input.optBoolean("stream", false)
        val maxTokens = input.optInt("max_tokens", 384).coerceIn(16, 1024)
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
                    val answer = NativeLlm.generate(prompt, callback, maxTokens)
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
        val answer = NativeLlm.generate(prompt, null, maxTokens)
        lastLatencyMs = (System.nanoTime() - start) / 1_000_000
        lastOutput = answer.take(300)
        if (answer.startsWith("[ERROR]")) { lastError = answer; try { ErrorStore.recordMessage(AppContextHolder.context, "Native inference", answer) } catch (_: Throwable) {} }
        val usage = JSONObject().put("prompt_tokens", -1).put("completion_tokens", -1).put("total_tokens", -1)
        return json(JSONObject().put("id", "local-${System.currentTimeMillis()}").put("object", "chat.completion").put("created", System.currentTimeMillis() / 1000).put("model", model).put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", JSONObject().put("role", "assistant").put("content", answer)).put("finish_reason", "stop"))).put("usage", usage).put("x_latency_ms", lastLatencyMs))
    }

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
