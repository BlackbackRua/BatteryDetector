package com.blackback.batterydetector.network

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A single SMTP conversation over implicit TLS.
 *
 * Closeable so a failed send cannot leak the socket; every send uses one instance
 * and one connection, which is enough for an alert that fires at most a few times
 * an hour and keeps the state machine trivial.
 *
 * All reads go through [readReply], which understands the multi-line `250-...`
 * form - treating only the first line as the reply is a classic way to misread a
 * server and then hang waiting for more output.
 */
internal class SmtpSession(host: String, port: Int) : Closeable {

    private val socket: SSLSocket
    private val reader: BufferedReader
    private val writer: OutputStream

    init {
        val plain = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        plain.soTimeout = READ_TIMEOUT_MS
        // No SNI override: the default factory already sends the host name, which
        // matters for shared mail infrastructure.
        plain.startHandshake()
        socket = plain
        reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII))
        writer = socket.outputStream
    }

    /** Reads one complete reply, including all continuation lines. */
    fun readReply(): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: break
            lines += line
            if (!SmtpMailer.isMultilineReply(line)) break
        }
        return lines
    }

    /** Sends a command and returns the full reply. */
    fun command(command: String): List<String> {
        writer.write((command + "\r\n").toByteArray(StandardCharsets.UTF_8))
        writer.flush()
        return readReply()
    }

    /**
     * Sends a command, requires an exact code, and returns null on success or the
     * server's reply text on failure.
     */
    fun commandExpect(command: String, expected: Int, label: String): String? {
        val reply = command(command)
        val code = SmtpMailer.parseReplyCode(reply.firstOrNull() ?: "")
        if (code == expected) return null
        return "$label 被拒绝: ${reply.joinToString(" ")}"
    }

    /** Asserts the greeting arrives with the code the protocol requires. */
    fun expect(code: Int, label: String) {
        val reply = readReply()
        val actual = SmtpMailer.parseReplyCode(reply.firstOrNull() ?: "")
        if (actual != code) {
            throw IllegalStateException("$label 失败: ${reply.joinToString(" ")}")
        }
    }

    /**
     * Sends `DATA` payload terminated by the end-of-data marker.
     *
     * The terminating sequence is written on its own line after a CRLF so that a
     * body not ending in a newline still produces a valid message.
     */
    fun sendData(payload: String): String {
        writer.write(payload.toByteArray(StandardCharsets.UTF_8))
        writer.write("\r\n.\r\n".toByteArray(StandardCharsets.US_ASCII))
        writer.flush()
        return readReply().firstOrNull() ?: ""
    }

    /**
     * Authenticates with AUTH LOGIN, falling back to AUTH PLAIN.
     *
     * Returns null on success, or a human-readable reason on failure.
     */
    fun authenticate(username: String, password: String): String? {
        val loginUser = SmtpMailer.authLoginUser(username)
        val loginPass = SmtpMailer.authLoginPassword(password)

        // AUTH LOGIN: 334 challenge, then user, then password.
        val start = command("AUTH LOGIN")
        if (SmtpMailer.parseReplyCode(start.firstOrNull() ?: "") == 334) {
            val userReply = command(loginUser)
            if (SmtpMailer.parseReplyCode(userReply.firstOrNull() ?: "") == 334) {
                val passReply = command(loginPass)
                val code = SmtpMailer.parseReplyCode(passReply.firstOrNull() ?: "")
                if (SmtpMailer.isSuccess(code)) return null
                return "认证失败: ${passReply.joinToString(" ")}"
            }
            return "认证失败(用户名步骤): ${userReply.joinToString(" ")}"
        }

        // Fall back to the single-shot form.
        val plain = command("AUTH PLAIN ${SmtpMailer.authPlainPayload(username, password)}")
        val plainCode = SmtpMailer.parseReplyCode(plain.firstOrNull() ?: "")
        if (SmtpMailer.isSuccess(plainCode)) return null
        return "认证失败: ${plain.joinToString(" ")}"
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
