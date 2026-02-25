package com.phonepclink.windows

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// סוגי ההודעות
enum class MessageType { TEXT, IMAGE, VIDEO, AUDIO, FILE }

// מבנה קובץ (לשימוש ברשימות קבצים אם צריך, מונע שגיאות בקבצים אחרים)
data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val type: String
) {
    val sizeStr: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", size / (1024.0 * 1024 * 1024))
        }
}

// מבנה של הודעה
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String? = null,           // תוכן טקסט
    val filePath: String? = null,       // נתיב קובץ מקומי
    val fileName: String? = null,
    val fileSize: Long = 0,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val isIncoming: Boolean,             // true = מהטלפון, false = מהמחשב (אני)
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

// מנהל ההיסטוריה - שומר וטוען מקובץ JSON
class HistoryManager {
    private val historyFile = File("chat_history.json")

    fun saveMessage(msg: ChatMessage) {
        val history = loadHistory().toMutableList()
        history.add(msg)

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
    }

    fun loadHistory(): List<ChatMessage> {
        if (!historyFile.exists()) return emptyList()

        return try {
            val jsonStr = historyFile.readText()
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<ChatMessage>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(ChatMessage(
                    id = obj.getString("id"),
                    text = if (obj.has("text")) obj.getString("text") else null,
                    filePath = if (obj.has("filePath")) obj.getString("filePath") else null,
                    fileName = if (obj.has("fileName")) obj.getString("fileName") else null,
                    fileSize = obj.optLong("fileSize", 0),
                    type = MessageType.valueOf(obj.getString("type")),
                    timestamp = obj.getLong("timestamp"),
                    isIncoming = obj.getBoolean("isIncoming")
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}