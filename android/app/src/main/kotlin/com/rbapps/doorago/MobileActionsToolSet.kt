package com.rbapps.doorago

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Official FunctionGemma Mobile Actions ToolSets for DooraGo.
 *
 * Designed specifically for google/functiongemma-270m-ft-mobile-actions.
 * The model was fine-tuned on the official mobile-actions dataset supporting:
 *  1. turn_on_flashlight
 *  2. turn_off_flashlight
 *  3. create_contact
 *  4. send_email
 *  5. show_map
 *  6. open_wifi_settings
 *  7. create_calendar_event
 *
 * To strictly maintain the 1024-token KV-cache headroom, each domain category
 * exposes ONLY its required function(s) to ConversationConfig.
 */
enum class ToolCategory(val label: String, val toolNames: List<String>) {
    FLASHLIGHT("FLASHLIGHT", listOf("turn_on_flashlight", "turn_off_flashlight")),
    CONTACT("CONTACT", listOf("create_contact")),
    EMAIL("EMAIL", listOf("send_email")),
    MAP("MAP", listOf("show_map")),
    WIFI("WIFI", listOf("open_wifi_settings")),
    CALENDAR("CALENDAR", listOf("create_calendar_event")),
    GENERAL("GENERAL", listOf("turn_on_flashlight", "turn_off_flashlight", "open_wifi_settings"))
}

/**
 * Common interface for domain-specific ActionTrackingToolSet implementations.
 */
interface ActionTrackingToolSet : ToolSet {
    val category: ToolCategory
    val toolNames: List<String>
    val lastActionResult: String?
    val lastActionName: String?
    fun clearLastAction()
}

/**
 * Flashlight ToolSet (2 tools: turn_on_flashlight, turn_off_flashlight).
 */
class FlashlightToolSet(
    private val context: Context,
    private val onActionExecuted: ((String, Map<String, Any?>, String) -> Unit)? = null
) : ActionTrackingToolSet {

    companion object {
        private const val TAG = "FlashlightToolSet"
    }

    override val category: ToolCategory = ToolCategory.FLASHLIGHT
    override val toolNames: List<String> = listOf("turn_on_flashlight", "turn_off_flashlight")

    @Volatile
    override var lastActionResult: String? = null
        private set

    @Volatile
    override var lastActionName: String? = null
        private set

    override fun clearLastAction() {
        lastActionResult = null
        lastActionName = null
    }

    private fun recordAction(actionName: String, params: Map<String, Any?>, resultMessage: String, success: Boolean): String {
        lastActionName = actionName
        lastActionResult = resultMessage
        Log.i("DooraGoNative", "[TOOL_CALL] name=$actionName")
        Log.i("DooraGoNative", "[TOOL_RESULT] name=$actionName success=$success")
        onActionExecuted?.invoke(actionName, params, resultMessage)
        return resultMessage
    }

    @Tool(description = "Turns the flashlight on.")
    fun turn_on_flashlight(): String {
        return setFlashlight(enabled = true)
    }

    @Tool(description = "Turns the flashlight off.")
    fun turn_off_flashlight(): String {
        return setFlashlight(enabled = false)
    }

    private fun setFlashlight(enabled: Boolean): String {
        val actionLabel = if (enabled) "turn_on_flashlight" else "turn_off_flashlight"
        val failMsg = if (enabled) "Couldn't turn on the flashlight." else "Couldn't turn off the flashlight."
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                Log.w(TAG, "Camera service is not available on this device.")
                return recordAction(actionLabel, emptyMap(), failMsg, success = false)
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                try {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (e: Exception) {
                    false
                }
            }
            if (cameraId == null) {
                Log.w(TAG, "This device doesn't have a flashlight.")
                recordAction(actionLabel, emptyMap(), failMsg, success = false)
            } else {
                cameraManager.setTorchMode(cameraId, enabled)
                val msg = "Flashlight turned ${if (enabled) "on" else "off"}."
                Log.i(TAG, "$actionLabel: $msg")
                recordAction(actionLabel, emptyMap(), msg, success = true)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error: ${e.localizedMessage}", e)
            recordAction(actionLabel, emptyMap(), failMsg, success = false)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission required: ${e.localizedMessage}", e)
            recordAction(actionLabel, emptyMap(), failMsg, success = false)
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight error: ${e.localizedMessage}", e)
            recordAction(actionLabel, emptyMap(), failMsg, success = false)
        }
    }
}

/**
 * Contacts ToolSet (1 tool: create_contact).
 */
