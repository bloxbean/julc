#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E3_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E3_DIR}/../.." && pwd)"
cd "${REPO_DIR}"

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")

if grep -R -E 'PlutusData|constr(Tag|Fields)|ContextsLib\.getSpendingDatum' \
    "${E3_DIR}/fixtures"/*/src; then
  echo "E.3 fixtures must rely on compiler-owned strict typed boundaries" >&2
  exit 1
fi

run_fixture() {
  local fixture="$1" validator="$2" fuel="$3" expected_exit="$4" expected="$5"
  local project="${E3_DIR}/fixtures/${fixture}"
  local build_dir="${project}/build/dsl"
  local model="${build_dir}/src/evidence/SaleModel.java"
  local classes="${build_dir}/classes"
  local workspace="${E3_DIR}/generated/${fixture}"

  mkdir -p "$(dirname "${model}")" "${classes}" "${workspace}"
  rm -f "${model}"
  "${JULC[@]}" verify dsl-init "${project}" --validator "${validator}" \
    --package evidence --class SaleModel --out "${model}"
  javac -cp "${REPO_DIR}/julc-cli/build/libs/julc.jar" -d "${classes}" \
    "${model}" "${E3_DIR}/SellerPaymentSpec.java"

  if [[ "${fixture}" != "authorized" ]]; then
    [[ -d "${workspace}/.lake" ]] || cp -R "${E3_DIR}/generated/authorized/.lake" "${workspace}/"
    [[ -d "${workspace}/.julc" ]] || cp -R "${E3_DIR}/generated/authorized/.julc" "${workspace}/"
  fi
  set +e
  "${JULC[@]}" verify dsl "${project}" --validator "${validator}" \
    --spec-class evidence.SellerPaymentSpec --spec-classpath "${classes}" \
    --seller-field seller --price-field price --source SellerPaymentSpec.java \
    --backend local --fuel "${fuel}" --out-dir "${workspace}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${fixture}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected}\"" "${workspace}/verification-result.json"
  grep -q '"boundarySemantics" : "strict-data-v1"' \
    "${workspace}/verification-result.json"
  grep -q '"boundarySemantics" : "strict-data-v1"' \
    "${workspace}/verification-manifest.json"
}

run_fixture authorized AuthorizedSale 1500 0 SMT-VALID
run_fixture unpaid UnpaidSale 1000 3 REFUTED
run_fixture vacuous VacuousSale 1000 2 COULD-NOT-EVALUATE
run_fixture multi-satisfaction MultiSatisfactionSale 1500 0 SMT-VALID

grep -q '"ledgerValidityModeled" : true' \
  "${E3_DIR}/generated/authorized/verification-result.json"
grep -q 'validSpendingContext/v3-pinned' \
  "${E3_DIR}/generated/authorized/verification-result.json"
grep -q 'Counterexample:' \
  "${E3_DIR}/generated/unpaid/verification-results/verify.log"
grep -q 'global protection against multi-satisfaction' \
  "${E3_DIR}/generated/multi-satisfaction/README.md"

echo "Milestone E.3 typed seller-payment DSL controls passed."
