# Galaxy Z Fold 5 (SM-F946B) — Camera2 Capability Gating: Root Cause & Magisk/KSU Feasibility Report

Date: 2026-09-06
Research folder: `G:/projects/fold5-camera2-research`
Device: SM-F946B, q5q (qcom/kalama), Android 16, One UI 8, build BP4A.251205.006.F946BXXS7GZE5, sales code EUX
Root: KernelSU (temporary root, `su` context `u:r:ksu:s0`), **locked bootloader** — zero system-partition writes performed during this research.

---

## Executive summary

1. **Root cause found and proven.** All 14 camera devices exist and are healthy in the HAL. Only the
   logical back+front devices (public IDs 0/1/2→3) are shown to normal apps because Samsung's
   **framework-level `PackagePolicyList`** in the native `cameraserver` daemon rejects `connect()`
   for hidden IDs (`isHiddenIdPermittedPackage()`) unless the calling **package name** is on a
   hardcoded allowlist. The classic AOSP `vendor.camera.aux.packagelist` prop gate was **removed**
   by Samsung in this Android 16 build — the prop still exists in `/system/build.prop` but nothing
   reads it (verified via full string scan of `cameraserver`).

2. **A Magisk/KSU "fix module" for other packages is NOT feasible** — the gate is native C++ code in
   a system daemon with a binary-embedded allowlist; there is no prop, file, or Java hook surface
   (Zygisk/LSPosed can't reach it). Details in `ida-analysis/cameraserver-gate-analysis.md`.

3. **But no module is needed.** The allowlist includes `com.samsung.android.ruler` (and ~25 more
   packages — the exact mechanism GCam modders exploit with `_ruler` builds). Spoofing the package
   name unlocks **everything**: verified live — full 14-ID enumeration, opening the Expert RAW main
   lens (56, LEVEL_3), the front (71), and the tele (52) / UW (58) aux IDs via in-app zoom switching,
   with captures off both the main (4080×3060) and tele (3648×2736) sensors.

4. **AGC 9.2 (BigKaka) works on the Fold 5 after two 3-line-class smali fixes** (see §4), including a
   successful 4080×3060 photo through hidden lens 56. This doubles as the proof that no
   Magisk module is required to "fix" Camera2 access.

---

## 1. Evidence: the device has far more cameras than Camera2 reports

From `device-evidence/dumpsys_media_camera.txt` (full dumpsys media.camera):

- Camera provider HAL (AIDL, Samsung `sec-camera-provider` + camx/unihal): **14 devices** —
  `0, 1, 2, 3, 4, 20, 21, 23, 52, 56, 58, 71, 73, 90`
- CameraService framework state: `Number of camera devices: 4`, `Number of public camera
  devices visible to API1: 3` — `Device 0→"0"`, `Device 1→"1"`, `Device 2→"3"` (a 4th is
  virtual/vision).
- Hidden devices seen opening **by Samsung's own apps** in the CameraService event log:
  - `CONNECT device 20 client for package com.sec.android.app.camera` (Samsung Camera, back logical)
  - `CONNECT device 56 client for package com.samsung.android.app.galaxyraw` (Expert RAW, main RAW lens)

Per-device capability matrix (from HAL static metadata in the same dump):

| HAL ID | Sensor | Facing | Level | RAW max | Capabilities |
| -------- | ----------------- | ------- | ---------- | ---------- | -------------- |
| 0/20/23 | S5KGN3 50MP logical (wide) | Back | LEVEL_3 (3) | 8160x6120 (binned 4080x3060) | FULL set: RAW, YUV/PRIVATE_REPROC, MANUAL_SENSOR, BURST, 10-bit, LOGICAL_MULTI |
| 5/56 | S5KGN3 physical (main RAW) | Back | LEVEL_3 (3) | 4080x3060 | RAW, MANUAL_SENSOR/POSTPROC, LOGICAL_MULTI |
| 6/52 | IMX754? 10MP 3x tele | Back | LIMITED | 3648x2736 | RAW, MANUAL_*, 10-bit |
| 2/58 | IMX258 12MP UW | Back | LIMITED | 4000x3000 | RAW, MANUAL_*, 10-bit |
| 1 | Front logical (cover+inner) | Front | LIMITED | 3648x2736 | CHS_VIDEO, READ_SENSOR, BURST |
| 71 | IMX374 10MP inner main | Front | LIMITED | (2304x1728 binned / 4608x3456 full) | CHS_VIDEO, BURST |
| 73 | inner UW | Front | LIMITED | 2128x1464 | CHS_VIDEO |
| 3 | cover display cam | Front | LIMITED | 3024x2080 | CHS_VIDEO |
| 4/90 | SECURE_IMAGE_DATA devices | Front | — | — | secure only, correctly restricted |

**Nothing is disabled in the HAL** — front cams simply don't implement RAW (normal for Samsung
front sensors), aux lenses report LIMITED (also normal), and the main sensor reports LEVEL_3.

