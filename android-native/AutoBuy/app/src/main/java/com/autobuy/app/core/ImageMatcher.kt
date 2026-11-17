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
}
