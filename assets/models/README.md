# MobileActions-270M Model Weights Guide (LiteRT-LM)

This directory is designated for the offline LiteRT-LM model binary used by **DooraGo**.

## Verified Model Specifications

| Attribute | Specification |
| :--- | :--- |
| **Model Name** | FunctionGemma MobileActions-270M |
| **Hugging Face Repository** | `litert-community/functiongemma-270m-ft-mobile-actions` |
| **Format** | `.litertlm` (Google AI Edge LiteRT-LM) |
| **Exact Filename** | `mobile_actions_q8_ekv1024.litertlm` (Do NOT rename) |
| **Exact File Size** | `288,964,608 bytes` |
| **Verified SHA-256** | `33E295CBD996B419BB1DE8F3F85C5B6B01EE058A2C89BDB2173CF3E6FF4CE9D0` |
| **Quantization** | Q8 (8-bit quantized weights, EKV cache 1024) |
| **Runtime SDK** | `com.google.ai.edge.litertlm:litertlm-android:0.16.1` |
| **Target Storage Path** | `context.filesDir/models/mobile_actions_q8_ekv1024.litertlm` |
| **Network Requirements** | **0% Network / 100% Offline Local Device Inference** |

## Development Installation (Local Model)

The verified model file is located on the development workstation at:
```
D:\DooraGo\mobile_actions_q8_ekv1024.litertlm
```

The model is **NOT** bundled as a normal Flutter asset to prevent APK bloat and unnecessary duplication.

### Push to Connected Android Device via ADB:

#### Option A: Using the Automated Windows Script
```cmd
scripts\install_model.bat
```

#### Option B: Manual ADB Push Commands
```bash
# 1. Push model file to device staging directory:
adb push D:\DooraGo\mobile_actions_q8_ekv1024.litertlm /data/local/tmp/mobile_actions_q8_ekv1024.litertlm

# 2. Copy directly into DooraGo internal storage (if run-as is permitted):
adb shell "run-as com.rbapps.doorago mkdir -p files/models && cp /data/local/tmp/mobile_actions_q8_ekv1024.litertlm files/models/mobile_actions_q8_ekv1024.litertlm"

# If run-as is restricted on your device, the DooraGo app will automatically
# detect the staged file at /data/local/tmp/ and copy it into internal storage on first launch!
```

## Runtime Verification Rules

When DooraGo starts:
1. **Checks if model exists** at `context.filesDir/models/mobile_actions_q8_ekv1024.litertlm`.
2. **If it exists**, verifies size and filename. **It does NOT re-copy it on every launch**.
3. **If missing**, returns `MODEL_NOT_FOUND` error clearly with installation instructions.
4. **Never** downloads from the internet automatically.
5. **Never** uses cloud APIs. All inference is 100% local on device via LiteRT-LM.
