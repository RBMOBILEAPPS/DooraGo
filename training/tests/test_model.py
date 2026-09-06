"""Unit tests for model architecture dimensions and parameter counts."""

import pytest
import tensorflow as tf
from src.model import build_ds_cnn_tiny


def test_ds_cnn_model_build_and_forward():
    """Validates building DS-CNN-Tiny and running a dummy forward pass."""
    input_shape = (98, 40, 1)
    num_classes = 2

    model = build_ds_cnn_tiny(input_shape=input_shape, num_classes=num_classes)
    assert model.input_shape == (None, 98, 40, 1)
    assert model.output_shape == (None, num_classes)

    dummy_input = tf.random.normal((2, 98, 40, 1))
    output = model(dummy_input)

    assert output.shape == (2, num_classes)
    # Output is a valid probability distribution (Softmax)
    prob_sums = tf.reduce_sum(output, axis=-1)
    assert tf.reduce_all(tf.abs(prob_sums - 1.0) < 1e-5)
