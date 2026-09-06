# DooraGo Wake-Word ("Doora") Local Dataset Guide

This guide describes how to record, organize, and validate a high-quality local dataset on your computer to train a lightweight on-device Keyword Spotting (KWS) model for **"Doora"**.

---

## 1. Directory Structure

All audio files must remain stored locally on your machine. Organize your local recordings according to the following folder hierarchy:

```text
kws_dataset/
├── positive/                     # Clean and realistic spoken "Doora" clips
│   ├── speaker_001_male/
│   │   ├── doora_0001.wav
│   │   └── ...
│   ├── speaker_002_female/
│   ├── speaker_003_male/
│   └── ...
├── negative/                     # Non-target speech and environmental sounds
│   ├── hard_negatives/           # Phonetically similar words (Hindi, English, Hinglish)
│   │   ├── speaker_001/
│   │   └── ...
│   ├── general_speech/           # Random conversational Hindi, English, Hinglish speech
│   ├── common_commands/          # App command phrases without the wake word
│   └── background_noise/         # Ambient noises (fan, silence, TV, street, room reverberation)
└── test_holdout/                 # Unseen speakers reserved exclusively for final evaluation
    ├── positive/
    └── negative/
```

---

## 2. Audio Format Standard

Every recording must conform to the standard Android audio capture specification:

| Parameter | Specification |
| :--- | :--- |
| **Format** | Linear PCM WAV (`.wav`) |
| **Sample Rate** | `16,000 Hz` (16 kHz) |
| **Bit Depth** | `16-bit signed integer` |
| **Channels** | `1 (Mono)` |
| **Clip Duration** | `1.0 second` (16,000 samples) to `1.5 seconds` |
| **Audio Alignment** | The target utterance should be centered within the 1-second window with ~150ms–250ms of natural silence/ambient background padding at both start and end. |

---

## 3. Positive Sample Collection Strategy ("Doora")

The goal is to capture diverse acoustic representations of the wake word **"Doora"** (phonetic: `/dʊərə/`, `/duːrɑː/`, Hindi: *"दोरा"*, *"दूरा"*).

### Speaker Diversity Requirements
- **Minimum Recommended Speakers**: 15–30 diverse individuals (different ages, genders, native accents).
- **Target Positive Clips**: 1,500 – 3,000 raw original clips (~50–100 clips per speaker).

### Recording Variations per Speaker
Each speaker should record "Doora" across different natural conditions:
1. **Speaking Style**: Conversational, brisk/fast, relaxed/slow, quiet (near-whisper), projecting/slightly loud.
2. **Device Distance**:
   - Near field: 15–30 cm (handheld phone).
   - Mid field: 60–100 cm (phone on desk).
   - Far field: 2–3 meters (across a room).
3. **Environment**:
   - Quiet bedroom / office.
   - Living room with mild background chatter or TV at low volume.
   - Room with ceiling fan / AC hum.
4. **Hardware**: Record directly on smartphone microphones (using a voice recorder app configured for 16kHz WAV) and laptop/USB microphones.

---

## 4. Negative & Hard-Negative Sample Strategy

A robust wake-word detector must reject non-target speech with near-zero false alarms.

### A. Phonetic Hard Negatives (Words sounding similar to "Doora")
Include spoken words that share syllables, plosives, or formant structures:
- **Hindi / Urdu**: *Dora*, *Doori*, *Dobaara*, *Doraemon*, *Dawa*, *Duniya*, *Dukan*, *Darwaza*, *Doosra*, *Doora-paas*, *Dhara*, *Kora*, *Mora*, *Chhora*.
- **English**: *Door*, *Dora*, *Dura*, *Dollar*, *Daughter*, *Dull*, *Donor*, *Aurora*, *Flora*, *Laura*.
- **Common Names**: *Dora*, *Dolly*, *Deepak*, *Deepa*, *Dinesh*.

### B. General Negative Speech
- Random conversational dialogue in English, Hindi, and regional accents.
- Common voice commands spoken without "Doora" (e.g., *"YouTube open karo"*, *"Papa ko call lagao"*, *"What time is it"*, *"Play music"*).

### C. Background Noise & Non-Speech
- Complete silence / room ambient noise.
- Continuous ceiling fan, AC, or refrigerator hum.
- Distant television / radio audio.
- Traffic noise, typing, coughing, throat clearing, door taps.

---

## 5. Speaker-Disjoint Split Strategy

> [!CRITICAL]
> **Never randomly split audio clips from the same speaker across training and test sets.**
> Random clip splitting leads to model overfitting on individual vocal timbres, causing artificially high test accuracy and poor generalization to new users.

### Recommended Disjoint Split

| Dataset Split | Speaker Allocation | Target Portion |
| :--- | :--- | :--- |
| **Train Set** | Speakers 1 to $N-6$ | $\approx 70\%$ of speakers |
| **Validation Set** | Speakers $N-5$ to $N-3$ | $\approx 15\%$ of speakers (Unseen in Train) |
| **Test Set (Holdout)** | Speakers $N-2$ to $N$ | $\approx 15\%$ of speakers (Unseen in Train & Val) |

---

## 6. Data Quality & Pre-Training Validation Checklist

Before initiating model training:
1. Verify that all WAV files are strictly 16 kHz Mono 16-bit PCM (no 44.1kHz or stereo files).
2. Ensure no audio clipping (peak amplitude within $\pm 0.95$ of maximum dynamic range).
3. Validate that positive clips contain the complete utterance without the first plosive "D-" or trailing "-ra" chopped off.
4. Verify speaker disjointness between `train/`, `val/`, and `test/` partitions.
