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
    ): Float = longestRunInBox(
        width = screen.width,
        height = screen.height,
        regionFrac = regionFrac,
        pixelAt = { x, y -> screen.getPixel(x, y) },
        target = target,
        tolerance = tolerance
    )

    /**
     * [longestColorRun] 的纯函数版本：只吃尺寸 + 取像素的 lambda，不碰 android.graphics，
     * 所以能在 JVM 单元测试里跑（Bitmap / Color 在单测里是 stub，一调用就抛异常）。
     *
     * 连续段是**按行**统计的：`run` 必须声明在内层 `y` 循环里，每行从 0 开始。
     * 否则「上一行行尾的连续段」会和「下一行行首的连续段」接起来，
     * 算出的长度能超过屏幕宽度（例：宽 10，行尾 6 + 下一行行首 6 = 12/10 > 1），
     * 把购物车「去结算」误判成确认页的通栏按钮 —— 那会点错「立即支付」直接下单。
     */
    fun longestRunInBox(
        width: Int,
        height: Int,
        regionFrac: FloatArray,
        pixelAt: (x: Int, y: Int) -> Int,
        target: Int,
        tolerance: Int
    ): Float {
        if (width <= 0 || height <= 0) return 0f
        val l = (regionFrac[0] * width).toInt().coerceIn(0, width - 1)
        val r = (regionFrac[2] * width).toInt().coerceIn(l + 1, width)
        val t = (regionFrac[1] * height).toInt().coerceIn(0, height - 1)
        val b = (regionFrac[3] * height).toInt().coerceIn(t + 1, height)
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        var best = 0
        for (y in t until b) {
            var run = 0
            for (x in l until r) {
                val p = pixelAt(x, y)
                val match = abs(((p shr 16) and 0xFF) - tr) <= tolerance &&
                    abs(((p shr 8) and 0xFF) - tg) <= tolerance &&
                    abs((p and 0xFF) - tb) <= tolerance
                if (match) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
        }
        return best.toFloat() / width
    }
}
