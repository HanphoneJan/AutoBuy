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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autobuy.app.core.LogBus
import com.autobuy.app.core.Permissions
import com.autobuy.app.seckill.Platform
import com.autobuy.app.seckill.SeckillConfig
import com.autobuy.app.seckill.SeckillService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeckillScreen() {
    val context = LocalContext.current
    val logs by LogBus.logs.collectAsState()
    val running by LogBus.running.collectAsState()

    var a11yEnabled by remember { mutableStateOf(Permissions.isAccessibilityEnabled(context)) }
    var platform by remember { mutableStateOf(Platform.TAOBAO) }
    var targetText by remember { mutableStateOf(defaultTargetText()) }
    var keyword by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = Permissions.isAccessibilityEnabled(context)
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("AutoBuy") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PermissionCard(enabled = a11yEnabled) {
                Permissions.openAccessibilitySettings(context)
            }
            ConfigCard(
                platform = platform,
                onPlatform = { platform = it },
                targetText = targetText,
                onTarget = { targetText = it },
                keyword = keyword,
                onKeyword = { keyword = it }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStart,
                    enabled = !running,
                    modifier = Modifier.weight(1f)
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
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("停止")
                }
            }
            LogCard(logs = logs, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PermissionCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (enabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "无障碍服务已开启" else "无障碍服务未开启",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (enabled) "可以开始抢购" else "抢购前需在系统设置中开启 AutoBuy 抢购服务",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!enabled) {
                TextButton(onClick = onOpenSettings) { Text("去开启") }
            }
        }
    }
}

@Composable
private fun ConfigCard(
    platform: Platform,
    onPlatform: (Platform) -> Unit,
    targetText: String,
    onTarget: (String) -> Unit,
    keyword: String,
    onKeyword: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("抢购配置", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Platform.entries.forEach { p ->
                    FilterChip(
                        selected = platform == p,
                        onClick = { onPlatform(p) },
                        label = { Text(p.label) }
                    )
                }
            }
            OutlinedTextField(
                value = targetText,
                onValueChange = onTarget,
                label = { Text("抢购时间") },
                supportingText = { Text("格式：yyyy-MM-dd HH:mm:ss") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeyword,
                label = { Text("商品关键词（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LogCard(logs: List<String>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text("运行日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
            }
            if (logs.isEmpty()) {
                Text(
                    "等待开始...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logs) { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
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
