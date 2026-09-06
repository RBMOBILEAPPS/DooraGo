#!/usr/bin/env python3
"""Exports a trained Keras model checkpoint to fully quantized (Int8) TFLite format."""

import os
import sys
import yaml
import json
import argparse
import numpy as np
import tensorflow as tf

# Add parent training root to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from src.features import LogMelFeatureExtractor
from src.dataset import KWSDataset
from src.quantization import export_tflite_full_int8


def export(config_path: str, model_path: str = None):
    """Executes Int8 post-training quantization and TFLite FlatBuffer export."""
    with open(config_path, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f)

    if model_path is None:
        model_path = os.path.join(cfg["paths"]["checkpoint_dir"], "best_model.keras")

    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Trained model not found at {model_path}")

    print(f"[INFO] Loading trained model from {model_path}...")
    model = tf.keras.models.load_model(model_path)

    # Prepare representative calibration dataset
    f_cfg = cfg["features"]
    feature_extractor = LogMelFeatureExtractor(
        sample_rate=cfg["audio"]["sample_rate"],
        frame_length_samples=f_cfg["frame_length_samples"],
        frame_step_samples=f_cfg["frame_step_samples"],
        fft_length=f_cfg["fft_length"],
        num_mel_bins=f_cfg["num_mel_bins"],
        lower_edge_hertz=f_cfg["lower_edge_hertz"],
        upper_edge_hertz=f_cfg["upper_edge_hertz"],
        log_eps=f_cfg["log_compression_eps"],
    )

    train_dataset = KWSDataset(
        manifest_path=cfg["paths"]["train_manifest_path"],
        feature_extractor=feature_extractor,
        label_to_index=cfg["classes"]["label_to_index"],
        target_sample_rate=cfg["audio"]["sample_rate"],
        target_samples=cfg["audio"]["clip_samples"],
        augment=False,
    )

    num_calib = min(len(train_dataset), cfg["quantization"]["num_calibration_samples"])
    print(f"[INFO] Preparing representative calibration data ({num_calib} samples)...")

    def representative_dataset_gen():
        for i in range(num_calib):
            feat, _ = train_dataset.get_item(i)
            # Yield batch shape [1, Time, Mel, 1]
            yield [np.expand_dims(feat, axis=0).astype(np.float32)]

    output_tflite = cfg["paths"]["exported_tflite_path"]
    print(f"[INFO] Running full integer (Int8) quantization export -> {output_tflite}...")

    export_meta = export_tflite_full_int8(
        keras_model=model,
        representative_dataset_generator=representative_dataset_gen,
        output_tflite_path=output_tflite,
        inference_input_type=cfg["quantization"]["inference_input_type"],
        inference_output_type=cfg["quantization"]["inference_output_type"],
    )

    print("\n" + "=" * 60)
    print("TFLITE EXPORT COMPLETED SUCCESSFULLY")
    print("=" * 60)
    print(f"Output File:     {export_meta['output_path']}")
    print(f"File Size:       {export_meta['file_size_kb']:.2f} KB ({export_meta['file_size_bytes']} bytes)")
    print(f"Input Tensors:   {export_meta['inputs']}")
    print(f"Output Tensors:  {export_meta['outputs']}")
    print("=" * 60)

    # Save export metadata for Android contract verification
    contract_path = cfg["paths"]["contract_output_path"]
    with open(contract_path, "w", encoding="utf-8") as f:
        json.dump(export_meta, f, indent=2)

    print(f"[INFO] Machine-readable contract saved to: {contract_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Export trained model to Int8 TFLite.")
    parser.add_argument("--config", type=str, default="training/configs/doora_kws.yaml")
    parser.add_argument("--model", type=str, default=None)
    args = parser.parse_args()

    export(args.config, args.model)
