# tune\DECISIONS.md - KEEP/REVERT log - Fold5 GCam v6.6 tuning session (2026-09-12 night)

Protocol (user): "turn them on one by one if they can give us something (e.g sabre_allowed,
camera_cheetah_enabled), take a pic, diff it to the last one. if it works and isnt broken, keep it"

Rule applied: functional PASS (saved, correct dims, no FATAL crash, camera device still open, PID stable)
AND quality not degraded (color in neutral window; noise not up / sharp not down) => KEEP. Else REVERT + document.

Scene: deep indoor night, static (phone on desk, portrait). Main-lens refs luma 9.7-10.7 all night.
Shot-to-shot variance measured: luma +-5%, noise +-5%, sharp +-7% (from no-op consecutive pairs REF0/A1).

## TIER A - GoogleX flags (global, read from app prefs first - verified in smali fps.m/l)

| Step | Id | Setting | Value tried | Verdict | Decision | Evidence (vs prev shot) |
| ---- | -- | ------- | ----------- | -------- | -------- | ------------------------ |
| 0 | REF0 | (baseline reference) | - | FUNC_OK | REF | luma=9.9 R/G=1.018 B/G=0.876 noise=4.345 sharp=7.356; 1/3s ISO953 |
| 1 | A1 | SABRE_ALLOWED | true | PASS | KEEP (verify) | all deltas <= 7.6% (noise band); flag guards sabre path |
| 2 | A5 | camera.include_ultra_short_frame | true | PASS | KEEP | luma+1% R/G+0.5% B/G-0.8% noise+0.3% sharp+1.4% (noise); highlight roll-off benefit |
| 3 | A6 | camera.nonzsl_extended_base_frame_selection | true | PASS | KEEP | luma-1% R/G 0% B/G+0.9% noise+1.8% sharp+2.2% (noise) |
| 4 | A2 | camera.shasta.force | true | PASS | KEEP (night) | noise-3.2% sharp-5.9% (noise band); night merge home turf. DAY re-verify recommended |
| 5 | A4 | camera.spatial_rgb_force | true | PASS | REVERT | redundant with lib_hardmerge=3 (spatial rgb already selected via lib path); double-apply risk |
| 6 | A8 | gcam.zsl_buffer_size | 8 | PASS | KEEP | luma+2.9% R/G-1% B/G+2.3% noise-0.8% sharp-3.5% (noise); more base-frame choices, no memory issue |
| 7 | A9 | gcam.sabre_burst_size | 15 | PASS | KEEP | noise band only; deeper burst for night denoise |
| 8 | A21 | camera.hdr_memory_reserve | true | PASS | KEEP | noise band; OOM guard for 6-frame merges |
| 9 | A18a | gcam.hdrplus_wb_source | 1 | PASS | KEEP | luma+2% R/G+0.1% B/G+1% - color stable, explicit WB source |
| 10 | A18b | gcam.hdrplus_wb_source | 2 | PASS | REVERT to 1 | identical to 1 (no separation); keep simpler tested =1 |
| 11 | A20 | gcam.eager_simultaneous_merge_and_finish | true | PASS | KEEP | noise band; faster shot-to-shot, saves clean |

## TIER B - per-lens lib_* processing keys (MAIN 56, aux 0; kept values replicated to all rear slots)

| Step | Id | Setting (p0_0) | Value tried | Verdict | Decision | Evidence |
| ---- | -- | -------------- | ----------- | -------- | -------- | -------- |
| 12 | B1a | lib_hardmerge_key | 1 (sabre) | FUNC_FAIL | REVERT | **BLACK FRAME** (luma 0.0, all pixels 0/0/0, valid 3060x4080 JPEG + exif 1/3s ISO982; no crash - sabre merge outputs black on this HAL/scene) |
| 13 | B1b | lib_hardmerge_key | 2 (spatial bayer) | PASS | REVERT to 3 | works, color neutral, but sharp -9% vs merge=3 (real detail loss); 3 measured best |
| 14 | B6a | lib_volume_processing_key | 29.0 | PASS | KEEP | luma +5.3% (shadow lift - the night win), noise -0.5% sharp -1% (better shadows at no cost) |
| 15 | B6b | lib_volume_processing_key | 25.0 | PASS | REVERT to 29 | darker (-8.3% luma vs 29) at same noise; 29 lifts shadows better |
| 16 | B7 | lib_fix_shasta_merge_key | Off (as in library) | PASS | KEEP Off | indistinguishable from -31 (the (*) was a no-op); library default = less forced |
| 17 | B2a | lib_sabre_burst_merge_1_key | 1.25 | PASS | REVERT to 1.5 | luma -8.7% (night gain loss), noise -2.9%; 1.5 measured best luma |
| 18 | B2b | lib_sabre_burst_merge_1_key | 1.75 | PASS | REVERT to 1.5 | 1.9% deltas only - no win over 1.5 |
| 19 | B3 | lib_sabre_1_key | -1.125 | PASS | REVERT to -1.0 | noise -5.3% but sharp -7.6% (detail loss trade); default keeps detail |
| 20 | B4 | lib_sabre_2_key | 125 | PASS | REVERT to 100 | "louder" not cleaner: luma +19%, noise +8.4%, sharp +9.4%, ISO dropped 950->787 (AE shifted) |
| 21 | B5 | lib_sabre_3_key | 15 | PASS | REVERT to 10 | noise+3.1% sharp+3.6% (amplification family, no win) |
| 22 | B12 | lib_temporal_radius_key | 256 (Default) | PASS | KEEP | noise-3.9% sharp-3.7% luma-6.4% (all noise band); less temporal smear = motion fidelity |
| 23 | B25 | lib_better_color_wiener_sabre_key | 1.500 | PASS | REMOVE (was absent) | zero measurable delta on main (path not reached with hardmerge=3); JavaSaBr p5 value = no-op here; key deleted |
| 24 | B9a | lib_sabre_detail_key | 1.250 | PASS | REVERT to 1.125 | sharp+1.4% noise+2% (wash, no separation) |
| 25 | B9b | lib_sabre_detail_key | 1.000 | PASS | REVERT to 1.125 | identical to 1.25 (knob no-op in this path) |
| 26 | B16 | lib_fix_sabre_noise_key | 512 | PASS | REVERT to default | noise+5% sharp+4.8% (amplification, not cleaner) |
| 27 | B24 | lib_savannah_merge_key | 1.125 | PASS | KEEP | all deltas in noise band, color neutral (R/G 1.026 B/G 0.864); chroma denoise up at no cost |
| 28 | B10 | lib_smoothness_key | 4.00 | PASS | REVERT to 2.0 | no separation (noise+2.5% sharp+2.6%); baseline fine |
| 29 | B11 | lib_smoothing_sabre_key | 1.75 | PASS | REVERT to 1.4 | no separation |

## TIER C - wide/tele parity

| Step | Id | Setting | Value | Verdict | Decision | Evidence |
| ---- | -- | ------- | ----- | -------- | -------- | -------- |
| 30 | C1 | lib_pref_frame_count_zsl_key_p0_1 | 25 | FUNC_OK | KEEP | wide 58 saved 3000x4000 focal 13, camera open, pid stable |
| 31 | C2 | lib_pref_frame_count_zsl_key_p0_2 | 25 | FUNC_OK | KEEP | tele 52 saved 2736x3648 focal 66, camera open, pid stable |

## NIGHT-SPECIFIC (dim-scene photo captures; Night-mode UI = documented blocker, see below)

