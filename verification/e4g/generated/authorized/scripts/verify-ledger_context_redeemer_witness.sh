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
    echo "COULD-NOT-EVALUATE: DSL property ledger-context.redeemer-witness missing ${tool}" >&2
    exit 2
  }
done
[[ "$(lean --version | head -n 1)" == *"4.24.0"* ]] || {
  echo "COULD-NOT-EVALUATE: DSL property ledger-context.redeemer-witness expected Lean 4.24.0" >&2
  exit 2
}
[[ "$(z3 --version)" == *"4.15.2"* ]] || {
  echo "COULD-NOT-EVALUATE: DSL property ledger-context.redeemer-witness expected Z3 4.15.2" >&2
  exit 2
}

if command -v sha256sum >/dev/null 2>&1; then
  actual_hash="$(tr -d '[:space:]' < artifacts/authorized-ledger-context-gate.compiledCode.hex | xxd -r -p | sha256sum | awk '{print $1}')"
else
  actual_hash="$(tr -d '[:space:]' < artifacts/authorized-ledger-context-gate.compiledCode.hex | xxd -r -p | shasum -a 256 | awk '{print $1}')"
fi
[[ "${actual_hash}" == "dba4f675fbea805a059fe169193d86d2549659fe6672bf9e8f1617d42aece0fe" ]] || {
  echo "COULD-NOT-EVALUATE: DSL property ledger-context.redeemer-witness artifact hash mismatch" >&2
  exit 2
}

while IFS=' ' read -r package expected; do
  actual="$(git -C ".lake/packages/${package}" rev-parse HEAD)"
  [[ "${actual}" == "${expected}" ]] || {
    echo "COULD-NOT-EVALUATE: DSL property ledger-context.redeemer-witness ${package} revision mismatch" >&2
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
lake env lean -o .lake/build/lib/lean/AuthorizedLedgerContextGate_ledger_context_redeemer_witnessProof.olean AuthorizedLedgerContextGate_ledger_context_redeemer_witnessProof.lean
proof_status=$?
set -e
if [[ ${proof_status} -eq 0 ]]; then
  if ! lake env lean AuthorizedLedgerContextGate_ledger_context_redeemer_witnessLedgerCorollary.lean; then
    echo "COULD-NOT-EVALUATE: ledger-domain kernel bridge failed" >&2
    exit 2
  fi
  echo "SMT-VALID: DSL property ledger-context.redeemer-witness established"
  exit 0
fi

if lake env lean AuthorizedLedgerContextGate_ledger_context_redeemer_witnessCounterexample.lean; then
  echo "REFUTED: DSL property ledger-context.redeemer-witness counterexample found"
  exit 3
fi
echo "COULD-NOT-EVALUATE: DSL property ledger-context.redeemer-witness solver result was not classifiable" >&2
exit 2
