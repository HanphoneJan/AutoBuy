package com.autobuy.app.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 轻量图像识别（不依赖 OpenCV）：
 *  - findTemplate：灰度 SAD 模板匹配，支持多尺度、比例区域、取最上方
 *  - findColorCentroid：区域内目标颜色的质心（用于纯色按钮）
 */
object ImageMatcher {

    /** 一次模板匹配结果：位置、分数（0~1）、使用的缩放。 */
    data class Match(val point: Point, val score: Double, val scale: Float)

    /** 最近一次 findTemplate 的最高分（即使未过阈值，也用于排查）。 */
    var lastBestScore = 0.0
        private set

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
    ): Match? {
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

        var globalBest = 0.0
        var topPoint: Point? = null
        var topY = Int.MAX_VALUE
        var topScore = 0.0
        var topScale = 1f
        var bestPoint: Point? = null
        var bestScore = -1.0
        var bestScale = 1f

        for (scale in scales) {
            val tw = (template.width * scale).roundToInt().coerceAtLeast(2) / ds
            val th = (template.height * scale).roundToInt().coerceAtLeast(2) / ds
            if (tw < 1 || th < 1 || tw >= sw || th >= sh) continue
            if (rr - rl < tw || rb - rt < th) continue

            val tSmall = Bitmap.createScaledBitmap(template, tw, th, false)
            val tGray = IntArray(tw * th)
            tSmall.getPixels(tGray, 0, tw, 0, 0, tw, th)
            for (i in tGray.indices) tGray[i] = lum(tGray[i])

            // 只比较模板里有内容的像素（灰环），否则「大片白色」会和白底区域高分误匹配。
            val mask = BooleanArray(tw * th) { tGray[it] < 235 }
            val maskCount = mask.count { it }
            val useMask = maskCount >= tw * th / 10
            val maxSad = if (useMask) 255.0 * maskCount else 255.0 * tw * th

            var y = rt
            while (y <= rb - th) {
                var x = rl
                while (x <= rr - tw) {
                    var sad = 0L
                    for (yy in 0 until th) {
                        val sBase = (y + yy) * sw + x
                        val tBase = yy * tw
                        for (xx in 0 until tw) {
                            if (useMask && !mask[tBase + xx]) continue
                            sad += abs(sGray[sBase + xx] - tGray[tBase + xx]).toLong()
                        }
                    }
                    val score = 1.0 - sad / maxSad
                    if (score > globalBest) globalBest = score
                    if (score >= threshold) {
                        val cx = (x + tw / 2) * ds
                        val cy = (y + th / 2) * ds
                        if (validator == null || validator(cx, cy, tw * ds)) {
                            if (topmost) {
                                if (y < topY) {
                                    topY = y
                                    topPoint = Point(cx, cy)
                                    topScore = score
                                    topScale = scale
                                }
                            } else if (score > bestScore) {
                                bestScore = score
                                bestPoint = Point(cx, cy)
                                bestScale = scale
                            }
                        }
                    }
                    x += step
                }
                y += step
            }
        }

        lastBestScore = globalBest
        return if (topmost) {
            topPoint?.let { Match(it, topScore, topScale) }
        } else {
            if (bestScore >= threshold) bestPoint?.let { Match(it, bestScore, bestScale) } else null
        }
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

    /**
     * 判断 (cx,cy) 处是否是"浅底 + 灰色环"（用于排除商品图、图标、文字边缘）。
     * 16 个方向采样，要求环上多为「灰色且比中心暗」。
     */
    fun isRingLike(screen: Bitmap, cx: Int, cy: Int, radius: Int): Boolean {
        val w = screen.width
        val h = screen.height
        fun pixel(x: Int, y: Int): Int =
            screen.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))

        val center = pixel(cx, cy)
        val centerLum = lum(center)
        if (centerLum < 200) return false

        var hits = 0
        for (i in 0 until 16) {
            val angle = Math.PI * i / 8.0
            val x = cx + (radius * cos(angle)).roundToInt()
            val y = cy + (radius * sin(angle)).roundToInt()
            val p = pixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val chroma = maxOf(r, g, b) - minOf(r, g, b)
            if (lum(p) <= centerLum - 10 && chroma <= 20) hits++
        }
        return hits >= 12
    }

    private fun lum(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
