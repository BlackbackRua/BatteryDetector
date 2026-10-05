package com.blackback.batterydetector.network

import android.content.Context
import android.util.Log
import com.blackback.batterydetector.data.LogRepository
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds receiver devices by probing the local subnet over unicast.
 *
 * Broadcast (255.255.255.255 and the subnet-directed address) and multicast
 * (224.0.0.0/4, which is what mDNS and NSD rely on) are both dropped by some
 * networks - a router with client isolation, or one that suppresses broadcast to
 * save airtime. On such a network there is no announcement channel at all, so the
 * only way to find a peer is to ask each address directly.
 *
 * Everything here is unicast, which those networks do deliver, so discovery keeps
 * working where broadcasting silently fails. Results are cached and reused, because
 * a full sweep is 254 connections and there is no reason to repeat it for every
 * alert.
 *
 * Addresses are never configured by hand: the peer list is derived from the subnet
 * the device is actually on, so it survives DHCP handing out a different address.
 */
object PeerDiscovery {

    private const val TAG = "PeerDiscovery"

    /** Concurrency for the sweep. High enough to finish fast, low enough to be polite. */
    private const val SCAN_THREADS = 24

    /**
     * How long a probe waits for a reply.
     *
     * A device on the same subnet answers in single-digit milliseconds; anything
     * slower than this is either absent or not the app, and waiting longer only
     * makes the sweep slower.
     */
    private const val PROBE_TIMEOUT_MS = 300

    /** A cached peer list older than this is refreshed before it is trusted. */
    private const val CACHE_TTL_MS = 5 * 60_000L

    /** Cap on addresses probed, so an unusually large subnet cannot stall an alert. */
    private const val MAX_HOSTS = 512

    data class Peer(val address: String, val deviceName: String)

    private val lock = Any()
    private var cachedPeers: List<Peer> = emptyList()
    private var lastScanAt: Long = 0L

    /** Peers found by the most recent sweep, without triggering one. */
    fun cached(): List<Peer> = synchronized(lock) { cachedPeers }

    /**
     * Returns the known receivers, sweeping the subnet if the cache is stale.
     *
     * [force] skips the freshness check, for the case where a delivery just failed
     * and the peer may have moved to a new address.
     */
    fun discover(context: Context, port: Int, force: Boolean = false): List<Peer> {
        synchronized(lock) {
            val fresh = System.currentTimeMillis() - lastScanAt < CACHE_TTL_MS
            if (!force && fresh && cachedPeers.isNotEmpty()) return cachedPeers
        }

        val found = scan(context, port)
        synchronized(lock) {
            cachedPeers = found
            lastScanAt = System.currentTimeMillis()
        }
        return found
    }

    /** Drops the cache, so the next discovery sweeps again. */
    fun invalidate() {
        synchronized(lock) {
            cachedPeers = emptyList()
            lastScanAt = 0L
        }
    }

