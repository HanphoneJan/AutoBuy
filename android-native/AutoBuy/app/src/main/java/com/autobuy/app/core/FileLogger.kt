package com.autobuy.app.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把日志持续写入文件，App 重启/被杀后仍可导出用于排查。
 * 文件：Android/data/com.autobuy.app/files/logs/autobuy.log（超过上限自动轮转一份 prev）。
 */
object FileLogger {

    private const val MAX_BYTES = 512 * 1024L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
            file = File(dir, "autobuy.log")
        }
    }

    fun append(message: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText("[${fmt.format(Date())}] $message\n", Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    fun read(): String {
        val f = file ?: return ""
        synchronized(lock) {
            return try {
                if (f.exists()) f.readText(Charsets.UTF_8) else ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun clear() {
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.exists()) f.writeText("", Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    private fun rotate(f: File) {
        try {
            val prev = File(f.parentFile, "autobuy.prev.log")
            if (prev.exists()) prev.delete()
            f.renameTo(prev)
        } catch (_: Exception) {
        }
    }
}
