package com.blindassistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.*
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Background service that continuously captures camera frames and
 * narrates the scene with priority-based ordering:
 *   DANGER > OBJECT > ENVIRONMENT
 *
 * Announces immediately when hazards (steps, vehicles, obstacles) are detected.
 */
class ContinuousVisionService : Service() {

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastSpokenScene = ""
    private var frameCount = 0
    private lateinit var aiClient: AiClient

    companion object {
        var isActive = false
            private set
        var activeInstance: ContinuousVisionService? = null
            private set

        const val ACTION_START = "START_VISION"
        const val ACTION_STOP = "STOP_VISION"
        const val EXTRA_MODE = "vision_mode"
        const val CHANNEL_ID = "blind_assistant_vision_channel"

        fun start(context: Context, mode: String = "full") {
            val intent = Intent(context, ContinuousVisionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ContinuousVisionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun buildNotification(context: Context, text: String): Notification {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Vision Mode Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Blind AI Vision")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }

    private lateinit var capabilityChecker: DeviceCapabilityChecker
    private var captureIntervalMs = 1500L
    private var visionMode = "full" // full | danger

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        capabilityChecker = DeviceCapabilityChecker(this)
        captureIntervalMs = capabilityChecker.getContinuousVisionIntervalMs()
        
        aiClient = AiClient(GeminiConfig.API_KEY, GeminiConfig.DEFAULT_MODEL)

        startForeground(
            NOTIF_ID,
            buildNotification(this, "Vision Mode Active — watching surroundings")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                visionMode = intent.getStringExtra(EXTRA_MODE) ?: "full"
                if (!isRunning) startContinuousCapture()
                AndroidVoiceService.speakGlobally("Vision mode started. I will describe your surroundings.")
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousCapture() {
        isRunning = true
        isActive = true

        cameraThread = HandlerThread("ContinuousVisionThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        scope.launch {
            var backCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameraId = id
                    break
                }
            }
            if (backCameraId == null) {
                AndroidVoiceService.speakGlobally("No rear camera found.")
                return@launch
            }

            val w = capabilityChecker.getRecommendedCameraWidth()
            val h = capabilityChecker.getRecommendedCameraHeight()
            imageReader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 2)

            try {
                cameraManager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        startCaptureSession(device)
                    }
                    override fun onDisconnected(device: CameraDevice) { device.close() }
                    override fun onError(device: CameraDevice, error: Int) { device.close() }
                }, cameraHandler)
            } catch (e: SecurityException) {
                AndroidVoiceService.speakGlobally("Camera permission required for vision mode.")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startCaptureSession(device: CameraDevice) {
        val reader = imageReader ?: return
        device.createCaptureSession(
            listOf(reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startFrameLoop(session, device, reader)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    AndroidVoiceService.speakGlobally("Vision camera failed to configure.")
                }
            },
            cameraHandler
        )
    }

    private fun startFrameLoop(
        session: CameraCaptureSession,
        device: CameraDevice,
        reader: ImageReader
    ) {
        scope.launch {
            while (isRunning) {
                if (capabilityChecker.isLowBattery()) {
                    AndroidVoiceService.speakGlobally("Battery low. Vision mode paused to save power.")
                    delay(30000)
                    continue
                }

                // Capture a single frame
                val base64 = captureFrame(session, device, reader)
                if (base64 != null) {
                    frameCount++
                    // Only send every other frame to AI on low-end devices
                    val shouldAnalyze = when (capabilityChecker.profile) {
                        DeviceCapabilityChecker.QualityProfile.LOW -> frameCount % 3 == 0
                        DeviceCapabilityChecker.QualityProfile.MEDIUM -> frameCount % 2 == 0
                        DeviceCapabilityChecker.QualityProfile.HIGH -> true
                    }

                    if (shouldAnalyze) {
                        analyzeAndSpeak(base64)
                    }
                }

                delay(captureIntervalMs)
            }
        }
    }

    private suspend fun captureFrame(
        session: CameraCaptureSession,
        device: CameraDevice,
        reader: ImageReader
    ): String? {
        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
        reader.setOnImageAvailableListener({ r ->
            var image: android.media.Image? = null
            try {
                image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                deferred.complete(Base64.encodeToString(bytes, Base64.NO_WRAP))
            } catch (_: Exception) {
                deferred.complete(null)
            } finally {
                image?.close()
            }
        }, cameraHandler)

        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            session.capture(req.build(), null, cameraHandler)
        } catch (_: Exception) {
            return null
        }

        return withTimeoutOrNull(3000) { deferred.await() }
    }

    private suspend fun analyzeAndSpeak(base64: String) {
        try {
            // CLOUD CONTEXTUAL ANALYSIS
            val prompt = buildVisionPrompt()
            val description = aiClient.askWithVision(prompt, base64)

            if (description.isBlank() || description == lastSpokenScene) return

            // Priority routing — danger interrupts anything
            val isDanger = containsDanger(description)
            if (isDanger || isSignificantlyDifferent(description, lastSpokenScene)) {
                lastSpokenScene = description
                val announcement = if (isDanger) "⚠ WARNING: $description" else description
                AndroidVoiceService.speakGlobally(announcement)
            }
        } catch (_: Exception) {}
    }

    private fun buildVisionPrompt(): String {
        return when (visionMode) {
            "danger", "obstacles" -> "You are a safety assistant for a blind person. Look for immediate dangers only: steps, stairs, vehicles, holes, low beams, wet floors, approaching people. Describe in ONE short sentence if any danger exists. If no danger, say 'Clear'."
            else -> "You are a safety guide for a blind person. Scan this scene and respond with a single short sentence describing: first any immediate dangers (steps, vehicles, obstacles), then surrounding people, then objects. Use directions: left, right, ahead, behind. Be concise. If the scene is clear and safe, say 'Path clear'."
        }
    }

    private fun containsDanger(text: String): Boolean {
        val dangerWords = listOf(
            "step", "stair", "vehicle", "car", "bike", "motorcycle", "obstacle",
            "hole", "gap", "puddle", "wet", "low", "warning", "danger",
            "approaching", "moving"
        )
        val lower = text.lowercase()
        return dangerWords.any { lower.contains(it) }
    }

    private fun isSignificantlyDifferent(newDesc: String, lastDesc: String): Boolean {
        if (lastDesc.isBlank()) return true
        val newWords = newDesc.lowercase().split(" ").toSet()
        val lastWords = lastDesc.lowercase().split(" ").toSet()
        val intersection = newWords.intersect(lastWords)
        val similarity = if (newWords.isEmpty()) 0.0 else intersection.size.toDouble() / newWords.size
        return similarity < 0.6 // less than 60% overlap → significantly different
    }

    private fun stopCapture() {
        isRunning = false
        isActive = true // keep isActive true until stopSelf is done or just set to false here
        isActive = false
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            cameraThread?.quitSafely()
            cameraThread = null
        } catch (_: Exception) {}
        scope.cancel()
    }

    override fun onDestroy() {
        stopCapture()
        isActive = false
        activeInstance = null
        super.onDestroy()
    }

    private val NOTIF_ID = 7003
}
