# Notices for this repository

## What is NOT included (and why)

- **The AGC APKs** (original and patched, ~400-500 MB each): they are a modified build of
  Google's proprietary Pixel Camera (GCam) binary with BigKaka's AGC patches on top. The full
  APKs are not redistributed here. The two device-specific fixes are provided as small smali
  diffs in `patches/` so anyone holding the original AGC9.2.14 `_ruler` APK can rebuild
  locally with apktool, exactly as documented in the README.
- **Samsung system binaries** (`cameraserver`, camera provider/HAL `.so` files pulled from
  the device): proprietary Samsung code, kept out of the repo. The research findings derived
  from them (function names, addresses, decompiled behavior) are documented in
  `ida-analysis/cameraserver-gate-analysis.md` for interoperability/research purposes.
- **Full 1.8 MB dumpsys / 900 KB logcat captures**: trimmed to the relevant static metadata
  and crash excerpts; the full captures remain in the local research folder.

## Personal-use research

Everything here documents behavior of the researcher's own device. No DRM circumvention, no
Samsung Knox bypass, no bootloader unlock — the device stays locked with temporary root and
zero system-partition modifications (see README §5 safety record).

## Copyright of the sample photo

`device-evidence/AGC_first_photo.jpg` is a test photo captured by the researcher of the
researcher's own environment; released as evidence artifact (CC0).
