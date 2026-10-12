#!/usr/bin/env python3
"""Unit tests for `tools/validate_dataset.py`.

Standard library only -- the validator is pure Python, so these run in CI and on
any machine without installing anything.
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

import validate_dataset  # noqa: E402  (imported after sys.path is prepared)


def annotation_document(objects, size=(640, 480)) -> str:
    """Builds a minimal Pascal VOC document with one object per tuple."""
    objects_xml = "".join(
        f"""
  <object>
    <name>{name}</name>
    <bndbox>
      <xmin>{xmin}</xmin>
      <ymin>{ymin}</ymin>
      <xmax>{xmax}</xmax>
      <ymax>{ymax}</ymax>
    </bndbox>
  </object>"""
        for name, xmin, ymin, xmax, ymax in objects
    )
    return f"""<annotation>
  <filename>image.jpg</filename>
  <size><width>{size[0]}</width><height>{size[1]}</height><depth>3</depth></size>{objects_xml}
</annotation>
"""


class DatasetBuilder:
    """Builds a dataset tree in a temporary directory."""

    def __init__(self, with_splits: bool = True):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name) / "dataset"
        if with_splits:
            for split in ("train", "validation"):
                (self.root / split / "images").mkdir(parents=True)
                (self.root / split / "annotations").mkdir(parents=True)

    def add(self, split, name, objects, size=(640, 480), image=True, xml=True, raw_xml=None):
        if image:
            (self.root / split / "images" / f"{name}.jpg").write_bytes(b"\xff\xd8\xff")
        if xml:
            body = annotation_document(objects, size) if raw_xml is None else raw_xml
            (self.root / split / "annotations" / f"{name}.xml").write_text(body, encoding="utf-8")
        return self

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self._tmp.cleanup()


GREEN = ("green_light", 10, 10, 40, 40)
RED = ("red_light", 100, 100, 130, 130)
YELLOW = ("yellow_light", 200, 200, 230, 230)


class CheckSplitTests(unittest.TestCase):
    def test_a_well_formed_split_has_no_problems(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", [GREEN, RED])
            builder.add("train", "002", [YELLOW])
            report = validate_dataset.check_split(builder.root / "train", "train")
        self.assertEqual(report.problems, [])
        self.assertEqual(len(report.images), 2)
        self.assertEqual(report.objects, 3)
        self.assertEqual(report.boxes["green_light"], 1)

    def test_image_without_annotation_is_a_problem(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", [GREEN], xml=False)
            report = validate_dataset.check_split(builder.root / "train", "train")
        self.assertTrue(any("no matching annotation" in problem for problem in report.problems))

    def test_annotation_without_image_is_a_problem(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", [GREEN], image=False)
            report = validate_dataset.check_split(builder.root / "train", "train")
        self.assertTrue(any("no matching image" in problem for problem in report.problems))

    def test_missing_directories_are_reported(self):
        with DatasetBuilder(with_splits=False) as builder:
            builder.root.mkdir(parents=True)
        report = validate_dataset.check_split(builder.root / "train", "train")
        self.assertEqual(len(report.problems), 2)
        self.assertTrue(all("missing directory" in problem for problem in report.problems))

    def test_malformed_xml_is_reported_not_raised(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", [], raw_xml="<annotation><object></annotation>")
            report = validate_dataset.check_split(builder.root / "train", "train")
        self.assertTrue(any("cannot parse XML" in problem for problem in report.problems))

    def test_annotation_without_objects_is_only_a_warning(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", [])
            report = validate_dataset.check_split(builder.root / "train", "train")
        self.assertEqual(report.problems, [])
        self.assertTrue(any("no <object>" in warning for warning in report.warnings))

    def test_empty_images_directory_is_a_problem(self):
        with DatasetBuilder() as builder:
            report = validate_dataset.check_split(builder.root / "train", "train")
        self.assertTrue(any("has no images" in problem for problem in report.problems))


class BoxTests(unittest.TestCase):
    def problems(self, box, size=(640, 480)):
        return validate_dataset.box_problems(box, size)

    def test_a_normal_box_has_no_problems(self):
        self.assertEqual(self.problems(validate_dataset.Box(*GREEN)), [])

    def test_box_outside_the_image_is_a_problem(self):
        box = validate_dataset.Box("green_light", 600.0, 10.0, 700.0, 60.0)
        self.assertTrue(any("outside the declared image" in problem for problem in self.problems(box)))

    def test_box_touching_the_border_is_allowed(self):
        box = validate_dataset.Box("green_light", 0.0, 0.0, 640.0, 480.0)
        self.assertEqual(self.problems(box), [])

    def test_box_without_area_is_a_problem(self):
        box = validate_dataset.Box("red_light", 100.0, 100.0, 100.0, 130.0)
        self.assertTrue(any("no area" in problem for problem in self.problems(box)))

    def test_reversed_coordinates_are_a_problem(self):
        box = validate_dataset.Box("red_light", 130.0, 130.0, 100.0, 100.0)
        self.assertTrue(any("no area" in problem for problem in self.problems(box)))

    def test_missing_coordinates_are_a_problem(self):
        box = validate_dataset.Box("red_light", float("nan"), float("nan"), float("nan"), float("nan"))
        self.assertTrue(any("not numbers" in problem for problem in self.problems(box)))

    def test_unknown_class_is_a_problem(self):
        box = validate_dataset.Box("stop_sign", 10.0, 10.0, 40.0, 40.0)
        self.assertTrue(any("unknown class" in problem for problem in self.problems(box)))

    def test_label_variants_are_normalised(self):
        self.assertEqual(validate_dataset.normalise_label(" Red Light "), "red_light")
        self.assertEqual(validate_dataset.normalise_label("GREEN-LIGHT"), "green_light")
        with DatasetBuilder() as builder:
            builder.add("train", "001", [(" Red Light ", 10, 10, 40, 40), ("GREEN-LIGHT", 50, 50, 80, 80)])
            report = validate_dataset.check_split(builder.root / "train", "train")
        self.assertEqual(report.problems, [])
        self.assertEqual(report.boxes["red_light"], 1)
        self.assertEqual(report.boxes["green_light"], 1)

    def test_annotation_without_declared_size_skips_the_bounds_check(self):
        box = validate_dataset.Box("green_light", 5000.0, 5000.0, 5100.0, 5100.0)
        self.assertEqual(validate_dataset.box_problems(box, None), [])


class BalanceTests(unittest.TestCase):
    def test_a_missing_class_is_flagged(self):
        warnings = validate_dataset.balance_warnings({"red_light": 200, "yellow_light": 200}, 50)
        self.assertTrue(any("cannot learn that class" in warning for warning in warnings))

    def test_a_class_below_the_recommended_count_is_flagged(self):
        warnings = validate_dataset.balance_warnings({"red_light": 200, "yellow_light": 200, "green_light": 20}, 100)
        self.assertTrue(any("only 20" in warning for warning in warnings))

    def test_an_unbalanced_class_is_flagged(self):
        warnings = validate_dataset.balance_warnings({"red_light": 1000, "yellow_light": 500, "green_light": 50}, 10)
        self.assertTrue(any("of the largest class" in warning for warning in warnings))

    def test_a_healthy_distribution_is_not_flagged(self):
        warnings = validate_dataset.balance_warnings({"red_light": 200, "yellow_light": 180, "green_light": 190}, 100)
        self.assertEqual(warnings, [])


class CommandLineTests(unittest.TestCase):
    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(TOOLS_DIR / "validate_dataset.py"), *arguments],
            capture_output=True,
            text=True,
        )

    def test_missing_argument_is_a_usage_error(self):
        self.assertEqual(self.run_cli().returncode, 2)

    def test_dataset_that_does_not_exist_exits_1(self):
        result = self.run_cli("--dataset", "definitely-not-here")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Dataset not found", result.stderr)

    def test_a_usable_dataset_exits_0_and_prints_counts(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", [GREEN, RED, YELLOW])
            builder.add("validation", "002", [GREEN])
            result = self.run_cli("--dataset", str(builder.root), "--min-per-class", "1")
        self.assertEqual(result.returncode, 0)
        self.assertIn("Dataset looks usable", result.stdout)
        self.assertIn("green_light", result.stdout)
        self.assertIn("objects: 3", result.stdout)

    def test_a_broken_dataset_exits_1_and_lists_problems(self):
        with DatasetBuilder() as builder:
            builder.add("train", "001", [GREEN], xml=False)
            builder.add("validation", "002", [GREEN])
            result = self.run_cli("--dataset", str(builder.root), "--min-per-class", "1")
        self.assertEqual(result.returncode, 1)
        self.assertIn("problem(s)", result.stdout)
        self.assertIn("no matching annotation", result.stdout)


if __name__ == "__main__":
    unittest.main()
