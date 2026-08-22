#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
if [[ -d "${HOME}/.elan/bin" ]]; then export PATH="${HOME}/.elan/bin:${PATH}"; fi
cd "${VERIFY_DIR}"
for tool in lake lean z3 git xxd; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "JULC-STATE-MACHINE-V1 COULD-NOT-EVALUATE missing=${tool}" >&2
    exit 2
  }
done
[[ "$(lean --version | head -n 1)" == *"4.24.0"* ]] || exit 2
[[ "$(z3 --version)" == *"4.15.2"* ]] || exit 2
if command -v sha256sum >/dev/null 2>&1; then
  actual_hash="$(tr -d '[:space:]' < artifacts/exact-counter.compiledCode.hex | xxd -r -p | sha256sum | awk '{print $1}')"
else
  actual_hash="$(tr -d '[:space:]' < artifacts/exact-counter.compiledCode.hex | xxd -r -p | shasum -a 256 | awk '{print $1}')"
fi
[[ "${actual_hash}" == "4304220724d7de16290d403a39cb7fe151a196934545fca498cc02bcba9c647b" ]] || {
  echo "JULC-STATE-MACHINE-V1 COULD-NOT-EVALUATE artifact-hash-mismatch" >&2
  exit 2
}
while IFS=' ' read -r package expected; do
  actual="$(git -C ".lake/packages/${package}" rev-parse HEAD)"
  [[ "${actual}" == "${expected}" ]] || exit 2
done <<'PINS'
Blaster 083bae7971414d894b56b5bbf4108c63e17bc42a
PlutusCore 7cf5a78c54b9694ef093bf49edb5d3799b2a49c9
CardanoLedgerApi 5dab3c43f042b8735b6d067223baaa8d32ed28a1
PINS
raw="$(mktemp "${TMPDIR:-/tmp}/julc-state-machine.XXXXXX")"
trap 'rm -f "${raw}"' EXIT
set +e
lake env lean StateMachineReachability.lean >"${raw}" 2>&1
lean_status=$?
set -e
cat "${raw}"
if [[ ${lean_status} -ne 0 ]]; then
  echo "JULC-STATE-MACHINE-V1 COULD-NOT-EVALUATE lean-exit=${lean_status}" >&2
  exit 2
fi
if grep -Fq "Counterexample detected at Depth 1" "${raw}"; then
  echo "JULC-STATE-MACHINE-V1 REACHABLE depth=1"
  exit 0
fi
echo "JULC-STATE-MACHINE-V1 UNREACHABLE depth=1" >&2
exit 2

