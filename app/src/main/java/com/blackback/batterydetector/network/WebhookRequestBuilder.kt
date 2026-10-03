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

    /** Telegram Bot API: `text` carries title and body separated by a newline. */
    fun telegramBody(title: String, message: String): String {
        return JsonBuilder().put("text", "$title\n$message").build()
    }

    /**
     * Bark payload.
     *
     * Field names follow Bark's documented POST parameters: `title`, `body`, plus
     * optional `subtitle` / `group` / `sound` / `level`. The previous version also
     * sent `device`, which is not a Bark parameter at all - Bark ignored it, so the
     * value never reached the notification.
     *
     * `subtitle` is deliberately not set: the alert text already names the device,
     * so filling it would show the same name twice.
     *
     * Works against both the official server and a self-hosted `bark-server`,
     * because the service type is chosen explicitly rather than sniffed from the URL.
     */
    fun barkBody(
        title: String,
        message: String,
        sound: String,
        level: String
    ): String {
        val builder = JsonBuilder()
            .put("title", title)
            .put("body", message)
            .put("group", "BatteryDetector")
        if (sound.isNotBlank()) builder.put("sound", sound.trim())
        if (level.isNotBlank()) builder.put("level", level.trim())
        return builder.build()
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

    // ------------------------------------------------------- custom body template

    /** Placeholders a custom template may use. */
    val TEMPLATE_TOKENS = listOf(
        "{{title}}", "{{message}}", "{{device}}", "{{battery}}", "{{timestamp}}"
    )

    /**
     * Substitutes template placeholders with escaped values.
     *
     * String tokens are inserted *without* surrounding quotes, because the template
     * already provides them (`"{{title}}"`). Quotes would be doubled otherwise, which
     * was a real bug caught by the template tests. Numbers are inserted bare so they
     * stay numeric.
     *
     * Escaping happens per token, so a device name or message containing a quote or
     * newline cannot break the surrounding document. The result is checked by
     * [isValidJson] before it is ever sent.
     */
    fun renderTemplate(
        template: String,
        title: String,
        message: String,
        deviceName: String,
        batteryLevel: Int,
        timestamp: Long
    ): String {
        var out = template
        out = out.replace("{{title}}", JsonBuilder.escapeContent(title))
        out = out.replace("{{message}}", JsonBuilder.escapeContent(message))
        out = out.replace("{{device}}", JsonBuilder.escapeContent(deviceName))
        // Numbers and timestamps are unquoted so they stay numeric in the document.
        out = out.replace("{{battery}}", batteryLevel.toString())
        out = out.replace("{{timestamp}}", timestamp.toString())
        return out
    }

    /**
     * Structural check of a rendered template.
     *
     * A hand-written scanner rather than a parser library: `org.json` has no working
     * implementation in unit tests, and the only question being asked is whether the
     * document is balanced and correctly quoted, so the sender can refuse to post
     * malformed JSON instead of failing silently on the receiving end.
     */
    fun isValidJson(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        val scanner = JsonScanner(trimmed)
        return scanner.scanValue(0)?.let { end ->
            trimmed.substring(end).isBlank()
        } ?: false
    }

    /**
     * Picks the payload shape for the chosen service.
     *
     * The service is an explicit setting. Sniffing it from the URL could not work
     * for a self-hosted Bark server (no `day.app` in the host) or for a custom
     * endpoint that happens to contain a matching substring.
     */
    fun build(
        url: String,
        service: String,
        method: String,
        rawHeaders: String,
        customBody: String,
        barkSound: String,
        barkLevel: String,
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

        val body = when (service) {
            SERVICE_TELEGRAM -> telegramBody(title, message)

            SERVICE_BARK -> barkBody(title, message, barkSound, barkLevel)

            SERVICE_CUSTOM ->
                renderTemplate(customBody, title, message, deviceName, batteryLevel, timestamp)

            else ->
                genericBody(title, message, deviceName, batteryLevel, timestamp)
        }
        return Spec(normalised, body, headers)
    }

    /** Service identifiers, also used as the stored preference values. */
    const val SERVICE_AUTO = "AUTO"
    const val SERVICE_BARK = "BARK"
    const val SERVICE_TELEGRAM = "TELEGRAM"
    const val SERVICE_CUSTOM = "CUSTOM"

    /**
     * Resolves what to send for a service setting.
     *
     * [SERVICE_AUTO] keeps the old substring heuristic as a convenience for users
     * who never touch the setting, but it is only a fallback now.
     */
    fun resolveService(service: String, url: String): String {
        if (service != SERVICE_AUTO) return service
        return when {
            url.contains("api.telegram.org/bot", ignoreCase = true) -> SERVICE_TELEGRAM
            url.contains("day.app", ignoreCase = true) -> SERVICE_BARK
            else -> SERVICE_AUTO
        }
    }
}

/**
 * Minimal JSON structure scanner used to validate a rendered template.
 *
 * Tracks whether the document is balanced and whether strings are properly closed;
 * it does not build a tree, because only validity is needed.
 */
internal class JsonScanner(private val text: String) {

    /** Returns the index just past the value starting at [start], or null. */
    fun scanValue(start: Int): Int? {
        var i = skipWhitespace(start)
        if (i >= text.length) return null
        return when (text[i]) {
            '{' -> scanObject(i)
            '[' -> scanArray(i)
            '"' -> scanString(i)
            't' -> if (text.startsWith("true", i)) i + 4 else null
            'f' -> if (text.startsWith("false", i)) i + 5 else null
            'n' -> if (text.startsWith("null", i)) i + 4 else null
            else -> scanNumber(i)
        }
    }

    private fun scanObject(start: Int): Int? {
        var i = skipWhitespace(start + 1)
        if (i < text.length && text[i] == '}') return i + 1
        while (true) {
            i = skipWhitespace(i)
            if (i >= text.length || text[i] != '"') return null
            i = scanString(i) ?: return null
            i = skipWhitespace(i)
            if (i >= text.length || text[i] != ':') return null
            i = scanValue(i + 1) ?: return null
            i = skipWhitespace(i)
            if (i >= text.length) return null
            when (text[i]) {
                ',' -> i += 1
                '}' -> return i + 1
                else -> return null
            }
        }
    }

    private fun scanArray(start: Int): Int? {
        var i = skipWhitespace(start + 1)
        if (i < text.length && text[i] == ']') return i + 1
        while (true) {
            i = scanValue(i) ?: return null
            i = skipWhitespace(i)
            if (i >= text.length) return null
            when (text[i]) {
                ',' -> i += 1
                ']' -> return i + 1
                else -> return null
            }
        }
    }

    /** Consumes a string literal, honouring backslash escapes. */
    private fun scanString(start: Int): Int? {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i += 1
            }
        }
        return null
    }

    private fun scanNumber(start: Int): Int? {
        var i = start
        if (i < text.length && (text[i] == '-' || text[i] == '+')) i += 1
        val digitsStart = i
        while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' ||
                    text[i] == 'E' || text[i] == '-' || text[i] == '+')
        ) {
            i += 1
        }
        return if (i > digitsStart) i else null
    }

    private fun skipWhitespace(from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i += 1
        return i
    }
}
