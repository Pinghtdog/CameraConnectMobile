package com.daanggit.nutrilens

import android.Manifest
import android.content.Intent // Import Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var ipAddressInput: EditText // Keep if you want manual input fallback
    private lateinit var connectButton: Button
    private lateinit var scanQrButton: Button // Reference to the new scan button

    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isStreaming = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    private val targetResolution = Size(640, 480) // Lower resolution = less data
    private val jpegQuality = 75 // 0-100; lower quality = less data

    // Store scanned IP and Port
    private var scannedIpAddress: String? = null
    private var scannedPort: Int = -1 // Use -1 to indicate no port scanned yet


    companion object {
        private const val TAG = "CameraStreamer"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val SERVER_PORT = 12345 // Default port, but will use scanned port if available
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        ipAddressInput = findViewById(R.id.ipAddressInput) // Keep if using manual input
        connectButton = findViewById(R.id.connectButton)
        scanQrButton = findViewById(R.id.scanQrButton) // Get reference to scan button


        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera() // Start camera feed for preview (and potential scanning if needed)
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // --- Button Listeners ---

        // Connect/Disconnect Button Logic
        connectButton.setOnClickListener {
            if (!isConnected.get()) {
                // Use scanned IP/Port if available, otherwise fallback to manual input
                val targetIp = scannedIpAddress ?: ipAddressInput.text.toString().trim()
                val targetPort = if (scannedPort != -1) scannedPort else SERVER_PORT

                if (targetIp.isNotEmpty()) {
                    connectToServer(targetIp, targetPort) // Pass target port
                } else {
                    showToast("Please scan QR code or enter PC IP Address")
                }
            } else {
                disconnectFromServer()
            }
        }

        // Scan QR Code Button Logic
        scanQrButton.setOnClickListener {
            // Check camera permission before launching scanner (required by library)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                initiateQrScan()
            } else {
                showToast("Camera permission is required to scan QR code.")
                // Request permission again if needed
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        }

        // Set default IP for easier testing (optional)
        // ipAddressInput.setText("192.168.1.100")
    }

    // --- Permission Handling ---
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                showToast("Permissions not granted by the user.")
                finish()
            }
        }
    }

    // --- Camera Setup (Remains largely the same) ---
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Preview Use Case
            val preview = Preview.Builder()
                .setTargetResolution(targetResolution)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Image Analysis Use Case (for getting frames)
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(targetResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Drop frames if busy
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isConnected.get() && isStreaming.get()) {
                    processAndSendFrame(imageProxy)
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Ensure preview is bound even if analysis isn't streaming yet
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.d(TAG, "Camera started and bound")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    // --- QR Scanning Logic ---

    private fun initiateQrScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE) // Only scan QR codes
        integrator.setPrompt("Scan the QR code on your PC") // Customize prompt
        integrator.setCameraId(0) // Use the rear camera
        integrator.setBeepEnabled(false) // Disable beep on scan
        integrator.setBarcodeImageEnabled(false) // Don't save scanned image
        integrator.initiateScan() // Start the scanning activity
    }

    // Handle the result from the scanning activity
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // Get the result from IntentIntegrator
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)

        if (result != null) {
            if (result.contents != null) {
                // QR Code scanned successfully
                val scannedData = result.contents // This is the string "IP_ADDRESS:PORT"
                Log.d(TAG, "Scanned QR data: $scannedData")
                parseAndConnect(scannedData)
            } else {
                // Scan cancelled
                showToast("Scan cancelled")
                Log.d(TAG, "Scan cancelled")
            }
        } else {
            // Handle results from other activities if you have any
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // Parse the scanned string (IP:PORT) and attempt connection
    private fun parseAndConnect(scannedData: String) {
        try {
            val parts = scannedData.split(":")
            if (parts.size == 2) {
                scannedIpAddress = parts[0]
                scannedPort = parts[1].toInt()

                Log.d(TAG, "Parsed IP: $scannedIpAddress, Port: $scannedPort")

                // Update UI (optional)
                ipAddressInput.setText(scannedIpAddress) // Show scanned IP in EditText
                // You could also automatically trigger connectToServer here:
                connectToServer(scannedIpAddress!!, scannedPort) // !! because we checked size == 2

            } else {
                showToast("Invalid QR code format. Expected IP:PORT")
                Log.e(TAG, "Invalid QR code format: $scannedData")
                scannedIpAddress = null // Clear invalid data
                scannedPort = -1
            }
        } catch (e: NumberFormatException) {
            showToast("Invalid port number in QR code.")
            Log.e(TAG, "Invalid port number in QR code: ${e.message}")
            scannedIpAddress = null // Clear invalid data
            scannedPort = -1
        } catch (e: Exception) {
            showToast("Error processing QR code data.")
            Log.e(TAG, "Error processing QR code data: ${e.message}")
            scannedIpAddress = null // Clear invalid data
            scannedPort = -1
        }
    }


    // --- Connection Logic (Modified to accept port) ---

    // Updated connectToServer to take ipAddress and port
    private fun connectToServer(ipAddress: String, port: Int) {
        if (isConnected.get()) return
        connectButton.isEnabled = false
        connectButton.text = "Connecting..."

        coroutineScope.launch {
            try {
                Log.d(TAG, "Attempting connection to $ipAddress:$port")
                // Use the provided port
                clientSocket = Socket(ipAddress, port)
                outputStream = DataOutputStream(clientSocket!!.getOutputStream())
                isConnected.set(true)
                isStreaming.set(true)
                Log.d(TAG, "Connected successfully")
                runOnUiThread {
                    connectButton.text = "Disconnect"
                    connectButton.isEnabled = true
                    showToast("Connected")
                    // Optional: Hide scan button after successful connection
                    // scanQrButton.visibility = View.GONE
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: ${e.message}", e)
                isConnected.set(false)
                isStreaming.set(false)
                runOnUiThread {
                    connectButton.text = "Connect"
                    connectButton.isEnabled = true
                    showToast("Connection Failed: ${e.message}")
                    // Optional: Show scan button again on failure
                    // scanQrButton.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed (Unknown): ${e.message}", e)
                isConnected.set(false)
                isStreaming.set(false)
                runOnUiThread {
                    connectButton.text = "Connect"
                    connectButton.isEnabled = true
                    showToast("Connection Failed")
                    // Optional: Show scan button again on failure
                    // scanQrButton.visibility = View.VISIBLE
                }
            }
        }
    }

    // Disconnect Logic (Remains the same)
    private fun disconnectFromServer() {
        if (!isConnected.get()) return
        connectButton.isEnabled = false
        isStreaming.set(false)

        coroutineScope.launch {
            try {
                outputStream?.close()
                clientSocket?.close()
                Log.d(TAG, "Disconnected")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing socket: ${e.message}", e)
            } finally {
                outputStream = null
                clientSocket = null
                isConnected.set(false)
                runOnUiThread {
                    connectButton.text = "Connect"
                    connectButton.isEnabled = true
                    showToast("Disconnected")
                    // Optional: Show scan button again on disconnect
                    // scanQrButton.visibility = View.VISIBLE
                }
            }
        }
    }

    // --- Frame Processing and Sending (Remains the same) ---
    private fun processAndSendFrame(imageProxy: ImageProxy) {
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Unsupported image format: ${imageProxy.format}")
            imageProxy.close()
            return
        }

        try {
            val jpegBytes = convertYuvToJpeg(imageProxy)
            sendFrameData(jpegBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing/sending frame: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun convertYuvToJpeg(imageProxy: ImageProxy): ByteArray {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), jpegQuality, out)
        return out.toByteArray()
    }

    private fun sendFrameData(jpegBytes: ByteArray) {
        if (!isConnected.get() || outputStream == null) return

        try {
            outputStream?.writeInt(jpegBytes.size)
            outputStream?.write(jpegBytes)
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send frame: ${e.message}")
            if (isConnected.get()) {
                runOnUiThread {
                    showToast("Connection Lost")
                    disconnectFromServer()
                }
            }
        }
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromServer()
        cameraExecutor.shutdown()
        coroutineScope.cancel()
        cameraProvider?.unbindAll()
        Log.d(TAG, "Activity destroyed, resources released")
    }
}