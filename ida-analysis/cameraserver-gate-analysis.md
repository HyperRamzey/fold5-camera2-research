# IDA Analysis Notes — Samsung Framework Camera Gate (cameraserver)

Binary: `/system/bin/cameraserver` (pulled via root `base64` read; sha-matched size 4,503,976 bytes)
Device: SM-F946B (Galaxy Z Fold 5), Android 16 (BP4A.251205.006.F946BXXS7GZE5, One UI 8)
Tool: idalib (Hex-Rays), session `camsrvA`

## 1. The hidden-camera gate

### 1.1 Entry point — `CameraService::validateClientPermissionsLocked` @ `0x143d64`

Decompiled control flow (paraphrased):

```cpp
if (!CameraIdUtils::isPublicId(cameraId)) {          // IDs like "20","52","56","58","71","73"
    if (!PackagePolicyList::isHiddenIdPermittedPackage(callingPackage)) {
        ALOGE("CameraService::connect X (PID %d) rejected (client %s can not use camera ID %s)", ...);
        return Status::fromServiceSpecificError("No camera device with ID %s available");
    }
}
```

`isHiddenIdPermittedPackage` @ `0x305a94` (`android::PackagePolicyList::isHiddenIdPermittedPackage`).
On success it logs: `"this package can access to hidden camera ids. %s"`.

Other gate helpers found alongside (Samsung-specific):

- `isHiddenPhysicalCamera` / `isHiddenPhysicalCameraInternal` — hides physical sub-cameras of logical devices
- `"Rejecting access to secure hidden camera %s"` — secure devices (4/90, SECURE_IMAGE_DATA)
- `"Device %s does not exist or not present or hidden secure camera!"`
- `PackagePolicyUtils::isSkipUidActiveCheckPackage`, `isKeepLowPriorityPackage`,
  `isAdaptiveBrightnessSystemPackage`, `isEadSystemPackage`, `isSmartFaceServiceSystemPackage`,
  `isSightCareServiceSystemPackage`, `isBiometricsFaceService`, `isCameraDisabledByMDME`
  (uid/pid/mdm-side policies, unrelated to lens hiding)

### 1.2 Allowlist construction — `PackagePolicyList::PackagePolicyList()` @ `0x302fd4`

The constructor (0x1ac8 bytes, 44 string refs) builds an in-memory map from HARDCODED
`pkgNameHint=<package>` entries baked into the binary. Hardcoded allowlisted package names
found in the binary (strings cache + xrefs):

- com.sec.android.app.camera            (Samsung Camera)
- com.samsung.android.app.galaxyraw    (Expert RAW)
- com.samsung.android.aremoji, com.samsung.android.sead, com.samsung.sightcare,
  com.samsung.adaptivebrightnessgo, com.samsung.android.smartface
- com.factory.mmigroup, com.val.hardware, com.longcheer.midtest,
  com.diamond.app, com.walmart.diamond.app, com.srbr.app.sensorproxy
- com.snapchat.android
- com.ss.android.ugc.aweme, com.ss.android.ugc.trill, com.zhiliaoapp.musically
- com.linecorp.b612.android, com.linecorp.foodcam.android, com.linecorp.foodcamcn.android
- com.campmobile.snow, com.snowcorp.soda.android, com.yiruike.sodacn.android
- com.quramsoft.tacticscamera, com.prism.live, ie.garda.android.anpr
- (com.samsung.android.ruler is permitted via the same hint-map — empirically verified)

Matching semantics (from the decompile of `isHiddenIdPermittedPackage`):
the map values are parsed as `<packageHint>:<flags>`-style hints; the matcher does a
contains/startsWith comparison of the calling package against each hint, with a numeric
flag suffix parsed via `atoi()` (bit 1 = allowed). Malformed hints log
`"%s : invalid pkghint : %s"` and are skipped.

### 1.3 Runtime updates — SCPM

