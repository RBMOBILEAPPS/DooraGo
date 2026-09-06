@echo off
setlocal enabledelayedexpansion

echo =======================================================================
echo  DooraGo - Local Development Model Installer
echo  Target: MobileActions-270M (FunctionGemma) LiteRT-LM
echo  Package: com.rbapps.doorago
echo =======================================================================

set DEFAULT_MODEL_PATH=D:\DooraGo\mobile_actions_q8_ekv1024.litertlm
set MODEL_FILE=%1

if "%MODEL_FILE%"=="" (
    set MODEL_FILE=%DEFAULT_MODEL_PATH%
)

echo.
echo [1/4] Checking local model file: %MODEL_FILE%
if not exist "%MODEL_FILE%" (
    echo [ERROR] Model file not found at: %MODEL_FILE%
    echo Please make sure the model is located at %DEFAULT_MODEL_PATH%
    echo or supply the path as an argument:
    echo   scripts\install_model.bat "C:\path\to\mobile_actions_q8_ekv1024.litertlm"
    exit /b 1
)

for %%I in ("%MODEL_FILE%") do set FILE_SIZE=%%~zI
echo [INFO] Found local model file (%FILE_SIZE% bytes). Expected: 288964608 bytes.

echo.
echo [2/4] Checking ADB device connectivity...
adb get-state >nul 2>&1
if errorlevel 1 (
    echo [ERROR] No connected Android device detected.
    echo Ensure your device or emulator is connected with USB Debugging enabled:
    echo   adb devices
    exit /b 1
)
for /f "tokens=*" %%i in ('adb get-serialno') do set DEVICE_ID=%%i
echo [INFO] Connected to Android device: %DEVICE_ID%

echo.
echo [3/4] Pushing model file to device temporary staging directory (/data/local/tmp/)...
adb push "%MODEL_FILE%" /data/local/tmp/mobile_actions_q8_ekv1024.litertlm
if errorlevel 1 (
    echo [ERROR] ADB push command failed.
    exit /b 1
)
echo [INFO] Pushed successfully to /data/local/tmp/mobile_actions_q8_ekv1024.litertlm

echo.
echo [4/4] Installing into DooraGo internal storage (context.filesDir/models/)...
rem Attempt run-as copy directly into app internal storage sandbox
adb shell "run-as com.rbapps.doorago mkdir -p files/models && cp /data/local/tmp/mobile_actions_q8_ekv1024.litertlm files/models/mobile_actions_q8_ekv1024.litertlm && chmod 600 files/models/mobile_actions_q8_ekv1024.litertlm" >nul 2>&1

adb shell "run-as com.rbapps.doorago ls -l files/models/mobile_actions_q8_ekv1024.litertlm" >nul 2>&1
if errorlevel 1 (
    echo [NOTICE] run-as sandbox copy restricted on this build/device.
    echo [NOTICE] Model is securely staged at /data/local/tmp/mobile_actions_q8_ekv1024.litertlm.
    echo [NOTICE] DooraGo app will automatically detect and import it into filesDir/models/ on first launch!
) else (
    echo [SUCCESS] Model copied directly to /data/user/0/com.rbapps.doorago/files/models/mobile_actions_q8_ekv1024.litertlm!
    rem Clean up staging file
    adb shell "rm /data/local/tmp/mobile_actions_q8_ekv1024.litertlm" >nul 2>&1
)

echo.
echo =======================================================================
echo  Installation complete! Launch DooraGo to initialize LiteRT-LM.
echo =======================================================================
