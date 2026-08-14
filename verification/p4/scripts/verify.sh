#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
P4_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${P4_DIR}/../.." && pwd)"

cd "${REPO_DIR}"
./gradlew \
  :julc-compiler:test --tests '*ContractSchemaTest' \
  :julc-blueprint:test \
  :julc-cardano-client-lib:test --tests '*PlutusDataAdapterConvertTest' \
  :julc-cli:test --tests '*BuildCommandTest' --tests '*ArtifactCommandTest' \
    --tests '*VerificationProjectGeneratorTest' \
  :julc-annotation-processor:test --tests '*JulcAnnotationProcessorTest' \
  :julc-gradle-plugin:test --tests '*JulcPluginTest' \
  :julc-playground:test --tests '*CompileControllerTest' \
  :julc-cli:shadowJar

JULC=(java -jar "${REPO_DIR}/julc-cli/build/libs/julc.jar")
FIXTURE="${P4_DIR}/fixture"
SPEND_WORKSPACE="${P4_DIR}/generated/spend"
MINT_WORKSPACE="${P4_DIR}/generated/mint"

"${JULC[@]}" build "${FIXTURE}"
BLUEPRINT="${FIXTURE}/build/plutus/plutus.json"

jq -e '
  [.validators[].title] == ["Protocol.mint", "Protocol.spend", "Protocol.publish"] and
  (.validators[0].redeemer.purpose == "mint") and
  (.validators[1].datum.purpose == "spend") and
  (.validators[1].redeemer.purpose == "spend") and
  (.validators[2].redeemer.purpose == "publish") and
  ([.validators[].compiledCode] | unique | length == 1) and
  ([.validators[].hash] | unique | length == 1)
' "${BLUEPRINT}" >/dev/null

"${JULC[@]}" verify init "${FIXTURE}" --validator Protocol --purpose spending \
  --out-dir "${SPEND_WORKSPACE}" --force
"${JULC[@]}" verify init "${FIXTURE}" --validator Protocol --purpose minting \
  --out-dir "${MINT_WORKSPACE}" --force

jq -e '
  .validatorTitle == "Protocol" and
  .blueprintEntryTitle == "Protocol.spend" and
  .scriptPurpose == "spending"
' "${SPEND_WORKSPACE}/verification-manifest.json" >/dev/null
jq -e '
  .validatorTitle == "Protocol" and
  .blueprintEntryTitle == "Protocol.mint" and
  .scriptPurpose == "minting"
' "${MINT_WORKSPACE}/verification-manifest.json" >/dev/null

spend_code="$(jq -r .compiledCodeSha256 "${SPEND_WORKSPACE}/verification-manifest.json")"
mint_code="$(jq -r .compiledCodeSha256 "${MINT_WORKSPACE}/verification-manifest.json")"
spend_hash="$(jq -r .cardanoScriptHash "${SPEND_WORKSPACE}/verification-manifest.json")"
mint_hash="$(jq -r .cardanoScriptHash "${MINT_WORKSPACE}/verification-manifest.json")"
[[ "${spend_code}" == "${mint_code}" ]]
[[ "${spend_hash}" == "${mint_hash}" ]]

echo "ADR-017 P.4 evidence passed: three exact interfaces, one deployed artifact."
