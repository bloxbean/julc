#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4I_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4I_DIR}/../.." && pwd)"
BACKEND="${E4I_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4I_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"
PROJECT="${E4I_DIR}/fixtures/certificates"
BUILD_DIR="${PROJECT}/build/dsl"
mkdir -p "${BUILD_DIR}/src/evidence" "${BUILD_DIR}/classes"

prepare() {
  local validator="$1" model="$2" spec="$3"
  local source="${BUILD_DIR}/src/evidence/${model}.java"
  rm -f "${source}"
  "${JULC[@]}" verify dsl-init "${PROJECT}" --validator "${validator}" \
    --purpose certifying --schema-version 7 --package evidence --class "${model}" \
    --out "${source}"
  javac -cp "${JULC_JAR}" -d "${BUILD_DIR}/classes" \
    "${source}" "${E4I_DIR}/${spec}.java"
}

prepare AuthorizedDRepRegistration AuthorizedCertificatePayloadModel \
  AuthorizedCertificatePayloadSpec
prepare VulnerableDRepRegistration VulnerableCertificatePayloadModel \
  VulnerableCertificatePayloadSpec
prepare VacuousCertificatePayload VacuousCertificatePayloadModel \
  VacuousCertificatePayloadSpec

run_property() {
  local validator="$1" spec="$2" output="$3" expected_exit="$4" expected="$5"
  set +e
  "${JULC[@]}" verify dsl "${PROJECT}" --validator "${validator}" \
    --purpose certifying --spec-class "evidence.${spec}" \
    --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
    --source "${E4I_DIR}/${spec}.java" --backend "${BACKEND}" --fuel 5000 \
    --recursive-depth 4 --out-dir "${E4I_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected}\"" \
    "${E4I_DIR}/generated/${output}/verification-result.json"
}

if [[ "${BACKEND}" == "local" ]]; then
  run_property AuthorizedDRepRegistration AuthorizedCertificatePayloadSpec \
    authorized 0 SMT-VALID
  run_property VulnerableDRepRegistration VulnerableCertificatePayloadSpec \
    vulnerable 3 REFUTED
  run_property VacuousCertificatePayload VacuousCertificatePayloadSpec \
    vacuous 2 COULD-NOT-EVALUATE
  grep -q '"schemaVersion" : 7' \
    "${E4I_DIR}/generated/authorized/verification-manifest.json"
  grep -q 'TxCertRegDRep' \
    "${E4I_DIR}/generated/authorized/SecurityProperty.lean"
  grep -q 'encodedTagAndArity' \
    "${E4I_DIR}/generated/authorized/CertifyingSemanticsTests.lean"
  grep -q 'stakeVoteDelegatee' \
    "${E4I_DIR}/generated/authorized/CertifyingSemanticsTests.lean"
  grep -q 'LedgerCorollary.lean' \
    "${E4I_DIR}/generated/authorized/scripts/verify-certificate_registration_deposit.sh"
else
  run_property AuthorizedDRepRegistration AuthorizedCertificatePayloadSpec \
    authorized-docker 0 SMT-VALID
fi

echo "Milestone E.4i controls passed with ${BACKEND}."
