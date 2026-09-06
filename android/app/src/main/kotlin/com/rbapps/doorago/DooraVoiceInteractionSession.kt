package com.rbapps.doorago

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * Android Assistant Session implementation for DooraGo.
 *
 * Handles voice assistant invocation events from the Android system and
 * routes captured commands directly into the existing trusted local command engine.
 */
class DooraVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "DooraVoiceSession"
        private const val NATIVE_TAG = "DooraGoNative"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        DooraVoiceController.requestMicOwnership(DooraVoiceController.MicOwner.VOICE_INTERACTION_SESSION)
        Log.i(NATIVE_TAG, "[VOICE_ENTRY] source=VoiceInteractionService showFlags=$showFlags")
        Log.i(TAG, "DooraVoiceInteractionSession shown.")
    }

    override fun onHide() {
        super.onHide()
        DooraVoiceController.releaseMicOwnership(DooraVoiceController.MicOwner.VOICE_INTERACTION_SESSION)
        Log.i(TAG, "DooraVoiceInteractionSession hidden.")
    }
}
