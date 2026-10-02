package com.blackback.batterydetector.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackback.batterydetector.R
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.data.LogRepository
import com.blackback.batterydetector.network.LanSyncEngine
import com.blackback.batterydetector.network.RemoteNotifier
import com.blackback.batterydetector.root.RootBatteryManager
import com.blackback.batterydetector.service.BatteryMonitorService
import com.blackback.batterydetector.shizuku.HyperOsFocusNotification
import com.blackback.batterydetector.utils.OemBatteryOptimizationHelper
import com.blackback.batterydetector.utils.ShizukuRunner
import com.blackback.batterydetector.ui.theme.BatteryDetectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, as its own Activity.
 *
 * This is deliberately a separate Activity rather than an in-Compose page swap:
 * the transition between the two screens is then a platform Activity transition,
 * animated by the window manager from `android.R.anim` (see
 * `Animation.BatteryDetector.Slide`), instead of Compose animating a whole screen
 * while the heavy dashboard recomposes alongside it.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        applyNativeTransition()
        setContent {
            BatteryDetectorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(onBack = { finish() })
                }
            }
        }
    }

    /**
     * Android 14 removed the theme's enter/exit animation attributes, so the
     * open transition has to be requested from code there. Earlier releases use
     * the style instead.
     */
    private fun applyNativeTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.slide_in_right,
                R.anim.slide_out_left
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val coroutineScope = rememberCoroutineScope()

    val latestInfoState by BatteryMonitorService.latestBatteryInfo.collectAsState()

    var deviceRole by remember { mutableStateOf(prefs.deviceRole) }
    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var lanPortStr by remember { mutableStateOf(prefs.lanPort.toString()) }
    var enableLanBroadcast by remember { mutableStateOf(prefs.enableLanBroadcast) }
    var webhookUrl by remember { mutableStateOf(prefs.webhookUrl) }
    var lowThreshold by remember { mutableFloatStateOf(prefs.lowBatteryThreshold.toFloat()) }
    var checkInterval by remember { mutableIntStateOf(prefs.checkIntervalMinutes) }
    var useRootMode by remember { mutableStateOf(prefs.useRootMode) }

    var useHyperOsBypass by remember { mutableStateOf(prefs.useHyperOsFocusBypass) }
    var enableDedup by remember { mutableStateOf(prefs.enableNotificationDedup) }
    var enableLiveUpdate by remember { mutableStateOf(prefs.enableLiveUpdate) }
    var isSendingTestPush by remember { mutableStateOf(false) }
    var isCheckingRoot by remember { mutableStateOf(false) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    // Whether this device can actually render a Super Island. Kept as state rather
    // than read inline so the island switch can be disabled with a clear reason
    // instead of silently doing nothing when tapped.
    val islandSupported = remember {
        val support = HyperOsFocusNotification.detectSupport(context)
        support.isXiaomi && support.capability == HyperOsFocusNotification.Capability.ISLAND
    }

    // A preference left true from a device that did support the island would keep
    // posting focus notifications here, where the switch reads as off. Clear it so
    // the stored state matches what the UI shows.
    LaunchedEffect(islandSupported) {
        if (!islandSupported && prefs.useHyperOsFocusBypass) {
            prefs.useHyperOsFocusBypass = false
        }
    }

    fun openNotificationSettings() {
        // The per-channel page needs the channel to exist. It is otherwise only
        // created once the receiver starts or a test notification is sent, so on a
        // fresh install this button used to open a blank screen on ColorOS: that
        // skin does not validate the channel id and launches the activity anyway.
        LanSyncEngine.ensureNotificationChannel(context)

        val channelExists = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager?.getNotificationChannel(LanSyncEngine.CHANNEL_ID) != null
        } else {
            // Channels do not exist below API 26; the channel page is meaningless
            // there, so fall through to the app-level page.
            false
        }

        // Only open the channel page when there is really a channel behind it.
        // Otherwise the app-level page is used, which always has content.
        if (channelExists) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, LanSyncEngine.CHANNEL_ID)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Fall through to the app-level page below.
            }
        }

        try {
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra("app_package", context.packageName)
                putExtra("app_uid", context.applicationInfo.uid)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "请在手机【设置-应用设置-通知管理】中开启【悬浮通知】和【响铃】", Toast.LENGTH_LONG).show()
        }
    }

    errorDialogMessage?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { errorDialogMessage = null },
            title = { Text("操作失败", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = errorMsg,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { errorDialogMessage = null }) {
                    Text("确定", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // ---------------- 常规设置 ----------------

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "通信",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = {
                            deviceName = it
                            prefs.deviceName = it
                        },
                        label = { Text("本机名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = lanPortStr,
                        onValueChange = {
                            lanPortStr = it
                            it.toIntOrNull()?.let { port -> prefs.lanPort = port }
                        },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text(
                        text = "当前角色：${
                            if (deviceRole == AppPreferences.ROLE_RECEIVER) "接收方" else "发送方"
                        }（在首页「服务模式」中切换）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "通知",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = { openNotificationSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("通知与弹窗设置", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    // Live updates for received alerts.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "使用实况通知", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = "需要 Android 16+，可能导致通知无法直接弹出。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = enableLiveUpdate,
                            onCheckedChange = {
                                enableLiveUpdate = it
                                prefs.enableLiveUpdate = it
                            }
                        )
                    }

                    val isIgnoringBattery = OemBatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                    if (!isIgnoringBattery) {
                        Text(
                            text = "检测到当前未开启【忽略电池优化】，建议开启以防止后台服务被休眠挂起。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Button(
                            onClick = { OemBatteryOptimizationHelper.smartOpenBatterySettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("开启后台保活与自启动权限", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // ---------------- HyperOS 超级岛 ----------------
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (islandSupported) {
                                    "使用小米超级岛（实验性）"
                                } else {
                                    "使用小米超级岛（实验性）— 当前设备不可用"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (islandSupported) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                            Text(
                                text = if (islandSupported) {
                                    "仅在 HyperOS 3.0 版本验证可用，可能出现通知无法显示的情况。"
                                } else {
                                    "需要小米 HyperOS 3.0 及以上系统。"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = useHyperOsBypass && islandSupported,
                            enabled = islandSupported,
                            onCheckedChange = {
                                useHyperOsBypass = it
                                prefs.useHyperOsFocusBypass = it
                            }
                        )
                    }

                    if (islandSupported) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    ShizukuRunner.grantHyperOsSuperIslandPermissions(
                                        context,
                                        LanSyncEngine.CHANNEL_ID
                                    ) { msg ->
                                        coroutineScope.launch(Dispatchers.Main) {
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("检测 Shizuku", fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        HyperOsFocusNotification.post(
                                            context = context,
                                            notificationId = 10012,
                                            channelId = LanSyncEngine.CHANNEL_ID,
                                            content = HyperOsFocusNotification.Content(
                                                title = "BatteryDetector 超级岛测试",
                                                body = "设备 [$deviceName] 超级岛通知测试",
                                                deviceName = deviceName,
                                                batteryLevel = (latestInfoState?.level ?: 88),
                                                isCharging = latestInfoState?.isCharging == true,
                                                isTest = true,
                                                iconRes = R.drawable.ic_battery_notification
                                            ),
                                            useXmsfBypass = useHyperOsBypass
                                        ) { line -> LogRepository.addLog("[超级岛] $line") }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("测试超级岛", fontSize = 13.sp)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // ---------------- 收发链路自检 ----------------
                    Text(text = "收发链路自检", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    // Short-window deduplication, which would otherwise swallow the
                    // second of two quick test sends.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "短时间内重复通知自动忽略", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = "同一设备5秒内仅能收到1次通知",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = enableDedup,
                            onCheckedChange = {
                                enableDedup = it
                                prefs.enableNotificationDedup = it
                            }
                        )
                    }

                    Button(
                        onClick = {
                            val trimmedWebhook = webhookUrl.trim()
                            if (!enableLanBroadcast && trimmedWebhook.isEmpty()) {
                                errorDialogMessage = "发送失败：未配置 Webhook 推送地址，且未开启局域网广播。"
                                return@Button
                            }

                            isSendingTestPush = true
                            val port = lanPortStr.toIntOrNull() ?: 18888

                            coroutineScope.launch {
                                LanSyncEngine.showLocalTestNotification(
                                    context = context,
                                    title = "BatteryDetector 测试推送",
                                    message = "设备 [$deviceName] 测试通知"
                                )
                            }

                            if (trimmedWebhook.isNotEmpty()) {
                                RemoteNotifier.sendNotification(
                                    webhookUrl = trimmedWebhook,
                                    deviceName = deviceName,
                                    batteryLevel = latestInfoState?.level ?: 88,
                                    isTest = true
                                ) { success, msg ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        isSendingTestPush = false
                                        if (success) {
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        } else {
                                            errorDialogMessage = msg
                                        }
                                    }
                                }
                            } else if (enableLanBroadcast) {
                                LanSyncEngine.sendUdpBroadcast(
                                    context = context,
                                    port = port,
                                    deviceName = deviceName,
                                    batteryLevel = latestInfoState?.level ?: 88,
                                    message = "局域网广播测试消息",
                                    isTest = true
                                ) { success, msg ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        isSendingTestPush = false
                                        if (success) {
                                            Toast.makeText(context, "局域网广播测试已发送", Toast.LENGTH_SHORT).show()
                                        } else {
                                            errorDialogMessage = "局域网广播发送失败:\n$msg"
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isSendingTestPush,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSendingTestPush) "发送中..." else "广播测试预警到局域网设备", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val port = lanPortStr.toIntOrNull() ?: 18888
                            LanSyncEngine.sendUdpBroadcast(
                                context = context,
                                port = port,
                                deviceName = "$deviceName (本机测试)",
                                batteryLevel = 12,
                                message = "接收方局域网同步测试",
                                isTest = true
                            ) { success, msg ->
                                if (success) {
                                    Toast.makeText(context, "已发送局域网同步测试数据", Toast.LENGTH_SHORT).show()
                                } else {
                                    errorDialogMessage = "局域网测试同步发送失败:\n$msg"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("广播低电量提醒到局域网设备", fontSize = 13.sp)
                    }
                }
            }

            if (deviceRole == AppPreferences.ROLE_SENDER) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "推送",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "局域网 UDP 广播同步", fontWeight = FontWeight.Medium)
                                Text(
                                    text = "自动向局域网内所有接收方设备广播低电量预警；" +
                                        "若关闭则需手动配置下方的推送地址。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = enableLanBroadcast,
                                onCheckedChange = {
                                    enableLanBroadcast = it
                                    prefs.enableLanBroadcast = it
                                }
                            )
                        }

                        // The webhook only matters when the LAN route is off, so the
                        // field is hidden while broadcasting is enabled rather than
                        // sitting there looking like a required setting.
                        if (!enableLanBroadcast) {
                            OutlinedTextField(
                                value = webhookUrl,
                                onValueChange = {
                                    webhookUrl = it
                                    prefs.webhookUrl = it
                                },
                                label = { Text("外网/指定 HTTP Webhook 地址") },
                                placeholder = { Text("Bark / Telegram / Gotify / 局域网接收方 IP") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Text(
                                text = "快捷填充 Webhook 示例:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SuggestionChip(
                                    onClick = {
                                        val example = "https://day.app/YOUR_BARK_KEY/"
                                        webhookUrl = example
                                        prefs.webhookUrl = example
                                    },
                                    label = { Text("Bark (iOS)") }
                                )
                                SuggestionChip(
                                    onClick = {
                                        val example = "https://api.telegram.org/bot<TOKEN>/sendMessage?chat_id=<CHAT_ID>"
                                        webhookUrl = example
                                        prefs.webhookUrl = example
                                    },
                                    label = { Text("Telegram") }
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "预警电量临界值: ${lowThreshold.toInt()}%",
                                fontWeight = FontWeight.Medium
                            )
                            Slider(
                                value = lowThreshold,
                                onValueChange = {
                                    lowThreshold = it
                                    prefs.lowBatteryThreshold = it.toInt()
                                },
                                valueRange = 5f..40f,
                                steps = 34
                            )
                        }

                        Column {
                            Text(text = "后台检测间隔: $checkInterval 分钟", fontWeight = FontWeight.Medium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                listOf(1, 3, 5, 10, 15).forEach { interval ->
                                    FilterChip(
                                        selected = checkInterval == interval,
                                        onClick = {
                                            checkInterval = interval
                                            prefs.checkIntervalMinutes = interval
                                        },
                                        label = { Text("${interval}m") }
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "优先使用 Root 模式", fontWeight = FontWeight.Medium)
                                Text(
                                    text = "通过 su 读取 /sys/class/.../capacity 核心节点",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = useRootMode,
                                enabled = !isCheckingRoot,
                                onCheckedChange = { wantsRoot ->
                                    if (!wantsRoot) {
                                        useRootMode = false
                                        prefs.useRootMode = false
                                        return@Switch
                                    }
                                    // Turning it on has to prove su works first. Leaving
                                    // the switch on when it does not would silently fall
                                    // back to the standard API at every check anyway.
                                    isCheckingRoot = true
                                    coroutineScope.launch {
                                        val hasRoot = withContext(Dispatchers.IO) {
                                            RootBatteryManager.checkRootAccess()
                                        }
                                        isCheckingRoot = false
                                        if (hasRoot) {
                                            useRootMode = true
                                            prefs.useRootMode = true
                                            Toast.makeText(
                                                context,
                                                "Root 权限可用 (uid=0)，已切换为 Root 模式",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            LogRepository.addLog("Root 权限检测通过，已开启 Root 模式")
                                        } else {
                                            useRootMode = false
                                            prefs.useRootMode = false
                                            errorDialogMessage =
                                                "无法获取 Root 权限。\n\n" +
                                                    "请确认设备已 Root，并在 Root 管理软件" +
                                                    "（Magisk / APatch / KernelSU）中为本应用授权。"
                                            LogRepository.addLog("Root 权限检测失败，已保持关闭", isError = true)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
