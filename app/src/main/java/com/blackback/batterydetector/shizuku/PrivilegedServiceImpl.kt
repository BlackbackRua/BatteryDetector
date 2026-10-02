package com.blackback.batterydetector.shizuku

import android.content.Context
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.annotation.Keep
import com.blackback.batterydetector.IPrivilegedService
import org.json.JSONArray
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs in a Shizuku user service process, which is a plain `app_process` started
 * as shell (uid 2000) instead of a normal Android app process.
 *
 * Why this exists: the HyperOS focus-notification ("超级岛") whitelist check asks
 * the XMSF service whether this package may post focus notifications. Blocking
 * XMSF's own network access for the moment the notification is posted makes that
 * check fail open. Doing so needs `IConnectivityManager.setUidFirewallRule`,
 * which an app process cannot call - only shell/root can. Hence this service.
 *
 * No Android APIs beyond framework classes are used here: a user service process
 * has no ContentResolver, no registered receivers, and is not a valid app process.
 */
@Keep
class PrivilegedServiceImpl() : IPrivilegedService.Stub() {

    init {
        record('D', "service started, uid=" + android.os.Process.myUid())
    }

    companion object {
        private const val TAG = "BatteryDetectorPriv"

        /**
         * Chain id used by HyperOS for this trick. HyperBridge (the shipping app
         * that popularised the workaround) hardcodes 9; the standard AOSP
         * firewall chains (0..3) are tried as a fallback.
         */
        private const val PREFERRED_CHAIN = 9
        private val FALLBACK_CHAINS = intArrayOf(0, 1, 2, 3)

        /** ConnectivityManager.FIREWALL_RULE_ALLOW / FIREWALL_RULE_DENY */
        private const val RULE_ALLOW = 0
        private const val RULE_DENY = 2

        private const val OP_TIMEOUT_MS = 3000L

        private const val MAX_LOG_LINES = 60

        /**
         * Secure settings SystemUI reads to decide whether a package may post
         * focus notifications. Verified on HyperOS 3 (Android 16).
         */
        private val FOCUS_ALLOWLIST_KEYS = listOf("focus_notifs", "updatable_focus_notifs")

        private val logLines: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        /** Diagnostic trail the app reads back to explain what happened on device. */
        fun drainLog(): List<String> = synchronized(logLines) {
            val copy = logLines.toList()
            logLines.clear()
            copy
        }

        private fun record(level: Char, message: String) {
            val line = "[$level] $message"
            Log.println(if (level == 'E') Log.ERROR else Log.DEBUG, TAG, message)
            synchronized(logLines) {
                logLines.add(line)
                while (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
            }
        }
    }

    override fun destroy() {
        record('D', "destroy requested")
        System.exit(0)
    }

    override fun getServiceUid(): Int = android.os.Process.myUid()

    override fun isFirewallApiAvailable(): Boolean {
        return try {
            val cm = connectivityManager()
            methodOrNull(cm, "setFirewallChainEnabled", 2) != null &&
                methodOrNull(cm, "setUidFirewallRule", 3) != null
        } catch (e: Throwable) {
            record('E', "firewall API probe failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    override fun setUidFirewallRule(uid: Int, allowed: Boolean): Boolean {
        val rule = if (allowed) RULE_ALLOW else RULE_DENY
        // Preferred chain first, then the standard ones; the first chain that
        // accepts the rule wins.
        val chains = IntArray(FALLBACK_CHAINS.size + 1).also {
            it[0] = PREFERRED_CHAIN
            FALLBACK_CHAINS.copyInto(it, 1)
        }

        for (chain in chains) {
            val applied = runOnWorker("chain=$chain uid=$uid rule=$rule") {
                applyFirewallRule(chain, uid, rule)
            }
            if (applied) {
                record('D', "firewall rule applied (chain=$chain uid=$uid ${if (allowed) "ALLOW" else "DENY"})")
                return true
            }
        }

        record('E', "no firewall chain accepted the rule for uid=$uid")
        return false
    }

    // ---------------------------------------------------------------------
    // Focus-notification allowlist
    //
    // SystemUI answers `canShowFocus` from Settings.Secure.focus_notifs. That
    // setting is normally written by Xiaomi's platform after their review, but
    // it is a plain secure setting, so the shell identity of this service can
    // write it too. This is the clean path: it needs no network manipulation.
    // ---------------------------------------------------------------------

    override fun grantFocusNotification(packageName: String): Boolean {
        var ok = true
        FOCUS_ALLOWLIST_KEYS.forEach { key ->
            val updated = updateAllowlist(key, packageName, add = true)
            if (updated == null) ok = false
        }
        record(if (ok) 'D' else 'E', "grantFocusNotification($packageName) ok=$ok")
        return ok
    }

    override fun revokeFocusNotification(packageName: String): Boolean {
        var ok = true
        FOCUS_ALLOWLIST_KEYS.forEach { key ->
            val updated = updateAllowlist(key, packageName, add = false)
            if (updated == null) ok = false
        }
        record(if (ok) 'D' else 'E', "revokeFocusNotification($packageName) ok=$ok")
        return ok
    }

    override fun getFocusAllowlist(): String {
        return runCatching {
            val context = systemContext() ?: return ""
            Settings.Secure.getString(context.contentResolver, FOCUS_ALLOWLIST_KEYS.first()).orEmpty()
        }.getOrDefault("")
    }

    /**
     * Rewrites one allowlist entry. The value is a JSON array of strings; note
     * that HyperOS later replaces the package name with the real notification
     * key once it has taken the notification over, so entries are matched by
     * prefix rather than equality.
     *
     * @return the new value, or null when the settings write failed.
     */
    private fun updateAllowlist(key: String, packageName: String, add: Boolean): String? {
        return runOnWorkerResult("$key add=$add") {
            val context = systemContext() ?: error("no system context")
            val resolver = context.contentResolver

            val current = Settings.Secure.getString(resolver, key).orEmpty()
            val array = runCatching { JSONArray(current) }.getOrElse { JSONArray() }

            val entries = (0 until array.length()).mapNotNull {
                array.optString(it).takeIf { s -> s.isNotEmpty() }
            }
            val kept = entries.filterNot { it == packageName || it.contains(packageName) }
            val next = if (add) kept + packageName else kept

            val json = JSONArray().apply { next.forEach { put(it) } }.toString()
            val written = Settings.Secure.putString(resolver, key, json)
            if (written) json else error("putString returned false for $key")
        }
    }

    /**
     * A user service process has no Application, but the system context is
     * reachable through ActivityThread and carries a working ContentResolver.
     */
    private fun systemContext(): Context? {
        cachedContext?.let { return it }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getMethod("systemMain").invoke(null)
            val getSystemContext = activityThread.getMethod("getSystemContext")
            val ctx = getSystemContext.invoke(systemMain) as? Context
            cachedContext = ctx
            ctx
        }.getOrNull()
    }

    /**
     * ConnectivityManager is fetched through IConnectivityManager rather than
     * Context.getSystemService, because this process has no usable Context.
     */
    private fun connectivityManager(): Any {
        val binder: IBinder = try {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "connectivity") as? IBinder
                ?: throw IllegalStateException("connectivity service not found")
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        // asInterface is what turns the raw binder into the framework proxy.
        val stub = Class.forName("android.net.IConnectivityManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw IllegalStateException("asInterface returned null")
    }

    private fun applyFirewallRule(chain: Int, uid: Int, rule: Int) {
        val cm = connectivityManager()

        // Enabling the chain is required before rules in it take effect. Some
        // builds already have it enabled and throw, which is harmless.
        runCatching { invokeResilient(cm, "setFirewallChainEnabled", chain, true) }
            .onFailure { record('D', "setFirewallChainEnabled($chain) ignored: ${it.javaClass.simpleName}") }

        invokeResilient(cm, "setUidFirewallRule", chain, uid, rule)
    }

    /**
     * Hidden API signatures differ between vendor builds, so match by name and
     * parameter count and coerce primitive arguments.
     */
    private fun invokeResilient(target: Any, name: String, vararg args: Any) {
        val method = methodOrNull(target, name, args.size)
            ?: throw NoSuchMethodException("$name/${args.size} not found on ${target.javaClass.name}")

        val coerced = Array(args.size) { i ->
            val type = method.parameterTypes[i]
            val arg = args[i]
            when {
                type == Int::class.javaPrimitiveType && arg is Number -> arg.toInt()
                type == Boolean::class.javaPrimitiveType && arg is Boolean -> arg
                type == Boolean::class.javaPrimitiveType && arg is Number -> arg.toInt() != 0
                type == Int::class.javaPrimitiveType && arg is Boolean -> if (arg) 1 else 0
                else -> arg
            }
        }

        try {
            method.invoke(target, *coerced)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun methodOrNull(target: Any, name: String, paramCount: Int): java.lang.reflect.Method? {
        return target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == paramCount
        }?.apply { isAccessible = true }
    }

    /**
     * Binder and settings calls must not run on the main thread of the user
     * service, so they are executed on a short-lived worker with a timeout.
     *
     * @return the block's value, or null when it failed or timed out.
     */
    private fun <T> runOnWorkerResult(label: String, block: () -> T): T? {
        val result = AtomicReference<Result<T>?>(null)
        val latch = CountDownLatch(1)
        val thread = Thread {
            result.set(runCatching(block))
            latch.countDown()
        }
        thread.isDaemon = true
        thread.start()

        if (!latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            record('E', "timed out after ${OP_TIMEOUT_MS}ms ($label)")
            return null
        }

        val outcome = result.get() ?: return null
        return outcome.fold(
            onSuccess = { it },
            onFailure = { e ->
                record('E', "$label failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        )
    }

    /**
     * Binder calls must not run on the main thread of the user service.
     */
    private fun runOnWorker(label: String, block: () -> Unit): Boolean =
        runOnWorkerResult(label, block) != null

    /** Cached system Context; created once and reused. */
    @Volatile
    private var cachedContext: Context? = null
}
