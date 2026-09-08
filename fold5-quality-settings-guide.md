# AGC 9.2 on Fold 5 (SM-F946B) — Recommended Settings per Lens

Grounded in the verified HAL scan (`device-evidence/hal_device_summary.txt`, `dumpsys_media_camera_static.txt`):

| Lens (zoom chip) | HAL ID | RAW support | OIS | Best processing path |
| -------------- | ------ | ----------- | --- | -------------------- |
| Main 1x | 56 | ✅ RAW 4080×3060 (LEVEL_3) | ✅ | Full HDR+ RAW merge |
| UW 0.6x | 58 | ✅ RAW 4000×3000 | ❌ | HDR+ RAW merge (software align) |
| Tele 3x | 52 | ✅ RAW 3648×2736 | ✅ | HDR+ RAW merge |
| Front | 71 | ❌ none (YUV only) | ❌ | Light/no merge — YUV path |

The two patched APK fixes make all four work; front cannot ever do RAW merge (Samsung HAL
publishes no RAW streams on ID 71 — see README §4 Fix 2), so treat it differently.

## Global (Settings → AGC main settings)

- **HDR+ : Auto** — on for back lenses, skip in low-motion-free dark scenes → use Night Sight
- **JPEG quality: 99** (also Lib settings → hard jpg quality → 99; default patch ships 97)
- **RAW output: OFF** (unless you want DNGs; they're 4080×3060 16-bit per shot)
- **Night Sight**: back lenses only, for actual low light. Skip on front (no RAW → mushy)
- **Astrophotography**: works (tripod), back lenses, 45-frame default

## Per-lens (Settings → AGC → Lens Settings → [Wide / Ultra Wide / Tele / Front] page)

**Main (Wide / ID 56):**

- HDR+ model: **AUTO** (auto-derives `redfin` — verified in logs; don't set exotic models)
- Image format: **RAW_SENSOR/RAW16** (app default 32 — keep)
- Resolution: **4080×3060** max
- ZSL/HDR+ frames: **15–20** (default shipped ~9; raise for cleaner shadows, slower shutter feel)
- Noise model: **AUTO**; AWB: default

**Ultra Wide (ID 58):**

- Same as main; frames **10–15** (no OIS — frames beyond that rarely add on a 14mm-eq)
- Fix-resolution: leave AUTO (picks 4000×3000)

**Tele 3x (ID 52):**

- Same as main; frames **10–15**; OIS on
- In dim indoor light prefer 2x digital-crop on main over 3x tele at high ISO

**Front (ID 71):**

- Image format: will show YUV — **that's correct, nothing to fix**
- HDR+ on front: **OFF for people/motion** (YUV merge ghosts), modest (≤8 frames) only for static scenes
- Night Sight front: avoid
- Selfie mirror: to taste (doesn't affect quality)

## Lib settings (Settings → AGC → Lib settings)

- Sharpness / merge / denoise: **Medium** tier — either set manually, or import the
  `JavaSaBr_S23_AGC92v13_v52_Medium.agc` for its Medium values (safe: the app discards the
  S23U's lens/ID bindings on import — verified in `ConfigLoader.smali` blackListKey)
- Keep noise model & AWB pickers on **AUTO** (see below)

## Experiments (optional, try and compare)

1. Main-lens noise model → **"S22U HM3" entry** (in libagc's DB). The S22U main is the closest
   Samsung-Snapdragon 50MP profile to your GN3 — may beat AUTO in extreme low light
2. **50MP Expert RAW Mode toggle** (Settings → Lens Setting → Main, since `fold5fix_v3`).
   In `fold5fix_v4.1` this toggle **only controls the 50MP viewfinder button**: ON = button
   visible, OFF = fully stock viewfinder. The normal shutter is ALWAYS GCam's own 12MP
   pipeline regardless of the toggle.
3. **50MP ×6 burst button** (since `fold5fix_v4`, fixed in `v4.1`) — the **50MP** oval
   button above the bottom-right controls (visible when the toggle is ON). Fires a
   6-frame 8160×6120 burst and merges them (temporal denoise, neutral color) into one
   ~35 MB JPEG, ~5–9 s total; the viewfinder auto-recovers after. Same single-AE
   limitation (not an HDR bracket). Daylight/static scenes; keep the phone still.
4. Tele Night Sight on tripod — 3648×2736 RAW + OIS handles it surprisingly well

## Don't waste time on

- Importing S23U **noise/AWB per-lens** values — tuned for HP2/IMX586/S5K3LU sensors you
  don't have; AUTO (live-derived from your scan) is already closer than any borrowed profile
  (the libagc DB has no GN3 entry at all)
- Pixel 6/7/8 HDR+ models — wrong sensor class, stick with redfin-class defaults
- Front-camera RAW anything — hardware doesn't offer it (proven in HAL metadata)
- Any system-level "unlock" work — already done via `_ruler` package + the patches; nothing
  further to enable
- The old "48MP/full-res" Advanced-Settings toggle — superseded by the real 50MP Expert RAW
  Mode toggle (v3). The old knob couldn't engage the Samsung remosaic pipeline; the new one
  does (proven: true 8160×6120 output)

## Where the menus live (BigKaka AGC 9.2 UI)

Open camera → tap the **chevron/arrow above the mode strip** (or swipe up on it) → **⚙ / More
options** → the AGC sections appear after Google's own: **AGC**, **AGC Lens Settings**
(per-lens pages), **AGC Advanced**, **AGC Lib**, **AGC Experimental**. Settings apply per
lens-slot (0=Wide, 1=UW, 2=Tele, 3=Front, 4/5=Video). The app auto-restarts the camera
after changes — normal.
