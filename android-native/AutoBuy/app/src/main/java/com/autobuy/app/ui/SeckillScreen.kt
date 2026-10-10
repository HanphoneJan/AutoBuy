package com.autobuy.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autobuy.app.core.LogBus
import com.autobuy.app.seckill.Platform
import com.autobuy.app.seckill.SeckillConfig
import com.autobuy.app.seckill.SeckillService
import com.autobuy.app.seckill.Stage
import com.autobuy.app.ui.components.AppCard
import com.autobuy.app.ui.components.CompactField
import com.autobuy.app.ui.components.RowDivider
import com.autobuy.app.ui.components.ScreenHeader
import com.autobuy.app.ui.components.SectionTitle
import com.autobuy.app.ui.components.StatusPill
import com.autobuy.app.ui.components.rememberPermissionState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 抢购页：配置 + 开始/停止 + 当前阶段。 */
@Composable
fun SeckillScreen(onOpenGuide: () -> Unit) {
    val context = LocalContext.current
    val running by LogBus.running.collectAsState()
    val stage by LogBus.stage.collectAsState()
    val permission = rememberPermissionState()

    var platform by remember { mutableStateOf(Platform.TAOBAO) }
    var targetText by remember { mutableStateOf(defaultTargetText()) }
    var keyword by remember { mutableStateOf("") }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val onStart: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val ms = parseTarget(targetText)
        if (ms == null) {
            LogBus.add("时间格式错误：应为 yyyy-MM-dd HH:mm:ss")
        } else if (!permission.accessibility) {
            LogBus.add("请先在「指引」页开启无障碍服务")
        } else {
            LogBus.clear()
            SeckillService.start(context, SeckillConfig(platform, ms, keyword.trim()))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("抢购") {
            if (running) {
                StatusPill(
                    "运行中",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                StatusPill(
                    "空闲",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (!permission.accessibility) {
                ReadinessPrompt(onOpenGuide)
            }

            SectionTitle("抢购配置")
            AppCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "平台",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Platform.entries.forEach { p ->
                            FilterChip(
                                selected = platform == p,
                                onClick = { platform = p },
                                label = { Text(p.label) }
                            )
                        }
                    }
                }
                RowDivider()
                CompactField(
                    label = "抢购时间",
                    value = targetText,
                    onValueChange = { targetText = it },
                    placeholder = "yyyy-MM-dd HH:mm:ss",
                    leadingIcon = Icons.Filled.Schedule
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(onClick = { targetText = todayAt(12, 0) }, label = { Text("今天 12:00") })
                    AssistChip(onClick = { targetText = todayAt(20, 0) }, label = { Text("今晚 20:00") })
                    AssistChip(onClick = { targetText = defaultTargetText() }, label = { Text("现在+1分") })
                }
                RowDivider()
                CompactField(
                    label = "商品关键词（可选）",
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = "用于日志与校验"
                )
            }

            if (running) {
                SectionTitle("当前阶段")
                StageCard(stage)
            }

            if (!running) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始抢购")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        SeckillService.stop(context)
                        LogBus.add("已请求停止")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("停止抢购")
                }
            }
        }
    }
}

@Composable
private fun ReadinessPrompt(onOpenGuide: () -> Unit) {
    AppCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("还不能开始", style = MaterialTheme.typography.titleMedium)
                Text(
                    "请先按「指引」完成准备：开启无障碍服务",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpenGuide) { Text("去指引") }
        }
    }
}

@Composable
private fun StageCard(stage: Stage) {
    AppCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stage.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                stage.tip,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun defaultTargetText(): String {
    val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(cal.time)
}

private fun todayAt(hour: Int, minute: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(cal.time)
}

private fun parseTarget(text: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(text.trim())?.time
} catch (_: Exception) {
    null
}
