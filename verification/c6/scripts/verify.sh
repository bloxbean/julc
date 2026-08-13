#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C6_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${C6_DIR}/../.." && pwd)"
cd "${REPO_DIR}"

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")

run_fixture() {
  local fixture="$1" validator="$2" fuel="$3" expected_exit="$4" expected="$5"
  local workspace="${C6_DIR}/generated/${fixture}"
  mkdir -p "${workspace}"
  if [[ "${fixture}" != "authorized" ]]; then
    [[ -d "${workspace}/.lake" ]] || cp -R "${C6_DIR}/generated/authorized/.lake" "${workspace}/"
    [[ -d "${workspace}/.julc" ]] || cp -R "${C6_DIR}/generated/authorized/.julc" "${workspace}/"
  fi
  set +e
  "${JULC[@]}" verify "${C6_DIR}/fixtures/${fixture}" --validator "${validator}" \
    --backend local --fuel "${fuel}" --out-dir "${workspace}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]]
  grep -q "\"outcome\" : \"${expected}\"" "${workspace}/verification-result.json"
}

run_fixture authorized AuthorizedStateMachine 3000 0 SMT-VALID
run_fixture missing-signer MissingSignerStateMachine 1000 3 REFUTED
run_fixture decreasing DecreasingStateMachine 1000 3 REFUTED
run_fixture value-leak ValueLeakStateMachine 1000 3 REFUTED
run_fixture vacuous VacuousStateMachine 1000 2 COULD-NOT-EVALUATE

grep -q '"reason" : "not-evaluated-vacuous"' \
  "${C6_DIR}/generated/vacuous/verification-result.json"
grep -q '"globalMultiInputLinkageModeled" : false' \
  "${C6_DIR}/generated/authorized/verification-result.json"
echo "Milestone C.6 stateful-spending controls passed."
