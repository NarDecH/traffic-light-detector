#!/usr/bin/env python3
"""Check on a real device that the on-screen `backend fallback — …` line works.

The accelerator fallback path only exists on hardware: the JVM tests cannot create
a TFLite interpreter, so "GPU was rejected, hence CPU" is unreachable for them.
This script drives the path end to end and checks the result where a human would
otherwise have to stare at the phone:

  1. install the debug build (`gradle :app:installDebug`),
  2. grant the camera permission and launch `MainActivity`,
  3. dump the UI hierarchy with `uiautomator dump`,
  4. fail unless a `backend fallback — <reason>` line is actually on screen,
     with a non-empty reason (a line without a reason is worthless in the field).

Run it with the dummy model (`python tools/make_dummy_model.py`): the ops it uses
are not supported by the GPU delegate, so a fallback is guaranteed and the check
is deterministic. With a real model the line may legitimately be absent (the
first accelerator loaded), and the script will say so.

Exit codes: 0 = fallback line with a reason seen, 1 = not seen (or seen empty),
2 = environment problem (no adb / no usable device / build, install or dump failed).

Usage:
    python tools/check_device_backend.py
    python tools/check_device_backend.py --serial 1234abcd --timeout 60
    python tools/check_device_backend.py --skip-install
    python tools/check_device_backend.py --gradle /opt/gradle-8.11.1/bin/gradle
"""

from __future__ import annotations

import argparse
import dataclasses
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ElementTree

PACKAGE = "com.example.trafficlightdetector"
ACTIVITY = f"{PACKAGE}/.MainActivity"
CAMERA_PERMISSION = "android.permission.CAMERA"
# Must match R.string.backend_fallback_format ("backend fallback — %1$s").
FALLBACK_MARKER = "backend fallback"
# Where `uiautomator dump` leaves the hierarchy on the device.
UI_DUMP_PATH = "/sdcard/check_device_backend.xml"
POLL_SECONDS = 2.0
DEFAULT_TIMEOUT_SECONDS = 30.0

NO_DEVICE_HINT = (
    "Phone checklist: unlock the screen, enable USB debugging, and on MIUI/HyperOS "
    "also enable 'Install via USB' (the toggle turns itself off regularly and makes "
    "adb install fail with INSTALL_FAILED_USER_RESTRICTED). Then accept the RSA "
    "dialog on the phone and re-run this script."
)
NO_FALLBACK_HINT = (
    "A fallback line only appears when a preferred accelerator is rejected. Run this "
    "with the dummy model (python tools/make_dummy_model.py), and check the reasons "
    "with: adb logcat -s TrafficLightDetector:I  (see docs/TESTING.md section 1.3)."
)


@dataclasses.dataclass
class FallbackCheck:
    """What one UI dump says about an on-screen `backend fallback` line."""

    lines: list[str] = dataclasses.field(default_factory=list)
    reasons: list[str] = dataclasses.field(default_factory=list)
    error: str | None = None

    @property
    def ok(self) -> bool:
        """True only for a line that shows *why* the accelerator was rejected."""
        return bool(self.reasons)

    def summary(self) -> str:
        if self.error is not None:
            return f"could not read the UI dump ({self.error})"
        if self.reasons:
            return f"fallback line: {self.lines[0]}"
        if self.lines:
            return f"fallback line without a reason: {self.lines[0]}"
        return f"no '{FALLBACK_MARKER}' line on screen"


def fallback_reason(line: str) -> str:
    """`backend fallback — GPU: no such op` -> `GPU: no such op` (dash stripped)."""
    remainder = line.strip()[len(FALLBACK_MARKER) :]
    return remainder.lstrip(" \t\u00a0—–-:…").strip()


def check_ui_dump(xml_text: str) -> FallbackCheck:
    """Reads a `uiautomator dump` document: which texts are fallback lines."""
    check = FallbackCheck()
    try:
        root = ElementTree.fromstring(xml_text)
    except ElementTree.ParseError as error:
        check.error = str(error)
        return check
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        if text.startswith(FALLBACK_MARKER):
            check.lines.append(text)
            reason = fallback_reason(text)
            if reason:
                check.reasons.append(reason)
    return check


