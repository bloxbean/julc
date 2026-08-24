#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4A_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4A_DIR}/../.." && pwd)"
cd "${REPO_DIR}"

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")

run_fixture() {
  local fixture="$1" expected_exit="$2" expected="$3" fuel="$4"
  local project="${E4A_DIR}/fixtures/${fixture}"
  local build_dir="${project}/build/dsl"
  local model="${build_dir}/src/evidence/TokenPolicyModel.java"
  local classes="${build_dir}/classes"
  local workspace="${E4A_DIR}/generated/${fixture}"

  mkdir -p "$(dirname "${model}")" "${classes}" "${workspace}"
  rm -f "${model}"
  "${JULC[@]}" verify dsl-init "${project}" --validator OneShotPolicy \
    --purpose minting --package evidence --class TokenPolicyModel --out "${model}"
  javac -cp "${REPO_DIR}/julc-cli/build/libs/julc.jar" -d "${classes}" \
    "${model}" "${E4A_DIR}/OneShotMintSpec.java"

  if [[ "${fixture}" != "authorized" ]]; then
    [[ -d "${workspace}/.lake" ]] || cp -R "${E4A_DIR}/generated/authorized/.lake" "${workspace}/"
    [[ -d "${workspace}/.julc" ]] || cp -R "${E4A_DIR}/generated/authorized/.julc" "${workspace}/"
  fi
  set +e
  "${JULC[@]}" verify dsl "${project}" --validator OneShotPolicy \
    --purpose minting --spec-class evidence.OneShotMintSpec \
    --spec-classpath "${classes}" --source OneShotMintSpec.java \
    --backend local --fuel "${fuel}" --out-dir "${workspace}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${fixture}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected}\"" "${workspace}/verification-result.json"
}

run_fixture authorized 0 SMT-VALID 5000

bridge_negative="${E4A_DIR}/generated/authorized/BridgeNegativeControl.lean"
cp "${E4A_DIR}/BridgeNegativeControl.lean.expected-failure" "${bridge_negative}"
if (cd "${E4A_DIR}/generated/authorized" \
    && lake env lean "${bridge_negative}") \
    >"${E4A_DIR}/generated/authorized/verification-results/bridge-negative.log" 2>&1; then
  rm -f "${bridge_negative}"
  echo "strengthened ledger-domain bridge unexpectedly compiled" >&2
  exit 1
fi
rm -f "${bridge_negative}"

run_fixture missing-anchor 3 REFUTED 5000
run_fixture missing-authority 3 REFUTED 5000
run_fixture wrong-asset 3 REFUTED 5000
run_fixture vacuous 2 COULD-NOT-EVALUATE 3000

grep -q 'validMintingContext/v3-pinned' \
  "${E4A_DIR}/generated/authorized/verification-result.json"
grep -q 'BLASTER_VALID_MINTING_SUPERSET' \
  "${E4A_DIR}/generated/missing-anchor/verification-result.json"
grep -q '"reason" : "not-evaluated-vacuous"' \
  "${E4A_DIR}/generated/vacuous/verification-result.json"

echo "Milestone E.4a typed minting DSL controls passed."
