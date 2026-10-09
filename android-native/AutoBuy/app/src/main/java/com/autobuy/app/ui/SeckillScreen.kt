package com.autobuy.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autobuy.app.BuildConfig
import com.autobuy.app.R
import com.autobuy.app.core.LogBus
import com.autobuy.app.core.Permissions
import com.autobuy.app.seckill.Platform
import com.autobuy.app.seckill.SeckillConfig
import com.autobuy.app.seckill.SeckillService
import com.autobuy.app.seckill.Stage
import com.autobuy.app.ui.components.AppCard
import com.autobuy.app.ui.components.CompactField
import com.autobuy.app.ui.components.RowDivider
import com.autobuy.app.ui.components.SectionTitle
import com.autobuy.app.ui.components.SettingRow
import com.autobuy.app.ui.components.StatusPill
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun SeckillScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val logs by LogBus.logs.collectAsState()
    val running by LogBus.running.collectAsState()
    val stage by LogBus.stage.collectAsState()

    var a11yEnabled by remember { mutableStateOf(Permissions.isAccessibilityEnabled(context)) }
    var overlayEnabled by remember { mutableStateOf(Permissions.canDrawOverlays(context)) }
    var platform by remember { mutableStateOf(Platform.TAOBAO) }
    var targetText by remember { mutableStateOf(defaultTargetText()) }
    var keyword by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = Permissions.isAccessibilityEnabled(context)
                overlayEnabled = Permissions.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        } else if (!Permissions.isAccessibilityEnabled(context)) {
            LogBus.add("请先开启无障碍服务")
        } else {
            LogBus.clear()
            SeckillService.start(context, SeckillConfig(platform, ms, keyword.trim()))
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ===== 紧凑标题栏 =====
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AutoBuy",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
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
                // ===== 准备状态 =====
                SetupCard(
                    a11yEnabled = a11yEnabled,
                    overlayEnabled = overlayEnabled,
                    onOpenA11y = { Permissions.openAccessibilitySettings(context) },
                    onOpenOverlay = { Permissions.requestOverlay(context) }
                )

                // ===== 抢购配置 =====
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
                    RowDivider()
                    CompactField(
                        label = "商品关键词（可选）",
                        value = keyword,
                        onValueChange = { keyword = it },
                        placeholder = "用于日志与校验"
                    )
                }

                // ===== 抢购流程 =====
                SectionTitle("抢购流程")
                FlowCard(current = stage)

                // ===== 操作 =====
                Button(
                    onClick = onStart,
                    enabled = !running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("开始抢购")
                }
                OutlinedButton(
                    onClick = {
                        SeckillService.stop(context)
                        LogBus.add("已请求停止")
                    },
                    enabled = running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("停止")
                }

                // ===== 运行日志 =====
                SectionTitle("运行日志")
                AppCard {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .padding(12.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                "等待开始...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            val listState = rememberLazyListState()
                            LaunchedEffect(logs.size) {
                                if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
                            }
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(logs) { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // ===== 关于 =====
                SectionTitle("关于")
                AppCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = "AutoBuy",
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "AutoBuy",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "原生 Kotlin + Jetpack Compose 抢购工具 · v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    RowDivider()
                    SettingRow(
                        title = "项目仓库",
                        desc = "HanphoneGitHub/AutoBuy",
                        onClick = { uriHandler.openUri("https://github.com/HanphoneJan/AutoBuy") }
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    a11yEnabled: Boolean,
    overlayEnabled: Boolean,
    onOpenA11y: () -> Unit,
    onOpenOverlay: () -> Unit
) {
    AppCard {
        StatusRow(
            ok = a11yEnabled,
            title = "无障碍服务",
            desc = if (a11yEnabled) "已开启，可自动操作淘宝 App" else "抢购必需，请在系统设置中开启",
            actionText = "去开启",
            onAction = onOpenA11y
        )
        RowDivider()
        StatusRow(
            ok = overlayEnabled,
            title = "悬浮指引",
            desc = if (overlayEnabled) "已授权，抢购时在淘宝上显示当前阶段" else "可选，便于随时看到进度",
            actionText = "去授权",
            onAction = onOpenOverlay
        )
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

private fun defaultTargetText(): String {
    val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(cal.time)
}

private fun parseTarget(text: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(text.trim())?.time
} catch (_: Exception) {
    null
}
