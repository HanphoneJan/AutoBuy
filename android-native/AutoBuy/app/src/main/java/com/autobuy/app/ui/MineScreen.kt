package com.autobuy.app.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autobuy.app.BuildConfig
import com.autobuy.app.R
import com.autobuy.app.core.Updater
import com.autobuy.app.ui.components.AppCard
import com.autobuy.app.ui.components.RowDivider
import com.autobuy.app.ui.components.ScreenHeader
import com.autobuy.app.ui.components.SectionTitle
import com.autobuy.app.ui.components.SettingRow
import kotlinx.coroutines.launch

/** 我的页：App 信息 + 关于（检查更新 / 仓库 / 反馈）+ 免责声明。 */
@Composable
fun MineScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updater.UpdateInfo?>(null) }

    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            val info = Updater.check()
            checking = false
            if (info != null) {
                update = info
            } else {
                Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("我的")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
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
                            .size(48.dp)
                            .clip(CircleShape)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "AutoBuy",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "原生 Kotlin + Jetpack Compose 抢购工具",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            SectionTitle("关于")
            AppCard {
                SettingRow(
                    title = "检查更新",
                    desc = if (checking) "检查中…" else "当前 v${BuildConfig.VERSION_NAME}",
                    onClick = { checkUpdate() }
                )
                RowDivider()
                SettingRow(
                    title = "项目仓库",
                    desc = "HanphoneGitHub/AutoBuy",
                    onClick = { uriHandler.openUri("https://github.com/HanphoneJan/AutoBuy") }
                )
                RowDivider()
                SettingRow(
                    title = "问题反馈",
                    desc = "GitHub Issues",
                    onClick = { uriHandler.openUri("https://github.com/HanphoneJan/AutoBuy/issues") }
                )
            }

            SectionTitle("免责声明")
            AppCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "本工具仅用于学习交流，自动化操作官方 App 可能违反平台服务条款，账号风险自负，请勿商用或分发。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    update?.let { info ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("发现新版本 ${info.version}") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "当前 v${BuildConfig.VERSION_NAME}，可升级到 ${info.version}。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (info.notes.isNotBlank()) {
                        Text(
                            info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uriHandler.openUri(info.apkUrl)
                    update = null
                }) { Text("去更新") }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) { Text("稍后") }
            }
        )
    }
}
