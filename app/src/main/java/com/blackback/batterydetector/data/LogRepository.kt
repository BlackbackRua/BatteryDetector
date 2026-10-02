package com.blackback.batterydetector.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRepository {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addLog(message: String, isError: Boolean = false, isPushEvent: Boolean = false) {
        val entry = LogEntry(
            message = "[${dateFormat.format(Date())}] $message",
            isError = isError,
            isPushEvent = isPushEvent
        )
        _logs.value = (listOf(entry) + _logs.value).take(100) // Keep last 100 entries
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
