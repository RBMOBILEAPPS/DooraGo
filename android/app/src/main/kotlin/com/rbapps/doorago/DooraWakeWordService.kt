package com.rbapps.doorago

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Production-hardened, battery-efficient Android Microphone Foreground Service
 * for 100% offline "Doora" wake-word detection.
 *
 * ROOT-CAUSE FIXES & OPTIMIZATIONS:
 * 1. Explicit State Machine: Strict state machine (WAKE_LISTENING, WAKE_DETECTED, HANDOFF, COMMAND_STARTING,
 *    COMMAND_READY, COMMAND_LISTENING, COMMAND_PROCESSING, COMMAND_COMPLETED, RESUMING_WAKE) preventing race conditions.
 * 2. Mode A (Wake-Only) & Mode B (Direct Wake + Command) Filtering:
 *    - Wake-only ("Doora") never routes to LLM/normalizer; plays wake beep & opens command window.
 *    - Direct wake ("Doora turn on flashlight") strips ONLY leading wake prefix & executes immediately.
 * 3. Immediate Wake Beep & UX Notification: Crisp beep plays immediately upon wake detection; notification
 *    accurately reflects "DooraGo is listening..." once command recognizer is ready.
 * 4. en-US Offline Recognition Preference: Prioritizes en-US offline engine to prevent ERROR_LANGUAGE_UNAVAILABLE (13).
 * 5. Clean Microphone Handoff: Strict sequential transition ensuring AudioRecord releases mic before SpeechRecognizer starts.
 * 6. Explicit Diagnostic Logging: Logs [WAKE_STATE], [WAKE_FLOW], [WAKE_HANDOFF], [WAKE_COMMAND], [WAKE_COMMAND_FILTER], and [WAKE_COMMAND_RESULT].
 */
class DooraWakeWordService : Service() {

    enum class WakeState {
        WAKE_LISTENING,
        WAKE_DETECTED,
        HANDOFF,
        COMMAND_STARTING,
        COMMAND_READY,
        COMMAND_LISTENING,
        COMMAND_PROCESSING,
        COMMAND_COMPLETED,
        RESUMING_WAKE
    }

