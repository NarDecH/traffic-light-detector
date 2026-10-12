#!/usr/bin/env python3
"""Unit tests for `tools/check_device_backend.py`.

Reading the fallback line out of a UI dump, choosing a device from `adb devices`
and building the Gradle command are pure decisions -- they are tested here. The
parts that talk to hardware (install, launch, dump) are not, so this suite runs
anywhere without a phone. Standard library only.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import unittest
from xml.sax.saxutils import quoteattr

TOOLS_DIR = pathlib.Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import check_device_backend  # noqa: E402  (imported after sys.path is prepared)

FALLBACK_LINE = "backend fallback — GPU: delegate creation failed"


def ui_dump(*texts: str) -> str:
    """A minimal `uiautomator dump` document with one TextView per text."""
    nodes = "".join(
        f'\n  <node index="{index}" class="android.widget.TextView" '
        f'text={quoteattr(text)} package="{check_device_backend.PACKAGE}" />'
        for index, text in enumerate(texts)
    )
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>\n'
        f'<hierarchy rotation="0">{nodes}\n</hierarchy>\n'
    )


def normalised(path: str) -> str:
    """Path with `/` separators, so assertions work on Windows and Linux."""
    return path.replace("\\", "/")


class FallbackLineTests(unittest.TestCase):
    def test_a_reason_is_read_out_of_the_line(self):
        check = check_device_backend.check_ui_dump(ui_dump("Detecting…", FALLBACK_LINE))
        self.assertTrue(check.ok)
        self.assertEqual(check.reasons, ["GPU: delegate creation failed"])
        self.assertEqual(check.lines, [FALLBACK_LINE])

    def test_the_dash_and_spacing_do_not_end_up_in_the_reason(self):
        check = check_device_backend.check_ui_dump(ui_dump("backend fallback —   NNAPI: no backend"))
        self.assertEqual(check.reasons, ["NNAPI: no backend"])

    def test_every_rejected_backend_on_the_line_is_one_reason(self):
        # MainActivity joins several fallbacks with " · ", they stay one text node.
        line = "backend fallback — GPU: x · NNAPI: y"
        check = check_device_backend.check_ui_dump(ui_dump(line))
        self.assertEqual(check.reasons, ["GPU: x · NNAPI: y"])

    def test_a_line_without_a_reason_is_reported_but_not_accepted(self):
        for line in ("backend fallback", "backend fallback —", "backend fallback —   "):
            with self.subTest(line=line):
                check = check_device_backend.check_ui_dump(ui_dump(line))
                self.assertFalse(check.ok)
                # The dump's text is stripped before it is reported.
                self.assertEqual(check.lines, [line.strip()])
                self.assertIn("without a reason", check.summary())

    def test_no_line_at_all_is_reported(self):
        check = check_device_backend.check_ui_dump(ui_dump("Detecting…", "green 92%", "5.0 fps"))
        self.assertFalse(check.ok)
        self.assertEqual(check.lines, [])
        self.assertIn("no 'backend fallback' line", check.summary())

    def test_only_a_text_that_starts_with_the_line_counts(self):
        check = check_device_backend.check_ui_dump(ui_dump("see backend fallback — GPU: x in the log"))
        self.assertFalse(check.ok)
        self.assertEqual(check.lines, [])

    def test_several_lines_are_all_reported(self):
        check = check_device_backend.check_ui_dump(
            ui_dump(FALLBACK_LINE, "backend fallback — CPU x4: no delegate")
        )
        self.assertEqual(len(check.lines), 2)
        self.assertEqual(check.reasons, ["GPU: delegate creation failed", "CPU x4: no delegate"])
        self.assertIn(FALLBACK_LINE, check.summary())

    def test_thai_text_and_xml_entities_do_not_confuse_the_scan(self):
        check = check_device_backend.check_ui_dump(
            ui_dump("ความมั่นใจ 92% & \"quoted\"", 'backend fallback — GPU: missing "op" & more')
        )
        self.assertTrue(check.ok)
        self.assertEqual(check.reasons, ['GPU: missing "op" & more'])

    def test_a_broken_dump_is_reported_not_raised(self):
        check = check_device_backend.check_ui_dump("<hierarchy><node text=")
        self.assertFalse(check.ok)
        self.assertIsNotNone(check.error)
        self.assertIn("could not read", check.summary())


class DevicePickTests(unittest.TestCase):
    def test_a_connected_device_is_picked(self):
        output = "List of devices attached\n1234abcd\tdevice\n"
        self.assertEqual(check_device_backend.pick_device(output), ("1234abcd", ""))

    def test_an_unauthorized_device_gives_the_dialog_hint(self):
        serial, note = check_device_backend.pick_device("List of devices attached\n1234abcd\tunauthorized\n")
        self.assertIsNone(serial)
        self.assertIn("Allow USB debugging", note)

    def test_an_offline_device_says_to_reconnect(self):
        serial, note = check_device_backend.pick_device("List of devices attached\n1234abcd\toffline\n")
        self.assertIsNone(serial)
        self.assertIn("offline", note)

    def test_an_empty_listing_says_nothing_is_attached(self):
        serial, note = check_device_backend.pick_device("List of devices attached\n\n")
        self.assertIsNone(serial)
        self.assertIn("no device is attached", note)

    def test_daemon_notices_are_ignored(self):
        output = (
            "* daemon not running; starting now at tcp:5037\n"
            "* daemon started successfully\n"
            "List of devices attached\n"
            "SERIAL01\tdevice\n"
        )
        self.assertEqual(check_device_backend.pick_device(output), ("SERIAL01", ""))


class AdbLookupTests(unittest.TestCase):
    def test_adb_on_the_path_wins(self):
        found = check_device_backend.find_adb(
            {}, which=lambda name: "/usr/local/bin/adb", exists=lambda path: False
        )
        self.assertEqual(found, "/usr/local/bin/adb")

    def test_adb_is_found_in_the_sdk_from_android_home(self):
        found = check_device_backend.find_adb(
            {"ANDROID_HOME": "/sdk"},
            which=lambda name: None,
            exists=lambda path: normalised(path).endswith("platform-tools/adb.exe"),
        )
        self.assertIsNotNone(found)
        self.assertTrue(normalised(found).endswith("platform-tools/adb.exe"))

    def test_the_sdk_under_local_app_data_is_checked_too(self):
        found = check_device_backend.find_adb(
            {"LOCALAPPDATA": "C:/Users/dev/AppData/Local"},
            which=lambda name: None,
            exists=lambda path: normalised(path).endswith("platform-tools/adb.exe"),
        )
        self.assertTrue(normalised(found).endswith("/Android/Sdk/platform-tools/adb.exe"))

    def test_no_adb_anywhere_returns_none(self):
        self.assertIsNone(
            check_device_backend.find_adb({}, which=lambda name: None, exists=lambda path: False)
        )


class GradleCommandTests(unittest.TestCase):
    def test_windows_wrapper_is_run_through_cmd(self):
        command = check_device_backend.gradle_command(
            pathlib.Path("/repo"),
            platform="nt",
            exists=lambda path: path.endswith("gradlew.bat"),
            which=lambda name: None,
        )
        self.assertIn("/c", command)
        self.assertTrue(command[-1].endswith("gradlew.bat"))

    def test_posix_wrapper_is_run_with_sh(self):
        command = check_device_backend.gradle_command(
            pathlib.Path("/repo"),
            platform="posix",
            exists=lambda path: path.endswith("gradlew"),
            which=lambda name: None,
        )
        self.assertEqual(command[0], "sh")
        self.assertTrue(command[-1].endswith("gradlew"))
        self.assertFalse(command[-1].endswith(".bat"))

    def test_no_wrapper_falls_back_to_gradle_on_the_path(self):
        # The repo does not commit `gradlew` (see the README), so this is the
        # normal case on a fresh checkout with a Gradle distribution installed.
        command = check_device_backend.gradle_command(
            pathlib.Path("/repo"),
            platform="posix",
            exists=lambda path: False,
            which=lambda name: "/opt/gradle/bin/gradle" if name == "gradle" else None,
        )
        self.assertEqual(command[0], "sh")
        # pathlib normalises separators to the host OS, so compare normalised.
        self.assertEqual(normalised(command[1]), "/opt/gradle/bin/gradle")

    def test_no_wrapper_and_no_gradle_is_none(self):
        self.assertIsNone(
            check_device_backend.gradle_command(
                pathlib.Path("/repo"),
                platform="posix",
                exists=lambda path: False,
                which=lambda name: None,
            )
        )


class CommandLineTests(unittest.TestCase):
    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(TOOLS_DIR / "check_device_backend.py"), *arguments],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

    def test_help_works_without_a_device(self):
        result = self.run_cli("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("usage", result.stdout.lower())
        self.assertIn("backend fallback", result.stdout)

    def test_a_malformed_timeout_is_a_usage_error(self):
        result = self.run_cli("--timeout", "soon")
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
