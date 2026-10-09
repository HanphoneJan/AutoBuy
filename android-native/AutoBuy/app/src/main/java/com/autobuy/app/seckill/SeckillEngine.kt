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
 *  就位  自动切到购物车 → 图像识别勾选目标商品 → 颜色识别点「结算」→ 进入确认订单页
 *  刷新  在确认订单页用「号码保护」开关以轻柔节奏刷新订单状态（非网页端的高频连点）
 *  提交  到点自动点击「立即支付 / 提交订单」
 *
 * 每一步都通过 [LogBus] 的 Stage/日志给出明确指引。
 */
class SeckillEngine(private val context: Context) {

    private val checkboxRegion = floatArrayOf(0.035f, 0.12f, 0.10f, 0.85f)
    private val settleRegion = floatArrayOf(0.60f, 0.85f, 1.0f, 0.95f)
    private val orange = Color.rgb(0xFF, 0x6A, 0x00)
    private val taobaoPkg = "com.taobao.taobao"
    private val privacyKeywords = listOf("号码保护", "隐私号码", "隐私保护")
    private val submitKeywords = listOf("立即支付", "提交订单", "提交订单并付款")

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

        LogBus.setStage(Stage.NAVIGATE)
        if (!navigateToConfirm(a11y)) {
            LogBus.add("自动就位未完成，请确认淘宝已登录、购物车已打开")
        }

        LogBus.setStage(Stage.REFRESH)
        refreshUntilTarget(a11y, config, ::now)

        LogBus.setStage(Stage.SUBMIT)
        val ok = a11y.clickByTextOrDesc(submitKeywords, contains = true, timeoutMs = 4000)
        if (ok) {
            LogBus.add("已点击提交，请尽快确认付款")
            LogBus.setStage(Stage.DONE)
        } else {
            LogBus.add("未找到提交按钮，请手动检查")
            LogBus.setStage(Stage.VERIFY)
        }
    }

    /** 自动就位：切到购物车 → 选商品 → 结算 → 确认订单页。 */
    private suspend fun navigateToConfirm(a11y: AutoBuyAccessibilityService): Boolean {
        if (a11y.hasDesc(submitKeywords, contains = true)) {
            LogBus.add("已在确认订单页，跳过就位")
            return true
        }

        var waited = 0
        while (a11y.currentPackage() != taobaoPkg && waited < 15) {
            if (waited == 0) LogBus.add("请切换到淘宝 App 并停留在购物车页...")
            delay(1000)
            waited++
        }
        if (a11y.currentPackage() != taobaoPkg) {
            LogBus.add("未检测到淘宝在前台，跳过自动就位")
            return false
        }

        // 打开购物车（无障碍文字）
        a11y.clickByText(listOf("购物车"), contains = false, timeoutMs = 1500)
        delay(1500)

        // 勾选第一个未选中的商品（图像识别，取最上方）
        stepTemplate(
            a11y,
            "templates/taobao_cart_checkbox.png",
            checkboxRegion,
            0.88f,
            topmost = true,
            deadline = System.currentTimeMillis() + 6000,
            label = "勾选第一个商品"
        )
        delay(700)

        // 点击「结算」（颜色识别纯色按钮）
        val settled = stepSettle(a11y, System.currentTimeMillis() + 6000)
        if (!settled) {
            LogBus.add("未找到「结算」按钮")
            return false
        }
        delay(2500)

        // 等待确认订单页出现
        var w = 0
        while (!a11y.hasDesc(listOf("立即支付"), contains = true) && w < 8) {
            delay(500)
            w++
        }
        return true
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
        LogBus.add("开始用「号码保护」刷新订单状态（间隔 ${config.refreshIntervalMs}ms）")
        var count = 0
        while (now() < config.targetTimeMs) {
            a11y.toggleByTextOrDesc(privacyKeywords, timeoutMs = 300)
            count++
            if (count % 3 == 0) LogBus.add("刷新中（已 $count 次）")
            delay(config.refreshIntervalMs)
        }
        LogBus.add("刷新结束，准备提交")
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

    private suspend fun stepSettle(a11y: AutoBuyAccessibilityService, deadline: Long): Boolean {
        while (System.currentTimeMillis() < deadline) {
            val shot = screenshot(a11y) ?: return false
            val p = ImageMatcher.findColorCentroid(shot, settleRegion, orange, 70)
            shot.recycle()
            if (p != null) {
                a11y.tap(p.x.toFloat(), p.y.toFloat())
                LogBus.add("点击「结算」@ ${p.x},${p.y}")
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
