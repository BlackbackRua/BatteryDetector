package com.blackback.batterydetector.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for webhook payload construction and header parsing.
 *
 * The escaping cases matter most: the previous implementation pasted user text
 * into a JSON string literal, so a device name with a quote produced invalid JSON
 * and the receiving server rejected the alert with no useful error on this side.
 *
 * These use the project's own [JsonBuilder] rather than `org.json`, because the
 * Android SDK's org.json is a stub on the JVM and every call would throw.
 */
class WebhookRequestBuilderTest {

    // ------------------------------------------------------------ json escaping

    @Test
    fun `quote escapes the characters that break a json document`() {
        assertEquals("\"plain\"", JsonBuilder.quote("plain"))
        assertEquals("\"a\\\"b\"", JsonBuilder.quote("a\"b"))
        assertEquals("\"a\\\\b\"", JsonBuilder.quote("a\\b"))
        assertEquals("\"a\\nb\"", JsonBuilder.quote("a\nb"))
        assertEquals("\"a\\rb\"", JsonBuilder.quote("a\rb"))
        assertEquals("\"a\\tb\"", JsonBuilder.quote("a\tb"))
    }

    @Test
    fun `quote escapes remaining control characters as unicode`() {
        assertEquals("\"\\u0000\"", JsonBuilder.quote("\u0000"))
        assertEquals("\"\\u0007\"", JsonBuilder.quote("\u0007"))
        assertEquals("\"\\u001f\"", JsonBuilder.quote("\u001F"))
    }

    @Test
    fun `quote leaves non-ascii intact for utf-8 transport`() {
        assertEquals("\"客厅平板\"", JsonBuilder.quote("客厅平板"))
        assertEquals("\"📱\"", JsonBuilder.quote("📱"))
    }

    @Test
    fun `device name containing a quote cannot break the payload`() {
        val body = WebhookRequestBuilder.genericBody(
            title = "alert",
            message = "low",
            deviceName = "My \"Phone\"",
            batteryLevel = 12,
            timestamp = 1L
        )
        // The quote must appear escaped, not raw.
        assertTrue(body.contains("\\\"Phone\\\""))
        assertEquals(1, Regex("(?<!\\\\)\"device\"").findAll(body).count())
    }

    @Test
    fun `newline in device name cannot inject a second field`() {
        val body = WebhookRequestBuilder.genericBody(
            title = "t",
            message = "m",
            deviceName = "evil\",\n\"injected\": \"x",
            batteryLevel = 1,
            timestamp = 0L
        )
        // The injected key must not appear as a real field name.
        assertFalse(body.contains("\"injected\":"))
        assertTrue(body.contains("\\n"))
    }

    // -------------------------------------------------------- payload selection

    @Test
    fun `generic body exposes every key receivers may look for`() {
        val body = WebhookRequestBuilder.genericBody("t", "m", "d", 42, 99L)
        assertTrue(body.contains("\"title\":\"t\""))
        assertTrue(body.contains("\"text\":\"m\""))
        assertTrue(body.contains("\"message\":\"m\""))
        assertTrue(body.contains("\"device\":\"d\""))
        assertTrue(body.contains("\"battery\":42"))
        assertTrue(body.contains("\"timestamp\":99"))
    }

    @Test
    fun `telegram urls get the telegram shape`() {
        val spec = WebhookRequestBuilder.build(
            url = "https://api.telegram.org/bot123/sendMessage",
            method = "POST", rawHeaders = "",
            title = "T", message = "M", deviceName = "D",
            batteryLevel = 3, timestamp = 0L
        )
        val body = spec.body!!
        // Title and message are joined with an escaped newline.
        assertTrue(body.contains("\"text\":\"T\\nM\""))
        assertFalse(body.contains("battery"))
    }

    @Test
    fun `bark urls get the bark shape`() {
        val spec = WebhookRequestBuilder.build(
            url = "https://day.app/KEY/",
            method = "POST", rawHeaders = "",
            title = "T", message = "M", deviceName = "D",
            batteryLevel = 3, timestamp = 0L
        )
        val body = spec.body!!
        assertTrue(body.contains("\"title\":\"T\""))
        assertTrue(body.contains("\"body\":\"M\""))
        assertTrue(body.contains("\"group\":\"BatteryDetector\""))
    }

