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
- HDR+, Night Sight (back lenses — mode entry, config panel and quick-menu controls all functional since V3), Astrophotography, Portrait, Panorama
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

### 🩹 The seven Fold 5 fixes in this build (all published as smali diffs in `patches/`)

**Fix 1 — NPE in AGC's camera scanner** (`com.agc.Camera.getRawSizeW/getRawSizeH`, patch 0001)
AGC crashes on boot with `Attempt to get length of null array` because the Fold 5's front cameras legitimately have **no RAW sizes** and the stock code does `array-length` before any null check. Added null guards → graceful 0 return.

**Fix 2 — gcam native rejecting our front camera** (`qix.smali`, patch 0002)
The interesting one. Samsung's front HAL device (71) publishes `availableRawSizes = [2304×1728, 4608×3456]` but its stream-configuration table has **no RAW_SENSOR entries** — only YUV at 3648×2736. AGC's Samsung-fix substitutes YUV as the "raw" source, so GCam's native `static_metadata.cc` consistency check saw `frame_raw_max 3648×2736` vs `activeArray 2304×1728`, failed the sensor, nulled the `Gcam` object → hard crash on `Gcam.f()` from two threads. The fix forces `frame_raw_max` to the sensor's own pixel-array dims (which match active array on every Fold 5 device). No-op for consistent cameras, so back lenses are untouched.

**Fix 3 — dead logical camera 0 dropped from the lens list** (`com/Utils/Lens.smali`, patch 0003)
Samsung's HAL exposes a logical multi-camera device (id 0) that can never be opened directly — it just pollutes AGC's camera list and can grab the lens switcher. The fix filters it out of `getAllCameras()`, leaving the real physical IDs.

**Fix 4 — front camera raw-size gate unblocks the front switch** (`com/agc/CamerasFinder.smali`, patch 0004)
AGC's finder queries RAW16 only; the Fold5 front cams (1, 3, 71, 73) expose no RAW at all → null → Go's `CalcFrontMainCamera` gates on `RawSizeW != 0` and silently drops every front camera → **no front/back switch button**. The fix adds a RAW16 → RAW10 → RAW12 → YUV_420_888 fallback returning the YUV max sizes, so the front passes the visibility gate while the engine's own stream picker still chooses the real per-camera format.

**Fix 5 — jba stream-format array YUV fallback** (`jba.smali`, patch 0005)
Same family: the stream-format loop hard-required a RAW entry per camera and threw on the RAW-less front; the fallback walks the format array and accepts YUV_420_888 as the merge source for the front path.

**Fix 6 — GCam device-recognition abort → generic class** (`jsc.smali`, patch 0006)
GCam's internal device classifier (`jsc.h()`) probes a Pixel-only fingerprint database (64-bit hardware constants per model). The SM-F946B matches nothing, so the cascade fell through to `throw new phq("Device is not recognizable. Aborting.")` — which is what killed the app whenever Night Sight's module/panel initialization ran the check on a background thread (crash state dump: `applicationMode=NIGHT_SIGHT`, Panel window opening). Fix 6 replaces the throw with a return of the generic device class (`gwt.h`) — unknown devices classify as generic instead of aborting. Known-device paths untouched.

**Fix 7 — Night Sight panel-item always-enable** (`hnf.smali`, patch 0007)
The moon+"1s" quick-controls sheet (White balance / Exposure / **Night Sight** / Reset all) showed the Night Sight row permanently grayed: the panel-item enable switch (`hnf.m(Z)`) is driven by an upstream observable that is always false on this device, which added NS to the exclusion set that disables the row. Fix 7 removes the disable branch — the row is always enabled. Verified end-to-end: menu opens, all four rows enabled, the NS row taps and responds.

Patches apply cleanly to the stock AGC9.2.14_V14.0_ruler apktool output — repo → `patches/`, each numbered in apply order.

---

### 📥 Download & install

**[DOWNLOAD — v6.0.0 release (GitHub)](https://github.com/HyperRamzey/fold5-camera2-research/releases/tag/v6.0.0)** — APK + `fold5_day_sun.agc` + `fold5_night_full.agc` (now with the 50-frame Night Sight stack) in the release assets. Legacy releases stay on their own tags for history.

1. Coming from v5.x of this fork: `adb install -r` upgrades in place (same key) — settings and configs preserved.
2. Coming from stock AGC `_ruler` or another fork: clean install required (different signature).
3. Open → grant Camera + (optionally) location/mic + **All files access** (for saving to DCIM).
4. Done. First launch takes a few seconds (it scans all 14 devices).

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

- ~~Tapping the **Night-mode chip** in the portrait chip row crashes the app on this device~~ — **root-caused and fixed in V3:** the crash was the same `jsc.h()` device-recognition abort as the NS config-panel crash (Fix 6), not a zoom-module resolver as first attributed. Both entry paths are clean now.
- If the phone dies mid-tune, the last-applied key **survives** in the config — always re-verify state after any interruption before judging results.
- Wireless ADB rotates its port on every reboot (and the daemon can drop when the battery dies) — reconnect via `adb mdns services`.
- **Tuning-at-your-own-risk note for root users:** never edit the app's prefs XML while the camera is running — the app rewrites the file from its in-memory snapshot on the next commit and silently deletes any keys you armed while it was alive. Always force-stop first. (Cost me two keys to learn; all harness scripts now enforce it.)

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


### 🎛️ V2.1 update (Sept 13, later) — viewfinder AE fix: the `meteringMode` anchor

One-pref fix for the "viewfinder takes exposure at open and never updates" complaint some of you will hit with imported configs: AGC stamps Samsung's vendor key `samsung.android.control.meteringMode` on **every** capture request on Samsung builds, reading it from `pref_metering_mode_key`. Configs built on the `fold5_best` lineage ship that as **`3`** — on the Fold 5's TsAe HAL that's trigger-style metering (converge once at open, park; only tap/zoom re-meters). The HAL was verified innocent first (continuous-AE OpMode, unlocked, no manual pin — Samsung's own `SS_3A` trace), then the vendor-tag dump caught `meteringMode: [3]` live in the request.

