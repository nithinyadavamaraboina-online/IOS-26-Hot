#!/bin/sh
# Build HakiosAudio.driver (CoreAudio HAL AudioServerPlugIn) for iOS 16 arm64 into phone/audio/out (signed ad hoc).
set -e
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
CC="$ROOT/work/userland/bin/ios-cc"
B=out/HakiosAudio.driver
rm -rf "$B" && mkdir -p "$B"

"$CC" -O3 -fno-stack-protector -Wall -Wextra -Wno-four-char-constants -Wno-unused-parameter -bundle \
	-framework CoreFoundation -Wl,-dead_strip \
	-o "$B/HakiosAudio" audio_plugin.c
strip -x "$B/HakiosAudio"
cp Info.plist "$B/Info.plist"
codesign -f -s - "$B" 2>/dev/null

WEAK=$(nm -m "$B/HakiosAudio" | grep "weak external" | grep -v "dynamically looked up" || true)
if [ -n "$WEAK" ]; then
	echo "ERROR: unexpected weak external symbols in $B/HakiosAudio:" >&2
	echo "$WEAK" >&2
	exit 1
fi
ls -la "$B"
