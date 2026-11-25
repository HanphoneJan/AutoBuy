package com.autobuy.app.seckill

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import com.autobuy.app.accessibility.AutoBuyAccessibilityService
import com.autobuy.app.core.ImageMatcher
import com.autobuy.app.core.LogBus
import com.autobuy.app.core.TimeSync
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抢购引擎（移动端流程，区别于网页端）：
 *
 *  准备  校准网络时间，等到 T-navigateLead 开始就位
 *  就位  自动切到购物车 → 校验已有勾选商品 → 点「结算/去结算」→ 进入确认订单页
 *        （不替用户勾选商品：复选框和商品图里都有圆，图像识别分不清，点错会
 *         把别的商品带进结算或跳进商品详情页，见 #29）
 *  刷新  开卖前 5 秒起，在确认订单页用「号码保护」开关刷新订单状态
 *  提交  到点快速点击「提交订单 / 立即支付」；显示未开售时留在确认页继续用
 *        「号码保护」刷新开卖状态，不退回购物车重进（重进会错过开卖瞬间），
 *        直到成功或超出窗口
 *
 * 淘宝：购物车/确认页为 Weex，读「合计」判断是否已勾选；提交按钮 content-desc。
 * 京东：购物车纯自绘、无文字节点，点「去结算」由平台自己判断；确认页有「号码保护」开关。
 * 预售/预约/定时开卖：开售前不可勾选，需要用户在开售后自己勾上，程序只负责结算与提交。
 */
class SeckillEngine(private val context: Context) {

    private val taobaoPkg = "com.taobao.taobao"
    private val jdPkg = "com.jingdong.app.mall"

    private val taobaoSettleColor = Color.rgb(0xFF, 0x6A, 0x00)
    private val jdSettleColor = Color.rgb(0xE1, 0x25, 0x1B)

    private val settleRegion = floatArrayOf(0.55f, 0.82f, 1.0f, 0.97f)

    /**
     * 底部按钮带（整行）：京东确认页的「立即支付」是通栏按钮（约占满屏宽），
     * 购物车的「去结算」只在右下角。只取 y 91%~97%，避开购物车里的红色优惠券横幅。
     */
    private val bottomButtonBand = floatArrayOf(0f, 0.91f, 1f, 0.97f)

    /** 通栏按钮宽度阈值：超过 60% 屏宽就认为在确认订单页/支付页 */
    private val fullWidthButtonRatio = 0.6f

    /** 图片判断确认页的缓存，避免 150ms 循环里每次都截图（系统限频 333ms） */
    private var confirmShotAt = 0L
    private var confirmShotHit = false

    /** 系统限制无障碍截图间隔 ≥333ms，这里留一点余量。 */
    private val shotMinGapMs = 360L
    private var lastShotAt = 0L
    private var lastShotError = 0
    private var noScreenshotLogged = false

    /** 「购物车没勾选商品」只提示一次，避免 4 秒重试刷屏。 */
    private var noSelectionLogged = false

    /** 点完提交后多久之内离开确认页才算「提交成功跳转」：避免把页面加载中误判为已提交 */
    private val leaveConfirmGraceMs = 5_000L
    /** 有效提交后，确认页消失需要持续多久才认成功（排除节点重建/跳转渲染的瞬间） */
    private val leaveConfirmConfirmMs = 1_200L
    /** 进/回结算页后等待确认页就绪的上限 */
    private val confirmReadyWaitMs = 3_000L
    /** 开卖前多久开始用「号码保护」刷新（不提前太久，避免长时间高频切换） */
    private val refreshLeadMs = 5_000L

    /** 购物车底部结算栏里的合计金额，如「合计：¥0.00」。 */
    private val totalRegex = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")
    private val totalLabelKeywords = listOf("合计", "总计")

    /** 平台风控/安全验证页（京东验证、滑块等）：程序点了没用，只能人工过验证。 */
    private val riskKeywords = listOf(
        "检测到安全风险", "安全验证", "京东验证", "验证一下，购物无忧",
        "滑动验证", "拖动滑块", "请完成验证", "账号存在风险", "验证码拦截"
    )
    private var riskLogged = false

