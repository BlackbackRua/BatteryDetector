package com.blackback.batterydetector.shizuku

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.blackback.batterydetector.MainActivity
import com.blackback.batterydetector.R
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.data.LogRepository
import org.json.JSONObject

/**
 * Builds and posts HyperOS focus notifications ("焦点通知" / "超级岛").
 *
 * The payload follows Xiaomi's documented structure: a JSON document placed in
 * the notification extras under `miui.focus.param`, rooted at `param_v2`, plus an
 * optional `miui.focus.pics` bundle holding the icons the JSON refers to by key.
 *
 * The earlier implementation in this project used `miui.focus_notification.title`,
 * `extra_miui_focus_notification` and `miui.category`; those keys are not read by
 * any HyperOS build, so a notification carrying them never became a focus
 * notification. They are gone.
 */
object HyperOsFocusNotification {

    /** Extras key SystemUI reads the payload from. */
    private const val EXTRA_FOCUS_PARAM = "miui.focus.param"

    private const val TAG = "HyperOsFocus"

    /** Extras key holding the icons referenced by `miui.focus.pic_*` keys. */
    private const val EXTRA_FOCUS_PICS = "miui.focus.pics"

    /** Key prefix used to reference an icon from the payload JSON. */
    private const val PIC_PREFIX = "miui.focus.pic_"

    private const val PIC_ISLAND = "island_icon"

    /** SystemUI content provider that answers whether this app may post focus notifications. */
    private const val FOCUS_PROVIDER = "content://miui.statusbar.notification.public"

    /**
     * Per-app focus-notification allowlists kept by HyperOS in secure settings.
     * Observed on HyperOS 3 (Android 16) as `[]` when nothing is granted.
     */
    private val FOCUS_ALLOWLIST_KEYS = listOf("focus_notifs", "updatable_focus_notifs")

    /**
     * Live updates (progress-centric notifications) start at Android 16 / API 36.
     * ProgressStyle is inert below that, so plain notifications are used instead.
     */
    private const val LIVE_UPDATE_MIN_SDK = 36

    private const val PROGRESS_MAX = 100
    private const val LOW_LEVEL_PERCENT = 15
    private const val CAUTION_LEVEL_PERCENT = 40

    /** Stable ids so a repost updates the existing segments rather than stacking. */
    private const val SEGMENT_CHARGED = 1
    private const val SEGMENT_REMAINING = 2
    private const val POINT_LEVEL_MARKER = 1

    private const val PROTOCOL_OS1 = 1
    private const val PROTOCOL_OS2 = 2
    private const val PROTOCOL_OS3 = 3

    /** Focus notifications live 12 hours by default unless told otherwise. */
    private const val DEFAULT_TIMEOUT_MINUTES = 720

    /** The island itself disappears after an hour if not dismissed. */
    private const val DEFAULT_ISLAND_TIMEOUT_SECONDS = 3600

    data class Content(
        val title: String,
        val body: String,
        val deviceName: String,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val isTest: Boolean = false,
        /**
         * Drawable used for the island and status-bar icons. When null, no icon is
         * referenced and the system falls back to the app icon.
         */
        val iconRes: Int? = null,
        /**
         * When set, the notification also rings, vibrates and takes over the screen
         * like a heads-up alert - used for incoming low-battery broadcasts.
         */
        val alertSoundUri: android.net.Uri? = null,
        val alertVibration: LongArray? = null,
        val fullScreenIntent: PendingIntent? = null
    )

    enum class Capability {
        /** No island on this device; a focus notification can still be attempted. */
        UNSUPPORTED,

        /** Island is rendered by SystemUI. */
        ISLAND
    }

    data class Support(
        val capability: Capability,
        val protocolVersion: Int,
        val hasFocusPermission: Boolean,
        val isXiaomi: Boolean,
        val summary: String
    )

