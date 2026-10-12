#!/usr/bin/env python3
"""Print one released version's section from `CHANGELOG.md`.

`.github/workflows/release.yml` uses this to build the body of a GitHub
Release out of the tagged version's changelog entry, so the release notes and
`CHANGELOG.md` can never disagree. Standard library only, like the rest of
`tools/`.

Exit codes: 0 = section printed, 1 = changelog unreadable, version missing, or
the section is empty.

Usage:
    python tools/changelog_section.py 1.2.0 [--changelog CHANGELOG.md]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

DEFAULT_CHANGELOG = pathlib.Path("CHANGELOG.md")

# `## [1.2.0] — 2026-10-13` (the project writes the date after an em dash; the
# date itself is irrelevant because only the bracketed version is compared).
VERSION_HEADING = re.compile(r"^##\s+\[([^\]]+)\]")

# Any other `## ...` heading -- `## Unreleased`, `## [1.1.0]`, ... -- ends the
# section. `### Added` and deeper subsections stay inside it.
SECTION_HEADING = re.compile(r"^##\s")

# Link definitions at the bottom of Keep a Changelog files, e.g.
# `[1.2.0]: https://example.com/compare/...` -- never part of a section body.
LINK_DEFINITION = re.compile(r"^\[[^\]]+\]:")


def section(changelog: str, version: str) -> str | None:
    """Body of the `## [version]` section, or `None` when it is not there.

    The body stops at the next `## ` heading or at the first link definition,
    and blank lines at both ends are stripped. The version must match exactly:
    `1.2.0` does not select `## [1.2.0-rc1]`.
    """
    lines = changelog.splitlines()

    start = None
    for index, line in enumerate(lines):
        heading = VERSION_HEADING.match(line)
        if heading and heading.group(1) == version:
            start = index + 1
            break
    if start is None:
        return None

    body: list[str] = []
    for line in lines[start:]:
        if SECTION_HEADING.match(line) or LINK_DEFINITION.match(line):
            break
        body.append(line)
    return "\n".join(body).strip()


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        # The changelog is Thai text; a Windows console defaults to a legacy
        # code page that cannot encode it (same guard as check_device_backend).
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("version", help="version to print, e.g. 1.2.0 (without the leading 'v')")
    parser.add_argument(
        "--changelog",
        type=pathlib.Path,
        default=DEFAULT_CHANGELOG,
        help=f"path to the changelog file (default: {DEFAULT_CHANGELOG})",
    )
    args = parser.parse_args()

    try:
        text = args.changelog.read_text(encoding="utf-8")
    except OSError as error:
        print(f"Cannot read {args.changelog}: {error}", file=sys.stderr)
        return 1

    body = section(text, args.version)
    if body is None:
        print(f"No '## [{args.version}]' section in {args.changelog}", file=sys.stderr)
        return 1
    if not body:
        print(f"The '## [{args.version}]' section in {args.changelog} is empty", file=sys.stderr)
        return 1

    print(body)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
