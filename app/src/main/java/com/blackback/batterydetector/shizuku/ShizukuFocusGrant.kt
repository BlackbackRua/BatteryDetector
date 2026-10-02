package com.blackback.batterydetector.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.blackback.batterydetector.IPrivilegedService
import com.blackback.batterydetector.data.LogRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Gives this app HyperOS focus-notification ("超级岛") permission without going
 * through Xiaomi's developer review.
 *
 * Two mechanisms are available, and this object owns both:
 *
 * 1. **Allowlist write (preferred).** SystemUI answers `canShowFocus` from
 *    `Settings.Secure.focus_notifs`. That setting is what Xiaomi's platform
 *    normally writes after approving an app, but it is an ordinary secure
 *    setting, so the shell identity of the Shizuku user service can write it
 *    directly. One write, no side effects, permission persists.
 *
 * 2. **XMSF network race (fallback).** Cut `com.xiaomi.xmsf` network access for
 *    the moment the notification is posted, so the remote auth call fails and
 *    SystemUI fails open. Works, but silences all Xiaomi push on the device for
 *    about a second per notification, so it is only used when (1) is unavailable.
 */
object ShizukuFocusGrant {

    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    /** How long XMSF stays cut off around a post, on the fallback path. */
    private const val PRE_POST_DELAY_MS = 50L
    private const val NETWORK_RESTORE_DELAY_MS = 1000L
    private const val SERVICE_BIND_TIMEOUT_MS = 6000L

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val bindMutex = Mutex()
    private val raceMutex = Mutex()

    @Volatile
    private var service: IPrivilegedService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IPrivilegedService?>? = null

    @Volatile
    private var xmsfUid: Int = -1

