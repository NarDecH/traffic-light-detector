#!/usr/bin/env python3
"""Unit tests for `tools/make_dummy_model.py`.

Standard library only: the model-building function needs TensorFlow, but the
command line surface (defaults, flags, help) must work without it -- that is
what users hit first, so it is what these tests pin down.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import unittest
from unittest import mock

TOOLS_DIR = pathlib.Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import make_dummy_model  # noqa: E402  (imported after sys.path is prepared)


class ArgumentTests(unittest.TestCase):
    def parse(self, *arguments: str):
        with mock.patch.object(sys, "argv", ["make_dummy_model.py", *arguments]):
            return make_dummy_model.parse_args()

    def test_defaults_write_into_the_app_assets_directory(self):
        args = self.parse()
        self.assertEqual(args.output, pathlib.Path("app/src/main/assets/traffic_light.tflite"))
        self.assertEqual(args.size, 320)

    def test_flags_override_the_defaults(self):
        args = self.parse("--output", "somewhere/else.tflite", "--size", "512")
        self.assertEqual(args.output, pathlib.Path("somewhere/else.tflite"))
        self.assertEqual(args.size, 512)

    def test_size_must_be_an_integer(self):
        with self.assertRaises(SystemExit):
            self.parse("--size", "large")


class CommandLineTests(unittest.TestCase):
    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(TOOLS_DIR / "make_dummy_model.py"), *arguments],
            capture_output=True,
            text=True,
        )

    def test_help_works_without_tensorflow_installed(self):
        result = self.run_cli("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("--output", result.stdout)
        self.assertIn("--size", result.stdout)

    def test_help_mentions_the_tensorflow_requirement(self):
        result = self.run_cli("--help")
        self.assertIn("tensorflow", result.stdout.lower())


class ContractTests(unittest.TestCase):
    def test_the_box_count_is_the_documented_default(self):
        # The detection contract in docs/MODEL.md uses 10 boxes for the dummy
        # model; the app reads the count from the tensor shape, never from here.
        self.assertEqual(make_dummy_model.NUM_BOXES, 10)


if __name__ == "__main__":
    unittest.main()
