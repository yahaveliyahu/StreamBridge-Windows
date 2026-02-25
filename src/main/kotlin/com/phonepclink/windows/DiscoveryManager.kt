package com.phonepclink.windows

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.Socket
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread
import org.json.JSONObject

class DiscoveryManager {
    private var jmdns: JmDNS? = null
    private var isDiscovering = false
    
    var onDeviceFound: ((deviceName: String, deviceIp: String, devicePort: Int) -> Unit)? = null
    
    companion object {
        private const val SERVICE_TYPE = "_phonepclink._tcp.local."
    }
    
    fun startDiscovery() {
        if (isDiscovering) return
        
        isDiscovering = true
        
        thread {
            try {
                jmdns = JmDNS.create()
                
                jmdns?.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        println("Service added: ${event.name}")
                        jmdns?.requestServiceInfo(event.type, event.name, 1000)
                    }
                    
                    override fun serviceRemoved(event: ServiceEvent) {
                        println("Service removed: ${event.name}")
                    }
                    
                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        val deviceName = info.name
                        val addresses = info.inet4Addresses
                        
                        if (addresses.isNotEmpty()) {
                            val deviceIp = addresses[0].hostAddress ?: return
                            val devicePort = info.port
                            
                            println("Device found: $deviceName at $deviceIp:$devicePort")
                            onDeviceFound?.invoke(deviceName, deviceIp, devicePort)
                        }
                    }
                })
                
                println("Discovery started")
            } catch (e: Exception) {
                println("Error starting discovery: ${e.message}")
            }
        }
    }
    
    fun stopDiscovery() {
        isDiscovering = false
        try {
            jmdns?.close()
        } catch (e: Exception) {
            println("Error stopping discovery: ${e.message}")
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

    // ✅ פונקציה חדשה: האזנה לקריאה מהטלפון אחרי סריקת QR
    fun startQrListener(onConnectRequest: (String) -> Unit) {
        thread {
            try {
                // פותח שרת האזנה בפורט 8083
                val serverSocket = java.net.ServerSocket(8083)
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
