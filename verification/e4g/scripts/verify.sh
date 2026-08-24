#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4G_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4G_DIR}/../.." && pwd)"
BACKEND="${E4G_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4G_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"
PROJECT="${E4G_DIR}/fixtures/contracts"
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
    "${source}" "${E4G_DIR}/${spec}.java"
}

prepare AuthorizedLedgerContextGate AuthorizedLedgerContextModel AuthorizedLedgerContextSpec
prepare VulnerableLedgerContextGate VulnerableLedgerContextModel VulnerableLedgerContextSpec
prepare VacuousLedgerContextGate VacuousLedgerContextModel VacuousLedgerContextSpec

run_property() {
  local validator="$1" spec="$2" output="$3" expected_exit="$4" expected="$5"
  set +e
  "${JULC[@]}" verify dsl "${PROJECT}" --validator "${validator}" \
    --purpose spending --spec-class "evidence.${spec}" \
    --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
    --source "${E4G_DIR}/${spec}.java" --backend "${BACKEND}" --fuel 5000 \
    --recursive-depth 8 --out-dir "${E4G_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected}\"" \
    "${E4G_DIR}/generated/${output}/verification-result.json"
}

if [[ "${BACKEND}" == "local" ]]; then
  run_property AuthorizedLedgerContextGate AuthorizedLedgerContextSpec authorized 0 SMT-VALID
  run_property VulnerableLedgerContextGate VulnerableLedgerContextSpec vulnerable 3 REFUTED
  run_property VacuousLedgerContextGate VacuousLedgerContextSpec vacuous 2 COULD-NOT-EVALUATE
  grep -q '"schemaVersion" : 1' \
    "${E4G_DIR}/generated/authorized/verification-manifest.json"
  grep -q 'julcListAt' "${E4G_DIR}/generated/authorized/SecurityProperty.lean"
  grep -q 'txInfoReferenceInputs' \
    "${E4G_DIR}/generated/authorized/SecurityProperty.lean"
  grep -q 'toScriptPurpose' \
    "${E4G_DIR}/generated/authorized/SecurityProperty.lean"
  grep -q 'dslGuarantee_ledger_context_current_purpose' \
    "${E4G_DIR}/generated/authorized/SecurityProperty.lean"
  grep -q 'julcMapLookupFirst' \
    "${E4G_DIR}/generated/authorized/SecurityProperty.lean"
  grep -q 'julcMapContainsKey redeemerEntries votingPurpose' \
    "${E4G_DIR}/generated/authorized/LedgerContextSemanticsTests.lean"
else
  run_property AuthorizedLedgerContextGate AuthorizedLedgerContextSpec authorized-docker 0 SMT-VALID
fi

echo "Milestone E.4g controls passed with ${BACKEND}."
