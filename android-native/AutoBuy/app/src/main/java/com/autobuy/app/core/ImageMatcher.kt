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

    /** 最近一次匹配的最高分（即使未过阈值，也用于排查）。 */
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
        val all = findAll(screen, template, regionFrac, threshold, downscale, step, scales, validator)
        if (all.isEmpty()) return null
        return if (topmost) all.first() else all.maxByOrNull { it.score }
    }

    /**
     * 返回所有通过阈值与校验的匹配，按 y 从上到下排序（同 y 取分高的）。
     *
     * 勾选购物车复选框时不能只取最上方那一个：京东商品图里也有圆形（镜头、旋钮），
     * 排在上面的假候选会让点击落到商品图上、跳进商品详情页（#29）。
     * 拿到全部候选后由调用方逐个点击、用「合计」金额验证哪个才是真的复选框。
     */
    fun findAll(
        screen: Bitmap,
        template: Bitmap,
        regionFrac: FloatArray?,
        threshold: Float,
        downscale: Int = 2,
        step: Int = 2,
        scales: FloatArray = floatArrayOf(1f),
        validator: ((Int, Int, Int) -> Boolean)? = null
    ): List<Match> {
        val ds = downscale.coerceAtLeast(1)
        val sw = screen.width / ds
        val sh = screen.height / ds
        if (sw < 2 || sh < 2) return emptyList()

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
        val found = ArrayList<Match>()

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
                            addCandidate(found, Match(Point(cx, cy), score, scale), tw * ds)
                        }
                    }
                    x += step
                }
                y += step
            }
        }

        lastBestScore = globalBest
        found.sortWith(compareBy({ it.point.y }, { -it.score }))
        return if (found.size > maxCandidates) found.subList(0, maxCandidates).toList() else found
    }

    /** 最多返回多少个候选（勾选时逐个试，太多没意义）。 */
    private const val maxCandidates = 12

    /** 合并相邻的重复命中（步长扫描会命中同一目标多次），保留分数高的。 */
    private fun addCandidate(list: MutableList<Match>, m: Match, sizeReal: Int) {
        val r = (sizeReal / 2).coerceAtLeast(4)
        val i = list.indexOfFirst {
            abs(it.point.x - m.point.x) <= r && abs(it.point.y - m.point.y) <= r
        }
        if (i < 0) {
            list.add(m)
        } else if (m.score > list[i].score) {
            list[i] = m
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
     * 复选框是否已勾上。
     *
     * 京东购物车是纯自绘、无障碍读不到任何文字（没有「合计 ¥xx」可校验），
     * 但勾选后复选框会变成实心品牌色（京东红 / 淘宝橙），未勾选是白底灰环。
     * [radius] 传复选框的外半径，按 0.35~0.80 倍取 16 个方向采样，避开中间白色对勾。
     */
    fun isCheckedMark(screen: Bitmap, cx: Int, cy: Int, radius: Int): Boolean {
        if (radius < 4) return false
        val w = screen.width
        val h = screen.height
        var vivid = 0
        var total = 0
        for (i in 0 until 16) {
            val angle = Math.PI * i / 8.0
            for (f in checkSampleFactors) {
                val x = (cx + radius * f * cos(angle)).roundToInt().coerceIn(0, w - 1)
                val y = (cy + radius * f * sin(angle)).roundToInt().coerceIn(0, h - 1)
                val p = screen.getPixel(x, y)
                val chroma = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) -
                    minOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                total++
                if (chroma >= 60) vivid++
            }
        }
        return vivid >= total * 40 / 100
    }

    private val checkSampleFactors = floatArrayOf(0.35f, 0.5f, 0.65f, 0.8f)

    /**
     * 判断 (cx,cy) 处是否是"浅底 + 灰色环"（用于排除商品图、图标、文字边缘）。
     *
     * [templateWidth] 是这一档缩放后模板的实际宽度（像素）。环在模板里只占约 38%，
     * 但真机上环的直径和模板不等比（同模板会按 0.65~1.25 倍命中），
     * 所以按 0.30~0.48 多个半径各试一次 —— 只用单一半径时，
     * 真复选框（环更小）会被判掉，商品图里的圆反而留下（#29）。
     */
    fun isRingLike(screen: Bitmap, cx: Int, cy: Int, templateWidth: Int): Boolean {
        val w = screen.width
        val h = screen.height
        fun pixel(x: Int, y: Int): Int =
            screen.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))

        val center = pixel(cx, cy)
        val centerLum = lum(center)
        if (centerLum < 200) return false

        for (f in ringRadiusFactors) {
            val radius = (templateWidth * f).roundToInt().coerceAtLeast(3)
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
            if (hits >= 12) return true
        }
        return false
    }

    /** 环半径相对模板宽度的比例（模板里的环约 0.38，真机可能偏大偏小）。 */
    private val ringRadiusFactors = floatArrayOf(0.30f, 0.34f, 0.38f, 0.42f, 0.46f)

    private fun lum(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