**Fix:** set `pref_metering_mode_key` (and any `lib_pref_metering_mode_key_p0_0` / `_p1_0` per-slot copies) to **`0`** (matrix/continuous). All four configs in the release now ship 0 — pan-tested, the preview adapts both directions like stock. If you imported an older config: change the value in the app or re-import the new files.

### 🎛️ V3 update (Sept 15) — Night Sight fully unlocked + 50-frame night stack

**The headline:** the config-gear crash is dead and the grayed Night Sight row in the quick-controls sheet works now — two more Samsung-hardware gates root-caused to the exact smali method and patched (Fixes 6 + 7 above). No more "Night Sight button grayed out" on the Fold 5.

**What was wrong:** two separate gates, both device-recognition.

1. **The config-panel crash.** Tapping the gear icon in Night Sight killed the app with `FATAL: phq: Device is not recognizable. Aborting.` — GCam's internal classifier probes a Pixel-only hardware-fingerprint database and the Fold 5 matches nothing, so the panel's init thread aborted. Now unknown devices classify as generic and the panel opens clean.
2. **The quick-menu gray-out.** The moon+"1s" sheet's Night Sight row (the one next to White balance and Exposure) was permanently disabled by an always-false device-gated observable. Now always enabled — verified live: menu opens, all four rows alive, the NS row taps and responds.

The V2 "night chip crash" gotcha turned out to be gate #1 all along — same root cause, now fixed. Full smali-level root-cause chains (with the DI wiring path) in `tuning/DECISIONS.md`.

**Night Sight tuning, leg 1 fired:** the stacking rack is now mapped and the first lever verified with a 2x-shots/2x-dwell A/B:

| Key | Was | Now | Measured |
| --- | --- | --- | --- |
| `lib_pref_frame_count_ns_key_p1_0` (NS frame count) | 25 | **50** | **−2.6% noise** vs stock (real merge gain, exceeded both sides' internal spread); luma/chroma flat; channel balance flat; no side effects |

50 frames ship in `fold5_night_full.agc` on the v6.0.0 release. The rest of the rack (max exposure 8s→16s, ISO ceiling, sabre/shasta merge tuning) is darkness-gated — fired the first leg at 05:48 and the scene was already twilight (ISO ~1000), so those resume at true dark per the campaign's light-gate discipline.

**Process hardening banked:** the prefs write-while-running bug (root cause of two silent key losses, now force-stop-before-write everywhere in the harness) and the ISO EXIF parse (SHORT little-endian) are documented in the repo log for anyone scripting their own passes.

---

*Changelog:*

- *V1 — initial Fold 5 release: Fix 1 (scanner NPE) + Fix 2 (front-cam gcam metadata rejection) on AGC9.2.14_V14.0_ruler base. All four lenses verified: 56/58/52/71.*
- *V2 (2026-09-13) — no APK changes, configs + research only: full A/B tuning campaign (158-row log), Night Sight exposure bank (up to 4x light), 14 feature flags kept / 7 reverted / 3 camera-breakers identified, 7 lib scalars kept / 7 reverted, and GUI-selectable Day/Night profiles — now at picker rows 1/2 — plus the corrected profile-slot mechanism (Ka-row trap documented). Patch series in the repo: 0001–0005 (V1 launch fixes 0001/0002; v4.x burst work 0003; v4.3 front-cam gates 0004/0005).*
- *V2.1 (2026-09-13) — viewfinder AE fix: pref_metering_mode_key 3→0 (Samsung vendor meteringMode trigger-style metering was anchoring preview exposure at open; configs rebaked, pan-verified both directions).*
- *V3 (2026-09-15) — Night Sight fully unlocked: Fix 6 (jsc device-recognition abort → generic class — kills the NS config-panel crash AND the night-chip crash from V2's gotchas) + Fix 7 (hnf NS panel-item always-enable — ungrays the quick-menu Night Sight row). Night leg 1: frame_count_ns 25→50 verified (−2.6% noise), shipped in fold5_night_full.agc. Night mow continues at true dark.*
