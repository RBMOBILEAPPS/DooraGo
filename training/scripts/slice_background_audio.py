#!/usr/bin/env python3
"""Offline background audio and general speech slicing utility for Doora KWS.

Slices long continuous audio recordings (such as LibriSpeech speech tracks,
ambient room noise, fan hum, or foley) into standardized 1.0-second 16kHz
16-bit Mono PCM WAV clips for negative training datasets.

Includes automated mono downmixing, 16kHz resampling, RMS filtering,
clipping protection, SHA-256 deduplication, and speaker/source isolation.
"""

import os
import sys
import glob
import wave
import hashlib
import argparse
from typing import List, Dict, Any, Optional
import numpy as np

# Add parent training root to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from src.audio import compute_audio_rms, save_audio_wav, resample_audio


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
                import json
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
        import json
        os.makedirs(os.path.dirname(os.path.abspath(self.registry_path)), exist_ok=True)
        with open(self.registry_path, "w", encoding="utf-8") as f:
            json.dump(self.hashes, f, indent=2)


def load_raw_source_audio(file_path: str, target_sr: int = 16000) -> np.ndarray:
    """Reads any standard WAV file, mixes to mono, and resamples to target_sr float32."""
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"Source file not found: {file_path}")

    with wave.open(file_path, "rb") as wf:
        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        orig_sr = wf.getframerate()
        num_frames = wf.getnframes()
        raw_bytes = wf.readframes(num_frames)

    if sample_width == 2:
        audio = np.frombuffer(raw_bytes, dtype=np.int16).astype(np.float32) / 32768.0
    elif sample_width == 1:
        audio = (np.frombuffer(raw_bytes, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif sample_width == 4:
        audio = np.frombuffer(raw_bytes, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        raise ValueError(f"Unsupported sample width: {sample_width} in {file_path}")

    # Downmix multi-channel to mono
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)

    # Resample to target rate if needed
    if orig_sr != target_sr:
        audio = resample_audio(audio, orig_sr, target_sr)

    return audio


def slice_audio_file(
    input_file: str,
    output_dir: str,
    category: str = "general_speech",
    speaker_id: str = "default_source",
    window_duration_s: float = 1.0,
    overlap_s: float = 0.0,
    target_sample_rate: int = 16000,
    min_rms: float = 0.005,
    max_peak: float = 0.999,
    max_slices: Optional[int] = None,
    hash_registry: Optional[HashRegistry] = None,
    start_index: int = 1
) -> Dict[str, int]:
    """Slices a single audio file into standardized 1.0-second WAV clips."""
    dest_dir = os.path.join(output_dir, category, speaker_id)
    os.makedirs(dest_dir, exist_ok=True)

    audio = load_raw_source_audio(input_file, target_sr=target_sample_rate)

    window_samples = int(target_sample_rate * window_duration_s)
    step_samples = int(target_sample_rate * (window_duration_s - overlap_s))
    if step_samples <= 0:
        step_samples = window_samples

    total_samples = len(audio)
    curr_offset = 0
    slice_idx = start_index

    stats = {
        "slices_written": 0,
        "silent_skipped": 0,
        "duplicates_rejected": 0,
    }

    base_slug = os.path.splitext(os.path.basename(input_file))[0].replace(" ", "_")[:12]

    while curr_offset + window_samples <= total_samples:
        if max_slices is not None and stats["slices_written"] >= max_slices:
            break

        chunk = audio[curr_offset : curr_offset + window_samples]
        curr_offset += step_samples

        # 1. Quality Filter: RMS Silence Check
        rms = compute_audio_rms(chunk)
        if rms < min_rms:
            stats["silent_skipped"] += 1
            continue

        # 2. Quality Filter: Clipping Protection
        peak = np.max(np.abs(chunk)) if len(chunk) > 0 else 0.0
        if peak >= max_peak:
            chunk = chunk / (peak + 1e-6) * 0.95

        # 3. Save standardized WAV
        filename = f"{category}_{speaker_id}_{base_slug}_{slice_idx:06d}.wav"
        dest_path = os.path.join(dest_dir, filename)

        save_audio_wav(dest_path, chunk, sample_rate=target_sample_rate, ensure_pcm16=True)

        # 4. SHA-256 Deduplication Check
        file_hash = compute_file_sha256(dest_path)
        if hash_registry:
            if hash_registry.is_duplicate(file_hash):
                stats["duplicates_rejected"] += 1
                os.remove(dest_path)
                continue
            hash_registry.register(file_hash, dest_path)

        stats["slices_written"] += 1
        slice_idx += 1

    return stats


def run_batch_slicing(
    input_paths: List[str],
    output_dir: str = "kws_dataset/negative",
    category: str = "general_speech",
    speaker_id: str = "source_001",
    window_duration_s: float = 1.0,
    overlap_s: float = 0.0,
    target_sample_rate: int = 16000,
    min_rms: float = 0.005,
    max_slices: Optional[int] = None,
    hash_registry_path: str = "training/data/hash_registry.json"
):
    """Slices multiple audio files or directories."""
    all_files = []
    for path in input_paths:
        if os.path.isdir(path):
            all_files.extend(glob.glob(os.path.join(path, "**", "*.wav"), recursive=True))
        elif os.path.isfile(path):
            all_files.append(path)

    if not all_files:
        print(f"[ERROR] No valid audio files found in: {input_paths}")
        return

    print("\n" + "=" * 65)
    print("DOORAGO AUDIO SLICING & PREPROCESSING PIPELINE")
    print("=" * 65)
    print(f"Total Source Files:  {len(all_files)}")
    print(f"Target Category:     {category}")
    print(f"Speaker/Source ID:   {speaker_id}")
    print(f"Window Duration:     {window_duration_s}s (Overlap: {overlap_s}s)")
    print(f"Sample Rate:         {target_sample_rate} Hz (PCM16 Mono)")
    print(f"Min RMS Energy:      {min_rms}")
    print(f"Output Directory:    {output_dir}")
    print("=" * 65 + "\n")

    hash_registry = HashRegistry(hash_registry_path)
    total_written = 0
    total_silent = 0
    total_duplicates = 0

    curr_idx = 1
    for f_idx, src_file in enumerate(all_files):
        print(f"[{f_idx + 1}/{len(all_files)}] Slicing: {os.path.basename(src_file)}...")
        stats = slice_audio_file(
            input_file=src_file,
            output_dir=output_dir,
            category=category,
            speaker_id=speaker_id,
            window_duration_s=window_duration_s,
            overlap_s=overlap_s,
            target_sample_rate=target_sample_rate,
            min_rms=min_rms,
            max_slices=(max_slices - total_written) if max_slices else None,
            hash_registry=hash_registry,
            start_index=curr_idx
        )
        total_written += stats["slices_written"]
        total_silent += stats["silent_skipped"]
        total_duplicates += stats["duplicates_rejected"]
        curr_idx += stats["slices_written"]

        if max_slices is not None and total_written >= max_slices:
            print(f"[INFO] Reached maximum requested slices ({max_slices}). Stopping.")
            break

    hash_registry.save()

    print("\n" + "=" * 65)
    print("AUDIO SLICING SUMMARY")
    print("=" * 65)
    print(f"Total Slices Generated:         {total_written}")
    print(f"Silent / Sub-threshold Skipped: {total_silent}")
    print(f"Exact Duplicates Rejected:      {total_duplicates}")
    print(f"Hash Registry Saved To:         {hash_registry_path}")
    print("=" * 65 + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Slice long audio files into 1.0s 16kHz WAV clips.")
    parser.add_argument("--input_paths", type=str, nargs="+", required=True, help="One or more input audio files or directories")
    parser.add_argument("--output_dir", type=str, default="kws_dataset/negative", help="Root negative dataset directory")
    parser.add_argument("--category", type=str, default="general_speech", choices=["general_speech", "background_noise", "common_commands"], help="Target negative category")
    parser.add_argument("--speaker_id", type=str, default="source_001", help="Stable speaker or source identifier")
    parser.add_argument("--duration", type=float, default=1.0, help="Clip duration in seconds (default: 1.0)")
    parser.add_argument("--overlap", type=float, default=0.0, help="Window overlap in seconds (default: 0.0)")
    parser.add_argument("--sample_rate", type=int, default=16000, help="Target sample rate in Hz (default: 16000)")
    parser.add_argument("--min_rms", type=float, default=0.005, help="Minimum RMS energy threshold to reject silence")
    parser.add_argument("--max_slices", type=int, default=None, help="Maximum number of slices to produce")
    parser.add_argument("--hash_registry", type=str, default="training/data/hash_registry.json", help="Path to SHA-256 hash registry")
    args = parser.parse_args()

    run_batch_slicing(
        input_paths=args.input_paths,
        output_dir=args.output_dir,
        category=args.category,
        speaker_id=args.speaker_id,
        window_duration_s=args.duration,
        overlap_s=args.overlap,
        target_sample_rate=args.sample_rate,
        min_rms=args.min_rms,
        max_slices=args.max_slices,
        hash_registry_path=args.hash_registry
    )
