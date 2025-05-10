package com.daanggit.nutrilens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
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
import java.util.concurrent.atomic.AtomicReference // For holding the latest frame for capture

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var ipAddressInput: EditText
    private lateinit var connectButton: Button
    private lateinit var scanQrButton: Button
    private lateinit var captureButton: Button // Declare the new capture button

    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isStreaming = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    private val targetResolution = Size(640, 480)
    private val jpegQuality = 80 // Can be slightly higher for stills if desired

    // Store scanned IP and Port
    private var scannedIpAddress: String? = null
    private var scannedPort: Int = -1

    // Flag to indicate a still image capture is requested
    private val captureImageRequested = AtomicBoolean(false)
    // To hold the latest frame if needed for a clean capture, though we'll try to use the one from the analyzer directly
    private val latestImageProxy = AtomicReference<ImageProxy?>()


    companion object {
        private const val TAG = "CameraStreamer"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val SERVER_PORT = 12345

        // Define message types to send to JavaFX
        private const val TYPE_VIDEO_FRAME: Byte = 1
        private const val TYPE_CAPTURED_IMAGE: Byte = 2 // New type for single image
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        ipAddressInput = findViewById(R.id.ipAddressInput)
        connectButton = findViewById(R.id.connectButton)
        scanQrButton = findViewById(R.id.scanQrButton)
        captureButton = findViewById(R.id.captureButton) // Initialize the capture button

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        connectButton.setOnClickListener {
            if (!isConnected.get()) {
                val targetIp = scannedIpAddress ?: ipAddressInput.text.toString().trim()
                val targetPort = if (scannedPort != -1) scannedPort else SERVER_PORT
                if (targetIp.isNotEmpty()) {
                    connectToServer(targetIp, targetPort)
                } else {
                    showToast("Please scan QR code or enter PC IP Address")
                }
            } else {
                disconnectFromServer()
            }
        }

        scanQrButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                initiateQrScan()
            } else {
                showToast("Camera permission is required to scan QR code.")
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        }

        // Capture Button Listener
        captureButton.setOnClickListener {
            if (isConnected.get() && cameraProvider != null) {
                if (!captureImageRequested.get()) { // Prevent multiple rapid requests if one is processing
                    showToast("Capturing image...")
                    captureImageRequested.set(true)
                    // The imageAnalysis.setAnalyzer will pick up this flag
                }
            } else {
                showToast("Not connected or camera not ready to capture.")
            }
        }
    }

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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .setTargetResolution(targetResolution)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(targetResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                // Atomically get and set the latest frame. Close the previous one.
                val oldFrame = latestImageProxy.getAndSet(imageProxy)
                oldFrame?.close() // Important to close the previous frame

                if (isConnected.get()) {
                    if (captureImageRequested.compareAndSet(true, false)) {
                        // Capture requested: process current imageProxy as a still
                        val currentFrameToCapture = latestImageProxy.get() // Get the latest frame
                        if (currentFrameToCapture != null) {
                            processAndSendStillImage(currentFrameToCapture)
                            // Do NOT close currentFrameToCapture here if processAndSendStillImage will use it AND close it.
                            // The latestImageProxy will be replaced by the next frame from the analyzer.
                            // If processAndSendStillImage makes a copy or uses it synchronously and closes it, this is fine.
                        } else {
                            imageProxy.close() // If somehow latestImageProxy was null, close current one.
                        }
                    } else if (isStreaming.get()) {
                        // Video streaming: process imageProxy as a video frame
                        // Pass the current imageProxy directly, not the one from latestImageProxy
                        // as it might be a frame behind in some race conditions.
                        processAndSendVideoFrame(imageProxy) // This function MUST close imageProxy
                    } else {
                        imageProxy.close() // Not streaming and not capturing, close it.
                    }
                } else {
                    imageProxy.close() // Not connected, close it.
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.d(TAG, "Camera started and bound")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun initiateQrScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Scan the QR code on your PC")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.setBarcodeImageEnabled(false)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val scannedData = result.contents
                Log.d(TAG, "Scanned QR data: $scannedData")
                parseAndConnect(scannedData)
            } else {
                showToast("Scan cancelled")
                Log.d(TAG, "Scan cancelled")
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun parseAndConnect(scannedData: String) {
        try {
            val parts = scannedData.split(":")
            if (parts.size == 2) {
                scannedIpAddress = parts[0]
                scannedPort = parts[1].toInt()
                Log.d(TAG, "Parsed IP: $scannedIpAddress, Port: $scannedPort")
                ipAddressInput.setText(scannedIpAddress)
                connectToServer(scannedIpAddress!!, scannedPort)
            } else {
                showToast("Invalid QR code format. Expected IP:PORT")
                scannedIpAddress = null; scannedPort = -1
            }
        } catch (e: NumberFormatException) {
            showToast("Invalid port number in QR code.")
            Log.e(TAG, "Invalid port number in QR: ${e.message}")
            scannedIpAddress = null; scannedPort = -1
        } catch (e: Exception) {
            showToast("Error processing QR code data.")
            Log.e(TAG, "Error processing QR: ${e.message}")
            scannedIpAddress = null; scannedPort = -1
        }
    }

    private fun connectToServer(ipAddress: String, port: Int) {
        if (isConnected.get()) return
        connectButton.isEnabled = false
        connectButton.text = "Connecting..."
        captureButton.isEnabled = false // Disable while connecting

        coroutineScope.launch {
            try {
                Log.d(TAG, "Attempting connection to $ipAddress:$port")
                clientSocket = Socket(ipAddress, port)
                outputStream = DataOutputStream(clientSocket!!.getOutputStream())
                isConnected.set(true)
                isStreaming.set(true) // Start video streaming by default on connection
                Log.d(TAG, "Connected successfully")
                runOnUiThread {
                    connectButton.text = "Disconnect"
                    connectButton.isEnabled = true
                    captureButton.isEnabled = true // Enable capture button on successful connection
                    showToast("Connected. Video streaming started.")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: ${e.message}", e)
                isConnected.set(false); isStreaming.set(false)
                runOnUiThread {
                    connectButton.text = "Connect"
                    connectButton.isEnabled = true
                    captureButton.isEnabled = false
                    showToast("Connection Failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed (Unknown): ${e.message}", e)
                isConnected.set(false); isStreaming.set(false)
                runOnUiThread {
                    connectButton.text = "Connect"
                    connectButton.isEnabled = true
                    captureButton.isEnabled = false
                    showToast("Connection Failed")
                }
            }
        }
    }

    private fun disconnectFromServer() {
        if (!isConnected.get() && clientSocket == null) return // Already disconnected or never connected
        // isStreaming.set(false) // Setting this early might cause issues if a send is in progress
        connectButton.isEnabled = false // Disable button during disconnection process

        coroutineScope.launch {
            val wasConnected = isConnected.getAndSet(false) // Ensure we only try to disconnect if we thought we were connected
            isStreaming.set(false) // Stop streaming flag

            try {
                outputStream?.flush() // Try to flush any pending data
                outputStream?.close()
                clientSocket?.close()
                Log.d(TAG, "Disconnected")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing socket: ${e.message}", e)
            } finally {
                outputStream = null
                clientSocket = null
                latestImageProxy.getAndSet(null)?.close() // Clear and close any held frame
                captureImageRequested.set(false) // Reset flag

                if (wasConnected) { // Only show toast and update UI if we were actually connected
                    runOnUiThread {
                        connectButton.text = "Connect"
                        connectButton.isEnabled = true
                        captureButton.isEnabled = false // Disable capture button on disconnect
                        showToast("Disconnected")
                    }
                } else { // If we weren't "officially" connected, still ensure button is re-enabled
                    runOnUiThread {
                        connectButton.text = "Connect"
                        connectButton.isEnabled = true
                        captureButton.isEnabled = false
                    }
                }
            }
        }
    }

    // Renamed your original processAndSendFrame for clarity
    private fun processAndSendVideoFrame(imageProxy: ImageProxy) {
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Unsupported image format for video: ${imageProxy.format}")
            imageProxy.close()
            return
        }
        try {
            val jpegBytes = convertYuvToJpeg(imageProxy, jpegQuality) // This MUST close the imageProxy
            sendFrameData(jpegBytes, TYPE_VIDEO_FRAME)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing/sending video frame: ${e.message}", e)
        } finally {
            // Ensure imageProxy is closed if convertYuvToJpeg didn't or threw an error before closing it.
            // However, convertYuvToJpeg SHOULD close it. If it might not, add:
            // if (imageProxy.image != null) imageProxy.close()
        }
    }

    // New function to process and send a single captured image
    private fun processAndSendStillImage(imageProxy: ImageProxy) {
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Unsupported image format for capture: ${imageProxy.format}")
            imageProxy.close() // Close it as we can't process it
            runOnUiThread { showToast("Capture failed: unsupported format") }
            return
        }

        Log.d(TAG, "Processing single image for capture.")
        try {
            // Use a potentially different (higher) quality for stills
            val jpegBytes = convertYuvToJpeg(imageProxy, 90) // This MUST close the imageProxy
            sendFrameData(jpegBytes, TYPE_CAPTURED_IMAGE)
            runOnUiThread { showToast("Image captured and sent!") }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing/sending captured image: ${e.message}", e)
            runOnUiThread { showToast("Failed to send captured image.") }
        } finally {
            // Ensure imageProxy is closed if convertYuvToJpeg didn't or an error occurred.
            // As convertYuvToJpeg is designed to close it, this is a safeguard.
            // if (imageProxy.image != null) imageProxy.close()
            // No need to clear latestImageProxy here, the analyzer loop will replace it.
        }
    }

    // Modified to take quality and ensure ImageProxy is closed
    private fun convertYuvToJpeg(imageProxy: ImageProxy, quality: Int): ByteArray {
        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            // For NV21 format, V plane data comes first in the UV interleaved buffer, then U.
            // The U and V planes are swapped in order in the planes array for YUV_420_888.
            // planes[0] is Y, planes[1] is U, planes[2] is V.
            // For NV21, after Y, it's VU VU VU...
            // So we take V buffer then U buffer if they are separate.
            // If planes[1] and planes[2] have pixelStride > 1, they are interleaved.
            // For simplicity assuming direct plane copy will form NV21 if ordered correctly (Y, then V, then U data).

            // Correct order for NV21 from YUV_420_888 planes:
            // Y plane first
            // Then V plane (imageProxy.planes[2])
            // Then U plane (imageProxy.planes[1])
            // However, the typical way is to copy Y, then interleave U and V or copy them if they are already in NV21 like structure in parts.
            // A common simplified approach if direct buffer copies are used:
            vBuffer.get(nv21, ySize, vSize) // Copy V buffer after Y
            uBuffer.get(nv21, ySize + vSize, uSize) // Copy U buffer after V

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), quality, out)
            return out.toByteArray()
        } finally {
            imageProxy.close() // IMPORTANT: Close the ImageProxy here after using its buffers
        }
    }

    // Modified to send a type byte before the data
    private fun sendFrameData(jpegBytes: ByteArray, frameType: Byte) {
        if (!isConnected.get() || outputStream == null) {
            Log.w(TAG, "Not connected or output stream is null. Cannot send frame data. Type: $frameType")
            // If this was a capture attempt, reset the flag
            if (frameType == TYPE_CAPTURED_IMAGE) {
                captureImageRequested.set(false) // Allow another attempt
            }
            return
        }

        try {
            outputStream?.writeByte(frameType.toInt()) // 1. Send the type of frame (distinguisher)
            outputStream?.writeInt(jpegBytes.size)    // 2. Send the size of the frame
            outputStream?.write(jpegBytes)            // 3. Send the frame data
            outputStream?.flush()
            Log.d(TAG, "Sent frame type: $frameType, size: ${jpegBytes.size}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send frame type $frameType: ${e.message}")
            // If connection is lost during send, trigger disconnect logic
            if (isConnected.get()) { // Check if we thought we were connected
                runOnUiThread {
                    showToast("Connection Lost")
                }
                // Schedule disconnect on the coroutine scope to avoid blocking current thread
                // and to handle socket closing properly.
                coroutineScope.launch {
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
        Log.d(TAG, "onDestroy called. Cleaning up...")
        // Ensure disconnection happens and resources are released.
        // Launch in a new scope if the activity scope is cancelling.
        GlobalScope.launch(Dispatchers.IO) { // Use GlobalScope if coroutineScope might be cancelled
            disconnectFromServer() // This will also close the latestImageProxy
        }
        cameraExecutor.shutdown()
        // cameraProvider?.unbindAll() // This might be called too late if disconnectFromServer handles it.
        // disconnectFromServer should ideally be the primary cleanup for network.
        // Unbinding camera is also good.
        if (cameraProvider != null && !cameraExecutor.isShutdown) {
            cameraProvider?.unbindAll() // Ensure camera is unbound if executor still active
        }
        coroutineScope.cancel() // Cancel jobs started by this activity's scope
        Log.d(TAG, "Activity destroyed, resources released")
    }
}