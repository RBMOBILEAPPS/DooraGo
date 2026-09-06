package com.rbapps.doorago

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Production Native Foreground Service for the Optional Floating Doora Button.
 *
 * Renders a compact, draggable overlay view above other applications using Android WindowManager.
 * Enforces strict microphone privacy:
 * - Floating Button visible != Microphone active.
 * - Microphone is 100% OFF during FLOATING_IDLE. Zero AudioRecord or SpeechRecognizer active.
 * - When user taps button, transitions to FLOATING_LISTENING, acquires microphone via DooraVoiceController,
 *   captures ONE voice command via AndroidVoiceManager, normalizes and executes it via MobileActionNormalizer,
 *   and immediately releases the microphone and returns to FLOATING_IDLE.
 */
class DooraFloatingButtonService : Service() {

    enum class FloatingState {
        FLOATING_OFF,
        FLOATING_IDLE,
        FLOATING_LISTENING,
        FLOATING_PROCESSING,
        FLOATING_ERROR,
        FLOATING_SUCCESS
    }

    companion object {
        private const val TAG = "DooraFloatingService"
        private const val NATIVE_TAG = "DooraGoNative"

        const val ACTION_START_FLOATING_BUTTON = "com.rbapps.doorago.action.START_FLOATING_BUTTON"
        const val ACTION_STOP_FLOATING_BUTTON = "com.rbapps.doorago.action.STOP_FLOATING_BUTTON"

        const val CHANNEL_ID = "doora_floating_button_channel"
        const val NOTIFICATION_ID = 4002

        @Volatile
        private var isServiceRunning = false

        fun isRunning(): Boolean = isServiceRunning

        fun start(context: Context) {
            val intent = Intent(context, DooraFloatingButtonService::class.java).apply {
                action = ACTION_START_FLOATING_BUTTON
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DooraFloatingButtonService::class.java).apply {
                action = ACTION_STOP_FLOATING_BUTTON
            }
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var statusDot: View? = null
    private var iconView: ImageView? = null
    private var labelText: TextView? = null

    private var voiceManager: AndroidVoiceManager? = null
    private var normalizer: MobileActionNormalizer? = null
    private var toolRegistry: MobileActionsToolRegistry? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentState = FloatingState.FLOATING_OFF
    private var wasWakeServiceActiveBeforeTap = false

    // Dragging parameters
    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.i(NATIVE_TAG, "[FLOATING_SERVICE] onCreate")

        createNotificationChannel()

        toolRegistry = MobileActionsToolRegistry(
            context = applicationContext,
            onActionExecuted = { actionName, _, resultMsg ->
                Log.i(NATIVE_TAG, "[FLOATING_ACTION] action=$actionName msg=$resultMsg")
            }
        )

        normalizer = MobileActionNormalizer(
            context = applicationContext,
            toolRegistry = toolRegistry!!
        )

        voiceManager = AndroidVoiceManager(
            context = applicationContext,
            activityProvider = { null }
        ).apply {
            setCallback(createVoiceCallback())
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(NATIVE_TAG, "[FLOATING_SERVICE] onStartCommand action=$action")

        if (action == ACTION_STOP_FLOATING_BUTTON) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "[FLOATING_SERVICE] SYSTEM_ALERT_WINDOW permission missing. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        setupOverlayView()
        setState(FloatingState.FLOATING_IDLE)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Doora Button",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating Doora shortcut button over apps for easy voice commands"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DooraFloatingButtonService::class.java).apply {
            action = ACTION_STOP_FLOATING_BUTTON
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Doora Active")
            .setContentText("Tap floating button anytime to speak a command")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn Off", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with microphone type failed, falling back: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupOverlayView() {
        if (overlayView != null) return

        val density = resources.displayMetrics.density

        // Create container view
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            contentDescription = "Doora voice button. Tap to start voice command."
            isFocusable = true
            isClickable = true
        }

        // Status indicator dot
        val dot = View(this).apply {
            val size = (8 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        statusDot = dot
        layout.addView(dot)

        // Brand Icon
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            val iconSize = (22 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (6 * density).toInt()
            }
        }
        iconView = icon
        layout.addView(icon)

        // Text Label
        val tv = TextView(this).apply {
            text = "Doora"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        labelText = tv
        layout.addView(tv)

        overlayView = layout

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (20 * density).toInt()
            y = (200 * density).toInt()
        }
        windowParams = params

        setupTouchAndDragListener(layout, params)

        try {
            windowManager?.addView(layout, params)
            Log.i(NATIVE_TAG, "[FLOATING_SERVICE] WindowManager overlay view added")
        } catch (e: Exception) {
            Log.e(TAG, "[FLOATING_SERVICE] Failed to add overlay view: ${e.message}", e)
        }
    }

    private fun setupTouchAndDragListener(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = (8 * resources.displayMetrics.density).toInt()

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()

                    if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params.x = clampX(initialX + dx)
                        params.y = clampY(initialY + dy)
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "updateViewLayout error: ${e.message}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                        handleButtonTap()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun clampX(x: Int): Int {
        val displayMetrics = resources.displayMetrics
        val width = overlayView?.width ?: 200
        return x.coerceIn(0, (displayMetrics.widthPixels - width).coerceAtLeast(0))
    }

    private fun clampY(y: Int): Int {
        val displayMetrics = resources.displayMetrics
        val height = overlayView?.height ?: 100
        return y.coerceIn(50, (displayMetrics.heightPixels - height).coerceAtLeast(50))
    }

    private fun setState(state: FloatingState, messageText: String? = null) {
        currentState = state
        Log.i(NATIVE_TAG, "[FLOATING_STATE] state=$state msg=${messageText ?: ""}")

        val density = resources.displayMetrics.density
        val bgDrawable = GradientDrawable()
        bgDrawable.cornerRadius = 24 * density

        when (state) {
            FloatingState.FLOATING_IDLE -> {
                bgDrawable.setColor(Color.parseColor("#E61E1E28")) // Dark sleek purple
                bgDrawable.setStroke((1.5 * density).toInt(), Color.parseColor("#448A85FF"))
                statusDot?.background = createDotDrawable(Color.parseColor("#4CAF50")) // Green idle dot
                labelText?.text = messageText ?: "Doora"
                overlayView?.contentDescription = "Doora voice button. Idle. Tap to speak command."
            }
            FloatingState.FLOATING_LISTENING -> {
                bgDrawable.setColor(Color.parseColor("#F012131F"))
                bgDrawable.setStroke((2 * density).toInt(), Color.parseColor("#00E5FF")) // Glowing Cyan
                statusDot?.background = createDotDrawable(Color.parseColor("#FF5252")) // Red recording dot
                labelText?.text = messageText ?: "Listening..."
                overlayView?.contentDescription = "Listening for a voice command..."
            }
            FloatingState.FLOATING_PROCESSING -> {
                bgDrawable.setColor(Color.parseColor("#F01A1A2A"))
                bgDrawable.setStroke((2 * density).toInt(), Color.parseColor("#FFD700")) // Gold processing
                statusDot?.background = createDotDrawable(Color.parseColor("#FFD700"))
                labelText?.text = messageText ?: "Thinking..."
                overlayView?.contentDescription = "Processing voice command..."
            }
            FloatingState.FLOATING_SUCCESS -> {
                bgDrawable.setColor(Color.parseColor("#F00D2B1D"))
                bgDrawable.setStroke((2 * density).toInt(), Color.parseColor("#00E676")) // Vibrant Green
                statusDot?.background = createDotDrawable(Color.parseColor("#00E676"))
                labelText?.text = messageText ?: "Done"
                overlayView?.contentDescription = "Command completed"
            }
            FloatingState.FLOATING_ERROR -> {
                bgDrawable.setColor(Color.parseColor("#F02D1515"))
                bgDrawable.setStroke((2 * density).toInt(), Color.parseColor("#FF1744")) // Crimson Red
                statusDot?.background = createDotDrawable(Color.parseColor("#FF1744"))
                labelText?.text = messageText ?: "Try again"
                overlayView?.contentDescription = "Voice recognition error"
            }
            FloatingState.FLOATING_OFF -> {}
        }

        overlayView?.background = bgDrawable
    }

    private fun createDotDrawable(colorInt: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorInt)
        }
    }

