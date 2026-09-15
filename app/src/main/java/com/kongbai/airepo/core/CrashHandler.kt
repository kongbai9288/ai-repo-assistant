package com.kongbai.airepo.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 崩溃落盘，方便在「设置 → 查看崩溃日志」里直接看堆栈，不用连 adb */
@Singleton
class CrashHandler @Inject constructor(@ApplicationContext private val ctx: Context) {

    private fun file() = File(ctx.filesDir, "crash.log")

    fun install() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sb = StringBuilder()
                sb.append("=== ${now()} ===\n")
                sb.append("thread: ${t.name}\n")
                sb.append("${e.javaClass.name}: ${e.message}\n")
                e.stackTrace.take(40).forEach { sb.append("    at $it\n") }
                e.cause?.let { c ->
                    sb.append("Caused by: ${c.javaClass.name}: ${c.message}\n")
                    c.stackTrace.take(20).forEach { sb.append("    at $it\n") }
                }
                sb.append("\n")
                val f = file()
                if (f.length() > 200_000) f.writeText("")
                f.appendText(sb.toString())
            }
            prev?.uncaughtException(t, e)
        }
    }

    suspend fun read(): String = withContext(Dispatchers.IO) {
        val f = file()
        if (!f.exists()) return@withContext "（暂无崩溃记录）"
        f.readText().take(20_000).ifBlank { "（暂无崩溃记录）" }
    }

    fun clear() { runCatching { file().delete() } }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