    private val privacyKeywords = listOf("号码保护", "隐私号码", "隐私保护")
    private val submitKeywords = listOf("立即支付", "提交订单")
    private val notOnSaleKeywords = listOf(
        "暂时不能购买", "未开售", "未开始", "即将开售", "已售罄", "已下架",
        "立即支付￥0", "提交订单￥0"
    )

    /** 真正跳到支付/收银台才有的文案（不能放「付款」/「立即付款」：确认页有「实付款」，待付款列表有「立即付款」）。 */
    private val payPageKeywords = listOf("收银台", "确认付款", "支付订单", "订单提交成功", "使用密码")

    /** 订单列表的空态文案。 */
    private val emptyOrderKeywords = listOf("暂无订单", "没有相关订单", "暂无相关订单", "快去挑选", "空空如也")

    /** 订单条目的特征文案（只用于确认「列表里有订单」，不用于判断提交成功）。 */
    private val orderEntryKeywords = listOf("去支付", "立即付款", "等待付款", "订单号", "实付款")

    suspend fun run(config: SeckillConfig) {
        val a11y = AutoBuyAccessibilityService.instance
        if (a11y == null) {
            LogBus.add("无障碍服务未开启，请先在设置中开启 AutoBuy 抢购服务")
            LogBus.setStage(Stage.IDLE)
            return
        }
        riskLogged = false
        noSelectionLogged = false

        LogBus.setStage(Stage.PREPARE)
        LogBus.add("网络时间校准中...")
        var offset = TimeSync.calibrateOffset()
        if (offset != 0L) {
            LogBus.add("网络时间校准成功，偏移 ${offset}ms")
        } else {
            LogBus.add("网络时间校准失败，先按本机时间；请确认手机已开启「自动确定日期和时间」")
        }
        fun now() = System.currentTimeMillis() + offset

        LogBus.add("目标时间：${format(config.targetTimeMs)}")
        if (config.keyword.isBlank()) {
            LogBus.add("未填商品关键词：回查只能确认「待付款列表有订单」，建议填写以便精确核验")
        }
        val navStart = config.targetTimeMs - config.navigateLeadSeconds * 1000L
        var retriedSync = offset != 0L
        while (now() < navStart) {
            val remain = navStart - now()
            // 启动时校准失败的话，离目标还早时再补一次（失败提示已在上面给过）
            if (!retriedSync && remain > 15_000) {
                retriedSync = true
                val retry = TimeSync.calibrateOffset()
                if (retry != 0L) {
                    offset = retry
                    LogBus.add("网络时间校准成功（重试），偏移 ${retry}ms")
                }
            }
            if (remain > 10_000) {
                LogBus.add("距开始就位还有 ${remain / 1000}s")
                delay(5000)
            } else {
                delay(50)
            }
        }

        val pkg: String
        val settleText: List<String>
        val settleColor: Int
        when (config.platform) {
            Platform.TAOBAO -> {
                pkg = taobaoPkg
                settleText = listOf("结算")
                settleColor = taobaoSettleColor
            }
            Platform.JD -> {
                pkg = jdPkg
                settleText = listOf("去结算", "结算")
                settleColor = jdSettleColor
            }
        }

        LogBus.setStage(Stage.NAVIGATE)
        if (!navigateToConfirm(a11y, pkg, settleText, settleColor)) {
            LogBus.add("暂未就位（购物车可能还没勾选商品），到点会重试")
        }

        LogBus.setStage(Stage.REFRESH)
        refreshUntilTarget(a11y, config, ::now, settleColor, pkg)

        LogBus.setStage(Stage.SUBMIT)
        val submitted = submitWithRetry(a11y, config, ::now, pkg, settleText, settleColor)
        if (!submitted) {
            // 一次提交都没点成，订单不可能存在，不必再去订单列表折腾
            LogBus.add("未能确认提交，请手动检查")
            LogBus.setStage(Stage.DONE)
            return
        }
        LogBus.setStage(Stage.VERIFY)
        LogBus.add("已提交，正在回查订单...")
        var verified = verifyOrder(a11y, config.platform, config.keyword, settleColor)
        if (!verified) {
            // 订单列表有延迟，补一次回查再下结论
            delay(2000)
            verified = verifyOrder(a11y, config.platform, config.keyword, settleColor)
        }
        LogBus.add(
            when {
                !verified -> "回查未确认，请手动到「待付款」核对"
                config.keyword.isBlank() ->
                    "回查通过：待付款列表有订单（未填关键词，可能含旧订单，请核对商品）"
                else -> "回查通过：待付款列表命中订单，请尽快付款"
            }
        )
        LogBus.setStage(Stage.DONE)
    }

