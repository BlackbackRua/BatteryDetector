package com.blackback.batterydetector.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Tests for the parts of SMTP that do not need a socket or a mail server.
 *
 * These are the steps that silently corrupt a message rather than fail loudly -
 * header injection, wrong base64 payloads, misread multi-line replies - so they are
 * worth pinning down precisely.
 */
class SmtpMailerTest {

    // ------------------------------------------------------------ configuration

    @Test
    fun `isConfigured needs every field a send uses`() {
        assertTrue(SmtpMailer.isConfigured("smtp.qq.com", "a@qq.com", "pw", "b@x.com"))
        assertFalse(SmtpMailer.isConfigured("", "a@qq.com", "pw", "b@x.com"))
        assertFalse(SmtpMailer.isConfigured("smtp.qq.com", "", "pw", "b@x.com"))
        assertFalse(SmtpMailer.isConfigured("smtp.qq.com", "a@qq.com", "", "b@x.com"))
        assertFalse(SmtpMailer.isConfigured("smtp.qq.com", "a@qq.com", "pw", ""))
        // Whitespace-only must count as missing, not as a value.
        assertFalse(SmtpMailer.isConfigured("   ", "a@qq.com", "pw", "b@x.com"))
    }

    // ---------------------------------------------------------------- addresses

    @Test
    fun `extractAddress handles bare and display-name forms`() {
        assertEquals("a@qq.com", SmtpMailer.extractAddress("a@qq.com"))
        assertEquals("a@qq.com", SmtpMailer.extractAddress("  a@qq.com  "))
        assertEquals("a@qq.com", SmtpMailer.extractAddress("Battery <a@qq.com>"))
        assertEquals("a@qq.com", SmtpMailer.extractAddress("电池预警 <a@qq.com>"))
    }

    @Test
    fun `extractAddress rejects input that could inject commands`() {
        // CRLF in an address is the classic SMTP header/command injection vector.
        assertNull(SmtpMailer.extractAddress("a@qq.com\r\nRCPT TO:<attacker@evil.com>"))
        assertNull(SmtpMailer.extractAddress("a@qq.com\nBcc: attacker@evil.com"))
        assertNull(SmtpMailer.extractAddress("Battery <a@qq.com\r\n>"))
    }

    @Test
    fun `extractAddress rejects malformed addresses`() {
        assertNull(SmtpMailer.extractAddress(""))
        assertNull(SmtpMailer.extractAddress("   "))
        assertNull(SmtpMailer.extractAddress("no-at-sign"))
        assertNull(SmtpMailer.extractAddress("@nolocal.com"))
        assertNull(SmtpMailer.extractAddress("two@at@signs.com"))
        assertNull(SmtpMailer.extractAddress("nolocal@nodot"))
        assertNull(SmtpMailer.extractAddress("has space@qq.com"))
    }

    // ------------------------------------------------------------------ headers

    @Test
    fun `encodeHeaderValue leaves plain ascii alone`() {
        assertEquals("Low battery", SmtpMailer.encodeHeaderValue("Low battery"))
        assertEquals("Battery 12%", SmtpMailer.encodeHeaderValue("Battery 12%"))
    }

    @Test
    fun `encodeHeaderValue wraps non-ascii as an rfc2047 encoded word`() {
        val encoded = SmtpMailer.encodeHeaderValue("低电量预警")
        assertTrue("expected encoded word, got $encoded", encoded.startsWith("=?UTF-8?B?"))
        assertTrue(encoded.endsWith("?="))

        // It must decode back to exactly the original text.
        val payload = encoded.removePrefix("=?UTF-8?B?").removeSuffix("?=")
        val decoded = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        assertEquals("低电量预警", decoded)
    }

