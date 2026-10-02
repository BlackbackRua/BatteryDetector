package com.blackback.batterydetector.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when the watchdog alarm elapses and re-launches the service if the user
 * still wants it running. See [ServiceWatchdog] for why this exists.
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ServiceWatchdog.ACTION_WATCHDOG) return
        // ensureRunning() re-arms the alarm, so the chain continues even when the
        // process was killed in the meantime and this receiver started a new one.
        ServiceWatchdog.ensureRunning(context.applicationContext)
    }
}
