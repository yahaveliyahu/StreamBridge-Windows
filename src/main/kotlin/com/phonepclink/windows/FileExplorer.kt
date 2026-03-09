package com.phonepclink.windows

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.stage.FileChooser
import javafx.geometry.Pos
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.geometry.Side
import javafx.scene.input.KeyCode
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.nio.file.Paths

import java.awt.Desktop
import java.io.File
import java.util.*

import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.input.MouseButton

import javafx.animation.FadeTransition
import javafx.scene.control.Label
import javafx.stage.Popup
import javafx.stage.Window
import javafx.util.Duration

import com.drew.imaging.ImageMetadataReader

import com.drew.metadata.exif.ExifDirectoryBase
import javafx.scene.layout.StackPane

import javafx.scene.Group

class FileExplorer(private val connectionManager: ConnectionManager) {

    private val chatListView = ListView<ChatMessage>()
    private val messageInput = TextField()
    private val sendButton = Button("➤")
    private val attachButton = Button("+")

    // Managers
    private val historyManager = HistoryManager()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    init {
        setupChatLayout()
        loadHistory()

        // Clean old messages on app start
        historyManager.cleanOldMessages()
        println("Chat history cleaned on startup")

        // Connecting to ConnectionManager: Listening for incoming messages
        connectionManager.onChatMessageReceived = { msg ->
            Platform.runLater {
                chatListView.items.add(msg)
                chatListView.scrollTo(chatListView.items.size - 1)
                // Also saves incoming messages in history
                historyManager.saveMessage(msg)
            }
        }

        // Setting button actions
        sendButton.setOnAction { sendMessage() }
        messageInput.setOnKeyPressed { if (it.code == KeyCode.ENTER) sendMessage() }
        attachButton.setOnAction { showAttachMenu() }
    }

    fun getView(): BorderPane {
        val root = BorderPane()
        root.style = "-fx-background-color: #FFFFFF;"

        // Chat list (center)
        root.center = chatListView

        // Writing area (bottom)
        val bottomBar = HBox(10.0).apply {
            padding = Insets(10.0, 15.0, 10.0, 15.0)
            alignment = Pos.CENTER
            style = "-fx-background-color: #F2F2F2; -fx-border-color: #E0E0E0; -fx-border-width: 1 0 0 0;"

            // Plus button design
            attachButton.style = """
                -fx-background-color: transparent; 
                -fx-font-size: 24px; 
                -fx-text-fill: #555; 
                -fx-cursor: hand;
            """.trimIndent()

            // Text field formatting (round)
            messageInput.promptText = "Enter a message..."
            messageInput.style = """
                -fx-background-color: white; 
                -fx-background-radius: 20; 
                -fx-border-radius: 20; 
                -fx-border-color: #DDD; 
                -fx-padding: 8 15 8 15;
            """.trimIndent()

            // Submit button design
            sendButton.style = """
                -fx-background-color: transparent; 
                -fx-text-fill: #0078D7; 
                -fx-font-size: 20px; 
                -fx-cursor: hand;
            """.trimIndent()

            children.addAll(attachButton, messageInput.apply { HBox.setHgrow(this, Priority.ALWAYS) }, sendButton)
        }
        root.bottom = bottomBar

        return root
    }

    private fun setupChatLayout() {
        chatListView.setCellFactory {
            object : ListCell<ChatMessage>() {
                override fun updateItem(item: ChatMessage?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        graphic = null
                        style = "-fx-background-color: transparent;"
                    } else {
                        val vbox = VBox(5.0)
                        vbox.alignment = Pos.CENTER

                        // Separation of days
                        val index = index
                        var showDate = false
                        if (index == 0) showDate = true
                        else if (index > 0 && index < chatListView.items.size) {
                            val prev = chatListView.items[index - 1]
                            val fmt = SimpleDateFormat("yyyyMMdd")
                            if (fmt.format(Date(prev.timestamp)) != fmt.format(Date(item.timestamp))) {
                                showDate = true
                            }
                        }

                        if (showDate) {
                            val dateLbl = Label(SimpleDateFormat("EEEE, d MMMM yyyy").format(Date(item.timestamp)))
                            dateLbl.style = "-fx-font-size: 11px; -fx-text-fill: gray; -fx-padding: 5;"
                            vbox.children.add(dateLbl)
                        }

                        // The bubble itself
                        val bubble = createBubble(item)
                        val alignBox = HBox(bubble)
                        alignBox.alignment = if (item.isIncoming) Pos.CENTER_LEFT else Pos.CENTER_RIGHT

                        vbox.children.add(alignBox)
                        graphic = vbox
                        style = "-fx-background-color: transparent; -fx-padding: 5 10 5 10;"
//                        graphic = createBubble(item)
//                        style = "-fx-background-color: transparent; -fx-padding: 5 10 5 10;"
//                        alignment = if (item.isIncoming) Pos.CENTER_LEFT else Pos.CENTER_RIGHT
                    }
                }
            }
        }

