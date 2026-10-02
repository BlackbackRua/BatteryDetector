package com.blackback.batterydetector.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the subnet-broadcast derivation behind LAN alerts.
 *
 * The original code broadcast only to 255.255.255.255, the limited-broadcast
 * address that many consumer routers refuse to forward - the packet left the
 * device and simply never arrived, with no error to show for it. These tests pin
 * the directed address that is sent instead.
 */
class LanSyncEngineTest {

    @Test
    fun `derives a class C subnet broadcast`() {
        assertEquals("192.168.1.255", LanSyncEngine.subnetBroadcast("192.168.1.7", 24))
        assertEquals("10.0.0.255", LanSyncEngine.subnetBroadcast("10.0.0.42", 24))
    }

    @Test
    fun `derives broader and narrower prefixes`() {
        assertEquals("192.168.255.255", LanSyncEngine.subnetBroadcast("192.168.1.7", 16))
        assertEquals("172.16.15.255", LanSyncEngine.subnetBroadcast("172.16.3.9", 20))
        assertEquals("192.168.1.127", LanSyncEngine.subnetBroadcast("192.168.1.7", 25))
        // /31 and /32 have no broadcast address
        assertEquals("192.168.1.1", LanSyncEngine.subnetBroadcast("192.168.1.0", 31))
    }

    @Test
    fun `rejects input it cannot interpret`() {
        assertNull(LanSyncEngine.subnetBroadcast("192.168.1.7", 0))
        assertNull(LanSyncEngine.subnetBroadcast("192.168.1.7", 32))
        assertNull(LanSyncEngine.subnetBroadcast("192.168.1", 24))
        assertNull(LanSyncEngine.subnetBroadcast("192.168.1.999", 24))
        assertNull(LanSyncEngine.subnetBroadcast("192.168.1.x", 24))
    }

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
