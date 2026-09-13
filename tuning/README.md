# tune\README.md — how to run the Fold5 GCam tuning pass (device time)

Everything in this folder is prepared offline. When the device is ready:

## 0. Connect + preflight

```
adb mdns services            # find current ip:port (rotates)
adb connect <ip:port>
$Dev = "<ip:port>"
powershell -File .\preflight.ps1 -Dev $Dev
```

Preflight verifies: v6.6 build MD5, all baseline guards (aux=0, CFA fix, patch profile 3,
saturn, SABRE_ALLOWED, HDR+ x3), dumps current per-lens values, checks no leftovers.
If SABRE_ALLOWED is MISSING on device: step 1 adds it (fold5_best has it; the device may not).

## 1. Run the queue (one variable at a time — the user's protocol)

```
powershell -File .\TEST_QUEUE.ps1 -List                          # see all 32 steps
powershell -File .\TEST_QUEUE.ps1 -RunStep 0 -Dev $Dev           # reference shot
powershell -File .\TEST_QUEUE.ps1 -RunStep 1 -Dev $Dev           # A1: SABRE_ALLOWED
powershell -File .\TEST_QUEUE.ps1 -RunStep 2 -Dev $Dev           # A5 ... etc
```

Each step: applies ONE pref, cold-boots on the target lens (verifies the camera ID!),
takes ONE shot, pulls it, measures + diffs vs the previous shot, logs to AB_RESULTS.tsv.
Gate summary prints VERDICT: PASS / FAIL / SCENE DRIFT.

Decide KEEP/REVERT per DECISIONS.md rule (works + not broken = keep; else revert):

```
powershell -File .\TEST_QUEUE.ps1 -RevertStep <N> -Dev $Dev      # restores original value
```

Record the decision + metrics in DECISIONS.md.

Scene rules:

- Keep the phone STILL; same room lighting throughout a tier. If VERDICT says SCENE DRIFT,
  re-shoot the reference (rerun previous kept step) before judging.
- Steps 9/10 (wb_source) are color-sensitive: any R/G or B/G shift > 10% = revert.
- If any step FUNC_FAILs (no save/crash/dead camera), revert immediately and note it.

## 2. Build the tuned config from kept settings

Fill KEPT.tsv (or just note kept steps), then:

```
powershell -File .\build_config.ps1 -KeptFile .\KEPT.tsv
# or manual: -Inline "lib_hardmerge_key=1;gcam.zsl_buffer_size=8;..."
```

- Per-lens kept settings written to ALL rear slots (_p0_0 main, _p0_1 wide, _p0_2 tele, _p0_3 main2).
- Guards verified (CFA fix, patch profile, saturn, HDR+).
- Prints full diff vs fold5_best.agc → this becomes fold5_tuned_v66.agc.

## 3. Device sync + final verification (goal requirement)

1. Push the diff keys to device prefs (ab_runner's sed patterns; or restore the .agc via app).
2. Fresh cold boot; 2 shots per rear lens (56/58/52): saves, dims, camera open, no FATAL.
3. Color within ±10% neutral on every lens (no blue-tint regression).
4. Grep-verify all 6 v6.6 patches intact + 50MP still absent.
5. Write FINAL_REPORT.md §7 (tuning section: tested/kept/rejected + per-lens final values).
6. Clean handoff: aux=0, stayon false, this session's scripts removed, app stopped.

## Files

- CANDIDATES.md    — enumeration + rationale (flag read-paths verified in smali)
- TEST_QUEUE.ps1   — 32-step A/B queue with exact valid values
- ab_runner.ps1   — single-step harness (apply→boot→verify→shoot→diff→log)
- measure.ps1     — metrics (luma, R/G, B/G, noise, sharpness) + PASS/FAIL gates
- preflight.ps1   — device-state check before starting
- build_config.ps1 — final .agc builder (validated dry-runs)
- DECISIONS.md    — keep/revert log (fill during runs)
- AB_RESULTS.tsv  — auto-log from ab_runner (one row per step)

## Verified facts baked into the tooling

- Flag read path: SharedPreferences (the main pref file) WINS over phenotype and over the
  mod's boot-time setDeveloperSettings — every camera.*/gcam.*/SABRE_ALLOWED flag is
  runtime-overridable via the pref XML (fps.m/l/a readers check prefs first).
- Per-lens key suffix = *p0*{auxKey}; filtered list on this device: [0]=56 main, [1]=58 wide,
  [2]=52 tele, [3]=56 main(2nd slot), [4]=73 U-front. Tele is _p0_2 (NOT _p0_4).
- lib_* values stored EXACTLY as UI entries (e.g. "256 (Default)", "1.5 (Burst Merge 1)",
  "Off (as in library)"); hardmerge uses entryvalues 0|1|2|3.
- cheetah_* = Pixel video EIS (fpj = camcorder) — not photo-relevant, skipped (documented).
