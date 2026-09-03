package com.example.blindaassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import com.google.firebase.FirebaseApp

class MainActivity : ComponentActivity() {

    private lateinit var voiceService: AndroidVoiceService
    private lateinit var deviceController: DeviceController

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted =
            permissions[Manifest.permission.RECORD_AUDIO] == true

        if (!recordAudioGranted) {
            voiceService.speak(
                "Microphone permission is required."
            )
        } else {
            BackgroundVoiceService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase
        FirebaseApp.initializeApp(this)

        val prefManager = PreferenceManager(applicationContext)

        // AI Client initialized with Google Gemini Developer API
        val aiClient = AiClient(
            cloudKey = GeminiConfig.API_KEY,
            cloudModel = GeminiConfig.DEFAULT_MODEL
        )

        deviceController = DeviceController(applicationContext, aiClient, prefManager)
        deviceController.onExitRequested = {
            moveTaskToBack(true)
        }

        val commandProcessor = CommandProcessor(deviceController, aiClient, prefManager)

        voiceService = AndroidVoiceService.getInstance(applicationContext, deviceController, commandProcessor)
        androidVoiceServiceInstance = voiceService

        checkAndRequestPermissions()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            BackgroundVoiceService.start(this)
        }

        setContent {
            App(
                aiClient = aiClient,
                voiceService = voiceService
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundVoiceService.setAppInForeground(true)
    }

    override fun onPause() {
        super.onPause()
        BackgroundVoiceService.setAppInForeground(false)
    }

    override fun onStop() {
        super.onStop()
        BackgroundVoiceService.setAppInForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT destroy voiceService or release deviceController here.
        // Voice service and accessibility must remain alive and responsive when YouTube or other apps are open.
    }
}
