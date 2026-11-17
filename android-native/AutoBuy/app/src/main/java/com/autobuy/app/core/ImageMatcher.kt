package com.autobuy.app.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.abs

/**
 * 轻量图像识别（不依赖 OpenCV）：区域内目标颜色的质心。
 *
 * 只用于纯色按钮（结算 / 提交）：购物车勾选已改为「只校验不代替用户勾选」，
 * 模板匹配（灰环）整块删掉了 —— 复选框和商品图里都有圆，识别分不清，
 * 会点在商品图上或别的商品上（#29）。
 */
object ImageMatcher {

    /**
     * 区域内接近 [target] 的颜色质心。
     * 返回 null 表示该区域没有足够的同色像素（按钮不在这里，或不是这个颜色）。
     */
    fun findColorCentroid(
        screen: Bitmap,
        regionFrac: FloatArray,
        target: Int,
        tolerance: Int,
        minCount: Int = 2000
    ): Point? {
        val sw = screen.width
        val sh = screen.height
        val l = (regionFrac[0] * sw).toInt().coerceIn(0, sw - 1)
        val t = (regionFrac[1] * sh).toInt().coerceIn(0, sh - 1)
        val r = (regionFrac[2] * sw).toInt().coerceIn(l + 1, sw)
        val b = (regionFrac[3] * sh).toInt().coerceIn(t + 1, sh)
        val tr = Color.red(target)
        val tg = Color.green(target)
        val tb = Color.blue(target)
        var sumX = 0L
        var sumY = 0L
        var count = 0
        for (y in t until b) {
            for (x in l until r) {
                val p = screen.getPixel(x, y)
                if (abs(Color.red(p) - tr) <= tolerance &&
                    abs(Color.green(p) - tg) <= tolerance &&
                    abs(Color.blue(p) - tb) <= tolerance
                ) {
                    sumX += x; sumY += y; count++
                }
            }
        }
        if (count < minCount) return null
        return Point((sumX / count).toInt(), (sumY / count).toInt())
    }

    /**
     * 底部区域里最长的一段连续同色像素，占屏幕宽度的比例（0~1）。
     *
     * 用来区分两种按钮：购物车的「去结算」只在右下角（一段约 0.3 宽），
     * 确认订单页的「立即支付 / 提交订单」是整行通栏（接近 1.0 宽）。
     * 京东购物车没有任何文字节点，只能靠这个判断「现在是不是已经在确认页」。
     */
    fun longestColorRun(
        screen: Bitmap,
        regionFrac: FloatArray,
        target: Int,
        tolerance: Int
    ): Float {
        val w = screen.width
        val h = screen.height
        val l = (regionFrac[0] * w).toInt().coerceIn(0, w - 1)
        val r = (regionFrac[2] * w).toInt().coerceIn(l + 1, w)
        val t = (regionFrac[1] * h).toInt().coerceIn(0, h - 1)
        val b = (regionFrac[3] * h).toInt().coerceIn(t + 1, h)
        val tr = Color.red(target)
        val tg = Color.green(target)
        val tb = Color.blue(target)
        var best = 0
        for (y in t until b) {
            var run = 0
            for (x in l until r) {
                val p = screen.getPixel(x, y)
                val match = abs(Color.red(p) - tr) <= tolerance &&
                    abs(Color.green(p) - tg) <= tolerance &&
                    abs(Color.blue(p) - tb) <= tolerance
                if (match) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
        }
        return best.toFloat() / w
    }
}
