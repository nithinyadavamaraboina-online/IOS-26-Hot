#!/usr/bin/env bash
# Dual-Channel Public GitHub Release & OTA Manifest Publisher for RMX1931:
#   https://github.com/nithinyadavamaraboina-online/PixelOS-Android17-RMX1931
# Usage:
#   bash publish_public_repo.sh [--channel stable|ios-beta|both] [--ota-only]
# Safety order:
#   1. Uploads all GitHub Release binary assets FIRST (Stable -> latest release; iOS Beta -> --prerelease)
#   2. Commits & pushes README.md + ota/{rmx1931.json, stable/rmx1931.json, ios-beta/rmx1931.json} SECOND
#      so devices never encounter a 404 download URL!
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A17_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${A17_ROOT}/.." && pwd)"
WIN_OUT="${REPO_ROOT}/work/a17/out"
PUB_SRC="${A17_ROOT}/public-repo"
PUB_GIT="/var/tmp/rmx1931-a17/pixelos_public_git"
TARGET_REPO="nithinyadavamaraboina-online/PixelOS-Android17-RMX1931"

PUB_CHANNEL="both"
OTA_ONLY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --channel)
            PUB_CHANNEL="${2:-}"
            shift 2
            ;;
        --channel=*)
            PUB_CHANNEL="${1#*=}"
            shift
            ;;
        --ota-only)
            OTA_ONLY=1
            shift
            ;;
        *)
            echo "ERROR: Unknown argument '$1'" >&2
            exit 1
            ;;
    esac
done

case "${PUB_CHANNEL}" in
    stable|ios-beta|both) ;;
    *)
        echo "ERROR: Invalid --channel '${PUB_CHANNEL}' (expected stable, ios-beta, or both)" >&2
        exit 1
        ;;
esac

GIT_WIN="/mnt/c/Program Files/Git/cmd/git.exe"
if ! [ -x "${GIT_WIN}" ]; then
    GIT_WIN="$(command -v git.exe || command -v git)"
fi

CRED_OUT="$(printf 'protocol=https\nhost=github.com\n\n' | "${GIT_WIN}" credential fill)"
GH_USER="$(printf '%s\n' "${CRED_OUT}" | awk -F= '/^username=/{print $2; exit}' | tr -d '\r\n')"
GH_PASS="$(printf '%s\n' "${CRED_OUT}" | awk -F= '/^password=/{print $2; exit}' | tr -d '\r\n')"
[ -n "${GH_PASS}" ] || { echo "ERROR: Could not read GitHub credential"; exit 1; }
[ -n "${GH_USER}" ] || GH_USER="nithinyadavamaraboina-online"

REPO_ROOT_WIN="$(wslpath -w "${REPO_ROOT}" 2>/dev/null || echo "${REPO_ROOT}")"
GIT_AUTHOR_NAME="$("${GIT_WIN}" -C "${REPO_ROOT_WIN}" config user.name | tr -d '\r\n' || echo "${GH_USER}")"
GIT_AUTHOR_EMAIL="$("${GIT_WIN}" -C "${REPO_ROOT_WIN}" config user.email | tr -d '\r\n' || echo "${GH_USER}@users.noreply.github.com")"

echo "=== [1/4] Syncing standalone public repository checkout (${TARGET_REPO}) ==="
rm -rf "${PUB_GIT}"
mkdir -p "${PUB_GIT}/docs" "${PUB_GIT}/ota/stable" "${PUB_GIT}/ota/ios-beta"
git -C "${PUB_GIT}" init -b main
git -C "${PUB_GIT}" config user.name "${GIT_AUTHOR_NAME}"
git -C "${PUB_GIT}" config user.email "${GIT_AUTHOR_EMAIL}"

ASKPASS_SH="/var/tmp/rmx1931-a17/.gh_askpass.sh"
cat > "${ASKPASS_SH}" <<EOF
#!/usr/bin/env bash
case "\$1" in
  *Username*) printf '%s\n' '${GH_USER}' ;;
  *)          printf '%s\n' '${GH_PASS}' ;;
esac
EOF
chmod 0700 "${ASKPASS_SH}"
trap 'rm -f "${ASKPASS_SH}"' EXIT

git -C "${PUB_GIT}" remote add origin "https://github.com/${TARGET_REPO}.git"
if GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="${ASKPASS_SH}" git -C "${PUB_GIT}" fetch origin main >/dev/null 2>&1; then
    git -C "${PUB_GIT}" checkout -B main FETCH_HEAD
fi
mkdir -p "${PUB_GIT}/docs" "${PUB_GIT}/ota/stable" "${PUB_GIT}/ota/ios-beta"

