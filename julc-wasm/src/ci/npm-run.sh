#!/usr/bin/env bash
# ADR-077 D8a: the npm files a recovery dispatch on a release tag publishes: the one npm-packages artifact of the run of
# native-image-release.yml for the tag's push (event push, head branch v<version>, head commit the tag's commit) whose
# attest job succeeded, in any attempt. Exactly one such run must exist (a release is built once, so a second run stops
# before attest), and it must have exactly one npm-packages artifact that has not expired (artifacts are kept 14
# days). Every API answer is checked: a history that cannot be read completely stops recovery, rather than choosing
# among what could be read.
#
#   npm-run.sh <version>        writes run_id=<id> and artifact_id=<id> to $GITHUB_OUTPUT (or stdout)
#
# Environment: GH_TOKEN (actions: read); GITHUB_REPOSITORY; SOURCE_DIGEST, the tag's commit (github.sha).
set -euo pipefail
version=$1
: "${GITHUB_REPOSITORY:?}" "${SOURCE_DIGEST:?}"
api="repos/${GITHUB_REPOSITORY}/actions"
gone() {
  echo "::error::The npm files of v${version} are no longer available (artifacts are kept 14 days): $1." \
    "This version can no longer be published to npm." >&2
  exit 1
}
unreadable() {
  echo "::error::Cannot read the run history needed to recover v${version}'s npm publication ($1); nothing was" \
    "chosen. Retry the dispatch later." >&2
  exit 1
}
query="${api}/workflows/native-image-release.yml/runs?event=push&head_sha=${SOURCE_DIGEST}&per_page=100"
# GitHub returns at most 1,000 runs for a filtered query: refuse if the history could be longer, or was not all read.
if ! total=$(gh api "$query" --jq '.total_count'); then unreadable "listing the workflow's runs"; fi
if ! listed=$(gh api --paginate "$query" --jq '.workflow_runs[] | "\(.id) \(.head_branch)"'); then
  unreadable "listing the workflow's runs"
fi
count=$(grep -c . <<< "$listed" || true)
if [[ ! "$total" =~ ^[0-9]+$ || "$total" -gt 1000 || "$count" -ne "$total" ]]; then
  unreadable "the run list is incomplete: ${count} of ${total} runs read"
fi
runs=$(awk -v branch="v${version}" '$2 == branch { print $1 }' <<< "$listed")
attested=()
for run in $runs; do
  if ! jobs=$(gh api --paginate "${api}/runs/${run}/jobs?filter=all&per_page=100" \
      --jq '.jobs[] | select(.name == "Attest the npm tarballs and engine archives" and .conclusion == "success") | .id'); then
    unreadable "the jobs of run ${run}"
  fi
  if [[ -n "$jobs" ]]; then attested+=("$run"); fi
done
if [[ ${#attested[@]} -eq 0 ]]; then
  gone "no run of native-image-release.yml for the push of v${version} (${SOURCE_DIGEST}) attested them"
fi
if [[ ${#attested[@]} -gt 1 ]]; then
  echo "::error::Several runs for the push of v${version} attested npm files (${attested[*]}); a release is built once," \
    "so this needs a maintainer to decide which run's files are the release's." >&2
  exit 1
fi
run=${attested[0]}
if ! live=$(gh api --paginate "${api}/runs/${run}/artifacts?name=npm-packages&per_page=100" \
    --jq '.artifacts[] | select(.name == "npm-packages" and .expired == false) | .id'); then
  unreadable "the artifacts of run ${run}"
fi
artifacts=$(grep -c . <<< "$live" || true)
if [[ "$artifacts" -eq 0 ]]; then
  gone "run ${run}'s npm-packages artifact has expired or was deleted"
fi
if [[ "$artifacts" -gt 1 ]]; then
  echo "::error::Run ${run} has ${artifacts} npm-packages artifacts (${live//$'\n'/, }), from several attempts: which one" \
    "is the release's is not decidable here; a maintainer decides." >&2
  exit 1
fi
echo "npm files of v${version}: run ${run}, artifact ${live}"
{
  echo "run_id=${run}"
  echo "artifact_id=${live}"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"