    /**
     * Probes what this device can actually do. Every value here comes from the
     * device, never from a guess, so the UI can explain a failure honestly.
     */
    fun detectSupport(context: Context): Support {
        val isXiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        val hasIsland = readSystemPropertyBoolean("persist.sys.feature.island", false)
        val protocol = runCatching {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        val canShowFocus = queryCanShowFocus(context)

        // HyperOS keeps two per-app allowlists in secure settings. They are empty
        // on a device where no app has been granted focus notifications, which
        // makes them a useful second opinion on the provider result.
        val allowlisted = isAllowlistedForFocus(context)

        val capability = if (hasIsland && protocol >= PROTOCOL_OS3) {
            Capability.ISLAND
        } else {
            Capability.UNSUPPORTED
        }

        val summary = buildString {
            when {
                !isXiaomi -> append("非小米设备，超级岛不可用")
                !hasIsland -> append("系统未提供超级岛 (persist.sys.feature.island=false)")
                protocol == 0 -> append("该 ROM 不支持焦点通知 (notification_focus_protocol=0)")
                protocol < PROTOCOL_OS3 -> append("焦点通知协议为 OS$protocol，超级岛需要 OS3")
                else -> append("超级岛可用 (协议 OS$protocol)")
            }
            // Deliberately not phrased as a permission verdict: on the verified
            // device canShowFocus returns true even with an empty allowlist, so it
            // cannot be trusted as the gate.
            append("；canShowFocus=").append(canShowFocus)
            append(if (allowlisted) "（在焦点白名单中）" else "（不在焦点白名单中）")
        }

        Log.d(
            TAG,
            "support: xiaomi=$isXiaomi island=$hasIsland protocol=$protocol " +
                "canShowFocus=$canShowFocus allowlisted=$allowlisted"
        )
        return Support(capability, protocol, canShowFocus, isXiaomi, summary)
    }

    /**
     * Whether this package appears in HyperOS's focus-notification allowlist
     * (`Settings.Secure.focus_notifs` / `updatable_focus_notifs`).
     */
    fun isAllowlistedForFocus(context: Context): Boolean {
        val pkg = context.packageName
        return FOCUS_ALLOWLIST_KEYS.any { key ->
            readSecureSetting(context, key)?.contains(pkg) == true
        }
    }

    @Suppress("PrivateApi")
    private fun readSecureSetting(context: Context, key: String): String? {
        // Settings.Secure.getString is a hidden overload for keys outside the
        // public Settings.NameValueTable, so it is reached reflectively.
        return try {
            val method = Class.forName("android.provider.Settings\$Secure")
                .getMethod("getString", android.content.ContentResolver::class.java, String::class.java)
            method.invoke(null, context.contentResolver, key) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True when SystemUI currently considers this package allowed to post focus
     * notifications.
     *
     * This is the authoritative gate. It is normally granted by Xiaomi's platform
     * after their review, but it is backed by `Settings.Secure.focus_notifs`,
     * which [ShizukuFocusGrant] can write locally instead.
     */
    fun queryCanShowFocus(context: Context): Boolean {
        return try {
            val extras = Bundle().apply { putString("package", context.packageName) }
            val result = context.contentResolver.call(
                android.net.Uri.parse(FOCUS_PROVIDER),
                "canShowFocus",
                null,
                extras
            )
            result?.getBoolean("canShowFocus", false) ?: false
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("PrivateApi")
    private fun readSystemPropertyBoolean(key: String, default: Boolean): Boolean {
        return try {
            val method = Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            method.invoke(null, key, default) as? Boolean ?: default
        } catch (e: Exception) {
            default
        }
    }

    /**
     * What actually happened when a post was attempted.
     *
     * [islandRendered] is the important field: callers need to know whether the
     * island path really delivered, because when it does not the alert must fall
     * back to a plain notification. Reporting that as free text - which is what
     * this used to do - let a "nothing was shown" case look like a success and
     * silently swallow incoming alerts.
     */
    data class PostResult(
        val islandRendered: Boolean,
        val message: String
    )

    /**
     * Posts a focus notification.
     *
     * The only mechanism verified to actually make HyperOS render the island on a
     * real device is the XMSF network race (see [ShizukuFocusGrant.withXmsfBlocked]):
     * SystemUI asks XMSF whether the package may post focus notifications, and
     * cutting XMSF's network for the instant of the post makes that remote check
     * fail open. `AuthManager` then logs `auth result code: -400` followed by
     * `Auth success`.
     *
     * Note that `canShowFocus` is NOT a reliable gate: on the verified device it
     * returns true even with `Settings.Secure.focus_notifs` empty, so it must not
     * be used to decide whether the permission is actually held.
     *
     * Without the bypass the focus notification is still posted, but HyperOS may
     * decline to render it as an island. [PostResult.islandRendered] distinguishes
     * that case so the caller can fall back to an ordinary alert.
     */
    suspend fun post(
        context: Context,
        notificationId: Int,
        channelId: String,
        content: Content,
        useXmsfBypass: Boolean,
        onDiagnostic: (String) -> Unit = {}
    ): PostResult {
        val support = detectSupport(context)
        val notification = build(context, channelId, content)

        if (!useXmsfBypass) {
            notifyNow(context, notificationId, notification)
            val result = PostResult(
                islandRendered = false,
                message = "已发送焦点通知但未启用网络绕行，超级岛可能不显示：${support.summary}"
            )
            LogRepository.addLog(result.message, isError = true)
            return result
        }

        val applied = ShizukuFocusGrant.withXmsfBlocked(context, onDiagnostic) {
            notifyNow(context, notificationId, notification)
        }

        val result = if (applied) {
            PostResult(true, "已通过 Shizuku 网络绕行发送超级岛通知 (XMSF 网络已临时切断)")
        } else {
            PostResult(false, "绕行未生效（Shizuku 未授权或接口不可用）：${support.summary}")
        }
        LogRepository.addLog(result.message, isError = !applied, isPushEvent = applied)
        return result
    }

    /**
     * Posts an alert that must be seen: island when the bypass works, otherwise an
     * ordinary high-priority notification.
     *
     * This is the entry point for real alerts. Without it, a device that supports
     * the island but has the bypass switched off - which is the default - would
     * show nothing at all.
     */
    suspend fun postAlert(
        context: Context,
        notificationId: Int,
        channelId: String,
        content: Content,
        useXmsfBypass: Boolean,
        soundUri: android.net.Uri?,
        vibration: LongArray?,
        fullScreenIntent: android.app.PendingIntent?,
        onDiagnostic: (String) -> Unit = {}
    ) {
        val result = post(
            context = context,
            notificationId = notificationId,
            channelId = channelId,
            content = content,
            useXmsfBypass = useXmsfBypass,
            onDiagnostic = onDiagnostic
        )

        if (!result.islandRendered) {
            postStandardAlert(
                context = context,
                notificationId = notificationId,
                channelId = channelId,
                title = content.title,
                message = content.body,
                batteryLevel = content.batteryLevel,
                soundUri = soundUri,
                vibration = vibration,
                fullScreenIntent = fullScreenIntent
            )
            LogRepository.addLog("已回退为普通高优先级通知（超级岛未生效）", isError = true)
        }
    }

    /**
     * The plain heads-up notification: sound, vibration, full-screen intent.
     *
     * Kept here rather than in the caller so every alert path, including the
     * fallback, produces the same notification.
     *
     * On Android 16+ the alert carries a progress style whose progress is the
     * battery level reported by the sending device, which makes it render as a live
     * update - a progress card on the lock screen and in the shade - instead of a
     * plain text notification.
     */
    fun postStandardAlert(
        context: Context,
        notificationId: Int,
        channelId: String,
        title: String,
        message: String,
        batteryLevel: Int?,
        soundUri: android.net.Uri?,
        vibration: LongArray?,
        fullScreenIntent: android.app.PendingIntent?
    ) {
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_battery_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        soundUri?.let { builder.setSound(it) }
        vibration?.let { builder.setVibrate(it) }
        if (soundUri != null || vibration != null) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }
        fullScreenIntent?.let {
            builder.setFullScreenIntent(it, false)
            builder.setContentIntent(it)
        }

        if (shouldUseProgressStyle(context, batteryLevel)) {
            builder.setStyle(buildProgressStyle(context, batteryLevel!!.coerceIn(0, 100)))
            builder.setOngoing(true)
            builder.setAutoCancel(false)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
        Log.d(TAG, "posted standard alert id=$notificationId level=$batteryLevel")
    }

    /**
     * Whether a received alert should be rendered as a live update.
     *
     * Requires a battery level to show, Android 16+ for ProgressStyle to have any
     * effect, and the user's setting. Checked in one place so the focus and the
     * fallback paths cannot disagree about it.
     */
    private fun shouldUseProgressStyle(context: Context, batteryLevel: Int?): Boolean {
        return batteryLevel != null &&
            Build.VERSION.SDK_INT >= LIVE_UPDATE_MIN_SDK &&
            AppPreferences(context).enableLiveUpdate
    }

    /**
     * Progress style for a received alert.
     *
     * Progress runs 0..100 with the reported battery level as the value, so the
     * system's progress chip and lock-screen card show that device's charge
     * directly. Segments and a tracker point are layered on top because a bare
     * percentage gives no sense of the remaining range.
     */
    @RequiresApi(LIVE_UPDATE_MIN_SDK)
    private fun buildProgressStyle(context: Context, level: Int): NotificationCompat.ProgressStyle {
        val levelColor = when {
            level <= LOW_LEVEL_PERCENT -> Color.RED
            level <= CAUTION_LEVEL_PERCENT -> Color.YELLOW
            else -> Color.GREEN
        }

        val segments = listOf(
            NotificationCompat.ProgressStyle.Segment(level)
                .setColor(levelColor)
                .setId(SEGMENT_CHARGED),
            NotificationCompat.ProgressStyle.Segment(PROGRESS_MAX - level)
                .setColor(Color.DKGRAY)
                .setId(SEGMENT_REMAINING)
        )

        val points = listOf(
            NotificationCompat.ProgressStyle.Point(level)
                .setColor(levelColor)
                .setId(POINT_LEVEL_MARKER)
        )

        return NotificationCompat.ProgressStyle()
            .setProgress(level)
            // There is no setProgressMax: the platform fixes the maximum at 100,
            // which is why the battery level is used as the value directly.
            .setProgressIndeterminate(false)
            .setProgressSegments(segments)
            .setProgressPoints(points)
            .setProgressTrackerIcon(
                IconCompat.createWithResource(context, R.drawable.ic_battery_notification)
            )
            .setStyledByProgress(true)
    }

    private fun notifyNow(context: Context, notificationId: Int, notification: Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
        Log.d(TAG, "posted id=$notificationId payload=${notification.extras.getString(EXTRA_FOCUS_PARAM)}")
    }

    /** Builds the notification carrying the focus payload and its icons. */
    fun build(context: Context, channelId: String, content: Content): Notification {
        val iconKey = content.iconRes?.let { PIC_ISLAND }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setSmallIcon(R.drawable.ic_battery_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))

        if (iconKey != null) {
            // The payload refers to icons by key, so each key must be present in the
            // `miui.focus.pics` bundle under exactly the name the JSON uses.
            //
            // The tint is set explicitly so the island keeps the same colour scheme
            // it would get from the notification itself; without it the drawable's
            // own colour would be used verbatim.
            val islandIcon = Icon.createWithResource(context, content.iconRes).apply {
                setTintList(ColorStateList.valueOf(Color.WHITE))
            }
            val pics = Bundle().apply {
                putParcelable(PIC_PREFIX + iconKey, islandIcon)
            }
            val extras = Bundle().apply { putBundle(EXTRA_FOCUS_PICS, pics) }
            builder.addExtras(extras)
        }

        // Optional alert behaviour, used for incoming LAN broadcasts.
        content.alertSoundUri?.let { builder.setSound(it) }
        content.alertVibration?.let { builder.setVibrate(it) }
        if (content.alertSoundUri != null || content.alertVibration != null) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }
        content.fullScreenIntent?.let { builder.setFullScreenIntent(it, false) }

        // Attach the progress style here, not only on the fallback path.
        //
        // HyperOS's island and Android's live update are two separate mechanisms,
        // and the same notification can satisfy both: when SystemUI renders the
        // island it uses the focus payload, and when it does not the progress style
        // is what the shade and lock screen show. Building it only in the fallback
        // left every island-rendered alert with a plain notification underneath.
        if (shouldUseProgressStyle(context, content.batteryLevel)) {
            builder.setStyle(buildProgressStyle(context, content.batteryLevel!!.coerceIn(0, 100)))
            builder.setOngoing(true)
            builder.setAutoCancel(false)
        }

        val notification = builder.build()
        notification.extras.putString(EXTRA_FOCUS_PARAM, buildPayload(content, iconKey))
        return notification
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Builds the `param_v2` document described by Xiaomi's Super Island guide.
     *
     * Kept as explicit JSON rather than data classes so the field names are
     * greppable against the official documentation.
     *
     * @param iconKey short icon key (without the `miui.focus.pic_` prefix), or
     *                null when no icon was supplied.
     */
    fun buildPayload(content: Content, iconKey: String?): String = buildPayloadJson(
        body = content.body,
        deviceName = content.deviceName,
        batteryLevel = content.batteryLevel,
        isCharging = content.isCharging,
        isTest = content.isTest,
        iconKey = iconKey
    )

    /**
     * Pure variant of [buildPayload] with no Android dependencies, so the payload
     * shape can be asserted in a JVM unit test.
     */
    fun buildPayloadJson(
        body: String,
        deviceName: String,
        batteryLevel: Int,
        isCharging: Boolean,
        isTest: Boolean,
        iconKey: String?
    ): String {
        // Content shared by the focus card, the expanded island and the AOD.
        val batteryText = "$batteryLevel%"
        val stateText = stateLabel(isCharging, isTest)

        val paramV2 = JSONObject().apply {
            put("protocol", PROTOCOL_OS3)
            put("business", "battery_detector")
            put("updatable", true)
            put("ticker", "$deviceName $batteryText")
            put("timeout", DEFAULT_TIMEOUT_MINUTES)
            put("enableFloat", true)
            put("isShowNotification", true)
            // Expand the island straight away: this is an alert, not a background task.
            put("islandFirstFloat", true)

            // Focus-card content.
            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", stateText)
                put("content", body)
                put("subTitle", deviceName)
                if (iconKey != null) {
                    put("picFunction", PIC_PREFIX + iconKey)
                }
            })

            // Status-bar and always-on-display text.
            put("aodTitle", "$batteryText $stateText")

            put("param_island", JSONObject().apply {
                put("islandProperty", 1)
                put("islandPriority", 2)
                put("islandTimeout", DEFAULT_ISLAND_TIMEOUT_SECONDS)
                put("dismissIsland", false)

                // Expanded island: icon plus the battery level, centred text.
                val bigIsland = JSONObject()
                if (iconKey != null) {
                    bigIsland.put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("picInfo", picInfo(iconKey))
                    })
                }
                bigIsland.put("textInfo", JSONObject().apply {
                    put("title", batteryText)
                    put("content", stateText)
                    put("showHighlightColor", true)
                })
                put("bigIslandArea", bigIsland)

                // Collapsed island: icon only, so the pill stays square.
                val smallIsland = JSONObject()
                if (iconKey != null) {
                    smallIsland.put("picInfo", picInfo(iconKey))
                }
                put("smallIslandArea", smallIsland)
            })
        }

        return JSONObject().put("param_v2", paramV2).toString()
    }

    /** @param iconKey short key, without the `miui.focus.pic_` prefix. */
    private fun picInfo(iconKey: String): JSONObject = JSONObject().apply {
        put("type", 1)
        put("pic", PIC_PREFIX + iconKey)
    }

    /**
     * Label describing the battery state. Pure so it can be unit tested without
     * an Android runtime.
     */
    fun stateLabel(isCharging: Boolean, isTest: Boolean): String = when {
        isTest -> "测试通知"
        isCharging -> "充电中"
        else -> "电量预警"
    }

    /** Exposed for tests: the short icon key used for the island icon. */
    fun islandIconKey(): String = PIC_ISLAND

    /** Exposed for tests: key that must be present in the pics bundle. */
    fun islandIconBundleKey(): String = PIC_PREFIX + PIC_ISLAND
}
