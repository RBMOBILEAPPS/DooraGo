"""Post-Training Int8 Quantization and TFLite Model Export."""

import os
import tensorflow as tf
import numpy as np
from typing import Generator, Callable, Dict, Any, List


def export_tflite_full_int8(
    keras_model: tf.keras.Model,
    representative_dataset_generator: Callable[[], Generator[List[np.ndarray], None, None]],
    output_tflite_path: str,
    inference_input_type: str = "float32",
    inference_output_type: str = "float32",
) -> Dict[str, Any]:
    """Converts a trained Keras model to a fully integer quantized (Int8) TFLite FlatBuffer.

    Args:
        keras_model: Trained Keras model instance.
        representative_dataset_generator: Yields representative float32 input batches for calibration.
        output_tflite_path: Target destination for the `.tflite` file.
        inference_input_type: Interface input format ('float32' or 'int8').
        inference_output_type: Interface output format ('float32' or 'int8').

    Returns:
        Metadata dictionary describing model byte size, input/output tensors, and quantization scales.
    """
    os.makedirs(os.path.dirname(output_tflite_path), exist_ok=True)

    converter = tf.lite.TFLiteConverter.from_keras_model(keras_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset_generator

    # Enforce integer quantization for all internal operations
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]

    if inference_input_type.lower() == "int8":
        converter.inference_input_type = tf.int8
    else:
        converter.inference_input_type = tf.float32

    if inference_output_type.lower() == "int8":
        converter.inference_output_type = tf.int8
    else:
        converter.inference_output_type = tf.float32

    # Execute conversion
    tflite_quant_model = converter.convert()

    # Save to disk
    with open(output_tflite_path, "wb") as f:
        f.write(tflite_quant_model)

    file_size_bytes = os.path.getsize(output_tflite_path)

    # Inspect generated TFLite interpreter
    interpreter = tf.lite.Interpreter(model_path=output_tflite_path)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    return {
        "output_path": output_tflite_path,
        "file_size_bytes": file_size_bytes,
        "file_size_kb": file_size_bytes / 1024.0,
        "inputs": [
            {
                "name": inp["name"],
                "shape": inp["shape"].tolist(),
                "dtype": str(inp["dtype"].__name__),
                "quantization_scale": float(inp["quantization_parameters"]["scales"][0]) if len(inp["quantization_parameters"]["scales"]) > 0 else 0.0,
                "quantization_zero_point": int(inp["quantization_parameters"]["zero_points"][0]) if len(inp["quantization_parameters"]["zero_points"]) > 0 else 0,
            }
            for inp in input_details
        ],
        "outputs": [
            {
                "name": out["name"],
                "shape": out["shape"].tolist(),
                "dtype": str(out["dtype"].__name__),
                "quantization_scale": float(out["quantization_parameters"]["scales"][0]) if len(out["quantization_parameters"]["scales"]) > 0 else 0.0,
                "quantization_zero_point": int(out["quantization_parameters"]["zero_points"][0]) if len(out["quantization_parameters"]["zero_points"]) > 0 else 0,
            }
            for out in output_details
        ],
    }
