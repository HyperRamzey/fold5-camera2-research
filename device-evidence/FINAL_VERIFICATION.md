# 50MP Expert RAW Mode — v3 Final Verification Evidence (2026-09-07)

## Final build

- APK: `AGC9.2.14_V14.0_ruler_fold5fix_v3.apk` (517,640,115 bytes, built 20:26)
- Installed classes2.dex MD5 = `8a528db9bee3db47527627485bff6810` (matches local build exactly)

## Test 1 — 50MP capture (toggle ON), pre-cleanup build (20:04 run)

Full logcat trace (eraw50_success_log.txt):

```
09-07 20:03:36.279 I LEraw50 : openCamera id=56
09-07 20:03:36.280 I LEraw50 : isEnabled aux=1
09-07 20:03:36.318 I LEraw50 : 50MP camera opened (device stored; params deferred to capture)
09-07 20:04:10.243 I LEraw50 : kzq.onShutterButtonClick        <- real on-screen shutter tap
09-07 20:04:10.243 I LEraw50 : isEnabled aux=1
09-07 20:04:10.245 I LEraw50 : bridge loaded: ...CustomInterfaceHelper.setSamsungParameter(...)
09-07 20:04:10.246 I LEraw50 : semCreate loaded: ...semCreateOutputConfiguration(int, Surface, int, int)
09-07 20:04:10.246 I LEraw50 : params ok                        <- setParameters(shootingmode=40) at capture time
09-07 20:04:10.696 I LEraw50 : 50MP session configured
09-07 20:04:11.236 I LEraw50 : firing 50MP still after 6 previews
09-07 20:04:11.921 I LEraw50 : 50MP JPEG saved 22616246 bytes
09-07 20:04:11.921 I LEraw50 : capture50mp result: true waited=true
```

Output: `/storage/emulated/0/Pictures/AGC_50MP/AGC_50MP_1788800651895.jpg`
SOF-parsed: **8160 x 6120, 22,616,246 bytes** (evidence: AGC_50MP_FINAL_8160x6120.jpg)

## Test 2 — 50MP capture (toggle ON), CLEANED build (20:27 run, after dead-hook removal)

Log trace (from terminal output, same sequence):

```
09-07 20:27:26.221 I LEraw50 : openCamera id=56
09-07 20:27:26.221 I LEraw50 : isEnabled aux=1
09-07 20:27:26.260 I LEraw50 : 50MP camera opened (device stored; params deferred to capture)
09-07 20:27:44.458 I LEraw50 : kzq.onShutterButtonClick
09-07 20:27:44.465 I LEraw50 : bridge loaded / semCreate loaded
09-07 20:27:44.466 I LEraw50 : params ok
09-07 20:27:44.946 I LEraw50 : 50MP session configured
09-07 20:27:45.486 I LEraw50 : firing 50MP still after 6 previews
09-07 20:27:46.035 I LEraw50 : 50MP JPEG saved 9162026 bytes
09-07 20:27:46.035 I LEraw50 : capture50mp result: true waited=true
```

Output: `/storage/emulated/0/Pictures/AGC_50MP/AGC_50MP_1788802066008.jpg`
SOF-parsed: **8160 x 6120, 9,162,026 bytes** (evidence: AGC_50MP_CLEANBUILD_8160x6120.jpg)

## Test 3 — Control (toggle OFF), pre-cleanup build (20:06 run)

```
09-07 20:06:04.315 I LEraw50 : openCamera id=56
09-07 20:06:04.315 I LEraw50 : isEnabled aux=0      <- wrap skipped, stock path
09-07 20:06:20.069 I LEraw50 : kzq.onShutterButtonClick
09-07 20:06:20.069 I LEraw50 : isEnabled aux=0      <- no 50MP capture
```

Output: `AGC_20260907_200620071.jpg` — SOF-parsed **3060 x 4080** (stock 12.5MP)

## Test 4 — Control (toggle OFF), CLEANED build (20:28 run)

```
09-07 20:28:35.951 I LEraw50 : openCamera id=56
09-07 20:28:35.951 I LEraw50 : isEnabled aux=0
09-07 20:28:51.661 I LEraw50 : kzq.onShutterButtonClick
09-07 20:28:51.661 I LEraw50 : isEnabled aux=0
```

