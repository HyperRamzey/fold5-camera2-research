#!/usr/bin/env python3
"""Verify an AGC v7 50MP RAW10 burst against the three live gates.

Usage: verify_v7.py <burst_dir>

Gate 1  distinct frames, exact stride*height size, structurally valid RAW10
        payload (0..4095, nonzero variance, >=64 distinct values). A dark
        scene (obstructed lens) is accepted and reported, not failed.
Gate 2  REALTIME timestamps, per-frame rolling-shutter skew, 500 Hz IMU
        bracket, non-empty OIS samples. A constant OIS hall reading is
        accepted only when the gyro proves the device was stationary.
Gate 3  tap-to-disk (burst submit -> last frame + meta synced) < 1200 ms

The duplicate-frame check is itself sabotage-tested: the same validator is
run on a frame list where frame_1 aliases frame_0 and must reject it.
"""
import hashlib
import json
import os
import statistics
import sys

RAW10_GROUP = 5


class GateFail(Exception):
    pass


class DuplicateFrame(GateFail):
    pass


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(8 * 1024 * 1024)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def unpack_raw10_patch(path, stride, width, rows=4):
    used = (width // 4) * RAW10_GROUP
    with open(path, "rb") as f:
        f.seek((6120 // 2) * stride)
        data = f.read(stride * rows)
    if len(data) < stride * rows:
        raise GateFail("short read for RAW10 patch: %d" % len(data))
    vals = []
    for r in range(rows):
        row = data[r * stride:r * stride + used]
        for i in range(0, used, RAW10_GROUP):
            b0, b1, b2, b3, b4 = row[i:i + 5]
            vals.append((b0 << 2) | (b4 & 3))
            vals.append((b1 << 2) | ((b4 >> 2) & 3))
            vals.append((b2 << 2) | ((b4 >> 4) & 3))
            vals.append((b3 << 2) | ((b4 >> 6) & 3))
    return vals


def check_distinct(base, frames):
    hashes = {}
    for fr in frames:
        p = os.path.join(base, fr["file"])
        if not os.path.isfile(p):
            raise GateFail("missing frame file: %s" % p)
        h = sha256(p)
        if h in hashes:
            raise DuplicateFrame("frame %s duplicates %s (sha256=%s)"
                                 % (fr["file"], hashes[h], h))
        hashes[h] = fr["file"]
    return hashes


def gate1(meta, base):
    frames = meta["frames"]
    if len(frames) != 6:
        raise GateFail("expected 6 frames, meta has %d" % len(frames))
    print("  frame sizes:")
    for fr in frames:
        p = os.path.join(base, fr["file"])
        size = os.path.getsize(p)
        expect = int(fr["row_stride"]) * int(fr["height"])
        if size != expect:
            raise GateFail("%s size %d != stride*height %d" % (fr["file"], size, expect))
        if int(fr["bytes"]) != size:
            raise GateFail("%s meta bytes %s != on-disk %d" % (fr["file"], fr["bytes"], size))
        vals = unpack_raw10_patch(p, int(fr["row_stride"]), int(fr["width"]))
        lo, hi = min(vals), max(vals)
        var = statistics.pvariance(vals)
        med = statistics.median(vals)
        uniq = len(set(vals))
        print("    %s %d B stride=%d patch[min=%d med=%d max=%d mean=%.1f"
              " std=%.2f uniq=%d]"
              % (fr["file"], size, fr["row_stride"], lo, med, hi,
                 sum(vals) / len(vals), var ** 0.5, uniq))
        if hi > 4095:
            raise GateFail("%s patch max %d > 4095" % (fr["file"], hi))
        if hi == lo:
            raise GateFail("%s patch is a constant %d (blank buffer)" % (fr["file"], lo))
        if hi == 0:
            raise GateFail("%s patch is all zero" % fr["file"])
        if uniq < 64:
            raise GateFail("%s patch has only %d distinct values (degenerate data)"
                           % (fr["file"], uniq))
        if lo < 200:
            # Obstructed lens / dark room: the pedestal-removed stream sits on
            # its noise floor. Real sensor frames, just dark; report it.
            print("    note: dark frame (median %d, max %d) - lens obstructed;"
                  " RAW10 noise floor accepted" % (med, hi))
    hashes = check_distinct(base, frames)
    print("  distinct: %d unique sha256" % len(hashes))
    # Sabotage: aliasing frame_1 to frame_0 must be rejected by the same check.
    fake = [dict(f) for f in frames]
    fake[1]["file"] = fake[0]["file"]
    try:
        check_distinct(base, fake)
    except DuplicateFrame as e:
        print("  sabotage PASS: deliberately duplicated buffer rejected (%s)" % e)
    else:
        raise GateFail("SABOTAGE FAILED: duplicate buffer was accepted")
    return True


def gate2(meta):
    st = meta.get("static_info") or {}
    src = st.get("SENSOR_INFO_TIMESTAMP_SOURCE")
    if src != 1:
        raise GateFail("SENSOR_INFO_TIMESTAMP_SOURCE=%r, expected 1 (REALTIME)" % src)
    print("  timestamp source: REALTIME (1)")

    frames = meta["frames"]
    skews = []
    for fr in frames:
        s = fr.get("rolling_shutter_skew_ns")
        if not isinstance(s, (int, float)):
            raise GateFail("frame %s has no rolling_shutter_skew_ns" % fr.get("file"))
        if s <= 20000000:
            raise GateFail("frame %s skew %s <= 20 ms" % (fr.get("file"), s))
        skews.append(s)
    print("  rolling-shutter skew: %s..%s ns (source=%s)"
          % (min(skews), max(skews), frames[0].get("rolling_shutter_skew_source")))

    gyro = meta.get("imu_gyro_uncal") or []
    if len(gyro) < 80:
        raise GateFail("imu_gyro_uncal has %d samples, need >= 80" % len(gyro))
    t0 = frames[0]["sensor_timestamp_ns"]
    t1 = frames[-1]["sensor_timestamp_ns"] + skews[-1]
    gmin = min(g["t_ns"] for g in gyro)
    gmax = max(g["t_ns"] for g in gyro)
    if gmin > t0:
        raise GateFail("gyro starts %.3f ms after frame0" % ((gmin - t0) / 1e6))
    if gmax < t1:
        raise GateFail("gyro ends %.3f ms before frame5+skew" % ((t1 - gmax) / 1e6))
    rate = (len(gyro) - 1) * 1e9 / (gmax - gmin) if len(gyro) > 1 and gmax > gmin else 0
    print("  IMU gyro: %d samples, %.1f Hz, brackets burst [%.3f, %.3f] ms"
          % (len(gyro), rate, (gmin - t0) / 1e6, (gmax - t1) / 1e6))

    vendor = []
    standard = []
    for fr in frames:
        for s in fr.get("ois_samples") or []:
            if s.get("unit") == "hall_adc_uncalibrated":
                vendor.append(s)
            else:
                standard.append(s)
    if not vendor and not standard:
        raise GateFail("no OIS samples in any frame (standard and vendor both empty)")
    if vendor:
        xs = [s.get("hall_x", s.get("dx_px")) for s in vendor]
        ys = [s.get("hall_y", s.get("dy_px")) for s in vendor]
        try:
            sx = statistics.pstdev(xs)
            sy = statistics.pstdev(ys)
        except Exception as e:
            raise GateFail("vendor OIS variance failed: %s" % e)
        if sx <= 0.5 and sy <= 0.5:
            # A constant hall reading is correct while the device is at rest:
            # OIS has nothing to compensate. Only call it stale if the IMU
            # shows real angular motion at the same time.
            gyro = meta.get("imu_gyro_uncal") or []
            rate_std = 0.0
            for ax in ("wx", "wy", "wz"):
                xs = [g[ax] for g in gyro if isinstance(g.get(ax), (int, float))]
                if len(xs) > 1:
                    rate_std = max(rate_std, statistics.pstdev(xs))
            if rate_std < 0.01:
                print("  OIS vendor: %d samples, centered (std x=%.2f y=%.2f);"
                      " device stationary (gyro axis std %.4f rad/s)"
                      " - constant hall is physically expected"
                      % (len(vendor), sx, sy, rate_std))
            else:
                raise GateFail("vendor OIS samples do not vary"
                               " (std x=%.3f y=%.3f) while gyro moves"
                               " (%.4f rad/s)" % (sx, sy, rate_std))
        if all(v == 0 for v in xs + ys):
            raise GateFail("vendor OIS hall values are all zero")
        print("  OIS vendor: %d samples, hall std x=%.2f y=%.2f (raw ADC)"
              % (len(vendor), sx, sy))
    if standard:
        dx = [s["dx_px"] for s in standard if isinstance(s.get("dx_px"), (int, float))]
        if dx and statistics.pstdev(dx) > 0:
            print("  OIS standard: %d samples, dx std=%.3f px"
                  % (len(standard), statistics.pstdev(dx)))
    return True


def gate3(meta):
    tm = meta.get("timings") or {}
    total = tm.get("total_ms")
    tap = tm.get("tap_to_disk_ms")
    if not isinstance(total, (int, float)):
        raise GateFail("timings.total_ms missing")
    if not isinstance(tap, (int, float)):
        raise GateFail("timings.tap_to_disk_ms missing")
    print("  breakdown: setup=%.1f settle=%.1f capture=%.1f meta=%.1f"
          " tap_to_disk=%.1f total(incl. warm-up)=%.1f ms"
          % (tm.get("setup_ms", -1), tm.get("settle_ms", -1),
             tm.get("capture_ms", -1), tm.get("meta_ms", -1), tap, total))
    if tap >= 1200:
        raise GateFail("tap-to-disk %.1f ms >= 1200 ms" % tap)
    return True


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    base = sys.argv[1]
    with open(os.path.join(base, "meta.json"), "r", encoding="utf-8") as f:
        meta = json.load(f)
    print("burst:", base)
    print("frames:", meta.get("frames_count"), "error:", meta.get("error"))
    results = []
    for name, fn in (("GATE 1 distinct/size/RAW10", lambda: gate1(meta, base)),
                     ("GATE 2 timestamp/skew/IMU/OIS", lambda: gate2(meta)),
                     ("GATE 3 speed < 1.2 s", lambda: gate3(meta))):
        print("\n[%s]" % name)
        try:
            fn()
            results.append((name, "PASS", ""))
        except GateFail as e:
            print("  FAIL:", e)
            results.append((name, "FAIL", str(e)))
    print("\n==== summary ====")
    for name, st, err in results:
        print("%-38s %s %s" % (name, st, err))
    return 0 if all(r[1] == "PASS" for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