echo "=== [2/4] Uploading Release Assets FIRST (channel=${PUB_CHANNEL}) ==="
if [ "${PUB_CHANNEL}" = "stable" ] || [ "${PUB_CHANNEL}" = "both" ]; then
    LEGACY_MANIFEST="${REPO_ROOT}/ota/rmx1931.json"
    STABLE_MANIFEST="${REPO_ROOT}/ota/stable/rmx1931.json"
    [ -f "${LEGACY_MANIFEST}" ] && [ -f "${STABLE_MANIFEST}" ] || { echo "ERROR: Missing Stable manifests"; exit 1; }
    if [ "${OTA_ONLY}" = "1" ]; then
        STABLE_ASSETS=(
            "${WIN_OUT}/RMX1931-Android17-PixelOS-OTA.zip"
            "${WIN_OUT}/SHA256SUMS.txt"
            "${WIN_OUT}/BUILD-MANIFEST.json"
            "${WIN_OUT}/SamuraiOTAUpdater.apk"
        )
    else
        STABLE_ASSETS=(
            "${WIN_OUT}/RMX1931-Android17-PixelOS-VendorBoot.zip"
            "${WIN_OUT}/RMX1931-Android17-PixelOS-System.zip"
            "${WIN_OUT}/RMX1931-Android17-PixelOS-OTA.zip"
            "${WIN_OUT}/SHA256SUMS.txt"
            "${WIN_OUT}/BUILD-MANIFEST.json"
            "${WIN_OUT}/SamuraiOTAUpdater.apk"
        )
    fi
    for f in "${STABLE_ASSETS[@]}"; do
        [ -f "${f}" ] || { echo "ERROR: Missing Stable asset ${f}"; exit 1; }
    done
    GITHUB_TOKEN="${GH_PASS}" python3 "${SCRIPT_DIR}/gh_release_upload.py" "${LEGACY_MANIFEST}" "${STABLE_ASSETS[@]}"
fi

if [ "${PUB_CHANNEL}" = "ios-beta" ] || [ "${PUB_CHANNEL}" = "both" ]; then
    BETA_MANIFEST="${REPO_ROOT}/ota/ios-beta/rmx1931.json"
    [ -f "${BETA_MANIFEST}" ] || { echo "ERROR: Missing iOS Beta manifest ${BETA_MANIFEST}"; exit 1; }
    BETA_FNAME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["response"][0]["filename"])' "${BETA_MANIFEST}")"
    BETA_ASSETS=("${WIN_OUT}/${BETA_FNAME}")
    [ -f "${WIN_OUT}/SHA256SUMS-ios-beta.txt" ] && BETA_ASSETS+=("${WIN_OUT}/SHA256SUMS-ios-beta.txt")
    [ -f "${WIN_OUT}/BUILD-MANIFEST-ios-beta.json" ] && BETA_ASSETS+=("${WIN_OUT}/BUILD-MANIFEST-ios-beta.json")
    for f in "${BETA_ASSETS[@]}"; do
        [ -f "${f}" ] || { echo "ERROR: Missing iOS Beta asset ${f}"; exit 1; }
    done
    GITHUB_TOKEN="${GH_PASS}" python3 "${SCRIPT_DIR}/gh_release_upload.py" --prerelease "${BETA_MANIFEST}" "${BETA_ASSETS[@]}"
fi

echo "=== [3/4] Committing README & Dual-Channel OTA Manifests to ${TARGET_REPO}@main ==="
cp -f "${PUB_SRC}/README.md" "${PUB_GIT}/README.md"
cp -f "${PUB_SRC}/docs/XDA_THREAD_TEMPLATE.bbcode" "${PUB_GIT}/docs/XDA_THREAD_TEMPLATE.bbcode"
sed -i 's/\r$//' "${PUB_GIT}/README.md" "${PUB_GIT}/docs/XDA_THREAD_TEMPLATE.bbcode"
git -C "${PUB_GIT}" add README.md docs/XDA_THREAD_TEMPLATE.bbcode

COMMIT_LABEL=""
if [ "${PUB_CHANNEL}" = "stable" ] || [ "${PUB_CHANNEL}" = "both" ]; then
    cp -f "${REPO_ROOT}/ota/rmx1931.json" "${PUB_GIT}/ota/rmx1931.json"
    cp -f "${REPO_ROOT}/ota/stable/rmx1931.json" "${PUB_GIT}/ota/stable/rmx1931.json"
    sed -i 's/\r$//' "${PUB_GIT}/ota/rmx1931.json" "${PUB_GIT}/ota/stable/rmx1931.json"
    git -C "${PUB_GIT}" add ota/rmx1931.json ota/stable/rmx1931.json
    S_VER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["response"][0]["version"])' "${REPO_ROOT}/ota/stable/rmx1931.json")"
    COMMIT_LABEL="stable ${S_VER}"
fi

if [ "${PUB_CHANNEL}" = "ios-beta" ] || [ "${PUB_CHANNEL}" = "both" ]; then
    cp -f "${REPO_ROOT}/ota/ios-beta/rmx1931.json" "${PUB_GIT}/ota/ios-beta/rmx1931.json"
    sed -i 's/\r$//' "${PUB_GIT}/ota/ios-beta/rmx1931.json"
    git -C "${PUB_GIT}" add ota/ios-beta/rmx1931.json
    B_VER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["response"][0]["version"])' "${REPO_ROOT}/ota/ios-beta/rmx1931.json")"
    COMMIT_LABEL="${COMMIT_LABEL:+${COMMIT_LABEL} + }ios-beta ${B_VER}"
fi

if ! git -C "${PUB_GIT}" diff --cached --quiet; then
    git -C "${PUB_GIT}" commit -m "ota(rmx1931): publish ${COMMIT_LABEL}"
    for push_try in 1 2 3; do
        if GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="${ASKPASS_SH}" git -C "${PUB_GIT}" push origin main; then
            break
        fi
        [ "${push_try}" -eq 3 ] && { echo "ERROR: git push failed after 3 attempts"; exit 1; }
        sleep 4
    done
fi

echo "=== [4/4] Done! Published ${COMMIT_LABEL} to https://github.com/${TARGET_REPO} ==="
