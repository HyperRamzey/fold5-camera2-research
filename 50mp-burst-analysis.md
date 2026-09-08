# 50MP Six-Frame Burst Mode — Implementation & Findings (v4)

Adds a **"50MP" button to the GCam viewfinder** (styled like the mod's existing oval
buttons, `camera_switch_button_background`) that fires a **6-frame 8160×6120 burst** with
in-app temporal merge. As of **v4.1** it is the ONLY 50MP entry point — the normal shutter
always runs GCam's stock 12MP pipeline, and the button is gated on the
**50MP Expert RAW Mode** settings toggle (Settings → Lens Setting → Main).

Status: **WORKING end-to-end** (v4.1, 2026-09-08). A real on-screen button tap produces a
neutral-color merged 8160×6120 JPEG (~35 MB) in ~5–9 s, the viewfinder recovers immediately,
and the stock shutter path is untouched. Full log:
`device-evidence/eraw50_v4.1_final_log.txt`; sample output:
`device-evidence/AGC_50MP_BURST_v4.1_sample_2048px.jpg`.

## v4.1 fixes (user-reported issues)

1. **Magenta/pink cast → fixed with device-probed color science.** Probe
   (`probe/burstapk` metadata dump): GN3 CFA is **GBRG** (`SENSOR_INFO_COLOR_FILTER_ARRANGEMENT=2`),
   black level **256**, white level **4095** (10-bit). v4 assumed RGGB / BL=64 / K=0.25,
   which swapped R↔B and crushed blacks. Additionally, the HighResolution RAW10 stream turns
   out to be **pre-white-balanced by the HAL** (the v3 single-shot JPEG of the same scene
   measures R/G≈1.14, B/G≈1.19 — near neutral), so the debayer applies **unity WB gains**;
   the HAL-reported `COLOR_CORRECTION_GAINS` (e.g. R=1.13/B=2.23) describe the ISP stage and
   double-correct if applied (measured R/G=1.37, B/G=2.15 with them). Verified result:
   **R/G=1.01, B/G=1.12** on the merged output — matches the HAL's own JPEG.
2. **Viewfinder freeze → fixed.** The burst must close the shared cam-56 device when done
   (GCam's Expert-RAW session can't be rebuilt passively — `dumpsys media.camera` shows
   **no re-CONNECT after `dev.close()`**), so `fire()` restarts the CameraActivity
   (CLEAR_TOP) after the save. The viewfinder, wrapOpenCb device store and the 50MP button
   all rebuild; verified with a 2-screenshot frame-diff test (499 sampled diffs = live).
3. **Button placement.** The `bottom_bar` container is a custom ViewGroup that ignores
   `FrameLayout` margins and **clips children** — a translation-based lift made the button
   vanish entirely. v4.1 attaches the button to the window's root DecorView with absolute
   coordinates: ~25% of screen height above the old slot, same right column — clear of the
   shutter, camera-switch oval and zoom chips (bounds [2019,337][2136,454] in landscape).
4. **Shutter = stock 12MP always.** The `kzq.onShutterButtonClick` funnel hook and the
   dead single-shot chain (`Eraw50Shot*`, `LEraw50$6`, `shotNow`, bridge delegators) are
   removed; `LEraw50` retains only the context/device store + `wrapOpenCb`. Verified:
   shutter tap with the toggle ON saves a stock 4080×3060 JPEG, zero `LEraw50` intercept
   lines; the toggle now gates **button visibility only**.

## Why only 6 frames (and no exposure bracket)

Device-proven HAL budget on the Fold5 (SM-F946B, F946BXXS7GZE5):

1. **~6 in-flight RAW10 buffers max.** The `SecHighReslolutionRdiBufferManager` allocates a
   fixed pool; a `captureBurst` of 6 fills it, and the 7th request dies with
   `GetImageBuffer() Waited 3x800 times and failed` → `dev onError 4` → `CAMERA_ERROR`.
   Probes with 25 stills: first 6 complete, then the device is killed
   (`probe/ErawBurstProbe.java`).
2. **Per-request manual exposure is HAL-overridden.** Frames submitted with
   `CONTROL_AE_MODE=OFF` + `SENSOR_EXPOSURE_TIME`/`SENSITIVITY` ramps still return with the
   AE-chosen values (`exp=58823529 iso=8000` on every frame; `aeState=1`). The Samsung
   HighResolution usecase owns exposure — so all frames share one AE.
   → The burst gives **temporal merge** (noise/hot-pixel reduction), **not** HDR bracketing.
   True 50MP HDR would require feeding the RAW10 frames into GCam's own
   `libgcastartup` HDR+ engine (native RAW10 support exists there:
   `"Only RAW10 buffers can be processed"`, `AlignBurst`, `nativeAddPayloadFrame`),
   which is a separate, much larger integration project.
