#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4K_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4K_DIR}/../.." && pwd)"
BACKEND="${E4K_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4K_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"
PROJECT="${E4K_DIR}/fixtures/governance-data"
BUILD_DIR="${PROJECT}/build/dsl"
mkdir -p "${BUILD_DIR}/src/evidence" "${BUILD_DIR}/classes"

prepare() {
  local validator="$1" model="$2" spec="$3"
  local source="${BUILD_DIR}/src/evidence/${model}.java"
  rm -f "${source}"
  "${JULC[@]}" verify dsl-init "${PROJECT}" --validator "${validator}" \
    --purpose spending --package evidence --class "${model}" \
    --out "${source}"
  javac -cp "${JULC_JAR}" -d "${BUILD_DIR}/classes" \
    "${source}" "${E4K_DIR}/${spec}.java"
}

prepare AuthorizedGovernance AuthorizedGovernanceModel AuthorizedGovernanceSpec
prepare VulnerableGovernance VulnerableGovernanceModel VulnerableGovernanceSpec
prepare VacuousGovernance VacuousGovernanceModel VacuousGovernanceSpec

run_property() {
  local validator="$1" spec="$2" output="$3" expected_exit="$4" expected="$5"
  set +e
  "${JULC[@]}" verify dsl "${PROJECT}" --validator "${validator}" \
    --purpose spending --spec-class "evidence.${spec}" \
    --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
    --source "${E4K_DIR}/${spec}.java" --backend "${BACKEND}" --fuel 5000 \
    --recursive-depth 4 --out-dir "${E4K_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected}\"" \
    "${E4K_DIR}/generated/${output}/verification-result.json"
}

if [[ "${BACKEND}" == "local" ]]; then
  run_property AuthorizedGovernance AuthorizedGovernanceSpec authorized 0 SMT-VALID
  run_property VulnerableGovernance VulnerableGovernanceSpec vulnerable 3 REFUTED
  run_property VacuousGovernance VacuousGovernanceSpec vacuous 2 COULD-NOT-EVALUATE
  grep -q '"schemaVersion" : 1' \
    "${E4K_DIR}/generated/authorized/verification-manifest.json"
  grep -q 'isKnownProposal' \
    "${E4K_DIR}/generated/authorized/GovernanceSemanticsTests.lean"
  grep -q 'decodeVoter (IsData.toData committeeVoter)' \
    "${E4K_DIR}/generated/authorized/GovernanceSemanticsTests.lean"
  grep -q 'decodeAction (IsData.toData action0)' \
    "${E4K_DIR}/generated/authorized/GovernanceSemanticsTests.lean"
  grep -q 'julcMapLookupAll innerVotes actionId' \
    "${E4K_DIR}/generated/authorized/GovernanceSemanticsTests.lean"
  grep -q '"strictGovernanceActionDecoding" : true' \
    "${E4K_DIR}/generated/vulnerable/verification-result.json"
  grep -q '"fullProposalEquality" : false' \
    "${E4K_DIR}/generated/vulnerable/verification-result.json"
  grep -q 'LedgerCorollary.lean' \
    "${E4K_DIR}/generated/authorized/scripts/verify-governance_minimum_deposit.sh"
else
  run_property AuthorizedGovernance AuthorizedGovernanceSpec authorized-docker 0 SMT-VALID
fi

echo "Milestone E.4k controls passed with ${BACKEND}."
