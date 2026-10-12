#!/usr/bin/env python3
"""Validate a Pascal VOC detection dataset before spending hours training on it.

The two mistakes that silently ruin a run are mismatched image/annotation pairs
and boxes that do not describe a traffic light. Both are cheap to catch here and
expensive to notice later, when the model keeps missing red lights.

What it checks:

  * every image has an annotation and every annotation has an image,
  * every `.xml` parses and declares at least one object,
  * each `<name>` is one of the three classes the app knows,
  * each box has a positive area and stays inside the image,
  * the class balance, so a "90% green" dataset cannot slip through.

Exit codes: 0 = usable (warnings allowed), 1 = problems found, 2 = usage error.

Usage:
    python tools/validate_dataset.py --dataset dataset
    python tools/validate_dataset.py --dataset dataset --min-per-class 150
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import xml.etree.ElementTree as ElementTree
from collections import Counter
from dataclasses import dataclass, field
from typing import NamedTuple

IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".bmp")
SPLITS = ("train", "validation")
# Must stay in sync with app/src/main/assets/labels.txt (see docs/MODEL.md).
KNOWN_CLASSES = ("red_light", "yellow_light", "green_light")
# Boxes may touch the border but must not cross it (annotation tools round).
BOX_TOLERANCE_PIXELS = 2.0
# Below this share of the largest train class, the dataset is called unbalanced.
IMBALANCE_RATIO = 0.2
DEFAULT_MIN_PER_CLASS = 100


class Box(NamedTuple):
    name: str
    xmin: float
    ymin: float
    xmax: float
    ymax: float


@dataclass
class SplitReport:
    """Result of checking one split (train or validation)."""

    split: str
    images: list[str] = field(default_factory=list)
    annotations: list[str] = field(default_factory=list)
    boxes: Counter = field(default_factory=Counter)
    problems: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def objects(self) -> int:
        return sum(self.boxes.values())


def normalise_label(raw: str) -> str:
    """`" Red Light "` / `"GREEN-LIGHT"` -> `red_light` / `green_light`."""
    return raw.strip().lower().replace("-", "_").replace(" ", "_")


def parse_annotation(path: pathlib.Path) -> tuple[list[Box], tuple[float, float] | None]:
    """Returns the boxes and the declared `(width, height)`, if the file has one.

    Raises `ElementTree.ParseError` for a malformed file -- the caller decides
    how to report that.
    """
    root = ElementTree.parse(path).getroot()

    size = root.find("size")
    declared = None
    if size is not None:
        width, height = size.findtext("width"), size.findtext("height")
        if width and height:
            declared = (float(width), float(height))

    boxes = []
    for obj in root.findall("object"):
        name = obj.findtext("name") or ""
        bndbox = obj.find("bndbox")
        if bndbox is None:
            boxes.append(Box(normalise_label(name), float("nan"), float("nan"), float("nan"), float("nan")))
            continue
        try:
            coords = [float(bndbox.findtext(key) or "nan") for key in ("xmin", "ymin", "xmax", "ymax")]
        except ValueError:
            coords = [float("nan")] * 4
        boxes.append(Box(normalise_label(name), *coords))
    return boxes, declared


def box_problems(box: Box, declared: tuple[float, float] | None) -> list[str]:
    """Everything wrong with a single box, as human-readable strings."""
    problems = []
    if box.name not in KNOWN_CLASSES:
        problems.append(
            f"unknown class '{box.name}' (expected one of {', '.join(KNOWN_CLASSES)})"
        )
    if any(value != value for value in (box.xmin, box.ymin, box.xmax, box.ymax)):  # NaN
        problems.append(f"{box.name}: coordinates are missing or not numbers")
        return problems
    if box.xmax - box.xmin <= 0 or box.ymax - box.ymin <= 0:
        problems.append(f"{box.name}: box has no area ({box.xmin}, {box.ymin}, {box.xmax}, {box.ymax})")
        return problems
    if declared is not None:
        width, height = declared
        if (
            box.xmin < -BOX_TOLERANCE_PIXELS
            or box.ymin < -BOX_TOLERANCE_PIXELS
            or box.xmax > width + BOX_TOLERANCE_PIXELS
            or box.ymax > height + BOX_TOLERANCE_PIXELS
        ):
            problems.append(
                f"{box.name}: box ({box.xmin}, {box.ymin}, {box.xmax}, {box.ymax}) "
                f"is outside the declared image {int(width)}x{int(height)}"
            )
    return problems


def check_split(split_dir: pathlib.Path, split_name: str) -> SplitReport:
    """Checks one split on disk (missing directories are reported, not raised)."""
    report = SplitReport(split=split_name)
    images_dir = split_dir / "images"
    annotations_dir = split_dir / "annotations"

    for directory in (images_dir, annotations_dir):
        if not directory.is_dir():
            report.problems.append(f"missing directory: {directory}")
    if report.problems:
        return report

    report.images = sorted(
        path.name for path in images_dir.iterdir() if path.suffix.lower() in IMAGE_SUFFIXES
    )
    report.annotations = sorted(path.name for path in annotations_dir.iterdir() if path.suffix == ".xml")

    image_stems = {pathlib.Path(name).stem for name in report.images}
    annotation_stems = {pathlib.Path(name).stem for name in report.annotations}
    for stem in sorted(image_stems - annotation_stems):
        report.problems.append(f"{split_name}/images/{stem} has no matching annotation")
    for stem in sorted(annotation_stems - image_stems):
        report.problems.append(f"{split_name}/annotations/{stem}.xml has no matching image")
    if not report.images:
        report.problems.append(f"{split_name} has no images (suffixes: {', '.join(IMAGE_SUFFIXES)})")

    for name in sorted(annotation_stems & image_stems):
        path = annotations_dir / f"{name}.xml"
        try:
            boxes, declared = parse_annotation(path)
        except ElementTree.ParseError as error:
            report.problems.append(f"{path}: cannot parse XML ({error})")
            continue
        if not boxes:
            report.warnings.append(f"{path}: no <object> in the file")
        for box in boxes:
            report.boxes[box.name] += 1
            for problem in box_problems(box, declared):
                report.problems.append(f"{path}: {problem}")

    return report


def balance_warnings(train_boxes: Counter, min_per_class: int) -> list[str]:
    """Warnings about class counts, so a skewed dataset does not surprise anyone."""
    warnings = []
    for name in KNOWN_CLASSES:
        count = train_boxes.get(name, 0)
        if count == 0:
            warnings.append(f"train has no '{name}' objects; the model cannot learn that class")
        elif count < min_per_class:
            warnings.append(f"train has only {count} '{name}' objects (recommended: >= {min_per_class})")

    present = {name: train_boxes.get(name, 0) for name in KNOWN_CLASSES if train_boxes.get(name, 0)}
    if present:
        largest = max(present.values())
        for name, count in sorted(present.items()):
            if count < largest * IMBALANCE_RATIO:
                warnings.append(
                    f"'{name}' is {count / largest:.0%} of the largest class ('{max(present, key=present.get)}'): "
                    "collect more or oversample it, otherwise the alarm will be biased"
                )
    return warnings


def print_report(reports: list[SplitReport], min_per_class: int) -> None:
    for report in reports:
        print(f"\n{report.split}")
        print(f"  images: {len(report.images)}  annotations: {len(report.annotations)}  objects: {report.objects}")
        for name in KNOWN_CLASSES:
            count = report.boxes.get(name, 0)
            print(f"    {name:<13} {count:>6}")
        unknown = sorted(name for name in report.boxes if name not in KNOWN_CLASSES)
        for name in unknown:
            print(f"    {name:<13} {report.boxes[name]:>6}   <- not a class the app knows")
        if report.warnings:
            print("  warnings:")
            for warning in report.warnings:
                print(f"    - {warning}")
        if report.problems:
            print("  problems:")
            for problem in report.problems[:20]:
                print(f"    - {problem}")
            if len(report.problems) > 20:
                print(f"    ... and {len(report.problems) - 20} more")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=pathlib.Path, help="dataset root (train/ and validation/)")
    parser.add_argument(
        "--min-per-class",
        type=int,
        default=DEFAULT_MIN_PER_CLASS,
        help=f"warn when a train class has fewer objects than this (default: {DEFAULT_MIN_PER_CLASS})",
    )
    args = parser.parse_args()

    if not args.dataset.is_dir():
        print(f"Dataset not found: {args.dataset}", file=sys.stderr)
        return 1

    reports = [check_split(args.dataset / split, split) for split in SPLITS]
    train_report = reports[0]
    train_report.warnings.extend(balance_warnings(train_report.boxes, args.min_per_class))

    print(f"Dataset: {args.dataset}")
    print_report(reports, args.min_per_class)

    problems = sum(len(report.problems) for report in reports)
    warnings = sum(len(report.warnings) for report in reports)
    print(f"\n{problems} problem(s), {warnings} warning(s)")
    if problems:
        print("Fix the problems above before training -- they are what make a model miss lights.")
        return 1
    print("Dataset looks usable. Next: docs/TRAINING.md step 4 (train + export).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
