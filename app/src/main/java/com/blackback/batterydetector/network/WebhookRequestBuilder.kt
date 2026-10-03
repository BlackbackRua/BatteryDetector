package com.blackback.batterydetector.network

/**
 * Builds the HTTP request shape for a webhook alert.
 *
 * Everything here is pure so the payload construction can be tested directly. The
 * previous implementation interpolated user text straight into a JSON literal,
 * which produced invalid JSON - and therefore a silent delivery failure on the
 * receiving end - as soon as a device name contained a quote or backslash.
 *
 * Payloads are written with [JsonBuilder] rather than `org.json`, which has no
 * working implementation in unit tests.
 */
object WebhookRequestBuilder {

    /** What the caller needs to hand to OkHttp. */
    data class Spec(
        val method: String,
        val body: String?,
        val extraHeaders: List<Pair<String, String>>
    )

    /** HTTP methods this app will send. Anything else falls back to POST. */
    val SUPPORTED_METHODS = listOf("POST", "GET", "PUT", "PATCH", "DELETE", "HEAD")

    private val JSON_TYPE = "application/json; charset=utf-8"

    /** Content type to send, or null for methods that carry no body. */
    fun contentTypeFor(method: String): String? = if (carriesBody(method)) JSON_TYPE else null

    /** True when the method is sent with a JSON body. */
    fun carriesBody(method: String): Boolean =
        method.uppercase() in setOf("POST", "PUT", "PATCH", "DELETE")

    /** Normalises a user-entered method to something OkHttp accepts. */
    fun normalizeMethod(raw: String): String {
        val candidate = raw.trim().uppercase()
        return if (candidate in SUPPORTED_METHODS) candidate else "POST"
    }

    /**
     * Parses the header field, one `Name: Value` per line.
     *
     * Blank lines and lines starting with `#` are ignored, so a user can comment a
     * header out without deleting it. Headers without a colon are skipped rather
     * than sent malformed. CR/LF inside a name or value is rejected, since that
     * would split one header into two.
     */
    fun parseHeaders(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = trimmed.substring(0, colon).trim()
            val value = trimmed.substring(colon + 1).trim()
            if (name.isEmpty()) return@mapNotNull null
            if (name.any { it == '\r' || it == '\n' }) return@mapNotNull null
            if (value.any { it == '\r' || it == '\n' }) return@mapNotNull null
            name to value
        }
    }

    /** Telegram Bot API: a single `text` field. */
    fun telegramBody(title: String, message: String): String {
        return JsonBuilder().put("text", "$title\n$message").build()
    }

    /** Bark: title/body plus a grouping key. */
    fun barkBody(title: String, message: String, deviceName: String): String {
        return JsonBuilder()
            .put("title", title)
            .put("body", message)
            .put("device", deviceName)
            .put("group", "BatteryDetector")
            .build()
    }

    /**
     * Generic shape covering Gotify, ServerChan and self-hosted receivers.
     *
     * `title`/`text`/`message` are all present because different servers read
     * different keys, and the alert is worth duplicating to be understood.
     */
    fun genericBody(
        title: String,
        message: String,
        deviceName: String,
        batteryLevel: Int,
        timestamp: Long
    ): String {
        return JsonBuilder()
            .put("title", title)
            .put("text", message)
            .put("message", message)
            .put("device", deviceName)
            .put("battery", batteryLevel)
            .put("timestamp", timestamp)
            .build()
    }

    /**
     * Chooses the payload for a URL and wraps it with the caller's method and
     * headers.
     *
     * URL sniffing is a heuristic, so it only decides the *body shape*; the method
     * and headers always come from the user's settings.
     */
    fun build(
        url: String,
        method: String,
        rawHeaders: String,
        title: String,
        message: String,
        deviceName: String,
        batteryLevel: Int,
        timestamp: Long
    ): Spec {
        val normalised = normalizeMethod(method)
        val headers = parseHeaders(rawHeaders)

        if (!carriesBody(normalised)) {
            return Spec(normalised, null, headers)
        }

        val body = when {
            url.contains("api.telegram.org/bot", ignoreCase = true) ->
                telegramBody(title, message)

            url.contains("day.app", ignoreCase = true) ->
                barkBody(title, message, deviceName)

            else ->
                genericBody(title, message, deviceName, batteryLevel, timestamp)
        }
        return Spec(normalised, body, headers)
    }
}
