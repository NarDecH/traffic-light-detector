#!/usr/bin/env python3
"""End-to-end check of the dummy model against the app's I/O contract.

These tests need TensorFlow, so they **skip** when it is not installed (which is
the case in CI). Run them locally after:

    pip install "tensorflow>=2.15"
    python -m unittest discover -s tools/tests -v

What they prove: `make_dummy_model.build_model` really produces a `.tflite`
whose outputs are `boxes [1, N, 4]`, `classes [1, N]`, `scores [1, N]`,
`num [1]` **in that order** -- the order `TrafficLightDetector` falls back to
when a graph has no tensor names.
"""

from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest

TOOLS_DIR = pathlib.Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import inspect_model  # noqa: E402
import make_dummy_model  # noqa: E402

try:
    import tensorflow as tf
except ImportError:  # pragma: no cover - exercised on machines without TF
    tf = None

# Small input: these tests check shapes, not accuracy, and conversion time
# matters more than resolution.
TEST_INPUT_SIZE = 64


@unittest.skipUnless(tf is not None, "TensorFlow is not installed")
class DummyModelContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.model_path = pathlib.Path(cls._tmp.name) / "dummy.tflite"
        model = make_dummy_model.build_model(tf, TEST_INPUT_SIZE)
        converted = tf.lite.TFLiteConverter.from_keras_model(model).convert()
        cls.model_path.write_bytes(converted)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def interpreter(self):
        interpreter = tf.lite.Interpreter(model_path=str(self.model_path))
        interpreter.allocate_tensors()
        return interpreter

    def test_model_is_compatible_with_the_app_contract(self):
        interpreter = self.interpreter()
        problems = inspect_model.check_compatibility(
            interpreter.get_input_details(), interpreter.get_output_details()
        )
        self.assertEqual(problems, [])

    def test_input_is_nhwc_at_the_requested_size(self):
        shape = list(self.interpreter().get_input_details()[0]["shape"])
        self.assertEqual(shape, [1, TEST_INPUT_SIZE, TEST_INPUT_SIZE, 3])

    def test_outputs_are_boxes_classes_scores_and_num_detections(self):
        # Asserted as a *multiset* of shapes on purpose: TFLite is free to reorder
        # the subgraph outputs relative to the order the Keras model declared them
        # (observed with TF 2.22 -- classes, num, boxes, scores), which is exactly
        # why TrafficLightDetector resolves tensors by shape/name instead of
        # relying on position. See docs/MODEL.md.
        shapes = sorted(inspect_model.shape_of(t) for t in self.interpreter().get_output_details())
        boxes = make_dummy_model.NUM_BOXES
        self.assertEqual(shapes, sorted([[1, boxes, 4], [1, boxes], [1, boxes], [1, 1]]))

    def test_exactly_one_bounding_box_tensor_exists(self):
        shapes = [inspect_model.shape_of(t) for t in self.interpreter().get_output_details()]
        self.assertEqual(sum(1 for shape in shapes if len(shape) == 3 and shape[2] == 4), 1)

    def test_input_shape_is_reported_as_plain_ints(self):
        # Guards the numpy-scalar formatting fix: TF 2.22 reports np.int32 dims.
        text = inspect_model.describe(self.interpreter().get_input_details()[0])
        self.assertIn(f"shape=[1, {TEST_INPUT_SIZE}, {TEST_INPUT_SIZE}, 3]", text)

    def test_inspect_model_describes_the_generated_model(self):
        interpreter = self.interpreter()
        described = [inspect_model.describe(t) for t in interpreter.get_output_details()]
        boxes = make_dummy_model.NUM_BOXES
        self.assertTrue(any(f"shape=[1, {boxes}, 4]" in text for text in described))


if __name__ == "__main__":
    unittest.main()
