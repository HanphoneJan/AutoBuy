package com.autobuy.app.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 轻量图像识别（不依赖 OpenCV）：
 *  - findTemplate：灰度 SAD 模板匹配，支持多尺度、比例区域、取最上方
 *  - findColorCentroid：区域内目标颜色的质心（用于纯色按钮）
 */
object ImageMatcher {

    fun findTemplate(
        screen: Bitmap,
        template: Bitmap,
        regionFrac: FloatArray?,
        threshold: Float,
        topmost: Boolean,
        downscale: Int = 2,
        step: Int = 2,
        scales: FloatArray = floatArrayOf(1f),
        validator: ((Int, Int, Int) -> Boolean)? = null
    ): Point? {
        val ds = downscale.coerceAtLeast(1)
        val sw = screen.width / ds
        val sh = screen.height / ds
        if (sw < 2 || sh < 2) return null

        val sSmall = Bitmap.createScaledBitmap(screen, sw, sh, false)
        val sGray = IntArray(sw * sh)
        sSmall.getPixels(sGray, 0, sw, 0, 0, sw, sh)
        for (i in sGray.indices) sGray[i] = lum(sGray[i])

        val region = regionFrac?.let {
            intArrayOf(
                (it[0] * screen.width).toInt() / ds,
                (it[1] * screen.height).toInt() / ds,
                (it[2] * screen.width).toInt() / ds,
                (it[3] * screen.height).toInt() / ds
            )
        } ?: intArrayOf(0, 0, sw, sh)
        val rl = region[0].coerceIn(0, sw - 1)
        val rt = region[1].coerceIn(0, sh - 1)
        val rr = region[2].coerceIn(rl + 1, sw)
        val rb = region[3].coerceIn(rt + 1, sh)

        var topPoint: Point? = null
        var topY = Int.MAX_VALUE
        var bestPoint: Point? = null
        var bestScore = -1.0

        for (scale in scales) {
            val tw = (template.width * scale).roundToInt().coerceAtLeast(2) / ds
            val th = (template.height * scale).roundToInt().coerceAtLeast(2) / ds
            if (tw < 1 || th < 1 || tw >= sw || th >= sh) continue
            if (rr - rl < tw || rb - rt < th) continue

            val tSmall = Bitmap.createScaledBitmap(template, tw, th, false)
            val tGray = IntArray(tw * th)
            tSmall.getPixels(tGray, 0, tw, 0, 0, tw, th)
            for (i in tGray.indices) tGray[i] = lum(tGray[i])
            val maxSad = 255.0 * tw * th

            var y = rt
            while (y <= rb - th) {
                var x = rl
                while (x <= rr - tw) {
                    var sad = 0L
                    for (yy in 0 until th) {
                        val sBase = (y + yy) * sw + x
                        val tBase = yy * tw
                        for (xx in 0 until tw) sad += abs(sGray[sBase + xx] - tGray[tBase + xx]).toLong()
                    }
                    val score = 1.0 - sad / maxSad
                    if (score >= threshold) {
                        val cx = (x + tw / 2) * ds
                        val cy = (y + th / 2) * ds
                        if (validator == null || validator(cx, cy, tw * ds)) {
                            if (topmost) {
                                if (y < topY) {
                                    topY = y
                                    topPoint = Point(cx, cy)
                                }
                            } else if (score > bestScore) {
                                bestScore = score
                                bestPoint = Point(cx, cy)
                            }
                        }
                    }
                    x += step
                }
                y += step
            }
        }

        return if (topmost) topPoint else if (bestScore >= threshold) bestPoint else null
    }

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

    /** 判断 (cx,cy) 处是否是"浅底 + 灰色环"（用于排除商品图里的实心圆）。 */
    fun isRingLike(screen: Bitmap, cx: Int, cy: Int, radius: Int): Boolean {
        val w = screen.width
        val h = screen.height
        fun sample(x: Int, y: Int): Int {
            val px = screen.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            return lum(px)
        }
        val center = sample(cx, cy)
        val ring = (sample(cx - radius, cy) + sample(cx + radius, cy) +
            sample(cx, cy - radius) + sample(cx, cy + radius)) / 4
        return center >= 200 && ring <= center - 12
    }

    private fun lum(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
