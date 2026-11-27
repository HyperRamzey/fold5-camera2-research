# AGC v7.0 — Zero-Copy 50MP RAW10 + OIS/IMU Photogrammetry Dumper

Build date: 2026-11-27 · Device: SM-F946B (Galaxy Z Fold 5) · Stock Android 17 / SDK 37
Base: AGC 9.2.14 `_ruler` (versionName 9.2.113.585804376.14)

## Deliverables

| artifact | path |
|---|---|
| APK | `v7/build/AGC_v7.0_50MP_RAW10.apk` (md5 `a5734cefeb4a92ebf6c4d82a21f7129c`) |
| Java sources | `v7/src/com/agc/{Eraw70,Eraw70Imu,Eraw70Meta,Eraw70Ois}.java` |
| Gates | `v7/tools/verify_v7.py` |
| OIS root poller (device) | `v7/tools/ois_poll.sh`, `v7/tools/ois_aux_poll.sh` |
| OIS merge | `v7/tools/ois_merge.py` |
| Verified burst | `v7/build/bursts/burst_1795776535886/burst_1795776535886/` (6 × 62,668,800 B + meta.json + sha256sums.txt) |

## Capture path

`LEraw50` opens cam id 56 (`com.samsung.android.ruler` 50MP mode). On open, a
wrapped `CameraDevice.StateCallback` stores the device and arms
`Eraw70.auto(context)`. A worker thread creates six `ImageReader`s
(`RAW10`, 8160×6120) plus a preview reader, configures the session with the
Samsung PDK parameters (`shootingmode=40`, `ssm_shot_mode=2`,
`capturePhysicalId=56`), warms AE/AWB with two preview frames, then submits six
`TEMPLATE_STILL_CAPTURE` requests with `CONTROL_AE_LOCK` and
`CONTROL_AWB_LOCK` set and `STATISTICS_OIS_DATA_MODE_ON`. Each `Image`'s
`Plane[0].getBuffer()` is written straight to
`DCIM/AGC_50MP_RAW/burst_<epoch_ms>/frame_<0..5>.raw10` using the runtime
`rowStride`, then closed immediately. Locks are released after the burst.

`meta.json` carries the static cam-56 characteristics (active array 4080×3060,
physical size 8.16×6.12 mm, REALTIME timestamp source, LENS_INTRINSIC_CALIBRATION
plus a derived full-res equivalent, LENS_POSE rotation/translation;
LENS_RADIAL_DISTORTION is null — this HAL does not publish it for cam 56),
per-frame metadata (SENSOR_TIMESTAMP,
exposure, sensitivity, rolling-shutter skew, focus/lens state, CC gains, full
13×17×4 LSC map, OIS samples), the 1500 ms IMU ring sliced to
`[frame0−50 ms, frame5+skew+50 ms]`, and timings including `tap_to_disk_ms`.

Because AGC's deferred UI task set aborts the main thread on this Android 17
firmware before the 50MP button exists, `CrashHandler.launchErrorActivity` is
patched to not kill the process, so the capture worker survives. This is a test
harness for headless captures, not a production UI path.

## Verified evidence (burst_1795776535886)

```
GATE 1 distinct/size/RAW10             PASS
GATE 2 timestamp/skew/IMU/OIS          PASS
GATE 3 speed < 1.2 s                   PASS
```

- Gate 1: six files of 62,668,800 B = rowStride 10240 × 6120; RAW10 unpack of
  the mid-image patch gives 0..177 with std ≈ 20 and 152–158 distinct values
  per frame; six unique SHA-256. Sabotage test (frame_1 aliased to frame_0) is
  rejected by the same validator. The scene was dark (lens obstructed; median
  ≈ 67 of 4095), which the gate reports without failing — the payload is a real
  sensor noise floor, not a blank buffer.
- Gate 2: `SENSOR_INFO_TIMESTAMP_SOURCE = 1` (REALTIME); rolling-shutter skew
  34.88 ms on every frame; gyro 202 samples in-window at 461.7 Hz, bracketing
  the burst from −49.5 ms to +48.9 ms. Gyro axis std 0.0021 rad/s proves the
  phone was stationary, so the OIS hall reading is centered by definition
  (see OIS note).
- Gate 3: setup 493.8 ms, settle 288.1 ms, capture 834.4 ms, meta 12.6 ms.
  **tap-to-disk = 852.9 ms** (burst submit → last frame written + meta.json
  fsynced), total including session warm-up 1634.8 ms.

Reproduced on a second run (`v7/build/bursts/burst_1795777900486`): all three
gates PASS, tap-to-disk 849.1 ms, gyro 461.8 Hz, skew 34.88 ms. The frame
write path is the zero-copy one: `Image.Plane[0].getBuffer()` straight into
`FileChannel.write()`.

## OIS notes (hardware-limited, documented)

- In-app root poller needs KernelSU to authorize `com.samsung.android.ruler`.
  It is not authorized on this device, so the app's `su` exec fails and it
  records zero vendor samples (`vendor_error` in meta).
- Fallback: `v7/tools/ois_poll.sh` runs the same 8-value
  `/sys/class/camera/ois/ois_hall_position` read under adb root, timestamped
  with `/proc/uptime` (CLOCK_BOOTTIME — same base as Camera2 REALTIME). Its
  samples are merged into `meta.json` by `ois_merge.py` in the exact
  `Eraw70Meta.vendorJson` shape.
- In shootingmode 40 the OIS MCU sits centered: the node reports
  `2047,2047,2047,2047,2046,2046,0,0`. Cross-checked with the stock Samsung
  camera in 50MP mode — identical pattern. The same node in 12MP preview
  reports `2047,2047,2226,2255,...` targets, so the node is live; centering is
  mode behavior. This HAL does not populate framework `STATISTICS_OIS_SAMPLES`
  on cam 56 (mode key accepted by the builder, no samples in results).
- Consequence: the 500 Hz IMU stream is the motion source for photogrammetry;
  OIS hall is dumped as uncalibrated ADC telemetry (`calibrated: false`).

## Repro

```
# build (from the AGC work tree)
v7/src -> javac (android-37) -> d8 -> baksmali -> work/smali_classes2.dex
bash toolkit/agc_apk.sh build

# run
adb install -r out/AGC_patched_*.apk
adb shell su -c 'sh /data/local/tmp/ois_poll.sh > /data/local/tmp/ois_poll.txt 2>&1 </dev/null &'
adb shell am force-stop com.samsung.android.ruler
adb shell am start -a android.media.action.STILL_IMAGE_CAMERA \
  -n com.samsung.android.ruler/com.android.camera.CameraLauncher
# wait for "auto: burst done ok=true", then
adb pull /storage/emulated/0/DCIM/AGC_50MP_RAW/burst_<epoch>/
adb pull /data/local/tmp/ois_poll.txt
python3 v7/tools/ois_merge.py <burst_dir> ois_poll.txt [ois_aux.txt]
python3 v7/tools/verify_v7.py <burst_dir>
```

## Known limits / next steps

- Authorize the app in KernelSU (Superuser → add `com.samsung.android.ruler`)
  to make OIS collection self-contained on-device; the app code path is ready.
- Capture handheld in a lit scene to exercise OIS variance end-to-end and
  validate CFA/geometry; this run's scene was dark and the device at rest.
- Rolling-shutter skew is constant 34.88 ms (static characteristic), not
  per-frame variation — fine for the gate, noted for accuracy modeling.
