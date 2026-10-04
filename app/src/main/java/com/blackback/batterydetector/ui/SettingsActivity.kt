package com.blackback.batterydetector.ui

import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackback.batterydetector.R
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.data.LogRepository
import com.blackback.batterydetector.network.LanSyncEngine
import com.blackback.batterydetector.network.RemoteNotifier
import com.blackback.batterydetector.network.SmtpMailer
import com.blackback.batterydetector.network.WebhookRequestBuilder
import com.blackback.batterydetector.service.BatteryMonitorService
import com.blackback.batterydetector.shizuku.HyperOsFocusNotification
import com.blackback.batterydetector.utils.OemBatteryOptimizationHelper
import com.blackback.batterydetector.utils.ShizukuRunner
import com.blackback.batterydetector.ui.theme.BatteryDetectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

/**
 * The delivery routes offered by the push picker, in display order.
 *
 * Declared once so the dropdown, its labels and the description text cannot drift
 * apart from the identifiers stored in preferences.
 */
private val PUSH_OPTIONS = listOf(
    AppPreferences.PUSH_LAN to "局域网广播",
    AppPreferences.PUSH_WEBHOOK to "Webhook 推送",
    AppPreferences.PUSH_EMAIL to "邮件推送",
    AppPreferences.PUSH_NONE to "不推送"
)

private fun pushMethodLabel(value: String): String =
    PUSH_OPTIONS.firstOrNull { it.first == value }?.second ?: "局域网广播"

/**
 * Notification service shapes offered by the webhook service picker.
 *
 * Explicit rather than sniffed from the URL: a self-hosted Bark server has no
 * `day.app` in its host, so guessing sent it the wrong payload entirely.
 */
private val SERVICE_OPTIONS = listOf(
    WebhookRequestBuilder.SERVICE_AUTO to "自动识别",
    WebhookRequestBuilder.SERVICE_BARK to "Bark",
    WebhookRequestBuilder.SERVICE_TELEGRAM to "Telegram Bot",
    WebhookRequestBuilder.SERVICE_CUSTOM to "自定义请求体"
)

private fun serviceLabel(value: String): String =
    SERVICE_OPTIONS.firstOrNull { it.first == value }?.second ?: "自动识别"

/** Bark `level` values; blank means the Bark app default. */
private val BARK_LEVELS = listOf(
    "" to "默认",
    "active" to "active",
    "timeSensitive" to "时效性",
    "critical" to "重要警告"
)

/**
 * Sends one alert over whichever route is currently selected.
 *
 * Extracted from the settings screen so it can sit at the bottom of the push card,
 * next to the settings it exercises, rather than far below them in the self-test
 * section. Keeping it a composable with explicit parameters avoids a second copy of
 * this logic drifting away from the first.
 */
