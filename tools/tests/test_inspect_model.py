#!/usr/bin/env python3
"""Unit tests for `tools/inspect_model.py`.

Standard library only -- these run in CI without TensorFlow, because the parts
worth testing (contract verdict, quantization parsing, formatting) are pure
Python. The TensorFlow-dependent paths are covered by
`test_model_contract.py`, which skips when TensorFlow is absent.

Run from the repository root:

    python -m unittest discover -s tools/tests -v
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import unittest

TOOLS_DIR = pathlib.Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import inspect_model  # noqa: E402  (imported after sys.path is prepared)

# ---------------------------------------------------------------- fixtures ---

INPUTS = [{"name": "image", "shape": [1, 320, 320, 3], "dtype": "float32"}]

OUTPUTS = [
    {"name": "detection_boxes", "shape": [1, 10, 4], "dtype": "float32"},
    {"name": "detection_classes", "shape": [1, 10], "dtype": "float32"},
    {"name": "detection_scores", "shape": [1, 10], "dtype": "float32"},
    {"name": "num_detections", "shape": [1], "dtype": "float32"},
]


class FakeArray:
    """Stands in for a numpy array: the script only ever calls `tolist()`."""

    def __init__(self, value):
        self._value = value

    def tolist(self):
        return self._value


def dtype_named(name: str):
    """Builds a stand-in dtype whose `__name__` matches what TensorFlow reports."""
    return type(name, (), {})


# ------------------------------------------------------------------ helpers ---


class FlattenTests(unittest.TestCase):
    def test_none_becomes_an_empty_list(self):
        self.assertEqual(inspect_model._to_list(None), [])

    def test_nested_lists_and_tuples_are_flattened(self):
        self.assertEqual(inspect_model._to_list([1, [2, 3], (4,)]), [1, 2, 3, 4])

    def test_array_like_scalar_is_wrapped_in_a_list(self):
        self.assertEqual(inspect_model._to_list(FakeArray(0.5)), [0.5])

    def test_array_like_list_is_flattened(self):
        self.assertEqual(inspect_model._to_list(FakeArray([0.1, 0.2])), [0.1, 0.2])


class QuantizationParamsTests(unittest.TestCase):
    def test_modern_quantization_parameters_are_read(self):
        tensor = {"quantization_parameters": {"scales": [0.0078], "zero_points": [0]}}
        self.assertEqual(inspect_model.quant_params(tensor), ([0.0078], [0]))

    def test_empty_modern_parameters_fall_back_to_the_legacy_field(self):
        tensor = {
            "quantization_parameters": {"scales": [], "zero_points": []},
            "quantization": {"scale": [0.25], "zero_point": [128]},
        }
        self.assertEqual(inspect_model.quant_params(tensor), ([0.25], [128]))

    def test_legacy_dict_is_read(self):
        tensor = {"quantization": {"scale": [0.5], "zero_point": [-1]}}
        self.assertEqual(inspect_model.quant_params(tensor), ([0.5], [-1]))

    def test_legacy_tuple_is_read(self):
        tensor = {"quantization": ([0.25], [128])}
        self.assertEqual(inspect_model.quant_params(tensor), ([0.25], [128]))

    def test_tensor_without_quantization_reports_nothing(self):
        self.assertEqual(inspect_model.quant_params({}), ([], []))


class CompatibilityTests(unittest.TestCase):
    def test_the_expected_contract_has_no_problems(self):
        self.assertEqual(inspect_model.check_compatibility(INPUTS, OUTPUTS), [])

    def test_quantized_tensors_are_still_compatible(self):
        int8 = dtype_named("int8")
        inputs = [{"name": "image", "shape": [1, 300, 300, 3], "dtype": int8}]
        outputs = [
            {"name": "boxes", "shape": [1, 25, 4], "dtype": "float32"},
            {"name": "classes", "shape": [1, 25], "dtype": "float32"},
            {"name": "scores", "shape": [1, 25], "dtype": "float32"},
        ]
        self.assertEqual(inspect_model.check_compatibility(inputs, outputs), [])

    def test_missing_input_is_reported(self):
        problems = inspect_model.check_compatibility([], OUTPUTS)
        self.assertIn("no input tensors found", problems)

    def test_non_nhwc_input_is_reported(self):
        problems = inspect_model.check_compatibility([{"shape": [1, 3, 320, 320]}], OUTPUTS)
        self.assertEqual(len(problems), 1)
        self.assertIn("NHWC", problems[0])

    def test_input_without_three_channels_is_reported(self):
        problems = inspect_model.check_compatibility([{"shape": [1, 320, 320, 1]}], OUTPUTS)
        self.assertTrue(any("NHWC" in problem for problem in problems))

    def test_too_few_outputs_are_reported(self):
        problems = inspect_model.check_compatibility(INPUTS, OUTPUTS[:2])
        self.assertTrue(any(">= 3 output tensors" in problem for problem in problems))

    def test_missing_box_tensor_is_reported(self):
        problems = inspect_model.check_compatibility(INPUTS, OUTPUTS[1:])
        self.assertTrue(any("[1, N, 4]" in problem for problem in problems))


class DescribeTests(unittest.TestCase):
    def test_unquantized_float_tensor_omits_the_quant_suffix(self):
        text = inspect_model.describe(INPUTS[0])
        self.assertIn("shape=[1, 320, 320, 3]", text)
        self.assertIn("dtype=float32", text)
        self.assertNotIn("quant:", text)

    def test_quantized_tensor_shows_scale_and_zero_point(self):
        tensor = {
            "name": "detection_scores",
            "shape": [1, 10],
            "dtype": dtype_named("int8"),
            "quantization_parameters": {"scales": [0.0078], "zero_points": [0]},
        }
        text = inspect_model.describe(tensor)
        self.assertIn("dtype=int8", text)
        self.assertIn("quant: scale=[0.0078] zero_point=[0]", text)

    def test_float_tensor_with_a_non_zero_scale_is_flagged(self):
        tensor = {
            "name": "image",
            "shape": [1, 300, 300, 3],
            "dtype": dtype_named("float32"),
            "quantization": {"scale": [0.5], "zero_point": [0]},
        }
        self.assertIn("quant:", inspect_model.describe(tensor))


class CommandLineTests(unittest.TestCase):
    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(TOOLS_DIR / "inspect_model.py"), *arguments],
            capture_output=True,
            text=True,
        )

    def test_missing_argument_is_a_usage_error(self):
        self.assertEqual(self.run_cli().returncode, 2)

    def test_missing_model_file_exits_1_with_an_explicit_message(self):
        result = self.run_cli("definitely-not-a-model.tflite")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Model not found", result.stdout)

    def test_help_documents_the_usage(self):
        result = self.run_cli("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("inspect_model.py", result.stdout)


if __name__ == "__main__":
    unittest.main()