    private fun handleButtonTap() {
        if (currentState != FloatingState.FLOATING_IDLE) {
            Log.w(TAG, "[FLOATING_TAP] Ignored tap in state $currentState")
            return
        }

        Log.i(NATIVE_TAG, "[FLOATING_TAP] User tapped floating Doora button")
        setState(FloatingState.FLOATING_LISTENING)

        // Mic Handoff: Request mic ownership & pause background wake service if running
        wasWakeServiceActiveBeforeTap = DooraWakeWordService.isVoiceWakeRunning()
        DooraVoiceController.requestMicOwnership(DooraVoiceController.MicOwner.MANUAL_MIC)

        voiceManager?.startListening(
            preferredLanguage = null,
            onPermissionRequired = {
                mainHandler.post {
                    setState(FloatingState.FLOATING_ERROR, "Mic perm needed")
                    scheduleReturnToIdle(2000L)
                }
            }
        )
    }

    private fun createVoiceCallback(): AndroidVoiceManager.VoiceCallback {
        return object : AndroidVoiceManager.VoiceCallback {
            override fun onReady() {
                Log.i(NATIVE_TAG, "[FLOATING_VOICE] Recognizer ready")
                // Start 6s ready timeout
                mainHandler.removeCallbacks(timeoutRunnable)
                mainHandler.postDelayed(timeoutRunnable, 6000L)
            }

            override fun onBeginSpeech() {
                Log.i(NATIVE_TAG, "[FLOATING_VOICE] Speech started")
                mainHandler.removeCallbacks(timeoutRunnable)
            }

            override fun onEndSpeech() {
                Log.i(NATIVE_TAG, "[FLOATING_VOICE] Speech ended")
                mainHandler.removeCallbacks(timeoutRunnable)
            }

            override fun onResult(recognizedText: String) {
                mainHandler.removeCallbacks(timeoutRunnable)
                Log.i(NATIVE_TAG, "[FLOATING_RESULT] transcript=\"$recognizedText\"")
                processVoiceCommand(recognizedText)
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                mainHandler.removeCallbacks(timeoutRunnable)
                Log.w(TAG, "[FLOATING_ERROR] code=$errorCode msg=$errorMessage")
                setState(FloatingState.FLOATING_ERROR, "Try again")
                finishVoiceSession()
                scheduleReturnToIdle(2000L)
            }

            override fun onListeningStateChanged(isListening: Boolean) {}
        }
    }

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "[FLOATING_TIMEOUT] No speech detected within ready timeout")
        voiceManager?.cancel()
        setState(FloatingState.FLOATING_ERROR, "No speech")
        finishVoiceSession()
        scheduleReturnToIdle(2000L)
    }

    private fun processVoiceCommand(rawTranscript: String) {
        if (rawTranscript.isBlank()) {
            setState(FloatingState.FLOATING_ERROR, "No speech")
            finishVoiceSession()
            scheduleReturnToIdle(2000L)
            return
        }

        setState(FloatingState.FLOATING_PROCESSING)

        serviceScope.launch(Dispatchers.IO) {
            val norm = normalizer
            if (norm != null) {
                val result = norm.process(rawTranscript)
                val outputText = when (result) {
                    is NormalizationResult.ExecuteDirect -> {
                        result.executionBlock.invoke()
                    }
                    is NormalizationResult.RouteToLlm -> {
                        val executor = LocalDeviceActionExecutor(applicationContext)
                        executor.launchApp(rawTranscript)
                    }
                    is NormalizationResult.AskFollowUp -> {
                        result.promptToUser
                    }
                }
                mainHandler.post {
                    Log.i(NATIVE_TAG, "[FLOATING_EXECUTION] result=\"$outputText\"")
                    setState(FloatingState.FLOATING_SUCCESS, "Done")
                    finishVoiceSession()
                    scheduleReturnToIdle(1800L)
                }
            } else {
                mainHandler.post {
                    setState(FloatingState.FLOATING_ERROR, "Error")
                    finishVoiceSession()
                    scheduleReturnToIdle(2000L)
                }
            }
        }
    }

    private fun finishVoiceSession() {
        voiceManager?.stopListening()
        DooraVoiceController.releaseMicOwnership(DooraVoiceController.MicOwner.MANUAL_MIC)
        if (wasWakeServiceActiveBeforeTap) {
            DooraWakeWordService.resumeDetection()
        }
    }

    private fun scheduleReturnToIdle(delayMs: Long) {
        mainHandler.postDelayed({
            if (currentState != FloatingState.FLOATING_OFF) {
                setState(FloatingState.FLOATING_IDLE)
            }
        }, delayMs)
    }

    override fun onDestroy() {
        Log.i(NATIVE_TAG, "[FLOATING_SERVICE] onDestroy")
        isServiceRunning = false
        currentState = FloatingState.FLOATING_OFF

        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()

        finishVoiceSession()
        voiceManager?.destroy()

        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: ${e.message}")
            }
            overlayView = null
        }

        super.onDestroy()
    }
}