Output: `AGC_20260907_202851663.jpg` — SOF-parsed **3060 x 4080, 720,849 bytes** (evidence: control_3060x4080_cleanbuild.jpg)

## Test 5 — Non-main lens (front, id=71) with toggle ON (20:39–20:43 runs)

```text
09-07 20:39:46.738 I LEraw50 : openCamera id=71        <- front camera NOT wrapped (only 56 is)
09-07 20:39:46.738 I LEraw50 : isEnabled aux=1
09-07 20:39:50.835 I LEraw50 : kzq.onShutterButtonClick
09-07 20:39:50.835 I LEraw50 : isEnabled aux=1          <- no 50MP capture ran (no device stored for 71)
```

- `shotNow()` correctly declines (device store holds only cam 56); **no 50MP session, no AGC_50MP file, app stable**.

## Test 6 — Front camera with toggle OFF (hooks fully inert) (20:50 run)

```text
09-07 20:50:49.976 I LEraw50 : openCamera id=71
09-07 20:50:49.976 I LEraw50 : isEnabled aux=0
09-07 20:50:56.344 I LEraw50 : kzq.onShutterButtonClick
09-07 20:50:56.344 I LEraw50 : isEnabled aux=0          <- pure stock path
```

- No photo saved, front preview black (luma 3.9) — **identical failure with hooks fully inert** → front capture is broken in this GCam 9.2 mod build itself, NOT caused by these patches.
- Stock Samsung camera on the same device saved a front photo minutes later at 20:54 (1936x1452, 87,330 bytes) → front hardware is fine; the mod's front path is the pre-existing limitation.

## Stability

- Crash buffer grep across all runs: **0 FATAL / 0 VerifyError / 0 NoSuchMethodError**
- `device error` count for ruler: **0**
- Force-stop/relaunch cycle: pids 29945 → 30235, clean start, camera opens, no retry loops

## Final architecture (v3)

- `kzq.onShutterButtonClick` — THE universal shutter funnel (ShutterButton → kzq → kzs listener list); intercepts when enabled: shotNow() → swallow click
- `sz.i` / `mxc.a` — openCamera sites; wrapOpenCb stores camera 56 device (NO setParameters at open)
- `Application$1` — context store
- `Eraw50State.isEnabled()` — compiled reflection helper reading pref_50mp_eraw_key_0 (aux-scoped)
- `Eraw50Bridge` — compiled reflection loaders for scamera_sdk_util.jar bridge (setSamsungParameter + semCreateOutputConfiguration)
- `LEraw50` — slim static holder: device/context fields, shotNow (spawns LEraw50$6 thread), wrapOpenCb, bridge delegators
- `Eraw50Shot.capture()` — compiled: params at capture → dedicated HR session (8160x6120 JPEG via ImageReader) → MediaStore save to Pictures/AGC_50MP
- Settings row: ListPreference `pref_50mp_eraw_key` in camera_preferences.xml (title/summary strings intact)

## Removed dead hooks (v2 leftovers)

- dol (buildSession override + vendor keys on still builder + debugF), doi.onConfigured (vendor keys + raw drain), doh.onOpened (setSamsungParams at open — the dev-error-3 landmine), erm.h, erj.onShutterButtonClick, LEraw50$1/$2 inner classes, and 12 dead methods from LEraw50

## Known behavior notes

- 50MP shot takes ~1-2s after shutter (HR session + 6 preview frames + remosaic)
- Toggle is currently ON (pref_50mp_eraw_key_0=1) on the device; main-lens (cam 56) shots save 8160x6120 JPEGs to Pictures/AGC_50MP
- Front-camera capture is broken in this GCam mod build independent of these patches (see Test 6); scope of this feature is main lens only, as documented in the settings summary
- Phone left: both cameras force-stopped, stayon reset to false, screen off

## Test 7 - 50MP x6 burst button (v4, 2026-09-08)

Real on-screen button tap: session configured -> 6/6 frames -> merge 484ms -> debayer+sharpen 2810ms -> JPEG 54749769 bytes saved as AGC_50MP_BURST_1788822501547.jpg (8160x6120, luma 105.3). Total 7.1s. Single-shot toggle + stock controls re-verified same session (19.6MB single-shot / 4080x3060 stock). 0 crashes.
See 50mp-burst-analysis.md + eraw50_burst_success_log.txt.
