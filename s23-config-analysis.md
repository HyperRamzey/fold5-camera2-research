# S23 Config Analysis — `JavaSaBr_S23_AGC92v13_v52_Medium.agc` on the Fold 5

**Question:** Is the S23 config correct/usable for the Fold 5 (SM-F946B)?
**Verdict: Partially.** It's the right *family* and safe to import, but it is an S23 Ultra
(dm3q) profile and several parts are tuned for hardware the Fold 5 doesn't have. Details below.

## 1. What the config is

- Exported AGC 9.2 **V13.0** preferences (flat Android `SharedPreferences` XML, 2,390 keys)
  from a **Samsung SM-S918B (Galaxy S23 Ultra, `dm3q`)**, Android 14, board **kalama**
- The "Medium" tuning tier of JavaSaBr's v52 series
- Same board as the Fold 5 (`kalama` = SM8550) — the *reason* it's the closest candidate

## 2. Camera ID topology: partially mismatched

Config's `pref_all_camera_id_list_key` vs. the Fold 5's live-scan set:

| ID | Config (S23U) | Fold 5 | Note |
| --- | --- | --- | --- |
| 0, 1, 2, 3 | ✅ | ✅ | logical back / logical front / UW physical / front wide physical |
| 20, 21, 23 | ✅ | ✅ | logical variants |
| 56 | ✅ | ✅ | **main RAW lens — same position on both** |
| 52 | ✅ | ✅ | 3x tele — same position |
| 58 | ✅ | ✅ | UW physical — same position |
| 5, 6 | ✅ | ❌ absent | S23U extra physical positions |
| 54 | ✅ | ❌ absent | S23U 10x telephoto (Fold 5 has no 10x) |
| 71, 73 | ❌ absent | ✅ | Fold 5 inner-display front main/UW |
| 4, 90 | ❌ absent | ✅ | SECURE_IMAGE_DATA devices (never usable anyway) |

→ **4 IDs don't exist on the Fold 5; the Fold 5's front-inner IDs are unknown to it.**
Samsung reuses the camx position scheme on kalama, which is why the *shared* core
(56/52/58/0/1/2/3/20/21/23) lines up perfectly — the S23U extras are the 10x-tele family.

**Mitigation (verified live):** AGC rebuilds these lists at every scan — our Fold 5 log shows
`Default CameraIdList [0, 1, 2, 3]` then auto-detection to `GetFilteredCameraIDs 56,58,52,0`.
Stale IDs in the config are filtered against the live scan, so **importing is safe** — but the
`pref_camera_id_list_key = [0, 56, 1, 58, 52]` aux list will be regenerated for the Fold 5's
own topology anyway.

## 3. Lens slots & HDR+ models (decoded against `com/PixelDevice.smali` enum)

Slot layout: 0=Wide(1x), 1=Ultra Wide(0.6x), 2=Telephoto(3x), 3=Front, 4/5=Video.

- `pref_model_key_0..4 = 4` → **TAIMEN (Pixel 2 XL)** HDR+ model for all photo lenses
- `pref_model_key_5 = 13` → **REDFIN (Pixel 5)** for video slot
- `pref_device_key_4/5 = 19` → **CHEETAH (Pixel 7 Pro)** device profile for video slots
- Global `pref_device_key = 13` → REDFIN

On our Fold 5 run, AGC auto-derived `q5q => redfin` per lens — the config's TAIMEN-per-slot
simply pins what auto-detection would otherwise choose, so this part is **functionally fine**
(TAIMEN is the standard choice for Snapdragon HDR+ on Samsungs).

## 4. Noise models & AWB — the actual mismatch

- Per-slot noise models: `82, 58, 59, 56, 82, 71` (numeric libagc DB indices) plus an
  explicit `S23_base_and_ultra_front_v1.c` for a video slot
- Slot AWB calibrations: `Mi10Pro s5khmx` (wide), `Mi11U UW IMX586` (UW),
  `Mi11U Tele5x IMX586` (tele/front), `Samsung23U Front S5K3LU` (video)

These are **borrowed profiles tuned for other sensors** (Xiaomi Mi 10/11 and S23U's HP2/S5K3LU
front). The S23 Ultra's main is the 200MP ISOCELL **HP2**; the Fold 5's main is the 50MP
**S5KGN3**. Expect:

- main-lens HDR+ noise merge ≈ usable but slightly off (HP2 curve vs GN3 read noise)
- AWB neutral but with small color casts possible on the Fold 5's GN3/IMX258/IMX374
- Frame-count sets (20/11 ZSL photo, 50 video, 1-3 astro/long) are device-agnostic — fine

## 5. Practical recommendation

1. **Safe to import** — format-compatible (V13→V14 config keys unchanged), stale IDs are
   filtered by the live scan, no crash risk (verified mechanism in the AGC logs)
2. **Best-fitting pieces** (keep): slot layout, TAIMEN/REDFIN models, frame counts,
   "Medium" sharpness/merge tuning — these are SoC-class-appropriate (kalama)
3. **Mismatches to re-tune after import** (bind by slot, not by sensor identity):
   - slot 0 (Wide) noise/AWB → Fold 5 GN3, not HP2
   - slot 3 (Front) → Fold 5 IMX374 (binned 2304×1728), not S5K3LU
   - slots for 10x/54 and 5/6/7 simply won't activate — ignore
4. **Better end-state**: export the Fold 5's auto-detected config after our smali fixes
   (`AGC.9.2/configs/` on the device once configured) and use *that* as the base — the
   device tables in `libagc.so` (Go) auto-derive per-lens profiles from the live scan
   (focal/sensor-size/level3, per `getGcamSensorID`), so the auto base is already
   Fold-5-correct; the S23U file then only contributes the global "Medium" tuning values.

## 6. How to import (no root needed)

AGC menu → configs → import `.agc`, or drop it into
`/storage/emulated/0/Download/AGC.9.2/configs/` (the app's `pref_xml_path_key` points there)
and load it from the AGC settings page. App-scope only; nothing system-level.

## Analysis scripts

- `scripts/analyze_s23_config.py` — parse + compare against Fold5 HAL IDs
- `scripts/decode_s23_devices.py` — decode `pref_model/device_key` ints via the
  `PixelDevice` enum ordering from the decompiled APK (0=AUTO … 13=REDFIN … 19=CHEETAH)
