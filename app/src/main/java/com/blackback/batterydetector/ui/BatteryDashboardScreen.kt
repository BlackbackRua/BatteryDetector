package com.blackback.batterydetector.ui

import android.Manifest
import android.content.Context
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blackback.batterydetector.R
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.data.BatteryInfo
import com.blackback.batterydetector.data.LogRepository
import com.blackback.batterydetector.network.LanSyncEngine
import com.blackback.batterydetector.root.RootBatteryManager
import com.blackback.batterydetector.service.BatteryMonitorService
import com.blackback.batterydetector.utils.OemBatteryOptimizationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryDashboardScreen() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val coroutineScope = rememberCoroutineScope()

    // Flow State
    val isServiceRunning by BatteryMonitorService.isRunning.collectAsState()
    val latestInfoState by BatteryMonitorService.latestBatteryInfo.collectAsState()
    val logs by LogRepository.logs.collectAsState()

    // Form Preferences State
    var deviceRole by remember { mutableStateOf(prefs.deviceRole) }
    var lanPortStr by remember { mutableStateOf(prefs.lanPort.toString()) }
    var useRootMode by remember { mutableStateOf(prefs.useRootMode) }

    // Runtime state
    var currentBatteryInfo by remember { mutableStateOf<BatteryInfo?>(null) }
    var localIpAddress by remember { mutableStateOf("获取中...") }
    var lastRefreshTimeStr by remember { mutableStateOf("未刷新") }
    var showBatteryOptimizationPrompt by remember { mutableStateOf(false) }
    var isCheckingRoot by remember { mutableStateOf(false) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Notification Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "通知权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未授予通知权限，提醒可能无法正常显示", Toast.LENGTH_LONG).show()
        }
    }

    fun refreshBatteryInfo() {
        coroutineScope.launch(Dispatchers.IO) {
            val info = if (useRootMode) {
                RootBatteryManager.getBatteryInfoViaRoot()
                    ?: RootBatteryManager.getBatteryInfoViaStandardApi(context)
            } else {
                RootBatteryManager.getBatteryInfoViaStandardApi(context)
            }
            val ip = LanSyncEngine.getLocalIpAddress(context)
            val nowStr = timeFormat.format(Date())
            withContext(Dispatchers.Main) {
                currentBatteryInfo = info
                localIpAddress = ip
                lastRefreshTimeStr = nowStr
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Auto-check battery optimization
        if (!OemBatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
            showBatteryOptimizationPrompt = true
        }
        refreshBatteryInfo()
    }

    // Auto-prompt for ignoring battery optimizations if not granted
    if (showBatteryOptimizationPrompt) {
        AlertDialog(
            onDismissRequest = { showBatteryOptimizationPrompt = false },
            title = { Text("后台保活提示", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "为了保证后台电量监测与局域网接收服务在系统休眠时不被终止，建议开启【忽略电池优化】及【后台无限制】权限。",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    showBatteryOptimizationPrompt = false
                    OemBatteryOptimizationHelper.smartOpenBatterySettings(context)
                }) {
                    Text("去设置", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryOptimizationPrompt = false }) {
                    Text("稍后再说")
                }
            }
        )
    }

    // Failure feedback for the Root switch, which cannot succeed silently.
    errorDialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorDialogMessage = null },
            title = { Text("操作失败", fontWeight = FontWeight.Bold) },
            text = { Text(text = msg, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { errorDialogMessage = null }) {
                    Text("知道了")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BatteryDetector", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        // Settings is its own Activity so the transition is a real
                        // platform Activity transition rather than a Compose page swap.
                        context.startActivity(
                            Intent(context, SettingsActivity::class.java),
                            ActivityOptions.makeCustomAnimation(
                                context,
                                R.anim.slide_in_right,
                                R.anim.slide_out_left
                            ).toBundle()
                        )
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "设置",
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

            // 1. 【最上方】设备状态概览 Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "设备状态",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val displayInfo = latestInfoState ?: currentBatteryInfo
                    val batteryLevel = displayInfo?.level ?: -1

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (batteryLevel >= 0) "$batteryLevel%" else "--%",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (batteryLevel <= prefs.lowBatteryThreshold) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = "数据来源: ${displayInfo?.source ?: "读取中..."}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            StatusBadge(
                                label = if (displayInfo?.isCharging == true) "正在充电" else "未充电",
                                isSuccess = displayInfo?.isCharging == true
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            StatusBadge(
                                label = if (isServiceRunning) {
                                    "服务已开启 (${if (deviceRole == AppPreferences.ROLE_RECEIVER) "接收" else "发送"})"
                                } else {
                                    "服务已停止"
                                },
                                isSuccess = isServiceRunning
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "电压: ${displayInfo?.voltage ?: "N/A"}", fontSize = 13.sp)
                        Text(text = "温度: ${displayInfo?.temperature ?: "N/A"}", fontSize = 13.sp)
                        Text(text = "IP: $localIpAddress", fontSize = 13.sp)
                    }

                    Text(
                        text = "上次刷新时间: $lastRefreshTimeStr",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // 服务开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "服务", fontWeight = FontWeight.Bold)
                            Text(
                                text = if (isServiceRunning) "服务运行中" else "关闭状态 (开启后配置服务模式)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { enable ->
                                prefs.isServiceEnabled = enable
                                if (enable) {
                                    BatteryMonitorService.startService(context)
                                    Toast.makeText(context, "后台服务已启动", Toast.LENGTH_SHORT).show()
                                } else {
                                    BatteryMonitorService.stopService(context)
                                    Toast.makeText(context, "后台服务已停止", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    // Root 模式。Turning it on proves su works first; leaving the
                    // switch on when it does not would silently fall back to the
                    // standard API on every read anyway.
                    //
                    // Shown only where it does something: a receiver never runs the
                    // battery check loop, and with the service off nothing is reading
                    // the battery at all.
                    if (isServiceRunning && deviceRole == AppPreferences.ROLE_SENDER) {
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
                                        // Re-read immediately so the card above
                                        // reflects the new source.
                                        refreshBatteryInfo()
                                    } else {
                                        useRootMode = false
                                        prefs.useRootMode = false
                                        errorDialogMessage =
                                            "无法获取 Root 权限，已保持关闭。\n\n" +
                                                "请确认设备已 Root，并在 Root 管理软件" +
                                                "（Magisk / APatch / KernelSU）中为本应用授权。"
                                        LogRepository.addLog("Root 权限检测失败，已保持关闭", isError = true)
                                    }
                                }
                            }
                        )
                    }
                    }

                    Button(
                        onClick = { refreshBatteryInfo() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text("刷新当前状态")
                    }
                }
            }

            // 2. 服务模式 Card (仅在后台服务开启时显示)
            if (isServiceRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "服务模式",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val isSender = deviceRole == AppPreferences.ROLE_SENDER
                            SegmentedButton(
                                selected = isSender,
                                onClick = {
                                    if (!isSender) {
                                        deviceRole = AppPreferences.ROLE_SENDER
                                        prefs.deviceRole = AppPreferences.ROLE_SENDER
                                        BatteryMonitorService.stopService(context)
                                        BatteryMonitorService.startService(context)
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) {
                                Text("发送方模式", fontWeight = FontWeight.Medium)
                            }

                            val isReceiver = deviceRole == AppPreferences.ROLE_RECEIVER
                            SegmentedButton(
                                selected = isReceiver,
                                onClick = {
                                    if (!isReceiver) {
                                        deviceRole = AppPreferences.ROLE_RECEIVER
                                        prefs.deviceRole = AppPreferences.ROLE_RECEIVER
                                        BatteryMonitorService.stopService(context)
                                        BatteryMonitorService.startService(context)
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) {
                                Text("接收方模式", fontWeight = FontWeight.Medium)
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (deviceRole == AppPreferences.ROLE_RECEIVER) {
                                    Text(
                                        text = "接收方说明",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "监控局域网广播，接收同一 Wi-Fi 内其他设备发来的电量预警。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    HorizontalDivider()
                                    Text(
                                        text = "本机接收地址: http://$localIpAddress:${lanPortStr.toIntOrNull() ?: 18888}/",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                } else {
                                    Text(
                                        text = "发送方说明",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "定时检测本设备电量，当电量不足时自动向外网 Webhook 或局域网广播发送通知。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Console Logs Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "日志", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { LogRepository.clearLogs() }) {
                            Text("清空")
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                text = "╰(￣ω￣ｏ)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        } else {
                            LazyColumn {
                                items(logs) { log ->
                                    val textColor = when {
                                        log.isError -> MaterialTheme.colorScheme.error
                                        log.isPushEvent -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        text = log.message,
                                        color = textColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Insets Protection
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
@Composable
fun StatusBadge(label: String, isSuccess: Boolean) {
    Surface(
        color = if (isSuccess) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (isSuccess) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
