#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C5_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${C5_DIR}/../.." && pwd)"

cd "${REPO_DIR}"
./gradlew :julc-verification:test :julc-cli:test \
  --tests '*VerificationProjectGeneratorTest' \
  --tests '*VerificationRunnerTest' \
  :julc-cli:shadowJar

JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")

run_fixture() {
  local fixture="$1"
  local validator="$2"
  local expected_exit="$3"
  local expected_outcome="$4"
  local workspace="${C5_DIR}/generated/${fixture}"

  set +e
  "${JULC[@]}" verify "${C5_DIR}/fixtures/${fixture}" \
    --validator "${validator}" --backend local --out-dir "${workspace}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${fixture}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected_outcome}\"" \
    "${workspace}/verification-result.json"
}

run_fixture authorized AuthorizedStateValidator 0 SMT-VALID
run_fixture vulnerable VulnerableStateValidator 3 REFUTED
run_fixture vacuous VacuousStateValidator 2 COULD-NOT-EVALUATE

grep -q '"reason" : "property-vacuous"' \
  "${C5_DIR}/generated/vacuous/verification-result.json"
grep -q '"reason" : "not-evaluated-vacuous"' \
  "${C5_DIR}/generated/vacuous/verification-result.json"
grep -q '"status" : "SKIPPED"' \
  "${C5_DIR}/generated/vacuous/verification-result.json"
grep -q 'Counterexample:' \
  "${C5_DIR}/generated/vulnerable/verification-results/verify.log"
grep -q 'only executions that complete within the CEK `fuel`' \
  "${C5_DIR}/generated/authorized/README.md"

echo "Milestone C.5 annotation-to-proof controls passed."
