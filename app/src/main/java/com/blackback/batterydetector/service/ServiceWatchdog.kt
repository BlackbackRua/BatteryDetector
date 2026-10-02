package com.blackback.batterydetector.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.data.LogRepository

/**
 * Periodically re-launches the monitor service if it is supposed to be running.
 *
 * Why this exists: `START_STICKY` is a request, not a guarantee. Aggressive OEM
 * builds - MIUI/HyperOS among them - kill a background process and simply do not
 * bring it back, and nothing in the framework tells the app it happened. A
 * reboot receiver alone cannot cover that window, so a repeating alarm is used as
 * a watchdog instead.
 *
 * The alarm deliberately uses setAndAllowWhileIdle rather than an exact alarm:
 * exact alarms need SCHEDULE_EXACT_ALARM on Android 12+ and are the wrong tool
 * for a retry loop. Being a few minutes late is harmless here.
 *
 * Rescheduling happens from the receiver on every fire, so the chain keeps itself
 * alive; each entry point cancels before scheduling so alarms cannot pile up.
 */
object ServiceWatchdog {

    private const val REQUEST_CODE = 10087
    const val ACTION_WATCHDOG = "com.blackback.batterydetector.WATCHDOG"

    /**
     * 15 minutes is the floor for setInexactRepeating, and also a sane retry rate:
     * quick enough to recover a killed service, slow enough not to matter for
     * battery.
     */
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /** Short delay used when the task was just swiped away. */
    private const val IMMEDIATE_RETRY_MS = 60 * 1000L

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? {
        val intent = Intent(context, ServiceWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Arm (or re-arm) the watchdog for the normal periodic case.
     *
     * Safe to call repeatedly and from any entry point.
     */
    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancel(context)

        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        val pi = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: Exception) {
            // Fall back to a plain alarm rather than losing the watchdog entirely.
            runCatching {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }.onFailure { LogRepository.addLog("自启看门狗调度失败: ${it.localizedMessage}", isError = true) }
        }
    }

    /**
     * Bring the next check forward. Used right after the task is swiped away, when
     * the service is most likely to be killed and waiting a full interval would
     * leave a long silent gap.
     */
    fun scheduleSoon(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancel(context)

        val triggerAt = SystemClock.elapsedRealtime() + IMMEDIATE_RETRY_MS
        val pi = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    /** Drop the watchdog; called when the user turns the service off. */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(
            context,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        runCatching { alarmManager.cancel(pi) }
    }

    /**
     * Starts the service when the user wants it running, and does nothing when
     * they do not. Called from the watchdog receiver and from boot.
     */
    fun ensureRunning(context: Context) {
        val prefs = AppPreferences(context)
        if (!prefs.isServiceEnabled) {
            cancel(context)
            return
        }
        if (BatteryMonitorService.isRunning.value) {
            // Already alive; just keep the chain going.
            schedule(context)
            return
        }

        LogRepository.addLog("自启看门狗：服务未在运行，正在重新拉起")
        BatteryMonitorService.startService(context)
        schedule(context)
    }
}
