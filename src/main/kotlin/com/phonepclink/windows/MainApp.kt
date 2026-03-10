package com.phonepclink.windows

import javafx.application.Application
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.scene.image.ImageView
import kotlinx.coroutines.launch


class MainApp : Application() {

    private lateinit var connectionManager: ConnectionManager
    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var qrCodeGenerator: QRCodeGenerator
    private lateinit var statusLabel: Label
    private lateinit var ipTextField: TextField
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var showQRButton: Button
    private lateinit var autoDiscoverButton: Button
    private lateinit var cameraView: CameraView
    private lateinit var fileExplorer: FileExplorer

    override fun start(primaryStage: Stage) {
        primaryStage.title = "StreamBridge"

        connectionManager = ConnectionManager()
        discoveryManager = DiscoveryManager()
        qrCodeGenerator = QRCodeGenerator()

        // Create UI
        val root = createUI()

        val scene = Scene(root, 1000.0, 700.0)
        primaryStage.scene = scene
        primaryStage.show()

        // Setup connection callbacks
        setupConnectionCallbacks()

        // Setup discovery callbacks
        setupDiscoveryCallbacks()

        discoveryManager.startQrListener { phoneIp ->
            javafx.application.Platform.runLater {
                ipTextField.text = phoneIp
                connect()
                showAlert("QR Scan Detected!\nConnecting to $phoneIp...", Alert.AlertType.INFORMATION)
            }
        }
    }

    private fun createUI(): BorderPane {
        val root = BorderPane()

        // Top bar - Connection
        val topBar = createTopBar()
        root.top = topBar

        // Center - TabPane with Camera and Files
        val tabPane = TabPane()

        // Files tab
        fileExplorer = FileExplorer(connectionManager)
        val filesTab = Tab("Chat", fileExplorer.getView())
        filesTab.isClosable = false

        // Camera tab
        cameraView = CameraView(connectionManager)
        val cameraTab = Tab("Camera", cameraView.getView())
        cameraTab.isClosable = false

        tabPane.tabs.addAll(filesTab, cameraTab)
        root.center = tabPane

        return root
    }

    private fun createTopBar(): VBox {
        val vbox = VBox(10.0)
        vbox.padding = Insets(10.0)
        vbox.style = "-fx-background-color: #f0f0f0;"

        // Manual connection row
        val manualRow = HBox(10.0)
        manualRow.alignment = Pos.CENTER_LEFT

        val ipLabel = Label("Phone IP:")
        ipTextField = TextField("192.168.1.100")
        ipTextField.prefWidth = 150.0

        connectButton = Button("Connect")
        connectButton.setOnAction {
            connect()
        }

        disconnectButton = Button("Disconnect")
        disconnectButton.isDisable = true
        disconnectButton.setOnAction {
            disconnect()
        }

        statusLabel = Label("Status: Disconnected")
        statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"

        manualRow.children.addAll(ipLabel, ipTextField, connectButton, disconnectButton, statusLabel)

        // Easy connection row
        val easyRow = HBox(10.0)
        easyRow.alignment = Pos.CENTER_LEFT

        val easyLabel = Label("Easy Connect:")

        showQRButton = Button("📱 Show QR Code")
        showQRButton.setOnAction {
            showQRCode()
        }

        autoDiscoverButton = Button("🔍 Auto-Discover Devices")
        autoDiscoverButton.setOnAction {
            startAutoDiscovery()
        }

        easyRow.children.addAll(easyLabel, showQRButton, autoDiscoverButton)

        val instructionLabel = Label("Choose: Enter IP manually, scan QR code from phone, or auto-discover")
        instructionLabel.style = "-fx-font-size: 11px; -fx-text-fill: gray;"

        vbox.children.addAll(manualRow, easyRow, instructionLabel)
        return vbox
    }

    private fun connect() {
        val ip = ipTextField.text
        if (ip.isBlank()) {
            showAlert("Please enter phone IP address")
            return
        }

        connectionManager.connect(ip)
    }

    private fun disconnect() {
        connectionManager.disconnect()
    }

