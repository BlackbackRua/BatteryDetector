package com.blackback.batterydetector.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("battery_detector_prefs", Context.MODE_PRIVATE)

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, Build.MODEL) ?: Build.MODEL
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var lowBatteryThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 15)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value).apply()

    var checkIntervalMinutes: Int
        get() = prefs.getInt(KEY_CHECK_INTERVAL, 5)
        set(value) = prefs.edit().putInt(KEY_CHECK_INTERVAL, value).apply()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var useRootMode: Boolean
        get() = prefs.getBoolean(KEY_USE_ROOT, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_ROOT, value).apply()

    var hasNotifiedLowBattery: Boolean
        get() = prefs.getBoolean(KEY_HAS_NOTIFIED, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_NOTIFIED, value).apply()

    var lastCheckTime: Long
        get() = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK_TIME, value).apply()

    var lastBatteryLevel: Int
        get() = prefs.getInt(KEY_LAST_BATTERY_LEVEL, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_BATTERY_LEVEL, value).apply()

    var deviceRole: String
        get() = prefs.getString(KEY_DEVICE_ROLE, ROLE_SENDER) ?: ROLE_SENDER
        set(value) = prefs.edit().putString(KEY_DEVICE_ROLE, value).apply()

    var enableLanBroadcast: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_LAN, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_LAN, value).apply()

    var lanPort: Int
        get() = prefs.getInt(KEY_LAN_PORT, 18888)
        set(value) = prefs.edit().putInt(KEY_LAN_PORT, value).apply()

    /**
     * Cuts XMSF's network for the moment a focus notification is posted, so the
     * remote permission check fails open and HyperOS renders the island.
     *
     * This is the only mechanism verified to work on a real HyperOS 3 device, so
     * it defaults to on. Cost: Xiaomi push is interrupted for about a second per
     * notification.
     */
    var useHyperOsFocusBypass: Boolean
        get() = prefs.getBoolean(KEY_HYPEROS_FOCUS_BYPASS, true)
        set(value) = prefs.edit().putBoolean(KEY_HYPEROS_FOCUS_BYPASS, value).apply()

    /**
     * When enabled, a repeat alert from the same device at the same battery level
     * inside LanSyncEngine's 5-second window is dropped instead of notifying again.
     *
     * On by default because a device that keeps re-broadcasting the same level
     * would otherwise spam the notification shade. Turn it off when debugging the
     * receive path, where a silently dropped second packet looks like a lost one.
     */
    var enableNotificationDedup: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_DEDUP, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_DEDUP, value).apply()

    /**
     * Renders a received LAN alert as an Android 16+ live update: a progress-centric
     * notification whose progress is the battery level the sending device reported,
     * so it shows as a progress card in the shade and on the lock screen.
     *
     * Only affects the alerts this device receives, not the foreground-service
     * notification. Ignored below API 36, where ProgressStyle has no effect.
     *
     * On by default - it is a richer rendering of the same alert - and live updates
     * are progress-centric, so the platform expects them to be ongoing.
     */
    var enableLiveUpdate: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_LIVE_UPDATE, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_LIVE_UPDATE, value).apply()

    companion object {
        const val ROLE_SENDER = "SENDER"
        const val ROLE_RECEIVER = "RECEIVER"

        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_CHECK_INTERVAL = "check_interval"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_USE_ROOT = "use_root"
        private const val KEY_HAS_NOTIFIED = "has_notified"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_LAST_BATTERY_LEVEL = "last_battery_level"
        private const val KEY_DEVICE_ROLE = "device_role"
        private const val KEY_ENABLE_LAN = "enable_lan"
        private const val KEY_LAN_PORT = "lan_port"
        private const val KEY_HYPEROS_FOCUS_BYPASS = "hyperos_focus_bypass"
        private const val KEY_ENABLE_DEDUP = "enable_notification_dedup"
        private const val KEY_ENABLE_LIVE_UPDATE = "enable_live_update"
    }
}
