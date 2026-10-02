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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.blackback.batterydetector.shizuku.ShizukuFocusGrant
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

    // Debug-area state
    var useHyperOsBypass by remember { mutableStateOf(prefs.useHyperOsFocusBypass) }
    var enableDedup by remember { mutableStateOf(prefs.enableNotificationDedup) }
    var enableLiveUpdate by remember { mutableStateOf(prefs.enableLiveUpdate) }
    var hyperOsSupport by remember { mutableStateOf("") }
    var isTestingRoot by remember { mutableStateOf(false) }
    var isSendingTestPush by remember { mutableStateOf(false) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    fun openNotificationSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, LanSyncEngine.CHANNEL_ID)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
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
    }

    fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "请在系统设置中找到【悬浮窗/出现在其他应用上层】权限并开启", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        hyperOsSupport = HyperOsFocusNotification.detectSupport(context).summary
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
                        text = "本机与通信",
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
                        label = { Text("本机设备名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = lanPortStr,
                        onValueChange = {
                            lanPortStr = it
                            it.toIntOrNull()?.let { port -> prefs.lanPort = port }
                        },
                        label = { Text("局域网通信端口") },
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
                        text = "通知与权限",
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
                            Text(text = "收到的预警用实况通知显示", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = if (Build.VERSION.SDK_INT >= 36) {
                                    "把收到的告警显示成进度卡片，进度即对方设备电量，" +
                                        "在通知栏和锁屏直接可见。需要 Android 16 及以上。"
                                } else {
                                    "需要 Android 16 及以上，当前系统不支持，开关不生效。"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = enableLiveUpdate,
                            enabled = Build.VERSION.SDK_INT >= 36,
                            onCheckedChange = {
                                enableLiveUpdate = it
                                prefs.enableLiveUpdate = it
                            }
                        )
                    }

                    if (!android.provider.Settings.canDrawOverlays(context)) {
                        OutlinedButton(
                            onClick = { openOverlayPermissionSettings() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(
                                "开启【出现在其他应用上层】(悬浮窗权限)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                }
            }

            if (deviceRole == AppPreferences.ROLE_SENDER) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "预警与推送",
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
                                Text(text = "开启局域网 UDP 广播同步", fontWeight = FontWeight.Medium)
                                Text(
                                    text = "自动向局域网内所有接收方设备广播低电量预警",
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
                                onCheckedChange = {
                                    useRootMode = it
                                    prefs.useRootMode = it
                                }
                            )
                        }
                    }
                }
            }

            // ---------------- 调试区 ----------------

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            Text(
                text = "调试区域",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = "以下为开发者排查与一次性修复用，不属于正常使用所需功能。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )

            // 超级岛 / 焦点通知
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "HyperOS 超级岛通知", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = hyperOsSupport.ifEmpty { "正在探测设备能力..." },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "网络绕行（上岛开关）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = "发通知瞬间切断 XMSF 网络，使其远程鉴权失败放行，系统随即上岛。" +
                                    "这是唯一经真机验证有效的方式；期间本机小米推送中断约 1 秒。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = useHyperOsBypass,
                            onCheckedChange = {
                                useHyperOsBypass = it
                                prefs.useHyperOsFocusBypass = it
                            }
                        )
                    }

                    HorizontalDivider()

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                hyperOsSupport = "正在写入白名单..."
                                val result = ShizukuFocusGrant.grantFocusPermission(context)
                                hyperOsSupport = result.message
                                LogRepository.addLog("[超级岛] ${result.message}", isError = !result.success)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("实验性：写入焦点通知白名单", fontSize = 11.sp)
                    }
                    Text(
                        text = "实测在 HyperOS 3 上即使白名单为空 canShowFocus 也返回 true，" +
                            "因此该写入无法确证能开通权限，请以上面的网络绕行为准。",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    OutlinedButton(
                        onClick = {
                            ShizukuRunner.grantHyperOsSuperIslandPermissions(context, LanSyncEngine.CHANNEL_ID) { msg ->
                                coroutineScope.launch(Dispatchers.Main) {
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("使用 Shizuku 强制开启超级岛/悬浮通知", fontSize = 11.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    hyperOsSupport = "检测中..."
                                    val probe = ShizukuFocusGrant.probe(context)
                                    val allowlist = ShizukuFocusGrant.readAllowlist(context)
                                    val text = "$probe\n白名单: $allowlist"
                                    hyperOsSupport = text
                                    LogRepository.addLog("[超级岛] $text")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("检测 Shizuku", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    hyperOsSupport = "发送中..."
                                    val result = HyperOsFocusNotification.post(
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
                                    hyperOsSupport = result.message
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("测试超级岛", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 收发链路自检
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "收发自检", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = "用于确认通知链路是否正常，需要另一台设备配合或本机回环。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

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
                                text = "开启后，同一设备、同一电量在 5 秒内重复到达只处理一次。" +
                                    "调试接收时建议关闭，否则第二次测试会被静默丢弃。",
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
                                    message = "设备 [$deviceName] 测试通知与超级岛显示正常！"
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
                        Text(if (isSendingTestPush) "发送中..." else "测试推送通知")
                    }

                    OutlinedButton(
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
                        Text("测试接收同步", fontSize = 12.sp)
                    }
                }
            }

            // Root 权限
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "Root 权限", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Button(
                        onClick = {
                            isTestingRoot = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val hasRoot = RootBatteryManager.checkRootAccess()
                                withContext(Dispatchers.Main) {
                                    isTestingRoot = false
                                    if (hasRoot) {
                                        Toast.makeText(context, "Root 权限测试成功 (uid=0)", Toast.LENGTH_SHORT).show()
                                        LogRepository.addLog("Root 权限测试成功 (uid=0)")
                                    } else {
                                        val errMsg = "Root 权限测试失败：无法执行 su 命令。\n\n请检查设备是否已 Root，并在 Root 管理软件（如 Magisk / APatch / KernelSU）中允许获取权限。"
                                        errorDialogMessage = errMsg
                                        LogRepository.addLog(errMsg, isError = true)
                                    }
                                }
                            }
                        },
                        enabled = !isTestingRoot,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isTestingRoot) "Root 测试中..." else "测试 Root 权限")
                    }
                }
            }

            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
