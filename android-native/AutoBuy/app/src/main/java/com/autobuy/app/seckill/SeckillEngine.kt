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
 * 抢购引擎：校准网络时间 → 等到目标时间 → 执行平台流程。
 * 淘宝流程（已在真机验证）：
 *   1. 图像识别勾选购物车第一个未选中的商品
 *   2. 颜色识别点击「结算」
 *   3. content-desc 点击「立即支付」
 */
class SeckillEngine(private val context: Context) {

    private val checkboxRegion = floatArrayOf(0.02f, 0.10f, 0.12f, 0.85f)
    private val settleRegion = floatArrayOf(0.60f, 0.85f, 1.0f, 0.95f)
    private val orange = Color.rgb(0xFF, 0x6A, 0x00)

    suspend fun run(config: SeckillConfig) {
        val a11y = AutoBuyAccessibilityService.instance
        if (a11y == null) {
            LogBus.add("无障碍服务未开启，请先在设置中开启 AutoBuy 抢购服务")
            return
        }

        LogBus.add("网络时间校准中...")
        val offset = TimeSync.calibrateOffset()
        LogBus.add(if (offset != 0L) "网络时间校准成功，偏移 ${offset}ms" else "网络时间校准失败，使用本机时间")

        fun now() = System.currentTimeMillis() + offset

        val start = config.targetTimeMs - config.leadSeconds * 1000L
        LogBus.add("目标时间：${format(config.targetTimeMs)}")
        while (now() < start) {
            val remain = start - now()
            if (remain > 10_000) {
                LogBus.add("距抢购还有 ${remain / 1000}s")
                delay(5000)
            } else {
                delay(50)
            }
        }
        LogBus.add("进入抢购窗口")

        val deadline = config.targetTimeMs + config.windowSeconds * 1000L
        when (config.platform) {
            Platform.TAOBAO -> runTaobao(a11y, deadline)
            Platform.JD -> LogBus.add("京东流程尚未实现")
        }
    }

    private suspend fun runTaobao(a11y: AutoBuyAccessibilityService, deadline: Long) {
        if (stepTemplate(a11y, "templates/taobao_cart_checkbox.png", checkboxRegion, 0.8f, true, deadline, "勾选第一个商品")) {
            delay(900)
        } else {
            LogBus.add("未找到商品复选框（请确认淘宝购物车已打开）")
        }

        if (stepSettle(a11y, deadline)) {
            delay(2500)
        } else {
            LogBus.add("未找到「结算」按钮，终止")
            return
        }

        val ok = a11y.clickByDesc(listOf("立即支付"), contains = true, timeoutMs = 3000)
        LogBus.add(if (ok) "已点击「立即支付」，请尽快确认付款" else "未找到「立即支付」，请手动检查")
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
            val p = ImageMatcher.findTemplate(shot, tmpl, region, threshold, topmost)
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
