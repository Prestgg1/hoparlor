// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.prestgg.hoparlor

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** One path to a server: the address we heard it on and whether that is the USB cable. */
data class Route(val host: String, val usb: Boolean, val lastSeen: Long)

/** A PC running hoparlor-server. The same PC can be reachable over Wi-Fi and USB at once. */
data class Server(val id: Long, val name: String, val port: Int, val routes: List<Route>) {
    /** USB tethering has far less jitter than Wi-Fi, so prefer it whenever it is there. */
    val best: Route get() = routes.firstOrNull { it.usb } ?: routes.first()
    val viaUsb: Boolean get() = best.usb
}

/**
 * Listens for the UDP broadcast beacons hoparlor-server sends every second on each interface,
 * so PCs show up automatically, with no IP addresses to type.
 */
class Discovery(context: Context) {
    val servers = mutableStateListOf<Server>()

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        // Many Wi-Fi drivers filter broadcast frames unless this is held.
        lock = wifi.createMulticastLock("hoparlor-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread = Thread(::loop, "hoparlor-discovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        lock?.release()
        lock = null
    }

    private fun loop() {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            return
        }
        socket.use {
            val buf = ByteArray(512)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    it.receive(packet)
                    parse(packet)?.let { b -> main.post { upsert(b) } }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!running) break
                }
                main.post { expire() }
            }
        }
    }

    private class Beacon(val id: Long, val name: String, val port: Int, val route: Route)

    private fun parse(p: DatagramPacket): Beacon? {
        val d = p.data
        val n = p.length
        if (n < 12 || d[0] != 'H'.code.toByte() || d[1] != 'P'.code.toByte() ||
            d[2] != 'R'.code.toByte() || d[3] != '1'.code.toByte() || d[4] != TYPE_BEACON
        ) return null
        val port = u8(d, 6) or (u8(d, 7) shl 8)
        val id = (u8(d, 8).toLong() or (u8(d, 9).toLong() shl 8) or
            (u8(d, 10).toLong() shl 16) or (u8(d, 11).toLong() shl 24))
        val addr = p.address as? Inet4Address ?: return null
        val host = addr.hostAddress ?: return null
        val name = String(d, 12, n - 12, Charsets.UTF_8).ifBlank { host }
        return Beacon(id, name, port, Route(host, isUsb(addr), SystemClock.elapsedRealtime()))
    }

    private fun upsert(b: Beacon) {
        val i = servers.indexOfFirst { it.id == b.id }
        if (i < 0) {
            servers.add(Server(b.id, b.name, b.port, listOf(b.route)))
        } else {
            val old = servers[i]
            val routes = old.routes.filter { it.host != b.route.host } + b.route
            servers[i] = old.copy(name = b.name, port = b.port, routes = routes.sortedBy { it.host })
        }
    }

    private fun expire() {
        val now = SystemClock.elapsedRealtime()
        for (i in servers.indices.reversed()) {
            val s = servers[i]
            val alive = s.routes.filter { now - it.lastSeen <= 4000 }
            when {
                alive.isEmpty() -> servers.removeAt(i)
                alive.size != s.routes.size -> servers[i] = s.copy(routes = alive)
            }
        }
    }

    companion object {
        const val DISCOVERY_PORT = 47801
        const val DEFAULT_PORT = 47800
        private const val TYPE_BEACON: Byte = 3

        private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xff

        /** True if [addr] is on the subnet of a USB tethering / USB network interface. */
        fun isUsb(addr: InetAddress): Boolean = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif ->
                val name = nif.name.lowercase()
                val usbLike = name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")
                usbLike && nif.interfaceAddresses.any { ia ->
                    val local = ia.address as? Inet4Address ?: return@any false
                    sameSubnet(local, addr, ia.networkPrefixLength.toInt())
                }
            }
        } catch (_: Exception) {
            false
        }

        private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
            val x = a.address
            val y = b.address
            if (x.size != y.size) return false
            var bits = prefix
            for (i in x.indices) {
                if (bits <= 0) return true
                val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
                if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
                bits -= 8
            }
            return true
        }
    }
}
