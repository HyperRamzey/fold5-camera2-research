# v5.0.1 — Viewfinder AE fix: `samsung.android.control.meteringMode` anchor

Same APK as v5.0.0 (v4.3.0 binary) — this is a **config-only fix**.

## The bug

The viewfinder metered exposure once at camera open and never re-converged: pan outside→inside and the preview went near-black; inside→outside and it blew out. Taps and zoom re-metered (masking it as "locked AE"); scene changes did nothing. Reports dating back to the first `fold5_best`-lineage imports.

## Root cause (proven, not guessed)

AGC's `createCaptureRequest` stamps Samsung's vendor key `samsung.android.control.meteringMode` on **every** capture request (smali: `AGC.smali` L447 ← `AdvancedSettings.getMeteringMode()` ← `pref_metering_mode_key`). The `fold5_best` lineage shipped that pref as `3`, and on the Fold 5's TsAe HAL (`libTsAe_q5.so`) mode 3 is trigger-style metering: converge once at open, park; re-meter only on tap / zoom / stream rebuild.

Evidence chain:
- HAL exonerated first: Samsung `SS_3A` AEC trace shows continuous-AE OpMode 1, `AEL 0` (unlocked), `AECManualSetting mode=0` (auto) — nothing pins exposure at the HAL layer; FastAEC-at-open is stock behavior.
- `dumpsys media.camera` vendor-tag dump caught `samsung.android.control.meteringMode (81080007): [3]` in the live request.
- `libTsAe_q5.so` symbol table shows mode-dependent convergence (`CMetering::getDeltaEvFor{Average,Spot,CenterWeighted,ManualMode}`, `GetAvgLuminanceStopState(..., EMeteringMode)`).
- A/B: mode 3 → multiple sessions of "anchored" verdicts; mode 0 → user pan test adapts (outside→inside verified; reverse + Night pending final release gate).

## The fix

`pref_metering_mode_key` → `0` (matrix/continuous), plus the per-slot copies `lib_pref_metering_mode_key_p0_0` / `_p1_0`. **All four shipped configs carry 0/0/0** (`fold5_best`, `fold5_day_sun`, `fold5_night_full`, `fold5`). Verified in the live HAL request post-fix (`meteringMode: [0]`), XML-validated.

If you imported an older config: change the value via the app's settings or re-import the new files.

## Also in this release

- The earlier "neutron-star" blown captures were separately caused by the built-in KaNight RAM patch over the tuned config (volatile — force-stop clears it; already documented in v5.0.0). The metering pref was the persistent viewfinder anchor.
- Dead ends logged for the record in `tuning/DECISIONS.md`: `camera.viewfinder_effect_disabled_photo` is R8-stripped (no consumer in this build), torch sysfs is HAL-owned (writes rejected), steady-state AEC re-metering emits no log lines (the `algo_out` silence during pans is normal).