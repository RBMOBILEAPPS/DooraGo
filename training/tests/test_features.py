"""Unit tests for Log-Mel Spectrogram feature extraction."""

import numpy as np
import pytest
from src.features import LogMelFeatureExtractor


def test_feature_extraction_shape():
    """Validates that a 1.0-second 16kHz audio array produces exactly [98, 40, 1] features."""
    extractor = LogMelFeatureExtractor(
        sample_rate=16000,
        frame_length_samples=480,  # 30ms
        frame_step_samples=160,    # 10ms
        fft_length=512,
        num_mel_bins=40,
    )

    dummy_audio = np.random.uniform(-0.8, 0.8, 16000).astype(np.float32)
    features = extractor.extract_from_waveform(dummy_audio)

    assert features.ndim == 3
    assert features.shape == (98, 40, 1)
    assert not np.isnan(features).any()
    assert not np.isinf(features).any()
