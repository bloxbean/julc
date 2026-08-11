#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${VERIFY_DIR}/../.." && pwd)"
GENERATED_DIR="${VERIFY_DIR}/generated"
ARTIFACT_DIR="${VERIFY_DIR}/artifacts"
UPDATE_LOCK=false

if [[ "${1:-}" == "--update-lock" ]]; then
  UPDATE_LOCK=true
elif [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--update-lock]" >&2
  exit 2
fi

for command_name in java jq git; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "COULD-NOT-EVALUATE: missing command ${command_name}" >&2
    exit 2
  fi
done

mkdir -p "${GENERATED_DIR}" "${ARTIFACT_DIR}"

cd "${REPO_DIR}"
./gradlew :julc-cli:shadowJar -PskipSigning=true
JULC_JAR="${REPO_DIR}/julc-cli/build/libs/julc.jar"

if [[ ! -f "${JULC_JAR}" ]]; then
  echo "COULD-NOT-EVALUATE: JuLC CLI jar was not produced" >&2
  exit 2
fi

is_supported_tag() {
  local candidate="$1"
  local line start end
  while IFS= read -r line; do
    line="${line%%#*}"
    line="${line//[[:space:]]/}"
    [[ -z "${line}" ]] && continue
    if [[ "${line}" == *-* ]]; then
      start="${line%-*}"
      end="${line#*-}"
      if (( candidate >= start && candidate <= end )); then
        return 0
      fi
    elif (( candidate == line )); then
      return 0
    fi
  done < "${VERIFY_DIR}/config/blaster-builtins.txt"
  return 1
}

prepare_fixture() {
  local fixture_id="$1"
  local validator_title="$2"
  local fixture_dir="${VERIFY_DIR}/fixtures/${fixture_id}"
  local fixture_generated="${GENERATED_DIR}/${fixture_id}"

  java -jar "${JULC_JAR}" build "${fixture_dir}"
  mkdir -p "${fixture_generated}"
  java -jar "${JULC_JAR}" blueprint artifact "${fixture_dir}" \
    --validator "${validator_title}" \
    --out-dir "${fixture_generated}"

  local metadata_file
  metadata_file="$(find "${fixture_generated}" -maxdepth 1 \
    -name '*.metadata.json' -type f -print -quit)"
  if [[ -z "${metadata_file}" ]]; then
    echo "COULD-NOT-EVALUATE: no artifact metadata for ${fixture_id}" >&2
    exit 2
  fi

  local unsupported=false
  while IFS= read -r tag; do
    if ! is_supported_tag "${tag}"; then
      echo "COULD-NOT-EVALUATE: ${fixture_id} uses unsupported builtin tag ${tag}" >&2
      unsupported=true
    fi
  done < <(jq -r '.builtins[].flatTag' "${metadata_file}")
  if [[ "${unsupported}" == true ]]; then
    exit 2
  fi

  jq --arg fixture "${fixture_id}" \
    '. + {fixture: $fixture} | del(.compiledCode)' \
    "${metadata_file}" > "${fixture_generated}/lock-entry.json"
}

prepare_fixture "smoke" "VerificationDatumGate"
prepare_fixture "typed-multisig" "VerificationMultiSig"
prepare_fixture "typed-multisig-broken" "VerificationMultiSig"

jq -s '{schemaVersion: 1, artifacts: sort_by(.fixture)}' \
  "${GENERATED_DIR}"/*/lock-entry.json > "${GENERATED_DIR}/artifact-lock.json"

if [[ "${UPDATE_LOCK}" == true ]]; then
  cp "${GENERATED_DIR}/artifact-lock.json" "${ARTIFACT_DIR}/artifact-lock.json"
  for fixture_id in smoke typed-multisig typed-multisig-broken; do
    source_hex="$(find "${GENERATED_DIR}/${fixture_id}" -maxdepth 1 \
      -name '*.compiledCode.hex' -type f -print -quit)"
    cp "${source_hex}" "${ARTIFACT_DIR}/${fixture_id}.compiledCode.hex"
  done
  echo "Updated committed artifact lock and imported hex artifacts."
elif [[ ! -f "${ARTIFACT_DIR}/artifact-lock.json" ]]; then
  echo "COULD-NOT-EVALUATE: artifact lock is missing; run with --update-lock" >&2
  exit 2
elif ! diff -u "${ARTIFACT_DIR}/artifact-lock.json" \
    "${GENERATED_DIR}/artifact-lock.json"; then
  echo "COULD-NOT-EVALUATE: generated artifact metadata differs from the lock" >&2
  exit 2
else
  for fixture_id in smoke typed-multisig typed-multisig-broken; do
    generated_hex="$(find "${GENERATED_DIR}/${fixture_id}" -maxdepth 1 \
      -name '*.compiledCode.hex' -type f -print -quit)"
    if ! cmp -s "${ARTIFACT_DIR}/${fixture_id}.compiledCode.hex" "${generated_hex}"; then
      echo "COULD-NOT-EVALUATE: ${fixture_id} compiledCode differs from the lock" >&2
      exit 2
    fi
  done
fi

dirty=false
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  dirty=true
fi

jq -n \
  --arg sourceCommit "$(git rev-parse HEAD)" \
  --argjson dirtyWorktree "${dirty}" \
  --slurpfile lock "${GENERATED_DIR}/artifact-lock.json" \
  '{
    sourceCommit: $sourceCommit,
    dirtyWorktree: $dirtyWorktree,
    artifactStatus: "PREPARED",
    artifactLock: $lock[0]
  }' > "${GENERATED_DIR}/run-manifest.json"

echo "Artifact preparation complete: ${GENERATED_DIR}/artifact-lock.json"
