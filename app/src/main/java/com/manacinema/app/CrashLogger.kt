package com.manacinema.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val FILE_NAME = "manacinema_crash_log.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let the crash logger mask the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val logFile = File(dir, FILE_NAME)

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

        val entry = buildString {
            appendLine("==================== CRASH ====================")
            appendLine("time    : $timestamp")
            appendLine("thread  : ${thread.name}")
            appendLine("message : ${throwable.message}")
            appendLine("app     : ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device  : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("--- stacktrace ---")
            append(stack)
            appendLine("===============================================")
            appendLine()
        }

        logFile.appendText(entry)
    }
}
