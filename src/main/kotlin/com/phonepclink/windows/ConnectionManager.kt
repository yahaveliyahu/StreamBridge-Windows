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

    // לקוח HTTP להורדת קבצים
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // Callbacks - פונקציות שיופעלו כשקורים דברים
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onChatMessageReceived: ((ChatMessage) -> Unit)? = null // ✅ קבלת הודעה חדשה

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
            val uri = URI("ws://$phoneIP:8081") // חיבור לפורט ה-WebSocket של הטלפון

            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    println("WebSocket connected")
                    isConnected = true
                    // ✅ החזרת שם המחשב האמיתי (DESKTOP-XXXX)
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
                                    // ✅ תיקון קריטי: פתיחת תהליך חדש (Thread) להורדה
                                    // זה מונע מהתקשורת להיתקע ולהתנתק בזמן הורדת קובץ גדול
                                    Thread {
                                        handleIncomingFile(json)
                                    }.start()
                                } else if (msgType == "TEXT") {
                                    // הודעת טקסט רגילה - מטפלים בה מיד
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
//                            // מנסים להבין אם זו הודעת צ'אט
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
//                                    // 1. הורדת הקובץ (עכשיו הפונקציה קיימת למטה!)
//                                    val downloadedBytes = downloadFile(downloadPath)
//
//                                    if (downloadedBytes != null) {
//                                        // 2. שמירה בתיקיית ההורדות
//                                        val downloadsDir = File(System.getProperty("user.home"), "Downloads/StreamBridge")
//                                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
//
//                                        val destFile = File(downloadsDir, fileName)
//                                        destFile.writeBytes(downloadedBytes)
//
//                                        println("File saved to: ${destFile.absolutePath}")
//
//                                        // 3. הצגה בצ'אט
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
//                                // ✅ טיפול בטקסט רגיל
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

    // פונקציה שמכילה את הלוגיקה של ההורדה (נקראת מתוך ה-Thread)
    private fun handleIncomingFile(json: JSONObject) {
        try {
            val fileName = json.getString("fileName")
            val downloadPath = json.getString("downloadPath")
            val size = json.getLong("fileSize")
            val mime = json.optString("mimeType", "file/*")
//            val mime = json.optString("mimeType", "application/octet-stream")
            // מוריד את הקובץ לתיקיית זמנית בזיכרון (לא שומר אוטומטית בדיסק)
            val bytes = downloadFile(downloadPath)
            if (bytes != null) {
                // ✅ אבחון: לוודא שלא ירד HTML/שגיאה במקום תמונה
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

                // ✅ תיקון קריטי: שמירה עם סיומת נכונה כדי ש-Windows יזהה את הקובץ
                val extension = if (fileName.contains(".")) ".${fileName.substringAfterLast(".")}" else ".tmp"
                val nameWithoutExt = if (fileName.contains(".")) fileName.substringBeforeLast(".") else fileName
                // ✅ פתרון לבעיה 1: שמירה ל-Temp כדי לאפשר Open/Save
                val tempFile = File.createTempFile("sb_${nameWithoutExt}_", extension)
                tempFile.writeBytes(bytes)
                tempFile.deleteOnExit() // הקובץ יימחק כשהתוכנה תיסגר

                // שומרים בתיקיית temp זמנית כדי להציג בתוכנה
//                val tempDir = File(System.getProperty("java.io.tmpdir"), "StreamBridge")
//                if (!tempDir.exists()) tempDir.mkdirs()
//                val tempFile = File(tempDir, fileName)
//                tempFile.writeBytes(bytes)
//
//                // שומרים בזיכרון את המיקום הזמני כדי שנוכל אחר כך לבצע Save ל-Downloads בלחיצת משתמש
//                receivedTempFiles[fileName] = tempFile

//            // ביצוע ההורדה בפועל
//            val downloadedBytes = downloadFile(downloadPath)
//
//            if (downloadedBytes != null) {
//                // שמירה בתיקיית ההורדות במחשב
//                val downloadsDir = File(System.getProperty("user.home"), "Downloads/StreamBridge")
//                if (!downloadsDir.exists()) {
//                    downloadsDir.mkdirs()
//                }

//                val destFile = File(downloadsDir, fileName)
//                destFile.writeBytes(downloadedBytes)
//
//                println("File saved to: ${destFile.absolutePath}")

                // יצירת הודעה לתצוגה בצ'אט
                val msg = ChatMessage(
//                    text = null,
                    filePath = tempFile.absolutePath, // נתיב לקובץ הזמני
                    remotePath = downloadPath,
                    fileName = fileName,
                    fileSize = size,
                    type = determineMessageType(mime),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    isIncoming = true
                )
                // עדכון המסך (Callback)
                onChatMessageReceived?.invoke(msg)
            } else {
                println("Failed to download file: $fileName")
            }
        } catch (e: Exception) {
            println("Error in file download thread: ${e.message}")
            e.printStackTrace()
        }
    }



    // בקשת רשימת קבצים (HTTP)
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


    // ✅ בדיקה אם זה באמת JPEG לפי חתימה (magic bytes)
    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
                (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xFF) == 0xD8 &&
                (bytes[2].toInt() and 0xFF) == 0xFF

    // ✅ בדיקה אם זה נראה כמו HTML/שגיאה שחזרה במקום קובץ
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
        // ✅ בדיקת אבטחה: מניעת קריסה אם הנתיב שגוי
        if (!path.startsWith("/files/")) return null
        return try {
            // ✅ תיקון קריטי: שימוש ב-URI כדי לקודד עברית ורווחים בצורה חוקית
            // המבנה: scheme, authority (host:port), path, query, fragment
            val uri = URI("http", null, phoneIP, 8080, path, null, null)
            val url = uri.toASCIIString() // זה הופך "הגדרות" ל-%D7%94..

            // אם הנתיב כבר מתחיל ב-http, משתמשים בו, אחרת בונים אותו
//            val url = if (path.startsWith("http")) path else "http://$phoneIP:8080$path"

            // במקרה של רווחים או תווים מיוחדים ב-URL, עדיף לקודד (אבל בזהירות לא לקודד את כל ה-URL)
            // לגרסה פשוטה כרגע נשתמש ב-URL כמו שהוא (NanoHTTPD מטפל בזה בדרך כלל)

            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ✅ שליחת הודעת צ'אט מהמחשב לטלפון
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
        Thread { // גם העלאה כדאי לעשות ברקע
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

                // ✅ הכי חשוב: bytes, לא string!
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
//
//                if (code == 204) return null
//                if (!resp.isSuccessful) return null
//
//                val bytes = resp.body?.bytes() ?: return null
//                println("fetchCameraFrame <- bytes=${bytes.size}")
//
//                // אם זה לא תמונה, לא ננסה לטעון
//                if (ct?.startsWith("image/") != true) return null
//
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
