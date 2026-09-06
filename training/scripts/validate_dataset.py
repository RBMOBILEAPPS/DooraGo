#!/usr/bin/env python3
"""Dataset validation tool for local Doora KWS audio recordings.

Non-destructive: Validates file headers, bit depth, sample rates, channels,
RMS levels, and clipping without modifying or deleting any original audio.
"""

import os
import sys
import argparse
import glob
from typing import Dict, Any, List

# Add parent training root to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from src.audio import read_wav_header, load_audio_wav, compute_audio_rms


def validate_dataset(dataset_dir: str, target_sr: int = 16000, target_duration_s: float = 1.0) -> Dict[str, Any]:
    """Scans and validates all WAV files in dataset_dir."""
    if not os.path.exists(dataset_dir):
        print(f"[ERROR] Dataset directory does not exist: {dataset_dir}")
        return {"valid": False, "total_files": 0, "errors": [f"Missing directory: {dataset_dir}"]}

    wav_files = glob.glob(os.path.join(dataset_dir, "**", "*.wav"), recursive=True)
    if not wav_files:
        print(f"[WARNING] No .wav files found in {dataset_dir}")
        return {"valid": False, "total_files": 0, "errors": ["No WAV files found"]}

    print(f"[INFO] Scanning {len(wav_files)} WAV files in {dataset_dir}...")

    stats = {
        "total_files": len(wav_files),
        "valid_files": 0,
        "invalid_files": 0,
        "sample_rate_mismatches": [],
        "channel_mismatches": [],
        "bit_depth_mismatches": [],
        "duration_warnings": [],
        "silent_files": [],
        "clipped_files": [],
        "corrupted_files": [],
    }

    for path in wav_files:
        rel_path = os.path.relpath(path, dataset_dir)
        try:
            meta = read_wav_header(path)

            has_error = False
            # 1. Sample Rate Check
            if meta["sample_rate"] != target_sr:
                stats["sample_rate_mismatches"].append((rel_path, meta["sample_rate"]))
                has_error = True

            # 2. Channel Check
            if meta["channels"] != 1:
                stats["channel_mismatches"].append((rel_path, meta["channels"]))
                # Non-fatal warning if stereo can be mixed down

            # 3. Bit Depth Check
            if meta["bit_depth"] != 16:
                stats["bit_depth_mismatches"].append((rel_path, meta["bit_depth"]))
                has_error = True

            # 4. Duration Check (Warn if < 0.5s or > 2.5s)
            dur = meta["duration_seconds"]
            if dur < 0.5 or dur > 2.5:
                stats["duration_warnings"].append((rel_path, dur))

            # 5. Acoustic Content Checks
            audio, _ = load_audio_wav(path, target_sample_rate=target_sr, normalize=True)
            rms = compute_audio_rms(audio)

            if rms < 0.005:  # Extremely low RMS (virtual silence)
                stats["silent_files"].append((rel_path, rms))

            if np.max(np.abs(audio)) >= 0.999:  # Audio clipping
                stats["clipped_files"].append(rel_path)

            if not has_error:
                stats["valid_files"] += 1
            else:
                stats["invalid_files"] += 1

        except Exception as e:
            stats["corrupted_files"].append((rel_path, str(e)))
            stats["invalid_files"] += 1

    # Print Summary Report
    print("\n" + "=" * 60)
    print("DOORAGO KWS DATASET VALIDATION REPORT")
    print("=" * 60)
    print(f"Total Audio Files Analyzed:  {stats['total_files']}")
    print(f"Fully Valid Files:          {stats['valid_files']}")
    print(f"Invalid / Flagged Files:    {stats['invalid_files']}")
    print(f"Sample Rate Mismatches:     {len(stats['sample_rate_mismatches'])}")
    print(f"Channel Mismatches:         {len(stats['channel_mismatches'])}")
    print(f"Bit Depth Mismatches:       {len(stats['bit_depth_mismatches'])}")
    print(f"Silent / Too Low RMS:       {len(stats['silent_files'])}")
    print(f"Clipped / Saturated:        {len(stats['clipped_files'])}")
    print(f"Corrupted Files:            {len(stats['corrupted_files'])}")
    print("=" * 60)

    is_overall_valid = (stats["invalid_files"] == 0 and stats["valid_files"] > 0)
    return {"valid": is_overall_valid, "stats": stats}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Validate local KWS dataset audio files.")
    parser.add_argument("--dataset_dir", type=str, default="kws_dataset", help="Path to local kws_dataset directory")
    parser.add_argument("--sample_rate", type=int, default=16000, help="Expected sample rate in Hz (default: 16000)")
    args = parser.parse_args()

    import numpy as np  # Imported locally for script execution
    validate_dataset(args.dataset_dir, target_sr=args.sample_rate)