## 2. Root cause: Samsung framework package gate (native cameraserver)

IDA analysis of `/system/bin/cameraserver` (full notes: `ida-analysis/cameraserver-gate-analysis.md`):

- `CameraService::validateClientPermissionsLocked` @ `0x143d64`:
  non-public camera ID + calling package not in `PackagePolicyList` → connect rejected
  (`"client %s can not use camera ID %s"`).
- `PackagePolicyList::isHiddenIdPermittedPackage` @ `0x305a94`; allowlist built in the
  constructor `0x302fd4` from **hardcoded `pkgNameHint=` strings**; runtime additions only
  via Samsung's SCPM cloud-policy channel (`notifyPkgListParamChange` @ `0xe728c`, guarded by
  a signature-level permission). On this device SCPM reports "not available" → static list only.
- Allowlist observed in binary: `com.sec.android.app.camera`, `com.samsung.android.app.galaxyraw`,
  `com.snapchat.android`, `com.ss.android.ugc.aweme`, `com.zhiliaoapp.musically` (TikTok),
  `com.linecorp.b612.android`, B612/Snow/Soda/Foodie family, `com.quramsoft.tacticscamera`,
  factory/test packages — and, critically for us, **`com.samsung.android.ruler`** is accepted
  (empirically proven — this is exactly why every GCam mod ships `_ruler` builds).
- **AOSP prop gate removed**: zero occurrences of `aux.packagelist` /
  `persist.vendor.camera.privapp.list` in the binary; those props in `getprop_all.txt` are
  dead config on this firmware. (They were the classic Magisk `system.prop` mod targets on
  older Samsung/Qualcomm builds — that trick is dead here.)

## 3. Feasibility verdict: can a Magisk/KSU module fix this?

**Goal:** let an arbitrary package (any app) see the hidden camera IDs, without touching system
partitions.

| Approach | Verdict | Why |
| --- | --- | --- |
| `system.prop` override (`vendor.camera.aux.packagelist`, `persist.vendor.camera.privapp.list`) | ❌ **Dead** | Prop no longer read by cameraserver (verified by string scan). It was the S9/S10/Note-era trick. |
| Zygisk module hooking the app's Java `CameraManager` | ⚠️ Partial | App-side enumeration could be faked, but `connect()` is validated server-side in native cameraserver → open still rejected. |
| Zygisk/LSPosed hooking system framework (Java) | ❌ | Gate is native C++ in cameraserver, not Java. LSPosed hooks zygote processes only. |
| Magisk overlay replacing `/system/bin/cameraserver` with patched binary | ⚠️ Theoretically possible | Patch `isHiddenIdPermittedPackage` to return true / `isPublicId` to return true, overlay via MagicMount. BUT: (a) bootloader locked, temporary root — a bad overlay at boot can mean permanent brick, (b) must re-patch a 4.5MB signed Samsung binary on every monthly security update, (c) verity/avb considerations on locked BL make bind-mount of system/bin risky. Not recommended on this device. |
| **Package-name spoof (use an allowlisted package)** | ✅ **Verified working** | The gate is *per package name*. Apps installed under `com.samsung.android.ruler` (or other allowlisted names) get all non-secure IDs. Zero system changes. |
| SCPM cloud allowlist | ❌ | Knox cloud channel, not accessible locally; "SCPM is not available" on this unit. |