class ContactToolSet(
    private val context: Context,
    private val onActionExecuted: ((String, Map<String, Any?>, String) -> Unit)? = null
) : ActionTrackingToolSet {

    companion object {
        private const val TAG = "ContactToolSet"
    }

    override val category: ToolCategory = ToolCategory.CONTACT
    override val toolNames: List<String> = listOf("create_contact")

    @Volatile
    override var lastActionResult: String? = null
        private set

    @Volatile
    override var lastActionName: String? = null
        private set

    override fun clearLastAction() {
        lastActionResult = null
        lastActionName = null
    }

    private fun recordAction(actionName: String, params: Map<String, Any?>, resultMessage: String, success: Boolean): String {
        lastActionName = actionName
        lastActionResult = resultMessage
        Log.i("DooraGoNative", "[TOOL_CALL] name=$actionName")
        Log.i("DooraGoNative", "[TOOL_RESULT] name=$actionName success=$success")
        onActionExecuted?.invoke(actionName, params, resultMessage)
        return resultMessage
    }

    @Tool(description = "Creates a new contact.")
    fun create_contact(
        @ToolParam(description = "First name.") first_name: String,
        @ToolParam(description = "Last name.") last_name: String,
        @ToolParam(description = "Phone number.") phone_number: String = "",
        @ToolParam(description = "Email address.") email: String = ""
    ): String {
        val actionLabel = "create_contact"
        val params = mapOf<String, Any?>(
            "first_name" to first_name,
            "last_name" to last_name,
            "phone_number" to phone_number,
            "email" to email
        )
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = "vnd.android.cursor.dir/contact"
                putExtra("name", "$first_name $last_name".trim())
                if (phone_number.isNotBlank()) putExtra("phone", phone_number)
                if (email.isNotBlank()) putExtra("email", email)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val nameDisplay = "$first_name $last_name".trim()
            val msg = if (nameDisplay.isNotEmpty()) "Contact creation opened for $nameDisplay." else "Contact creation opened."
            Log.i(TAG, "$actionLabel: $msg")
            recordAction(actionLabel, params, msg, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create contact: ${e.localizedMessage}", e)
            recordAction(actionLabel, params, "Couldn't create the contact. Please check contact permission.", success = false)
        }
    }
}

/**
 * Email ToolSet (1 tool: send_email).
 */
class EmailToolSet(
    private val context: Context,
    private val onActionExecuted: ((String, Map<String, Any?>, String) -> Unit)? = null
) : ActionTrackingToolSet {

    companion object {
        private const val TAG = "EmailToolSet"
    }

    override val category: ToolCategory = ToolCategory.EMAIL
    override val toolNames: List<String> = listOf("send_email")

    @Volatile
    override var lastActionResult: String? = null
        private set

    @Volatile
    override var lastActionName: String? = null
        private set

    override fun clearLastAction() {
        lastActionResult = null
        lastActionName = null
    }

    private fun recordAction(actionName: String, params: Map<String, Any?>, resultMessage: String, success: Boolean): String {
        lastActionName = actionName
        lastActionResult = resultMessage
        Log.i("DooraGoNative", "[TOOL_CALL] name=$actionName")
        Log.i("DooraGoNative", "[TOOL_RESULT] name=$actionName success=$success")
        onActionExecuted?.invoke(actionName, params, resultMessage)
        return resultMessage
    }

    @Tool(description = "Sends an email.")
    fun send_email(
        @ToolParam(description = "Recipient email address.") to: String,
        @ToolParam(description = "Subject line.") subject: String,
        @ToolParam(description = "Body text.") body: String = ""
    ): String {
        val actionLabel = "send_email"
        val params = mapOf<String, Any?>("to" to to, "subject" to subject, "body" to body)
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).apply {
                putExtra(Intent.EXTRA_SUBJECT, subject)
                if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val msg = "Email composer opened."
            Log.i(TAG, "$actionLabel: $msg")
            recordAction(actionLabel, params, msg, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open email: ${e.localizedMessage}", e)
            recordAction(actionLabel, params, "Couldn't open the email composer.", success = false)
        }
    }
}

/**
 * Map ToolSet (1 tool: show_map).
 */
