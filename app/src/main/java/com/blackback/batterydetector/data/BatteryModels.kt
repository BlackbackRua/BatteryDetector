package com.blackback.batterydetector.data

data class BatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val source: String, // "Root" or "Standard API"
    val voltage: String = "N/A",
    val temperature: String = "N/A",
    val health: String = "N/A",
    val timestamp: Long = System.currentTimeMillis()
)

data class LogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false,
    val isPushEvent: Boolean = false
)