    @Test
    fun `buildMessage emits the required headers and crlf framing`() {
        val msg = SmtpMailer.buildMessage("a@qq.com", "b@x.com", "低电量", "设备仅剩 12%")
        assertTrue(msg.contains("From: a@qq.com\r\n"))
        assertTrue(msg.contains("To: b@x.com\r\n"))
        assertTrue(msg.contains("MIME-Version: 1.0\r\n"))
        assertTrue(msg.contains("Content-Type: text/plain; charset=UTF-8\r\n"))
        // Header block must be separated from the body by a blank line.
        assertTrue(msg.contains("\r\n\r\n"))
        // Subject must be encoded, since it is not ASCII.
        assertTrue(msg.contains("Subject: =?UTF-8?B?"))
    }

    @Test
    fun `buildMessage normalises body line endings to crlf`() {
        val msg = SmtpMailer.buildMessage("a@qq.com", "b@x.com", "t", "line1\nline2")
        assertTrue(msg.endsWith("line1\r\nline2"))
        assertFalse(msg.endsWith("line1\nline2"))
    }

    // --------------------------------------------------------------------- auth

    @Test
    fun `authPlainPayload is nul separated and base64`() {
        val payload = SmtpMailer.authPlainPayload("user@qq.com", "secret")
        val decoded = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        assertEquals("\u0000user@qq.com\u0000secret", decoded)
    }

    @Test
    fun `authLogin steps are the two halves base64 encoded`() {
        assertEquals(
            "dXNlckBxcS5jb20=",
            SmtpMailer.authLoginUser("user@qq.com")
        )
        assertEquals(
            "c2VjcmV0",
            SmtpMailer.authLoginPassword("secret")
        )
    }

    @Test
    fun `auth encoding round trips non-ascii credentials`() {
        val user = "用户@qq.com"
        val decoded = String(Base64.getDecoder().decode(SmtpMailer.authLoginUser(user)), Charsets.UTF_8)
        assertEquals(user, decoded)
    }

    // ------------------------------------------------------------------- replies

    @Test
    fun `parseReplyCode reads the numeric status`() {
        assertEquals(220, SmtpMailer.parseReplyCode("220 smtp.qq.com ESMTP"))
        assertEquals(250, SmtpMailer.parseReplyCode("250 OK"))
        assertEquals(535, SmtpMailer.parseReplyCode("535 Authentication failed"))
    }

    @Test
    fun `parseReplyCode rejects malformed lines`() {
        assertNull(SmtpMailer.parseReplyCode(""))
        assertNull(SmtpMailer.parseReplyCode("OK"))
        assertNull(SmtpMailer.parseReplyCode("2a0 broken"))
        assertNull(SmtpMailer.parseReplyCode("22"))
    }

    @Test
    fun `isSuccess accepts only the 2xx class`() {
        assertTrue(SmtpMailer.isSuccess(220))
        assertTrue(SmtpMailer.isSuccess(250))
        assertTrue(SmtpMailer.isSuccess(299))
        assertFalse(SmtpMailer.isSuccess(354))
        assertFalse(SmtpMailer.isSuccess(535))
        assertFalse(SmtpMailer.isSuccess(null))
    }

    @Test
    fun `isMultilineReply detects the continuation form`() {
        // `250-` means more lines follow; `250 ` is the final line.
        assertTrue(SmtpMailer.isMultilineReply("250-smtp.qq.com"))
        assertTrue(SmtpMailer.isMultilineReply("250-AUTH LOGIN PLAIN"))
        assertFalse(SmtpMailer.isMultilineReply("250 OK"))
        assertFalse(SmtpMailer.isMultilineReply("250"))
    }

    // -------------------------------------------------------------- dot stuffing

    @Test
    fun `dotStuff escapes leading dots that would end the message early`() {
        val body = "normal\r\n.hidden\r\n..already"
        val stuffed = SmtpMailer.dotStuff(body)
        assertEquals("normal\r\n..hidden\r\n...already", stuffed)
    }

    @Test
    fun `dotStuff leaves ordinary lines untouched`() {
        val body = "line one\r\nline two"
        assertEquals(body, SmtpMailer.dotStuff(body))
    }
}