class MapToolSet(
    private val context: Context,
    private val onActionExecuted: ((String, Map<String, Any?>, String) -> Unit)? = null
) : ActionTrackingToolSet {

    companion object {
        private const val TAG = "MapToolSet"
    }

    override val category: ToolCategory = ToolCategory.MAP
    override val toolNames: List<String> = listOf("show_map")

    @Volatile
    override var lastActionResult: String? = null
        private set

    @Volatile
    override var lastActionName: String? = null
        private set

    override fun clearLastAction() {
        lastActionResult = null
        lastActionName = null
    }

    private fun recordAction(actionName: String, params: Map<String, Any?>, resultMessage: String, success: Boolean): String {
        lastActionName = actionName
        lastActionResult = resultMessage
        Log.i("DooraGoNative", "[TOOL_CALL] name=$actionName")
        Log.i("DooraGoNative", "[TOOL_RESULT] name=$actionName success=$success")
        onActionExecuted?.invoke(actionName, params, resultMessage)
        return resultMessage
    }

    @Tool(description = "Shows the map.")
    fun show_map(
        @ToolParam(description = "Location query or address.") query: String
    ): String {
        val actionLabel = "show_map"
        val params = mapOf<String, Any?>("query" to query)
        return try {
            val encodedQuery = Uri.encode(query)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedQuery")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val msg = "Map opened."
            Log.i(TAG, "$actionLabel: $msg (query=$query)")
            recordAction(actionLabel, params, msg, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open map: ${e.localizedMessage}", e)
            recordAction(actionLabel, params, "Couldn't open the map.", success = false)
        }
    }
}

/**
 * Wi-Fi Settings ToolSet (1 tool: open_wifi_settings).
 */
class WifiToolSet(
    private val context: Context,
    private val onActionExecuted: ((String, Map<String, Any?>, String) -> Unit)? = null
) : ActionTrackingToolSet {

    companion object {
        private const val TAG = "WifiToolSet"
    }

    override val category: ToolCategory = ToolCategory.WIFI
    override val toolNames: List<String> = listOf("open_wifi_settings")

    @Volatile
    override var lastActionResult: String? = null
        private set

    @Volatile
    override var lastActionName: String? = null
        private set

    override fun clearLastAction() {
        lastActionResult = null
        lastActionName = null
    }

    private fun recordAction(actionName: String, params: Map<String, Any?>, resultMessage: String, success: Boolean): String {
        lastActionName = actionName
        lastActionResult = resultMessage
        Log.i("DooraGoNative", "[TOOL_CALL] name=$actionName")
        Log.i("DooraGoNative", "[TOOL_RESULT] name=$actionName success=$success")
        onActionExecuted?.invoke(actionName, params, resultMessage)
        return resultMessage
    }

    @Tool(description = "Opens the Wi-Fi settings.")
    fun open_wifi_settings(): String {
        val actionLabel = "open_wifi_settings"
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val msg = "Wi-Fi settings opened."
            Log.i(TAG, "$actionLabel: $msg")
            recordAction(actionLabel, emptyMap(), msg, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Wi-Fi settings: ${e.localizedMessage}", e)
            recordAction(actionLabel, emptyMap(), "Couldn't open Wi-Fi settings.", success = false)
        }
    }
}

/**
 * Calendar ToolSet (1 tool: create_calendar_event).
 */
class CalendarToolSet(
    private val context: Context,
    private val onActionExecuted: ((String, Map<String, Any?>, String) -> Unit)? = null
) : ActionTrackingToolSet {

    companion object {
        private const val TAG = "CalendarToolSet"
    }

    override val category: ToolCategory = ToolCategory.CALENDAR
    override val toolNames: List<String> = listOf("create_calendar_event")

    @Volatile
    override var lastActionResult: String? = null
        private set

    @Volatile
    override var lastActionName: String? = null
        private set

    override fun clearLastAction() {
        lastActionResult = null
        lastActionName = null
    }

    private fun recordAction(actionName: String, params: Map<String, Any?>, resultMessage: String, success: Boolean): String {
        lastActionName = actionName
        lastActionResult = resultMessage
        Log.i("DooraGoNative", "[TOOL_CALL] name=$actionName")
        Log.i("DooraGoNative", "[TOOL_RESULT] name=$actionName success=$success")
        onActionExecuted?.invoke(actionName, params, resultMessage)
        return resultMessage
    }

    @Tool(description = "Creates a calendar event.")
    fun create_calendar_event(
        @ToolParam(description = "Event title.") title: String,
        @ToolParam(description = "Datetime in ISO format.") datetime: String
    ): String {
        val actionLabel = "create_calendar_event"
        val params = mapOf<String, Any?>("title" to title, "datetime" to datetime)
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                if (datetime.isNotBlank()) {
                    putExtra(CalendarContract.Events.DESCRIPTION, "Scheduled: $datetime")
                    try {
                        val parsed = java.time.LocalDateTime.parse(datetime, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        val millis = parsed.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis)
                    } catch (_: Exception) {}
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val msg = "Calendar event creation opened."
            Log.i(TAG, "$actionLabel: $msg ($title at $datetime)")
            recordAction(actionLabel, params, msg, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create calendar event: ${e.localizedMessage}", e)
            recordAction(actionLabel, params, "Couldn't create the calendar event.", success = false)
        }
    }
}

/**
 * General ToolSet (Compact 3-tool set: flashlight ON/OFF + Wi-Fi).
 */
class GeneralMobileActionsToolSet(
    private val context: Context,
    private val onActionExecuted: ((String, Map<String, Any?>, String) -> Unit)? = null
) : ActionTrackingToolSet {

    companion object {
        private const val TAG = "GeneralToolSet"
    }

    override val category: ToolCategory = ToolCategory.GENERAL
    override val toolNames: List<String> = listOf("turn_on_flashlight", "turn_off_flashlight", "open_wifi_settings")

    @Volatile
    override var lastActionResult: String? = null
        private set

    @Volatile
    override var lastActionName: String? = null
        private set

    override fun clearLastAction() {
        lastActionResult = null
        lastActionName = null
    }

    private fun recordAction(actionName: String, params: Map<String, Any?>, resultMessage: String, success: Boolean): String {
        lastActionName = actionName
        lastActionResult = resultMessage
        Log.i("DooraGoNative", "[TOOL_CALL] name=$actionName")
        Log.i("DooraGoNative", "[TOOL_RESULT] name=$actionName success=$success")
        onActionExecuted?.invoke(actionName, params, resultMessage)
        return resultMessage
    }

    @Tool(description = "Turns the flashlight on.")
    fun turn_on_flashlight(): String {
        return setFlashlight(enabled = true)
    }

    @Tool(description = "Turns the flashlight off.")
    fun turn_off_flashlight(): String {
        return setFlashlight(enabled = false)
    }

    @Tool(description = "Opens the Wi-Fi settings.")
    fun open_wifi_settings(): String {
        val actionLabel = "open_wifi_settings"
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val msg = "Wi-Fi settings opened."
            recordAction(actionLabel, emptyMap(), msg, success = true)
        } catch (e: Exception) {
            val msg = "Failed to open Wi-Fi settings: ${e.localizedMessage ?: "Unknown error"}"
            recordAction(actionLabel, emptyMap(), msg, success = false)
        }
    }

    private fun setFlashlight(enabled: Boolean): String {
        val actionLabel = if (enabled) "turn_on_flashlight" else "turn_off_flashlight"
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                val msg = "Camera service is not available on this device."
                return recordAction(actionLabel, emptyMap(), msg, success = false)
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                try {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (e: Exception) {
                    false
                }
            }
            if (cameraId == null) {
                val msg = "This device doesn't have a flashlight."
                recordAction(actionLabel, emptyMap(), msg, success = false)
            } else {
                cameraManager.setTorchMode(cameraId, enabled)
                val msg = "Flashlight turned ${if (enabled) "on" else "off"}."
                recordAction(actionLabel, emptyMap(), msg, success = true)
            }
        } catch (e: Exception) {
            val msg = "Flashlight error: ${e.localizedMessage ?: "Unknown error"}"
            recordAction(actionLabel, emptyMap(), msg, success = false)
        }
    }
}

