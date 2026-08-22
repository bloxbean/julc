#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
if [[ -d "${HOME}/.elan/bin" ]]; then
  export PATH="${HOME}/.elan/bin:${PATH}"
fi
cd "${VERIFY_DIR}"

for tool in lake lean z3 git xxd; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "COULD-NOT-EVALUATE: non-vacuity missing ${tool}" >&2
    exit 2
  }
done
[[ "$(lean --version | head -n 1)" == *"4.24.0"* ]] || {
  echo "COULD-NOT-EVALUATE: non-vacuity expected Lean 4.24.0" >&2
  exit 2
}
[[ "$(z3 --version)" == *"4.15.2"* ]] || {
  echo "COULD-NOT-EVALUATE: non-vacuity expected Z3 4.15.2" >&2
  exit 2
}

if command -v sha256sum >/dev/null 2>&1; then
  actual_hash="$(tr -d '[:space:]' < artifacts/vulnerable-governance.compiledCode.hex | xxd -r -p | sha256sum | awk '{print $1}')"
else
  actual_hash="$(tr -d '[:space:]' < artifacts/vulnerable-governance.compiledCode.hex | xxd -r -p | shasum -a 256 | awk '{print $1}')"
fi
[[ "${actual_hash}" == "45f40ed3a4f61fe795adc652271e6bfd4bb730d2db2baca1fc6a96f76ed3acc2" ]] || {
  echo "COULD-NOT-EVALUATE: non-vacuity artifact hash mismatch" >&2
  exit 2
}

while IFS=' ' read -r package expected; do
  actual="$(git -C ".lake/packages/${package}" rev-parse HEAD)"
  [[ "${actual}" == "${expected}" ]] || {
    echo "COULD-NOT-EVALUATE: non-vacuity ${package} revision mismatch" >&2
    exit 2
  }
done <<'PINS'
Blaster 083bae7971414d894b56b5bbf4108c63e17bc42a
PlutusCore 7cf5a78c54b9694ef093bf49edb5d3799b2a49c9
CardanoLedgerApi 5dab3c43f042b8735b6d067223baaa8d32ed28a1
PINS

mkdir -p .lake/build/lib/lean
lake env lean -o .lake/build/lib/lean/GeneratedSchemas.olean GeneratedSchemas.lean
lake env lean -o .lake/build/lib/lean/PropertyTemplates.olean PropertyTemplates.lean
lake env lean -o .lake/build/lib/lean/CheckedExecution.olean CheckedExecution.lean
lake env lean -o .lake/build/lib/lean/SecurityProperty.olean SecurityProperty.lean
lake env lean -o .lake/build/lib/lean/VulnerableGovernance_governance_hard_fork_v11Obligation.olean VulnerableGovernance_governance_hard_fork_v11Obligation.lean

set +e
lake env lean VulnerableGovernance_governance_hard_fork_v11NonVacuityCounterexample.lean
witness_status=$?
set -e
if [[ ${witness_status} -eq 0 ]]; then
  echo "NON-VACUOUS: successful input witness exists"
  exit 0
fi

if lake env lean VulnerableGovernance_governance_hard_fork_v11VacuityProof.lean; then
  echo "VACUOUS: validator has no successful input"
  exit 4
fi
echo "COULD-NOT-EVALUATE: non-vacuity solver result was not classifiable" >&2
exit 2
