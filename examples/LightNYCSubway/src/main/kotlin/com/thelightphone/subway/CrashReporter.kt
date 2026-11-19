package com.thelightphone.subway

import android.util.Log
import java.io.File

/**
 * Writes the last uncaught exception to a file before the tool dies. The Light Phone has
 * no easy way to pull a log, so a build made for development shows that file the next time
 * the tool opens. A rider's build writes it and never shows it: a stack trace is no use to
 * them. See [ToolManifest.debuggable].
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
            runCatching { File(filesDir, FILE).writeText(Log.getStackTraceString(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(): String? =
        dir?.let { d -> File(d, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }

    fun clear() {
        dir?.let { runCatching { File(it, FILE).delete() } }
    }
}