/**
 * Registry holding domain-specific ToolSets and routing prompts to minimal tool schemas.
 */
class MobileActionsToolRegistry(
    private val context: Context,
    val onActionExecuted: ((actionName: String, params: Map<String, Any?>, resultMessage: String) -> Unit)? = null
) {
    val flashlightToolSet = FlashlightToolSet(context, onActionExecuted)
    val contactToolSet = ContactToolSet(context, onActionExecuted)
    val emailToolSet = EmailToolSet(context, onActionExecuted)
    val mapToolSet = MapToolSet(context, onActionExecuted)
    val wifiToolSet = WifiToolSet(context, onActionExecuted)
    val calendarToolSet = CalendarToolSet(context, onActionExecuted)
    val generalToolSet = GeneralMobileActionsToolSet(context, onActionExecuted)

    fun routeCategory(prompt: String): ToolCategory {
        val p = prompt.lowercase().trim()
        return when {
            p.contains("flash") || p.contains("torch") || p.contains("light") -> ToolCategory.FLASHLIGHT
            p.contains("contact") || p.contains("person") -> ToolCategory.CONTACT
            p.contains("email") || p.contains("mail") -> ToolCategory.EMAIL
            p.contains("map") || p.contains("location") || p.contains("navigate") || p.contains("where") || p.contains("place") -> ToolCategory.MAP
            p.contains("wifi") || p.contains("wi-fi") || p.contains("network") || p.contains("internet") || p.contains("setting") -> ToolCategory.WIFI
            p.contains("calendar") || p.contains("event") || p.contains("schedule") || p.contains("appointment") -> ToolCategory.CALENDAR
            else -> ToolCategory.GENERAL
        }
    }

    fun getToolSet(category: ToolCategory): ActionTrackingToolSet {
        return when (category) {
            ToolCategory.FLASHLIGHT -> flashlightToolSet
            ToolCategory.CONTACT -> contactToolSet
            ToolCategory.EMAIL -> emailToolSet
            ToolCategory.MAP -> mapToolSet
            ToolCategory.WIFI -> wifiToolSet
            ToolCategory.CALENDAR -> calendarToolSet
            ToolCategory.GENERAL -> generalToolSet
        }
    }
}

// Backwards-compatibility alias for previous imports
typealias MobileActionsToolSet = FlashlightToolSet
