#!/usr/bin/env bash
# ADR-077 D8a, I11: checks the npm files a job downloaded from the npm-packages artifact of the run that built them:
# the three npm tarballs, the two engine archives and the checksum file over them (npm-publish.mjs and npm-verify.mjs
# check the checksum file against all five). The five must be exactly the subjects of ONE attestation the release
# workflow's attest job made in that run (one statement, five subjects), so files of two attempts or builds can never
# be mixed; and the engine archives must be the ones the published GitHub release attaches, so npm never gets a
# version whose archives are not public (I1). Used by npm-dry-run, npm-publish and npm-verify.
#
#   npm-files.sh <version> <directory>
#
# Environment: GH_TOKEN; GITHUB_REPOSITORY; SOURCE_DIGEST, the tag's commit (github.sha); SIGNER_DIGEST, the commit of
# the workflow file that runs (github.workflow_sha); NPM_FILES_RUN, the id of the run the artifact came from (this
# run on a tag push, npm-run.sh's on a dispatch). An attestation must name this repository, this workflow file, the
# release tag and both commits, and come from a GitHub-hosted runner.
set -euo pipefail
version=$1
dir=$2
: "${GITHUB_REPOSITORY:?}" "${SOURCE_DIGEST:?}" "${SIGNER_DIGEST:?}" "${NPM_FILES_RUN:?}"
archives=("julc-vm-wasm-${version}.tar.gz" "julc-wasm-${version}.tar.gz")
files=("julclang-wasm-client-${version}.tgz" "julclang-julc-vm-wasm-${version}.tgz" "julclang-julc-wasm-${version}.tgz"
  "${archives[@]}")
sums="julc-npm-${version}-SHA256SUMS.txt"
for file in "${files[@]}" "$sums"; do
  if [[ ! -f "$dir/$file" ]]; then
    echo "::error::The npm files of v${version} have no ${file}." >&2
    exit 1
  fi
done
if command -v sha256sum > /dev/null; then sha256() { sha256sum "$1" | cut -d' ' -f1; }
else sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }; fi
# The five files, as attest names them: "<file name> <sha256>", sorted.
expected=$(for file in "${files[@]}"; do echo "${file} $(sha256 "$dir/$file")"; done | LC_ALL=C sort | paste -sd ';' -)
# Every attestation that covers the first file, each verified against this workflow's identity at the tag; then one of
# them must have exactly the five files as subjects and come from the artifact's run.
work=$(mktemp -d)
gh attestation verify "$dir/${files[0]}" --repo "$GITHUB_REPOSITORY" \
  --signer-workflow "${GITHUB_REPOSITORY}/.github/workflows/native-image-release.yml" \
  --source-ref "refs/tags/v${version}" --source-digest "$SOURCE_DIGEST" --signer-digest "$SIGNER_DIGEST" \
  --deny-self-hosted-runners --format json > "$work/attestations.json"
statements=$(jq -r '.[] | (.verificationResult.statement.subject | map("\(.name) \(.digest.sha256)") | sort | join(";"))
  + "|" + .verificationResult.signature.certificate.runInvocationURI' "$work/attestations.json")
run="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${NPM_FILES_RUN}/attempts/"
found=false
while IFS='|' read -r subjects invocation; do
  if [[ "$subjects" == "$expected" && "$invocation" == "$run"* ]]; then found=true; fi
done <<< "$statements"
if [[ "$found" != true ]]; then
  echo "::error::No single attestation of run ${NPM_FILES_RUN} covers exactly these five npm files of v${version}: they" \
    "are not one attested set (files of different attempts or builds, or another run's). Attested sets for" \
    "${files[0]}:" >&2
  echo "$statements" | tr ';' '\n' >&2
  exit 1
fi
echo "attested: the five files are one set, run ${NPM_FILES_RUN}"
# The published release (a read-only token sees no draft) attaches the same engine archives.
if ! assets=$(gh api "repos/${GITHUB_REPOSITORY}/releases/tags/v${version}" \
    --jq '.assets[] | "\(.name) \(.state) \(.digest // "")"' 2> "${RUNNER_TEMP:-/tmp}/npm-files-release.err"); then
  cat "${RUNNER_TEMP:-/tmp}/npm-files-release.err" >&2
  echo "::error::v${version} has no published GitHub release: npm gets only versions whose engine archives are public." >&2
  exit 1
fi
for archive in "${archives[@]}"; do
  ours=$(sha256 "$dir/$archive")
  theirs=$(awk -v name="$archive" '$1 == name { print $2 " " $3 }' <<< "$assets")
  if [[ "$theirs" != "uploaded sha256:${ours}" ]]; then
    echo "::error::The release of v${version} does not attach ${archive} with the npm files' bytes" \
      "(release: ${theirs:-missing}; npm files: sha256:${ours})." >&2
    exit 1
  fi
  echo "public: ${archive}"
done
