package dev.agentworkbench

import android.content.Context
import java.io.File

/**
 * Keeps the last uncaught crash on disk so the next launch can show it (and let the user copy it)
 * without a USB cable or adb. The original handler still runs, so Android's own reporting is intact.
 */
internal object CrashReporter {
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 20_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(app, previous))
    }

    fun pending(context: Context): String? =
        File(context.filesDir, FILE_NAME).takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private class Recorder(
        private val app: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                File(app.filesDir, FILE_NAME).writeText(
                    "Refrator ${BuildConfig.VERSION_NAME} (${BuildConfig.DISTRIBUTION}) · thread ${thread.name}\n" +
                        error.stackTraceToString().take(MAX_CHARS),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
