package com.phonepclink.windows

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import javafx.embed.swing.SwingFXUtils
import javafx.scene.image.Image
import org.json.JSONObject
import java.awt.Color
import java.awt.image.BufferedImage
import java.net.Inet4Address
import java.net.NetworkInterface


class QRCodeGenerator {

    fun generateQRCode(pcIp: String, pcPort: Int, pcName: String, size: Int = 300): Image {
        val data = JSONObject().apply {
            put("ip", pcIp)
            put("port", pcPort)
            put("name", pcName)
        }.toString()
        
        val hints = mapOf(
            EncodeHintType.MARGIN to 1
        )
        
        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, size, size, hints)
        
        val bufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bufferedImage.setRGB(x, y, if (bitMatrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        
        return SwingFXUtils.toFXImage(bufferedImage, null)
    }

    fun getLocalIPAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // Filters: No Loopback (127.0.0.1) and must be active (Up)
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()

                    // Looking for IPv4 address only (not IPv6)
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1" // Only if we really didn't find anything
    }
}
