#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C7_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${C7_DIR}/../.." && pwd)"
cd "${REPO_DIR}"

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")

run_fixture() {
  local fixture="$1" validator="$2" fuel="$3" expected_exit="$4" expected="$5"
  local workspace="${C7_DIR}/generated/${fixture}"
  mkdir -p "${workspace}"
  if [[ "${fixture}" != "mint" ]]; then
    [[ -d "${workspace}/.lake" ]] || cp -R "${C7_DIR}/generated/mint/.lake" "${workspace}/"
    [[ -d "${workspace}/.julc" ]] || cp -R "${C7_DIR}/generated/mint/.julc" "${workspace}/"
  fi
  set +e
  "${JULC[@]}" verify "${C7_DIR}/fixtures/${fixture}" --validator "${validator}" \
    --backend local --fuel "${fuel}" --out-dir "${workspace}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]]
  grep -q "\"outcome\" : \"${expected}\"" "${workspace}/verification-result.json"
}

run_fixture mint ControlledMintPolicy 5000 0 SMT-VALID
run_fixture burn ControlledBurnPolicy 5000 0 SMT-VALID
run_fixture missing-authority MissingAuthorityPolicy 1000 3 REFUTED
run_fixture wrong-asset WrongAssetPolicy 1000 3 REFUTED
run_fixture wrong-quantity WrongQuantityPolicy 1000 3 REFUTED
run_fixture vacuous VacuousMintPolicy 1000 2 COULD-NOT-EVALUATE

grep -q '"otherPoliciesPermitted" : true' \
  "${C7_DIR}/generated/mint/verification-result.json"
grep -q '"reason" : "not-evaluated-vacuous"' \
  "${C7_DIR}/generated/vacuous/verification-result.json"
echo "Milestone C.7 controlled-mint controls passed."
