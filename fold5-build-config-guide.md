# Fold5 (SM-F946B) GCam Build & Config Guide — Field Notes

Everything in this doc is **device-verified** on a Galaxy Z Fold 5 (SM-F946B, kalama SoC,
GN3 main sensor, One UI / SDK 36, KernelSU root) between 2026-09-05 and 2026-09-09.

## 1. Build survey: which GCam works on the Fold5?

| Build | Package | Device Interface picked | Verdict (evidence) |
| --- | --- | --- | --- |
| **AGC 9.2.14 V14.0 ruler** | `com.samsung.android.ruler` | **comodo** (Pixel 9 Pro XL profile) | ✅ **Best.** Full E2E: captures, all lenses, HDR+, our 50MP burst patch |
| AGC 9.6.19 V7.0 scan3d | `com.samsung.android.scan3d` | redfin (Pixel 5 profile) | ❌ FATAL at startup: `NullPointerException: com.google.googlex.gcam.Gcam.f()` (crash buffer, 2026-09-09 00:29) |
| LMC 9.6 Release 1 Fix2 Samsung | `com.samsung.android.scan3d` | — | ❌ FATAL: `IllegalArgumentException: No HDR+ compatible raw format supported` (2026-09-09 00:42) |
| MGC 9.7.047 V27 scan3d | `com.samsung.android.scan3d` | — | ❌ Silent self-exit (activity detaches ~1 s after launch; no crash logged) |

### Why "newer = GN3 support" turned out to be wrong

We opened every APK and inspected the actual native libs:

