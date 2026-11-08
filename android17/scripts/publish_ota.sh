#!/usr/bin/env bash
# Wrapper delegating to publish_public_repo.sh for Dual-Channel PixelOS Stable & Opt-In iOS Style UI Beta
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/publish_public_repo.sh" "$@"
