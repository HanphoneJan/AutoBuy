package com.autobuy.app.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 对齐淘宝/京东服务器时间，避免依赖手机本地时钟。 */
object TimeSync {

    private const val TIMEOUT_MS = 3000

    private val TAOBAO_URLS = listOf(
        "https://acs.m.taobao.com/gw/mtop.common.getTimestamp/",
        "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
    )

    /** 京东服务器时间，淘宝时间源不可用时兜底（很多用户只抢京东）。 */
    private const val JD_TIME_URL = "https://a.jd.com//ajax/queryServerData.html"

    /** 返回网络时间与本地时间的偏移（毫秒）；全部失败返回 0。 */
    fun calibrateOffset(): Long {
        val now = System.currentTimeMillis()
        for (url in TAOBAO_URLS) {
            parseTaobaoTime(request(url))?.let { return it - now }
        }
        parseJdTime(request(JD_TIME_URL))?.let { return it - now }
        return 0L
    }

    private fun parseTaobaoTime(body: String?): Long? {
        if (body.isNullOrBlank()) return null
        return try {
            JSONObject(body).optJSONObject("data")?.optString("t")?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseJdTime(body: String?): Long? {
        if (body.isNullOrBlank()) return null
        return try {
            JSONObject(body).optLong("serverTime").takeIf { it > 0L }
        } catch (_: Exception) {
            null
        }
    }

    private fun request(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)")
        }
        conn.inputStream.bufferedReader().use { it.readText() }
            .also { conn.disconnect() }
    } catch (_: Exception) {
        null
    }
}
