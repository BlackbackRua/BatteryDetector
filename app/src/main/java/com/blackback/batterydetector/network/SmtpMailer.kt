package com.blackback.batterydetector.network

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * SMTP alert delivery.
 *
 * Deliberately dependency-free: the JDK's SSLSocket already provides everything
 * needed for implicit TLS, and this project builds offline with a deliberately
 * small dependency set. Only implicit TLS on 465 is supported - it is what every
 * mainstream provider offers (Gmail, QQ, 163, Outlook), and unlike STARTTLS there
 * is no plaintext phase to fall back into by accident.
 *
 * The protocol steps are split into pure helpers so the parts that are easy to get
 * wrong - message framing, header injection, UTF-8 subject encoding, auth payloads
 * and response parsing - can be tested without a network or a mail server.
 */
object SmtpMailer {

    /** Implicit TLS port used by every provider this app targets. */
    const val DEFAULT_PORT = 465

    data class Config(
        val host: String,
        val port: Int = DEFAULT_PORT,
        val username: String,
        val password: String,
        val from: String,
        val to: String
    )

    /** Outcome of one send attempt, reported back to the log and the UI. */
    data class Result(val success: Boolean, val message: String)

    /**
     * Reads the mail settings and returns a config, or null when the route is not
     * configured.
     *
     * Callers use the null to decide whether to attempt mail at all, which keeps the
     * "is email set up?" question in one place.
     */
    fun configFrom(prefs: com.blackback.batterydetector.data.AppPreferences): Config? {
        if (!isConfigured(prefs.smtpHost, prefs.smtpUsername, prefs.smtpPassword, prefs.smtpTo)) {
            return null
        }
        return Config(
            host = prefs.smtpHost.trim(),
            port = if (prefs.smtpPort > 0) prefs.smtpPort else DEFAULT_PORT,
            username = prefs.smtpUsername.trim(),
            password = prefs.smtpPassword,
            // Providers require the envelope sender to match the authenticated
            // account, so the username is the correct default.
            from = prefs.smtpFrom.ifBlank { prefs.smtpUsername }.trim(),
            to = prefs.smtpTo.trim()
        )
    }

    // ---------------------------------------------------------------- pure logic

    /**
     * True when every field a send needs is present.
     *
     * Kept separate from sending so the settings screen can decide whether the
     * mail route is configured at all without opening a socket.
     */
    fun isConfigured(host: String, username: String, password: String, to: String): Boolean {
        return host.isNotBlank() && username.isNotBlank() &&
            password.isNotBlank() && to.isNotBlank()
    }

    /**
     * Accepts `user@host` or `Name <user@host>` and returns the bare address.
     *
     * Rejects anything containing CR or LF: those characters would let a crafted
     * address inject extra SMTP commands or headers.
     */
    fun extractAddress(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.contains('\r') || value.contains('\n')) return null

        val bracketed = Regex("<([^<>]+)>").find(value)
        val address = (bracketed?.groupValues?.get(1) ?: value).trim()
        if (address.isEmpty()) return null
        if (address.contains('\r') || address.contains('\n')) return null
        if (address.contains(' ')) return null

