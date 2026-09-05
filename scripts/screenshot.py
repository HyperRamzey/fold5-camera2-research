#!/usr/bin/env python
"""Capture screenshot from Fold5 internal display via adb exec-out, writing binary-safe PNG."""
import pathlib
import subprocess
import sys

DEVID = "RFCWC0G1Z1J"
DISPLAY = sys.argv[1] if len(sys.argv) > 1 else "4630947175568309891"
OUT = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else pathlib.Path(
    r"G:\projects\fold5-camera2-research\device-evidence\screenshot.png")

# adb exec-out writes raw bytes to stdout; capture directly
res = subprocess.run(
    ["adb", "-s", DEVID, "exec-out", "screencap", "-d", DISPLAY, "-p"],
    capture_output=True, timeout=60)
png = res.stdout
# strip any leading warning text before PNG magic if present
idx = png.find(b"\x89PNG")
if idx < 0:
    print("ERROR: no PNG magic found; stderr:", res.stderr[:500])
    sys.exit(1)
if idx > 0:
    print(f"stripped {idx} bytes of warning text")
    png = png[idx:]
OUT.write_bytes(png)
print(f"saved {len(png)} bytes -> {OUT}")
