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
    val windowSeconds: Int = 30,
    /** 提前多久开始自动就位（打开购物车→选商品→进结算页） */
    val navigateLeadSeconds: Int = 20,
    /** 「号码保护」刷新间隔（毫秒），移动端用轻柔节奏，避免高频触发风控 */
    val refreshIntervalMs: Long = 1500
)

/** 抢购阶段：UI 与悬浮指引共用，保证"充分指引"。 */
enum class Stage(val label: String, val tip: String) {
    IDLE("空闲", "填好抢购时间后点「开始抢购」"),
    PREPARE("准备", "校准网络时间，等待进入抢购窗口"),
    NAVIGATE("就位", "自动打开购物车、选中目标商品、进入结算页"),
    REFRESH("刷新", "用「号码保护」开关刷新订单状态"),
    SUBMIT("提交", "到点自动点击「提交订单 / 立即支付」"),
    VERIFY("核对", "核对订单是否提交成功"),
    DONE("完成", "已提交，请尽快完成付款")
}
