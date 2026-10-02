package com.blackback.batterydetector.utils

import android.content.Context
import android.content.pm.PackageManager
import com.blackback.batterydetector.data.LogRepository
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuRunner {

    private const val PERMISSION_REQUEST_CODE = 1001

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            isShizukuAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun execCommand(command: String): Pair<Boolean, String> {
        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val sb = StringBuilder()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            Pair(exitCode == 0, sb.toString().trim())
        } catch (e: Exception) {
            Pair(false, "Shizuku 执行异常: ${e.localizedMessage}")
        }
    }

    /**
     * Grants the MIUI/HyperOS permissions that a normal floating notification
     * needs: a high-importance channel plus the MIUI "background pop-up" appop.
     *
     * This does NOT grant focus-notification ("超级岛") permission. That gate is an
     * XMSF remote check, not an appop; see
     * [com.blackback.batterydetector.shizuku.ShizukuFocusGrant.withXmsfBlocked]
     * for the mechanism that actually makes the island render.
     */
    fun grantHyperOsSuperIslandPermissions(context: Context, channelId: String, onFinished: (String) -> Unit) {
        if (!isShizukuAvailable()) {
            onFinished("未检测到 Shizuku 服务运行，请先在手机上启动 Shizuku。")
            return
        }

        if (!hasShizukuPermission()) {
            requestPermissionOnce(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onFinished(executeGrantCommands(context, channelId))
                    } else {
                        onFinished("Shizuku 授权被拒绝，无法自动开启悬浮通知权限。")
                    }
                }
            })
            onFinished("已发起 Shizuku 授权，请在弹出的 Shizuku 窗口中点击【允许】！")
            return
        }

        onFinished(executeGrantCommands(context, channelId))
    }

    /**
     * Installs exactly one result listener for [requestCode] and removes it on
     * both paths, so repeated taps cannot stack listeners or leave one behind.
     */
    private fun requestPermissionOnce(listener: Shizuku.OnRequestPermissionResultListener) {
        val requestCode = PERMISSION_REQUEST_CODE
        val wrapper = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(resultCode: Int, grantResult: Int) {
                if (resultCode != requestCode) return
                Shizuku.removeRequestPermissionResultListener(this)
                listener.onRequestPermissionResult(resultCode, grantResult)
            }
        }
        Shizuku.addRequestPermissionResultListener(wrapper)
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            Shizuku.removeRequestPermissionResultListener(wrapper)
        }
    }

    private fun executeGrantCommands(context: Context, channelId: String): String {
        val pkg = context.packageName

        // Only commands with an established effect are kept.
        //  - channel importance 4 = IMPORTANCE_HIGH, needed for heads-up
        //  - 10021 is MIUI's "background pop-up" op, which really is settable
        // Deliberately removed: `appops set $pkg 10022`, `SYSTEM_ALERT_WINDOW`
        // (not grantable from shell since Android 11) and the invented
        // `miui_notification_focus_allow` settings key - none of them do anything.
        val cmds = listOf(
            "cmd notification set_importance $pkg $channelId 4" to "提升通知渠道重要级",
            "appops set $pkg 10021 allow" to "允许后台弹出界面",
            "cmd notification set_bubbles $pkg true" to "允许气泡通知"
        )

        val failed = mutableListOf<String>()
        cmds.forEach { (cmd, label) ->
            val (ok, output) = execCommand(cmd)
            if (!ok) failed += "$label (${output.ifBlank { "无输出" }})"
        }

        val msg = if (failed.isEmpty()) {
            "已通过 Shizuku 开启悬浮通知相关权限（后台弹出界面、高重要级渠道、气泡）。"
        } else {
            "部分权限设置失败：${failed.joinToString("；")}"
        }
        LogRepository.addLog(msg, isError = failed.isNotEmpty())
        return msg
    }
}