    @Test
    fun `unknown urls get the generic shape and carry headers`() {
        val spec = WebhookRequestBuilder.build(
            url = "https://self.hosted/notify",
            method = "POST", rawHeaders = "X-Api-Key: k",
            title = "T", message = "M", deviceName = "D",
            batteryLevel = 7, timestamp = 5L
        )
        assertTrue(spec.body!!.contains("\"battery\":7"))
        assertEquals(listOf("X-Api-Key" to "k"), spec.extraHeaders)
    }

    // ------------------------------------------------------------------ methods

    @Test
    fun `normalizeMethod accepts supported verbs case-insensitively`() {
        assertEquals("GET", WebhookRequestBuilder.normalizeMethod("get"))
        assertEquals("PUT", WebhookRequestBuilder.normalizeMethod("  Put "))
        assertEquals("PATCH", WebhookRequestBuilder.normalizeMethod("PATCH"))
        assertEquals("DELETE", WebhookRequestBuilder.normalizeMethod("delete"))
        assertEquals("HEAD", WebhookRequestBuilder.normalizeMethod("head"))
    }

    @Test
    fun `normalizeMethod falls back to POST for anything unsupported`() {
        assertEquals("POST", WebhookRequestBuilder.normalizeMethod(""))
        assertEquals("POST", WebhookRequestBuilder.normalizeMethod("TRACE"))
        assertEquals("POST", WebhookRequestBuilder.normalizeMethod("nonsense"))
    }

    @Test
    fun `only body-carrying methods get a payload and content type`() {
        assertTrue(WebhookRequestBuilder.carriesBody("POST"))
        assertTrue(WebhookRequestBuilder.carriesBody("PUT"))
        assertTrue(WebhookRequestBuilder.carriesBody("PATCH"))
        assertTrue(WebhookRequestBuilder.carriesBody("DELETE"))
        assertFalse(WebhookRequestBuilder.carriesBody("GET"))
        assertFalse(WebhookRequestBuilder.carriesBody("HEAD"))

        assertNull(WebhookRequestBuilder.contentTypeFor("GET"))
        assertTrue(WebhookRequestBuilder.contentTypeFor("POST")!!.startsWith("application/json"))
    }

    @Test
    fun `GET produces no body`() {
        val spec = WebhookRequestBuilder.build(
            url = "https://example.com/hook",
            method = "GET", rawHeaders = "",
            title = "t", message = "m", deviceName = "d",
            batteryLevel = 1, timestamp = 0L
        )
        assertEquals("GET", spec.method)
        assertNull(spec.body)
    }

    // ------------------------------------------------------------------ headers

    @Test
    fun `parseHeaders reads name value pairs and skips noise`() {
        val raw = "Authorization: Bearer abc123\n# a comment\nX-Api-Key:secret\n\nbad-line-no-colon"
        val headers = WebhookRequestBuilder.parseHeaders(raw)
        assertEquals(2, headers.size)
        assertEquals("Authorization" to "Bearer abc123", headers[0])
        assertEquals("X-Api-Key" to "secret", headers[1])
    }

    @Test
    fun `parseHeaders keeps colons inside the value`() {
        val headers = WebhookRequestBuilder.parseHeaders("X-Url: https://a.b/c")
        assertEquals("X-Url" to "https://a.b/c", headers.single())
    }

    @Test
    fun `parseHeaders splits multi-line input into separate headers`() {
        // A CRLF inside the field separates entries; it cannot smuggle a value
        // across two headers, because each line is parsed on its own.
        val headers = WebhookRequestBuilder.parseHeaders("A: 1\r\nB: 2")
        assertEquals(2, headers.size)
        assertEquals("A" to "1", headers[0])
        assertEquals("B" to "2", headers[1])
        assertTrue(headers.none { it.first.contains('\r') || it.second.contains('\r') })
    }

    @Test
    fun `parseHeaders returns nothing for blank input`() {
        assertTrue(WebhookRequestBuilder.parseHeaders("").isEmpty())
        assertTrue(WebhookRequestBuilder.parseHeaders("   \n  \n").isEmpty())
    }
}
