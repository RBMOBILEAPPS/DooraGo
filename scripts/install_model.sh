#!/usr/bin/env bash
set -e

echo "======================================================================="
echo " DooraGo - Local Development Model Installer"
echo " Target: MobileActions-270M (FunctionGemma) LiteRT-LM"
echo " Package: com.rbapps.doorago"
echo "======================================================================="

DEFAULT_MODEL_PATH="mobile_actions_q8_ekv1024.litertlm"
MODEL_FILE="${1:-$DEFAULT_MODEL_PATH}"

if [ ! -f "$MODEL_FILE" ]; then
    echo "[ERROR] Model file not found at: $MODEL_FILE"
    echo "Usage: ./scripts/install_model.sh [path/to/mobile_actions_q8_ekv1024.litertlm]"
    exit 1
fi

echo "[1/3] Verifying ADB device connection..."
adb get-state > /dev/null 2>&1 || {
    echo "[ERROR] No connected Android device found. Run 'adb devices' to check."
    exit 1
}

echo "[2/3] Pushing model file to device temporary staging (/data/local/tmp/)..."
adb push "$MODEL_FILE" /data/local/tmp/mobile_actions_q8_ekv1024.litertlm

echo "[3/3] Installing into DooraGo internal storage..."
if adb shell "run-as com.rbapps.doorago mkdir -p files/models && cp /data/local/tmp/mobile_actions_q8_ekv1024.litertlm files/models/mobile_actions_q8_ekv1024.litertlm && chmod 600 files/models/mobile_actions_q8_ekv1024.litertlm" 2>/dev/null; then
    echo "[SUCCESS] Model installed directly to internal app storage!"
    adb shell "rm /data/local/tmp/mobile_actions_q8_ekv1024.litertlm" 2>/dev/null || true
else
    echo "[NOTICE] run-as sandbox copy restricted on this build/device."
    echo "[NOTICE] Model staged at /data/local/tmp/mobile_actions_q8_ekv1024.litertlm."
    echo "[NOTICE] DooraGo will auto-import it to internal storage on next app launch!"
fi

echo "======================================================================="
echo " Installation complete!"
echo "======================================================================="
