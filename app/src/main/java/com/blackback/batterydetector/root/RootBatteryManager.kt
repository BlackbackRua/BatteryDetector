package com.blackback.batterydetector.root

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.blackback.batterydetector.data.BatteryInfo
import java.io.BufferedReader
import java.io.InputStreamReader

object RootBatteryManager {

    private val CAPACITY_PATHS = listOf(
        "/sys/class/power_supply/battery/capacity",
        "/sys/class/power_supply/bms/capacity",
        "/sys/class/power_supply/main/capacity"
    )

    private val STATUS_PATHS = listOf(
        "/sys/class/power_supply/battery/status",
        "/sys/class/power_supply/bms/status",
        "/sys/class/power_supply/main/status"
    )

    private val VOLTAGE_PATHS = listOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now"
    )

    private val TEMP_PATHS = listOf(
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/bms/temp"
    )

    /**
     * Check whether Root privilege is granted.
     */
    fun checkRootAccess(): Boolean {
        val output = executeRootCommand("id") ?: return false
        return output.contains("uid=0")
    }

    /**
     * Read battery info via Root sysfs or dumpsys battery.
     */
    fun getBatteryInfoViaRoot(): BatteryInfo? {
        val level = readCapacityViaSysfs() ?: readCapacityViaDumpsys() ?: return null
        val isCharging = readStatusViaSysfs() ?: readStatusViaDumpsys() ?: false
        val voltageRaw = readSysfsFirstAvailable(VOLTAGE_PATHS)
        val tempRaw = readSysfsFirstAvailable(TEMP_PATHS)

        val voltageFormatted = voltageRaw?.toIntOrNull()?.let {
            if (it > 10000) "${it / 1000} mV" else "$it mV"
        } ?: "N/A"

        val tempFormatted = tempRaw?.toIntOrNull()?.let {
            val celsius = if (it > 100) it / 10.0 else it.toDouble()
            "$celsius °C"
        } ?: "N/A"

        return BatteryInfo(
            level = level,
            isCharging = isCharging,
            source = "Root (sysfs/dumpsys)",
            voltage = voltageFormatted,
            temperature = tempFormatted,
            health = "Good"
        )
    }

    /**
     * Fallback: Read battery info via Standard Android BatteryManager API.
     */
    fun getBatteryInfoViaStandardApi(context: Context): BatteryInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            0
        }

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

        val voltageFormatted = if (voltage > 0) "$voltage mV" else "N/A"
        val tempFormatted = if (temp > 0) "${temp / 10.0} °C" else "N/A"

        return BatteryInfo(
            level = batteryPct,
            isCharging = isCharging,
            source = "Standard Android API",
            voltage = voltageFormatted,
            temperature = tempFormatted,
            health = "Good"
        )
    }

    private fun readCapacityViaSysfs(): Int? {
        val value = readSysfsFirstAvailable(CAPACITY_PATHS)
        return value?.trim()?.toIntOrNull()
    }

    private fun readStatusViaSysfs(): Boolean? {
        val status = readSysfsFirstAvailable(STATUS_PATHS)?.trim() ?: return null
        return status.contains("Charging", ignoreCase = true) || status.contains("Full", ignoreCase = true)
    }

    private fun readCapacityViaDumpsys(): Int? {
        val output = executeRootCommand("dumpsys battery") ?: return null
        output.lines().forEach { line ->
            if (line.trim().startsWith("level:")) {
                return line.substringAfter(":").trim().toIntOrNull()
            }
        }
        return null
    }

    private fun readStatusViaDumpsys(): Boolean? {
        val output = executeRootCommand("dumpsys battery") ?: return null
        var statusInt: Int? = null
        output.lines().forEach { line ->
            if (line.trim().startsWith("status:")) {
                statusInt = line.substringAfter(":").trim().toIntOrNull()
            }
        }
        return statusInt?.let { it == 2 || it == 5 } // 2: Charging, 5: Full
    }

    private fun readSysfsFirstAvailable(paths: List<String>): String? {
        for (path in paths) {
            val result = executeRootCommand("cat $path")?.trim()
            if (!isInvalidResult(result)) {
                return result
            }
        }
        return null
    }

    private fun isInvalidResult(str: String?): Boolean {
        return str.isNullOrEmpty() ||
                str.contains("No such file", ignoreCase = true) ||
                str.contains("Permission denied", ignoreCase = true)
    }

    private fun executeRootCommand(command: String): String? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            if (exitCode == 0 || sb.isNotEmpty()) {
                sb.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
