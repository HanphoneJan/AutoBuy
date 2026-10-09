package com.autobuy.app.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 对齐淘宝服务器时间，避免依赖手机本地时钟。 */
object TimeSync {

    private val TAOBAO_URLS = listOf(
        "https://acs.m.taobao.com/gw/mtop.common.getTimestamp/",
        "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
    )

    /** 返回网络时间与本地时间的偏移（毫秒）；失败返回 0。 */
    fun calibrateOffset(): Long {
        for (url in TAOBAO_URLS) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                }
                conn.inputStream.bufferedReader().use { reader ->
                    val json = JSONObject(reader.readText())
                    val t = json.optJSONObject("data")?.optString("t").orEmpty()
                    if (t.isNotEmpty()) return t.toLong() - System.currentTimeMillis()
                }
            } catch (_: Exception) {
                // 尝试下一个时间源
            }
        }
        return 0L
    }
}
