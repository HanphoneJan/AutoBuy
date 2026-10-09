package com.autobuy.app.seckill

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
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
 *  就位  自动切到购物车 → 图像识别勾选目标商品 → 点「结算/去结算」→ 进入确认订单页
 *  刷新  在确认订单页用「号码保护」开关刷新订单状态（低频可控）
 *  提交  到点快速点击「提交订单 / 立即支付」；
 *        未在确认页则重新就位，未开售则重进结算页刷新，直到成功或超出窗口
 *
 * 淘宝：购物车/确认页为 Weex，图像识别；提交按钮 content-desc。
 * 京东：购物车为自绘视图，图像识别；普通订单确认页有「号码保护」开关。
 * 预售/预约/定时开卖：开售前不可选或显示"暂时不能购买"，到点后重试即可。
 */
class SeckillEngine(private val context: Context) {

    private val taobaoPkg = "com.taobao.taobao"
    private val jdPkg = "com.jingdong.app.mall"

    private val taobaoSettleColor = Color.rgb(0xFF, 0x6A, 0x00)
    private val jdSettleColor = Color.rgb(0xE1, 0x25, 0x1B)

    private val settleRegion = floatArrayOf(0.55f, 0.82f, 1.0f, 0.97f)
    private val checkboxRegion = floatArrayOf(0.035f, 0.10f, 0.10f, 0.88f)

    private val privacyKeywords = listOf("号码保护", "隐私号码", "隐私保护")
    private val submitKeywords = listOf("立即支付", "提交订单")
    private val notOnSaleKeywords = listOf(
        "暂时不能购买", "未开售", "未开始", "即将开售", "立即支付￥0", "提交订单￥0"
    )

    suspend fun run(config: SeckillConfig) {
        val a11y = AutoBuyAccessibilityService.instance
        if (a11y == null) {
            LogBus.add("无障碍服务未开启，请先在设置中开启 AutoBuy 抢购服务")
            LogBus.setStage(Stage.IDLE)
            return
        }

        LogBus.setStage(Stage.PREPARE)
        LogBus.add("网络时间校准中...")
        val offset = TimeSync.calibrateOffset()
        LogBus.add(if (offset != 0L) "网络时间校准成功，偏移 ${offset}ms" else "网络时间校准失败，使用本机时间")
        fun now() = System.currentTimeMillis() + offset

        LogBus.add("目标时间：${format(config.targetTimeMs)}")
        val navStart = config.targetTimeMs - config.navigateLeadSeconds * 1000L
        while (now() < navStart) {
            val remain = navStart - now()
            if (remain > 10_000) {
                LogBus.add("距开始就位还有 ${remain / 1000}s")
                delay(5000)
            } else {
                delay(50)
            }
        }

        val pkg: String
        val checkboxTemplate: String
        val settleText: List<String>
        val settleColor: Int
        when (config.platform) {
            Platform.TAOBAO -> {
                pkg = taobaoPkg
                checkboxTemplate = "templates/taobao_cart_checkbox.png"
                settleText = listOf("结算")
                settleColor = taobaoSettleColor
            }
            Platform.JD -> {
                pkg = jdPkg
                checkboxTemplate = "templates/jd_cart_checkbox.png"
                settleText = listOf("去结算", "结算")
                settleColor = jdSettleColor
            }
        }

        LogBus.setStage(Stage.NAVIGATE)
        if (!navigateToConfirm(a11y, pkg, checkboxTemplate, settleText, settleColor)) {
            LogBus.add("暂未就位（预约/预售商品可能到点才可选），到点会重试")
        }

        LogBus.setStage(Stage.REFRESH)
        refreshUntilTarget(a11y, config, ::now)

        LogBus.setStage(Stage.SUBMIT)
        if (submitWithRetry(a11y, config, ::now, pkg, checkboxTemplate, settleText, settleColor)) {
            LogBus.add("已提交，请尽快确认付款")
            LogBus.setStage(Stage.DONE)
        } else {
            LogBus.add("未能确认提交，请手动检查")
            LogBus.setStage(Stage.VERIFY)
        }
    }

    /** 自动就位：切到购物车 → 选商品 → 结算 → 确认订单页。fast 用于到点后的快速重试。 */
    private suspend fun navigateToConfirm(
        a11y: AutoBuyAccessibilityService,
        pkg: String,
        checkboxTemplate: String,
        settleText: List<String>,
        settleColor: Int,
        pkgWaitSeconds: Int = 15,
        fast: Boolean = false
    ): Boolean {
        if (a11y.hasDesc(submitKeywords, contains = true)) return true

        var waited = 0
        while (a11y.currentPackage() != pkg && waited < pkgWaitSeconds) {
            if (waited == 0 && pkgWaitSeconds > 3) {
                LogBus.add("请切换到目标 App 并停留在购物车页（当前：${a11y.currentPackage() ?: "未知"}）...")
            }
            delay(1000)
            waited++
        }
        if (a11y.currentPackage() != pkg) return false

        // 打开购物车
        a11y.clickByTextOrDesc(listOf("购物车"), contains = true, timeoutMs = if (fast) 600 else 1500)
        delay(if (fast) 800 else 1500)

        // 勾选第一个未选中的商品（图像识别 + 环形校验，取最上方）
        val selectDeadline = System.currentTimeMillis() + if (fast) 2000 else 6000
        stepTemplate(a11y, checkboxTemplate, checkboxRegion, 0.86f, true, selectDeadline, "勾选第一个商品")
        delay(if (fast) 400 else 700)

        // 点击「结算 / 去结算」
        val settleDeadline = System.currentTimeMillis() + if (fast) 2000 else 6000
        if (!clickSettle(a11y, settleText, settleColor, settleDeadline)) {
            return false
        }
        delay(if (fast) 1200 else 2500)

        var w = 0
        val maxW = if (fast) 4 else 8
        while (!a11y.hasDesc(submitKeywords, contains = true) && w < maxW) {
            delay(500)
            w++
        }
        return true
    }

