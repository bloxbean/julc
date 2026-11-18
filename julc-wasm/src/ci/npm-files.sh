#!/usr/bin/env bash
# ADR-077 D8a, I11: checks the npm files a job downloaded from the npm-packages artifact of the run that built them:
# the three npm tarballs, the two engine archives and the checksum file over them (npm-publish.mjs and npm-verify.mjs
# check the checksum file against all five). Each of the five is verified against the attestation the release
# workflow's attest job made, and the engine archives must be the ones the published GitHub release attaches, so npm
# never gets a version whose archives are not public (I1). Used by npm-dry-run, npm-publish and npm-verify.
#
#   npm-files.sh <version> <directory>
#
# Environment: GH_TOKEN; GITHUB_REPOSITORY; SOURCE_DIGEST, the tag's commit (github.sha); SIGNER_DIGEST, the commit of
# the workflow file that runs (github.workflow_sha). An attestation must name this repository, this workflow file, the
# release tag and both commits, and come from a GitHub-hosted runner.
set -euo pipefail
version=$1
dir=$2
: "${GITHUB_REPOSITORY:?}" "${SOURCE_DIGEST:?}" "${SIGNER_DIGEST:?}"
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
for file in "${files[@]}"; do
  gh attestation verify "$dir/$file" --repo "$GITHUB_REPOSITORY" \
    --signer-workflow "${GITHUB_REPOSITORY}/.github/workflows/native-image-release.yml" \
    --source-ref "refs/tags/v${version}" --source-digest "$SOURCE_DIGEST" --signer-digest "$SIGNER_DIGEST" \
    --deny-self-hosted-runners > /dev/null
  echo "attested: ${file}"
done
# The published release (a read-only token sees no draft) attaches the same engine archives.
if ! assets=$(gh api "repos/${GITHUB_REPOSITORY}/releases/tags/v${version}" \
    --jq '.assets[] | "\(.name) \(.state) \(.digest // "")"' 2> "${RUNNER_TEMP:-/tmp}/npm-files-release.err"); then
  cat "${RUNNER_TEMP:-/tmp}/npm-files-release.err" >&2
  echo "::error::v${version} has no published GitHub release: npm gets only versions whose engine archives are public." >&2
  exit 1
fi
for archive in "${archives[@]}"; do
  ours=$(sha256sum "$dir/$archive" | cut -d' ' -f1)
  theirs=$(awk -v name="$archive" '$1 == name { print $2 " " $3 }' <<< "$assets")
  if [[ "$theirs" != "uploaded sha256:${ours}" ]]; then
    echo "::error::The release of v${version} does not attach ${archive} with the npm files' bytes" \
      "(release: ${theirs:-missing}; npm files: sha256:${ours})." >&2
    exit 1
  fi
  echo "public: ${archive}"
done
