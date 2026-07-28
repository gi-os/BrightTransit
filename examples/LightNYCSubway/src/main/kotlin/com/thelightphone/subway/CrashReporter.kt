package com.thelightphone.subway

import android.util.Log
import java.io.File

/**
 * Captures uncaught exceptions to a file so the last crash can be shown on the
 * home screen (the Light Phone has no easy logcat access). Also keeps the app
 * from silently dying without a trace.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private var dir: File? = null
    private var installed = false

    fun install(filesDir: File) {
        if (installed) return
        installed = true
        dir = filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, FILE).writeText(
                    "Subway Times crash\n\n" + Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(): String? =
        dir?.let { d -> File(d, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }

    fun clear() {
        dir?.let { runCatching { File(it, FILE).delete() } }
    }
}
