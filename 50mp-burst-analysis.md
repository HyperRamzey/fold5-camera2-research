# 50MP Six-Frame Burst Mode — Implementation & Findings (v4)

Adds a **"50MP" button to the GCam viewfinder bottom bar** (styled like the mod's existing
oval buttons, `camera_switch_button_background`) that fires a **6-frame 8160×6120 burst** with
in-app temporal merge — on top of the single-shot "50MP Expert RAW Mode" settings toggle from v3.

Status: **WORKING end-to-end** (2026-09-08). A real on-screen button tap produces a merged
8160×6120 JPEG (~54 MB) in about 7 seconds. Full log: `device-evidence/eraw50_final_log.txt`
section below; sample output: `device-evidence/AGC_50MP_BURST_sample_2048px.jpg`.

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
  translucent black + white ring, white bold "50MP" text). Tap → busy-guarded toast →
  background thread.
- `com.agc.Eraw50Burst` — the capture+process pipeline:
  1. PDK params (`shootingmode=40;ssm_shot_mode=2;…`) via the `scamera_sdk_util.jar`
     bridge on the stored cam-56 device
  2. `semCreateOutputConfiguration` streams: RAW10 8160×6120 (option 2) + 1280×960 preview
     (option 1); session-parameter preview request carries the Samsung vendor keys
  3. repeating preview left running; 6 preview completions for 3A/FastAEC settle
  4. `captureBurst` of 6 STILL requests (vendor keys), `acquireNextImage` so none drop
  5. **merge**: per-byte robust average (drop min+max, mean of the rest) across frames
  6. **debayer + sharpen**: band-streamed (384-row bands, ~12 MB scratch) bilinear RGGB
     debayer with black-level 64 and WB gains 1.65/1.45, fused 3×3 unsharp mask,
     written straight into one ARGB bitmap — peak heap ≈ 200 MB (largeHeap app)
  7. JPEG q95 → MediaStore → `Pictures/AGC_50MP/AGC_50MP_BURST_<ts>.jpg`
- `com.agc.Eraw50IO` — MediaStore saver.
- Smali hook surface (unchanged from v3 plus one line): `kzs.<init>` → `Eraw50UI.install`
  (signature `Landroid/view/View;` — d8 erases param types), `kzq.onShutterButtonClick`
  (single-shot funnel), `sz.i`/`mxc.a` → `wrapOpenCb` (device store).

## Measured performance (device, release build)

- Session config ~0.5 s, 6 previews ~0.6 s, 6 frames captured in ~0.4 s
- Merge: ~0.5 s (multithreaded over 6 × 62.7 MB)
- Debayer+sharpen: ~2.8 s (band-streamed)
- JPEG encode (q95): ~1.8 s
- **Total: ~7.1 s**, output 54.7 MB, 8160×6120, scene luma ~105 (verified real content)

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
