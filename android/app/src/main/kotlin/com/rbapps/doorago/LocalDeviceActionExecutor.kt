package com.rbapps.doorago

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Local Offline Device Action Executor for DooraGo.
 *
 * Implements 100% offline, safe Android intents, generic app launching, direct calling, and system queries across
 * all categories with zero cloud dependencies and zero model tokens.
 */
class LocalDeviceActionExecutor(
    private val context: Context,
    private val activityProvider: (() -> Activity?)? = null
) {

    companion object {
        private const val TAG = "LocalDeviceExecutor"
        private const val NATIVE_TAG = "DooraGoNative"
        const val PERMISSION_REQUEST_CALL_PHONE = 1002
        const val PERMISSION_REQUEST_READ_CONTACTS = 1003
    }

    private fun safeStartActivity(intent: Intent, actionName: String, successMsg: String, fallbackMsg: String): String {
        val isUiVisible = MainActivity.isUiVisible
        val assistantRole = DooraVoiceInteractionService.isRoleHeld(context)
        Log.i(NATIVE_TAG, "[APP_LAUNCH] action=$actionName uiVisible=$isUiVisible assistantRole=$assistantRole")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // Always attempt direct startActivity first
        val directSuccess = try {
            Log.i(NATIVE_TAG, "[ANDROID_ACTION] action=${intent.action ?: actionName}")
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "[ACTION_RESULT] direct launch failed for $actionName: ${e.localizedMessage}")
            false
        }

        if (directSuccess) {
            val pkg = intent.component?.packageName ?: actionName
            Log.i(NATIVE_TAG, "[APP_LAUNCH] package=$pkg foreground=$isUiVisible assistantRole=$assistantRole directLaunchAttempt=true notificationFallback=false notificationPosted=false result=SUCCESS")
            return successMsg
        }

        val displayLabel = actionName.replace("open", "").replace("launch", "").trim().ifBlank { actionName }
        val notificationPosted = appResolver.postBackgroundLaunchNotification("com.android.settings", displayLabel, intent)

        return if (notificationPosted) {
            Log.i(NATIVE_TAG, "[APP_LAUNCH] action=$actionName foreground=$isUiVisible assistantRole=$assistantRole directLaunchAttempt=true notificationFallback=true notificationPosted=true result=FALLBACK")
            "Tap notification to open $displayLabel."
        } else {
            Log.i(NATIVE_TAG, "[APP_LAUNCH] action=$actionName foreground=$isUiVisible assistantRole=$assistantRole directLaunchAttempt=true notificationFallback=true notificationPosted=false result=BLOCKED")
            "$displayLabel cannot be opened automatically while DooraGo is in the background. Please allow notifications or set DooraGo as your assistant."
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Category 4: Generic Installed-App Launcher via PackageManager
    // ──────────────────────────────────────────────────────────────

    val appResolver = InstalledAppResolver(context)

    fun launchApp(rawQueryAppName: String): String {
        Log.i(NATIVE_TAG, "[COMMAND_ROUTE] type=APP_LAUNCH")
        Log.i(NATIVE_TAG, "[APP_RESOLVE] requested='$rawQueryAppName'")

        val cleanQuery = sanitizeAppName(rawQueryAppName)

        if (cleanQuery.isEmpty()) {
            return "Please specify which app you want to open."
        }

        // 1. Direct system feature aliases
        when (cleanQuery) {
            "camera", "कैमरा" -> {
                Log.i(NATIVE_TAG, "[APP_RESOLVE] matched='Camera'")
                Log.i(NATIVE_TAG, "[APP_LAUNCH] feature=CAMERA")
                return openCamera()
            }
            "gallery", "photos", "photo", "गैलरी", "फोटो" -> {
                Log.i(NATIVE_TAG, "[APP_RESOLVE] matched='Gallery'")
                Log.i(NATIVE_TAG, "[APP_LAUNCH] feature=GALLERY")
                return openGallery()
            }
            "clock", "alarms", "alarm", "timer", "घड़ी", "अलार्म" -> {
                Log.i(NATIVE_TAG, "[APP_RESOLVE] matched='Clock'")
                Log.i(NATIVE_TAG, "[APP_LAUNCH] feature=CLOCK")
                return openClock()
            }
            "files", "file manager", "downloads", "my files", "फाइल्स", "डाउनलोड" -> {
                Log.i(NATIVE_TAG, "[APP_RESOLVE] matched='Files'")
                Log.i(NATIVE_TAG, "[APP_LAUNCH] feature=FILES")
                return openFiles()
            }
            "phone", "dialer", "call", "फोन", "डायलर" -> {
                Log.i(NATIVE_TAG, "[APP_RESOLVE] matched='Phone'")
                Log.i(NATIVE_TAG, "[APP_LAUNCH] feature=PHONE")
                return openPhone()
            }
            "contacts", "contact", "संपर्क", "कांटेक्ट" -> {
                Log.i(NATIVE_TAG, "[APP_RESOLVE] matched='Contacts'")
                Log.i(NATIVE_TAG, "[APP_LAUNCH] feature=CONTACTS")
                return openContacts()
            }
            "settings", "सेटिंग", "सेटिंग्स" -> {
                Log.i(NATIVE_TAG, "[APP_RESOLVE] matched='Settings'")
                Log.i(NATIVE_TAG, "[APP_LAUNCH] feature=SETTINGS")
                return openSettings("settings")
            }
        }

        // 2. Delegate to InstalledAppResolver
        return when (val res = appResolver.resolveApp(cleanQuery)) {
            is InstalledAppResolver.AppLaunchResolution.Launch -> res.action()
            is InstalledAppResolver.AppLaunchResolution.DisambiguationNeeded -> res.prompt
            is InstalledAppResolver.AppLaunchResolution.ErrorOrNotFound -> res.message
        }
    }

    /**
     * Returns a list of all launchable installed apps on the device discovered via PackageManager.
     * Used by the offline "Choose an installed app" picker UI.
     */
    fun getLaunchableApps(): List<Map<String, String>> {
        return appResolver.getInstalledLaunchableApps().map {
            mapOf("label" to it.appLabel, "packageName" to it.packageName)
        }.sortedBy { (it["label"] ?: "").lowercase(Locale.ROOT) }
    }

    private fun sanitizeAppName(raw: String): String {
        var clean = raw.lowercase(Locale.ROOT).trim()

        // Strip common prefixes (English + Hinglish + Devanagari)
        val prefixes = listOf(
            "open the app ", "open app ", "open the ", "open ",
            "launch the app ", "launch app ", "launch ", "start ",
            "please open ", "can you open ", "go to ",
            "ऐप खोलो ", "ऐप चालू करो ", "एप खोलो ", "खोलो "
        )
        for (prefix in prefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.substring(prefix.length).trim()
            }
        }

        // Strip common suffixes (English + Hinglish + Devanagari)
        val suffixes = listOf(
            " kholo", " khol do", " khol de", " kholna", " kholiye",
            " open karo", " open kar do", " open kardo", " open kar",
            " chalu karo", " chalu kar do", " chalu kar", " chalao", " start karo",
            " launch karo", " shuru karo", " app", " application", " ko kholo", " ko open karo",
            " खोलो", " खोल दो", " खोल दे", " खोलिये", " खोलिए", " चालू करो", " चालू कर दो",
            " चलाओ", " शुरू करो", " ओपन करो", " स्टार्ट करो", " ऐप", " एप", " को खोलो", " को चालू करो"
        )
        for (suffix in suffixes) {
            if (clean.endsWith(suffix)) {
                clean = clean.substring(0, clean.length - suffix.length).trim()
            }
        }

        return clean.trim()
    }

    private fun similarityRatio(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return 1.0 - (dist.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[m][n]
    }

    // ──────────────────────────────────────────────────────────────
    // Category 1 & 14: Settings & Device Navigation
    // ──────────────────────────────────────────────────────────────

    fun openSettings(type: String): String {
        val (intent, title) = when (type.lowercase(Locale.ROOT)) {
            "wifi" -> Pair(Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings")
            "bluetooth" -> Pair(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth settings")
            "battery" -> Pair(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                "Battery settings"
            )
            "display" -> Pair(Intent(Settings.ACTION_DISPLAY_SETTINGS), "Display settings")
            "sound", "volume" -> Pair(Intent(Settings.ACTION_SOUND_SETTINGS), "Sound settings")
            "notifications" -> Pair(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                } else Intent(Settings.ACTION_SETTINGS),
                "Notification settings"
            )
            "security" -> Pair(Intent(Settings.ACTION_SECURITY_SETTINGS), "Security settings")
            "privacy" -> Pair(
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                "Privacy settings"
            )
            "storage" -> Pair(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), "Storage settings")
            "apps" -> Pair(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), "App settings")
            "accessibility" -> Pair(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Accessibility settings")
            "language" -> Pair(Intent(Settings.ACTION_LOCALE_SETTINGS), "Language settings")
            "datetime", "date", "time" -> Pair(Intent(Settings.ACTION_DATE_SETTINGS), "Date & time settings")
            "developer" -> Pair(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), "Developer options")
            "about", "about_phone" -> Pair(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), "About phone")
            "network", "mobile_network" -> Pair(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS), "Mobile network settings")
            "internet" -> Pair(
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                "Internet settings"
            )
            "hotspot", "tethering" -> Pair(
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                "Hotspot & tethering settings"
            )
            "vpn" -> Pair(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Intent(Settings.ACTION_VPN_SETTINGS)
                else Intent(Settings.ACTION_WIRELESS_SETTINGS),
                "VPN settings"
            )
            "airplane" -> Pair(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "Airplane mode settings")
            "dnd", "zen" -> Pair(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                else Intent(Settings.ACTION_SOUND_SETTINGS),
                "Do Not Disturb settings"
            )
            else -> Pair(Intent(Settings.ACTION_SETTINGS), "Device settings")
        }

        return safeStartActivity(
            intent = intent,
            actionName = "openSettings($type)",
            successMsg = "$title opened.",
            fallbackMsg = "I couldn't open $title."
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Category 5: Phone & Contacts
    // ──────────────────────────────────────────────────────────────

    fun directCall(phoneNumber: String): String {
        val cleanNumber = normalizePhoneNumber(phoneNumber)
        val masked = maskPhoneNumber(cleanNumber)
        val digitsCount = cleanNumber.replace(Regex("[^0-9]"), "").length
        val numberType = classifyPhoneNumber(cleanNumber)
        val typeLabel = numberType?.name ?: "UNKNOWN"
        Log.i(NATIVE_TAG, "[CALL_DETECT] intent=DIRECT_CALL valid_number=${isValidPhoneNumber(cleanNumber)} digits=$digitsCount numberType=$typeLabel")

        if (!isValidPhoneNumber(cleanNumber)) {
            Log.w(NATIVE_TAG, "[CALL_VALIDATE] valid=false digits=$digitsCount")
            return "Please provide a valid phone number."
        }
        Log.i(NATIVE_TAG, "[CALL_VALIDATE] valid=true digits=$digitsCount numberType=$typeLabel")

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        Log.i(NATIVE_TAG, "[CALL_PERMISSION] granted=$hasPermission")

        if (!hasPermission) {
            val activity = activityProvider?.invoke()
            if (activity != null) {
                if (activity is MainActivity) {
                    activity.setPendingCall(cleanNumber)
                }
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.CALL_PHONE),
                    PERMISSION_REQUEST_CALL_PHONE
                )
            }
            return "DooraGo needs phone permission to place a call when you explicitly ask it to."
        }

        return executeDirectCallIntent(cleanNumber)
    }

    fun executeDirectCallIntent(cleanNumber: String): String {
        val masked = maskPhoneNumber(cleanNumber)
        Log.i(NATIVE_TAG, "[CALL_EXECUTE] action=ACTION_CALL number=$masked")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(cleanNumber)}"))
        return safeStartActivity(
            intent,
            "directCall($masked)",
            "Calling $cleanNumber",
            "Couldn't place the phone call."
        )
    }

    fun openPhone(): String {
        Log.i(NATIVE_TAG, "[CALL_DETECT] intent=OPEN_DIALER")
        Log.i(NATIVE_TAG, "[CALL_EXECUTE] action=ACTION_DIAL")
        val intent = Intent(Intent.ACTION_DIAL)
        return safeStartActivity(intent, "openPhone", "Opening dialer.", "Couldn't open phone dialer.")
    }

    fun dialNumber(phoneNumber: String): String {
        val cleanNumber = normalizePhoneNumber(phoneNumber)
        val masked = maskPhoneNumber(cleanNumber)
        Log.i(NATIVE_TAG, "[CALL_DETECT] intent=OPEN_DIALER_WITH_NUMBER")
        Log.i(NATIVE_TAG, "[CALL_EXECUTE] action=ACTION_DIAL number=$masked")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(cleanNumber)}"))
        return safeStartActivity(intent, "dialNumber($masked)", "Dialer opened for $cleanNumber.", "Couldn't open phone dialer.")
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

    fun normalizePhoneNumber(phoneNumber: String): String {
        var cleaned = phoneNumber.trim()
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

    fun maskPhoneNumber(number: String): String {
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

    // ──────────────────────────────────────────────────────────────
    // Contact Name Calling & Search via ContactsContract
    // ──────────────────────────────────────────────────────────────

    data class ContactPhoneInfo(
        val number: String,
        val typeLabel: String
    )

    data class ContactRecord(
        val contactId: String,
        val displayName: String,
        val phones: MutableList<ContactPhoneInfo> = mutableListOf()
    )

    data class ContactMatchCandidate(
        val contact: ContactRecord,
        val score: Double,
        val matchType: String
    )

    sealed class ContactCallResolution {
        data class DirectCall(val contact: ContactRecord, val action: () -> String) : ContactCallResolution()
        data class DisambiguationNeeded(val originalQuery: String, val candidates: List<ContactRecord>, val prompt: String) : ContactCallResolution()
        data class ErrorOrNotice(val message: String) : ContactCallResolution()
    }

    sealed class ContactMessageResolution {
        data class Resolved(val contact: ContactRecord, val phoneNumber: String) : ContactMessageResolution()
        data class DisambiguationNeeded(val originalQuery: String, val candidates: List<ContactRecord>, val prompt: String) : ContactMessageResolution()
        data class ErrorOrNotice(val message: String) : ContactMessageResolution()
    }

    sealed class DisambiguationSelectionResult {
        data class Selected(val contact: ContactRecord) : DisambiguationSelectionResult()
        data class OutOfBounds(val index: Int) : DisambiguationSelectionResult()
        object AffirmationOnly : DisambiguationSelectionResult()
        object Unrecognized : DisambiguationSelectionResult()
    }

    fun buildContactDisambiguationPrompt(
        query: String,
        candidates: List<ContactRecord>,
        actionVerb: String = "call"
    ): String {
        val displayQuery = query.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val targetPhrase = if (displayQuery.isNotBlank() && !displayQuery.equals("contact", ignoreCase = true)) {
            displayQuery
        } else {
            "contact"
        }

        val question = when (actionVerb.lowercase(Locale.ROOT)) {
            "call" -> "Kaunse $targetPhrase ko call karna hai?"
            "whatsapp" -> "Kaunse $targetPhrase ko WhatsApp message bhejna hai?"
            "sms" -> "Kaunse $targetPhrase ko SMS bhejna hai?"
            else -> "Kaunse $targetPhrase ko message bhejna hai?"
        }

        val options = candidates.mapIndexed { index, cand ->
            "${index + 1}. ${cand.displayName}"
        }.joinToString("\n")

        return "$question\n$options"
    }

    fun resolveContactForMessaging(rawQuery: String, actionVerb: String = "message"): ContactMessageResolution {
        val cleanQuery = sanitizeContactQuery(rawQuery)
        Log.i(NATIVE_TAG, "[RESOLVE_MESSAGE_CONTACT] rawInput='$rawQuery' extractedName='$cleanQuery' verb=$actionVerb")

        if (cleanQuery.isBlank()) {
            return ContactMessageResolution.ErrorOrNotice("Please tell me which contact you want to message.")
        }

        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            val activity = activityProvider?.invoke()
            if (activity != null) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_CONTACTS),
                    PERMISSION_REQUEST_READ_CONTACTS
                )
            }
            return ContactMessageResolution.ErrorOrNotice("To send a message to a saved contact, DooraGo needs access to your contacts.")
        }

        val allContacts = loadAllContactsWithPhones()
        if (allContacts.isEmpty()) {
            return ContactMessageResolution.ErrorOrNotice("I couldn't find any contacts with phone numbers on this phone.")
        }

        val matchedCandidates = matchContacts(cleanQuery, allContacts)
        if (matchedCandidates.isEmpty()) {
            val displayTarget = cleanQuery.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            return ContactMessageResolution.ErrorOrNotice("I couldn't find '$displayTarget' in your contacts.")
        }

        val topMatch = matchedCandidates[0]

        val isExactWinner = topMatch.score >= 0.99
        val hasClearMargin = if (matchedCandidates.size > 1) {
            val secondMatch = matchedCandidates[1]
            (topMatch.score >= 0.88 && (topMatch.score - secondMatch.score) >= 0.08)
        } else {
            topMatch.score >= 0.65
        }

        if (!isExactWinner && !hasClearMargin) {
            val closeMatches = matchedCandidates.filter { it.score >= 0.60 && it.score >= (topMatch.score - 0.08) }
            val distinctCandidates = closeMatches.map { it.contact }.distinctBy { it.displayName }
            if (distinctCandidates.size > 1) {
                val topCandidates = distinctCandidates.take(4)
                val prompt = buildContactDisambiguationPrompt(cleanQuery, topCandidates, actionVerb)
                Log.i(NATIVE_TAG, "[RESOLVE_MESSAGE_CONTACT] disambiguation_needed candidates=${topCandidates.map { it.displayName }}")
                return ContactMessageResolution.DisambiguationNeeded(
                    originalQuery = cleanQuery,
                    candidates = topCandidates,
                    prompt = prompt
                )
            }
        }

        val contact = topMatch.contact
        val validPhones = contact.phones.filter { isValidPhoneNumber(normalizePhoneNumber(it.number)) }

        if (validPhones.isEmpty()) {
            return ContactMessageResolution.ErrorOrNotice("${contact.displayName} does not have a valid phone number saved.")
        }

        val normalized = normalizePhoneNumber(validPhones[0].number)
        return ContactMessageResolution.Resolved(contact, normalized)
    }

    fun resolveContactCall(rawQuery: String): ContactCallResolution {
        val cleanQuery = sanitizeContactQuery(rawQuery)
        Log.i(NATIVE_TAG, "[CALL_CONTACT] rawInput='$rawQuery' extractedName='$cleanQuery'")

        if (cleanQuery.isBlank()) {
            return ContactCallResolution.ErrorOrNotice("Please tell me which contact you want to call.")
        }

        // Check READ_CONTACTS runtime permission
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        Log.i(NATIVE_TAG, "[CALL_CONTACT_PERMISSION] granted=$hasContactsPermission")

        if (!hasContactsPermission) {
            val activity = activityProvider?.invoke()
            if (activity != null) {
                if (activity is MainActivity) {
                    activity.setPendingContactCall(cleanQuery)
                }
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_CONTACTS),
                    PERMISSION_REQUEST_READ_CONTACTS
                )
            }
            return ContactCallResolution.ErrorOrNotice("To call a saved contact by name, DooraGo needs access to your contacts.")
        }

        val allContacts = loadAllContactsWithPhones()
        if (allContacts.isEmpty()) {
            Log.i(NATIVE_TAG, "[CALL_CONTACT] contactsDb=empty")
            return ContactCallResolution.ErrorOrNotice("I couldn't find any contacts with phone numbers on this phone.")
        }

        val matchedCandidates = matchContacts(cleanQuery, allContacts)
        if (matchedCandidates.isEmpty()) {
            Log.i(NATIVE_TAG, "[CALL_CONTACT] matchedContact=null")
            val displayTarget = cleanQuery.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            return ContactCallResolution.ErrorOrNotice("I couldn't find '$displayTarget' in your contacts.")
        }

        for (cand in matchedCandidates.take(5)) {
            Log.i(NATIVE_TAG, "[CALL_CONTACT_MATCH] name='${cand.contact.displayName}' score=${String.format(Locale.ROOT, "%.2f", cand.score)} type=${cand.matchType}")
        }

        val topMatch = matchedCandidates[0]
        Log.i(NATIVE_TAG, "[CALL_CONTACT] topMatch='${topMatch.contact.displayName}' matchConfidence=${String.format(Locale.ROOT, "%.2f", topMatch.score)} phoneCount=${topMatch.contact.phones.size}")

        // 1. If exact or normalized exact match, or top score is high with sufficient margin -> Single Winner
        val isExactWinner = topMatch.score >= 0.99
        val hasClearMargin = if (matchedCandidates.size > 1) {
            val secondMatch = matchedCandidates[1]
            (topMatch.score >= 0.88 && (topMatch.score - secondMatch.score) >= 0.08)
        } else {
            topMatch.score >= 0.65
        }

        if (!isExactWinner && !hasClearMargin) {
            // Check for close ambiguous multiple candidates
            val closeMatches = matchedCandidates.filter { it.score >= 0.60 && it.score >= (topMatch.score - 0.08) }
            val distinctCandidates = closeMatches.map { it.contact }.distinctBy { it.displayName }
            if (distinctCandidates.size > 1) {
                val topCandidates = distinctCandidates.take(4)
                val prompt = buildContactDisambiguationPrompt(cleanQuery, topCandidates, "call")
                Log.i(NATIVE_TAG, "[CALL_CONTACT] disambiguation_needed collision_count=${distinctCandidates.size} candidates=${topCandidates.map { it.displayName }}")
                return ContactCallResolution.DisambiguationNeeded(
                    originalQuery = cleanQuery,
                    candidates = topCandidates,
                    prompt = prompt
                )
            }
        }

        val contact = topMatch.contact
        val validPhones = contact.phones.filter { isValidPhoneNumber(normalizePhoneNumber(it.number)) }

        if (validPhones.isEmpty()) {
            return ContactCallResolution.ErrorOrNotice("${contact.displayName} does not have a valid phone number saved.")
        }

        if (validPhones.size == 1) {
            val phone = validPhones[0]
            val normalized = normalizePhoneNumber(phone.number)
            Log.i(NATIVE_TAG, "[CALL_CONTACT] selectedPhoneType=${phone.typeLabel} action=ACTION_CALL")
            return ContactCallResolution.DirectCall(contact) {
                directCall(normalized)
            }
        }

        // Multiple numbers for this contact - check if query had a preference ("mobile", "home", "work")
        val lowerRaw = rawQuery.lowercase(Locale.ROOT)
        val preferred = when {
            lowerRaw.contains("mobile") || lowerRaw.contains("cell") -> validPhones.firstOrNull { it.typeLabel.equals("Mobile", ignoreCase = true) }
            lowerRaw.contains("home") || lowerRaw.contains("ghar") -> validPhones.firstOrNull { it.typeLabel.equals("Home", ignoreCase = true) }
            lowerRaw.contains("work") || lowerRaw.contains("office") -> validPhones.firstOrNull { it.typeLabel.equals("Work", ignoreCase = true) }
            else -> null
        }

        if (preferred != null) {
            val normalized = normalizePhoneNumber(preferred.number)
            Log.i(NATIVE_TAG, "[CALL_CONTACT] preferredType=${preferred.typeLabel} action=ACTION_CALL")
            return ContactCallResolution.DirectCall(contact) {
                directCall(normalized)
            }
        }

        val options = validPhones.joinToString(", ") { "${it.typeLabel}: ${maskPhoneNumber(normalizePhoneNumber(it.number))}" }
        Log.i(NATIVE_TAG, "[CALL_CONTACT] multiple_numbers_prompt count=${validPhones.size}")
        return ContactCallResolution.ErrorOrNotice("${contact.displayName} has ${validPhones.size} numbers ($options). Which one should I call?")
    }

    fun callContactByName(rawQuery: String): String {
        return when (val res = resolveContactCall(rawQuery)) {
            is ContactCallResolution.DirectCall -> res.action()
            is ContactCallResolution.DisambiguationNeeded -> res.prompt
            is ContactCallResolution.ErrorOrNotice -> res.message
        }
    }

    private fun loadAllContactsWithPhones(): List<ContactRecord> {
        val contactsMap = mutableMapOf<String, ContactRecord>()
        val contentResolver = context.contentResolver

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )

        val cursor = try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts: ${e.message}", e)
            null
        }

        cursor?.use {
            val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            val labelIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

            while (it.moveToNext()) {
                val id = if (idIdx >= 0) it.getString(idIdx) ?: "" else ""
                val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                val number = if (numberIdx >= 0) it.getString(numberIdx) ?: "" else ""
                val typeInt = if (typeIdx >= 0) it.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                val customLabel = if (labelIdx >= 0) it.getString(labelIdx) else null

                if (name.isNotBlank() && number.isNotBlank()) {
                    val record = contactsMap.getOrPut(id.ifBlank { name }) {
                        ContactRecord(contactId = id, displayName = name.trim())
                    }

                    val typeLabel = when (typeInt) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
                        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> customLabel ?: "Custom"
                        else -> "Other"
                    }

                    if (record.phones.none { it.number.replace(Regex("[^0-9]"), "") == number.replace(Regex("[^0-9]"), "") }) {
                        record.phones.add(ContactPhoneInfo(number = number.trim(), typeLabel = typeLabel))
                    }
                }
            }
        }

        return contactsMap.values.toList()
    }

    fun matchContacts(query: String, allContacts: List<ContactRecord>): List<ContactMatchCandidate> {
        val cleanQuery = query.lowercase(Locale.ROOT).trim()
        val alphaQuery = cleanQuery.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
        val queryTokens = cleanQuery.split(Regex("[\\s\\-_.]+")).filter { it.isNotBlank() }
        val candidates = mutableListOf<ContactMatchCandidate>()

        if (cleanQuery.isBlank() || alphaQuery.isBlank()) return emptyList()

        for (contact in allContacts) {
            val name = contact.displayName
            val lowerName = name.lowercase(Locale.ROOT).trim()
            val alphaName = lowerName.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
            val contactTokens = lowerName.split(Regex("[\\s\\-_.]+")).filter { it.isNotBlank() }

            // 1. Exact full string match
            if (lowerName == cleanQuery) {
                candidates.add(ContactMatchCandidate(contact, 1.0, "EXACT_FULL_NAME"))
                continue
            }

            // 2. Normalized alphanumeric exact match (ignores punctuation & whitespace differences, e.g. "Af Net" == "Afnet")
            if (alphaName == alphaQuery) {
                candidates.add(ContactMatchCandidate(contact, 0.99, "NORMALIZED_EXACT"))
                continue
            }

            // 3. Multi-token comprehensive scoring
            var score = 0.0
            var matchType = "NONE"

            val qTokenCount = queryTokens.size
            val cTokenCount = contactTokens.size

            if (qTokenCount > 0 && cTokenCount > 0) {
                var matchedQueryTokens = 0
                var matchedContactTokens = 0
                val contactTokenMatched = BooleanArray(cTokenCount)

                for (qTok in queryTokens) {
                    val alphaQ = qTok.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
                    for (i in contactTokens.indices) {
                        if (contactTokenMatched[i]) continue
                        val cTok = contactTokens[i]
                        val alphaC = cTok.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")

                        if (qTok == cTok || alphaQ == alphaC) {
                            contactTokenMatched[i] = true
                            matchedQueryTokens++
                            matchedContactTokens++
                            break
                        } else if (alphaQ.length >= 3 && alphaC.length >= 3 && similarityRatio(alphaQ, alphaC) >= 0.82) {
                            contactTokenMatched[i] = true
                            matchedQueryTokens++
                            matchedContactTokens++
                            break
                        }
                    }
                }

                val precision = matchedQueryTokens.toDouble() / qTokenCount
                val recall = matchedContactTokens.toDouble() / cTokenCount

                val isOrderedSubstring = lowerName.contains(cleanQuery) || alphaName.contains(alphaQuery)
                val startsWithQuery = lowerName.startsWith(cleanQuery) || alphaName.startsWith(alphaQuery)

                if (precision == 1.0 && recall == 1.0) {
                    score = 0.98
                    matchType = "ALL_TOKENS_EXACT"
                } else if (precision == 1.0) {
                    score = if (startsWithQuery) {
                        0.75 + (0.18 * (qTokenCount.toDouble() / cTokenCount))
                    } else if (isOrderedSubstring) {
                        0.70 + (0.18 * (qTokenCount.toDouble() / cTokenCount))
                    } else {
                        0.65 + (0.18 * (qTokenCount.toDouble() / cTokenCount))
                    }
                    matchType = "QUERY_CONTAINED"
                } else if (recall == 1.0) {
                    score = 0.70 + (0.20 * (cTokenCount.toDouble() / qTokenCount))
                    matchType = "CONTACT_CONTAINED"
                } else if (matchedQueryTokens > 0) {
                    val f1 = 2.0 * (precision * recall) / (precision + recall)
                    score = 0.50 + (0.35 * f1)
                    matchType = "PARTIAL_TOKENS"
                }
            }

            // 4. Fallback fuzzy string similarity
            if (score < 0.60 && alphaQuery.length >= 3 && alphaName.length >= 3) {
                val fullSim = similarityRatio(alphaQuery, alphaName)
                if (fullSim >= 0.75) {
                    score = maxOf(score, fullSim * 0.85)
                    matchType = "FUZZY_STRING"
                }
            }

            if (score >= 0.55) {
                candidates.add(ContactMatchCandidate(contact, score, matchType))
            }
        }

        return candidates.sortedByDescending { it.score }
    }

    fun parseOrdinalOrNumericSelection(reply: String, candidateCount: Int): Int? {
        val lower = reply.lowercase(Locale.ROOT).trim()
        if (lower.isBlank() || candidateCount <= 0) return null

        // 1. Direct digits & prefixes (e.g. "1", "2", "3", "4", "5", "1.", "2.", "#1", "option 1", "number 1", "1 number", "1st", "2nd", "3rd", "4th")
        val digitMatch = Regex("^(?:option|number|no\\.?|#)?\\s*(\\d+)(?:st|nd|rd|th|\\.)?(?:\\s+(?:wala|wali|wale|option|number|contact|one))?$").find(lower)
        if (digitMatch != null) {
            val num = digitMatch.groupValues[1].toIntOrNull()
            if (num != null && num > 0) {
                return num - 1
            }
        }

        // Devanagari digits
        val devanagariMap = mapOf('१' to 1, '२' to 2, '३' to 3, '४' to 4, '५' to 5, '६' to 6, '७' to 7, '८' to 8, '९' to 9)
        for ((char, num) in devanagariMap) {
            if (lower == "$char" || lower == "$char." || lower == "नंबर $char" || lower == "$char नंबर" ||
                lower.contains("$char वाला") || lower.contains("$char वाली") || lower.contains("$char वाले") || lower.contains("$char नंबर")) {
                return num - 1
            }
        }

        // 2. Check for "last" / "aakhri" dynamically mapped to candidateCount - 1
        val isLast = lower == "last" || lower == "last one" || lower == "the last one" ||
                lower == "last wala" || lower == "last wali" || lower == "last wale" ||
                lower == "aakhri" || lower == "akhri" || lower == "aakhri wala" || lower == "akhri wala" ||
                lower == "aakhri wali" || lower == "aakhri wale" || lower == "akhri wale" ||
                lower == "आखिरी" || lower == "आखिरी वाला" || lower == "आखिरी वाली" || lower == "आखिरी वाले" ||
                lower == "अंतिम" || lower == "अंतिम वाला" || lower == "अंतिम वाली" || lower == "अंतिम वाले" ||
                lower == "लास्ट" || lower == "लास्ट वाला" || lower == "लास्ट वाली" || lower == "लास्ट वाले" ||
                lower.startsWith("last ") || lower.startsWith("aakhri ") || lower.startsWith("akhri ") ||
                lower.endsWith(" last") || lower.endsWith(" aakhri") || lower.endsWith(" आखिरी")

        if (isLast) {
            return candidateCount - 1
        }

        // 3. Dynamic Ordinal Synonym Table (1st to 10th)
        val ordinalPatterns = listOf(
            // 1st (index 0)
            setOf(
                "first", "one", "1st", "1",
                "pehla", "pehli", "pahla", "pahli", "pehla wala", "pehli wali", "pahla wala", "pehla wale", "pahle wala", "pahle wale",
                "पहला", "पहली", "पहला वाला", "पहली वाली", "पहले वाला", "पहले वाले",
                "first one", "the first one", "first wala", "1st wala", "1 wala",
                "option 1", "option one", "number 1", "number one", "no 1", "no. 1"
            ),
            // 2nd (index 1)
            setOf(
                "second", "two", "2nd", "2",
                "dusra", "dusri", "doosra", "doosri", "dusra wala", "dusri wali", "doosra wala", "doosri wali", "dusre wala", "dusra wale", "doosre wala", "doosre wale",
                "दूसरा", "दूसरी", "दूसरा वाला", "दूसरी वाली", "दूसरे वाला", "दूसरे वाले",
                "second one", "the second one", "second wala", "2nd wala", "2 wala",
                "option 2", "option two", "number 2", "number two", "no 2", "no. 2"
            ),
            // 3rd (index 2)
            setOf(
                "third", "three", "3rd", "3",
                "teesra", "teesri", "tisra", "tisri", "teesra wala", "teesri wali", "tisra wala", "teesre wala", "teesra wale", "tisre wala", "tisre wale",
                "तीसरा", "तीसरी", "तीसरा वाला", "तीसरी वाली", "तीसरे वाला", "तीसरे वाले",
                "third one", "the third one", "third wala", "3rd wala", "3 wala",
                "option 3", "option three", "number 3", "number three", "no 3", "no. 3"
            ),
            // 4th (index 3)
            setOf(
                "fourth", "four", "4th", "4",
                "chautha", "chauthi", "chotha", "chothi", "chautha wala", "chauthi wali", "chotha wala", "chauthe wala", "chautha wale",
                "चौथा", "चौथी", "चौथा वाला", "चौथी वाली", "चौथे वाला", "चौथे वाले",
                "fourth one", "the fourth one", "fourth wala", "4th wala", "4 wala",
                "option 4", "option four", "number 4", "number four", "no 4", "no. 4"
            ),
            // 5th (index 4)
            setOf(
                "fifth", "five", "5th", "5",
                "paanchwa", "paanchwi", "panchwa", "panchwi", "paanchva", "panchva", "paanchwa wala", "panchwa wala", "paanchve wala", "paanchve wale",
                "पांचवा", "पांचवी", "पांचवां", "पाँचवा", "पांचवा वाला", "पांचवी वाली", "पांचवे वाला", "पांचवे वाले",
                "fifth one", "the fifth one", "fifth wala", "5th wala", "5 wala",
                "option 5", "option five", "number 5", "number five", "no 5", "no. 5"
            ),
            // 6th (index 5)
            setOf(
                "sixth", "six", "6th", "6",
                "chhatta", "chhatti", "chhattha", "chhatthi", "chatha", "chhattha wala", "chhathe wala", "chhathe wale",
                "छठा", "छठी", "छठवां", "छठा वाला", "छठी वाली", "छठे वाला", "छठे वाले",
                "sixth one", "the sixth one", "sixth wala", "6th wala", "6 wala",
                "option 6", "option six", "number 6", "number six", "no 6", "no. 6"
            ),
            // 7th (index 6)
            setOf(
                "seventh", "seven", "7th", "7",
                "saatwa", "saatwi", "satwa", "satwi", "saatva", "saatwa wala", "saatve wala", "saatve wale",
                "सातवां", "सातवा", "सातवी", "सातवा वाला", "सातवी वाली", "सातवे वाला", "सातवे वाले",
                "seventh one", "the seventh one", "seventh wala", "7th wala", "7 wala",
                "option 7", "option seven", "number 7", "number seven", "no 7", "no. 7"
            ),
            // 8th (index 7)
            setOf(
                "eighth", "eight", "8th", "8",
                "aathwa", "aathwi", "athwa", "athwi", "aathva", "aathwa wala", "aathve wala", "aathve wale",
                "आठवां", "आठवा", "आठवी", "आठवा वाला", "आठवी वाली", "आठवे वाला", "आठवे वाले",
                "eighth one", "the eighth one", "eighth wala", "8th wala", "8 wala",
                "option 8", "option eight", "number 8", "number eight", "no 8", "no. 8"
            ),
            // 9th (index 8)
            setOf(
                "ninth", "nine", "9th", "9",
                "nauwa", "nauwi", "nouwa", "nauva", "nauwa wala", "nawe wala", "nawe wale",
                "नौवां", "नौवा", "नौवी", "नौवा वाला", "नौवी वाली", "नौवे वाला", "नौवे वाले",
                "ninth one", "the ninth one", "ninth wala", "9th wala", "9 wala",
                "option 9", "option nine", "number 9", "number nine", "no 9", "no. 9"
            ),
            // 10th (index 9)
            setOf(
                "tenth", "ten", "10th", "10",
                "daswa", "daswi", "dasva", "daswa wala", "daswe wala", "daswe wale",
                "दसवां", "दसवा", "दसवी", "दसवा वाला", "दसवी वाली", "दसवे वाला", "दसवे वाले",
                "tenth one", "the tenth one", "tenth wala", "10th wala", "10 wala",
                "option 10", "option ten", "number 10", "number ten", "no 10", "no. 10"
            )
        )

        for (index in ordinalPatterns.indices) {
            val synonyms = ordinalPatterns[index]
            if (synonyms.contains(lower)) {
                return index
            }
            if (synonyms.any { syn ->
                lower == syn ||
                lower == "$syn wala" || lower == "$syn wali" || lower == "$syn wale" ||
                lower == "$syn number" || lower == "$syn contact" || lower == "$syn option" ||
                lower == "option $syn" || lower == "number $syn" ||
                (lower.startsWith("$syn ") && syn.length > 2) ||
                (lower.endsWith(" $syn") && syn.length > 2)
            }) {
                return index
            }
        }

        return null
    }

    fun isAffirmationOnly(reply: String): Boolean {
        val lower = reply.lowercase(Locale.ROOT).trim()
        val affirmativeSet = setOf(
            "haan", "ha", "yes", "yep", "yeah", "ok", "okay", "theek hai", "thik hai", "theek", "thik",
            "sahi hai", "sure", "yup", "haa", "han",
            "हां", "हाँ", "जी", "जी हाँ", "जी हां", "ठीक है", "सही है", "श्योर"
        )
        return affirmativeSet.contains(lower)
    }

    fun matchCandidateFromDisambiguationList(
        reply: String,
        candidates: List<ContactRecord>
    ): DisambiguationSelectionResult {
        val lowerReply = reply.lowercase(Locale.ROOT).trim()
        if (lowerReply.isBlank() || candidates.isEmpty()) return DisambiguationSelectionResult.Unrecognized

        // 1. Check for standalone affirmative replies (e.g. "haan", "yes", "ok")
        if (isAffirmationOnly(lowerReply)) {
            Log.i(NATIVE_TAG, "[DISAMBIGUATION_MATCH] result=AFFIRMATION_ONLY")
            return DisambiguationSelectionResult.AffirmationOnly
        }

        // 2. Check ordinal / numeric selection on raw lower input
        val ordinalIndex = parseOrdinalOrNumericSelection(lowerReply, candidates.size)
        if (ordinalIndex != null) {
            if (ordinalIndex in candidates.indices) {
                val chosen = candidates[ordinalIndex]
                Log.i(NATIVE_TAG, "[DISAMBIGUATION_MATCH] matchedBy=ORDINAL index=$ordinalIndex candidate='${chosen.displayName}'")
                return DisambiguationSelectionResult.Selected(chosen)
            } else {
                Log.w(NATIVE_TAG, "[DISAMBIGUATION_MATCH] index=$ordinalIndex outOfBounds candidateCount=${candidates.size}")
                return DisambiguationSelectionResult.OutOfBounds(ordinalIndex)
            }
        }

        // 3. Clean conversational filler words from reply for name/phrase matching
        var cleaned = lowerReply
        cleaned = cleaned.replace(Regex("^(?:haan|ha|yes|yep|yeah|ok|okay|please|arre|jo|the|that|हां|हाँ|जी|जो)\\s+"), "").trim()
        cleaned = cleaned.replace(Regex("^(?:call|phone|dial|make a call to|send message to|whatsapp|sms|message|कॉल|फोन|डायल|मैसेज|व्हाट्सएप)\\s+(?:to\\s+)?"), "").trim()
        cleaned = cleaned.replace(Regex("\\s+(?:ko phone karo|ko call karo|ko phone lagao|ko call lagao|ko dial karo|phone karo|call karo|phone lagao|call lagao|dial karo|lagao|karo|milao|ko message bhejo|ko whatsapp karo|ko sms bhejo|message bhejo|whatsapp karo|sms bhejo|bhejo|को फोन करो|को कॉल करो|को फोन लगाओ|को कॉल लगाओ|फोन करो|कॉल करो|फोन लगाओ|कॉल लगाओ|लगाओ|करो|मिलाओ|को मैसेज भेजो|मैसेज भेजो|भेजो)$"), "").trim()
        cleaned = cleaned.replace(Regex("\\s+(?:ko|pe|par|se|wala|wali|wale|bhi|hai|h|को|पर|पे|से|वाला|वाली|वाले|भी|है)$"), "").trim()

        // Check if cleaned string alone was reduced to an ordinal/number (e.g. "haan dusra wala" -> "dusra" -> index 1)
        val cleanedOrdinal = parseOrdinalOrNumericSelection(cleaned, candidates.size)
        if (cleanedOrdinal != null) {
            if (cleanedOrdinal in candidates.indices) {
                val chosen = candidates[cleanedOrdinal]
                Log.i(NATIVE_TAG, "[DISAMBIGUATION_MATCH] matchedBy=ORDINAL_CLEANED index=$cleanedOrdinal candidate='${chosen.displayName}'")
                return DisambiguationSelectionResult.Selected(chosen)
            } else {
                return DisambiguationSelectionResult.OutOfBounds(cleanedOrdinal)
            }
        }

        // 4. Exact display name match
        if (cleaned.isNotBlank()) {
            val exactMatch = candidates.find {
                it.displayName.equals(cleaned, ignoreCase = true) ||
                it.displayName.lowercase(Locale.ROOT).trim() == cleaned
            }
            if (exactMatch != null) {
                Log.i(NATIVE_TAG, "[DISAMBIGUATION_MATCH] matchedByExactName candidate='${exactMatch.displayName}'")
                return DisambiguationSelectionResult.Selected(exactMatch)
            }

            // 5. Fuzzy contact matching via matchContacts
            val matches = matchContacts(cleaned, candidates)
            if (matches.isNotEmpty() && matches[0].score >= 0.60) {
                val topMatch = matches[0]
                val secondScore = if (matches.size > 1) matches[1].score else 0.0
                if (topMatch.score >= 0.70 || (topMatch.score - secondScore) >= 0.15) {
                    Log.i(NATIVE_TAG, "[DISAMBIGUATION_MATCH] matchedByName candidate='${topMatch.contact.displayName}' score=${topMatch.score} type=${topMatch.matchType}")
                    return DisambiguationSelectionResult.Selected(topMatch.contact)
                }
            }

            // 6. Distinguishing token match (e.g. user says "Bikaner wala", "Bikaner", "Sakhuniya", "Bikaner wale Sunil", "Afnet")
            val replyTokens = cleaned.split(Regex("[\\s\\-_.,]+")).filter {
                it.length >= 2 && !setOf("wala", "wali", "wale", "ko", "se", "pe", "par", "hai", "bhi", "वाला", "वाली", "वाले", "को").contains(it)
            }
            if (replyTokens.isNotEmpty()) {
                val candidateTokenScores = candidates.map { cand ->
                    val candTokens = cand.displayName.lowercase(Locale.ROOT).split(Regex("[\\s\\-_.,]+")).filter { it.length >= 2 }
                    val matchCount = replyTokens.count { rTok ->
                        candTokens.any { cTok -> cTok == rTok || cTok.contains(rTok) || rTok.contains(cTok) }
                    }
                    val uniqueTokenCount = replyTokens.count { rTok ->
                        candTokens.any { cTok -> cTok == rTok || cTok.contains(rTok) } &&
                        candidates.count { other ->
                            other != cand && other.displayName.lowercase(Locale.ROOT).contains(rTok)
                        } == 0
                    }
                    Triple(cand, matchCount, uniqueTokenCount)
                }

                val uniqueMatch = candidateTokenScores.filter { it.third > 0 }
                if (uniqueMatch.size == 1) {
                    val chosen = uniqueMatch[0].first
                    Log.i(NATIVE_TAG, "[DISAMBIGUATION_MATCH] matchedByUniqueToken candidate='${chosen.displayName}' tokenMatches=${uniqueMatch[0].second}")
                    return DisambiguationSelectionResult.Selected(chosen)
                }

                val maxScore = candidateTokenScores.maxOf { it.second }
                if (maxScore > 0) {
                    val topCandidates = candidateTokenScores.filter { it.second == maxScore }
                    if (topCandidates.size == 1) {
                        val chosen = topCandidates[0].first
                        Log.i(NATIVE_TAG, "[DISAMBIGUATION_MATCH] matchedByMaxTokens candidate='${chosen.displayName}' tokenMatches=$maxScore")
                        return DisambiguationSelectionResult.Selected(chosen)
                    }
                }
            }
        }

        return DisambiguationSelectionResult.Unrecognized
    }

    fun sanitizeContactQuery(raw: String): String {
        var clean = raw.trim()

        val prefixes = listOf(
            "call to my friend ", "call my friend ", "call to friend ", "call friend ",
            "call to my ", "call my ", "call to ", "call ",
            "dial to my friend ", "dial my friend ", "dial to ", "dial ",
            "phone to my friend ", "phone my friend ", "phone to ", "phone ",
            "ring to my friend ", "ring my friend ", "ring to ", "ring ",
            "please call ", "please dial ", "please phone ",
            "mere dost ", "apne dost ", "apne bhai ", "dost ", "bhai ",
            "mr ", "mrs ", "ms ", "dr ",
            "कॉल करो ", "कॉल लगाओ ", "फोन करो ", "फोन लगाओ ", "डायल करो "
        )
        for (prefix in prefixes) {
            if (clean.startsWith(prefix, ignoreCase = true)) {
                clean = clean.substring(prefix.length).trim()
            }
        }

        val suffixes = listOf(
            " ko phone karo", " ko phone lagao", " ko call karo", " ko call lagao", " ko dial karo", " ko call kar do", " ko call kar",
            " par phone karo", " par call karo", " par dial karo",
            " pe phone karo", " pe call karo", " pe dial karo",
            " se baat karao", " se baat karwao", " se baat karo",
            " call karo", " phone karo", " dial karo", " call kar do", " call kar", " call", " phone", " dial",
            " ko lagao", " par lagao", " pe lagao", " lagao", " number lagao", " number milao",
            " ko call kijiye", " ko phone kijiye", " call kijiye",
            " को फोन करो", " को फोन लगाओ", " को कॉल करो", " को कॉल लगाओ", " को कॉल लगा दो", " को फोन लगा दो",
            " पर फोन करो", " पर कॉल लगाओ", " पे फोन करो", " पे कॉल लगाओ", " से बात कराओ", " से बात करवाओ", " से बात करो",
            " फोन करो", " फोन लगाओ", " कॉल करो", " कॉल लगाओ", " कॉल लगा दो", " फोन लगा दो",
            " ko", " par", " pe", " se", " ka", " ke", " ki", " ji", " bhai", " sir", " please", " na",
            " को", " पर", " पे", " से", " का", " के", " की", " जी", " भाई"
        )
        for (suffix in suffixes) {
            if (clean.endsWith(suffix, ignoreCase = true)) {
                clean = clean.substring(0, clean.length - suffix.length).trim()
            }
        }

        return clean.trim()
    }

    fun lookupContactDetails(rawQuery: String): String {
        val cleanQuery = sanitizeContactQuery(rawQuery)
        Log.i(NATIVE_TAG, "[CONTACT_LOOKUP] rawInput='$rawQuery' query='$cleanQuery'")

        if (cleanQuery.isBlank()) {
            return "Please specify which contact's details you want to find."
        }

        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            val activity = activityProvider?.invoke()
            if (activity != null) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_CONTACTS),
                    PERMISSION_REQUEST_READ_CONTACTS
                )
            }
            return "DooraGo needs access to your contacts to look up phone numbers."
        }

        val allContacts = loadAllContactsWithPhones()
        if (allContacts.isEmpty()) {
            return "I couldn't find any contacts on this phone."
        }

        val matches = matchContacts(cleanQuery, allContacts)
        if (matches.isEmpty()) {
            val displayTarget = cleanQuery.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            return "I couldn't find '$displayTarget' in your contacts."
        }

        val top = matches[0]
        if (top.score >= 0.65) {
            val contact = top.contact
            val validPhones = contact.phones.filter { isValidPhoneNumber(normalizePhoneNumber(it.number)) }
            if (validPhones.isEmpty()) {
                return "${contact.displayName} is in your contacts, but does not have a saved phone number."
            }
            return if (validPhones.size == 1) {
                val masked = maskPhoneNumber(normalizePhoneNumber(validPhones[0].number))
                "${contact.displayName}'s phone number is $masked."
            } else {
                val listDesc = validPhones.joinToString(", ") { "${it.typeLabel}: ${maskPhoneNumber(normalizePhoneNumber(it.number))}" }
                "${contact.displayName} has ${validPhones.size} numbers: $listDesc."
            }
        }

        val closeMatches = matches.take(3).map { it.contact.displayName }.distinct()
        val namesList = closeMatches.joinToString(", ")
        return "I found multiple contacts matching '$cleanQuery': $namesList. Please be more specific."
    }

    fun saveContact(name: String?, phoneNumber: String?): String {
        val cleanNumber = if (!phoneNumber.isNullOrBlank()) normalizePhoneNumber(phoneNumber) else null
        val masked = if (cleanNumber != null) maskPhoneNumber(cleanNumber) else null
        Log.i(NATIVE_TAG, "[CONTACT_SAVE] name='$name' number=$masked")

        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = "vnd.android.cursor.dir/contact"
            if (!name.isNullOrBlank()) {
                putExtra(ContactsContract.Intents.Insert.NAME, name.trim())
            }
            if (!cleanNumber.isNullOrBlank()) {
                putExtra(ContactsContract.Intents.Insert.PHONE, cleanNumber)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val spokenMsg = if (!name.isNullOrBlank()) "${name.trim()} ke naam se contact ready kar raha hoon." else "Contact ready kar raha hoon."

        return safeStartActivity(
            intent = intent,
            actionName = "saveContact",
            successMsg = spokenMsg,
            fallbackMsg = "Couldn't open contact creation screen."
        )
    }

    fun openContacts(): String {
        val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        return safeStartActivity(intent, "openContacts", "Contacts opened.", "Couldn't open contacts.")
    }

    fun searchContacts(query: String): String {
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
        }
        return safeStartActivity(intent, "searchContacts($query)", "Searching contacts for $query.", "Couldn't search contacts.")
    }

    // ──────────────────────────────────────────────────────────────
    // Category 6: Messaging (WhatsApp, SMS, Generic)
    // ──────────────────────────────────────────────────────────────

    fun openWhatsAppMessage(phoneNumber: String?, message: String?): String {
        Log.i(NATIVE_TAG, "[WHATSAPP_ACTION] phone=${phoneNumber?.let { maskPhoneNumber(it) }} msgLen=${message?.length ?: 0}")

        val isInstalled = isPackageInstalled("com.whatsapp") || isPackageInstalled("com.whatsapp.w4b")
        if (!isInstalled) {
            Log.w(NATIVE_TAG, "[WHATSAPP_ACTION] not installed")
            return "WhatsApp is not installed on this device."
        }

        val cleanDigits = if (!phoneNumber.isNullOrBlank()) {
            normalizeForWhatsApp(phoneNumber)
        } else {
            ""
        }

        val text = message?.trim() ?: ""

        val uri = if (cleanDigits.isNotBlank()) {
            if (text.isNotBlank()) {
                Uri.parse("https://api.whatsapp.com/send?phone=$cleanDigits&text=${Uri.encode(text)}")
            } else {
                Uri.parse("https://api.whatsapp.com/send?phone=$cleanDigits")
            }
        } else {
            if (text.isNotBlank()) {
                Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(text)}")
            } else {
                null
            }
        }

        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW, uri).apply {
                if (isPackageInstalled("com.whatsapp")) {
                    setPackage("com.whatsapp")
                } else if (isPackageInstalled("com.whatsapp.w4b")) {
                    setPackage("com.whatsapp.w4b")
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            val pkg = if (isPackageInstalled("com.whatsapp")) "com.whatsapp" else "com.whatsapp.w4b"
            context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            } ?: Intent(Intent.ACTION_MAIN).apply {
                setPackage(pkg)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        return safeStartActivity(
            intent = intent,
            actionName = "openWhatsAppMessage",
            successMsg = "WhatsApp message ready. Tap Send in WhatsApp.",
            fallbackMsg = "Couldn't open WhatsApp."
        )
    }

    fun openSmsComposer(recipient: String, message: String): String {
        val cleanRecip = recipient.trim()
        val uri = if (cleanRecip.isNotBlank()) Uri.parse("smsto:${Uri.encode(cleanRecip)}") else Uri.parse("smsto:")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            if (message.isNotBlank()) {
                putExtra("sms_body", message)
                putExtra(Intent.EXTRA_TEXT, message)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val targetDesc = if (cleanRecip.isNotBlank()) " for $cleanRecip" else ""
        return safeStartActivity(
            intent,
            "openSmsComposer",
            "SMS composer ready$targetDesc. Tap Send in Messages.",
            "Couldn't open SMS composer."
        )
    }

    fun openGenericMessageComposer(recipient: String?, message: String): String {
        return if (!recipient.isNullOrBlank()) {
            openSmsComposer(recipient, message)
        } else {
            val text = message.trim()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                if (text.isNotBlank()) {
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            safeStartActivity(
                Intent.createChooser(intent, "Send message via...").apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                "openGenericMessageComposer",
                "Messaging app ready.",
                "Couldn't open messaging app."
            )
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun normalizeForWhatsApp(phone: String): String {
        val digits = phone.replace(Regex("[^0-9]"), "")
        return when {
            phone.startsWith("+") -> digits
            digits.length == 10 && (digits.startsWith("6") || digits.startsWith("7") || digits.startsWith("8") || digits.startsWith("9")) -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91${digits.substring(1)}"
            else -> digits
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Category 8: Maps & Navigation
    // ──────────────────────────────────────────────────────────────

    fun showMap(query: String): String {
        val encoded = Uri.encode(query.trim())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
        return safeStartActivity(intent, "showMap($query)", "Map opened for $query.", "Couldn't open map application.")
    }

    fun openDirections(destination: String): String {
        val encoded = Uri.encode(destination.trim())
        val navUri = Uri.parse("google.navigation:q=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, navUri)
        return safeStartActivity(
            intent = intent,
            actionName = "openDirections($destination)",
            successMsg = "Directions opened for $destination.",
            fallbackMsg = showMap(destination)
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Category 9: Camera & Gallery
    // ──────────────────────────────────────────────────────────────

    fun openCamera(): String {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        return safeStartActivity(intent, "openCamera", "Camera opened.", "Couldn't open camera.")
    }

    fun openGallery(): String {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
        }
        return safeStartActivity(intent, "openGallery", "Gallery opened.", "Couldn't open gallery/photos.")
    }

    // ──────────────────────────────────────────────────────────────
    // Category 11: Alarms & Timers
    // ──────────────────────────────────────────────────────────────

    fun setAlarm(hour: Int, minutes: Int, title: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            if (title.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val displayMin = if (minutes < 10) "0$minutes" else "$minutes"
        val timeFormatted = "$displayHour:$displayMin $amPm"

        return safeStartActivity(
            intent = intent,
            actionName = "setAlarm($hour:$minutes, $title)",
            successMsg = "Alarm creation opened for $timeFormatted.",
            fallbackMsg = "Couldn't set the alarm. Please check clock permissions."
        )
    }

    fun openAlarms(): String {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        return safeStartActivity(intent, "openAlarms", "Alarms opened.", "Couldn't open alarms.")
    }

    fun setTimer(lengthSeconds: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, lengthSeconds)
            if (label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        val mins = lengthSeconds / 60
        val secs = lengthSeconds % 60
        val durationDesc = when {
            mins > 0 && secs > 0 -> "$mins minutes and $secs seconds"
            mins > 0 -> "$mins minutes"
            else -> "$secs seconds"
        }
        return safeStartActivity(
            intent = intent,
            actionName = "setTimer($lengthSeconds, $label)",
            successMsg = "Timer creation opened for $durationDesc.",
            fallbackMsg = "Couldn't set timer."
        )
    }

    fun openClock(): String {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        return safeStartActivity(intent, "openClock", "Clock opened.", "Couldn't open clock.")
    }

    // ──────────────────────────────────────────────────────────────
    // Category 12: Files & Downloads
    // ──────────────────────────────────────────────────────────────

    fun openFiles(): String {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        return safeStartActivity(
            intent = intent,
            actionName = "openFiles",
            successMsg = "Files and downloads opened.",
            fallbackMsg = "Couldn't open file manager."
        )
    }

    fun openDownloads(): String {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        return safeStartActivity(
            intent = intent,
            actionName = "openDownloads",
            successMsg = "Downloads opened.",
            fallbackMsg = "Couldn't open downloads."
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Category 13: Local Device Information (100% On-Device, Read-Only)
    // ──────────────────────────────────────────────────────────────

    fun getBatteryStatus(): String {
        return try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, batteryFilter)
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else level
            val chargeState = if (isCharging) "and is currently charging" else "and is not charging"
            "Your battery is at $batteryPct% $chargeState."
        } catch (e: Exception) {
            "I couldn't retrieve battery information."
        }
    }

    fun getAndroidVersion(): String {
        val release = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT
        return "This device is running Android $release (API level $sdk)."
    }

    fun getDeviceInfo(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val model = Build.MODEL
        val device = Build.DEVICE
        return "This device is a $manufacturer $model ($device)."
    }

    fun getStorageInfo(): String {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val totalBlocks = stat.blockCountLong

            val freeGb = (availableBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
            val totalGb = (totalBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)

            String.format(Locale.ROOT, "Storage: %.1f GB available out of %.1f GB total.", freeGb, totalGb)
        } catch (e: Exception) {
            "I couldn't read storage information."
        }
    }
}
