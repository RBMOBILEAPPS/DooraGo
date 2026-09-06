package com.rbapps.doorago

import android.app.Activity
import android.content.Context
import android.util.Log
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.regex.Pattern

/**
 * Represents a pending action across multi-turn user conversations
 * when required parameters are missing from the initial command.
 */
sealed class PendingAction {
    data class SendEmail(
        val to: String,
        val subject: String? = null,
        val body: String = "",
        val waitingFor: String = "subject"
    ) : PendingAction()

    data class CreateContact(
        val firstName: String,
        val lastName: String = "",
        val phoneNumber: String? = null,
        val email: String? = null,
        val waitingFor: String = "phone_number"
    ) : PendingAction()

    data class ShowMap(
        val query: String? = null,
        val waitingFor: String = "location"
    ) : PendingAction()

    data class CreateCalendarEvent(
        val title: String = "Calendar event",
        val datetime: String? = null,
        val waitingFor: String = "datetime"
    ) : PendingAction()

    data class SetAlarm(
        val title: String = "Alarm",
        val waitingFor: String = "time"
    ) : PendingAction()

    data class SetTimer(
        val title: String = "Timer",
        val waitingFor: String = "duration"
    ) : PendingAction()

    data class DisambiguateApp(
        val originalQuery: String,
        val candidates: List<InstalledAppResolver.InstalledAppRecord>,
        val timestamp: Long = System.currentTimeMillis()
    ) : PendingAction()

    data class DisambiguateContact(
        val originalQuery: String,
        val candidates: List<LocalDeviceActionExecutor.ContactRecord>,
        val actionType: ContactActionType = ContactActionType.CALL,
        val targetApp: MessagingApp = MessagingApp.GENERIC,
        val messageBody: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : PendingAction()

    data class SendMessage(
        val targetApp: MessagingApp,
        val contactName: String? = null,
        val phoneNumber: String? = null,
        val messageBody: String? = null,
        val waitingFor: String = "message", // "contact", "message", "disambiguation"
        val disambiguationCandidates: List<LocalDeviceActionExecutor.ContactRecord> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    ) : PendingAction()
}

enum class ContactActionType {
    CALL,
    MESSAGING
}

enum class MessagingApp {
    WHATSAPP,
    SMS,
    GENERIC
}

/**
 * Result of local parameter normalization and command resolution.
 */
sealed class NormalizationResult {
    data class ExecuteDirect(
        val category: ToolCategory,
        val toolName: String,
        val params: Map<String, Any?>,
        val executionBlock: () -> String
    ) : NormalizationResult()

    data class AskFollowUp(
        val promptToUser: String,
        val pendingAction: PendingAction
    ) : NormalizationResult()

