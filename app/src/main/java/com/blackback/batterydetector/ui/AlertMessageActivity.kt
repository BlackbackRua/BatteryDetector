package com.blackback.batterydetector.ui

import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackback.batterydetector.R
import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.network.WebhookRequestBuilder
import com.blackback.batterydetector.ui.theme.BatteryDetectorTheme
import kotlinx.coroutines.delay

/**
 * Editor for the alert message text.
 *
 * Applies to real low-battery alerts as well as test sends: whatever is set here is
 * used whenever the alert fires, and the built-in wording is only a fallback for a
 * route left blank.
 *
 * The route is chosen from a drop-down rather than showing every route's field at
 * once, matching how the push method is picked on the settings screen. Each route
 * keeps its own template so a receiver can be exercised with the shape it will
 * actually get - a webhook body with awkward characters, an email with a newline,
 * and so on.
 *
 * Typing saves automatically after a short idle delay: there is no save button, and
 * writing on every keystroke would hit the disk for no benefit.
 */
class AlertMessageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyNativeTransition()
        setContent {
            BatteryDetectorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlertMessageScreen(onBack = { finish() })
                }
            }
        }
    }

    /**
     * Uses the platform activity transition so the page matches the rest of the app
     * instead of appearing instantly.
     */
    private fun applyNativeTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.slide_in_right,
                R.anim.slide_out_left
            )
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
        }
    }
}

/**
 * Routes whose test message can be customised, in the same order the push picker
 * presents them so the two lists do not read differently.
 *
 * "不推送" has no message of its own and is therefore absent; the screen falls back
 * to the first entry when that method is selected.
 */
private val MESSAGE_ROUTES = listOf(
    AppPreferences.PUSH_LAN to "局域网",
    AppPreferences.PUSH_WEBHOOK to "Webhook",
    AppPreferences.PUSH_EMAIL to "邮件"
)

private fun routeLabel(value: String): String =
    MESSAGE_ROUTES.firstOrNull { it.first == value }?.second ?: "Webhook"

/** Default text shown when a route has no custom template. */
private fun defaultPreview(route: String): String = when (route) {
    AppPreferences.PUSH_EMAIL -> "主题：BatteryDetector 测试推送\n\n设备 [本机] 邮件推送测试，当前电量 88%。"
    AppPreferences.PUSH_LAN -> "设备 [本机] 当前电量为 88%，网络通知功能正常！"
    else -> "设备 [本机] 当前电量为 88%，网络通知功能正常！"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertMessageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }

    // Open on whichever route the push settings are actually using, so the page
    // starts where the user is most likely to be editing. Falls back to the first
    // route when the current push method has no editable message (NONE).
    var route by remember {
        val current = prefs.pushMethod
        mutableStateOf(
            if (MESSAGE_ROUTES.any { it.first == current }) current else MESSAGE_ROUTES.first().first
        )
    }
    var text by remember { mutableStateOf(prefs.alertMessageTemplate(route)) }
    var routeMenuExpanded by remember { mutableStateOf(false) }

    // Reload the field whenever the route changes, so switching tabs shows that
    // route's own template rather than carrying the previous one over.
    LaunchedEffect(route) {
        text = prefs.alertMessageTemplate(route)
    }

    // Debounced autosave. Waiting for a pause avoids a disk write per keystroke
    // while still meaning the user never has to press save.
    LaunchedEffect(route, text) {
        if (prefs.alertMessageTemplate(route) == text) return@LaunchedEffect
        delay(500)
        prefs.setAlertMessageTemplate(route, text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("预警消息", fontWeight = FontWeight.Bold) },
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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "预警消息内容",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "真实低电量预警与测试发送都使用这里的内容；留空则用内置默认文案。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    // Route picker, styled like the push method selector.
                    Box {
                        OutlinedButton(
                            onClick = { routeMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "通道：${routeLabel(route)}",
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_drop_down),
                                contentDescription = "下拉菜单",
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(if (routeMenuExpanded) 180f else 0f),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = routeMenuExpanded,
                            onDismissRequest = { routeMenuExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.92f)
                        ) {
                            MESSAGE_ROUTES.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 13.sp) },
                                    trailingIcon = {
                                        RadioButton(selected = route == value, onClick = null)
                                    },
                                    onClick = {
                                        route = value
                                        routeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("消息内容") },
                        placeholder = { Text(defaultPreview(route)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4
                    )

                    Text(
                        text = "留空则使用内置默认文案。输入后自动保存。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    HorizontalDivider()

                    Text(
                        text = "可用占位符",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "发送时会被替换成实际值：",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    PLACEHOLDER_HINTS.forEach { (token, meaning) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = token,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = meaning,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
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

/**
 * Placeholder tokens offered in the editor.
 *
 * Kept in step with [WebhookRequestBuilder.TEMPLATE_TOKENS]; only the wording lives
 * here, the substitution rules stay in one place.
 */
private val PLACEHOLDER_HINTS = listOf(
    "{{device}}" to "本机设备名称",
    "{{battery}}" to "当前电量数字，例如 88",
    "{{title}}" to "标题，例如「低电量预警」",
    "{{message}}" to "内置的默认消息全文",
    "{{timestamp}}" to "毫秒时间戳"
)
