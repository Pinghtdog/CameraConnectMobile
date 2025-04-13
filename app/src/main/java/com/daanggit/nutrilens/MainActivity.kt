package com.daanggit.nutrilens
import android.Manifest
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
    private lateinit var ipAddressInput: EditText
    private lateinit var connectButton: Button

    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isStreaming = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    private val targetResolution = Size(640, 480) // Lower resolution = less data
    private val jpegQuality = 75 // 0-100; lower quality = less data

    companion object {
        private const val TAG = "CameraStreamer"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val SERVER_PORT = 12345 // Must match JavaFX server port
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Use your layout file name

        previewView = findViewById(R.id.previewView)
        ipAddressInput = findViewById(R.id.ipAddressInput)
        connectButton = findViewById(R.id.connectButton)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        connectButton.setOnClickListener {
            if (!isConnected.get()) {
                val ipAddress = ipAddressInput.text.toString().trim()
                if (ipAddress.isNotEmpty()) {
                    connectToServer(ipAddress)
                } else {
                    showToast("Please enter PC IP Address")
                }
            } else {
                disconnectFromServer()
            }
        }
        // Set default IP for easier testing (optional)
        // ipAddressInput.setText("192.168.1.100") // Replace with your PC's typical IP if known
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
                // Process image only if connected and streaming
                if (isConnected.get() && isStreaming.get()) {
                    processAndSendFrame(imageProxy)
                } else {
                    imageProxy.close() // Must close imageProxy when done
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA // Or .DEFAULT_FRONT_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.d(TAG, "Camera started and bound")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun connectToServer(ipAddress: String) {
        if (isConnected.get()) return
        connectButton.isEnabled = false // Prevent multiple clicks
        connectButton.text = "Connecting..."

        coroutineScope.launch {
            try {
                Log.d(TAG, "Attempting connection to $ipAddress:$SERVER_PORT")
                clientSocket = Socket(ipAddress, SERVER_PORT)
                outputStream = DataOutputStream(clientSocket!!.getOutputStream()) // Use buffered for potential speedup?
                isConnected.set(true)
                isStreaming.set(true) // Start streaming frames now
                Log.d(TAG, "Connected successfully")
                runOnUiThread {
                    connectButton.text = "Disconnect"
                    connectButton.isEnabled = true
                    showToast("Connected")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: ${e.message}", e)
                isConnected.set(false)
                isStreaming.set(false)
                runOnUiThread {
                    connectButton.text = "Connect"
                    connectButton.isEnabled = true
                    showToast("Connection Failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed (Unknown): ${e.message}", e)
                isConnected.set(false)
                isStreaming.set(false)
                runOnUiThread {
                    connectButton.text = "Connect"
                    connectButton.isEnabled = true
                    showToast("Connection Failed")
                }
            }
        }
    }

    private fun disconnectFromServer() {
        if (!isConnected.get()) return
        connectButton.isEnabled = false // Prevent multiple clicks
        isStreaming.set(false) // Stop sending frames immediately

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
                }
            }
        }
    }

    private fun processAndSendFrame(imageProxy: ImageProxy) {
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Unsupported image format: ${imageProxy.format}")
            imageProxy.close()
            return
        }

        try {
            val jpegBytes = convertYuvToJpeg(imageProxy)

            // Send frame over socket (already on background thread via cameraExecutor/coroutines)
            sendFrameData(jpegBytes)

        } catch (e: Exception) {
            // Catch potential errors during conversion or sending
            Log.e(TAG, "Error processing/sending frame: ${e.message}", e)
            // Consider disconnecting on error
            // runOnUiThread { disconnectFromServer() }
        } finally {
            imageProxy.close() // Crucial: Always close the ImageProxy
        }
    }

    // Function to convert YUV_420_888 ImageProxy to JPEG byte array
    private fun convertYuvToJpeg(imageProxy: ImageProxy): ByteArray {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        //U and V are swapped in NV21 vs YUV_420_888 plane order
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize) // V plane (plane[2]) data goes first after Y
        uBuffer.get(nv21, ySize + vSize, uSize) // U plane (plane[1]) data goes last

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), jpegQuality, out)
        return out.toByteArray()
    }

    private fun sendFrameData(jpegBytes: ByteArray) {
        if (!isConnected.get() || outputStream == null) return // Check connection again

        try {
            // Protocol: Send size (4-byte integer) first, then the JPEG bytes
            outputStream?.writeInt(jpegBytes.size)
            outputStream?.write(jpegBytes)
            outputStream?.flush() // Try to send immediately
            // Log.v(TAG, "Sent frame: ${jpegBytes.size} bytes") // Very verbose log
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send frame: ${e.message}")
            // Handle error - Assume connection is broken, trigger disconnect UI update
            if (isConnected.get()) { // Prevent triggering disconnect multiple times
                runOnUiThread {
                    showToast("Connection Lost")
                    disconnectFromServer() // Attempt graceful disconnect and UI update
                }
            }
        }
    }


    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromServer() // Ensure disconnection
        cameraExecutor.shutdown()
        coroutineScope.cancel() // Cancel any ongoing coroutines
        cameraProvider?.unbindAll()
        Log.d(TAG, "Activity destroyed, resources released")
    }
}