        // Removing lines and background
        chatListView.style = "-fx-background-color: white; -fx-control-inner-background: white;"
    }

    // Creating the bubble (blue to the right, gray to the left)
    private fun createBubble(msg: ChatMessage): VBox {
        val isMe = !msg.isIncoming // I (the computer) = not logged in

        val bubble = VBox(4.0).apply {
            padding = Insets(10.0, 14.0, 10.0, 14.0)
            maxWidth = 350.0

            style = if (isMe) {
                // My design (computer) - blue
                """
                -fx-background-color: #0078D7; 
                -fx-background-radius: 18 18 2 18; 
                -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 2, 0, 0, 1);
                """.trimIndent()
            } else {
                // His design (phone) - light gray
                """
                -fx-background-color: #F2F2F2; 
                -fx-background-radius: 18 18 18 2;
                """.trimIndent()
            }
        }

        // Message content
        if (msg.type == MessageType.TEXT) {
            val text = Text(msg.text ?: "")
            text.fill = if (isMe) Color.WHITE else Color.BLACK
            text.style = "-fx-font-size: 14px;"
            val flow = TextFlow(text)
            bubble.children.add(flow)

            // Right-click context menu for text messages
            bubble.setOnContextMenuRequested { event ->
                showTextContextMenu(msg, bubble, event.screenX, event.screenY)
                event.consume()
            }
        } else {
            // File/Image
            val fileBox = HBox(10.0).apply { alignment = Pos.CENTER_LEFT }

            // IMAGE PREVIEW: Show actual image thumbnail for image files
            val thumbnail: javafx.scene.Node = if (isImageFile(msg.fileName) && msg.filePath != null) {
                // Create image preview
                createImageThumbnail(msg.filePath)
            } else {
                // Show emoji icon for non-images
                Label(getEmojiForType(msg.type)).apply {
                    style = "-fx-font-size: 24px;"
                }
            }

//            val icon = Label(getEmojiForType(msg.type)).apply {
//                style = "-fx-font-size: 24px;"
//            }

            val infoBox = VBox(2.0).apply {
                val nameLbl = Label(msg.fileName).apply {
                    style = "-fx-font-weight: bold; -fx-font-size: 13px;"
                    textFill = if (isMe) Color.WHITE else Color.BLACK
                }
                val sizeLbl = Label(msg.sizeStr).apply {
                    style = "-fx-font-size: 11px;"
                    textFill = if (isMe) Color.rgb(220, 220, 220) else Color.GRAY
                }
                children.addAll(nameLbl, sizeLbl)
            }

            fileBox.children.addAll(thumbnail, infoBox)
            bubble.children.add(fileBox)

            // Open and save buttons
//            val btnBox = HBox(10.0).apply {
//                alignment = Pos.CENTER
//                padding = Insets(5.0, 0.0, 0.0, 0.0)
//            }

//            val openBtn = Button("Open").apply {
//                style = "-fx-font-size: 10px; -fx-background-radius: 5; -fx-cursor: hand;"
//                setOnAction {
//                    val path = msg.filePath ?: return@setOnAction
//                    try {
//                        Desktop.getDesktop().open(File(path))
//                    } catch (e: Exception) {
//                        e.printStackTrace()
//                    }
//                }
//            }

//            val saveBtn = Button("Save As").apply {
//                style = "-fx-font-size: 10px; -fx-background-radius: 5; -fx-cursor: hand;"
//
//                setOnAction {
//                    val originalName = msg.fileName ?: "file_${System.currentTimeMillis()}"
//                    val chooser = FileChooser().apply { initialFileName = msg.fileName }
//                    val dest = chooser.showSaveDialog(chatListView.scene.window) ?: return@setOnAction
//
//                    try {
//                        var finalDest = dest
//
//                        // If the user deletes an extension – it is automatically restored.
//                        if (!finalDest.name.contains(".") && originalName.contains(".")) {
//                            val ext = originalName.substringAfterLast(".")
//
////                        if (!finalDest.name.contains(".") && msg.fileName.contains(".")) {
////                            val ext = msg.fileName.substringAfterLast(".")
//                            finalDest = File(finalDest.parentFile, "${finalDest.name}.$ext")
//                        }
//                        val local = msg.filePath?.let { File(it) }
//
//                        // If there is a local file and it exists – normal copy
//                        if (local != null && local.exists()) {
//                            local.copyTo(finalDest, overwrite = true)
//                            println("Saved locally: ${finalDest.absolutePath}")
//                            return@setOnAction
//                        }
//
//                        // Otherwise – re-download from the phone and save to the destination
//                        val remote = msg.remotePath
//                        if (remote.isNullOrBlank()) {
//                            println("No local file and no remotePath to download")
//                            return@setOnAction
//                        }
//
//                        // Download in the background to avoid UI crashes
//                        scope.launch {
//                            try {
//                                val bytes = connectionManager.downloadFile(remote)
//                                if (bytes != null) {
//                                    finalDest.writeBytes(bytes)
//                                    println("Saved by re-download: ${finalDest.absolutePath}")
//                                } else {
//                                    println("Failed to re-download for saving. remotePath=$remote")
//                                }
//                            } catch (e: Exception) {
//                                e.printStackTrace()
//                            }
//                        }
//
//                    } catch (e: Exception) {
//                        e.printStackTrace()
//                    }
//                }
//            }

//            if (msg.filePath != null) {
//                        val chooser = FileChooser()
//                        chooser.initialFileName = msg.fileName
//                        val dest = chooser.showSaveDialog(chatListView.scene.window)
//                        if (dest != null) {
//                            File(msg.filePath).copyTo(dest, overwrite = true)
//                        }
//                    }
//                }
//            }
//            btnBox.children.addAll(openBtn, saveBtn)
//            btnBox.children.add(saveBtn)
//            bubble.children.add(btnBox)

        // Right-click context menu for file messages
        bubble.setOnContextMenuRequested { event ->
            showFileContextMenu(msg, bubble, event.screenX, event.screenY)
            event.consume()
        }
    }

        // Make bubble clickable on left-click
        bubble.setOnMouseClicked { event ->
            // Only respond to left click (primary button)
            if (event.button == MouseButton.PRIMARY && event.clickCount == 1) {
                val path = msg.filePath ?: return@setOnMouseClicked
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        Alert(javafx.scene.control.Alert.AlertType.ERROR).apply {
                            title = "File Not Found"
                            contentText = "File no longer exists at:\n$path"
                            showAndWait()
                        }
                        return@setOnMouseClicked
                    }

                    // VCF contacts: show a parsed dialog instead of opening "People" (which crashes)
//                        try {
//                            // הנתיב הסטנדרטי ב-Windows ל-WAB
//                            val wabPath = "C:\\Program Files\\Common Files\\System\\wab.exe"
//                            ProcessBuilder(wabPath, file.absolutePath).start()
//                            println("Opened VCF with wab.exe")
//                        } catch (e: Exception) {
//                            println("wab.exe not found or failed, falling back to internal dialog")
//                        showVcfDialog(file)
//                        }
                    // Try all known wab.exe locations before falling back to built-in dialog
                    if (file.extension.lowercase() == "vcf") {
                        showVcfDialog(file)
                    } else {
                        Desktop.getDesktop().open(file)
                        println("Opened file: $path")
                    }
                } catch (e: Exception) {
                    println("Failed to open file: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        // Change cursor to hand when hovering over bubble
        bubble.style += "-fx-cursor: hand;"

        // time
        val timeLbl = Label(msg.timeStr).apply {
            style = "-fx-font-size: 10px;"
            textFill = if (isMe) Color.rgb(220, 220, 220) else Color.GRAY
            alignment = Pos.BOTTOM_RIGHT
            maxWidth = Double.MAX_VALUE
        }

        return VBox(2.0).apply {
            children.addAll(bubble, timeLbl)
        }
    }

    // Helper function to detect image files
    private fun isImageFile(fileName: String?): Boolean {
        if (fileName == null) return false
        val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in imageExtensions
    }

    // Create image thumbnail for preview
    private fun createImageThumbnail(filePath: String): javafx.scene.Node {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                // File doesn't exist, return placeholder
                return Label("🖼️").apply {
                    style = "-fx-font-size: 24px;"
                    minWidth = 80.0
                    minHeight = 80.0
                    alignment = Pos.CENTER
                }
            }

            // Get the exact rotation angle
            val rotationAngle = getExifRotation(file)

            // Load image: Set the last parameter to 'false' (disables background loading)
            // This ensures the image is fully loaded before we calculate bounds and rotation
            val image = Image(file.toURI().toString(), 120.0, 120.0, true, true, false)
            val imageView = ImageView(image).apply {
                isPreserveRatio = true
                isSmooth = true
                rotate = rotationAngle // Apply the rotation
            }

            // Wrap in a Group.
            // JavaFX layouts ignore rotated dimensions unless wrapped in a Group!
            val rotatedGroup = Group(imageView)

            // Wrap the ImageView in a StackPane!
            // This forces JavaFX to respect the rotated dimensions in the HBox layout.
            StackPane(rotatedGroup).apply {
                style = "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: rgba(0,0,0,0.1); -fx-border-width: 1;"
                padding = Insets(2.0) // Keeps the image from clipping the border
            }

        } catch (e: Exception) {
            println("Error loading image thumbnail: ${e.message}")
            // Fallback to icon on error
            Label("🖼️").apply {
                style = "-fx-font-size: 24px;"
                minWidth = 80.0
                minHeight = 80.0
                alignment = Pos.CENTER
            }
        }
    }

    // Helper function to extract EXIF rotation
    private fun getExifRotation(file: File): Double {
        try {
            val metadata = ImageMetadataReader.readMetadata(file)

            // Loop through ALL directories because Android phones store this in different places
            for (directory in metadata.directories) {
                if (directory.containsTag(ExifDirectoryBase.TAG_ORIENTATION)) {
                    return when (directory.getInt(ExifDirectoryBase.TAG_ORIENTATION)) {
                        3 -> 180.0
                        6 -> 90.0
                        8 -> 270.0
                        else -> 0.0
                    }
                }
            }
        } catch (e: Exception) {
            println("Could not read EXIF metadata: ${e.message}")
        }
        return 0.0
    }

