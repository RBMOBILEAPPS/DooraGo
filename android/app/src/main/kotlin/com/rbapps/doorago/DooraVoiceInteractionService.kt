package com.rbapps.doorago

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Native Android VoiceInteractionService for DooraGo Assistant Architecture.
 *
 * Serves as the primary system-level entry point when DooraGo is set as the default
 * Android Assistant on supported devices.
 *
 * ARCHITECTURAL PRINCIPLES:
 * 1. Zero Business Logic: Does NOT handle contact matching, app launching, or AI LLM inference.
 *    Delegates all command processing to the existing trusted Local Command Engine.
 * 2. Privacy First: Respects RECORD_AUDIO permissions and Android privacy indicators.
 * 3. Graceful Fallback: If Assistant role is unavailable or disabled by user, the existing
 *    DooraWakeWordService continuous microphone service operates seamlessly.
 */
class DooraVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "DooraVoiceService"
        private const val NATIVE_TAG = "DooraGoNative"

        @Volatile
        var isAssistantRoleActive: Boolean = false
            private set

        fun isAssistantServiceAvailable(context: Context): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        }

        fun isRoleHeld(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                    return roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
                }
            }
            return isAssistantRoleActive
        }

        fun openAssistantSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent("android.settings.VOICE_INPUT_SETTINGS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed to open Assistant Settings: ${ex.message}")
                }
            }
        }
    }

    override fun onReady() {
        super.onReady()
        isAssistantRoleActive = true
        Log.i(NATIVE_TAG, "[ASSISTANT] roleAvailable=true roleActive=true")
        Log.i(TAG, "DooraVoiceInteractionService is ready and set as active Android Assistant.")
    }

    override fun onShutdown() {
        super.onShutdown()
        isAssistantRoleActive = false
        Log.i(NATIVE_TAG, "[ASSISTANT] roleActive=false")
        Log.i(TAG, "DooraVoiceInteractionService shut down.")
    }
}
