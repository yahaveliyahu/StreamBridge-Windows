package com.phonepclink.windows

import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread
import org.json.JSONObject
import java.net.*

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket


class DiscoveryManager {
    private var jmdns: JmDNS? = null
    private var isDiscovering = false

    // Thread-safe: both mDNS and the subnet scan run in parallel and share this set.
    // ConcurrentHashMap.newKeySet() is the correct choice here — mutableSetOf()
    // returns a LinkedHashSet which is NOT thread-safe.
    private val reportedIps = ConcurrentHashMap.newKeySet<String>()

    var onDeviceFound: ((deviceName: String, deviceIp: String, devicePort: Int) -> Unit)? = null

    companion object {
        private const val SERVICE_NAME = "_phonepclink._tcp.local."
        private const val PAIRING_PORT = 8082   // Android DiscoveryService listens here
        private const val HTTP_PORT = 8080   // passed to onDeviceFound for the pairing flow
    }

    fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true
        reportedIps.clear()  // reset for each new discovery session

        thread { runMdnsDiscovery() }
        thread { runSubnetScan() }
    }

    // ── mDNS – passive listener + active list() probe ────────────────────────────

    private fun runMdnsDiscovery() {
        try {
            try {
                jmdns?.close()
            } catch (_: Exception) {
            }
            jmdns = null

            val localAddr = getLocalAddress()
            println("Discovery mDNS: binding JmDNS to $localAddr")
            jmdns = if (localAddr != null) JmDNS.create(localAddr) else JmDNS.create()

            jmdns?.addServiceListener(SERVICE_NAME, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    println("mDNS service added: ${event.name}")
                    jmdns?.requestServiceInfo(event.type, event.name, 1000)
                }

                override fun serviceRemoved(event: ServiceEvent) {}
                override fun serviceResolved(event: ServiceEvent) {
                    reportDevice(
                        event.info.name,
                        event.info.inet4Addresses.firstOrNull()?.hostAddress,
                        event.info.port
                    )
                }
            })

            println("Discovery mDNS: listener registered, sending active probe…")
            val existing = jmdns?.list(SERVICE_NAME, 4000) ?: emptyArray()
            for (info in existing) {
                reportDevice(info.name, info.inet4Addresses.firstOrNull()?.hostAddress, info.port)
            }
            println("Discovery mDNS: active probe done (${existing.size} results)")

        } catch (e: Exception) {
            println("Discovery mDNS error: ${e.message}")
        }
    }

    // ── Subnet scan ──────────────────────────────────────────────────────────────

    private fun runSubnetScan() {
        try {
            val localAddr = getLocalAddress() ?: run {
                println("Discovery subnet: could not determine local IP, skipping scan")
                return
            }
            val localIp = localAddr.hostAddress ?: return
            val subnet = localIp.substringBeforeLast(".")   // e.g. "192.168.1"

            println("Discovery subnet: scanning $subnet.1–254 on port $PAIRING_PORT …")

            val latch = CountDownLatch(254)

            for (i in 1..254) {
                val targetIp = "$subnet.$i"
                if (targetIp == localIp) {
                    latch.countDown(); continue
                }  // skip self

                thread {
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(targetIp, PAIRING_PORT), 400)
                            // Connection accepted → this host is running StreamBridge
                            println("Discovery subnet: StreamBridge found at $targetIp")
                            reportDevice("StreamBridge", targetIp, HTTP_PORT)
                        }
                    } catch (_: Exception) {
                        // Port closed or host unreachable – not our phone, ignore
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(6, TimeUnit.SECONDS)
            println("Discovery subnet: scan complete")

        } catch (e: Exception) {
            println("Discovery subnet error: ${e.message}")
        }
    }

    // ── Shared helper – called by both mDNS and subnet scan ──────────────────────

    private fun reportDevice(deviceName: String, deviceIp: String?, devicePort: Int) {
        if (deviceIp.isNullOrBlank()) return
        // add() returns false if the IP was already in the set → skip duplicate
        if (!reportedIps.add(deviceIp)) {
            println("Discovery: $deviceIp already reported – skipping duplicate")
            return
        }
        println("Device found: $deviceName at $deviceIp:$devicePort")
        onDeviceFound?.invoke(deviceName, deviceIp, devicePort)
    }

    fun stopDiscovery() {
        isDiscovering = false
        try {
            jmdns?.close()
        } catch (e: Exception) {
            println("Error stopping discovery: ${e.message}")
        } finally {
            jmdns = null  // must be nulled so next startDiscovery() creates a fresh instance
        }
    }

    // ── Pairing (Auto-Discover) ───────────────────────────────────────────────────
    // The response now includes the phone's TLS certificate so Windows can pin it.
    // Signature change: callback receives (approved, phoneName, certBase64?)

    fun requestPairing(
        deviceIp: String,
        pcName: String,
        callback: (approved: Boolean, phoneName: String?, certBase64: String?) -> Unit
    ) {

        thread {
            try {
                val socket = Socket(deviceIp, PAIRING_PORT)
                val output = socket.getOutputStream().bufferedWriter()
                val input = socket.getInputStream().bufferedReader()

                val request = JSONObject().apply {
                    put("name", pcName)
                }
                output.write(request.toString() + "\n")
                output.flush()

                val json = JSONObject(input.readLine())
                val approved = json.getBoolean("approved")
                val phoneName = if (approved) json.optString("phone_name", null) else null
                // ── cert sent by Android DiscoveryService.sendResponse() ────────
                val certBase64 = if (approved) json.optString("cert", null) else null

                socket.close()
                callback(approved, phoneName, certBase64)
            } catch (e: Exception) {
                println("Error requesting pairing: ${e.message}")
                callback(false, null, null)
            }
        }
    }

    // ── QR listener ──────────────────────────────────────────────────────────────
    // The phone sent a JSON object: {"ip":"...","cert":"<base64>","fingerprint":"..."}

    // האזנה לקריאה מהטלפון אחרי סריקת QR
    fun startQrListener(onConnectRequest: (phoneIp: String, certBase64: String?) -> Unit) {
        thread {
            try {
                // Opens a server listening on port 8083
                val serverSocket = ServerSocket(8083)
                println("QR Listener started on port 8083")

                while (true) {
                    val clientSocket = serverSocket.accept() // Waiting for the phone to connect
                    try {
                        val input = clientSocket.getInputStream().bufferedReader()
                        val message = input.readLine() // Reads the phone's IP
                        if (!message.isNullOrBlank()) {
                            println("QR Scan detected! Phone IP: $message")
                            if (message.trim().startsWith("{")) {
                                // New JSON format: {"ip":"...","cert":"...","fingerprint":"..."}
                                val json = JSONObject(message)
                                val ip = json.getString("ip")
                                val certBase64 = json.optString("cert", null)
                                onConnectRequest(ip, certBase64)
                            } else {
                                // Legacy: plain IP string (no cert — will use trust-all TLS)
                                // Activates the connection in MainApp
                                onConnectRequest(message.trim(), null)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        clientSocket.close()
                    }
                }
            } catch (e: Exception) {
                println("Error in QR Listener: ${e.message}")
            }
        }
    }

    // ── Network interface selection ───────────────────────────────────────────────
    // Returns the IPv4 address of the real (WiFi / Ethernet) NIC, skipping virtual
    // adapters so JmDNS uses the interface that is actually on the phone's network

    private fun getLocalAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { intf ->
                    intf.isUp && !intf.isLoopback && !intf.isVirtual &&
                            !intf.displayName.lowercase().let { n ->
                                n.contains("virtual") || n.contains("vmware") ||
                                        n.contains("vbox") || n.contains("hyper-v") ||
                                        n.contains("wsl") || n.contains("loopback")
                            }
                }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { addr ->
                    addr is Inet4Address &&
                            !addr.isLoopbackAddress &&
                            !addr.hostAddress.startsWith("169.254")   // skip APIPA/link-local
                }
        } catch (e: Exception) {
            println("getLocalAddress error: ${e.message}")
            null
        }
    }
}