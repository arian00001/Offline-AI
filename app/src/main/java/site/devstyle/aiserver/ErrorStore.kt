package site.devstyle.aiserver

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent diagnostic log kept inside this app's private storage. */
object ErrorStore {
    private const val PREFS = "diagnostics"
    private const val KEY = "errors"
    private const val MAX_CHARS = 48_000
    @Volatile private var crashHandlerInstalled = false

    @Synchronized fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record(context.applicationContext, "Uncaught exception on ${thread.name}", error) } catch (_: Throwable) {}
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
        crashHandlerInstalled = true
    }

    @Synchronized fun record(context: Context, source: String, error: Throwable) {
        val detail = buildString {
            append("[").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("] ")
            append(source).append(": ").append(error.javaClass.simpleName)
            append(": ").append(error.message ?: "No message")
            val trace = error.stackTrace.take(10).joinToString("\n") { "  at $it" }
            if (trace.isNotBlank()) append("\n").append(trace)
        }
        appendText(context, detail)
    }

    @Synchronized fun recordMessage(context: Context, source: String, message: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendText(context, "[$stamp] $source: $message")
    }

    private fun appendText(context: Context, line: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY, "").orEmpty()
        val updated = (old + if (old.isEmpty()) line else "\n\n$line").takeLast(MAX_CHARS)
        prefs.edit().putString(KEY, updated).apply()
    }

    fun read(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply() }
}