`CameraService::notifyPkgListParamChange` @ `0xe728c` — binder entry guarded by
`checkCallingPermission()`; error string: `"cannot send updates to camera service about SCPM
state changes"`. This is Samsung's Knox/Cloud policy channel that can extend the allowlist at
runtime. On this device `dumpsys` shows: `CameraService/ScpmHelper: registerScpm - SCPM is
not available` (retries every 5 min) — so on this EUX unit the hardcoded list is the only
active list.

### 1.4 AOSP prop gate REMOVED

Upstream AOSP `CameraService` reads `vendor.camera.aux.packagelist` (that's what
`/system/build.prop:179 vendor.camera.aux.packagelist=org.codeaurora.snapcam` is for).
Full-ASCII string extraction of this Samsung `cameraserver` build contains **zero**
occurrences of `aux.packagelist`, `persist.vendor.camera.privapp.list`, or any consumer
thereof. Samsung replaced the AOSP prop-driven allowlist with the hardcoded
`PackagePolicyList` + SCPM. **Therefore no system.prop override can open hidden camera IDs
on this build.** The props are dead configuration on this firmware.

## 2. What the gate hides (cross-referenced with dumpsys)

- HAL provider exposes 14 devices: 0,1,2,3,4,20,21,23,52,56,58,71,73,90
  (IDs 4/90 are SECURE_IMAGE_DATA — separately gated, correctly so)
- CameraService exposes to API1/API2 to non-allowlisted apps:
  `Number of camera devices: 4; Number of public camera devices: 3`
  `Device 0 maps to "0" (back logical, LEVEL_3), Device 1 maps to "1" (front),
   Device 2 maps to "3" (front UW)` — i.e. apps see logical IDs 0,1,2(->3) only
- Samsung Camera itself connects to hidden `device 20`; Expert RAW connects to `device 56`
  (both observed live in the CameraService event log) — the hardware capabilities exist,
  they are simply package-gated.
- `com.samsung.android.ruler` (AGC GCam spoof) successfully enumerates and opens all
  non-secure IDs incl. 56 and 71 — empirically proving the package gate is the only
  framework-side block.

## 3. Verdict for a Magisk/KSU module

- The gate lives in a NATIVE system daemon (`/system/bin/cameraserver`), not in Java:
  - Zygisk only hooks zygote-forked app processes → cannot touch cameraserver
  - LSPosed/Xposed hooks Java framework (system_server) → cannot patch native C++ code
  - The allowlist is hardcoded in the binary + SCPM cloud policy — not prop/file driven
    (no `vendor.camera.aux.packagelist` reader exists in this build)
- A Magisk "systemless" overlay REPLACING `/system/bin/cameraserver` with a patched binary
  is theoretically possible on an unlocked root (MagicMount bind), but on this device the
  bootloader is LOCKED with temporary root; rebuilding Samsung's 4.5MB Android-16
  cameraserver per security patch is impractical and a wrong overlay at boot = brick.
- Practical, verified solution: no module needed at all. Any app whose package name is in
  the hardcoded allowlist gets all lenses. GCam mods ship as `_ruler` builds for exactly
  this reason. Combined with the 2 AGC smali fixes (see report), full camera access works:
  back main (56, LEVEL_3 RAW), front (71), aux list (56,58,52,0) selectable.

## 4. Artifacts

- `../binaries/cameraserver` — pulled binary (read-only pull, size verified)
- `../binaries/strings/cameraserver.txt` — ASCII strings dump
- IDA functions of interest (addresses in pulled binary):
  - `0x143d64` CameraService::validateClientPermissionsLocked (the gate)
  - `0x305a94` PackagePolicyList::isHiddenIdPermittedPackage
  - `0x302fd4` PackagePolicyList::PackagePolicyList (allowlist ctor)
  - `0xe728c`  CameraService::notifyPkgListParamChange (SCPM updater)
- Live device corroboration in `../device-evidence/`:
  - `dumpsys_media_camera.txt` (14 HAL devices, 4 devices exposed, hidden-ID connect log)
  - `getprop_all.txt` (vendor.camera.aux.packagelist=org.codeaurora.snapcam present but dead)
  - `agc_*logcat*.txt` (ruler package sees all IDs; opens 56 & 71)