    data class RouteToLlm(
        val category: ToolCategory,
        val normalizedPrompt: String
    ) : NormalizationResult()
}

enum class PhoneNumberType {
    SHORT_SERVICE,   // e.g. 100, 101, 102, 108, 112, 121, 139, 198
    INDIAN_MOBILE,   // 10-digit mobile starting with 6-9 or +91
    STANDARD_PHONE   // 7-15 digits standard / landline / international
}

/**
 * Offline, deterministic parameter extraction and command router for DooraGo.
 *
 * Architecture Priority:
 * PRIORITY 1: Direct Phone Call (ACTION_CALL) / Dialer Control (ACTION_DIAL)
 * PRIORITY 2: Simple deterministic device controls (e.g. Flashlight)
 * PRIORITY 3: Device telemetry & read-only info (Battery, Storage, OS version)
 * PRIORITY 4: Settings & Connectivity shortcuts (Wi-Fi, Bluetooth, Airplane mode, etc.)
 * PRIORITY 5: Generic installed app launcher (YouTube, WhatsApp, Chrome, Camera, etc. via PackageManager)
 * PRIORITY 6: Local deterministic utilities (Alarms, Timers, Files, SMS, Contacts)
 * PRIORITY 7: FunctionGemma official 7 MobileActions (Deterministic parameter matches)
 * PRIORITY 8: FunctionGemma on-device LLM fallback for ambiguous phrasing
 */
class MobileActionNormalizer(
    private val context: Context,
    private val toolRegistry: MobileActionsToolRegistry,
    private val activityProvider: (() -> Activity?)? = null
) {
    companion object {
        private const val TAG = "MobileActionNormalizer"
        private const val NATIVE_TAG = "DooraGoNative"

        private val EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        private val PHONE_PATTERN = Pattern.compile("(?:\\+?\\d{1,3}[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}|\\b\\d{10,12}\\b")
    }

    private val localExecutor = LocalDeviceActionExecutor(context, activityProvider)

    @Volatile
    private var activePendingAction: PendingAction? = null

    fun getPendingAction(): PendingAction? = activePendingAction
    fun clearPendingAction() {
        activePendingAction = null
    }

    /**
     * Analyzes and routes user prompt locally before FunctionGemma.
     */
    fun process(rawPrompt: String): NormalizationResult {
        val prompt = preprocessInput(rawPrompt)
        val lower = normalizeForIntentMatching(prompt)

        Log.i(NATIVE_TAG, "[VOICE_TRANSCRIPT] text='$rawPrompt'")
        Log.i(NATIVE_TAG, "[COMMAND_NORMALIZED] text='$prompt'")
        Log.i(NATIVE_TAG, "[COMMAND_INPUT] text='$prompt'")

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 1: Pending Action Resolution (Multi-turn state)
        // ──────────────────────────────────────────────────────────────
        val pending = activePendingAction
        if (pending != null) {
            if (isCancellation(lower)) {
                Log.i(NATIVE_TAG, "[PENDING_ACTION] Cancelled active pending action by user: $lower")
                activePendingAction = null
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "cancel_action",
                    params = emptyMap()
                ) {
                    "Cancelled."
                }
            }

            val resolved = resolvePendingAction(pending, prompt, lower)
            if (resolved != null) {
                if (resolved is NormalizationResult.AskFollowUp) {
                    activePendingAction = resolved.pendingAction
                    Log.i(NATIVE_TAG, "[STATE_TRANSITION] Pending state updated: ${resolved.pendingAction.javaClass.simpleName}")
                } else {
                    activePendingAction = null
                    Log.i(NATIVE_TAG, "[STATE_TRANSITION] Pending action completed or cleared.")
                }
                return resolved
            } else if (isClearlyNewCommand(lower)) {
                Log.i(NATIVE_TAG, "[PENDING_ACTION] Discarded prior pending action due to new command: $lower")
                activePendingAction = null
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 2: Explicit SAVE_CONTACT (English, Hindi, Hinglish, Mixed)
        // Checked before call intent so "8094857263 save karo" routes to save, not call!
        // ──────────────────────────────────────────────────────────────
        if (isSaveContactIntent(lower)) {
            val (extractedName, extractedPhone) = extractSaveContactEntities(prompt, lower)
            val maskedPhone = extractedPhone?.let { maskPhoneNumber(it) }
            Log.i(NATIVE_TAG, "[SAVE_CONTACT_DETECT] name='$extractedName' phone='$maskedPhone'")

            // Case 1: Both Phone and Name are present -> Execute immediately without asking!
            if (!extractedPhone.isNullOrBlank() && isValidPhoneNumber(extractedPhone) && !extractedName.isNullOrBlank()) {
                val cleanPhone = normalizePhoneNumber(extractedPhone)
                activePendingAction = null
                Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=SAVE_CONTACT category=CONTACT name='$extractedName' phone='$maskedPhone'")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.CONTACT,
                    toolName = "save_contact",
                    params = mapOf("name" to extractedName, "phoneNumber" to cleanPhone)
                ) {
                    localExecutor.saveContact(extractedName, cleanPhone)
                }
            }
            // Case 2: Phone is present, but Name is missing -> Ask for Name!
            else if (!extractedPhone.isNullOrBlank() && isValidPhoneNumber(extractedPhone)) {
                val cleanPhone = normalizePhoneNumber(extractedPhone)
                val pendingAction = PendingAction.CreateContact(
                    firstName = "",
                    lastName = "",
                    phoneNumber = cleanPhone,
                    waitingFor = "name"
                )
                activePendingAction = pendingAction
                Log.i(NATIVE_TAG, "[PENDING_ACTION] save_contact phone='$maskedPhone' waiting_for=name")
                return NormalizationResult.AskFollowUp(
                    promptToUser = "Kis naam se save karna hai?",
                    pendingAction = pendingAction
                )
            }
            // Case 3: Name is present, but Phone is missing -> Ask for Phone!
            else if (!extractedName.isNullOrBlank()) {
                val pendingAction = PendingAction.CreateContact(
                    firstName = extractedName,
                    lastName = "",
                    phoneNumber = null,
                    waitingFor = "phone_number"
                )
                activePendingAction = pendingAction
                Log.i(NATIVE_TAG, "[PENDING_ACTION] save_contact name='$extractedName' waiting_for=phone_number")
                return NormalizationResult.AskFollowUp(
                    promptToUser = "What phone number should I save for $extractedName?",
                    pendingAction = pendingAction
                )
            }
            // Case 4: Neither Name nor Phone is present -> Ask for Phone first!
            else {
                val pendingAction = PendingAction.CreateContact(
                    firstName = "",
                    lastName = "",
                    phoneNumber = null,
                    waitingFor = "phone_number"
                )
                activePendingAction = pendingAction
                Log.i(NATIVE_TAG, "[PENDING_ACTION] save_contact waiting_for=phone_number")
                return NormalizationResult.AskFollowUp(
                    promptToUser = "Kaunsa number save karna hai?",
                    pendingAction = pendingAction
                )
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 3 & 4: Explicit Phone Call (CALL_CONTACT & CALL_NUMBER)
        // ──────────────────────────────────────────────────────────────
        val wordsConverted = wordsToDigits(prompt)
        val rawCandidate = extractPhoneNumberCandidate(wordsConverted)
        val normalizedCandidate = if (rawCandidate != null) normalizePhoneNumber(rawCandidate) else null
        val isCallIntent = isDirectCallIntent(lower)

        if (isCallIntent && normalizedCandidate != null && isValidPhoneNumber(normalizedCandidate)) {
            val masked = maskPhoneNumber(normalizedCandidate)
            val digitsCount = normalizedCandidate.replace(Regex("[^0-9]"), "").length
            val numberType = classifyPhoneNumber(normalizedCandidate)
            val typeLabel = numberType?.name ?: "UNKNOWN"

            Log.i(NATIVE_TAG, "[CALL_DETECT] intent=CALL_NUMERIC numberType=$typeLabel number=$masked")
            Log.i(NATIVE_TAG, "[CALL_NUMBER] rawInput='$prompt'")
            Log.i(NATIVE_TAG, "[CALL_NUMBER] candidate='$rawCandidate'")
            Log.i(NATIVE_TAG, "[CALL_NUMBER] numberType=$typeLabel")
            Log.i(NATIVE_TAG, "[CALL_NUMBER] valid=true")
            Log.i(NATIVE_TAG, "[CALL_NUMBER] action=ACTION_CALL")
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=DIRECT_CALL category=PHONE")

            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.GENERAL,
                toolName = "direct_call_number",
                params = mapOf("phoneNumber" to normalizedCandidate)
            ) {
                localExecutor.directCall(normalizedCandidate)
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 5: Dialer Control
        // ──────────────────────────────────────────────────────────────
        if (isDialerOpenCommand(lower)) {
            Log.i(NATIVE_TAG, "[CALL_DETECT] intent=OPEN_DIALER")
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=OPEN_DIALER category=PHONE")
            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.GENERAL,
                toolName = "open_dialer",
                params = emptyMap()
            ) {
                localExecutor.openPhone()
            }
        }

        // Contact-name calling
        if (isCallIntent && (normalizedCandidate == null || !isValidPhoneNumber(normalizedCandidate))) {
            val contactName = extractContactNameForCall(prompt)
            if (contactName.isNotBlank() && !contactName.equals("phone", true) && !contactName.equals("dialer", true)) {
                Log.i(NATIVE_TAG, "[CALL_DETECT] intent=CALL_CONTACT contactQuery='$contactName'")
                val contactRes = localExecutor.resolveContactCall(contactName)
                return when (contactRes) {
                    is LocalDeviceActionExecutor.ContactCallResolution.DirectCall -> {
                        Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=CONTACT_CALL category=PHONE")
                        activePendingAction = null
                        NormalizationResult.ExecuteDirect(
                            category = ToolCategory.GENERAL,
                            toolName = "call_contact_by_name",
                            params = mapOf("contactName" to contactRes.contact.displayName)
                        ) {
                            contactRes.action()
                        }
                    }
                    is LocalDeviceActionExecutor.ContactCallResolution.DisambiguationNeeded -> {
                        Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=CONTACT_DISAMBIGUATION category=PHONE")
                        val pendingAction = PendingAction.DisambiguateContact(
                            originalQuery = contactName,
                            candidates = contactRes.candidates,
                            actionType = ContactActionType.CALL
                        )
                        activePendingAction = pendingAction
                        NormalizationResult.AskFollowUp(
                            promptToUser = contactRes.prompt,
                            pendingAction = pendingAction
                        )
                    }
                    is LocalDeviceActionExecutor.ContactCallResolution.ErrorOrNotice -> {
                        NormalizationResult.ExecuteDirect(
                            category = ToolCategory.GENERAL,
                            toolName = "call_contact_notice",
                            params = mapOf("query" to contactName)
                        ) {
                            contactRes.message
                        }
                    }
                }
            } else if (rawCandidate != null && !isValidPhoneNumber(normalizedCandidate ?: "")) {
                Log.w(NATIVE_TAG, "[CALL_VALIDATE] valid=false candidate='$rawCandidate'")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "invalid_phone_number",
                    params = emptyMap()
                ) {
                    "Please provide a valid phone number."
                }
            } else {
                Log.i(NATIVE_TAG, "[CALL_DETECT] intent=CALL_NO_TARGET")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "prompt_for_call_target",
                    params = emptyMap()
                ) {
                    "Please tell me the contact name or number you want to call."
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 6: Local Contact Lookup (DOES NOT CALL, DOES NOT SEND TO LLM)
        // ──────────────────────────────────────────────────────────────
        if (isContactLookupCommand(lower)) {
            val contactName = extractContactNameForLookup(prompt)
            if (contactName.isNotBlank()) {
                Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=CONTACT_LOOKUP category=CONTACTS")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "lookup_contact_details",
                    params = mapOf("contactName" to contactName)
                ) {
                    localExecutor.lookupContactDetails(contactName)
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 7: Smart Messaging (WhatsApp, SMS, Generic Message)
        // ──────────────────────────────────────────────────────────────
        if (isMessagingCommand(lower)) {
            val intentData = extractMessagingIntent(prompt, lower)
            Log.i(NATIVE_TAG, "[MESSAGING_DETECT] app=${intentData.app} contact='${intentData.contactName}' hasPhone=${intentData.phoneNumber != null} hasMsg=${!intentData.messageBody.isNullOrBlank()}")

            // Check if user specified a direct phone number instead of a contact name
            val targetPhone = if (intentData.phoneNumber != null && isValidPhoneNumber(intentData.phoneNumber)) {
                normalizePhoneNumber(intentData.phoneNumber)
            } else null

            // Case A: Direct phone number + message body already present -> Execute directly!
            if (targetPhone != null && !intentData.messageBody.isNullOrBlank()) {
                activePendingAction = null
                Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=DIRECT_MESSAGE app=${intentData.app}")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "send_message",
                    params = mapOf("app" to intentData.app.name, "phone" to targetPhone)
                ) {
                    when (intentData.app) {
                        MessagingApp.WHATSAPP -> localExecutor.openWhatsAppMessage(targetPhone, intentData.messageBody)
                        MessagingApp.SMS -> localExecutor.openSmsComposer(targetPhone, intentData.messageBody)
                        MessagingApp.GENERIC -> localExecutor.openGenericMessageComposer(targetPhone, intentData.messageBody)
                    }
                }
            }

            // Case B: Direct phone number present, but message is missing -> Ask for message!
            if (targetPhone != null && intentData.messageBody.isNullOrBlank()) {
                val pending = PendingAction.SendMessage(
                    targetApp = intentData.app,
                    contactName = targetPhone,
                    phoneNumber = targetPhone,
                    messageBody = null,
                    waitingFor = "message"
                )
                activePendingAction = pending
                val appName = if (intentData.app == MessagingApp.WHATSAPP) "WhatsApp" else "SMS"
                return NormalizationResult.AskFollowUp("Kya $appName message bhejna hai?", pending)
            }

            // Case C: Contact Name is present -> Resolve contact!
            if (!intentData.contactName.isNullOrBlank()) {
                val actionVerb = when (intentData.app) {
                    MessagingApp.WHATSAPP -> "whatsapp"
                    MessagingApp.SMS -> "sms"
                    MessagingApp.GENERIC -> "message"
                }
                val resolution = localExecutor.resolveContactForMessaging(intentData.contactName, actionVerb)
                when (resolution) {
                    is LocalDeviceActionExecutor.ContactMessageResolution.Resolved -> {
                        val contact = resolution.contact
                        val phone = resolution.phoneNumber
                        if (!intentData.messageBody.isNullOrBlank()) {
                            // Both contact and message ready -> Execute!
                            activePendingAction = null
                            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=CONTACT_MESSAGE app=${intentData.app} contact='${contact.displayName}'")
                            return NormalizationResult.ExecuteDirect(
                                category = ToolCategory.GENERAL,
                                toolName = "send_message",
                                params = mapOf("app" to intentData.app.name, "contactName" to contact.displayName)
                            ) {
                                when (intentData.app) {
                                    MessagingApp.WHATSAPP -> localExecutor.openWhatsAppMessage(phone, intentData.messageBody)
                                    MessagingApp.SMS -> localExecutor.openSmsComposer(phone, intentData.messageBody)
                                    MessagingApp.GENERIC -> localExecutor.openGenericMessageComposer(phone, intentData.messageBody)
                                }
                            }
                        } else {
                            // Contact resolved, but message missing -> Ask for message!
                            val pending = PendingAction.SendMessage(
                                targetApp = intentData.app,
                                contactName = contact.displayName,
                                phoneNumber = phone,
                                messageBody = null,
                                waitingFor = "message"
                            )
                            activePendingAction = pending
                            Log.i(NATIVE_TAG, "[PENDING_ACTION] message_waiting_for_body contact='${contact.displayName}'")
                            val appLabel = if (intentData.app == MessagingApp.WHATSAPP) "WhatsApp" else "SMS"
                            return NormalizationResult.AskFollowUp("${contact.displayName} ko kya $appLabel message bhejna hai?", pending)
                        }
                    }
                    is LocalDeviceActionExecutor.ContactMessageResolution.DisambiguationNeeded -> {
                        val pending = PendingAction.DisambiguateContact(
                            originalQuery = resolution.originalQuery,
                            candidates = resolution.candidates,
                            actionType = ContactActionType.MESSAGING,
                            targetApp = intentData.app,
                            messageBody = intentData.messageBody
                        )
                        activePendingAction = pending
                        Log.i(NATIVE_TAG, "[PENDING_ACTION] message_disambiguation_needed query='${resolution.originalQuery}'")
                        return NormalizationResult.AskFollowUp(resolution.prompt, pending)
                    }
                    is LocalDeviceActionExecutor.ContactMessageResolution.ErrorOrNotice -> {
                        return NormalizationResult.ExecuteDirect(
                            category = ToolCategory.GENERAL,
                            toolName = "message_contact_notice",
                            params = emptyMap()
                        ) {
                            resolution.message
                        }
                    }
                }
            }

            // Case D: Neither contact nor phone was extracted (e.g. "WhatsApp pe message bhejo", "SMS karo")
            val pending = PendingAction.SendMessage(
                targetApp = intentData.app,
                contactName = null,
                phoneNumber = null,
                messageBody = intentData.messageBody,
                waitingFor = "contact"
            )
            activePendingAction = pending
            Log.i(NATIVE_TAG, "[PENDING_ACTION] message_waiting_for_contact app=${intentData.app}")
            val appLabel = if (intentData.app == MessagingApp.WHATSAPP) "WhatsApp" else "SMS"
            return NormalizationResult.AskFollowUp("Kisko $appLabel message bhejna hai?", pending)
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 8: Generic Installed-App Launcher (BEFORE LLM / OTHER TOOLS)
        // ──────────────────────────────────────────────────────────────
        val appNameToOpen = extractAppNameToOpen(prompt, lower)
        if (appNameToOpen != null) {
            Log.i(NATIVE_TAG, "[WAKE_TRACE] normalization intent=OPEN_APP")
            Log.i(NATIVE_TAG, "[WAKE_TRACE] app_candidate='$appNameToOpen'")
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] type=APP_LAUNCH app='$appNameToOpen'")
            val resolution = localExecutor.appResolver.resolveApp(appNameToOpen)
            when (resolution) {
                is InstalledAppResolver.AppLaunchResolution.Launch -> {
                    Log.i(NATIVE_TAG, "[WAKE_TRACE] app_resolution_result=SUCCESS matched='${resolution.app.appLabel}'")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.GENERAL,
                        toolName = "open_app",
                        params = mapOf("appName" to resolution.app.appLabel)
                    ) {
                        val launchMsg = resolution.action()
                        Log.i(NATIVE_TAG, "[WAKE_TRACE] execution_result=SUCCESS package='${resolution.app.packageName}'")
                        launchMsg
                    }
                }
                is InstalledAppResolver.AppLaunchResolution.DisambiguationNeeded -> {
                    Log.i(NATIVE_TAG, "[WAKE_TRACE] app_resolution_result=DISAMBIGUATION_NEEDED query='$appNameToOpen'")
                    val pending = PendingAction.DisambiguateApp(
                        originalQuery = resolution.originalQuery,
                        candidates = resolution.candidates
                    )
                    activePendingAction = pending
                    return NormalizationResult.AskFollowUp(resolution.prompt, pending)
                }
                is InstalledAppResolver.AppLaunchResolution.ErrorOrNotFound -> {
                    Log.i(NATIVE_TAG, "[WAKE_TRACE] app_resolution_result=NOT_FOUND query='$appNameToOpen'")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.GENERAL,
                        toolName = "open_app_not_found",
                        params = mapOf("appName" to appNameToOpen)
                    ) {
                        resolution.message
                    }
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 8: Flashlight (turn_on_flashlight, turn_off_flashlight)
        // ──────────────────────────────────────────────────────────────
        if (isFlashlightCommand(lower)) {
            val isOff = lower.contains("off") || lower.contains("disable") || lower.contains("stop") ||
                    lower.contains("band") || lower.contains("bujhao") || lower.contains("bujha") ||
                    lower.contains("बंद") || lower.contains("बुझाओ")
            val toolName = if (isOff) "turn_off_flashlight" else "turn_on_flashlight"
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=$toolName category=FLASHLIGHT")
            Log.i(NATIVE_TAG, "[PARAM_EXTRACT] function=$toolName params={}")
            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.FLASHLIGHT,
                toolName = toolName,
                params = emptyMap()
            ) {
                if (isOff) toolRegistry.flashlightToolSet.turn_off_flashlight()
                else toolRegistry.flashlightToolSet.turn_on_flashlight()
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 9: Device Telemetry & Device Controls (Volume / Brightness / Battery / Storage)
        // ──────────────────────────────────────────────────────────────
        if (isVolumeCommand(lower)) {
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=ADJUST_VOLUME category=DEVICE_CONTROL")
            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.GENERAL,
                toolName = "adjust_volume",
                params = emptyMap()
            ) {
                localExecutor.openSettings("sound")
            }
        }

        if (isBrightnessCommand(lower)) {
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=ADJUST_BRIGHTNESS category=DEVICE_CONTROL")
            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.GENERAL,
                toolName = "adjust_brightness",
                params = emptyMap()
            ) {
                localExecutor.openSettings("display")
            }
        }

        if (isDeviceInfoCommand(lower)) {
            val (infoType, actionLabel) = when {
                lower.contains("battery") || lower.contains("charge") || lower.contains("charging") || lower.contains("बैटरी") ->
                    Pair("battery", "GET_BATTERY_STATUS")
                lower.contains("android") || lower.contains("os") || lower.contains("version") || lower.contains("वर्जन") ->
                    Pair("version", "GET_ANDROID_VERSION")
                lower.contains("storage") || lower.contains("space") || lower.contains("memory") || lower.contains("स्टोरेज") ->
                    Pair("storage", "GET_STORAGE_INFO")
                else -> Pair("device", "GET_DEVICE_INFO")
            }
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=$actionLabel category=DEVICE_INFO")
            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.GENERAL,
                toolName = actionLabel,
                params = mapOf("infoType" to infoType)
            ) {
                when (infoType) {
                    "battery" -> localExecutor.getBatteryStatus()
                    "version" -> localExecutor.getAndroidVersion()
                    "storage" -> localExecutor.getStorageInfo()
                    else -> localExecutor.getDeviceInfo()
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 10: Settings & Connectivity Shortcuts
        // ──────────────────────────────────────────────────────────────
        val settingsType = matchSettingsType(lower)
        if (settingsType != null) {
            val routeName = "OPEN_${settingsType.uppercase(Locale.ROOT)}_SETTINGS"
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=$routeName category=SETTINGS")
            return NormalizationResult.ExecuteDirect(
                category = if (settingsType == "wifi") ToolCategory.WIFI else ToolCategory.GENERAL,
                toolName = routeName,
                params = mapOf("settingsType" to settingsType)
            ) {
                localExecutor.openSettings(settingsType)
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 11: Local Utilities (Alarms, Timers, Files, Camera, SMS, Phone)
        // ──────────────────────────────────────────────────────────────

        // Alarms
        if (isAlarmCommand(lower)) {
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=SET_ALARM category=ALARM_TIMER")
            val (hour, minute) = extractAlarmTime(lower)
            if (hour != null) {
                val min = minute ?: 0
                val title = extractAlarmTitle(prompt)
                Log.i(NATIVE_TAG, "[PARAM_EXTRACT] hour=$hour minute=$min title=$title")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "set_alarm",
                    params = mapOf("hour" to hour, "minute" to min, "title" to title)
                ) {
                    localExecutor.setAlarm(hour, min, title)
                }
            } else if (lower.contains("open") || lower.contains("show") || lower.contains("kholo")) {
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "open_alarms",
                    params = emptyMap()
                ) {
                    localExecutor.openAlarms()
                }
            } else {
                val pendingAction = PendingAction.SetAlarm()
                activePendingAction = pendingAction
                return NormalizationResult.AskFollowUp(
                    promptToUser = "For what time should I set the alarm?",
                    pendingAction = pendingAction
                )
            }
        }

        // Timers
        if (isTimerCommand(lower)) {
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=SET_TIMER category=ALARM_TIMER")
            val seconds = extractTimerSeconds(lower)
            if (seconds != null && seconds > 0) {
                Log.i(NATIVE_TAG, "[PARAM_EXTRACT] seconds=$seconds")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "set_timer",
                    params = mapOf("seconds" to seconds)
                ) {
                    localExecutor.setTimer(seconds, "Timer")
                }
            } else if (lower.contains("open") || lower.contains("kholo")) {
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.GENERAL,
                    toolName = "open_timer",
                    params = emptyMap()
                ) {
                    localExecutor.openClock()
                }
            } else {
                val pendingAction = PendingAction.SetTimer()
                activePendingAction = pendingAction
                return NormalizationResult.AskFollowUp(
                    promptToUser = "For how long should I set the timer?",
                    pendingAction = pendingAction
                )
            }
        }

        // Camera & Gallery
        if (isCameraCommand(lower)) {
            val isGallery = lower.contains("gallery") || lower.contains("photos")
            val toolName = if (isGallery) "OPEN_GALLERY" else "OPEN_CAMERA"
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=$toolName category=CAMERA")
            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.GENERAL,
                toolName = toolName,
                params = emptyMap()
            ) {
                if (isGallery) localExecutor.openGallery()
                else localExecutor.openCamera()
            }
        }

        // Files & Downloads
        if (isFilesCommand(lower)) {
            val isDownloads = lower.contains("download")
            val toolName = if (isDownloads) "OPEN_DOWNLOADS" else "OPEN_FILES"
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=$toolName category=FILES")
            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.GENERAL,
                toolName = toolName,
                params = emptyMap()
            ) {
                if (isDownloads) localExecutor.openDownloads()
                else localExecutor.openFiles()
            }
        }

        // SMS
        if (isSmsCommand(lower)) {
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=OPEN_SMS category=SMS")
            val recipient = extractPhoneNumber(prompt) ?: extractSmsRecipient(prompt)
            val body = extractSmsBody(prompt)
            Log.i(NATIVE_TAG, "[PARAM_EXTRACT] recipient=$recipient body=$body")
            return NormalizationResult.ExecuteDirect(
                category = ToolCategory.GENERAL,
                toolName = "open_sms_composer",
                params = mapOf("recipient" to recipient, "body" to body)
            ) {
                localExecutor.openSmsComposer(recipient, body)
            }
        }

        // Contacts & Address Book
        if (lower.contains("contacts") || lower.contains("contact")) {
            if (!isCreateContactCommand(lower)) {
                val contactName = extractContactNameForCall(prompt)
                if (contactName.isNotBlank()) {
                    Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=SEARCH_CONTACTS category=CONTACTS")
                    Log.i(NATIVE_TAG, "[PARAM_EXTRACT] contactName=$contactName")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.GENERAL,
                        toolName = "search_contacts",
                        params = mapOf("contactName" to contactName)
                    ) {
                        localExecutor.searchContacts(contactName)
                    }
                } else {
                    Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=OPEN_CONTACTS category=CONTACTS")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.GENERAL,
                        toolName = "open_contacts",
                        params = emptyMap()
                    ) {
                        localExecutor.openContacts()
                    }
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 6: FunctionGemma Official MobileActions (Deterministic Resolution)
        // ──────────────────────────────────────────────────────────────

        // Email (send_email)
        if (isEmailCommand(lower)) {
            val emailMatcher = EMAIL_PATTERN.matcher(prompt)
            val recipient = if (emailMatcher.find()) emailMatcher.group() else null
            val subject = extractEmailSubject(prompt)
            val body = extractEmailBody(prompt)

            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=SEND_EMAIL category=EMAIL")

            if (recipient != null) {
                if (!subject.isNullOrBlank()) {
                    Log.i(NATIVE_TAG, "[PARAM_EXTRACT] to=$recipient subject=$subject body=$body")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.EMAIL,
                        toolName = "send_email",
                        params = mapOf("to" to recipient, "subject" to subject, "body" to body)
                    ) {
                        toolRegistry.emailToolSet.send_email(to = recipient, subject = subject, body = body)
                    }
                } else {
                    Log.i(NATIVE_TAG, "[PARAM_EXTRACT] to=$recipient subject=null")
                    Log.i(NATIVE_TAG, "[PENDING_ACTION] send_email waiting_for=subject")
                    val pendingAction = PendingAction.SendEmail(to = recipient, subject = null, body = body)
                    activePendingAction = pendingAction
                    return NormalizationResult.AskFollowUp(
                        promptToUser = "What subject should I use?",
                        pendingAction = pendingAction
                    )
                }
            } else {
                Log.i(NATIVE_TAG, "[PENDING_ACTION] send_email waiting_for=to")
                val pendingAction = PendingAction.SendEmail(to = "", subject = subject, body = body, waitingFor = "to")
                activePendingAction = pendingAction
                return NormalizationResult.AskFollowUp(
                    promptToUser = "Who would you like to send an email to?",
                    pendingAction = pendingAction
                )
            }
        }

        // Create Contact (create_contact) fallback
        if (isCreateContactCommand(lower)) {
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=CREATE_CONTACT category=CONTACT")
            val (firstName, phone) = extractSaveContactEntities(prompt, lower)

            if (!firstName.isNullOrBlank() && !phone.isNullOrBlank() && isValidPhoneNumber(phone)) {
                val cleanPhone = normalizePhoneNumber(phone)
                val masked = maskPhoneNumber(cleanPhone)
                Log.i(NATIVE_TAG, "[PARAM_EXTRACT] name=$firstName phone_number=$masked")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.CONTACT,
                    toolName = "save_contact",
                    params = mapOf("name" to firstName, "phoneNumber" to cleanPhone)
                ) {
                    localExecutor.saveContact(firstName, cleanPhone)
                }
            } else if (!phone.isNullOrBlank() && isValidPhoneNumber(phone)) {
                val cleanPhone = normalizePhoneNumber(phone)
                val pendingAction = PendingAction.CreateContact(
                    firstName = "",
                    lastName = "",
                    phoneNumber = cleanPhone,
                    waitingFor = "name"
                )
                activePendingAction = pendingAction
                return NormalizationResult.AskFollowUp(
                    promptToUser = "Kis naam se save karna hai?",
                    pendingAction = pendingAction
                )
            } else if (!firstName.isNullOrBlank()) {
                val pendingAction = PendingAction.CreateContact(
                    firstName = firstName,
                    lastName = "",
                    phoneNumber = null,
                    waitingFor = "phone_number"
                )
                activePendingAction = pendingAction
                return NormalizationResult.AskFollowUp(
                    promptToUser = "What phone number should I save for $firstName?",
                    pendingAction = pendingAction
                )
            } else {
                val pendingAction = PendingAction.CreateContact(
                    firstName = "",
                    lastName = "",
                    phoneNumber = null,
                    waitingFor = "phone_number"
                )
                activePendingAction = pendingAction
                return NormalizationResult.AskFollowUp(
                    promptToUser = "Kaunsa number save karna hai?",
                    pendingAction = pendingAction
                )
            }
        }

        // Map / Location (show_map)
        if (isMapCommand(lower)) {
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=SHOW_MAP category=MAP")
            val isDirections = lower.contains("directions") || lower.contains("navigate") || lower.contains("rasta")
            val query = extractMapQuery(prompt)

            if (!query.isNullOrBlank()) {
                Log.i(NATIVE_TAG, "[PARAM_EXTRACT] query=$query isDirections=$isDirections")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.MAP,
                    toolName = if (isDirections) "open_directions" else "show_map",
                    params = mapOf("query" to query)
                ) {
                    if (isDirections) localExecutor.openDirections(query)
                    else toolRegistry.mapToolSet.show_map(query = query)
                }
            } else {
                Log.i(NATIVE_TAG, "[PENDING_ACTION] show_map waiting_for=location")
                val pendingAction = PendingAction.ShowMap(query = null)
                activePendingAction = pendingAction
                return NormalizationResult.AskFollowUp(
                    promptToUser = "What location should I show?",
                    pendingAction = pendingAction
                )
            }
        }

        // Calendar (create_calendar_event)
        if (isCalendarCommand(lower)) {
            Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=CREATE_CALENDAR_EVENT category=CALENDAR")
            val datetime = parseRelativeDateTime(prompt)
            val title = extractCalendarTitle(prompt)

            if (datetime != null) {
                val finalTitle = if (title.isNotBlank()) title else "Calendar event"
                Log.i(NATIVE_TAG, "[PARAM_EXTRACT] title=$finalTitle datetime=$datetime")
                return NormalizationResult.ExecuteDirect(
                    category = ToolCategory.CALENDAR,
                    toolName = "create_calendar_event",
                    params = mapOf("title" to finalTitle, "datetime" to datetime)
                ) {
                    toolRegistry.calendarToolSet.create_calendar_event(title = finalTitle, datetime = datetime)
                }
            } else {
                Log.i(NATIVE_TAG, "[PENDING_ACTION] create_calendar_event waiting_for=datetime")
                val pendingAction = PendingAction.CreateCalendarEvent(
                    title = if (title.isNotBlank()) title else "Calendar event",
                    datetime = null
                )
                activePendingAction = pendingAction
                return NormalizationResult.AskFollowUp(
                    promptToUser = "When should the event be scheduled?",
                    pendingAction = pendingAction
                )
            }
        }

        // ──────────────────────────────────────────────────────────────
        // PRIORITY 7: Fallback to FunctionGemma LLM
        // ──────────────────────────────────────────────────────────────
        val routedCategory = toolRegistry.routeCategory(prompt)
        Log.i(NATIVE_TAG, "[COMMAND_ROUTE] command=FUNCTION_GEMMA category=${routedCategory.label}")
        return NormalizationResult.RouteToLlm(
            category = routedCategory,
            normalizedPrompt = prompt
        )
    }

    // ──────────────────────────────────────────────────────────────
    // App Command Extractor (English & Hinglish)
    // ──────────────────────────────────────────────────────────────

    private fun extractAppNameToOpen(prompt: String, lower: String): String? {
        val trimmed = prompt.trim()
        val cleanLower = lower.trim()

        // 1. Negation & conversational question guard (e.g. "Don't open YouTube", "My friend uses YouTube", "I like YouTube")
        if (localExecutor.appResolver.isNegatedOrConversational(cleanLower)) {
            return null
        }

        // 2. Informational / negative question exclusion (e.g. "Which app is YouTube?", "कौन सा ऐप यूट्यूब है?")
        if (cleanLower.startsWith("which app") || cleanLower.startsWith("what app") ||
            cleanLower.startsWith("who is") || cleanLower.startsWith("what is") ||
            cleanLower.startsWith("कौन सा ऐप") || cleanLower.startsWith("कौन सी ऐप") ||
            cleanLower.contains("kya hai") || cleanLower.contains("क्या है")
        ) {
            return null
        }

        // Common non-app keywords to exclude
        val nonAppWords = setOf(
            "flashlight", "torch", "light",
            "setting", "settings", "wifi", "wi-fi", "bluetooth",
            "battery", "display", "brightness", "storage",
            "airplane", "flight mode", "vpn", "hotspot",
            "alarm", "alarms", "timer", "countdown",
            "email", "mail", "contact", "contacts",
            "map", "maps", "location", "directions", "calendar", "event",
            "फ्लैशलाइट", "टॉर्च", "सेटिंग्स", "सेटिंग", "बैटरी", "ब्राइटनेस", "वॉल्यूम",
            "अलार्म", "टाइमर", "ईमेल", "संपर्क", "मैप", "कैलेंडर"
        )

        // 3. English patterns: "open [app]", "launch [app]", "start [app]"
        val englishPatterns = listOf(
            Pattern.compile("^(?:open|launch|start|run)\\s+(?:the\\s+app\\s+|the\\s+|app\\s+)?([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)(?:\\s+app|\\s+application)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:can\\s+you\\s+|please\\s+)?(?:open|launch|start)\\s+(?:the\\s+app\\s+|the\\s+|app\\s+)?([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)(?:\\s+app|\\s+application)?$", Pattern.CASE_INSENSITIVE)
        )
        for (pat in englishPatterns) {
            val m = pat.matcher(trimmed)
            if (m.find()) {
                val candidate = m.group(1)?.trim() ?: ""
                val candLower = candidate.lowercase(Locale.ROOT)
                if (candLower.isNotEmpty() && !nonAppWords.contains(candLower)) {
                    return candidate
                }
            }
        }

        // 4. Hinglish / Hindi patterns with genuine open command verbs
        val hinglishPatterns = listOf(
            Pattern.compile("^([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)\\s+(?:app\\s+|ऐप\\s+)?(?:kholo|khol\\s+do|khol\\s+de|kholna|kholiye|khol|open|open\\s+karo|open\\s+kar\\s+do|open\\s+kardo|open\\s+kar|chalu|chalu\\s+karo|chalu\\s+kar\\s+do|chalu\\s+kar|chalao|start|start\\s+karo|launch|launch\\s+karo|shuru\\s+karo|खोलो|खोल\\s+दो|खोल\\s+दे|खोलिए|खोल|चालू\\s+करो|चालू\\s+कर\\s+दो|चालू|चलाओ|शुरू\\s+करो|ओपन|ओपन\\s+करो)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:kholo|khol\\s+do|open\\s+karo|chalu\\s+karo|start\\s+karo|खोलो|खोल\\s+दो|चालू\\s+करो|ओपन\\s+करो)\\s+(?:app\\s+|ऐप\\s+)?([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+)$", Pattern.CASE_INSENSITIVE)
        )
        for (pat in hinglishPatterns) {
            val m = pat.matcher(trimmed)
            if (m.find()) {
                val candidate = m.group(1)?.trim() ?: ""
                val candLower = candidate.lowercase(Locale.ROOT)
                if (candLower.isNotEmpty() && !nonAppWords.contains(candLower)) {
                    return candidate
                }
            }
        }

        // 5. Dynamic App-Anchor Detection (e.g. "YouTube polo", "Upstox please", "Google Maps fast")
        val anchorMatch = localExecutor.appResolver.findAppAnchor(trimmed)
        if (anchorMatch != null && !nonAppWords.contains(anchorMatch.matchedSpan.lowercase(Locale.ROOT))) {
            return anchorMatch.app.appLabel
        }

        return null
    }

    // ──────────────────────────────────────────────────────────────
    // Pending Action Resolver (Multi-turn completion)
    // ──────────────────────────────────────────────────────────────

    private fun resolvePendingAction(pending: PendingAction, rawPrompt: String, lower: String): NormalizationResult? {
        when (pending) {
            is PendingAction.SendEmail -> {
                if (pending.waitingFor == "to") {
                    val emailMatcher = EMAIL_PATTERN.matcher(rawPrompt)
                    val to = if (emailMatcher.find()) emailMatcher.group() else rawPrompt.trim()
                    if (to.isNotBlank()) {
                        if (!pending.subject.isNullOrBlank()) {
                            Log.i(NATIVE_TAG, "[PARAM_EXTRACT] to=$to subject=${pending.subject}")
                            Log.i(NATIVE_TAG, "[TOOL_CALL] name=send_email")
                            return NormalizationResult.ExecuteDirect(
                                category = ToolCategory.EMAIL,
                                toolName = "send_email",
                                params = mapOf("to" to to, "subject" to pending.subject, "body" to pending.body)
                            ) {
                                toolRegistry.emailToolSet.send_email(to = to, subject = pending.subject, body = pending.body)
                            }
                        } else {
                            val nextPending = pending.copy(to = to, waitingFor = "subject")
                            Log.i(NATIVE_TAG, "[PENDING_ACTION] send_email waiting_for=subject")
                            return NormalizationResult.AskFollowUp("What subject should I use?", nextPending)
                        }
                    }
                } else if (pending.waitingFor == "subject") {
                    val subject = rawPrompt.trim()
                    Log.i(NATIVE_TAG, "[PARAM_EXTRACT] to=${pending.to} subject=$subject")
                    Log.i(NATIVE_TAG, "[TOOL_CALL] name=send_email")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.EMAIL,
                        toolName = "send_email",
                        params = mapOf("to" to pending.to, "subject" to subject, "body" to pending.body)
                    ) {
                        toolRegistry.emailToolSet.send_email(to = pending.to, subject = subject, body = pending.body)
                    }
                }
            }

            is PendingAction.CreateContact -> {
                if (pending.waitingFor == "name") {
                    val candidateName = cleanExtractedContactName(rawPrompt)
                    val phone = pending.phoneNumber ?: extractPhoneNumber(rawPrompt)
                    if (candidateName.isNotBlank()) {
                        val cleanPhone = if (phone != null) normalizePhoneNumber(phone) else null
                        val maskedPhone = cleanPhone?.let { maskPhoneNumber(it) }
                        Log.i(NATIVE_TAG, "[RESOLVE_PENDING_NAME] name='$candidateName' phone='$maskedPhone'")
                        Log.i(NATIVE_TAG, "[TOOL_CALL] name=save_contact")
                        return NormalizationResult.ExecuteDirect(
                            category = ToolCategory.CONTACT,
                            toolName = "save_contact",
                            params = mapOf("name" to candidateName, "phoneNumber" to cleanPhone)
                        ) {
                            localExecutor.saveContact(candidateName, cleanPhone)
                        }
                    }
                } else if (pending.waitingFor == "phone_number") {
                    val digitsConverted = wordsToDigits(rawPrompt)
                    val phoneCandidate = extractPhoneNumberCandidate(digitsConverted)
                    val phone = if (phoneCandidate != null) normalizePhoneNumber(phoneCandidate) else extractPhoneNumber(rawPrompt)
                    if (!phone.isNullOrBlank() && isValidPhoneNumber(phone)) {
                        val cleanPhone = normalizePhoneNumber(phone)
                        val maskedPhone = maskPhoneNumber(cleanPhone)
                        if (pending.firstName.isNotBlank()) {
                            val fullName = "${pending.firstName} ${pending.lastName}".trim()
                            Log.i(NATIVE_TAG, "[RESOLVE_PENDING_PHONE] name='$fullName' phone='$maskedPhone'")
                            Log.i(NATIVE_TAG, "[TOOL_CALL] name=save_contact")
                            return NormalizationResult.ExecuteDirect(
                                category = ToolCategory.CONTACT,
                                toolName = "save_contact",
                                params = mapOf("name" to fullName, "phoneNumber" to cleanPhone)
                            ) {
                                localExecutor.saveContact(fullName, cleanPhone)
                            }
                        } else {
                            val nextPending = pending.copy(phoneNumber = cleanPhone, waitingFor = "name")
                            Log.i(NATIVE_TAG, "[PENDING_ACTION] save_contact phone='$maskedPhone' waiting_for=name")
                            return NormalizationResult.AskFollowUp("Kis naam se save karna hai?", nextPending)
                        }
                    }
                }
            }

            is PendingAction.ShowMap -> {
                val query = rawPrompt.trim()
                if (query.isNotBlank()) {
                    Log.i(NATIVE_TAG, "[PARAM_EXTRACT] query=$query")
                    Log.i(NATIVE_TAG, "[TOOL_CALL] name=show_map")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.MAP,
                        toolName = "show_map",
                        params = mapOf("query" to query)
                    ) {
                        toolRegistry.mapToolSet.show_map(query = query)
                    }
                }
            }

            is PendingAction.CreateCalendarEvent -> {
                val dt = parseRelativeDateTime(rawPrompt)
                if (dt != null) {
                    Log.i(NATIVE_TAG, "[PARAM_EXTRACT] title=${pending.title} datetime=$dt")
                    Log.i(NATIVE_TAG, "[TOOL_CALL] name=create_calendar_event")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.CALENDAR,
                        toolName = "create_calendar_event",
                        params = mapOf("title" to pending.title, "datetime" to dt)
                    ) {
                        toolRegistry.calendarToolSet.create_calendar_event(title = pending.title, datetime = dt)
                    }
                }
            }

