"""Unit tests for audio utilities."""

import os
import wave
import tempfile
import numpy as np
import pytest
from src.audio import read_wav_header, load_audio_wav, compute_audio_rms, compute_audio_zcr


def test_audio_rms_and_zcr_computation():
    """Validates basic RMS and ZCR numerical computations."""
    # Sine wave
    t = np.linspace(0, 1.0, 16000, endpoint=False)
    sine = (np.sin(2 * np.pi * 440 * t) * 0.5).astype(np.float32)

    rms = compute_audio_rms(sine)
    assert 0.34 < rms < 0.36

    zcr = compute_audio_zcr(sine)
    assert 0.05 < zcr < 0.06

    # Absolute silence
    silence = np.zeros(16000, dtype=np.float32)
    assert compute_audio_rms(silence) == 0.0
    assert compute_audio_zcr(silence) == 0.0


def test_wav_read_and_load():
    """Validates reading WAV header and sample loading."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tf:
        wav_path = tf.name

    try:
        # Write dummy 16-bit PCM mono 16kHz WAV
        sr = 16000
        samples = (np.sin(2 * np.pi * 440 * np.linspace(0, 1.0, sr, endpoint=False)) * 16000).astype(np.int16)

        with wave.open(wav_path, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(sr)
            wf.writeframes(samples.tobytes())

        header = read_wav_header(wav_path)
        assert header["sample_rate"] == 16000
        assert header["channels"] == 1
        assert header["bit_depth"] == 16
        assert 0.99 < header["duration_seconds"] < 1.01

        audio_loaded, loaded_sr = load_audio_wav(wav_path, target_sample_rate=16000, target_samples=16000)
        assert loaded_sr == 16000
        assert len(audio_loaded) == 16000
        assert np.max(np.abs(audio_loaded)) <= 1.0

    finally:
        if os.path.exists(wav_path):
            os.remove(wav_path)
