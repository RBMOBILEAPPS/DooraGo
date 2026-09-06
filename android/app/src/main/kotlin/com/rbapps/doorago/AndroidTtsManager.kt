package com.rbapps.doorago

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Android Native Text-to-Speech Manager for DooraGo.
 *
 * Implements 100% offline TTS using Android's built-in TextToSpeech engine and installed voice data.
 * Features:
 * - Proactive voice data availability checks
 * - Multi-tier locale fallback (hi-IN -> hi -> en-IN -> en-US -> default)
 * - Automatic voice data installation / Android TTS settings routing
 * - Automatic speech interruption when user speaks (STT) or when new commands arrive
 * - Safe lifecycle management and zero cloud dependencies
 */
class AndroidTtsManager(
    private val context: Context,
    private var activityProvider: () -> Activity?
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AndroidTtsManager"
        private const val NATIVE_TAG = "DooraGoNative"
        private const val UTTERANCE_ID_PREFIX = "doorago_tts_"

        val LOCALE_HINDI_IN: Locale = Locale("hi", "IN")
        val LOCALE_HINDI: Locale = Locale("hi")
        val LOCALE_ENGLISH_IN: Locale = Locale("en", "IN")
        val LOCALE_ENGLISH_US: Locale = Locale.US
    }

    interface TtsCallback {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int)
        fun onVoiceDataMissing(missingLocale: String)
    }

    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false
    private var isInitializing = false
    private var isSpeaking = false
    private var lastUtteranceText: String? = null
    private var ttsCallback: TtsCallback? = null
    private val initCallbacks = mutableListOf<(Boolean) -> Unit>()

    init {
        initialize()
    }

    fun setCallback(callback: TtsCallback?) {
        this.ttsCallback = callback
    }

    /**
     * Initializes or re-initializes the TextToSpeech engine.
     */
    @Synchronized
    fun initialize(onComplete: ((Boolean) -> Unit)? = null) {
        if (onComplete != null) {
            initCallbacks.add(onComplete)
        }

        if (isInitialized && tts != null) {
            notifyInitCallbacks(true)
            return
        }

        if (isInitializing) {
            return
        }

        isInitializing = true
        Log.i(NATIVE_TAG, "[TTS_INIT] Initializing Android TextToSpeech engine...")

        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "[TTS_ERROR] Failed to instantiate TextToSpeech: ${e.message}", e)
            isInitializing = false
            notifyInitCallbacks(false)
        }
    }

    override fun onInit(status: Int) {
        isInitializing = false
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            Log.i(NATIVE_TAG, "[TTS_INIT] TextToSpeech initialized successfully")

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    Log.i(NATIVE_TAG, "[TTS_STATUS] Speaking started: $utteranceId")
                    mainHandler.post {
                        ttsCallback?.onStart(utteranceId ?: "")
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    Log.i(NATIVE_TAG, "[TTS_STATUS] Speaking completed: $utteranceId")
                    mainHandler.post {
                        ttsCallback?.onDone(utteranceId ?: "")
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    Log.w(TAG, "[TTS_ERROR] Speaking error: $utteranceId")
                    mainHandler.post {
                        ttsCallback?.onError(utteranceId ?: "", -1)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isSpeaking = false
                    Log.w(TAG, "[TTS_ERROR] Speaking error: $utteranceId errorCode=$errorCode")
                    mainHandler.post {
                        ttsCallback?.onError(utteranceId ?: "", errorCode)
                    }
                }
            })

            // Log available voices & locales
            checkAndLogLocales()
            notifyInitCallbacks(true)
        } else {
            isInitialized = false
            Log.e(TAG, "[TTS_INIT] TextToSpeech initialization failed with status=$status")
            notifyInitCallbacks(false)
        }
    }

    private fun notifyInitCallbacks(success: Boolean) {
        val callbacks = ArrayList(initCallbacks)
        initCallbacks.clear()
        for (cb in callbacks) {
            cb.invoke(success)
        }
    }

    fun isAvailable(): Boolean = isInitialized && tts != null

    fun isSpeaking(): Boolean = isSpeaking || (tts?.isSpeaking == true)

    /**
     * Checks availability of a specific locale in the TTS engine.
     */
    fun isLanguageAvailable(locale: Locale): Int {
        val t = tts ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return try {
            t.isLanguageAvailable(locale)
        } catch (e: Exception) {
            Log.e(TAG, "[TTS_ERROR] isLanguageAvailable failed for $locale: ${e.message}")
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    /**
     * Returns true if voice data is present and ready to speak for this locale.
     */
    fun isVoiceDataInstalled(locale: Locale): Boolean {
        val status = isLanguageAvailable(locale)
        return (status == TextToSpeech.LANG_AVAILABLE ||
                status == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                status == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE)
    }

    /**
     * Returns true if language is supported by the engine but voice data package is missing.
     */
    fun isVoiceDataMissing(locale: Locale): Boolean {
        val status = isLanguageAvailable(locale)
        return status == TextToSpeech.LANG_MISSING_DATA
    }

    private fun checkAndLogLocales() {
        val hiIn = isLanguageAvailable(LOCALE_HINDI_IN)
        val enIn = isLanguageAvailable(LOCALE_ENGLISH_IN)
        val enUs = isLanguageAvailable(LOCALE_ENGLISH_US)
        Log.i(NATIVE_TAG, "[TTS_LOCALE] hi-IN status=$hiIn installed=${isVoiceDataInstalled(LOCALE_HINDI_IN)}")
        Log.i(NATIVE_TAG, "[TTS_LOCALE] en-IN status=$enIn installed=${isVoiceDataInstalled(LOCALE_ENGLISH_IN)}")
        Log.i(NATIVE_TAG, "[TTS_LOCALE] en-US status=$enUs installed=${isVoiceDataInstalled(LOCALE_ENGLISH_US)}")
    }

    /**
     * Detects if the given text is in Hindi or Hinglish.
     */
    fun isHindiText(text: String): Boolean {
        // 1. Devanagari Unicode range
        if (text.any { it in '\u0900'..'\u097F' }) return true

        // 2. Common Hinglish verbs, auxiliary words, and markers
        val lower = " ${text.lowercase(Locale.ROOT)} "
        val hindiKeywords = listOf(
            " hai ", " hain ", " hoon ", " kholo ", " karo ", " kar do ", " band ", " chalu ",
            " jalao ", " bujhao ", " dikhao ", " rasta ", " kitna ", " kitni ", " baje ",
            " lagao ", " bhejo ", " banao ", " nahi ", " kya ", " kab ", " kaise ", " kar raha ",
            " ho gaya ", " mil gaya ", " sun raha "
        )
        return hindiKeywords.any { lower.contains(it) }
    }

    /**
     * Selects the most appropriate available locale for speaking the text.
     */
    fun selectBestLocale(text: String): Locale {
        val wantsHindi = isHindiText(text)
        if (wantsHindi) {
            if (isVoiceDataInstalled(LOCALE_HINDI_IN)) return LOCALE_HINDI_IN
            if (isVoiceDataInstalled(LOCALE_HINDI)) return LOCALE_HINDI
        }

        // English & default fallbacks
        if (isVoiceDataInstalled(LOCALE_ENGLISH_IN)) return LOCALE_ENGLISH_IN
        if (isVoiceDataInstalled(LOCALE_ENGLISH_US)) return LOCALE_ENGLISH_US
        if (isVoiceDataInstalled(Locale.getDefault())) return Locale.getDefault()

        // If no confirmed voice data, return best guess
        return if (wantsHindi) LOCALE_HINDI_IN else LOCALE_ENGLISH_IN
    }

    /**
     * Speaks the provided text offline via Android TextToSpeech.
     * Replaces any currently playing utterance immediately (QUEUE_FLUSH).
     */
    fun speak(rawText: String, preferredLocaleStr: String? = null): Map<String, Any?> {
        val cleanText = sanitizeTtsText(rawText)
        if (cleanText.isBlank()) {
            return mapOf("success" to false, "reason" to "EMPTY_TEXT")
        }

        if (!isInitialized || tts == null) {
            Log.w(TAG, "[TTS_SPEAK] TTS engine not initialized yet")
            initialize()
            return mapOf("success" to false, "reason" to "NOT_INITIALIZED")
        }

        val targetLocale = if (!preferredLocaleStr.isNullOrBlank()) {
            val parts = preferredLocaleStr.split("-", "_")
            if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
        } else {
            selectBestLocale(cleanText)
        }

        Log.i(NATIVE_TAG, "[TTS_LOCALE] targetLocale=${targetLocale.toLanguageTag()}")

        val isInstalled = isVoiceDataInstalled(targetLocale)
        val isMissing = isVoiceDataMissing(targetLocale)

        if (isMissing || (!isInstalled && isVoiceDataMissing(LOCALE_ENGLISH_IN))) {
            val missingTag = targetLocale.toLanguageTag()
            Log.w(NATIVE_TAG, "[TTS_VOICE_DATA] Voice data missing for $missingTag")
            mainHandler.post {
                ttsCallback?.onVoiceDataMissing(missingTag)
            }
            return mapOf(
                "success" to false,
                "reason" to "VOICE_DATA_MISSING",
                "missingLocale" to missingTag
            )
        }

        return try {
            tts?.language = targetLocale
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)

            val utteranceId = "$UTTERANCE_ID_PREFIX${System.currentTimeMillis()}"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            Log.i(NATIVE_TAG, "[TTS_SPEAK] text='$cleanText' locale=${targetLocale.toLanguageTag()}")
            lastUtteranceText = cleanText

            val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            val success = result == TextToSpeech.SUCCESS

            Log.i(NATIVE_TAG, "[TTS_STATUS] result=$result success=$success")
            mapOf("success" to success, "utteranceId" to utteranceId, "locale" to targetLocale.toLanguageTag())
        } catch (e: Exception) {
            Log.e(TAG, "[TTS_ERROR] speak exception: ${e.message}", e)
            mapOf("success" to false, "error" to (e.message ?: "Speak failed"))
        }
    }

    /**
     * Stops any currently playing speech immediately.
     */
    fun stop() {
        try {
            if (isSpeaking || tts?.isSpeaking == true) {
                Log.i(NATIVE_TAG, "[TTS_STOP] Stopping active TTS speech playback")
                tts?.stop()
                isSpeaking = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[TTS_ERROR] stop exception: ${e.message}")
        }
    }

    /**
     * Opens the official Android TTS voice data installation intent.
     */
    fun openVoiceDataInstaller(): Boolean {
        Log.i(NATIVE_TAG, "[TTS_INSTALL] Launching Android TTS voice-data installer")
        val activity = activityProvider.invoke() ?: context
        return try {
            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "[TTS_INSTALL] ACTION_INSTALL_TTS_DATA unavailable, opening TTS settings: ${e.message}")
            openTtsSettings()
        }
    }

    /**
     * Opens Android Text-to-Speech system settings.
     */
    fun openTtsSettings(): Boolean {
        Log.i(NATIVE_TAG, "[TTS_INSTALL] Launching Android Text-to-Speech settings")
        val activity = activityProvider.invoke() ?: context
        return try {
            val settingsIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(settingsIntent)
            true
        } catch (e: Exception) {
            try {
                val voiceIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(voiceIntent)
                true
            } catch (e2: Exception) {
                try {
                    val genSettings = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(genSettings)
                    true
                } catch (e3: Exception) {
                    Log.e(TAG, "[TTS_ERROR] Failed to open TTS settings: ${e3.message}")
                    false
                }
            }
        }
    }

    /**
     * Returns detailed TTS status for Flutter diagnostics.
     */
    fun getStatus(): Map<String, Any?> {
        val hiInAvailable = isVoiceDataInstalled(LOCALE_HINDI_IN)
        val enInAvailable = isVoiceDataInstalled(LOCALE_ENGLISH_IN)
        val hiDataMissing = isVoiceDataMissing(LOCALE_HINDI_IN)
        val enDataMissing = isVoiceDataMissing(LOCALE_ENGLISH_IN)

        return mapOf(
            "isAvailable" to isAvailable(),
            "isInitialized" to isInitialized,
            "isSpeaking" to isSpeaking(),
            "hiInAvailable" to hiInAvailable,
            "enInAvailable" to enInAvailable,
            "hiDataMissing" to hiDataMissing,
            "enDataMissing" to enDataMissing,
            "engineName" to (tts?.defaultEngine ?: "android_default")
        )
    }

    /**
     * Cleans text for speech output (removes markdown symbols, handles technical tokens).
     */
    private fun sanitizeTtsText(input: String): String {
        var text = input
        // Remove markdown formatting
        text = text.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        text = text.replace(Regex("\\*(.*?)\\*"), "$1")
        text = text.replace(Regex("`(.*?)`"), "$1")
        text = text.replace(Regex("#+\\s*"), "")
        text = text.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")

        // Clean extra whitespace
        text = text.replace(Regex("\\s+"), " ").trim()
        return text
    }

    /**
     * Releases TTS resources.
     */
    fun shutdown() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            Log.i(NATIVE_TAG, "[TTS_STATUS] TTS resources released")
        } catch (e: Exception) {
            Log.e(TAG, "[TTS_ERROR] shutdown exception: ${e.message}")
        }
    }
}