    /**
     * 结果回查：提交后确认是否真的生成了订单。
     *
     * 不能按「页面有『付款/待付款』字样」就判成功：
     *  - 确认页有「实付款 / 付款方式」，contains 匹配「付款」会假成功；
     *  - 「我的淘宝 / 我的」页自带「待付款」入口，keyword 为空时会直接误判；
     *  - 待付款列表可能是空的，标题仍然存在。
     * 所以：先排除仍在确认页；只认收银台/确认付款等支付页文案；
     * 打开待付款后必须有订单条目，填了关键词还要命中商品标题。
     */
    private suspend fun verifyOrder(
        a11y: AutoBuyAccessibilityService,
        platform: Platform,
        keyword: String,
        color: Int
    ): Boolean {
        delay(1500)
        // 支付页在确认页之前判：京东收银台也可能有「立即支付」按钮
        if (isOnPayPage(a11y)) return true
        if (isOnConfirmPage(a11y, color)) {
            LogBus.add("回查：仍在确认订单页，未提交成功")
            return false
        }
        // 打开订单列表回查
        val homeClicked = if (platform == Platform.TAOBAO) {
            a11y.clickByTextOrDesc(listOf("我的淘宝"), contains = false, timeoutMs = 1500)
        } else {
            a11y.clickByTextOrDesc(listOf("我的"), contains = false, timeoutMs = 1500)
        }
        delay(1500)
        val ordersClicked = a11y.clickByTextOrDesc(listOf("待付款"), contains = false, timeoutMs = 2000)
        if (!homeClicked || !ordersClicked) {
            // 收银台/弹窗占着页面时打不开订单列表：这是「没查成」，不是「没订单」
            LogBus.add("回查：打不开「待付款」（页面被占用），请手动到待付款核对")
            return false
        }
        delay(2500)
        if (a11y.hasText(emptyOrderKeywords, contains = true) ||
            a11y.hasDesc(emptyOrderKeywords, contains = true)
        ) {
            LogBus.add("回查：待付款列表为空")
            return false
        }
        val hasOrder = a11y.hasText(orderEntryKeywords, contains = true) ||
            a11y.hasDesc(orderEntryKeywords, contains = true)
        if (!hasOrder) {
            LogBus.add("回查：未发现订单条目")
            return false
        }
        if (keyword.isNotBlank()) {
            val hit = a11y.hasText(listOf(keyword), contains = true) ||
                a11y.hasDesc(listOf(keyword), contains = true)
            LogBus.add(if (hit) "回查：待付款列表命中「$keyword」" else "回查：待付款列表未找到「$keyword」")
            return hit
        }
        return true
    }

    /**
     * 是否在确认订单页。
     *
     * 淘宝：读 content-desc/text。京东购物车与确认页都是纯自绘、没有文字，
     * 只能看底部按钮：确认页是整行通栏（「立即支付」），购物车是右下角小按钮。
     * 图片判断带 500ms 缓存，避免提交循环里被截图限频拖慢。
     */
    private suspend fun isOnConfirmPage(a11y: AutoBuyAccessibilityService, color: Int): Boolean {
        if (isNotOnSale(a11y)) return true
        if (a11y.hasDesc(submitKeywords, contains = true) ||
            a11y.hasText(submitKeywords, contains = true)
        ) return true
        return fullWidthBottomButton(a11y, color)
    }

