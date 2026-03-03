package com.phonepclink.windows

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// Types of messages
enum class MessageType { TEXT, IMAGE, VIDEO, AUDIO, FILE }

// מבנה קובץ (לשימוש ברשימות קבצים אם צריך, מונע שגיאות בקבצים אחרים)
//data class FileItem(
//    val name: String,
//    val path: String,
//    val size: Long,
//    val type: String
//) {
//    val sizeStr: String
//        get() = when {
//            size < 1024 -> "$size B"
//            size < 1024 * 1024 -> "${size / 1024} KB"
//            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
//            else -> String.format("%.2f GB", size / (1024.0 * 1024 * 1024))
//        }
//}

// Message structure
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String? = null,           // Text content
    val filePath: String? = null,       // Local file path
    val fileName: String? = null,
    val fileSize: Long = 0,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val isIncoming: Boolean,             // true = from the phone, false = from the computer (me)
    val remotePath: String? = null
) {
    val timeStr: String
        get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    val sizeStr: String
        get() = when {
            fileSize <= 0 -> ""
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
            else -> String.format("%.1f MB", fileSize / (1024.0 * 1024.0))
        }
}

// History Manager - Saves and loads from JSON file
class HistoryManager {
    private val historyFile = File("chat_history.json")

    companion object {
        private const val HISTORY_TTL_DAYS = 30
    }

    // Calculate cutoff timestamp for old messages
    private fun historyCutoffMillis(): Long {
        return System.currentTimeMillis() - HISTORY_TTL_DAYS * 24L * 60L * 60L * 1000L
    }

    fun saveMessage(msg: ChatMessage) {
        val history = loadHistory().toMutableList()
        history.add(msg)

        // Filter out messages older than 30 days before saving
        val cutoff = historyCutoffMillis()
        val filteredHistory = history.filter { it.timestamp >= cutoff }

        val jsonArray = JSONArray()
        filteredHistory.forEach { item ->
        val json = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("filePath", item.filePath)
                put("fileName", item.fileName)
                put("fileSize", item.fileSize)
                put("type", item.type.name)
                put("timestamp", item.timestamp)
                put("isIncoming", item.isIncoming)
            }
            jsonArray.put(json)
        }
        historyFile.writeText(jsonArray.toString())
    }

    fun loadHistory(): List<ChatMessage> {
        if (!historyFile.exists()) return emptyList()

        return try {
            val jsonStr = historyFile.readText()
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<ChatMessage>()

            // Filter messages older than 30 days when loading
            val cutoff = historyCutoffMillis()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val timestamp = obj.getLong("timestamp")

                // Skip messages older than 30 days
                if (timestamp < cutoff) continue

                list.add(ChatMessage(
                    id = obj.getString("id"),
                    text = if (obj.has("text")) obj.getString("text") else null,
                    filePath = if (obj.has("filePath")) obj.getString("filePath") else null,
                    fileName = if (obj.has("fileName")) obj.getString("fileName") else null,
                    fileSize = obj.optLong("fileSize", 0),
                    type = MessageType.valueOf(obj.getString("type")),
                    timestamp = timestamp,
                    isIncoming = obj.getBoolean("isIncoming")
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Manual cleanup method - can be called periodically
    fun cleanOldMessages() {
        val history = loadHistory() // This already filters old messages

        // Rewrite the file with only recent messages
        val jsonArray = JSONArray()
        history.forEach { item ->
            val json = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("filePath", item.filePath)
                put("fileName", item.fileName)
                put("fileSize", item.fileSize)
                put("type", item.type.name)
                put("timestamp", item.timestamp)
                put("isIncoming", item.isIncoming)
            }
            jsonArray.put(json)
        }
        historyFile.writeText(jsonArray.toString())
        println("Chat history cleaned: ${history.size} messages kept (older than $HISTORY_TTL_DAYS days removed)")
    }

    // Delete specific message from history
    fun deleteMessage(msg: ChatMessage) {
        val history = loadHistory().toMutableList()
        history.removeAll { it.id == msg.id }

        val jsonArray = JSONArray()
        history.forEach { item ->
            val json = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("filePath", item.filePath)
                put("fileName", item.fileName)
                put("fileSize", item.fileSize)
                put("type", item.type.name)
                put("timestamp", item.timestamp)
                put("isIncoming", item.isIncoming)
            }
            jsonArray.put(json)
        }
        historyFile.writeText(jsonArray.toString())
        println("Message deleted from history: ${msg.id}")
    }
}