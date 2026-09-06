#!/usr/bin/env python
"""Analyze the S23 AGC config (.agc = flat preferences XML) for Fold5 compatibility.

The .agc file is a flat key/value map (Android SharedPreferences export). It is parsed
line-by-line with anchored regexes instead of an XML tree parser: the format is a
single-level <map> of <string>/<int>/<set> entries, so structural XML attacks are not
applicable, and this avoids xml.etree entirely.
"""

from __future__ import annotations

import re
import sys

CFG = r"G:\projects\fold5-camera2-research\JavaSaBr_S23_AGC92v13_v52_Medium.agc"

# Anchored patterns for the three element shapes this format uses.
RE_STRING = re.compile(r'^\s*<string name="([^"]+)">([^<]*)</string>\s*$')
RE_INT = re.compile(r'^\s*<int name="([^"]+)" value="([^"]*)"\s*/>\s*$')
RE_SET_ITEM = re.compile(r'^\s*<string>([^<]*)</string>\s*$')


def parse(path: str) -> dict[str, object]:
    entries: dict[str, object] = {}
    current_set: list[str] | None = None
    set_name = ""
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\r\n")
            if current_set is not None:
                m = RE_SET_ITEM.match(line)
                if m:
                    current_set.append(m.group(1))
                    continue
                if "</set>" in line:
                    entries[set_name] = current_set
                    current_set = None
                    continue
            m = RE_STRING.match(line)
            if m:
                entries[m.group(1)] = m.group(2)
                continue
            m = RE_INT.match(line)
            if m:
                entries[m.group(1)] = m.group(2)
                continue
            m = re.match(r'^\s*<set name="([^"]+)"', line)
            if m:
                set_name = m.group(1)
                current_set = []
    return entries


def main() -> int:
    entries = parse(CFG)
    print(f"Total keys: {len(entries)}")

    print("\n=== IDENTITY ===")
    for k in sorted(entries):
        if k.startswith("info_"):
            print(f"  {k} = {entries[k]}")

    print("\n=== MODEL / LENS SELECTION ===")
    for k in sorted(entries):
        if re.search(r"model|device|camera_id|aux|lens_id|gcam_s|sensor_id", k) and not k.startswith("lib_"):
            print(f"  {k} = {entries[k]}")

    print("\n=== ALL CAMERA ID SETS ===")
    for k, v in entries.items():
        if isinstance(v, list) and "camera_id" in k:
            print(f"  {k} = {v}")

    print("\n=== FRAME COUNTS / ZSL ===")
    for k in sorted(entries):
        if "frame_count" in k or k.startswith("lib_pref_frame"):
            print(f"  {k} = {entries[k]}")

    print("\n=== NOISE MODEL / AWB (lens-specific) ===")
    for k in sorted(entries):
        if re.search(r"noise_model|noise_\d|awb_model", k):
            print(f"  {k} = {str(entries[k])[:80]}")

    print("\n=== LENS-INDEXED KEYS (pN_M pattern) ===")
    lens_idx: dict[str, int] = {}
    for k in entries:
        m = re.search(r"_p(\d)_(\d)", k)
        if m:
            slot = f"p{m.group(1)}_{m.group(2)}"  # digits straight from the regex, no int()
            lens_idx[slot] = lens_idx.get(slot, 0) + 1
    for slot in sorted(lens_idx):
        print(f"  slot {slot}: {lens_idx[slot]} keys")

    print("\n=== FOLD5 REFERENCE (from dumpsys evidence) ===")
    fold5_ids = {"0", "1", "2", "3", "4", "20", "21", "23", "52", "56", "58", "71", "73", "90"}
    cfg_list = entries.get("pref_all_camera_id_list_key", [])
    cfg_ids = set(cfg_list) if isinstance(cfg_list, list) else set()
    print(f"  S23 cfg all_camera_ids: {sorted(cfg_ids, key=lambda x: (len(x), x))}")
    print(f"  IDs in cfg but NOT on Fold5: {sorted(cfg_ids - fold5_ids, key=lambda x: (len(x), x))}")
    print(f"  Fold5 IDs missing from cfg: {sorted(fold5_ids - cfg_ids, key=lambda x: (len(x), x))}")
    print(f"  info_board says: {entries.get('info_board_key')!r} (Fold5 is also kalama - MATCH)")

    if "pref_camera_id_list_key" in entries:
        print(f"  cfg pref_camera_id_list: {entries['pref_camera_id_list_key']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
