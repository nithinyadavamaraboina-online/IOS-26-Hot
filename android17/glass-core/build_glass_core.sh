#!/usr/bin/env bash
# Build shared clean-room 90Hz AGSL & Spring library (`samurai-glass-core-classes.jar`)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_ROOT="${WORK_ROOT:-/var/tmp/rmx1931-a17}"
PLATFORM_JAR="${WORK_ROOT}/tools/android-sdk/platforms/android-35/android.jar"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

BUILD_TMP="${WORK_ROOT}/work/glass-core-build"
OUT_FWK="${WORK_ROOT}/out/framework"
rm -rf "${BUILD_TMP}"
mkdir -p "${BUILD_TMP}/classes" "${OUT_FWK}"

mapfile -t src_files < <(find "${SCRIPT_DIR}/src" -name "*.java" | sort)

"${JAVA_HOME}/bin/javac" \
    -Xlint:all -Werror \
    -source 17 -target 17 \
    -encoding UTF-8 \
    -classpath "${PLATFORM_JAR}" \
    -d "${BUILD_TMP}/classes" \
    "${src_files[@]}"

"${JAVA_HOME}/bin/jar" cf "${OUT_FWK}/samurai-glass-core-classes.jar" -C "${BUILD_TMP}/classes" .
ls -lh "${OUT_FWK}/samurai-glass-core-classes.jar"
