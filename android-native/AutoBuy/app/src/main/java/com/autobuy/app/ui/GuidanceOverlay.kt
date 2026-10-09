package com.autobuy.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮指引条：抢购时覆盖在淘宝之上，实时显示当前阶段与提示（不可触摸，不挡点击）。
 * 需要「显示在其他应用上层」权限；未授权时静默跳过。
 *
 * 所有对 View 的操作都切到主线程（服务端在后台协程调用）。
 */
class GuidanceOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: LinearLayout? = null
    private var titleView: TextView? = null
    private var tipView: TextView? = null

    fun update(stageLabel: String, tip: String) {
        main.post {
            if (view == null) create()
            titleView?.text = "AutoBuy · $stageLabel"
            tipView?.text = tip
        }
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            try {
                wm?.removeView(v)
            } catch (_: Exception) {
            }
            view = null
            wm = null
            titleView = null
            tipView = null
        }
    }

    private fun create() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC0F172A"))
            setPadding(30, 18, 30, 18)
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        val tip = TextView(context).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
        }
        container.addView(title)
        container.addView(tip)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 140
        }
        try {
            windowManager.addView(container, params)
            view = container
            titleView = title
            tipView = tip
        } catch (_: Exception) {
            view = null
        }
    }
}
