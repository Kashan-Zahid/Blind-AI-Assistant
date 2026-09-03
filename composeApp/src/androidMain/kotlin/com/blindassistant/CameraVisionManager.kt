package com.blindassistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

/**
 * CameraVisionManager handles targeted assistive vision capture and analysis.
 * Adheres strictly to privacy and resource guidelines:
 * - Camera only activates upon user command.
 * - Stops and releases all camera resources immediately after capture.
 * - Does not stream or store continuous video indefinitely.
 */
class CameraVisionManager(private val context: Context, private val aiClient: AiClient) {

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraVisionThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        try {
            cameraThread?.quitSafely()
            cameraThread?.join(500)
        } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Executes the "Describe around me" environmental scene analysis.
     */
    @SuppressLint("MissingPermission")
    suspend fun describeAroundMe(): String {
        if (!hasCameraPermission()) {
            return "Camera permission is required to describe your surroundings."
        }

        // Spoken cue to user
        AndroidVoiceService.speakGlobally("Camera activated. Please hold the phone steady.")

        val base64Image = captureFrameInternal("Surroundings Scanner")
        if (base64Image == null) {
            return "I couldn't capture an image from the camera. Please hold the device steady and try again."
        }

        val environmentalPrompt = """
            You are an assistive vision assistant for a blind user. Describe the environment captured in this image clearly and concisely.
            Prioritize:
            1. Large obstacles or hazards (stairs, steps, doorways, open paths, vehicles).
            2. Key room or outdoor features (tables, chairs, walls, doors, exits).
            3. People present.
            4. Relative positions (e.g., ahead of you, to your left, on your right, approximately X meters).
            
            Guidelines:
            - Keep the description natural and spoken, between 2 and 4 sentences.
            - Do not guarantee collision safety or claim absolute distance certainty.
            - Avoid markdown, bullet points, asterisks, or formatting.
        """.trimIndent()

        return try {
            val response = aiClient.askWithVision(environmentalPrompt, base64Image)
            if (response.isBlank()) {
                "I couldn't analyze the surroundings. Please try again."
            } else {
                response
            }
        } catch (_: Exception) {
            "I couldn't analyze the surroundings. Please check your internet connection."
        }
    }

    /**
     * General scene capture and description based on specific modes.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureAndDescribeScene(promptMode: String = "describe"): String {
        if (!hasCameraPermission()) {
            return "Camera permission is required to analyze your surroundings."
        }

        val taskTitle = when (promptMode) {
            "currency" -> "Currency Reader"
            "object" -> "Object Identifier"
            "obstacles", "danger" -> "Obstacle Scanner"
            else -> "Scene Analysis"
        }
        val base64Image = captureFrameInternal(taskTitle)
        if (base64Image == null) {
            return "I couldn't capture an image from the camera. Please hold the device steady and try again."
        }

        val customPrompt = when (promptMode) {
            "currency" -> "Identify every currency note, coin, or denomination visible. State the total value if multiple notes. Be specific about currency type."
            "object" -> "Describe the main object held in front of the camera, its shape, colour, brand name if visible, and what it is used for."
            "obstacles", "danger" -> "Scan this image for immediate hazards only: steps, stairs, holes, vehicles, sharp objects, wet floors, low beams. State any danger in one concise sentence. If clear, say 'Path appears clear'."
            else -> "You are an assistive vision guide for a blind person. Describe this scene clearly. First mention any obstacles or doorways. Then people, tables, chairs. Give approximate positions: left, right, ahead."
        }

        return try {
            val response = aiClient.askWithVision(customPrompt, base64Image)
            if (response.isBlank()) {
                "I couldn't analyze the scene. Please try again."
            } else {
                response
            }
        } catch (_: Exception) {
            "I couldn't analyze the scene. Please check your internet connection."
        }
    }

    /**
     * OCR: Reads all visible text on signs, labels, packages, or books.
     */
    @SuppressLint("MissingPermission")
    suspend fun readTextOCR(): String {
        if (!hasCameraPermission()) {
            return "Camera permission is required to read text."
        }
        AndroidVoiceService.speakGlobally("Scanning for text. Please hold steady.")
        val base64Image = captureFrameInternal("Text & OCR Reader") ?: return "Could not capture image. Please hold steady and try again."
        val prompt = """
            You are an assistive OCR text reader for a blind user.
            Read all visible text in this image accurately and verbatim.
            If this is a product label, read the brand and product title first, followed by key instructions, ingredients, warnings, or expiry dates.
            If this is a sign or document, read it top to bottom in logical order.
            If no text is visible, respond with: "No readable text detected in front of the camera."
            Keep the output spoken and natural. Do not use markdown, bullet points, or asterisks.
        """.trimIndent()
        return try {
            val response = aiClient.askWithVision(prompt, base64Image)
            if (response.isBlank()) "No readable text detected in front of the camera." else response
        } catch (_: Exception) {
            "Could not read text. Please check your internet connection."
        }
    }

