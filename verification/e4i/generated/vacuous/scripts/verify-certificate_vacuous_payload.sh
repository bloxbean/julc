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
    echo "COULD-NOT-EVALUATE: DSL property certificate.vacuous-payload missing ${tool}" >&2
    exit 2
  }
done
[[ "$(lean --version | head -n 1)" == *"4.24.0"* ]] || {
  echo "COULD-NOT-EVALUATE: DSL property certificate.vacuous-payload expected Lean 4.24.0" >&2
  exit 2
}
[[ "$(z3 --version)" == *"4.15.2"* ]] || {
  echo "COULD-NOT-EVALUATE: DSL property certificate.vacuous-payload expected Z3 4.15.2" >&2
  exit 2
}

if command -v sha256sum >/dev/null 2>&1; then
  actual_hash="$(tr -d '[:space:]' < artifacts/vacuous-certificate-payload.compiledCode.hex | xxd -r -p | sha256sum | awk '{print $1}')"
else
  actual_hash="$(tr -d '[:space:]' < artifacts/vacuous-certificate-payload.compiledCode.hex | xxd -r -p | shasum -a 256 | awk '{print $1}')"
fi
[[ "${actual_hash}" == "f9ebb737bd31c880afe96edfb43201bbc54ebd23e3e6c60b2dd5f84798cc5018" ]] || {
  echo "COULD-NOT-EVALUATE: DSL property certificate.vacuous-payload artifact hash mismatch" >&2
  exit 2
}

while IFS=' ' read -r package expected; do
  actual="$(git -C ".lake/packages/${package}" rev-parse HEAD)"
  [[ "${actual}" == "${expected}" ]] || {
    echo "COULD-NOT-EVALUATE: DSL property certificate.vacuous-payload ${package} revision mismatch" >&2
    exit 2
  }
done <<'PINS'
Blaster 083bae7971414d894b56b5bbf4108c63e17bc42a
PlutusCore 7cf5a78c54b9694ef093bf49edb5d3799b2a49c9
CardanoLedgerApi 5dab3c43f042b8735b6d067223baaa8d32ed28a1
PINS

# The authenticated non-vacuity step immediately before this step
# recompiles all support files and this obligation from current sources.

set +e
lake env lean -o .lake/build/lib/lean/VacuousCertificatePayload_certificate_vacuous_payloadProof.olean VacuousCertificatePayload_certificate_vacuous_payloadProof.lean
proof_status=$?
set -e
if [[ ${proof_status} -eq 0 ]]; then
  if ! lake env lean VacuousCertificatePayload_certificate_vacuous_payloadLedgerCorollary.lean; then
    echo "COULD-NOT-EVALUATE: ledger-domain kernel bridge failed" >&2
    exit 2
  fi
  echo "SMT-VALID: DSL property certificate.vacuous-payload established"
  exit 0
fi

if lake env lean VacuousCertificatePayload_certificate_vacuous_payloadCounterexample.lean; then
  echo "REFUTED: DSL property certificate.vacuous-payload counterexample found"
  exit 3
fi
echo "COULD-NOT-EVALUATE: DSL property certificate.vacuous-payload solver result was not classifiable" >&2
exit 2