//        val container = VBox(2.0).apply {
//            children.addAll(bubble, timeLbl)
//            alignment = if (isMe) Pos.CENTER_RIGHT else Pos.CENTER_LEFT
//        }
//        return container
//

    private fun showAttachMenu() {
        val menu = ContextMenu()

        val items = listOf(
            "🖼️ תמונה" to "*.png,*.jpg,*.jpeg,*.gif",
            "🎬 וידאו" to "*.mp4,*.avi,*.mkv,*.mov",
            "🎵 שמע" to "*.mp3,*.wav,*.aac",
            "📁 קובץ" to "*.*"
        )

        items.forEach { (label, ext) ->
            val menuItem = MenuItem(label)
            menuItem.style = "-fx-font-size: 14px; -fx-padding: 5 10 5 10;"
            menuItem.setOnAction { chooseAndSendFile(ext, getTypeFromExt(label)) }
            menu.items.add(menuItem)
        }

        menu.show(attachButton, Side.TOP, 0.0, 0.0)
    }

    private fun getTypeFromExt(label: String): MessageType {
        return when {
            label.contains("תמונה") -> MessageType.IMAGE
            label.contains("וידאו") -> MessageType.VIDEO
            label.contains("שמע") -> MessageType.AUDIO
            else -> MessageType.FILE
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.trim()
        if (text.isEmpty()) return
        // Create a message
        val msg = ChatMessage(text = text, isIncoming = false, type = MessageType.TEXT)
        // Save and add to screen
        addMessageToChat(msg)
        // Field cleaning
        messageInput.clear()
        // Send to phone (future logic)
        connectionManager.sendChatMessage(msg)
    }

    private fun chooseAndSendFile(extensions: String, type: MessageType) {
        val chooser = FileChooser()
        // Opening folder by type
        chooser.initialDirectory = getDefaultDir(type)
        // Filter by suffixes (fix splitting)
        if (extensions != "*.*") {
            // Converts the string "*.jpg;*.png" to a list
            val extList = extensions.split(",").map { it.trim() }
            chooser.extensionFilters.add(FileChooser.ExtensionFilter("Files", extList))
        } else {
            chooser.extensionFilters.add(FileChooser.ExtensionFilter("All Files", "*.*"))
        }

        val file = chooser.showOpenDialog(chatListView.scene.window) ?: return
        // Create an outgoing file message
        val msg = ChatMessage(
            filePath = file.absolutePath,
            fileName = file.name,
            fileSize = file.length(),
            type = type,
            isIncoming = false
        )

        addMessageToChat(msg)

        // Performing the actual sending in the background
        scope.launch {
            connectionManager.uploadFile(file, type)
        }
    }

    private fun getDefaultDir(type: MessageType): File {
        val home = System.getProperty("user.home")

        // “Standard” libraries in Windows
        val dir = when (type) {
            MessageType.IMAGE -> Paths.get(home, "Pictures").toFile()
            MessageType.VIDEO -> Paths.get(home, "Videos").toFile()
            MessageType.AUDIO -> Paths.get(home, "Music").toFile()
            else -> Paths.get(home, "Documents").toFile()
        }

        // If it does not exist (rare cases) – we will fall to home
        return if (dir.exists() && dir.isDirectory) dir else File(home)
    }

    private fun addMessageToChat(msg: ChatMessage) {
        Platform.runLater {
            chatListView.items.add(msg)
            chatListView.scrollTo(chatListView.items.size - 1)
        }
        // Saving for history
        historyManager.saveMessage(msg)
    }

    private fun loadHistory() {
        val history = historyManager.loadHistory()
        Platform.runLater {
            chatListView.items.addAll(history)
            if (history.isNotEmpty()) chatListView.scrollTo(history.size - 1)
        }
    }

//    private fun downloadFile(msg: ChatMessage) {
//        val fileChooser = FileChooser().apply { initialFileName = msg.fileName }
//        val saveFile = fileChooser.showSaveDialog(chatListView.scene.window) ?: return
//
//        scope.launch {
//            val data = connectionManager.downloadFile(msg.filePath!!) // הנחה שהנתיב נשמר
//            if (data != null) {
//                FileOutputStream(saveFile).use { it.write(data) }
//            }
//        }
//    }

    private fun getEmojiForType(type: MessageType) = when (type) {
        MessageType.IMAGE -> "🖼️"
        MessageType.VIDEO -> "🎬"
        MessageType.AUDIO -> "🎵"
        else -> "📄"
    }

    // Show context menu for file messages
    private fun showFileContextMenu(msg: ChatMessage, bubble: VBox, screenX: Double, screenY: Double) {
        val contextMenu = ContextMenu()

        // Delete option
        val deleteItem = MenuItem("Delete").apply {
            setOnAction {
                showDeleteConfirmation(msg)

                val window = bubble.scene.window
                if (window != null) {
                    showToast(window, "The message has been deleted!")
                }
            }
        }

        // Copy text option (copies filename)
        val copyItem = MenuItem("Copy text").apply {
            setOnAction {
                val clipboard = Clipboard.getSystemClipboard()
                val content = ClipboardContent()
                content.putString(msg.fileName ?: "")
                clipboard.setContent(content)
                println("Copied filename: ${msg.fileName}")

                val window = bubble.scene.window
                if (window != null) {
                    showToast(window, "Text copied!")
                }
            }
        }

        // Share option (opens file location in explorer)
        val shareItem = MenuItem("Copy File").apply {
            setOnAction {
                copyFile(msg)

                val window = bubble.scene.window
                showToast(window, "Copied to clipboard!")
            }
        }

        val saveItem = MenuItem("Save As").apply {
            setOnAction {
                saveFileAs(msg)
            }
        }

        contextMenu.items.addAll(deleteItem, copyItem, shareItem, saveItem)
        contextMenu.show(bubble, screenX, screenY)
    }

    // Show context menu for text messages
    private fun showTextContextMenu(msg: ChatMessage, bubble: VBox, screenX: Double, screenY: Double) {
        val contextMenu = ContextMenu()

        // Delete option
        val deleteItem = MenuItem("Delete").apply {
            setOnAction {
                showDeleteConfirmation(msg)

                val window = bubble.scene.window
                if (window != null) {
                    showToast(window, "The message has been deleted!")
                }
            }
        }

        // Copy text option
        val copyItem = MenuItem("Copy text").apply {
            setOnAction {
                val clipboard = Clipboard.getSystemClipboard()
                val content = ClipboardContent()
                content.putString(msg.text ?: "")
                clipboard.setContent(content)
                println("Copied text: ${msg.text}")

                val window = bubble.scene.window
                if (window != null) {
                    showToast(window, "Text copied!")
                }
            }
        }

        // Share option (copies text to clipboard for sharing)
//        val shareItem = MenuItem("Share").apply {
//            setOnAction {
//                shareText(msg)
//            }
//        }

        contextMenu.items.addAll(deleteItem, copyItem)
        contextMenu.show(bubble, screenX, screenY)
    }

    // Show delete confirmation dialog
    private fun showDeleteConfirmation(msg: ChatMessage) {
        val alert = Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION)
        alert.title = "Delete Message"
        alert.headerText = "Are you sure you want to delete this message?"
        alert.contentText = if (msg.type == MessageType.TEXT) {
            "Text: ${msg.text?.take(50)}..."
        } else {
            "File: ${msg.fileName}"
        }

        val result = alert.showAndWait()
        if (result.isPresent && result.get() == ButtonType.OK) {
            deleteMessage(msg)
        }
    }

    private fun saveFileAs(msg: ChatMessage) {
        val originalName = msg.fileName ?: "file_${System.currentTimeMillis()}"
        val chooser = FileChooser().apply { initialFileName = msg.fileName }
        val dest = chooser.showSaveDialog(chatListView.scene.window) ?: return

        try {
            var finalDest = dest

            // If the user deletes an extension – it is automatically restored.
            if (!finalDest.name.contains(".") && originalName.contains(".")) {
                val ext = originalName.substringAfterLast(".")
                finalDest = File(finalDest.parentFile, "${finalDest.name}.$ext")
            }
            val local = msg.filePath?.let { File(it) }

            // If there is a local file and it exists – normal copy
            if (local != null && local.exists()) {
                local.copyTo(finalDest, overwrite = true)
                println("Saved locally: ${finalDest.absolutePath}")
                return
            }

            // Otherwise – re-download from the phone and save to the destination
            val remote = msg.remotePath
            if (remote.isNullOrBlank()) {
                println("No local file and no remotePath to download")
                return
            }

            // Download in the background to avoid UI crashes
            scope.launch {
                try {
                    val bytes = connectionManager.downloadFile(remote)
                    if (bytes != null) {
                        finalDest.writeBytes(bytes)
                        println("Saved by re-download: ${finalDest.absolutePath}")
                    } else {
                        println("Failed to re-download for saving. remotePath=$remote")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Delete message from chat
    private fun deleteMessage(msg: ChatMessage) {
        Platform.runLater {
            // Remove from ListView
            chatListView.items.remove(msg)

            // Remove from history
            historyManager.deleteMessage(msg)

            println("Message deleted: ${msg.id}")
        }
    }

    private fun copyFile(msg: ChatMessage) {
            // Extract the file path from your message object
            val file = File(msg.filePath)

            if (file.exists()) {
                val clipboard = Clipboard.getSystemClipboard()
                val content = ClipboardContent()

                // Add the file to the clipboard content
                content.putFiles(listOf(file))

                // Push it to the system clipboard
                clipboard.setContent(content)

                // Log it or show a lightweight UI notification
                println("File copied to clipboard: ${file.name}")
            } else {
                println("Could not share: File does not exist at ${file.absolutePath}")
            }
        }


//    // ✅ SIMPLER: Share text using Win+H
//    private fun shareText(msg: ChatMessage) {
//        try {
//            val text = msg.text ?: return
//
//            // Copy text to clipboard
//            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
//            val selection = StringSelection(text)
//            clipboard.setContents(selection, selection)
//
//            // Wait for clipboard
//            Thread.sleep(100)
//
//            // Press Win+H to open Share UI
//            val robot = Robot()
//            robot.keyPress(KeyEvent.VK_WINDOWS)
//            robot.keyPress(KeyEvent.VK_H)
//            Thread.sleep(50)
//            robot.keyRelease(KeyEvent.VK_H)
//            robot.keyRelease(KeyEvent.VK_WINDOWS)
//
//            println("✅ Opened Windows Share UI with text")
//
//        } catch (e: Exception) {
//            println("❌ Error: ${e.message}")
//            e.printStackTrace()
//        }
//    }

    private fun showToast(ownerWindow: Window, message: String) {
        val popup = Popup()

        // Create the visual element with some basic CSS styling
        val label = Label(message).apply {
            style = """
            -fx-background-color: rgba(50, 50, 50, 0.9); 
            -fx-text-fill: white; 
            -fx-padding: 10px 20px; 
            -fx-background-radius: 20px;
            -fx-font-size: 14px;
        """.trimIndent()
        }

        popup.content.add(label)

        // Show the popup first so JavaFX calculates its actual width/height
        popup.show(ownerWindow)

        // Position it at the bottom center of the parent window
        popup.x = ownerWindow.x + (ownerWindow.width / 2) - (label.width / 2)
        popup.y = ownerWindow.y + ownerWindow.height - 80.0 // 80 pixels from the bottom

        // Create a fade-out animation
        val fadeOut = FadeTransition(Duration.seconds(1.0), label).apply {
            fromValue = 1.0
            toValue = 0.0
            delay = Duration.seconds(1.5) // How long it stays fully visible
            setOnFinished { popup.hide() } // Clean up when the animation is done
        }

        fadeOut.play()
    }

    // ─────────── VCF Contact Viewer ───────────

    /**
     * Parses a VCF file and shows the contact details in a native JavaFX dialog.
     * Avoids handing the file to Windows "People" app which crashes on modern systems.
     */
    private fun showVcfDialog(vcfFile: File) {
        val contacts = parseVcf(vcfFile)

        val dialog = Dialog<Void>()
        dialog.title = "Contact – ${vcfFile.nameWithoutExtension}"
        dialog.headerText = null

        val content = VBox(12.0).apply { padding = Insets(20.0) }

        if (contacts.isEmpty()) {
            content.children.add(Label("Could not read the contact file.").apply {
                style = "-fx-text-fill: gray;"
            })
        } else {
            for (contact in contacts) {
                val card = VBox(6.0).apply {
                    padding = Insets(12.0)
                    style = """
                        -fx-background-color: #F8F8F8;
                        -fx-background-radius: 10;
                        -fx-border-color: #DDD;
                        -fx-border-radius: 10;
                        -fx-border-width: 1;
                    """.trimIndent()
                }

                // Draws (or redraws) all labels inside the card.
                // Called once on load and again after the user saves edits.
                fun refreshCard(contact: VcfContact, target: VBox) {
                    target.children.clear()
                    if (contact.fullName.isNotBlank()) {
                        target.children.add(Label(contact.fullName).apply {
                            style = "-fx-font-size: 16px; -fx-font-weight: bold;"
                        })
                    }
                    if (contact.organization.isNotBlank()) {
                        target.children.add(Label("\uD83C\uDFE2  ${contact.organization}").apply {
                            style = "-fx-font-size: 12px; -fx-text-fill: #555;"
                        })
                    }
                    contact.phones.forEach { (label, number) ->
                        val lbl = if (label.isNotBlank()) "\uD83D\uDCDE  $label: $number" else "\uD83D\uDCDE  $number"
                        val phoneLbl = Label(lbl).apply {
                            style = "-fx-font-size: 13px; -fx-cursor: hand;"
                            tooltip = Tooltip("Click to copy")
                        }
                        phoneLbl.setOnMouseClicked {
                            val cb = Clipboard.getSystemClipboard()
                            val cc = ClipboardContent(); cc.putString(number); cb.setContent(cc)
                            showToast(dialog.dialogPane.scene.window, "Copied: $number")
                        }
                        target.children.add(phoneLbl)
                    }
                    contact.emails.forEach { (label, email) ->
                        val lbl = if (label.isNotBlank()) "\u2709\uFE0F  $label: $email" else "\u2709\uFE0F  $email"
                        val emailLbl = Label(lbl).apply {
                            style = "-fx-font-size: 13px; -fx-cursor: hand;"
                            tooltip = Tooltip("Click to copy")
                        }
                        emailLbl.setOnMouseClicked {
                            val cb = Clipboard.getSystemClipboard()
                            val cc = ClipboardContent(); cc.putString(email); cb.setContent(cc)
                            showToast(dialog.dialogPane.scene.window, "Copied: $email")
                        }
                        target.children.add(emailLbl)
                    }
                    contact.addresses.forEach { addr ->
                        if (addr.isNotBlank()) {
                            target.children.add(Label("\uD83D\uDCCD  $addr").apply {
                                style = "-fx-font-size: 13px; -fx-wrap-text: true; -fx-max-width: 380;"
                            })
                        }
                    }
                    if (contact.note.isNotBlank()) {
                        target.children.add(Label("\uD83D\uDCDD  ${contact.note}").apply {
                            style = "-fx-font-size: 12px; -fx-text-fill: #666; -fx-wrap-text: true; -fx-max-width: 380;"
                        })
                    }
                }

                refreshCard(contact, card)
                content.children.add(card)
            }
        }


//                if (contact.fullName.isNotBlank()) {
//                    card.children.add(Label(contact.fullName).apply {
//                        style = "-fx-font-size: 16px; -fx-font-weight: bold;"
//                    })
//                }
//                if (contact.organization.isNotBlank()) {
//                    card.children.add(Label("\uD83C\uDFE2  ${contact.organization}").apply {
//                        style = "-fx-font-size: 12px; -fx-text-fill: #555;"
//                    })
//                }
//                contact.phones.forEach { (label, number) ->
//                    val lbl = if (label.isNotBlank()) "\uD83D\uDCDE  $label: $number" else "\uD83D\uDCDE  $number"
//                    val phoneLbl = Label(lbl).apply {
//                        style = "-fx-font-size: 13px; -fx-cursor: hand;"
//                        tooltip = Tooltip("Click to copy")
//                    }
//                    phoneLbl.setOnMouseClicked {
//                        val cb = Clipboard.getSystemClipboard()
//                        val cc = ClipboardContent(); cc.putString(number); cb.setContent(cc)
//                        showToast(dialog.dialogPane.scene.window, "Copied: $number")
//                    }
//                    card.children.add(phoneLbl)
//                }
//                contact.emails.forEach { (label, email) ->
//                    val lbl = if (label.isNotBlank()) "\u2709\uFE0F  $label: $email" else "\u2709\uFE0F  $email"
//                    val emailLbl = Label(lbl).apply {
//                        style = "-fx-font-size: 13px; -fx-cursor: hand;"
//                        tooltip = Tooltip("Click to copy")
//                    }
//                    emailLbl.setOnMouseClicked {
//                        val cb = Clipboard.getSystemClipboard()
//                        val cc = ClipboardContent(); cc.putString(email); cb.setContent(cc)
//                        showToast(dialog.dialogPane.scene.window, "Copied: $email")
//                    }
//                    card.children.add(emailLbl)
//                }
//                contact.addresses.forEach { addr ->
//                    if (addr.isNotBlank()) {
//                        card.children.add(Label("\uD83D\uDCCD  $addr").apply {
//                            style = "-fx-font-size: 13px; -fx-wrap-text: true; -fx-max-width: 380;"
//                        })
//                    }
//                }
//                if (contact.note.isNotBlank()) {
//                    card.children.add(Label("\uD83D\uDCDD  ${contact.note}").apply {
//                        style = "-fx-font-size: 12px; -fx-text-fill: #666; -fx-wrap-text: true; -fx-max-width: 380;"
//                    })
//                }
//                content.children.add(card)
//            }
//        }

        // ── Button row ──
        // ── Helper: build a nicely-formatted plain-text summary of all contacts ──
//        fun buildContactText(): String {
//            val sb = StringBuilder()
//            for ((idx, c) in contacts.withIndex()) {
//                if (idx > 0) sb.appendLine()
//                if (c.fullName.isNotBlank())     sb.appendLine("Name:    ${c.fullName}")
//                if (c.organization.isNotBlank()) sb.appendLine("Company: ${c.organization}")
//                c.phones.forEach { (lbl, num) ->
//                    sb.appendLine(if (lbl.isNotBlank()) "Phone ($lbl): $num" else "Phone: $num")
//                }
//                c.emails.forEach { (lbl, email) ->
//                    sb.appendLine(if (lbl.isNotBlank()) "Email ($lbl): $email" else "Email: $email")
//                }
//                c.addresses.forEach { addr -> sb.appendLine("Address: $addr") }
//                if (c.note.isNotBlank())         sb.appendLine("Note:    ${c.note}")
//            }
//            return sb.toString().trimEnd()
//        }

        val buttonRow = HBox(10.0).apply { alignment = Pos.CENTER_LEFT; padding = Insets(4.0, 0.0, 0.0, 0.0) }

        // ── Helper: build a clean UTF-8 vCard 3.0 string from parsed contact data ──
        // Samsung exports use Quoted-Printable / v2.1; this writes a clean v3.0 that
        // WhatsApp, Outlook, Thunderbird, and Android all understand perfectly.
        fun buildVCard(): String {
            val sb = StringBuilder()
            for (c in contacts) {
                sb.appendLine("BEGIN:VCARD")
                sb.appendLine("VERSION:3.0")
                if (c.fullName.isNotBlank()) {
                    sb.appendLine("FN:${c.fullName}")
                    // N field: Lastname;Firstname (split on last space for simplicity)
                    val parts = c.fullName.trim().split(" ")
                    val last  = parts.lastOrNull() ?: ""
                    val first = parts.dropLast(1).joinToString(" ")
                    sb.appendLine("N:$last;$first;;;")
                }
                if (c.organization.isNotBlank()) sb.appendLine("ORG:${c.organization}")
                c.phones.forEach { (lbl, num) ->
                    val type = when (lbl.lowercase()) {
                        "mobile", "cell" -> "CELL"
                        "home"           -> "HOME"
                        "work"           -> "WORK"
                        else             -> "VOICE"
                    }
                    sb.appendLine("TEL;TYPE=$type:$num")
                }
                c.emails.forEach { (lbl, email) ->
                    val type = when (lbl.lowercase()) {
                        "home" -> "HOME"; "work" -> "WORK"; else -> "INTERNET"
                    }
                    sb.appendLine("EMAIL;TYPE=$type:$email")
                }
                c.addresses.forEach { addr -> sb.appendLine("ADR;TYPE=HOME:;;$addr;;;;") }
                if (c.note.isNotBlank()) sb.appendLine("NOTE:${c.note}")
                sb.appendLine("END:VCARD")
            }
            return sb.toString().trimEnd()
        }

        // ── Button 1: Export as vCard – share on WhatsApp or any contacts app ──
        val copyBtn = Button("\uD83D\uDCF2 Export as vCard").apply {
            style = "-fx-cursor: hand;"
            tooltip = Tooltip("Saves a clean vCard file and puts it on the clipboard.\nDrag it into WhatsApp (or any app) to share.")
            setOnAction {
                try {
                    val contactName = contacts.firstOrNull()?.fullName?.trim()
                        ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        ?: "Contact"

                    // 2. בניית השם המבוקש: שם איש הקשר + חותמת האפליקציה
                    val customFileName = "${contactName}_StreamBridge.vcf"

                    // 3. יצירת הנתיב בתיקיית הזמניים של המערכת
                    val tempDir = System.getProperty("java.io.tmpdir")
                    val tempFile = java.io.File(tempDir, customFileName)

                    // Write to a temp file – deleted when the app closes, never opened by People app
                    tempFile.writeText(buildVCard(), Charsets.UTF_8)
                    tempFile.deleteOnExit() // מחיקה בסגירת האפליקציה

                    // Put the file on the clipboard so the user can Ctrl+V in WhatsApp Desktop
                    val cb = Clipboard.getSystemClipboard()
                    val cc = ClipboardContent()
                    cc.putFiles(listOf(tempFile))
                    cb.setContent(cc)

                    showToast(dialog.dialogPane.scene.window,
                        "Ready!")

                } catch (e: Exception) {
                    Alert(javafx.scene.control.Alert.AlertType.ERROR).apply {
                        title = "Export Failed"; contentText = e.message; showAndWait()
                    }
                }
            }
        }

        // ── Button 2: Save as .txt and open with Notepad ──
        // A plain-text file opens with Notepad by default on every Windows PC
        // and stores the contact permanently with no app dependency.
        val savePdfBtn = Button("\uD83D\uDCC4 Save as PDF").apply {
            style = "-fx-cursor: hand;"
            setOnAction {
                val contact = contacts.firstOrNull() ?: return@setOnAction

                val chooser = FileChooser().apply {
                    title = "Save Contact as PDF"
                    initialFileName = "${contact.fullName.ifBlank { "Contact" }}.pdf"
                    extensionFilters.add(FileChooser.ExtensionFilter("PDF Files (*.pdf)", "*.pdf"))

                    val docs = Paths.get(System.getProperty("user.home"), "Documents").toFile()
                    if (docs.exists()) initialDirectory = docs
                }

                val dest = chooser.showSaveDialog(dialog.dialogPane.scene.window) ?: return@setOnAction

                try {
                    val contactName = contact.fullName.ifBlank { "Contact" }
                    fun String.rev() = this.reversed()
                    val htmlContent = StringBuilder().apply {
                        append("""<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
                "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
                    <style type="text/css">
                        body {
                        font-family: 'Arial', sans-serif;
                        background-color: #f0f0f0;
                        padding: 20px;
                    }
                    .card {
                        background: white;
                        padding: 20px;
                        border-radius: 12px;
                        max-width: 460px;
                        margin: auto;
                        border: 1px solid #ddd;
                    }
                    .name {
                        color: #0078D7;
                        font-size: 20px;
                        font-weight: bold;
                        text-align: right;          
                        margin-bottom: 12px;
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                    }
                    td {
                        padding: 5px 6px;
                        vertical-align: top; 
                    }
                    .lbl {
                        font-weight: bold;
                        color: #555;
                        text-align: left;                        
                        white-space: nowrap;
                        width: 1%;
                        padding-right: 16px;
                    }
                    .val {
                        text-align: right;                        
                    }
                    .ltr {
                        text-align: left;                        
                    }
                   .sep  {
                        border-top: 1px solid #eee;
                    }                
                    </style>
                </head>
                <body>
                    <div class="card">
                      <div class="name">${contactName.rev()}</div>
                        <table>
                """)

                        // שימוש בתוויות באנגלית וסינון שדות ריקים
                        if (contact.organization.isNotBlank()) {
                            append("<tr><td class='lbl'>Company:</td><td class='val'>${contact.organization.rev()}</td></tr>")
                            append("<tr><td colspan='2'><hr/></td></tr>")
                        }
//                            append("<div class='field'><span class='label'>Company:</span> ${contact.organization}</div>")
//                        }
//                        append("<hr/>")

                        contact.phones.forEach { (label, num) ->
                            if (num.isNotBlank()) {
                                val displayLabel = if (label.isNotBlank()) "Phone ($label)" else "Phone"
                                append("<tr><td class='lbl'>$displayLabel</td><td class='val-ltr'>$num</td></tr>")
                            }
                        }

                        contact.emails.forEach { (label, email) ->
                            if (email.isNotBlank()) {
                                val displayLabel = if (label.isNotBlank()) "Email ($label)" else "Email"
                                append("<tr><td class='lbl'>$displayLabel</td><td class='val-ltr'>$email</td></tr>")
                            }
                        }

                        contact.addresses.forEach { addr ->
                            if (addr.isNotBlank()) {
                                append("<tr><td class='lbl'>Address:</td><td class='val'>$addr</td></tr>")
                            }
                        }

                        if (contact.note.isNotBlank()) {
                            append("<tr><td class=\"lbl\">Notes:</td><td class=\"val\">${contact.note.rev()}</td></tr>")
                        }

                        append("""
                        </table>
                    </div>
                </body>
                </html>
                """)
                    }.toString()

                    // יצירת ה-PDF
                    java.io.FileOutputStream(dest).use { os ->
                        val builder = com.openhtmltopdf.pdfboxout.PdfRendererBuilder()

                        // טעינת פונט Arial תקין שתומך בעברית כדי למנוע סימני #
                        val fontFile = File("C:/Windows/Fonts/arial.ttf")
                        if (fontFile.exists()) {
                            builder.useFont(fontFile, "Arial")
                        }

                        builder.withHtmlContent(htmlContent, null)
                        builder.toStream(os)
                        builder.run()
                    }

                    Desktop.getDesktop().open(dest)
                    showToast(dialog.dialogPane.scene.window, "PDF Saved!")

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // ── Edit Contact button ──
        // Opens a form pre-filled with all contact fields.
        // On OK: writes changes back into the contact object and redraws the card instantly.
        val editBtn = Button("\u270F\uFE0F Edit Contact").apply {
            style = "-fx-cursor: hand;"
            setOnAction {
                val contact = contacts.firstOrNull() ?: return@setOnAction

                val editDialog = Dialog<ButtonType>()
                editDialog.title = "Edit Contact"
                editDialog.headerText = null

                val grid = GridPane().apply {
                    hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
                }
                var row = 0
                fun addRow(label: String, initial: String): TextField {
                    grid.add(Label(label).apply { style = "-fx-font-weight: bold;" }, 0, row)
                    val tf = TextField(initial).apply { prefWidth = 280.0 }
                    grid.add(tf, 1, row); row++; return tf
                }

                val nameField = addRow("Name:", contact.fullName)
                val orgField  = addRow("Company:", contact.organization)

                val phoneFields = contact.phones.map { (_, num) -> addRow("Phone:", num) }
                    .toMutableList()
                    .also { if (it.isEmpty()) it.add(addRow("Phone:", "")) }

                val emailFields = contact.emails.map { (_, email) -> addRow("Email:", email) }
                    .toMutableList()
                    .also { if (it.isEmpty()) it.add(addRow("Email:", "")) }

                val addrField = addRow("Address:", contact.addresses.firstOrNull() ?: "")
                val noteField = addRow("Notes:", contact.note)

                editDialog.dialogPane.content = ScrollPane(grid).apply {
                    isFitToWidth = true; prefViewportHeight = 380.0
                }
                editDialog.dialogPane.prefWidth = 420.0
                editDialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

                if (editDialog.showAndWait().orElse(null) == ButtonType.OK) {
                    contact.fullName     = nameField.text.trim()
                    contact.organization = orgField.text.trim()
                    contact.note         = noteField.text.trim()
                    contact.phones.clear()
                    phoneFields.forEachIndexed { i, tf ->
                        val num = tf.text.trim()
                        if (num.isNotBlank()) {
                            val origLabel = contacts.firstOrNull()?.phones?.getOrNull(i)?.first ?: ""
                            contact.phones.add(origLabel to num)
                        }
                    }
//                        if (n.text.isNotBlank()) contact.phones.add(l.text.trim() to n.text.trim())
//                    }
                    contact.emails.clear()
                    emailFields.forEachIndexed { i, tf ->
                        val email = tf.text.trim()
                        if (email.isNotBlank()) {
                            val origLabel = contacts.firstOrNull()?.emails?.getOrNull(i)?.first ?: ""
                            contact.emails.add(origLabel to email)
                        }
                    }
//                        if (e.text.isNotBlank()) contact.emails.add(l.text.trim() to e.text.trim())
//                    }
                    contact.addresses.clear()
                    if (addrField.text.isNotBlank()) contact.addresses.add(addrField.text.trim())

                    // Redraw the card immediately with the updated values
                    val card = content.children.filterIsInstance<VBox>()
                        .firstOrNull { it.padding == Insets(12.0) }
                    card?.let { c ->
                        c.children.clear()
                        if (contact.fullName.isNotBlank())
                            c.children.add(Label(contact.fullName).apply { style = "-fx-font-size: 16px; -fx-font-weight: bold;" })
                        if (contact.organization.isNotBlank())
                            c.children.add(Label("\uD83C\uDFE2  ${contact.organization}").apply { style = "-fx-font-size: 12px; -fx-text-fill: #555;" })
                        contact.phones.forEach { (label, number) ->
                            c.children.add(Label(if (label.isNotBlank()) "\uD83D\uDCDE  $label: $number" else "\uD83D\uDCDE  $number").apply { style = "-fx-font-size: 13px;" })
                        }
                        contact.emails.forEach { (label, email) ->
                            c.children.add(Label(if (label.isNotBlank()) "\u2709\uFE0F  $label: $email" else "\u2709\uFE0F  $email").apply { style = "-fx-font-size: 13px;" })
                        }
                        contact.addresses.forEach { addr ->
                            if (addr.isNotBlank()) c.children.add(Label("\uD83D\uDCCD  $addr").apply { style = "-fx-font-size: 13px;" })
                        }
                        if (contact.note.isNotBlank())
                            c.children.add(Label("\uD83D\uDCDD  ${contact.note}").apply { style = "-fx-font-size: 12px; -fx-text-fill: #666;" })
                    }
                    showToast(dialog.dialogPane.scene.window, "Contact updated!")
                }
            }
        }
        buttonRow.children.addAll(copyBtn, savePdfBtn, editBtn)
        content.children.add(buttonRow)

        val scroll = ScrollPane(content).apply {
            isFitToWidth = true
            prefViewportHeight = 420.0
            style = "-fx-background-color: white;"
        }

        dialog.dialogPane.content = scroll
        dialog.dialogPane.prefWidth = 460.0
        dialog.dialogPane.buttonTypes.add(ButtonType.CLOSE)
        dialog.showAndWait()
    }


//        // "Export VCF" – saves file AND opens containing folder so user can import into Outlook etc.
//        // (does NOT open the .vcf directly, which would launch the broken People app)
//        val saveBtn = Button("\uD83D\uDCBE Export VCF for Import...").apply {
//            style = "-fx-cursor: hand;"
//            setOnAction {
//                val chooser = FileChooser().apply {
//                    title = "Save Contact VCF"
//                    initialFileName = vcfFile.name
//                    extensionFilters.add(FileChooser.ExtensionFilter("vCard (*.vcf)", "*.vcf"))
//                }
//                val dest = chooser.showSaveDialog(dialog.dialogPane.scene.window)
//                if (dest != null) {
//                    vcfFile.copyTo(dest, overwrite = true)
//                    // Open the folder, NOT the file – avoids opening the broken People app
//                    try { Desktop.getDesktop().browseFileDirectory(dest) } catch (_: Exception) {
//                        try { Desktop.getDesktop().open(dest.parentFile) } catch (_: Exception) {}
//                    }
//                    showToast(dialog.dialogPane.scene.window,
//                        "Saved! Import via Outlook or Windows Contacts.")
//                }
//            }
//        }
//
//        buttonRow.children.addAll(copyBtn, saveBtn)
//        content.children.add(buttonRow)
//
//        val scroll = ScrollPane(content).apply {
//            isFitToWidth = true
//            prefViewportHeight = 420.0
//            style = "-fx-background-color: white;"
//        }
//
//        dialog.dialogPane.content = scroll
//        dialog.dialogPane.prefWidth = 460.0
//        dialog.dialogPane.buttonTypes.add(ButtonType.CLOSE)
//        dialog.showAndWait()
//    }

    private fun parseVcf(file: File): List<VcfContact> {
        val contacts = mutableListOf<VcfContact>()
        var current: VcfContact? = null
        var currentKey = ""
        var currentValue = StringBuilder()
        var currentIsQP = false   // whether the current field uses Quoted-Printable encoding
        var currentCharset = "UTF-8"

        /** Decode a Quoted-Printable encoded string to a Unicode string. */
        fun decodeQP(raw: String, charset: String): String {
            return try {
                val bytes = mutableListOf<Byte>()
                var i = 0
                while (i < raw.length) {
                    when {
                        raw[i] == '=' && i + 2 < raw.length &&
                                raw[i+1].isLetterOrDigit() && raw[i+2].isLetterOrDigit() -> {
                            bytes.add(raw.substring(i+1, i+3).toInt(16).toByte())
                            i += 3
                        }
                        else -> { bytes.add(raw[i].code.toByte()); i++ }
                    }
                }
                String(bytes.toByteArray(), charset(charset))
            } catch (e: Exception) {
                raw // fallback: return as-is
            }
        }

        fun commitLine(key: String, rawValue: String) {
            val c = current ?: return
            val keyUpper = key.uppercase()
            val params = keyUpper.split(";")
            val baseKey = params[0]
            val typeParam = params.find { it.startsWith("TYPE=") }?.removePrefix("TYPE=")?.uppercase() ?: ""

            // Decode value: if QP-encoded, decode it; otherwise use as-is
            val value = if (currentIsQP) decodeQP(rawValue, currentCharset) else rawValue

            when {
                baseKey == "FN"  -> c.fullName = value.trim()
                baseKey == "N"    -> {
                    // N:LastName;FirstName;Middle;Prefix;Suffix  — only fill fullName if FN is empty
                    if (c.fullName.isBlank()) {
                        val parts = value.split(";").map { it.trim() }.filter { it.isNotBlank() }
                        c.fullName = parts.joinToString(" ")
                    }
                }
                baseKey == "ORG" -> c.organization = value.replace(";", " ").trim()
                baseKey == "NOTE" -> c.note = value.trim()
                baseKey.startsWith("TEL") -> {
                    val label = when {
                        "CELL" in typeParam || "MOBILE" in typeParam -> "Mobile"
                        "HOME" in typeParam -> "Home"
                        "WORK" in typeParam -> "Work"
                        else -> typeParam.lowercase().replaceFirstChar { it.uppercase() }
                    }
                    if (value.trim().isNotBlank()) c.phones.add(label to value.trim())
                }
                baseKey.startsWith("EMAIL") -> {
                    val label = when {
                        "HOME" in typeParam -> "Home"
                        "WORK" in typeParam -> "Work"
                        else -> ""
                    }
                    if (value.trim().isNotBlank()) c.emails.add(label to value.trim())
                }
                baseKey.startsWith("ADR") -> {
                    val parts = value.split(";").map { it.trim() }.filter { it.isNotBlank() }
                    val addr = parts.joinToString(", ")
                    if (addr.isNotBlank()) c.addresses.add(addr)
                }
            }
        }

        // Read raw bytes so we can handle any charset properly
        val rawLines = file.readLines(Charsets.ISO_8859_1) // read as Latin-1 to preserve raw bytes

        rawLines.forEach { rawLine ->
            // RFC 2425/6350 line folding: continuation starts with space/tab
            if ((rawLine.startsWith(" ") || rawLine.startsWith("\t")) && current != null) {
                // For QP, trim the leading whitespace; for folded lines, just append
                currentValue.append(rawLine.trimStart())
                // QP soft line break: if previous line ended with '=', remove it
                // (handled below when building currentValue)
                return@forEach
            }

            // QP soft line break: line ends with '=' means the value continues
            if (currentIsQP && currentValue.endsWith("=")) {
                currentValue.deleteCharAt(currentValue.length - 1)
                currentValue.append(rawLine)
                return@forEach
            }

            // Commit the previous accumulated line
            if (currentKey.isNotBlank()) commitLine(currentKey, currentValue.toString())
            currentKey = ""; currentValue = StringBuilder()
            currentIsQP = false; currentCharset = "UTF-8"

            val line = rawLine // keep original (not trimmed) to preserve encoding
            when {
                line.trim().equals("BEGIN:VCARD", ignoreCase = true) -> current = VcfContact()
                line.trim().equals("END:VCARD",   ignoreCase = true) -> {
                    current?.let { contacts.add(it) }; current = null
                }
                line.contains(":") && current != null -> {
                    val idx = line.indexOf(':')
                    currentKey   = line.substring(0, idx)
                    currentValue = StringBuilder(line.substring(idx + 1))

                    // Detect Quoted-Printable encoding in params
                    val keyUpper = currentKey.uppercase()
                    currentIsQP = "ENCODING=QUOTED-PRINTABLE" in keyUpper || "ENCODING=QP" in keyUpper
                    // Detect charset (default UTF-8 for Samsung)
                    val csMatch = Regex("CHARSET=([A-Za-z0-9_-]+)").find(keyUpper)
                    if (csMatch != null) currentCharset = csMatch.groupValues[1]

                    // QP soft line break check: if value ends with '=', more lines follow
                    // (handled at top of next iteration)
                }
            }
        }
        if (currentKey.isNotBlank()) commitLine(currentKey, currentValue.toString())
        return contacts
    }



//        file.readLines(Charsets.UTF_8).forEach { rawLine ->
//            if ((rawLine.startsWith(" ") || rawLine.startsWith("\t")) && current != null) {
//                currentValue.append(rawLine.trimStart())
//                return@forEach
//            }
//            if (currentKey.isNotBlank()) commitLine(currentKey, currentValue.toString())
//            currentKey = ""; currentValue = StringBuilder()
//            val line = rawLine.trim()
//            when {
//                line.equals("BEGIN:VCARD", ignoreCase = true) -> current = VcfContact()
//                line.equals("END:VCARD",   ignoreCase = true) -> {
//                    current?.let { contacts.add(it) }; current = null
//                }
//                line.contains(":") && current != null -> {
//                    val idx = line.indexOf(':')
//                    currentKey = line.substring(0, idx)
//                    currentValue = StringBuilder(line.substring(idx + 1))
//                }
//            }
//        }
//        if (currentKey.isNotBlank()) commitLine(currentKey, currentValue.toString())
//        return contacts
//    }

    private data class VcfContact(
        var fullName: String = "",
        var organization: String = "",
        var note: String = "",
        val phones: MutableList<Pair<String, String>> = mutableListOf(),
        val emails: MutableList<Pair<String, String>> = mutableListOf(),
        val addresses: MutableList<String> = mutableListOf()
    )
}

//    // Share file (open in file explorer)
//    private fun shareFile(msg: ChatMessage) {
//        try {
//            val path = msg.filePath ?: return
//            val file = File(path)
//
//            if (file.exists()) {
//                // Open file location in explorer
//                Desktop.getDesktop().browseFileDirectory(file)
//                println("Opened file location: ${file.parent}")
//            } else {
//                println("File not found: $path")
//            }
//        } catch (e: Exception) {
//            println("Error sharing file: ${e.message}")
//            e.printStackTrace()
//        }
//    }
//
//    // ✅ NEW: Share text (show dialog with text to copy)
//    private fun shareText(msg: ChatMessage) {
//        val clipboard = Clipboard.getSystemClipboard()
//        val content = ClipboardContent()
//        content.putString(msg.text ?: "")
//        clipboard.setContent(content)
//
//        val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION)
//        alert.title = "Text Copied"
//        alert.headerText = "Text copied to clipboard"
//        alert.contentText = "You can now paste it anywhere to share"
//        alert.showAndWait()
//    }
//}


























































//class FileExplorer(private val connectionManager: ConnectionManager) {
//    private val tableView = TableView<FileItem>()
//    private val statusLabel = Label("Not connected")
//    private val refreshButton = Button("Refresh")
//    private val downloadButton = Button("Download Selected")
//    private val scope = CoroutineScope(Dispatchers.Default)
//
//    init {
//        setupTable()
//
//        refreshButton.setOnAction {
//            loadFiles()
//        }
//
//        downloadButton.setOnAction {
//            downloadSelected()
//        }
//
//        downloadButton.isDisable = true
//    }
//
//    fun getView(): BorderPane {
//        val root = BorderPane()
//
//        // Top bar
//        val topBar = HBox(10.0)
//        topBar.padding = Insets(10.0)
//        topBar.children.addAll(refreshButton, downloadButton, statusLabel)
//        root.top = topBar
//
//        // Center - File table
//        root.center = tableView
//
//        return root
//    }
//
//    private fun setupTable() {
//        val nameColumn = TableColumn<FileItem, String>("Name")
//        nameColumn.cellValueFactory = PropertyValueFactory("name")
//        nameColumn.prefWidth = 300.0
//
//        val sizeColumn = TableColumn<FileItem, String>("Size")
//        sizeColumn.cellValueFactory = PropertyValueFactory("sizeStr")
//        sizeColumn.prefWidth = 100.0
//
//        val typeColumn = TableColumn<FileItem, String>("Type")
//        typeColumn.cellValueFactory = PropertyValueFactory("type")
//        typeColumn.prefWidth = 150.0
//
//        val pathColumn = TableColumn<FileItem, String>("Path")
//        pathColumn.cellValueFactory = PropertyValueFactory("path")
//        pathColumn.prefWidth = 400.0
//
//        tableView.columns.addAll(nameColumn, sizeColumn, typeColumn, pathColumn)
//
//        tableView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
//            downloadButton.isDisable = newValue == null
//        }
//    }
//
//    private fun loadFiles() {
//        if (!connectionManager.isConnected()) {
//            Platform.runLater {
//                statusLabel.text = "Please connect to phone first"
//            }
//            return
//        }
//
//        Platform.runLater {
//            statusLabel.text = "Loading files..."
//        }
//
//        scope.launch {
//            try {
//                val jsonStr = connectionManager.fetchFileList()
//                if (jsonStr != null) {
//                    val jsonArray = JSONArray(jsonStr)
//                    val fileItems = mutableListOf<FileItem>()
//
//                    for (i in 0 until jsonArray.length()) {
//                        val obj = jsonArray.getJSONObject(i)
//                        val fileItem = FileItem(
//                            name = obj.getString("name"),
//                            path = obj.getString("path"),
//                            size = obj.getLong("size"),
//                            type = obj.getString("type")
//                        )
//                        fileItems.add(fileItem)
//                    }
//
//                    Platform.runLater {
//                        tableView.items.clear()
//                        tableView.items.addAll(fileItems)
//                        statusLabel.text = "Loaded ${fileItems.size} files"
//                    }
//                } else {
//                    Platform.runLater {
//                        statusLabel.text = "Failed to load files"
//                    }
//                }
//            } catch (e: Exception) {
//                println("Error loading files: ${e.message}")
//                Platform.runLater {
//                    statusLabel.text = "Error: ${e.message}"
//                }
//            }
//        }
//    }
//
//    private fun downloadSelected() {
//        val selected = tableView.selectionModel.selectedItem ?: return
//
//        val fileChooser = FileChooser()
//        fileChooser.title = "Save File"
//        fileChooser.initialFileName = selected.name
//
//        val saveFile = fileChooser.showSaveDialog(tableView.scene.window) ?: return
//
//        Platform.runLater {
//            statusLabel.text = "Downloading ${selected.name}..."
//        }
//
//        scope.launch {
//            try {
//                val fileData = connectionManager.downloadFile(selected.path)
//                if (fileData != null) {
//                    FileOutputStream(saveFile).use { fos ->
//                        fos.write(fileData)
//                    }
//
//                    Platform.runLater {
//                        statusLabel.text = "Downloaded: ${saveFile.absolutePath}"
//                    }
//                } else {
//                    Platform.runLater {
//                        statusLabel.text = "Failed to download file"
//                    }
//                }
//            } catch (e: Exception) {
//                println("Error downloading file: ${e.message}")
//                Platform.runLater {
//                    statusLabel.text = "Error: ${e.message}"
//                }
//            }
//        }
//    }
//
//    fun cleanup() {
//        scope.cancel()
//    }
//}
//

