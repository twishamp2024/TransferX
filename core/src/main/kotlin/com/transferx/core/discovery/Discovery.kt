package com.transferx.core.discovery

import com.transferx.core.util.Ids
import com.transferx.core.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException

@Serializable
data class Announcement(
    val app: String = APP,
    val v: Int = 1,
    /** "announce", "query" (please announce yourselves), or "reply" (unicast answer to a query). */
    val kind: String = "announce",
    val id: String,
    val name: String,
    val platform: String,
    val port: Int,
    /** Certificate fingerprint (hex). A hint only: discovery packets are unauthenticated. */
    val fp: String,
    val transports: List<String> = listOf("wifi"),
) {
    companion object {
        const val APP = "transferx"
    }
}

data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    /** Most recently seen address first. */
    val addresses: List<String>,
    val port: Int,
    val fingerprint: String,
    val transports: List<String>,
    val lastSeenAt: Long,
    val available: Boolean,
)

data class DiscoveryStatus(
    val running: Boolean = false,
    /** Set when discovery couldn't use its usual port or network; it keeps working with reduced reach. */
    val problem: String? = null,
    val startedAt: Long = 0,
    /** True once a search has gone on long enough without results to suggest manual connection. */
    val suggestFallback: Boolean = false,
)

/**
 * Finds TransferX devices on the local network by UDP multicast (239.255.47.82) *and*
 * broadcast, because many routers and guest networks filter one or the other. If both
 * are blocked, [DiscoveryStatus.suggestFallback] tells the UI to offer the QR code or
 * address-and-code connection, which only needs unicast TCP.
 */
