#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4C_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4C_DIR}/../.." && pwd)"
BACKEND="${E4C_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4C_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"
PROJECT="${E4C_DIR}/fixtures/rewarding"
BUILD_DIR="${PROJECT}/build/dsl"
MODEL="${BUILD_DIR}/src/evidence/RewardingModel.java"

mkdir -p "$(dirname "${MODEL}")" "${BUILD_DIR}/classes"
rm -f "${MODEL}"
"${JULC[@]}" verify dsl-init "${PROJECT}" --validator AuthorizedRewards \
  --purpose rewarding --package evidence --class RewardingModel --out "${MODEL}"
javac -cp "${JULC_JAR}" -d "${BUILD_DIR}/classes" \
  "${MODEL}" "${E4C_DIR}/RewardingSpec.java"

run_property() {
  local validator="$1" output="$2" expected_exit="$3" expected_outcome="$4"
  set +e
  "${JULC[@]}" verify dsl "${PROJECT}" --validator "${validator}" \
    --purpose rewarding --spec-class evidence.RewardingSpec \
    --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
    --source "${E4C_DIR}/RewardingSpec.java" --backend "${BACKEND}" \
    --fuel 5000 --out-dir "${E4C_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected_outcome}\"" \
    "${E4C_DIR}/generated/${output}/verification-result.json"
}

if [[ "${BACKEND}" == "local" ]]; then
  run_property AuthorizedRewards authorized 0 SMT-VALID
  run_property MissingAuthorityRewards missing-authority 3 REFUTED
  run_property UnboundedRewards unbounded 3 REFUTED
  run_property VacuousRewards vacuous 2 COULD-NOT-EVALUATE

  grep -q 'validRewardingContext_implies_blasterDomain' \
    "${E4C_DIR}/generated/authorized/LedgerDomainEquivalence.lean"
  grep -q 'RewardingSemanticsTests' \
    "${E4C_DIR}/generated/authorized/lakefile.lean"
  grep -q 'AuthorizedRewards_reward_authorized_minimumLedgerCorollary.lean' \
    "${E4C_DIR}/generated/authorized/scripts/verify-reward_authorized_minimum.sh"
  grep -q '"counterexampleDomain" : "BLASTER_VALID_REWARDING_SUPERSET"' \
    "${E4C_DIR}/generated/missing-authority/verification-result.json"
  grep -q '"reason" : "not-evaluated-vacuous"' \
    "${E4C_DIR}/generated/vacuous/verification-result.json"
else
  # The full negative/vacuity matrix is exercised by the local evidence run.
  # Keep the positive Docker acceptance certificate separate so both
  # backends' bound manifests and results remain reviewable at once.
  run_property AuthorizedRewards authorized-docker 0 SMT-VALID
  grep -q 'AuthorizedRewards_reward_authorized_minimumLedgerCorollary.lean' \
    "${E4C_DIR}/generated/authorized-docker/scripts/verify-reward_authorized_minimum.sh"
fi

echo "Milestone E.4c rewarding DSL controls passed with ${BACKEND}."