    /**
     * Probes every usable address on the local subnet and keeps those that identify
     * themselves as a receiver.
     *
     * Public so the sweep can be exercised on its own; callers normally want
     * [discover], which adds caching.
     */
    fun scan(context: Context, port: Int, localIpOverride: String? = null): List<Peer> {
        val localIp = localIpOverride ?: localIpv4() ?: return emptyList()
        val prefix = prefixLengthFor(localIp)
        val hosts = hostsInSubnet(localIp, prefix)

        if (hosts.isEmpty()) {
            LogRepository.addLog("无法确定本机网段，跳过接收端发现", isError = true)
            return emptyList()
        }

        LogRepository.addLog("开始扫描网段寻找接收端 (${hosts.size} 个地址, 端口 $port)")

        val results = java.util.Collections.synchronizedList(mutableListOf<Peer>())
        val pool = Executors.newFixedThreadPool(SCAN_THREADS)
        val probed = AtomicInteger(0)

        try {
            for (host in hosts) {
                pool.execute {
                    try {
                        probe(host, port)?.let { results.add(it) }
                    } catch (_: Exception) {
                        // A silent host is the normal case; nothing to report.
                    } finally {
                        probed.incrementAndGet()
                    }
                }
            }
            pool.shutdown()
            // Bounded so a wedged probe cannot hold up an alert forever.
            pool.awaitTermination(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            LogRepository.addLog("扫描接收端异常: ${e.localizedMessage}", isError = true)
        } finally {
            pool.shutdownNow()
        }

        val peers = results.toList().sortedBy { it.address }
        if (peers.isEmpty()) {
            LogRepository.addLog(
                "扫描完成，未发现接收端 (已探测 ${probed.get()} 个地址)",
                isError = true
            )
        } else {
            LogRepository.addLog(
                "扫描完成，发现 ${peers.size} 个接收端: " +
                    peers.joinToString(", ") { "${it.deviceName}@${it.address}" }
            )
        }
        return peers
    }

    /**
     * Asks one address whether it is a receiver.
     *
     * Uses HTTP rather than a bare connect so the reply can be checked: an open port
     * alone proves nothing, and treating any listener as a receiver would send
     * alerts to unrelated services.
     */
    private fun probe(host: String, port: Int): Peer? {
        // Cheap reachability gate first: opening a TCP connection is far less work
        // than a full HTTP exchange, and most addresses do not answer at all.
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            return null
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://$host:$port${LanSyncEngine.DISCOVERY_PATH}")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                requestMethod = "GET"
            }
            if (connection.responseCode != 200) return null

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            // The marker is what separates a receiver from anything else on the port.
            if (!text.contains(LanSyncEngine.DISCOVERY_APP_ID)) return null

            val name = runCatching {
                org.json.JSONObject(text).optString("device", "")
            }.getOrNull().orEmpty()

            Peer(address = host, deviceName = name.ifBlank { host })
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** The device's own IPv4 address on the Wi-Fi interface, or null. */
    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "localIpv4 failed: ${e.localizedMessage}")
            null
        }
    }

    /** Prefix length of the interface holding [localIp], defaulting to /24. */
    fun prefixLengthFor(localIp: String): Int {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.interfaceAddresses.toList() }
                .firstOrNull { it.address is java.net.Inet4Address &&
                    it.address.hostAddress == localIp }
                ?.networkPrefixLength
                ?.toInt()
                ?: 24
        } catch (_: Exception) {
            24
        }
    }

    /**
     * Every host address in the subnet containing [localIp].
     *
     * The network and broadcast addresses are excluded, and the host's own address
     * with them: a receiver would never be the sender itself. A very large subnet is
     * capped, because probing tens of thousands of addresses would take longer than
     * the alert is worth.
     */
    fun hostsInSubnet(localIp: String, prefixLength: Int): List<String> {
        if (localIp.isBlank()) return emptyList()

        val address = runCatching { java.net.InetAddress.getByName(localIp) }.getOrNull()
            ?: return emptyList()
        // An empty or unresolved name can come back as the loopback address rather
        // than throwing, which would otherwise sweep 127.0.0.0/24 and open 253
        // connections that cannot possibly reach a receiver.
        if (address.isLoopbackAddress) return emptyList()

        val ip = address.address
        if (ip.size != 4) return emptyList()

        val prefix = prefixLength.coerceIn(8, 30)
        val mask = (-1 shl (32 - prefix))
        val ipInt = ((ip[0].toInt() and 0xFF) shl 24) or
            ((ip[1].toInt() and 0xFF) shl 16) or
            ((ip[2].toInt() and 0xFF) shl 8) or
            (ip[3].toInt() and 0xFF)
        val network = ipInt and mask
        val broadcast = network or mask.inv()

        val hosts = mutableListOf<String>()
        var current = network + 1
        while (current < broadcast && hosts.size < MAX_HOSTS) {
            if (current != ipInt) {
                hosts.add(
                    "${(current ushr 24) and 0xFF}.${(current ushr 16) and 0xFF}." +
                        "${(current ushr 8) and 0xFF}.${current and 0xFF}"
                )
            }
            current++
        }
        return hosts
    }
}
