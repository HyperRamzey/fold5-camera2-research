# XDA Post Draft — paste into a new thread (Galaxy Z Fold 5 themes & apps section)

---

**Title suggestion:**

`[GCam] AGC 9.2.14 _ruler for Galaxy Z Fold 5 (SM-F946B) — all lenses working + full research on why Samsung hides them`

---

## Post body (copy from here)

---

## 📷 AGC 9.2 (BigKaka base) — Galaxy Z Fold 5 / SM-F946B — working main, UW, tele & front, plus why only the "main" camera ever shows up in Camera2

**Tested on:** SM-F946B, One UI 8 / Android 16, build **BP4A.251205.006.F946BXXS7GZE5** (EUX), locked bootloader, no root required.

**Base:** BigKaka's **AGC9.2.14_V14.0_ruler** — huge thanks to him as always (Telegram: @bigkaka). This build adds **two device-specific smali fixes** for the Fold 5 that stop the two hard crashes his build hits on our device. Nothing else was touched.

---

### ✅ What works (verified live, not guesswork)

- **Main 50MP (hidden lens 56)** — LEVEL_3, RAW 4080×3060, full HDR+ ✅
- **Ultrawide (hidden lens 58)** — RAW 4000×3000, HDR+ ✅
- **3x tele (hidden lens 52)** — RAW 3648×2736, HDR+ ✅
- **Front (hidden lens 71)** — opens, previews, captures (no RAW — see FAQ) ✅
- Photo verified end-to-end: 4080×3060 JPEG through lens 56, 3648×2736 through tele 52
- HDR+, Night Sight (back lenses), Astrophotography, Portrait, Panorama
- No crashes on launch / lens switch / front switch — crash buffer stays empty

### ❌ What doesn't / won't ever

- **Front camera HDR+ RAW merge** — Samsung's HAL exposes **no RAW streams at all** on the front sensor (not a GCam limitation; it's absent from the HAL metadata). YUV-based light merge only; keep frames low or off for people.
- The SECURE_IMAGE_DATA devices (IDs 4/90) — those are locked down by design (biometric/secure path), correctly so.
- 10x tele — we don't have that hardware (that's S23U territory).

---

### 🔧 Why your Fold 5 "only has 3 cameras" in normal apps — short version

Full research with IDA decompiles & evidence: **github.com/HyperRamzey/fold5-camera2-research**

Your Fold 5 has **14 camera devices in the HAL**. The main sensor reports Camera2 **LEVEL_3 with 8160×6120 RAW**. Samsung Camera itself opens a hidden logical device (20), Expert RAW opens the hidden RAW lens (56). Regular apps only ever see 4 public IDs.

The gate is **not** the HAL, kernel, or any prop you can flip:

- Samsung's Android 16 `cameraserver` contains a native **`PackagePolicyList`** — `validateClientPermissionsLocked()` → `isHiddenIdPermittedPackage()` rejects any `connect()` to hidden IDs unless the **calling package name** is on a **hardcoded allowlist embedded in the binary** (Samsung Camera, Expert RAW, Snapchat, TikTok, B612/Snow family… and a batch of odd ones like `com.samsung.android.ruler`).
- The classic AOSP `vendor.camera.aux.packagelist` prop trick is **dead on this firmware** — the prop still sits in build.prop but nothing reads it anymore (verified by full string scan of the binary). So the old Magisk `system.prop` mods do nothing on One UI 8.
- Runtime list updates come only via Samsung's SCPM/Knox cloud channel ("SCPM is not available" on my unit).

**Practical consequences:**

1. A Magisk/KSU "unlock hidden cameras for all apps" module is **not feasible** — the gate is native C++ inside a system daemon; Zygisk/LSPosed can't reach it, and there's no prop or file to override.
2. The systemless fix is exactly what GCam modders have always done: **run under an allowlisted package name** — that's why BigKaka ships `_ruler` builds, and it works perfectly here.
3. Zero root needed. It's just an app.

---

### 🩹 The two Fold 5 fixes in this build (both published as smali diffs)

**Fix 1 — NPE in AGC's camera scanner** (`com.agc.Camera.getRawSizeW/getRawSizeH`)
AGC crashes on boot with `Attempt to get length of null array` because the Fold 5's front cameras legitimately have **no RAW sizes** and the stock code does `array-length` before any null check. Added null guards → graceful 0 return.

