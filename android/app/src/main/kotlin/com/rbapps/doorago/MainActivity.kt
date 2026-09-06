package com.rbapps.doorago

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android Main Activity for DooraGo.
 *
 * Hosts Flutter engine and bridges native Google AI Edge LiteRT-LM runtime
 * and Android SpeechRecognizer to Dart AssistantProvider.
 */
class MainActivity : FlutterActivity() {

    companion object {
        const val METHOD_CHANNEL_NAME = "com.rbapps.doorago/ai_engine"
        const val EVENT_CHANNEL_NAME = "com.rbapps.doorago/ai_engine_events"
        const val VOICE_METHOD_CHANNEL_NAME = "com.rbapps.doorago/voice_engine"
        const val VOICE_EVENT_CHANNEL_NAME = "com.rbapps.doorago/voice_events"
        const val TTS_METHOD_CHANNEL_NAME = "com.rbapps.doorago/tts_engine"
        const val PERMISSION_REQUEST_RECORD_AUDIO_VOICE_WAKE = 2002

        @Volatile
        var isUiVisible: Boolean = false
    }

    override fun onPause() {
        super.onPause()
        isUiVisible = false
    }

    private var aiEngine: LiteRtLmAiEngine? = null
    private var eventSink: EventChannel.EventSink? = null
    private var voiceManager: AndroidVoiceManager? = null
    private var voiceEventSink: EventChannel.EventSink? = null
    private var ttsManager: AndroidTtsManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(Dispatchers.Main)

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Initialize Native Offline TTS Manager
        ttsManager = AndroidTtsManager(
            context = applicationContext,
            activityProvider = { this }
        )

        // Initialize AI Engine
        aiEngine = LiteRtLmAiEngine(
            context = applicationContext,
            toolRegistry = MobileActionsToolRegistry(
                context = applicationContext,
                onActionExecuted = { actionName, params, resultMessage ->
                    mainHandler.post {
                        eventSink?.success(
                            mapOf(
                                "status" to "ready",
                                "message" to resultMessage,
                                "action_result" to true,
                                "action_name" to actionName
                            )
                        )
                    }
                }
            ),
            activityProvider = { this }
        )

        // Initialize Voice Recognition Manager
        voiceManager = AndroidVoiceManager(
            context = applicationContext,
            activityProvider = { this }
        ).apply {
            setCallback(object : AndroidVoiceManager.VoiceCallback {
                override fun onReady() {
                    // Interrupt any active TTS output when mic starts
                    ttsManager?.stop()
                    mainHandler.post {
                        voiceEventSink?.success(mapOf("event" to "ready"))
                    }
                }

                override fun onBeginSpeech() {
                    // Interrupt any active TTS output when user speaks
                    ttsManager?.stop()
                    mainHandler.post {
                        voiceEventSink?.success(mapOf("event" to "beginSpeech"))
                    }
                }

                override fun onEndSpeech() {
                    mainHandler.post {
                        voiceEventSink?.success(mapOf("event" to "endSpeech"))
                    }
                }

                override fun onResult(recognizedText: String) {
                    mainHandler.post {
                        voiceEventSink?.success(
                            mapOf(
                                "event" to "result",
                                "text" to recognizedText
                            )
                        )
                    }
                }

                override fun onError(errorCode: Int, errorMessage: String) {
                    mainHandler.post {
                        voiceEventSink?.success(
                            mapOf(
                                "event" to "error",
                                "code" to errorCode,
                                "message" to errorMessage
                            )
                        )
                    }
                }

                override fun onListeningStateChanged(isListening: Boolean) {
                    if (isListening) {
                        ttsManager?.stop()
                        DooraWakeWordService.pauseDetection()
                    } else {
                        DooraWakeWordService.resumeDetection()
                    }
                    mainHandler.post {
                        voiceEventSink?.success(
                            mapOf(
                                "event" to "listening",
                                "isListening" to isListening
                            )
                        )
                    }
                }
            })
        }

