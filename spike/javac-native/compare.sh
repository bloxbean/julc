#!/usr/bin/env bash
# Compare the deterministic part of two probe outputs (drops TIMING, mode/platform banner and OPENED lines).
set -euo pipefail
filter() { grep -v -e '^TIMING' -e '^using ' -e '^OPENED' -e '^embedded platform' "$1"; }
if diff <(filter "$1") <(filter "$2"); then echo "SAME: $1 == $2"; else echo "DIFFERENT: $1 vs $2"; exit 1; fi
