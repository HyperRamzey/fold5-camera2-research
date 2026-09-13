# XDA Post Draft — paste into a new thread (Galaxy Z Fold 5 themes & apps section)

---

**Title suggestion:**

`[GCam] AGC 9.2.14 _ruler for Galaxy Z Fold 5 (SM-F946B) — all lenses working + full research on why Samsung hides them + tuned day/night configs`

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

**[DOWNLOAD — v5.0.0 release (GitHub)](https://github.com/HyperRamzey/fold5-camera2-research/releases/tag/v5.0.0)** — APK + `fold5_day_sun.agc` + `fold5_night_full.agc` in the release assets. Legacy v4.x releases stay on their own tags for history.

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

If you import JavaSaBr's S23U `.agc` configs for the "Medium" tuning: it's **safe** (AGC discards the config's lens/ID bindings on import — verified in its ConfigLoader code), but keep noise model/AWB on AUTO anyway. Since the V2 update below there's a better option — the Fold 5-specific tuned Day/Night profiles, no S23U import needed.

**⚠️ One important trap with this build:** the profile picker's built-in **Ka** rows (KaNight/KaDay) are BigKaka's RAM patch bundles compiled for the **stock** tune. On a tuned config they land wrong and corrupt the merge — measured: a blown, crushed, color-shifted frame plus an insanely bright preview with a hard half-screen seam (the night-tune preview tonemap mis-firing in daylight). If your viewfinder suddenly looks nuclear or photos come out crushed, you're on a Ka row — switch to Profile 1/2 and it clears. RAM patches are volatile: if anything feels wedged, force-close and reopen the app.

---

### 🎛️ V2 update (Sept 13) — full A/B tuning pass + Day/Night configs

The repo now includes a complete A/B tuning campaign for this exact device — a 158-row evidence log, every step one variable at a time: apply key → cold-restart the camera app on main lens 56 → one shot → measure luma / color balance (R/G, B/G) / noise / sharpness → diff vs the previous shot → KEEP or REVERT (works + not broken = keep). It started with a 32-step night pass on a static indoor scene (deep night, luma ~10) that established the confirmed baselines — notably `lib_hardmerge_key=1` (sabre merge) outputs **black frames** on this HAL, `=2` (spatial bayer) works but measured measurably softer (−9% and −13.5% sharp in two separate retests), so 3 stays. Everything below is grep-verified against the live config; full logs in the repo's `tuning/` folder (`DECISIONS.md` + `AB_RESULTS.tsv` + the harness scripts).

**Night Sight exposure bank — up to 4x more total light.** Six exposure-path ceilings raised in a single ladder, every step functionally PASS:

| Key | Was | Now |
| --- | --- | --- |
| `lib_max_exp_ms_key_p0_0` (per-frame exposure cap) | 4000 ms | **8000 ms** |
| `lib_shasta_max_exp_ms_key_p0_0` (shasta night-merge cap) | 4000 ms | **8000 ms** |
| `lib_pref_frame_count_zsl_key_p0_0` (Night Sight frame count) | 30 | **60** |
| `lib_max_frame_count_key_p0_0` (burst ceiling) | 25 | **50** |
| `lib_max_bracketing_frames_key_p0_0` | 25 | **50** |
| `lib_max_short_frames_key_p0_0` | 25 | **50** |

Honest caveat: the ladder was tuned in morning light, so AE never actually hit the ceilings during testing — this is **banked headroom** (frames × per-frame exposure = up to 4x more light available to the night merge) that only engages in real darkness. Treat the daytime numbers as directional; the stable-light re-verification list is in the repo log.

**Feature flags — 24 probed, every one dispositioned:**

- **Kept (14):** anglerfish, ark, beholder_force_opt_in, the falcon inner trio (force_fusion + md + tpu), force_anglerfish.RESTART, force_cuttle.extended, **gyrfalcon** (strongest isolated win of the pass: +12.3% sharp at matched light — the super-res upscale stage actually fires), hawk_force_fusion + hawk_tpu, ica_in_front, kepler, and **sabre_raw** (gates RAW/DNG saving in sabre mode — RAW capture now enabled at zero JPEG-path cost).
- **Reverted (7):** autobahn_options (softening), decepticon_force_run (no benefit — the decepticon path already runs via `decepticon_enabled=true`), falcon_always_on and gouda.firefly (both hard-fail: noise up AND sharp down), gouda.matting (2:1 softening — it's portrait segmentation, no static-scene gain), sabre_gcam (AE-shift only, no image change — and it would silently reinterpret the entire sabre key family Google-style), shasta_ON (2:1 softening).

⚠️ **Camera-breakers — do NOT set these three:** `camera.cyclops_enabled`, `camera.falcon_enabled`, `camera.hawk_enabled`. All three block the camera from opening at all on lens 56 (no crash in the buffer — the open just never completes; same signature, verified three times). Pattern: the `*_enabled` master gates on the newer codename features are stubbed in this port, while their inner functional flags (force_fusion / md / tpu) work fine. If you set one anyway: remove the key, force-stop the app — the camera recovers, nothing persistent.

**Lib scalars — surprise finding: the "neutral" lib keys are live global scalars, not dead entries.** 14 probed:

- **Kept (7):** sharpness_b 0.5 (+9.4% sharp vs +3.8% noise), luma_a 0.5, luma_b 0.5 (a daylight ON/OFF pair confirmed this one — removing it costs +13.3% noise), chroma_a 0.5, spatial_a 0.5, spatial_b 0.5, tone 1.0 (best matched-light pair of the whole campaign: +6.3% sharp at flat noise).
- **Reverted (7):** sharpness_a 0.5 (halves the whole sharpening stack: −27.6% sharp), chroma_b 0.5 (2:1 smear), denoise 1.0 (the global master — re-couples every downstream denoiser, softening), sharp_gain 1.5 (**2.5x grain explosion** — both metrics go up and the verdict gate passes, but the image is visibly oversharpened; beware that class of "win"), gamma 1.0 (no measurable signal), brightness 1.0 (softening — brightness belongs in the exposure bank, not post), noise_reduction_adjust 1.0 (clamps the whole tuned stack at unity).

**Day / Night profiles — selectable in the app's own GUI.** The app has a native config-file system: Settings → **Configs, LUT, Libraries, AWB, NoiseModel file** → Import → `/Download/AGC.9.2/configs/`. Two snapshots live there:

- `fold5_day_sun.agc` — daylight: **Profile 1 (Day)** in the app's picker — stock ceilings (compiled defaults), fast bursts in sun
- `fold5_night_full.agc` — night: **Profile 2 (Night)** — the full tuned state (8s / 60 frames / 50 ceilings + every keep above)

The profiles live in the app's own quick profile picker (the patch button in the viewfinder) as rows 1/2, titled "Day (sun)" / "Night (full tune)" — one tap to switch. The config files attached to the release do the same thing through Settings → Import if you prefer. Rows 3+ are stock empty slots. **Do not use the built-in "Ka" rows** — KaNight/KaDay are BigKaka's RAM patch bundles compiled for the stock tune; on a tuned config they corrupt the merge (measured: blown crushed frame) and the preview tonemap. Don't ask how I know.

Switching = importing the other file (round-trip verified through the app's own picker). After your own hand-tweaks, the **Save** button snapshots current state as a new `.agc` in the same folder. No root needed to import.

**[Files on the v5.0.0 release: fold5_day_sun.agc + fold5_night_full.agc]**

Mechanism notes for the curious: `lib_patch_profile_key` is the patch selector — 0 = no patch (pure compiled bare state), 1/2 = the built-in KaNight/KaDay RAM bundles (avoid), N≥3 = user profile slot N−3, where `lib_*_key_p{N−3}_{lens}` entries override the bare keys per profile/lens (missing keys fall back to bare). Day = slot 0 left empty (bare defaults), Night = slot 1 carrying the full tune. The tuned scalars are visible and editable in the GUI too: Image processing → Main Settings. The tuning pass itself ran over wireless ADB with root (KernelSU); the harness scripts are in the repo. Root is only needed to *produce* configs, never to use them.

**Fold 5 gotchas from the pass:**

- Tapping the **Night-mode chip** in the portrait chip row crashes the app on this device (pre-existing zoom-module resolver crash — reproduces byte-identically on the stock AGC build, so it's not a tuning artifact). Night captures work via auto-engagement; the exposure bank lives in the photo pipeline anyway.
- If the phone dies mid-tune, the last-applied key **survives** in the config — always re-verify state after any interruption before judging results.
- Wireless ADB rotates its port on every reboot (and the daemon can drop when the battery dies) — reconnect via `adb mdns services`.

**Dusk re-verify pending:** several keeps were measured in drifting morning light (sunrise + clouds) — the ratios were consistent but the light wasn't. The stable-light confirmation list is documented in the repo log; treat morning numbers as directional until that pass runs.

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
- *V2 (2026-09-13) — no APK changes, configs + research only: full A/B tuning campaign (158-row log), Night Sight exposure bank (up to 4x light), 14 feature flags kept / 7 reverted / 3 camera-breakers identified, 7 lib scalars kept / 7 reverted, and GUI-selectable Day/Night `.agc` configs.*
