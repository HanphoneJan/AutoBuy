package com.autobuy.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.autobuy.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
        val dm = context.resources.displayMetrics
        return buildString {
            appendLine("AutoBuy 诊断日志")
            appendLine("========================================")
            appendLine("导出时间 : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
            appendLine("App 版本 : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备     : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android  : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI      : ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("屏幕     : ${dm.widthPixels}x${dm.heightPixels} @ ${dm.density} (${dm.densityDpi}dpi)")
            appendLine("语言/时区: ${Locale.getDefault().toLanguageTag()} / ${TimeZone.getDefault().id}")
            appendLine("淘宝版本 : ${packageVersion(context, "com.taobao.taobao")}")
            appendLine("京东版本 : ${packageVersion(context, "com.jingdong.app.mall")}")
            appendLine("无障碍   : ${Permissions.isAccessibilityEnabled(context)}")
            appendLine("悬浮窗   : ${Permissions.canDrawOverlays(context)}")
            appendLine("========================================")
            appendLine("日志（含历史，最新在下）")
            appendLine("----------------------------------------")
            append(body)
        }
    }

    /** 目标 App 的版本号；没安装返回「未安装」。定位模板/页面改版时很有用。 */
    private fun packageVersion(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        info.versionName ?: "未知"
    } catch (_: Exception) {
        "未安装"
    }
}