- `libagc.so` (AGC's config/tuning layer) contains the **same sensor-model table in
  9.2 and 9.6**: `IMX682, IMX355, IMX471, OV5675, S5KGW3, S5KHM2, IMX598, S5K3L6, S5KJN1,
  IMX766, GN1, GN2…` — **no GN3 profile in either**. The hex blobs that follow the names
  are identical between versions.
- The GN3 / `q5q` strings found inside `libgcastartup.so` of the 9.6/9.7-generation builds
  are embedded in compressed/obfuscated payloads of the **engine** (GCam 9.6's HDR+ core,
  avutil 58.29 / swscale 7.5 vs 9.2's 57.28 / 6.7) — they do not make the *mod* GN3-aware,
  and in practice the newer builds never got far enough to merge a single frame on this device.
- Device-interface selection: AGC 9.2 maps `q5q` (BOARD kalama, dev-ID 29) to **comodo**;
  AGC 9.6 logs `0 => q5q => redfin` and then dies in `Gcam.f()`. The comodo profile (Pixel 9
  Pro XL, also kalama) is the newest interface available in any of these mods.

**Conclusion:** AGC 9.2.14 ruler is not just the best option — it is the only one that runs.

## 2. The final config (`fold5_best.agc`)

Base: the user's own exported `fold5.agc` (working state, 742 entries) **plus three
surgical changes**. Everything else is the mod's own per-interface ("comodo") defaults —
`Off (as in library)` where applicable.

| Key | Old | New | Why (device evidence) |
| --- | --- | --- | --- |
| `pref_sensor_color_filter_key_0` | `3` (BGGR) | **`2` (GBRG)** | Probed via Camera2: `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT = 2` on all back cameras. A wrong CFA map swaps R/B in any processing that trusts it. |
| `pref_awb_key_0` | `Mi11U Main GN2` | **`0` (Auto)** | The GN3 RAW stream is already white-balanced by the HAL (our 50MP work: HAL JPEG of same scene measures R/G≈1.14, B/G≈1.19; applying borrowed GN2 gains double-corrects → warm cast R/G 1.22 in daylight photos). Stock Samsung: R/G 0.92. |
| `camera.eis_tr_supported` | `true` (imported) | **removed** | See §3 — this one key black-screened every lens. |

Also kept from the working state: `gcam.psaf_frame_count=3`, HDR+ `on`, ZSL 25 frames,
black levels 64.0 (correct: 256 in the 10-bit RAW = 64 in the 8-bit domain the mod uses),
`show_debug_data`/`show_af_data` ON (harmless, verified), `hardjpgquality 100`.

Lens selection set: `{56, 58, 52, 1, 3, 71, 73}` — all lenses incl. the three front cameras,
and **without dead lens 0** (logical camera 0 was removed by our v2 fix; a saved selection
containing id 0 makes the mod restore a lens that no longer exists).

## 3. Root causes found this session (worth keeping)

1. **Settings didn't save** — `shared_prefs` was owned by uid 10410 after a probe-swap
   restore, but the app runs as **u0_a417 (10417)**. Mode 644 + wrong owner = app can read
   but never write, and it silently resets. Fix: `chown -R 10417:10417`, `chmod 771` dir /
   `660` files. Verify with `stat -c %u` any time settings "forget".
2. **All-lens black viewfinder** — a `.agc` import wrote
   `camera.eis_tr_supported=true` into prefs. On the Samsung HAL this makes GCam set the
   Pixel vendor key `com.google.pixel.experimental2020.eisTrackRegion` on the session's
   queued repeating request → `IllegalArgumentException: Could not find tag for key…`
   (CAM_QReqProcessor) → **zero requests ever submitted** → black viewfinder on every
   lens and "Short service timeout" on every shot (`shot_log` stuck at `startEmpty`,
   never `onFramesRequested`). Removing the one key restores the pipeline instantly.
   **Never enable EIS-TR debug setting on this device.**
3. **"Stuck shot" pattern** — if captures hang and the shot DB shows
   `started → marked stuck → CANCELED` with no `onFramesRequested`, the repeating request
   died. Check `dumpsys media.camera` → `Request ID counter` (should be climbing) and
   logcat for `CAM_QReqProcessor`.
4. **Testing in the dark** — a pitch-black scene produces a live viewfinder that is ~0.1
   luma and frame-diffs of only a few thousand pixels; luma/frame-diff are **not** proof of
   a dead camera. Ground truth = does a capture persist? (`KEYCODE_CAMERA` goes to the
   stock camera on this ROM — tap the in-app shutter instead.)
5. **uiautomator wedge** — `uiautomator dump` returns `could not get idle state` whenever
   the GCam viewfinder is rendering (the SurfaceView floods the a11y tree). Dumps work
   when an overlay is open (e.g. the Options / Photo-settings panel) or the app is closed.

## 4. Install / reproduce

```sh
# APK: AGC9.2.14_V14.0_ruler_fold5fix_v4.1 (see releases) — includes 50MP burst button
adb install -r AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk
# Config: import fold5_best.agc via AGC Settings → Configs, or copy to prefs:
adb push fold5_best.agc /sdcard/Download/AGC.9.2/configs/
# (prefs path, if editing directly — force-stop the app first!)
# /data/data/com.samsung.android.ruler/shared_prefs/com.samsung.android.ruler_preferences.xml
```

The 50MP Expert RAW toggle (`pref_50mp_eraw_key_0`) gates only the viewfinder burst button
since v4.1; the normal shutter is always GCam's stock 12MP pipeline either way.

## v4.3 — Front camera fix (2026-09-09)

The Fold5 front cameras (ids 1, 3, 71, 73) are hwLevel LIMITED with **no RAW output at all**. Three independent gates kept the front camera dead on this device; all three are now patched in the v4.3 build:

### Root cause chain (verified end-to-end)
1. **Go-layer filter (libagc.so)**: `CalcFrontMainCamera` only admits a front camera to `CameraIDs` when `RawSizeW != 0`. The Java `getRawSizes()` queried RAW16 (0x20) only → null on all four front cams → the flip button never rendered (`GetFilteredCameraIDs 56,58,52,0`, no front ids).
   **Fix (patch 0004)**: `getRawSizes()` fallback chain RAW16 → RAW10 (0x25) → RAW12 (0x26) → YUV_420_888 (0x23) in BOTH copies (com/agc/Camera.smali + com/agc/CamerasFinder.smali — the latter feeds the real Camera construction). Result: `CameraId: 71 CameraRawSizes: [3648x2736, ...]`, `GetFilteredCameraIDs 56,58,52,71,0`, `SetCamera &{71 ... 3648 2736 ...}` — front main enters the switch cycle (`getSwitchCameraList [56, 71]`).
2. **Session builder exception**: the engine's format picker received `[RAW16]` (the mod's `IsSupportRAW10` check strips RAW10 for no-RAW cameras) → `jba.b()` threw `IllegalStateException: No supported output sizes found!!` → `CAM_iiu: Cancelling viewfinder due to createTransaction exception` → front session configured zero streams (Request ID counter stuck at 0).
   **Fix (patch 0005)**: `jba.b()` now falls back RAW10 → RAW12 → YUV_420_888 before throwing. The front session builds on YUV_420_888 (3648x2736 on id 71) — the same path the stock Samsung selfie camera uses. Request counter climbs, viewfinder streams.
3. **HDR+ per-lens matrix**: front cameras cannot complete the ZSL reprocessing merge in a LIMITED session (stuck shots: `marked stuck`, `onCaptureCanceled-API2_ZSL`). The mod's per-lens override keys (`pref_camera_hdr_plus_override_key_p0_<auxKey>` — the suffix is the index in the filtered lens list, front 71 = slot 3, 73 = slot 4) now ship `off` for front slots, `on` for all back slots (56/58/52). The mod copies the current lens's override into the global key at every lens switch, so back lenses always restore HDR+ automatically.

### Verified results (all in pitch-black, lenses covered — persistence is the test)
- Front switch button renders; state-aware tap loop reaches front 71 (`SetCameraID: 71`, `openCamera id=71`, Active client 71, Request ID counter 5+).
- **Front photo persisted**: AGC_20260909_191524558.jpg (3648x2736 = front main max YUV resolution, f/1.8, ISO 5743) and repeat captures (AGC_20260909_194933374.jpg, 8mm focal = front lens) — shot_db rows `failed=0 stuck=0 NORMAL`.
- **Back HDR+ regression-free**: multiple HDR_PLUS shots persisted during the same test window (17:47/18:29/18:30/19:21/19:22/19:48/19:54), `failed=0 stuck=0`.
- Cold-launch opens on back main 56, activity stays on top; force-stop/relaunch cycles clean; zero FATAL in crash buffer.

### Dark-frame findings (noise model / black level)
- The mod runs `Noise Model ID 0` = **AUTO** on every camera (see `Download/AGC.9.2/logs/_NM_*.txt`: Noise Model A/B/C/D all 0) — the engine derives per-shot noise coefficients from HAL sensor metadata. The exported as_/bs_/do_ noise-table keys are inert in this mode; no GN3 profile exists in any AGC build (proven by libagc inspection in the build survey).
- Black levels: `black_level_*=64.0` (8-bit domain) and `pref_black_level_key_0=6` (≈ 256/4095 as %) both match the probed GN3 10-bit pedestal of 256. Pitch-black captures at max auto-exposure (ISO 1600, f/1.8, 1/3s) come out pure 0.00 mean / 0.00 stdev — the pedestal subtraction works; nothing leaks through HDR+.
- Front-camera dark shots (ISO 5743) equally clean, no stuck shots.

### Config notes
- `pref_camera_id_list_key` = [56, 58, 71, 73, 52] — the two LIMITED front ultrawide/closeup ids (1, 3) stay OUT: opening them triggers `CAM_fuy: Camera Hardware failure -> Finishing activity` (the cold-launch hide bug). Front main 71 + front UW 73 are the working set.
- The per-aux ZSL frame-count keys (`lib_pref_frame_count_zsl_key_p0_N`) are set 0 (engine default) for non-main positions; main stays 25.
