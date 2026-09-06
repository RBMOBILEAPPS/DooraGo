#!/usr/bin/env python3
"""Evaluation tool for static and streaming keyword spotting testing."""

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
from src.metrics import compute_kws_metrics, generate_threshold_calibration_table


def evaluate(config_path: str, model_path: str = None):
    """Evaluates a trained KWS model on the speaker-disjoint test set."""
    with open(config_path, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f)

    if model_path is None:
        model_path = os.path.join(cfg["paths"]["checkpoint_dir"], "best_model.keras")

    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Model checkpoint not found: {model_path}")

    print(f"[INFO] Loading model from {model_path}...")
    model = tf.keras.models.load_model(model_path)

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

    test_dataset = KWSDataset(
        manifest_path=cfg["paths"]["test_manifest_path"],
        feature_extractor=feature_extractor,
        label_to_index=cfg["classes"]["label_to_index"],
        target_sample_rate=cfg["audio"]["sample_rate"],
        target_samples=cfg["audio"]["clip_samples"],
        augment=False,
    )

    print(f"[INFO] Evaluating across {len(test_dataset)} holdout test samples...")

    y_true = []
    y_scores = []

    for i in range(len(test_dataset)):
        feat, lbl = test_dataset.get_item(i)
        feat_batch = np.expand_dims(feat, axis=0)  # Shape [1, Time, Mel, 1]
        probs = model.predict(feat_batch, verbose=0)[0]

        target_class_idx = cfg["classes"]["label_to_index"]["doora"]
        y_true.append(lbl)
        y_scores.append(probs[target_class_idx])

    y_true = np.array(y_true)
    y_scores = np.array(y_scores)

    # Compute calibration table across thresholds
    calibration_table = generate_threshold_calibration_table(y_true, y_scores)

    default_thresh = cfg["evaluation"]["default_confidence_threshold"]
    primary_metrics = compute_kws_metrics(y_true, y_scores, threshold=default_thresh)

    print("\n" + "=" * 65)
    print(f"DOORAGO KWS EVALUATION REPORT (Threshold = {default_thresh})")
    print("=" * 65)
    print(f"Accuracy:                    {primary_metrics['accuracy'] * 100.0:.2f}%")
    print(f"Precision:                   {primary_metrics['precision'] * 100.0:.2f}%")
    print(f"Recall:                      {primary_metrics['recall'] * 100.0:.2f}%")
    print(f"F1 Score:                    {primary_metrics['f1_score']:.4f}")
    print(f"False Reject Rate (FRR):     {primary_metrics['false_reject_rate_percent']:.2f}% (Target: <= {cfg['evaluation']['target_frr_percent']}%)")
    print(f"False Accept Rate (FAR):     {primary_metrics['false_accept_rate_percent']:.2f}%")
    print(f"True Positives (TP):         {primary_metrics['confusion_matrix']['true_positives']}")
    print(f"False Negatives (FN):        {primary_metrics['confusion_matrix']['false_negatives']}")
    print(f"False Positives (FP):        {primary_metrics['confusion_matrix']['false_positives']}")
    print(f"True Negatives (TN):         {primary_metrics['confusion_matrix']['true_negatives']}")
    print("=" * 65)

    print("\nTHRESHOLD CALIBRATION TABLE:")
    print(f"{'Threshold':<10} | {'FRR (%)':<10} | {'FAR (%)':<10} | {'Precision':<10} | {'Recall':<10} | {'F1':<10}")
    print("-" * 65)
    for row in calibration_table:
        print(f"{row['threshold']:<10.2f} | {row['false_reject_rate_percent']:<10.2f} | {row['false_accept_rate_percent']:<10.2f} | {row['precision']:<10.3f} | {row['recall']:<10.3f} | {row['f1_score']:<10.3f}")

    eval_out_path = os.path.join(cfg["paths"]["output_dir"], "evaluation_results.json")
    with open(eval_out_path, "w", encoding="utf-8") as f:
        json.dump({"metrics": primary_metrics, "calibration_table": calibration_table}, f, indent=2)

    print(f"\n[SUCCESS] Full metrics saved to: {eval_out_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Evaluate trained Doora KWS model.")
    parser.add_argument("--config", type=str, default="training/configs/doora_kws.yaml")
    parser.add_argument("--model", type=str, default=None)
    args = parser.parse_args()

    evaluate(args.config, args.model)