@Composable
private fun TestAlertButton(
    pushMethod: String,
    webhookUrl: String,
    webhookMethod: String,
    webhookHeaders: String,
    webhookService: String,
    webhookCustomBody: String,
    barkSound: String,
    barkLevel: String,
    deviceName: String,
    batteryLevel: Int,
    lanPortStr: String,
    enabled: Boolean,
    onSendingChanged: (Boolean) -> Unit,
    onFailure: (String) -> Unit,
    onInfo: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    Button(
        onClick = {
            val port = lanPortStr.toIntOrNull() ?: 18888

            // Validate the selected route first, so the message names the thing
            // the user has to fix instead of failing later with a network error.
            when (pushMethod) {
                AppPreferences.PUSH_WEBHOOK ->
                    if (webhookUrl.trim().isEmpty()) {
                        onFailure("请先填写 Webhook 地址。")
                        return@Button
                    }
                AppPreferences.PUSH_EMAIL ->
                    if (SmtpMailer.configFrom(prefs) == null) {
                        onFailure("邮件未配置完整：需要 SMTP 服务器、发信账号、授权码和收件人。")
                        return@Button
                    }
                AppPreferences.PUSH_NONE ->
                    onInfo("当前推送方式为「不推送」，仅本机弹出通知")
            }

            onSendingChanged(true)

            // Resolved through the same helper the service uses, so a test send and
            // a real alert can never disagree about the wording.
            val testMessage = prefs.resolveAlertMessage(
                route = pushMethod,
                isTest = true,
                deviceName = deviceName,
                batteryLevel = batteryLevel
            )

            // The on-device notification is always shown, so the local rendering
            // path is exercised whatever route is selected.
            scope.launch {
                LanSyncEngine.showLocalTestNotification(
                    context = context,
                    title = "BatteryDetector 测试推送",
                    message = testMessage
                )
            }

            scope.launch {
                var failure: String? = null

                when (pushMethod) {
                    AppPreferences.PUSH_WEBHOOK -> {
                        val ok = suspendCancellableCoroutine<Boolean> { cont ->
                            RemoteNotifier.sendNotification(
                                webhookUrl = webhookUrl.trim(),
                                deviceName = deviceName,
                                batteryLevel = batteryLevel,
                                isTest = true,
                                method = webhookMethod,
                                headers = webhookHeaders,
                                service = webhookService,
                                customBody = webhookCustomBody,
                                barkSound = barkSound,
                                barkLevel = barkLevel,
                                messageOverride = testMessage
                            ) { success, msg ->
                                if (!success) failure = msg
                                if (cont.isActive) cont.resume(success)
                            }
                        }
                        if (ok) onInfo("Webhook 测试已发送")
                    }

                    AppPreferences.PUSH_EMAIL -> {
                        val config = SmtpMailer.configFrom(prefs)
                        val result = if (config == null) {
                            SmtpMailer.Result(false, "邮件未配置完整")
                        } else {
                            withContext(Dispatchers.IO) {
                                SmtpMailer.send(
                                    config = config,
                                    subject = "BatteryDetector 测试推送",
                                    body = testMessage
                                )
                            }
                        }
                        LogRepository.addLog("[邮件] ${result.message}", isError = !result.success)
                        if (result.success) onInfo("测试邮件已发送") else failure = result.message
                    }

                    AppPreferences.PUSH_NONE -> Unit

                    else -> {
                        val ok = suspendCancellableCoroutine<Boolean> { cont ->
                            LanSyncEngine.sendUdpBroadcast(
                                context = context,
                                port = port,
                                deviceName = deviceName,
                                batteryLevel = batteryLevel,
                                message = testMessage,
                                isTest = true
                            ) { success, msg ->
                                if (!success) failure = "局域网广播：$msg"
                                if (cont.isActive) cont.resume(success)
                            }
                        }
                        if (ok) onInfo("局域网广播测试已发送")
                    }
                }

                onSendingChanged(false)
                failure?.let { onFailure(it) }
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (enabled) "发送测试预警" else "发送中...", fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val coroutineScope = rememberCoroutineScope()

    val latestInfoState by BatteryMonitorService.latestBatteryInfo.collectAsState()

    var deviceRole by remember { mutableStateOf(prefs.deviceRole) }
    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var lanPortStr by remember { mutableStateOf(prefs.lanPort.toString()) }
    var enableLanBroadcast by remember { mutableStateOf(prefs.enableLanBroadcast) }
    var webhookUrl by remember { mutableStateOf(prefs.webhookUrl) }
    var webhookMethod by remember { mutableStateOf(prefs.webhookMethod) }
    var webhookHeaders by remember { mutableStateOf(prefs.webhookHeaders) }
    var webhookService by remember { mutableStateOf(prefs.webhookService) }
    var webhookCustomBody by remember { mutableStateOf(prefs.webhookCustomBody) }
    var barkSound by remember { mutableStateOf(prefs.barkSound) }
    var barkLevel by remember { mutableStateOf(prefs.barkLevel) }
    var serviceMenuExpanded by remember { mutableStateOf(false) }
    var barkLevelMenuExpanded by remember { mutableStateOf(false) }
    var pushMethod by remember { mutableStateOf(prefs.pushMethod) }
    var pushMenuExpanded by remember { mutableStateOf(false) }
    var smtpHost by remember { mutableStateOf(prefs.smtpHost) }
    var smtpPortStr by remember { mutableStateOf(prefs.smtpPort.toString()) }
    var smtpUser by remember { mutableStateOf(prefs.smtpUsername) }
    var smtpPassword by remember { mutableStateOf(prefs.smtpPassword) }
    var smtpFrom by remember { mutableStateOf(prefs.smtpFrom) }
    var smtpTo by remember { mutableStateOf(prefs.smtpTo) }
    var isSendingTestMail by remember { mutableStateOf(false) }
    var lowThreshold by remember { mutableFloatStateOf(prefs.lowBatteryThreshold.toFloat()) }
    var checkInterval by remember { mutableIntStateOf(prefs.checkIntervalMinutes) }

    var useHyperOsBypass by remember { mutableStateOf(prefs.useHyperOsFocusBypass) }
    var enableDedup by remember { mutableStateOf(prefs.enableNotificationDedup) }
    var enableLiveUpdate by remember { mutableStateOf(prefs.enableLiveUpdate) }
    var isSendingTestPush by remember { mutableStateOf(false) }
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

    /**
     * Opens the app's notification settings page.
     *
     * Deliberately the app-level page rather than the per-channel one: this button
     * is labelled as the app's notification settings, and the user needs to reach
     * every channel (alerts, the foreground service notification, live updates) from
     * one place. The channel page also required the channel to already exist, which
     * is what produced a blank screen on ColorOS before the channel was created.
     */
    fun openNotificationSettings() {
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

                        // Delivery route picker. One explicit choice replaces the
                        // previous layout, where every channel's fields were on
                        // screen at once and it was unclear which ones would fire.
                        Box {
                            OutlinedButton(
                                onClick = { pushMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = pushMethodLabel(pushMethod),
                                    modifier = Modifier.weight(1f),
                                    fontSize = 13.sp
                                )
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                                    contentDescription = "下拉菜单",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .rotate(if (pushMenuExpanded) 180f else 0f),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = pushMenuExpanded,
                                onDismissRequest = { pushMenuExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.92f)
                            ) {
                                PUSH_OPTIONS.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontSize = 13.sp) },
                                        trailingIcon = {
                                            RadioButton(
                                                selected = pushMethod == value,
                                                onClick = null
                                            )
                                        },
                                        onClick = {
                                            pushMethod = value
                                            prefs.pushMethod = value
                                            // Keep the legacy LAN flag in step, so
                                            // anything still reading it agrees.
                                            prefs.enableLanBroadcast = value == AppPreferences.PUSH_LAN
                                            enableLanBroadcast = value == AppPreferences.PUSH_LAN
                                            pushMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = when (pushMethod) {
                                AppPreferences.PUSH_WEBHOOK ->
                                    "预警会 POST 到下方地址。按 URL 自动匹配 Bark / Telegram 格式，其他地址使用通用 JSON。"
                                AppPreferences.PUSH_EMAIL ->
                                    "预警通过 SMTP 发送到邮箱，使用隐式 TLS（465 端口）。"
                                AppPreferences.PUSH_NONE ->
                                    "不发送任何推送，预警只记录在应用日志中。"
                                else ->
                                    "预警广播到同一 Wi-Fi 下的接收方设备。"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )

                        if (pushMethod == AppPreferences.PUSH_WEBHOOK) {
                            OutlinedTextField(
                                value = webhookUrl,
                                onValueChange = {
                                    webhookUrl = it
                                    prefs.webhookUrl = it
                                },
                                label = { Text("Webhook 地址") },
                                placeholder = { Text("https://... 或 局域网接收方 IP") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            // Service shape. Explicit so a self-hosted Bark server,
                            // whose host contains no day.app, still receives Bark's
                            // payload instead of the generic one.
                            Box {
                                OutlinedButton(
                                    onClick = { serviceMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "服务类型：${serviceLabel(webhookService)}",
                                        modifier = Modifier.weight(1f),
                                        fontSize = 13.sp
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_drop_down),
                                        contentDescription = "下拉菜单",
                                        modifier = Modifier
                                            .size(24.dp)
                                            .rotate(if (serviceMenuExpanded) 180f else 0f),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                DropdownMenu(
                                    expanded = serviceMenuExpanded,
                                    onDismissRequest = { serviceMenuExpanded = false },
                                    modifier = Modifier.fillMaxWidth(0.92f)
                                ) {
                                    SERVICE_OPTIONS.forEach { (value, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label, fontSize = 13.sp) },
                                            trailingIcon = {
                                                RadioButton(
                                                    selected = webhookService == value,
                                                    onClick = null
                                                )
                                            },
                                            onClick = {
                                                webhookService = value
                                                prefs.webhookService = value
                                                serviceMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            when (webhookService) {
                                WebhookRequestBuilder.SERVICE_BARK -> {
                                    Text(
                                        text = "Bark 官方服务器与自建 bark-server 都选这一项。" +
                                            "地址填到 key 为止，例如 https://day.app/你的KEY/",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = barkSound,
                                            onValueChange = {
                                                barkSound = it
                                                prefs.barkSound = it
                                            },
                                            label = { Text("铃声 sound（可空）") },
                                            placeholder = { Text("alarm") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        Box(modifier = Modifier.weight(1f)) {
                                            OutlinedButton(
                                                onClick = { barkLevelMenuExpanded = true },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                val current = BARK_LEVELS
                                                    .firstOrNull { it.first == barkLevel }?.second
                                                    ?: "默认"
                                                Text("级别：$current", modifier = Modifier.weight(1f), fontSize = 12.sp)
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                                                    contentDescription = "下拉菜单",
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .rotate(if (barkLevelMenuExpanded) 180f else 0f),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = barkLevelMenuExpanded,
                                                onDismissRequest = { barkLevelMenuExpanded = false }
                                            ) {
                                                BARK_LEVELS.forEach { (value, label) ->
                                                    DropdownMenuItem(
                                                        text = { Text(label, fontSize = 13.sp) },
                                                        onClick = {
                                                            barkLevel = value
                                                            prefs.barkLevel = value
                                                            barkLevelMenuExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                WebhookRequestBuilder.SERVICE_TELEGRAM -> {
                                    Text(
                                        text = "地址需含 token 与 chat_id，例如 " +
                                            "https://api.telegram.org/bot<TOKEN>/sendMessage?chat_id=<CHAT_ID>",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }

                                WebhookRequestBuilder.SERVICE_CUSTOM -> {
                                    Text(
                                        text = "自定义 JSON 请求体。可用占位符（字符串值需自己加引号）：" +
                                            "{{title}} {{message}} {{device}} {{battery}} {{timestamp}}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    OutlinedTextField(
                                        value = webhookCustomBody,
                                        onValueChange = {
                                            webhookCustomBody = it
                                            prefs.webhookCustomBody = it
                                        },
                                        label = { Text("请求体模板") },
                                        placeholder = {
                                            Text("""{"content":"{{message}}","level":{{battery}}}""")
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 3
                                    )
                                    // Live feedback, because the send path refuses
                                    // malformed JSON outright.
                                    val preview = WebhookRequestBuilder.renderTemplate(
                                        template = webhookCustomBody,
                                        title = "低电量预警",
                                        message = "设备 [示例] 当前电量仅剩 12%，请及时充电！",
                                        deviceName = "示例",
                                        batteryLevel = 12,
                                        timestamp = 0L
                                    )
                                    val bodyOk = webhookCustomBody.isNotBlank() &&
                                        WebhookRequestBuilder.isValidJson(preview)
                                    Text(
                                        text = when {
                                            webhookCustomBody.isBlank() -> "填入模板后这里会显示校验结果。"
                                            bodyOk -> "✓ 模板校验通过"
                                            else -> "✗ 模板不是合法 JSON，发送会被拒绝"
                                        },
                                        fontSize = 11.sp,
                                        color = if (bodyOk) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    )
                                }

                                else -> {
                                    Text(
                                        text = "按地址自动判断服务类型；无法识别时使用通用 JSON" +
                                            "（title / text / message / device / battery / timestamp）。",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = webhookMethod,
                                    onValueChange = {
                                        webhookMethod = it
                                        prefs.webhookMethod = it
                                    },
                                    label = { Text("请求方法") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = lanPortStr,
                                    onValueChange = {
                                        lanPortStr = it
                                        it.toIntOrNull()?.let { port -> prefs.lanPort = port }
                                    },
                                    label = { Text("局域网端口") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }

                            OutlinedTextField(
                                value = webhookHeaders,
                                onValueChange = {
                                    webhookHeaders = it
                                    prefs.webhookHeaders = it
                                },
                                label = { Text("自定义请求头（每行一个）") },
                                placeholder = { Text("Authorization: Bearer xxx\nX-Api-Key: yyy") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )

                            Text(
                                text = "需要鉴权的服务在这里填 Authorization / X-Api-Key 等；" +
                                    "以 # 开头的行会被忽略。GET / HEAD 不携带请求体。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )

                            Text(
                                text = "快捷填充地址:",
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

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // ---------------- 邮件推送 (SMTP) ----------------
                        if (pushMethod == AppPreferences.PUSH_EMAIL) {
                        Text(text = "邮件推送", fontWeight = FontWeight.Medium)
                        Text(
                            text = "将预警发到邮箱。固定使用隐式 TLS（465 端口）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )

                        OutlinedTextField(
                            value = smtpHost,
                            onValueChange = {
                                smtpHost = it
                                prefs.smtpHost = it
                            },
                            label = { Text("SMTP 服务器") },
                            placeholder = { Text("smtp.example.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = smtpPortStr,
                            onValueChange = {
                                smtpPortStr = it
                                it.toIntOrNull()?.let { port -> prefs.smtpPort = port }
                            },
                            label = { Text("端口") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = smtpUser,
                            onValueChange = {
                                smtpUser = it
                                prefs.smtpUsername = it
                            },
                            label = { Text("发信账号") },
                            placeholder = { Text("Username@example.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = smtpPassword,
                            onValueChange = {
                                smtpPassword = it
                                prefs.smtpPassword = it
                            },
                            label = { Text("授权码") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = smtpFrom,
                            onValueChange = {
                                smtpFrom = it
                                prefs.smtpFrom = it
                            },
                            label = { Text("发件人") },
                            placeholder = { Text("使用发件人账号") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = smtpTo,
                            onValueChange = {
                                smtpTo = it
                                prefs.smtpTo = it
                            },
                            label = { Text("收件人") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedButton(
                            onClick = {
                                val config = SmtpMailer.configFrom(prefs)
                                if (config == null) {
                                    errorDialogMessage =
                                        "邮件未配置完整：需要填写 SMTP 服务器、发信账号、授权码和收件人。"
                                    return@OutlinedButton
                                }
                                isSendingTestMail = true
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        SmtpMailer.send(
                                            config = config,
                                            subject = "BatteryDetector 邮件测试",
                                            body = "设备 [${prefs.deviceName}] 邮件推送配置正常。"
                                        )
                                    }
                                    isSendingTestMail = false
                                    if (result.success) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                        LogRepository.addLog("[邮件] ${result.message}")
                                    } else {
                                        errorDialogMessage = result.message
                                        LogRepository.addLog("[邮件] ${result.message}", isError = true)
                                    }
                                }
                            },
                            enabled = !isSendingTestMail,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isSendingTestMail) "发送中..." else "发送测试邮件",
                                fontSize = 12.sp
                            )
                        }
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

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // ---------------- 测试 ----------------
                        // Both test actions live at the end of this card, beside the
                        // settings they exercise.
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(context, AlertMessageActivity::class.java),
                                    ActivityOptions.makeCustomAnimation(
                                        context,
                                        R.anim.slide_in_right,
                                        R.anim.slide_out_left
                                    ).toBundle()
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("预警消息内容", fontSize = 13.sp)
                        }

                        TestAlertButton(
                            pushMethod = pushMethod,
                            webhookUrl = webhookUrl,
                            webhookMethod = webhookMethod,
                            webhookHeaders = webhookHeaders,
                            webhookService = webhookService,
                            webhookCustomBody = webhookCustomBody,
                            barkSound = barkSound,
                            barkLevel = barkLevel,
                            deviceName = deviceName,
                            batteryLevel = latestInfoState?.level ?: 88,
                            lanPortStr = lanPortStr,
                            enabled = !isSendingTestPush,
                            onSendingChanged = { isSendingTestPush = it },
                            onFailure = { errorDialogMessage = it },
                            onInfo = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        )

                        Button(
                            onClick = {
                                val port = lanPortStr.toIntOrNull() ?: 18888
                                val level = 12
                                // Same resolver as every other send path.
                                val lanMessage = prefs.resolveAlertMessage(
                                    route = AppPreferences.PUSH_LAN,
                                    isTest = true,
                                    deviceName = deviceName,
                                    batteryLevel = level
                                )
                                LanSyncEngine.sendUdpBroadcast(
                                    context = context,
                                    port = port,
                                    deviceName = "$deviceName (本机测试)",
                                    batteryLevel = level,
                                    message = lanMessage,
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
            }

            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