    /**
     * Currency reader: Identifies banknotes, coins, and total value.
     */
    @SuppressLint("MissingPermission")
    suspend fun identifyCurrency(): String {
        if (!hasCameraPermission()) {
            return "Camera permission is required to identify currency."
        }
        AndroidVoiceService.speakGlobally("Checking currency. Please hold the money steady.")
        val base64Image = captureFrameInternal("Currency Counter") ?: return "Could not capture image. Please hold steady and try again."
        val prompt = """
            You are an assistive currency recognizer for a blind user.
            Identify all banknotes, bills, or coins in this image.
            State the exact denomination and currency name clearly (e.g. "One hundred US dollars", "Five hundred Pakistani rupees", "Twenty euros").
            If multiple bills or coins are present, list each one and state the total combined amount.
            If no currency is visible, say: "I do not see any money or currency in front of the camera."
            Keep the response concise, clear, and spoken. Do not use markdown.
        """.trimIndent()
        return try {
            val response = aiClient.askWithVision(prompt, base64Image)
            if (response.isBlank()) "I do not see any currency in front of the camera." else response
        } catch (_: Exception) {
            "Could not identify currency. Please check your internet connection."
        }
    }

    /**
     * Color detector: Identifies dominant colors of clothes or items for matching.
     */
    @SuppressLint("MissingPermission")
    suspend fun detectColor(): String {
        if (!hasCameraPermission()) {
            return "Camera permission is required to detect colors."
        }
        AndroidVoiceService.speakGlobally("Detecting colors. Please hold steady.")
        val base64Image = captureFrameInternal("Color Detector") ?: return "Could not capture image. Please hold steady and try again."
        val prompt = """
            You are an assistive color identifier for a visually impaired user.
            Identify the exact colors and patterns of the item, clothing, or object held in front of the camera.
            Be specific with color names (e.g., "Navy blue", "Olive green", "Light grey with white stripes") to help the user match clothing or recognize items.
            Provide a direct, natural 1-2 sentence spoken answer without markdown or bullet points.
        """.trimIndent()
        return try {
            val response = aiClient.askWithVision(prompt, base64Image)
            if (response.isBlank()) "Could not determine the colors in front of the camera." else response
        } catch (_: Exception) {
            "Could not detect colors. Please check your internet connection."
        }
    }

    /**
     * Object finder: Searches the scene for a specific item (e.g. keys, glasses, door).
     */
    @SuppressLint("MissingPermission")
    suspend fun findTargetObject(target: String): String {
        if (!hasCameraPermission()) {
            return "Camera permission is required to look for $target."
        }
        AndroidVoiceService.speakGlobally("Looking for $target. Please hold steady.")
        val base64Image = captureFrameInternal("Looking for $target") ?: return "Could not capture image. Please hold steady and try again."
        val prompt = """
            You are an assistive guide helping a blind person locate their '$target'.
            Carefully search this image for '$target'.
            If found, clearly describe its relative position using clock directions (e.g., 'at your 12 o'clock', 'at your 2 o'clock'), whether it is on a table/floor, and approximate distance in feet or meters.
            If '$target' is not visible in this frame, say: "I don't see $target in this view. Try panning your camera slightly to the left or right."
            Keep it concise and spoken. Do not use markdown.
        """.trimIndent()
        return try {
            val response = aiClient.askWithVision(prompt, base64Image)
            if (response.isBlank()) "I do not see $target in front of the camera." else response
        } catch (_: Exception) {
            "Could not search for $target. Please check your internet connection."
        }
    }

    /**
     * Document & Mail reader: Reads letters, receipts, and documents top to bottom.
     */
    @SuppressLint("MissingPermission")
    suspend fun describeDocument(): String {
        if (!hasCameraPermission()) {
            return "Camera permission is required to read documents."
        }
        AndroidVoiceService.speakGlobally("Scanning document. Please hold steady.")
        val base64Image = captureFrameInternal("Document Reader") ?: return "Could not capture image. Please hold steady and try again."
        val prompt = """
            You are an assistive document reader for a blind user.
            Analyze this document, receipt, bill, or letter.
            First, state what type of document it is and the sender or company name.
            Then read the key content, dates, and amounts in clear logical order.
            Do not use markdown formatting.
        """.trimIndent()
        return try {
            val response = aiClient.askWithVision(prompt, base64Image)
            if (response.isBlank()) "No readable document found in front of the camera." else response
        } catch (_: Exception) {
            "Could not read the document. Please check your internet connection."
        }
    }

