package com.rbapps.doorago

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Native LiteRT-LM Engine Controller for DooraGo.
 *
 * Runs the fine-tuned MobileActions-270M (FunctionGemma) model strictly on-device
 * using Google's official LiteRT-LM Android SDK.
 *
 * CONTEXT CAPACITY & MINIMAL SCHEMAS:
 * The model (mobile_actions_q8_ekv1024.litertlm) has a 1024-token KV-cache context.
 * To keep prefill well under 1024 tokens (~60 tokens for flashlight), [MobileActionsToolRegistry]
 * routes prompts to only the domain-specific ToolSet needed (e.g. FlashlightToolSet with 2 tools).
 */
class LiteRtLmAiEngine(
    private val context: Context,
    private val toolRegistry: MobileActionsToolRegistry? = null,
    private val activityProvider: (() -> Activity?)? = null
) {

    companion object {
        private const val TAG = "LiteRtLmAiEngine"
        private const val NATIVE_TAG = "DooraGoNative"

        const val MODEL_REPO = "litert-community/functiongemma-270m-ft-mobile-actions"
        const val MODEL_FILENAME = "mobile_actions_q8_ekv1024.litertlm"
        const val MODEL_ALIAS_FILENAME = "mobile-actions_q8_ekv1024.litertlm"
        const val EXPECTED_MODEL_SIZE = 288964608L
        const val EXPECTED_MODEL_SHA256 = "33E295CBD996B419BB1DE8F3F85C5B6B01EE058A2C89BDB2173CF3E6FF4CE9D0"

        const val ERROR_CODE_MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
        const val ERROR_CODE_INIT_FAILED = "INIT_FAILED"
        const val ERROR_CODE_NOT_READY = "NOT_READY"
        const val ERROR_CODE_INFERENCE_ERROR = "INFERENCE_ERROR"
        const val ERROR_CODE_INSTALL_FAILED = "INSTALL_FAILED"

        // FunctionGemma standard system instruction (~14 tokens)
        private const val SYSTEM_INSTRUCTION = "You are a helpful assistant with access to the following functions. Use them if required."
    }

    enum class EngineState(val value: String) {
        UNINITIALIZED("uninitialized"),
        INITIALIZING("initializing"),
        READY("ready"),
        PROCESSING("processing"),
        ERROR("error")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateMutex = Mutex()

    // Engine holds loaded model weights — resident throughout app session
    private var engine: Engine? = null

    // Single active Conversation — closed and recreated per command to guarantee clean 0-token KV-cache
    private var conversation: Conversation? = null

    private var currentState: EngineState = EngineState.UNINITIALIZED
    private var lastErrorMessage: String? = null
    private var lastErrorCode: String? = null
    private var activeModelPath: String? = null

    val modelDeliveryManager: ModelDeliveryManager by lazy {
        ModelDeliveryManager(
            context = context,
            activityProvider = activityProvider,
            onStatusChanged = { status ->
                val extraMap = mapOf(
                    "aiPackState" to status.state.value,
                    "aiPackProgress" to status.progressPercent,
                    "aiPackBytesDownloaded" to status.bytesDownloaded,
                    "aiPackTotalBytes" to status.totalBytesToDownload
                )
                onStatusChanged?.invoke(currentState, status.message, extraMap)
            }
        )
    }

    var onStatusChanged: ((state: EngineState, message: String?, extra: Map<String, Any?>) -> Unit)? = null

    fun getCurrentState(): EngineState = currentState
    fun getLastErrorMessage(): String? = lastErrorMessage
    fun getLastErrorCode(): String? = lastErrorCode
    fun getActiveModelPath(): String? = activeModelPath
    fun getModelDeliveryStatus(): ModelDeliveryStatus = modelDeliveryManager.getCurrentStatus()

    private fun updateState(
        newState: EngineState,
        message: String? = null,
        errorCode: String? = null,
        extra: Map<String, Any?> = emptyMap()
    ) {
        currentState = newState
        if (newState == EngineState.ERROR) {
            lastErrorMessage = message
            lastErrorCode = errorCode
        } else if (newState == EngineState.READY) {
            lastErrorMessage = null
            lastErrorCode = null
        }
        Log.i(TAG, "[ENGINE_STATE] -> ${newState.value} | $message | code=$errorCode")
        val combinedExtra = extra.toMutableMap()
        if (errorCode != null) combinedExtra["errorCode"] = errorCode
        onStatusChanged?.invoke(newState, message, combinedExtra)
    }

    fun getExpectedInternalModelFile(): File {
        val modelsDir = File(context.filesDir, "models")
        return File(modelsDir, MODEL_FILENAME)
    }

    fun calculateSha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(256 * 1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val bytes = digest.digest()
            val sb = StringBuilder()
            for (b in bytes) sb.append(String.format("%02X", b))
            sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute SHA-256 for ${file.name}: ${e.message}")
            null
        }
    }

    fun verifyModel(checkHash: Boolean = false): Map<String, Any?> {
        val file = getExpectedInternalModelFile()
        val exists = file.exists() && file.isFile
        val size = if (exists) file.length() else 0L
        val sizeMatches = size == EXPECTED_MODEL_SIZE
        val hash = if (exists && checkHash) calculateSha256(file) else null
        val hashMatches = if (hash != null) hash.equals(EXPECTED_MODEL_SHA256, ignoreCase = true) else null
        return mapOf(
            "exists" to exists,
            "path" to file.absolutePath,
            "filename" to MODEL_FILENAME,
            "size" to size,
            "expectedSize" to EXPECTED_MODEL_SIZE,
            "sizeMatches" to sizeMatches,
            "sha256" to hash,
            "expectedSha256" to EXPECTED_MODEL_SHA256,
            "sha256Matches" to hashMatches
        )
    }

    fun installModel(sourcePath: String): Map<String, Any?> {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() == 0L) {
            return mapOf("installed" to false, "error" to "Source file does not exist or is empty: $sourcePath")
        }
        val installedFile = installFromSource(sourceFile)
        return if (installedFile != null && installedFile.exists()) {
            mapOf(
                "installed" to true,
                "path" to installedFile.absolutePath,
                "size" to installedFile.length(),
                "verification" to verifyModel()
            )
        } else {
            mapOf("installed" to false, "error" to "Failed to copy model file to internal storage")
        }
    }

    /**
     * Initializes the LiteRT-LM Engine off the UI thread and verifies runtime readiness.
     */
    fun initialize(
        modelPathOverride: String? = null,
        onComplete: (success: Boolean, errorCode: String?, errorMessage: String?) -> Unit
    ) {
        scope.launch {
            stateMutex.withLock {
                if (currentState == EngineState.INITIALIZING) {
                    onComplete(false, ERROR_CODE_INIT_FAILED, "Initialization already in progress")
                    return@launch
                }

                updateState(EngineState.INITIALIZING, "Locating model and allocating runtime...")
                Log.i(TAG, "[ENGINE_INIT] Starting initialization")

                try {
                    val resolvedModelFile = resolveModelFile(modelPathOverride)
                    if (resolvedModelFile == null || !resolvedModelFile.exists() || resolvedModelFile.length() == 0L) {
                        modelDeliveryManager.checkAndFetchModelPack()
                        val packStatus = modelDeliveryManager.getCurrentStatus()
                        val errMsg = "Preparing Offline AI: Download via Google Play in progress (${packStatus.progressPercent}%). Device commands remain active."
                        Log.w(TAG, "[ENGINE_INIT] $errMsg")
                        updateState(
                            EngineState.ERROR, errMsg, ERROR_CODE_MODEL_NOT_FOUND,
                            mapOf(
                                "missingFile" to MODEL_FILENAME,
                                "aiPackState" to packStatus.state.value,
                                "aiPackProgress" to packStatus.progressPercent,
                                "aiPackBytesDownloaded" to packStatus.bytesDownloaded,
                                "aiPackTotalBytes" to packStatus.totalBytesToDownload
                            )
                        )
                        onComplete(false, ERROR_CODE_MODEL_NOT_FOUND, errMsg)
                        return@launch
                    }

                    activeModelPath = resolvedModelFile.absolutePath
                    val fileSize = resolvedModelFile.length()
                    Log.i(TAG, "[MODEL_LOAD] Loading from: $activeModelPath ($fileSize bytes)")

                    // Dispose any existing resources before initializing
                    safeCloseConversation()
                    safeCloseEngine()

                    val engineConfig = EngineConfig(
                        modelPath = resolvedModelFile.absolutePath,
                        backend = Backend.CPU()
                    )
                    val newEngine = Engine(engineConfig)
                    Log.i(TAG, "[MODEL_LOAD] Calling blocking Engine.initialize()...")
                    newEngine.initialize()   // Blocking — executed on Dispatchers.IO via scope
                    Log.i(TAG, "[MODEL_LOAD] Engine.initialize() complete")

                    // Verify conversation creation using minimal ToolSet
                    val testToolSet = toolRegistry?.flashlightToolSet
                    val testConfig = if (testToolSet != null) {
                        ConversationConfig(
                            systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                            tools = listOf(tool(testToolSet)),
                            automaticToolCalling = true
                        )
                    } else {
                        ConversationConfig(
                            systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                            automaticToolCalling = true
                        )
                    }
                    val testConversation = newEngine.createConversation(testConfig)
                    testConversation.close() // Close test session immediately; fresh ones created per prompt

                    engine = newEngine

                    updateState(
                        EngineState.READY,
                        "Model loaded successfully (${resolvedModelFile.name})",
                        null,
                        mapOf("modelPath" to activeModelPath, "fileSize" to fileSize)
                    )
                    onComplete(true, null, null)
                } catch (e: Throwable) {
                    val errMsg = "Failed to initialize LiteRT-LM engine: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    Log.e(TAG, "[ENGINE_INIT] $errMsg", e)
                    updateState(EngineState.ERROR, errMsg, ERROR_CODE_INIT_FAILED, mapOf("exception" to e.toString()))
                    onComplete(false, ERROR_CODE_INIT_FAILED, errMsg)
                }
            }
        }
    }

    private val normalizer = MobileActionNormalizer(
        context,
        toolRegistry ?: MobileActionsToolRegistry(context),
        activityProvider
    )

    /**
     * Executes a device command via hybrid local normalization and on-device FunctionGemma inference.
     *
     * 1. Local normalization handles deterministic commands, multi-turn pending actions, and relative dates.
     * 2. When required, category router identifies the minimal ToolSet (e.g. FlashlightToolSet with 2 tools).
     * 3. ConversationConfig is created with ONLY that ToolSet (~60 tokens schema prefill).
     * 4. Previous Conversation is closed, fresh Conversation created from resident Engine.
     * 5. FunctionGemma model selects tool and calls it.
     */
    suspend fun processPrompt(prompt: String): Result<String> = withContext(Dispatchers.IO) {

        // --- Phase 1: Local Command & Parameter Normalization (Hybrid Architecture) ---
        val normalization = normalizer.process(prompt)

        when (normalization) {
            is NormalizationResult.ExecuteDirect -> {
                Log.i(NATIVE_TAG, "[TOOL_CALL] name=${normalization.toolName}")
                val resultMsg = try {
                    normalization.executionBlock()
                } catch (e: Throwable) {
                    val errMsg = "Execution error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    Log.e(TAG, "[DIRECT_EXEC_ERROR] $errMsg", e)
                    "Couldn't execute the command."
                }
                return@withContext Result.success(resultMsg)
            }
            is NormalizationResult.AskFollowUp -> {
                Log.i(NATIVE_TAG, "[FOLLOW_UP] Asking user: ${normalization.promptToUser}")
                return@withContext Result.success(normalization.promptToUser)
            }
            is NormalizationResult.RouteToLlm -> {
                // Proceed to FunctionGemma on-device inference with category-specific minimal ToolSet
            }
        }

        val category: ToolCategory = normalization.category
        val selectedToolSet: ActionTrackingToolSet = toolRegistry?.getToolSet(category)
            ?: FlashlightToolSet(context)
        val inferencePrompt = normalization.normalizedPrompt

        val freshConversation: Conversation = stateMutex.withLock {
            val currentEngine = engine
            if (currentState != EngineState.READY || currentEngine == null) {
                val err = "Engine not ready. State=${currentState.value}. ${lastErrorMessage ?: "Call initialize first."}"
                Log.w(TAG, "[INFERENCE_START] Rejected: $err")
                return@withContext Result.failure(IllegalStateException(err))
            }

            // Clear previous action execution state
            selectedToolSet.clearLastAction()

            updateState(EngineState.PROCESSING, "Running on-device inference...")

            // Close prior session to ensure 0-token starting KV-cache
            safeCloseConversation()

            val toolObject = tool(selectedToolSet)
            val toolList = listOf(toolObject)

            Log.i(NATIVE_TAG, "[TOOL_CONFIG] category=${category.label}")
            Log.i(NATIVE_TAG, "[TOOL_CONFIG] toolCount=${category.toolNames.size}")
            Log.i(NATIVE_TAG, "[TOOL_CONFIG] tools=${category.toolNames.joinToString(",")}")
            Log.i(NATIVE_TAG, "[TOOL_CONFIG] automaticToolCalling=true")

            val config = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                tools = toolList,
                automaticToolCalling = true
            )

            try {
                val fresh = currentEngine.createConversation(config)
                conversation = fresh
                Log.i(NATIVE_TAG, "[CONVERSATION_CREATE] toolsRegistered=true")
                Log.i(NATIVE_TAG, "[CONVERSATION_CREATE] toolCount=${category.toolNames.size}")
                fresh
            } catch (e: Throwable) {
                val errMsg = "Failed to create inference session: ${e.localizedMessage ?: e.javaClass.simpleName}"
                Log.e(TAG, "[CONVERSATION_CREATE] $errMsg", e)
                updateState(EngineState.READY, "Ready (session reset failed)")
                return@withContext Result.failure(e)
            }
        }

        // --- Phase 2: Inference (mutex NOT held — non-blocking to other state checks) ---
        return@withContext try {
            Log.i(NATIVE_TAG, "[INFERENCE_START] prompt='$inferencePrompt'")

            val responseBuilder = StringBuilder()
            freshConversation.sendMessageAsync(inferencePrompt).collect { chunk ->
                responseBuilder.append(chunk.toString())
            }
            val rawResponse = responseBuilder.toString().trim()
            val actionResult = selectedToolSet.lastActionResult

            if (actionResult == null && rawResponse.isNotEmpty()) {
                Log.i(NATIVE_TAG, "[MODEL_TEXT_RESPONSE] $rawResponse")
            }

            // If a tool was executed, return the tool's structured result message
            // even if FunctionGemma emitted no text or its stock disclaimer.
            val responseText = when {
                actionResult != null -> actionResult
                rawResponse.isNotEmpty() && !rawResponse.startsWith("I am FunctionGemma") && !rawResponse.contains("do not have a tool") -> rawResponse
                rawResponse.isNotEmpty() -> rawResponse
                else -> "Action completed."
            }

            stateMutex.withLock { updateState(EngineState.READY, "Inference completed") }
            Result.success(responseText)
        } catch (e: Throwable) {
            val errMsg = "Inference error: ${e.localizedMessage ?: e.javaClass.simpleName}"
            Log.e(TAG, "[INFERENCE_ERROR] Category=${category.label} | $errMsg", e)
            // Recoverable inference failure: close session and return engine to READY
            stateMutex.withLock {
                safeCloseConversation()
                updateState(EngineState.READY, "Ready (retry available)")
            }
            Result.failure(e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Model file resolution
    // ──────────────────────────────────────────────────────────────

    private fun resolveModelFile(customPath: String?): File? {
        // 1. Check Google Play AI Pack fast-follow delivery (zero-copy direct loading)
        val playAiPackFile = modelDeliveryManager.getInstalledAiPackModelFile()
        if (playAiPackFile != null && playAiPackFile.exists() && playAiPackFile.length() == EXPECTED_MODEL_SIZE) {
            Log.i(TAG, "[MODEL_LOAD] Using direct Google Play AI Pack model path: ${playAiPackFile.absolutePath} (Zero duplicate storage)")
            return playAiPackFile
        }

        // 2. Check internal app files directory
        val internalModel = getExpectedInternalModelFile()
        if (internalModel.exists() && internalModel.isFile) {
            if (internalModel.length() == EXPECTED_MODEL_SIZE) {
                Log.i(TAG, "[MODEL_LOAD] Using existing valid internal model (${internalModel.length()} bytes)")
                return internalModel
            } else {
                Log.w(TAG, "[MODEL_LOAD] Internal model size mismatch (${internalModel.length()} bytes vs expected $EXPECTED_MODEL_SIZE). Deleting incomplete file.")
                try { internalModel.delete() } catch (_: Exception) { }
            }
        }

        val aliasModel = File(File(context.filesDir, "models"), MODEL_ALIAS_FILENAME)
        if (aliasModel.exists() && aliasModel.isFile) {
            if (aliasModel.length() == EXPECTED_MODEL_SIZE) {
                Log.i(TAG, "[MODEL_LOAD] Found valid alias model; renaming to $MODEL_FILENAME")
                return try {
                    if (aliasModel.renameTo(internalModel)) internalModel else aliasModel
                } catch (_: Exception) { aliasModel }
            } else {
                Log.w(TAG, "[MODEL_LOAD] Alias model size mismatch (${aliasModel.length()} bytes). Deleting.")
                try { aliasModel.delete() } catch (_: Exception) { }
            }
        }

        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.isFile && customFile.length() > 0) {
                Log.i(TAG, "[MODEL_LOAD] Installing from custom path: ${customFile.absolutePath}")
                val installed = installFromSource(customFile)
                if (installed != null) return installed
            }
        }

        val stagingLocations = listOf(
            File("/data/local/tmp", MODEL_FILENAME),
            File("/data/local/tmp", MODEL_ALIAS_FILENAME),
            File(context.getExternalFilesDir(null), "models/$MODEL_FILENAME"),
            File("/sdcard/Download", MODEL_FILENAME),
            File("/storage/emulated/0/Download", MODEL_FILENAME)
        )
        for (stageFile in stagingLocations) {
            try {
                if (stageFile.exists() && stageFile.isFile && stageFile.canRead() && stageFile.length() == EXPECTED_MODEL_SIZE) {
                    Log.i(TAG, "[MODEL_LOAD] Found valid model at staging location: ${stageFile.absolutePath}. Copying...")
                    val installed = installFromSource(stageFile)
                    if (installed != null) return installed
                }
            } catch (e: Exception) {
                Log.d(TAG, "[MODEL_LOAD] Staging check skipped for ${stageFile.path}: ${e.message}")
            }
        }

        val assetNamesToTry = listOf(
            "models/$MODEL_FILENAME",
            "models/$MODEL_ALIAS_FILENAME",
            "flutter_assets/assets/models/$MODEL_FILENAME"
        )
        for (assetPath in assetNamesToTry) {
            try {
                context.assets.open(assetPath).use { input ->
                    Log.i(TAG, "[MODEL_LOAD] Found bundled model in Android assets: $assetPath. Extracting to internal storage...")
                    val extractedFile = copyStream(input, internalModel)
                    if (extractedFile != null && extractedFile.exists() && extractedFile.length() == EXPECTED_MODEL_SIZE) {
                        Log.i(TAG, "[MODEL_LOAD] Successfully extracted packaged asset model to ${extractedFile.absolutePath}")
                        return extractedFile
                    }
                }
            } catch (_: Exception) { }
        }

        Log.e(TAG, "[MODEL_LOAD] Model artifact '$MODEL_FILENAME' could not be found or extracted from assets/staging.")
        return null
    }

    private fun installFromSource(sourceFile: File): File? {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val targetFile = File(modelsDir, MODEL_FILENAME)
        val tempFile = File(modelsDir, "${MODEL_FILENAME}.tmp_${System.currentTimeMillis()}")
        return try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            if (targetFile.exists()) targetFile.delete()
            if (tempFile.renameTo(targetFile)) {
                Log.i(TAG, "[MODEL_LOAD] Installed to: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                targetFile
            } else {
                Log.e(TAG, "[MODEL_LOAD] Failed to rename temp file to target path")
                if (tempFile.exists()) tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MODEL_LOAD] Error installing from ${sourceFile.absolutePath}: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            null
        }
    }

    private fun copyStream(input: InputStream, output: File): File? {
        val modelsDir = output.parentFile ?: File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val tempFile = File(modelsDir, "${output.name}.tmp_${System.currentTimeMillis()}")
        return try {
            FileOutputStream(tempFile).use { out ->
                val buffer = ByteArray(256 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
                out.flush()
            }
            if (output.exists()) output.delete()
            if (tempFile.renameTo(output)) {
                Log.i(TAG, "[MODEL_LOAD] Extracted stream to ${output.absolutePath} (${output.length()} bytes)")
                output
            } else {
                Log.e(TAG, "[MODEL_LOAD] Failed to rename temp file to ${output.name}")
                if (tempFile.exists()) tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MODEL_LOAD] Error extracting stream to ${output.name}: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Safe close helpers — never throw, always null the reference
    // ──────────────────────────────────────────────────────────────

    private fun safeCloseConversation() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "[ENGINE_CLOSE] conversation.close() threw (non-critical): ${e.message}")
        } finally {
            conversation = null
        }
    }

    private fun safeCloseEngine() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "[ENGINE_CLOSE] engine.close() threw (non-critical): ${e.message}")
        } finally {
            engine = null
        }
    }

    /**
     * Releases model weights, native delegates, and closes active sessions.
     */
    fun dispose() {
        scope.launch {
            stateMutex.withLock {
                Log.i(TAG, "[ENGINE_CLOSE] Disposing engine and conversation")
                safeCloseConversation()
                safeCloseEngine()
                updateState(EngineState.UNINITIALIZED, "Engine disposed")
            }
        }
    }
}