    /**
     * The user service runs under this app's own package, so the component name
     * is arbitrary as long as it is stable: Shizuku resolves the class by name in
     * the `app_process` it starts.
     */
    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                "com.blackback.batterydetector",
                PrivilegedServiceImpl::class.java.name
            )
        )
            .daemon(false)
            .processNameSuffix("hyperos_focus")
            .version(2)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = IPrivilegedService.Stub.asInterface(binder)
            service = bound
            _isAvailable.value = bound != null
            pendingBind?.complete(bound)
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _isAvailable.value = false
        }
    }

    private fun isXiaomiDevice(): Boolean =
        android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    suspend fun ensureService(context: Context): IPrivilegedService? {
        service?.let { return it }
        if (!isXiaomiDevice()) return null

        return bindMutex.withLock {
            service?.let { return@withLock it }

            if (!Shizuku.pingBinder()) return@withLock null

            if (!hasPermission()) {
                if (!requestPermission()) return@withLock null
            }

            val deferred = CompletableDeferred<IPrivilegedService?>()
            pendingBind = deferred

            try {
                withContext(Dispatchers.Main) {
                    Shizuku.bindUserService(serviceArgs, connection)
                }
            } catch (e: Exception) {
                pendingBind = null
                return@withLock null
            }

            val bound = withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) { deferred.await() }
            if (bound == null) pendingBind = null
            bound
        }
    }

    fun hasPermission(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /**
     * Blocks until the user answers the Shizuku permission dialog, unlike a
     * fire-and-forget request.
     */
    private suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        val requestCode = 0x0F06
        val result = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
            if (code != requestCode) return@OnRequestPermissionResultListener
            result.complete(grantResult == PackageManager.PERMISSION_GRANTED)
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(requestCode)
            withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) { result.await() } ?: false
        } catch (e: Exception) {
            false
        } finally {
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
        }
    }

    // ------------------------------------------------------------------
    // Path 1: allowlist write
    // ------------------------------------------------------------------

    /**
     * Grants focus-notification permission by adding this package to HyperOS's
     * allowlists.
     *
     * @return a message describing the result, suitable for showing and logging.
     */
    /**
     * Writes this package into HyperOS's focus-notification allowlists.
     *
     * **Experimental, and not the working mechanism.** Measurement on a real
     * HyperOS 3 device showed `canShowFocus` returns true even when both
     * allowlists are empty, so this cannot be confirmed or refuted through the
     * provider, and it was not what produced the island. The verified path is
     * [withXmsfBlocked]. Kept only because it demonstrably writes the setting and
     * may matter on other builds.
     *
     * @return a message describing what was actually observed.
     */
    suspend fun grantFocusPermission(context: Context): GrantResult {
        val privileged = try {
            ensureService(context)
        } catch (e: TimeoutCancellationException) {
            null
        } ?: return GrantResult(false, "无法启动 Shizuku 用户服务，请确认 Shizuku 正在运行并已授权")

        val wrote = privileged.grantFocusNotification(context.packageName)
        delay(300)
        val allowlist = readAllowlist(context)

        // Deliberately no claim about the permission taking effect: canShowFocus
        // is not a reliable indicator on this build.
        return if (wrote) {
            GrantResult(
                true,
                "已写入焦点通知白名单（实验性，无法确证生效）。当前 focus_notifs=$allowlist\n" +
                    "真正能上岛的是下方「网络绕行」，请确保它已开启。"
            )
        } else {
            GrantResult(false, "写入焦点通知白名单失败（Shizuku 权限或系统限制）")
        }
    }

    /** Removes this package from the allowlists, restoring the stock state. */
    suspend fun revokeFocusPermission(context: Context): GrantResult {
        val privileged = try {
            ensureService(context)
        } catch (e: TimeoutCancellationException) {
            null
        } ?: return GrantResult(false, "无法启动 Shizuku 用户服务")

        val revoked = privileged.revokeFocusNotification(context.packageName)
        return if (revoked) {
            GrantResult(true, "已从焦点通知白名单移除")
        } else {
            GrantResult(false, "移除失败")
        }
    }

    /** Reads the current allowlist back, for diagnostics. */
    suspend fun readAllowlist(context: Context): String {
        val privileged = try {
            ensureService(context)
        } catch (e: TimeoutCancellationException) {
            null
        } ?: return "（无法连接 Shizuku 用户服务）"
        return runCatching { privileged.focusAllowlist }.getOrDefault("（读取失败）")
    }

    // ------------------------------------------------------------------
    // Path 2: XMSF network race (fallback)
    // ------------------------------------------------------------------

    fun xmsfUidOrNull(context: Context): Int? {
        if (xmsfUid > 0) return xmsfUid
        return try {
            context.packageManager.getPackageUid(XMSF_PACKAGE, 0).also { xmsfUid = it }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Runs [post] while XMSF has no network access, then restores it. The network
     * is always restored, including when [post] throws, so a failure cannot leave
     * XMSF permanently cut off.
     *
     * @return true when the bypass was applied; false means [post] ran without it.
     */
    suspend fun <T> withXmsfBlocked(
        context: Context,
        onDiagnostic: (String) -> Unit = {},
        post: suspend () -> T
    ): Boolean {
        val privileged = ensureService(context)
        if (privileged == null) {
            onDiagnostic("Shizuku 用户服务不可用，直接发送通知（未绕过校验）")
            post()
            return false
        }

        val uid = xmsfUidOrNull(context)
        if (uid == null) {
            onDiagnostic("未找到 $XMSF_PACKAGE，跳过网络绕行")
            post()
            return false
        }

        return raceMutex.withLock {
            var blocked = false
            try {
                blocked = privileged.setUidFirewallRule(uid, false)
                if (!blocked) {
                    onDiagnostic("无法切断 XMSF 网络（防火墙接口不可用），直接发送通知")
                } else {
                    delay(PRE_POST_DELAY_MS)
                }

                post()
                blocked
            } catch (e: Exception) {
                onDiagnostic("发送过程异常: ${e.localizedMessage}")
                false
            } finally {
                if (blocked) {
                    delay(NETWORK_RESTORE_DELAY_MS)
                    val restored = runCatching { privileged.setUidFirewallRule(uid, true) }
                        .getOrDefault(false)
                    if (!restored) {
                        LogRepository.addLog(
                            "警告：XMSF 网络恢复失败，请重启 Shizuku 或重新授权",
                            isError = true
                        )
                    }
                    drainServiceLog(onDiagnostic)
                }
            }
        }
    }

    private fun drainServiceLog(onDiagnostic: (String) -> Unit) {
        val lines = runCatching { PrivilegedServiceImpl.drainLog() }.getOrDefault(emptyList())
        lines.takeLast(6).forEach(onDiagnostic)
    }

    /** Explains why the bypass may be unavailable, for the settings screen. */
    suspend fun probe(context: Context): String {
        if (!isXiaomiDevice()) return "非小米设备，无需也无法使用该绕行"
        if (!Shizuku.pingBinder()) return "Shizuku 服务未运行"
        if (!hasPermission()) return "尚未授予 Shizuku 权限"

        val privileged = try {
            ensureService(context)
        } catch (e: TimeoutCancellationException) {
            null
        }

        if (privileged == null) return "无法启动 Shizuku 用户服务"

        return buildString {
            append("Shizuku 用户服务已连接 (uid=").append(privileged.serviceUid).append(")")
            append(if (privileged.isFirewallApiAvailable) "，网络绕行接口可用" else "，网络绕行接口不可用")
            val target = xmsfUidOrNull(context)
            append(if (target != null) "，XMSF uid=$target" else "，未找到 XMSF")
            val allowlist = runCatching { privileged.focusAllowlist }.getOrDefault("")
            append("；focus_notifs=").append(allowlist.ifBlank { "[]" })
        }
    }

    data class GrantResult(val success: Boolean, val message: String)
}
