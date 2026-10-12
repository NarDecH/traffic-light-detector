#!/usr/bin/env python3
"""Unit tests for `tools/changelog_section.py`.

Standard library only. The output of this tool becomes the body of a GitHub
Release, so the interesting cases are: only the requested section is printed,
it stops at the next heading (bracketed or not) and at the link definitions at
the bottom of Keep a Changelog files, and the version match is exact.

Run from the repository root:

    python -m unittest discover -s tools/tests -v
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

import changelog_section  # noqa: E402  (imported after sys.path is prepared)

# Mirrors the real file's shape: bracketed headings with an em dash date,
# subsections, and link definitions at the bottom.
CHANGELOG = """# Changelog

## [Unreleased]

### Planned

- ของที่ยังไม่ทำ

## [1.2.0] — 2026-10-13

### Added

- release อัตโนมัติ

### Fixed

- บั๊กเล็ก ๆ

## [1.1.0] — 2026-10-12

- ของเก่า

[Unreleased]: https://example.com/compare/v1.1.0...HEAD
[1.2.0]: https://example.com/compare/v1.1.0...v1.2.0
"""


class SectionTests(unittest.TestCase):
    def test_only_the_requested_section_is_returned(self):
        body = changelog_section.section(CHANGELOG, "1.1.0")
        self.assertEqual(body, "- ของเก่า")
        self.assertNotIn("release อัตโนมัติ", body)

    def test_subsections_belong_to_the_section(self):
        body = changelog_section.section(CHANGELOG, "1.2.0")
        self.assertIn("### Added", body)
        self.assertIn("release อัตโนมัติ", body)
        self.assertIn("### Fixed", body)
        self.assertIn("บั๊กเล็ก ๆ", body)
        self.assertNotIn("ของเก่า", body)

    def test_stops_at_a_plain_heading_without_brackets(self):
        text = "## [1.0.0]\n\n- ใหม่\n\n## Unreleased\n\n- เก่า\n"
        self.assertEqual(changelog_section.section(text, "1.0.0"), "- ใหม่")

    def test_last_section_ends_before_the_link_definitions(self):
        body = changelog_section.section(CHANGELOG, "1.1.0")
        self.assertNotIn("https://", body)

    def test_version_must_match_exactly(self):
        text = "## [1.2.0-rc1]\n\n- ตัวทดสอบ\n"
        self.assertIsNone(changelog_section.section(text, "1.2.0"))

    def test_missing_version_returns_none(self):
        self.assertIsNone(changelog_section.section(CHANGELOG, "9.9.9"))

    def test_blank_lines_around_the_body_are_stripped(self):
        text = "## [1.0.0]\n\n\n- ใหม่\n\n\n## [0.9.0]\n"
        self.assertEqual(changelog_section.section(text, "1.0.0"), "- ใหม่")


class CommandLineTests(unittest.TestCase):
    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(TOOLS_DIR / "changelog_section.py"), *arguments],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )

    def write_changelog(self, text: str, directory: str) -> pathlib.Path:
        path = pathlib.Path(directory) / "CHANGELOG.md"
        path.write_text(text, encoding="utf-8")
        return path

    def test_prints_the_section_and_exits_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_changelog(CHANGELOG, directory)
            result = self.run_cli("1.2.0", "--changelog", str(path))
        self.assertEqual(result.returncode, 0)
        self.assertIn("release อัตโนมัติ", result.stdout)
        self.assertNotIn("ของเก่า", result.stdout)

    def test_missing_version_reports_on_stderr_and_exits_one(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_changelog(CHANGELOG, directory)
            result = self.run_cli("9.9.9", "--changelog", str(path))
        self.assertEqual(result.returncode, 1)
        self.assertIn("No '## [9.9.9]' section", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_empty_section_exits_one(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_changelog("## [1.0.0]\n\n## [0.9.0]\n\n- เก่า\n", directory)
            result = self.run_cli("1.0.0", "--changelog", str(path))
        self.assertEqual(result.returncode, 1)
        self.assertIn("is empty", result.stderr)

    def test_unreadable_changelog_exits_one(self):
        result = self.run_cli("1.0.0", "--changelog", "definitely/not/a/changelog.md")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Cannot read", result.stderr)


if __name__ == "__main__":
    unittest.main()