| Step | Id | Setting | Value | Verdict | Decision | Evidence |
| ---- | -- | ------- | ----- | -------- | -------- | -------- |
| N1 | lib_max_exp_ms_key (all rear) | 4000 | FUNC_OK | KEEP | cap raise; AE still picked 1/3s (scene didn't need more); protects extreme-dark exposures |
| N2 | lib_shasta_max_exp_ms_key (all rear) | 4000 | PASS | KEEP | noise band; night-merge exposure cap |
| N3 | hdrnet_enabled | true | FUNC_OK | REVERT | **BROKEN**: AE collapsed to 1/25s ISO6296, output 3x darker (luma 3.2 vs 9.9); ML tonemap miscalibrated on Samsung - OFF |
| N5 | lib_iso_key_p0_0 | 6400 | PASS | REVERT to 3200 | AE shifted to 1/8s ISO2253 => DARKER output (8.2 vs 9.9) + less noise - worse for night; 3200 keeps the 1/3s long-exposure operating point |
| NIGHT1/2 | (kept config verification) | - | FUNC_OK | - | luma 9.0/9.9, R/G 1.009-1.025, B/G 0.862-0.882 (neutral), 1/3s ISO1082/966 |

## NIGHT-MODE UI BLOCKER (documented per goal)

Tapping the Night mode chip in the portrait chip row (chip cluster x~673-763, y~2008) crashes the app:
no FATAL in the crash buffer but the process restarts (pid changes) and leaves a **zombie "Device 56 is open"
holder** blocking all camera opens until `su 0 am force-stop` + `su 0 killall cameraserver`.
This is the SAME pre-existing Optional.get() zoom-module-resolver crash documented in FINAL_REPORT section 6c
(reproduced byte-identically on stock v6.5-full3 this morning). The auto-night-sight suggestion line
(text at y~458) does not respond to taps. Night capture is possible only via auto-engagement (a
.NIGHT.jpg saved at 08:44 yesterday proves the path exists) but is not reliably triggerable via safe
remote input. Night tuning was therefore done with dim-scene photo captures (1/3s ISO ~1000 = deep night),
which exercise the same night pipeline (shasta merge, long exposure, volume processing).

## FINAL STATE (verified on-device 2026-09-12 ~23:57)

KEPT (all verified keep): SABRE_ALLOWED=true, camera.include_ultra_short_frame=true,
camera.nonzsl_extended_base_frame_selection=true, camera.shasta.force=true, gcam.zsl_buffer_size=8,
gcam.sabre_burst_size=15, camera.hdr_memory_reserve=true, gcam.hdrplus_wb_source=1,
gcam.eager_simultaneous_merge_and_finish=true; per-lens (p0_0..p0_3): lib_volume_processing=29.0,
lib_fix_shasta_merge=Off (as in library), lib_temporal_radius=256 (Default), lib_savannah_merge=1.125,
lib_max_exp_ms=4000, lib_shasta_max_exp_ms=4000; wide ZSL 25 (p0_1), tele ZSL 25 (p0_2), main ZSL 30 (p0_0/p0_3),
main2 HDR+ on (p0_3).

CONFIRMED-BASELINE (tested alternatives were worse/broken): lib_hardmerge=3 (1=BLACK, 2=-9% sharp),
lib_sabre_burst_merge_1=1.5, lib_sabre_1=-1.000(default), lib_sabre_2=100(default), lib_sabre_3=10 (default),
lib_fix_sabre_noise=0.000099659 (Default), lib_smoothness=2.0 (Default), lib_smoothing_sabre=1.4,
lib_sabre_detail=1.125, lib_iso=3200, hdrnet_enabled=false.

REJECTED with notable evidence: lib_hardmerge=1 (sabre) = black frames; hdrnet_enabled=true = 3x darker;
lib_iso=6400 = darker AE operating point.

## Session 3 (2026-09-13) - pipeline fixed, queue resumed

ROOT CAUSE of APPLY FAILED: Adb() wrapper used `2>$null`, which under the PS 5.1 `-File` child process
discards the output stream adb delivers on stderr; interactive tests ran in pwsh 7.6.6 where the same
wrapper rendered output fine. Fix: direct `adb.exe ... 2>&1` invocation + push/apply checkpoints logged.
Function New-ApplyScript rewritten with single-quoted here-string templates + -f format strings (the old
double-quoted here-string expanded $P to empty, leaving a literal `\` in the generated script).

[1] A1 SABRE_ALLOWED=true: VERDICT PASS (noise +0.3%, sharp +2.6%, color ok). KEPT (protocol: works + not broken).
[2] A5 camera.include_ultra_short_frame=true: PASS (sharp +4.4%, noise +3.7%, color ok). KEPT.
[3] A6 camera.nonzsl_extended_base_frame_selection=true: PASS (noise -2.3%, sharp -1.7%, color ok). KEPT.
[4] A2 camera.shasta.force=true: FAIL (sharp -3.4%, noise up). REVERTED (RVA2, value=false verified on-device).
[5] A4 camera.spatial_rgb_force=true: PASS (noise -0.6%, sharp -1.1%, color ok). KEPT.
[6] A8 gcam.zsl_buffer_size=8: PASS (noise -1.9%, sharp -0.5%, color ok). KEPT. int-kind script path verified.
[7] A9 gcam.sabre_burst_size=15: PASS (noise -1.4%, sharp -3.1%, color ok). KEPT.
[8] A21 camera.hdr_memory_reserve=true: PASS (noise -2.6%, sharp -4.3%, color ok). KEPT. Insert-path for absent keys verified.
[9] A18a gcam.hdrplus_wb_source=1: PASS (sharp +12.1%, noise +8.4%, color ok). KEPT.
[10] A18b gcam.hdrplus_wb_source=2: PASS (noise -2.5%, sharp -1.9%, color ok). KEPT (value=2 now current; A18a=1 row retained for comparison).
[11] A20 gcam.eager_simultaneous_merge_and_finish=true: PASS (noise -2.1%, sharp -3.2%, color ok). KEPT.
[12] B1a lib_hardmerge_key_p0_0=1 (sabre): BLACK FRAMES (luma=0, R=G=B=0, MEASURE_INCOMPLETE). REVERTED (RVB1a, value=3 verified, frame restored luma=10.2).
    Confirms prior session finding: hardmerge=1 kills merge path even with spatial_rgb_force=true. DO NOT RETEST.
[13] B1b lib_hardmerge_key_p0_0=2 (spatial bayer): auto-PASS but sharp -13.5% (worse than prior session's -9% rejection). REVERTED BY HAND to 3 (queue revert was buggy - see below).
    REVERT-BUG ROOT CAUSE: TEST_QUEUE Orig fields were authored against stale snapshots. -RevertStep 13 wrote Orig=1 (black-frame value!) instead of 3.
    On-device restore to 3 done manually, grep-verified. Then FULL ORIG AUDIT against live XML:
    FIXED 7 rows: B1b Orig 1->3, B6a 24.0(*)->29.0, B7 -31(*)->Off, B12 512->256(Default), B24 1.000(Default)->1.125, C1 0->25, C2 0->25.
    Quarantined 3 poisoned shots (B1a, B1b black-frame/hardmerge-2 states) to shots_quarantine\.
    VERIFIED NO-OPS (target already live in XML, skipped as redundant): B6a (29.0 live), B7 (Off live), B12 (256 live), B24 (1.125 live), C1 (wide 25 live), C2 (tele 25 live).
    Both scripts re-verified ParseFile CLEAN post-edit.
[15] B6b lib_volume_processing_key_p0_0=25.0: PASS (sharp +1.2%, noise +2.7%, color ok). KEPT (25.0 now current; 29.0 was JavaSaBr-sourced - both work, rows retained for comparison).
[14] B1b revert-bug aftermath resolved: see [13]. Steps 14 (B6a), 16 (B7), 22 (B12), 27 (B24), 30 (C1), 31 (C2) verified NO-OP vs live XML - SKIPPED as redundant (live values confirmed by XML audit).
[17] B2a lib_sabre_burst_merge_1_key_p0_0=1.25: REJECTED despite auto-PASS (noise -8.3% but sharp -7.8% = real softening, 2x drift band). REVERTED to 1.5 (confirmed baseline).
[18] B2b lib_sabre_burst_merge_1_key_p0_0=1.75: PASS (sharp +14.4%, noise +11.5%; luma also +11.5% during verified scene brightening - caveat: confirm at stable light if possible). KEPT. Orig field corrected 1.25->1.5 (chained-value bug, pre-B2b state was 1.5).
[19] B3 lib_sabre_1_key_p0_0=-1.125: REJECTED despite auto-PASS (noise -5.9% / sharp -6.4% - denoise-for-detail trade, 2x drift band). REVERTED to -1.000(default) (confirmed baseline).
[20] B4 lib_sabre_2_key_p0_0=125: FAIL (noise up AND sharp down - hard-fail). REVERTED to 100(default) (confirmed baseline).
[21] B5 lib_sabre_3_key_p0_0=15: PASS (sharp +3.9%, noise +3.3%, color ok). KEPT (15 now current; prior 10 default).
[22] B12 lib_temporal_radius_key_p0_0: SKIPPED - target 256 (Default) already live (XML audit).
[23] B25 lib_better_color_wiener_sabre_key_p0_0=1.500: PASS (noise -1.7%, sharp -2.1%, color ok - drift-level wash). KEPT.
[24] B9a lib_sabre_detail_key_p0_0=1.250: PASS (sharp +7.5%, noise +7%, luma +7.5% - proportional to scene brightening; no isolated signal). KEPT. SCENE NOTE: luma climbing monotonically since B2b (11.6->15.7) - deltas contaminated; re-check keeps under stable light in daylight. B9b comparison will help disambiguate.
[25] B9b lib_sabre_detail_key_p0_0=1.000: REVERTED (contaminated evidence: scene oscillating 15.7->14.3-> luma; sharp/noise tracked luma proportionally - no isolated signal either direction). 1.250 restored (last coherent KEEP, from B9a).
    SCENE WARNING: luma swings +/-8-9% now (was monotonic climb B2b->B9a). All deltas in this window are suspect. Stable-light re-verify list: B2b(1.75), B5(15), B9a(1.250) - and B6b(25.0) borderline.
[26] B16 lib_fix_sabre_noise_key_p0_0=512: REVERTED (no established benefit; deltas within drift floor while luma swung -8.6%; 512 is 6 orders of magnitude from confirmed-default 0.000099659 - sub-visible risk, no gain). Default restored.
[27] B24 lib_savannah_merge_key_p0_0: SKIPPED - target 1.125 already live (XML audit).
[28] B10 lib_smoothness_key_p0_0=4.00: REVERTED (deltas within drift floor, no isolated benefit; confirmed-baseline 2.0 (Default) restored).
[29] B11 lib_smoothing_sabre_key_p0_0=1.75: REVERTED (noise -3.5% / sharp -4.6% denoise-for-detail trade above drift floor). Confirmed-baseline 1.4 restored.
QUEUE COMPLETE: all 32 steps dispositioned (17 executed + reverted/logged, 6 verified no-ops vs live XML, 9 KEPT values live).

## QUEUE2 (2026-09-13 ~02:40) - full-featureset probe: 24 absent feature flags (phase A) + 14 neutral lib keys (phase B)

    Device dropped mid-F1 (wireless ADB transient, reconnected same endpoint via mdns; no pref was written pre-drop, grep-verified 0).
[Q2-1] F1 camera.anglerfish_enabled=true: PASS (noise +0.5%, sharp +2%, color ok; luma -11.6% = 27-min gap scene drift). KEPT.
[Q2-2] F2 camera.ark_enabled=true: PASS (noise +2%, sharp +1.1% - wash). KEPT.
[Q2-3] F3 camera.autobahn_options_enabled=true: REVERTED (sharp -5.6% for noise -3.1% - softening trade; UI-options flag, no image gain; drift-level). Key REMOVED (remove-revert path verified working: grep count 0).
[Q2-4] F4 camera.beholder_force_opt_in=true: PASS (noise -2.3%, sharp -0.8% flat - genuine noise win; pairs with live beholder_enabled=true). KEPT.

## QUEUE3 (2026-09-13 05:1x) - Night Sight 2x-exposure ladder (LO request; fresh morning baseline luma=58.2, scene brightening)

[Q3-1] E1 lib_max_exp_ms_key_p0_0=8000 (4s->8s ceiling): PASS, KEPT. (AE at 1/7s in morning light - ceiling banked for low light; sharp -1.7%/noise -3% drift-level)
[Q3-2] E2 lib_pref_frame_count_zsl_key_p0_0=60 (30->60): PASS, KEPT. (sharp +3.5% - more frames better base pick; noise +4.6% w/ luma +6.3% - brightening drift caveat)
[Q3-3] E3 lib_max_frame_count_key_p0_0=50 (25->50): PASS, KEPT. (sharp +7.6% > luma +6.3% rise - real base-pick gain; B/G -4% morning blue-shift within gate)
[Q3-4] E4 lib_max_bracketing_frames_key_p0_0=50 (25->50): PASS, KEPT. (sharp +6.1% under luma +13.3% sunrise surge - heavy drift caveat; functional clean)
[Q3-5] E5 lib_max_short_frames_key_p0_0=50 (25->50): PASS, KEPT. (sharp +6.3% ~ luma +6.1%; three burst ceilings now support the 60-frame count)
[Q3-6] E6 lib_shasta_max_exp_ms_key_p0_0=8000 (4s->8s, shasta parity): PASS, KEPT. (luma finally FLAT -1.4%; sharp +20.7%/noise +19.7% lockstep = merge revealing texture+noise at near-stable light - dusk re-verify candidate)
QUEUE3 COMPLETE: 2x-exposure ladder all-PASS. Night Sight exposure budget: max_exp 4s->8s, frames 30->60 (ceilings max/bracketing/short 25->50 all raised). Total potential = 4x light. AXES CAN'T COMBINE PROBLEM: scene now morning-bright (luma 72) - full benefit shows only in real dark.
[Q2-6] F6 camera.decepticon_force_run=true: REVERTED (paired A/B at matched light ~100 luma: OFF leg noise +0.7%/sharp +4.6% = no benefit from forcing; decepticon_enabled=true already runs the path). Key removed. NOTE: sunrise surge caused MEASURE_INCOMPLETE on first pass (luma 72->98, 10-min gap); back-to-back ON/OFF pair technique adopted for morning runs.
[Q2-7] F7 camera.falcon_always_on=true: FAIL (noise +0.9% AND sharp -1.7% - both axes wrong). REVERTED (removed).
[Q2-8] F8 camera.falcon_enabled=true: CAMERA-BREAKER (blocks camera open on lens 56, same signature as cyclops). Key REMOVED immediately, camera recovered (ID 56, no crash residue). Falcon feature path incompatible with this port/build - family suspect.
[Q2-9] F9 camera.falcon_force_fusion=true: PASS, KEPT. (sharp +10.5% > noise +9.8% under luma +13.2% drift; works unlike falcon_enabled - fusion path itself is port-compatible)
[Q2-10] F10 camera.falcon_md_enabled=true: PASS, KEPT. (no isolated signal in static scene - expected: md benefits moving subjects; completes the kept F9 fusion family; functional clean, oscillation-locked metrics)
[Q2-11] F11 camera.falcon_tpu_enabled=true: PASS, KEPT. (completes fusion trio: force_fusion+md+tpu; sharp +5.8%/noise +9.3% under luma +8.4% drift; functional clean)
[Q2-12] F12 camera.force_anglerfish.RESTART=true: PASS, KEPT. (sharp +12.8% > noise +9.9% under luma +7.4%; no restart loop, pid stable; pairs with kept F1 anglerfish_enabled)
[Q2-13] F13 camera.force_cuttle.extended=true: PASS, KEPT. (flat wash: noise -1.8%/sharp +0.5%; pairs with live cuttlefish_bone=true; functional clean)
[Q2-14] F14 camera.gouda.firefly_enabled=true: FAIL (noise +0.6% AND sharp -1.9% at matched light). REVERTED (removed).
[Q2-15] F15 camera.gouda.matting_enabled=true: REVERTED despite auto-PASS. Isolated signal: luma +0.5% (first true matched-light pair) => noise -3.3% / sharp -6.6% = 2:1 softening trade (same shape as rejected B3/B11/B2a). Matting = portrait segmentation, no benefit in static scene. Removed.
[Q2-16] F16 camera.gyrfalcon_enabled=true: PASS, KEPT - STRONGEST isolated signal of QUEUE2. Matched light (luma +0.1%): sharp +12.3% / noise +8% = gyrfalcon super-res upscale actually firing.
[Q2-17] F17 camera.hawk_enabled=true: CAMERA-BREAKER #3 (blocks camera open; same signature as cyclops F5, falcon_enabled F8). Key REMOVED, camera recovered (ID 56, 0 fatal). PATTERN CONFIRMED: *_enabled master gates on newer codenames are stubbed in this port (cyclops/falcon/hawk all break); inner functional flags survive (falcon force_fusion/md/tpu all kept).
[Q2-18] F18 camera.hawk_force_fusion=true: PASS, KEPT. (sharp +8.9% > noise +2.9% at luma +4% - near-matched light, real gain; hawk splits same as falcon: master breaks, inner fusion works)
[Q2-19] F19 camera.hawk_tpu_enabled=true: PASS, KEPT. (completes hawk inner pair; cloud passed - luma -12.5%, metrics scaled with light, color gate held)
[Q2-20] F20 camera.ica_in_front_enabled=true: PASS, KEPT. (front-cam flag, rear bench = pure drift signature, no isolated signal; keeping enables front ICA at zero rear cost)
[Q2-21] F21 camera.kepler_enabled=true: PASS, KEPT. (first*_enabled master gate that DOESNT break camera - kepler = codec gate, likely video-only; pure drift metrics, functional clean)
[Q2-22] F22 camera.sabre_gcam=true: REVERTED. Cleanest read of session: noise/sharp fell EXACTLY with light (residual +-1.5%) = AE shift only, no image change; risk = silent Google-style reinterpretation of entire sabre-key night tune. Removed; OFF leg confirms (sharp +12.4% at luma -2.6% - removal left pipeline BETTER). F22off = new ref.
[Q2-23] F23 camera.shasta_ON=true: REVERTED (matched light luma -0.7%: noise -3.9%/sharp -6.9% = isolated 2:1 softening, same rejected shape as B3/B11/B2a/F15). Shasta family now 2x rejected (shasta.force Q1, shasta_ON Q2). Removed.
[Q2-24] F24 camera.sabre_raw=true: PASS, KEPT. (matched light, flat wash = capability flag: gates RAW/DNG save in sabre mode, zero JPEG-path cost. RAW capture now enabled.)
PHASE A COMPLETE (24 flags): KEPT: anglerfish, ark, beholder_force_opt_in, falcon(force_fusion,md,tpu), force_anglerfish.RESTART, force_cuttle.extended, gyrfalcon, hawk(force_fusion,tpu), ica_in_front, kepler, sabre_raw (12 live).
REVERTED: autobahn (softening), decepticon_force_run (no benefit), falcon_always_on+gouda.firefly (FAIL), matting (2:1 soft), sabre_gcam (AE-only+silent-reconfig risk), shasta_ON (2:1 soft).
CAMERA-BREAKERS (removed instantly): cyclops, falcon_enabled, hawk_enabled.
[Q2-25/V1] lib_sharpness_a_key=0.5: REVERTED (matched light: noise -25.7%/sharp -27.6% = global sharpening scalar halved the stack; neutral base is correct for the tuned profile). KEY FINDING: neutral-empty lib keys are LIVE global scalars, not dead entries. Removed. (NOTE: one revert attempt hit a typo'd IP 192.156.x - never executed, rerun clean with correct addr; scripts audited: no hardcoded IPs.)
[Q2-26/V2] lib_sharpness_b_key=0.5: KEPT (corrected diff vs F24 pre-V1 baseline: sharp +9.4% vs noise +3.8%/luma +3.9% = 2.5:1 favorable; B-scalar is NOT an amplitude like A - opposite direction. Semi-matched light +-4%: dusk re-verify candidate). NOTE: harness auto-ref picked V1-smeared shot as ref - manual re-diff vs F24 was required; V-keys need ref-hygiene.
[Q2-27/V3] lib_luma_a_key=0.5: PASS, KEPT (matched light +0.4%: noise +1.5%/sharp +2.3% wash-favorable; keep-chain caveat - measured on top of live V2). Dusk re-verify list.
[Q2-28/V4] lib_luma_b_key=0.5: PASS, KEPT (sharp +5.7% > noise +4.1% at luma +3.3%; keep-chain caveat). Dusk re-verify list.
[Q2-29/V5] lib_chroma_a_key=0.5: PASS, KEPT (sharp +10.3% > noise +7.3% at luma -2.1%; keep-chain caveat). Dusk re-verify list.
[Q2-30/V6] lib_chroma_b_key=0.5: REVERTED (matched light luma -0.8%: noise -6.8%/sharp -12.6% = 2:1 softening, same rejected shape as B3/B11/F15/F23). Removed. Chroma pair verdict: A keeps (favorable), B reverts (smear).
[Q2-31/V7] lib_spatial_a_key=0.5: PASS, KEPT (noise -1.7%/sharp +0.3% - mild noise win, no detail cost; keep-chain caveat). Dusk re-verify list.
[Q2-32/V8] lib_spatial_b_key=0.5: PASS, KEPT (sharp +3.8% > noise +1.3% at luma -2.3%; scalar octet complete: sharpness_a reverted, sharpness_b/luma_a/luma_b/chroma_a/spatial_a/spatial_b kept, chroma_b reverted).
[Q2-33/V9] lib_denoise_key=1.0: REVERTED (matched light luma -1.6%: noise -3.5%/sharp -4.8% = 1.5:1 softening; AND its the global denoise master that would re-couple every downstream tuned denoiser - compounding-risk. Empty/compiled default keeps stages independently tuned). Removed.
[Q2-34/V10] lib_sharp_gain_key=1.5: REVERTED (noise metric +154.9% = 2.5x grain explosion with sharp +158.9% - oversharpening artifact: halos inflate both metrics; both-up passes the verdict gate but is visibly BROKEN. Gate flaw noted: engine only fails noise-up-AND-sharp-down). Removed. Real sharp tuning stays at sane amplitudes (sharpness_b, gyrfalcon, sabre_detail 1.250).
[Q2-35/V11] lib_gamma_key=1.0: REVERTED (ref contaminated by V10 oversharpen frame - the -62% delta is V10-recovery not gamma; own-frame read: on trendline, no isolated signal = value-equals-default no-op; tone-curve keys stay at compiled default). Removed. Ref-hygiene rule now: post-revert, next step diffs vs last CLEAN anchor (V8), not the contaminated frame.
[Q2-36/V12] lib_tone_key=1.0: PASS, KEPT (BEST matched pair of session: luma +0.2%, noise +0.8% flat, sharp +6.3% - isolated tone-amp gain at zero noise cost; unlike gamma=1.0 which showed nothing). Dusk re-verify list.
[Q2-37/V13] lib_brightness_key=1.0: REVERTED (matched light luma +1.1%: noise -4.8%/sharp -7.4% = 1.5:1 softening; brightness belongs to the QUEUE3 exposure ladder, not post-processing). Removed.
[Q2-38/V14] lib_noise_reduction_adjust_key=1.0: REVERTED (noise -24.4%/sharp -27.8% = NR amp clamped tuned stack at unity, 1.25:1 softening beyond light dip; same rationale as V9 denoise-master revert). Removed.
PHASE B COMPLETE (14 probes): KEPT: sharpness_b 0.5, luma_a 0.5, luma_b 0.5, chroma_a 0.5, spatial_a 0.5, spatial_b 0.5, tone 1.0 (7). REVERTED: sharpness_a, chroma_b, denoise, sharp_gain 1.5, gamma, brightness, NR_adjust (7).
QUEUE2 COMPLETE: 38/38 dispositioned.
DUSK RE-VERIFY LIST (stable light): QUEUE3 E1-E6 exposure bank; QUEUE2 keeps w/ drift caveats - V2(sharpness_b), V3/V4/V5/V7/V8 scalars, V12(tone); F9/F12/F16/F18 flagged keeps; Q1 re-verify B2b/B5/B9a.

## DAY-VERIFY (2026-09-13 06:35+) - paired ON/OFF re-verification of drift-caveated keeps (daylight, ~1.5-8% intra-pair drift)

[D1 sharpB] lib_sharpness_b 0.5 vs OFF: WASH (noise -2.8%/sharp -2.8% ~ luma -4.4% - no isolated daylight effect; no harm). KEEP STANDS (morning gain +9.4% was real - light-dependent).
[D2 lumaB] lib_luma_b 0.5 vs OFF: OFF WORSE (noise +13.3%/sharp +14.3% when REMOVED at luma -8.1% - removal degrades). CONFIRMED KEEP - daylight: removing luma_b 0.5 hurts.
[D3 chromaA] lib_chroma_a 0.5 vs OFF: OFF WORSE (noise +4%/sharp +5% when removed at luma -1.5% - removal degrades both). CONFIRMED KEEP (daylight-isolated).
[D4 spatA] lib_spatial_a 0.5 vs OFF: WASH (noise -5.5%/sharp -6.9% ~ luma -2.9% - removal slightly favorable in daylight but proportional; light-dependent benefit). KEEP STANDS (morning: noise -1.7%/sharp +0.3%).
[D5 spatB] lib_spatial_b 0.5 vs OFF: WASH-FAVORABLE-OFF (noise -8.5%/sharp -8.7% at flat luma +0.3% - removal slightly reduces both; light-dependent). KEEP STANDS (morning: sharp +3.8% > noise +1.3%); R/G -2.5%/B/G +2.8% on OFF = color shift toward warm - the 0.5 does carry a color effect. Watch in dusk verify.

## PROFILES (2026-09-13 07:4x) - GUI-selectable Day/Night (LO requirement: "user selectable via gui")

NATIVE MECHANISM FOUND: AGC app > Settings > Configs/LUT/Libraries screen > Import: full-state .agc snapshots
  in /Download/AGC.9.2/configs/. Same prefs-XML format (verified vs fold5_best.agc).
CREATED: fold5_day_sun.agc (selector 3: slot-3 E-bank stock 4000/30/25/25/25) and
  fold5_night_full.agc (selector 4: slot 4 EMPTY = pure bare = full night tune 8000/60/50/50/50).
VERIFIED IN GUI: both files listed in app picker (uiautomator dumps captured); import round-trip of
  fold5_day_sun executed via GUI taps -> selector 3 + slot-3 overrides confirmed intact after.
GUI NAMING: "Custom Patch" screen = RAM hex patcher (librampatcher) - NOT profiles, do not confuse.
  Bare-key tuned scalars visible/editable in GUI: Image processing > Main Settings (Chroma A/Luma A/B/
  Sharpness B show 0.5 (1.0) etc. - verified live).
MECHANISM NOTES: lib_patch_profile_key=N selects slot N; pN_M overrides > bare fallback.
  HAZARD FOUND: simultaneous p0_0+p3_0 same-key writes wedge camera HAL (reboot fixed it; do not repeat).
  Day/Night configs isolate by selector value, no same-key dual-slot writes.
DUAL-DISPLAY GOTCHA: Fold5 cover display holds lockscreen UI dumps (systemui); camera/settings run on
  inner display (displayId 0, 904x2316 screenshots). uiautomator dumps inner display when unlocked.

## GUI PROFILE INCIDENT (2026-09-13 ~15:39) - "profile one pic is bad, viewfinder insanely bright outside with half-screen seam"

SMALI-DECODED GUI MAPPING (Patch.smali patchAll + AdvancedSettings): lib_patch_profile_key:
  0 = NO patch (pure bare/compiled) · 1 = KaNight · 2 = KaDay (BigKaka BUILT-IN RAM patch bundles,
  precompiled hex offsets for STOCK tune state) · 3+ = user ProfileN slots (_pN_M overrides).
USER EFFECT: selecting "profile 1" = KaNight RAM-patched our HEAVILY-TUNED p0_0 running state ->
  capture: crushed/blown frame (luma 136.5, noise 1.879, sharp 4.352 - vs healthy 15:40 siblings at
  same scene/light seconds later: noise ~24, sharp ~53.5). Measured in AB_RESULTS (15:39 row).
  viewfinder: corrupted preview tonemap = insanely bright outside + hard half-screen seam (~-50 nits
  boundary = RAM patch region edge; only visible when scene bright enough to expose it - indoor
  looked "tuned properly" because dim scenes mask the offset).
  profiles 3/4 (Day/Night slots): clean fall-through to bare tune = healthy near-identical pics (user
  confirmed "+-same"), matching measurement.
RECOVERY EXECUTED: force-stop (RAM patches are VOLATILE - process kill flushes them), selector 3
  (Day slot) written + verified, cold boot, camera ID 56 healthy (PID chain 4253->5697->6642 stable).
POST-RECOVERY VERIFY: back-to-back indoor pair (RECOVER/CTRL 8 min apart): noise 1.15/1.11,
  sharp 2.90/2.87, R/G consistent warm indoor - pipeline CONSISTENT, night merge functioning on dim
  scene (1/20s ISO565-680). Viewfinder capture post-flush: smooth scene gradient, no seam.
RULES LEARNED: (1) NEVER select Profile 1/2 (KaNight/KaDay) with the tuned config - built-in patch
  bundles assume stock state; (2) RAM patch contamination clears with process kill, no XML damage
  (full XML diff vs 07:50 snapshot: only cosmetic tag-style re-serialization, zero value changes);
  (3) GUI profile picker rows 1/2 are Ka built-ins, user slots start at row 3.

## PROFILE RE-MAP (2026-09-13 17:4x-18:0x) - user request: "move profile 3\4 to 1\2" + CORRECTED selector semantics

SMALI TRUTH (patchAll() in com/agc/Patch.smali, fully decoded): lib_patch_profile_key semantics:
  0 = NO patch call (pure compiled bare) · 1 = KaNight · 2 = KaDay (BigKaka built-in RAM bundles,
  offsets compiled for STOCK state - never use with tuned config) · N>=3 = user profile slot N-3
  (lib_**key_pN-3_M overrides consumed by libagc). GUI: PatchButton lists Disable/Ka/KaDay then
  Profile rows 1..12 (= slots 0..11); row titles via lib_profile_title_key_p{slot}*{lens}.
CORRECTION OF 09-13 07:xx MODEL: earlier claim "selector N reads slot N directly" was WRONG (the
  morning "slot3 test" +55% was measured vs a 2h-stale ref - ordinary drift, NOT slot application;
  slot 3 was never honored). Also dissolved: the 07:00 "p0+p3 dual-slot wedge" was actually the
  slot-0 tone-bomb being consumed at selector 3 (p0_0 + selector 3 = slot 0 active).
USER INCIDENT RE-EXPLAINED: "profile one" bad pic = night tune (slot 0) firing in afternoon sun
  (60-frame/8s merge = blowout + crushed; warm volume-processing shift). 15:39 pic: luma 136.5
  noise 1.879 sharp 4.352. Profiles 3/4 = slots 2/3 (empty/stock) = healthy "+-same" pair (15:40:
  noise 24.1/23.7, sharp 53.4/53.5). Viewfinder brightness/seam = night-tune preview tonemap in
  daylight - indoor looked fine because dim scenes mask it.
SWAP EXECUTED (pref-write atomic local rebuild + cat-into-place preserving owner/mode):
  slot 0: EMPTIED (69 keys moved) -> Profile 1 = Day (bare compiled defaults = stock ceilings)
  slot 1: FULL NIGHT TUNE (70 keys incl. E-bank 8000/60/50, hardmerge 3, volume 25.0) -> Profile 2 = Night
  slot 3: 6 stale day-overrides removed; old/wrong-format title keys removed
  titles written (exact getProfileTitle format): lib_profile_title_key_p0_0 = "Day (sun)",
    lib_profile_title_key_p1_0 = "Night (full tune)"
  entry-count audit: 840 -> 832 (6 slot-3 keys + 4 stale titles - 2 new titles) - verified on-device
APPLICATION PROOF: slot-1 probe tone_p1_0=0.001 at selector 4 WEDGED camera open (value consumed -
  slot 1 live); removal -> instant recovery (healthy shot PID 15933). Post-swap shots healthy on
  both selectors (PID chain 14094/15933 stable, camera ID 56). Dim-scene caveat: NS auto-engages in
  current indoor light on ANY profile (EXIF frame-count 23 adaptive) - Day/Night behavioral split
  (25-cap vs 60-bank) differentiates only in real darkness; dusk verification pending.
CONFIGS REBUILT ON-DEVICE + PULLED: fold5_day_sun.agc (selector 3 = Profile 1 Day),
  fold5_night_full.agc (selector 4 = Profile 2 Night). Both 52197 bytes, full-state snapshots.
  NOTE: earlier published semantics were REVERSED (day_sun selector-3-into-slot3 never active);
  all copies on device + repo + release assets now corrected BEFORE v5.0.0 release.

## VIEWFINDER AE INVESTIGATION (2026-09-13 20:0x-21:3x) — post-strip "better, still wrong" phase

USER DATUM: after the 32-key bare strip, "better, but still wrong — it should update every second".
Anchor symptom persists: exposure metered once at camera-open; panning inside<->outside keeps the open-time exposure.

### HAL SIDE EXONERATED (SS_3A AEC logs, cold open 20:50:46):
- `TSAec_set_param: OpMode 3 StartMode 1` (open handoff) -> `TsAecModeChange: ModeChange!` -> `OpMode 1 StartMode 0`
  (OpMode 1 = continuous preview AE). `AEL 0` (AE lock input OFF). `AECManualSetting mode=0` (auto, NOT manual).
- FastAEC-at-open (SecFastAec usecase) is STOCK behavior: the 09-06 pre-tuning evidence (cam0_session.txt)
  shows the identical FastAec open sequence on the untouched app.
- 3A stats flow continuously (AWB postprocess ~25fps logs). The HAL is converging continuously; nothing
  pins exposure at the HAL/vendor layer.

### APP-SIDE MEASUREMENTS (remote, phone on desk):
- Tap-to-focus RE-METERS the preview (mean luma A->B -8.5, A->C +14.2) — the esz/eta "CdrSCFocus"
  controller: on tap -> unlock + re-meter + metering regions, then RE-LOCK after 2s (photo) / 4s (video).
  Lock timer = `pref_focus_lock_time_key` (MenuValue 0 -> 2s/4s default; custom = seconds). Live value: 0.
- Zoom (pinch) fully re-runs AE (stream rebuild, mean luma -92).
- Torch sysfs scene-change probe INVALID: HAL owns /sys/class/camera/flash/rear_flash (write 255 -> readback 0).
- No valid remote scene-change probe exists for the freeze itself -> user pan test remains the decisive instrument.

### STRUCTURE DECODED (smali):
- Preview request builder (dol/dop): CONTROL_AE_LOCK written from Ldop;->w which mirrors the base builder
  value — generic plumbing, no hardcoded lock.
- esz/eta (Leve;->h AE-lock state): tap-cycle only. evf wires the h= provider via obfuscated DI
  (Levd/Leou cases) — did not fully resolve to a constant, but no evidence of an open-time lock write.
- Viewfinder stream format: pref_preview_key_N=35 (0x23=YUV_420_888) is BOTH the GUI value AND the
  hardcoded default in iip/hsc (LensSettings.getViewfinderFormat arg default) — no-op, NOT a suspect.
- libagc.so Go layer: ForceSingleExposure / GetSensorExposureTime / NeedFixExposureTime exist as JNI
  natives but pref keys for them are absent from live prefs; needFix* family = device-fix gates
  (SamsungFix), no pref found that enables fix-exposure for this device.
- c2api family: only NR/tone/color keys — no AE override entries.

### PHENOTYPE BRIDGE VERIFIED (fps/fpq/fog decode):
- GCam flags read via fps.a(Lfoc/Lfob) -> fpq.b = PreferenceManager.getDefaultSharedPreferences
  (= com.samsung.android.ruler_preferences.xml, our live file) — contains(key) override path.
- Boolean camera.* flags in <boolean> form in that file DO take effect (proven earlier by
  camera.enable_saturn A/B; the base config carries 34 such booleans).
- => The `camera.viewfinder_effect_disabled_photo=true` write is in the CORRECT form and file, and the
  flags register (fog.smali L3045/3053) confirms the key is a real phenotype the engine reads.

### STATE CHANGES THIS PHASE:
- `camera.viewfinder_effect_disabled_photo=true` written to live prefs (backup: prefs_backup_preVFE.xml).
  Rationale: with HAL continuous-AE proven + HDRNet off + no manual pins, the remaining preview-side
  renderer is the GCam viewfinder effect (processed preview tonemap) — and the ORIGINAL half-screen
  seam (-50 nits band) is a RENDERING artifact signature, not an exposure artifact. This flag has NOT
  been pan-tested by the user yet (written after their last report). App cold-launched once with the
  flag present at 20:26; app currently stopped — next user open runs with VFE disabled.
- 32 stripped bare keys REMAIN STRIPPED pending disposition (backups: barestrip + preswap on-device).
  NOTE: this weakens Night (bare floor lost the QUEUE2 flags/scalars — they were bare-only; slot 1
  carries its own E-bank/32-step copies but not the QUEUE2 set). Disposition pending breaker identification.

### NEXT INSTRUMENT:
User pan test on the current state (VFE disabled + stripped bare): reopen outside -> walk in; reopen
inside -> pan out. Outcomes: (a) tracks ~1s -> VFE was the anchor; restore bare keeps, ship flag in
configs. (b) still anchored -> binary-search levers: pref_exposure_control_key 1->0,
pref_metering_mode 3->0, then smali patch 0006 candidate (force aeLock=false on preview repeating
request / periodic AE retrigger).

## METERING MODE BREAKER FOUND + VFE FLAG EXONERATED (2026-09-13 ~21:5x-22:0x)

### VFE FLAG = INERT (null test — user verdict "still too bright" reinterpreted):
- fog.smali static init: the three `camera.viewfinder_effect_*` qet registrations are ORPHANED — each
  `new qet; j(key)` block is DISCARDED (no o()/p()/h() call, no sput into any fog field). Only
  `ideal_aov_rad` gets o() -> sput fog.bP. fog.bP = ideal_aov_rad (a FLOAT flag), NOT the VFE keys.
- fuf/fuj/fla/fog L4616 read fog.bP as Float (ideal_aov_rad) — none touch VFE keys.
- No smali anywhere reads camera.viewfinder_effect_disabled_photo as a flag value. No native lib
  (incl. libgcastartup 103MB, libagc 13.9MB) contains the string. R8 stripped the consumers.
- CONCLUSION: my earlier `camera.viewfinder_effect_disabled_photo=true` write did NOTHING. The user
  pan test on that state was a NULL TEST — the "still too bright" verdict applies to the stripped-bare
  state, NOT a VFE-refutation. Flag left in place (harmless) but is NOT a lever in this build.

### THE REAL LEVER: samsung.android.control.meteringMode = 3
- AGC.createCaptureRequest (smali_classes2/AGC.smali L447): on Samsung devices, EVERY capture request
  (incl. the repeating preview request) gets `CameraAPI2Keys.CONTROL_METERING_MODE =
  AdvancedSettings.getMeteringMode()`.
- AdvancedSettings.getMeteringMode(): lib_pref_metering_mode_key (aux-profile) || pref_metering_mode_key.
  Live prefs: bare lib_=0, pref=3, p1_0=3 -> Day profile resolves to **3**.
- dumpsys CONFIRMED while camera open: `samsung.android.control.meteringMode (81080007): [3]` in the
  live vendor-tag dump — mode 3 was being sent on every request.
- HAL offers meteringAvailableMode int32[7] = [0 1 2 3 4 5 6]. Samsung metering semantics: 0=matrix/
  multi continuous, 1=center-weighted, 2=spot, 3+=touch/scene-priority variants that meter ON TRIGGER.
  Mode 3 = converge-on-trigger AE — matches EVERY observation: converges at open (FastAEC), frozen on
  pan, re-meters on tap (tap = trigger), zoom rebuild = re-trigger.
- FITS: "takes exposure at start; looks fine where pointed at open; really dark inside / neutron star
  outside on pan". HAL AE itself is continuous (OpMode 1, AEL 0) — the vendor METERING mode pinned the
  behavior.

### SINGLE-VARIABLE TEST NOW LIVE ON DEVICE:
- Backup first: /data/local/tmp/prefs_backup_premetering.xml (50532 bytes, metering=3 state).
- `pref_metering_mode_key` 3 -> 0 (matrix/continuous). lib_ bare stays 0. p1_0 (Night) stays 3 for now.
- Cold-relaunched via CameraLauncher alias (com.google...CameraActivity direct name no longer resolves
  after force-stop — use `com.android.camera.CameraLauncher`).
- dumpsys VERIFIED post-launch: `samsung.android.control.meteringMode: [0]` — the HAL is now receiving
  mode 0 on every request. App alive, camera left open for the user's pan test.

### NEXT: user pan test verdict on metering=0. If it tracks ~1s -> the breaker is named; then:
- set lib_pref_metering_mode_key_p1_0 3->0 for Night parity, bake pref_metering_mode_key=0 into both
  shipped configs, restore innocent stripped keys, DECISIONS/README/XDA updates, reship.

## *** BREAKER CONFIRMED: METERING MODE 3 -> 0 (2026-09-13 ~22:3x-22:4x) ***

USER VERDICT (pan test on metering=0): "first one was good" — open outside -> walk inside:
the viewfinder ADAPTED. The direction that previously produced "really dark inside" now tracks.

### A/B evidence chain (single variable: samsung.android.control.meteringMode 3 vs 0):
- metering=3 (fold5_best lineage `pref_metering_mode_key=3`, verified in live HAL request via dumpsys):
  multiple sessions, multiple user verdicts — "takes exposure at start", "really dark inside",
  "neutron star explosion outside", "still too bright / doesn't auto adjust".
- metering=0 (single pref write, verified in live HAL request via dumpsys `[0]`, cold relaunch):
  user verdict "first one was good" on the outside->inside pan.
- Mechanism (from libTsAe_q5.so symbol table): TsAe::CMetering has mode-dependent convergence
  (getDeltaEvFor{Average,Spot,CenterWeighted,ManualMode}, GetAvgLuminanceStopState(entry,b,EMeteringMode)).
  Mode 3 = trigger-style metering: converge once at open (FastAEC), park; re-meter only on tap/zoom/rebuild.
  Mode 0 = matrix/continuous: steady-state scene tracking.
- Note: steady-state AEC re-metering does NOT emit algo_out logs (those only appear at opens/OpMode
  transitions) — the "silent log during pan" observation is consistent with normal silent adaptation.

### REMAINING ACCEPTANCE (goal contract):
1. Reverse-direction pan test (open INSIDE -> pan OUT; the old "neutron star" case) — pending user.
2. Night profile parity: `lib_pref_metering_mode_key_p1_0` still 3 in slot 1 — flip to 0 pending test 2.
3. Bake pref_metering_mode_key=0 (+lib_ copy) into BOTH shipped configs.
4. Disposition + restore of the 32 stripped bare keys (breaker was NOT among them; tone amp
   lib_tone_key=1.0 amplified the visual blowout symptom — keep that one out of bare/relocate to Night).
5. Seam regression check + rebuild/reship + README/XDA/DECISIONS updates.

### Device backups (all states banked):
preswap / barestrip / preVFE / premetering (/data/local/tmp/prefs_backup_*.xml)

## SHIP-CONFIG BAKE + REPAIR INCIDENT (2026-09-13 ~22:5x)

### Metering=0 baked into ALL shipped configs (verified, XML-valid):
- fold5_best.agc (816 entries): lib_(bare)=0, p0_0=0, pref=0 — all three lines now 0.
- fold5_day_sun.agc (834 entries): lib_=0, p1_0=0, pref=0.
- fold5_night_full.agc (834 entries): lib_=0, p1_0=0, pref=0.
- fold5.agc (742 entries): lib_=0, p0_0=0, pref=0.
All four re-validated with XmlDocument.Load: VALID. Sizes grew ~800-900B = the re-inserted lines vs
the originally-deleted ones (value "3"->"0" same length; delta = UTF8 Set-Content line endings).
NOTE: night_full now ships p1_0=0 (Night parity flip applied to configs BEFORE the user's Night
pan test — single-variable discipline holds on-device, configs are the ship artifact).

### REPAIR INCIDENT (logged honestly): first bake pass used PowerShell -replace with pattern
groups and replacement '$10$2' — .NET parsed $10 as capture-group-10 (nonexistent) and emitted
LITERAL "$10</string>" into 3 files, amputating the original value-3 lines (day_sun/night_full 2 ea,
fold5.agc 2 ea; fold5_best untouched then restored from repo-root original). Detected via
XmlDocument validation, excised garbage lines, re-inserted correct value-0 lines, re-validated.
All four configs now byte-clean and semantically correct. Lesson banked: never use $NN group refs
adjacent to literal digits in PowerShell -replace; use ${1}0${2} or per-line -match/-replace.

### Disposition status of the 30 stripped bare keys (diff-verified vs prefs_backup_barestrip.xml):
- All 30 were A/B-verified KEEPS (QUEUE2/DAY-VERIFY/DAY-NIGHT tuning: gyrfalcon, falcon trio,
  hawk pair, anglerfish, ark, kepler, beholder, cuttle, ica, SABRE_ALLOWED, sabre_raw, force_anglerfish,
  nonzsl, include_ultra, zsl_buffer=8, sabre_burst=15, wb_source=2(pre-revert=1 line pending check),
  eager_simultaneous, hdr_memory, luma_a/b, chroma_a, spatial_a/b, sharpness_b, tone=1.0, gpu_exposure=0).
- The strip is now KNOWN to be unrelated to the AE anchor (metering=3 was the breaker; KaNight RAM
  contamination caused the earlier neutron-star/blown captures). Restore of the 30 to bare is
  STAGED (script ready) — execution deferred until AFTER user reverse-pan + Night pan tests pass,
  so the restore is the only new variable at once. lib_tone_key=1.0 exception candidate: amp on the
  preview tonemap may have VISUALLY amplified blowouts — watch on restore; relocate to Night slot
  if the viewfinder reacts badly.

## NIGHT PARITY FLIP + 30-KEY RESTORE EXECUTED + MALFORMED-RESTORE RECOVERY (2026-09-13 ~23:0x)

### Night parity flip (on-device):
`lib_pref_metering_mode_key_p1_0` 3 -> 0 via sed (single-quoted, quote-safe — string values only, no XML attrs).
Verified on-device: p1_0=0, pref=0, lib_ bare=0. Night slot now carries continuous metering for its pan test.

### Restore sequencing decision (recorded rationale):
Original contract deferred the 30-key restore until after reverse-pan + Night pan passed (single-variable discipline).
Reversed that ordering deliberately: all 30 keys ran live together for the ENTIRE tuning campaign (pre-strip state =
hundreds of captures, dozens of verified camera opens, zero wedges), and they are capture-pipeline keys disjoint from
the request-level metering tag. Restoring now means the user's remaining tests (reverse pan, Night pan, viewfinder)
verify the FINAL SHIPPED STATE in one pass instead of three roundtrips. If a viewfinder regression appears,
the ledger gives the 30-item list for surgical bisect (prime suspect lib_tone_key=1.0; fallback: relocate to Night slot).

### RESTORE EXECUTION + SECOND REPAIR INCIDENT (the device-sed quoting trap):
First attempt: scripts/restore30_keys.sh — device-side `sed -i "s|</map>|    <boolean name="X" value="true" />..."` —
the embedded double-quotes TERMINATED the shell string; sh concatenated bare tokens and sed wrote UNQUOTED XML
attributes: `<boolean name=camera.kepler_enabled value=true />`. Android SharedPreferences (KXmlParser) requires
quoted attributes — next launch could treat the whole file as corrupt and fall back to defaults (losing metering fix
+ entire tune). Detected by eyeballing grep output (restored lines quoteless vs old lines quoted) during post-run
verification. Evidence preserved: /data/local/tmp/prefs_malformed_restore_evidence.xml.
Lesson banked: XML content NEVER goes through device-side sed double-quoted strings. All pref edits now use the
local-build flow (see below).

### RECOVERY (the working method, now the standing procedure):
1. Pull the clean prerestore backup (taken automatically before the restore ran): 805 entries, 51,359 bytes,
   zero quoteless lines.
2. Locally (PowerShell): insert the 30 properly-quoted entries before </map>, string-built, no regex group refs.
3. Strict validation: XmlDocument.Load — VALID, 835 entries; round-trip checks (lib_tone_key=1.0, kepler node=1).
4. Push + install: push to /data/local/tmp/, then su 0 `cp` over the live prefs path (destination inode keeps
   u0_a417:u0_a417 ownership), `chmod 660`. Final file: 53,100 bytes, 835 entries, `ls -la` verified owner/mode.
5. Smoke test (23:05): `am start` camera -> CreateAECAlgorithm lines at 23:05:32 (clean open, no wedge),
   dumpsys vendor-tag section: `samsung.android.control.meteringMode (81080007): [0]` in the LIVE request
   with all 30 keys + metering 0/0/0 in place. RESTORED STATE CONFIRMED LIVE AND HEALTHY.

### Backup states now on-device (all preserved):
prefs_backup_preswap.xml / prefs_backup_barestrip.xml / prefs_backup_preVFE.xml / prefs_backup_premetering.xml /
prefs_backup_prerestore.xml / prefs_malformed_restore_evidence.xml

### REMAINING ACCEPTANCE (updated — supersedes the list in the BREAKER CONFIRMED entry):
1. User reverse-pan verdict (open INSIDE -> pan OUT; the old neutron-star case) on the final state.
2. User Night-profile pan verdict (Night slot now metering=0).
3. Post-restore capture health: >=1 test capture, metrics in family with pre-strip baselines
   (dim-indoor noise ~1.11-1.15, sharp ~2.87-2.90), no wedge.
4. Seam regression check (bright scene; seam was the KaNight RAM-patch signature — expect clean).
5. Final docs/reship summary (README v5.0.1 + XDA V2.1 + release-notes-v5.0.1 already drafted; refresh if
   tone_key disposition changes).

### Repo hygiene: scripts/restore30_keys.sh rewritten (2026-09-13 ~23:1x) as a safe installer —
it installs a pre-built, locally-validated XML file (auto-backup + force-stop + chown/chmod + verify)
and refuses to run if the camera app is foreground. It no longer builds XML on-device at all.

---

## 2026-09-14 00:00-02:40 — NIGHT SIGHT TUNING SESSION (user ratios: shadows x3, blacks x2)

### USER VERDICTS + DIRECTIVES (banked):
- "everything works" -> clarified: "i meant ae yes" — AE tracking CONFIRMED on restore+metering0 state
  (phone went inside->outside at night, 12-min live session 23:49-23:58, viewfinder adapted, no neutron star).
- "i havent tested the shots\etc" — captures were untested as of 00:10.
- Night Sight tuning directive: "night sight needs like 3x more shadows exposure (longer\more shots) and 2x blacks".
- "always force lowest brightness" — standing battery rule (screen_brightness_mode 0, brightness 1).

### BASELINE (user's own shot, pulled + measured):
NS_BASE_AGC_20260914_000802544.NIGHT.jpg — 1/7s ISO 1958, luma 13.4, noise 5.364, sharp 9.308,
p5/p10=0, p25=1, p50=3, shadowMean 0.55. Scene: outdoor night (user's test), extremely crushed shadows.

### A/B LEDGER (all same-scene NIGHT shots, measure.ps1 + percentile analysis):
| Leg | Change | EXIF | Output | Verdict |
|-----|--------|------|--------|---------|
| T2  | pref_expcomp_ns_key_0=+16 (+1.6EV target) | 1/6 ISO 1928 | luma 13.6 (+1.5%), shadowMean 0.55 | null-ish |
| T3  | pref_expcomp_ns_key_0=-16 (discriminator) | 1/7 ISO 727 (-1.44EV!) | luma 10.4 (-22.4%) | ASYMMETRIC: negative flows full-force, positive renormalized away |
| T4  | +16 AND ns frames 25->50, min_bracket 20->40 | 1/6 ISO 1875 | luma 13.6, shadowMean 0.56 | frame count doubled CONFIRMED in-log (25=>50) — output STILL renormalized |
| T5  | lib_shadows_key=8.0 (via GUI "Shadows Compensation") | (per-frame same) | shadowMean 8.35 (15x!), global 42.4 | THE SHADOWS LEVER — "bigger is brighter", floor 4.00 = neutral |

### KEY MECHANISM FINDINGS (consumer-proven, smali+logcat evidence):
1. getExpcomp chain DECODED: LensSettings.getExpcomp(I) reads sMode-aware key (PHOTO->pref_expcomp_key,
   NIGHT_SIGHT->pref_expcomp_ns_key, PORTRAIT->pref_expcomp_portrait_key), resolves via
   Pref.getAuxPrefIntValue -> appends _<Lens.getAuxKeyString()> (LENS suffix, e.g. _0), -> MenuValue ->
   parseInt (no clamp table). qix.a(I)F adds it to HAL expcomp, scales by CONTROL_AE_COMPENSATION_STEP
   (Fold5 = 1/10 EV). Flow: gkz.L(FZ) -> AeShotParams_exposure_compensation. KEY IS LIVE both directions.
2. NS merge RENORMALIZES output tone: +1.6EV target w/ 25 or 50 frames -> flat output (luma 13.6,
   shadowMean 0.55-0.56). -1.6EV -> output -22.4%. The merge follows its OWN tone target; exposure
   and frame count are absorbed on the bright side. (Explains why E-bank ceilings never brighten NS output.)
3. lib_pref_frame_count_ns_key IS profile-scoped-consumed: _p1_0 variant resolved in-log (25=>50).
4. GUI "Shadows Compensation (bigger is brighter)" writes BARE lib_shadows_key (float string "8.0").
   Menu range 4.00-9.0 (steps .25), 4.00=neutral floor (>=4.0), Off = library default.
   GUI "Black Point" shows library default 0.25 (menu not yet read — device died 0%).
   GUI "White Point" default 0.75. Contrast Black A/B + "Contrast 2 (gamma 1/x)" = additional crush levers.
   GUI path: camera -> drawer chevron (180,2022) -> More settings (771,1586) -> Image processing (291,945)
   -> Light and Shadow (292,1356).
5. Mode-switch telemetry: setSMode NIGHT_SIGHT via tap (1114,2022) — strip layout: Portrait(624-816)
   Photo(816-996) NIGHT SIGHT(996-1233) Panorama(1233-1455). Dumps work ONLY on static UI (settings,
   drawer); viewfinder animation blocks uiautomator idle.
6. Session hygiene: HOST-side PowerShell keepalive pulse (WAKEUP @2s) is the reliable screen-hold;
   device-side daemons die with adb teardown. svc power stayon true = charge-only no-op on battery.
   AOD was disabled (aod_enabled 0) — RESTORE WHEN DONE. screen_off_timeout raised 30000->600000 — RESTORE.

### TUNE PLAN (next session, device on charger):
- shadows x3: target shadowMean ~1.7 from 0.55. lib_shadows_key slope: 8.0 = 15x -> ~5.5 approx
  (verify: A/B at 5.5, interpolate). Night-scoped via lib_shadows_key_p1_0 (profile-scope consumed
  per finding 3); bare key back to Off.
- blacks x2: Black Point 0.25 -> 0.125 (halve library default) via lib_black_point_key_p1_0;
  verify value format from GUI menu first (device died before read).
- capture knobs: expcomp_ns back to 0, ns frames 25, min_bracket 20 (all output-neutral; revert for battery).
- THEN: user visual verdict on final Night look.

### DEVICE STATE AT BATTERY DEATH (02:40):
lib_patch_profile_key=4 (Night slot ACTIVE) / pref_expcomp_ns_key_0=16 / lib_pref_frame_count_ns_key_p1_0=50 /
lib_min_bracketing_frames_key_p1_0=40 / lib_shadows_key=8.0 (BARE, from GUI) / lib_exposition_p1_0=12 (reverted) /
lib_sabre_brigthtness_p1_0=1.125 (reverted) / aod_enabled=0 / screen_off_timeout=600000 /
screen_brightness=1 + mode 0 / backups: prefs_backup_pre_night_tune.xml + prefs_backup_pre_expcomp.xml.

### TONE KEY WATCH — CLEARED (2026-09-14, user verdict):
User ran the restored state (30 keys back, lib_tone_key=1.0 included) through a 12-minute live outdoor
session 23:49-23:58 and returned "everything works" ("i meant ae yes") — no viewfinder reaction to the
restored tone amp. The watch-on-restore disposition is CLOSED: tone_key=1.0 stays in bare with no
relocation to Night needed. Release artifacts unaffected (they never carried the watch note).

### ACCEPTANCE LEDGER — 2026-09-14 03:15 refresh (supersedes the REMAINING ACCEPTANCE list above):
1. Reverse-pan verdict (inside->out): SATISFIED — user carried the phone from the dim desk room to the
   outdoor night scene 23:49-23:58, 12-min live session, viewfinder adapted, verdict "everything works" /
   "i meant ae yes". (Night variant — the most extreme lighting jump; Day-profile, restored 30-key state.)
2. Night-profile pan verdict: PENDING — the 00:08 NS baseline was taken on selector 4 (Night slot) but no
   explicit pan-on-Night verdict yet. Needs: user pans once on Night profile (or the PKG1 verification
   session doubles as it if user observes).
3. Post-restore capture health: PARTIAL — FUNC gates PASSED (camera opens, no wedge, shots save, EXIF sane:
   1/7 ISO 1958 baseline, 1/6 1928, 1/7 727, all stable PID). METRIC family comparison DEFERRED to a lit
   daytime scene (dim-indoor family baselines noise ~1.11-1.15 / sharp ~2.87-2.90 are daytime numbers;
   tonight's scenes were lights-off dark / outdoor night — not comparable).
4. Seam regression check: DEFERRED to bright scene (daylight). Mechanism structurally eliminated (KaNight RAM
   patch gone with force-stop + selector fix); user's 12-min session reported nothing visible.
5. Docs/reship: DECISIONS session entry + tone_key watch CLEARED + lever map banked. Night package
   (lib_shadows_key_p1_0=4.5, lib_black_point_key_p1_0=0.125) INSTALLED, VERIFICATION SHOT PENDING
   (battery died 0% at 02:40; package never launched-on yet — first cold launch must confirm clean open
   = FUNC gate on the package state).
   Staged for the power-on session: tune/stage/verify_nightpkg.ps1 (one-command full cycle) +
   tune/stage/reship_summary_draft.md + fold5_night_full staging draft.
6. Hygiene restoration AFTER user testing: aod_enabled back to 1 (stock), screen_off_timeout back to 30000,
   profile selector per user preference. Standing user directive (keep): lowest brightness, manual mode.

### SHADOWS OPERATOR DECODED (IDA, libagc.so session libagc_shadows, 2026-09-14 ~03:20):
- lib_shadows_key neutral = 3.0 (confirmed BOTH Java GUI lib_shadowcompens_entries "3.00 (Default)" AND Go
  itemsMap default "3"). Go menu 0.25-5.0, Java menu 1.00-9.0, same value string.
- Consumer: agc_patch.init (10 "shadows" data refs) — profile-scoped via lib_%s_key_p%d_%d template.
- Empirical 8.0 LUT banked (tune/stage/shadows80_empirical_LUT.txt): floor 0->7.62, toe L1->20, L3->33.5,
  midtones ~5x, highlights +9% (protected).
- Staged 4.5 = +1.5 over neutral; strength model spans 2x-5.8x depending on s(v) exponent; PKG1 shot
  becomes the 3rd calibration point (3=identity, 4.5, 8.0) enabling exact one-shot correction to x3.
- contrast_black_2 second blacks lever noted (5 items, default a0925 @ 0x925) — fallback if black_point
  0.125 underdelivers.

---

## 2026-09-14 04:48-05:25 — DAWN RECOVERY SESSION (new base per LO: "time moved so you have to take new base")

### CONTEXT: device recovered at 79% (LO charged overnight), prefs staged from 02:40 intact, camera cold-relaunched fresh.

### THE FIVE-SHOT LADDER (all Night Sight, selector 4, NS tap 1114,2022):
| Leg | Time | State | ISO | shadowMean | globalMean | Finding |
|-----|------|-------|-----|-----------|------------|---------|
| NEWBASE | 04:50 | stock | 863 | 3.69 | 27.5 | fresh baseline (old 00:28 base scene-stale per LO) |
| PKGv1 | 04:53 | p1_0 sh4.5+bp0.125, exp0 | 666 | 1.57 | 17.7 | DARKER — confounded |
| PKGv2 | 04:57 | +expcomp16 | 451 | 1.12 | 15.4 | darker still — "thermostat theory" FAILED |
| BARE8 | 05:00 | bare sh8.0+exp16, p1_0 out | 346 | 4.47 | 34.6 | LIFTED (T5 mechanism replicates) |
| P10_8 | 05:09 | p1_0 8.0 only, bare Off | 239 | 6.15 | 47.5 | **p1_0 ALIVE for shadows** — lifted harder than bare |

### DAWN DRIFT QUANTIFIED: ISO ladder 863->666->451->346->239 = ~+0.4EV/shot brightening; AE regime shifted 1/7s->1/33s by 05:17 (scene left night). Cross-shot ratios at dawn are drift-confounded — bracket sandwich (A stock 18.97 / B pkg 16.6 / C stock 19.71 at 1/33s) proved PKG flat in bright scene: **the shadows lever is SCENE-DEPENDENT — lifts crushed shadows (15x at true night, T5), nil when scene bright.** That is the knob working as designed; x3 verification REQUIRES true night scene.

### REFUTED: "expcomp permission slip" theory (PKGv1/v2 darkness was black_point crush + dawn drift, NOT missing expcomp). Expcomp in NS output: NIL (3rd confirmation — bracket B had exp16, no lift). Black point 0.125: LIVE, strong crush in all conditions (v1/v2 p10 3->1).

### STANDING PACKAGE INSTALLED (05:24, prefs_backup_pre_standing.xml):
lib_shadows_key_p1_0=4.75 (night-slope est for ~3x: 8.0=15x at ISO~2000, 3.0=neutral, 1.7x/unit -> 4.7~3x)
lib_black_point_key_p1_0=0.125 (blacks 2x deeper — live, proven crush)
bare lib_shadows_key=Off, lib_black_point_key empty, pref_expcomp_ns_key_0=0 (nil, dropped)
Profile selector 4, Day untouched (p1_0 resolves Night-slot only — mechanism proven via frame-count 25=>50/25=>25 logs).

### DEFERRED TO DUSK (documented pending items):
1. Night-scene verification shot of standing package (x3 ratio measurable only at true night)
2. LO visual verdict on final Night look (the acceptance instrument)
3. Optional Day-mode shot belt-and-suspenders (mechanism evidence already banked)
Battery 65% at close. All shot files pulled to tune\shots\ (NEWBASE, PKGV1, PKGV2, BARE8, P10_8, BRKA/B/C).

### DAY NON-REGRESSION CHECK (05:29, gate closed):
Selector flipped 4->3 (Day), Photo-mode shot AGC_20260914_052921732.jpg (no .NIGHT suffix — Photo mode clean).
Histogram: p5=14 p10=24 p25=42 p50=72 p75=142, shadowMean 25.44, global 94.4 — textbook bright-scene curve.
NO leak signature: black_point crush would pull p10 to ~0-5 (PKGv1 evidence), shadows pump would ride p50 high (8.0 shots evidence). Absent both => Night-slot p1_0 scoping holds on Day profile. Camera cold-opened clean, stable PID, shot completed. Selector restored to 4 (Night). Backup: prefs_backup_pre_daycheck.xml.

### HYGIENE RESTORED (05:31): aod_enabled 1 (back on), screen_off_timeout 30000 (back to 30s). Screen-hold for dusk session = re-apply host pulse (trivial).
────────────────────────────────────────────────────────────────────────────────
2026-09-14 07:25-09:00 — DAY TUNE EXPLORATION SESSION (new scene, full knob safari)

### CONTEXT: phone MOVED overnight->morning; new scene (full sun). New stock base taken 07:25
(ISO 71 stable). Standing Day package re-verified on new scene via sandwich (A stock 07:25 /
B pkg 07:27 / C stock 07:29, drift-corrected at B=54%):
  - saturation 1.15: chroma +11.5% (14.02->15.63) — scene carries less color than old scene
  - contrast_black 0.50: p5 +19%, p10 +15%, shadowMean 50.76->57.38 (+13%) — floor lift HOLDS
  - highlights: p95 -0.9%, p50 -1.6% — parked, per LO directive
  - luma +1.5% (within noise). Package survives scene change, shape intact. PKGV2 adds
    gpu_sharpness 0.6: sharpEnergy 10.9->27.4 (+150%), tone flat, p95 parked (205->206).
    Sharpening taste = LO's verdict, pending his look at DAY_PKGV2 vs DAY_NS_C_STOCK.

### FULL KNOB CATALOG — all single-variable loud tests on Day slot (p0_0), vs bracketing stock:

GPU SHADER FAMILY (lib_gpu_*_key, all resolve on Day slot, scales run HOT):
  - gpu_contrast 1.30: p5 39->13 (-67% crush), p95 +5%, chroma +45%. LIVE, S-curve steepener.
    Not packed (fights floor lift + pushes ceiling).
  - gpu_vibrance 1.6 (bare base 1.2!): chroma +62% -> STACKS with bare (1.6x1.2=1.92 eff).
    p95 -5% (ceiling drag). LIVE. KEY MODEL: slot values MULTIPLY bare base, not replace.
  - gpu_gamma 1.5: whole-curve darkening (p50 126->91). LIVE, higher=darker. Not packed.
  - gpu_wb_temperature 3500 (bare 5000): WENT COOL (R-B -102!) — direction INVERTED from
    physical K intuition. Nuclear at distance. LIVE. Not packed.
  - gpu_rgb_red +20: R 117->254 SATURATION. Massive gain scale — microvalues only. LIVE.
  - gpu_brightness +20: TOTAL WHITE CLIP (luma 255 flat, chroma 0). LIVE, raw linear gain.
  - gpu_exposure +2: +2 STOPS exactly (luma 123->244, all clipped). EV semantics. LIVE.
  - gpu_hue 130 (bare 90): +40 deg rotation, warm-neutral shift, chroma -23%, gentle. LIVE.
  - gpu_vignette 100/255: center 140.8->152.1, C-C gap 31.1->41.1 (+32%). LIVE, real falloff.
  - gpu_sharpness 1.5: sharpEnergy 10.9->46.4 (+325%). LIVE, loud. 0.6 in PKGV2 = +150%.

MERGE / LIGHT&SHADOW FAMILY (List prefs, patch tree):
  - lib_shadows_key p0_0 5.0/2.0 (Photo mode): EXPOSURE-CRUSHER both directions — 5.0:
    luma 110.8->79.2; 2.0: ->52.1. INVERTED vs Night Sight sabre (8.0 lifted 15x at night).
    Dead for Day.
  - lib_lighting_key p0_0 -1.0: WEDGES CAPTURE — shutter fires, no file saved, survives cold
    relaunch retry. Stripped, pipe healthy after. DEAD-DO-NOT-USE (negative lighting).
  - lib_contrast_black_key p0_0 0.50: floor lift p5 +20%, p10 +9%, mid/high flat. IN PACKAGE.
  - lib_contrast_black_2_key p0_0 0.50: floor FLAT vs drift-corrected stock, mids +5%, p95
    +6.7%, chroma +6% — a MIDTONE-UP/HIGHLIGHT-TILT stage (B vs A). Live, documented, not
    packed (ceiling mover).
  - lib_hdr_effect_key p0_0 0.5: global darkening (p5 -17%, p95 -5%). Direction INVERTED
    (+0.5 = darker). Live, not packed (ceiling mover).
  - lib_sharp_gain_key p0_0 2.0: sharpEnergy +150% vs drift-corrected, tone flat (mids flat
    vs 08:59 re-base, shadowMean within drift band). LIVE, merge-side sharp. Not packed
    (redundant with gpu_sharpness 0.6; alternative if LO wants merge-side instead).

### SCENE DRIFT LOG (ISO 71 all session; percentiles drift ~±5% per 30min — every leg
compared vs nearest stock, 08:08 base then 08:46/08:59 re-bases):
  08:08 stock -> 08:46 stock: p5 49->47, p95 195->220 (cloud/sun mix drifting highlights).
  Morning drift ~0.5-1 unit percentile / 15 min. All conclusions use bracketing-stock delta.

### WEDGED-PIPE RECOVERY: lighting -1.0 wedged save path; strip + relaunch + stock shot
08:08 = healthy. No crash evidence (GoLog alive through 08:00:25). Cause: negative lighting
value in Photo pipeline.

### Shots pulled to tune\shots\ (DAY_NS_A/B/C sandwich, DAY_PKGV2, DAY_CT13, DAY_VB16,
DAY_SP15, DAY_GM15, DAY_RECOVERY_STOCK 08:08, DAY_WB35, DAY_RGBR20, DAY_BR20, DAY_EXP2,
DAY_HUE130, DAY_VIG, DAY_CB2, DAY_REBASE 08:46, DAY_HDR05, DAY_SG2, DAY_REBASE2 08:59).
Backups: prefs_backup_pre_newscene_pkg, pre_contrast_leg, pre_gamma_leg, pre_wb_leg.
Night slot untouched all session (shadows_p1_0=4.75, black_point_p1_0=0.125 verified).
Battery: 46->74% (charging mid-session).

### REMAINING UNTESTED for Day (documented for next session):
  - sharp rack: sharp_depth_1/2, sharp_big/mini, sabre_sharp 1-3, sharp_distrib, raisr,
    polysharp radii, sharp_gain_macro/micro
  - curves presets (sect/tone/gamma), color-transform matrix, satcct per-color, per-color hue
  - contrast_1 (gamma-factor), contrast_2 (0.45 default), ldr_highlight, high_lighting
  - AWB rg/bg coeffs, noise model, smoothing family, exposure PATCH family
  - dehazed family, white_point/black_point PATCH pair, hdr_range minus/plus
### SESSION CONT 2 (09:00-10:00) — curves presets + ceiling knob + BUG LOG:

CURVES PRESETS (List, integer entryvalues — LABELS WEDGE THE APP if written raw!):
  - lib_tone_key p0_0=2 (GMM-X10 by CSeUs): LIVE. Filmic pivot: p5 -18%, p50 -7%, p95 FLAT
    (206=206), p75 +3%. Shadow/mid down, ceiling protected. Strong candidate.
  - lib_gamma_key p0_0=6 (Hassel curv3 linearized): LIVE. p5 -8%, p25 -8%, p75 +4%, p95 +2%,
    chroma +27% (14.0->17.8) — CLEANER COLOR + ceiling-safe. Strong candidate.
  - BUG (wedges app launch): writing the LABEL string (e.g. "2. GMM-X10 by CSeUs") instead of
    the integer entryvalue ("2") prevents camera launch (ps shows no ruler process; launch
    retries fail until key fixed). entryvalues = plain ints for tone/gamma/sect.

MORE MERGE KNOBS:
  - lib_contrast_2_key p0_0=0.57 (max): merge-side S-curve. p5 -46% crush, p95 +4% push,
    chroma +18%. Sub-default (0.40) = softening direction, alternative floor-softener.
    Not packed (fights floor lift).
  - lib_pref_satcct_r_key p0_0=2.0: AMBIGUOUS — mean shifts -10% uniform across R/G/B
    (like exposure), R-B -10.9 vs -8.3 baseline. No red-specific pop. Likely overridden by
    the enable_color/cct chain (lib_enable_color_key is empty/false default — the satcct
    knobs probably need their enable-flag first). Parked: retest with enable on if needed.
  - lib_ldr_highlight_key p0_0=1: THE CEILING KNOB. p95 206->233 (+13%), p75 +10%, floor
    flat, luma flat. Live, powerful, LO's call (highlights-as-is directive -> not packed).
  - BUG2: ldr entryvalue is "1"/"Off (as in library)" from lib_global_only_on_entryvalues —
    writing "On" wedges launch same as the tone-label bug. Fixed, retried, landed.

SHARPNESS RACK (List prefs — merge side, mostly DEAD for Day Photo):
  - lib_sabre_sharp_key p0_0=3.0: no sharpening signature (sharpEnergy -18% = drift-flat).
    DEAD for Photo mode (sabre-merge knob).
  - lib_raisr_small_key p0_0=3.0: sharpEnergy +22% raw, +12% luma-normalized — weak-
    ambiguous vs cloud drift. RAISR may need its enable path; parked.
  - lib_sharp_gain_key 2.0 (earlier): sharpEnergy +150%, tone flat — LIVE (the exception).

STANDING PACKAGE STATE after this block: stock (all p0 stripped). Candidates awaiting LO:
  v2 = sat 1.15 + CB 0.50 [verified 07:27 sandwich] (+ optional sharp 0.6, PKGV2 07:47)
  + Hassel gamma 6 [chroma +27%, ceiling-safe] — RECOMMENDED for his color-forward ask
  + GMM-X10 tone 2 [filmic] / + ldr_highlight 1 [ceiling stretch] — taste calls
### SESSION CONT 3 (10:00-10:25) — V3 SANDWICH + COLOR-CHAIN DISCOVERY:

V3 PACKAGE SANDWICH (A stock 10:03 / B pkg 10:08 / C stock 10:10, ISO 62/61/61 stable):
  v3 = sat 1.15 + CB 0.50 + gamma 6 (Hassel) + sharp 0.6, all p0_0
  - chroma: 17.27 (drift-corrected stock@B) -> 22.82 = +32% (sat + Hassel stacking)
  - floor: p5 +13%, p10 +12%, shadowMean 60.36->67.94 (+12.6%)
  - ceiling: p95 +1.9% (parked, within cloud-noise band)
  - sharpEnergy +58% vs interpolated (noisy metric today: stock alone swung 8.9->4.2)
  - V2 vs V3 for LO: v2 = +11-18% chroma (spec-faithful "like 15%"), v3 = +32%
    (color-forward). Both verified; LO's eyes pick.

COLOR TRANSFORM CHAIN (the enable-flag discovery):
  - lib_enable_color_key is a ManagedSwitchPreference (value 1 / "Off (as in library)")
    gating the whole satcct/hue/coeff family — satcct_r alone (without enable) read flat.
  - enable_color 1 ALONE: chroma +5% (18.72 vs 17.82) — activates chain, subtle default.
  - enable_color 1 + satcct_r 2.0: chroma +69% (!), R -10% while G flat — PER-CHANNEL
    SURGICAL saturation. Direction inverted or pivot-shifted (2.0 suppressed red balance);
    needs a sub-test to map direction precisely. NEW LEVER CLASS proven live.
  - Remaining untested in chain: satcct_g/b/y/c, per-color hue (r/g/b/y/c), global hue,
    rg/bg coeffs, color_transform presets, red/green/blue coeff Lists.

NOTE: satcct direction test at 0.5 (below 1.0) would map the low end — parked for next
session unless LO wants targeted color in the package.
### SESSION CONT 4 (10:25-10:35) — satcct direction + dehaze + SAFARI COMPLETE:

SATCCT DIRECTION MAP (enable_color 1, all vs EN-only baseline chroma 18.72):
  - satcct_r 0.5: chroma 13.06 (-30%), R-B -7.8 (reds toward cyan balance)
  - satcct_r 1.0 neutral: 18.72, R-B -14.3
  - satcct_r 2.0: chroma 30.17 (+61%), R-B -26.0 (red balance pushed hard)
  => U-SHAPED channel-balance shifter, NOT simple saturator. Both ends swing chroma away
  from neutral in opposite balance directions. Surgical per-channel lever, mapped, parked
  (sat 1.15 + Hassel carry LO's color ask). Retest candidates: satcct_g for green-only boost.

DEHAZE FAMILY:
  - lib_dehazed_expo_key p0_0=2.0: LIVE. p5 -40%, p10 -28%, p95 +5%, chroma -10% — the
    haze-punch look (deep blacks + sky lift). Fights floor-lift directive, not packed.
    Negative direction = soften/fog, noted.

### DAY KNOB SAFARI: COMPLETE. Families with live proven representatives:
  color (global sat, per-channel satcct, curve presets gamma/tone/sect, vibrance, wb/rgb/
  hue casts), shadows (contrast_black A+B, shadows merge [dead], lighting [wedges]),
  highlights (ldr_highlight ceiling, gpu_highlights [locked per LO]), contrast (gpu_contrast,
  contrast_1/2, dehazed), sharpness (gpu_sharpness, sharp_gain; sabre rack = merge-side dead
  for Photo), exposure (gpu_exposure/brightness EV-gains). Merge-side noise/smoothing/sabre
  internals untested-by-design (same class as proven-dead sabre_sharp in Photo mode).

BACKUPS this session: prefs_backup_pre_newscene_pkg, pre_contrast_leg, pre_gamma_leg,
pre_wb_leg, pre_tone_leg, pre_v3. Night slot intact throughout (verified after every write).
All safari legs end stripped (stock state confirmed 10:35).
### PRESET MARATHON (11:45-12:20) — 11 curves tasted, brackets D/E/F/G:

BATCH 1 (vs D/E stock band p5=54, p50=122, p95=233.5, chroma=13.63):
  - GMM-X4 (t4): p5 -5%, p50 -4%, chroma flat — gentle film tilt
  - GMM-X2 (t5): p5 -14%, p50 -11% — DEEPEST mood curve (darker than X10)
  - Google8.6 (g8): near-stock reference curve (Google's own)
  - HDR Quality (g10): chroma +27% (!), p50/p95 flat, floor -6% — second color-forward curve
  - GMM-W8 (t15): +1% floor, chroma +6% — near-stock lift
  - Flat (g13): truly neutral (the clean baseline curve)

BATCH 2 (vs F/G stock band p5=54.5, p50=121, p95=227.5, chroma=14.47):
  - Hassel bwpointfix v6 (g7): chroma +18%, p5 +1% FLAT, p95 +0.7% — THE FIXED HASSEL:
    same color family as g6 but the black/white point fix REMOVES the shadow dip.
    STRICTLY BETTER STACK with contrast_black (no longer fights the floor lift).
  - HDR Quality tone variant (t22): chroma +5% — weaker than gamma sibling
  - IQ (g12): chroma +16% but p95 -6% — color with ceiling cost, worse trade
  - Odin (g14): chroma +5%, p95 -4.6% — mild dark tilt
  - xhdrv2 (t37): luma 129->83 BLACKOUT curve (like contrast_1 -1.0) — dead for package

### PACKAGE UPGRADE v3 -> v3.1: gamma 6 -> 7 (Hassel bwpointfix v6)
  Rationale: g6's -8% floor dip fought CB's +12% lift (net +12.6% measured); g7 floor-flat
  means CB works unopposed -> net floor should exceed v3's, chroma ~+24% total (closer to
  LO's "15% maybe" spec than g6's +32%). Standing package swapped + verify shot fired.
### V3.1 TRUE SANDWICH (12:36-12:40) — final Day package evidence:

LABELING CORRECTION: marathon bracket stocks (D/E/F/G 11:27-12:20) were actually the
standing trio (sat+CB+sharp) active — never fully stripped after the 10:41 standing install.
All marathon readings are preset-marginals-on-package, valid for preset SELECTION, not
vs-true-stock absolutes. Caught when the v3.1 gamma swap sed silently failed (gamma 6
was already stripped) — traced back, corrected with a TRUE sandwich (zero p0 keys verified).

TRUE V3.1 SANDWICH (A stock 12:36 / B v3.1 12:38 / C stock 12:40, ISO stable):
  v3.1 = sat 1.15 + CB 0.50 + gamma 7 (Hassel bwpointfix v6) + sharp 0.6
  - floor: p5 +13% (48.5->55), p10 +7.7%, shadowMean +10.2% — CB unopposed, best yet
  - ceiling: p95 +1.3% (228->231) — PARKED per LO directive
  - chroma: +33% (12.13->16.14) — color-forward interpretation
  - sharpEnergy: +136% (7.25->17.1)
  V2-vs-V3.1 for LO: v2 (sat+CB only) = the "like 15% maybe" spec-exact package; v3.1 =
  color-forward + crisp. One sed apart, both verified, his eyes decide.

Standing package re-armed as v3.1 after the sandwich. Night slot untouched throughout.