class DiscoveryService(
    private val scope: CoroutineScope,
    /** Our announcement, or null when not discoverable (we still listen and query). */
    private val self: () -> Announcement?,
    private val udpPort: Int = DEFAULT_PORT,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Platform hook, e.g. Android's WifiManager.MulticastLock. */
    private val acquireMulticastLock: () -> AutoCloseable? = { null },
    private val fallbackAfterMs: Long = 8_000,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val group: InetAddress = InetAddress.getByName(GROUP)
    private val registry = LinkedHashMap<String, DiscoveredDevice>()
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    private val _status = MutableStateFlow(DiscoveryStatus())
    val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()

    @Volatile private var socket: MulticastSocket? = null
    private var jobs = emptyList<Job>()
    private var lock: AutoCloseable? = null
    private var joined = emptySet<String>()
    private val packetBudget = RateLimiter(maxEvents = 200, windowMs = 1_000, clock = clock)

    @Synchronized
    fun start() {
        if (socket != null) return
        lock = runCatching { acquireMulticastLock() }.getOrNull()
        var problem: String? = null
        val s = try {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(udpPort))
            }
        } catch (e: Exception) {
            problem = "Discovery port $udpPort is unavailable; only replies to our searches can be received."
            Log.w(TAG, problem, e)
            runCatching { MulticastSocket(0) }.getOrNull()
        }
        if (s == null) {
            _status.value = DiscoveryStatus(running = false, problem = "Network discovery unavailable", suggestFallback = true)
            return
        }
        runCatching { s.broadcast = true }
        runCatching { s.timeToLive = 1 } // never leave the local network segment
        runCatching { s.loopbackMode = false } // false = loopback enabled (JDK naming is inverted)
        socket = s
        joinGroups(s)
        _status.value = DiscoveryStatus(running = true, problem = problem, startedAt = clock())
        jobs = listOf(
            scope.launch(Dispatchers.IO) { receiveLoop(s) },
            scope.launch(Dispatchers.IO) { announceLoop(s) },
            scope.launch(Dispatchers.IO) { maintenanceLoop(s) },
        )
        refresh()
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        socket?.close()
        socket = null
        runCatching { lock?.close() }
        lock = null
        joined = emptySet()
        _status.value = _status.value.copy(running = false)
    }

    /** Asks every device to announce itself now and restarts the "no devices found" timer. */
    fun refresh() {
        val s = socket ?: return
        _status.value = _status.value.copy(startedAt = clock(), suggestFallback = false)
        val me = self() ?: Announcement(id = QUERY_ONLY_ID, name = "", platform = "", port = 0, fp = "")
        scope.launch(Dispatchers.IO) { sendAll(s, me.copy(kind = "query")) }
    }

    private fun joinGroups(s: MulticastSocket) {
        val now = NetUtils.localIpv4Interfaces().map { it.name }.toSet()
        for (name in now - joined) {
            val ni = runCatching { NetworkInterface.getByName(name) }.getOrNull() ?: continue
            if (runCatching { !ni.supportsMulticast() }.getOrDefault(true)) continue
            try {
                s.joinGroup(InetSocketAddress(group, udpPort), ni)
            } catch (e: Exception) {
                Log.d(TAG, "joinGroup failed on $name: ${e.message}")
            }
        }
        joined = now
    }

    private fun receiveLoop(s: MulticastSocket) {
        val buf = ByteArray(MAX_PACKET)
        while (!s.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                continue
            }
            val from = packet.address ?: continue
            if (!NetUtils.isLocalNetworkAddress(from)) continue
            if (!packetBudget.tryAcquire("all")) continue
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val a = runCatching { json.decodeFromString(Announcement.serializer(), text) }.getOrNull() ?: continue
            if (a.app != Announcement.APP || a.v != 1) continue
            val me = self()
            if (a.kind == "query") {
                // Reply to queries even from devices we don't know: that's what lets a new device find us.
                if (me != null && a.id != me.id) {
                    sendTo(s, me.copy(kind = "reply"), InetSocketAddress(from, packet.port))
                }
                if (a.id == QUERY_ONLY_ID || a.port == 0) continue
            }
            if (me != null && a.id == me.id) continue
            accept(a, from.hostAddress ?: continue)
        }
    }

    private fun accept(a: Announcement, address: String) {
        if (!Ids.isValid(a.id) || a.port !in 1..65535 || a.fp.length != 64 || !a.fp.all { it in "0123456789abcdef" }) return
        val name = a.name.filter { it.code >= 0x20 }.trim().take(64).ifEmpty { "Unknown device" }
        val now = clock()
        synchronized(registry) {
            val old = registry[a.id]
            val addresses = (listOf(address) + (old?.addresses ?: emptyList())).distinct().take(4)
            registry[a.id] = DiscoveredDevice(
                a.id, name, a.platform.take(16), addresses, a.port, a.fp,
                a.transports.take(4).map { it.take(16) }, now, true,
            )
            if (registry.size > MAX_DEVICES) registry.remove(registry.keys.first())
            publishLocked(now)
        }
    }

    private suspend fun announceLoop(s: MulticastSocket) {
        while (scope.isActive && !s.isClosed) {
            self()?.let { sendAll(s, it.copy(kind = "announce")) }
            delay(ANNOUNCE_INTERVAL_MS)
        }
    }

    private suspend fun maintenanceLoop(s: MulticastSocket) {
        var lastIfaceCheck = 0L
        while (scope.isActive && !s.isClosed) {
            delay(2_000)
            val now = clock()
            synchronized(registry) {
                registry.entries.removeIf { now - it.value.lastSeenAt > FORGET_AFTER_MS }
                publishLocked(now)
            }
            val st = _status.value
            if (st.running && !st.suggestFallback && _devices.value.none { it.available } && now - st.startedAt > fallbackAfterMs) {
                _status.value = st.copy(suggestFallback = true)
            }
            if (now - lastIfaceCheck > 10_000) {
                lastIfaceCheck = now
                joinGroups(s) // network changes: new Wi-Fi, new address
            }
        }
    }

    private fun publishLocked(now: Long) {
        _devices.value = registry.values.map { it.copy(available = now - it.lastSeenAt < STALE_AFTER_MS) }
            .sortedWith(compareByDescending<DiscoveredDevice> { it.available }.thenBy { it.name.lowercase() })
        if (_devices.value.any { it.available }) _status.value = _status.value.copy(suggestFallback = false)
    }

    private fun sendAll(s: MulticastSocket, a: Announcement) {
        sendTo(s, a, InetSocketAddress(group, udpPort))
        sendTo(s, a, InetSocketAddress(InetAddress.getByName("255.255.255.255"), udpPort))
        for (iface in NetUtils.localIpv4Interfaces()) {
            iface.broadcast?.let { sendTo(s, a, InetSocketAddress(it, udpPort)) }
        }
    }

    private fun sendTo(s: MulticastSocket, a: Announcement, to: InetSocketAddress) {
        val bytes = json.encodeToString(Announcement.serializer(), a).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PACKET) return
        try {
            s.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: Exception) {
            Log.d(TAG, "send to ${to.address?.hostAddress} failed: ${e.message}")
        }
    }

    /** Known devices with their addresses, for the engine's reconnect resolver. */
    fun addressesOf(deviceId: String): List<String>? = synchronized(registry) { registry[deviceId]?.addresses }

    companion object {
        private const val TAG = "Discovery"
        const val DEFAULT_PORT = 47821
        const val GROUP = "239.255.47.82"
        private const val MAX_PACKET = 2048
        private const val MAX_DEVICES = 256
        private const val ANNOUNCE_INTERVAL_MS = 4_000L
        private const val STALE_AFTER_MS = 15_000L
        private const val FORGET_AFTER_MS = 120_000L
        private const val QUERY_ONLY_ID = "query-only-client"
    }
}
