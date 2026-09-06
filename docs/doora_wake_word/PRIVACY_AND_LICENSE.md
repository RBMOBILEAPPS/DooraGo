# DooraGo Wake-Word Privacy & Licensing Compliance

This document outlines the privacy guarantees, Google Play Store compliance rules, and software licensing policies for the DooraGo wake-word subsystem.

---

## 1. On-Device Privacy Architecture

1. **Zero Cloud Audio Transmission**:
   - Audio recorded by the foreground service is processed exclusively in volatile device RAM.
   - Microphone buffers are never uploaded to any remote server or third-party cloud API.
2. **Zero Audio Persistence**:
   - Audio buffers are overwritten continuously in circular ring buffers in memory and discarded immediately after keyword inference.
   - No audio files, voice clips, or microphone telemetry are ever written to internal or external storage.
3. **Local Dataset Collection**:
   - The developer's training recordings remain stored locally on their workstation. No voice clips are collected from end users in production.

---

## 2. Google Play Store Compliance

| Requirement | Implementation Verification |
| :--- | :--- |
| **Microphone Permission** | Declared as `Manifest.permission.RECORD_AUDIO`. Requested with clear in-app rationale prior to service activation. |
| **Foreground Service Type** | Declared as `android:foregroundServiceType="microphone"` in `AndroidManifest.xml` (compliant with Android 14+ API 34+ policies). |
| **Ongoing Notification** | Displays a persistent, user-visible notification (*"DooraGo Voice Wake is active — Listening for 'Doora'..."*) with an explicit **"Turn off"** action. |
| **No Background Evasion** | Does NOT use `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`, `AccessibilityService`, or hidden overlays. |
| **User Transparency** | Feature requires explicit toggle activation by the user in the app UI and can be stopped at any time. |

---

## 3. Licensing Audit Checklist

When selecting external KWS training scripts, audio processing toolkits, or baseline architectures:

| Component | Allowed Licenses | Prohibited / Restricted Licenses |
| :--- | :--- | :--- |
| **Training Toolchain** | Apache 2.0, MIT, BSD 2/3-Clause | Proprietary non-commercial licenses |
| **Exported Model Weights** | Apache 2.0, MIT, CC-BY 4.0, or Proprietary Custom Ownership | GPL v3 (copyleft restriction on bundled assets), CC-BY-NC (Non-Commercial) |
| **Android Runtime SDK** | Apache 2.0 (`org.tensorflow:tensorflow-lite`, `com.google.ai.edge.litert`) | Any non-permissive copyleft runtime |
