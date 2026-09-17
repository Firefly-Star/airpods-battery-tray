package com.example.airpodsbattery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务：保持 BLE 扫描、把电量写进常驻通知，并顺带刷新桌面小组件。
 */
class BatteryService : Service() {

    companion object {
        private const val CHANNEL_ID = "airpods_battery_status"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.example.airpodsbattery.action.STOP"

        /** 界面用来显示启停状态。活动和服务在同一个进程，所以一个标志位就够了。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BatteryService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryService::class.java))
        }
    }

    private lateinit var scanner: BleScanner

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scanner = BleScanner(applicationContext, onState = ::onState)
        Diagnostics.log("服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 必须立刻进入前台，否则系统会在几秒内杀掉这个服务。
        startForeground(NOTIFICATION_ID, buildNotification(BatteryStore.load(this)))
        scanner.start()
        isRunning = true
        return START_STICKY
    }

    private var lastRenderedKey: String? = null

    private fun onState(state: AirPodsState) {
        BatteryStore.save(this, state)

        // 扫描器每秒都会回调一次，但通知和小组件不该每秒重画——那既费电又会让通知栏闪。
        // 只在数值变化、或"多久之前"这个标签翻页时才真的更新。
        val key = "${state.left}|${state.right}|${state.case}|${state.lastSeenAt}|${state.ageLabel()}"
        if (key == lastRenderedKey) return
        lastRenderedKey = key

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
        BatteryWidgetProvider.refresh(this)
    }

    override fun onDestroy() {
        isRunning = false
        Diagnostics.log("服务已停止")
        scanner.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AirPods 电量",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "常驻显示 AirPods 电量"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: AirPodsState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, BatteryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = state.modelName ?: "AirPods 电量"
        val text = if (state.hasAnyReading) {
            "${state.summary()} · ${state.ageLabel()}"
        } else {
            "暂无广播 · 耳机广播时才会出现数值"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, "退出", stopService)
            .build()
    }
}
