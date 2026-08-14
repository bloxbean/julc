#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCK="${VERIFY_DIR}/artifacts/artifact-lock.json"
PROPERTIES="${VERIFY_DIR}/config/properties.json"

command -v jq >/dev/null 2>&1 || {
  echo "COULD-NOT-EVALUATE: jq is required" >&2
  exit 2
}

seen=""
while IFS= read -r regression; do
  property_id="$(jq -er '.propertyId' "${regression}")"
  fixture="$(jq -er '.fixture' "${regression}")"
  expected="$(jq -er '.expectedResult' "${regression}")"
  [[ "${expected}" == "REFUTED" ]] || {
    echo "COULD-NOT-EVALUATE: ${regression} is not an expected refutation" >&2
    exit 2
  }
  [[ " ${seen} " != *" ${property_id} "* ]] || {
    echo "COULD-NOT-EVALUATE: duplicate counterexample ${property_id}" >&2
    exit 2
  }
  seen="${seen} ${property_id}"

  jq -e --arg id "${property_id}" --arg fixture "${fixture}" '
    any(.properties[]; .id == $id and .fixture == $fixture and
      .result == "REFUTED" and .evidence == "COUNTEREXAMPLE")
  ' "${PROPERTIES}" >/dev/null || {
    echo "COULD-NOT-EVALUATE: no matching property for ${property_id}" >&2
    exit 2
  }

  jq -e --arg fixture "${fixture}" \
    --arg code "$(jq -er '.compiledCodeSha256' "${regression}")" \
    --arg hash "$(jq -er '.cardanoScriptHash' "${regression}")" '
      any(.artifacts[]; .fixture == $fixture and
        .compiledCodeSha256 == $code and .cardanoScriptHash == $hash)
    ' "${LOCK}" >/dev/null || {
      echo "COULD-NOT-EVALUATE: stale artifact identity in ${regression}" >&2
      exit 2
    }
done < <(find "${VERIFY_DIR}/counterexamples" -type f -name '*.json' | sort)

expected_count="$(jq '[.properties[] | select(.result == "REFUTED")] | length' "${PROPERTIES}")"
actual_count="$(find "${VERIFY_DIR}/counterexamples" -type f -name '*.json' | wc -l | tr -d ' ')"
[[ "${actual_count}" == "${expected_count}" ]] || {
  echo "COULD-NOT-EVALUATE: expected ${expected_count} counterexamples, found ${actual_count}" >&2
  exit 2
}

echo "Validated ${actual_count} source-linked counterexample regressions."
