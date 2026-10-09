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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autobuy.app.core.LogBus
import com.autobuy.app.core.Permissions
import com.autobuy.app.seckill.Stage
import com.autobuy.app.ui.components.AppCard
import com.autobuy.app.ui.components.RowDivider
import com.autobuy.app.ui.components.ScreenHeader
import com.autobuy.app.ui.components.SectionTitle
import com.autobuy.app.ui.components.rememberPermissionState

/** 指引页：准备状态（权限）+ 抢购流程 + 说明。 */
@Composable
fun GuideScreen() {
    val context = LocalContext.current
    val stage by LogBus.stage.collectAsState()
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
            SectionTitle("准备状态")
            AppCard {
                StatusRow(
                    ok = permission.accessibility,
                    title = "无障碍服务",
                    desc = if (permission.accessibility) {
                        "已开启，可自动操作淘宝 App"
                    } else {
                        "抢购必需，请在系统设置中开启"
                    },
                    actionText = "去开启",
                    onAction = { Permissions.openAccessibilitySettings(context) }
                )
                RowDivider()
                StatusRow(
                    ok = permission.overlay,
                    title = "悬浮指引",
                    desc = if (permission.overlay) {
                        "已授权，抢购时在淘宝上显示当前阶段"
                    } else {
                        "可选，便于随时看到进度"
                    },
                    actionText = "去授权",
                    onAction = { Permissions.requestOverlay(context) }
                )
            }

            SectionTitle("抢购流程")
            FlowCard(current = stage)

            SectionTitle("说明")
            AppCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "移动端全自动：到点前自动就位，确认页低频刷新，到点自动提交，全程有指引。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "刷新使用确认订单页的「号码保护」开关，节奏低频可控；若该页没有此开关，则跳过刷新、到点直接提交。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "请确保淘宝已登录、目标商品在购物车，并保持屏幕常亮。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    title: String,
    desc: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!ok) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun FlowCard(current: Stage) {
    AppCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Stage.entries.filter { it != Stage.IDLE }.forEach { s ->
                val active = s == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            s.tip,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
