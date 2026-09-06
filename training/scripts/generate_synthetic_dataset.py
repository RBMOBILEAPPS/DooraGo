#!/usr/bin/env python3
"""Offline batch dataset generator for Doora Keyword Spotting (KWS).

Uses local Piper TTS binary and pre-downloaded, permissively licensed ONNX voice models
to synthesize diverse, high-quality positive ("Doora") and hard-negative audio clips.

Supports both single-speaker and multi-speaker Piper ONNX models:
- Single-speaker models (e.g. LJSpeech, Kristin) generate a single clean speaker identity.
- Multi-speaker models (e.g. VCTK) discover available speakers from the accompanying
  .onnx.json config and generate distinct speaker identities (e.g. tts_piper_vctk_spk000)
  while safely enforcing conservative default limits to prevent dataset imbalance.

Zero Network Calls: Requires user to supply local Piper executable and voice model paths.
"""

import os
import sys
import json
import wave
import shutil
import hashlib
import argparse
import tempfile
import subprocess
from typing import List, Dict, Any, Optional, Tuple
import numpy as np

# Add parent training root to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from src.audio import load_audio_wav, compute_audio_rms, save_audio_wav, resample_audio


DEFAULT_POSITIVE_PHRASES = ["Doora"]
DEFAULT_HARD_NEGATIVES = [
    "Dora", "Door", "Doori", "Dura", "Dawa", "Duniya", "Dukan",
    "Dobaara", "Doraemon", "Dollar", "Daughter", "Dull"
]
DEFAULT_LENGTH_SCALES = [1.25, 1.15, 1.05, 1.00, 0.95, 0.85, 0.75]  # Range of synthesis variants
DEFAULT_MAX_SPEAKERS_PER_MODEL = 5  # Conservative default to avoid runaway generation on 100+ speaker models


def compute_file_sha256(file_path: str) -> str:
    """Computes SHA-256 hash of a file."""
    sha = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            sha.update(chunk)
    return sha.hexdigest()


class HashRegistry:
    """Manages SHA-256 hash registry to guarantee zero exact duplicate audio clips."""

    def __init__(self, registry_path: str):
        self.registry_path = registry_path
        self.hashes: Dict[str, str] = {}
        self._load()

    def _load(self):
        if os.path.exists(self.registry_path):
            try:
                with open(self.registry_path, "r", encoding="utf-8") as f:
                    self.hashes = json.load(f)
            except Exception as e:
                print(f"[WARN] Failed to load hash registry ({e}), starting fresh.")
                self.hashes = {}

    def is_duplicate(self, file_hash: str) -> bool:
        return file_hash in self.hashes

    def register(self, file_hash: str, file_path: str):
        self.hashes[file_hash] = os.path.abspath(file_path)

    def save(self):
        os.makedirs(os.path.dirname(os.path.abspath(self.registry_path)), exist_ok=True)
        with open(self.registry_path, "w", encoding="utf-8") as f:
            json.dump(self.hashes, f, indent=2)


def inspect_piper_voice_model(voice_model_path: str) -> Dict[str, Any]:
    """Inspects a Piper ONNX voice model and its JSON configuration metadata.

    Args:
        voice_model_path: Path to the .onnx model file.

    Returns:
        Dict containing model metadata: voice_id, model_path, config_path,
        num_speakers, speaker_id_map, sample_rate, and is_multi_speaker.

    Raises:
        FileNotFoundError: If the .onnx or .onnx.json file is missing.
        ValueError: If config JSON is malformed or speaker metadata is invalid.
    """
    if not os.path.exists(voice_model_path):
        raise FileNotFoundError(f"Piper voice model .onnx file not found at: {voice_model_path}")

    # Determine accompanying config JSON path: <model>.onnx.json
    config_path = voice_model_path + ".json"
    if not os.path.exists(config_path):
        # Fallback check if user passed path without .onnx or other naming convention
        base_no_ext = os.path.splitext(voice_model_path)[0]
        alt_config = base_no_ext + ".json"
        if os.path.exists(alt_config):
            config_path = alt_config
        else:
            raise FileNotFoundError(
                f"Required Piper voice config JSON not found for model: {voice_model_path}. "
                f"Expected config at: {config_path}"
            )

    try:
        with open(config_path, "r", encoding="utf-8") as f:
            config_data = json.load(f)
    except Exception as e:
        raise ValueError(f"Malformed JSON in Piper voice config '{config_path}': {e}")

    num_speakers = config_data.get("num_speakers", 1)
    if not isinstance(num_speakers, int) or num_speakers < 1:
        raise ValueError(
            f"Invalid 'num_speakers' value ({num_speakers}) in voice config: {config_path}. Must be an integer >= 1."
        )

    speaker_id_map = config_data.get("speaker_id_map", {})
    audio_meta = config_data.get("audio", {})
    sample_rate = audio_meta.get("sample_rate", 22050)
    voice_id = os.path.splitext(os.path.basename(voice_model_path))[0]

    return {
        "voice_id": voice_id,
        "model_path": os.path.abspath(voice_model_path),
        "config_path": os.path.abspath(config_path),
        "num_speakers": num_speakers,
        "speaker_id_map": speaker_id_map,
        "sample_rate": sample_rate,
        "is_multi_speaker": num_speakers > 1,
    }


