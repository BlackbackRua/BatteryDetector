package com.blackback.batterydetector.service

import com.blackback.batterydetector.service.BatteryMonitorService.Companion.alertSuppressionReason
import com.blackback.batterydetector.service.BatteryMonitorService.Companion.isAlertDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the low-battery notification rule.
 *
 * Split out from the service because this is the part most likely to be got subtly
 * wrong: a mistake either silences the warning entirely while the battery drains, or
 * turns it into a stream of duplicates. Both are worse than they look, because the
 * alert is what the user acts on.
 */
class LowBatteryAlertRuleTest {

    private val minute = 60_000L

    // ------------------------------------------------------------- alert once

    @Test
    fun `first alert is always due`() {
        // Zero means nothing has been sent since the battery last recovered.
        assertTrue(isAlertDue(lastAlertAt = 0L, now = 1_000L, repeatMinutes = 0))
        assertTrue(isAlertDue(lastAlertAt = 0L, now = 1_000L, repeatMinutes = 30))
    }

    @Test
    fun `alert once never repeats while the battery stays low`() {
        val sentAt = 1_000_000L

        assertFalse(isAlertDue(sentAt, now = sentAt + minute, repeatMinutes = 0))
        assertFalse(isAlertDue(sentAt, now = sentAt + 1000 * minute, repeatMinutes = 0))
    }

    // ------------------------------------------------------------ repeat mode

    @Test
    fun `repeat waits for the full interval`() {
        val sentAt = 1_000_000L

        assertFalse("too early", isAlertDue(sentAt, sentAt + 9 * minute, repeatMinutes = 10))
        assertTrue("exactly due", isAlertDue(sentAt, sentAt + 10 * minute, repeatMinutes = 10))
        assertTrue("well past due", isAlertDue(sentAt, sentAt + 11 * minute, repeatMinutes = 10))
    }

    @Test
    fun `a negative interval behaves as alert once`() {
        // Defensive: a corrupt preference value must not become "alert constantly".
        assertTrue(isAlertDue(0L, now = 5L, repeatMinutes = -5))
        assertFalse(isAlertDue(1_000L, now = 10_000_000L, repeatMinutes = -5))
    }

    // ------------------------------------------------------- log explanations

    @Test
    fun `suppression reason explains alert once`() {
        assertEquals(
            "已设置为只提醒一次",
            alertSuppressionReason(lastAlertAt = 1_000L, now = 2_000L, repeatMinutes = 0)
        )
    }

    @Test
    fun `suppression reason counts down the remaining minutes`() {
        val sentAt = 0L
        // 3 minutes elapsed of a 30 minute interval, so 27 remain.
        assertEquals(
            "距离下次提醒还需 27 分钟",
            alertSuppressionReason(sentAt, now = 3 * minute, repeatMinutes = 30)
        )
    }

    @Test
    fun `suppression reason never reports zero or negative minutes`() {
        val sentAt = 0L
        // The interval appears to have elapsed, which can happen between the check
        // and the log line. Reporting "0 分钟" or a negative would read as nonsense.
        assertEquals(
            "距离下次提醒还需 1 分钟",
            alertSuppressionReason(sentAt, now = 99 * minute, repeatMinutes = 30)
        )
    }
}
