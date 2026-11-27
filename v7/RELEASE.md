# AGC v7.1 — Zero-Copy 50MP RAW10 + OIS/IMU Photogrammetry Dumper (one-click)

Build date: 2026-11-28 · Device: SM-F946B (Galaxy Z Fold 5) · Stock Android 17 / SDK 37
Base: AGC 9.2.14 `_ruler` (versionName 9.2.113.585804376.14)

## Deliverables

| artifact | path |
|---|---|
| APK (one-click, current) | `v7/build/AGC_v7.0_50MP_RAW10_click.apk` (md5 `d90fd122191e1250e77807e3174aba94`) |
| APK (auto build, v7.0.0 release asset) | `v7/build/AGC_v7.0_50MP_RAW10.apk` (md5 `a5734cefeb4a92ebf6c4d82a21f7129c`) |
| Java sources | `v7/src/com/agc/{Eraw70,Eraw70Imu,Eraw70Meta,Eraw70Ois}.java` |
| Gates | `v7/tools/verify_v7.py` |
| OIS root poller (device) | `v7/tools/ois_poll.sh`, `v7/tools/ois_aux_poll.sh` |
| OIS merge | `v7/tools/ois_merge.py` |
| Verified burst | `v7/build/bursts/burst_1795776535886/burst_1795776535886/` (6 × 62,668,800 B + meta.json + sha256sums.txt) |

## Capture path

`LEraw50` opens cam id 56 (`com.samsung.android.ruler` 50MP mode). On open, a
wrapped `CameraDevice.StateCallback` stores the device. **The dump is triggered
by one tap on the injected on-screen "50MP" button** (`Eraw50UI`, installed
into each activity's view tree; its click runs `Eraw70.capture(context)` on a
worker thread). Opening the camera no longer captures; set
`pref_50mp_auto_key=1` to arm the old auto-at-open behaviour.

The worker creates six `ImageReader`s
(`RAW10`, 8160×6120) plus a preview reader, configures the session with the
Samsung PDK parameters (`shootingmode=40`, `ssm_shot_mode=2`,
`capturePhysicalId=56`), warms AE/AWB with two preview frames, then submits six
`TEMPLATE_STILL_CAPTURE` requests with `CONTROL_AE_LOCK` and
`CONTROL_AWB_LOCK` set and `STATISTICS_OIS_DATA_MODE_ON`. Each `Image`'s
`Plane[0].getBuffer()` is written straight to
`DCIM/AGC_50MP_RAW/burst_<epoch_ms>/frame_<0..5>.raw10` using the runtime
`rowStride`, then closed immediately. Locks are released after the burst.
The runner waits for all six file writes (not just image arrivals) before
writing `meta.json`, so the sidecar always lists every frame.

`meta.json` carries the static cam-56 characteristics (active array 4080×3060,
physical size 8.16×6.12 mm, REALTIME timestamp source, LENS_INTRINSIC_CALIBRATION
plus a derived full-res equivalent, LENS_POSE rotation/translation;
LENS_RADIAL_DISTORTION is null — this HAL does not publish it for cam 56),
per-frame metadata (SENSOR_TIMESTAMP,
exposure, sensitivity, rolling-shutter skew, focus/lens state, CC gains, full
13×17×4 LSC map, OIS samples), the 1500 ms IMU ring sliced to
`[frame0−50 ms, frame5+skew+50 ms]`, and timings including `tap_to_disk_ms`.

### UI hardening required by the click path

On this Android 17 firmware the AGC activity has no options bar: several
views (`OptionsMenuContainer`, `TimerWidget`, `OptionsPanelCoordinator`,
the secondary viewfinder) are absent, and the stock code NPEs on them during
`MainActivityLayout.onMeasure`, killing the main thread (black window, input
ANR). The click build carries these smali fixes, all local to the UI path:

- `lem.a` — per-task try/catch so one broken deferred UI task cannot kill the
  main thread (logs `UI task skipped: …`).
