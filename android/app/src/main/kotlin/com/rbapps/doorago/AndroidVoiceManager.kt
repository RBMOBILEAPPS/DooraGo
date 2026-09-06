package com.rbapps.doorago

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Android Native Speech-to-Text Manager for DooraGo.
 *
 * Implements robust locale detection, multi-tier language fallback (hi-IN -> en-IN -> device default),
 * on-device recognition preference with graceful fallback, and protection against multiple concurrent sessions.
 */
class AndroidVoiceManager(
    private val context: Context,
    private var activityProvider: () -> Activity?
) {
    companion object {
        private const val TAG = "AndroidVoiceManager"
        private const val NATIVE_TAG = "DooraGoNative"
        const val PERMISSION_REQUEST_RECORD_AUDIO = 2001

        // Standard Error Codes for SpeechRecognizer
        const val ERROR_NETWORK_TIMEOUT = 1
        const val ERROR_NETWORK = 2
        const val ERROR_AUDIO = 3
        const val ERROR_SERVER = 4
        const val ERROR_CLIENT = 5
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_CANNOT_CHECK_SUPPORT = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 14
        const val ERROR_TOO_MANY_REQUESTS = 15
    }

    interface VoiceCallback {
        fun onReady()
        fun onBeginSpeech()
        fun onEndSpeech()
        fun onResult(recognizedText: String)
        fun onError(errorCode: Int, errorMessage: String)
        fun onListeningStateChanged(isListening: Boolean)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var isStarting = false
    private var voiceCallback: VoiceCallback? = null
    private var currentAttemptLocale: String? = null
    private var hasAttemptedFallback = false

    fun setCallback(callback: VoiceCallback?) {
        this.voiceCallback = callback
    }

    fun isListening(): Boolean = isListening

    /**
     * Checks if SpeechRecognizer is available on this device.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Checks whether on-device speech recognition is supported on Android 12+ (API 31+).
     */
    fun isOnDeviceAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    /**
     * Starts listening for voice commands with multi-tier locale fallback.
     * Fallback hierarchy: requestedLanguage (e.g. hi-IN / en-IN) -> en-IN -> Device Default Locale -> System Default
     */
    fun startListening(
        preferredLanguage: String? = null,
        onPermissionRequired: (() -> Unit)? = null
    ) {
        mainHandler.post {
            // Prevent multiple concurrent instances from rapid microphone taps
            if (isListening || isStarting) {
                Log.w(TAG, "[VOICE_START] Session already active. Canceling prior session first.")
                cleanupRecognizer()
            }

            isStarting = true
            hasAttemptedFallback = false

            // Check microphone permission
            val activity = activityProvider.invoke()
            val permissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!permissionGranted) {
                isStarting = false
                Log.w(TAG, "[VOICE_ERROR] RECORD_AUDIO permission not granted.")
                if (activity != null) {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSION_REQUEST_RECORD_AUDIO
                    )
                } else {
                    onPermissionRequired?.invoke()
                    voiceCallback?.onError(ERROR_INSUFFICIENT_PERMISSIONS, "Microphone permission is required for voice commands.")
                }
                return@post
            }

            if (!isAvailable()) {
                isStarting = false
                val errMsg = "Speech recognition is not available on this device."
                Log.e(TAG, "[VOICE_ERROR] $errMsg")
                voiceCallback?.onError(-2, errMsg)
                return@post
            }

            val targetLocale = resolveInitialLocale(preferredLanguage)
            DooraVoiceController.requestMicOwnership(DooraVoiceController.MicOwner.MANUAL_MIC)
            startRecognitionInternal(targetLocale, isFallback = false)
        }
    }

    private fun resolveInitialLocale(preferred: String?): String {
        val deviceLocale = Locale.getDefault().toLanguageTag()
        return when {
            !preferred.isNullOrBlank() -> preferred
            deviceLocale.startsWith("hi", ignoreCase = true) -> "hi-IN"
            deviceLocale.startsWith("en", ignoreCase = true) -> "en-IN"
            else -> deviceLocale
        }
    }

    private fun startRecognitionInternal(localeTag: String?, isFallback: Boolean) {
        try {
            cleanupRecognizer()

            currentAttemptLocale = localeTag
            val isOnDevice = isOnDeviceAvailable()

            Log.i(NATIVE_TAG, "[VOICE_START] targetLocale=$localeTag isFallback=$isFallback onDevice=$isOnDevice")
            Log.i(NATIVE_TAG, "[VOICE_LOCALE] active=${localeTag ?: "system_default"}")

            // On Android 12+ prefer on-device recognizer if available
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isOnDevice && !isFallback) {
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (e: Exception) {
                    Log.w(TAG, "createOnDeviceSpeechRecognizer failed, falling back to standard: ${e.message}")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            recognizer.setRecognitionListener(createListener())
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)

                if (!localeTag.isNullOrBlank()) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                }

                // If on-device and not in fallback mode, request prefer offline
                if (isOnDevice && !isFallback) {
                    putExtra("android.speech.extra.PREFER_OFFLINE", true)
                }
            }

            recognizer.startListening(intent)
            isStarting = false
            isListening = true
            voiceCallback?.onListeningStateChanged(true)
        } catch (e: Exception) {
            isStarting = false
            isListening = false
            val errMsg = "Failed to start speech recognition: ${e.localizedMessage ?: "Unknown error"}"
            Log.e(TAG, "[VOICE_ERROR] startListening failed: $errMsg", e)
            voiceCallback?.onListeningStateChanged(false)
            voiceCallback?.onError(-3, errMsg)
        }
    }

    /**
     * Attempts automatic fallback when language error (13, 14) occurs.
     */
    private fun attemptLanguageFallback(originalErrorCode: Int): Boolean {
        if (hasAttemptedFallback) return false
        hasAttemptedFallback = true

        val deviceLocale = Locale.getDefault().toLanguageTag()
        val nextLocale = when {
            currentAttemptLocale == "hi-IN" -> "en-IN"
            currentAttemptLocale == "en-IN" && !deviceLocale.equals("en-IN", ignoreCase = true) -> deviceLocale
            currentAttemptLocale != null -> null // Fallback to system default (no language tag)
            else -> null
        }

        Log.w(NATIVE_TAG, "[VOICE_FALLBACK] Error $originalErrorCode on $currentAttemptLocale. Retrying with: ${nextLocale ?: "system default"}")
        mainHandler.post {
            startRecognitionInternal(nextLocale, isFallback = true)
        }
        return true
    }

    /**
     * Stops listening and processes captured speech.
     */
    fun stopListening() {
        mainHandler.post {
            try {
                if (isListening) {
                    Log.i(NATIVE_TAG, "[VOICE] stopListening")
                    speechRecognizer?.stopListening()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping SpeechRecognizer: ${e.message}")
            }
        }
    }

    /**
     * Cancels active recognition without processing.
     */
    fun cancel() {
        mainHandler.post {
            try {
                Log.i(NATIVE_TAG, "[VOICE] cancel")
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error canceling SpeechRecognizer: ${e.message}")
            } finally {
                isListening = false
                isStarting = false
                voiceCallback?.onListeningStateChanged(false)
            }
        }
    }

    /**
     * Releases SpeechRecognizer resources.
     */
    fun destroy() {
        mainHandler.post {
            cleanupRecognizer()
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up SpeechRecognizer: ${e.message}")
        } finally {
            speechRecognizer = null
            isListening = false
            isStarting = false
            DooraVoiceController.releaseMicOwnership(DooraVoiceController.MicOwner.MANUAL_MIC)
            voiceCallback?.onListeningStateChanged(false)
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(NATIVE_TAG, "[VOICE] ready")
                voiceCallback?.onReady()
            }

            override fun onBeginningOfSpeech() {
                Log.i(NATIVE_TAG, "[VOICE] beginSpeech")
                voiceCallback?.onBeginSpeech()
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.i(NATIVE_TAG, "[VOICE] endSpeech")
                isListening = false
                isStarting = false
                voiceCallback?.onListeningStateChanged(false)
                voiceCallback?.onEndSpeech()
            }

            override fun onError(error: Int) {
                Log.i(NATIVE_TAG, "[VOICE_ERROR] code=$error")

                // If error is language unavailable (13) or language not supported (14), attempt fallback
                if ((error == ERROR_LANGUAGE_UNAVAILABLE || error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_CANNOT_LISTEN_TO_DOWNLOAD)
                    && attemptLanguageFallback(error)) {
                    return
                }

                isListening = false
                isStarting = false
                voiceCallback?.onListeningStateChanged(false)
                val friendlyMessage = getFriendlyErrorMessage(error)
                voiceCallback?.onError(error, friendlyMessage)
                Log.i(NATIVE_TAG, "[VOICE_END] Session finished with error $error")
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                isStarting = false
                voiceCallback?.onListeningStateChanged(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val recognizedText = selectBestHypothesis(matches)
                Log.i(NATIVE_TAG, "[VOICE_RESULT] result=\"$recognizedText\"")
                Log.i(NATIVE_TAG, "[VOICE_END] Session completed successfully")
                if (recognizedText.isNotBlank()) {
                    voiceCallback?.onResult(recognizedText)
                } else {
                    voiceCallback?.onError(ERROR_NO_MATCH, "No speech detected. Please try again.")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Evaluates N-best candidate hypotheses returned by SpeechRecognizer.
     * Prefers the most coherent command utterance when alternative hypotheses contain
     * recognized action verbs/structures.
     */
    private fun selectBestHypothesis(matches: List<String>): String {
        if (matches.isEmpty()) return ""
        if (matches.size == 1) return matches[0].trim()

        Log.i(NATIVE_TAG, "[VOICE_N_BEST] count=${matches.size} candidates=$matches")

        var bestCandidate = matches[0].trim()
        var bestScore = scoreHypothesis(bestCandidate, index = 0)

        for (i in 1 until matches.size) {
            val cand = matches[i].trim()
            val score = scoreHypothesis(cand, index = i)
            if (score > bestScore) {
                bestScore = score
                bestCandidate = cand
            }
        }

        Log.i(NATIVE_TAG, "[VOICE_N_BEST] selected=\"$bestCandidate\" (topAcoustic=\"${matches[0].trim()}\")")
        return bestCandidate
    }

    private fun scoreHypothesis(text: String, index: Int): Double {
        if (text.isBlank()) return -1.0
        val lower = text.lowercase(Locale.ROOT)

        // Baseline acoustic prior: Candidate 0 has highest raw acoustic confidence
        var score = 1.0 - (index * 0.15)

        // Command indicators in Hindi / Hinglish / English
        val strongCommandVerbs = listOf(
            "kholo", "khol do", "khol de", "kholiye", "open", "launch", "chalu karo", "chalao",
            "call karo", "call lagao", "phone karo", "phone lagao", "dial karo", "call", "dial",
            "message bhejo", "message karo", "bhejo", "bhej do", "whatsapp", "sms",
            "save contact", "save number", "save karo", "sev karo",
            "flashlight", "torch", "wifi", "bluetooth", "volume", "brightness", "alarm", "timer", "settings",
            "खोलो", "खोल दो", "चालू करो", "कॉल करो", "फोन करो", "लगाओ", "भेजो", "मैसेज", "व्हाट्सएप", "सेव करो"
        )

        val relationalParticles = listOf(
            " ko ", " se ", " pe ", " par ", " ka ", " ke ", " ki ", " to ", " for ", " on ", " via ", " saying ", " bolo ", " puch "
        )

        if (strongCommandVerbs.any { lower.contains(it) }) {
            score += 0.5
        }

        if (relationalParticles.any { " $lower ".contains(it) }) {
            score += 0.2
        }

        return score
    }

    private fun getFriendlyErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            ERROR_LANGUAGE_UNAVAILABLE, ERROR_LANGUAGE_NOT_SUPPORTED -> {
                when {
                    currentAttemptLocale == "hi-IN" ->
                        "Offline Hindi voice recognition is not available on this device. You can install the Hindi voice/language pack in Android settings or use English."
                    currentAttemptLocale == "en-IN" ->
                        "Offline English voice recognition is not available on this device. You can install English voice data in Android settings."
                    else ->
                        "Voice language is not available on this device. Please check language settings or install offline voice data."
                }
            }
            ERROR_AUDIO -> "Audio recording error. Please check microphone."
            ERROR_CLIENT -> "Voice recognition was cancelled."
            ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice commands. Please enable it in App Settings."
            ERROR_NETWORK -> "Network connection required for device speech engine."
            ERROR_NETWORK_TIMEOUT -> "Speech service timed out. Please try again."
            ERROR_NO_MATCH -> "No speech detected. Please try again."
            ERROR_RECOGNIZER_BUSY -> "Voice recognizer is busy. Please try again."
            ERROR_SERVER -> "Speech recognition server error. Please try again."
            ERROR_SPEECH_TIMEOUT -> "No speech detected. Please try again."
            ERROR_TOO_MANY_REQUESTS -> "Too many speech requests. Please wait a moment."
            else -> "Voice recognition error ($errorCode). Please try again."
        }
    }
}
