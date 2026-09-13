# V2 tuning pass — Day/Night profiles + 4x Night Sight exposure bank

No APK changes in this release — same `AGC9.2.14_V14.0_ruler_fold5fix_v4.3-frontfix.apk` from [v4.3.0](https://github.com/HyperRamzey/fold5-camera2-research/releases/tag/v4.3.0). What's new is a complete A/B tuning campaign for this exact device: **158 measured rows**, every step one variable at a time (apply key → cold-restart camera on lens 56 → one shot → measure luma / color balance / noise / sharpness → diff → KEEP or REVERT). Full evidence in [`tuning/`](https://github.com/HyperRamzey/fold5-camera2-research/tree/main/tuning) (`DECISIONS.md` KEEP/REVERT log + `AB_RESULTS.tsv` + the harness scripts).

## Night Sight exposure bank — up to 4x more total light

| Key | Was | Now |
| --- | --- | --- |
| `lib_max_exp_ms_key_p0_0` (per-frame exposure cap) | 4000 ms | **8000 ms** |
| `lib_shasta_max_exp_ms_key_p0_0` (shasta night-merge cap) | 4000 ms | **8000 ms** |
| `lib_pref_frame_count_zsl_key_p0_0` (Night Sight frames) | 30 | **60** |
| `lib_max_frame_count_key_p0_0` (burst ceiling) | 25 | **50** |
| `lib_max_bracketing_frames_key_p0_0` | 25 | **50** |
| `lib_max_short_frames_key_p0_0` | 25 | **50** |

All six steps functionally PASS. Honest caveat: tuned in morning light, so AE never hit the ceilings during testing — this is **banked headroom** (frames × per-frame exposure = up to 4x light for the night merge) that only engages in real darkness. Stable-light re-verification list is documented in the log.

## Feature flags — 24 probed, 38 total dispositions

- **14 kept:** anglerfish, ark, beholder_force_opt_in, falcon inner trio (force_fusion + md + tpu), force_anglerfish.RESTART, force_cuttle.extended, **gyrfalcon** (+12.3% sharp at matched light — super-res upscale actually fires), hawk_force_fusion + hawk_tpu, ica_in_front, kepler, **sabre_raw** (RAW/DNG saving enabled at zero JPEG cost)
- **7 reverted:** autobahn_options, decepticon_force_run, falcon_always_on, gouda.firefly, gouda.matting, sabre_gcam, shasta_ON — all with measured reasons in the log

## ⚠️ Camera-breakers — do NOT set these

`camera.cyclops_enabled`, `camera.falcon_enabled`, `camera.hawk_enabled` — all three block camera open on lens 56 (no crash, the open just never completes; verified three times). The `*_enabled` master gates on newer codenames are stubbed in this port; the inner functional flags (force_fusion / md / tpu) work fine. Recovery: remove the key + force-stop the app.

## Lib scalars — the "neutral" keys are live global scalars

- **7 kept:** sharpness_b 0.5 (+9.4% sharp), luma_a/luma_b/chroma_a/spatial_a/spatial_b 0.5, tone 1.0 (best matched pair of the campaign: +6.3% sharp at flat noise)
- **7 reverted:** sharpness_a (−27.6% sharp), chroma_b, denoise master, sharp_gain 1.5 (**2.5x grain explosion** — both metrics rise and naive gates pass, but the image is visibly oversharpened), gamma, brightness, NR_adjust

## Day / Night configs — selectable in the app's own GUI

Two ways to use them:

- **In the app's own profile picker** (the patch button in the viewfinder): rows 1/2 are **"Day (sun)"** / **"Night (full tune)"** — one tap to switch. Rows 3+ are empty stock slots.
- **As config files**: Settings → Configs, LUT, Libraries, AWB, NoiseModel file → Import → `/Download/AGC.9.2/configs/` → `fold5_day_sun.agc` / `fold5_night_full.agc` (attached below). The **Save** button snapshots your own tweaks as a new `.agc` in the same folder.

⚠️ Do not use the built-in **Ka** rows (KaNight/KaDay) — they're RAM patch bundles compiled for the stock tune and corrupt the merge on a tuned config (measured: blown, crushed output).

**Dusk re-verify pending:** several keeps were measured under sunrise/cloud drift — ratios were consistent but treat morning numbers as directional until the stable-light pass runs (list in `tuning/DECISIONS.md`).
