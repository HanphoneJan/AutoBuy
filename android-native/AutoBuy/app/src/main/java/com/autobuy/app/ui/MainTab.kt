package com.autobuy.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

/** 底部导航 Tab（对齐 hanphone-blog 的 MainTab 结构）。 */
enum class MainTab(val route: String, val label: String, val icon: ImageVector) {
    SECKILL("seckill", "抢购", Icons.Filled.Bolt),
    GUIDE("guide", "指引", Icons.Filled.MenuBook),
    LOGS("logs", "日志", Icons.AutoMirrored.Filled.List),
    MINE("mine", "我的", Icons.Filled.Person)
}