def parse_speaker_indices(speaker_indices_str: Optional[str]) -> Optional[List[int]]:
    """Parses and validates a comma-separated speaker index string (e.g. '0,12,25,50')."""
    if not speaker_indices_str or not speaker_indices_str.strip():
        return None

    indices = []
    seen = set()
    for token in speaker_indices_str.split(","):
        token = token.strip()
        if not token:
            continue
        try:
            val = int(token)
        except ValueError:
            raise ValueError(f"Invalid non-integer speaker index token: '{token}' in '{speaker_indices_str}'")

        if val < 0:
            raise ValueError(f"Speaker indices cannot be negative. Found: {val}")

        if val in seen:
            raise ValueError(f"Duplicate speaker index detected: {val} in '{speaker_indices_str}'")

        seen.add(val)
        indices.append(val)

    if not indices:
        raise ValueError(f"No valid speaker indices found in: '{speaker_indices_str}'")

    return indices


def synthesize_utterance_piper(
    piper_bin: str,
    voice_model_path: str,
    phrase: str,
    output_wav_path: str,
    length_scale: float = 1.00,
    speaker_index: Optional[int] = None
) -> bool:
    """Synthesizes a single audio clip using local Piper binary via stdin.

    Args:
        piper_bin: Path to local piper executable.
        voice_model_path: Path to voice .onnx model file.
        phrase: Text phrase to synthesize.
        output_wav_path: Destination WAV file.
        length_scale: Piper speaking rate variation (e.g. 1.15, 1.00, 0.85).
        speaker_index: Optional multi-speaker index (e.g. 0, 1, 2 for VCTK).

    Returns:
        True if synthesis succeeded, False otherwise.
    """
    cmd = [
        piper_bin,
        "--model", os.path.abspath(voice_model_path),
        "--output_file", os.path.abspath(output_wav_path),
        "--length_scale", str(length_scale),
    ]

    # Voice config JSON check
    voice_json = voice_model_path + ".json"
    if os.path.exists(voice_json):
        cmd.extend(["--config", os.path.abspath(voice_json)])

    # Pass explicit speaker index when targeting multi-speaker models
    if speaker_index is not None:
        cmd.extend(["--speaker", str(speaker_index)])

    try:
        proc = subprocess.run(
            cmd,
            input=phrase.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=30,
            check=True
        )
        return os.path.exists(output_wav_path) and os.path.getsize(output_wav_path) > 44
    except subprocess.TimeoutExpired:
        print(f"[ERROR] Piper synthesis timed out after 30s for phrase: '{phrase}' (Speaker: {speaker_index})")
        return False
    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Piper synthesis failed for '{phrase}' (Speaker: {speaker_index}): {e.stderr.decode('utf-8', errors='ignore')}")
        return False
    except Exception as e:
        print(f"[ERROR] Execution failed for Piper: {e}")
        return False


