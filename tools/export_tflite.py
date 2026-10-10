#!/usr/bin/env python3
"""Train a 3-class traffic-light detector and export it as `traffic_light.tflite`.

Classes (keep this order, it is what `app/src/main/assets/labels.txt` declares):

    0 red_light
    1 yellow_light
    2 green_light

The exported model uses the standard TFLite object-detection signature, i.e. the
four `TFLite_Detection_PostProcess` outputs the Android app expects:

    locations [1, N, 4]   ymin, xmin, ymax, xmax (normalised 0..1)
    classes   [1, N]      class index
    scores    [1, N]      confidence 0..1
    num       [1]         valid detections

Dataset layout (Pascal VOC XML):

    dataset/
      train/images/*.jpg
      train/annotations/*.xml
      validation/images/*.jpg
      validation/annotations/*.xml

Usage:

    pip install -r tools/requirements.txt
    python tools/export_tflite.py --dataset dataset --output app/src/main/assets

Note: `tflite-model-maker` needs Python 3.9-3.11 and TensorFlow <= 2.15. If you
are on a newer Python, train with the TF Model Garden instead and export with
`converter.optimizations = [tf.lite.Optimize.DEFAULT]`; the app only cares about
the I/O contract above.
"""

from __future__ import annotations

import argparse
import pathlib
import shutil
import sys

CLASS_NAMES = ["red_light", "yellow_light", "green_light"]
# Pascal VOC label ids used in the annotation files. Adjust if your labels differ.
VOC_LABEL_MAP = {1: "red_light", 2: "yellow_light", 3: "green_light"}

MODELS = {
    "efficientdet-lite0": "efficientdet_lite0",
    "efficientdet-lite1": "efficientdet_lite1",
    "efficientdet-lite2": "efficientdet_lite2",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=pathlib.Path, help="dataset root (see docstring)")
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("app/src/main/assets"))
    parser.add_argument("--model", default="efficientdet-lite0", choices=sorted(MODELS))
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--tflite-filename", default="traffic_light.tflite")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    try:
        from tflite_model_maker import model_spec, object_detector
        from tflite_model_maker.config import ExportFormat
    except ImportError:
        print(
            "tflite-model-maker is missing. Install it first:\n"
            "  pip install -r tools/requirements.txt",
            file=sys.stderr,
        )
        return 1

    train_dir = args.dataset / "train"
    validation_dir = args.dataset / "validation"
    for directory in (train_dir, validation_dir):
        if not directory.is_dir():
            print(f"Missing dataset directory: {directory}", file=sys.stderr)
            return 1

    print(f"Loading dataset from {args.dataset} ...")
    train_data = object_detector.DataLoader.from_pascal_voc(
        images_dir=str(train_dir / "images"),
        annotations_dir=str(train_dir / "annotations"),
        label_map=VOC_LABEL_MAP,
    )
    validation_data = object_detector.DataLoader.from_pascal_voc(
        images_dir=str(validation_dir / "images"),
        annotations_dir=str(validation_dir / "annotations"),
        label_map=VOC_LABEL_MAP,
    )

    spec = model_spec.get(MODELS[args.model])
    spec.config.epochs = args.epochs
    spec.config.batch_size = args.batch_size

    print(f"Training {args.model} for {args.epochs} epochs ...")
    model = object_detector.create(
        train_data,
        model_spec=spec,
        batch_size=args.batch_size,
        train_whole_model=True,
        validation_data=validation_data,
        epochs=args.epochs,
    )

    print("Evaluating ...")
    print(model.evaluate(validation_data))

    export_dir = pathlib.Path("build/tflite_export")
    export_dir.mkdir(parents=True, exist_ok=True)
    model.export(
        export_dir=str(export_dir),
        tflite_filename=args.tflite_filename,
        export_format=[ExportFormat.TFLITE, ExportFormat.LABEL],
    )

    args.output.mkdir(parents=True, exist_ok=True)
    tflite_path = export_dir / args.tflite_filename
    target_tflite = args.output / args.tflite_filename
    shutil.copyfile(tflite_path, target_tflite)
    print(f"Model written to {target_tflite}")

    # The app reads a plain one-label-per-line file (see README "Model contract").
    # Writing it from CLASS_NAMES keeps it in sync with the dataset label map.
    labels_path = args.output / "labels.txt"
    labels_path.write_text("\n".join(CLASS_NAMES) + "\n", encoding="utf-8")
    print(f"Labels written to {labels_path}")
    print("\nVerify the exported model with:\n  python tools/inspect_model.py " + str(target_tflite))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
