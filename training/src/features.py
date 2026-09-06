"""Feature extraction module for Log-Mel Spectrogram computation.

Ensures strict parity with on-device mobile preprocessing.
"""

import numpy as np
import tensorflow as tf
from typing import Dict, Any, Tuple


class LogMelFeatureExtractor:
    """Computes Log-Mel Spectrograms from 16kHz audio waveforms.

    Strict Configuration:
    - Sample Rate: 16000 Hz
    - Window Length: 30ms (480 samples)
    - Hop Length: 10ms (160 samples)
    - FFT Size: 512
    - Mel Bins: 40 (80 Hz to 7600 Hz)
    - Feature Output Shape: [98, 40, 1] for 1.0s (16000 samples)
    """

    def __init__(
        self,
        sample_rate: int = 16000,
        frame_length_samples: int = 480,
        frame_step_samples: int = 160,
        fft_length: int = 512,
        num_mel_bins: int = 40,
        lower_edge_hertz: float = 80.0,
        upper_edge_hertz: float = 7600.0,
        log_eps: float = 1.0e-6,
    ):
        self.sample_rate = sample_rate
        self.frame_length = frame_length_samples
        self.frame_step = frame_step_samples
        self.fft_length = fft_length
        self.num_mel_bins = num_mel_bins
        self.lower_edge_hertz = lower_edge_hertz
        self.upper_edge_hertz = upper_edge_hertz
        self.log_eps = log_eps

        # Pre-calculate standard linear-to-mel weight matrix
        self.linear_to_mel_weight_matrix = tf.signal.linear_to_mel_weight_matrix(
            num_mel_bins=self.num_mel_bins,
            num_spectrogram_bins=self.fft_length // 2 + 1,
            sample_rate=self.sample_rate,
            lower_edge_hertz=self.lower_edge_hertz,
            upper_edge_hertz=self.upper_edge_hertz,
        )

    def extract_from_waveform(self, waveform: np.ndarray) -> np.ndarray:
        """Extracts Log-Mel Spectrogram from a 1D float32 audio array.

        Args:
            waveform: 1D NumPy float32 array [-1.0, 1.0].

        Returns:
            3D NumPy float32 array with shape [TimeFrames, MelBins, 1].
        """
        tensor = tf.convert_to_tensor(waveform, dtype=tf.float32)

        # 1. Short-Time Fourier Transform (STFT) with Hann window
        stft = tf.signal.stft(
            tensor,
            frame_length=self.frame_length,
            frame_step=self.frame_step,
            fft_length=self.fft_length,
            window_fn=tf.signal.hann_window,
            pad_end=False,
        )

        # 2. Magnitude Spectrogram
        spectrogram = tf.abs(stft)

        # 3. Mel Filterbank Multiplication
        mel_spectrogram = tf.tensordot(spectrogram, self.linear_to_mel_weight_matrix, 1)
        mel_spectrogram.set_shape(
            spectrogram.shape[:-1].concatenate(self.linear_to_mel_weight_matrix.shape[-1:])
        )

        # 4. Log Dynamic Range Compression
        log_mel_spectrogram = tf.math.log(mel_spectrogram + self.log_eps)

        # 5. Expand channel dimension to [Time, Mel, 1]
        log_mel_spectrogram = tf.expand_dims(log_mel_spectrogram, axis=-1)

        return log_mel_spectrogram.numpy()

    def get_feature_shape(self, num_samples: int = 16000) -> Tuple[int, int, int]:
        """Returns expected feature output shape for a given audio length."""
        num_frames = 1 + (num_samples - self.frame_length) // self.frame_step
        return (num_frames, self.num_mel_bins, 1)

