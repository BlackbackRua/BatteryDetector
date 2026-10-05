package com.blackback.batterydetector.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the subnet sweep used to find receivers.
 *
 * The list decides which addresses get probed, so an off-by-one here means either a
 * receiver is never found (the sweep skips it) or the app wastes a connection per
 * alert probing an address that can never answer.
 */
class PeerDiscoveryTest {

    @Test
    fun `a slash 24 subnet covers the usable host range`() {
        val hosts = PeerDiscovery.hostsInSubnet("192.168.1.7", 24)

        assertEquals(253, hosts.size) // 254 addresses minus the host itself
        assertTrue(hosts.contains("192.168.1.1"))
        assertTrue(hosts.contains("192.168.1.254"))
    }

    @Test
    fun `network and broadcast addresses are excluded`() {
        val hosts = PeerDiscovery.hostsInSubnet("192.168.1.7", 24)

        // Neither can be a receiver: one names the subnet, the other is the
        // broadcast address itself.
        assertFalse("network address probed", hosts.contains("192.168.1.0"))
        assertFalse("broadcast address probed", hosts.contains("192.168.1.255"))
    }

    @Test
    fun `the device does not probe itself`() {
        val hosts = PeerDiscovery.hostsInSubnet("192.168.1.7", 24)

        assertFalse("own address probed", hosts.contains("192.168.1.7"))
        assertEquals("a self-probe would waste a connection per alert", 253, hosts.size)
    }

    @Test
    fun `a different subnet is walked correctly`() {
        val hosts = PeerDiscovery.hostsInSubnet("10.0.5.20", 24)

        assertTrue(hosts.contains("10.0.5.1"))
        assertTrue(hosts.contains("10.0.5.254"))
        assertFalse(hosts.contains("10.0.5.20"))
        assertFalse(hosts.contains("10.0.6.1"))
    }

    @Test
    fun `a slash 25 subnet stays inside its half`() {
        // 192.168.1.128/25 spans .129-.254, so nothing below .128 may appear.
        val hosts = PeerDiscovery.hostsInSubnet("192.168.1.130", 25)

        assertTrue(hosts.contains("192.168.1.129"))
        assertTrue(hosts.contains("192.168.1.254"))
        assertFalse("leaked below the subnet", hosts.contains("192.168.1.100"))
        assertFalse("leaked the broadcast", hosts.contains("192.168.1.255"))
    }

    @Test
    fun `a large subnet is capped rather than probing tens of thousands of hosts`() {
        // A /16 would be 65534 addresses; the sweep must not try to open them all.
        val hosts = PeerDiscovery.hostsInSubnet("10.1.2.3", 16)

        assertEquals(512, hosts.size)
    }

    @Test
    fun `a malformed address yields nothing instead of throwing`() {
        assertTrue(PeerDiscovery.hostsInSubnet("not-an-ip", 24).isEmpty())
        assertTrue(PeerDiscovery.hostsInSubnet("999.1.1.1", 24).isEmpty())
        assertTrue(PeerDiscovery.hostsInSubnet("", 24).isEmpty())
        assertTrue(PeerDiscovery.hostsInSubnet("   ", 24).isEmpty())
    }

    @Test
    fun `a loopback address is refused rather than swept`() {
        // InetAddress parses "" and "localhost" as 127.0.0.1 instead of failing, so
        // without this guard an unresolved address would probe 127.0.0.0/24 - 253
        // connections that cannot reach any receiver.
        assertTrue(PeerDiscovery.hostsInSubnet("127.0.0.1", 24).isEmpty())
        assertTrue(PeerDiscovery.hostsInSubnet("localhost", 24).isEmpty())
    }

    @Test
    fun `a prefix too narrow to hold hosts is clamped`() {
        // /31 and /32 have no usable host addresses; clamping keeps the sweep from
        // returning an empty or nonsensical range.
        assertTrue(PeerDiscovery.hostsInSubnet("192.168.1.1", 32).isNotEmpty())
    }
}
