#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${VERIFY_DIR}/../.." && pwd)"
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"

BLASTER_REV="083bae7971414d894b56b5bbf4108c63e17bc42a"
PLUTUS_CORE_REV="7cf5a78c54b9694ef093bf49edb5d3799b2a49c9"
LEDGER_API_REV="5dab3c43f042b8735b6d067223baaa8d32ed28a1"

if [[ -d "${HOME}/.elan/bin" ]]; then
  export PATH="${HOME}/.elan/bin:${PATH}"
fi
if [[ -x "${REPO_DIR}/verification/blaster/.tools/z3-4.15.2/bin/z3" ]]; then
  export PATH="${REPO_DIR}/verification/blaster/.tools/z3-4.15.2/bin:${PATH}"
fi

for tool in java lake lean git rg jq z3 xxd; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "COULD-NOT-EVALUATE: missing ${tool}" >&2
    exit 2
  }
done

[[ "$(z3 --version)" == *"4.15.2"* ]] || {
  echo "COULD-NOT-EVALUATE: expected Z3 4.15.2" >&2
  exit 2
}

cd "${REPO_DIR}"
./gradlew :julc-cli:shadowJar -PskipSigning=true

verify_workspace() {
  local fixture="$1"
  local validator="$2"
  local purpose="$3"
  local output="${VERIFY_DIR}/generated/${purpose}"

  java -jar "${JULC_JAR}" build "${VERIFY_DIR}/fixtures/${fixture}"
  java -jar "${JULC_JAR}" verify init "${VERIFY_DIR}/fixtures/${fixture}" \
    --validator "${validator}" \
    --purpose "${purpose}" \
    --out-dir "${output}" \
    --force

  if rg -n '(^|[[:space:]])(sorry|admit)([[:space:]]|$)' \
      --glob '*.lean' "${output}" "${VERIFY_DIR}/CodecTests.lean"; then
    echo "COULD-NOT-EVALUATE: C.2 workspace contains sorry or admit" >&2
    exit 2
  fi

  cd "${output}"
  lake update

  [[ "$(git -C .lake/packages/Blaster rev-parse HEAD)" == "${BLASTER_REV}" ]]
  [[ "$(git -C .lake/packages/PlutusCore rev-parse HEAD)" == "${PLUTUS_CORE_REV}" ]]
  [[ "$(git -C .lake/packages/CardanoLedgerApi rev-parse HEAD)" == "${LEDGER_API_REV}" ]]

  set +e
  local generated_result
  generated_result="$(scripts/verify.sh 2>&1)"
  local generated_status=$?
  set -e
  printf '%s\n' "${generated_result}"
  if [[ ${generated_status} -ne 2 ]] ||
      [[ "${generated_result}" != *"workspace compiles; specialize securityProperty"* ]]; then
    echo "COULD-NOT-EVALUATE: generated verification driver did not reach its safe initial result" >&2
    exit 2
  fi

  if [[ "${purpose}" == "spending" ]]; then
    lake env lean --root="${REPO_DIR}" "${VERIFY_DIR}/CodecTests.lean"
  fi
}

verify_workspace spending VerificationContainersSpending spending
verify_workspace minting VerificationContainersMinting minting

echo "ESTABLISHED: Milestone C.2 generated container codecs and workspaces compile"
