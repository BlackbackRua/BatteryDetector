package com.blackback.batterydetector.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the duplicate-alert window on the receiving side.
 *
 * The subnet-broadcast tests that used to live here went with the broadcast path
 * itself: alerts are now delivered by unicast to discovered peers, so there is no
 * broadcast address left to derive. The subnet arithmetic that replaced it is
 * covered by [PeerDiscoveryTest].
 */
class LanSyncEngineTest {

    @Test
    fun `drops an identical alert inside the window`() {
        val seen = mutableMapOf<String, Long>()
        val key = "phoneA:15"

        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, key, 1_000L, dedupEnabled = true))
        // 1s later, same device and level
        assertEquals(true, LanSyncEngine.shouldDropAsDuplicate(seen, key, 2_000L, dedupEnabled = true))
        // just inside the 5s window
        assertEquals(true, LanSyncEngine.shouldDropAsDuplicate(seen, key, 5_999L, dedupEnabled = true))
        // past the window, accepted again
        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, key, 6_001L, dedupEnabled = true))
    }

    @Test
    fun `a different level or device is never a duplicate`() {
        val seen = mutableMapOf<String, Long>()
        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, "phoneA:15", 1_000L, true))
        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, "phoneA:14", 1_100L, true))
        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, "phoneB:15", 1_200L, true))
    }

    @Test
    fun `with the switch off nothing is ever dropped`() {
        val seen = mutableMapOf<String, Long>()
        val key = "phoneA:15"

        // Even back-to-back packets pass, which is the point of the debug switch.
        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, key, 1_000L, dedupEnabled = false))
        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, key, 1_100L, dedupEnabled = false))
        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, key, 1_200L, dedupEnabled = false))
    }

    @Test
    fun `turning the switch off clears what was remembered`() {
        val seen = mutableMapOf<String, Long>()
        val key = "phoneA:15"

        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, key, 1_000L, dedupEnabled = true))
        assertEquals(true, LanSyncEngine.shouldDropAsDuplicate(seen, key, 2_000L, dedupEnabled = true))

        // switch off: the stale entry is dropped, so re-enabling later still works
        assertEquals(false, LanSyncEngine.shouldDropAsDuplicate(seen, key, 3_000L, dedupEnabled = false))
        assertEquals(true, seen.isEmpty())
    }
}
