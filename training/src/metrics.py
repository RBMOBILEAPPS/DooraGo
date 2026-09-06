"""Evaluation metrics and threshold calibration for Keyword Spotting."""

import numpy as np
from typing import Dict, Any, List, Tuple


def compute_kws_metrics(
    y_true: np.ndarray,
    y_scores: np.ndarray,
    target_class_index: int = 1,
    threshold: float = 0.85
) -> Dict[str, Any]:
    """Computes standard and keyword-spotting metrics at a specific confidence threshold.

    Args:
        y_true: Ground-truth class indices [N].
        y_scores: Output probability scores for the target class [N].
        target_class_index: Class index representing "Doora" (default: 1).
        threshold: Confidence threshold for positive activation.

    Returns:
        Dictionary containing Precision, Recall, FRR (%), FAR (%), F1, Confusion Matrix.
    """
    is_positive = (y_true == target_class_index)
    is_negative = ~is_positive

    predicted_positive = (y_scores >= threshold)
    predicted_negative = ~predicted_positive

    tp = int(np.sum(is_positive & predicted_positive))
    fn = int(np.sum(is_positive & predicted_negative))
    fp = int(np.sum(is_negative & predicted_positive))
    tn = int(np.sum(is_negative & predicted_negative))

    total_positives = tp + fn
    total_negatives = fp + tn

    # False Reject Rate (FRR): Missed detections among true positives
    frr_percent = (fn / total_positives * 100.0) if total_positives > 0 else 0.0

    # False Accept Rate (FAR): False alarms among true negatives
    far_percent = (fp / total_negatives * 100.0) if total_negatives > 0 else 0.0

    precision = (tp / (tp + fp)) if (tp + fp) > 0 else 0.0
    recall = (tp / (tp + fn)) if (tp + fn) > 0 else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0
    accuracy = (tp + tn) / len(y_true) if len(y_true) > 0 else 0.0

    return {
        "threshold": threshold,
        "accuracy": accuracy,
        "precision": precision,
        "recall": recall,
        "f1_score": f1,
        "false_reject_rate_percent": frr_percent,
        "false_accept_rate_percent": far_percent,
        "confusion_matrix": {
            "true_positives": tp,
            "false_negatives": fn,
            "false_positives": fp,
            "true_negatives": tn,
        },
        "total_samples": len(y_true),
        "total_positives": total_positives,
        "total_negatives": total_negatives,
    }


def generate_threshold_calibration_table(
    y_true: np.ndarray,
    y_scores: np.ndarray,
    thresholds: List[float] = None
) -> List[Dict[str, Any]]:
    """Evaluates multiple thresholds to generate DET / ROC calibration tables."""
    if thresholds is None:
        thresholds = [0.50, 0.60, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 0.98]

    results = []
    for thresh in thresholds:
        metrics = compute_kws_metrics(y_true, y_scores, threshold=thresh)
        results.append(metrics)
    return results
