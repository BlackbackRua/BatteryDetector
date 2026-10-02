package com.blackback.batterydetector.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.data.LogRepository

/**
 * Restores the monitor service after a reboot or an app update.
 *
 * ACTION_MY_PACKAGE_REPLACED matters as much as boot: the process is killed for
 * the install, so without it an updated app would sit with the service down until
 * the next reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = AppPreferences(context)
                if (!prefs.isServiceEnabled) return

                LogRepository.addLog("开机/更新后自动拉起电量监控服务")
                BatteryMonitorService.startService(context)
                // Arm the watchdog so a later kill is recoverable too.
                ServiceWatchdog.schedule(context)
            }
        }
    }
}
