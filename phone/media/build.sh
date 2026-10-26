#!/bin/sh
# Build libsweet_media.dylib and libsweet_sysfix.dylib for iOS 16 arm64 into phone/media/out (signed ad hoc).
set -e
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
CC="$ROOT/work/userland/bin/ios-cc"
mkdir -p out

"$CC" -O3 -fno-stack-protector -Wall -Wextra -Wno-unused-parameter -dynamiclib \
	-install_name /usr/local/lib/libsweet_media.dylib \
	-framework CoreFoundation -framework CoreMedia -framework CoreVideo -lobjc \
	-Wl,-dead_strip -o out/libsweet_media.dylib inject_media.c
strip -x out/libsweet_media.dylib
codesign -f -s - out/libsweet_media.dylib 2>/dev/null

"$CC" -O3 -fno-stack-protector -Wall -Wextra -Wno-unused-parameter -dynamiclib \
	-install_name /usr/local/lib/libsweet_sysfix.dylib \
	-framework CoreFoundation -lobjc \
	-Wl,-dead_strip -o out/libsweet_sysfix.dylib inject_sysfix.c
strip -x out/libsweet_sysfix.dylib
codesign -f -s - out/libsweet_sysfix.dylib 2>/dev/null

for dylib in out/libsweet_media.dylib out/libsweet_sysfix.dylib; do
	WEAK=$(nm -m "$dylib" | grep "weak external" | grep -v "dynamically looked up" || true)
	if [ -n "$WEAK" ]; then
		echo "ERROR: unexpected weak external symbols in $dylib:" >&2
		echo "$WEAK" >&2
		exit 1
	fi
done
ls -la out/libsweet_media.dylib out/libsweet_sysfix.dylib
