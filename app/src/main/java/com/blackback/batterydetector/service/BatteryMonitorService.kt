package com.blackback.batterydetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blackback.batterydetector.MainActivity
import com.blackback.batterydetector.R
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.data.BatteryInfo
import com.blackback.batterydetector.data.LogRepository
import com.blackback.batterydetector.network.LanSyncEngine
import com.blackback.batterydetector.network.RemoteNotifier
import com.blackback.batterydetector.network.SmtpMailer
import com.blackback.batterydetector.root.RootBatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class BatteryMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: AppPreferences
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        _isRunning.value = true
        createNotificationChannel()

        // Running implies the user wants it running; keep the flag and the watchdog
        // consistent no matter which path started us.
        prefs.isServiceEnabled = true
        ServiceWatchdog.schedule(this)

        if (prefs.deviceRole == AppPreferences.ROLE_RECEIVER) {
            val localIp = LanSyncEngine.getLocalIpAddress(this)
            startForeground(NOTIFICATION_ID, buildNotification("局域网接收器运行中 (端口: ${prefs.lanPort})"))
            LogRepository.addLog("后台服务启动为【接收方模式】，IP: $localIp, 监听端口: ${prefs.lanPort}")
            LanSyncEngine.startReceiver(this, prefs.lanPort)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("发送方服务运行中..."))
            LogRepository.addLog("后台服务启动为【发送方模式】")
            startMonitoringLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only an explicit stop request clears the user's intent. A system kill must
        // not, or the service would never be brought back.
        if (intent?.action == ACTION_STOP) {
            prefs.isServiceEnabled = false
            ServiceWatchdog.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // Any other start (boot, watchdog, UI) means the service should stay up.
        prefs.isServiceEnabled = true
        ServiceWatchdog.schedule(this)
        return START_STICKY
    }

    /**
     * Called when the user swipes the task away. On MIUI and similar builds this
     * also tears down the service, so the watchdog is brought forward to shorten
     * the gap before it is restored.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (prefs.isServiceEnabled) {
            LogRepository.addLog("任务被划掉，已安排自启看门狗提前复查")
            ServiceWatchdog.scheduleSoon(this)
        }
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                performBatteryCheck()
                val intervalMs = (prefs.checkIntervalMinutes.coerceAtLeast(1)) * 60 * 1000L
                delay(intervalMs)
            }
        }
    }

    /**
     * Reads the battery and fires an alert when it is low.
     *
     * Suspending because alert delivery waits for every configured channel to
     * report back before deciding whether the cooldown flag may be set.
     */
    suspend fun performBatteryCheck() {
        val batteryInfo = if (prefs.useRootMode) {
            RootBatteryManager.getBatteryInfoViaRoot()
                ?: RootBatteryManager.getBatteryInfoViaStandardApi(this)
        } else {
            RootBatteryManager.getBatteryInfoViaStandardApi(this)
        }

        _latestBatteryInfo.value = batteryInfo
        prefs.lastCheckTime = System.currentTimeMillis()
        prefs.lastBatteryLevel = batteryInfo.level

        val logMsg = "检测电量: ${batteryInfo.level}% (${if (batteryInfo.isCharging) "充电中" else "未充电"}), 来源: ${batteryInfo.source}"
        LogRepository.addLog(logMsg)

        updateNotification("当前电量: ${batteryInfo.level}% (${if (batteryInfo.isCharging) "充电中" else "放电中"})")

        // 状态判定逻辑
        val threshold = prefs.lowBatteryThreshold
        val isCharging = batteryInfo.isCharging

        if (isCharging || batteryInfo.level > threshold + 5) {
            if (prefs.hasNotifiedLowBattery) {
                prefs.hasNotifiedLowBattery = false
                LogRepository.addLog("设备已恢复充电或电量高于临界值，重置推状态")
            }
        } else if (batteryInfo.level <= threshold) {
            if (!prefs.hasNotifiedLowBattery) {
                LogRepository.addLog("触发低电量预警 (当前 ${batteryInfo.level}% <= 设定 ${threshold}%)，准备推送通知...", isError = true)
                dispatchAlert(batteryInfo.level, isTest = false) { success ->
                    if (success) prefs.hasNotifiedLowBattery = true
                }
            } else {
                LogRepository.addLog("电量持续偏低 (${batteryInfo.level}%)，已处于推送冷却状态")
            }
        }
    }

    /**
     * Delivers a low-battery alert over every configured channel.
     *
     * The channels are independent: previously a configured Webhook suppressed the
     * LAN broadcast entirely, because the branches were `if / else if`. That meant
     * enabling one route silently disabled the other. Now each configured channel
     * runs, and the alert counts as delivered if any of them succeeded.
     *
     * Runs on a background dispatcher and blocks until every channel has reported,
     * so the caller's "do not re-alert" flag is only cleared on a real failure.
     */
    private suspend fun dispatchAlert(batteryLevel: Int, isTest: Boolean, onResult: (Boolean) -> Unit) {
        val webhook = prefs.webhookUrl.trim()
        val mailConfig = SmtpMailer.configFrom(prefs)
        val title = if (isTest) "BatteryDetector 测试推送" else "低电量预警"
        val message = if (isTest) {
            "设备 [${prefs.deviceName}] 当前电量为 $batteryLevel%，网络通知功能正常！"
        } else {
            "警告：设备 [${prefs.deviceName}] 当前电量仅剩 $batteryLevel%，请及时充电！"
        }

        var anyAttempt = false
        var anySuccess = false

        if (webhook.isNotEmpty()) {
            anyAttempt = true
            val ok = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    RemoteNotifier.sendNotification(
                        webhookUrl = webhook,
                        deviceName = prefs.deviceName,
                        batteryLevel = batteryLevel,
                        isTest = isTest
                    ) { success, _ -> if (cont.isActive) cont.resume(success) }
                }
            }
            if (ok) anySuccess = true
        }

        if (mailConfig != null) {
            anyAttempt = true
            val result = withContext(Dispatchers.IO) {
                SmtpMailer.send(mailConfig, title, message)
            }
            LogRepository.addLog(
                if (result.success) "邮件推送成功" else result.message,
                isError = !result.success,
                isPushEvent = result.success
            )
            if (result.success) anySuccess = true
        }

        if (prefs.enableLanBroadcast) {
            anyAttempt = true
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                LanSyncEngine.sendUdpBroadcast(
                    context = this@BatteryMonitorService,
                    port = prefs.lanPort,
                    deviceName = prefs.deviceName,
                    batteryLevel = batteryLevel,
                    message = message,
                    isTest = isTest
                ) { success, _ -> if (cont.isActive) cont.resume(success) }
            }
            if (ok) anySuccess = true
        }

        if (!anyAttempt) {
            LogRepository.addLog("未配置任何推送通道，预警仅记录在日志中", isError = true)
        }
        onResult(anySuccess)
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BatteryDetector 电量服务")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_battery_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "电量监控服务通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示后台电量监控状态"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        // Deliberately NOT clearing isServiceEnabled here.
        //
        // onDestroy also runs when the OS reclaims the process, when the app
        // crashes, and when MIUI kills a background app. Clearing the flag in all
        // those cases erased the user's "keep this running" intent, so the service
        // never came back - not even after a reboot. The flag is now owned by
        // explicit user actions (the settings switch, ACTION_STOP) and by
        // onStartCommand, which means the watchdog can still revive the service.
        LanSyncEngine.stopReceiver()
        serviceScope.cancel()
        LogRepository.addLog("后台电量监控服务已停止（自启标志保持为 ${prefs.isServiceEnabled}）")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "battery_detector_service_channel"
        private const val NOTIFICATION_ID = 10086
        const val ACTION_STOP = "com.blackback.batterydetector.STOP_SERVICE"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _latestBatteryInfo = MutableStateFlow<BatteryInfo?>(null)
        val latestBatteryInfo: StateFlow<BatteryInfo?> = _latestBatteryInfo.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            // Keep the watchdog in step with a manual start.
            ServiceWatchdog.schedule(context)
        }

        /**
         * Stops the service for good.
         *
         * Requests the stop through [ACTION_STOP] rather than calling
         * [Context.stopService] directly, because only that path clears
         * `isServiceEnabled` and disarms the watchdog. Calling stopService alone
         * would leave the watchdog armed, and it would promptly restart what the
         * user just turned off.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { runCatching { context.stopService(intent) } }

            // Belt and braces: the service may already be dead, in which case
            // onStartCommand never runs and nothing would clear the flag.
            AppPreferences(context).isServiceEnabled = false
            ServiceWatchdog.cancel(context)
        }
    }
}
