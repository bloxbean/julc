#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4L_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4L_DIR}/../.." && pwd)"
BACKEND="${E4L_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4L_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"
PROJECT="${E4L_DIR}/fixtures/reviewed-adapters"
BUILD_DIR="${PROJECT}/build/dsl"
mkdir -p "${BUILD_DIR}/src/evidence" "${BUILD_DIR}/classes"

prepare() {
  local validator="$1" model="$2" spec="$3"
  local source="${BUILD_DIR}/src/evidence/${model}.java"
  rm -f "${source}"
  "${JULC[@]}" verify dsl-init "${PROJECT}" --validator "${validator}" \
    --purpose spending --schema-version 10 --package evidence --class "${model}" \
    --out "${source}"
  javac -cp "${JULC_JAR}" -d "${BUILD_DIR}/classes" \
    "${source}" "${E4L_DIR}/${spec}.java"
}

prepare AuthorizedReviewedAdapters AuthorizedReviewedAdaptersModel \
  AuthorizedReviewedAdaptersSpec
javac -cp "${JULC_JAR}:${BUILD_DIR}/classes" -d "${BUILD_DIR}/classes" \
  "${E4L_DIR}/TreasuryCalibrationSpec.java"
prepare VulnerableReviewedAdapters VulnerableReviewedAdaptersModel \
  VulnerableReviewedAdaptersSpec
prepare VacuousReviewedAdapters VacuousReviewedAdaptersModel \
  VacuousReviewedAdaptersSpec

run_property() {
  local validator="$1" spec="$2" output="$3" expected_exit="$4" expected="$5"
  set +e
  "${JULC[@]}" verify dsl "${PROJECT}" --validator "${validator}" \
    --purpose spending --spec-class "evidence.${spec}" \
    --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
    --source "${E4L_DIR}/${spec}.java" --backend "${BACKEND}" --fuel 5000 \
    --recursive-depth 4 --out-dir "${E4L_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected}\"" \
    "${E4L_DIR}/generated/${output}/verification-result.json"
}

if [[ "${BACKEND}" == "local" ]]; then
  run_property AuthorizedReviewedAdapters AuthorizedReviewedAdaptersSpec \
    authorized 0 SMT-VALID
  run_property AuthorizedReviewedAdapters TreasuryCalibrationSpec \
    treasury-calibration 3 REFUTED
  run_property VulnerableReviewedAdapters VulnerableReviewedAdaptersSpec \
    vulnerable 3 REFUTED
  run_property VacuousReviewedAdapters VacuousReviewedAdaptersSpec \
    vacuous 2 COULD-NOT-EVALUATE
  grep -q '"schemaVersion" : 10' \
    "${E4L_DIR}/generated/authorized/verification-manifest.json"
  grep -q 'noncanonicalInfinite' \
    "${E4L_DIR}/generated/authorized/ReviewedDataAdapterSemanticsTests.lean"
  grep -q 'validTreasuryAmount malformed' \
    "${E4L_DIR}/generated/authorized/ReviewedDataAdapterSemanticsTests.lean"
  grep -q 'LedgerCorollary.lean' \
    "${E4L_DIR}/generated/authorized/scripts/verify-reviewed_time_and_authority.sh"
else
  run_property AuthorizedReviewedAdapters AuthorizedReviewedAdaptersSpec \
    authorized-docker 0 SMT-VALID
fi

echo "Milestone E.4l controls passed with ${BACKEND}."
