package com.transferx.core.discovery

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

data class LocalInterface(
    val name: String,
    val address: InetAddress,
    val prefixLength: Int,
    val broadcast: InetAddress?,
) {
    val isLinkLocal: Boolean get() = address.isLinkLocalAddress
}

object NetUtils {
    /**
     * Addresses we accept connections from by default: loopback, RFC 1918, link-local,
     * CGNAT/VPN (100.64/10), and IPv6 unique-local. Public addresses are refused, so an
     * accidentally exposed port isn't reachable from the internet.
     */
    fun isLocalNetworkAddress(a: InetAddress): Boolean {
        val addr = unmapIpv4(a)
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) {
            return true
        }
        val b = addr.address
        if (addr is Inet4Address) {
            val b0 = b[0].toInt() and 0xff
            val b1 = b[1].toInt() and 0xff
            return b0 == 100 && b1 in 64..127
        }
        if (addr is Inet6Address) return (b[0].toInt() and 0xfe) == 0xfc
        return false
    }

    private fun unmapIpv4(a: InetAddress): InetAddress {
        if (a is Inet6Address) {
            val b = a.address
            val mapped = (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
            if (mapped) return InetAddress.getByAddress(b.copyOfRange(12, 16))
        }
        return a
    }

    fun localIpv4Interfaces(): List<LocalInterface> {
        val result = mutableListOf<LocalInterface>()
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (ni in ifaces) {
            val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!up) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                result += LocalInterface(ni.name, a, ia.networkPrefixLength.toInt(), ia.broadcast)
            }
        }
        // Routable LAN addresses first; link-local (169.254/16, "no DHCP") last.
        return result.sortedBy { if (it.isLinkLocal) 1 else 0 }
    }

    /** Addresses to show the user and put into QR codes. */
    fun advertisedAddresses(): List<String> {
        val all = localIpv4Interfaces()
        val routable = all.filter { !it.isLinkLocal }
        return (routable.ifEmpty { all }).map { it.address.hostAddress }.distinct()
    }

    fun sameSubnet(a: InetAddress, iface: LocalInterface): Boolean {
        val x = a.address
        val y = iface.address.address
        if (x.size != y.size) return false
        var bits = iface.prefixLength
        for (i in x.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    /** True/false if [host] is a literal address we can judge; null if it can't be decided. */
    fun isOnLocalSubnet(host: String): Boolean? {
        val a = parseLiteral(host) ?: return null
        if (a.isLoopbackAddress) return true
        val ifaces = localIpv4Interfaces()
        if (ifaces.isEmpty()) return null
        return ifaces.any { sameSubnet(a, it) }
    }

    /** Parses a numeric address without triggering DNS. */
    fun parseLiteral(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        if (v4.matches(h)) {
            val parts = h.split('.').map { it.toInt() }
            if (parts.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() })
        }
        if (h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) {
            return runCatching { InetAddress.getByName(h) }.getOrNull()
        }
        return null
    }
}

/** Sliding-window limiter keyed by remote host. */
class RateLimiter(private val maxEvents: Int, private val windowMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val events = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val q = events.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        if (q.size >= maxEvents) return false
        q.addLast(now)
        if (events.size > 1024) events.entries.removeIf { it.value.isEmpty() || now - it.value.last() > windowMs }
        return true
    }
}
