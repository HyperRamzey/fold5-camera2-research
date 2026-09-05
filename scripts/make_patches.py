#!/usr/bin/env python
"""Generate clean unified diffs of the two AGC Fold5 smali fixes (a/pristine vs b/patched)."""
import difflib
import pathlib

BASE = pathlib.Path(r"G:\projects\fold5-camera2-research\_pristine")
MOD = pathlib.Path(r"G:\projects\fold5-camera2-research\gcam_decompiled")
OUT = pathlib.Path(r"G:\projects\fold5-camera2-research\github-publish\patches")
OUT.mkdir(parents=True, exist_ok=True)

FILES = [
    ("smali_classes2/com/agc/Camera.smali",
     "0001-agc-Camera-rawsize-null-guard.patch"),
    ("smali/qix.smali",
     "0002-qix-frame-raw-max-pixelarray.patch"),
]

for rel, outname in FILES:
    a = (BASE / rel).read_text(encoding="utf-8").splitlines(keepends=True)
    b = (MOD / rel).read_text(encoding="utf-8").splitlines(keepends=True)
    diff = difflib.unified_diff(
        a, b, fromfile=f"a/{rel}", tofile=f"b/{rel}", n=6)
    text = "".join(diff)
    if not text:
        print(f"ERROR: no diff for {rel}")
        raise SystemExit(1)
    (OUT / outname).write_text(text, encoding="utf-8", newline="\n")
    print(f"{outname}: {len(text)} bytes, {text.count(chr(10))} lines")
