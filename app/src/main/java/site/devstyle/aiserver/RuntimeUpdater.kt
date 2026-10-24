package site.devstyle.aiserver

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads only the chat web assets; native inference and APK code are never replaced. */
object RuntimeUpdater {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/arian00001/Offline-AI/main/runtime/runtime.json"
    private const val PREFS = "runtime_update"
    private const val MAX_MANIFEST_BYTES = 1_500_000

    fun checkAndApply(context: Context): String {
        val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DEV-STYLE-AI-Server-Android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub runtime manifest returned HTTP $code. If Offline-AI is private, raw access is not available to the app.")
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_MANIFEST_BYTES) throw IllegalStateException("Runtime update is too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val manifest = JSONObject(String(bytes, Charsets.UTF_8))
            val remoteVersion = manifest.getString("version").trim()
            val html = manifest.getString("html")
            val expectedHash = manifest.getString("sha256").trim().lowercase()
            if (!remoteVersion.matches(Regex("[0-9]+(\\.[0-9]+){1,3}"))) throw IllegalStateException("Invalid runtime version in manifest")
            val actualHash = sha256(html.toByteArray(Charsets.UTF_8))
            if (actualHash != expectedHash) throw IllegalStateException("Runtime asset integrity check failed; nothing was installed")

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val currentVersion = prefs.getString("version", "1.0.0") ?: "1.0.0"
            if (compareVersions(remoteVersion, currentVersion) <= 0) return "Runtime is up to date (v$currentVersion)."

            val dir = File(context.filesDir, "runtime")
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Cannot create runtime update directory")
            val target = File(dir, "chat.html")
            val temp = File(dir, "chat.html.tmp")
            FileOutputStream(temp).use { it.write(html.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            if (target.exists() && !target.delete()) { temp.delete(); throw IllegalStateException("Cannot replace previous runtime file") }
            if (!temp.renameTo(target)) { temp.delete(); throw IllegalStateException("Cannot activate downloaded runtime file") }
            prefs.edit().putString("version", remoteVersion).apply()
            ErrorStore.recordMessage(context, "Runtime update", "Applied web runtime v$remoteVersion from GitHub; SHA-256 verified")
            return "Runtime updated to v$remoteVersion. Reopen http://127.0.0.1:8080/ to load the updated chat UI. APK/model files were not downloaded."
        } finally {
            conn.disconnect()
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aa.size, bb.size)) {
            val diff = (aa.getOrElse(i) { 0 }).compareTo(bb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
