#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVIDENCE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${EVIDENCE_DIR}/../.." && pwd)"
cd "${REPO_DIR}"

./gradlew :julc-compiler:test :julc-blueprint:test :julc-verification:test :julc-cli:test
verification/c3/scripts/verify.sh
verification/c5/scripts/verify.sh
verification/c6/scripts/verify.sh
verification/c7/scripts/verify.sh

echo "ADR-015 strict-boundary evidence passed."
