package com.blackback.batterydetector.data

import android.util.Log
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

    /**
     * Tag for the logcat mirror. Filter with `adb logcat -s BatteryDetector`.
     *
     * Short and stable, so it is easy to type; the app id is already unique.
     */
    const val LOGCAT_TAG = "BatteryDetector"

    fun addLog(message: String, isError: Boolean = false, isPushEvent: Boolean = false) {
        val entry = LogEntry(
            message = "[${dateFormat.format(Date())}] $message",
            isError = isError,
            isPushEvent = isPushEvent
        )
        _logs.value = (listOf(entry) + _logs.value).take(100) // Keep last 100 entries

        // Mirrored to logcat, not just the in-app list.
        //
        // The list lives in a StateFlow, so it dies with the process and is
        // invisible to anyone reading `adb logcat`. Without this, a failure such as
        // a dropped broadcast looks like the app simply stopped doing anything: the
        // one line that would have explained it was never written where a developer
        // can see it.
        runCatching {
            if (isError) {
                Log.e(LOGCAT_TAG, message)
            } else {
                Log.i(LOGCAT_TAG, message)
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
