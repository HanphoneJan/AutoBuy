package com.autobuy.app.seckill

enum class Platform(val label: String) {
    TAOBAO("淘宝"),
    JD("京东")
}

data class SeckillConfig(
    val platform: Platform,
    val targetTimeMs: Long,
    val keyword: String,
    val leadSeconds: Int = 3,
    val windowSeconds: Int = 30
)