    companion object {
        private const val TAG = "DooraWakeWordService"
        private const val NATIVE_TAG = "DooraGoNative"
        private const val AICORE_TAG = "AICORE"

        const val ACTION_START_VOICE_WAKE = "com.rbapps.doorago.action.START_VOICE_WAKE"
        const val ACTION_STOP_VOICE_WAKE = "com.rbapps.doorago.action.STOP_VOICE_WAKE"

        const val CHANNEL_ID = "doora_voice_wake_channel"
        const val NOTIFICATION_ID = 4001

        private const val COMMAND_TIMEOUT_MS = 5000L
        private const val TRIGGER_COOLDOWN_MS = 2000L

        @Volatile
        private var isRunning = false

        @Volatile
        private var activeInstance: DooraWakeWordService? = null

        fun isVoiceWakeRunning(): Boolean = isRunning

        fun pauseDetection() {
            activeInstance?.stopWakeWordDetectionLoop()
        }

        fun resumeDetection() {
            activeInstance?.let { service ->
                if (isRunning && !service.isCommandListening.get()) {
                    service.startWakeWordDetectionLoop()
                }
            }
        }

        var onStateChangeListener: ((Boolean) -> Unit)? = null
        var onWakeWordDetectedListener: (() -> Unit)? = null
        var onCommandCapturedListener: ((String) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, DooraWakeWordService::class.java).apply {
                action = ACTION_START_VOICE_WAKE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DooraWakeWordService::class.java).apply {
                action = ACTION_STOP_VOICE_WAKE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var commandTimeoutRunnable: Runnable? = null

    private val currentState = AtomicReference<WakeState>(WakeState.WAKE_LISTENING)
    private val isDetecting = AtomicBoolean(false)
    private val isCommandListening = AtomicBoolean(false)
    private val isModeACommandWindow = AtomicBoolean(false)

    private var toneGenerator: ToneGenerator? = null
    private var lastTriggerTimestamp = 0L

    // Lightweight deterministic action normalizer (Zero LLM overhead)
    private var normalizer: MobileActionNormalizer? = null
    // Lazy AI Engines: Loaded only if a complex ambiguous prompt explicitly requires LLM inference
    private var lazyAiEngine: LiteRtLmAiEngine? = null
    private var lazyNanoEngine: AiCoreGeminiNanoEngine? = null

    private fun transitionToState(newState: WakeState): Boolean {
        val oldState = currentState.getAndSet(newState)
        if (oldState != newState) {
            Log.i(NATIVE_TAG, "[WAKE_STATE] $oldState -> $newState")
        }
        return true
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.i(TAG, "[SERVICE_CREATE] Initializing optimized DooraWakeWordService")
        createNotificationChannel()

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator init skipped: ${e.message}")
        }

        // Initialize lightweight deterministic normalizer (0 AI model overhead)
        val toolRegistry = MobileActionsToolRegistry(applicationContext)
        normalizer = MobileActionNormalizer(
            context = applicationContext,
            toolRegistry = toolRegistry
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_VOICE_WAKE
        Log.i(TAG, "[SERVICE_START_COMMAND] action=$action")

        when (action) {
            ACTION_START_VOICE_WAKE -> {
                startForegroundServiceInternal()
            }
            ACTION_STOP_VOICE_WAKE -> {
                stopForegroundServiceInternal()
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        val permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            Log.e(TAG, "[PERMISSION_DENIED] RECORD_AUDIO not granted. Stopping service.")
            stopSelf()
            return
        }

        isRunning = true
        onStateChangeListener?.invoke(true)

        // Acquire partial wake lock for reliable screen-off operation
        acquireWakeLock()

        // Build persistent notification with "Turn off" action
        val notification = buildNotification(
            title = "DooraGo Voice Wake is active",
            content = "Listening for 'Doora'..."
        )

        // Start Foreground Service with Microphone type on Android 14+ (API 34+)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }

        // Start offline audio capture and keyword spotting
        transitionToState(WakeState.WAKE_LISTENING)
        startWakeWordDetectionLoop()
    }

    private fun stopForegroundServiceInternal() {
        Log.i(TAG, "[SERVICE_STOP] Stopping DooraWakeWordService")
        isRunning = false
        onStateChangeListener?.invoke(false)

        cancelCommandTimeoutWatchdog()
        stopWakeWordDetectionLoop()
        cleanupSpeechRecognizer()
        releaseWakeLock()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground error: ${e.message}")
        }

        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DooraGo:VoiceWakeLock")
        }
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(12 * 60 * 60 * 1000L) // 12-hour maximum safety cap
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Adaptive Hardware Audio Probing & Capture Loop
    // ──────────────────────────────────────────────────────────────

    private data class AudioConfig(
        val record: AudioRecord,
        val sampleRate: Int,
        val frameSize: Int
    )

    @SuppressLint("MissingPermission")
    private fun probeAudioHardware(): AudioConfig? {
        val sampleRatesToTry = intArrayOf(16000, 44100, 48000, 8000)
        val audioSourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        for (source in audioSourcesToTry) {
            for (rate in sampleRatesToTry) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf <= 0) continue

                    val bufferSize = minBuf.coerceAtLeast(rate * 2)
                    val record = AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        val frameSize = (rate / 10).coerceAtLeast(800) // 100ms frame
                        Log.i(NATIVE_TAG, "[WAKE_AUDIO] AudioRecord initialized: source=$source rate=${rate}Hz frameSize=$frameSize")
                        return AudioConfig(record, rate, frameSize)
                    } else {
                        record.release()
                    }
                } catch (e: Exception) {
                    // Try next combination
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startWakeWordDetectionLoop() {
        if (isDetecting.get() || isCommandListening.get()) return

        DooraVoiceController.requestMicOwnership(DooraVoiceController.MicOwner.WAKE_SERVICE)
        stopWakeWordDetectionLoop()

        val config = probeAudioHardware()
        if (config == null) {
            Log.e(TAG, "[AUDIO_RECORD_ERROR] No compatible AudioRecord configuration found.")
            DooraVoiceController.releaseMicOwnership(DooraVoiceController.MicOwner.WAKE_SERVICE)
            return
        }

        try {
            val record = config.record
            record.startRecording()
            audioRecord = record
            isDetecting.set(true)
            Log.i(NATIVE_TAG, "[WAKE_AUDIO] AudioRecord recording started (state=${record.recordingState})")

            recordingJob = serviceScope.launch(Dispatchers.IO) {
                // Pre-allocate frame buffer ONCE before loop (Zero GC pressure)
                val frameBuffer = ShortArray(config.frameSize)
                val spotter = DooraNeuralSpotter(applicationContext)
                var retryBackoff = 0L

                while (isActive && isDetecting.get()) {
                    // Blocking read: Suspends on ALSA kernel driver, ~0% idle CPU
                    val readCount = record.read(frameBuffer, 0, frameBuffer.size)
                    if (readCount > 0) {
                        val isTriggered = spotter.processFrame(frameBuffer, readCount)
                        val now = System.currentTimeMillis()

                        if (isTriggered && (now - lastTriggerTimestamp > TRIGGER_COOLDOWN_MS)) {
                            lastTriggerTimestamp = now
                            Log.i(NATIVE_TAG, "[WAKE_WORD_DETECTED] Keyword 'Doora' spotted successfully via ${spotter.name} (isNeural=${spotter.isNeural})!")
                            spotter.reset()
                            mainHandler.post {
                                handleWakeWordDetected()
                            }
                            break // Clean microphone handoff to command listener
                        }
                    } else if (readCount < 0) {
                        Log.w(TAG, "[AUDIO_RECORD_ERROR] read error code=$readCount")
                        retryBackoff = (retryBackoff + 150).coerceAtMost(1500)
                        delay(retryBackoff)
                    }
                }
                spotter.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AUDIO_RECORD_EXCEPTION] ${e.message}", e)
            isDetecting.set(false)
        }
    }

    private fun stopWakeWordDetectionLoop() {
        isDetecting.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
            DooraVoiceController.releaseMicOwnership(DooraVoiceController.MicOwner.WAKE_SERVICE)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Wake Word Trigger & SpeechRecognizer Command Handoff
    // ──────────────────────────────────────────────────────────────

    private fun handleWakeWordDetected() {
        if (!isRunning) return

        // Prevent duplicate wake triggers if already handling a wake event
        if (!currentState.compareAndSet(WakeState.WAKE_LISTENING, WakeState.WAKE_DETECTED)) {
            Log.w(NATIVE_TAG, "[WAKE_STATE] Ignoring duplicate wake trigger. Current state=${currentState.get()}")
            return
        }

        Log.i(NATIVE_TAG, "[WAKE_TRACE] wake detected")
        Log.i(NATIVE_TAG, "[WAKE_FLOW] Keyword detected. Starting handoff to command recognizer.")
        isModeACommandWindow.set(false)
        onWakeWordDetectedListener?.invoke()

        // Transition to HANDOFF
        transitionToState(WakeState.HANDOFF)

        // 1. Release AudioRecord microphone before starting SpeechRecognizer
        stopWakeWordDetectionLoop()
        Log.i(NATIVE_TAG, "[WAKE_HANDOFF] AudioRecord stopped & microphone released.")

        // 2. Play immediate confirmation wake beep
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Log.i(NATIVE_TAG, "[WAKE_FLOW] Wake beep played.")
        } catch (e: Exception) {
            Log.w(TAG, "Tone playback error: ${e.message}")
        }

        // 3. Keep notification truthful during handoff
        updateNotification(
            title = "DooraGo Voice Wake",
            content = "Activating speech recognizer..."
        )

        // 4. Start command speech capture with multi-tier offline locale fallback
        isCommandListening.set(true)
        val candidates = buildOfflineLocaleCandidates()
        startCommandSpeechRecognition(candidates, candidateIndex = 0)
    }

    private fun buildOfflineLocaleCandidates(): List<String?> {
        val devLocale = Locale.getDefault().toLanguageTag()
        val list = mutableListOf<String?>()

        // Priority order: en-US (available on test SODA engine) -> en-IN -> hi-IN -> device default -> system default (null)
        list.add("en-US")
        list.add("en-IN")
        list.add("hi-IN")
        if (!devLocale.startsWith("en", ignoreCase = true) && !devLocale.startsWith("hi", ignoreCase = true)) {
            list.add(devLocale)
        }
        list.add(null) // null = system default without explicit EXTRA_LANGUAGE tag

        return list.distinct()
    }

    private fun startCommandSpeechRecognition(candidates: List<String?>, candidateIndex: Int) {
        cleanupSpeechRecognizer()
        cancelCommandTimeoutWatchdog()

        if (candidateIndex >= candidates.size) {
            Log.w(NATIVE_TAG, "[WAKE_COMMAND_ERROR] All offline speech recognition locale candidates exhausted. Returning to wake-word listening.")
            resumeWakeWordMode()
            return
        }

        val targetLocale = candidates[candidateIndex]
        transitionToState(WakeState.COMMAND_STARTING)

        // Arm initialization watchdog to recover if SpeechRecognizer fails to invoke onReadyForSpeech
        armCommandTimeoutWatchdog()

        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    Log.w(TAG, "SpeechRecognizer unavailable on device. Returning to wake word.")
                    resumeWakeWordMode()
                    return@post
                }

                val isOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

                Log.i(
                    NATIVE_TAG,
                    "[WAKE_COMMAND] requestedLocale=${targetLocale ?: "system_default"} selectedLocale=${targetLocale ?: "system_default"} onDeviceRecognizer=$isOnDevice offlinePreferred=true"
                )

                val recognizer = if (isOnDevice) {
                    try {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    } catch (e: Exception) {
                        SpeechRecognizer.createSpeechRecognizer(this)
                    }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(this)
                }

                speechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.i(NATIVE_TAG, "[WAKE_TRACE] recognizer ready")
                        Log.i(NATIVE_TAG, "[WAKE_FLOW] Command recognizer ready")
                        transitionToState(WakeState.COMMAND_READY)
                        isCommandListening.set(true)
                        updateNotification(
                            title = "DooraGo is listening...",
                            content = "Listening for your command"
                        )
                        // Arm 5-second command timeout watchdog ONLY AFTER onReadyForSpeech
                        armCommandTimeoutWatchdog()
                    }

                    override fun onBeginningOfSpeech() {
                        Log.i(NATIVE_TAG, "[WAKE_COMMAND] User speech detected")
                        transitionToState(WakeState.COMMAND_LISTENING)
                        isCommandListening.set(true)
                        // Re-arm watchdog to give full command window during speech
                        armCommandTimeoutWatchdog()
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.i(NATIVE_TAG, "[WAKE_COMMAND] Speech ended, processing...")
                    }

                    override fun onError(error: Int) {
                        Log.i(NATIVE_TAG, "[WAKE_COMMAND_ERROR] code=$error locale=${targetLocale ?: "system_default"}")
                        cancelCommandTimeoutWatchdog()

                        val isLocaleError = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                                error == 10 // ERROR_CANNOT_LISTEN_TO_DOWNLOAD

                        if (isLocaleError && (candidateIndex + 1) < candidates.size) {
                            val nextLocale = candidates[candidateIndex + 1]
                            Log.w(
                                NATIVE_TAG,
                                "[WAKE_COMMAND_FALLBACK] from=${targetLocale ?: "system_default"} to=${nextLocale ?: "system_default"}"
                            )
                            cleanupSpeechRecognizer()
                            startCommandSpeechRecognition(candidates, candidateIndex + 1)
                        } else {
                            resumeWakeWordMode()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        cancelCommandTimeoutWatchdog()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        Log.i(NATIVE_TAG, "[WAKE_TRACE] final transcript received: \"$text\"")
                        Log.i(NATIVE_TAG, "[WAKE_COMMAND] Final result received: \"$text\"")

                        if (text.isNotBlank()) {
                            processCapturedCommand(text)
                        } else {
                            resumeWakeWordMode()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)

                    if (!targetLocale.isNullOrBlank()) {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLocale)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLocale)
                    }

                    putExtra("android.speech.extra.PREFER_OFFLINE", true)
                }

                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start command SpeechRecognizer: ${e.message}", e)
                cancelCommandTimeoutWatchdog()
                resumeWakeWordMode()
            }
        }
    }

    private fun armCommandTimeoutWatchdog() {
        cancelCommandTimeoutWatchdog()
        val watchdog = Runnable {
            val st = currentState.get()
            if (st == WakeState.COMMAND_STARTING || st == WakeState.COMMAND_READY ||
                st == WakeState.COMMAND_LISTENING || st == WakeState.HANDOFF || isCommandListening.get()) {
                Log.i(NATIVE_TAG, "[WAKE_COMMAND_TIMEOUT] User silent or recognizer timeout (state=$st). Resuming wake-word mode.")
                resumeWakeWordMode()
            }
        }
        commandTimeoutRunnable = watchdog
        mainHandler.postDelayed(watchdog, COMMAND_TIMEOUT_MS)
    }

    private fun cancelCommandTimeoutWatchdog() {
        commandTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        commandTimeoutRunnable = null
    }

    private fun isWakeOnlyUtterance(rawText: String): Boolean {
        val cleaned = rawText.trim().lowercase(Locale.ROOT)
        val wakeOnlyRegex = Regex("^(?:(?:hey|ok|hello|hi)?\\s*(?:doora|dora|doraa|doura|dura|durah|doorago|dorago|दोरा|दूरा)[\\s,.:!-]*)$", RegexOption.IGNORE_CASE)
        return cleaned.isEmpty() || wakeOnlyRegex.matches(cleaned)
    }

    private fun hasLeadingWakeWord(rawText: String): Boolean {
        val cleaned = rawText.trim()
        val wakePrefixRegex = Regex("^(?i)(?:(?:hey|ok|hello|hi)?\\s*(?:doora|dora|doraa|doura|dura|durah|doorago|dorago|दोरा|दूरा)(?:[\\s,.:!-]+|$))", RegexOption.IGNORE_CASE)
        return wakePrefixRegex.containsMatchIn(cleaned)
    }

    private fun stripLeadingWakeWord(rawText: String): String {
        val cleaned = rawText.trim()
        val wakePrefixRegex = Regex("^(?i)(?:(?:hey|ok|hello|hi)?\\s*(?:doora|dora|doraa|doura|dura|durah|doorago|dorago|दोरा|दूरा)(?:[\\s,.:!-]+|$))", RegexOption.IGNORE_CASE)
        val stripped = cleaned.replace(wakePrefixRegex, "").trim()
        return if (stripped.isNotBlank()) stripped else cleaned
    }

    private fun processCapturedCommand(rawCommand: String) {
        // Prevent duplicate processing
        if (!currentState.compareAndSet(WakeState.COMMAND_READY, WakeState.COMMAND_PROCESSING) &&
            !currentState.compareAndSet(WakeState.COMMAND_LISTENING, WakeState.COMMAND_PROCESSING) &&
            !currentState.compareAndSet(WakeState.COMMAND_STARTING, WakeState.COMMAND_PROCESSING)
        ) {
            if (currentState.get() == WakeState.COMMAND_PROCESSING) {
                Log.w(NATIVE_TAG, "[WAKE_COMMAND] Already processing command. Ignoring duplicate callback.")
                return
            }
            transitionToState(WakeState.COMMAND_PROCESSING)
        }

        if (isWakeOnlyUtterance(rawCommand)) {
            Log.i(NATIVE_TAG, "[WAKE_GATE] state=COMMAND_LISTENING commandAllowed=true mode=MODE_A text=\"$rawCommand\"")
            isModeACommandWindow.set(true)
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            } catch (e: Exception) {
                Log.w(TAG, "Tone playback error: ${e.message}")
            }
            updateNotification(
                title = "DooraGo Voice Wake",
                content = "Activating speech recognizer..."
            )
            val candidates = buildOfflineLocaleCandidates()
            startCommandSpeechRecognition(candidates, candidateIndex = 0)
            return
        }

        val command: String
        val isDirectWake = hasLeadingWakeWord(rawCommand)
        val isFollowUpFromModeA = isModeACommandWindow.getAndSet(false)

        if (isDirectWake) {
            command = stripLeadingWakeWord(rawCommand)
            Log.i(NATIVE_TAG, "[WAKE_GATE] state=COMMAND_LISTENING commandAllowed=true mode=MODE_B_DIRECT raw=\"$rawCommand\" cleaned=\"$command\"")
        } else if (isFollowUpFromModeA) {
            command = rawCommand.trim()
            Log.i(NATIVE_TAG, "[WAKE_GATE] state=COMMAND_LISTENING commandAllowed=true mode=MODE_A_FOLLOWUP command=\"$command\"")
        } else {
            // WAKE GATE BLOCK: Speech transcript does NOT contain wake word and was NOT preceded by Mode A wake prompt!
            Log.w(NATIVE_TAG, "[WAKE_GATE] state=WAKE_LISTENING commandBlocked=true reason=WakeWordRequired text=\"$rawCommand\"")
            resumeWakeWordMode()
            return
        }

        Log.i(NATIVE_TAG, "[WAKE_COMMAND_RESULT] Command routed: \"$command\"")
        updateNotification(
            title = "DooraGo",
            content = "Processing command..."
        )

        // Notify Flutter UI listener
        onCommandCapturedListener?.invoke(command)

        // Process command with zero unnecessary LLM memory/CPU overhead
        serviceScope.launch(Dispatchers.IO) {
            try {
                val normResult = normalizer?.process(command)
                val responseMessage = when (normResult) {
                    is NormalizationResult.ExecuteDirect -> {
                        normResult.executionBlock()
                    }
                    is NormalizationResult.AskFollowUp -> {
                        normResult.promptToUser
                    }
                    is NormalizationResult.RouteToLlm, null -> {
                        // TIER 2: Attempt On-Device Gemini Nano / AICore (if available on Android 14+)
                        var nanoResponse: String? = null
                        try {
                            val nanoEngine = lazyNanoEngine ?: AiCoreGeminiNanoEngine(applicationContext).also { lazyNanoEngine = it }
                            val nanoResult = nanoEngine.understandCommand(command)
                            if (nanoResult.isSuccess) {
                                val jsonString = nanoResult.getOrNull()
                                if (!jsonString.isNullOrBlank()) {
                                    Log.i(NATIVE_TAG, "[AICORE] Structured intent validated: $jsonString")
                                    nanoResponse = executeValidatedJsonIntent(jsonString)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[$AICORE_TAG] Tier 2 exception: ${e.message}")
                        }

                        if (!nanoResponse.isNullOrBlank()) {
                            nanoResponse
                        } else {
                            // TIER 3: Fallback to bundled LiteRT-LM FunctionGemma 270M engine
                            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] Fallback to LiteRT-LM FunctionGemma (Tier 3)")
                            val engine = lazyAiEngine ?: LiteRtLmAiEngine(
                                context = applicationContext,
                                toolRegistry = MobileActionsToolRegistry(applicationContext)
                            ).also { lazyAiEngine = it }

                            val result = engine.processPrompt(command)
                            result.getOrNull() ?: "Command executed."
                        }
                    }
                }

                Log.i(NATIVE_TAG, "[WAKE_COMMAND_EXECUTED] result=\"$responseMessage\"")

                updateNotification(
                    title = "DooraGo Voice Wake active",
                    content = "Say 'Doora' to activate"
                )

                delay(1500)
            } catch (e: Exception) {
                Log.e(TAG, "Command execution error: ${e.message}", e)
            } finally {
                transitionToState(WakeState.COMMAND_COMPLETED)
                mainHandler.post {
                    resumeWakeWordMode()
                }
            }
        }
    }

    private fun resumeWakeWordMode() {
        if (!isRunning) return

        cancelCommandTimeoutWatchdog()
        cleanupSpeechRecognizer()
        isCommandListening.set(false)
        isModeACommandWindow.set(false)

        transitionToState(WakeState.RESUMING_WAKE)

        updateNotification(
            title = "DooraGo Voice Wake active",
            content = "Say 'Doora' to activate"
        )

        // Small guard delay before re-enabling AudioRecord to avoid hardware lock
        serviceScope.launch {
            delay(250)
            if (isRunning && !isDetecting.get() && !isCommandListening.get()) {
                transitionToState(WakeState.WAKE_LISTENING)
                startWakeWordDetectionLoop()
            }
        }
    }

    private fun cleanupSpeechRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up SpeechRecognizer: ${e.message}")
        } finally {
            speechRecognizer = null
            DooraVoiceController.releaseMicOwnership(DooraVoiceController.MicOwner.COMMAND_RECOGNIZER)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Notification Management
    // ──────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Wake Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active status when DooraGo Voice Wake is listening locally for 'Doora'."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun executeValidatedJsonIntent(jsonString: String): String? {
        return try {
            val json = JSONObject(jsonString)
            val action = json.optString("action", "UNKNOWN")
            when (action) {
                "OPEN_APP" -> {
                    val appName = json.optString("app_name", "").trim()
                    if (appName.isNotEmpty()) {
                        val normRes = normalizer?.process("open $appName")
                        if (normRes is NormalizationResult.ExecuteDirect) normRes.executionBlock() else null
                    } else null
                }
                "TOGGLE_FLASHLIGHT" -> {
                    val state = json.optString("state", "ON").uppercase()
                    val normRes = normalizer?.process(if (state == "OFF") "turn off flashlight" else "turn on flashlight")
                    if (normRes is NormalizationResult.ExecuteDirect) normRes.executionBlock() else null
                }
                "CALL_CONTACT" -> {
                    val contactName = json.optString("contact_name", "").trim()
                    if (contactName.isNotEmpty()) {
                        val normRes = normalizer?.process("call $contactName")
                        if (normRes is NormalizationResult.ExecuteDirect) normRes.executionBlock() else null
                    } else null
                }
                "CALL_NUMBER" -> {
                    val phoneNum = json.optString("phone_number", "").trim()
                    if (phoneNum.isNotEmpty()) {
                        val normRes = normalizer?.process("call $phoneNum")
                        if (normRes is NormalizationResult.ExecuteDirect) normRes.executionBlock() else null
                    } else null
                }
                "SEND_SMS" -> {
                    val contact = json.optString("contact_name", json.optString("phone_number", "")).trim()
                    val msg = json.optString("message", "").trim()
                    if (contact.isNotEmpty()) {
                        val normRes = normalizer?.process("send message to $contact $msg")
                        if (normRes is NormalizationResult.ExecuteDirect) normRes.executionBlock() else null
                    } else null
                }
                "SEND_WHATSAPP" -> {
                    val contact = json.optString("contact_name", json.optString("phone_number", "")).trim()
                    val msg = json.optString("message", "").trim()
                    if (contact.isNotEmpty()) {
                        val normRes = normalizer?.process("whatsapp $contact $msg")
                        if (normRes is NormalizationResult.ExecuteDirect) normRes.executionBlock() else null
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$AICORE_TAG] Intent execution exception: ${e.message}")
            null
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DooraWakeWordService::class.java).apply {
            action = ACTION_STOP_VOICE_WAKE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Turn off",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        if (!isRunning) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    override fun onDestroy() {
        Log.i(TAG, "[SERVICE_DESTROY] Destroying DooraWakeWordService")
        activeInstance = null
        isRunning = false
        onStateChangeListener?.invoke(false)

        cancelCommandTimeoutWatchdog()
        stopWakeWordDetectionLoop()
        cleanupSpeechRecognizer()
        releaseWakeLock()

        toneGenerator?.release()
        toneGenerator = null

        lazyAiEngine?.dispose()
        lazyAiEngine = null
        normalizer = null

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
