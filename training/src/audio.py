"""Audio utility functions for loading, validating, and preprocessing WAV clips."""

import os
import wave
import struct
import numpy as np
from typing import Tuple, Dict, Any, Optional


def read_wav_header(file_path: str) -> Dict[str, Any]:
    """Reads metadata from WAV header without loading full raw sample data.

    Returns dict containing sample_rate, channels, sample_width, num_frames, duration_seconds.
    """
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"Audio file not found: {file_path}")

    with wave.open(file_path, "rb") as wf:
        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        sample_rate = wf.getframerate()
        num_frames = wf.getnframes()
        duration_seconds = num_frames / float(sample_rate) if sample_rate > 0 else 0.0

    return {
        "file_path": file_path,
        "channels": channels,
        "sample_width": sample_width,
        "bit_depth": sample_width * 8,
        "sample_rate": sample_rate,
        "num_frames": num_frames,
        "duration_seconds": duration_seconds,
    }


def load_audio_wav(
    file_path: str,
    target_sample_rate: int = 16000,
    target_samples: int = 16000,
    normalize: bool = True
) -> Tuple[np.ndarray, int]:
    """Loads a 16-bit PCM WAV file into a normalized NumPy float32 array.

    Args:
        file_path: Path to WAV audio file.
        target_sample_rate: Expected sample rate (default: 16000 Hz).
        target_samples: Expected length in samples (default: 16000 for 1.0s).
        normalize: If True, scales PCM int16 to [-1.0, 1.0] float32.

    Returns:
        (audio_array, actual_sample_rate)
    """
    with wave.open(file_path, "rb") as wf:
        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        sr = wf.getframerate()
        num_frames = wf.getnframes()

        if sample_width != 2:
            raise ValueError(f"Expected 16-bit PCM (sample width 2), got {sample_width} in {file_path}")

        raw_bytes = wf.readframes(num_frames)

    # Convert binary bytes to int16 array
    audio = np.frombuffer(raw_bytes, dtype=np.int16)

    # Convert stereo to mono by averaging channels if necessary
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1).astype(np.int16)

    # Convert to float32
    if normalize:
        audio_float = audio.astype(np.float32) / 32768.0
    else:
        audio_float = audio.astype(np.float32)

    # Deterministic pad or center-crop to exact target_samples
    if len(audio_float) < target_samples:
        pad_total = target_samples - len(audio_float)
        pad_left = pad_total // 2
        pad_right = pad_total - pad_left
        audio_float = np.pad(audio_float, (pad_left, pad_right), mode="constant", constant_values=0.0)
    elif len(audio_float) > target_samples:
        start_idx = (len(audio_float) - target_samples) // 2
        audio_float = audio_float[start_idx : start_idx + target_samples]

    return audio_float, sr


def compute_audio_rms(audio: np.ndarray) -> float:
    """Computes Root Mean Square (RMS) energy of an audio waveform."""
    if len(audio) == 0:
        return 0.0
    return float(np.sqrt(np.mean(np.square(audio))))


def compute_audio_zcr(audio: np.ndarray) -> float:
    """Computes Zero-Crossing Rate (ZCR) of an audio waveform."""
    if len(audio) < 2:
        return 0.0
    zero_crossings = np.sum(np.abs(np.diff(np.sign(audio))) > 0)
    return float(zero_crossings / len(audio))


def save_audio_wav(
    file_path: str,
    audio: np.ndarray,
    sample_rate: int = 16000,
    ensure_pcm16: bool = True
) -> str:
    """Saves a NumPy float32 or int16 array to a standard 16-bit Mono PCM WAV file.

    Args:
        file_path: Destination WAV path.
        audio: 1D NumPy array of audio samples (Float32 in [-1.0, 1.0] or Int16).
        sample_rate: Target sample rate in Hz (default: 16000).
        ensure_pcm16: If True, clips and converts Float32 to 16-bit signed integer.

    Returns:
        Absolute destination file path.
    """
    os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)

    if len(audio) == 0:
        pcm16_data = np.array([], dtype=np.int16)
    else:
        if audio.ndim > 1:
            # Downmix multi-channel to mono
            audio = np.mean(audio, axis=1)

        # Sanitize NaN and +/-Inf to prevent numeric distortion or undefined casting
        audio = np.nan_to_num(audio, nan=0.0, posinf=1.0, neginf=-1.0)

        if np.issubdtype(audio.dtype, np.floating):
            # Clip to [-1.0, 1.0] to prevent integer wrap-around distortion
            clipped = np.clip(audio, -1.0, 1.0)
            pcm16_data = (clipped * 32767.0).astype(np.int16)
        else:
            pcm16_data = audio.astype(np.int16)

    with wave.open(file_path, "wb") as wf:
        wf.setnchannels(1)  # Strictly Mono
        wf.setsampwidth(2)  # 16-bit PCM (2 bytes)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm16_data.tobytes())

    return os.path.abspath(file_path)


def resample_audio(
    audio: np.ndarray,
    orig_sr: int,
    target_sr: int = 16000
) -> np.ndarray:
    """Resamples a 1D audio array from orig_sr to target_sr using SciPy or linear interpolation."""
    if len(audio) == 0 or orig_sr == target_sr:
        return audio

    if orig_sr <= 0 or target_sr <= 0:
        raise ValueError(f"Sample rates must be positive integers, got orig_sr={orig_sr}, target_sr={target_sr}")

    # Sanitize NaN and +/-Inf
    audio = np.nan_to_num(audio, nan=0.0, posinf=1.0, neginf=-1.0)

    num_target_samples = int(round(len(audio) * float(target_sr) / float(orig_sr)))
    if num_target_samples == 0:
        return np.array([], dtype=audio.dtype)

    if len(audio) == 1:
        return np.full(num_target_samples, audio[0], dtype=audio.dtype)

    try:
        from scipy import signal
        resampled = signal.resample(audio, num_target_samples)
        return resampled.astype(audio.dtype)
    except ImportError:
        # Fallback to deterministic linear interpolation if SciPy is not installed
        old_indices = np.linspace(0, len(audio) - 1, len(audio))
        new_indices = np.linspace(0, len(audio) - 1, num_target_samples)
        return np.interp(new_indices, old_indices, audio).astype(audio.dtype)


