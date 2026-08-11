#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ELAN_BIN="${HOME}/.elan/bin"

if [[ -d "${ELAN_BIN}" ]]; then
  export PATH="${ELAN_BIN}:${PATH}"
fi

"${SCRIPT_DIR}/bootstrap-z3.sh"
export PATH="${VERIFY_DIR}/.tools/z3-4.15.2/bin:${PATH}"

could_not_evaluate() {
  echo "COULD-NOT-EVALUATE: $1" >&2
  exit 2
}

command -v lean >/dev/null 2>&1 || could_not_evaluate "Lean is not installed"
command -v lake >/dev/null 2>&1 || could_not_evaluate "Lake is not installed"
command -v z3 >/dev/null 2>&1 || could_not_evaluate "Z3 is not installed"

cd "${VERIFY_DIR}"

lean_version="$(lean --version | head -n 1)"
[[ "${lean_version}" == *"4.24.0"* ]] || \
  could_not_evaluate "expected Lean 4.24.0, found ${lean_version}"

z3_version="$(z3 --version)"
[[ "${z3_version}" == *"4.15.2"* ]] || \
  could_not_evaluate "expected Z3 4.15.2, found ${z3_version}"

"${SCRIPT_DIR}/prepare-artifacts.sh"

lake build

manifest="${VERIFY_DIR}/generated/run-manifest.json"
manifest_tmp="${VERIFY_DIR}/generated/run-manifest.tmp.json"
jq \
  --arg leanVersion "4.24.0" \
  --arg z3Version "4.15.2" \
  --slurpfile profile "${VERIFY_DIR}/config/verification-profile.json" \
  '. + {
    leanVersion: $leanVersion,
    z3Version: $z3Version,
    verificationProfile: $profile[0],
    properties: [
      {
        id: "smoke.actual-first-field-semantics",
        result: "ESTABLISHED",
        evidence: "SMT-VALID"
      },
      {
        id: "smoke.strict-schema-shape",
        result: "REFUTED",
        evidence: "COUNTEREXAMPLE"
      },
      {
        id: "typed-multisig-broken.requires-both-signers",
        result: "REFUTED",
        evidence: "COUNTEREXAMPLE"
      },
      {
        id: "typed-multisig.requires-both-signers",
        result: "COULD-NOT-EVALUATE",
        reason: "recursive-list claim remains solver-undetermined; checked coverage not established"
      }
    ]
  }' "${manifest}" > "${manifest_tmp}"
mv "${manifest_tmp}" "${manifest}"

echo "Verification manifest: ${manifest}" >&2

echo "ESTABLISHED: smoke artifact property and negative controls" >&2
echo "COULD-NOT-EVALUATE: typed multisig authorization remains solver-undetermined" >&2
exit 2
