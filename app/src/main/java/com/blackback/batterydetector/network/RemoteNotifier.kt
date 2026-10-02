package com.blackback.batterydetector.network

import com.blackback.batterydetector.data.LogRepository
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object RemoteNotifier {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        return if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "http://$trimmed"
        } else {
            trimmed
        }
    }

    fun sendNotification(
        webhookUrl: String,
        deviceName: String,
        batteryLevel: Int,
        isTest: Boolean = false,
        onResult: (Boolean, String) -> Unit
    ) {
        val formattedUrl = normalizeUrl(webhookUrl)
        if (formattedUrl.isEmpty()) {
            val errorMsg = "推送失败: Webhook 地址未配置"
            LogRepository.addLog(errorMsg, isError = true)
            onResult(false, errorMsg)
            return
        }

        val title = if (isTest) "BatteryDetector 测试推送" else "低电量预警"
        val message = if (isTest) {
            "设备 [$deviceName] 当前电量为 $batteryLevel%，网络通知功能正常！"
        } else {
            "警告：设备 [$deviceName] 当前电量仅剩 $batteryLevel%，请及时充电！"
        }

        val request = try {
            buildRequest(formattedUrl, title, message, deviceName, batteryLevel)
        } catch (e: Exception) {
            val errorMsg = "推送失败: URL 格式无效 (${e.localizedMessage})"
            LogRepository.addLog(errorMsg, isError = true, isPushEvent = true)
            onResult(false, errorMsg)
            return
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val error = "推送失败: ${e.localizedMessage}"
                LogRepository.addLog(error, isError = true, isPushEvent = true)
                onResult(false, error)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val successMsg = "推送成功 (${response.code})"
                    LogRepository.addLog(successMsg, isError = false, isPushEvent = true)
                    onResult(true, successMsg)
                } else {
                    val errorMsg = "推送失败 (HTTP ${response.code}): $responseBody"
                    LogRepository.addLog(errorMsg, isError = true, isPushEvent = true)
                    onResult(false, errorMsg)
                }
            }
        })
    }

    private fun buildRequest(
        url: String,
        title: String,
        message: String,
        deviceName: String,
        batteryLevel: Int
    ): Request {
        return when {
            // Telegram Bot API
            url.contains("api.telegram.org/bot", ignoreCase = true) -> {
                val json = """
                    {
                        "text": "$title\n$message"
                    }
                """.trimIndent()
                Request.Builder()
                    .url(url)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }

            // Bark App Push
            url.contains("day.app", ignoreCase = true) -> {
                val json = """
                    {
                        "title": "$title",
                        "body": "$message",
                        "device": "$deviceName",
                        "group": "BatteryDetector"
                    }
                """.trimIndent()
                Request.Builder()
                    .url(url)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }

            // 通用 Webhook / Gotify / ServerChan / 自建服务
            else -> {
                val json = """
                    {
                        "title": "$title",
                        "text": "$message",
                        "message": "$message",
                        "device": "$deviceName",
                        "battery": $batteryLevel,
                        "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()
                Request.Builder()
                    .url(url)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }
        }
    }
}