**Bottom line:** A Magisk/KSU module to *unlock hidden cameras for arbitrary packages* is **not
feasible without replacing the native cameraserver binary** (high brick risk on this locked-BL
device, and unmaintainable across OTA security patches). The **systemless** — in fact *no-root* —
solution verified on this device is: **run the camera app under an allowlisted package name**.
That is why AGC ships `AGC9.2.14_V14.0_ruler.apk`, and why that exact APK enumerated all 14 IDs,
opened hidden devices 56 (back RAW lens) and 71 (front) with the framework's blessing.

**Kernel sources** (D:\previews\SM-F946B_16_Opensource): camera-kernel contains drivers for all
sensors present (cam_sensor with adaptive-MIPI tables for s5kgn3/s5k3lu/s5k2ld/imx258 etc.).
No kernel-level restriction exists; the kernel registers all sensors; enumeration policy is
entirely userspace. (Kernel files under `kernel/vendor/qcom/opensource/camera-kernel/`.)

## 4. Making AGC 9.2 actually work on the Fold 5 (two app-level fixes)

AGC 9.2.14 `_ruler` build: enumerates all IDs out of the box (logs 14 cameras), but crashed twice
on this device. Both are **app bugs**, fixed with smali patches (decompiled with apktool 3.0.3;
edited `smali_classes2/com/agc/Camera.smali`, `smali/qix.smali`; rebuilt, zipaligned, signed with
apksigner; installed as normal app → **no system change at all**):

### Fix 1 — NPE in camera scanner (`com.agc.Camera`)

