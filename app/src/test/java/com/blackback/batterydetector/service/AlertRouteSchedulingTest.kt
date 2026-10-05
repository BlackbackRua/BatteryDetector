package com.blackback.batterydetector.service

import com.blackback.batterydetector.data.AppPreferences
import com.blackback.batterydetector.service.BatteryMonitorService.Companion.dueRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for per-channel alert scheduling.
 *
 * Each push channel repeats on its own interval, so the interesting cases are the
 * asymmetric ones: one channel has just fired while another is still waiting, or two
 * channels with different intervals come due at different moments. A shared timer
 * would pass a single-channel test and still hold a quiet channel back, which is the
 * bug these guard against.
 */
class AlertRouteSchedulingTest {

    private val minute = 60_000L
    private val lan = AppPreferences.PUSH_LAN
    private val webhook = AppPreferences.PUSH_WEBHOOK
    private val email = AppPreferences.PUSH_EMAIL

    @Test
    fun `every route fires the first time`() {
        val due = dueRoutes(
            routes = listOf(lan, webhook, email),
            lastAlertAtByRoute = emptyMap(),
            repeatMinutesByRoute = mapOf(lan to 5, webhook to 30, email to 0),
            now = 1_000L
        )

        assertEquals(listOf(lan, webhook, email), due)
    }

    @Test
    fun `a route that already fired does not hold back a route that has not`() {
        // LAN fired a moment ago and must wait; the others have never fired. With a
        // single shared clock the LAN send would have suppressed all three.
        val due = dueRoutes(
            routes = listOf(lan, webhook, email),
            lastAlertAtByRoute = mapOf(lan to 1_000L),
            repeatMinutesByRoute = mapOf(lan to 30, webhook to 5, email to 0),
            now = 2_000L
        )

        assertEquals(listOf(webhook, email), due)
    }

    @Test
    fun `routes with different intervals come due at different moments`() {
        val sentAt = 1_000L
        val last = mapOf(lan to sentAt, webhook to sentAt, email to sentAt)
        val intervals = mapOf(lan to 5, webhook to 30, email to 0)

        // 6 minutes on: only the 5-minute channel is due.
        assertEquals(
            listOf(lan),
            dueRoutes(listOf(lan, webhook, email), last, intervals, now = sentAt + 6 * minute)
        )

        // 31 minutes on: both repeating channels are due; "once only" still is not.
        assertEquals(
            listOf(lan, webhook),
            dueRoutes(listOf(lan, webhook, email), last, intervals, now = sentAt + 31 * minute)
        )
    }

    @Test
    fun `a once-only route never repeats until the battery recovers`() {
        val sentAt = 1_000L

        // Recovery is what clears the timestamp, so however long passes with the
        // battery still low, the route stays silent.
        assertTrue(
            dueRoutes(
                routes = listOf(email),
                lastAlertAtByRoute = mapOf(email to sentAt),
                repeatMinutesByRoute = mapOf(email to 0),
                now = sentAt + 100_000 * minute
            ).isEmpty()
        )
    }

    @Test
    fun `a route with no stored interval falls back to once-only`() {
        // Missing entries must not accidentally mean "always due", which would turn
        // an unconfigured channel into a flood.
        assertTrue(
            dueRoutes(
                routes = listOf(webhook),
                lastAlertAtByRoute = mapOf(webhook to 1_000L),
                repeatMinutesByRoute = emptyMap(),
                now = 10_000_000L
            ).isEmpty()
        )
    }

    @Test
    fun `an empty route set yields nothing to send`() {
        assertTrue(
            dueRoutes(emptyList(), emptyMap(), emptyMap(), now = 1_000L).isEmpty()
        )
    }

    @Test
    fun `routes are returned in the order they were given`() {
        // Order is the caller's preference (settings order), not insertion order of
        // the maps, so the log stays predictable.
        val due = dueRoutes(
            routes = listOf(email, lan, webhook),
            lastAlertAtByRoute = emptyMap(),
            repeatMinutesByRoute = emptyMap(),
            now = 1_000L
        )

        assertEquals(listOf(email, lan, webhook), due)
    }
}
