#!/usr/bin/env python3
"""Unit tests for `tools/export_tflite.py`.

Standard library only -- the dataset gate runs *before* TensorFlow /
`tflite-model-maker` is imported, so these pass on any machine (and in CI).

The training itself needs `tflite-model-maker` and is not exercised here; the
gate is what protects a long run from a broken dataset, so that is what we pin.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile
import unittest

TOOLS_DIR = pathlib.Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import export_tflite  # noqa: E402  (imported after sys.path is prepared)

ANNOTATION = """<annotation>
  <size><width>640</width><height>480</height></size>
  <object><name>green_light</name><bndbox><xmin>10</xmin><ymin>10</ymin><xmax>40</xmax><ymax>40</ymax></bndbox></object>
</annotation>
"""


class DatasetBuilder:
    """Builds the train/validation tree `export_tflite.py` expects."""

    def __init__(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name) / "dataset"
        for split in ("train", "validation"):
            (self.root / split / "images").mkdir(parents=True)
            (self.root / split / "annotations").mkdir(parents=True)

    def add(self, split, name, image=True, annotation=True, body=ANNOTATION):
        if image:
            (self.root / split / "images" / f"{name}.jpg").write_bytes(b"\xff\xd8\xff")
        if annotation:
            (self.root / split / "annotations" / f"{name}.xml").write_text(body, encoding="utf-8")
        return self

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self._tmp.cleanup()


class DatasetGateTests(unittest.TestCase):
    def test_a_usable_dataset_has_no_problems(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001")
            builder.add("validation", "002")
            self.assertEqual(export_tflite.dataset_problems(builder.root), [])

    def test_an_image_without_annotation_is_caught(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", annotation=False)
            builder.add("validation", "002")
            problems = export_tflite.dataset_problems(builder.root)
        self.assertTrue(any("no matching annotation" in problem for problem in problems))

    def test_a_bad_box_in_validation_is_caught(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001")
            builder.add(
                "validation",
                "002",
                body=ANNOTATION.replace(
                    "<xmax>40</xmax><ymax>40</ymax>",
                    "<xmax>900</xmax><ymax>900</ymax>",
                ),
            )
            problems = export_tflite.dataset_problems(builder.root)
        self.assertTrue(any("outside the declared image" in problem for problem in problems))


class CommandLineGateTests(unittest.TestCase):
    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(TOOLS_DIR / "export_tflite.py"), *arguments],
            capture_output=True,
            text=True,
        )

    def test_missing_argument_is_a_usage_error(self):
        self.assertEqual(self.run_cli().returncode, 2)

    def test_missing_dataset_directory_exits_1(self):
        result = self.run_cli("--dataset", "definitely-not-here")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Missing dataset directory", result.stderr)

    def test_a_broken_dataset_exits_before_touching_tensorflow(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", annotation=False)
            builder.add("validation", "002")
            result = self.run_cli("--dataset", str(builder.root))
        self.assertEqual(result.returncode, 1)
        self.assertIn("problem(s); not training on it", result.stderr)
        self.assertIn("validate_dataset.py", result.stderr)
        # The gate runs before the import, so a broken dataset is reported even
        # on a machine where `tflite-model-maker` is not installed at all.
        self.assertNotIn("tflite-model-maker", result.stderr)


if __name__ == "__main__":
    unittest.main()