        // A single @ with a non-empty local part and a dotted domain.
        val at = address.indexOf('@')
        if (at <= 0 || at != address.lastIndexOf('@')) return null
        val domain = address.substring(at + 1)
        if (!domain.contains('.')) return null
        return address
    }

    /**
     * Encodes a header value as a UTF-8 RFC 2047 encoded word when it contains
     * anything outside printable ASCII, so Chinese subjects survive the trip.
     */
    fun encodeHeaderValue(value: String): String {
        val isPlainAscii = value.all { it.code in 32..126 }
        if (isPlainAscii) return value
        val encoded = Base64.getEncoder()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        return "=?UTF-8?B?$encoded?="
    }

    /** Base64 payload for `AUTH PLAIN`: NUL, user, NUL, password. */
    fun authPlainPayload(username: String, password: String): String {
        val raw = "\u0000$username\u0000$password"
        return Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    /** Base64 user name, the first step of `AUTH LOGIN`. */
    fun authLoginUser(username: String): String =
        Base64.getEncoder().encodeToString(username.toByteArray(StandardCharsets.UTF_8))

    /** Base64 password, the second step of `AUTH LOGIN`. */
    fun authLoginPassword(password: String): String =
        Base64.getEncoder().encodeToString(password.toByteArray(StandardCharsets.UTF_8))

    /** Numeric status code of an SMTP reply line, or null when it is malformed. */
    fun parseReplyCode(line: String): Int? {
        if (line.length < 3) return null
        val code = line.substring(0, 3)
        if (!code.all { it.isDigit() }) return null
        return code.toIntOrNull()
    }

    /** True when the code is in the 2xx success class. */
    fun isSuccess(code: Int?): Boolean = code != null && code in 200..299

    /** True for the `250-` continuation form, meaning more lines follow. */
    fun isMultilineReply(line: String): Boolean = line.length > 3 && line[3] == '-'

    /**
     * Builds the RFC 5322 message.
     *
     * Headers are kept free of raw CR/LF from user input by [encodeHeaderValue] and
     * by never interpolating an unsanitised address; the body uses CRLF line
     * endings and dot-stuffing is applied by the caller when sending.
     */
    fun buildMessage(from: String, to: String, subject: String, body: String): String {
        val sb = StringBuilder()
        sb.append("From: ").append(encodeHeaderValue(from)).append("\r\n")
        sb.append("To: ").append(encodeHeaderValue(to)).append("\r\n")
        sb.append("Subject: ").append(encodeHeaderValue(subject)).append("\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(body.replace("\n", "\r\n"))
        return sb.toString()
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends one alert email.
     *
     * Blocking; call from a background dispatcher. Every protocol step is checked,
     * and the first failure aborts with the server's own reply text so the log says
     * what the relay actually objected to.
     */
    fun send(config: Config, subject: String, body: String): Result {
        val from = extractAddress(config.from.ifBlank { config.username })
            ?: return Result(false, "发件地址无效: ${config.from}")
        val to = extractAddress(config.to)
            ?: return Result(false, "收件地址无效: ${config.to}")

        return try {
            SmtpSession(config.host, config.port).use { session ->
                session.expect(220, "连接")

                val ehlo = session.command("EHLO ${clientName()}")
                if (!isSuccess(parseReplyCode(ehlo.first()))) {
                    // Some relays only speak HELO.
                    val helo = session.command("HELO ${clientName()}")
                    if (!isSuccess(parseReplyCode(helo.first()))) {
                        return Result(false, "握手被拒绝: ${helo.first()}")
                    }
                }

                if (config.username.isNotBlank()) {
                    val auth = session.authenticate(config.username, config.password)
                    if (auth != null) return Result(false, auth)
                }

                session.commandExpect("MAIL FROM:<$from>", 250, "MAIL FROM")
                    ?.let { return Result(false, it) }
                session.commandExpect("RCPT TO:<$to>", 250, "RCPT TO")
                    ?.let { return Result(false, it) }
                session.commandExpect("DATA", 354, "DATA")
                    ?.let { return Result(false, it) }

                val payload = dotStuff(buildMessage(from, to, subject, body))
                val dataReply = session.sendData(payload)
                if (!isSuccess(parseReplyCode(dataReply))) {
                    return Result(false, "邮件正文被拒绝: $dataReply")
                }

                session.command("QUIT")
                Result(true, "邮件已发送至 $to")
            }
        } catch (e: Exception) {
            Result(false, "SMTP 发送失败: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Escapes leading dots, as required by RFC 5321 section 4.5.2.
     *
     * A body line that starts with `.` would otherwise be read as the end-of-data
     * marker and truncate the message.
     */
    fun dotStuff(message: String): String {
        return message.split("\r\n").joinToString("\r\n") { line ->
            if (line.startsWith(".")) ".$line" else line
        }
    }

    private fun clientName(): String = "batterydetector.local"
}
