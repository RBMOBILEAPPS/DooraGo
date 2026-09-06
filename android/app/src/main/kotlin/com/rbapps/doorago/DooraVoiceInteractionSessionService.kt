package com.rbapps.doorago

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Service managing Android Assistant voice interaction sessions for DooraGo.
 */
class DooraVoiceInteractionSessionService : VoiceInteractionSessionService() {

    companion object {
        private const val TAG = "DooraSessionService"
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "Creating new DooraVoiceInteractionSession.")
        return DooraVoiceInteractionSession(this)
    }
}
