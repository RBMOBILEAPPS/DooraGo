package com.rbapps.doorago

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackLocation
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File

/**
 * Model Pack State Machine for Google Play AI Pack fast-follow delivery.
 */
enum class ModelPackState(val value: String) {
    NOT_AVAILABLE("not_available"),
    CHECKING("checking"),
    DOWNLOADING("downloading"),
    WAITING_FOR_WIFI("waiting_for_wifi"),
    READY("ready"),
    FAILED("failed")
}

data class ModelDeliveryStatus(
    val state: ModelPackState,
    val message: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytesToDownload: Long = 288964608L,
    val localPath: String? = null,
    val errorCode: Int? = null
)

class ModelDeliveryManager(
    private val context: Context,
    private val activityProvider: (() -> Activity?)? = null,
    private val onStatusChanged: ((ModelDeliveryStatus) -> Unit)? = null
) {
    companion object {
        private const val TAG = "ModelDeliveryManager"
        const val PACK_NAME = "doora_ai_model"
        const val MODEL_FILENAME = "mobile_actions_q8_ekv1024.litertlm"
        const val EXPECTED_MODEL_SIZE = 288964608L
        const val EXPECTED_MODEL_SHA256 = "33E295CBD996B419BB1DE8F3F85C5B6B01EE058A2C89BDB2173CF3E6FF4CE9D0"
    }

    private val assetPackManager: AssetPackManager by lazy {
        AssetPackManagerFactory.getInstance(context)
    }

    private var currentStatus: ModelDeliveryStatus = ModelDeliveryStatus(
        state = ModelPackState.CHECKING,
        message = "Checking offline AI model availability..."
    )

    private val listener = AssetPackStateUpdateListener { state ->
        if (state.name() == PACK_NAME) {
            handlePackStateUpdate(state)
        }
    }

    fun getCurrentStatus(): ModelDeliveryStatus = currentStatus

    /**
     * Checks if the AI pack is already downloaded and valid locally.
     * Returns the direct filesystem path to the model file inside the Play AI Pack directory, or null.
     */
    fun getInstalledAiPackModelFile(): File? {
        return try {
            val location: AssetPackLocation? = assetPackManager.getPackLocation(PACK_NAME)
            if (location != null) {
                val assetsPath = location.assetsPath()
                if (!assetsPath.isNullOrBlank()) {
                    val candidate1 = File(assetsPath, "models/$MODEL_FILENAME")
                    if (candidate1.exists() && candidate1.isFile && candidate1.length() == EXPECTED_MODEL_SIZE) {
                        return candidate1
                    }
                    val candidate2 = File(assetsPath, MODEL_FILENAME)
                    if (candidate2.exists() && candidate2.isFile && candidate2.length() == EXPECTED_MODEL_SIZE) {
                        return candidate2
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Error checking asset pack location: ${e.message}")
            null
        }
    }

    fun checkAndFetchModelPack() {
        val localFile = getInstalledAiPackModelFile()
        if (localFile != null) {
            updateStatus(
                ModelDeliveryStatus(
                    state = ModelPackState.READY,
                    message = "Offline AI model pack is ready",
                    progressPercent = 100,
                    bytesDownloaded = EXPECTED_MODEL_SIZE,
                    totalBytesToDownload = EXPECTED_MODEL_SIZE,
                    localPath = localFile.absolutePath
                )
            )
            return
        }

        updateStatus(
            ModelDeliveryStatus(
                state = ModelPackState.CHECKING,
                message = "Preparing Offline AI: Requesting model download via Google Play (289 MB)..."
            )
        )

        try {
            assetPackManager.registerListener(listener)
            assetPackManager.fetch(listOf(PACK_NAME))
                .addOnSuccessListener { states ->
                    val packState = states.packStates()[PACK_NAME]
                    if (packState != null) {
                        handlePackStateUpdate(packState)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to fetch asset pack via Google Play: ${e.message}")
                    updateStatus(
                        ModelDeliveryStatus(
                            state = ModelPackState.NOT_AVAILABLE,
                            message = "Google Play fast-follow pack unavailable. Development/local fallback active."
                        )
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "Play Asset Manager exception: ${e.message}")
            updateStatus(
                ModelDeliveryStatus(
                    state = ModelPackState.NOT_AVAILABLE,
                    message = "Play Core services unavailable."
                )
            )
        }
    }

    private fun handlePackStateUpdate(state: AssetPackState) {
        when (state.status()) {
            AssetPackStatus.PENDING -> {
                updateStatus(
                    ModelDeliveryStatus(
                        state = ModelPackState.CHECKING,
                        message = "Preparing Offline AI: Download pending in Google Play queue..."
                    )
                )
            }
            AssetPackStatus.DOWNLOADING -> {
                val downloaded = state.bytesDownloaded()
                val total = if (state.totalBytesToDownload() > 0) state.totalBytesToDownload() else EXPECTED_MODEL_SIZE
                val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                updateStatus(
                    ModelDeliveryStatus(
                        state = ModelPackState.DOWNLOADING,
                        message = "Preparing Offline AI: Downloading model via Google Play ($percent%)...",
                        progressPercent = percent,
                        bytesDownloaded = downloaded,
                        totalBytesToDownload = total
                    )
                )
            }
            AssetPackStatus.TRANSFERRING -> {
                updateStatus(
                    ModelDeliveryStatus(
                        state = ModelPackState.DOWNLOADING,
                        message = "Preparing Offline AI: Installing model package...",
                        progressPercent = 99,
                        bytesDownloaded = EXPECTED_MODEL_SIZE,
                        totalBytesToDownload = EXPECTED_MODEL_SIZE
                    )
                )
            }
            AssetPackStatus.COMPLETED -> {
                val installedFile = getInstalledAiPackModelFile()
                if (installedFile != null) {
                    updateStatus(
                        ModelDeliveryStatus(
                            state = ModelPackState.READY,
                            message = "Offline AI model successfully downloaded and ready",
                            progressPercent = 100,
                            bytesDownloaded = EXPECTED_MODEL_SIZE,
                            totalBytesToDownload = EXPECTED_MODEL_SIZE,
                            localPath = installedFile.absolutePath
                        )
                    )
                } else {
                    updateStatus(
                        ModelDeliveryStatus(
                            state = ModelPackState.FAILED,
                            message = "Offline AI model download completed but file verification failed"
                        )
                    )
                }
                try { assetPackManager.unregisterListener(listener) } catch (_: Exception) {}
            }
            AssetPackStatus.WAITING_FOR_WIFI -> {
                updateStatus(
                    ModelDeliveryStatus(
                        state = ModelPackState.WAITING_FOR_WIFI,
                        message = "Preparing Offline AI: Download paused waiting for Wi-Fi (289 MB). User consent required for cellular data."
                    )
                )
                requestCellularConsentIfPossible()
            }
            AssetPackStatus.FAILED -> {
                updateStatus(
                    ModelDeliveryStatus(
                        state = ModelPackState.FAILED,
                        message = "Preparing Offline AI: Download failed (error code ${state.errorCode()}).",
                        errorCode = state.errorCode()
                    )
                )
                try { assetPackManager.unregisterListener(listener) } catch (_: Exception) {}
            }
            AssetPackStatus.CANCELED -> {
                updateStatus(
                    ModelDeliveryStatus(
                        state = ModelPackState.FAILED,
                        message = "Preparing Offline AI: Download canceled."
                    )
                )
                try { assetPackManager.unregisterListener(listener) } catch (_: Exception) {}
            }
            else -> {}
        }
    }

    fun requestCellularConsentIfPossible(): Boolean {
        return try {
            val activity = activityProvider?.invoke()
            if (activity != null) {
                assetPackManager.showCellularDataConfirmation(activity)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show cellular data confirmation: ${e.message}")
            false
        }
    }

    private fun updateStatus(newStatus: ModelDeliveryStatus) {
        currentStatus = newStatus
        Log.i(TAG, "[AI_PACK_STATUS] state=${newStatus.state.value} progress=${newStatus.progressPercent}% msg=${newStatus.message}")
        onStatusChanged?.invoke(newStatus)
    }

    fun dispose() {
        try {
            assetPackManager.unregisterListener(listener)
        } catch (_: Exception) {}
    }
}
