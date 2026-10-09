package com.autobuy.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autobuy.app.BuildConfig
import com.autobuy.app.R
import com.autobuy.app.ui.components.AppCard
import com.autobuy.app.ui.components.RowDivider
import com.autobuy.app.ui.components.ScreenHeader
import com.autobuy.app.ui.components.SectionTitle
import com.autobuy.app.ui.components.SettingRow

/** 我的页：App 信息 + 关于 + 免责声明。 */
@Composable
fun MineScreen() {
    val uriHandler = LocalUriHandler.current

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
}
