#!/usr/bin/env python3
"""Inspects and dumps detailed metadata from any compiled TFLite FlatBuffer binary."""

import os
import sys
import argparse
import json
import tensorflow as tf


def inspect_tflite_model(model_path: str):
    """Parses input/output tensor shapes, quantization parameters, and operators."""
    if not os.path.exists(model_path):
        raise FileNotFoundError(f"TFLite model file not found: {model_path}")

    file_size_bytes = os.path.getsize(model_path)
    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    tensor_details = interpreter.get_tensor_details()

    report = {
        "file_path": os.path.abspath(model_path),
        "file_size_bytes": file_size_bytes,
        "file_size_kb": file_size_bytes / 1024.0,
        "total_tensors": len(tensor_details),
        "inputs": [
            {
                "index": inp["index"],
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
                "index": out["index"],
                "name": out["name"],
                "shape": out["shape"].tolist(),
                "dtype": str(out["dtype"].__name__),
                "quantization_scale": float(out["quantization_parameters"]["scales"][0]) if len(out["quantization_parameters"]["scales"]) > 0 else 0.0,
                "quantization_zero_point": int(out["quantization_parameters"]["zero_points"][0]) if len(out["quantization_parameters"]["zero_points"]) > 0 else 0,
            }
            for out in output_details
        ],
    }

    print("\n" + "=" * 60)
    print("TFLITE MODEL INSPECTION REPORT")
    print("=" * 60)
    print(f"File Path:       {report['file_path']}")
    print(f"File Size:       {report['file_size_kb']:.2f} KB ({report['file_size_bytes']} bytes)")
    print(f"Total Tensors:   {report['total_tensors']}")
    print("\nINPUT TENSORS:")
    for inp in report["inputs"]:
        print(f" - [{inp['index']}] '{inp['name']}' Shape: {inp['shape']} Type: {inp['dtype']} Scale: {inp['quantization_scale']} ZeroPoint: {inp['quantization_zero_point']}")

    print("\nOUTPUT TENSORS:")
    for out in report["outputs"]:
        print(f" - [{out['index']}] '{out['name']}' Shape: {out['shape']} Type: {out['dtype']} Scale: {out['quantization_scale']} ZeroPoint: {out['quantization_zero_point']}")
    print("=" * 60)

    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Inspect TFLite model FlatBuffer details.")
    parser.add_argument("model_path", type=str, help="Path to .tflite model file")
    args = parser.parse_args()

    inspect_tflite_model(args.model_path)
