#!/usr/bin/env python3
"""Training script for Doora Keyword Spotting (DS-CNN-Tiny) model."""

import os
import sys
import yaml
import json
import argparse
import tensorflow as tf

# Add parent training root to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from src.features import LogMelFeatureExtractor
from src.dataset import KWSDataset
from src.model import build_ds_cnn_tiny


def train(config_path: str):
    """Executes model training following configuration settings."""
    if not os.path.exists(config_path):
        raise FileNotFoundError(f"Configuration file not found: {config_path}")

    with open(config_path, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f)

    # 1. Deterministic Seeds
    seed = cfg["project"].get("random_seed", 42)
    tf.random.set_seed(seed)

    # 2. Instantiate Audio Feature Extractor
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

    label_to_index = cfg["classes"]["label_to_index"]

    # 3. Construct Datasets
    print("[INFO] Constructing training and validation datasets...")
    train_dataset = KWSDataset(
        manifest_path=cfg["paths"]["train_manifest_path"],
        feature_extractor=feature_extractor,
        label_to_index=label_to_index,
        target_sample_rate=cfg["audio"]["sample_rate"],
        target_samples=cfg["audio"]["clip_samples"],
        augment=cfg["augmentation"]["enabled_for_training"],
        augmentation_config=cfg["augmentation"],
        seed=seed,
    )

    val_dataset = KWSDataset(
        manifest_path=cfg["paths"]["val_manifest_path"],
        feature_extractor=feature_extractor,
        label_to_index=label_to_index,
        target_sample_rate=cfg["audio"]["sample_rate"],
        target_samples=cfg["audio"]["clip_samples"],
        augment=False,
        seed=seed + 1,
    )

    batch_size = cfg["training"]["batch_size"]
    train_ds = train_dataset.to_tf_dataset(batch_size=batch_size, shuffle=True)
    val_ds = val_dataset.to_tf_dataset(batch_size=batch_size, shuffle=False)

    # 4. Build Neural Model
    input_shape = feature_extractor.get_feature_shape(num_samples=cfg["audio"]["clip_samples"])
    print(f"[INFO] Building DS-CNN model with input shape {input_shape}...")

    m_cfg = cfg["model"]
    model = build_ds_cnn_tiny(
        input_shape=input_shape,
        num_classes=cfg["classes"]["num_classes"],
        first_conv_filters=m_cfg["first_conv_filters"],
        first_conv_kernel=tuple(m_cfg["first_conv_kernel"]),
        first_conv_stride=tuple(m_cfg["first_conv_stride"]),
        ds_layers_config=m_cfg["ds_layers"],
        dropout_rate=m_cfg["dropout_rate"],
    )

    model.summary()

    # 5. Compile Model with AdamW and Cross-Entropy
    lr = cfg["training"]["learning_rate"]
    optimizer = tf.keras.optimizers.AdamW(learning_rate=lr, weight_decay=cfg["training"]["weight_decay"])
    model.compile(
        optimizer=optimizer,
        loss=tf.keras.losses.SparseCategoricalCrossentropy(),
        metrics=["accuracy"],
    )

    # 6. Setup Callbacks
    checkpoint_dir = cfg["paths"]["checkpoint_dir"]
    os.makedirs(checkpoint_dir, exist_ok=True)
    best_model_path = os.path.join(checkpoint_dir, "best_model.keras")

    callbacks = [
        tf.keras.callbacks.ModelCheckpoint(
            filepath=best_model_path,
            monitor="val_loss",
            mode="min",
            save_best_only=True,
            verbose=1,
        ),
        tf.keras.callbacks.EarlyStopping(
            monitor=cfg["training"]["early_stopping_metric"],
            mode=cfg["training"]["early_stopping_mode"],
            patience=cfg["training"]["early_stopping_patience"],
            restore_best_weights=True,
            verbose=1,
        ),
    ]

    # 7. Execute Training Loop
    epochs = cfg["training"]["epochs"]
    print(f"[INFO] Starting training for {epochs} epochs...")
    history = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=epochs,
        callbacks=callbacks,
    )

    # Save training history
    output_dir = cfg["paths"]["output_dir"]
    os.makedirs(output_dir, exist_ok=True)
    history_path = os.path.join(output_dir, "training_history.json")
    with open(history_path, "w", encoding="utf-8") as f:
        json.dump(history.history, f, indent=2)

    print(f"[SUCCESS] Training completed. Best model saved to: {best_model_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train Doora KWS model.")
    parser.add_argument("--config", type=str, default="training/configs/doora_kws.yaml")
    args = parser.parse_args()

    train(args.config)
