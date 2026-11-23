package com.example.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/** Something that went wrong, shown once in the app so the user can copy the details and report it. */
data class ProblemReport(
    val title: String,
    /** One line, e.g. "IllegalStateException: ...". */
    val summary: String,
    /** Everything needed to debug it: device, version, stack trace. */
    val details: String,
    val timestamp: Long
)

/**
 * Keeps the last problem on disk, because a crash cannot be shown while it happens:
 *  - uncaught exceptions (the app closes) are written by the default handler installed in [install];
 *  - unexpected errors that were caught (the app stays open) are written by [recordHandled];
 *  - closes that no Java handler sees (native crash, ANR, killed for memory) come from Android's own exit
 *    records on Android 11+.
 * The app shows the newest one ([pending]) until the user dismisses it ([dismiss]).
 */
object CrashReporter {

    private const val TAG = "MangaLens"
    private const val FILE = "last_problem.txt"
    private const val PREFS = "manga_lens_diagnostics"
    private const val KEY_SEEN_AT = "seen_at"
    private const val MAX_CHARS = 24_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "O MangaLens fechou por um erro", thread.name, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** An unexpected error that was caught: the app kept running, but the details are worth reporting. */
    fun recordHandled(context: Context, where: String, error: Throwable) {
        Log.e(TAG, "Erro inesperado ($where)", error)
        runCatching { write(context.applicationContext, "Erro inesperado em $where (o app não fechou)", Thread.currentThread().name, error) }
    }

    /** Newest problem the user has not dismissed yet, or null. Reads files: call off the main thread. */
    fun pending(context: Context): ProblemReport? {
        val seenAt = maxOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SEEN_AT, 0L),
            installedAt(context)
        )
        val fromFile = readFile(context)?.takeIf { it.timestamp > seenAt }
        val fromSystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lastAbnormalExit(context, seenAt) else null
        return when {
            fromFile == null -> fromSystem
            fromSystem == null -> fromFile
            // A Java crash appears twice (our file and Android's record); our file has the stack trace.
            fromSystem.title == EXIT_CRASH_TITLE && kotlin.math.abs(fromSystem.timestamp - fromFile.timestamp) < 60_000 -> fromFile
            else -> if (fromSystem.timestamp > fromFile.timestamp) fromSystem else fromFile
        }
    }

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SEEN_AT, System.currentTimeMillis()).apply()
        File(context.filesDir, FILE).delete()
    }

    private fun write(context: Context, title: String, thread: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine(title)
            appendLine("Quando: ${format(System.currentTimeMillis())}")
            appendLine(deviceLine())
            appendLine("Thread: $thread")
            appendLine()
            append(trace)
        }
        File(context.filesDir, FILE).writeText(text.take(MAX_CHARS))
    }

    private fun readFile(context: Context): ProblemReport? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val lines = text.lines()
        // The first line after the blank separator is "pkg.SomeException: message".
        val exceptionLine = lines.dropWhile { it.isNotBlank() }.firstOrNull { it.isNotBlank() }.orEmpty()
        return ProblemReport(
            title = lines.first(),
            summary = shortException(exceptionLine),
            details = text,
            timestamp = file.lastModified()
        )
    }

    private fun lastAbnormalExit(context: Context, after: Long): ProblemReport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val exits = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 10) }.getOrNull() ?: return null
        val info = exits.firstOrNull { it.timestamp > after && it.reason in ABNORMAL_REASONS } ?: return null
        val title = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> EXIT_CRASH_TITLE
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "O MangaLens fechou por um erro em código nativo"
            ApplicationExitInfo.REASON_ANR -> "O MangaLens travou e o Android o encerrou"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "O Android encerrou o MangaLens por falta de memória"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "O Android encerrou o MangaLens por uso excessivo de recursos"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "O MangaLens falhou ao iniciar"
            else -> "O MangaLens foi encerrado pelo sistema"
        }
        // ANR and native crashes carry a trace; keep only the beginning, which names the stuck/crashed thread.
        val trace = if (info.reason == ApplicationExitInfo.REASON_ANR || info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText().take(MAX_CHARS / 2) } }.getOrNull()
        } else {
            null
        }
        val details = buildString {
            appendLine(title)
            appendLine("Quando: ${format(info.timestamp)}")
            appendLine(deviceLine())
            appendLine("Motivo do Android: ${info.reason} (${info.description ?: "sem descrição"})")
            appendLine("Estado: importância ${info.importance}, memória ${info.pss / 1024} MB")
            if (trace != null) {
                appendLine()
                append(trace)
            }
        }
        return ProblemReport(title, info.description?.takeIf { it.isNotBlank() } ?: title, details, info.timestamp)
    }

    private fun shortException(line: String): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return "Detalhes disponíveis abaixo"
        val name = trimmed.substringBefore(':').substringAfterLast('.')
        val message = trimmed.substringAfter(':', "").trim()
        return if (message.isEmpty()) name else "$name: ${message.take(160)}"
    }

    private fun installedAt(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    private fun deviceLine() =
        "MangaLens ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}"

    private fun format(timestamp: Long) =
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private const val EXIT_CRASH_TITLE = "O MangaLens fechou por um erro"

    private val ABNORMAL_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
    )
}
