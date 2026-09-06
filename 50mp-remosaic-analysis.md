# 50MP / Full-Resolution (Remosaic) Investigation — SM-F946B

**Question:** Can AGC 9.2 (GCam) capture 50MP full-resolution (8160×6120, unbinned) on the
Fold 5 main lens?

**Verdict: No — not through any GCam-compatible API path on this firmware.** The sensor and
HAL support it, but Samsung does not expose it through the standard Android API that GCam's
50MP ("lobster") pipeline reads. Details and evidence below.

## What the hardware CAN do (proven)

From Samsung's own vendor tags on hidden lens 56 (physical main, S5KGN3), captured in
`device-evidence/dumpsys_media_camera.txt`:

- `samsung.android.scaler.availableRemosaicCropCapabilities (810c0025) = [1 1 2000]`
  → remosaic **supported** on 56 (note: camera 0/logical reports `0` — disabled there)
- `samsung.android.unihal.mmfSize (81200017) = [8160 6120 ...]` → full 50MP array available
- `samsung.android.scaler.availableHighresRawStreamConfigurations (810c0022)`
  = `[56 8160 6120 1]` → Samsung's private full-res RAW stream declaration
- `samsung.android.scaler.availableProRawStreamConfigurations (810c0023)` → same, ProRaw path

Expert RAW (Samsung's own app) uses these private tags — that's how it delivers 50MP.

## What GCam's 50MP mode needs (proven by decompile)

GCam 9.2's hires/"lobster" path (`iip.smali` → `irf.c(ngi, format, is48MP=true)`):
`is48MP=true` switches the lookup from `SCALER_STREAM_CONFIGURATION_MAP` to
**`SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`** — the AOSP Android 14+
maximum-resolution stream table — and expects RAW10/RAW16 entries at 8160×6120 in it.

The mode is gated by the `camera.lobster.enabled` phenotype flag + a user toggle, both of
which we enabled on-device (flag written into the app's flag-store SharedPreferences —
`fps.m()` reads SharedPreferences-first, store = `PreferenceManager.getDefaultSharedPreferences`,
proven via `gvf.smali` case 9).

## Why it still can't work (probe-proven)

We compiled a diagnostic dex (`probe/Probe.java`, run via `app_process` with root) that
queries `CameraCharacteristics` directly:

```text
PIDS: [0, 1, 2, 3]                          <- package gate hides aux IDs from system ctx too
== cam 0/2 (and 5, 6) ==
  std RAW_SENSOR: [4080x3060] / [4000x3000]  <- only binned sizes in the standard table
  MAXRES map: null                           <- SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                                               is NULL on EVERY camera
== cam 20/21/23/52/56/58/71/73 ==            <- hidden IDs
  ERR: Unknown camera ID                     <- PackagePolicyList gate rejects characteristics too
```

**Samsung's Android 16 firmware does not populate `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`
on any camera.** Without that table, GCam's 50MP path has no size list to read — enabling the
lobster flag does nothing (verified: `irf is48MP false` stayed false across Comodo and
Felix device-interface profiles with the flag set).

Device-interface experiment: switching AGC's "Device Interface" to Pixel Fold (Felix) made
GCam crash with `IndexOutOfBoundsException` in `jtl` (zoom/lens-list built from the Felix
profile is empty for our lens topology) — reverted to Comodo, which runs clean. Conclusion:
the Pixel-profile picker changes HDR+ model + processing defaults but cannot conjure the
missing HAL table.

## What 50MP would require (for future work)

1. **App-side vendor-tag path**: an app that reads Samsung's `810c0022`/`810c0023` tags
   (they're in the characteristics blob — `samsung.android.scaler.*` keys are visible to
   any app holding the characteristics of an accessible camera) and configures the session
   via Samsung's UniHAL parameter hints. This is exactly what Expert RAW does — but it
   requires reimplementing Samsung's capture stack around the private stream, not GCam's.
2. **Framework change**: Samsung populating the standard MAX_RESOLUTION table — would make
   GCam's built-in 50MP mode work instantly. Not something we can change on a locked
   bootloader (that table is built inside the Samsung camera HAL/provider).
3. Logical camera 0 has remosaic **disabled** by Samsung (`[0 1 2000]`) — even the logical
   device wouldn't help.

## Practical maximum today

- GCam/AGC: **4080×3060 (12.5MP binned)** — full HDR+ quality pipeline, verified captures.
- Expert RAW (Samsung): 50MP — the only full-res path available to a locked device.
- The 50MP/8160 files: `availableRawSizes` includes `8160x6120` as a declared raw size, but
  no accessible standard stream carries it; declaring ≠ streamable via public API.

## Evidence files

- `device-evidence/dumpsys_media_camera.txt` — vendor tags (mmfSize, RemosaicCrop, highres raw)
- `device-evidence/lobster_test1.txt`, `felix_session.txt` — flag-injection + Felix experiments
- `probe/Probe.java` + probe output (in this document) — MAXRES=null proof
- `gcam_decompiled/smali/irf.smali`, `iip.smali`, `fps.smali`, `gvf.smali`, `hlo.smali` —
  the lobster gate chain and flag-store mechanics
