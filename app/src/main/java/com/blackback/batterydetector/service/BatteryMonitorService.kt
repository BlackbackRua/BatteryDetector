package com.blackback.batterydetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
            startForegroundCompat("局域网接收器运行中 (端口: ${prefs.lanPort})")
            LogRepository.addLog("后台服务启动为【接收方模式】，IP: $localIp, 监听端口: ${prefs.lanPort}")
            LanSyncEngine.startReceiver(this, prefs.lanPort)
        } else {
            startForegroundCompat("发送方服务运行中...")
            LogRepository.addLog("后台服务启动为【发送方模式】")
            startMonitoringLoop()
        }
    }

    /**
     * Promotes the service to the foreground, declaring its type.
     *
     * The type has to be passed to `startForeground`: on Android 14 and later a
     * call that omits it while the manifest declares one raises
     * `MissingForegroundServiceTypeException`, which kills the process outright.
     * That is what made the receiver die moments after it started listening, and it
     * looked exactly like a network or notification fault because the service had
     * already logged that it was up.
     */
    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        startForeground(NOTIFICATION_ID, buildNotification(text), type)
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
     * Suspending because alert delivery waits for the configured channels to report
     * back before the cooldown timestamp may be set.
     */
    suspend fun performBatteryCheck() {
        val batteryInfo = if (prefs.useRootMode) {
            RootBatteryManager.getBatteryInfoViaRoot()
                ?: RootBatteryManager.getBatteryInfoViaStandardApi(this)
        } else {
            RootBatteryManager.getBatteryInfoViaStandardApi(this)
        }

        // Routed through the shared write path so the alert rules below and a manual
        // refresh from the dashboard cannot diverge.
        publishBatteryInfo(this, batteryInfo)

        val logMsg = "检测电量: ${batteryInfo.level}% (${if (batteryInfo.isCharging) "充电中" else "未充电"}), 来源: ${batteryInfo.source}"
        LogRepository.addLog(logMsg)

        updateNotification("当前电量: ${batteryInfo.level}% (${if (batteryInfo.isCharging) "充电中" else "放电中"})")

        // The alert rules live in the companion so the dashboard's manual refresh can
        // run exactly the same ones.
        evaluateLowBatteryAlert(context = this, batteryInfo = batteryInfo)
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

        /**
         * Publishes a battery reading as the current one.
         *
         * The single write path for this state, used both by the monitoring loop and
         * by the dashboard's manual refresh. The dashboard used to keep its own copy,
         * which the card never displayed once the service was running, so pressing
         * refresh appeared to do nothing.
         */
        fun publishBatteryInfo(context: Context, info: BatteryInfo) {
            _latestBatteryInfo.value = info
            val prefs = AppPreferences(context)
            prefs.lastCheckTime = System.currentTimeMillis()
            prefs.lastBatteryLevel = info.level
        }

        /**
         * Whether a low-battery alert is due, given when the last one was delivered.
         *
         * Pure so it can be tested: this rule is easy to get subtly wrong, and a
         * mistake means either silence while the battery dies or a stream of
         * duplicate warnings. A zero [lastAlertAt] means no alert has been delivered
         * on this route since the battery last recovered.
         */
        fun isAlertDue(lastAlertAt: Long, now: Long, repeatMinutes: Int): Boolean {
            if (lastAlertAt == 0L) return true
            // 0 means "alert once": the recorded alert suppresses everything until
            // the battery recovers and clears it.
            if (repeatMinutes <= 0) return false
            return now - lastAlertAt >= repeatMinutes * 60_000L
        }

        /** Human-readable reason an alert is being withheld, for the log. */
        fun alertSuppressionReason(lastAlertAt: Long, now: Long, repeatMinutes: Int): String {
            if (repeatMinutes <= 0) return "已设置为只提醒一次"
            val elapsedMinutes = (now - lastAlertAt) / 60_000L
            val remaining = (repeatMinutes - elapsedMinutes).coerceAtLeast(1)
            return "距离下次提醒还需 $remaining 分钟"
        }

        /**
         * Picks the routes that are due, each judged on its own interval.
         *
         * Pure so per-channel scheduling can be tested without a Context. The whole
         * point of per-route intervals is that a route which already fired must not
         * hold back one that has not, and that separation is what the tests pin.
         *
         * [lastAlertAtByRoute] holds 0 for a route that has not fired since the
         * battery last recovered.
         */
        fun dueRoutes(
            routes: Collection<String>,
            lastAlertAtByRoute: Map<String, Long>,
            repeatMinutesByRoute: Map<String, Int>,
            now: Long
        ): List<String> = routes.filter { route ->
            isAlertDue(
                lastAlertAt = lastAlertAtByRoute[route] ?: 0L,
                now = now,
                repeatMinutes = repeatMinutesByRoute[route] ?: 0
            )
        }

        /**
         * Applies the low-battery rules to a reading and dispatches an alert if due.
         *
         * In the companion rather than an instance method so the dashboard's manual
         * refresh can run exactly the same rules; otherwise a refresh would display a
         * low level without ever warning about it.
         */
        suspend fun evaluateLowBatteryAlert(context: Context, batteryInfo: BatteryInfo) {
            val prefs = AppPreferences(context)
            val threshold = prefs.lowBatteryThreshold

            if (batteryInfo.isCharging || batteryInfo.level > threshold + 5) {
                // Recovery re-arms every route. Each keeps its own timestamp, so all
                // of them are cleared together.
                if (prefs.hasAnyAlertOnRecord()) {
                    prefs.clearAllLastAlertAt()
                    LogRepository.addLog("设备已恢复充电或电量高于临界值，重置推送状态")
                }
                return
            }

            if (batteryInfo.level > threshold) return

            val now = System.currentTimeMillis()
            val ignoreCooldown = prefs.debugIgnoreAlertCooldown

            // Each route is judged on its own clock: a LAN popup may be wanted every
            // few minutes while the same alert should reach an inbox only once. A
            // shared timer let whichever route fired first hold the others back.
            val due = if (ignoreCooldown) {
                prefs.alertRoutes.toList()
            } else {
                dueRoutes(
                    routes = prefs.alertRoutes,
                    lastAlertAtByRoute = prefs.alertRoutes.associateWith { prefs.lastAlertAt(it) },
                    repeatMinutesByRoute = prefs.alertRoutes.associateWith {
                        prefs.alertRepeatMinutes(it)
                    },
                    now = now
                )
            }

            if (due.isEmpty()) {
                val detail = prefs.alertRoutes.joinToString("; ") { route ->
                    "$route ${alertSuppressionReason(prefs.lastAlertAt(route), now, prefs.alertRepeatMinutes(route))}"
                }
                LogRepository.addLog("电量持续偏低 (${batteryInfo.level}%)，$detail")
                return
            }

            val reason = if (ignoreCooldown) {
                "调试模式已忽略提醒限制"
            } else {
                due.joinToString("、") { route ->
                    val last = prefs.lastAlertAt(route)
                    if (last == 0L) "$route 首次" else "$route 距上次已超过 ${prefs.alertRepeatMinutes(route)} 分钟"
                }
            }
            LogRepository.addLog(
                "触发低电量预警 (当前 ${batteryInfo.level}% <= 设定 $threshold%，$reason)，准备推送通知...",
                isError = true
            )
            dispatchAlert(context, batteryInfo.level, isTest = false, routes = due) { delivered ->
                // Only the routes that actually succeeded get their clock stamped, so
                // a failing channel retries on the next check instead of being treated
                // as delivered.
                val stamp = System.currentTimeMillis()
                for (route in delivered) prefs.setLastAlertAt(route, stamp)
            }
        }

        /**
         * Delivers a low-battery alert over the given routes.
         *
         * [routes] is passed in rather than read here, because each route has its own
         * repeat interval: some may be due while others are still cooling down, so the
         * caller decides which ones this round covers.
         *
         * Reports the routes that actually succeeded, so only those get their clock
         * stamped and a failing channel retries on the next check.
         */
        private suspend fun dispatchAlert(
            context: Context,
            batteryLevel: Int,
            isTest: Boolean,
            routes: Collection<String>,
            onResult: (List<String>) -> Unit
        ) {
            val prefs = AppPreferences(context)
            if (routes.isEmpty()) {
                LogRepository.addLog("未选择任何推送通道，预警仅记录在日志中", isError = true)
                onResult(emptyList())
                return
            }

            val delivered = mutableListOf<String>()

            // Webhook before email before LAN, matching the order in settings, so the
            // log reads predictably.
            val ordered = listOf(
                AppPreferences.PUSH_WEBHOOK,
                AppPreferences.PUSH_EMAIL,
                AppPreferences.PUSH_LAN
            ).filter { it in routes }

            for (route in ordered) {
                // Resolved per route: each keeps its own title and body template.
                val title = prefs.resolveAlertTitle(
                    route = route,
                    isTest = isTest,
                    deviceName = prefs.deviceName,
                    batteryLevel = batteryLevel
                )
                val message = prefs.resolveAlertMessage(
                    route = route,
                    isTest = isTest,
                    deviceName = prefs.deviceName,
                    batteryLevel = batteryLevel
                )

                when (route) {
                    AppPreferences.PUSH_WEBHOOK -> {
                        val webhook = prefs.webhookUrl.trim()
                        if (webhook.isEmpty()) {
                            LogRepository.addLog("Webhook 通道已启用，但地址未配置，已跳过", isError = true)
                            continue
                        }
                        val ok = suspendCancellableCoroutine<Boolean> { cont ->
                            RemoteNotifier.sendNotification(
                                webhookUrl = webhook,
                                deviceName = prefs.deviceName,
                                batteryLevel = batteryLevel,
                                isTest = isTest,
                                method = prefs.webhookMethod,
                                headers = prefs.webhookHeaders,
                                service = prefs.webhookService,
                                customBody = prefs.webhookCustomBody,
                                barkSound = prefs.barkSound,
                                barkLevel = prefs.barkLevel,
                                titleOverride = title,
                                messageOverride = message
                            ) { success, _ -> if (cont.isActive) cont.resume(success) }
                        }
                        if (ok) delivered.add(route)
                    }

                    AppPreferences.PUSH_EMAIL -> {
                        val config = SmtpMailer.configFrom(prefs)
                        if (config == null) {
                            LogRepository.addLog("邮件通道已启用，但 SMTP 未配置完整，已跳过", isError = true)
                            continue
                        }
                        val result = withContext(Dispatchers.IO) {
                            prefs.sendAlertEmail(
                                config = config,
                                isTest = isTest,
                                deviceName = prefs.deviceName,
                                batteryLevel = batteryLevel
                            )
                        }
                        LogRepository.addLog(
                            if (result.success) "邮件推送成功" else result.message,
                            isError = !result.success,
                            isPushEvent = result.success
                        )
                        if (result.success) delivered.add(route)
                    }

                    else -> {
                        val ok = suspendCancellableCoroutine<Boolean> { cont ->
                            LanSyncEngine.sendUdpBroadcast(
                                context = context,
                                port = prefs.lanPort,
                                deviceName = prefs.deviceName,
                                batteryLevel = batteryLevel,
                                message = message,
                                isTest = isTest,
                                titleOverride = title
                            ) { success, _ -> if (cont.isActive) cont.resume(success) }
                        }
                        if (ok) delivered.add(route)
                    }
                }
            }

            onResult(delivered)
        }
    }
}