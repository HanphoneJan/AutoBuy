package com.autobuy.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AutoBuy 无障碍服务：负责节点查找/点击、坐标手势、无障碍截图。
 * 通过 [instance] 供抢购引擎调用。
 */
class AutoBuyAccessibilityService : AccessibilityService() {

    companion object {
        /** 截图派发失败（例如服务未连接）。 */
        const val ERROR_DISPATCH_FAILED = -100
        /** takeScreenshot 抛异常（例如缺少 canTakeScreenshot 能力）。 */
        const val ERROR_EXCEPTION = -101
        /** 2 秒内没有回调。 */
        const val ERROR_TIMEOUT = -102
        /** 系统返回成功但 Bitmap.wrapHardwareBuffer 失败（部分厂商 ROM）。 */
        const val ERROR_WRAP_FAILED = -103

        @Volatile
        var instance: AutoBuyAccessibilityService? = null
            private set

        fun isReady(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 按文字点击；contains=true 模糊匹配。 */
    fun clickByText(texts: List<String>, contains: Boolean = false, timeoutMs: Long = 800): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            for (t in texts) {
                val node = findNode { n ->
                    val txt = n.text?.toString() ?: return@findNode false
                    if (contains) txt.contains(t) else txt == t
                }
                if (node != null && clickNode(node)) return true
            }
            sleepQuietly(80)
        }
        return false
    }

    /** 按 contentDescription 点击；contains=true 模糊匹配。 */
    fun clickByDesc(values: List<String>, contains: Boolean = true, timeoutMs: Long = 800): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            for (v in values) {
                val node = findNode { n ->
                    val d = n.contentDescription?.toString() ?: return@findNode false
                    if (contains) d.contains(v) else d == v
                }
                if (node != null && clickNode(node)) return true
            }
            sleepQuietly(80)
        }
        return false
    }

    /** 当前前台应用包名（优先活动窗口，其次任一有 root 的窗口）。 */
    fun currentPackage(): String? {
        try {
            rootInActiveWindow?.packageName?.toString()?.let { return it }
        } catch (_: Exception) {
        }
        return try {
            windows.mapNotNull { it.root?.packageName?.toString() }.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** 供日志排查：无障碍窗口/根节点数量（拿不到前台包名时用）。 */
    fun windowSummary(): String = try {
        val ws = windows
        "窗口 ${ws.size} 个，有内容 ${ws.count { it.root != null }} 个，活动窗口 ${if (rootInActiveWindow != null) "有" else "无"}"
    } catch (e: Exception) {
        "读取窗口异常：${e.javaClass.simpleName}"
    }

    /** 是否存在匹配的文字。 */
    fun hasText(values: List<String>, contains: Boolean = false): Boolean =
        findNode { n ->
            val t = n.text?.toString() ?: return@findNode false
            values.any { if (contains) t.contains(it) else t == it }
        } != null

    /** 是否存在匹配的 contentDescription。 */
    fun hasDesc(values: List<String>, contains: Boolean = true): Boolean =
        findNode { n ->
            val d = n.contentDescription?.toString() ?: return@findNode false
            values.any { if (contains) d.contains(it) else d == it }
        } != null

    /**
     * 读取某个屏幕区域内的文字/描述，按从上到下、从左到右排序。
     * 用于读购物车底部结算栏（京东自绘，只有这里的「合计 ¥xx」能反映是否真的勾上了商品）。
     */
    fun textsInRegion(regionFrac: FloatArray): List<String> {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val left = (regionFrac[0] * w).toInt()
        val top = (regionFrac[1] * h).toInt()
        val right = (regionFrac[2] * w).toInt()
        val bottom = (regionFrac[3] * h).toInt()

        val roots = try {
            windows.mapNotNull { it.root }
        } catch (_: Exception) {
            emptyList()
        }.ifEmpty {
            listOfNotNull(rootInActiveWindow)
        }

        val hits = ArrayList<Pair<Rect, String>>()
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard < 8000) {
                val n = queue.removeFirst()
                guard++
                val txt = (n.text ?: n.contentDescription)?.toString()
                if (!txt.isNullOrBlank()) {
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    if (rect.centerX() in left until right && rect.centerY() in top until bottom) {
                        hits.add(rect to txt)
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return hits.sortedWith(compareBy({ it.first.top }, { it.first.left })).map { it.second }
    }

    /** 按文字或描述点击（合并匹配，兼容「提交订单 / 立即支付」等不同文案）。 */
    fun clickByTextOrDesc(values: List<String>, contains: Boolean = true, timeoutMs: Long = 800): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            val node = findNode { n ->
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                values.any { v -> if (contains) t.contains(v) || d.contains(v) else t == v || d == v }
            }
            if (node != null && clickNode(node)) return true
            sleepQuietly(80)
        }
        return false
    }

    /** 按文字/描述找到开关并切换（用于「号码保护」刷新订单状态）。 */
    fun toggleByTextOrDesc(keywords: List<String>, timeoutMs: Long = 800): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            val node = findNode { n ->
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                keywords.any { t.contains(it) || d.contains(it) }
            }
            if (node != null) {
                var n: AccessibilityNodeInfo? = node
                var guard = 0
                while (n != null && guard < 6) {
                    if (n.isCheckable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    n = n.parent
                    guard++
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) return tap(rect.exactCenterX(), rect.exactCenterY())
            }
            sleepQuietly(80)
        }
        return false
    }

    /** 执行返回（全局动作），用于"未开售"时退回重新进入结算页。 */
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 在坐标处派发点击手势。 */
    fun tap(x: Float, y: Float): Boolean {        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true; latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) return false
        latch.await(600, TimeUnit.MILLISECONDS)
        return ok
    }

    /** 无障碍截图（Android 11+），无需 MediaProjection。 */
    @RequiresApi(Build.VERSION_CODES.R)
    fun screenshot(): Bitmap? = screenshotWithCode().first

    /**
     * 截图并返回失败原因（0 表示成功）。
     * 常见失败：1 调用太频繁（间隔需 ≥333ms）、3 没有无障碍截图权限（需重开服务）。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun screenshotWithCode(): Pair<Bitmap?, Int> {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        var errorCode = 0
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            result = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                            result = null
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                        latch.countDown()
                    }

                    override fun onFailure(code: Int) {
                        errorCode = code
                        latch.countDown()
                    }
                }
            )
        } catch (_: Exception) {
            return null to ERROR_EXCEPTION
        }
        if (!latch.await(2000, TimeUnit.MILLISECONDS)) return null to ERROR_TIMEOUT
        return result to errorCode
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) return tap(rect.exactCenterX(), rect.exactCenterY())
        return false
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val roots = try {
            windows.mapNotNull { it.root }
        } catch (_: Exception) {
            emptyList()
        }.ifEmpty {
            listOfNotNull(rootInActiveWindow)
        }
        for (root in roots) {
            bfs(root, predicate)?.let { return it }
        }
        return null
    }

    private fun bfs(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < 8000) {
            val n = queue.removeFirst()
            guard++
            if (predicate(n)) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
