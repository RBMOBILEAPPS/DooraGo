#!/usr/bin/env python3
"""Deterministic speaker-disjoint dataset splitting tool for Doora KWS.

Supports:
- Standard 3-way speaker-disjoint partitioning (>= 3 speakers: Train, Val, Test disjoint)
- 2-Speaker Pilot Mode (2 speakers: Speaker 1 -> Train, Speaker 2 -> Val & Test holdout)
"""

import os
import sys
import json
import random
import argparse
from typing import Dict, Any, List

# Add parent training root to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))


def split_speaker_disjoint(
    manifest_path: str,
    output_dir: str = "training/data",
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
    test_ratio: float = 0.15,
    seed: int = 42
) -> Dict[str, str]:
    """Splits a manifest into speaker-disjoint train, validation, and test manifests."""
    with open(manifest_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    records = data.get("records", [])
    if not records:
        raise ValueError("Manifest contains no records.")

    # Group records by speaker ID
    speaker_map = {}
    for r in records:
        spk = r.get("speaker_id", "unknown")
        if spk not in speaker_map:
            speaker_map[spk] = []
        speaker_map[spk].append(r)

    all_speakers = sorted(list(speaker_map.keys()))
    rng = random.Random(seed)
    rng.shuffle(all_speakers)

    num_speakers = len(all_speakers)
    if num_speakers < 2:
        raise ValueError(
            f"At least 2 distinct speakers required for speaker-aware dataset split. Found {num_speakers}."
        )

    is_pilot_2_speaker = (num_speakers == 2)

    if is_pilot_2_speaker:
        # Speaker 1 -> Train, Speaker 2 -> Validation & Test holdout
        train_speakers = {all_speakers[0]}
        val_speakers = {all_speakers[1]}
        test_speakers = {all_speakers[1]}
        split_strategy = "2_speaker_pilot_split"
        pilot_warning = (
            "2-SPEAKER PILOT SPLIT: Speaker 1 allocated to Train; Speaker 2 allocated as unseen holdout for Validation and Test. "
            "Zero speaker leakage exists between Train and Val/Test. Note that a full 3-way disjoint evaluation requires >= 3 speakers."
        )
    else:
        num_val = max(1, int(round(num_speakers * val_ratio)))
        num_test = max(1, int(round(num_speakers * test_ratio)))
        num_train = max(1, num_speakers - num_val - num_test)

        train_speakers = set(all_speakers[:num_train])
        val_speakers = set(all_speakers[num_train : num_train + num_val])
        test_speakers = set(all_speakers[num_train + num_val:])
        split_strategy = "3_way_speaker_disjoint"
        pilot_warning = None

    # Strict assertion: Zero speaker leakage between Train and Val, and between Train and Test
    assert len(train_speakers.intersection(val_speakers)) == 0, "Speaker leakage detected between Train and Val!"
    assert len(train_speakers.intersection(test_speakers)) == 0, "Speaker leakage detected between Train and Test!"

    if not is_pilot_2_speaker:
        assert len(val_speakers.intersection(test_speakers)) == 0, "Speaker leakage detected between Val and Test!"

    train_records = [r for spk in train_speakers for r in speaker_map[spk]]
    val_records = [r for spk in val_speakers for r in speaker_map[spk]]
    test_records = [r for spk in test_speakers for r in speaker_map[spk]]

    os.makedirs(output_dir, exist_ok=True)
    paths = {
        "train": os.path.join(output_dir, "train_manifest.json"),
        "val": os.path.join(output_dir, "val_manifest.json"),
        "test": os.path.join(output_dir, "test_manifest.json"),
    }

    for split_name, recs, spks in [
        ("train", train_records, train_speakers),
        ("val", val_records, val_speakers),
        ("test", test_records, test_speakers),
    ]:
        payload = {
            "split": split_name,
            "split_strategy": split_strategy,
            "is_pilot_2_speaker": is_pilot_2_speaker,
            "seed": seed,
            "total_records": len(recs),
            "speakers": sorted(list(spks)),
            "positive_count": sum(1 for r in recs if r["label"] == "doora"),
            "negative_count": sum(1 for r in recs if r["label"] == "non_doora"),
            "notes": pilot_warning if is_pilot_2_speaker else "Standard 3-way speaker-disjoint partition.",
            "records": recs,
        }
        with open(paths[split_name], "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2)

    print("\n" + "=" * 60)
    if is_pilot_2_speaker:
        print("SPEAKER-AWARE SPLIT COMPLETED (2-Speaker Pilot Mode)")
        print("=" * 60)
        print(f"Total Speakers: 2 (Random Seed: {seed})")
        print(f"Train Speaker:    {sorted(list(train_speakers))} ({len(train_records)} clips)")
        print(f"Val/Test Speaker: {sorted(list(val_speakers))} ({len(val_records)} clips)")
        print(f"[NOTE] {pilot_warning}")
    else:
        print("SPEAKER-DISJOINT SPLIT COMPLETED (Zero Leakage Verified)")
        print("=" * 60)
        print(f"Total Speakers: {num_speakers} (Random Seed: {seed})")
        print(f"Train: {len(train_records)} clips | {len(train_speakers)} speakers -> {paths['train']}")
        print(f"Val:   {len(val_records)} clips | {len(val_speakers)} speakers -> {paths['val']}")
        print(f"Test:  {len(test_records)} clips | {len(test_speakers)} speakers -> {paths['test']}")
    print("=" * 60)

    return paths


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Split manifest into speaker-disjoint partitions.")
    parser.add_argument("--manifest_path", type=str, default="training/data/manifest.json")
    parser.add_argument("--output_dir", type=str, default="training/data")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--val_ratio", type=float, default=0.15, help="Fraction of speakers for validation (default: 0.15)")
    parser.add_argument("--test_ratio", type=float, default=0.15, help="Fraction of speakers for test (default: 0.15)")
    args = parser.parse_args()

    split_speaker_disjoint(args.manifest_path, args.output_dir, seed=args.seed,
                           val_ratio=args.val_ratio, test_ratio=args.test_ratio)

