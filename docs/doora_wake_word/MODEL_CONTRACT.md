# DooraGo Wake-Word ("Doora") Model Contract Specification

This document defines the interface and execution requirements that any trained `.tflite` model must satisfy before being integrated into `DooraNeuralSpotter.kt`.

---

## 1. File Artifact Identity

| Parameter | Specification |
| :--- | :--- |
| **Model Filename** | `doora_kws.tflite` |
| **Deployment Asset Location** | `android/app/src/main/assets/models/doora_kws.tflite` |
| **Target Storage Fallback** | `context.filesDir/models/doora_kws.tflite` |
| **Format** | TensorFlow Lite FlatBuffer (`.tflite`) |
| **Quantization** | Full Int8 or Float32 with Int8 quantized weights |
| **Target Binary Size** | $\le 1,000,000\text{ bytes}$ ($< 1\text{ MB}$) |

---

## 2. Input Tensor Contract

The model input tensor must adhere to one of the two standard Keyword Spotting ingestion schemes:

### Scheme A: Raw Time-Domain PCM Ingestion (Preferred for Simplicity)
- **Tensor Name**: e.g., `audio_input` or `serving_default_input_1:0`
- **Tensor Shape**: `[1, 16000]` (1-dimensional 1.0-second time series)
- **Data Type**: `FLOAT32` (normalized to $[-1.0, 1.0]$) or `INT8` / `INT16`
- **Sample Rate**: `16,000 Hz` Mono Linear PCM

### Scheme B: Pre-extracted Spectral Feature Matrix (Log-Mel Spectrogram)
- **Tensor Name**: e.g., `spectrogram_input`
- **Tensor Shape**: `[1, 98, 40, 1]` or `[1, 49, 40, 1]` (Batch, Time Frames, Mel Bins, Channels)
- **Data Type**: `FLOAT32` or `INT8` (Quantized with fixed scale and zero-point)
- **Feature Standard**: 40 Mel filterbanks computed with Hann windowing ($30\text{ms}$ frame, $10\text{ms}$ hop, $512$ FFT).

---

## 3. Output Tensor Contract

- **Tensor Name**: e.g., `output_probabilities` or `Identity:0`
- **Tensor Shape**: `[1, 2]` (Two-class: `[0: non_doora, 1: doora]`) or `[1, 1]` (Single sigmoid probability $[0.0, 1.0]$)
- **Data Type**: `FLOAT32` (Softmax/Sigmoid probability score)
- **Confidence Evaluation**:
  $$\text{Confidence} = P(\text{class} = \text{"Doora"})$$
  Detection is triggered when $\text{Confidence} \ge \text{Threshold}$ across temporal debounce rules.

---

## 4. Runtime Latency & Memory Constraints

- **Execution Engine**: `org.tensorflow.lite.Interpreter` / LiteRT Tensor Runtime.
- **Max CPU Execution Time**: $< 15\text{ms}$ per 100ms inference step on standard mobile ARM64 cores.
- **RAM Footprint**: $< 4\text{MB}$ peak memory allocated for model weights and intermediate scratch tensors.
- **Thread Count**: 1 thread (single-threaded CPU execution inside background coroutine).
