# Doora Keyword Spotting (KWS) Local Training Workspace

This workspace contains the complete local machine learning toolchain to validate audio datasets, train, evaluate, and export an ultra-lightweight, on-device Keyword Spotting model for the custom wake word **"Doora"** in **DooraGo**.

---

## 1. Directory Structure

```text
training/
├── README.md                      # This training workspace guide
├── requirements.txt               # Python dependency requirements
├── configs/
│   ├── doora_kws.yaml             # Master YAML training & preprocessing configuration
│   └── dataset_sources.json       # Formal dataset licensing & provenance registry
├── src/
│   ├── audio.py                   # Audio WAV loading/saving, RMS/ZCR, resampler
│   ├── features.py                # Log-Mel Spectrogram extractor (16kHz, 30ms window, 10ms hop, 40 bins)
│   ├── model.py                   # Streaming Depthwise-Separable CNN (DS-CNN-Tiny) architecture
│   ├── dataset.py                 # Manifest parser, speaker grouping, tf.data pipeline
│   ├── metrics.py                 # FAR, FRR, calibration tables, precision, recall, F1
│   └── quantization.py            # Post-Training Full Integer (Int8) TFLite FlatBuffer exporter
├── scripts/
│   ├── generate_synthetic_dataset.py # Batch Piper TTS generator for positive & hard negatives
│   ├── slice_background_audio.py     # Slices & resamples speech/noise tracks to 1.0s 16kHz clips
│   ├── validate_dataset.py        # Non-destructive audio & metadata validator
│   ├── build_manifest.py          # Indexes local kws_dataset/ into manifest.json
│   ├── split_dataset.py           # Deterministic speaker-disjoint splitting (train/val/test)
│   ├── train.py                   # Model training loop with early stopping & checkpointing
│   ├── evaluate.py                # Test set evaluation and threshold calibration
│   ├── export_tflite.py           # Quantization and export to doora_kws.tflite
│   └── inspect_tflite.py          # FlatBuffer inspector (tensor shapes, types, scale/zero_point)
└── tests/                         # Unit tests for training toolchain
```

---

## 2. Local Dataset Organization

Store your local recordings under `kws_dataset/` on your computer:

```text
kws_dataset/
├── positive/
│   ├── speaker_001_male/
│   │   ├── doora_0001.wav
│   │   └── ...
│   ├── speaker_002_female/
│   └── ...
└── negative/
    ├── hard_negatives/
    │   ├── speaker_001/
    │   └── ...
    ├── general_speech/
    └── background_noise/
```

- **Format**: 16,000 Hz, 16-bit Mono Linear PCM WAV.
- **Pilot Dataset (Current)**: 2 synthetic speakers (LJSpeech & Kristin) — 232 clips used solely for verifying the technical pipeline (manifest indexing, training loop, evaluation, and Int8 quantization).
- **Production Dataset Target**: 1,500 – 3,000 positive "Doora" clips across 15–30+ diverse speakers (human + permissive synthetic voices) and 10,000+ negative audio slices across hard negatives, general speech, and background noise.

---

## 3. End-to-End Execution Workflow (Manual)

When ready to train on your local workstation, run the following steps:

### Step 1: Environment Setup
```bash
python -m venv venv
# On Windows:
venv\Scripts\activate
# On Linux/macOS:
source venv/bin/activate

pip install -r training/requirements.txt
```

### Step 1b: Pilot Synthetic Dataset Generation (Piper TTS)
```bash
# Generate positive 'Doora' and hard negatives using verified local Piper setup
python training/scripts/generate_synthetic_dataset.py \
  --piper_bin tools/piper/piper.exe \
  --voice_models tools/piper/en_US-ljspeech-medium.onnx tools/piper/en_US-kristin-medium.onnx \
  --output_dir kws_dataset \
  --pos_reps 8 \
  --neg_reps 1
```

### Step 1c: Optional Background Audio & Speech Slicing
```bash
# Slice long continuous speech or ambient noise tracks into 1.0s 16kHz WAV clips
python training/scripts/slice_background_audio.py \
  --input_paths /path/to/librispeech_tracks/ \
  --output_dir kws_dataset/negative \
  --category general_speech \
  --speaker_id libri_clean_spk_01
```

### Step 2: Validate Audio Files
```bash
python training/scripts/validate_dataset.py --dataset_dir kws_dataset
```

### Step 3: Build Manifest & Create Speaker-Disjoint Split
```bash
python training/scripts/build_manifest.py --dataset_dir kws_dataset --output_path training/data/manifest.json
python training/scripts/split_dataset.py --manifest_path training/data/manifest.json --output_dir training/data --seed 42
```

### Step 4: Train DS-CNN-Tiny Model
```bash
python training/scripts/train.py --config training/configs/doora_kws.yaml
```

### Step 5: Evaluate Model & Generate Calibration Table
```bash
python training/scripts/evaluate.py --config training/configs/doora_kws.yaml
```

### Step 6: Export Full Integer (Int8) TFLite Model
```bash
python training/scripts/export_tflite.py --config training/configs/doora_kws.yaml
```

### Step 7: Inspect TFLite Contract
```bash
python training/scripts/inspect_tflite.py training/output/doora_kws.tflite
```

---

## 4. Quality Acceptance Gates

Prior to Android integration, verify the exported model meets:
- **Model Size**: $\le 1.0\text{ MB}$ (Expected: $\sim 200\text{KB} - 400\text{KB}$).
- **False Reject Rate (FRR)**: $\le 5.0\%$ on speaker-disjoint test set.
- **False Accept Rate (FAR)**: $< 1\text{ false trigger / 10 hours}$ of background speech/audio.
- **Inference Latency**: $< 15\text{ms}$ on mobile CPU.

---

## 5. Android Handoff

Once the model passes all quality gates:
1. Copy `training/output/doora_kws.tflite` to `android/app/src/main/assets/models/doora_kws.tflite`.
2. Add `implementation "org.tensorflow.lite:tensorflow-lite:2.16.1"` in `android/app/build.gradle`.
3. Update `DooraNeuralSpotter.kt` to bind the TFLite Interpreter to the 1.0s sliding ring buffer.
