#!/usr/bin/env python
"""Second pass on S23 config: noise models + device index decode."""
import re

CFG = r"G:\projects\fold5-camera2-research\JavaSaBr_S23_AGC92v13_v52_Medium.agc"

PI = {
    0: "AUTO", 1: "SAILFISH (P1)", 2: "MARLIN (P1 XL)", 3: "WALLEYE (P2)",
    4: "TAIMEN (P2 XL)", 5: "BLUELINE (P3)", 6: "CROSSHATCH (P3 XL)",
    7: "SARGO (P3a)", 8: "BONITO (P3a XL)", 9: "FLAME (P4)", 10: "CORAL (P4 XL)",
    11: "SUNFISH (P4a)", 12: "BRAMBLE (P4a 5G)", 13: "REDFIN (P5)",
    14: "BARBET (P5a)", 15: "ORIOLE (P6)", 16: "RAVEN (P6 Pro)",
    17: "BLUEJAY (P6a)", 18: "PANTHER (P7)", 19: "CHEETAH (P7 Pro)",
    20: "LYNX (P7a)", 21: "FELIX (P Fold)", 22: "PIPIT", 23: "TANGOR (P8)",
    24: "HUSKY (P8 Pro)", 25: "SHIBA (P8a)", 26: "AKITA",
    27: "TOKAY (P9)", 28: "CAIMAN (P9 Pro)", 29: "COMODO (P10)",
}

with open(CFG, encoding="utf-8") as fh:
    text = fh.read()

print("=== noise model bindings (lib_pref_noise_model_key_pN_M) ===")
for m in re.finditer(r'name="(lib_pref_noise_model_key_p\d_\d)"[^>]*>([^<]*)<', text):
    print(f"  {m.group(1)} = {m.group(2)}")

print("\n=== explicit .c model file references ===")
for m in re.finditer(r'>([^<]*\.c)<', text):
    print(f"  {m.group(1)}")

print("\n=== device keys decoded ===")
for m in re.finditer(r'name="pref_device_key(?:_(\d))?"[^>]*>(\d+)<', text):
    idx = m.group(1)
    val = int(m.group(2))
    name = PI.get(val, f"UNKNOWN({val})")
    suffix = f"[lens slot {idx}]" if idx else "[global]"
    print(f"  pref_device_key{('_' + idx) if idx else ''} = {val} -> {name} {suffix}")

print("\n=== model keys decoded ===")
for m in re.finditer(r'name="pref_model_key(?:_(\d))?"[^>]*>(\d+)<', text):
    idx = m.group(1)
    val = int(m.group(2))
    name = PI.get(val, f"UNKNOWN({val})")
    suffix = f"[lens slot {idx}]" if idx else "[global]"
    print(f"  pref_model_key{('_' + idx) if idx else ''} = {val} -> {name} {suffix}")
