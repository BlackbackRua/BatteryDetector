package com.blackback.batterydetector.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blackback.batterydetector.MainActivity
import com.blackback.batterydetector.R
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.data.LogRepository
import com.blackback.batterydetector.shizuku.HyperOsFocusNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object LanSyncEngine {

    const val CHANNEL_ID = "lan_battery_receiver_channel_v4"

    /** Fixed ids so a repeat alert replaces the previous one instead of stacking. */
    private const val ALERT_NOTIFICATION_ID = 10010
    private const val TEST_NOTIFICATION_ID = 10011
    private val ALERT_VIBRATION = longArrayOf(0, 300, 200, 300)

    /** Common home-router subnet, used only if interface discovery yields nothing. */
    private const val WIFI_BROADCAST_FALLBACK = "192.168.1.255"

    /** Window in which an identical repeat alert counts as a duplicate. */
    private const val DEDUP_WINDOW_MS = 5000L

    /** How long an entry is kept before pruning, independent of the window. */
    private const val DEDUP_RETENTION_MS = 15000L

    private var listenerJob: Job? = null
    private var httpServerJob: Job? = null
    private var datagramSocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // 5-second deduplication map by (device + battery) to guarantee single notification
    private val recentNotifications = ConcurrentHashMap<String, Long>()

    /**
     * Get Local Wi-Fi IPv4 Address (e.g. 192.168.1.100)
     */
    fun getLocalIpAddress(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }

            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && host.indexOf(':') < 0) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    /**
     * Addresses a UDP broadcast should be sent to.
     *
     * The limited broadcast address 255.255.255.255 is deliberately not relied on
     * alone: many consumer routers only forward subnet-directed broadcasts such as
     * 192.168.1.255 and silently drop the limited form, which looks exactly like
     * "the other device never received anything". The subnet address derived from
     * the interface broadcast / netmask is used first, with the limited address kept
     * as a fallback for networks that do forward it.
     */
    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = LinkedHashSet<InetAddress>()

        fun add(host: String?) {
            if (host.isNullOrBlank()) return
            runCatching { InetAddress.getByName(host) }
                .getOrNull()
                ?.let { addresses.add(it) }
        }

        try {
            for (intf in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp || intf.isLoopback) continue
                for (ia in intf.interfaceAddresses) {
                    val addr = ia.address ?: continue
                    if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
                    // The one the kernel considers correct for this interface.
                    val br = ia.broadcast
                    if (br != null && !br.isLoopbackAddress) add(br.hostAddress)
                    // Some ROMs report 255.255.255.255 here, so derive it as well.
                    add(subnetBroadcast(addr.hostAddress, ia.networkPrefixLength.toInt()))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Common home-network fallback, and the limited address last.
        add(WIFI_BROADCAST_FALLBACK)
        add("255.255.255.255")
        return addresses.toList()
    }

    /**
     * Derives e.g. 192.168.1.255 from 192.168.1.7 with a /24 prefix.
     *
     * Internal rather than private so it can be unit tested: getting this wrong is
     * exactly the class of bug that makes broadcasts silently go nowhere.
     */
    internal fun subnetBroadcast(ipv4: String, prefixLength: Int): String? {
        if (prefixLength !in 1..31) return null
        val parts = ipv4.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (p in parts) {
            val octet = p.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val broadcast = (value and mask) or (mask.inv() and 0xFFFFFFFFL)
        return listOf(24, 16, 8, 0).joinToString(".") {
            ((broadcast shr it) and 0xFF).toString()
        }
    }

    /** Reads the Wi-Fi netmask as a prefix length, for the fallback path only. */
    @Suppress("DEPRECATION")
    private fun wifiPrefixLength(context: Context): Int? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mask = wifiManager.dhcpInfo?.netmask ?: return null
            if (mask == 0) return null
            Integer.bitCount(mask)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Broadcast battery alert packet over UDP to the LAN.
     */
    fun sendUdpBroadcast(
        context: Context,
        port: Int,
        deviceName: String,
        batteryLevel: Int,
        message: String,
        isTest: Boolean = false,
        onResult: (Boolean, String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val titleText = if (isTest) "BatteryDetector 测试推送" else "低电量预警"
                // The caller's message is used as-is in both cases. Previously a
                // test send substituted its own wording here, which silently threw
                // away whatever the caller passed - so a custom test message never
                // reached the receiver.
                val bodyText = message

                val json = JSONObject().apply {
                    put("type", if (isTest) "TEST" else "LOW_BATTERY")
                    put("title", titleText)
                    put("device", deviceName)
                    put("battery", batteryLevel)
                    put("message", bodyText)
                    put("timestamp", System.currentTimeMillis())
                }.toString()

                val bytes = json.toByteArray(Charsets.UTF_8)

                // Also make sure the Wi-Fi subnet is represented, in case the
                // interface walk above found nothing usable.
                val targets = LinkedHashSet(broadcastAddresses())
                wifiPrefixLength(context)?.let { prefix ->
                    getLocalIpAddress(context).takeIf { it != "127.0.0.1" }?.let { ip ->
                        subnetBroadcast(ip, prefix)?.let { host ->
                            runCatching { InetAddress.getByName(host) }.getOrNull()
                                ?.let { targets.add(it) }
                        }
                    }
                }

                val socket = DatagramSocket()
                socket.broadcast = true
                var sentTo = 0
                val failures = mutableListOf<String>()

                for (target in targets) {
                    try {
                        val packet = DatagramPacket(bytes, bytes.size, target, port)
                        socket.send(packet)
                        sentTo++
                    } catch (e: Exception) {
                        failures += "${target.hostAddress}: ${e.localizedMessage}"
                    }
                }
                socket.close()

                withContext(Dispatchers.Main) {
                    if (sentTo == 0) {
                        val errorMsg = "局域网广播发送失败: ${failures.joinToString("; ")}"
                        LogRepository.addLog(errorMsg, isError = true, isPushEvent = true)
                        onResult(false, errorMsg)
                    } else {
                        val targetsText = targets.joinToString(", ") { it.hostAddress ?: "?" }
                        val logMsg = "局域网 UDP 广播已发送 (端口 $port, 目标 $targetsText)"
                        LogRepository.addLog(logMsg, isError = false, isPushEvent = true)
                        onResult(true, logMsg)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    val errorMsg = "局域网广播发送失败: ${e.localizedMessage}"
                    LogRepository.addLog(errorMsg, isError = true, isPushEvent = true)
                    onResult(false, errorMsg)
                }
            }
        }
    }

    /**
     * Start LAN Receiver Service (UDP Listener + HTTP Local Server)
     */
    fun startReceiver(context: Context, port: Int) {
        stopReceiver()

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("BatteryDetectorMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        ensureNotificationChannel(context)

        val scope = CoroutineScope(Dispatchers.IO)

        // 1. Start UDP Listener Thread
        listenerJob = scope.launch {
            try {
                datagramSocket = DatagramSocket(port)
                val buffer = ByteArray(2048)
                LogRepository.addLog("局域网接收器已启动 (UDP 监听端口: $port)")

                while (isActive && datagramSocket?.isClosed == false) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    datagramSocket?.receive(packet)

                    val receivedData = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleIncomingPayload(context, receivedData, packet.address.hostAddress ?: "未知")
                }
            } catch (e: Exception) {
                if (isActive) {
                    LogRepository.addLog("UDP 监听端口异常关闭: ${e.localizedMessage}", isError = true)
                }
            }
        }

        // 2. Start HTTP Local Server Thread
        httpServerJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                LogRepository.addLog("局域网 HTTP 服务器已启动 (监听端口: $port)")

                while (isActive && serverSocket?.isClosed == false) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch { handleHttpClient(context, clientSocket) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    LogRepository.addLog("HTTP 服务器异常关闭: ${e.localizedMessage}", isError = true)
                }
            }
        }
    }

    /**
     * Stop LAN Receiver
     */
    fun stopReceiver() {
        try {
            listenerJob?.cancel()
            httpServerJob?.cancel()
            datagramSocket?.close()
            serverSocket?.close()
            multicastLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            datagramSocket = null
            serverSocket = null
            multicastLock = null
            LogRepository.addLog("局域网接收器已停止")
        }
    }

    private suspend fun handleHttpClient(context: Context, socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output: OutputStream = socket.getOutputStream()

            var line: String?
            var contentLength = 0
            val headers = mutableListOf<String>()

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                headers.add(line)
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val charBuffer = CharArray(contentLength)
                reader.read(charBuffer, 0, contentLength)
                String(charBuffer)
            } else ""

            if (body.isNotEmpty()) {
                handleIncomingPayload(context, body, socket.inetAddress.hostAddress ?: "未知")
            }

            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"status\":\"ok\"}"
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun handleIncomingPayload(context: Context, jsonStr: String, senderIp: String) {
        try {
            val json = JSONObject(jsonStr)
            val device = json.optString("device", "未知设备")
            val battery = json.optInt("battery", -1)
            val message = json.optString("message", json.optString("text", "电量警告"))
            val title = json.optString("title", "局域网低电量通知")

            // Deduplication by device + battery, unless it has been switched off in
            // the debug settings - which is what you want while testing the receive
            // path, since a dropped second packet is otherwise indistinguishable
            // from one that never arrived.
            val dedupEnabled = AppPreferences(context).enableNotificationDedup
            if (isDuplicate(device, battery, dedupEnabled)) {
                LogRepository.addLog("收到来自 [$device] 的极短时间内重复通知，已自动忽略（去重开启中）")
                return
            }

            val logMsg = "收到来自 [$device] ($senderIp) 的局域网同步: $message (电量 $battery%)"
            LogRepository.addLog(logMsg, isError = false, isPushEvent = true)

            // Post Heads-up System Notification on Receiver Device
            showSystemNotification(
                context = context,
                title = "$title - $device",
                message = message,
                deviceName = device,
                batteryLevel = battery
            )

        } catch (e: Exception) {
            val errorMsg = "解析局域网同步数据异常 ($senderIp): ${e.localizedMessage}"
            LogRepository.addLog(errorMsg, isError = true)
        }
    }

    /**
     * Shows a test notification on this device.
     *
     * Goes through postAlert for the same reason real alerts do: without the
     * fallback, a device that supports the island but has the bypass off would
     * show nothing, and a test that shows nothing is worse than useless.
     */
    suspend fun showLocalTestNotification(context: Context, title: String, message: String) {
        ensureNotificationChannel(context)

        val prefs = AppPreferences(context)
        val focusSupported = HyperOsFocusNotification.detectSupport(context).capability ==
            HyperOsFocusNotification.Capability.ISLAND

        if (focusSupported) {
            HyperOsFocusNotification.postAlert(
                context = context,
                notificationId = TEST_NOTIFICATION_ID,
                channelId = CHANNEL_ID,
                content = HyperOsFocusNotification.Content(
                    title = title,
                    body = message,
                    deviceName = prefs.deviceName,
                    batteryLevel = prefs.lastBatteryLevel.coerceAtLeast(0),
                    isCharging = false,
                    isTest = true,
                    iconRes = R.drawable.ic_battery_notification
                ),
                useXmsfBypass = prefs.useHyperOsFocusBypass,
                soundUri = null,
                vibration = null,
                fullScreenIntent = null
            ) { line -> LogRepository.addLog("[超级岛] $line") }
            return
        }

        showSystemNotification(
            context = context,
            title = title,
            message = message,
            deviceName = prefs.deviceName,
            batteryLevel = prefs.lastBatteryLevel.coerceAtLeast(0)
        )
    }

    /**
     * Whether this packet repeats one seen moments ago.
     *
     * Returns false immediately when the caller has deduplication switched off, so
     * every packet is treated as new. The map is still pruned in that case, so
     * toggling the setting does not leave stale entries behind.
     */
    private fun isDuplicate(device: String, battery: Int, dedupEnabled: Boolean): Boolean {
        return shouldDropAsDuplicate(
            seen = recentNotifications,
            key = "$device:$battery",
            now = System.currentTimeMillis(),
            dedupEnabled = dedupEnabled
        )
    }

    /**
     * The decision half of [isDuplicate], with the clock and storage passed in.
     *
     * Split out so the rule can be tested directly: it is the part that silently
     * swallows packets, which is exactly what made the receive path confusing to
     * debug. Returns true when the packet should be dropped as a duplicate.
     */
    internal fun shouldDropAsDuplicate(
        seen: MutableMap<String, Long>,
        key: String,
        now: Long,
        dedupEnabled: Boolean
    ): Boolean {
        if (!dedupEnabled) {
            seen.remove(key)
            seen.entries.removeIf { now - it.value > DEDUP_RETENTION_MS }
            return false
        }

        // Absence is tested with containsKey rather than a 0 sentinel: treating
        // "never seen" as timestamp 0 makes the elapsed time enormous, which is
        // correct, but it reads as a huge window in the other direction too and is
        // easy to get wrong. The original code did exactly that.
        val lastTime = seen[key]
        if (lastTime != null && now - lastTime < DEDUP_WINDOW_MS) {
            return true
        }

        seen[key] = now
        seen.entries.removeIf { now - it.value > DEDUP_RETENTION_MS }
        return false
    }

    private suspend fun showSystemNotification(
        context: Context,
        title: String,
        message: String,
        deviceName: String,
        batteryLevel: Int
    ) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        // Stable ids: a random id per call would make a repeat alert overlap the
        // previous one instead of replacing it.
        val notificationId = ALERT_NOTIFICATION_ID

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prefs = AppPreferences(context)
        val focusSupported = HyperOsFocusNotification.detectSupport(context).capability ==
            HyperOsFocusNotification.Capability.ISLAND

        if (focusSupported) {
            // postAlert guarantees the alert is actually shown: the island when the
            // bypass works, an ordinary high-priority notification otherwise.
            //
            // The previous version of this called post() and returned, so a device
            // that supports the island but has the bypass switched off - the
            // default - ended up showing nothing at all.
            HyperOsFocusNotification.postAlert(
                context = context,
                notificationId = notificationId,
                channelId = CHANNEL_ID,
                content = HyperOsFocusNotification.Content(
                    title = title,
                    body = message,
                    deviceName = deviceName,
                    batteryLevel = batteryLevel,
                    isCharging = false,
                    iconRes = R.drawable.ic_battery_notification,
                    alertSoundUri = soundUri,
                    alertVibration = ALERT_VIBRATION,
                    fullScreenIntent = fullScreenIntent
                ),
                useXmsfBypass = prefs.useHyperOsFocusBypass,
                soundUri = soundUri,
                vibration = ALERT_VIBRATION,
                fullScreenIntent = fullScreenIntent
            ) { line -> LogRepository.addLog("[超级岛] $line") }
            return
        }

        HyperOsFocusNotification.postStandardAlert(
            context = context,
            notificationId = notificationId,
            channelId = CHANNEL_ID,
            title = title,
            message = message,
            batteryLevel = batteryLevel,
            soundUri = soundUri,
            vibration = ALERT_VIBRATION,
            fullScreenIntent = fullScreenIntent
        )
    }

    /**
     * Creates the alert channel if it is not there yet.
     *
     * Public because the settings screen must call it before opening the system's
     * per-channel notification page. ColorOS (and possibly other skins) does not
     * validate the channel id in ACTION_CHANNEL_NOTIFICATION_SETTINGS: asked about
     * a channel that does not exist it still launches the activity, which then has
     * nothing to show and renders as a blank page. Ensuring the channel exists
     * first makes that page meaningful on every device.
     *
     * Safe to call repeatedly; createNotificationChannel is idempotent and leaves
     * an existing channel's user settings alone aside from the description.
     */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "紧急低电量广播通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "接收来自局域网的低电量紧急弹窗"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
