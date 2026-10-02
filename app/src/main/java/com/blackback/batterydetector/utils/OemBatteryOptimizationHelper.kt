package com.blackback.batterydetector.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object OemBatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Smartly opens battery optimization or OEM-specific autostart/background manager page
     */
    fun smartOpenBatterySettings(context: Context) {
        try {
            if (!isIgnoringBatteryOptimizations(context)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {
        }

        openOemAutoStartSettings(context)
    }

    fun openOemAutoStartSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        val intents = mutableListOf<Intent>()

        if (manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
            intents.add(Intent().apply {
                component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            })
            intents.add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
        } else if (manufacturer.contains("huawei") || brand.contains("honor")) {
            intents.add(Intent().apply {
                component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            })
            intents.add(Intent().apply {
                component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            })
        } else if (manufacturer.contains("oppo") || brand.contains("realme") || manufacturer.contains("oneplus")) {
            intents.add(Intent().apply {
                component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            })
            intents.add(Intent().apply {
                component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            })
        } else if (manufacturer.contains("vivo") || brand.contains("iqoo")) {
            intents.add(Intent().apply {
                component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            })
        } else if (manufacturer.contains("meizu")) {
            intents.add(Intent().apply {
                component = ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
            })
        }

        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }
}
