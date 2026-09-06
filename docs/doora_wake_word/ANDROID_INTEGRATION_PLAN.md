# DooraGo Wake-Word Android Integration Plan

This document describes the architectural steps required to integrate the trained `doora_kws.tflite` model into DooraGo's native Android subsystem once model weights are available.

---

## 1. Native Pipeline Architecture

```text
[ AudioRecord: 16kHz Mono 16-bit PCM ] (100ms / 1600 samples)
                 │
                 ▼
[ DooraNeuralSpotter.processFrame() ]
        ├─► Circular Ring Buffer (16,000 samples rolling window)
        │
        ├─► Stage 1: Energy VAD Gating (< 0.1% CPU)
        │       └─► [Silence / Low Noise] ──► Skip Neural Inference (0% ML CPU)
        │
        └─► Stage 2: TFLite / LiteRT Interpreter Ingestion
                ├─► Format Audio Window / Compute Mel Spectrogram
                ├─► Run TFLite Interpreter Inference
                └─► Extract Confidence Score
                          │
                          ▼
        [ Confidence >= Dynamic Threshold (e.g. 0.85) ]
                          │
                          ▼
        [ Temporal Debounce & Cooldown Check (2000ms) ]
                          │
                          ▼
        [ Trigger Wake Tone + Release AudioRecord + SpeechRecognizer STT ]
```

---

## 2. Dependency & Gradle Configuration

When model weights are ready for integration:

### In `android/app/build.gradle`:
```groovy
dependencies {
    // Standard TensorFlow Lite runtime for lightweight on-device KWS inference
    implementation "org.tensorflow.lite:tensorflow-lite:2.16.1"

    // Existing LiteRT-LM SDK for FunctionGemma command generation (unchanged)
    implementation "com.google.ai.edge.litertlm:litertlm-android:0.16.1"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0"
}
```

### In `android/app/build.gradle` `androidResources`:
```groovy
androidResources {
    noCompress 'litertlm', 'bin', 'task', 'tflite'
}
```

---

## 3. Strict Boundary with FunctionGemma

1. **DooraNeuralSpotter**:
   - Sole responsibility: Continuously spots the keyword "Doora" from 16kHz audio frames.
   - Runtime: TensorFlow Lite runtime (`org.tensorflow.lite.Interpreter`).
   - Memory footprint: $\le 1\text{MB}$.
2. **LiteRtLmAiEngine / FunctionGemma**:
   - Sole responsibility: Executes high-level function calling and command interpretation.
   - Runtime: Google AI Edge LiteRT-LM (`com.google.ai.edge.litertlm.Engine`).
   - Execution rule: **Only loaded and invoked when a complex user command requires LLM inference**. It remains completely dormant during idle wake-word monitoring.

---

## 4. Microphone Handoff Protocol

To prevent hardware lock conflicts:
1. When "Doora" is spotted, `DooraWakeWordService` immediately calls `stopWakeWordDetectionLoop()`.
2. `AudioRecord.stop()` and `AudioRecord.release()` are executed synchronously.
3. System `SpeechRecognizer.startListening()` is started to capture the user's voice command.
4. When the command completes, errors, or times out (5-second watchdog), `resumeWakeWordMode()` delays 250ms and cleanly restarts `AudioRecord`.
