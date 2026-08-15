#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ELAN_BIN="${HOME}/.elan/bin"
OFFLINE=false

if [[ "${1:-}" == "--offline" ]]; then
  OFFLINE=true
elif [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--offline]" >&2
  exit 2
fi

if [[ -d "${ELAN_BIN}" ]]; then
  export PATH="${ELAN_BIN}:${PATH}"
fi

if [[ "${OFFLINE}" == true ]]; then
  "${SCRIPT_DIR}/bootstrap-z3.sh" --check
else
  "${SCRIPT_DIR}/bootstrap-z3.sh"
fi
export PATH="${VERIFY_DIR}/.tools/z3-4.15.2/bin:${PATH}"

could_not_evaluate() {
  echo "COULD-NOT-EVALUATE: $1" >&2
  exit 2
}

for command_name in lean lake z3 jq git rg; do
  command -v "${command_name}" >/dev/null 2>&1 || \
    could_not_evaluate "${command_name} is not installed"
done

cd "${VERIFY_DIR}"

lean_version="$(lean --version | head -n 1)"
[[ "${lean_version}" == *"4.24.0"* ]] || \
  could_not_evaluate "expected Lean 4.24.0, found ${lean_version}"

z3_version="$(z3 --version)"
[[ "${z3_version}" == *"4.15.2"* ]] || \
  could_not_evaluate "expected Z3 4.15.2, found ${z3_version}"

while IFS=$'\t' read -r package expected_revision; do
  package_dir="${VERIFY_DIR}/.lake/packages/${package}"
  [[ -d "${package_dir}/.git" ]] || \
    could_not_evaluate "Lake dependency ${package} is not acquired"
  actual_revision="$(git -C "${package_dir}" rev-parse HEAD)"
  [[ "${actual_revision}" == "${expected_revision}" ]] || \
    could_not_evaluate "${package} expected ${expected_revision}, found ${actual_revision}"
done < <(jq -r '.packages[] | [.name, .rev] | @tsv' lake-manifest.json)

if rg -n '(^|[[:space:]])(sorry|admit)([[:space:]]|$)' \
    JulcVerification.lean JulcVerification; then
  could_not_evaluate "project-owned Lean source contains sorry or admit"
fi

"${SCRIPT_DIR}/prepare-artifacts.sh"
"${SCRIPT_DIR}/validate-counterexamples.sh"

RESULTS_DIR="${VERIFY_DIR}/results"
OLEAN_DIR="${VERIFY_DIR}/.lake/build/lib/lean/JulcVerification"
mkdir -p "${RESULTS_DIR}" "${OLEAN_DIR}"

verify_module() {
  local module="$1"
  local source="$2"
  local output="$3"
  echo "Verifying ${module} ..."
  if ! lake env lean -o "${output}" "${source}" 2>&1 | \
      tee "${RESULTS_DIR}/${module}.log"; then
    could_not_evaluate "Lean verification failed in ${module}"
  fi
}

# Compile every artifact-importing module directly. Lake does not treat the
# imported hex path as a source dependency, so a plain cached `lake build`
# could otherwise replay an olean produced for older UPLC bytes.
verify_module "CheckedExecution" \
  "JulcVerification/CheckedExecution.lean" \
  "${OLEAN_DIR}/CheckedExecution.olean"
verify_module "Smoke" \
  "JulcVerification/Smoke.lean" \
  "${OLEAN_DIR}/Smoke.olean"
verify_module "StateThread" \
  "JulcVerification/StateThread.lean" \
  "${OLEAN_DIR}/StateThread.olean"
verify_module "StateThreadNegative" \
  "JulcVerification/StateThreadNegative.lean" \
  "${OLEAN_DIR}/StateThreadNegative.olean"
verify_module "ControlledMint" \
  "JulcVerification/ControlledMint.lean" \
  "${OLEAN_DIR}/ControlledMint.olean"
verify_module "ControlledMintNegative" \
  "JulcVerification/ControlledMintNegative.lean" \
  "${OLEAN_DIR}/ControlledMintNegative.olean"

manifest="${VERIFY_DIR}/generated/run-manifest.json"
manifest_tmp="${VERIFY_DIR}/generated/run-manifest.tmp.json"
jq \
  --arg leanVersion "4.24.0" \
  --arg z3Version "4.15.2" \
  --argjson offline "${OFFLINE}" \
  --slurpfile profile "${VERIFY_DIR}/config/verification-profile.json" \
  --slurpfile properties "${VERIFY_DIR}/config/properties.json" \
  '. + {
    milestone: "B",
    result: "ESTABLISHED",
    leanVersion: $leanVersion,
    z3Version: $z3Version,
    offlineEvidence: $offline,
    verificationProfile: $profile[0],
    properties: $properties[0].properties,
    legacyFindings: [
      {
        id: "typed-multisig.requires-both-signers",
        result: "COULD-NOT-EVALUATE",
        gating: false,
        reason: "Milestone A recursive-list claim remains solver-undetermined"
      },
      {
        id: "smoke.strict-schema-shape",
        result: "ESTABLISHED",
        gating: true,
        reason: "ADR-015 strict-data-v1 establishes the exact constructor tag and arity"
      }
    ]
  }' "${manifest}" > "${manifest_tmp}"
mv "${manifest_tmp}" "${manifest}"

echo "ESTABLISHED: Milestone B positive properties and negative controls"
echo "Verification manifest: ${manifest}"
