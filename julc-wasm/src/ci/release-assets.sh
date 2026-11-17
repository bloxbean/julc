#!/usr/bin/env bash
# ADR-077 D8a, I11: the npm files of a release, as the GitHub release holds them, each verified against the attestation
# the release workflow's attest job made: the three npm tarballs, the two engine archives and the checksum file over
# them (the checksum file itself is checked against them by npm-publish.mjs and npm-verify.mjs). Used by the
# npm-dry-run, npm-publish and npm-verify jobs of native-image-release.yml.
#
#   release-assets.sh <version> <directory>
#
# Environment: GH_TOKEN; GITHUB_REPOSITORY; SOURCE_DIGEST, the tag's commit (github.sha); SIGNER_DIGEST, the commit of
# the workflow file that runs (github.workflow_sha). An attestation must name this repository, this workflow file, the
# release tag and both commits, and come from a GitHub-hosted runner. A draft release is invisible to a read-only
# token, so only a published release's assets are found.
set -euo pipefail
version=$1
dir=$2
: "${GITHUB_REPOSITORY:?}" "${SOURCE_DIGEST:?}" "${SIGNER_DIGEST:?}"
files=("julclang-wasm-client-${version}.tgz" "julclang-julc-vm-wasm-${version}.tgz" "julclang-julc-wasm-${version}.tgz"
  "julc-vm-wasm-${version}.tar.gz" "julc-wasm-${version}.tar.gz")
mkdir -p "$dir"
patterns=()
for file in "${files[@]}" "julc-npm-${version}-SHA256SUMS.txt"; do patterns+=(--pattern "$file"); done
gh release download "v${version}" --repo "$GITHUB_REPOSITORY" --dir "$dir" "${patterns[@]}"
for file in "${files[@]}" "julc-npm-${version}-SHA256SUMS.txt"; do
  if [[ ! -f "$dir/$file" ]]; then
    echo "::error::The release of v${version} has no asset ${file}." >&2
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