def process_and_standardize_clip(
    temp_wav_path: str,
    dest_wav_path: str,
    target_sample_rate: int = 16000,
    target_duration_s: float = 1.0,
    min_rms: float = 0.005,
    max_peak: float = 0.999,
    time_shift_samples: int = 0,
    gain_linear: float = 1.0,
    hash_registry: Optional[HashRegistry] = None
) -> Tuple[bool, str]:
    """Validates, standardizes, downmixes to mono, and standardizes to 16kHz 16-bit PCM."""
    if not os.path.exists(temp_wav_path):
        return False, "Temporary file missing"

    target_samples = int(target_sample_rate * target_duration_s)

    # 1. Read raw audio from temp output
    with wave.open(temp_wav_path, "rb") as wf:
        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        orig_sr = wf.getframerate()
        num_frames = wf.getnframes()
        raw_bytes = wf.readframes(num_frames)

    # Convert binary bytes to numpy float array
    if sample_width == 2:
        audio = np.frombuffer(raw_bytes, dtype=np.int16).astype(np.float32) / 32768.0
    elif sample_width == 1:
        audio = (np.frombuffer(raw_bytes, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif sample_width == 4:
        audio = np.frombuffer(raw_bytes, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        return False, f"Unsupported sample width: {sample_width}"

    # Downmix multi-channel to mono
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)

    # Resample if sample rate != target_sample_rate
    if orig_sr != target_sample_rate:
        audio = resample_audio(audio, orig_sr, target_sample_rate)

    # Apply deterministic gain perturbation
    if gain_linear != 1.0:
        audio = audio * gain_linear

    # 2. Quality Checks
    rms = compute_audio_rms(audio)
    if rms < min_rms:
        return False, f"Audio too quiet (RMS: {rms:.4f} < {min_rms})"

    peak = np.max(np.abs(audio)) if len(audio) > 0 else 0.0
    if peak >= max_peak:
        # Prevent clipping distortion by normalizing
        audio = audio / (peak + 1e-6) * 0.95

    # 3. Deterministic Pad / Crop with time shift to target length
    if len(audio) < target_samples:
        pad_total = target_samples - len(audio)
        pad_left = max(0, min(pad_total, (pad_total // 2) + time_shift_samples))
        pad_right = pad_total - pad_left
        audio = np.pad(audio, (pad_left, pad_right), mode="constant", constant_values=0.0)
    elif len(audio) > target_samples:
        max_start = len(audio) - target_samples
        start_idx = max(0, min(max_start, (max_start // 2) + time_shift_samples))
        audio = audio[start_idx : start_idx + target_samples]

    # 4. Save standardized 16-bit PCM WAV
    save_audio_wav(dest_wav_path, audio, sample_rate=target_sample_rate, ensure_pcm16=True)

    # 5. SHA-256 Deduplication Check
    file_hash = compute_file_sha256(dest_wav_path)
    if hash_registry:
        if hash_registry.is_duplicate(file_hash):
            existing = hash_registry.hashes[file_hash]
            os.remove(dest_wav_path)
            return False, f"Duplicate clip rejected (Identical hash to {existing})"
        hash_registry.register(file_hash, dest_wav_path)

    return True, "Success"


def run_batch_generation(
    piper_bin: str,
    voice_models: List[str],
    output_dir: str = "kws_dataset",
    positive_phrases: Optional[List[str]] = None,
    hard_negatives: Optional[List[str]] = None,
    length_scales: Optional[List[float]] = None,
    positive_repetitions: int = 8,
    hard_negative_repetitions: int = 1,
    max_speakers_per_model: int = DEFAULT_MAX_SPEAKERS_PER_MODEL,
    speaker_indices: Optional[List[int]] = None,
    target_sample_rate: int = 16000,
    target_duration_s: float = 1.0,
    hash_registry_path: str = "training/data/hash_registry.json",
    dry_run: bool = False,
):
    """Executes deterministic offline batch dataset generation across single/multi-speaker Piper models.

    Speaker Identity Design:
    - Single-speaker models (num_speakers == 1) produce speaker_id = 'tts_piper_<voice_id>'.
    - Multi-speaker models (num_speakers > 1) produce speaker_id = 'tts_piper_<voice_id>_spk<idx:03d>'.
      This ensures every distinct synthetic voice is isolated into its own speaker identity,
      maintaining strict speaker-disjoint boundaries for train/val/test splitting.
    - The default limit (max_speakers_per_model=5) prevents massive multi-speaker models (like VCTK
      with 109 speakers) from monopolizing dataset generation unless explicitly requested.
    """
    positive_phrases = positive_phrases or DEFAULT_POSITIVE_PHRASES
    hard_negatives = hard_negatives or DEFAULT_HARD_NEGATIVES
    length_scales = length_scales or DEFAULT_LENGTH_SCALES

    if max_speakers_per_model <= 0:
        raise ValueError(f"--max_speakers_per_model must be an integer > 0. Received: {max_speakers_per_model}")

    # Strict assertion: Ensure zero overlap between positive and negative phrase sets
    overlap = set(p.strip().lower() for p in positive_phrases).intersection(
        set(n.strip().lower() for n in hard_negatives)
    )
    if overlap:
        raise ValueError(
            f"CRITICAL CONFIGURATION ERROR: Overlapping phrases detected in both positive and negative sets: {overlap}"
        )

    if not dry_run and not os.path.exists(piper_bin):
        raise FileNotFoundError(f"Piper binary not found at: {piper_bin}")

    # Discover and inspect each voice model configuration
    inspected_models: List[Dict[str, Any]] = []
    for vm in voice_models:
        meta = inspect_piper_voice_model(vm)
        inspected_models.append(meta)

    # Build execution target list: List of (meta, speaker_id, speaker_index_or_None)
    generation_targets: List[Tuple[Dict[str, Any], str, Optional[int]]] = []

    for meta in inspected_models:
        voice_id = meta["voice_id"]
        num_speakers = meta["num_speakers"]

        if not meta["is_multi_speaker"]:
            # Single-speaker voice model: Preserve exact historical speaker_id convention
            spk_id = f"tts_piper_{voice_id}"
            generation_targets.append((meta, spk_id, None))
        else:
            # Multi-speaker voice model: Determine selected speaker indices
            if speaker_indices is not None:
                # User specified exact speaker indices
                for idx in speaker_indices:
                    if idx >= num_speakers:
                        raise ValueError(
                            f"Specified speaker index {idx} is out of range for model '{voice_id}' "
                            f"(total available speakers: {num_speakers}, valid range: 0..{num_speakers - 1})."
                        )
                selected_indices = speaker_indices
            else:
                # Deterministic selection: Take the first N speaker indices
                limit = min(num_speakers, max_speakers_per_model)
                selected_indices = list(range(limit))

            for spk_idx in selected_indices:
                spk_id = f"tts_piper_{voice_id}_spk{spk_idx:03d}"
                generation_targets.append((meta, spk_id, spk_idx))

    print("\n" + "=" * 70)
    print("DOORAGO SYNTHETIC DATASET GENERATION PIPELINE")
    print("=" * 70)
    print(f"Piper Binary:             {piper_bin}")
    print(f"Voice Models Discovered:  {len(inspected_models)}")
    for m in inspected_models:
        spk_type = f"Multi-Speaker ({m['num_speakers']} speakers)" if m['is_multi_speaker'] else "Single-Speaker (1 speaker)"
        print(f"  - {m['voice_id']}: {spk_type}")
    print(f"Total Speaker Identities: {len(generation_targets)}")
    print(f"Positive Phrases:         {positive_phrases} (Reps: {positive_repetitions})")
    print(f"Hard Negatives:           {len(hard_negatives)} phrase(s) (Reps: {hard_negative_repetitions})")
    print(f"Length Scales:            {length_scales}")
    print(f"Output Directory:         {output_dir}")
    print(f"Dry-Run Mode:             {dry_run}")
    print("=" * 70 + "\n")

    if dry_run:
        print("[AUDIT / DRY-RUN] Discovered Planned Speaker Generation Targets:")
        for idx, (meta, spk_id, spk_idx) in enumerate(generation_targets, 1):
            idx_str = f"Speaker Index: {spk_idx}" if spk_idx is not None else "Default Single Speaker"
            print(f"  {idx:02d}. Target Speaker ID: '{spk_id}' ({idx_str}) -> Model: {meta['voice_id']}")
        print("\n[SUCCESS] Dry run complete. No audio was synthesized or written to disk.\n")
        return

    hash_registry = HashRegistry(hash_registry_path)
    temp_dir = tempfile.mkdtemp(prefix="doorago_kws_gen_")

    stats = {
        "positive_generated": 0,
        "positive_skipped_or_failed": 0,
        "hard_negatives_generated": 0,
        "hard_negatives_skipped_or_failed": 0,
        "duplicates_rejected": 0,
    }

    try:
        for meta, speaker_id, spk_idx in generation_targets:
            voice_id = meta["voice_id"]
            vm_path = meta["model_path"]
            spk_label = f"speaker index {spk_idx}" if spk_idx is not None else "single speaker"

            print(f"[INFO] Generating for Speaker Identity: '{speaker_id}' ({spk_label})...")

            # --- 1. Generate Positive "Doora" Clips ---
            pos_speaker_dir = os.path.join(output_dir, "positive", speaker_id)
            os.makedirs(pos_speaker_dir, exist_ok=True)

            for p_idx, phrase in enumerate(positive_phrases):
                for r_idx, rate in enumerate(length_scales):
                    rate_str = f"r{int(round(rate * 100))}"
                    spk_tag = f"spk{spk_idx:03d}_" if spk_idx is not None else ""
                    temp_wav = os.path.join(temp_dir, f"temp_pos_{voice_id}_{spk_tag}{p_idx}_{r_idx}.wav")

                    ok = synthesize_utterance_piper(
                        piper_bin=piper_bin,
                        voice_model_path=vm_path,
                        phrase=phrase,
                        output_wav_path=temp_wav,
                        length_scale=rate,
                        speaker_index=spk_idx
                    )

                    if not ok:
                        stats["positive_skipped_or_failed"] += positive_repetitions
                        continue

                    for rep in range(positive_repetitions):
                        if spk_idx is not None:
                            file_name = f"doora_{voice_id}_spk{spk_idx:03d}_p{p_idx:02d}_{rate_str}_rep{rep:02d}.wav"
                        else:
                            file_name = f"doora_{voice_id}_p{p_idx:02d}_{rate_str}_rep{rep:02d}.wav"

                        dest_path = os.path.join(pos_speaker_dir, file_name)

                        if os.path.exists(dest_path):
                            print(f"  [SKIP] Exists: {file_name}")
                            continue

                        # Deterministic time offset & gain per repetition
                        shift = (rep - (positive_repetitions // 2)) * 160
                        gain = 0.90 + (rep % 5) * 0.05

                        valid, reason = process_and_standardize_clip(
                            temp_wav_path=temp_wav,
                            dest_wav_path=dest_path,
                            target_sample_rate=target_sample_rate,
                            target_duration_s=target_duration_s,
                            time_shift_samples=shift,
                            gain_linear=gain,
                            hash_registry=hash_registry
                        )

                        if valid:
                            stats["positive_generated"] += 1
                        else:
                            if "Duplicate" in reason:
                                stats["duplicates_rejected"] += 1
                            stats["positive_skipped_or_failed"] += 1
                            print(f"  [WARN] Flagged {file_name}: {reason}")

            # --- 2. Generate Hard-Negative Clips ---
            neg_speaker_dir = os.path.join(output_dir, "negative", "hard_negatives", speaker_id)
            os.makedirs(neg_speaker_dir, exist_ok=True)

            for hn_idx, phrase in enumerate(hard_negatives):
                # Clean slug for filename
                slug = phrase.lower().replace(" ", "_")[:12]
                for r_idx, rate in enumerate(length_scales[:5]):  # Use 5 rates for hard negatives
                    rate_str = f"r{int(round(rate * 100))}"
                    spk_tag = f"spk{spk_idx:03d}_" if spk_idx is not None else ""
                    temp_wav = os.path.join(temp_dir, f"temp_neg_{voice_id}_{spk_tag}{hn_idx}_{r_idx}.wav")

                    ok = synthesize_utterance_piper(
                        piper_bin=piper_bin,
                        voice_model_path=vm_path,
                        phrase=phrase,
                        output_wav_path=temp_wav,
                        length_scale=rate,
                        speaker_index=spk_idx
                    )

                    if not ok:
                        stats["hard_negatives_skipped_or_failed"] += hard_negative_repetitions
                        continue

                    for rep in range(hard_negative_repetitions):
                        if spk_idx is not None:
                            file_name = f"hardneg_{slug}_{voice_id}_spk{spk_idx:03d}_{rate_str}_rep{rep:02d}.wav"
                        else:
                            file_name = f"hardneg_{slug}_{voice_id}_{rate_str}_rep{rep:02d}.wav"

                        dest_path = os.path.join(neg_speaker_dir, file_name)

                        if os.path.exists(dest_path):
                            print(f"  [SKIP] Exists: {file_name}")
                            continue

                        shift = (rep - (hard_negative_repetitions // 2)) * 160
                        gain = 1.0

                        valid, reason = process_and_standardize_clip(
                            temp_wav_path=temp_wav,
                            dest_wav_path=dest_path,
                            target_sample_rate=target_sample_rate,
                            target_duration_s=target_duration_s,
                            time_shift_samples=shift,
                            gain_linear=gain,
                            hash_registry=hash_registry
                        )

                        if valid:
                            stats["hard_negatives_generated"] += 1
                        else:
                            if "Duplicate" in reason:
                                stats["duplicates_rejected"] += 1
                            stats["hard_negatives_skipped_or_failed"] += 1

        hash_registry.save()

        print("\n" + "=" * 70)
        print("SYNTHETIC DATASET GENERATION SUMMARY")
        print("=" * 70)
        print(f"Positive 'Doora' Clips Generated:     {stats['positive_generated']}")
        print(f"Hard-Negative Clips Generated:       {stats['hard_negatives_generated']}")
        print(f"Exact Duplicate Clips Rejected:      {stats['duplicates_rejected']}")
        print(f"Flagged / Skipped Clips:             {stats['positive_skipped_or_failed'] + stats['hard_negatives_skipped_or_failed']}")
        print(f"Hash Registry Saved To:              {hash_registry_path}")
        print("=" * 70 + "\n")

    finally:
        if os.path.exists(temp_dir):
            shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate synthetic KWS dataset clips via Piper TTS.")
    parser.add_argument("--piper_bin", type=str, required=True, help="Path to local piper binary executable")
    parser.add_argument("--voice_models", type=str, nargs="+", required=True, help="One or more paths to Piper voice .onnx files")
    parser.add_argument("--output_dir", type=str, default="kws_dataset", help="Root dataset output directory")
    parser.add_argument("--positive_phrases", type=str, nargs="+", default=DEFAULT_POSITIVE_PHRASES, help="Target wake-word phrases")
    parser.add_argument("--hard_negatives", type=str, nargs="+", default=DEFAULT_HARD_NEGATIVES, help="Hard-negative lookalike phrases")
    parser.add_argument("--length_scales", type=float, nargs="+", default=DEFAULT_LENGTH_SCALES, help="Piper synthesis length scale rates")
    parser.add_argument("--pos_reps", type=int, default=8, help="Number of repetitions per positive phrase/rate variation")
    parser.add_argument("--neg_reps", type=int, default=1, help="Number of repetitions per negative phrase/rate variation")
    parser.add_argument("--max_speakers_per_model", type=int, default=DEFAULT_MAX_SPEAKERS_PER_MODEL, help="Maximum speakers to synthesize from a multi-speaker model (default: 5)")
    parser.add_argument("--speaker_indices", type=str, default=None, help="Optional comma-separated speaker indices (e.g. '0,12,25,50')")
    parser.add_argument("--sample_rate", type=int, default=16000, help="Target sample rate in Hz (default: 16000)")
    parser.add_argument("--duration", type=float, default=1.0, help="Target clip duration in seconds (default: 1.0)")
    parser.add_argument("--hash_registry", type=str, default="training/data/hash_registry.json", help="Path to SHA-256 hash registry")
    parser.add_argument("--dry_run", action="store_true", help="Audit model configs and print planned targets without generating audio")
    args = parser.parse_args()

    parsed_speaker_indices = parse_speaker_indices(args.speaker_indices)

    run_batch_generation(
        piper_bin=args.piper_bin,
        voice_models=args.voice_models,
        output_dir=args.output_dir,
        positive_phrases=args.positive_phrases,
        hard_negatives=args.hard_negatives,
        length_scales=args.length_scales,
        positive_repetitions=args.pos_reps,
        hard_negative_repetitions=args.neg_reps,
        max_speakers_per_model=args.max_speakers_per_model,
        speaker_indices=parsed_speaker_indices,
        target_sample_rate=args.sample_rate,
        target_duration_s=args.duration,
        hash_registry_path=args.hash_registry,
        dry_run=args.dry_run
    )
