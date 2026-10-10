package com.autobuy.app.core

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.autobuy.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出可排查的诊断日志：设备/系统/权限等上下文 + 持久化的完整日志。
 * 通过 FileProvider 分享为 txt，方便用户发给开发者求助。
 */
object LogExporter {

    fun export(context: Context, fallbackLogs: List<String>): Uri? = try {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "autobuy_log_$stamp.txt")
        file.writeText(buildReport(context, fallbackLogs), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    }

    private fun buildReport(context: Context, fallbackLogs: List<String>): String {
        val persisted = FileLogger.read()
        val body = if (persisted.isNotBlank()) persisted else fallbackLogs.joinToString("\n")
        return buildString {
            appendLine("AutoBuy 诊断日志")
            appendLine("========================================")
            appendLine("导出时间 : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("App 版本 : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备     : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android  : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI      : ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("无障碍   : ${Permissions.isAccessibilityEnabled(context)}")
            appendLine("悬浮窗   : ${Permissions.canDrawOverlays(context)}")
            appendLine("========================================")
            appendLine("日志（含历史，最新在下）")
            appendLine("----------------------------------------")
            append(body)
        }
    }
}
