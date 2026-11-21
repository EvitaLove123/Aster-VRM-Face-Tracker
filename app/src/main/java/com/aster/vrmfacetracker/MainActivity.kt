package com.aster.vrmfacetracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var faceState: TextView
    private lateinit var trackingValues: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null
    private var lastUiUpdate = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = "Camera permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.cameraPreview)
        statusText = findViewById(R.id.statusText)
        faceState = findViewById(R.id.faceState)
        trackingValues = findViewById(R.id.trackingValues)
        cameraExecutor = Executors.newSingleThreadExecutor()
        try {
            setupFaceLandmarker()
        } catch (error: Exception) {
            statusText.text = "Face model setup failed: ${error.message}"
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupFaceLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, _ -> updateTrackingUi(result) }
            .setErrorListener { error -> runOnUiThread {
                statusText.text = "Tracker error: ${error.message}"
            } }
            .build()
        faceLandmarker = FaceLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                analyzeFrame(image)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                statusText.text = "Camera active · looking for a face"
            } catch (error: Exception) {
                statusText.text = "Camera start failed: ${error.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val source = imageProxy.toBitmap()
            val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            val mpImage = BitmapImageBuilder(rotated).build()
            faceLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
            if (rotated !== source) rotated.recycle()
            source.recycle()
        } catch (error: Exception) {
            runOnUiThread { statusText.text = "Frame error: ${error.message}" }
        } finally {
            imageProxy.close()
        }
    }

    private fun updateTrackingUi(result: FaceLandmarkerResult) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdate < 100) return
        lastUiUpdate = now
        val categories = result.faceBlendshapes().orElse(emptyList()).firstOrNull()
        val values = categories?.associate { it.categoryName() to it.score() } ?: emptyMap()
        fun score(name: String): Float = values[name] ?: 0f
        val eyeBlink = maxOf(score("eyeBlinkLeft"), score("eyeBlinkRight"))
        val mouthOpen = score("jawOpen")
        val smile = maxOf(score("mouthSmileLeft"), score("mouthSmileRight"))
        val brow = maxOf(score("browInnerUp"), score("browOuterUpLeft"), score("browOuterUpRight"))
        runOnUiThread {
            faceState.text = if (result.faceLandmarks().isNotEmpty()) "Face: detected" else "Face: not detected"
            trackingValues.text = String.format(
                Locale.US,
                "Eyes blink: %.2f\nMouth open: %.2f · Smile: %.2f\nBrows raised: %.2f\nBlendshape channels: %d",
                eyeBlink, mouthOpen, smile, brow, values.size
            )
            statusText.text = "Live tracking · MediaPipe Face Landmarker"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceLandmarker?.close()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