    private fun setupConnectionCallbacks() {
        connectionManager.onConnectionChanged = { connected ->
            javafx.application.Platform.runLater {
                if (connected) {
                    statusLabel.text = "Status: Connected"
                    statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
                    connectButton.isDisable = true
                    disconnectButton.isDisable = false
                    ipTextField.isDisable = true

                    // Auto-start camera streaming when connected
                    println("MainApp: Connection established, starting camera stream...")
                    cameraView.startStreamingFromMainApp()

                } else {
                    statusLabel.text = "Status: Disconnected"
                    statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
                    connectButton.isDisable = false
                    disconnectButton.isDisable = true
                    ipTextField.isDisable = false

                    // Stop streaming when disconnected
                    println("MainApp: Disconnected, stopping camera stream...")
                    cameraView.stopStreamingFromMainApp()
                }
            }
        }
    }

    private fun showQRCode() {
        val pcIp = qrCodeGenerator.getLocalIPAddress()
        val pcName = System.getProperty("user.name") + "'s PC"
        val qrImage = qrCodeGenerator.generateQRCode(pcIp, 8080, pcName)

        val imageView = ImageView(qrImage)
        imageView.fitWidth = 300.0
        imageView.fitHeight = 300.0

        val dialog = Alert(Alert.AlertType.INFORMATION)
        dialog.title = "Scan QR Code"
        dialog.headerText = "Scan this QR code with your phone"
        dialog.contentText = "1. Start server on phone\n2. Tap 'Scan QR Code'\n3. Point camera at this QR code"
        dialog.graphic = imageView
        dialog.showAndWait()
    }

    private fun startAutoDiscovery() {
        autoDiscoverButton.isDisable = true
        autoDiscoverButton.text = "🔍 Searching..."

        discoveryManager.startDiscovery()

        // Stop after 10 seconds
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(10000)
            javafx.application.Platform.runLater {
                discoveryManager.stopDiscovery()
                autoDiscoverButton.isDisable = false
                autoDiscoverButton.text = "🔍 Auto-Discover Devices"
            }
        }
    }

    private fun setupDiscoveryCallbacks() {
        discoveryManager.onDeviceFound = { deviceName, deviceIp, devicePort ->
            javafx.application.Platform.runLater {
                // Stop discovery immediately — we found a device and the user is deciding.
                // This also prevents any further duplicate serviceResolved callbacks from
                // popping up a second dialog while the first one is still open.
                discoveryManager.stopDiscovery()
                javafx.application.Platform.runLater {
                    autoDiscoverButton.isDisable = false
                    autoDiscoverButton.text = "🔍 Auto-Discover Devices"
                }

                val alert = Alert(Alert.AlertType.CONFIRMATION)
                alert.title = "Device Found"
                alert.headerText = "Found: $deviceName"
                alert.contentText = "IP: $deviceIp:$devicePort\n\nConnect to this device?"

                val result = alert.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    val pcName = System.getProperty("user.name") + "'s PC"
                    discoveryManager.requestPairing(deviceIp, pcName) { approved, phoneName ->
                        javafx.application.Platform.runLater {
                            if (approved) {
                                ipTextField.text = deviceIp
                                connect()
                                showAlert("Connected to ${phoneName ?: deviceName}", Alert.AlertType.INFORMATION)
                            } else {
                                showAlert("Connection denied by phone", Alert.AlertType.WARNING)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showAlert(message: String, type: Alert.AlertType = Alert.AlertType.WARNING) {
        val alert = Alert(type)
        alert.title = when(type) {
            Alert.AlertType.WARNING -> "Warning"
            Alert.AlertType.INFORMATION -> "Information"
            Alert.AlertType.ERROR -> "Error"
            else -> "Alert"
        }
        alert.headerText = null
        alert.contentText = message
        alert.showAndWait()
    }

    override fun stop() {
        connectionManager.disconnect()
        discoveryManager.stopDiscovery()
        super.stop()
    }
}

fun main() {
    Application.launch(MainApp::class.java)
}