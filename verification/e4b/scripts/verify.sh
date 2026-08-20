#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E4B_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${E4B_DIR}/../.." && pwd)"
BACKEND="${E4B_BACKEND:-local}"
cd "${REPO_DIR}"

if [[ "${BACKEND}" != "local" && "${BACKEND}" != "docker" ]]; then
  echo "E4B_BACKEND must be local or docker" >&2
  exit 1
fi

./gradlew :julc-verification:test :julc-cli:test :julc-cli:shadowJar
JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"

prepare_model() {
  local fixture="$1" validator="$2" model="$3"
  local project="${E4B_DIR}/fixtures/${fixture}"
  local build_dir="${project}/build/dsl"
  local model_source="${build_dir}/src/evidence/${model}.java"
  mkdir -p "$(dirname "${model_source}")" "${build_dir}/classes"
  rm -f "${model_source}"
  "${JULC[@]}" verify dsl-init "${project}" --validator "${validator}" \
    --purpose spending --package evidence --class "${model}" --out "${model_source}"
}

prepare_model spending ComposedSale ComposedSaleModel
javac -cp "${JULC_JAR}" -d "${E4B_DIR}/fixtures/spending/build/dsl/classes" \
  "${E4B_DIR}/fixtures/spending/build/dsl/src/evidence/ComposedSaleModel.java" \
  "${E4B_DIR}/ComposedSpendingSpec.java" \
  "${E4B_DIR}/MixedSpendingSpec.java"

mint_project="${E4B_DIR}/fixtures/minting"
mint_build="${mint_project}/build/dsl"
mint_model="${mint_build}/src/evidence/ComposedPolicyModel.java"
mkdir -p "$(dirname "${mint_model}")" "${mint_build}/classes"
rm -f "${mint_model}"
"${JULC[@]}" verify dsl-init "${mint_project}" --validator ComposedPolicy \
  --purpose minting --package evidence --class ComposedPolicyModel --out "${mint_model}"
javac -cp "${JULC_JAR}" -d "${mint_build}/classes" \
  "${mint_model}" "${E4B_DIR}/ComposedMintingSpec.java"

prepare_model vacuous VacuousSpending VacuousSpendingModel
javac -cp "${JULC_JAR}" -d "${E4B_DIR}/fixtures/vacuous/build/dsl/classes" \
  "${E4B_DIR}/fixtures/vacuous/build/dsl/src/evidence/VacuousSpendingModel.java" \
  "${E4B_DIR}/VacuousSpendingSpec.java"

run_property() {
  local fixture="$1" validator="$2" purpose="$3" spec="$4"
  local output="$5" expected_exit="$6" expected_outcome="$7" fuel="$8"
  local classes="${E4B_DIR}/fixtures/${fixture}/build/dsl/classes"
  set +e
  "${JULC[@]}" verify dsl "${E4B_DIR}/fixtures/${fixture}" \
    --validator "${validator}" --purpose "${purpose}" \
    --spec-class "evidence.${spec}" --spec-classpath "${classes}:${JULC_JAR}" \
    --source "${E4B_DIR}/${spec}.java" --backend "${BACKEND}" --fuel "${fuel}" \
    --out-dir "${E4B_DIR}/generated/${output}" --force
  local actual_exit=$?
  set -e
  [[ "${actual_exit}" -eq "${expected_exit}" ]] || {
    echo "${output}: expected exit ${expected_exit}, found ${actual_exit}" >&2
    exit 1
  }
  grep -q "\"outcome\" : \"${expected_outcome}\"" \
    "${E4B_DIR}/generated/${output}/verification-result.json"
}

run_property spending ComposedSale spending ComposedSpendingSpec \
  spending 0 SMT-VALID 5000
run_property minting ComposedPolicy minting ComposedMintingSpec \
  minting 0 SMT-VALID 5000
run_property spending ComposedSale spending MixedSpendingSpec \
  mixed 3 REFUTED 5000
run_property vacuous VacuousSpending spending VacuousSpendingSpec \
  vacuous 2 COULD-NOT-EVALUATE 3000

grep -q '"id" : "mixed.paid"' \
  "${E4B_DIR}/generated/mixed/verification-result.json"
grep -q '"id" : "mixed.signed"' \
  "${E4B_DIR}/generated/mixed/verification-result.json"
grep -q '"reason" : "not-evaluated-vacuous"' \
  "${E4B_DIR}/generated/vacuous/verification-result.json"
grep -q '"ledgerValidCounterexampleEstablished" : false' \
  "${E4B_DIR}/generated/mixed/verification-result.json"

echo "Milestone E.4b compositional DSL controls passed with ${BACKEND}."