        // Setup AI EventChannel
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL_NAME)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    val current = aiEngine?.getCurrentState()?.value ?: "uninitialized"
                    val error = aiEngine?.getLastErrorMessage()
                    eventSink?.success(
                        mapOf(
                            "status" to current,
                            "message" to (error ?: "Ready for initialization"),
                            "modelPath" to aiEngine?.getActiveModelPath()
                        )
                    )
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        // Setup Voice EventChannel
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, VOICE_EVENT_CHANNEL_NAME)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    voiceEventSink = events
                    voiceEventSink?.success(
                        mapOf(
                            "event" to "listening",
                            "isListening" to (voiceManager?.isListening() ?: false),
                            "isVoiceWakeActive" to DooraWakeWordService.isVoiceWakeRunning()
                        )
                    )
                }

                override fun onCancel(arguments: Any?) {
                    voiceEventSink = null
                }
            })

        // Bridge DooraWakeWordService events to Flutter UI
        DooraWakeWordService.onStateChangeListener = { isActive ->
            mainHandler.post {
                voiceEventSink?.success(
                    mapOf(
                        "event" to "voiceWakeState",
                        "isActive" to isActive
                    )
                )
            }
        }
        DooraWakeWordService.onWakeWordDetectedListener = {
            mainHandler.post {
                voiceEventSink?.success(
                    mapOf(
                        "event" to "wakeWordDetected"
                    )
                )
            }
        }
        DooraWakeWordService.onCommandCapturedListener = { command ->
            mainHandler.post {
                voiceEventSink?.success(
                    mapOf(
                        "event" to "wakeWordCommand",
                        "text" to command
                    )
                )
            }
        }

        // Listen to AI engine state updates
        aiEngine?.onStatusChanged = { state, message, extra ->
            mainHandler.post {
                val payload = mutableMapOf<String, Any?>(
                    "status" to state.value,
                    "message" to message,
                    "modelPath" to aiEngine?.getActiveModelPath()
                )
                payload.putAll(extra)
                eventSink?.success(payload)
            }
        }

        // Setup AI MethodChannel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL_NAME)
            .setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
                when (call.method) {
                    "initialize" -> handleInitialize(call, result)
                    "processPrompt" -> handleProcessPrompt(call, result)
                    "verifyModel" -> handleVerifyModel(call, result)
                    "installModel" -> handleInstallModel(call, result)
                    "getStatus" -> handleGetStatus(result)
                    "getModelPackStatus" -> handleGetModelPackStatus(result)
                    "requestCellularConsent" -> handleRequestCellularConsent(result)
                    "getInstalledApps", "getInstalledLaunchableApps" -> handleGetInstalledApps(result)
                    "dispose" -> handleDispose(result)
                    else -> result.notImplemented()
                }
            }

        // Setup Voice MethodChannel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VOICE_METHOD_CHANNEL_NAME)
            .setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
                when (call.method) {
                    "startListening" -> {
                        val language = call.argument<String>("language")
                        voiceManager?.startListening(language)
                        result.success(true)
                    }
                    "stopListening" -> {
                        voiceManager?.stopListening()
                        result.success(true)
                    }
                    "cancel" -> {
                        voiceManager?.cancel()
                        result.success(true)
                    }
                    "isAvailable" -> {
                        result.success(voiceManager?.isAvailable() ?: false)
                    }
                    "isListening" -> {
                        result.success(voiceManager?.isListening() ?: false)
                    }
                    "startVoiceWake" -> {
                        val recordAudioGranted = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        } else {
                            true
                        }

                        if (!recordAudioGranted || !postNotifGranted) {
                            val permissionsToRequest = mutableListOf<String>()
                            if (!recordAudioGranted) permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                            if (!postNotifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ActivityCompat.requestPermissions(
                                this,
                                permissionsToRequest.toTypedArray(),
                                PERMISSION_REQUEST_RECORD_AUDIO_VOICE_WAKE
                            )
                        }

                        if (recordAudioGranted) {
                            DooraWakeWordService.start(applicationContext)
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    }
                    "stopVoiceWake" -> {
                        DooraWakeWordService.stop(applicationContext)
                        result.success(true)
                    }
                    "openAssistantSettings" -> {
                        DooraVoiceInteractionService.openAssistantSettings(this)
                        result.success(true)
                    }
                    "requestAssistantRole" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val roleManager = getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
                            if (roleManager != null && roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)
                                startActivity(intent)
                                result.success(true)
                            } else {
                                DooraVoiceInteractionService.openAssistantSettings(this)
                                result.success(false)
                            }
                        } else {
                            DooraVoiceInteractionService.openAssistantSettings(this)
                            result.success(true)
                        }
                    }
                    "getAssistantStatus" -> {
                        val roleAvailable = DooraVoiceInteractionService.isAssistantServiceAvailable(this)
                        val roleHeld = DooraVoiceInteractionService.isRoleHeld(this)
                        val serviceRunning = DooraVoiceInteractionService.isAssistantRoleActive
                        val voiceWakeEnabled = DooraWakeWordService.isVoiceWakeRunning()
                        result.success(
                            mapOf(
                                "roleAvailable" to roleAvailable,
                                "roleHeld" to roleHeld,
                                "serviceRunning" to serviceRunning,
                                "voiceWakeEnabled" to voiceWakeEnabled
                            )
                        )
                    }
                    "isVoiceWakeActive" -> {
                        result.success(DooraWakeWordService.isVoiceWakeRunning())
                    }
                    "startFloatingButton" -> {
                        if (android.provider.Settings.canDrawOverlays(this)) {
                            DooraFloatingButtonService.start(applicationContext)
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    }
                    "stopFloatingButton" -> {
                        DooraFloatingButtonService.stop(applicationContext)
                        result.success(true)
                    }
                    "isFloatingButtonActive" -> {
                        result.success(DooraFloatingButtonService.isRunning())
                    }
                    "checkOverlayPermission" -> {
                        result.success(android.provider.Settings.canDrawOverlays(this))
                    }
                    "requestOverlayPermission" -> {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        ).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        result.success(true)
                    }
                    "checkPermission" -> {
                        val perm = call.argument<String>("permission") ?: ""
                        if (perm.isBlank()) {
                            result.success(false)
                        } else {
                            val androidPerm = when (perm) {
                                "RECORD_AUDIO" -> Manifest.permission.RECORD_AUDIO
                                "POST_NOTIFICATIONS" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
                                "READ_CONTACTS" -> Manifest.permission.READ_CONTACTS
                                "CALL_PHONE" -> Manifest.permission.CALL_PHONE
                                else -> perm
                            }
                            val granted = if (androidPerm != null) {
                                ContextCompat.checkSelfPermission(this, androidPerm) == PackageManager.PERMISSION_GRANTED
                            } else {
                                true
                            }
                            result.success(granted)
                        }
                    }
                    "requestPermission" -> {
                        val perm = call.argument<String>("permission") ?: ""
                        val androidPerm = when (perm) {
                            "RECORD_AUDIO" -> Manifest.permission.RECORD_AUDIO
                            "POST_NOTIFICATIONS" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
                            "READ_CONTACTS" -> Manifest.permission.READ_CONTACTS
                            "CALL_PHONE" -> Manifest.permission.CALL_PHONE
                            else -> perm
                        }
                        if (androidPerm != null && ContextCompat.checkSelfPermission(this, androidPerm) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this, arrayOf(androidPerm), 2099)
                        }
                        result.success(true)
                    }
                    "openAppSettings" -> {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")
                        ).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        result.success(true)
                    }
                    "hasCompletedPermissionOnboarding" -> {
                        val prefs = getSharedPreferences("doorago_prefs", Context.MODE_PRIVATE)
                        val completed = prefs.getBoolean("has_completed_permission_onboarding", false)
                        result.success(completed)
                    }
                    "setPermissionOnboardingCompleted" -> {
                        val prefs = getSharedPreferences("doorago_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("has_completed_permission_onboarding", true).apply()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }

        // Setup TTS MethodChannel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, TTS_METHOD_CHANNEL_NAME)
            .setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
                when (call.method) {
                    "speak" -> {
                        val text = call.argument<String>("text") ?: ""
                        val locale = call.argument<String>("locale")
                        val speakResult = ttsManager?.speak(text, locale) ?: mapOf("success" to false, "reason" to "TTS_NULL")
                        result.success(speakResult)
                    }
                    "stop" -> {
                        ttsManager?.stop()
                        result.success(true)
                    }
                    "getStatus" -> {
                        val status = ttsManager?.getStatus() ?: mapOf("isAvailable" to false)
                        result.success(status)
                    }
                    "openInstaller" -> {
                        val opened = ttsManager?.openVoiceDataInstaller() ?: false
                        result.success(opened)
                    }
                    "openSettings" -> {
                        val opened = ttsManager?.openTtsSettings() ?: false
                        result.success(opened)
                    }
                    "isSpeaking" -> {
                        result.success(ttsManager?.isSpeaking() ?: false)
                    }
                    "isAvailable" -> {
                        result.success(ttsManager?.isAvailable() ?: false)
                    }
                    "initialize" -> {
                        ttsManager?.initialize { success ->
                            mainHandler.post {
                                result.success(success)
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private var pendingCallNumber: String? = null
    private var pendingContactCallName: String? = null

    fun setPendingCall(number: String) {
        pendingCallNumber = number
    }

    fun setPendingContactCall(name: String) {
        pendingContactCallName = name
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AndroidVoiceManager.PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                voiceManager?.startListening()
            } else {
                voiceEventSink?.success(
                    mapOf(
                        "event" to "error",
                        "code" to -1,
                        "message" to "Microphone permission is required for voice commands."
                    )
                )
            }
        } else if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO_VOICE_WAKE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                DooraWakeWordService.start(applicationContext)
            } else {
                voiceEventSink?.success(
                    mapOf(
                        "event" to "error",
                        "code" to -1,
                        "message" to "Microphone permission is required for DooraGo Voice Wake."
                    )
                )
            }
        } else if (requestCode == LocalDeviceActionExecutor.PERMISSION_REQUEST_CALL_PHONE) {
            val number = pendingCallNumber
            pendingCallNumber = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (!number.isNullOrBlank()) {
                    activityScope.launch(Dispatchers.IO) {
                        val executor = LocalDeviceActionExecutor(applicationContext, activityProvider = { this@MainActivity })
                        val resultMsg = executor.executeDirectCallIntent(number)
                        mainHandler.post {
                            eventSink?.success(
                                mapOf(
                                    "status" to "ready",
                                    "message" to resultMsg,
                                    "action_result" to true,
                                    "action_name" to "direct_call_number"
                                )
                            )
                        }
                    }
                }
            } else {
                if (!number.isNullOrBlank()) {
                    activityScope.launch(Dispatchers.IO) {
                        val executor = LocalDeviceActionExecutor(applicationContext, activityProvider = { this@MainActivity })
                        executor.dialNumber(number)
                        mainHandler.post {
                            eventSink?.success(
                                mapOf(
                                    "status" to "ready",
                                    "message" to "Call permission was denied. Opening the dialer instead.",
                                    "action_result" to true,
                                    "action_name" to "dial_number_fallback"
                                )
                            )
                        }
                    }
                } else {
                    eventSink?.success(
                        mapOf(
                            "status" to "ready",
                            "message" to "Call permission was denied.",
                            "action_result" to false,
                            "action_name" to "direct_call_number"
                        )
                    )
                }
            }
        } else if (requestCode == LocalDeviceActionExecutor.PERMISSION_REQUEST_READ_CONTACTS) {
            val contactName = pendingContactCallName
            pendingContactCallName = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (!contactName.isNullOrBlank()) {
                    activityScope.launch(Dispatchers.IO) {
                        val executor = LocalDeviceActionExecutor(applicationContext, activityProvider = { this@MainActivity })
                        val resultMsg = executor.callContactByName(contactName)
                        mainHandler.post {
                            eventSink?.success(
                                mapOf(
                                    "status" to "ready",
                                    "message" to resultMsg,
                                    "action_result" to true,
                                    "action_name" to "call_contact_by_name"
                                )
                            )
                        }
                    }
                }
            } else {
                eventSink?.success(
                    mapOf(
                        "status" to "ready",
                        "message" to "To call a saved contact by name, DooraGo needs access to your contacts.",
                        "action_result" to false,
                        "action_name" to "call_contact_by_name"
                    )
                )
            }
        }
    }

    private fun handleInitialize(call: MethodCall, result: MethodChannel.Result) {
        val modelPath = call.argument<String>("modelPath")
        aiEngine?.initialize(modelPath) { success, errorCode, errorMsg ->
            mainHandler.post {
                if (success) {
                    result.success(
                        mapOf(
                            "success" to true,
                            "status" to "ready",
                            "modelPath" to aiEngine?.getActiveModelPath()
                        )
                    )
                } else {
                    val code = errorCode ?: "INIT_FAILED"
                    result.error(
                        code,
                        errorMsg ?: "Failed to initialize LiteRT-LM engine",
                        mapOf(
                            "status" to "error",
                            "errorCode" to code,
                            "modelPath" to aiEngine?.getActiveModelPath()
                        )
                    )
                }
            }
        }
    }

    private fun handleProcessPrompt(call: MethodCall, result: MethodChannel.Result) {
        val prompt = call.argument<String>("prompt")
        if (prompt.isNullOrBlank()) {
            result.error("INVALID_ARGUMENT", "Prompt cannot be empty", null)
            return
        }

        activityScope.launch(Dispatchers.IO) {
            val inferenceResult = aiEngine?.processPrompt(prompt)
            mainHandler.post {
                if (inferenceResult != null && inferenceResult.isSuccess) {
                    result.success(
                        mapOf(
                            "success" to true,
                            "result" to inferenceResult.getOrNull()
                        )
                    )
                } else {
                    val ex = inferenceResult?.exceptionOrNull()
                    result.error(
                        "INFERENCE_ERROR",
                        ex?.localizedMessage ?: "Failed to run inference",
                        mapOf("status" to "error")
                    )
                }
            }
        }
    }

    private fun handleVerifyModel(call: MethodCall, result: MethodChannel.Result) {
        val checkHash = call.argument<Boolean>("checkHash") ?: false
        activityScope.launch(Dispatchers.IO) {
            val verification = aiEngine?.verifyModel(checkHash) ?: emptyMap<String, Any?>()
            mainHandler.post {
                result.success(verification)
            }
        }
    }

    private fun handleInstallModel(call: MethodCall, result: MethodChannel.Result) {
        val sourcePath = call.argument<String>("sourcePath")
        if (sourcePath.isNullOrBlank()) {
            result.error("INVALID_ARGUMENT", "Source path cannot be empty", null)
            return
        }
        activityScope.launch(Dispatchers.IO) {
            val installResult = aiEngine?.installModel(sourcePath)
            mainHandler.post {
                if (installResult != null && installResult["installed"] == true) {
                    result.success(installResult)
                } else {
                    val errorMsg = installResult?.get("error")?.toString() ?: "Failed to install model"
                    result.error("INSTALL_FAILED", errorMsg, installResult)
                }
            }
        }
    }

    private fun handleGetStatus(result: MethodChannel.Result) {
        val state = aiEngine?.getCurrentState()?.value ?: "uninitialized"
        val lastErr = aiEngine?.getLastErrorMessage()
        val modelPath = aiEngine?.getActiveModelPath()
        result.success(
            mapOf(
                "status" to state,
                "errorMessage" to lastErr,
                "modelPath" to modelPath
            )
        )
    }

    private fun handleGetInstalledApps(result: MethodChannel.Result) {
        activityScope.launch(Dispatchers.IO) {
            val executor = LocalDeviceActionExecutor(applicationContext)
            val apps = executor.getLaunchableApps()
            mainHandler.post {
                result.success(apps)
            }
        }
    }

    private fun handleGetModelPackStatus(result: MethodChannel.Result) {
        val status = aiEngine?.getModelDeliveryStatus()
        if (status != null) {
            result.success(
                mapOf(
                    "state" to status.state.value,
                    "message" to status.message,
                    "progressPercent" to status.progressPercent,
                    "bytesDownloaded" to status.bytesDownloaded,
                    "totalBytesToDownload" to status.totalBytesToDownload,
                    "localPath" to status.localPath,
                    "errorCode" to status.errorCode
                )
            )
        } else {
            result.success(
                mapOf(
                    "state" to "not_available",
                    "message" to "Model delivery manager not initialized"
                )
            )
        }
    }

    private fun handleRequestCellularConsent(result: MethodChannel.Result) {
        val triggered = aiEngine?.modelDeliveryManager?.requestCellularConsentIfPossible() ?: false
        result.success(mapOf("success" to triggered))
    }

    private fun handleDispose(result: MethodChannel.Result) {
        aiEngine?.dispose()
        voiceManager?.destroy()
        result.success(mapOf("success" to true, "status" to "uninitialized"))
    }

    override fun onResume() {
        super.onResume()
        isUiVisible = true
        // Re-check and re-initialize TTS availability if user returned from Android TTS settings/installer
        ttsManager?.initialize()
    }

    override fun onDestroy() {
        ttsManager?.shutdown()
        voiceManager?.destroy()
        aiEngine?.dispose()
        super.onDestroy()
    }
}
