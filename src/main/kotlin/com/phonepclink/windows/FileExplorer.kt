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

import java.awt.Robot
import java.awt.event.KeyEvent
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

import javafx.animation.FadeTransition
import javafx.scene.control.Label
import javafx.stage.Popup
import javafx.stage.Window
import javafx.util.Duration

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
            val btnBox = HBox(10.0).apply {
                alignment = Pos.CENTER
                padding = Insets(5.0, 0.0, 0.0, 0.0)
            }

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

            val saveBtn = Button("Save As").apply {
                style = "-fx-font-size: 10px; -fx-background-radius: 5; -fx-cursor: hand;"

                setOnAction {
                    val originalName = msg.fileName ?: "file_${System.currentTimeMillis()}"
                    val chooser = FileChooser().apply { initialFileName = msg.fileName }
                    val dest = chooser.showSaveDialog(chatListView.scene.window) ?: return@setOnAction

                    try {
                        var finalDest = dest

                        // If the user deletes an extension – it is automatically restored.
                        if (!finalDest.name.contains(".") && originalName.contains(".")) {
                            val ext = originalName.substringAfterLast(".")

//                        if (!finalDest.name.contains(".") && msg.fileName.contains(".")) {
//                            val ext = msg.fileName.substringAfterLast(".")
                            finalDest = File(finalDest.parentFile, "${finalDest.name}.$ext")
                        }
                        val local = msg.filePath?.let { File(it) }

                        // If there is a local file and it exists – normal copy
                        if (local != null && local.exists()) {
                            local.copyTo(finalDest, overwrite = true)
                            println("Saved locally: ${finalDest.absolutePath}")
                            return@setOnAction
                        }

                        // Otherwise – re-download from the phone and save to the destination
                        val remote = msg.remotePath
                        if (remote.isNullOrBlank()) {
                            println("No local file and no remotePath to download")
                            return@setOnAction
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
            }

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
            btnBox.children.add(saveBtn)
            bubble.children.add(btnBox)

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
                    if (file.exists()) {
                        Desktop.getDesktop().open(file)
                        println("Opened file: $path")
                    } else {
                        println("File not found: $path")
                        val alert = Alert(javafx.scene.control.Alert.AlertType.ERROR)
                        alert.title = "File Not Found"
                        alert.contentText = "File no longer exists"
                        alert.showAndWait()
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

            // Load image
            val image = Image(file.toURI().toString(), 80.0, 80.0, true, true, true)

            ImageView(image).apply {
                fitWidth = 80.0
                fitHeight = 80.0
                isPreserveRatio = true
                isSmooth = true
                style =
                    "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: rgba(0,0,0,0.1); -fx-border-width: 1;"
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

        contextMenu.items.addAll(deleteItem, copyItem, shareItem)
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

                // Optional: Log it or show a lightweight UI notification
                println("File copied to clipboard: ${file.name}")
            } else {
                println("Could not share: File does not exist at ${file.absolutePath}")
            }
        }


    // ✅ SIMPLER: Share text using Win+H
    private fun shareText(msg: ChatMessage) {
        try {
            val text = msg.text ?: return

            // Copy text to clipboard
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(text)
            clipboard.setContents(selection, selection)

            // Wait for clipboard
            Thread.sleep(100)

            // Press Win+H to open Share UI
            val robot = Robot()
            robot.keyPress(KeyEvent.VK_WINDOWS)
            robot.keyPress(KeyEvent.VK_H)
            Thread.sleep(50)
            robot.keyRelease(KeyEvent.VK_H)
            robot.keyRelease(KeyEvent.VK_WINDOWS)

            println("✅ Opened Windows Share UI with text")

        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
            e.printStackTrace()
        }
    }

    fun showToast(ownerWindow: Window, message: String) {
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

