#!/usr/bin/env python3
"""Print the I/O contract of a `.tflite` model.

Use it before dropping a model into `app/src/main/assets/` to check that

  * the input is NHWC `[1, H, W, 3]`,
  * there is a boxes tensor `[1, N, 4]`, a classes tensor, a scores tensor and
    (optionally) a `num_detections` scalar,
  * whether the tensors are quantized.

Exit codes: 0 = compatible, 1 = missing tooling, 2 = incompatible model.

Usage:
    python tools/inspect_model.py path/to/model.tflite
"""

from __future__ import annotations

import argparse
import pathlib


def _to_list(value) -> list:
    """Flattens numpy arrays, nested tuples and scalars into a plain list."""
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        flattened = []
        for item in value:
            flattened.extend(_to_list(item))
        return flattened
    if hasattr(value, "tolist"):  # numpy array or scalar
        converted = value.tolist()
        return _to_list(converted) if isinstance(converted, (list, tuple)) else [converted]
    return [value]


def quant_params(tensor: dict) -> tuple[list, list]:
    """Returns `(scales, zero_points)` for a detail dict, across TF versions.

    `get_input_details()` / `get_output_details()` expose quantization either as
    `quantization_parameters` (dict, TF >= 2.6) or as the legacy
    `quantization` tuple.
    """
    parameters = tensor.get("quantization_parameters")
    if parameters:
        scales = _to_list(parameters.get("scales"))
        if scales:
            return scales, _to_list(parameters.get("zero_points"))

    legacy = tensor.get("quantization")
    if isinstance(legacy, dict):
        return _to_list(legacy.get("scale")), _to_list(legacy.get("zero_point"))
    if isinstance(legacy, (list, tuple)) and len(legacy) == 2:
        return _to_list(legacy[0]), _to_list(legacy[1])
    return [], []


def describe(tensor: dict) -> str:
    dtype = tensor.get("dtype")
    dtype_name = getattr(dtype, "__name__", str(dtype))
    description = f"{tensor.get('name')}  shape={list(tensor.get('shape', []))}  dtype={dtype_name}"

    scales, zero_points = quant_params(tensor)
    if dtype_name not in ("float32", "float16") or any(scale != 0.0 for scale in scales):
        description += f"  quant: scale={scales} zero_point={zero_points}"
    return description


def check_compatibility(inputs: list[dict], outputs: list[dict]) -> list[str]:
    """Returns the list of problems that would stop the Android app from loading."""
    problems: list[str] = []

    if not inputs:
        problems.append("no input tensors found")
    else:
        shape = list(inputs[0].get("shape", []))
        if len(shape) != 4 or shape[3] != 3:
            problems.append(f"expected an NHWC [1, H, W, 3] input, found {shape}")

    if len(outputs) < 3:
        problems.append(f"expected >= 3 output tensors (boxes/classes/scores), found {len(outputs)}")

    has_boxes = any(
        len(list(tensor.get("shape", []))) == 3 and list(tensor["shape"])[2] == 4
        for tensor in outputs
    )
    if not has_boxes:
        problems.append("no [1, N, 4] bounding-box output tensor found")

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("model", type=pathlib.Path)
    args = parser.parse_args()

    try:
        import tensorflow as tf
    except ImportError:
        print("TensorFlow is required: pip install tensorflow")
        return 1

    if not args.model.is_file():
        print(f"Model not found: {args.model}")
        return 1

    interpreter = tf.lite.Interpreter(model_path=str(args.model))
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()

    print(f"Model: {args.model} ({args.model.stat().st_size / 1e6:.2f} MB)")
    print("\nInputs:")
    for tensor in inputs:
        print("  " + describe(tensor))

    print("\nOutputs:")
    for tensor in outputs:
        print("  " + describe(tensor))

    problems = check_compatibility(inputs, outputs)
    print()
    if problems:
        print("INCOMPATIBLE with the app's TrafficLightDetector:")
        for problem in problems:
            print(f"  - {problem}")
        return 2

    print("Compatible: the app can load this model.")
    print("Remember that labels.txt must list the labels in the model's class index order.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
