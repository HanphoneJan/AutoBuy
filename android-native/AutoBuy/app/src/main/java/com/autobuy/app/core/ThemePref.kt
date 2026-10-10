package com.autobuy.app.core

import android.content.Context

/** 主题模式：跟随系统 / 浅色 / 深色。 */
object ThemePref {
    private const val NAME = "autobuy_prefs"
    private const val KEY = "theme_mode"

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun get(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, mode: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
    }
}
