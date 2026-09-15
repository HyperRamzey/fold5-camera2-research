# Release v6.0.0 — "Night Sight Unlocked" — AGC 9.2.14 for Galaxy Z Fold 5 (SM-F946B)

**The headline:** Night Sight's config button no longer crashes the app, and the grayed-out
Night Sight control in the quick-settings sheet now works — two more Samsung-hardware gates
root-caused to the exact smali method and patched. This release carries **seven** device
fixes total, all verified live on-device.

**APK:** `AGC9.2.14_v6.0.0_fold5.apk` (attached) — signed with the project debug key;
install over any previous build of this fork (`adb install -r`), settings and configs are
preserved.

---

## What's new since v5.0.1

### Night Sight unlocked (two separate gates)

**1. Config panel crash (v6.7 patch).** Tapping the gear/config icon while in Night Sight
killed the app with `FATAL: phq: Device is not recognizable. Aborting.` — GCam's internal
device-recognition cascade (`jsc.h()`) probes a Pixel-only fingerprint database (64-bit
hardware constants), and the SM-F946B matches nothing, so the panel's init thread aborted.
Fix: unknown devices now classify as the generic device class (`gwt.h`) instead of throwing.
Known-device paths untouched.

**2. Quick-menu gray-out (v6.8 patch).** The moon+"1s" chip's control sheet
(White balance / Exposure / Night Sight / Reset all) showed Night Sight permanently grayed
— `hnf.m(Z)` (the NS panel-item enable switch) is fed by an upstream observable that is
always false on this device, adding NS to the exclusion set that disables the row.
Fix: the add-branch is removed; the row is always enabled. Verified end-to-end: menu opens,
all four rows enabled, the NS row taps and responds, no crash.

Both root-cause chains (with smali method names, condition analysis, and the DI wiring
path through `heg`/`hng`/`hdg`) are documented in `tuning/DECISIONS.md`.

### Night Sight tuning campaign (first leg)

- Night Sight stacking rack mapped: frame counts, max exposure, ISO caps, sabre/shasta
  merge parameters — all in the Night profile slot (`_p1_0`).
- **N1: frame_count_ns 25→50** fired with the 2x-shots / 2x-dwell methodology:
  **-2.6% noise** vs stock 25 frames (real merge gain, exceeding internal spread), no
  color/shadow side effects, channel balance flat. 50 frames armed standing in
  `fold5_night_full.agc`.
- Remaining night levers (max_exp_ms 8000→16000, ISO cap, sabre/shasta rack) are
  darkness-gated: measured at 05:48 the scene was already twilight (ISO ~1000, 1/13s),
  so further legs resume at true dark per the campaign's light-gate discipline.

### Process hardening (root-caused from this session)

- **Write-while-running bug found and fixed:** editing prefs XML while the camera app is
  alive lets the app rewrite the file from its in-memory snapshot on next commit,
  silently deleting any keys armed while it ran. Two key losses traced to this; the
  standing discipline is now force-stop before every prefs write (all cycle scripts updated).
- Night profile crown jewels (`lib_shadows_key_p1_0=4.75`, `lib_black_point_key_p1_0=0.125`)
  restored after one such loss; verified byte-identical across every subsequent operation.
- ISO EXIF parse documented: PropertyItem 34855 is SHORT little-endian.

---

## All seven device fixes in this fork

1. `Camera.smali` — RAW size null-guard (front cam crash)
2. `qix.smali` — frame RAW max pixel-array guard
3. `Lens.smali` — drop dead logical camera 0 (unlocks the hidden lenses)
4. `CamerasFinder.smali` — getRawSizes raw-format fallback (UW/tele RAW)
5. `jba.smali` — format-array YUV fallback
6. `jsc.smali` — device-recognition abort → generic class (NS config panel crash)
7. `hnf.smali` — Night Sight panel-item always-enable (quick-menu gray-out)

All published as smali diffs in `patches/`. Full research trail in the repo:
`kernel-analysis-findings.md` (why Samsung hides the lenses), `ida-analysis/`
(cameraserver gate), `probe/` (Camera2 probe lineage), `device-evidence/`
(crash traces, logs, sample photos), `tuning/DECISIONS.md` (every A/B verdict with metrics).

## Install

1. Uninstall is NOT required — `adb install -r AGC9.2.14_v6.0.0_fold5.apk` over any
   prior build of this fork (same signature), settings preserved.
2. First launch: grant camera + storage permissions.
3. Optional configs: import `fold5_day_sun.agc` / `fold5_night_full.agc` from AGC's
   settings → config import. `fold5_night_full.agc` includes the 50-frame Night Sight stack.

## Verified state at release

- All four lenses live (main 50MP lens 56, UW 58, 3x tele 52, front 71)
- Day v3.1 standing (saturation 1.15 / contrast-black 0.50 / gamma 7 / sharpness 0.6),
  sandwich-verified on two scenes with drift-corrected metrics
- Night jewels standing (shadows 4.75 / black point 0.125), byte-verified
- Night Sight: mode entry, config panel, quick-menu all functional
- Prefs integrity verified across every install and leg this campaign (47 night-slot keys
  - full Day slot, counted and grepped every time)

## Credits

- **BigKaka** (@bigkaka, Telegram) — the AGC 9.2.14 _ruler base. This fork is his work
  plus seven device-specific fixes.
- The Fold 5 owners who reported the NS crash and gray-out — precise symptom reports
  ("config button above the exposure counter", "WB works, NS grayed") are what made the
  root-cause chain fast.

— HyperRamzey, 2026-09-15
