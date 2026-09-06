"""Keyword Spotting (KWS) Neural Network Architectures."""

import tensorflow as tf
from tensorflow.keras import layers, models
from typing import Tuple, Dict, Any, List


def build_ds_cnn_tiny(
    input_shape: Tuple[int, int, int] = (98, 40, 1),
    num_classes: int = 2,
    first_conv_filters: int = 28,
    first_conv_kernel: Tuple[int, int] = (10, 4),
    first_conv_stride: Tuple[int, int] = (2, 2),
    ds_layers_config: List[Dict[str, Any]] = None,
    dropout_rate: float = 0.20,
) -> tf.keras.Model:
    """Builds a lightweight Depthwise-Separable CNN (DS-CNN-Tiny) for Keyword Spotting.

    Designed for ultra-low latency (<10ms) and minimal memory footprint (<300KB Int8).

    Args:
        input_shape: Dimension of Log-Mel spectrogram [TimeFrames, MelBins, Channels].
        num_classes: Number of output classification targets.
        first_conv_filters: Number of initial 2D convolution filters.
        first_conv_kernel: Initial standard 2D convolution kernel size.
        first_conv_stride: Initial convolution stride.
        ds_layers_config: Configuration list for depthwise separable blocks.
        dropout_rate: Dropout rate before classification layer.

    Returns:
        Keras Model instance ready for training or TFLite conversion.
    """
    if ds_layers_config is None:
        ds_layers_config = [
            {"filters": 28, "kernel": (3, 3), "stride": (1, 1)},
            {"filters": 28, "kernel": (3, 3), "stride": (1, 1)},
            {"filters": 28, "kernel": (3, 3), "stride": (1, 1)},
            {"filters": 28, "kernel": (3, 3), "stride": (1, 1)},
        ]

    inputs = layers.Input(shape=input_shape, name="audio_feature_input")

    # 1. Initial 2D Convolution + BatchNorm + ReLU
    x = layers.Conv2D(
        filters=first_conv_filters,
        kernel_size=first_conv_kernel,
        strides=first_conv_stride,
        padding="same",
        use_bias=False,
        name="conv_1",
    )(inputs)
    x = layers.BatchNormalization(name="bn_1")(x)
    x = layers.ReLU(name="relu_1")(x)

    # 2. Depthwise Separable Blocks
    for i, cfg in enumerate(ds_layers_config):
        f = cfg["filters"]
        k = cfg["kernel"]
        s = cfg["stride"]

        # Depthwise Conv (Spatial feature correlation)
        x = layers.DepthwiseConv2D(
            kernel_size=k,
            strides=s,
            padding="same",
            use_bias=False,
            name=f"dw_conv_{i+2}",
        )(x)
        x = layers.BatchNormalization(name=f"bn_dw_{i+2}")(x)
        x = layers.ReLU(name=f"relu_dw_{i+2}")(x)

        # Pointwise Conv (Channel feature mixing)
        x = layers.Conv2D(
            filters=f,
            kernel_size=(1, 1),
            strides=(1, 1),
            padding="same",
            use_bias=False,
            name=f"pw_conv_{i+2}",
        )(x)
        x = layers.BatchNormalization(name=f"bn_pw_{i+2}")(x)
        x = layers.ReLU(name=f"relu_pw_{i+2}")(x)

    # 3. Global Average Pooling + Dropout
    x = layers.GlobalAveragePooling2D(name="global_avg_pool")(x)
    x = layers.Dropout(rate=dropout_rate, name="dropout")(x)

    # 4. Dense Classification Layer with Softmax Output
    outputs = layers.Dense(
        units=num_classes,
        activation="softmax",
        name="output_probabilities"
    )(x)

    model = models.Model(inputs=inputs, outputs=outputs, name="Doora_DSCNN_Tiny")
    return model
