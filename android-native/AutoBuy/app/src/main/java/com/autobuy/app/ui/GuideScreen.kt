package com.autobuy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autobuy.app.core.Permissions
import com.autobuy.app.ui.components.AppCard
import com.autobuy.app.ui.components.RowDivider
import com.autobuy.app.ui.components.ScreenHeader
import com.autobuy.app.ui.components.SectionTitle
import com.autobuy.app.ui.components.rememberPermissionState

/** 指引页：清楚区分「你要做的」和「App 自动做的」。 */
@Composable
fun GuideScreen(onGoSeckill: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberPermissionState()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("指引")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ===== 你要做的 =====
            SectionTitle("你要做的")
            AppCard {
                StepRow(
                    index = 1,
                    title = "开启无障碍服务",
                    desc = "必需。让 AutoBuy 能操作淘宝/京东 App"
                ) {
                    StatusAction(
                        ok = permission.accessibility,
                        actionText = "去开启",
                        onAction = { Permissions.openAccessibilitySettings(context) }
                    )
                }
                RowDivider()
                StepRow(
                    index = 2,
                    title = "在淘宝/京东里登录并加入购物车",
                    desc = "打开目标 App 登录，把要抢的商品加入购物车；预约/预售商品请先完成预约"
                )
                RowDivider()
                StepRow(
                    index = 3,
                    title = "在「抢购」页设置并开始",
                    desc = "选平台 → 设抢购时间（可用「今天 12:00」）→ 点「开始抢购」"
                ) {
                    TextButton(onClick = onGoSeckill) { Text("去设置") }
                }
                RowDivider()
                StepRow(
                    index = 4,
                    title = "切到目标 App 的购物车页",
                    desc = "保持屏幕常亮，剩下的交给 AutoBuy；可开「悬浮指引」看进度"
                )
            }

            // ===== App 自动做的 =====
            SectionTitle("AutoBuy 会自动完成")
            AppCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AutoItem("到点前自动勾选商品，进入结算页")
                    AutoItem("用「号码保护」开关刷新开卖状态")
                    AutoItem("到点自动点击「提交订单 / 立即支付」")
                    AutoItem("提交后请尽快回到 App 完成付款")
                }
            }

            // ===== 悬浮指引 =====
            SectionTitle("悬浮指引（可选）")
            AppCard {
                StepRow(
                    index = 5,
                    title = "显示在其他应用上层",
                    desc = "授权后，抢购时会在淘宝上实时显示当前阶段"
                ) {
                    StatusAction(
                        ok = permission.overlay,
                        actionText = "去授权",
                        onAction = { Permissions.requestOverlay(context) }
                    )
                }
            }

            // ===== 注意事项 =====
            SectionTitle("注意事项")
            AppCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AutoItem("无障碍服务可能被系统关闭，抢购前请确认这里显示「已开启」")
                    AutoItem("抢购前不要重装或强制停止 AutoBuy，否则无障碍会掉")
                    AutoItem("本工具仅用于学习交流，自动化可能违反平台条款，账号风险自负")
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    title: String,
    desc: String,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$index",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

@Composable
private fun StatusAction(ok: Boolean, actionText: String, onAction: () -> Unit) {
    if (ok) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
    } else {
        TextButton(onClick = onAction) { Text(actionText) }
    }
}

@Composable
private fun AutoItem(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("·", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
