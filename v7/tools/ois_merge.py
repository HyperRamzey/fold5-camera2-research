#!/usr/bin/env python3
"""Merge an adb-root OIS hall poll into a v7 burst meta.json.

Usage: ois_merge.py <burst_dir> <poll_file> [aux_file]

The in-app poller needs KernelSU to authorize com.samsung.android.ruler.
When it is not authorized, `su` is hidden from the app process and the app
records zero vendor samples. This tool takes the same node's samples from a
root poller started over adb (ois_poll.sh) and folds them into meta.json in
exactly the shape Eraw70Meta.vendorJson emits, so the dump stays complete.

Timestamps: the poller prefixes /proc/uptime, which is CLOCK_BOOTTIME; the
burst's SENSOR_TIMESTAMP values are in the same base (SENSOR_INFO_TIMESTAMP_
SOURCE == REALTIME). /proc/uptime is centisecond-resolution here, so sample
times carry up to ~10 ms quantization plus the node's ~125 ms read latency;
that error is recorded in ois.vendor_timing_accuracy_ms.
"""
import json
import os
import shutil
import sys

NODE = "/sys/class/camera/ois/ois_hall_position"
ASSIGN_WINDOW_NS = 500_000_000
WINDOW_PAD_NS = 500_000_000


def parse_poll(path):
    out = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or "|" not in line:
                continue
            u, h = line.split("|", 1)
            try:
                t = int(round(float(u) * 1e9))
            except ValueError:
                continue
            parts = [p.strip() for p in h.split(",")]
            if len(parts) < 8:
                continue
            try:
                v = [int(p) for p in parts[:8]]
            except ValueError:
                continue
            out.append((t, v))
    out.sort(key=lambda s: s[0])
    return out


def vendor_json(t, v, base_x, base_y):
    return {
        "t_ns": t,
        "module": 1,
        "dx_px": v[4] - base_x,
        "dy_px": v[5] - base_y,
        "hall_dx": v[4] - base_x,
        "hall_dy": v[5] - base_y,
        "hall_x": v[4],
        "hall_y": v[5],
        "target_x": v[0],
        "target_y": v[1],
        "unit": "hall_adc_uncalibrated",
        "calibrated": False,
        "source": "sysfs:" + NODE,
        "module2": {"hall_x": v[6], "hall_y": v[7],
                    "target_x": v[2], "target_y": v[3]},
    }


def parse_aux(path):
    """ois_aux_poll.sh lines: "<uptime>|exif=..|gain=..|raw=..|adc=.."."""
    out = []
    if not path or not os.path.isfile(path):
        return out
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if "|" not in line:
                continue
            u, rest = line.split("|", 1)
            try:
                t = int(round(float(u) * 1e9))
            except ValueError:
                continue
            fields = {}
            for part in rest.split("|"):
                if "=" in part:
                    k, v = part.split("=", 1)
                    fields[k] = v
            out.append((t, fields))
    out.sort(key=lambda s: s[0])
    return out


def main():
    if len(sys.argv) not in (3, 4):
        print(__doc__)
        return 2
    burst_dir, poll_file = sys.argv[1], sys.argv[2]
    aux_file = sys.argv[3] if len(sys.argv) == 4 else None
    meta_path = os.path.join(burst_dir, "meta.json")
    with open(meta_path, "r", encoding="utf-8") as f:
        meta = json.load(f)

    frames = meta["frames"]
    if not frames:
        print("no frames in meta")
        return 1
    frames.sort(key=lambda fr: fr["sensor_timestamp_ns"])
    skew = meta.get("rolling_shutter_skew_ns")
    if not isinstance(skew, (int, float)):
        skew = 0
    lo = frames[0]["sensor_timestamp_ns"] - WINDOW_PAD_NS
    hi = frames[-1]["sensor_timestamp_ns"] + int(skew) + WINDOW_PAD_NS

    all_samples = parse_poll(poll_file)
    win = [(t, v) for t, v in all_samples if lo <= t <= hi]
    print("poll lines: %d, in burst window: %d" % (len(all_samples), len(win)))
    if not win:
        print("no OIS samples inside the burst window")
        return 1
    # Every value zero means the module was idle / camera closed.
    if all(all(x == 0 for x in v) for _, v in win):
        print("all-zero hall values inside the burst window (module idle)")
        return 1

    base_x, base_y = win[0][1][4], win[0][1][5]
    vendor = [vendor_json(t, v, base_x, base_y) for t, v in win]

    assigned = 0
    for fr in frames:
        best, bd = None, None
        for t, v in win:
            d = abs(t - fr["sensor_timestamp_ns"])
            if bd is None or d < bd:
                best, bd = (t, v), d
        samples = fr.get("ois_samples") or []
        if best is not None and bd is not None and bd <= ASSIGN_WINDOW_NS:
            samples.append(vendor_json(best[0], best[1], base_x, base_y))
            assigned += 1
        std = sum(1 for s in samples if s.get("unit") != "hall_adc_uncalibrated")
        ven = sum(1 for s in samples if s.get("unit") == "hall_adc_uncalibrated")
        fr["ois_samples"] = samples
        fr["ois_source"] = ("standard+vendor_sysfs" if std and ven else
                            "standard" if std else
                            "vendor_sysfs" if ven else "none")

    o = meta.setdefault("ois", {})
    aux = parse_aux(aux_file)
    if aux:
        mid = (frames[0]["sensor_timestamp_ns"] +
               frames[-1]["sensor_timestamp_ns"] + int(skew)) // 2
        t, f = min(aux, key=lambda s: abs(s[0] - mid))
        o["vendor_exif"] = f.get("exif")
        o["vendor_gain_rear"] = f.get("gain")
        o["vendor_rawdata"] = f.get("raw")
        o["vendor_adc"] = f.get("adc")
        o["vendor_aux_t_ns"] = t
    o["vendor_mode_note"] = (
        "shootingmode=40 (50MP high-res): the OIS MCU sits centered. Verified"
        " against the stock Samsung camera in 50MP mode, which reports the same"
        " ois_hall_position pattern (2047,2047,2047,2047,2046,2046,0,0); in"
        " 12MP preview the same node reports 2226/2255 targets, so the node is"
        " live and the centering is mode behavior, not a stale read. Framework"
        " STATISTICS_OIS_SAMPLES is not populated by this HAL on cam 56. The"
        " 500 Hz IMU stream is the motion source for photogrammetry.")
    o["vendor_samples"] = vendor
    o["vendor_samples_count"] = len(vendor)
    o["vendor_samples_assigned_to_frames"] = assigned
    o["vendor_samples_source"] = "adb_su_poller:/data/local/tmp/ois_poll.sh"
    o["vendor_node"] = NODE
    o["vendor_units"] = "hall_adc_uncalibrated"
    o["vendor_timing_accuracy_ms"] = 125
    app_err = o.get("vendor_error")
    if app_err:
        o["vendor_error_in_app"] = app_err
        o["vendor_error"] = None

    shutil.copyfile(meta_path, os.path.join(burst_dir, "meta.app.json"))
    tmp = meta_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    os.replace(tmp, meta_path)

    hall_x = [s["hall_x"] for s in vendor]
    hall_y = [s["hall_y"] for s in vendor]
    print("merged %d vendor samples (assigned %d), hall_x %d..%d hall_y %d..%d"
          % (len(vendor), assigned, min(hall_x), max(hall_x),
             min(hall_y), max(hall_y)))
    print("wrote", meta_path, "(backup meta.app.json)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
