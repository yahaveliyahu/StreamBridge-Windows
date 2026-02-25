package com.phonepclink.windows

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.embed.swing.SwingFXUtils
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

class CameraView(private val connectionManager: ConnectionManager) {
    private val imageView = ImageView()
    private val statusLabel = Label("Not streaming")
    private val startButton = Button("Start Live Preview")
    private val captureButton = Button("Capture Photo")
    private var isStreaming = false
    private var streamingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        imageView.fitWidth = 640.0
        imageView.fitHeight = 480.0
        imageView.isPreserveRatio = true
        imageView.isSmooth = true
        imageView.style = "-fx-background-color: #000000; -fx-border-color: #333333; -fx-border-width: 2px;"

        // Style status label for top bar
        statusLabel.style = "-fx-text-fill: #333333; -fx-font-size: 12px; -fx-padding: 0 0 0 20px;"

        // Set a placeholder to ensure imageView is visible
        println("CameraView: Initializing ImageView (${imageView.fitWidth}x${imageView.fitHeight})")

        captureButton.isDisable = true

        startButton.setOnAction {
            println("CameraView: Start button clicked, isStreaming=$isStreaming")
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        captureButton.setOnAction {
            println("CameraView: Capture button clicked")
            capturePhoto()
        }
    }

    fun getView(): BorderPane {
        val root = BorderPane()
        root.style = "-fx-background-color: #FFFFFF;"

        // TOP - Control buttons (moved here for better visibility on small screens)
        val controlBox = HBox(10.0)
        controlBox.alignment = Pos.CENTER_LEFT
        controlBox.padding = Insets(10.0)
        controlBox.style = "-fx-background-color: #E0E0E0;"

        startButton.style = "-fx-font-size: 14px; -fx-padding: 8px 15px;"
        captureButton.style = "-fx-font-size: 14px; -fx-padding: 8px 15px;"

        controlBox.children.addAll(startButton, captureButton, statusLabel)
        root.top = controlBox

        // CENTER - Camera preview
        val centerBox = VBox(10.0)
        centerBox.alignment = Pos.CENTER
        centerBox.padding = Insets(20.0)
        centerBox.style = "-fx-background-color: #FFFFFF;"

        // Make imageView VERY visible with a colored border
        imageView.style = "-fx-border-color: red; -fx-border-width: 5px; -fx-background-color: black;"

        centerBox.children.add(imageView)
        root.center = centerBox

        println("CameraView: getView() called, returning layout")

        return root
    }

    private fun startStreaming() {
        if (!connectionManager.isConnected()) {
            Platform.runLater {
                statusLabel.text = "Please connect to phone first"
            }
            return
        }

        isStreaming = true
        startButton.text = "Stop Live Preview"
        captureButton.isDisable = false

        streamingJob = scope.launch {
            println("CameraView: Streaming job started")
            var frameCount = 0

            while (isStreaming) {
                try {
//                    println("CameraView: Fetching frame ${++frameCount}...")
                    val frameBytes = connectionManager.fetchCameraFrame()

                    if (frameBytes != null && frameBytes.isNotEmpty()) {
//                        println("CameraView: Received ${frameBytes.size} bytes")

                        // Create image from bytes
                        val inputStream = ByteArrayInputStream(frameBytes)
                        val image = Image(inputStream)

                        // Check if image loaded successfully
                        if (image.isError) {
                            println("CameraView: ERROR - Image failed to load")
                            Platform.runLater {
                                statusLabel.text = "Error loading image"
                            }
                        } else {
//                            println("CameraView: Image created successfully (${image.width}x${image.height})")

                            // Update UI on JavaFX thread
                            Platform.runLater {
                                try {
                                    imageView.image = image
                                    statusLabel.text = "Streaming... (${frameBytes.size / 1024} KB) ${image.width.toInt()}x${image.height.toInt()}"
//                                    println("CameraView: UI updated successfully")
                                } catch (e: Exception) {
                                    println("CameraView: ERROR updating UI - ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        }
                    } else {
//                        println("CameraView: No frame received (null or empty)")
                        Platform.runLater {
                            statusLabel.text = "Waiting for camera feed..."
                        }
                    }
                } catch (e: Exception) {
                    println("CameraView: EXCEPTION in streaming loop - ${e.message}")
                    e.printStackTrace()
                    Platform.runLater {
                        statusLabel.text = "Error: ${e.message}"
                    }
                }

                delay(100) // 10 FPS
            }

            println("CameraView: Streaming job ended")
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        streamingJob?.cancel()
        startButton.text = "Start Live Preview"
        captureButton.isDisable = true

        Platform.runLater {
            statusLabel.text = "Streaming stopped"
        }
    }

    private fun capturePhoto() {
        scope.launch {
            try {
                println("CameraView: Capturing photo...")
                val frameBytes = connectionManager.fetchCameraFrame()

                if (frameBytes != null && frameBytes.isNotEmpty()) {
                    val image = Image(ByteArrayInputStream(frameBytes))

                    // Save to file
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    val fileName = "captured_$timestamp.jpg"
                    val picturesDir = File(System.getProperty("user.home"), "Pictures")
                    picturesDir.mkdirs()
                    val file = File(picturesDir, fileName)

                    val bufferedImage = SwingFXUtils.fromFXImage(image, null)
                    ImageIO.write(bufferedImage, "jpg", file)

                    println("CameraView: Photo saved to ${file.absolutePath}")

                    Platform.runLater {
                        // Update status with success message
                        statusLabel.text = "✅ Photo saved successfully!"
                        statusLabel.style = "-fx-text-fill: #008000; -fx-font-weight: bold; -fx-font-size: 14px;"

                        // Show popup dialog
                        val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION)
                        alert.title = "Photo Captured"
                        alert.headerText = "Photo saved successfully!"
                        alert.contentText = "Saved to:\n${file.absolutePath}\n\nFile size: ${file.length() / 1024} KB"
                        alert.show()

                        // Reset status after 3 seconds
                        scope.launch {
                            delay(3000)
                            if (isStreaming) {
                                Platform.runLater {
                                    statusLabel.style = "-fx-text-fill: #333333; -fx-font-size: 12px; -fx-padding: 0 0 0 20px;" // Reset to default style
                                }
                            }
                        }
                    }
                } else {
                    Platform.runLater {
                        statusLabel.text = "❌ Failed to capture - no frame available"
                        statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
                    }
                }
            } catch (e: Exception) {
                println("CameraView: Error capturing photo: ${e.message}")
                e.printStackTrace()
                Platform.runLater {
                    statusLabel.text = "❌ Error capturing photo"
                    statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"

                    // Show error dialog
                    val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR)
                    alert.title = "Capture Failed"
                    alert.headerText = "Failed to capture photo"
                    alert.contentText = "Error: ${e.message}"
                    alert.show()
                }
            }
        }
    }

    // Public methods for external control (called from MainApp)
    fun startStreamingFromMainApp() {
        println("CameraView: startStreamingFromMainApp() called")
        Platform.runLater {
            if (!isStreaming) {
                startStreaming()
            } else {
                println("CameraView: Already streaming, ignoring")
            }
        }
    }

    fun stopStreamingFromMainApp() {
        println("CameraView: stopStreamingFromMainApp() called")
        Platform.runLater {
            if (isStreaming) {
                stopStreaming()
            } else {
                println("CameraView: Already stopped, ignoring")
            }
        }
    }

    fun cleanup() {
        stopStreaming()
        scope.cancel()
    }
}