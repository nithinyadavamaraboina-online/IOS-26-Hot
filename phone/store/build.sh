#!/bin/sh
# Build SweetStore.app (/Applications/SweetStore.app) and sweetstored (/var/local/bin/sweetstored)
# for iOS 16 arm64 into phone/store/out (ad-hoc signed with entitlements).
set -e
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
CC="$ROOT/work/userland/bin/ios-cc"

rm -rf out
mkdir -p out/SweetStore.app

# 1. Root installer daemon (127.0.0.1:29170)
"$CC" -O3 -Wall -Wno-deprecated-declarations -fobjc-arc \
	-framework Foundation -lz \
	-o out/sweetstored sweetstored.m
strip -x out/sweetstored
codesign -f -s - --entitlements sweetstored-ents.plist out/sweetstored

# 2. Native UIKit Store & IPA Installer app
"$CC" -O3 -Wall -Wno-deprecated-declarations -fobjc-arc \
	-framework UIKit -framework QuartzCore -framework Foundation \
	-framework CoreGraphics -framework UniformTypeIdentifiers \
	-o out/SweetStore.app/SweetStore SweetStore.m
strip -x out/SweetStore.app/SweetStore
cp Info.plist out/SweetStore.app/Info.plist

# 3. Generate crisp PNG app icons (pure Python stdlib, zero external deps)
python3 - <<'PY'
import math, struct, zlib

def write_icon(path, size):
    rows = []
    cx = (size - 1) / 2.0
    for y in range(size):
        ty = y / float(size - 1)
        row = bytearray([0])  # PNG filter type 0 (None)
        for x in range(size):
            tx = x / float(size - 1)
            # Rich iOS App Store cyan -> cobalt -> indigo diagonal gradient
            t = 0.55 * ty + 0.45 * tx
            r = int(18 + (52 - 18) * t)
            g = int(168 + (74 - 168) * t)
            b = int(255 + (232 - 255) * t)

            # Normalized coordinates [-1, 1]
            nx = (x - cx) / (size * 0.5)
            ny = (y - cx) / (size * 0.5)

            # White rounded bag body + handle + downward install arrow
            alpha = 0.0
            # Bag body: x in [-0.46, 0.46], y in [-0.22, 0.56] with rounded corners
            bx = max(abs(nx) - 0.36, 0.0)
            by = max(abs(ny - 0.17) - 0.29, 0.0)
            bdist = math.hypot(bx, by) - 0.10
            if bdist < 0.02:
                alpha = max(alpha, min(1.0, (0.02 - bdist) * size * 0.35))

            # Bag handle arc above body: center (0, -0.20), radius 0.21, thickness 0.055
            if ny < -0.16:
                hdist = abs(math.hypot(nx, ny + 0.18) - 0.20) - 0.048
                if hdist < 0.02:
                    alpha = max(alpha, min(1.0, (0.02 - hdist) * size * 0.35))

            # Cutout install arrow inside bag (reveals gradient through white bag)
            cut = 0.0
            # Arrow shaft: |nx| <= 0.065, ny in [-0.04, 0.27]
            if -0.05 <= ny <= 0.26 and abs(nx) <= 0.072:
                cdist = max(abs(nx) - 0.058, abs(ny - 0.105) - 0.145)
                if cdist < 0.02:
                    cut = max(cut, min(1.0, (0.02 - cdist) * size * 0.35))
            # Arrow chevron head pointing down to (0, 0.38)
            ay = 0.37 - ny
            if 0.0 <= ay <= 0.22:
                half_w = ay * 1.05 + 0.02
                if abs(nx) <= half_w + 0.02:
                    cdist = abs(nx) - half_w
                    if cdist < 0.02:
                        cut = max(cut, min(1.0, (0.02 - cdist) * size * 0.35))

            eff = alpha * (1.0 - 0.88 * cut)
            if eff > 0.0:
                r = int(r * (1.0 - eff) + 255 * eff)
                g = int(g * (1.0 - eff) + 255 * eff)
                b = int(b * (1.0 - eff) + 255 * eff)
            row.extend((max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b))))
        rows.append(bytes(row))

    raw = b"".join(rows)
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)

write_icon("out/SweetStore.app/AppIcon60x60@2x.png", 120)
write_icon("out/SweetStore.app/AppIcon60x60@3x.png", 180)
PY

codesign -f -s - --entitlements SweetStore-ents.plist out/SweetStore.app
ls -la out out/SweetStore.app
