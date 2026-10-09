package com.autobuy.app.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.abs

/**
 * 轻量图像识别（不依赖 OpenCV）：
 *  - findTemplate：灰度 SAD 模板匹配，支持缩放、比例区域、取最上方
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
        step: Int = 2
    ): Point? {
        val ds = downscale.coerceAtLeast(1)
        val sw = screen.width / ds
        val sh = screen.height / ds
        if (sw < 2 || sh < 2) return null
        val tw = (template.width / ds).coerceAtLeast(1)
        val th = (template.height / ds).coerceAtLeast(1)
        if (tw >= sw || th >= sh) return null

        val sSmall = Bitmap.createScaledBitmap(screen, sw, sh, false)
        val tSmall = Bitmap.createScaledBitmap(template, tw, th, false)
        val sGray = IntArray(sw * sh)
        sSmall.getPixels(sGray, 0, sw, 0, 0, sw, sh)
        val tGray = IntArray(tw * th)
        tSmall.getPixels(tGray, 0, tw, 0, 0, tw, th)
        for (i in sGray.indices) sGray[i] = lum(sGray[i])
        for (i in tGray.indices) tGray[i] = lum(tGray[i])

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
        if (rr - rl < tw || rb - rt < th) return null

        val maxSad = 255.0 * tw * th
        var bestX = -1
        var bestY = -1
        var bestScore = -1.0
        var topX = -1
        var topY = Int.MAX_VALUE

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
                if (topmost) {
                    if (score >= threshold && y < topY) {
                        topY = y; topX = x
                    }
                } else if (score > bestScore) {
                    bestScore = score; bestX = x; bestY = y
                }
                x += step
            }
            y += step
        }

        return if (topmost) {
            if (topX < 0) null else Point((topX + tw / 2) * ds, (topY + th / 2) * ds)
        } else {
            if (bestX < 0 || bestScore < threshold) null
            else Point((bestX + tw / 2) * ds, (bestY + th / 2) * ds)
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

    private fun lum(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
