package com.rbapps.doorago

import android.content.Context
import android.util.Log

/**
 * Centralized Voice Entry Controller for DooraGo Assistant Architecture.
 *
 * Coordinates Voice Mode (ASSISTANT_MODE, FALLBACK_WAKE_MODE, MANUAL_MIC_MODE)
 * and enforces single microphone ownership across all capture paths.
 */
object DooraVoiceController {

    private const val TAG = "DooraVoiceController"
    private const val NATIVE_TAG = "DooraGoNative"

    enum class VoiceMode {
        ASSISTANT_MODE,
        FALLBACK_WAKE_MODE,
        MANUAL_MIC_MODE
    }

    enum class MicOwner {
        NONE,
        WAKE_SERVICE,
        COMMAND_RECOGNIZER,
        MANUAL_MIC,
        VOICE_INTERACTION_SESSION
    }

    @Volatile
    private var currentMode: VoiceMode = VoiceMode.FALLBACK_WAKE_MODE

    @Volatile
    private var currentOwner: MicOwner = MicOwner.NONE

    fun updateMode(context: Context): VoiceMode {
        val roleHeld = DooraVoiceInteractionService.isRoleHeld(context)
        currentMode = if (roleHeld) {
            VoiceMode.ASSISTANT_MODE
        } else {
            VoiceMode.FALLBACK_WAKE_MODE
        }
        Log.i(NATIVE_TAG, "[VOICE_MODE] activeMode=$currentMode roleHeld=$roleHeld")
        return currentMode
    }

    fun getActiveMode(): VoiceMode = currentMode

    fun getMicOwner(): MicOwner = currentOwner

    @Synchronized
    fun requestMicOwnership(newOwner: MicOwner): Boolean {
        if (currentOwner == newOwner) {
            return true
        }

        Log.i(NATIVE_TAG, "[VOICE_OWNER] changing owner from $currentOwner to $newOwner")

        // Release prior mic owner
        when (currentOwner) {
            MicOwner.WAKE_SERVICE -> {
                DooraWakeWordService.pauseDetection()
            }
            MicOwner.COMMAND_RECOGNIZER -> {
                // Command recognizer will be cleaned up by service lifecycle
            }
            MicOwner.MANUAL_MIC -> {
                // Manual mic handles its own cleanup
            }
            MicOwner.VOICE_INTERACTION_SESSION -> {
                // Voice interaction session handles its own cleanup
            }
            MicOwner.NONE -> {}
        }

        currentOwner = newOwner
        Log.i(NATIVE_TAG, "[VOICE_OWNER] owner=$currentOwner")
        return true
    }

    @Synchronized
    fun releaseMicOwnership(releasingOwner: MicOwner) {
        if (currentOwner == releasingOwner) {
            Log.i(NATIVE_TAG, "[VOICE_OWNER] released by $releasingOwner")
            currentOwner = MicOwner.NONE
        }
    }
}
