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
        method: String = "POST",
        headers: String = "",
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
            buildRequest(formattedUrl, method, headers, title, message, deviceName, batteryLevel)
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

    /**
     * Assembles the OkHttp request from a [WebhookRequestBuilder.Spec].
     *
     * The JSON is produced by JSONObject rather than string interpolation so that a
     * device name containing a quote, backslash or newline cannot corrupt the
     * payload - the old code did exactly that and the receiver saw a parse error.
     */
    private fun buildRequest(
        url: String,
        method: String,
        headers: String,
        title: String,
        message: String,
        deviceName: String,
        batteryLevel: Int
    ): Request {
        val spec = WebhookRequestBuilder.build(
            url = url,
            method = method,
            rawHeaders = headers,
            title = title,
            message = message,
            deviceName = deviceName,
            batteryLevel = batteryLevel,
            timestamp = System.currentTimeMillis()
        )

        val mediaType = WebhookRequestBuilder.contentTypeFor(spec.method)?.toMediaType()
        val body = spec.body?.toRequestBody(mediaType)

        val builder = Request.Builder().url(url)
        spec.extraHeaders.forEach { (name, value) -> builder.addHeader(name, value) }

        when (spec.method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> {
                if (body != null) builder.delete(body) else builder.delete()
            }
            "PUT" -> builder.put(body ?: ByteArray(0).toRequestBody(null))
            "PATCH" -> builder.patch(body ?: ByteArray(0).toRequestBody(null))
            else -> builder.post(body ?: ByteArray(0).toRequestBody(null))
        }
        return builder.build()
    }
}
