package com.autobuy.app.seckill

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.autobuy.app.R
import com.autobuy.app.core.LogBus
import com.autobuy.app.core.Permissions
import com.autobuy.app.seckill.Stage
import com.autobuy.app.ui.GuidanceOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 前台服务：保证抢购期间进程存活，运行 [SeckillEngine]。 */
class SeckillService : Service() {

    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_TARGET = "target"
        const val EXTRA_KEYWORD = "keyword"

        private const val CHANNEL_ID = "autobuy_seckill"
        private const val NOTIF_ID = 1001

        fun start(context: Context, config: SeckillConfig) {
            val intent = Intent(context, SeckillService::class.java).apply {
                putExtra(EXTRA_PLATFORM, config.platform.name)
                putExtra(EXTRA_TARGET, config.targetTimeMs)
                putExtra(EXTRA_KEYWORD, config.keyword)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SeckillService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var overlay: GuidanceOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val platformName = intent?.getStringExtra(EXTRA_PLATFORM) ?: Platform.TAOBAO.name
        val target = intent?.getLongExtra(EXTRA_TARGET, 0L) ?: 0L
        val keyword = intent?.getStringExtra(EXTRA_KEYWORD).orEmpty()

        startForeground(NOTIF_ID, buildNotification())
        LogBus.setRunning(true)

        val platform = runCatching { Platform.valueOf(platformName) }.getOrDefault(Platform.TAOBAO)
        LogBus.add("任务启动：${platform.label}")

        // 悬浮指引条（需「显示在其他应用上层」权限）
        if (Permissions.canDrawOverlays(this)) {
            overlay = GuidanceOverlay(this).also { it.update(Stage.IDLE.label, Stage.IDLE.tip) }
            scope.launch {
                LogBus.stage.collect { s -> overlay?.update(s.label, s.tip) }
            }
        } else {
            LogBus.add("未授权「显示在其他应用上层」，无悬浮指引（可在首页开启）")
        }

        scope.launch {
            try {
                SeckillEngine(applicationContext).run(
                    SeckillConfig(
                        platform = platform,
                        targetTimeMs = target,
                        keyword = keyword
                    )
                )
            } catch (e: Exception) {
                LogBus.add("任务异常：${e.message}")
            } finally {
                LogBus.add("任务结束")
                LogBus.setRunning(false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
        scope.cancel()
        LogBus.setRunning(false)
        LogBus.setStage(Stage.IDLE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "AutoBuy 抢购", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AutoBuy")
            .setContentText("抢购任务运行中")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
