#!/usr/bin/env python3
"""Packs the Thmanyah fonts for the APK (run by tools/fonts.sh in CI).

Thmanyah's license allows the font inside an app only as part of a compiled, packaged or obfuscated
product, and forbids making the font files extractable. So the APK never carries the .otf files:
each weight is stored scrambled (app/src/main/assets/type/tNNN.bin) and the app unscrambles it in
memory (ui/theme/AppFonts.kt). The scramble below must match AppFonts.unscramble exactly.

usage: pack_fonts.py <dir with the five Thmanyah Sans .otf files> <output dir>
"""
import pathlib
import sys

MASK = (1 << 64) - 1
BASE = 0x9E3779B97F4A7C15
MAGIC = b"HSF1"
WEIGHTS = {"light": 300, "regular": 400, "medium": 500, "bold": 700, "black": 900}


def seed(weight: int) -> int:
    s = (BASE ^ (weight * 0x100000001B3)) & MASK
    return s or 1


def scramble(data: bytes, weight: int) -> bytes:
    x = seed(weight)
    out = bytearray(len(data))
    for i, b in enumerate(data):
        x ^= (x << 13) & MASK
        x ^= x >> 7
        x ^= (x << 17) & MASK
        out[i] = b ^ (x >> 56)
    return bytes(out)


def main() -> int:
    src, dst = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)
    found = 0
    for f in sorted(src.glob("*.otf")):
        name = f.stem.lower()
        weight = next((w for k, w in WEIGHTS.items() if name.endswith(k)), None)
        if weight is None:
            continue
        (dst / f"t{weight}.bin").write_bytes(MAGIC + scramble(f.read_bytes(), weight))
        found += 1
        print(f"packed weight {weight}")
    if found != len(WEIGHTS):
        print(f"expected {len(WEIGHTS)} weights, found {found}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
