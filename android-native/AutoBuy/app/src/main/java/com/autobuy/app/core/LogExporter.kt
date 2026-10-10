package com.autobuy.app.core

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把运行日志导出为 txt 文件，并通过 FileProvider 分享。 */
object LogExporter {

    fun export(context: Context, logs: List<String>): Uri? = try {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "autobuy_log_$stamp.txt")
        file.writeText(
            "AutoBuy 运行日志\n" +
                "导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                "----------------------------------------\n" +
                logs.joinToString("\n")
        )
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    }
}
