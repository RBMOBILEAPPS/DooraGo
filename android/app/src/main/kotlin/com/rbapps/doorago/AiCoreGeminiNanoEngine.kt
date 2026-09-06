package com.rbapps.doorago

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Production-grade On-Device Gemini Nano / AICore Engine for DooraGo.
 *
 * Tier 2 natural language command understanding layer powered by Google's system-level
 * AICore engine on supported Android 14+ devices.
 *
 * PRIVACY & SAFETY PRINCIPLES:
 * 1. 100% On-Device: Zero cloud network requests, zero external Gemini API keys.
 * 2. Non-Executing Parser: Interprets natural language commands into validated JSON intent representations.
 *    Does NOT execute system actions directly.
 * 3. Graceful Fallback: If AICore is unavailable, preparing, or encounters an exception,
 *    fails gracefully and returns failure so the pipeline seamlessly routes to Tier 3 (FunctionGemma LiteRT-LM).
 * 4. Bounded Execution: Wrapped in strict 5.0-second timeout to prevent UI lag.
 */
class AiCoreGeminiNanoEngine(private val context: Context) {

    companion object {
        private const val TAG = "AiCoreGeminiNanoEngine"
        private const val AICORE_TAG = "AICORE"
        private const val TIMEOUT_MS = 5000L

        private const val SYSTEM_PROMPT = """
You are a fast, local voice command intent parser for an Android phone app named DooraGo.
Analyze the user command and extract the intent. Format output strictly as a single JSON object.

Supported "action" values:
- "OPEN_APP": requires "app_name" (e.g. {"action": "OPEN_APP", "app_name": "YouTube"})
- "TOGGLE_FLASHLIGHT": requires "state" ("ON" or "OFF") (e.g. {"action": "TOGGLE_FLASHLIGHT", "state": "ON"})
- "CALL_CONTACT": requires "contact_name" (e.g. {"action": "CALL_CONTACT", "contact_name": "Sunil"})
- "CALL_NUMBER": requires "phone_number" (e.g. {"action": "CALL_NUMBER", "phone_number": "9876543210"})
- "SEND_SMS": requires "contact_name" or "phone_number" and optional "message"
- "SEND_WHATSAPP": requires "contact_name" or "phone_number" and optional "message"
- "SHOW_MAP": requires "query" or "location"
- "UNKNOWN": if command cannot be parsed into a known action.

Do NOT include extra prose, markdown fences, or explanations. Return ONLY the raw JSON string.
"""
    }

    enum class AvailabilityState {
        AVAILABLE,
        PREPARING,
        UNAVAILABLE,
        UNSUPPORTED
    }

    /**
     * Checks current status of system AICore / Gemini Nano on this device.
     */
    suspend fun checkAvailability(): AvailabilityState = withContext(Dispatchers.IO) {
        try {
            // Check Android version requirement (Android 14+ / API 34+)
            if (android.os.Build.VERSION.SDK_INT < 34) {
                Log.d(TAG, "[$AICORE_TAG] availability=UNSUPPORTED (API ${android.os.Build.VERSION.SDK_INT} < 34)")
                return@withContext AvailabilityState.UNSUPPORTED
            }

            // Attempt reflection/direct check for official com.google.ai.edge.aicore classes
            val aicoreClass = try {
                Class.forName("com.google.ai.edge.aicore.GenerativeModel")
            } catch (e: ClassNotFoundException) {
                null
            }

            if (aicoreClass == null) {
                Log.d(TAG, "[$AICORE_TAG] availability=UNAVAILABLE (AICore SDK classes not found)")
                return@withContext AvailabilityState.UNAVAILABLE
            }

            Log.d(TAG, "[$AICORE_TAG] availability=AVAILABLE")
            AvailabilityState.AVAILABLE
        } catch (e: Throwable) {
            Log.w(TAG, "[$AICORE_TAG] availability check failed: ${e.message}")
            AvailabilityState.UNAVAILABLE
        }
    }

    /**
     * Parses unresolved natural language command using Gemini Nano via AICore.
     * Returns a Result containing the raw validated JSON string intent.
     */
    suspend fun understandCommand(text: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanInput = text.trim()
        if (cleanInput.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Empty input command"))
        }

        val status = checkAvailability()
        if (status != AvailabilityState.AVAILABLE) {
            Log.d(TAG, "[$AICORE_TAG] Routing skipped; status=$status. Fallback to FunctionGemma Tier 3.")
            return@withContext Result.failure(IllegalStateException("AICore unavailable (status=$status)"))
        }

        Log.d(TAG, "[$AICORE_TAG] routing unresolved command to on-device Gemini Nano model")

        val resultText = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                // Instantiation and execution of AICore GenerativeModel
                val modelClass = Class.forName("com.google.ai.edge.aicore.GenerativeModel")
                val instance = modelClass.getDeclaredConstructor().newInstance()

                val prompt = "$SYSTEM_PROMPT\nUser Command: \"$cleanInput\""
                val generateMethod = modelClass.getMethod("generateContent", String::class.java)

                val responseObj = generateMethod.invoke(instance, prompt)
                responseObj?.toString() ?: ""
            } catch (e: Throwable) {
                Log.w(TAG, "[$AICORE_TAG] AICore inference exception: ${e.message}")
                null
            }
        }

        if (resultText.isNullOrBlank()) {
            Log.d(TAG, "[$AICORE_TAG] inference returned null/timeout. Fallback to FunctionGemma.")
            return@withContext Result.failure(IllegalStateException("AICore inference timed out or returned empty"))
        }

        // Clean potential markdown quotes
        val cleanedJson = resultText
            .replace("```json", "")
            .replace("```", "")
            .trim()

        try {
            // Basic JSON structure validation
            val jsonObj = JSONObject(cleanedJson)
            val action = jsonObj.optString("action", "UNKNOWN")

            if (action == "UNKNOWN") {
                Log.d(TAG, "[$AICORE_TAG] Gemini Nano parsed intent as UNKNOWN")
                return@withContext Result.failure(IllegalStateException("Parsed intent is UNKNOWN"))
            }

            Log.d(TAG, "[$AICORE_TAG] structured intent validated action=$action")
            Result.success(cleanedJson)
        } catch (e: Exception) {
            Log.w(TAG, "[$AICORE_TAG] JSON validation failed for output: $cleanedJson")
            Result.failure(IllegalArgumentException("Malformed JSON from AICore output", e))
        }
    }
}
