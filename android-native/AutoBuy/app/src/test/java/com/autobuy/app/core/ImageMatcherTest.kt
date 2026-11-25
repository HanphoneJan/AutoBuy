package com.autobuy.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ImageMatcher.longestRunInBox] 的离线单测（纯 JVM，不加载图片、不碰 android.graphics）。
 *
 * 这个比例是「现在在购物车还是在确认订单页」的唯一依据（京东那两个页面一个文字节点都没有）：
 * 购物车「去结算」约 0.25、确认页「立即支付 / 提交订单」约 0.87。
 * 判成通栏却其实在购物车 → 会把确认页当结算页反复点；判反了 → 直接点「立即支付」生成订单。
 */
class ImageMatcherTest {

    private companion object {
        /** 京东按钮红（「去结算 / 立即支付」都是这个色系） */
        const val JD_RED = 0xFFE1251B.toInt()

        /** 背景：白色，和目标色差得远 */
        const val BG = 0xFFFFFFFF.toInt()

        /** 整屏区域 */
        val FULL = floatArrayOf(0f, 0f, 1f, 1f)

        /** 只取屏幕最下面 10% 的一条（和 SeckillEngine 的 bottomButtonBand 用法一致） */
        val BOTTOM_BAND = floatArrayOf(0f, 0.9f, 1f, 1f)
    }

    /** 内存画布：pixels[y * width + x] 就是 (x, y) 的 ARGB */
    private class Canvas(val width: Int, val height: Int) {
        val pixels = IntArray(width * height) { BG }

        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int = JD_RED) {
            for (y in y0..y1) {
                for (x in x0..x1) {
                    pixels[y * width + x] = color
                }
            }
        }

        fun ratio(
            regionFrac: FloatArray = FULL,
            target: Int = JD_RED,
            tolerance: Int = 20
        ): Float = ImageMatcher.longestRunInBox(
            width = width,
            height = height,
            regionFrac = regionFrac,
            pixelAt = { x, y -> pixels[y * width + x] },
            target = target,
            tolerance = tolerance
        )
    }

    @Test
    fun fullWidthRowIsAboutOne() {
        val canvas = Canvas(100, 10)
        canvas.fill(0, 0, 99, 9)

        assertEquals(1.0f, canvas.ratio(), 1e-3f)
    }

    @Test
    fun rightCornerQuarterIsAboutZeroPointTwoFive_likeCheckoutButton() {
        // 购物车「去结算」：只在右下角，一段约 1/4 屏宽
        val canvas = Canvas(100, 40)
        canvas.fill(0, 0, 99, 35)          // 上半屏整行同色，用来证明区域裁剪真的生效
        canvas.fill(75, 36, 99, 39)        // 底部条只填右边 1/4

        assertEquals(0.25f, canvas.ratio(BOTTOM_BAND), 1e-3f)
    }

    @Test
    fun centeredWideBarIsAboutZeroPointEightSeven_likePayButton() {
        // 确认订单页「立即支付」：居中通栏，两侧各留一点边距
        val canvas = Canvas(100, 40)
        canvas.fill(6, 36, 92, 39)         // 87 / 100 = 0.87

        assertEquals(0.87f, canvas.ratio(BOTTOM_BAND), 1e-3f)
    }

    @Test
    fun segmentsOnAdjacentRowsDoNotAccumulate() {
        // 宽 10 高 4：偶数行是行尾 6 个像素，奇数行是行首 6 个像素。
        // 单行最长连续段只有 6（= 0.6）；如果 run 不在每行开头归零，
        // 上一行行尾 6 + 下一行行首 6 会被接成 12，返回值 > 1。
        val canvas = Canvas(10, 4)
        canvas.fill(4, 0, 9, 0)
        canvas.fill(0, 1, 5, 1)
        canvas.fill(4, 2, 9, 2)
        canvas.fill(0, 3, 5, 3)

        assertEquals(0.6f, canvas.ratio(), 1e-3f)
    }

    @Test
    fun colorDiffEqualToToleranceStillMatches() {
        val canvas = Canvas(10, 1)
        canvas.fill(0, 0, 9, 0, color = 0xFF6E6E6E.toInt())   // 100 + 10

        // 每个通道差都是 10，正好等于 tolerance → 算命中，整行连成 1.0
        assertEquals(1.0f, canvas.ratio(target = 0xFF646464.toInt(), tolerance = 10), 1e-3f)
    }

    @Test
    fun colorDiffOverToleranceDoesNotMatch() {
        val canvas = Canvas(10, 1)
        canvas.fill(0, 0, 9, 0, color = 0xFF6F6F6F.toInt())   // 100 + 11

        // 差 11 > tolerance 10 → 一个都不算命中
        assertEquals(0.0f, canvas.ratio(target = 0xFF646464.toInt(), tolerance = 10), 1e-3f)
    }
}
