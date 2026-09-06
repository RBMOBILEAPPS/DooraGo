#!/usr/bin/env python3
"""Builds a unified JSON manifest file from the local audio dataset hierarchy."""

import os
import sys
import json
import argparse
import glob
from typing import List, Dict, Any

# Add parent training root to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from src.audio import read_wav_header


def build_manifest(dataset_dir: str, output_manifest_path: str) -> str:
    """Scans dataset directory and generates a structured manifest JSON."""
    if not os.path.exists(dataset_dir):
        raise FileNotFoundError(f"Dataset directory not found: {dataset_dir}")

    wav_files = glob.glob(os.path.join(dataset_dir, "**", "*.wav"), recursive=True)
    if not wav_files:
        raise ValueError(f"No WAV files found in {dataset_dir}")

    records = []
    print(f"[INFO] Indexing {len(wav_files)} files into manifest...")

    for path in wav_files:
        rel_path = os.path.relpath(path, dataset_dir)
        parts = rel_path.replace("\\", "/").split("/")

        # Determine label and speaker from folder structure
        # Expected layouts:
        # positive/speaker_001/doora_01.wav -> label: "doora", speaker_id: "speaker_001"
        # negative/hard_negatives/speaker_002/dora_01.wav -> label: "non_doora", speaker_id: "speaker_002"
        # negative/speech/sample_01.wav -> label: "non_doora", speaker_id: "speaker_unknown"

        top_dir = parts[0].lower()
        if "positive" in top_dir or "doora" in top_dir:
            label = "doora"
            speaker_id = parts[1] if len(parts) > 2 else "positive_default_speaker"
        else:
            label = "non_doora"
            speaker_id = parts[2] if len(parts) > 3 else (parts[1] if len(parts) > 2 else "negative_default_speaker")

        try:
            meta = read_wav_header(path)
            records.append({
                "file_path": os.path.abspath(path),
                "relative_path": rel_path,
                "label": label,
                "speaker_id": speaker_id,
                "sample_rate": meta["sample_rate"],
                "channels": meta["channels"],
                "duration_seconds": round(meta["duration_seconds"], 4),
            })
        except Exception as e:
            print(f"[WARN] Skipping unreadable file {rel_path}: {e}")

    os.makedirs(os.path.dirname(os.path.abspath(output_manifest_path)), exist_ok=True)
    manifest_data = {
        "dataset_root": os.path.abspath(dataset_dir),
        "total_records": len(records),
        "positive_count": sum(1 for r in records if r["label"] == "doora"),
        "negative_count": sum(1 for r in records if r["label"] == "non_doora"),
        "unique_speakers": len(set(r["speaker_id"] for r in records)),
        "records": records,
    }

    with open(output_manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest_data, f, indent=2)

    print(f"[SUCCESS] Manifest created at {output_manifest_path}")
    print(f" - Positives: {manifest_data['positive_count']}")
    print(f" - Negatives: {manifest_data['negative_count']}")
    print(f" - Unique Speakers: {manifest_data['unique_speakers']}")
    return output_manifest_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate dataset manifest JSON.")
    parser.add_argument("--dataset_dir", type=str, default="kws_dataset")
    parser.add_argument("--output_path", type=str, default="training/data/manifest.json")
    args = parser.parse_args()

    build_manifest(args.dataset_dir, args.output_path)
