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
