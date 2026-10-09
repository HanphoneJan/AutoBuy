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

    /** 在坐标处派发点击手势。 */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
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
    fun screenshot(): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
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
                    } finally {
                        screenshot.hardwareBuffer.close()
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )
        latch.await(2000, TimeUnit.MILLISECONDS)
        return result
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