3. **The repeating preview must stay alive during the burst.** Stopping it
   (`stopRepeating`) kills the session within ~10 frames (`PREVIEW FAILED r=0` ×N → onError 4).
4. **Never open a second camera while GCam runs.** `openCamera("0")` from inside the app
   collides with GCam's live session → `ERROR_CAMERA_IN_USE` (dev error 2). The burst must
   run on the **cam-56 device GCam already opened** (stored at `openCamera` time by the
   `wrapOpenCb` hook).

## Architecture (all new logic compiled Java → d8 → smali; only 1-line smali hooks)

- `com.agc.Eraw50UI` — adds the viewfinder button from the `kzs` (shutter controller) ctor,
  styled with the app's own `camera_switch_button_background` drawable (52 dp oval,
  translucent black + white ring, white bold "50MP" text). Since v4.1 it attaches to the
  window DecorView at absolute screen coordinates (the bottom_bar ViewGroup ignores margins
  and clips translated children). Tap → busy-guarded toast → background thread →
  **CameraActivity restart** after the save so the viewfinder rebuilds.
- `com.agc.Eraw50Burst` — the capture+process pipeline:
  1. PDK params (`shootingmode=40;ssm_shot_mode=2;…`) via the `scamera_sdk_util.jar`
     bridge on the stored cam-56 device
  2. `semCreateOutputConfiguration` streams: RAW10 8160×6120 (option 2) + 1280×960 preview
     (option 1); session-parameter preview request carries the Samsung vendor keys
  3. repeating preview left running; 6 preview completions for 3A/FastAEC settle
  4. `captureBurst` of 6 STILL requests (vendor keys), `acquireNextImage` so none drop
  5. **merge**: per-byte robust average (drop min+max, mean of the rest) across frames
  6. **debayer + sharpen**: band-streamed (384-row bands, ~12 MB scratch) bilinear **GBRG**
     debayer with probed black level 256 / white 4095 and **unity WB gains** (the stream is
     pre-WB), fused 3×3 unsharp mask, written straight into one ARGB bitmap — peak heap
     ≈ 200 MB (largeHeap app)
  7. JPEG q95 → MediaStore → `Pictures/AGC_50MP/AGC_50MP_BURST_<ts>.jpg`
- `com.agc.Eraw50IO` — MediaStore saver.
- Smali hook surface (v4.1): `kzs.<init>` → `Eraw50UI.install` (signature
  `Landroid/view/View;` — d8 erases param types) and `sz.i`/`mxc.a` → `wrapOpenCb`
  (device store). The `kzq.onShutterButtonClick` funnel hook was **removed** in v4.1 —
  the shutter is always GCam's own pipeline now.

## Measured performance (device, v4.1 release build)

- Session config ~0.5 s, 6 previews ~0.6 s, 6 frames captured in ~0.4 s
- Merge: ~0.3 s (multithreaded over 6 × 62.7 MB)
- Debayer+sharpen: ~2.2 s (band-streamed)
- JPEG encode (q95): ~0.8 s
- **Total: ~5.3 s**, output 32.7 MB, 8160×6120, channel balance R/G=1.01 B/G=1.12
  (matches the HAL's own 50MP JPEG of a similar scene)
- Viewfinder recovery: immediate (activity restart); frame-diff verified live

## Lessons for anyone extending this

- 25-frame HDR+ at 50MP is not reachable through the Samsung HighResolution usecase
  (AE override + 6-buffer pool). The paths that remain: (a) feed frames to libgcastartup's
  own burst engine, (b) accept 6-frame temporal merge (this implementation), or
  (c) sequential multi-session capture (each session re-pays ~1.5 s setup).
- The OOM wall: app heap growth limit is 512 MB; naive full-image `int[]` debayer
  (~200 MB) on top of the 376 MB frame set OOMs. Free frames before processing, then
  band-stream (this is what v1 of the burst code hit — `Failed to allocate a 199756816 byte
  allocation`).
- First-run mod popups ("Tap to use Lut", the AGC side menu) intercept UI automation taps;
  always re-dump the hierarchy and dismiss by real bounds before driving the viewfinder.
