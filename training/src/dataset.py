"""Dataset loading, manifest management, and batch generation."""

import os
import json
import random
import numpy as np
import tensorflow as tf
from typing import List, Dict, Any, Tuple, Optional
from .audio import load_audio_wav
from .features import LogMelFeatureExtractor


class KWSDataset:
    """Manages audio clips from a JSON manifest for KWS training/evaluation."""

    def __init__(
        self,
        manifest_path: str,
        feature_extractor: LogMelFeatureExtractor,
        label_to_index: Dict[str, int],
        target_sample_rate: int = 16000,
        target_samples: int = 16000,
        augment: bool = False,
        augmentation_config: Optional[Dict[str, Any]] = None,
        seed: int = 42
    ):
        self.manifest_path = manifest_path
        self.feature_extractor = feature_extractor
        self.label_to_index = label_to_index
        self.target_sample_rate = target_sample_rate
        self.target_samples = target_samples
        self.augment = augment
        self.aug_config = augmentation_config or {}
        self.rng = random.Random(seed)
        self.np_rng = np.random.RandomState(seed)

        self.records = self._load_manifest()

    def _load_manifest(self) -> List[Dict[str, Any]]:
        if not os.path.exists(self.manifest_path):
            raise FileNotFoundError(f"Manifest file not found: {self.manifest_path}")

        with open(self.manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        records = data.get("records", []) if isinstance(data, dict) else data
        if not records:
            raise ValueError(f"Manifest is empty: {self.manifest_path}")
        return records

    def __len__(self) -> int:
        return len(self.records)

    def get_speakers(self) -> List[str]:
        return sorted(list(set(r.get("speaker_id", "unknown") for r in self.records)))

    def _apply_augmentation(self, audio: np.ndarray) -> np.ndarray:
        """Applies deterministic, bounded on-the-fly audio augmentation."""
        if not self.augment:
            return audio

        # 1. Random Time Shift (Jitter)
        shift_range = self.aug_config.get("time_shift_ms_range", [-150, 150])
        max_shift_samples = int(abs(shift_range[1]) * self.target_sample_rate / 1000.0)
        shift = self.rng.randint(-max_shift_samples, max_shift_samples)
        if shift != 0:
            audio = np.roll(audio, shift)
            if shift > 0:
                audio[:shift] = 0.0
            else:
                audio[shift:] = 0.0

        # 2. Random Gain Perturbation (dB)
        gain_db_range = self.aug_config.get("gain_db_range", [-6.0, 6.0])
        gain_db = self.rng.uniform(gain_db_range[0], gain_db_range[1])
        gain_linear = 10.0 ** (gain_db / 20.0)
        audio = audio * gain_linear

        # 3. Additive Gaussian/Ambient Noise
        noise_cfg = self.aug_config.get("noise_injection", {})
        if noise_cfg.get("enabled", False):
            snr_db = self.rng.uniform(noise_cfg.get("min_snr_db", 5.0), noise_cfg.get("max_snr_db", 25.0))
            signal_power = np.mean(np.square(audio)) + 1e-12
            noise_power = signal_power / (10.0 ** (snr_db / 10.0))
            noise = self.np_rng.normal(0, np.sqrt(noise_power), size=audio.shape).astype(np.float32)
            audio = audio + noise

        # Clip values safely to [-1.0, 1.0]
        return np.clip(audio, -1.0, 1.0)

    def get_item(self, idx: int) -> Tuple[np.ndarray, int]:
        record = self.records[idx]
        file_path = record["file_path"]
        label_str = record["label"]

        if label_str not in self.label_to_index:
            raise KeyError(f"Label '{label_str}' not found in label_to_index mapping.")

        label_idx = self.label_to_index[label_str]

        # Load normalized waveform
        audio, _ = load_audio_wav(
            file_path,
            target_sample_rate=self.target_sample_rate,
            target_samples=self.target_samples,
            normalize=True,
        )

        # Apply augmentation if enabled
        if self.augment:
            audio = self._apply_augmentation(audio)

        # Extract Log-Mel Spectrogram features [Time, Mel, 1]
        features = self.feature_extractor.extract_from_waveform(audio)

        return features, label_idx

    def to_tf_dataset(self, batch_size: int = 64, shuffle: bool = True) -> tf.data.Dataset:
        """Constructs a high-performance tf.data.Dataset pipeline."""
        def generator():
            indices = list(range(len(self)))
            if shuffle:
                self.rng.shuffle(indices)
            for idx in indices:
                feat, lbl = self.get_item(idx)
                yield feat, lbl

        sample_feat, _ = self.get_item(0)
        output_signature = (
            tf.TensorSpec(shape=sample_feat.shape, dtype=tf.float32),
            tf.TensorSpec(shape=(), dtype=tf.int32),
        )

        ds = tf.data.Dataset.from_generator(generator, output_signature=output_signature)
        ds = ds.batch(batch_size).prefetch(tf.data.AUTOTUNE)
        return ds
