package com.phonepclink.windows

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.io.File
import java.net.InetAddress


class ConnectionManager {
    private var phoneIP: String = ""
    private var webSocketClient: WebSocketClient? = null

    // Rebuilt on each connect() call with the appropriate SSL trust policy.
    private var httpClient: OkHttpClient = buildHttpClient()

    // Callbacks - functions that will be triggered when things happen
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onChatMessageReceived: ((ChatMessage) -> Unit)? = null // Receive a new message
    var onDeleteMessage: ((Long) -> Unit)? = null              // Receive a delete by timestamp

    private var isConnected = false

    // ── Connection ───────────────────────────────────────────────────────────────

    fun connect(ip: String) {
        phoneIP = ip
        // Rebuild the HTTP client every time we connect so it always uses the
        // most recently saved certificate (in case the user just paired).
        httpClient = buildHttpClient()
        connectWebSocket()
    }

    fun disconnect() {
        try {
            webSocketClient?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isConnected = false
        onConnectionChanged?.invoke(false)
    }

    private fun connectWebSocket() {
        try {
            // ── Always WSS (encrypted) ─────────────────────────────────────────
            // If a cert was saved during pairing we use cert-pinned TLS.
            // If no cert yet (very first run) we use trust-all TLS — still
            // encrypted, just not yet authenticated.
            val sslContext = CertStore.buildPinnedSSLContext()
                ?: CertStore.buildTrustAllSSLContext()

            val wrappedFactory = CertStore.NoHostnameVerificationSocketFactory(
                sslContext.socketFactory
            )
            val uri = URI("wss://$phoneIP:8081") // Connecting to the phone's WebSocket secure port

            webSocketClient = object : WebSocketClient(uri) {

                override fun onSetSSLParameters(sslParameters: javax.net.ssl.SSLParameters) {
                    // Intentionally empty — disables java-websocket's forced HTTPS hostname
                    // verification. Authentication is handled by cert pinning in CertStore instead.
                }

                override fun onOpen(handshakedata: ServerHandshake?) {
                    // ── Verify TLS is actually active ────────────────────────────────────
                    val sslSocket = webSocketClient?.socket as? javax.net.ssl.SSLSocket
                    val tlsSession = sslSocket?.session
                    if (tlsSession != null) {
                        println("✅ TLS active")
                        println("   Protocol:     ${tlsSession.protocol}")      // e.g. TLSv1.3
                        println("   Cipher suite: ${tlsSession.cipherSuite}")   // e.g. TLS_AES_256_GCM_SHA384
                    } else {
                        println("⚠️ WARNING: TLS is NOT active — connection is unencrypted!")
                    }

                    // ─────────────────────────────────────────────────────────────────────

                    println("WebSocket secure connected (TLS)")
                    isConnected = true
                    // Returning the real computer name
                    val pcName = getComputerName()
                    val json = JSONObject().apply {
                        put("type", "HANDSHAKE")
                        put("name", pcName)
                    }
                    send(json.toString())

                    onConnectionChanged?.invoke(true)
                }

                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            if (it.trim().startsWith("{")) {
                                val json = JSONObject(it)
                                val msgType = json.optString("type", "TEXT")
                                val timestamp = json.optLong("timestamp", System.currentTimeMillis())

                                if (msgType == "FILE_TRANSFER") {
                                    // Opening a new process (Thread) for downloading
                                    // This prevents communication from getting stuck and disconnected while downloading a large file
                                    Thread {
                                        handleIncomingFile(json)
                                    }.start()
                                } else if (msgType == "TEXT") {
                                    // Regular text message - handled immediately
                                    val msg = ChatMessage(
                                        text = json.optString("text", ""),
                                        type = MessageType.TEXT,
                                        timestamp = timestamp,
                                        isIncoming = true
                                    )
                                    onChatMessageReceived?.invoke(msg)
                                } else if (msgType == "DELETE") {
                                    // Phone deleted a message – notify FileExplorer to remove it
                                    val ts = json.optLong("timestamp", -1L)
                                    if (ts >= 0) onDeleteMessage?.invoke(ts)
                                }
                            }
                        } catch (e: Exception) {
                            println("Error parsing message: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    println("WebSocket closed: $reason")
                    isConnected = false
                    onConnectionChanged?.invoke(false)
                }

                override fun onError(ex: Exception?) {
                    println("WebSocket error: ${ex?.message}")
                    isConnected = false
                    onConnectionChanged?.invoke(false)
                }
            }
            webSocketClient?.setSocketFactory(wrappedFactory)
            // 10-second TCP connect timeout — without this, java-websocket's default
            // is 0 (infinite).  If port 8081 is reachable at the TCP layer but the
            // TLS handshake stalls, onError would never fire and the connection hangs
            // silently.  connectBlocking() is called on a new thread so we don't block
            // the caller; any failure goes through the normal onError / catch paths.
            Thread {
                try {
                    val connected = webSocketClient?.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS) ?: false
                    if (!connected) {
                        println("WebSocket: connectBlocking timed out or returned false — check port 8081")
                        onConnectionChanged?.invoke(false)
                    }
                } catch (e: Exception) {
                    println("WebSocket: connectBlocking threw — ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                    onConnectionChanged?.invoke(false)
                }
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
            onConnectionChanged?.invoke(false)
        }
    }
//            webSocketClient?.connect()
//        } catch (e: Exception) {
//            e.printStackTrace()
//            onConnectionChanged?.invoke(false)
//        }
//    }

    // ── Incoming message handling ────────────────────────────────────────────────

    // A function that contains the download logic (called from the Thread)
    private fun handleIncomingFile(json: JSONObject) {
        try {
            val fileName = json.getString("fileName")
            val downloadPath = json.getString("downloadPath")
            val size = json.getLong("fileSize")
            val mime = json.optString("mimeType", "file/*")

            // Downloads the file to a temporary folder in memory (does not automatically save to disk)
            val bytes = downloadFile(downloadPath)
            if (bytes != null) {
                // Make sure no HTML/error is displayed instead of an image.
                if (mime.startsWith("image/")) {
                    val jpeg = isJpeg(bytes)
                    val htmlErr = looksLikeHtmlOrError(bytes)

                    if (!jpeg || htmlErr) {
                        val head = bytes.take(120).toByteArray().toString(Charsets.UTF_8)
                        println("⚠️ WARNING: Downloaded data may NOT be a real image!")
                        println("fileName=$fileName")
                        println("downloadPath=$downloadPath")
                        println("mime=$mime bytes=${bytes.size} isJpeg=$jpeg looksLikeHtmlOrError=$htmlErr")
                        println("first120=$head")
                    }
                }

                // Save with the correct extension so that Windows recognizes the file
                val extension = if (fileName.contains(".")) ".${fileName.substringAfterLast(".")}" else ".tmp"
                val nameWithoutExt = if (fileName.contains(".")) fileName.substringBeforeLast(".") else fileName
                // Save to Temp to enable Open/Save
                val tempFile = File.createTempFile("sb_${nameWithoutExt}_", extension)
                tempFile.writeBytes(bytes)
                tempFile.deleteOnExit() // The file will be deleted when the software closes

                val streamBridgeDir = File(System.getProperty("user.home"), "Documents/StreamBridge/ReceivedFiles")
                if (!streamBridgeDir.exists()) {
                    streamBridgeDir.mkdirs()
                }

                // Use timestamp + original name to avoid collisions
                val uniqueName = "${System.currentTimeMillis()}_$fileName"
                val permanentFile = File(streamBridgeDir, uniqueName)
                permanentFile.writeBytes(bytes)

                println("File saved permanently to: ${permanentFile.absolutePath}")

                // Create a message to display in chat
                val msg = ChatMessage(
                    filePath = permanentFile.absolutePath, // Path to the temporary file
                    remotePath = downloadPath,
                    fileName = fileName, // Keep original name for display
                    fileSize = size,
                    type = determineMessageType(mime),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    isIncoming = true
                )
                // Screen update (Callback)
                onChatMessageReceived?.invoke(msg)
            } else {
                println("Failed to download file: $fileName")
            }
        } catch (e: Exception) {
            println("Error in file download thread: ${e.message}")
            e.printStackTrace()
        }
    }

    // Checking if it is really a JPEG by signature (magic bytes)
    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
                (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xFF) == 0xD8 &&
                (bytes[2].toInt() and 0xFF) == 0xFF

    // Checking if it looks like HTML/error returned instead of a file
    private fun looksLikeHtmlOrError(bytes: ByteArray): Boolean {
        val head = bytes.take(200).toByteArray().toString(Charsets.UTF_8).lowercase()
        return head.contains("<html") ||
                head.contains("not found") ||
                head.contains("404") ||
                head.contains("error") ||
                head.contains("exception")
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds an OkHttpClient configured for TLS with the phone's certificate.
     * Uses cert pinning if a cert was saved during pairing; falls back to
     * trust-all TLS otherwise (still encrypted).
     */
    private fun buildHttpClient(): OkHttpClient {
        val trustManager = CertStore.buildPinnedTrustManager()
            ?: CertStore.buildTrustAllTrustManager()
        val sslContext   = CertStore.buildPinnedSSLContext()
            ?: CertStore.buildTrustAllSSLContext()

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            // Provide both socketFactory and trustManager so OkHttp's internal
            // validation uses our custom trust policy.
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The phone's cert has CN=StreamBridge, not an IP address, so standard
            // hostname verification would fail.  We authenticate via cert pinning.
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun downloadFile(path: String): ByteArray? {
        if (phoneIP.isEmpty()) return null
        // Preventing a crash if the path is incorrect
        if (!path.startsWith("/files/")) return null
        return try {
            // Using URI to encode Hebrew and spaces legally
            // המבנה: scheme, authority (host:port), path, query, fragment
            val uri = URI("https", null, phoneIP, 8080, path, null, null)
            val url = uri.toASCIIString() // This turns "Settings" into %D7%94

            // In case of spaces or special characters in the URL, it is better to encode
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun uploadFile(file: File, type: MessageType) {
        if (phoneIP.isEmpty()) return
        Thread { // Upload in the background
            try {
                val mediaType = "application/octet-stream".toMediaTypeOrNull()
                val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                    .addFormDataPart("type", type.name)
                    .build()

                val encoded = java.net.URLEncoder.encode(file.name, "UTF-8")
                val url = "https://$phoneIP:8080/upload?name=$encoded"

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()


                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) println("Upload failed: ${response.code}")
                }
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    fun fetchCameraFrame(): ByteArray? {
        if (phoneIP.isEmpty()) return null

        val url = "https://$phoneIP:8080/camera"
//        println("fetchCameraFrame -> GET $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (resp.code == 204) return null
                if (!resp.isSuccessful) {
                    println("fetchCameraFrame HTTP ${resp.code}")
                    return null
                }

                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) null else bytes
            }
        } catch (e: Exception) {
            println("fetchCameraFrame error: ${e.message}")
            null
        }
    }

    // ── Outgoing messages ────────────────────────────────────────────────────────

    // Send a chat message from your computer to your phone
    fun sendChatMessage(msg: ChatMessage) {
        if (!isConnected || webSocketClient == null) return

        try {
            webSocketClient?.send(
                JSONObject().apply {
                    put("text", msg.text)
                    put("type", msg.type.name)
                    put("timestamp", msg.timestamp)
                }.toString()
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun sendDeleteMessage(timestamp: Long) {
        if (!isConnected || webSocketClient == null) return
        try {
            webSocketClient?.send(
                JSONObject().apply {
                    put("type", "DELETE")
                    put("timestamp", timestamp)
                }.toString()
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ── Utilities ────────────────────────────────────────────────────────────────

    private fun determineMessageType(mime: String): MessageType {
        return when {
            mime.startsWith("image/") -> MessageType.IMAGE
            mime.startsWith("video/") -> MessageType.VIDEO
            mime.startsWith("audio/") -> MessageType.AUDIO
            else -> MessageType.FILE
        }
    }

    fun isConnected(): Boolean = isConnected

    companion object {
        /**
         * Returns the real Windows computer name (e.g. "DESKTOP-025G0LF").
         * Priority order:
         *   1. COMPUTERNAME env var  — set directly by Windows, always correct
         *   2. InetAddress.localHost — works on most machines but can fail
         *   3. "StreamBridge-Windows"                  — last-resort fallback
         */
        fun getComputerName(): String =
            System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: try { InetAddress.getLocalHost().hostName } catch (_: Exception) { null }
                ?: "StreamBridge-Windows"
    }
}