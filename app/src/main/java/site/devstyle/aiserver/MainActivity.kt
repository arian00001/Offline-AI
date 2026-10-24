package site.devstyle.aiserver

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val green = Color.rgb(151, 220, 44)
    private val ink = Color.rgb(35, 49, 38)
    private val muted = Color.rgb(112, 126, 116)
    private val bg = Color.rgb(247, 250, 246)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var modelLine: TextView
    private lateinit var apiLine: TextView
    private lateinit var requestLine: TextView
    private lateinit var latencyLine: TextView
    private lateinit var logLine: TextView
    private var selectedModel = ModelCatalog.models.first()
    private var downloadId = -1L
    private var downloadProgressText: TextView? = null
    private var downloadProgressBar: ProgressBar? = null
    private var downloadFileName: String? = null
    private val downloadPoll = object : Runnable {
        override fun run() {
            val id = downloadId
            if (id < 0L) return
            try {
                val query = DownloadManager.Query().setFilterById(id)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).query(query).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val soFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L)
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val percent = if (total > 0L) ((soFar * 100L) / total).toInt().coerceIn(0, 100) else 0
                        val sizeText = if (total > 0L) "${soFar / 1048576} / ${total / 1048576} MB" else "${soFar / 1048576} MB received"
                        when (status) {
                            DownloadManager.STATUS_PENDING -> showDownloadProgress("Preparing download… $sizeText", percent)
                            DownloadManager.STATUS_RUNNING -> showDownloadProgress("Downloading ${downloadFileName ?: "model"} · $percent% · $sizeText", percent)
                            DownloadManager.STATUS_PAUSED -> {
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                showDownloadProgress("Download paused (reason $reason). Check connection/storage.", percent)
                                handler.postDelayed(this, 1500)
                                return
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                showDownloadProgress("Download complete · $sizeText. Tap Load model.", 100)
                                downloadId = -1L
                                ErrorStore.recordMessage(this@MainActivity, "Model download", "Completed ${downloadFileName ?: "model"} ($soFar bytes)")
                                renderModels()
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                val message = when (reason) {
                                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free storage."
                                    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download storage is unavailable."
                                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network data error."
                                    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Server rejected the link (HTTP error). Use a direct GGUF download URL."
                                    DownloadManager.ERROR_CANNOT_RESUME -> "Download could not resume; retry it."
                                    else -> "Download failed (reason $reason). Check link, internet and free storage."
                                }
                                ErrorStore.recordMessage(this@MainActivity, "Model download", "$message URL=$downloadFileName")
                                downloadId = -1L
                                showDownloadProgress(message, 0)
                                toast(message)
                                return
                            }
                        }
                    } else {
                        showDownloadProgress("Download task not found. Retry the download.", 0)
                        downloadId = -1L
                        return
                    }
                }
            } catch (t: Throwable) {
                ErrorStore.record(this@MainActivity, "Download progress", t)
                showDownloadProgress("Could not read download status: ${t.message}", 0)
            }
            handler.postDelayed(this, 900)
        }
    }
    private var customUrlInput: EditText? = null
    private var customNameInput: EditText? = null
    private var testInput: EditText? = null
    private var testOutput: TextView? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == downloadId) {
                var message = "Download finished. Check the model card, then load it."
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).query(query).use { cursor ->
                        if (cursor != null && cursor.moveToFirst()) {
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                message = "Download failed (reason $reason). Check network and free storage."
                                ErrorStore.recordMessage(this@MainActivity, "Model download", message)
                            }
                        }
                    }
                } catch (t: Throwable) { ErrorStore.record(this@MainActivity, "Download status", t) }
                toast(message)
                renderModels()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.context = applicationContext
        ErrorStore.installCrashHandler(applicationContext)
        try { if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED) else registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) } catch (_: Throwable) {}
        buildShell()
        renderHome()
        handler.post(statsRunnable)
    }

    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(18), dp(16), dp(18), dp(10)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        header.addView(TextView(this).apply { text = "DEV STYLE"; textSize = 12f; letterSpacing = .22f; setTextColor(Color.rgb(93, 151, 35)); typeface = Typeface.DEFAULT_BOLD })
        header.addView(TextView(this).apply { text = "Local AI Server"; textSize = 27f; setTextColor(ink); typeface = Typeface.create("sans-serif", Typeface.BOLD) })
        header.addView(TextView(this).apply { text = "PRIVATE BY DESIGN · ON-DEVICE INFERENCE"; textSize = 10f; letterSpacing = .10f; setTextColor(muted) })
        root.addView(header)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }
        listOf("Home", "Models", "Settings").forEach { label -> nav.addView(button(label, filled = false) { when (label) { "Home" -> renderHome(); "Models" -> renderModels(); else -> renderSettings() } }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }) }
        root.addView(nav)
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(18)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun renderHome() {
        content.removeAllViews()
        content.addView(card {
            addView(TextView(this@MainActivity).apply { text = "SERVER OVERVIEW"; textSize = 11f; letterSpacing = .12f; setTextColor(muted); typeface = Typeface.DEFAULT_BOLD })
            statusLine = TextView(this@MainActivity).apply { textSize = 21f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(2)) }
            addView(statusLine)
            modelLine = bodyText("Model: none loaded")
            addView(modelLine)
            apiLine = bodyText("API: stopped")
            addView(apiLine)
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
            row.addView(button("Start API", true) { startServer() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(7) })
            row.addView(button("Stop API", false) { stopServer() }, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(row)
        })
        content.addView(card {
            addView(sectionTitle("LIVE TELEMETRY"))
            requestLine = bodyText("Requests received: 0"); addView(requestLine)
            latencyLine = bodyText("Last response: —"); addView(latencyLine)
            logLine = bodyText("Last prompt: —\nLast output: —"); addView(logLine)
            addView(button("Send test request", false) { showTestPanel() }.apply { setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        })
        content.addView(card {
            addView(sectionTitle("QUICK START"))
            addView(bodyText("1. Open Models and download a GGUF model.\n2. Load the downloaded model.\n3. Start API.\n4. Point Jarvis to http://127.0.0.1:8080/v1"))
            addView(bodyText("API endpoints: GET /health · GET /v1/models · POST /v1/chat/completions"))
        })
        refreshStats()
    }

    private fun renderModels() {
        content.removeAllViews()
        content.addView(bodyText("Choose a model, download it once, then load it. Large files need Wi-Fi and several GB of free space. Start with Qwen3 0.6B to test the full flow; 4B may exceed available RAM."))
        if (downloadId >= 0L) {
            content.addView(card {
                addView(sectionTitle("MODEL DOWNLOAD PROGRESS"))
                downloadProgressText = bodyText("Connecting to Hugging Face…")
                addView(downloadProgressText)
                downloadProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
                addView(downloadProgressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(8) })
                addView(bodyText("Keep the app installed and leave enough free storage. Download runs through Android Download Manager."))
            })
            handler.removeCallbacks(downloadPoll)
            handler.post(downloadPoll)
        }
        ModelCatalog.models.forEach { model ->
            val file = modelFile(model.file)
            content.addView(card {
                addView(TextView(this@MainActivity).apply { text = model.title; textSize = 17f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
                addView(bodyText(model.detail))
                addView(button("Open Hugging Face source", false) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.url.substringBefore("/resolve/") + "/tree/main"))) }
                    catch (t: Throwable) { ErrorStore.record(this@MainActivity, "Open model source", t); toast("Could not open model source") }
                }, LinearLayout.LayoutParams(-1, dp(38)))
                addView(bodyText(if (file.exists()) "Downloaded · ${(file.length() / (1024.0 * 1024)).toInt()} MB" else "Not downloaded"))
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
                row.addView(button("Download", false) { startDownload(model.url, model.file) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(7) })
                row.addView(button("Load model", true) { loadModel(model) }, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(row)
            })
        }
        val savedCustomName = getSharedPreferences("models", MODE_PRIVATE).getString("custom_file", null)
        if (!savedCustomName.isNullOrBlank()) {
            val customFile = modelFile(savedCustomName)
            content.addView(card {
                addView(TextView(this@MainActivity).apply { text = "Custom · $savedCustomName"; textSize = 16f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
                addView(bodyText(if (customFile.exists()) "Downloaded · ${(customFile.length() / (1024.0 * 1024)).toInt()} MB" else "Download incomplete or file missing"))
                addView(button("Load custom model", true) {
                    loadModel(ModelChoice("custom-${savedCustomName.hashCode()}", savedCustomName, "Custom GGUF", savedCustomName, ""))
                }, LinearLayout.LayoutParams(-1, dp(44)))
            })
        }
        content.addView(card {
            addView(sectionTitle("CUSTOM MODEL LINK"))
            customNameInput = edit("File name, e.g. custom-model.gguf")
            addView(customNameInput)
            customUrlInput = edit("Direct HTTPS URL to a .gguf file")
            addView(customUrlInput)
            addView(bodyText("Use a direct download URL. Only download models from sources you trust; this app does not verify model hashes yet."))
            addView(button("Download custom model", true) {
                val name = customNameInput?.text?.toString()?.trim().orEmpty()
                val url = customUrlInput?.text?.toString()?.trim().orEmpty()
                val isHuggingFacePage = url.contains("huggingface.co/") && !url.contains("/resolve/")
                if (!name.endsWith(".gguf", true) || !url.startsWith("https://") || !url.contains("/") || isHuggingFacePage) {
                    toast("Use a direct HTTPS .gguf file URL. Hugging Face links must contain /resolve/main/ (not /blob/main/).")
                } else {
                    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    getSharedPreferences("models", MODE_PRIVATE).edit().putString("custom_file", safeName).apply()
                    startDownload(url, safeName)
                }
            }, LinearLayout.LayoutParams(-1, dp(46)))
        })
    }

    private fun renderSettings() {
        content.removeAllViews()
        content.addView(card {
            addView(sectionTitle("SERVER SETTINGS"))
            addView(bodyText("Bind address: 127.0.0.1 (this phone only)\nPort: 8080\nAPI base: http://127.0.0.1:8080/v1\nContext length: 2048 tokens\nDefault max output: 384 tokens\nInference: CPU-first for compatibility"))
            addView(bodyText("Security: API binds only to localhost so other devices on Wi-Fi cannot connect directly. Jarvis on this same phone can use localhost."))
        })
        content.addView(card {
            addView(sectionTitle("JARVIS CONNECTION"))
            addView(bodyText("Base URL: http://127.0.0.1:8080/v1\nChat endpoint: POST /chat/completions\nModel id: local-assistant\nStreaming: set \"stream\": true (SSE)"))
            addView(button("Copy API base URL", false) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Local API", "http://127.0.0.1:8080/v1")); toast("API URL copied")
            }, LinearLayout.LayoutParams(-1, dp(44)))
        })
        content.addView(card {
            addView(sectionTitle("DIAGNOSTICS"))
            addView(bodyText("Native engine: llama.cpp\nModel format: GGUF\nArchitecture: arm64-v8a\nApp data: private app storage\nInternet is only needed to download models and build the APK; inference is local."))
            addView(button("Unload current model", false) {
                io.execute { try { NativeLlm.unloadModel(); ModelState.loaded = false; runOnUiThread { toast("Model unloaded"); renderSettings() } } catch (t: Throwable) { ErrorStore.record(this@MainActivity, "Unload model", t); runOnUiThread { toast(t.message ?: "Could not unload") } } }
            }, LinearLayout.LayoutParams(-1, dp(44)))
        })
        content.addView(card {
            addView(sectionTitle("ERRORS & DIAGNOSTICS"))
            val errorCount = ErrorStore.read(this@MainActivity).lineSequence().count { it.startsWith("[") }
            addView(bodyText("Saved diagnostic entries: $errorCount\nErrors are stored locally on this phone. They are not uploaded."))
            addView(button("Open error log", true) { renderErrors() }, LinearLayout.LayoutParams(-1, dp(44)))
        })
    }

    private fun renderErrors() {
        content.removeAllViews()
        content.addView(card {
            addView(sectionTitle("LOCAL ERROR LOG"))
            addView(bodyText("Diagnostics stay on this device. Copy the log before clearing it if you need help debugging."))
            val log = TextView(this@MainActivity).apply {
                text = ErrorStore.read(this@MainActivity).ifBlank { "No errors recorded. Everything is quiet so far." }
                textSize = 11f; setTextColor(ink); setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(242, 247, 239)); cornerRadius = dp(12).toFloat() }
            }
            addView(log, LinearLayout.LayoutParams(-1, -2))
            addView(button("Copy error log", false) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DEV STYLE error log", ErrorStore.read(this@MainActivity)))
                toast("Error log copied")
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
            addView(button("Clear error log", false) {
                ErrorStore.clear(this@MainActivity); toast("Error log cleared"); renderErrors()
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        })
    }

    private fun showTestPanel() {
        content.addView(card {
            addView(sectionTitle("API PLAYGROUND"))
            testInput = edit("Ask the local model something…")
            addView(testInput)
            testOutput = bodyText("Response will appear here.")
            addView(testOutput)
            addView(button("Run local request", true) {
                val prompt = testInput?.text?.toString()?.trim().orEmpty()
                if (prompt.isBlank()) { toast("Enter a prompt"); return@button }
                if (!ModelState.loaded) { toast("Load a model first"); return@button }
                testOutput?.text = "Generating…"
                io.execute {
                    val start = System.currentTimeMillis()
                    val answer = NativeLlm.generate(prompt, null, 256)
                    val elapsed = System.currentTimeMillis() - start
                    if (answer.startsWith("[ERROR]")) ErrorStore.recordMessage(this@MainActivity, "API playground inference", answer)
                    runOnUiThread { testOutput?.text = "$answer\n\nTime: ${elapsed} ms"; refreshStats() }
                }
            }, LinearLayout.LayoutParams(-1, dp(44)))
        })
    }

    private fun startServer() {
        if (!ModelState.loaded) { toast("Download and load a model first"); renderModels(); return }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, ServerService::class.java)) else startService(Intent(this, ServerService::class.java))
            val ok = ServerController.start()
            if (ok) { toast("Local API started on port 8080"); renderHome() } else toast("Port 8080 unavailable. Stop other server and retry.")
        } catch (t: Throwable) { ErrorStore.record(this, "Start API", t); toast("Could not start API: ${t.message}") }
    }

    private fun stopServer() {
        ServerController.stop()
        try { stopService(Intent(this, ServerService::class.java)) } catch (_: Throwable) {}
        toast("API stopped")
        renderHome()
    }

    private fun startDownload(url: String, fileName: String) {
        val parsed = try { Uri.parse(url) } catch (_: Throwable) { null }
        if (parsed == null || parsed.scheme != "https" || parsed.host.isNullOrBlank()) {
            toast("Enter a valid direct HTTPS model file URL")
            return
        }
        if (!fileName.endsWith(".gguf", true) || fileName.contains("..") || fileName.contains('/')) {
            toast("Model filename must end in .gguf")
            return
        }
        try {
            val dir = getExternalFilesDir("models") ?: filesDir
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Could not create model storage directory")
            val existing = File(dir, fileName)
            if (existing.exists() && existing.length() > 1024 * 1024) {
                toast("Model file already exists. Tap Load model.")
                renderModels()
                return
            }
            val request = DownloadManager.Request(parsed)
                .setTitle("DEV STYLE · $fileName")
                .setDescription("Downloading GGUF model. In-app progress is shown in Models.")
                .setMimeType("application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(this, "models", fileName)
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            if (downloadId >= 0L) {
                try { manager.remove(downloadId) } catch (_: Throwable) {}
                handler.removeCallbacks(downloadPoll)
            }
            downloadFileName = fileName
            downloadId = manager.enqueue(request)
            ErrorStore.recordMessage(this, "Model download", "Queued $fileName from $url")
            renderModels()
            handler.removeCallbacks(downloadPoll)
            handler.post(downloadPoll)
            toast("Download started. Open Models to watch live progress.")
        } catch (t: Throwable) {
            ErrorStore.record(this, "Model download", t)
            toast("Download could not start: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun showDownloadProgress(message: String, percent: Int) {
        runOnUiThread {
            downloadProgressText?.text = message
            downloadProgressBar?.progress = percent
        }
    }

    private fun loadModel(model: ModelChoice) {
        val file = modelFile(model.file)
        if (!file.exists() || file.length() < 1024 * 1024) { toast("Model file not found or incomplete. Download it first."); return }
        toast("Loading model… first load may take a while")
        io.execute {
            try {
                val ok = NativeLlm.loadModel(file.absolutePath)
                ModelState.loaded = ok
                ModelState.modelId = model.id
                if (!ok) ErrorStore.recordMessage(this, "Model load", "Native engine could not load ${model.file}. The file may be incomplete, unsupported, or too large for available memory.")
                runOnUiThread { toast(if (ok) "Model loaded" else "Model load failed. Try a smaller model." ); renderHome() }
            } catch (t: Throwable) { ErrorStore.record(this, "Model load", t); runOnUiThread { toast("Model load failed: ${t.message}") } }
        }
    }

    private val statsRunnable = object : Runnable {
        override fun run() { refreshStats(); handler.postDelayed(this, 1200) }
    }
    private fun refreshStats() {
        if (!::statusLine.isInitialized) return
        statusLine.text = if (ModelState.loaded) "● MODEL READY" else "○ MODEL NOT LOADED"
        statusLine.setTextColor(if (ModelState.loaded) Color.rgb(85, 151, 28) else muted)
        modelLine.text = "Model: ${if (ModelState.loaded) ModelState.modelId else "none loaded"}"
        apiLine.text = "API: ${if (ServerController.server != null) "http://127.0.0.1:8080" else "stopped"}"
        val server = ServerController.server
        requestLine.text = "Requests received: ${server?.requestCount() ?: 0}"
        latencyLine.text = "Last response: ${if (server != null && server.lastLatencyMs > 0) "${server.lastLatencyMs} ms" else "—"}"
        logLine.text = "Last prompt: ${server?.lastPrompt ?: "—"}\nLast output: ${server?.lastOutput ?: "—"}${if (!server?.lastError.isNullOrBlank()) "\nLast error: ${server?.lastError}" else ""}"
    }

    private fun modelFile(name: String): File = File(getExternalFilesDir("models") ?: filesDir, name)
    private fun card(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.rgb(231, 238, 228)) }
        elevation = dp(2).toFloat(); build(); val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(13); layoutParams = lp
    }
    private fun sectionTitle(s: String) = TextView(this).apply { text = s; textSize = 11f; letterSpacing = .12f; setTextColor(Color.rgb(96, 145, 46)); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(10)) }
    private fun bodyText(s: String) = TextView(this).apply { text = s; textSize = 13f; setTextColor(muted); setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(5), 0, dp(5)) }
    private fun edit(hintText: String) = EditText(this).apply { hint = hintText; textSize = 13f; setTextColor(ink); setHintTextColor(muted); setSingleLine(true); setPadding(dp(12), dp(8), dp(12), dp(8)); background = android.graphics.drawable.GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(222, 231, 218)) }; layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) } }
    private fun button(label: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (filled) Color.rgb(32, 51, 22) else ink)
        background = android.graphics.drawable.GradientDrawable().apply { setColor(if (filled) green else Color.rgb(244, 248, 241)); cornerRadius = dp(13).toFloat(); if (!filled) setStroke(dp(1), Color.rgb(223, 232, 219)) }
        setOnClickListener { action() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onDestroy() { try { unregisterReceiver(downloadReceiver) } catch (_: Throwable) {}; handler.removeCallbacks(statsRunnable); handler.removeCallbacks(downloadPoll); io.shutdown(); super.onDestroy() }
}
