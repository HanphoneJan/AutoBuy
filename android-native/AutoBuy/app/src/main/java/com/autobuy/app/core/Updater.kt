package com.autobuy.app.core

import com.autobuy.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 检查 GitHub Release 是否有新版本。 */
object Updater {

    data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

    private const val API = "https://api.github.com/repos/HanphoneJan/AutoBuy/releases/latest"

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AutoBuy")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name", "").trim().removePrefix("v")
            val notes = obj.optString("body", "")
            var apkUrl = ""
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (tag.isNotEmpty() && apkUrl.isNotEmpty() && isNewer(tag, BuildConfig.VERSION_NAME)) {
                UpdateInfo(tag, apkUrl, notes)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        fun parse(v: String) = v.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
