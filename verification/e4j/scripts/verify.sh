#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4J_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4J_DIR}/../.." && pwd)"
BACKEND="${E4J_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4J_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"
PROJECT="${E4J_DIR}/fixtures/value-algebra"
BUILD_DIR="${PROJECT}/build/dsl"
mkdir -p "${BUILD_DIR}/src/evidence" "${BUILD_DIR}/classes"

prepare() {
  local validator="$1" model="$2" spec="$3"
  local source="${BUILD_DIR}/src/evidence/${model}.java"
  rm -f "${source}"
  "${JULC[@]}" verify dsl-init "${PROJECT}" --validator "${validator}" \
    --purpose spending --schema-version 8 --package evidence --class "${model}" \
    --out "${source}"
  javac -cp "${JULC_JAR}" -d "${BUILD_DIR}/classes" \
    "${source}" "${E4J_DIR}/${spec}.java"
}

prepare AuthorizedValue AuthorizedValueModel AuthorizedValueSpec
prepare AuthorizedValue AuthorizedValueModel StrictValueCalibrationSpec
prepare AuthorizedValue AuthorizedValueModel ExtensionalValueCalibrationSpec
prepare VulnerableValue VulnerableValueModel VulnerableValueSpec
prepare VacuousValue VacuousValueModel VacuousValueSpec

run_property() {
  local validator="$1" spec="$2" output="$3" expected_exit="$4" expected="$5"
  set +e
  "${JULC[@]}" verify dsl "${PROJECT}" --validator "${validator}" \
    --purpose spending --spec-class "evidence.${spec}" \
    --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
    --source "${E4J_DIR}/${spec}.java" --backend "${BACKEND}" --fuel 5000 \
    --recursive-depth 4 --out-dir "${E4J_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected}\"" \
    "${E4J_DIR}/generated/${output}/verification-result.json"
}

if [[ "${BACKEND}" == "local" ]]; then
  run_property AuthorizedValue AuthorizedValueSpec authorized 0 SMT-VALID
  run_property VulnerableValue VulnerableValueSpec vulnerable 3 REFUTED
  run_property VacuousValue VacuousValueSpec vacuous 2 COULD-NOT-EVALUATE
  run_calibration() {
    local spec="$1" output="$2"
    set +e
    "${JULC[@]}" verify dsl "${PROJECT}" --validator AuthorizedValue \
      --purpose spending --spec-class "evidence.${spec}" \
      --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
      --source "${E4J_DIR}/${spec}.java" --backend local --fuel 5000 \
      --recursive-depth 4 --out-dir "${E4J_DIR}/generated/${output}" --force
    calibration_exit=$?
    set -e
    [[ "${calibration_exit}" -eq 0 || "${calibration_exit}" -eq 2 \
        || "${calibration_exit}" -eq 3 ]] || exit "${calibration_exit}"
  }
  if [[ "${E4J_CALIBRATE:-0}" == "strict" \
      || "${E4J_CALIBRATE:-0}" == "all" ]]; then
    run_calibration StrictValueCalibrationSpec calibration-strict
  fi
  if [[ "${E4J_CALIBRATE:-0}" == "extensional" \
      || "${E4J_CALIBRATE:-0}" == "all" ]]; then
    run_calibration ExtensionalValueCalibrationSpec calibration-extensional
  fi
  grep -q '"schemaVersion" : 8' \
    "${E4J_DIR}/generated/authorized/verification-manifest.json"
  grep -q 'julcValueQuantitySumStrict' \
    "${E4J_DIR}/generated/authorized/ValueAlgebraSemanticsTests.lean"
  grep -q 'malformedQuantity' \
    "${E4J_DIR}/generated/authorized/ValueAlgebraSemanticsTests.lean"
  grep -q 'LedgerCorollary.lean' \
    "${E4J_DIR}/generated/authorized/scripts/verify-value_first_match_payment.sh"
else
  run_property AuthorizedValue AuthorizedValueSpec authorized-docker 0 SMT-VALID
fi

echo "Milestone E.4j controls passed with ${BACKEND}."