    /**
     * Product identifier: Identifies groceries, medicine, or household cans.
     */
    @SuppressLint("MissingPermission")
    suspend fun identifyProduct(): String {
        if (!hasCameraPermission()) {
            return "Camera permission is required to identify products."
        }
        AndroidVoiceService.speakGlobally("Identifying product. Please hold the item steady.")
        val base64Image = captureFrameInternal("Product Identifier") ?: return "Could not capture image. Please hold steady and try again."
        val prompt = """
            You are an assistive product identifier for a blind person holding a food item, medicine, can, or household product in front of the camera.
            Identify the brand name, product name, flavor or variant, and any important warnings or dosage notes visible on the packaging.
            Keep the response spoken, helpful, and concise without markdown.
        """.trimIndent()
        return try {
            val response = aiClient.askWithVision(prompt, base64Image)
            if (response.isBlank()) "Could not identify the product. Please rotate the item and try again." else response
        } catch (_: Exception) {
            "Could not identify the product. Please check your internet connection."
        }
    }

    /**
     * Captures a single sharp JPEG frame and immediately shuts down camera.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureRawBase64(taskTitle: String = "Camera Scanner"): String? {
        if (!hasCameraPermission()) return null
        return captureFrameInternal(taskTitle)
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureFrameInternal(taskTitle: String = "Camera Scanner"): String? {
        AndroidVoiceService.showCameraPopupGlobally(
            title = taskTitle,
            status = "Camera active. Hold phone steady..."
        )
        startBackgroundThread()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: run {
            stopBackgroundThread()
            AndroidVoiceService.showCameraPopupGlobally(
                title = taskTitle,
                status = "Camera unavailable."
            )
            return null
        }

        val deferredImage = CompletableDeferred<String?>()
        var imageReader: ImageReader? = null
        var openCamera: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null

        try {
            var backCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameraId = id
                    break
                }
            }

            if (backCameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                backCameraId = cameraManager.cameraIdList[0]
            }

            if (backCameraId == null) {
                stopBackgroundThread()
                AndroidVoiceService.showCameraPopupGlobally(
                    title = taskTitle,
                    status = "No camera found."
                )
                return null
            }

            val characteristics = cameraManager.getCameraCharacteristics(backCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
            val optimalSize = jpegSizes?.firstOrNull { it.width in 1280..1920 && it.height in 720..1080 }
                ?: jpegSizes?.firstOrNull { it.width >= 1024 }
                ?: Size(1280, 720)

            val readerInstance = ImageReader.newInstance(optimalSize.width, optimalSize.height, ImageFormat.JPEG, 4)
            imageReader = readerInstance
            var frameCount = 0
            val warmUpFrames = 5 // Skip initial unmetered frames to allow AE & AF to lock
            var captured = false

            readerInstance.setOnImageAvailableListener({ reader ->
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        if (captured) {
                            return@setOnImageAvailableListener
                        }
                        frameCount++
                        if (frameCount <= warmUpFrames) {
                            // Sensor adjusting exposure, white-balance, and focus
                            return@setOnImageAvailableListener
                        }
                        captured = true
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        AndroidVoiceService.showCameraPopupGlobally(
                            title = taskTitle,
                            status = "Frame captured. Analyzing with AI...",
                            thumbnail = base64
                        )
                        deferredImage.complete(base64)
                        try { captureSession?.stopRepeating() } catch (_: Exception) {}
                        try { captureSession?.close() } catch (_: Exception) {}
                        try { openCamera?.close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    if (!captured) deferredImage.complete(null)
                } finally {
                    image?.close()
                }
            }, cameraHandler)

            cameraManager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openCamera = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(readerInstance.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                        }

                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(readerInstance.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        // Run repeating stream for warm-up frames to settle exposure and focus
                                        session.setRepeatingRequest(captureBuilder.build(), null, cameraHandler)
                                    } catch (_: Exception) {
                                        try { camera.close() } catch (_: Exception) {}
                                        try { readerInstance.close() } catch (_: Exception) {}
                                        deferredImage.complete(null)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    try { camera.close() } catch (_: Exception) {}
                                    try { readerInstance.close() } catch (_: Exception) {}
                                    deferredImage.complete(null)
                                }
                            },
                            cameraHandler
                        )
                    } catch (_: Exception) {
                        try { camera.close() } catch (_: Exception) {}
                        try { readerInstance.close() } catch (_: Exception) {}
                        deferredImage.complete(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) {}
                    try { readerInstance.close() } catch (_: Exception) {}
                    deferredImage.complete(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) {}
                    try { readerInstance.close() } catch (_: Exception) {}
                    deferredImage.complete(null)
                }
            }, cameraHandler)

            val base64 = withTimeoutOrNull(5000) {
                deferredImage.await()
            }

            return base64
        } catch (_: Exception) {
            return null
        } finally {
            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            try { openCamera?.close() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            stopBackgroundThread()
        }
    }
}
