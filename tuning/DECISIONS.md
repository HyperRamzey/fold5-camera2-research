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
## QUEUE3 (2026-09-13 05:1x) - Night Sight 2x-exposure ladder (user request; fresh morning baseline luma=58.2, scene brightening)
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
[Q2-21] F21 camera.kepler_enabled=true: PASS, KEPT. (first *_enabled master gate that DOESNT break camera - kepler = codec gate, likely video-only; pure drift metrics, functional clean)
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

## PROFILES (2026-09-13 07:4x) - GUI-selectable Day/Night (user requirement: "user selectable via gui")
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