    /** 结算按钮：先试文字（京东），再用颜色（淘宝 Weex）。 */
    private suspend fun clickSettle(
        a11y: AutoBuyAccessibilityService,
        texts: List<String>,
        color: Int,
        deadline: Long
    ): Boolean {
        while (System.currentTimeMillis() < deadline) {
            if (a11y.clickByTextOrDesc(texts, contains = true, timeoutMs = 150)) {
                LogBus.add("点击「${texts.first()}」（文字）")
                return true
            }
            val shot = screenshot(a11y) ?: return false
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

    /** 在确认订单页用「号码保护」开关刷新，直到目标时间。 */
    private suspend fun refreshUntilTarget(
        a11y: AutoBuyAccessibilityService,
        config: SeckillConfig,
        now: () -> Long
    ) {
        val hasPrivacy = a11y.hasDesc(privacyKeywords, contains = true) ||
            a11y.hasText(privacyKeywords, contains = true)
        if (!hasPrivacy) {
            LogBus.add("未发现「号码保护」开关，跳过刷新（到点直接提交）")
            while (now() < config.targetTimeMs) delay(50)
            return
        }
        LogBus.add("开始用「号码保护」刷新订单状态（间隔 ${config.refreshIntervalMs}ms，略快于手动）")
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
     *  - 不在确认页 → 快速重新就位（预约商品到点才可选）；
     *  - 在确认页但未开售 → 退回购物车重进结算页刷新；
     * 直到成功或超出窗口。
     */
    private suspend fun submitWithRetry(
        a11y: AutoBuyAccessibilityService,
        config: SeckillConfig,
        now: () -> Long,
        pkg: String,
        checkboxTemplate: String,
        settleText: List<String>,
        settleColor: Int
    ): Boolean {
        val deadline = config.targetTimeMs + config.windowSeconds * 1000L
        var reenter = 0
        var reachedConfirm = false
        while (now() < deadline) {
            if (!a11y.hasDesc(submitKeywords, contains = true)) {
                // 曾到过确认页、现在离开 → 视为已提交
                if (reachedConfirm) return true
                navigateToConfirm(a11y, pkg, checkboxTemplate, settleText, settleColor, pkgWaitSeconds = 3, fast = true)
                if (a11y.hasDesc(submitKeywords, contains = true)) reachedConfirm = true
                continue
            }
            reachedConfirm = true
            val notOnSale = a11y.hasDesc(notOnSaleKeywords, contains = true)
            if (notOnSale && reenter < 12) {
                reenter++
                LogBus.add("商品尚未开售，重新进入结算页刷新（第 $reenter 次）...")
                a11y.back()
                delay(500)
                a11y.clickByTextOrDesc(listOf("购物车"), contains = true, timeoutMs = 600)
                delay(500)
                clickSettle(a11y, settleText, settleColor, System.currentTimeMillis() + 2000)
                delay(600)
                continue
            }
            a11y.clickByTextOrDesc(submitKeywords, contains = true, timeoutMs = 150)
            delay(150)
        }
        return reachedConfirm && !a11y.hasDesc(submitKeywords, contains = true)
    }

    private suspend fun stepTemplate(
        a11y: AutoBuyAccessibilityService,
        asset: String,
        region: FloatArray,
        threshold: Float,
        topmost: Boolean,
        deadline: Long,
        label: String
    ): Boolean {
        val tmpl = loadAsset(asset)
        if (tmpl == null) {
            LogBus.add("模板缺失：$asset")
            return false
        }
        while (System.currentTimeMillis() < deadline) {
            val shot = screenshot(a11y) ?: return false
            val radius = (tmpl.width * 38 / 100).coerceAtLeast(6)
            val p = ImageMatcher.findTemplate(shot, tmpl, region, threshold, topmost) { cx, cy ->
                ImageMatcher.isRingLike(shot, cx, cy, radius)
            }
            shot.recycle()
            if (p != null) {
                a11y.tap(p.x.toFloat(), p.y.toFloat())
                LogBus.add("$label @ ${p.x},${p.y}")
                return true
            }
            delay(60)
        }
        return false
    }

    private fun screenshot(a11y: AutoBuyAccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LogBus.add("Android 11 以下不支持无障碍截图")
            return null
        }
        return a11y.screenshot()
    }

    private fun loadAsset(path: String): Bitmap? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun format(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
