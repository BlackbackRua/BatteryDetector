package com.blackback.batterydetector.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.blackback.batterydetector.network.SmtpMailer
import com.blackback.batterydetector.network.WebhookRequestBuilder

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("battery_detector_prefs", Context.MODE_PRIVATE)

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    /** HTTP method for the webhook, validated by WebhookRequestBuilder. */
    var webhookMethod: String
        get() = prefs.getString(KEY_WEBHOOK_METHOD, "POST") ?: "POST"
        set(value) = prefs.edit().putString(KEY_WEBHOOK_METHOD, value).apply()

    /**
     * Extra request headers, one `Name: Value` per line.
     *
     * Needed by services that authenticate with `Authorization` or an API-key
     * header; without it those endpoints always answer 401.
     */
    var webhookHeaders: String
        get() = prefs.getString(KEY_WEBHOOK_HEADERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_HEADERS, value).apply()

    /**
     * Which payload shape to send.
     *
     * One of [WebhookRequestBuilder.SERVICE_AUTO], `SERVICE_BARK`,
     * `SERVICE_TELEGRAM`, `SERVICE_CUSTOM`. Chosen explicitly rather than sniffed
     * from the URL, so a self-hosted Bark server - whose host contains no `day.app`
     * - still receives the Bark payload it expects.
     */
    var webhookService: String
        get() = prefs.getString(KEY_WEBHOOK_SERVICE, WebhookRequestBuilder.SERVICE_AUTO)
            ?: WebhookRequestBuilder.SERVICE_AUTO
        set(value) = prefs.edit().putString(KEY_WEBHOOK_SERVICE, value).apply()

    /** Custom JSON body template; only used when the service is CUSTOM. */
    var webhookCustomBody: String
        get() = prefs.getString(KEY_WEBHOOK_CUSTOM_BODY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_CUSTOM_BODY, value).apply()

    /** Bark `sound` parameter; blank uses the Bark app default. */
    var barkSound: String
        get() = prefs.getString(KEY_BARK_SOUND, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BARK_SOUND, value).apply()

    /** Bark `level` parameter: blank, `active`, `timeSensitive` or `critical`. */
    var barkLevel: String
        get() = prefs.getString(KEY_BARK_LEVEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BARK_LEVEL, value).apply()

    /**
     * Routes an alert is delivered over; any combination may be enabled.
     *
     * Stored as a comma-separated list. An empty selection means no push at all,
     * which replaces the old explicit "NONE" choice - leaving every box unchecked
     * says the same thing without a dedicated option.
     *
     * Falls back to the older single-value key so an upgrade keeps the route the
     * user had already chosen.
     */
    var alertRoutes: Set<String>
        get() {
            val stored = prefs.getStringSet(KEY_ALERT_ROUTES, null)
            if (stored != null) return stored
            val legacy = prefs.getString(KEY_PUSH_METHOD, null)
            return when (legacy) {
                PUSH_WEBHOOK -> setOf(PUSH_WEBHOOK)
                PUSH_EMAIL -> setOf(PUSH_EMAIL)
                // The retired single-route "NONE" value maps to no routes.
                "NONE" -> emptySet()
                // No stored preference at all: keep the original default.
                null -> setOf(PUSH_LAN)
                else -> setOf(PUSH_LAN)
            }
        }
        set(value) {
            prefs.edit().putStringSet(KEY_ALERT_ROUTES, value).apply()
        }

    // --- SMTP / email alert route -------------------------------------------
    //
    // Empty by default: the mail route only becomes active once a host, account and
    // recipient are filled in, so upgrading users see no behaviour change.

    var smtpHost: String
        get() = prefs.getString(KEY_SMTP_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMTP_HOST, value).apply()

    var smtpPort: Int
        get() = prefs.getInt(KEY_SMTP_PORT, SmtpMailer.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_SMTP_PORT, value).apply()

    var smtpUsername: String
        get() = prefs.getString(KEY_SMTP_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMTP_USER, value).apply()

    /** Stored as plain text in app-private preferences; never leaves the device. */
    var smtpPassword: String
        get() = prefs.getString(KEY_SMTP_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMTP_PASSWORD, value).apply()

    /** Falls back to the SMTP username when blank, which is the usual case. */
    var smtpFrom: String
        get() = prefs.getString(KEY_SMTP_FROM, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMTP_FROM, value).apply()

    var smtpTo: String
        get() = prefs.getString(KEY_SMTP_TO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMTP_TO, value).apply()

    // --- custom test message -------------------------------------------------
    // --- custom alert text ---------------------------------------------------
    //
    // Title and body are stored separately per push route, because most receivers
    // treat them as distinct fields (Bark `title`/`body`, an email subject versus
    // its body, the notification's title versus its text). An empty value means
    // "use the built-in default", which keeps the stored state distinguishable from
    // a deliberately blank string.

    /** Body template for [route]; empty means the built-in default text is used. */
    fun alertMessageTemplate(route: String): String =
        prefs.getString(KEY_ALERT_MESSAGE_PREFIX + route, "") ?: ""

    /** Stores the body template for [route]; blank clears it back to the default. */
    fun setAlertMessageTemplate(route: String, value: String) {
        prefs.edit().putString(KEY_ALERT_MESSAGE_PREFIX + route, value).apply()
    }

    /** Title template for [route]; empty means the built-in default title is used. */
    fun alertTitleTemplate(route: String): String =
        prefs.getString(KEY_ALERT_TITLE_PREFIX + route, "") ?: ""

    /** Stores the title template for [route]; blank clears it back to the default. */
    fun setAlertTitleTemplate(route: String, value: String) {
        prefs.edit().putString(KEY_ALERT_TITLE_PREFIX + route, value).apply()
    }

    /** Built-in title, used when no template is set. */
    fun defaultAlertTitle(isTest: Boolean): String =
        if (isTest) "BatteryDetector 测试推送" else "低电量预警"

    /** Built-in body, used when no template is set. */
    fun defaultAlertMessage(isTest: Boolean, deviceName: String, batteryLevel: Int): String =
        if (isTest) {
            "设备 [$deviceName] 当前电量为 $batteryLevel%，网络通知功能正常！"
        } else {
            "警告：设备 [$deviceName] 当前电量仅剩 $batteryLevel%，请及时充电！"
        }

    /**
     * Final title text for an alert on [route], after placeholder substitution.
     *
     * Applies to real alerts as well as test sends: the configured template is used
     * whenever one is set, and the built-in wording only when it is blank. Kept here
     * so the service and the settings screen cannot resolve it differently.
     */
    fun resolveAlertTitle(
        route: String,
        isTest: Boolean,
        deviceName: String,
        batteryLevel: Int,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val template = alertTitleTemplate(route)
        if (template.isBlank()) return defaultAlertTitle(isTest)
        return WebhookRequestBuilder.renderPlainText(
            template = template,
            title = defaultAlertTitle(isTest),
            message = defaultAlertMessage(isTest, deviceName, batteryLevel),
            deviceName = deviceName,
            batteryLevel = batteryLevel,
            timestamp = timestamp
        )
    }

    /**
     * Final body text for an alert on [route], after placeholder substitution.
     *
     * The `{{title}}` token resolves to the title that will actually be sent, so a
     * body can still repeat or reference it after the title was customised.
     */
    fun resolveAlertMessage(
        route: String,
        isTest: Boolean,
        deviceName: String,
        batteryLevel: Int,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val builtIn = defaultAlertMessage(isTest, deviceName, batteryLevel)
        val template = alertMessageTemplate(route)
        if (template.isBlank()) return builtIn
        return WebhookRequestBuilder.renderPlainText(
            template = template,
            title = resolveAlertTitle(route, isTest, deviceName, batteryLevel, timestamp),
            message = builtIn,
            deviceName = deviceName,
            batteryLevel = batteryLevel,
            timestamp = timestamp
        )
    }

    /**
     * Sends an alert over the email route using the resolved title and body.
     *
     * Lives here rather than at the call site so the custom title cannot be
     * forgotten: the subject used to be hard-coded, which silently ignored a
     * configured title while the editor showed one.
     */
    suspend fun sendAlertEmail(
        config: SmtpMailer.Config,
        isTest: Boolean,
        deviceName: String,
        batteryLevel: Int
    ): SmtpMailer.Result {
        val route = PUSH_EMAIL
        return SmtpMailer.send(
            config = config,
            subject = resolveAlertTitle(route, isTest, deviceName, batteryLevel),
            body = resolveAlertMessage(route, isTest, deviceName, batteryLevel)
        )
    }

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

    /**
     * Minutes between repeat low-battery alerts; 0 means "alert once".
     *
     * While the battery stays below the threshold and is not charging, the alert is
     * re-sent once this much time has passed. Setting 0 disables the repeat, which is
     * the same as stopping after the first warning.
     */
    var alertRepeatMinutes: Int
        get() = prefs.getInt(KEY_ALERT_REPEAT_MINUTES, 0)
        set(value) = prefs.edit().putInt(KEY_ALERT_REPEAT_MINUTES, value).apply()

    /**
     * When the last low-battery alert was actually delivered, or 0 for "never".
     *
     * Replaces a plain boolean: a boolean could say that an alert happened but not
     * when, so it could not support a repeat interval. Cleared when the battery
     * recovers, which is what re-arms the next alert.
     */
    var lastAlertAt: Long
        get() = prefs.getLong(KEY_LAST_ALERT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ALERT_AT, value).apply()

    /**
     * Debug switch: ignore the repeat interval so every check alerts.
     *
     * Exists so the alert path can be exercised repeatedly without waiting out a
     * cooldown. Off by default; while on, the log says so, because an alert firing
     * every check would otherwise look like a bug.
     */
    var debugIgnoreAlertCooldown: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_IGNORE_ALERT_COOLDOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_IGNORE_ALERT_COOLDOWN, value).apply()

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
        private const val KEY_ALERT_REPEAT_MINUTES = "alert_repeat_minutes"
        private const val KEY_LAST_ALERT_AT = "last_alert_at"
        private const val KEY_DEBUG_IGNORE_ALERT_COOLDOWN = "debug_ignore_alert_cooldown"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_LAST_BATTERY_LEVEL = "last_battery_level"
        private const val KEY_DEVICE_ROLE = "device_role"
        private const val KEY_ENABLE_LAN = "enable_lan"
        private const val KEY_LAN_PORT = "lan_port"
        private const val KEY_HYPEROS_FOCUS_BYPASS = "hyperos_focus_bypass"
        private const val KEY_ENABLE_DEDUP = "enable_notification_dedup"
        private const val KEY_ENABLE_LIVE_UPDATE = "enable_live_update"
        private const val KEY_SMTP_HOST = "smtp_host"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_SMTP_USER = "smtp_user"
        private const val KEY_SMTP_PASSWORD = "smtp_password"
        private const val KEY_SMTP_FROM = "smtp_from"
        private const val KEY_SMTP_TO = "smtp_to"
        private const val KEY_ALERT_MESSAGE_PREFIX = "alert_message_"
        private const val KEY_ALERT_TITLE_PREFIX = "alert_title_"
        private const val KEY_WEBHOOK_METHOD = "webhook_method"
        private const val KEY_WEBHOOK_HEADERS = "webhook_headers"
        private const val KEY_WEBHOOK_SERVICE = "webhook_service"
        private const val KEY_WEBHOOK_CUSTOM_BODY = "webhook_custom_body"
        private const val KEY_BARK_SOUND = "bark_sound"
        private const val KEY_BARK_LEVEL = "bark_level"
        private const val KEY_ALERT_ROUTES = "alert_routes"

        /** Push route identifiers, also used as the stored preference values. */
        const val PUSH_LAN = "LAN"
        const val PUSH_WEBHOOK = "WEBHOOK"
        const val PUSH_EMAIL = "EMAIL"

        /** Legacy single-route key, still read so an upgrade keeps the old choice. */
        private const val KEY_PUSH_METHOD = "push_method"
    }
}
