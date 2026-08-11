#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JULC_GRADLE_OFFLINE=true
exec "${SCRIPT_DIR}/verify.sh" --offline