    /** 底部是否整行通栏品牌色按钮（京东确认页的「立即支付」）。 */
    private suspend fun fullWidthBottomButton(a11y: AutoBuyAccessibilityService, color: Int): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - confirmShotAt < 500) return confirmShotHit
        val shot = screenshot(a11y) ?: return confirmShotHit
        val ratio = ImageMatcher.longestColorRun(shot, bottomButtonBand, color, 70)
        shot.recycle()
        confirmShotAt = SystemClock.elapsedRealtime()
        confirmShotHit = ratio >= fullWidthButtonRatio
        return confirmShotHit
    }

    /** 是否显示「未开售 / ￥0」等不可提交状态（淘宝 Weex 多是 text，必须两种都判）。 */
    private fun isNotOnSale(a11y: AutoBuyAccessibilityService): Boolean =
        a11y.hasDesc(notOnSaleKeywords, contains = true) ||
            a11y.hasText(notOnSaleKeywords, contains = true)

    /** 是否已经进入支付/收银台。 */
    private fun isOnPayPage(a11y: AutoBuyAccessibilityService): Boolean =
        a11y.hasText(payPageKeywords, contains = true) ||
            a11y.hasDesc(payPageKeywords, contains = true)

    /** 把目标 App 拉到前台。无障碍服务在前台任务豁免内，可直接 startActivity。 */
    private fun launchTargetApp(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            LogBus.add("找不到目标 App：$pkg")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            LogBus.add("打开目标 App 失败：${e.message}")
            false
        }
    }

    /** 自动就位：切到购物车 → 校验已勾选 → 结算 → 确认订单页。fast 用于到点后的快速重试。 */
    private suspend fun navigateToConfirm(
        a11y: AutoBuyAccessibilityService,
        pkg: String,
        settleText: List<String>,
        settleColor: Int,
        pkgWaitSeconds: Int = 15,
        fast: Boolean = false
    ): Boolean {
        // 只有已经在目标 App 里，「确认订单页」的判断才可信；
        // 否则会把本应用自己的宽按钮当成确认页，跳过就位（真机踩过）
        if (a11y.currentPackage() == pkg && isOnConfirmPage(a11y, settleColor)) return true

        // 主动把目标 App 拉到前台：无障碍服务启动 Activity 不受后台限制
        if (a11y.currentPackage() != pkg) {
            LogBus.add("目标 App 不在前台（当前：${a11y.currentPackage() ?: "未知"}），正在打开…")
            launchTargetApp(pkg)
            delay(if (fast) 1200 else 1800)
        }

        var waited = 0
        while (a11y.currentPackage() != pkg && waited < pkgWaitSeconds) {
            if (waited == 2 && pkgWaitSeconds > 3) {
                LogBus.add(
                    "未能自动切到目标 App，请手动切到购物车页（当前：${a11y.currentPackage() ?: "未知"}；${a11y.windowSummary()}）..."
                )
            }
            delay(1000)
            waited++
        }
        if (a11y.currentPackage() != pkg) {
            LogBus.add("未等到目标 App（当前：${a11y.currentPackage() ?: "未知"}；${a11y.windowSummary()}）")
            return false
        }
        if (isRiskPage(a11y)) {
            warnRiskOnce()
            return false
        }

        // 打开购物车（可能还停在商品详情页，先退回来）
        if (isProductDetailPage(a11y)) {
            LogBus.add("当前在商品详情页，先退回购物车")
            backToCart(a11y)
        }
        a11y.clickByTextOrDesc(listOf("购物车"), contains = true, timeoutMs = if (fast) 600 else 1500)
        delay(if (fast) 800 else 1500)

        // 只校验「购物车里已经勾选商品」，不再替用户勾选：
        // 复选框和商品图里都有圆，图像识别分不清是哪一个，点在商品上会跳详情页、
        // 点在别的商品上会把那件商品带进结算（#29）。
        if (!cartHasSelection(a11y)) {
            warnNoSelectionOnce()
            return false
        }

        // 点击「结算 / 去结算」
        val settleDeadline = System.currentTimeMillis() + if (fast) 2000 else 6000
        if (!clickSettle(a11y, settleText, settleColor, settleDeadline)) {
            return false
        }
        delay(if (fast) 1200 else 2500)

        var w = 0
        val maxW = if (fast) 4 else 8
        while (!isOnConfirmPage(a11y, settleColor) && w < maxW) {
            delay(500)
            w++
        }
        if (!isOnConfirmPage(a11y, settleColor)) {
            LogBus.add(
                "没有进入确认订单页：请确认购物车里已勾选要抢的商品、商品在售；" +
                    "当前：${a11y.currentPackage() ?: "未知"}，" +
                    "合计 ${cartTotal(a11y)?.let { "¥${money(it)}" } ?: "未读到"}"
            )
            return false
        }
        return true
    }

    /**
     * 当前是否在商品详情页（勾选点偏、点进商品图时会到这里，不处理就会在详情页和购物车之间反复跳）。
     *
     * strict=true 要求「立即购买」和「加入购物车」同时存在：京东首页商品流里也有「加入购物车」，
     * 只认一个会在首页被误判、把用户一路退出 App。刚点完复选框时用 strict=false 更灵敏。
     */
    private fun isProductDetailPage(a11y: AutoBuyAccessibilityService, strict: Boolean = true): Boolean {
        val hasBuy = a11y.hasText(listOf("立即购买"), contains = false)
        if (strict) return hasBuy && a11y.hasText(listOf("加入购物车"), contains = false)
        return hasBuy || a11y.hasText(listOf("加入购物车"), contains = false)
    }

    /** 从商品详情页退回购物车；退不回去返回 false。 */
    private suspend fun backToCart(a11y: AutoBuyAccessibilityService): Boolean {
        var tries = 0
        while (tries < 3 && isProductDetailPage(a11y, strict = false)) {
            a11y.back()
            tries++
            delay(700)
        }
        return !isProductDetailPage(a11y, strict = false)
    }

    /** 购物车底部结算栏的文字（合计金额、去结算等都在这里）。 */
    private fun cartBarTexts(a11y: AutoBuyAccessibilityService): List<String> =
        a11y.textsInRegion(settleRegion)

    /** 是否停在平台的风控/安全验证页（这类页面点什么都推进不了）。 */
    private fun isRiskPage(a11y: AutoBuyAccessibilityService): Boolean =
        a11y.hasText(riskKeywords, contains = true)

    private fun warnRiskOnce() {
        if (riskLogged) return
        riskLogged = true
        LogBus.add("检测到平台安全验证（风控），请在 App 里手动完成验证后重新开始；本轮不继续")
    }

    /**
     * 购物车合计金额（元）；读不到返回 null。
     *
     * 京东购物车是自绘的，只有底部「合计 ¥xx」能说明是否真的勾上了商品：
     * 结算按钮不管有没有选中都是红色，颜色判断不可靠（AGENTS 里也记了这点）。
     */
    private fun cartTotal(a11y: AutoBuyAccessibilityService): Double? {
        val texts = cartBarTexts(a11y)
        if (texts.isEmpty()) return null
        val labelAt = texts.indexOfFirst { t -> totalLabelKeywords.any { t.contains(it) } }
        // 「合计」和金额可能是两个节点：优先取「合计」之后的金额
        val afterLabel = if (labelAt >= 0) texts.drop(labelAt) else emptyList()
        for (t in afterLabel) {
            totalRegex.find(t)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
        }
        // 没有「合计」标签时取最右侧的金额（结算栏里合计是最靠右的那个）
        if (labelAt < 0) {
            for (t in texts.asReversed()) {
                totalRegex.find(t)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    /** 购物车是否已有选中商品；读不到合计时按未知处理（不拦流程）。 */
    private fun cartHasSelection(a11y: AutoBuyAccessibilityService): Boolean {
        val total = cartTotal(a11y) ?: return true
        return total > 0.0
    }

    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** 购物车没有勾选商品时提示一次（4 秒重试期间不刷屏）。 */
    private fun warnNoSelectionOnce() {
        if (noSelectionLogged) return
        noSelectionLogged = true
        LogBus.add("购物车里还没有勾选商品：请切到购物车勾选要抢的商品（京东加购默认已勾选），本轮先不结算")
    }

    /** 结算按钮：先试文字（京东），再用颜色（淘宝 Weex）。 */
    private suspend fun clickSettle(
        a11y: AutoBuyAccessibilityService,
        texts: List<String>,
        color: Int,
        deadline: Long
    ): Boolean {
        while (System.currentTimeMillis() < deadline) {
            // 商品详情页右下角也有通栏红按钮（立即购买），不能当结算点
            if (isProductDetailPage(a11y, strict = false)) {
                LogBus.add("当前在商品详情页，不点结算")
                return false
            }
            if (a11y.clickByTextOrDesc(texts, contains = true, timeoutMs = 150)) {
                LogBus.add("点击「${texts.first()}」（文字）")
                return true
            }
            val shot = screenshot(a11y)
            if (shot == null) {
                delay(250)
                continue
            }
            // 底部变成整行通栏按钮 = 已经在确认订单页（京东的「立即支付」就在这里），
            // 再点就变成直接下单付款了（真机测试时出过这个事故）
            val run = ImageMatcher.longestColorRun(shot, bottomButtonBand, color, 70)
            confirmShotAt = SystemClock.elapsedRealtime()
            confirmShotHit = run >= fullWidthButtonRatio
            if (confirmShotHit) {
                shot.recycle()
                LogBus.add("底部是整行通栏按钮（已在确认/支付页），不再点结算")
                return false
            }
            val p = ImageMatcher.findColorCentroid(shot, settleRegion, color, 70)
            shot.recycle()
            if (p != null) {
                a11y.tap(p.x.toFloat(), p.y.toFloat())
                LogBus.add("点击结算 @ ${p.x},${p.y}")
                return true
            }
            delay(60)
        }
        return false
    }

    /** 在确认订单页用「号码保护」开关刷新，直到目标时间；开卖前 5 秒才开始刷新。 */
    private suspend fun refreshUntilTarget(
        a11y: AutoBuyAccessibilityService,
        config: SeckillConfig,
        now: () -> Long,
        color: Int,
        pkg: String
    ) {
        val startAt = config.targetTimeMs - refreshLeadMs
        if (now() < startAt) {
            val onConfirm = a11y.currentPackage() == pkg && isOnConfirmPage(a11y, color)
            LogBus.add(
                if (onConfirm) "确认订单页已就位，开卖前 5 秒开始刷新（还有 ${(startAt - now()) / 1000}s）"
                else "尚未进入确认订单页，开卖后会自动重试（还有 ${(startAt - now()) / 1000}s）"
            )
            while (now() < startAt) delay(50)
        }
        val hasPrivacy = a11y.currentPackage() == pkg && (
            a11y.hasDesc(privacyKeywords, contains = true) ||
                a11y.hasText(privacyKeywords, contains = true)
            )
        if (!hasPrivacy) {
            LogBus.add("未发现「号码保护」开关，跳过刷新（到点直接提交）")
            while (now() < config.targetTimeMs) delay(50)
            return
        }
        LogBus.add("开始用「号码保护」刷新订单状态（间隔 ${config.refreshIntervalMs}ms）")
        var count = 0
        while (now() < config.targetTimeMs) {
            a11y.toggleByTextOrDesc(privacyKeywords, timeoutMs = 150)
            count++
            if (count % 8 == 0) LogBus.add("刷新中（已 $count 次）")
            delay(config.refreshIntervalMs)
        }
        LogBus.add("刷新结束，准备提交")
    }

    /**
     * 到点提交：快速连点提交按钮；
     *  - 在确认页但显示「未开售」→ 留在确认页用「号码保护」刷新开卖状态后继续点，
     *    绝不退回购物车重进（重进要 2~3s，会正好错过开卖瞬间）；
     *  - 只有「本轮从没进过确认页」且持续 4s 不在确认页，才做一次快速就位；
     *  - 只有按钮可用（非「未开售/￥0」）的点击，才作为提交成功判据；
     * 直到成功或超出窗口（窗口内不会提前结束）。
     */
    private suspend fun submitWithRetry(
        a11y: AutoBuyAccessibilityService,
        config: SeckillConfig,
        now: () -> Long,
        pkg: String,
        settleText: List<String>,
        settleColor: Int
    ): Boolean {
        val deadline = config.targetTimeMs + config.windowSeconds * 1000L
        var reachedConfirm = false
        var lastNavAt = 0L
        var offConfirmSince = 0L
        var submitClicks = 0
        var validSubmitAt = 0L
        var confirmGoneSince = 0L
        var refreshCount = 0
        var nextRefreshAt = 0L

        while (now() < deadline) {
            // 收银台可能跳出目标 App（拉起支付），先判支付页再判包名
            if (isOnPayPage(a11y)) return true
            val cur = a11y.currentPackage()
            // 窗口切换瞬间 currentPackage() 会返回 null，不能当成「离开了目标 App」
            if (cur != null && cur != pkg) {
                // 不在目标 App：绝不盲点，否则会把点击落在本应用自己的按钮上（真机踩过）
                if (now() - lastNavAt > 3_000) {
                    lastNavAt = now()
                    LogBus.add("不在目标 App（当前：$cur），正在切回…")
                    launchTargetApp(pkg)
                }
                delay(200)
                continue
            }
            if (!isOnConfirmPage(a11y, settleColor)) {
                if (isRiskPage(a11y)) {
                    warnRiskOnce()
                    return false
                }
                if (validSubmitAt > 0 && now() - validSubmitAt <= leaveConfirmGraceMs) {
                    // 点过有效提交后确认页消失，但要持续一段时间才认，
                    // 避免页面跳转/节点重建的瞬间被当成提交成功（#26 假成功）
                    if (confirmGoneSince == 0L) confirmGoneSince = now()
                    if (now() - confirmGoneSince >= leaveConfirmConfirmMs) return true
                }
                // 持续不在确认页：只有本轮从没进过确认页才重新就位；
                // 进过确认页就说明是页面自己走了（正在跳转/已提交），留在原地等，不退回购物车
                if (offConfirmSince == 0L) offConfirmSince = now()
                // 误进了商品详情页：马上退回购物车，别干等 4 秒
                if (isProductDetailPage(a11y)) {
                    LogBus.add("当前在商品详情页，退回购物车")
                    backToCart(a11y)
                    offConfirmSince = 0L
                    delay(200)
                    continue
                }
                if (!reachedConfirm && now() - offConfirmSince >= 4_000 && now() - lastNavAt > 3_000) {
                    lastNavAt = now()
                    offConfirmSince = 0L
                    LogBus.add("持续不在确认页，尝试快速就位...")
                    navigateToConfirm(a11y, pkg, settleText, settleColor, pkgWaitSeconds = 3, fast = true)
                    waitForConfirm(a11y, confirmReadyWaitMs, settleColor)
                }
                delay(150)
                continue
            }
            reachedConfirm = true
            offConfirmSince = 0L
            confirmGoneSince = 0L

            // 1) 点提交：文字/描述优先，取不到描述用颜色兜底
            if (clickSubmit(a11y, submitKeywords, settleColor)) {
                submitClicks++
                // 京东确认页的按钮是「立即支付」：点一次就会下单并拉起支付页。
                // 再点会落在支付页的按钮上（小额免密有被直接扣款的风险），所以只点一次。
                if (config.platform == Platform.JD) {
                    LogBus.add("已点击京东「立即支付」，请到京东完成付款")
                    return true
                }
                if (submitClicks == 1 || submitClicks % 10 == 0) LogBus.add("点击提交（第 $submitClicks 次）")
                // 按钮可用（不是「未开售/￥0」）才算有效提交。
                // 淘宝 Weex 的「暂时不能购买/￥0」是 text，必须 text + desc 都判。
                if (!isNotOnSale(a11y)) {
                    validSubmitAt = now()
                } else if (submitClicks == 1) {
                    LogBus.add("提交按钮显示未开售（￥0/暂时不能购买），继续刷新")
                }
            }

            // 2) 仍显示未开售 → 留在确认页用「号码保护」刷新开卖状态，不离开页面
            if (isNotOnSale(a11y)) {
                if (now() >= nextRefreshAt) {
                    a11y.toggleByTextOrDesc(privacyKeywords, timeoutMs = 150)
                    refreshCount++
                    nextRefreshAt = now() + config.refreshIntervalMs
                    if (refreshCount == 1 || refreshCount % 10 == 0) LogBus.add("未开售，已刷新开卖状态 $refreshCount 次")
                }
            } else {
                nextRefreshAt = now()
            }
            delay(150)
        }
        LogBus.add("提交窗口结束（共点击提交 $submitClicks 次，刷新 $refreshCount 次）")
        // 窗口结束时点过提交且已离开确认页，仍按成功处理，交回查确认；
        // 但还在确认页/未开售状态时不能算提交成功
        return submitClicks > 0 && (isOnPayPage(a11y) || !isOnConfirmPage(a11y, settleColor))
    }

    /** 点一次提交：文字/描述优先，取不到描述用颜色兜底（淘宝 Weex 提交按钮同为橙色、同在右下）。 */
    private suspend fun clickSubmit(
        a11y: AutoBuyAccessibilityService,
        keywords: List<String>,
        settleColor: Int
    ): Boolean {
        if (a11y.clickByTextOrDesc(keywords, contains = true, timeoutMs = 150)) return true
        val p = findColorCentroid(a11y, settleColor) ?: return false
        a11y.tap(p.x.toFloat(), p.y.toFloat())
        return true
    }

    /** 等待确认订单页就绪（出现「提交订单/立即支付」）；就绪返回 true。 */
    private suspend fun waitForConfirm(
        a11y: AutoBuyAccessibilityService,
        timeoutMs: Long,
        color: Int
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isOnConfirmPage(a11y, color)) return true
            delay(120)
        }
        return false
    }

    /** 按颜色找右下角的提交按钮（截图失败返回 null）。 */
    private suspend fun findColorCentroid(a11y: AutoBuyAccessibilityService, color: Int): Point? {
        val shot = screenshot(a11y) ?: return null
        val p = ImageMatcher.findColorCentroid(shot, settleRegion, color, 70)
        shot.recycle()
        return p
    }

    /**
     * 无障碍截图：限制最小间隔（系统要求 ≥333ms），并记录失败原因。
     * 失败不直接放弃，由调用方按 deadline 重试。
     */
    private suspend fun screenshot(a11y: AutoBuyAccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!noScreenshotLogged) {
                noScreenshotLogged = true
                LogBus.add("Android 11 以下不支持无障碍截图，购物车勾选会不可用")
            }
            return null
        }
        val gap = shotMinGapMs - (SystemClock.elapsedRealtime() - lastShotAt)
        if (gap > 0) delay(gap)
        lastShotAt = SystemClock.elapsedRealtime()
        val (bitmap, code) = try {
            a11y.screenshotWithCode()
        } catch (e: Exception) {
            LogBus.add("截图异常：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}")
            return null
        }
        if (bitmap == null) {
            if (code != lastShotError) LogBus.add("截图失败：${shotErrorText(code)}")
            lastShotError = code
        } else {
            lastShotError = 0
        }
        return bitmap
    }

    private fun shotErrorText(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "调用太频繁（系统要求间隔 ≥333ms）"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无效的屏幕"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "没有截图权限：请到系统设置里把 AutoBuy 的无障碍服务关掉再重新开启"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面禁止截图（安全窗口）"
        AutoBuyAccessibilityService.ERROR_DISPATCH_FAILED -> "截图请求派发失败（服务可能未连接）"
        AutoBuyAccessibilityService.ERROR_EXCEPTION -> "takeScreenshot 抛异常（缺少 canTakeScreenshot 能力）"
        AutoBuyAccessibilityService.ERROR_TIMEOUT -> "2 秒内没有截图回调"
        0 -> "系统返回空截图（部分厂商 ROM 不支持硬件缓冲转换）"
        else -> "错误码 $code"
    }

    private fun format(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
