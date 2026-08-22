#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4H_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4H_DIR}/../.." && pwd)"
BACKEND="${E4H_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4H_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"
PROJECT="${E4H_DIR}/fixtures/contracts"
BUILD_DIR="${PROJECT}/build/dsl"
mkdir -p "${BUILD_DIR}/src/evidence" "${BUILD_DIR}/classes"

prepare() {
  local validator="$1" model="$2" spec="$3"
  local source="${BUILD_DIR}/src/evidence/${model}.java"
  rm -f "${source}"
  "${JULC[@]}" verify dsl-init "${PROJECT}" --validator "${validator}" \
    --purpose spending --schema-version 6 --package evidence --class "${model}" \
    --out "${source}"
  javac -cp "${JULC_JAR}" -d "${BUILD_DIR}/classes" \
    "${source}" "${E4H_DIR}/${spec}.java"
}

prepare AuthorizedThresholdGate AuthorizedThresholdModel AuthorizedThresholdSpec
prepare VulnerableThresholdGate VulnerableThresholdModel VulnerableThresholdSpec
prepare VacuousThresholdGate VacuousThresholdModel VacuousThresholdSpec

run_property() {
  local validator="$1" spec="$2" output="$3" expected_exit="$4" expected="$5"
  set +e
  "${JULC[@]}" verify dsl "${PROJECT}" --validator "${validator}" \
    --purpose spending --spec-class "evidence.${spec}" \
    --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
    --source "${E4H_DIR}/${spec}.java" --backend "${BACKEND}" --fuel 1700 \
    --recursive-depth 4 --out-dir "${E4H_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected}\"" \
    "${E4H_DIR}/generated/${output}/verification-result.json"
}

if [[ "${BACKEND}" == "local" ]]; then
  run_property AuthorizedThresholdGate AuthorizedThresholdSpec authorized 0 SMT-VALID
  run_property VulnerableThresholdGate VulnerableThresholdSpec vulnerable 3 REFUTED
  run_property VacuousThresholdGate VacuousThresholdSpec vacuous 2 COULD-NOT-EVALUATE
  grep -q '"schemaVersion" : 6' \
    "${E4H_DIR}/generated/authorized/verification-manifest.json"
  grep -q 'julcExactlySigned 2' \
    "${E4H_DIR}/generated/authorized/SecurityProperty.lean"
  grep -q 'julcNoUnexpectedSigners' \
    "${E4H_DIR}/generated/authorized/AuthorizationSemanticsTests.lean"
  grep -q 'julcExactlySigned 2 \[keyA, keyA, keyB\]' \
    "${E4H_DIR}/generated/authorized/AuthorizationSemanticsTests.lean"
else
  run_property AuthorizedThresholdGate AuthorizedThresholdSpec authorized-docker 0 SMT-VALID
fi

echo "Milestone E.4h controls passed with ${BACKEND}."