def pick_device(adb_devices_output: str) -> tuple[str | None, str]:
    """First device in state `device`, else `(None, why not)`.

    `adb devices` also prints a header and, occasionally, `* daemon …` notices;
    both are skipped here.
    """
    attached: list[tuple[str, str]] = []
    for line in adb_devices_output.splitlines():
        fields = line.split()
        if len(fields) == 2 and not fields[0].startswith("*"):
            attached.append((fields[0], fields[1]))

    for serial, state in attached:
        if state == "device":
            return serial, ""
    if not attached:
        return None, "no device is attached (adb devices is empty)"
    unauthorized = [serial for serial, state in attached if state == "unauthorized"]
    if unauthorized:
        return None, (
            f"device {unauthorized[0]} is unauthorized: accept the 'Allow USB debugging' "
            "dialog on the phone (and keep the screen unlocked)"
        )
    offline = [serial for serial, state in attached if state == "offline"]
    if offline:
        return None, f"device {offline[0]} is offline: reconnect the cable or run 'adb kill-server'"
    return None, f"no usable device (states: {', '.join(sorted({s for _, s in attached}))})"


def find_adb(env: dict[str, str], which=shutil.which, exists=os.path.isfile) -> str | None:
    """`adb` from PATH, else from the Android SDK the environment points at."""
    found = which("adb")
    if found:
        return found

    roots = [env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT")]
    local_app_data = env.get("LOCALAPPDATA")
    if local_app_data:
        roots.append(str(pathlib.Path(local_app_data) / "Android" / "Sdk"))
    for root in roots:
        if not root:
            continue
        for name in ("adb", "adb.exe"):
            candidate = pathlib.Path(root) / "platform-tools" / name
            if exists(str(candidate)):
                return str(candidate)
    return None


def runnable(path: pathlib.Path, platform: str | None = None) -> list[str]:
    """Command line that runs `path` here (a `.bat` needs cmd.exe; the rest need sh)."""
    if (platform or os.name) == "nt":
        return [os.environ.get("COMSPEC", "cmd.exe"), "/c", str(path)]
    return ["sh", str(path)]


def gradle_command(
    root: pathlib.Path,
    platform: str | None = None,
    exists=os.path.isfile,
    which=shutil.which,
) -> list[str] | None:
    """How to run Gradle for this checkout, or `None` when it cannot be found.

    The wrapper script is not committed either (like `gradle-wrapper.jar`, see the
    README), so a checkout without `gradlew` falls back to `gradle` on PATH.
    """
    nt = (platform or os.name) == "nt"
    wrapper = root / ("gradlew.bat" if nt else "gradlew")
    if exists(str(wrapper)):
        return runnable(wrapper, platform)
    installed = which("gradle.bat" if nt else "gradle")
    return runnable(pathlib.Path(installed), platform) if installed else None


def run(command: list[str]) -> subprocess.CompletedProcess:
    """Runs a helper tool, never raising: the caller inspects the exit code."""
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def install_debug_build(serial: str, gradle: list[str]) -> int:
    """Installs the debug APK on `serial`; returns a process exit code style result."""
    root = pathlib.Path(__file__).resolve().parents[1]
    print(f"Installing the debug build on {serial} ...")
    result = run(gradle + ["--project-dir", str(root), ":app:installDebug"])
    if result.returncode != 0:
        tail = "\n".join((result.stdout + result.stderr).splitlines()[-15:])
        print(f":app:installDebug failed (exit {result.returncode}):\n{tail}", file=sys.stderr)
        return 2
    return 0


def read_ui_dump(adb_base: list[str]) -> tuple[FallbackCheck | None, str | None]:
    """One dump+pull cycle: the parsed check, or `None` plus why it could not be read."""
    dump = run(adb_base + ["shell", "uiautomator", "dump", UI_DUMP_PATH])
    with tempfile.TemporaryDirectory() as directory:
        target = pathlib.Path(directory) / "ui.xml"
        pulled = run(adb_base + ["pull", UI_DUMP_PATH, str(target)])
        if pulled.returncode != 0 or not target.exists():
            detail = ((dump.stderr or dump.stdout) or "uiautomator dump failed").strip()
            return None, (detail.splitlines()[0] if detail else "uiautomator dump failed")
        return check_ui_dump(target.read_text(encoding="utf-8", errors="replace")), None


def wait_for_fallback_line(adb_base: list[str], timeout: float) -> FallbackCheck:
    """Polls the UI until the fallback line shows up, or until `timeout` runs out.

    A dump that fails mid-run (the UI was animating) does not erase an earlier
    reading: only a device where *no* dump could ever be read reports an error.
    """
    deadline = time.monotonic() + max(timeout, 0.0)
    parsed: FallbackCheck | None = None
    while True:
        time.sleep(POLL_SECONDS)
        check, problem = read_ui_dump(adb_base)
        if check is not None:
            parsed = check
            print(f"  {check.summary()}")
        else:
            print(f"  could not read the UI dump ({problem})")
        if (check is not None and check.ok) or time.monotonic() >= deadline:
            break
    if parsed is None:
        return FallbackCheck(error=problem or "uiautomator dump failed")
    return parsed


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        # The dump contains non-ASCII (the em dash in the fallback line).
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", help="device to use when several are attached")
    parser.add_argument(
        "--skip-install",
        action="store_true",
        help="use the build already on the device (skips the Gradle install step)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT_SECONDS,
        help=f"seconds to wait for the line to appear (default: {DEFAULT_TIMEOUT_SECONDS:.0f})",
    )
    parser.add_argument(
        "--gradle",
        help="Gradle executable to use when the checkout has no wrapper (e.g. a distribution path)",
    )
    args = parser.parse_args()

    adb = find_adb(os.environ)
    if adb is None:
        print("adb not found: install Android platform-tools or set ANDROID_HOME.", file=sys.stderr)
        return 2

    serial, note = args.serial, ""
    if serial is None:
        serial, note = pick_device(run([adb, "devices"]).stdout)
    if serial is None:
        print(f"Cannot run the device check: {note}", file=sys.stderr)
        print(NO_DEVICE_HINT, file=sys.stderr)
        return 2

    if not args.skip_install:
        root = pathlib.Path(__file__).resolve().parents[1]
        gradle = runnable(pathlib.Path(args.gradle)) if args.gradle else gradle_command(root)
        if gradle is None:
            print(
                "Gradle not found: this checkout has no `gradlew` script and `gradle` is not on "
                "PATH. Generate the wrapper once (Android Studio, or `gradle wrapper --gradle-version "
                "8.11.1`) or pass --gradle <path>.",
                file=sys.stderr,
            )
            return 2
        status = install_debug_build(serial, gradle)
        if status != 0:
            return status

    adb_base = [adb, "-s", serial]
    print(f"Launching {ACTIVITY} on {serial} ...")
    run(adb_base + ["shell", "pm", "grant", PACKAGE, CAMERA_PERMISSION])
    started = run(adb_base + ["shell", "am", "start", "-n", ACTIVITY])
    if "Error" in (started.stdout + started.stderr):
        print(
            f"Could not launch {ACTIVITY}: {(started.stdout + started.stderr).strip()}",
            file=sys.stderr,
        )
        return 2

    print(f"Waiting up to {args.timeout:.0f}s for the '{FALLBACK_MARKER}' line ...")
    check = wait_for_fallback_line(adb_base, args.timeout)
    run(adb_base + ["shell", "rm", "-f", UI_DUMP_PATH])

    if check.ok:
        print(f"\nOK: the screen reports why it fell back -- {check.reasons[0]}")
        return 0
    if check.error is not None:
        print(f"\nCould not read the UI hierarchy: {check.error}", file=sys.stderr)
        print("Check that 'uiautomator dump' works over adb on this device.", file=sys.stderr)
        return 2

    print(f"\nFAIL: {check.summary()}", file=sys.stderr)
    print(NO_FALLBACK_HINT, file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
