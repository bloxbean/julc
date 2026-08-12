#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${VERIFY_DIR}/../.." && pwd)"

"${SCRIPT_DIR}/bootstrap-z3.sh"

cd "${REPO_DIR}"
./gradlew :julc-cli:shadowJar -PskipSigning=true

cd "${VERIFY_DIR}"
# The offline phase compiles artifact modules directly for freshness. Build
# their external import root explicitly; CheckedExecution alone does not force
# Lake to materialize PlutusCore/UPLC.olean on a fresh runner.
lake build PlutusCore.UPLC JulcVerification.CheckedExecution

echo "Pinned Gradle, Lean, Lake, and Z3 dependencies are ready for offline evidence."