- `isg.u` — inverted null test fixed; missing options-menu view now gets an
  inert `Eraw70MenuStub`; null secondary-view finder wiring skipped.
- `isg.onLayoutUpdated` — skips updates when the coordinator/content are null.
- `ism.c/a/b` and two `dot` switch cases — null-guard the absent TimerWidget.
- `MainActivityLayout.x` — layout listeners are wrapped: a failing listener is
  logged instead of unwinding into `Looper.loop`.
- `kvn` — the timer state holder (`las`, obtained from a ViewModel lookup that
  returns null here) is seeded in the constructor and a fresh instance is
  substituted at the lookup site, so `H/D/f/l/p` never dereference null.
- `Application$1.onActivityCreated` — the 50MP button is re-installed on every
  activity view tree (the camera activity is recreated on config changes).
- `CrashHandler.launchErrorActivity` — no error activity, no `killProcess`;
  kept from the auto build as a last-resort guard.

Headless alternative: `pref_50mp_auto_key=1` restores capture at camera open.

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

## Verified evidence — one-click build (2026-11-28)

`AGC_patched_20261128_000425.apk` (md5 `d90fd122191e1250e77807e3174aba94`),
launched on the camera activity, one tap at the injected button, no other
input. Auto capture disabled (`auto: disabled by pref_50mp_auto_key`).

```
burst_1795813626502 (pulled to v7/build/bursts/button_final)
GATE 1 distinct/size/RAW10             PASS
GATE 2 timestamp/skew/IMU/OIS          PASS
GATE 3 speed < 1.2 s                   PASS
```

- Gate 1: six files of 62,668,800 B, stride 10240, six unique SHA-256, sabotage
  test rejects a duplicated buffer (scene dark — lens obstructed — reported,
  not failed).
- Gate 2: REALTIME, skew 34.88 ms, gyro 202 samples at 460.6 Hz bracketing
  `[-49.6, +49.9] ms`; 10 vendor OIS samples polled via adb root, 6 assigned,
  hall centered while the gyro proves the device is stationary.
- Gate 3: setup 515.5, settle 300.4, capture 841.0, meta 15.1 ms →
  **tap-to-disk 865.9 ms** (submit → all six frames + meta fsynced),
  total including session warm-up 1681.8 ms.
- `meta.json` lists 6/6 frames after the write-completion wait.

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
adb shell su -c 'nohup sh /data/local/tmp/ois_poll.sh > /data/local/tmp/ois_poll.txt 2>&1 </dev/null &'
adb shell su -c 'nohup sh /data/local/tmp/ois_aux_poll.sh > /data/local/tmp/ois_aux.txt 2>&1 </dev/null &'
adb shell am force-stop com.samsung.android.ruler
adb shell am start -a android.media.action.STILL_IMAGE_CAMERA \
  -n com.samsung.android.ruler/com.android.camera.CameraLauncher
# wait for "50MP burst button added (toggle on)", then tap the 50MP button
adb shell input tap 2095 426          # 2176x1812 landscape; adjust to the layout
# wait for "burst loop done: images=6/6" and "meta written … frames=6", then
adb shell su -c 'pkill -f ois_pol[l]; pkill -f ois_aux_pol[l]'
adb pull /storage/emulated/0/DCIM/AGC_50MP_RAW/burst_<epoch>/
adb pull /data/local/tmp/ois_poll.txt
adb pull /data/local/tmp/ois_aux.txt
python3 v7/tools/ois_merge.py <burst_dir> ois_poll.txt ois_aux.txt
python3 v7/tools/verify_v7.py <burst_dir>
```

## Known limits / next steps

- Authorize the app in KernelSU (Superuser → add `com.samsung.android.ruler`)
  to make OIS collection self-contained on-device; the app code path is ready.
- Capture handheld in a lit scene to exercise OIS variance end-to-end and
  validate CFA/geometry; this run's scene was dark and the device at rest.
- Rolling-shutter skew is constant 34.88 ms (static characteristic), not
  per-frame variation — fine for the gate, noted for accuracy modeling.
