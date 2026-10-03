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
            service = WebhookRequestBuilder.SERVICE_TELEGRAM,
            method = "POST", rawHeaders = "", customBody = "",
            barkSound = "", barkLevel = "",
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
            service = WebhookRequestBuilder.SERVICE_BARK,
            method = "POST", rawHeaders = "", customBody = "",
            barkSound = "", barkLevel = "",
            title = "T", message = "M", deviceName = "D",
            batteryLevel = 3, timestamp = 0L
        )
        val body = spec.body!!
        assertTrue(body.contains("\"title\":\"T\""))
        assertTrue(body.contains("\"body\":\"M\""))
        assertTrue(body.contains("\"group\":\"BatteryDetector\""))
        // `device` is not a Bark parameter and must not be sent.
        assertFalse(body.contains("device"))
    }

    @Test
    fun `bark optional sound and level are only sent when set`() {
        val bare = WebhookRequestBuilder.barkBody("T", "M", "", "")
        assertFalse(bare.contains("sound"))
        assertFalse(bare.contains("level"))

        val full = WebhookRequestBuilder.barkBody("T", "M", "alarm", "critical")
        assertTrue(full.contains("\"sound\":\"alarm\""))
        assertTrue(full.contains("\"level\":\"critical\""))
    }

    @Test
    fun `a self-hosted bark server still gets the bark payload`() {
        // The host contains no day.app, which is exactly why URL sniffing failed.
        val spec = WebhookRequestBuilder.build(
            url = "https://bark.myhost.internal/KEY/",
            service = WebhookRequestBuilder.SERVICE_BARK,
            method = "POST", rawHeaders = "", customBody = "",
            barkSound = "", barkLevel = "",
            title = "T", message = "M", deviceName = "D",
            batteryLevel = 3, timestamp = 0L
        )
        assertTrue(spec.body!!.contains("\"body\":\"M\""))
    }

    @Test
    fun `unknown urls get the generic shape and carry headers`() {
        val spec = WebhookRequestBuilder.build(
            url = "https://self.hosted/notify",
            service = WebhookRequestBuilder.SERVICE_AUTO,
            method = "POST", rawHeaders = "X-Api-Key: k", customBody = "",
            barkSound = "", barkLevel = "",
            title = "T", message = "M", deviceName = "D",
            batteryLevel = 7, timestamp = 5L
        )
        assertTrue(spec.body!!.contains("\"battery\":7"))
        assertEquals(listOf("X-Api-Key" to "k"), spec.extraHeaders)
    }

    // ----------------------------------------------------------- service choice

    @Test
    fun `resolveService honours an explicit choice over the url`() {
        // A telegram-looking URL must still obey an explicit BARK selection.
        assertEquals(
            WebhookRequestBuilder.SERVICE_BARK,
            WebhookRequestBuilder.resolveService(
                WebhookRequestBuilder.SERVICE_BARK,
                "https://api.telegram.org/bot1/sendMessage"
            )
        )
    }

    @Test
    fun `resolveService auto-detects known hosts as a fallback`() {
        assertEquals(
            WebhookRequestBuilder.SERVICE_TELEGRAM,
            WebhookRequestBuilder.resolveService(
                WebhookRequestBuilder.SERVICE_AUTO,
                "https://api.telegram.org/bot123/sendMessage"
            )
        )
        assertEquals(
            WebhookRequestBuilder.SERVICE_BARK,
            WebhookRequestBuilder.resolveService(
                WebhookRequestBuilder.SERVICE_AUTO,
                "https://day.app/KEY/"
            )
        )
        assertEquals(
            WebhookRequestBuilder.SERVICE_AUTO,
            WebhookRequestBuilder.resolveService(
                WebhookRequestBuilder.SERVICE_AUTO,
                "https://self.hosted/notify"
            )
        )
    }

    // ------------------------------------------------------ custom body template

    @Test
    fun `renderTemplate substitutes every token`() {
        val out = WebhookRequestBuilder.renderTemplate(
            template = """{"t":"{{title}}","m":"{{message}}","d":"{{device}}","b":{{battery}},"ts":{{timestamp}}}""",
            title = "标题", message = "正文", deviceName = "设备",
            batteryLevel = 12, timestamp = 99L
        )
        assertTrue(out.contains("\"t\":\"标题\""))
        assertTrue(out.contains("\"m\":\"正文\""))
        assertTrue(out.contains("\"d\":\"设备\""))
        // Numbers stay unquoted so they remain numeric.
        assertTrue(out.contains("\"b\":12"))
        assertTrue(out.contains("\"ts\":99"))
        assertTrue(WebhookRequestBuilder.isValidJson(out))
    }

    @Test
    fun `renderTemplate escapes quotes so the document stays valid`() {
        val out = WebhookRequestBuilder.renderTemplate(
            template = """{"device":"{{device}}"}""",
            title = "", message = "", deviceName = "He said \"hi\"",
            batteryLevel = 1, timestamp = 0L
        )
        assertTrue(WebhookRequestBuilder.isValidJson(out))
        assertTrue(out.contains("\\\"hi\\\""))
    }

    @Test
    fun `renderTemplate keeps a newline from breaking the document`() {
        val out = WebhookRequestBuilder.renderTemplate(
            template = """{"m":"{{message}}"}""",
            title = "", message = "a\nb", deviceName = "",
            batteryLevel = 1, timestamp = 0L
        )
        assertTrue(WebhookRequestBuilder.isValidJson(out))
        assertTrue(out.contains("\\n"))
    }

    @Test
    fun `renderTemplate leaves unknown placeholders untouched`() {
        val out = WebhookRequestBuilder.renderTemplate(
            template = """{"x":"{{unknown}}"}""",
            title = "t", message = "m", deviceName = "d",
            batteryLevel = 1, timestamp = 0L
        )
        assertTrue(out.contains("{{unknown}}"))
    }

    // --------------------------------------------------------- json validation

    @Test
    fun `isValidJson accepts well formed documents`() {
        assertTrue(WebhookRequestBuilder.isValidJson("""{"a":1}"""))
        assertTrue(WebhookRequestBuilder.isValidJson("""{"a":"b","c":true,"d":null}"""))
        assertTrue(WebhookRequestBuilder.isValidJson("""[1,2,3]"""))
        assertTrue(WebhookRequestBuilder.isValidJson("""{"nested":{"x":[1,{"y":"z"}]}}"""))
        assertTrue(WebhookRequestBuilder.isValidJson("\"just a string\""))
    }

    @Test
    fun `isValidJson rejects malformed documents`() {
        assertFalse(WebhookRequestBuilder.isValidJson(""))
        assertFalse(WebhookRequestBuilder.isValidJson("   "))
        assertFalse(WebhookRequestBuilder.isValidJson("""{"a":}"""))
        assertFalse(WebhookRequestBuilder.isValidJson("""{"a":1"""))
        assertFalse(WebhookRequestBuilder.isValidJson("""{"a" 1}"""))
        assertFalse(WebhookRequestBuilder.isValidJson("""{"a":"unterminated}"""))
        assertFalse(WebhookRequestBuilder.isValidJson("""{"a":1} trailing"""))
        assertFalse(WebhookRequestBuilder.isValidJson("not json at all"))
    }

    @Test
    fun `isValidJson handles escaped quotes inside strings`() {
        assertTrue(WebhookRequestBuilder.isValidJson("""{"a":"say \"hi\""}"""))
        assertTrue(WebhookRequestBuilder.isValidJson("""{"a":"back\\slash"}"""))
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
            service = WebhookRequestBuilder.SERVICE_AUTO,
            method = "GET", rawHeaders = "", customBody = "",
            barkSound = "", barkLevel = "",
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