**Fix 2 — gcam native rejecting our front camera** (`qix.smali`)
The interesting one. Samsung's front HAL device (71) publishes `availableRawSizes = [2304×1728, 4608×3456]` but its stream-configuration table has **no RAW_SENSOR entries** — only YUV at 3648×2736. AGC's Samsung-fix substitutes YUV as the "raw" source, so GCam's native `static_metadata.cc` consistency check saw `frame_raw_max 3648×2736` vs `activeArray 2304×1728`, failed the sensor, nulled the `Gcam` object → hard crash on `Gcam.f()` from two threads. The fix forces `frame_raw_max` to the sensor's own pixel-array dims (which match active array on every Fold 5 device). No-op for consistent cameras, so back lenses are untouched.

Patches apply cleanly to the stock AGC9.2.14_V14.0_ruler apktool output: repo → `patches/`.

---

### 📥 Download & install

**[DOWNLOAD LINK — attach APK here]**

1. Uninstall any previous GCam/AGC on the Fold 5 first (package `com.samsung.android.ruler`).
2. Install the APK (allow unknown sources).
3. Open → grant Camera + (optionally) location/mic + **All files access** (for saving to DCIM).
4. Done. First launch takes a few seconds (it scans all 14 devices).

Sign key: self-signed debug — install with `-r` over the stock AGC `_ruler` build won't work (different signature); clean install required.

---

### ⚙️ Recommended settings (quick version — full guide in the repo)

| Setting | Value |
| --- | --- |
| HDR+ | Auto (back lenses) |
| HDR+ model | **AUTO** — it auto-derives a working profile per lens; don't hand-pick Pixel 6/7/8 models |
| Image format | RAW16 (default) |
| Noise model / AWB | **AUTO** — don't import S23U configs' sensor calibrations (HP2/IMX586 ≠ our GN3/IMX258/IMX374) |
| HDR+ frames | Main 15–20 · UW 10–15 (no OIS!) · Tele 10–15 |
| JPEG quality | 99 |
| Front | HDR+ off for people; it's YUV-only, ≤8 frames static scenes |
| 50MP full-res | Try it: main lens exposes 8160×6120 RAW — bright light, static scene, HDR+ off |

If you import JavaSaBr's S23U `.agc` configs for the "Medium" tuning: it's **safe** (AGC discards the config's lens/ID bindings on import — verified in its ConfigLoader code), but keep noise model/AWB on AUTO anyway.

---

### ❓FAQ

**Is root needed?** No. Nothing here touches /system. Verified: boot state stays green/locked, zero system writes.

**Why `_ruler` package name?** It's in Samsung's hardcoded cameraserver allowlist — that's the whole trick, and it's BigKaka's trick, not mine. It's also why no Magisk module can do the same for *other* apps.

**Will this survive OTA updates?** The APK is user-space — OTA won't remove it. If Samsung changes the HAL metadata layout (they haven't in this One UI 8 build), Fix 2 might need a re-check.

**Front camera quality?** Same as Samsung's app for stills from the 10MP sensor; no RAW path exists in the HAL, so no HDR+ magic there — hardware fact, not fixable in software.

**Is it safe?** It's a camera app. Worst case: force-close and uninstall. The research repo documents every claim with captured evidence (dumpsys, logcat, IDA analysis).

---

### 🙏 Credits

- **BigKaka** — AGC 9.2 mod, the `_ruler` package-spoof approach, RAM patcher infrastructure
- **Google** — Pixel Camera / GCam base (HDR+ is theirs)
- **Samsung** — for making a 14-device HAL and only letting us see 4 of them 😄
- Research, IDA analysis & Fold 5 fixes: **HyperRamzey** — repo with full evidence: **github.com/HyperRamzey/fold5-camera2-research**

Standard GCam-modding disclaimer: this is a modified proprietary Pixel Camera binary, shared for personal use on your own device, same as every other AGC/GCam build on XDA. Use at your own risk.

---

*Changelog:*

- *V1 — initial Fold 5 release: Fix 1 (scanner NPE) + Fix 2 (front-cam gcam metadata rejection) on AGC9.2.14_V14.0_ruler base. All four lenses verified: 56/58/52/71.*