            is PendingAction.SetAlarm -> {
                val (hour, min) = extractAlarmTime(lower)
                if (hour != null) {
                    val m = min ?: 0
                    Log.i(NATIVE_TAG, "[PARAM_EXTRACT] hour=$hour minute=$m")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.GENERAL,
                        toolName = "set_alarm",
                        params = mapOf("hour" to hour, "minute" to m)
                    ) {
                        localExecutor.setAlarm(hour, m, pending.title)
                    }
                }
            }

            is PendingAction.SetTimer -> {
                val seconds = extractTimerSeconds(lower)
                if (seconds != null && seconds > 0) {
                    Log.i(NATIVE_TAG, "[PARAM_EXTRACT] seconds=$seconds")
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.GENERAL,
                        toolName = "set_timer",
                        params = mapOf("seconds" to seconds)
                    ) {
                        localExecutor.setTimer(seconds, pending.title)
                    }
                }
            }

            is PendingAction.DisambiguateApp -> {
                val chosen = pending.candidates.find {
                    it.appLabel.equals(rawPrompt.trim(), ignoreCase = true) ||
                    it.normalizedLabel.contains(lower.trim()) ||
                    it.compactLabel == lower.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
                } ?: pending.candidates.firstOrNull()

                if (chosen != null) {
                    return NormalizationResult.ExecuteDirect(
                        category = ToolCategory.GENERAL,
                        toolName = "open_app",
                        params = mapOf("appName" to chosen.appLabel)
                    ) {
                        localExecutor.launchApp(chosen.appLabel)
                    }
                }
            }

            is PendingAction.DisambiguateContact -> {
                when (val result = localExecutor.matchCandidateFromDisambiguationList(rawPrompt, pending.candidates)) {
                    is LocalDeviceActionExecutor.DisambiguationSelectionResult.Selected -> {
                        val matched = result.contact
                        val validPhones = matched.phones.filter { localExecutor.isValidPhoneNumber(localExecutor.normalizePhoneNumber(it.number)) }
                        if (validPhones.isEmpty()) {
                            return NormalizationResult.ExecuteDirect(
                                category = ToolCategory.GENERAL,
                                toolName = "contact_no_phone",
                                params = mapOf("contactName" to matched.displayName)
                            ) {
                                "${matched.displayName} does not have a valid phone number saved."
                            }
                        }

                        val normalized = localExecutor.normalizePhoneNumber(validPhones[0].number)
                        when (pending.actionType) {
                            ContactActionType.CALL -> {
                                Log.i(NATIVE_TAG, "[DISAMBIGUATION_RESOLVE] selectedCandidate='${matched.displayName}' action=ACTION_CALL")
                                return NormalizationResult.ExecuteDirect(
                                    category = ToolCategory.GENERAL,
                                    toolName = "call_disambiguated_contact",
                                    params = mapOf("contactName" to matched.displayName, "phoneNumber" to normalized)
                                ) {
                                    localExecutor.directCall(normalized)
                                }
                            }
                            ContactActionType.MESSAGING -> {
                                if (!pending.messageBody.isNullOrBlank()) {
                                    Log.i(NATIVE_TAG, "[DISAMBIGUATION_RESOLVE] selectedCandidate='${matched.displayName}' action=SEND_MESSAGE")
                                    return NormalizationResult.ExecuteDirect(
                                        category = ToolCategory.GENERAL,
                                        toolName = "send_message",
                                        params = mapOf("app" to pending.targetApp.name, "contactName" to matched.displayName)
                                    ) {
                                        when (pending.targetApp) {
                                            MessagingApp.WHATSAPP -> localExecutor.openWhatsAppMessage(normalized, pending.messageBody)
                                            MessagingApp.SMS -> localExecutor.openSmsComposer(normalized, pending.messageBody)
                                            MessagingApp.GENERIC -> localExecutor.openGenericMessageComposer(normalized, pending.messageBody)
                                        }
                                    }
                                } else {
                                    val nextPending = PendingAction.SendMessage(
                                        targetApp = pending.targetApp,
                                        contactName = matched.displayName,
                                        phoneNumber = normalized,
                                        messageBody = null,
                                        waitingFor = "message"
                                    )
                                    Log.i(NATIVE_TAG, "[DISAMBIGUATION_RESOLVE] selectedCandidate='${matched.displayName}' waiting_for=message")
                                    val appLabel = if (pending.targetApp == MessagingApp.WHATSAPP) "WhatsApp" else if (pending.targetApp == MessagingApp.SMS) "SMS" else "message"
                                    return NormalizationResult.AskFollowUp("${matched.displayName} ko kya $appLabel message bhejna hai?", nextPending)
                                }
                            }
                        }
                    }
                    is LocalDeviceActionExecutor.DisambiguationSelectionResult.OutOfBounds,
                    is LocalDeviceActionExecutor.DisambiguationSelectionResult.AffirmationOnly -> {
                        val count = pending.candidates.size
                        val prompt = if (count == 2) "Please 1 ya 2 choose karein." else "Please 1 se $count choose karein."
                        return NormalizationResult.AskFollowUp(prompt, pending)
                    }
                    is LocalDeviceActionExecutor.DisambiguationSelectionResult.Unrecognized -> {
                        if (isClearlyNewCommand(lower)) {
                            return null
                        }
                        val count = pending.candidates.size
                        val prompt = if (count == 2) "Please 1 ya 2 choose karein." else "Please 1 se $count choose karein."
                        return NormalizationResult.AskFollowUp(prompt, pending)
                    }
                }
            }

            is PendingAction.SendMessage -> {
                if (pending.waitingFor == "disambiguation") {
                    when (val result = localExecutor.matchCandidateFromDisambiguationList(rawPrompt, pending.disambiguationCandidates)) {
                        is LocalDeviceActionExecutor.DisambiguationSelectionResult.Selected -> {
                            val matched = result.contact
                            val validPhones = matched.phones.filter { localExecutor.isValidPhoneNumber(localExecutor.normalizePhoneNumber(it.number)) }
                            if (validPhones.isNotEmpty()) {
                                val normalized = localExecutor.normalizePhoneNumber(validPhones[0].number)
                                if (!pending.messageBody.isNullOrBlank()) {
                                    Log.i(NATIVE_TAG, "[DISAMBIGUATION_RESOLVE] selectedCandidate='${matched.displayName}' action=SEND_MESSAGE")
                                    return NormalizationResult.ExecuteDirect(
                                        category = ToolCategory.GENERAL,
                                        toolName = "send_message",
                                        params = mapOf("app" to pending.targetApp.name, "contactName" to matched.displayName)
                                    ) {
                                        when (pending.targetApp) {
                                            MessagingApp.WHATSAPP -> localExecutor.openWhatsAppMessage(normalized, pending.messageBody)
                                            MessagingApp.SMS -> localExecutor.openSmsComposer(normalized, pending.messageBody)
                                            MessagingApp.GENERIC -> localExecutor.openGenericMessageComposer(normalized, pending.messageBody)
                                        }
                                    }
                                } else {
                                    val next = pending.copy(
                                        contactName = matched.displayName,
                                        phoneNumber = normalized,
                                        waitingFor = "message"
                                    )
                                    Log.i(NATIVE_TAG, "[DISAMBIGUATION_RESOLVE] selectedCandidate='${matched.displayName}' waiting_for=message")
                                    val appLabel = if (pending.targetApp == MessagingApp.WHATSAPP) "WhatsApp" else "SMS"
                                    return NormalizationResult.AskFollowUp("${matched.displayName} ko kya $appLabel message bhejna hai?", next)
                                }
                            } else {
                                return NormalizationResult.ExecuteDirect(
                                    category = ToolCategory.GENERAL,
                                    toolName = "contact_no_phone",
                                    params = mapOf("contactName" to matched.displayName)
                                ) {
                                    "${matched.displayName} does not have a valid phone number saved."
                                }
                            }
                        }
                        is LocalDeviceActionExecutor.DisambiguationSelectionResult.OutOfBounds,
                        is LocalDeviceActionExecutor.DisambiguationSelectionResult.AffirmationOnly -> {
                            val count = pending.disambiguationCandidates.size
                            val prompt = if (count == 2) "Please 1 ya 2 choose karein." else "Please 1 se $count choose karein."
                            return NormalizationResult.AskFollowUp(prompt, pending)
                        }
                        is LocalDeviceActionExecutor.DisambiguationSelectionResult.Unrecognized -> {
                            if (isClearlyNewCommand(lower)) {
                                return null
                            }
                            val count = pending.disambiguationCandidates.size
                            val prompt = if (count == 2) "Please 1 ya 2 choose karein." else "Please 1 se $count choose karein."
                            return NormalizationResult.AskFollowUp(prompt, pending)
                        }
                    }
                } else if (pending.waitingFor == "contact") {
                    // Check if response contains direct phone number
                    val digitsConverted = wordsToDigits(rawPrompt)
                    val phoneCandidate = extractPhoneNumberCandidate(digitsConverted)
                    val rawPhone = if (phoneCandidate != null) normalizePhoneNumber(phoneCandidate) else null
                    if (rawPhone != null && isValidPhoneNumber(rawPhone)) {
                        val cleanPhone = normalizePhoneNumber(rawPhone)
                        if (!pending.messageBody.isNullOrBlank()) {
                            return NormalizationResult.ExecuteDirect(
                                category = ToolCategory.GENERAL,
                                toolName = "send_message",
                                params = mapOf("app" to pending.targetApp.name, "phone" to cleanPhone)
                            ) {
                                when (pending.targetApp) {
                                    MessagingApp.WHATSAPP -> localExecutor.openWhatsAppMessage(cleanPhone, pending.messageBody)
                                    MessagingApp.SMS -> localExecutor.openSmsComposer(cleanPhone, pending.messageBody)
                                    MessagingApp.GENERIC -> localExecutor.openGenericMessageComposer(cleanPhone, pending.messageBody)
                                }
                            }
                        } else {
                            val next = pending.copy(
                                contactName = cleanPhone,
                                phoneNumber = cleanPhone,
                                waitingFor = "message"
                            )
                            val appLabel = if (pending.targetApp == MessagingApp.WHATSAPP) "WhatsApp" else "SMS"
                            return NormalizationResult.AskFollowUp("Kya $appLabel message bhejna hai?", next)
                        }
                    }

                    // Otherwise resolve contact name
                    val candidateName = cleanExtractedContactName(rawPrompt)
                    val actionVerb = when (pending.targetApp) {
                        MessagingApp.WHATSAPP -> "whatsapp"
                        MessagingApp.SMS -> "sms"
                        MessagingApp.GENERIC -> "message"
                    }
                    val resolution = localExecutor.resolveContactForMessaging(candidateName.ifBlank { rawPrompt }, actionVerb)
                    when (resolution) {
                        is LocalDeviceActionExecutor.ContactMessageResolution.Resolved -> {
                            val contact = resolution.contact
                            val phone = resolution.phoneNumber
                            if (!pending.messageBody.isNullOrBlank()) {
                                return NormalizationResult.ExecuteDirect(
                                    category = ToolCategory.GENERAL,
                                    toolName = "send_message",
                                    params = mapOf("app" to pending.targetApp.name, "contactName" to contact.displayName)
                                ) {
                                    when (pending.targetApp) {
                                        MessagingApp.WHATSAPP -> localExecutor.openWhatsAppMessage(phone, pending.messageBody)
                                        MessagingApp.SMS -> localExecutor.openSmsComposer(phone, pending.messageBody)
                                        MessagingApp.GENERIC -> localExecutor.openGenericMessageComposer(phone, pending.messageBody)
                                    }
                                }
                            } else {
                                val next = pending.copy(
                                    contactName = contact.displayName,
                                    phoneNumber = phone,
                                    waitingFor = "message"
                                )
                                val appLabel = if (pending.targetApp == MessagingApp.WHATSAPP) "WhatsApp" else "SMS"
                                return NormalizationResult.AskFollowUp("${contact.displayName} ko kya $appLabel message bhejna hai?", next)
                            }
                        }
                        is LocalDeviceActionExecutor.ContactMessageResolution.DisambiguationNeeded -> {
                            val next = pending.copy(
                                waitingFor = "disambiguation",
                                disambiguationCandidates = resolution.candidates
                            )
                            return NormalizationResult.AskFollowUp(resolution.prompt, next)
                        }
                        is LocalDeviceActionExecutor.ContactMessageResolution.ErrorOrNotice -> {
                            return NormalizationResult.ExecuteDirect(
                                category = ToolCategory.GENERAL,
                                toolName = "message_contact_notice",
                                params = emptyMap()
                            ) {
                                resolution.message
                            }
                        }
                    }
                } else if (pending.waitingFor == "message") {
                    if (isClearlyNewCommand(lower)) {
                        return null
                    }
                    val messageText = rawPrompt.trim()
                    if (messageText.isNotBlank()) {
                        Log.i(NATIVE_TAG, "[RESOLVE_PENDING_MSG] app=${pending.targetApp} hasContact=${!pending.contactName.isNullOrBlank()}")
                        val targetPhone = if (!pending.phoneNumber.isNullOrBlank()) {
                            pending.phoneNumber
                        } else if (!pending.contactName.isNullOrBlank()) {
                            val res = localExecutor.resolveContactForMessaging(pending.contactName)
                            if (res is LocalDeviceActionExecutor.ContactMessageResolution.Resolved) {
                                res.phoneNumber
                            } else null
                        } else null

                        return NormalizationResult.ExecuteDirect(
                            category = ToolCategory.GENERAL,
                            toolName = "send_message",
                            params = mapOf("app" to pending.targetApp.name, "contactName" to pending.contactName)
                        ) {
                            when (pending.targetApp) {
                                MessagingApp.WHATSAPP -> localExecutor.openWhatsAppMessage(targetPhone, messageText)
                                MessagingApp.SMS -> localExecutor.openSmsComposer(targetPhone ?: "", messageText)
                                MessagingApp.GENERIC -> localExecutor.openGenericMessageComposer(targetPhone, messageText)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun isCancellation(lower: String): Boolean {
        val trimmed = lower.trim()
        return trimmed == "cancel" || trimmed == "stop" || trimmed == "nahi" || trimmed == "no" ||
                trimmed == "leave it" || trimmed == "cancel karo" || trimmed == "stop karo" ||
                trimmed == "rehne do" || trimmed == "rahne do" || trimmed == "chhodo" || trimmed == "chodo" ||
                trimmed == "रद्द करो" || trimmed == "रहने दो" || trimmed == "छोड़ो" || trimmed == "छोड़ो"
    }

    // ──────────────────────────────────────────────────────────────
    // Pattern Matchers & Extractors
    // ──────────────────────────────────────────────────────────────

    private fun isClearlyNewCommand(lower: String): Boolean {
        val trimmed = lower.trim()
        if (trimmed.isBlank()) return false

        // Explicit flashlight
        if (isFlashlightCommand(trimmed)) return true
        // Explicit settings shortcut
        if (matchSettingsType(trimmed) != null) return true
        // Explicit volume / brightness / telemetry
        if (isVolumeCommand(trimmed) || isBrightnessCommand(trimmed) || isDeviceInfoCommand(trimmed)) return true
        // Explicit phone call intent
        if (isDirectCallIntent(trimmed) || isDialerOpenCommand(trimmed)) return true
        // Explicit save contact intent
        if (isSaveContactIntent(trimmed)) return true
        // Explicit alarms / timers
        if (isAlarmCommand(trimmed) || isTimerCommand(trimmed)) return true
        // Explicit map / calendar / email / camera / files
        if (isMapCommand(trimmed) || isCalendarCommand(trimmed) || isEmailCommand(trimmed) || isCameraCommand(trimmed) || isFilesCommand(trimmed)) return true
        // Explicit app open command (e.g. "open YouTube", "open Chrome")
        if (trimmed.startsWith("open ") || trimmed.startsWith("launch ") || trimmed.startsWith("start ")) return true

        return false
    }

    /**
     * Negative intent protection: identifies informative statements, OTPs, or questions
     * without explicit action verbs that MUST NOT trigger calling, saving, or opening.
     * Pure predicate: DOES NOT invoke other intent classifiers.
     */
    fun isNegativeOrInformational(lower: String): Boolean {
        val trimmed = lower.trim()

        // 1. OTP / Verification codes
        if (trimmed.contains("otp") || trimmed.contains("code is") || trimmed.contains("passcode") || trimmed.contains("ओटीपी")) {
            return true
        }

        // 2. Informative / declarative statements (e.g. "number 8094857263 hai", "My OTP is...", "मेरा नंबर 8094857263 है")
        val hasActionVerb = trimmed.contains("save") || trimmed.contains("sev") || trimmed.contains("sav") ||
                trimmed.contains("add") || trimmed.contains("call") || trimmed.contains("dial") ||
                trimmed.contains("phone") || trimmed.contains("karo") || trimmed.contains("lagao") ||
                trimmed.contains("milao") || trimmed.contains("rakh") || trimmed.contains("banao") ||
                trimmed.contains("सेव") || trimmed.contains("कॉल") || trimmed.contains("लगाओ") ||
                trimmed.contains("मिलाओ") || trimmed.contains("जोड़") || trimmed.contains("बनाओ")

        if (trimmed.matches(Regex("(?i)^.*\\b(?:my|mera|hamara|apna|मेरा)\\s+(?:number|no|phone|mobile|contact|नंबर)\\s+(?:is|hai|h|था|है).*$")) &&
            !hasActionVerb) {
            return true
        }

        if (trimmed.matches(Regex("(?i)^(?:number|no|phone|mobile|नंबर)\\s+.*\\b(?:hai|h|था|है)$")) &&
            !hasActionVerb) {
            return true
        }

        // 3. Saved contact mentions
        if (trimmed.contains("my saved contact") || trimmed.contains("saved contact is") || trimmed.contains("saved contact hai")) {
            return true
        }

        return false
    }

    /**
     * Detects explicit SAVE_CONTACT intents across English, Hindi Devanagari, Hinglish, and Mixed language.
     * Pure predicate: DOES NOT invoke other intent classifiers.
     */
    fun isSaveContactIntent(lower: String): Boolean {
        val trimmed = lower.trim()

        // Pure exclusions without calling other intent classifiers
        if (trimmed.contains("otp") || trimmed.contains("code is") || trimmed.contains("passcode") || trimmed.contains("ओटीपी")) {
            return false
        }
        if (trimmed.contains("my saved contact") || trimmed.contains("saved contact is") || trimmed.contains("saved contact hai")) {
            return false
        }
        if (trimmed.startsWith("send sms") || trimmed.startsWith("send message") || trimmed.startsWith("text ")) {
            return false
        }
        if (trimmed.startsWith("what is") || trimmed.startsWith("what's") || trimmed.contains("kya hai")) {
            val hasSaveVerb = trimmed.contains("save") || trimmed.contains("sev") || trimmed.contains("sav") ||
                    trimmed.contains("saav") || trimmed.contains("सेव")
            if (!hasSaveVerb) return false
        }

        val savePatterns = listOf(
            // English patterns
            Regex("(?i)\\b(?:save|sev|sav|saav|add|create|insert)\\s+(?:this\\s+)?(?:number|contact|phone|num|no)\\b"),
            Regex("(?i)\\b(?:save|sev|sav|saav|add|create)\\s+[a-zA-Z\\u0900-\\u097F\\s]+'s\\s+number\\b"),
            Regex("(?i)\\b(?:save|sev|sav|saav|add|create)\\s+[a-zA-Z\\u0900-\\u097F\\s]+\\s+(?:in|to|as)\\s+contacts?\\b"),
            Regex("(?i)\\b(?:save|sev|sav|saav|add)\\s+\\+?\\d{3,15}\\b"),
            Regex("(?i)\\b(?:save|sev|sav|saav|add)\\s+[a-zA-Z\\u0900-\\u097F\\s]+\\s+\\+?\\d[\\d\\s\\-]{2,}\\d"),
            Regex("(?i)\\b(?:save|sev|sav|saav|add)\\s+\\+?\\d[\\d\\s\\-]{2,}\\d\\s+[a-zA-Z\\u0900-\\u097F\\s]+"),

            // Hinglish patterns
            Regex("(?i)\\b(?:number|num|contact|no)\\s+(?:save|sev|sav|saav|add|rakh|rakho)\\s*(?:karo|kro|kar|kar\\s+do|kardo|kijiye)?\\b"),
            Regex("(?i)\\b(?:save|sev|sav|saav|add|rakh|rakho)\\s*(?:karo|kro|kar|kar\\s+do|kardo|kijiye)\\b"),
            Regex("(?i)\\b(?:contact|phone)\\s*(?:me|mein|par|pe)\\s*(?:save|sev|sav|saav|add)\\s*(?:karo|kar|kar\\s+do|kardo)?\\b"),
            Regex("(?i)\\b(?:ke\\s+naam\\s+se|ke\\s+name\\s+se)\\s+(?:number\\s+)?(?:save|sev|sav|saav|add|rakh|karo|kar\\s+do)?\\b"),
            Regex("(?i)\\b\\+?\\d{3,15}\\s+.*(?:save|sev|sav|saav|add|rakh)\\s*(?:karo|kro|kar|kar\\s+do)?\\b"),
            Regex("(?i)\\b(?:contact|number)\\s+(?:bana\\s+do|banao|jod\\s+do|jodo|rakh\\s+lo)\\b"),

            // Devanagari patterns
            Regex("(?i)(?:नंबर|संपर्क|कांटेक्ट)\\s*(?:को|में)?\\s*(?:सेव|जोड़|बना|रख)\\s*(?:करो|कर\\s*दो|दो|कीजिए|कीजिये|बनाओ)?"),
            Regex("(?i)(?:सेव|जोड़|रख)\\s*(?:करो|कर\\s*दो|कीजिये|कीजिए)\\s*(?:नंबर|संपर्क)?"),
            Regex("(?i)(?:के\\s*नाम\\s*से)\\s*(?:नंबर|संपर्क)?\\s*(?:सेव|जोड़|रख)"),
            Regex("(?i)(?:यह|इस|ये)\\s+नंबर\\s*(?:को)?\\s*(?:सेव|जोड़)")
        )

        return savePatterns.any { it.containsMatchIn(lower) }
    }

    /**
     * Universal, deterministic contact candidate extractor across all contact-based intents
     * (Calling, WhatsApp, SMS, Generic Messaging, Save Contact, Contact Lookup).
     *
     * Extracts the complete candidate phrase (single-word, multi-word, initials, honorifics)
     * using structural grammar boundaries without premature token truncation.
     */
    fun extractUniversalContactCandidate(
        prompt: String,
        lower: String,
        intentHint: String? = null,
        messageBody: String? = null
    ): String {
        var textToParse = prompt.trim()

        // 1. Isolate contact clause: remove message body if present
        if (!messageBody.isNullOrBlank()) {
            val idx = textToParse.lastIndexOf(messageBody)
            if (idx >= 0) {
                textToParse = textToParse.substring(0, idx).trim()
            }
        }

        // 2. Strip trailing message boundary verbs & markers
        textToParse = textToParse.replace(Regex("(?i)\\s+(?:aur|and)\\s+(?:bolo|bol\\s+do|bol\\s+dena|bol|keh\\s+do|kehna|keh\\s+dena|puch|puchho|puchna|puchh|likho|likh\\s+do|likh\\s+dena|bata\\s+do|saying|say|tell|ask|write).*$"), "").trim()
        textToParse = textToParse.replace(Regex("(?i)\\s+(?:bolo|bol\\s+do|bol\\s+dena|bol|keh\\s+do|kehna|keh\\s+dena|puch|puchho|puchna|puchh|likho|likh\\s+do|likh\\s+dena|bata\\s+do|saying|ki|that|with\\s+body|with\\s+text|with\\s+message|body|:).*$"), "").trim()
        textToParse = textToParse.replace(Regex("(?i)\\s+(?:और)?\\s*(?:बोलो|बोल\\s*दो|कह\\s*दो|पूछो|पूछना|पूछ|लिखो|लिख\\s*दो|बता\\s*दो|कि).*$"), "").trim()

        // 3. Strip phone numbers for save contact or calling so they don't get parsed as contact names
        val wordsConverted = wordsToDigits(textToParse)
        val rawPhone = extractPhoneNumberCandidate(wordsConverted)
        if (rawPhone != null && isValidPhoneNumber(rawPhone)) {
            textToParse = textToParse.replace(rawPhone, " ").trim()
        }

        val patterns = listOf(
            // Pattern 1: English "send [a] [whatsapp/sms/text] [message] to/for <RECIPIENT> [on/via/in whatsapp/sms]"
            Pattern.compile("^(?:send\\s+)?(?:a\\s+)?(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|text|generic)?\\s*(?:message|msg)?\\s+(?:to|for)\\s+([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)(?:\\s+(?:on|via|through|in|by)\\s+(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|text|messages?)|$)", Pattern.CASE_INSENSITIVE),

            // Pattern 2: English "message / text / whatsapp / sms <RECIPIENT> [on/via whatsapp/sms]"
            Pattern.compile("^(?:message|text|whatsapp|sms)\\s+([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)(?:\\s+(?:on|via|through|in|by)\\s+(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|text|messages?)|$)", Pattern.CASE_INSENSITIVE),

            // Pattern 3: English Call "call / phone / dial / ring <RECIPIENT>"
            Pattern.compile("^(?:call|phone|dial|ring|make\\s+a\\s+call\\s+to)\\s+(?:to\\s+)?([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)(?:\\s+(?:on|via|through|pe|par|ko)?\\s*(?:phone|call|dial)|$)", Pattern.CASE_INSENSITIVE),

            // Pattern 4: English Save "save / add / create contact <RECIPIENT>"
            Pattern.compile("^(?:save|sev|sav|saav|add|create|insert)\\s+(?:this\\s+)?(?:contact|number|phone|no|num)?\\s*(?:for|as|named|name)?\\s+([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)(?:\\s+(?:in|to|as)\\s+contacts?|$)", Pattern.CASE_INSENSITIVE),

            // Pattern 5: Hinglish Particle Recipient BEFORE Channel/Verb "<RECIPIENT> ko/se/ke [whatsapp/sms/message/call/phone]"
            Pattern.compile("^([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)\\s+(?:ko|se|ke|par|pe)\\s+(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|message|msg|phone|call|dial|baat|hi|hello)\\b", Pattern.CASE_INSENSITIVE),

            // Pattern 6: Hinglish Save "<RECIPIENT> ka number save karo / <RECIPIENT> ke naam se save karo"
            Pattern.compile("^([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)\\s+(?:ka|ki|ke)\\s+(?:number|phone|contact|mobile|no|num)\\s+(?:save|sev|sav|add|rakh|rakho|banao|karo)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)\\s+(?:ke\\s+naam\\s+se|ke\\s+name\\s+se)\\s+(?:number\\s+)?(?:save|sev|sav|add|rakh|rakho|karo)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)\\s+(?:ko|se|ke)\\s+(?:save|sev|sav|add|rakh|rakho|bhejo|bhej|karo|kar|phone|call)\\b", Pattern.CASE_INSENSITIVE),

            // Pattern 7: Hinglish Channel BEFORE Recipient "whatsapp/sms/message pe <RECIPIENT> [ko/se]"
            Pattern.compile("(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|message|msg)\\s+(?:pe|par|mein|me|se)\\s+([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)(?:\\s+(?:ko|se|par|pe|ke|to|bhejo|karo|kar\\s+do|kar\\s+de|bhej\\s+do|bhej\\s+de|aur|likh|bolo|puch)|$)", Pattern.CASE_INSENSITIVE),

            // Pattern 8: Hinglish Call Verb BEFORE Recipient "call karo / phone lagao <RECIPIENT> [ko]"
            Pattern.compile("^(?:call|phone|dial)\\s+(?:karo|lagao|milao|karao)\\s+([a-zA-Z0-9._\\-\\s\\u0900-\\u097F]+?)(?:\\s+(?:ko|pe|par|se)|$)", Pattern.CASE_INSENSITIVE),

            // Pattern 9: Devanagari Patterns
            Pattern.compile("^(.*?)\\s*(?:को|पर|पे|से|के)\\s*(?:व्हाट्सएप|एसएमएस|मैसेज|संदेश|कॉल|फोन|डायल|बात|सेव|जोड़)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:व्हाट्सएप|एसएमएस|मैसेज|संदेश|कॉल|फोन|डायल|सेव)\\s*(?:पे|पर|में|से|करो|लगाओ|कीजिए)?\\s*(.*?)(?:\\s*(?:को|पर|पे|से|के)|$)", Pattern.CASE_INSENSITIVE),

            // Pattern 10: Lookup queries "Suresh ka number / What is Suresh's number"
            Pattern.compile("^(.*?)\\s+(?:ka|ki|ke|का|की|के)\\s+(?:number|phone|contact|mobile|no|नंबर|फोन|संपर्क|मोबाइल)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:what\\s+is|what's|tell\\s+me|find|show|search|lookup)\\s+(?:contact\\s+)?(.*?)(?:'s\\s+number|'s\\s+phone|'s\\s+contact|\\s+number|\\s+phone|\\s+contact|$)", Pattern.CASE_INSENSITIVE)
        )

        val ignoreList = setOf(
            "whatsapp", "whats app", "what's app", "what app", "watsapp", "wa", "sms", "message", "msg", "mesg",
            "karo", "bhejo", "send", "kardo", "kar do", "kar de", "bhej do", "bhej de", "pe", "par", "ko", "se", "to", "ke",
            "wala", "wali", "whatsapp wala", "whatsapp wali", "call", "phone", "dial", "dialer", "keypad",
            "save", "sev", "sav", "add", "number", "this number", "is number", "contacts", "contact",
            "me", "him", "her", "them", "someone", "anyone",
            "व्हाट्सएप", "एसएमएस", "मैसेज", "करो", "भेजो", "को", "पर", "पे", "से", "फोन", "कॉल", "नंबर", "सेव"
        )

        for (pat in patterns) {
            val m = pat.matcher(textToParse)
            if (m.find()) {
                var candidate = m.group(1)?.trim() ?: ""
                val cleaned = cleanExtractedContactName(candidate)
                if (cleaned.isNotBlank() && !ignoreList.contains(cleaned.lowercase(Locale.ROOT))) {
                    val digitsOnly = cleaned.replace(Regex("[^0-9]"), "")
                    val alphaOnly = cleaned.replace(Regex("[^a-zA-Z\\u0900-\\u097F]"), "")
                    if (alphaOnly.isNotBlank() || digitsOnly.length < 3) {
                        return cleaned
                    }
                }
            }
        }

        // Fallback: clean the whole textToParse if it's already an isolated name candidate
        val fallback = cleanExtractedContactName(textToParse)
        if (fallback.isNotBlank() && !ignoreList.contains(fallback.lowercase(Locale.ROOT))) {
            val alphaOnly = fallback.replace(Regex("[^a-zA-Z\\u0900-\\u097F]"), "")
            if (alphaOnly.isNotBlank()) {
                return fallback
            }
        }

        return ""
    }

    /**
     * Extracts name and phone number for SAVE_CONTACT intent.
     * Supports names positioned BEFORE or AFTER the phone number, multi-word names, and language variations.
     */
    fun extractSaveContactEntities(prompt: String, lower: String): Pair<String?, String?> {
        val wordsConverted = wordsToDigits(prompt)
        val rawCandidate = extractPhoneNumberCandidate(wordsConverted)
        val normalizedPhone = if (rawCandidate != null) {
            val norm = normalizePhoneNumber(rawCandidate)
            if (isValidPhoneNumber(norm) && norm.replace(Regex("[^0-9]"), "").length >= 3) norm
            else null
        } else null

        val candidateName = extractUniversalContactCandidate(prompt, lower, intentHint = "SAVE")
        val extractedName = if (candidateName.isNotBlank()) candidateName else null

        return Pair(extractedName, normalizedPhone)
    }

    fun cleanExtractedContactName(raw: String): String {
        var clean = raw.trim()

        // 1. Remove common English/Hinglish/Hindi prefix phrases
        val prefixPatterns = listOf(
            Regex("(?i)^(?:save|sev|sav|saav|add|create|insert)\\s+(?:this\\s+)?(?:contact|number|phone|no|num|wala\\s+number)?\\s*(?:for|as|named|name)?\\s*"),
            Regex("(?i)^(?:send\\s+)?(?:a\\s+)?(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|text|generic)?\\s*(?:message|msg)?\\s+(?:to|for)\\s*"),
            Regex("(?i)^(?:call|phone|dial|ring|make\\s+a\\s+call\\s+to)\\s+(?:to\\s+)?"),
            Regex("(?i)^(?:message|text|whatsapp|sms)\\s+(?:to|for)?\\s*"),
            Regex("(?i)^(?:what\\s+is|what's|tell\\s+me|find|show|search|lookup)\\s+(?:contact\\s+)?"),
            Regex("(?i)^(?:please|can\\s+you|kripya|kripya\\s+karke)\\s+"),
            Regex("(?i)^(?:this|is|iss|ye|yeh|yeh\\s+wala|is\\s+wale)\\s+(?:number|no|contact|phone|ko|par|pe)?\\s*"),
            Regex("(?i)^(?:mere\\s+dost|apne\\s+dost|my\\s+friend|friend|mere\\s+bhai|bhai)\\s+"),
            Regex("(?i)^(?:number|contact|phone|no)\\s+(?:ko|par|pe|me|mein)?\\s*(?:save|sev|sav|add|rakh|rakho)?\\s*(?:karo|kro|kar|kar\\s+do|kardo)?\\s*"),
            Regex("(?i)^(?:as|for|named|title|to)\\s+"),
            Regex("(?i)^(?:कृपया|कृपया\\s+करके)?\\s*(?:यह|इस|ये|ये\\s+वाला)?\\s*(?:नंबर|संपर्क|फोन|कांटेक्ट)?\\s*(?:को|पर|पे|में)?\\s*(?:सेव|जोड़|रख|बना)?\\s*(?:करो|कर\\s*दो|कीजिए|कीजिये|दो)?\\s*")
        )
        for (p in prefixPatterns) {
            clean = clean.replace(p, "").trim()
        }

        // 2. Remove common English/Hinglish/Hindi suffix patterns
        val suffixPatterns = listOf(
            Regex("(?i)\\s+(?:on|via|through|in|by)\\s+(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|text|messages?)$"),
            Regex("(?i)\\s+(?:ka|ki|ke|ko)\\s+(?:number|phone|contact|mobile|no|num)\\s+(?:save|sev|sav|add|rakh|rakho|banao|bana\\s+do)\\s*(?:karo|kro|kar|kar\\s+do|kardo|kijiye)?$"),
            Regex("(?i)\\s+(?:ka|ki|ke|ko)\\s+(?:number|phone|contact|mobile|no|num)$"),
            Regex("(?i)\\s+(?:ke|ka|ki)?\\s*(?:naam\\s+se|name\\s+se)\\s+(?:number\\s+)?(?:save|sev|sav|add|rakh|rakho|karo|kar\\s+do|kardo)?$"),
            Regex("(?i)\\s+(?:ke|ka|ki)?\\s*(?:naam\\s+se|name\\s+se)$"),
            Regex("(?i)\\s+(?:ke|ka|ki)?\\s*(?:contact|contacts|phone)\\s+(?:me|mein|par|pe)\\s*(?:save|sev|sav|add|rakh|karo|kar\\s+do)?$"),
            Regex("(?i)\\s+(?:ko|par|pe|se|me|mein)\\s+(?:save|sev|sav|add|rakh|rakho)\\s*(?:karo|kro|kar|kar\\s+do|kardo)?$"),
            Regex("(?i)\\s+(?:save|sev|sav|saav|add|rakh|rakho)\\s*(?:karo|kro|kar|kar\\s+do|kardo|kijiye|kijie|karna)?$"),
            Regex("(?i)\\s+(?:bana\\s+do|banao|jod\\s+do|jodo|rakh\\s+lo)$"),
            Regex("(?i)\\s+(?:in|to|as)\\s+contacts?$"),
            Regex("(?i)\\s+'s\\s+(?:number|phone|contact|mobile)$"),
            Regex("(?i)\\s+'s$"),
            Regex("(?i)\\s+(?:ke\\s+whatsapp\\s+pe|ke\\s+whatsapp\\s+par|ke\\s+whatsapp|ke\\s+sms\\s+pe|ke\\s+sms|ke\\s+message|ke\\s+msg)$"),
            Regex("(?i)\\s+(?:ko\\s+whatsapp\\s+pe|ko\\s+whatsapp|ko\\s+sms|ko\\s+message|ko\\s+msg)$"),
            Regex("(?i)\\s+(?:whatsapp\\s+wala|whatsapp\\s+wali|wala|wali)$"),
            Regex("(?i)\\s+(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|message|msg)$"),
            Regex("(?i)\\s+(?:phone|call|dial|baat)\\s*(?:karo|lagao|milao|karao|kar)?$"),
            Regex("(?i)\\s+(?:karo|lagao|milao|karao|kar|bhejo|bhej|send)$"),
            Regex("(?i)\\s+(?:ka|ki|ke|ko|par|pe|se|me|mein|ji|bhai|sir|please|na)$"),
            Regex("(?i)\\s*(?:का|की|के|को)\\s*(?:नंबर|फोन|संपर्क|कांटेक्ट)?\\s*(?:सेव|जोड़|रख|बना)?\\s*(?:करो|कर\\s*दो|कीजिए|कीजिये|बनाओ|दो)?$"),
            Regex("(?i)\\s*(?:के\\s*नाम\\s*से)\\s*(?:नंबर|संपर्क)?\\s*(?:सेव|जोड़|रख|करो|कर\\s*दो)?$"),
            Regex("(?i)\\s*(?:के\\s*नाम\\s*से)$"),
            Regex("(?i)\\s*(?:में|पे|पर|को|से)\\s*(?:सेव|जोड़|रख|कॉल|फोन|व्हाट्सएप|मैसेज|संदेश)?\\s*(?:करो|कर\\s*दो|कीजिये|कीजिए|लगाओ|भेजो)?$"),
            Regex("(?i)\\s*(?:सेव|जोड़|रख|कॉल|फोन|व्हाट्सएप|मैसेज|संदेश)\\s*(?:करो|कर\\s*दो|कीजिये|कीजिए|लगाओ|भेजो)$"),
            Regex("(?i)\\s*(?:का|की|के|को|पे|पर|से|में)$")
        )
        for (p in suffixPatterns) {
            clean = clean.replace(p, "").trim()
        }

        // Clean up leftover symbols while preserving letters, Devanagari, and meaningful spaces
        clean = clean.replace(Regex("[0-9+\\-#*]"), " ")
        clean = clean.replace(Regex("\\s+"), " ").trim()

        val ignoreList = setOf(
            "number", "contact", "phone", "mobile", "this", "is", "iss", "kisi",
            "save", "sev", "sav", "saav", "add", "karo", "kardo", "kar", "naam", "name", "as", "for", "named",
            "ye", "yeh", "wala", "wale", "ke", "ka", "ki", "ko", "se", "pe", "par", "me", "mein",
            "whatsapp", "whats app", "what's app", "what app", "watsapp", "wa", "sms", "message", "msg", "mesg",
            "call", "dial", "dialer", "keypad", "to", "on", "via", "through", "in", "by", "send", "bhejo", "bhej",
            "नंबर", "संपर्क", "फोन", "कांटेक्ट", "सेव", "करो", "नाम", "यह", "इस", "ये", "में", "को", "पर", "पे", "से", "कॉल", "व्हाट्सएप", "मैसेज"
        )
        if (ignoreList.contains(clean.lowercase(Locale.ROOT))) return ""

        return clean
    }

    /**
     * Detects CONTACT_LOOKUP queries (e.g. "Suresh ka number kya hai?", "सुरेश का नंबर क्या है?").
     * These must search local contacts and return the masked details without calling or sending to LLM.
     * Pure predicate: DOES NOT invoke other intent classifiers.
     */
    fun isContactLookupCommand(lower: String): Boolean {
        val trimmed = lower.trim()

        // Pure exclusions without calling other intent classifiers
        if (trimmed.startsWith("send sms") || trimmed.startsWith("send message") || trimmed.startsWith("text ")) {
            return false
        }
        val hasSaveVerb = trimmed.contains("save") || trimmed.contains("sev") || trimmed.contains("sav") ||
                trimmed.contains("saav") || trimmed.contains("add to contact") || trimmed.contains("create contact") ||
                trimmed.contains("सेव") || trimmed.contains("जोड़")
        if (hasSaveVerb) {
            return false
        }
        val hasDirectCallVerb = trimmed.startsWith("call ") || trimmed.startsWith("dial ") ||
                trimmed.contains("call karo") || trimmed.contains("phone lagao") || trimmed.contains("कॉल करो")
        if (hasDirectCallVerb && !trimmed.contains("kya hai") && !trimmed.contains("batao") && !trimmed.contains("बताओ")) {
            return false
        }

        val lookupPatterns = listOf(
            // English lookup
            Regex("(?i)\\b(?:what\\s+is|what's|tell\\s+me|find|show|search|lookup|get)\\s+.*\\b(?:number|phone|mobile|contact)\\b"),
            Regex("(?i)\\b[a-zA-Z\\u0900-\\u097F\\s]+'s\\s+(?:number|phone|mobile|contact)\\b"),

            // Hinglish lookup: "Suresh ka number kya hai", "Suresh ka phone batao"
            Regex("(?i)\\b(?:ka|ki|ke)\\s+(?:number|phone|contact|mobile|no)\\s+(?:kya\\s+hai|kya\\s+h|batao|batana|bataye|dikhao|dikhaye|kya)\\b"),
            Regex("(?i)\\b(?:number|phone|contact|mobile)\\s+(?:batao|batana|bataye|dikhao|dikhaye|kya\\s+hai|kya\\s+h)\\b"),

            // Devanagari lookup: "सुरेश का नंबर क्या है", "सुरेश का फोन नंबर बताओ"
            Regex("(?i)(?:का|की|के)\\s*(?:नंबर|फोन|मोबाइल|संपर्क|कांटेक्ट)\\s*(?:क्या\\s+है|क्या\\s+h|बताओ|बताइए|दिखाओ)"),
            Regex("(?i)(?:नंबर|फोन|संपर्क)\\s*(?:बताओ|बताइए|क्या\\s+है|दिखाओ)")
        )

        return lookupPatterns.any { it.containsMatchIn(lower) }
    }

    /**
     * Extracts contact name from a lookup command.
     */
    fun extractContactNameForLookup(prompt: String): String {
        return extractUniversalContactCandidate(prompt, normalizeForIntentMatching(prompt), intentHint = "LOOKUP")
    }

    // ──────────────────────────────────────────────────────────────
    // Smart Messaging (WhatsApp, SMS, Generic) Matchers & Extractors
    // ──────────────────────────────────────────────────────────────

    data class MessagingIntentData(
        val app: MessagingApp,
        val contactName: String?,
        val phoneNumber: String?,
        val messageBody: String?
    )

    fun detectMessagingChannel(lower: String): MessagingApp {
        val trimmed = lower.trim()
        val isExplicitWhatsApp = trimmed.contains("whatsapp") || trimmed.contains("what's app") ||
                trimmed.contains("whats app") || trimmed.contains("what app") ||
                trimmed.contains("watsapp") || trimmed.contains("watsap") || trimmed.contains("watzap") ||
                trimmed.contains("व्हाट्सएप") || trimmed.contains("व्हाट्स ऐप") || trimmed.contains("व्हाट्सअप") ||
                trimmed.contains("वाट्सएप") || trimmed.contains("वाट्सअप") ||
                trimmed.contains("whatsapp wala") || trimmed.contains("whatsapp wali") ||
                trimmed.matches(Regex("(?i).*\\b(?:wa|डब्ल्यूए)\\b\\s+(?:pe|par|me|mein|se|ko|msg|message|karo|kar|bhejo|bhej).*")) ||
                trimmed.matches(Regex("(?i).*\\b(?:pe|par|me|mein|se|ke)\\s+(?:wa|डब्ल्यूए)\\b.*"))

        if (isExplicitWhatsApp) return MessagingApp.WHATSAPP

        val isExplicitSms = trimmed.contains("sms") || trimmed.startsWith("text ") ||
                trimmed.contains("text message") || trimmed.contains("text msg") ||
                trimmed.contains("एसएमएस") || trimmed.contains("टेक्स्ट") ||
                trimmed.contains("sms karo") || trimmed.contains("sms bhejo") ||
                trimmed.contains("sms kar") || trimmed.contains("ko sms")

        if (isExplicitSms) return MessagingApp.SMS

        return MessagingApp.GENERIC
    }

    fun isMessagingCommand(lower: String): Boolean {
        val trimmed = lower.trim()

        // 1. Exclude pure app open/launch commands (e.g. "open whatsapp", "whatsapp kholo", "whatsapp open karo")
        val isAppLaunchOnly = (trimmed == "whatsapp" || trimmed == "what's app" || trimmed == "whats app" || trimmed == "what app" ||
                trimmed == "watsapp" || trimmed == "wa" ||
                trimmed == "open whatsapp" || trimmed == "open whats app" || trimmed == "open what's app" || trimmed == "open what app" ||
                trimmed == "whatsapp kholo" || trimmed == "whats app kholo" || trimmed == "whatsapp open karo" ||
                trimmed == "whatsapp chalu karo" || trimmed == "whatsapp launch karo" || trimmed == "whatsapp start karo" ||
                trimmed == "व्हाट्सएप खोलो" || trimmed == "व्हाट्सएप चालू करो" || trimmed == "open sms" || trimmed == "sms kholo")
        if (isAppLaunchOnly) return false

        // 2. Exclude direct cellular phone calls that mention whatsapp voice call if any without message markers
        if ((trimmed.contains("whatsapp call") || trimmed.contains("whatsapp pe call")) &&
            !trimmed.contains("message") && !trimmed.contains("msg") && !trimmed.contains("bolo") && !trimmed.contains("bhejo") &&
            !trimmed.contains("puch") && !trimmed.contains("keh do") && !trimmed.contains("likh")) {
            return false
        }

        // 3. Exclude email commands
        if (trimmed.contains("email") || trimmed.contains("mail to") || trimmed.contains("mail bhejo") || trimmed.contains("ईमेल")) {
            return false
        }

        // 4. WhatsApp messaging patterns
        val hasWhatsAppKeyword = trimmed.contains("whatsapp") || trimmed.contains("what's app") ||
                trimmed.contains("whats app") || trimmed.contains("what app") ||
                trimmed.contains("watsapp") || trimmed.contains("watsap") || trimmed.contains("watzap") ||
                trimmed.contains("व्हाट्सएप") || trimmed.contains("व्हाट्स ऐप") || trimmed.contains("व्हाट्सअप") ||
                trimmed.contains("वाट्सएप") || trimmed.contains("वाट्सअप") ||
                trimmed.matches(Regex("(?i).*\\b(?:wa|डब्ल्यूए)\\b\\s+(?:pe|par|me|mein|se|ko|msg|message|karo|kar|bhejo|bhej).*")) ||
                trimmed.matches(Regex("(?i).*\\b(?:pe|par|me|mein|se|ke)\\s+(?:wa|डब्ल्यूए)\\b.*"))

        if (hasWhatsAppKeyword) {
            return true
        }

        // 5. SMS patterns
        val isSms = trimmed.startsWith("send sms") || trimmed.startsWith("send a message") || trimmed.startsWith("text ") ||
                trimmed.contains("sms bhejo") || trimmed.contains("sms karo") || trimmed.contains("sms kar do") ||
                trimmed.contains("sms kar de") || trimmed.contains("ko sms") || trimmed.startsWith("sms ") ||
                trimmed.contains("sms to") || trimmed.contains("एसएमएस भेजो") || trimmed.contains("एसएमएस करो") ||
                trimmed.contains("को एसएमएस") || trimmed.contains("टेक्स्ट")

        if (isSms) {
            return true
        }

        // 6. Generic messaging patterns with recipient or action verb
        val isGenericMsg = (trimmed.contains("message") || trimmed.contains("msg") || trimmed.contains("mesg") ||
                trimmed.contains("मैसेज") || trimmed.contains("संदेश")) &&
                (trimmed.contains("bhejo") || trimmed.contains("bhej") || trimmed.contains("karo") ||
                        trimmed.contains("kar do") || trimmed.contains("kar de") || trimmed.contains("send") ||
                        trimmed.contains("likho") || trimmed.contains("likh") || trimmed.contains("bolo") ||
                        trimmed.contains("bol") || trimmed.contains("puch") || trimmed.contains("keh do") ||
                        trimmed.contains("bata do") || trimmed.contains("लिखो") || trimmed.contains("भेजो") ||
                        trimmed.contains("करो") || trimmed.contains("बोलो") || trimmed.contains("पूछो"))

        if (isGenericMsg) {
            return true
        }

        // 7. Conversational "ko [text] bhejo" / "se puch" / "ko bolo" (e.g. "Dushyant ko hi bhejo", "Rahul ko hello bhejo")
        val isQuickBhejo = trimmed.matches(Regex("(?i)^[a-zA-Z\\u0900-\\u097F\\s]+\\s+(?:ko|pe|par|se)\\s+.*\\b(?:bhejo|bhej\\s+do|bhej\\s+de|bhejna|send\\s+karo|send\\s+kar\\s+do|send\\s+kar\\s+de|bata\\s+do|puch|puchho|puchna|keh\\s+do|likh\\s+do|likh\\s+dena)\\b.*$"))
        if (isQuickBhejo) {
            return true
        }

        // 8. Direct numeric messaging: "8094857263 ko whatsapp/sms/message"
        val isNumericMsg = trimmed.matches(Regex("(?i)^\\+?\\d[\\d\\s\\-]{2,}\\d\\s+(?:ko|pe|par|se|to)?\\s*(?:whatsapp|whats\\s+app|watsapp|wa|sms|message|msg).*$"))
        if (isNumericMsg) {
            return true
        }

        return false
    }

    fun extractMessagingIntent(prompt: String, lower: String): MessagingIntentData {
        val app = detectMessagingChannel(lower)
        val messageBody = extractMessageBody(prompt, lower)
        val (contactName, phoneNumber) = extractContactOrPhoneForMessaging(prompt, lower, messageBody)

        return MessagingIntentData(
            app = app,
            contactName = contactName,
            phoneNumber = phoneNumber,
            messageBody = messageBody
        )
    }

    fun extractMessageBody(prompt: String, lower: String): String? {
        val text = prompt.trim()

        // 1. Quoted Message Extraction (Highest Priority — Preserves exact content, case, and nested verbs)
        val quotePatterns = listOf(
            Pattern.compile("[\"“](.*?)[\"”]", Pattern.DOTALL),
            Pattern.compile("['‘](.*?)['’]", Pattern.DOTALL),
            Pattern.compile("«(.*?)»", Pattern.DOTALL)
        )
        for (qPat in quotePatterns) {
            val qm = qPat.matcher(text)
            if (qm.find()) {
                val quoted = qm.group(1)?.trim() ?: ""
                if (quoted.isNotBlank() && !isPureMessagingControlWord(quoted)) {
                    return quoted
                }
            }
        }

        // 2. Colon Boundary (e.g. "Send WhatsApp to Dushyant: kal milte hain", "Dushyant ko bolo: meeting cancel")
        val colonIdx = text.indexOf(':')
        if (colonIdx >= 0 && colonIdx < text.length - 1) {
            val afterColon = text.substring(colonIdx + 1).trim()
            val cleanAfter = afterColon.replace(Regex("^[\"']|[\"']$"), "").trim()
            if (cleanAfter.isNotBlank() && !isPureMessagingControlWord(cleanAfter)) {
                return cleanAfter
            }
        }

        // 3. Natural Boundary Verbs (English + Hinglish + Hindi)
        // Markers: bolo, bol do, bol dena, keh do, kehna, puch, puchho, puchna, likho, likh do, likh dena, bata do, saying, say, tell, ask, write
        val boundaryPatterns = listOf(
            // Hinglish / English boundary with optional "aur/and" and optional "ki/that/ke"
            Pattern.compile("(?:aur|and)?\\s*(?:bolo|bol\\s+do|bol\\s+dena|bol|keh\\s+do|kehna|keh\\s+dena|kaho|puch|puchho|puchna|puchh|puch\\s+lo|likho|likh\\s+do|likh\\s+dena|bata\\s+do|bata\\s+dena|saying|say|tell\\s+him|tell\\s+her|tell|ask\\s+him|ask\\s+her|ask|write|writing)\\s+(?:ki\\s+|that\\s+|ke\\s+|कि\\s+)?(.*)$", Pattern.CASE_INSENSITIVE),
            // Hindi Devanagari boundary
            Pattern.compile("(?:और)?\\s*(?:बोलो|बोल\\s*दो|बोल\\s*देना|कह\\s*दो|कहना|कहो|पूछो|पूछना|पूछ|पूछ\\s*लो|लिखो|लिख\\s*दो|लिख\\s*देना|बता\\s*दो|बता\\s*देना)\\s*(?:कि\\s*)?(.*)$", Pattern.CASE_INSENSITIVE),
            // "ki / that / कि" connector directly after messaging clause
            Pattern.compile("\\b(?:message\\s+bhejo|message\\s+karo|msg\\s+bhejo|msg\\s+kar\\s+do|msg\\s+kar\\s+de|whatsapp\\s+karo|whatsapp\\s+kar\\s+do|whatsapp\\s+kar\\s+de|whatsapp\\s+pe|sms\\s+karo|sms\\s+bhejo)\\s+(?:ki|that|कि)\\s+(.*)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:ki|that|कि)\\s+(.*)$", Pattern.CASE_INSENSITIVE),
            // "saying / with text / with message"
            Pattern.compile("\\b(?:saying|with\\s+text|with\\s+message|with\\s+body|body)\\s+(.*)$", Pattern.CASE_INSENSITIVE)
        )

        for (pat in boundaryPatterns) {
            val m = pat.matcher(text)
            if (m.find()) {
                var body = m.group(1)?.trim() ?: ""
                body = body.replace(Regex("^[\"']|[\"']$"), "").trim()
                if (body.isNotBlank() && !isPureMessagingControlWord(body)) {
                    return body
                }
            }
        }

        // 4. Trailing Action Verb Pattern: "ko [channel] [message] bhejo/karo/send karo"
        // E.g. "Dushyant ko WhatsApp pe hello bhejo", "Dushyant ko hi bhejo", "Rahul ko kal 5 baje milte hain send karo"
        val trailingVerbPattern = Pattern.compile("(?:ko|pe|par|to|se)\\s+(?:whatsapp|whats\\s+app|what\\s+app|watsapp|wa|sms|message|msg)?\\s*(?:pe|par|me|mein)?\\s*(.+?)\\s+(?:bhejo|bhej\\s+do|bhej\\s+de|bhejna|send\\s+karo|send\\s+kar\\s+do|send\\s+kar\\s+de|bhej|send|karo|kar\\s+do|kar\\s+de|likh\\s+do|likho)$", Pattern.CASE_INSENSITIVE)
        val tvMatcher = trailingVerbPattern.matcher(text)
        if (tvMatcher.find()) {
            var candidate = tvMatcher.group(1)?.trim() ?: ""
            candidate = candidate.replace(Regex("^[\"']|[\"']$"), "").trim()
            if (candidate.isNotBlank() && !isPureMessagingControlWord(candidate)) {
                return candidate
            }
        }

        // 5. Tail Message Pattern after Action Verb (e.g. "WhatsApp Dushyant kal milte hain", "8094857263 ko WhatsApp karo kal call karna", "Dushyant ko message karo kal meeting cancel hai")
        val actionVerbTailPatterns = listOf(
            Pattern.compile("^(?:whatsapp|sms)\\s+[a-zA-Z0-9\\u0900-\\u097F]+\\s+(.+)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:whatsapp|sms|message|msg)\\s*(?:karo|kar\\s+do|kar\\s+de|karna|likh\\s+dena|likh\\s+do|likho|bhejo|bhej\\s+do|bhej\\s+de)\\s+(?:ki\\s+|that\\s+)?(.+)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:whatsapp|whats\\s+app|what\\s+app|watsapp|sms|message|msg)\\s+(?:wala|wali)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        )
        for (pat in actionVerbTailPatterns) {
            val m = pat.matcher(text)
            if (m.find()) {
                var candidate = m.group(1)?.trim() ?: ""
                candidate = candidate.replace(Regex("^[\"']|[\"']$"), "").trim()
                if (candidate.isNotBlank() && !isPureMessagingControlWord(candidate)) {
                    return candidate
                }
            }
        }

        return null
    }

    private fun isPureMessagingControlWord(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT).trim()
        val controlWords = setOf(
            "whatsapp", "whats app", "what's app", "what app", "watsapp", "wa",
            "sms", "message", "msg", "mesg", "text", "karo", "kar do", "kar de", "bhejo",
            "bhej", "bhej do", "bhej de", "bhejna", "send", "bolo", "bol", "puch", "keh do",
            "likho", "likh do", "bata do", "whatsapp wala", "whatsapp wali",
            "व्हाट्सएप", "मैसेज", "एसएमएस", "करो", "भेजो", "बोलो", "पूछो", "लिखो"
        )
        return controlWords.contains(lower) || lower.isBlank()
    }

    fun extractContactOrPhoneForMessaging(prompt: String, lower: String, messageBody: String?): Pair<String?, String?> {
        val wordsConverted = wordsToDigits(prompt)
        val rawPhone = extractPhoneNumberCandidate(wordsConverted)
        if (rawPhone != null && isValidPhoneNumber(rawPhone)) {
            val normalized = normalizePhoneNumber(rawPhone)
            return Pair(null, normalized)
        }

        val contact = extractUniversalContactCandidate(prompt, lower, intentHint = "MESSAGE", messageBody = messageBody)
        return if (contact.isNotBlank()) Pair(contact, null) else Pair(null, null)
    }

    /**
     * Volume control command detection.
     */
    fun isVolumeCommand(lower: String): Boolean {
        return (lower.contains("volume") || lower.contains("sound") || lower.contains("awaaz") || lower.contains("awaz") ||
                lower.contains("आवाज़") || lower.contains("आवाज") || lower.contains("ध्वनि")) &&
                (lower.contains("badhao") || lower.contains("badha") || lower.contains("increase") || lower.contains("up") ||
                        lower.contains("louder") || lower.contains("high") || lower.contains("kam") || lower.contains("decrease") ||
                        lower.contains("down") || lower.contains("lower") || lower.contains("mute") || lower.contains("बढ़ाओ") ||
                        lower.contains("बढ़ा") || lower.contains("कम") || lower.contains("धीमा") || lower.contains("तेज़") || lower.contains("तेज"))
    }

    /**
     * Brightness control command detection.
     */
    fun isBrightnessCommand(lower: String): Boolean {
        return (lower.contains("brightness") || lower.contains("screen light") || lower.contains("display light") ||
                lower.contains("roshni") || lower.contains("रोशनी") || lower.contains("ब्राइटनेस")) &&
                (lower.contains("badhao") || lower.contains("badha") || lower.contains("increase") || lower.contains("up") ||
                        lower.contains("brighter") || lower.contains("high") || lower.contains("kam") || lower.contains("decrease") ||
                        lower.contains("down") || lower.contains("lower") || lower.contains("बढ़ाओ") || lower.contains("बढ़ा") ||
                        lower.contains("कम") || lower.contains("धीमा") || lower.contains("ज्यादा"))
    }

    private fun isFlashlightCommand(lower: String): Boolean {
        return lower.contains("flashlight") || lower.contains("torch") || lower.contains("फ्लैशलाइट") || lower.contains("टॉर्च") ||
                (lower.contains("light") && (lower.contains("turn on") || lower.contains("turn off") ||
                        lower.contains("on karo") || lower.contains("off karo") ||
                        lower.contains("chalu") || lower.contains("band") ||
                        lower.contains("jalao") || lower.contains("bujhao") || lower.contains("toggle"))) ||
                (lower.contains("लाइट") && (lower.contains("चालू") || lower.contains("बंद") || lower.contains("जलाओ") || lower.contains("बुझाओ")))
    }

    private fun isDeviceInfoCommand(lower: String): Boolean {
        return (lower.contains("battery") || lower.contains("बैटरी")) && (lower.contains("what") || lower.contains("percentage") || lower.contains("status") || lower.contains("kitna") || lower.contains("kitni") || lower.contains("level") || lower.contains("charge") || lower.contains("कितनी") || lower.contains("प्रतिशत")) ||
                (lower.contains("android") || lower.contains("एंड्रॉयड")) && (lower.contains("version") || lower.contains("what") || lower.contains("वर्जन")) ||
                (lower.contains("storage") || lower.contains("स्टोरेज")) && (lower.contains("free") || lower.contains("available") || lower.contains("kitna") || lower.contains("how much") || lower.contains("space") || lower.contains("खाली") || lower.contains("कितना")) ||
                (lower.contains("device") || lower.contains("डिवाइस")) && (lower.contains("what") || lower.contains("phone") || lower.contains("model") || lower.contains("kon sa") || lower.contains("कौन सा"))
    }

    private fun matchSettingsType(lower: String): String? {
        val isSettingIntent = lower.contains("setting") || lower.contains("kholo") || lower.contains("open ") || lower.contains("show ") ||
                lower.contains("सेटिंग") || lower.contains("सेटिंग्स") || lower.contains("खोलो")

        return when {
            lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("वाईफाई") || lower.contains("वाई-फाई") -> "wifi"
            lower.contains("bluetooth") || lower.contains("ब्लूटूथ") -> "bluetooth"
            lower.contains("battery setting") || lower.contains("बैटरी सेटिंग") || (lower.contains("battery") && isSettingIntent && !lower.contains("percentage") && !lower.contains("level") && !lower.contains("kitni")) -> "battery"
            lower.contains("display") || lower.contains("screen setting") || lower.contains("brightness") || lower.contains("डिस्प्ले") -> "display"
            lower.contains("sound") || lower.contains("volume setting") || lower.contains("ringtone") || lower.contains("साउंड") || lower.contains("रिंगटोन") -> "sound"
            lower.contains("notification") || lower.contains("नोटिफिकेशन") -> "notifications"
            lower.contains("security") || lower.contains("सुरक्षा") -> "security"
            lower.contains("privacy") || lower.contains("प्राइवेसी") -> "privacy"
            lower.contains("storage setting") || lower.contains("स्टोरेज सेटिंग") -> "storage"
            lower.contains("app setting") || lower.contains("application setting") || lower.contains("manage app") || lower.contains("ऐप सेटिंग") -> "apps"
            lower.contains("accessibility") || lower.contains("एक्सेसिबिलिटी") -> "accessibility"
            lower.contains("language") || lower.contains("locale") || lower.contains("भाषा") -> "language"
            lower.contains("date and time") || lower.contains("date & time") || lower.contains("time setting") || lower.contains("date setting") || lower.contains("समय सेटिंग") -> "datetime"
            lower.contains("developer option") || lower.contains("developer setting") || lower.contains("डेवलपर") -> "developer"
            lower.contains("about phone") || lower.contains("about device") || lower.contains("फोन के बारे में") -> "about_phone"
            lower.contains("mobile network") || lower.contains("cellular") || lower.contains("data roaming") || lower.contains("मोबाइल नेटवर्क") -> "network"
            lower.contains("internet setting") || lower.contains("इंटरनेट") -> "internet"
            lower.contains("hotspot") || lower.contains("tethering") || lower.contains("हॉटस्पॉट") -> "hotspot"
            lower.contains("vpn") || lower.contains("वीपीएन") -> "vpn"
            lower.contains("airplane") || lower.contains("flight mode") || lower.contains("एरोप्लेन") || lower.contains("फ्लाइट मोड") -> "airplane"
            lower.contains("dnd") || lower.contains("do not disturb") || lower.contains("zen mode") || lower.contains("डीएनडी") -> "dnd"
            lower == "open settings" || lower == "settings kholo" || lower == "settings" || lower == "device settings" || lower == "open device settings" ||
                    lower == "सेटिंग्स खोलो" || lower == "सेटिंग खोलो" || lower == "सेटिंग्स" -> "settings"
            else -> null
        }
    }

    private fun isAlarmCommand(lower: String): Boolean {
        return lower.contains("alarm") || lower.contains("wake me up") || lower.contains("अलार्म") ||
                (lower.contains("baje") && (lower.contains("uthana") || lower.contains("uthaye") || lower.contains("alarm"))) ||
                (lower.contains("बजे") && (lower.contains("उठाना") || lower.contains("अलार्म")))
    }

    private fun isTimerCommand(lower: String): Boolean {
        return lower.contains("timer") || lower.contains("countdown") || lower.contains("टाइमर")
    }

    private fun isCameraCommand(lower: String): Boolean {
        return (lower.contains("camera") || lower.contains("photo") || lower.contains("picture") ||
                lower.contains("gallery") || lower.contains("photos") || lower.contains("कैमरा") || lower.contains("गैलरी") || lower.contains("फोटो")) &&
                (lower.contains("open") || lower.contains("take") || lower.contains("kholo") || lower.contains("start") || lower.contains("launch") || lower.contains("खोलो") || lower.contains("चालू"))
    }

    private fun isFilesCommand(lower: String): Boolean {
        return (lower.contains("files") || lower.contains("file manager") || lower.contains("documents") || lower.contains("downloads") || lower.contains("फाइल्स") || lower.contains("डाउनलोड")) &&
                (lower.contains("open") || lower.contains("kholo") || lower.contains("show") || lower.contains("खोलो") || lower.contains("दिखाओ"))
    }

    private fun isPhoneOrCallCommand(lower: String): Boolean {
        return isDirectCallIntent(lower) || isDialerOpenCommand(lower)
    }

    private fun isSmsCommand(lower: String): Boolean {
        return lower.startsWith("send sms") || lower.startsWith("send a message") || lower.startsWith("text ") ||
                lower.contains("sms bhejo") || lower.contains("message bhejo") || lower.startsWith("sms to") ||
                lower.contains("एसएमएस भेजो") || lower.contains("मैसेज भेजो")
    }

    private fun isEmailCommand(lower: String): Boolean {
        return lower.contains("email") || lower.contains("mail to") || lower.contains("send email") || lower.contains("send mail") || lower.contains("mail bhejo") ||
                lower.contains("ईमेल") || lower.contains("मेल भेजो")
    }

    private fun isCreateContactCommand(lower: String): Boolean {
        return isSaveContactIntent(lower)
    }

    private fun isMapCommand(lower: String): Boolean {
        return lower.contains("map") || lower.contains("navigate") || lower.contains("directions") ||
                lower.contains("dikhao on map") || lower.contains("rasta") || lower.contains("where is") || lower.contains("location") ||
                lower.contains("मैप") || lower.contains("रास्ता") || lower.contains("लोकेशन")
    }

    private fun isCalendarCommand(lower: String): Boolean {
        return lower.contains("calendar") || lower.contains("schedule") || lower.contains("appointment") ||
                lower.contains("कैलेंडर") ||
                (lower.contains("event") && (lower.contains("create") || lower.contains("add") || lower.contains("set") || lower.contains("banao") || lower.contains("karo")))
    }

    private fun extractAlarmTime(lower: String): Pair<Int?, Int?> {
        val pat = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje|बजे)?", Pattern.CASE_INSENSITIVE)
        val m = pat.matcher(lower)
        while (m.find()) {
            val hourStr = m.group(1) ?: continue
            val minStr = m.group(2)
            val ampm = m.group(3)?.lowercase(Locale.ROOT)

            var hour = hourStr.toIntOrNull() ?: continue
            val minute = minStr?.toIntOrNull() ?: 0

            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (ampm == "baje" || ampm == "बजे" || ampm == null) {
                if (lower.contains("evening") || lower.contains("sham") || lower.contains("raat") || lower.contains("night") ||
                    lower.contains("शाम") || lower.contains("रात")) {
                    if (hour < 12) hour += 12
                }
            }
            if (hour in 0..23 && minute in 0..59) {
                return Pair(hour, minute)
            }
        }
        return Pair(null, null)
    }

    private fun extractAlarmTitle(prompt: String): String {
        val pat = Pattern.compile("(?:called|named|for|title|के\\s*लिए)\\s+[\"']?(.*?)[\"']?$", Pattern.CASE_INSENSITIVE)
        val m = pat.matcher(prompt)
        return if (m.find()) m.group(1)?.trim() ?: "Alarm" else "Alarm"
    }

    private fun extractTimerSeconds(lower: String): Int? {
        val minPat = Pattern.compile("(\\d+)\\s*(?:minutes|minute|min|m|minute\\s+ka|मिनट)", Pattern.CASE_INSENSITIVE)
        val secPat = Pattern.compile("(\\d+)\\s*(?:seconds|second|sec|s|सेकंड)", Pattern.CASE_INSENSITIVE)
        val hourPat = Pattern.compile("(\\d+)\\s*(?:hours|hour|hr|h|ghante|घंटे|घंटा)", Pattern.CASE_INSENSITIVE)

        var totalSeconds = 0
        var found = false

        val hm = hourPat.matcher(lower)
        if (hm.find()) {
            val h = hm.group(1)?.toIntOrNull() ?: 0
            totalSeconds += h * 3600
            found = true
        }

        val mm = minPat.matcher(lower)
        if (mm.find()) {
            val m = mm.group(1)?.toIntOrNull() ?: 0
            totalSeconds += m * 60
            found = true
        }

        val sm = secPat.matcher(lower)
        if (sm.find()) {
            val s = sm.group(1)?.toIntOrNull() ?: 0
            totalSeconds += s
            found = true
        }

        return if (found) totalSeconds else null
    }

    /**
     * Preprocesses and cleans raw voice/typed input text.
     */
    fun preprocessInput(raw: String): String {
        if (raw.isBlank()) return ""
        // 1. Unicode NFC normalization
        var cleaned = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)

        // 2. Remove question marks, exclamation marks, semicolons (preserve quotes and colons for message boundaries!)
        cleaned = cleaned.replace(Regex("[`?,!;]"), " ")

        // 3. Normalize multiple whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        // 4. Normalize common STT typos safely without breaking words
        cleaned = cleaned.replace(Regex("(?i)\\b(?:phon|fone)\\b"), "phone")
        cleaned = cleaned.replace(Regex("(?i)\\b(?:col|kall)\\b"), "call")
        cleaned = cleaned.replace(Regex("(?i)\\b(?:sav|seve|sev|saav)\\b"), "save")
        cleaned = cleaned.replace(Regex("(?i)\\bno\\.\\b"), "number ")
        cleaned = cleaned.replace(Regex("(?i)\\b(?:num|numbar)\\b"), "number")
        cleaned = cleaned.replace(Regex("(?i)\\bwhat['’]?s\\s*app\\b"), "whatsapp")
        cleaned = cleaned.replace(Regex("(?i)\\byou\\s*tube\\b"), "youtube")

        // 5. Strip leading wake word ("Doora", "Dura", "DooraGo", etc.) if followed by actual command
        val wakeWordMatch = Regex("^(?i)(?:(?:hey|ok|hello|hi)?\\s*(?:doora|dura|doorago|दोरा|दूरा)[\\s,:]+)(.+)").find(cleaned)
        if (wakeWordMatch != null) {
            val commandPortion = wakeWordMatch.groupValues[1].trim()
            if (commandPortion.isNotBlank()) {
                cleaned = commandPortion
            }
        }

        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Normalizes text for intent matching (lowercasing, verbal inflection normalization, etc.).
     */
    fun normalizeForIntentMatching(raw: String): String {
        if (raw.isBlank()) return ""
        var norm = raw.lowercase(Locale.ROOT)

        // Normalize Hindi/Hinglish verbal endings and STT variations
        norm = norm.replace(Regex("\\b(?:kar\\s+do|kardo|kar\\s+de|karde|kar\\s+dena|kar\\s+dijiye|kijiye|kijie|karna)\\b"), "karo")
        norm = norm.replace(Regex("\\b(?:laga\\s+do|lagado|laga\\s+de|lagade|laga\\s+dena|laga\\s+dijiye|lagana|lagaye|lagayein|lagao\\s+na)\\b"), "lagao")
        norm = norm.replace(Regex("\\b(?:mila\\s+do|milado|mila\\s+de|milade|milana|milaye|milayein|milao\\s+na)\\b"), "milao")
        norm = norm.replace(Regex("\\b(?:karwa\\s+do|karwado|karwa\\s+de|karwa\\s+dena|kara\\s+do|karado|kara\\s+de|karade|karaye|karayein|karao\\s+na|karwao)\\b"), "karao")
        norm = norm.replace(Regex("\\b(?:badha\\s+do|badhado|badha\\s+de|badhade|badhana)\\b"), "badhao")
        norm = norm.replace(Regex("\\b(?:kam\\s+kar\\s+do|kam\\s+kardo|kam\\s+kar\\s+de|kam\\s+karde|kam\\s+karna)\\b"), "kam karo")
        norm = norm.replace(Regex("\\b(?:phon|fone)\\b"), "phone")
        norm = norm.replace(Regex("\\b(?:col|kall)\\b"), "call")
        norm = norm.replace(Regex("\\b(?:diall)\\b"), "dial")
        norm = norm.replace(Regex("\\b(?:sav|seve|sev|saav)\\b"), "save")

        // Devanagari verbal endings
        norm = norm.replace(Regex("(?:कर\\s+दो|करदो|कर\\s+दे|करदे|कर\\s+देना|कीजिए|कीजिये|करना)"), "करो")
        norm = norm.replace(Regex("(?:लगा\\s+दो|लगादो|लगा\\s+दे|लगादे|लगा\\s+देना|लगाएं|लगाइए|लगाना)"), "लगाओ")
        norm = norm.replace(Regex("(?:खोल\\s+दो|खोलदो|खोल\\s+दे|खोलदे|खोलना|खोलिए|खोलिये)"), "खोलो")
        norm = norm.replace(Regex("(?:चालू\\s+कर\\s+दो|चालू\\s+करदो|चालू\\s+कर\\s+दे|चालू\\s+करना)"), "चालू करो")
        norm = norm.replace(Regex("(?:बंद\\s+कर\\s+दो|बंद\\s+करदो|बंद\\s+कर\\s+दे|बंद\\s+करना)"), "बंद करो")
        norm = norm.replace(Regex("(?:बढ़ा\\s+दो|बढ़ादो|बढ़ा\\s+दे|बढ़ाना|बढ़ाइए)"), "बढ़ाओ")
        norm = norm.replace(Regex("(?:कम\\s+कर\\s+दो|कम\\s+करदो|कम\\s+कर\\s+दे|कम\\s+करना|कम\\s+करिए)"), "कम करो")

        return norm.replace(Regex("\\s+"), " ").trim()
    }

    fun wordsToDigits(text: String): String {
        var result = text
        // Devanagari numerals ०-९
        val devanagariNumerals = mapOf(
            '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
            '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9'
        )
        val sb = StringBuilder()
        for (ch in result) {
            sb.append(devanagariNumerals[ch] ?: ch)
        }
        result = sb.toString()

        // Devanagari digit words
        val devanagariWords = mapOf(
            "शून्य" to "0", "एक" to "1", "दो" to "2", "तीन" to "3", "चार" to "4",
            "पांच" to "5", "पाँच" to "5", "छह" to "6", "छः" to "6", "सात" to "7",
            "आठ" to "8", "नौ" to "9"
        )
        for ((word, digit) in devanagariWords) {
            result = result.replace(Regex("(?i)\\b$word\\b"), digit)
        }

        // English digit words
        val englishWords = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "plus" to "+"
        )
        for ((word, digit) in englishWords) {
            result = result.replace(Regex("(?i)\\b$word\\b"), digit)
        }

        // Hindi transliterated digit words
        val hindiWords = mapOf(
            "shunya" to "0", "sunya" to "0", "ek" to "1", "teen" to "3", "chaar" to "4", "char" to "4",
            "paanch" to "5", "panch" to "5", "chhah" to "6", "chheh" to "6",
            "che" to "6", "chhe" to "6", "saat" to "7", "sat" to "7",
            "aath" to "8", "ath" to "8", "nau" to "9", "nou" to "9"
        )
        for ((word, digit) in hindiWords) {
            result = result.replace(Regex("(?i)\\b$word\\b"), digit)
        }

        // Safely replace Hindi "do" -> 2 only when not preceded by verbal helpers (kar/khol/bhej/laga/de/kr)
        result = result.replace(Regex("(?i)(?<!\\b(?:kar|khol|bhej|laga|kr|de)\\s)\\bdo\\b"), "2")

        return result
    }

    private fun extractPhoneNumberCandidate(prompt: String): String? {
        val patterns = listOf(
            // +91 or 0091 followed by digits with spaces/hyphens
            Pattern.compile("(?i)(?:\\+91|0091)[\\s\\-]?\\d[\\d\\s\\-]{8,}\\d"),
            // + followed by country code and digits
            Pattern.compile("(?i)\\+\\d{1,3}[\\s\\-]?\\(?\\d{2,4}\\)?[\\s\\-]?[\\d\\s\\-]{5,}\\d"),
            // Spaced individual digits e.g. "9 8 7 6 5 4 3 2 1 0" or "1 2 1" or "1 0 0"
            Pattern.compile("(?i)(?:\\d[\\s\\-]){2,}\\d"),
            // Formatted number with space or hyphen (e.g. "98765 43210", "9876-543-210")
            Pattern.compile("(?i)\\b\\d{2,5}[\\s\\-]\\d{2,5}(?:[\\s\\-]\\d{2,5})*\\b"),
            // Standard contiguous digit sequence (3 to 15 digits, supports 121, 100, 112, 108, 9876543210)
            Pattern.compile("(?i)\\b\\d{3,15}\\b"),
            // General phone number pattern with parentheses
            Pattern.compile("(?i)\\(?\\d{2,5}\\)?[\\s\\-]?\\d{3,5}[\\s\\-]\\d{3,5}")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(prompt)
            if (matcher.find()) {
                val candidate = matcher.group().trim()
                if (candidate.replace(Regex("[^0-9]"), "").length >= 3) {
                    return candidate
                }
            }
        }
        return null
    }

    fun classifyPhoneNumber(raw: String): PhoneNumberType? {
        val clean = raw.trim()
        val digits = clean.replace(Regex("[^0-9]"), "")
        if (digits.length < 3 || digits.length > 15) return null

        // 1. Short service / emergency numbers (3 to 6 digits, e.g. 100, 101, 108, 112, 121, 198, 139)
        if (digits.length in 3..6) {
            if (digits[0] in '1'..'9') {
                return PhoneNumberType.SHORT_SERVICE
            }
            return null
        }

        // 2. Indian Mobile (10 digits starting with 6-9, or +91 / 91 followed by 6-9)
        if (clean.startsWith("+91") && digits.length == 12 && digits.substring(2)[0] in '6'..'9') {
            return PhoneNumberType.INDIAN_MOBILE
        }
        if (!clean.startsWith("+") && digits.length == 12 && digits.startsWith("91") && digits[2] in '6'..'9') {
            return PhoneNumberType.INDIAN_MOBILE
        }
        if (!clean.startsWith("+") && digits.length == 10 && digits[0] in '6'..'9') {
            return PhoneNumberType.INDIAN_MOBILE
        }

        // 3. Standard Phone (7 to 15 digits)
        if (digits.length in 7..15 && digits[0] in '1'..'9') {
            return PhoneNumberType.STANDARD_PHONE
        }

        return null
    }

    fun normalizePhoneNumber(raw: String): String {
        var cleaned = raw.trim()
        val digitsOnly = cleaned.replace(Regex("[^0-9]"), "")
        val hasPlus = cleaned.startsWith("+")

        // Short / service numbers (3 to 6 digits) -> do not prepend +91
        if (digitsOnly.length in 3..6) {
            return digitsOnly
        }

        if (cleaned.startsWith("0091")) {
            cleaned = "+91" + cleaned.substring(4)
        } else if (cleaned.startsWith("00")) {
            cleaned = "+" + cleaned.substring(2)
        }

        return if (hasPlus) {
            "+$digitsOnly"
        } else {
            if (digitsOnly.length == 12 && digitsOnly.startsWith("91") && digitsOnly[2] in '6'..'9') {
                "+$digitsOnly"
            } else {
                digitsOnly
            }
        }
    }

    fun isValidPhoneNumber(normalized: String): Boolean {
        if (normalized.isBlank()) return false
        val digits = normalized.replace(Regex("[^0-9]"), "")
        if (digits.length !in 3..15) return false

        // Short service numbers (3 to 6 digits, e.g. 100, 101, 108, 112, 121, 198, 139)
        if (digits.length in 3..6) {
            return digits[0] in '1'..'9'
        }

        // Indian 10-digit mobile numbers start with 6, 7, 8, or 9
        if (digits.length == 10 && !normalized.startsWith("+")) {
            return digits[0] in '6'..'9'
        }
        if (normalized.startsWith("+91")) {
            val nationalPart = digits.substring(2)
            return nationalPart.length == 10 && nationalPart[0] in '6'..'9'
        }

        // Standard / Landline (7 to 15 digits)
        if (digits.length in 7..15) {
            if (digits.all { it == digits[0] }) return false
            return digits[0] in '1'..'9'
        }

        return false
    }

    private fun maskPhoneNumber(number: String): String {
        val digits = number.replace(Regex("[^0-9]"), "")
        // Short service/emergency numbers (3 to 6 digits like 121, 100, 112) remain unmasked
        if (digits.length in 3..6) {
            return digits
        }
        if (number.length <= 4) return "****"
        val prefix = if (number.startsWith("+91")) "+91 " else if (number.startsWith("+")) "+" else ""
        return if (digits.length >= 4) {
            val last4 = digits.takeLast(4)
            val maskedLen = (digits.length - 4).coerceAtLeast(2)
            val stars = "*".repeat(maskedLen)
            "$prefix$stars$last4"
        } else {
            "****"
        }
    }

    private fun isDirectCallIntent(lower: String): Boolean {
        // Query exclusion
        if (lower.startsWith("what is") || lower.startsWith("who is") || lower.contains("kya hai") ||
            lower.contains("tell me about") || lower.contains("meaning of") || lower.startsWith("search ")
        ) {
            return false
        }

        // Contact saving exclusion
        if (lower.startsWith("save ") || lower.contains("save contact") || lower.contains("create contact") ||
            lower.contains("add contact") || lower.contains("add to contacts") || lower.contains("contact banao") ||
            lower.contains("save karo")
        ) {
            return false
        }

        // SMS / Messaging exclusion
        if (lower.startsWith("send sms") || lower.startsWith("send message") || lower.startsWith("text ") ||
            lower.contains("sms bhejo") || lower.contains("message bhejo")
        ) {
            return false
        }

        // Alarm / Timer exclusion
        if (lower.contains("alarm") || lower.contains("timer")) {
            return false
        }

        val callPatterns = listOf(
            // English verb phrases
            Regex("(?i)\\b(?:call|dial|make a call to|ring)\\b"),
            Regex("(?i)^phone\\s+"),
            Regex("(?i)\\bcall\\s+this\\s+number\\b"),

            // Explicit Hindi/Hinglish calling combinations
            // "phone karo", "phone lagao", "phone milao", "call karo", "call lagao", "call milao", "dial karo", "dial lagao"
            Regex("(?i)\\b(?:phone|call|dial)\\s*(?:karo|lagao|milao|karao)\\b"),
            // "number lagao", "number milao", "number dial karo", "number dial"
            Regex("(?i)\\bnumber\\s*(?:lagao|milao|dial|dial\\s*karo)\\b"),
            // "pe phone karo", "ko phone lagao", "par call karo", "se baat karao", "ko call", "pe phone"
            Regex("(?i)\\b(?:pe|ko|par|se)\\s*(?:call|phone|dial|baat)\\s*(?:karo|lagao|milao|karao)?\\b"),
            // "is number pe call karo", "is number par phone lagao"
            Regex("(?i)\\b(?:is|iss|this)\\s+number\\s*(?:pe|ko|par)?\\s*(?:call|phone|dial)?\\s*(?:karo|lagao|milao)?\\b"),
            // "baat karao", "baat karwao", "baat karwana"
            Regex("(?i)\\bbaat\\s*(?:karao|karwao|karo)\\b"),

            // Devanagari explicit call intent phrases
            Regex("(?i)(?:कॉल|फोन|डायल)\\s*(?:करो|कीजिये|कीजिए|लगाओ|लगाएं|मिलाओ|करना|कराओ)"),
            Regex("(?i)नंबर\\s*(?:लगाओ|मिलाओ|डायल)"),
            Regex("(?i)(?:को|पर|पे|से)\\s*(?:कॉल|फोन|डायल|बात)"),
            Regex("(?i)बात\\s*(?:कराओ|करवाओ|करो)"),
            Regex("(?i)इस\\s+नंबर\\s*(?:पर|पे|को)?\\s*(?:कॉल|फोन|डायल)?\\s*(?:करो|लगाओ|मिलाओ)?")
        )

        return callPatterns.any { it.containsMatchIn(lower) }
    }

    private fun isDialerOpenCommand(lower: String): Boolean {
        val dialerPhrases = listOf(
            "open dialer", "dialer kholo", "dialer open karo", "open phone", "phone kholo",
            "phone open karo", "keypad kholo", "open keypad", "dial pad kholo", "open dial pad",
            "dialer", "keypad", "डायलर खोलो", "फोन खोलो", "कीपैड खोलो"
        )
        val trimmed = lower.trim()
        return dialerPhrases.any { trimmed == it || trimmed == "please $it" }
    }

    private fun extractContactNameForCall(prompt: String): String {
        return extractUniversalContactCandidate(prompt, normalizeForIntentMatching(prompt), intentHint = "CALL")
    }

    private fun extractSmsRecipient(prompt: String): String {
        return extractUniversalContactCandidate(prompt, normalizeForIntentMatching(prompt), intentHint = "SMS")
    }

    private fun extractSmsBody(prompt: String): String {
        val pat = Pattern.compile("(?:saying|body|message|text)\\s+[\"']?(.*?)[\"']?$", Pattern.CASE_INSENSITIVE)
        val m = pat.matcher(prompt)
        return if (m.find()) m.group(1)?.trim() ?: "" else ""
    }

    private fun extractEmailSubject(prompt: String): String? {
        val patterns = listOf(
            Pattern.compile("with\\s+subject\\s+[\"']?(.*?)[\"']?(?:\\s+and\\s+body|\\s+body|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("subject\\s+[\"']?(.*?)[\"']?(?:\\s+body|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("about\\s+[\"']?(.*?)[\"']?$", Pattern.CASE_INSENSITIVE)
        )
        for (pat in patterns) {
            val m = pat.matcher(prompt)
            if (m.find()) {
                val s = m.group(1)?.trim()
                if (!s.isNullOrBlank()) return s
            }
        }
        return null
    }

    private fun extractEmailBody(prompt: String): String {
        val pat = Pattern.compile("(?:with\\s+body|body|saying)\\s+[\"']?(.*?)[\"']?$", Pattern.CASE_INSENSITIVE)
        val m = pat.matcher(prompt)
        return if (m.find()) m.group(1)?.trim() ?: "" else ""
    }

    private fun extractEmailAddress(prompt: String): String? {
        val m = EMAIL_PATTERN.matcher(prompt)
        return if (m.find()) m.group() else null
    }

    private fun extractPhoneNumber(prompt: String): String? {
        val converted = wordsToDigits(prompt)
        val candidate = extractPhoneNumberCandidate(converted)
        if (candidate != null) {
            val normalized = normalizePhoneNumber(candidate)
            if (isValidPhoneNumber(normalized)) {
                return normalized
            }
        }
        val m = PHONE_PATTERN.matcher(prompt)
        return if (m.find()) m.group().trim() else null
    }

    private fun extractContactName(prompt: String): Pair<String, String> {
        val patterns = listOf(
            Pattern.compile("(?:named|contact|add)\\s+([a-zA-Z]+)(?:\\s+([a-zA-Z]+))?(?:\\s+with|\\s+phone|\\s+number|\\s+\\d|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("create\\s+contact\\s+([a-zA-Z]+)(?:\\s+([a-zA-Z]+))?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("add\\s+([a-zA-Z]+)(?:\\s+([a-zA-Z]+))?\\s+to\\s+contacts", Pattern.CASE_INSENSITIVE)
        )
        for (pat in patterns) {
            val m = pat.matcher(prompt)
            if (m.find()) {
                val first = m.group(1)?.trim() ?: ""
                val second = m.group(2)?.trim() ?: ""
                val sanitizedSecond = if (second.equals("with", true) || second.equals("phone", true) ||
                    second.equals("number", true) || second.equals("to", true) || second.equals("my", true)) "" else second
                if (first.isNotBlank() && !first.equals("a", true) && !first.equals("new", true)) {
                    return Pair(first, sanitizedSecond)
                }
            }
        }
        return Pair("", "")
    }

    private fun extractMapQuery(prompt: String): String? {
        val patterns = listOf(
            Pattern.compile("(?:navigate|directions)\\s+(?:to\\s+)?(.*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("show\\s+(?:the\\s+)?map\\s+(?:for|of|to)\\s+(.*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("show\\s+(.*)\\s+on\\s+(?:the\\s+)?map", Pattern.CASE_INSENSITIVE),
            Pattern.compile("show\\s+(?:the\\s+)?map\\s+(.*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(.*)\\s+(?:on\\s+)?map\\s+(?:dikhao|dikhaye|kholo)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(.*)\\s+(?:dikhao|dikhaye)\\s+on\\s+map", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(.*)\\s+(?:ka\\s+)?rasta\\s+dikhao", Pattern.CASE_INSENSITIVE),
            Pattern.compile("where\\s+is\\s+(.*)", Pattern.CASE_INSENSITIVE)
        )
        for (pat in patterns) {
            val m = pat.matcher(prompt)
            if (m.find()) {
                val q = m.group(1)?.trim()
                if (!q.isNullOrBlank() && !q.equals("map", true) && !q.equals("the map", true)) {
                    return q
                }
            }
        }
        if (prompt.startsWith("Show ", ignoreCase = true) && !prompt.equals("Show map", ignoreCase = true)) {
            val rest = prompt.substring(5).trim()
            if (rest.isNotBlank()) return rest
        }
        return null
    }

    private fun extractCalendarTitle(prompt: String): String {
        val patterns = listOf(
            Pattern.compile("(?:title|named|called|for)\\s+[\"']?(.*?)[\"']?(?:\\s+tomorrow|\\s+today|\\s+at|\\s+on|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("create\\s+(?:a\\s+)?calendar\\s+event\\s+[\"']?(.*?)[\"']?(?:\\s+tomorrow|\\s+today|\\s+at|\\s+on|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("schedule\\s+(?:a\\s+)?[\"']?(.*?)[\"']?(?:\\s+tomorrow|\\s+today|\\s+at|\\s+on|$)", Pattern.CASE_INSENSITIVE)
        )
        for (pat in patterns) {
            val m = pat.matcher(prompt)
            if (m.find()) {
                val t = m.group(1)?.trim() ?: ""
                if (t.isNotBlank() && !t.equals("event", true) && !t.equals("meeting", true)) {
                    return t
                }
            }
        }
        return "Calendar event"
    }

    fun parseRelativeDateTime(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        val now = LocalDateTime.now()

        var targetDate: LocalDate = now.toLocalDate()
        var dateFound = false

        if (lower.contains("day after tomorrow")) {
            targetDate = targetDate.plusDays(2)
            dateFound = true
        } else if (lower.contains("tomorrow") || lower.contains("kal")) {
            targetDate = targetDate.plusDays(1)
            dateFound = true
        } else if (lower.contains("today") || lower.contains("aaj")) {
            dateFound = true
        } else {
            val daysOfWeek = mapOf(
                "monday" to DayOfWeek.MONDAY,
                "tuesday" to DayOfWeek.TUESDAY,
                "wednesday" to DayOfWeek.WEDNESDAY,
                "thursday" to DayOfWeek.THURSDAY,
                "friday" to DayOfWeek.FRIDAY,
                "saturday" to DayOfWeek.SATURDAY,
                "sunday" to DayOfWeek.SUNDAY
            )
            for ((dayName, dayEnum) in daysOfWeek) {
                if (lower.contains(dayName)) {
                    targetDate = targetDate.with(TemporalAdjusters.next(dayEnum))
                    dateFound = true
                    break
                }
            }
        }

        val monthPattern = Pattern.compile("(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\s+(\\d{1,2})", Pattern.CASE_INSENSITIVE)
        val monthMatcher = monthPattern.matcher(lower)
        if (monthMatcher.find()) {
            val monthStr = monthMatcher.group(1) ?: ""
            val dayNum = monthMatcher.group(2)?.toIntOrNull() ?: 1
            val monthVal = parseMonth(monthStr)
            if (monthVal != null) {
                val year = if (monthVal < now.monthValue || (monthVal == now.monthValue && dayNum < now.dayOfMonth)) now.year + 1 else now.year
                try {
                    targetDate = LocalDate.of(year, monthVal, dayNum)
                    dateFound = true
                } catch (_: Exception) {}
            }
        }

        var targetTime: LocalTime = LocalTime.of(9, 0)
        var timeFound = false

        val timePattern = Pattern.compile("(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje)?", Pattern.CASE_INSENSITIVE)
        val timeMatcher = timePattern.matcher(lower)
        while (timeMatcher.find()) {
            val hourStr = timeMatcher.group(1) ?: continue
            val minStr = timeMatcher.group(2)
            val ampm = timeMatcher.group(3)

            var hour = hourStr.toIntOrNull() ?: continue
            val minute = minStr?.toIntOrNull() ?: 0

            if (ampm != null) {
                if (ampm.equals("pm", ignoreCase = true) && hour < 12) hour += 12
                if (ampm.equals("am", ignoreCase = true) && hour == 12) hour = 0
                targetTime = LocalTime.of(hour, minute, 0)
                timeFound = true
                break
            } else if (lower.contains("at $hourStr") || lower.contains("$hourStr baje")) {
                if (hour < 8) hour += 12
                targetTime = LocalTime.of(hour, minute, 0)
                timeFound = true
                break
            }
        }

        if (dateFound || timeFound) {
            val targetDateTime = LocalDateTime.of(targetDate, targetTime)
            return targetDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }

        return null
    }

    private fun parseMonth(monthStr: String): Int? {
        val m = monthStr.lowercase(Locale.ROOT)
        return when {
            m.startsWith("jan") -> 1
            m.startsWith("feb") -> 2
            m.startsWith("mar") -> 3
            m.startsWith("apr") -> 4
            m.startsWith("may") -> 5
            m.startsWith("jun") -> 6
            m.startsWith("jul") -> 7
            m.startsWith("aug") -> 8
            m.startsWith("sep") -> 9
            m.startsWith("oct") -> 10
            m.startsWith("nov") -> 11
            m.startsWith("dec") -> 12
            else -> null
        }
    }
}
