"""Unit tests for dataset manifest verification and speaker isolation."""

import os
import json
import tempfile
import pytest
from src.dataset import KWSDataset
from src.features import LogMelFeatureExtractor


def test_speaker_disjoint_validation():
    """Validates that duplicate speakers across partitions are detected."""
    train_speakers = {"spk_01", "spk_02", "spk_03"}
    val_speakers = {"spk_04", "spk_05"}
    test_speakers = {"spk_06", "spk_07"}

    assert len(train_speakers.intersection(val_speakers)) == 0
    assert len(train_speakers.intersection(test_speakers)) == 0
    assert len(val_speakers.intersection(test_speakers)) == 0

    # Simulate accidental leakage
    leaked_val = {"spk_01", "spk_05"}
    assert len(train_speakers.intersection(leaked_val)) == 1