Crash: `java.lang.NullPointerException: Attempt to get length of null array` at
`com.agc.Camera.getRawSizeW()` — front/UDC cameras legitimately have no RAW sizes (`rawSizes == null`).
Both `getRawSizeW()` and `getRawSizeH()` did `array-length` before a null check.
Patch: added `if-eqz v0, :cond_1 → return 0` null guards (degrade gracefully, per "make GCam
degrade gracefully on LIMITED lenses").

### Fix 2 — gcam native metadata rejection (front camera 71)

Crash: `libgcam: StaticMetadata::frame_raw_max (3648x2736) matches neither the active area size
(2304x1728) nor pixel array size (2304x1728) → Create: StaticMetadata for sensor ID 1 failed
→ java NPE on com.google.googlex.gcam.Gcam.f()` (fatal, on 00UiWorker and main threads).

Root cause chain: Samsung's front cam 71 publishes `availableRawSizes = [2304x1728, 4608x3456]`
but its `availableStreamConfigurations` contain no RAW_SENSOR entries — only RAW_PRIVATE(34)/
YUV(35)/PRIVATE(33) at 3648x2736. AGC's Samsung-fix (`Globals.HdrRawFix*` + `isSamsungFix`)
substitutes YUV_420_888 as the "raw" source for HDR+ metadata, so `qix.h()` returns 3648x2736
as frame_raw_max while `SENSOR_INFO_ACTIVE_ARRAY_SIZE`=2304x1728 → gcam's consistency Check
fails → Gcam object null → hard crash.

Patch (in `qix.smali` `v()` right after the frame_raw_max setters): re-read
`SENSOR_INFO_PIXEL_ARRAY_SIZE` and overwrite `frame_raw_max_width/height` with it. On all Fold5
HAL devices pixel array == active array == first raw size entry, so the check passes everywhere;
for cameras with consistent metadata the patch is a no-op.

### Verification (all captured in `device-evidence/`)

- Launch: crash buffer **empty**, no `static_metadata.cc` errors (was: 3× fatal + 3× check fail).
- `CONNECT device 56 client for package com.samsung.android.ruler` — hidden Expert-RAW lens opened.
- `CONNECT device 71 client for package com.samsung.android.ruler` — front camera opened and closed cleanly.
- **Every aux lens verified**: zoom-chip switching live-connected `device 58` (ultrawide, 0.6x chip) and
  `device 52` (3x tele, 2.9x chip); tele capture `AGC_20260906_022353109.jpg` is 3648×2736 — the
  tele sensor's exact native resolution (proof it really came through lens 52).
- **Photo captured**: `/storage/emulated/0/DCIM/Camera/AGC_20260906_011732208.jpg`,
  4080×3060 RGB — pulled and verified as valid JPEG (`AGC_first_photo.jpg`).
- AGC reports back aux IDs `56,58,52,0` and lens map incl. tele (52, 67mm-eq) and UW (58, 14mm-eq).

### Reproduce the patched APK

Source APK: `AGC9.2.14_V14.0_ruler.apk` (in research folder root)
Current build (all fixes, ready to install): **`AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk`** —
download from the [v4.1.0 release](https://github.com/HyperRamzey/fold5-camera2-research/releases/tag/v4.1.0), or build it yourself below.

```sh
apktool d AGC9.2.14_V14.0_ruler.apk -o gcam_decompiled
# apply patches 1-3 (see patches/) + v3/v4 feature classes (see 50mp-burst-analysis.md)
apktool b gcam_decompiled -o out.apk
zipalign -p 4 out.apk out-aligned.apk
apksigner sign --ks <debug.keystore> --out AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk out-aligned.apk
adb install -r AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk
```

## 5. Safety record (goal constraint: zero device writes)

All device interaction was strictly read-only apart from standard user-space app installs:

- Read-only evidence: getprop, dumpsys, logcat, `su -c cat/base64` pulls of binaries,
  `stat` size verifications (sizes matched exactly).
- `adb install -r` of AGC APKs — normal app-scope install to /data (uninstallable), plus
  `pm grant` of runtime permissions to the app — not a system change.
- NO dd, no remount, no /system or /vendor writes, no prop sets, no persist changes.
- The only filesystem writes happened on the research PC (G: drive).

## 6. Deliverables in this folder

- `README.md` (this file — root cause + feasibility verdict)
- `ida-analysis/cameraserver-gate-analysis.md` — native gate deep-dive with addresses
- `kernel-analysis/` — kernel-source findings (sensor drivers; no kernel restriction)
- `device-evidence/` — dumpsys/getprop/logcat captures, prefs, screenshots, captured JPEG
- `binaries/` — cameraserver + Samsung camera provider/HAL libs (pulled read-only), strings dumps
- `gcam_decompiled/` — apktool output of AGC 9.2.14 with both Fold5 fixes applied
- `AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk` — current working build (see releases)
- `scripts/` — adb helpers (base64 su wrapper, read-only pull, screenshot)

## V2 update (2026-09-07): dead-chip fix + 50MP verdict

**Fix 3 � logical camera 0 removed** (patches/0003-Lens-drop-dead-logical-cam0.patch):
The stock AGC lens list includes both the logical main (id 0) and the physical main (id 56).
On the Fold 5, camera 0 black-screens: Samsung UniHAL routes third-party sessions through
3RD_PARTY_BYPASS and rejects the logical device's multi-physical stream layout
(CheckEnableCondition: isValidStreamConfiguration=false) � the session configures but the
preview request loop never starts (verified: 86 s of zero capture requests, then flush/close).
The patch drops id "0" from the lens list whenever "56" is present (no-op on other devices).
UI now shows only working chips; front (71) + main (56) both verified live post-patch, and a
Night Sight capture (3060x4080) completed on the v2 build.

**50MP / full-res remosaic: not possible via GCam on this firmware** � see
50mp-remosaic-analysis.md. Short version: Samsung's HAL supports 50MP remosaic on lens 56
(vendor tags RemosaicCropCapabilities=[1 ...], mmfSize=[8160x6120], private
vailableHighresRawStreamConfigurations) but does **not** populate the standard
SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION table that GCam's lobster/50MP pipeline
reads � probe-proven MAXRES map: null on every camera (see probe/Probe.java). The only
50MP path on a locked Fold 5 is Expert RAW (Samsung private stream). Also: AGC's "Device
Interface = Pixel Fold (Felix)" profile crashes (zoom-list IndexOutOfBounds) � stick with the
auto profile or Comodo.

Patches 1-3 apply cleanly to stock AGC9.2.14_V14.0_ruler.apk apktool output, in order.

## V3 update (2026-09-07): 50MP Expert RAW Mode works - through the Samsung private path

Rather than the dead standard-table route, v3 wires the **Samsung Expert RAW pipeline itself**
into GCam: a settings toggle (Lens Setting -> Main -> **50MP Expert RAW Mode**) routes the
shutter through `shootingmode=40` PDK params + `semCreateOutputConfiguration` HighResolution
streams on hidden lens 56, saving true 8160x6120 remosaic JPEGs to `Pictures/AGC_50MP/`.
Toggle off = stock GCam behavior, untouched. Full mechanism + verification matrix:
`device-evidence/FINAL_VERIFICATION.md`.

## V4 update (2026-09-08): 50MP x6 burst button (temporal merge)

A **"50MP" button in the viewfinder bottom bar** fires a 6-frame 8160x6120 RAW10 burst and
merges it in-app (robust average), producing a single temporally-denoised 50MP JPEG (~7 s,
~54 MB). Device-proven limits that shape it: the HAL's HighResolution RDI buffer manager
serves only ~6 in-flight RAW10 frames, per-request manual exposure is overridden (single-AE),
the repeating preview must stay alive, and the burst must run on the already-open cam-56
device (a second openCamera = ERROR_CAMERA_IN_USE). Full findings, architecture, and the
verified run log: `50mp-burst-analysis.md` + `device-evidence/eraw50_burst_success_log.txt`.
Release: **`AGC9.2.14_V14.0_ruler_fold5fix_v4.apk`** (v1-v3 fixes + burst button).

The v3/v4 feature classes live in `smali_classes2/com/agc/` (see 50mp-burst-analysis.md
for the full hook map).

## V4.1 update (2026-09-08): burst fixes + shutter back to stock

Four user-reported issues fixed, verified E2E on device:

1. **Pink/magenta cast** — device-probed color science: GN3 CFA is **GBRG** (not RGGB),
   black level **256**, white level **4095**; and the HighResolution RAW10 stream is
   **pre-white-balanced** by the HAL, so the debayer applies unity gains (measured output
   R/G=1.01, B/G=1.12 — matches the HAL's own JPEG).
2. **Viewfinder freeze after burst** — GCam never re-opens cam-56 passively, so after the
   save the app restarts the CameraActivity; the viewfinder, device store and button all
   rebuild (frame-diff verified live).
3. **Button placement** — bottom_bar ignores margins and clips translated children; the
   button now attaches to the window DecorView ~25% of screen height above the old slot,
   clear of every control.
4. **Shutter = stock 12MP always** — the kzq funnel hook and the dead single-shot chain
   are removed. The **50MP toggle now gates only the button** (Settings → Lens Setting →
   Main → 50MP Expert RAW Mode): ON = button visible, OFF = stock viewfinder.

Build: `AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk` (release v4.1.0). Details:
`50mp-burst-analysis.md` + `device-evidence/eraw50_v4.1_final_log.txt`.
