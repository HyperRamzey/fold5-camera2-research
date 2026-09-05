#!/usr/bin/env python
"""Extract ASCII strings (min length 6) from binaries for gating analysis."""
import pathlib
import re

dst = pathlib.Path(r"G:\projects\fold5-camera2-research\binaries")
out = dst / "strings"
out.mkdir(exist_ok=True)

for name in ["cameraserver", "sec-camera-provider", "libsamsungcamerahal.so",
             "libsamsungcamerahwl_impl.so", "com.qti.camx.chiiqutils.so"]:
    p = dst / name
    if not p.exists():
        print(f"{name}: MISSING")
        continue
    data = p.read_bytes()
    strs = re.findall(rb"[\x20-\x7e]{6,}", data)
    (out / f"{name}.txt").write_bytes(b"\n".join(strs))
    print(f"{name}: {len(strs)} strings, {p.stat().st_size} bytes")
