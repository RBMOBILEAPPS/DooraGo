# DooraGo Wake-Word ("Doora") Local Training Plan

This document outlines the local machine learning pipeline to train, validate, quantize, and export an ultra-lightweight Keyword Spotting (KWS) model for **"Doora"**.

---

## 1. Target Model Architecture

For continuous mobile listening, the architecture must balance high discriminative accuracy with minimal CPU and RAM usage.

### Primary Architecture: Streaming Depthwise-Separable CNN (DS-CNN-Tiny)
- **Why DS-CNN?**
  - Factorizes standard 2D convolutions into depthwise and pointwise layers, reducing multiply-accumulate (MAC) operations by 85–90% compared to standard CNNs.
  - Parameter footprint: $\sim 150\text{KB} - 400\text{KB}$ after Int8 quantization.
  - Single-threaded inference latency: $< 10\text{ms}$ on low-end ARM Cortex-A53/A55 CPUs.
- **Alternative Architecture: Tiny BC-ResNet-1** (Broadcasted Residual Network)
  - Broadcast residual connections offer state-of-the-art accuracy with $< 200\text{K}$ parameters.

---

## 2. Audio Preprocessing Specification (Log-Mel Filterbanks)

The audio feature extraction must be mathematically identical during local training and runtime inference on Android.

| Preprocessing Parameter | Target Value |
| :--- | :--- |
| **Audio Input** | 16,000 Hz, 16-bit Mono Linear PCM (1.0 second = 16,000 samples) |
| **Window Type** | Hann / Periodic Hann |
| **Window Length (Frame Size)** | $30\text{ms}$ ($480$ samples at $16\text{kHz}$) or $40\text{ms}$ ($640$ samples) |
| **Hop Length (Stride)** | $10\text{ms}$ ($160$ samples) or $20\text{ms}$ ($320$ samples) |
| **FFT Size (`n_fft`)** | $512$ |
| **Mel Filterbanks (`n_mels`)** | $40$ bins |
| **Frequency Range** | $80\text{ Hz}$ (Low) to $7,600\text{ Hz}$ (High) |
| **Dynamic Range Compression** | $\log(\text{MelEnergies} + 1\text{e}-6)$ |
| **Output Feature Matrix** | Shape: `[1, 98, 40, 1]` (Batch, Time Frames, Mel Bins, Channels) |

---

## 3. Data Augmentation Pipeline

Augmentation increases model resilience to real-world acoustic variance without overwriting original local audio clips.

1. **Noise Injection**: Mix random room ambient, ceiling fan, traffic, and white noise at SNRs ranging from $+5\text{dB}$ to $+25\text{dB}$.
2. **Time Jitter / Roll**: Circularly shift 1.0s audio clips by $\pm 100\text{ms} - 200\text{ms}$.
3. **Gain Perturbation**: Randomly scale audio amplitude between $-6\text{dB}$ and $+6\text{dB}$ (volume variation).
4. **Room Impulse Response (RIR)**: Apply light reverberation filters simulating diverse room acoustic dimensions.
5. **Pitch / Formant Shift**: Apply very slight pitch perturbation ($\pm 1$ semitone max) to avoid altering phoneme identity.

---

## 4. Training Hyperparameters

- **Loss Function**: Weighted Cross-Entropy or Focal Loss (to counteract positive vs. negative class imbalance).
- **Optimizer**: AdamW (Learning Rate: $1\text{e}-3$, Weight Decay: $1\text{e}-4$) with Cosine Annealing scheduler.
- **Batch Size**: 32 or 64.
- **Epochs**: 50 – 80 with Early Stopping on speaker-disjoint validation loss (patience: 10 epochs).
- **Output Classes**: 2 classes (`[0: non_doora, 1: doora]`) or 3 classes (`[0: silence/noise, 1: doora, 2: unknown_speech]`).

---

## 5. Quantization & TFLite Export Strategy

To ensure optimal battery consumption on Android:
1. **Post-Training Full Integer Quantization (Int8)**:
   - Quantize all weights and activations from 32-bit float to 8-bit signed integer (`int8`).
   - Use a representative dataset calibration loop (200–500 sample frames) during TFLite conversion to compute accurate scaling factors ($\text{scale}, \text{zero\_point}$).
   - Set input/output interfaces: Int8 or Float32 with embedded quantize/dequantize nodes.
2. **Artifact Verification**:
   - Confirm exported `.tflite` model size is $\le 1.0\text{ MB}$.
   - Validate model inference using `tf.lite.Interpreter` against holdout test WAV clips before deployment.
