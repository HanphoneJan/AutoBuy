package com.autobuy.app.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.autobuy.app.accessibility.AutoBuyAccessibilityService

object Permissions {

    /** 判断 AutoBuy 的无障碍服务是否已在系统设置中开启。 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${AutoBuyAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
