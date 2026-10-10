#!/usr/bin/env python3
"""Build a throwaway `.tflite` with the detection output contract, no training.

Why: it lets you smoke test the whole Android pipeline (CameraX -> analyzer ->
detector -> overlay -> alarm) before a real model exists. The predictions are
meaningless (they are just dense layers over a global average pool), but the
shapes, output order, quantization paths and post-processing are exercised.

Usage:
    pip install "tensorflow>=2.15"
    python tools/make_dummy_model.py --output app/src/main/assets/traffic_light.tflite
"""

from __future__ import annotations

import argparse
import pathlib

NUM_BOXES = 10


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("app/src/main/assets/traffic_light.tflite"))
    parser.add_argument("--size", type=int, default=320, help="square input size")
    return parser.parse_args()


def build_model(tf, size: int):
    inputs = tf.keras.Input(shape=(size, size, 3), dtype=tf.float32, name="image")
    features = tf.keras.layers.Conv2D(8, 3, strides=2, padding="same", activation="relu")(inputs)
    features = tf.keras.layers.GlobalAveragePooling2D()(features)

    # Order matters: locations, classes, scores, num_detections -- the order the
    # app falls back to when a graph has no tensor names (TFLite strips them).
    boxes = tf.keras.layers.Reshape((NUM_BOXES, 4), name="detection_boxes")(
        tf.keras.layers.Dense(NUM_BOXES * 4, name="boxes_raw")(features)
    )
    classes = tf.keras.layers.Lambda(
        lambda t: tf.cast(tf.round(t), tf.float32), name="detection_classes"
    )(tf.keras.layers.Dense(NUM_BOXES, name="classes_raw")(features))
    scores = tf.keras.layers.Activation("sigmoid", name="detection_scores")(
        tf.keras.layers.Dense(NUM_BOXES, name="scores_raw")(features)
    )
    num = tf.keras.layers.Lambda(lambda t: tf.zeros_like(t[:, :1]) + NUM_BOXES, name="num_detections")(
        features
    )
    return tf.keras.Model(inputs=inputs, outputs=[boxes, classes, scores, num])


def main() -> int:
    args = parse_args()

    try:
        import tensorflow as tf
    except ImportError:
        print("TensorFlow is required: pip install 'tensorflow>=2.15'")
        return 1

    model = build_model(tf, args.size)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(tflite_model)

    interpreter = tf.lite.Interpreter(model_content=tflite_model)
    interpreter.allocate_tensors()
    print(f"Wrote {args.output} ({len(tflite_model) / 1e6:.2f} MB)")
    print("Inputs:")
    for tensor in interpreter.get_input_details():
        print(f"  {tensor['name']} {list(tensor['shape'])} {tensor['dtype'].__name__}")
    print("Outputs (detection contract order):")
    for tensor in interpreter.get_output_details():
        print(f"  {tensor['name']} {list(tensor['shape'])} {tensor['dtype'].__name__}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
