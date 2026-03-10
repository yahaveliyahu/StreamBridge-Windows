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

class DiscoveryManager {
    private var jmdns: JmDNS? = null
    private var isDiscovering = false

    // Tracks IPs already reported this session so JmDNS duplicate callbacks don't
    // pop up a second "Device Found" dialog for a device the user already saw.
//    private val reportedIps = mutableSetOf<String>()


    // Thread-safe: both mDNS and the subnet scan run in parallel and share this set.
    // ConcurrentHashMap.newKeySet() is the correct choice here — mutableSetOf()
    // returns a LinkedHashSet which is NOT thread-safe.
    private val reportedIps = ConcurrentHashMap.newKeySet<String>()

    var onDeviceFound: ((deviceName: String, deviceIp: String, devicePort: Int) -> Unit)? = null
    
    companion object {
        private const val SERVICE_NAME = "_phonepclink._tcp.local."
        private const val PAIRING_PORT  = 8082   // Android DiscoveryService listens here
        private const val HTTP_PORT     = 8080   // passed to onDeviceFound for the pairing flow
    }
    
    fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true
        reportedIps.clear()  // reset for each new discovery session


        thread { runMdnsDiscovery() }
        thread { runSubnetScan()    }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // mDNS – passive listener + active list() probe
    // ─────────────────────────────────────────────────────────────────────────────
    private fun runMdnsDiscovery() {
        try {
            try { jmdns?.close() } catch (_: Exception) {}
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

    private fun runSubnetScan() {
        try {
            val localAddr = getLocalAddress() ?: run {
                println("Discovery subnet: could not determine local IP, skipping scan")
                return
            }
            val localIp = localAddr.hostAddress ?: return
            val subnet  = localIp.substringBeforeLast(".")   // e.g. "192.168.1"

            println("Discovery subnet: scanning $subnet.1–254 on port $PAIRING_PORT …")

            val latch = CountDownLatch(254)

            for (i in 1..254) {
                val targetIp = "$subnet.$i"
                if (targetIp == localIp) { latch.countDown(); continue }  // skip self

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

    // ─────────────────────────────────────────────────────────────────────────────
    // Shared helper – called by both mDNS and subnet scan
    // ─────────────────────────────────────────────────────────────────────────────
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Returns the IPv4 address of the real (WiFi / Ethernet) NIC, skipping virtual
    // adapters so JmDNS uses the interface that is actually on the phone's network.
    // ─────────────────────────────────────────────────────────────────────────────
    private fun getLocalAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { intf ->
                    intf.isUp && !intf.isLoopback && !intf.isVirtual &&
                            !intf.displayName.lowercase().let { n ->
                                n.contains("virtual") || n.contains("vmware") ||
                                        n.contains("vbox")    || n.contains("hyper-v") ||
                                        n.contains("wsl")     || n.contains("loopback")
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

    fun requestPairing(deviceIp: String, pcName: String, callback: (Boolean, String?) -> Unit) {
        thread {
            try {
                val socket = Socket(deviceIp, 8082)
                val output = socket.getOutputStream().bufferedWriter()
                val input = socket.getInputStream().bufferedReader()

                val request = JSONObject().apply {
                    put("name", pcName)
                }

                output.write(request.toString() + "\n")
                output.flush()

                val response = input.readLine()
                val json = JSONObject(response)
                val approved = json.getBoolean("approved")
                val phoneName = if (approved) json.optString("phone_name", null) else null

                socket.close()
                callback(approved, phoneName)
            } catch (e: Exception) {
                println("Error requesting pairing: ${e.message}")
                callback(false, null)
            }
        }
    }

    // האזנה לקריאה מהטלפון אחרי סריקת QR
    fun startQrListener(onConnectRequest: (String) -> Unit) {
        thread {
            try {
                // פותח שרת האזנה בפורט 8083
                val serverSocket = ServerSocket(8083)
                println("QR Listener started on port 8083")

                while (true) {
                    val clientSocket = serverSocket.accept() // מחכה שהטלפון יתחבר
                    try {
                        val input = clientSocket.getInputStream().bufferedReader()
                        val message = input.readLine() // קורא את ה-IP של הטלפון

                        if (!message.isNullOrBlank()) {
                            println("QR Scan detected! Phone IP: $message")
                            // מפעיל את החיבור ב-MainApp
                            onConnectRequest(message)
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
}








































//
//    thread {
//            try {
//                // Close any leftover instance from a previous run before creating a new one
//                try {
//                    jmdns?.close()
//                } catch (_: Exception) {}
//                jmdns = null
////                jmdns = JmDNS.create()
//
//                // Passing the actual LAN/WiFi InetAddress forces JmDNS to use the right NIC.
//                val localAddr = getLocalAddress()
//                println("Discovery: binding JmDNS to $localAddr")
//                jmdns = if (localAddr != null) JmDNS.create(localAddr) else JmDNS.create()
//
//                // ── Passive listener ──────────────────────────────────────────────────────
//                // Catches phones that announce themselves *after* we start listening,
//                // and also handles late/repeated mDNS announcements during the session
//                jmdns?.addServiceListener(SERVICE_NAME, object : ServiceListener {
//                    override fun serviceAdded(event: ServiceEvent) {
//                        println("Service added: ${event.name}")
//                        jmdns?.requestServiceInfo(event.type, event.name, 1000)
//                    }
//
//                    override fun serviceRemoved(event: ServiceEvent) {
//                        println("Service removed: ${event.name}")
//                    }
//
//                    override fun serviceResolved(event: ServiceEvent) {
//                        reportDevice(
//                            event.info.name,
//                            event.info.inet4Addresses.firstOrNull()?.hostAddress,
//                            event.info.port)
//                    }
//                })
//
//                println("Discovery started")
//
//                // ── Active probe ──────────────────────────────────────────────────────────
//                // The passive listener only fires when the phone re-announces.  If the
//                // phone already advertised before we called addServiceListener that packet
//                // is gone.  list() sends an explicit mDNS query so the phone replies
//                // immediately — this makes the first click reliable instead of needing
//                // several attempts.
//                val existing = jmdns?.list(SERVICE_NAME, 5000) ?: emptyArray()
//                for (info in existing) {
//                    reportDevice(
//                        info.name,
//                        info.inet4Addresses.firstOrNull()?.hostAddress,
//                        info.port)
//                }
//
//            } catch (e: Exception) {
//                println("Error starting discovery: ${e.message}")
//                // Reset flag so the user can retry — without this, the button stays broken
//                isDiscovering = false
//                jmdns = null
//            }
//        }
//    }
//
//    // Returns the IPv4 address of the first real (non-loopback, non-virtual,
//    // non-link-local) network interface that is currently up.
//    private fun getLocalAddress(): InetAddress? {
//        return try {
//            NetworkInterface.getNetworkInterfaces()
//                ?.asSequence()
//                ?.filter { intf ->
//                    intf.isUp &&
//                            !intf.isLoopback &&
//                            !intf.isVirtual &&
//                            // Skip Microsoft / VMware / VirtualBox / Hyper-V virtual adapters
//                            // by checking common name prefixes (best-effort filter).
//                            !intf.displayName.lowercase().let { name ->
//                                name.contains("virtual") ||
//                                        name.contains("vmware") ||
//                                        name.contains("vbox") ||
//                                        name.contains("hyper-v") ||
//                                        name.contains("wsl") ||
//                                        name.contains("loopback")
//                            }
//                }
//                ?.flatMap { it.inetAddresses.asSequence() }
//                ?.firstOrNull { addr ->
//                    addr is Inet4Address &&
//                            !addr.isLoopbackAddress &&
//                            !addr.hostAddress.startsWith("169.254") // exclude APIPA/link-local
//                }
//        } catch (e: Exception) {
//            println("getLocalAddress error: ${e.message}")
//            null
//        }
//    }
//
//    // ── Helper used by both the passive listener and the active probe ─────────────
//    private fun reportDevice(deviceName: String, deviceIp: String?, devicePort: Int) {
//        if (deviceIp.isNullOrBlank()) return
//        // Only fire onDeviceFound once per IP per session (JmDNS & list() can both
//        // return the same device; reportedIps deduplicates them).
//        if (!reportedIps.add(deviceIp)) {
//            println("serviceResolved: already reported $deviceIp – skipping duplicate")
//            return
//        }
//        println("Device found: $deviceName at $deviceIp:$devicePort")
//        onDeviceFound?.invoke(deviceName, deviceIp, devicePort)
//    }






























//                    override fun serviceResolved(event: ServiceEvent) {
//                        val info = event.info
//                        val deviceName = info.name
//                        val addresses = info.inet4Addresses
//
//                        if (addresses.isNotEmpty()) {
//                            val deviceIp = addresses[0].hostAddress ?: return
//                            val devicePort = info.port
//
//                        // JmDNS often can't resolve the Android host via DNS.
//                        // Android sets an explicit "ip" TXT attribute – use that as primary,
//                        // fall back to inet4Addresses if the TXT record is absent.
////                        val deviceIp: String? = info.getPropertyString("ip")?.takeIf { it.isNotBlank() }
////                                ?: info.inet4Addresses.firstOrNull()?.hostAddress
//
////                        if (deviceIp == null) {
////                            println("serviceResolved: could not determine IP for $deviceName – skipping")
////                            return
////                        }
//
//                        // JmDNS fires serviceResolved multiple times for the same device.
//                        // Only report each IP once per discovery session.
//                        if (!reportedIps.add(deviceIp)) {
//                            println("serviceResolved: already reported $deviceIp – skipping duplicate")
//                            return
//                        }
//
//                            println("Device found: $deviceName at $deviceIp:$devicePort")
//                            onDeviceFound?.invoke(deviceName, deviceIp, devicePort)
//                        }
//                    }
//                })
//                println("Discovery started")
//            } catch (e: Exception) {
//                println("Error starting discovery: ${e.message}")
//                // Reset flag so the user can retry — without this, the button stays broken
//                isDiscovering = false
//                jmdns = null
//            }
//        }
//    }
//
//                        val devicePort = info.port
//                        println("Device found: $deviceName at $deviceIp:$devicePort")
//                        onDeviceFound?.invoke(deviceName, deviceIp, devicePort)
//                    }
//                })

                
//                println("Discovery started")
//            } catch (e: Exception) {
//                println("Error starting discovery: ${e.message}")
//            }
//        }
//    }

