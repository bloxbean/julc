#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4D_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4D_DIR}/../.." && pwd)"
BACKEND="${E4D_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4D_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"
PROJECT="${E4D_DIR}/fixtures/certifying"
BUILD_DIR="${PROJECT}/build/dsl"
MODEL="${BUILD_DIR}/src/evidence/CertifyingModel.java"

mkdir -p "$(dirname "${MODEL}")" "${BUILD_DIR}/classes"
rm -f "${MODEL}"
"${JULC[@]}" verify dsl-init "${PROJECT}" --validator AuthorizedCertificates \
  --purpose certifying --package evidence --class CertifyingModel --out "${MODEL}"
javac -cp "${JULC_JAR}" -d "${BUILD_DIR}/classes" \
  "${MODEL}" "${E4D_DIR}/CertifyingSpec.java"

run_property() {
  local validator="$1" output="$2" expected_exit="$3" expected_outcome="$4"
  set +e
  "${JULC[@]}" verify dsl "${PROJECT}" --validator "${validator}" \
    --purpose certifying --spec-class evidence.CertifyingSpec \
    --spec-classpath "${BUILD_DIR}/classes:${JULC_JAR}" \
    --source "${E4D_DIR}/CertifyingSpec.java" --backend "${BACKEND}" \
    --fuel 5000 --out-dir "${E4D_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected_outcome}\"" \
    "${E4D_DIR}/generated/${output}/verification-result.json"
}

if [[ "${BACKEND}" == "local" ]]; then
  run_property AuthorizedCertificates authorized 0 SMT-VALID

  bridge_negative="${E4D_DIR}/generated/authorized/BridgeNegativeControl.lean"
  cp "${E4D_DIR}/BridgeNegativeControl.lean.expected-failure" "${bridge_negative}"
  if (cd "${E4D_DIR}/generated/authorized" \
      && lake env lean "${bridge_negative}") \
      >"${E4D_DIR}/generated/authorized/verification-results/bridge-negative.log" 2>&1; then
    rm -f "${bridge_negative}"
    echo "strengthened certifying ledger-domain bridge unexpectedly compiled" >&2
    exit 1
  fi
  rm -f "${bridge_negative}"

  run_property MissingAuthorityCertificates missing-authority 3 REFUTED
  run_property AnyCertificate any-certificate 3 REFUTED
  run_property VacuousCertificates vacuous 2 COULD-NOT-EVALUATE

  grep -q 'validCertifyingContext_implies_blasterDomain' \
    "${E4D_DIR}/generated/authorized/LedgerDomainEquivalence.lean"
  grep -q 'CertifyingSemanticsTests' \
    "${E4D_DIR}/generated/authorized/lakefile.lean"
  grep -q 'AuthorizedCertificates_certificate_authorized_updateLedgerCorollary.lean' \
    "${E4D_DIR}/generated/authorized/scripts/verify-certificate_authorized_update.sh"
  grep -q '"counterexampleDomain" : "BLASTER_VALID_CERTIFYING_SUPERSET"' \
    "${E4D_DIR}/generated/missing-authority/verification-result.json"
  grep -q '"reason" : "not-evaluated-vacuous"' \
    "${E4D_DIR}/generated/vacuous/verification-result.json"
else
  run_property AuthorizedCertificates authorized-docker 0 SMT-VALID
  grep -q 'AuthorizedCertificates_certificate_authorized_updateLedgerCorollary.lean' \
    "${E4D_DIR}/generated/authorized-docker/scripts/verify-certificate_authorized_update.sh"
fi

echo "Milestone E.4d certifying DSL controls passed with ${BACKEND}."
