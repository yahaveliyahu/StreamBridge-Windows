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

    // HTTP client for downloading files
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // Callbacks - functions that will be triggered when things happen
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onChatMessageReceived: ((ChatMessage) -> Unit)? = null // Receive a new message

    private var isConnected = false

    fun connect(ip: String) {
        phoneIP = ip
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
            val uri = URI("ws://$phoneIP:8081") // Connecting to the phone's WebSocket port

            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    println("WebSocket connected")
                    isConnected = true
                    // Returning the real computer name (DESKTOP-XXXX)
                    val pcName = try { InetAddress.getLocalHost().hostName } catch (e:Exception) { "PC" }
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
//                                        type = try { MessageType.valueOf(msgTypeStr) } catch(e:Exception){ MessageType.TEXT },
                                        timestamp = timestamp,
                                        isIncoming = true
                                    )
                                    onChatMessageReceived?.invoke(msg)
                                }
                            }
                        } catch (e: Exception) {
                            println("Error parsing message: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
//                override fun onMessage(message: String?) {
//                    message?.let {
//                        try {
//                            if (it.trim().startsWith("{")) {
//                                val json = JSONObject(it)
//                                val msgTypeStr = json.optString("type", "TEXT")
//
//                                if (msgTypeStr == "FILE_TRANSFER") {
//                                    val fileName = json.getString("fileName")
//                                    val downloadPath = json.getString("downloadPath")
//                                    val size = json.getLong("fileSize")
//                                    val mime = json.optString("mimeType", "application/octet-stream")
//
//                                    val downloadedBytes = downloadFile(downloadPath)
//
//                                    if (downloadedBytes != null) {
//                                        val downloadsDir = File(System.getProperty("user.home"), "Downloads/StreamBridge")
//                                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
//
//                                        val destFile = File(downloadsDir, fileName)
//                                        destFile.writeBytes(downloadedBytes)
//
//                                        println("File saved to: ${destFile.absolutePath}")
//
//                                        val msg = ChatMessage(
//                                            text = null,
//                                            filePath = destFile.absolutePath,
//                                            fileName = fileName,
//                                            fileSize = size,
//                                            type = determineMessageType(mime),
//                                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
//                                            isIncoming = true
//                                        )
//                                        onChatMessageReceived?.invoke(msg)
//                                    }
//                                }
//                                else {
//                                    val msg = ChatMessage(
//                                        text = json.optString("text", ""),
//                                        type = try { MessageType.valueOf(msgTypeStr) } catch(e:Exception){ MessageType.TEXT },
//                                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
//                                        isIncoming = true
//                                    )
//                                    onChatMessageReceived?.invoke(msg)
//                                }
//                            }
//                        } catch (e: Exception) {
//                            println("Error parsing message: ${e.message}")
//                            e.printStackTrace()
//                        }
//                    }
//                }

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
            webSocketClient?.connect()
        } catch (e: Exception) {
            e.printStackTrace()
            onConnectionChanged?.invoke(false)
        }
    }

    // A function that contains the download logic (called from the Thread)
    private fun handleIncomingFile(json: JSONObject) {
        try {
            val fileName = json.getString("fileName")
            val downloadPath = json.getString("downloadPath")
            val size = json.getLong("fileSize")
            val mime = json.optString("mimeType", "file/*")
//            val mime = json.optString("mimeType", "application/octet-stream")
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

                println("✅ File saved permanently to: ${permanentFile.absolutePath}")

//                val tempDir = File(System.getProperty("java.io.tmpdir"), "StreamBridge")
//                if (!tempDir.exists()) tempDir.mkdirs()
//                val tempFile = File(tempDir, fileName)
//                tempFile.writeBytes(bytes)
//                receivedTempFiles[fileName] = tempFile
//            val downloadedBytes = downloadFile(downloadPath)
//            if (downloadedBytes != null) {
//                val downloadsDir = File(System.getProperty("user.home"), "Downloads/StreamBridge")
//                if (!downloadsDir.exists()) {
//                    downloadsDir.mkdirs()
//                }
//                val destFile = File(downloadsDir, fileName)
//                destFile.writeBytes(downloadedBytes)
//                println("File saved to: ${destFile.absolutePath}")

                // Create a message to display in chat
                val msg = ChatMessage(
//                    text = null,
                    filePath = permanentFile.absolutePath, // נתיב לקובץ הזמני
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



//    fun fetchFileList(): String? {
//        if (phoneIP.isEmpty()) return null
//        return try {
//            val request = Request.Builder()
//                .url("http://$phoneIP:8080/files")
//                .build()
//            httpClient.newCall(request).execute().use { response ->
//                if (response.isSuccessful) response.body?.string() else null
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            null
//        }
//    }


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

    fun downloadFile(path: String): ByteArray? {
        if (phoneIP.isEmpty()) return null
        // Preventing a crash if the path is incorrect
        if (!path.startsWith("/files/")) return null
        return try {
            // Using URI to encode Hebrew and spaces legally
            // המבנה: scheme, authority (host:port), path, query, fragment
            val uri = URI("http", null, phoneIP, 8080, path, null, null)
            val url = uri.toASCIIString() // This turns "Settings" into %D7%94..

            // אם הנתיב כבר מתחיל ב-http, משתמשים בו, אחרת בונים אותו
//            val url = if (path.startsWith("http")) path else "http://$phoneIP:8080$path"

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

    // Send a chat message from your computer to your phone
    fun sendChatMessage(msg: ChatMessage) {
        if (!isConnected || webSocketClient == null) return

        val json = JSONObject().apply {
            put("text", msg.text)
            put("type", msg.type.name)
            put("timestamp", msg.timestamp)
        }

        try {
            webSocketClient?.send(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
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
//                val request = Request.Builder().url("http://$phoneIP:8080/upload").post(requestBody).build()
                val encoded = java.net.URLEncoder.encode(file.name, "UTF-8")
                val url = "http://$phoneIP:8080/upload?name=$encoded"

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

        val url = "http://$phoneIP:8080/camera"
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

//                val code = resp.code
//                val ct = resp.header("Content-Type")
//                println("fetchCameraFrame <- code=$code contentType=$ct")
//                if (code == 204) return null
//                if (!resp.isSuccessful) return null
//                val bytes = resp.body?.bytes() ?: return null
//                println("fetchCameraFrame <- bytes=${bytes.size}")
//                if (ct?.startsWith("image/") != true) return null
//                bytes
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            null
//        }
//    }

//            httpClient.newCall(request).execute().use { it.body?.bytes() }
//        } catch (e: Exception) { null }
//    }

    private fun determineMessageType(mime: String): MessageType {
        return when {
            mime.startsWith("image/") -> MessageType.IMAGE
            mime.startsWith("video/") -> MessageType.VIDEO
            mime.startsWith("audio/") -> MessageType.AUDIO
            else -> MessageType.FILE
        }
    }

    fun isConnected(): Boolean = isConnected
}





//class ConnectionManager {
//    private var phoneIP: String = ""
//    private var webSocketClient: WebSocketClient? = null
//    private val httpClient = OkHttpClient.Builder()
//        .connectTimeout(5, TimeUnit.SECONDS)
//        .readTimeout(10, TimeUnit.SECONDS)
//        .build()
//
//    var onConnectionChanged: ((Boolean) -> Unit)? = null
//    var onMessageReceived: ((String) -> Unit)? = null
//
//    private var isConnected = false
//
//    fun connect(ip: String) {
//        phoneIP = ip
//        connectWebSocket()
//    }
//
//    private fun connectWebSocket() {
//        try {
//            val uri = URI("ws://$phoneIP:8081")
//
//            webSocketClient = object : WebSocketClient(uri) {
//                override fun onOpen(handshakedata: ServerHandshake?) {
//                    println("WebSocket connected")
//                    isConnected = true
//                    onConnectionChanged?.invoke(true)
//                }
//
//                override fun onMessage(message: String?) {
//                    message?.let {
//                        println("Received: $it")
//                        onMessageReceived?.invoke(it)
//                    }
//                }
//
//                override fun onClose(code: Int, reason: String?, remote: Boolean) {
//                    println("WebSocket closed: $reason")
//                    isConnected = false
//                    onConnectionChanged?.invoke(false)
//                }
//
//                override fun onError(ex: Exception?) {
//                    println("WebSocket error: ${ex?.message}")
//                    isConnected = false
//                    onConnectionChanged?.invoke(false)
//                }
//            }
//
//            webSocketClient?.connect()
//        } catch (e: Exception) {
//            println("Error connecting: ${e.message}")
//            onConnectionChanged?.invoke(false)
//        }
//    }
//
//    fun disconnect() {
//        webSocketClient?.close()
//        isConnected = false
//        onConnectionChanged?.invoke(false)
//    }
//
//    fun sendCommand(command: String) {
//        if (isConnected) {
//            webSocketClient?.send(command)
//        }
//    }
//
//    fun fetchCameraFrame(): ByteArray? {
//        if (!isConnected) return null
//
//        return try {
//            val request = Request.Builder()
//                .url("http://$phoneIP:8080/camera")
//                .build()
//
//            val response = httpClient.newCall(request).execute()
//            if (response.isSuccessful) {
//                response.body?.bytes()
//            } else {
//                null
//            }
//        } catch (e: Exception) {
//            println("Error fetching camera frame: ${e.message}")
//            null
//        }
//    }
//
//    fun fetchFileList(): String? {
//        if (!isConnected) return null
//
//        return try {
//            val request = Request.Builder()
//                .url("http://$phoneIP:8080/file-list")
//                .build()
//
//            val response = httpClient.newCall(request).execute()
//            if (response.isSuccessful) {
//                response.body?.string()
//            } else {
//                null
//            }
//        } catch (e: Exception) {
//            println("Error fetching file list: ${e.message}")
//            null
//        }
//    }
//
//    fun downloadFile(filePath: String): ByteArray? {
//        if (!isConnected) return null
//
//        return try {
//            val request = Request.Builder()
//                .url("http://$phoneIP:8080/files$filePath")
//                .build()
//
//            val response = httpClient.newCall(request).execute()
//            if (response.isSuccessful) {
//                response.body?.bytes()
//            } else {
//                null
//            }
//        } catch (e: Exception) {
//            println("Error downloading file: ${e.message}")
//            null
//        }
//    }
//
//    fun isConnected(): Boolean = isConnected
//
//    fun getPhoneIP(): String = phoneIP
//}