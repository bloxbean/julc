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
if ! total=$(gh api "$query" --jq '.total_count | if type == "number" then . else "INVALID" end'); then
  unreadable "listing the workflow's runs"
fi
# Each run classified only from well-typed fields: a positive integer id and a non-blank string branch; any other row
# is INVALID, and stops recovery.
if ! listed=$(gh api --paginate "$query" --jq '.workflow_runs[] | if (.id | type) == "number" and .id > 0
    and (.id | floor) == .id and (.head_branch | type) == "string" and (.head_branch | test("^[^\\s]+$"))
    then "\(.id) \(.head_branch)" else "INVALID" end'); then
  unreadable "listing the workflow's runs"
fi
# Each row a numeric run id and a branch; the distinct ids must be exactly as many as GitHub says there are (pages can
# overlap when runs are added while reading).
if [[ -n "$listed" ]] && grep -qvE '^[1-9][0-9]* [^ ]+$' <<< "$listed"; then unreadable "a malformed run list"; fi
rows=$(grep -c . <<< "$listed" || true)
count=$({ cut -d' ' -f1 <<< "$listed" | grep . || true; } | sort -u | wc -l | tr -d ' ')
if [[ ! "$total" =~ ^[0-9]+$ || "$total" -gt 1000 || "$count" -ne "$total" || "$rows" -ne "$count" ]]; then
  unreadable "the run list is incomplete or inconsistent: ${count} distinct of ${rows} rows read, ${total} reported"
fi
runs=$(awk -v branch="v${version}" '$2 == branch { print $1 }' <<< "$listed")
if [[ -z "$runs" ]]; then
  echo "::error::No run of native-image-release.yml for the push of v${version} (${SOURCE_DIGEST}) was found: the tag's" \
    "release run is needed to publish its npm files." >&2
  exit 1
fi
attested=()
for run in $runs; do
  # Every job classified from well-typed fields (a positive integer id, a string name, a string conclusion, or null
  # for a job still pending) before any is counted; anything else is INVALID and stops recovery.
  if ! jobs=$(gh api --paginate "${api}/runs/${run}/jobs?filter=all&per_page=100" \
      --jq '.jobs[] | if (.id | type) == "number" and .id > 0 and (.id | floor) == .id and (.name | type) == "string"
        and ((.conclusion | type) == "string" or .conclusion == null)
        then (if .name == "Attest the npm tarballs and engine archives" and .conclusion == "success"
          then "ATTESTED \(.id)" else "OTHER \(.id)" end) else "INVALID" end'); then
    unreadable "the jobs of run ${run}"
  fi
  if [[ -n "$jobs" ]] && grep -qvE '^(ATTESTED|OTHER) [1-9][0-9]*$' <<< "$jobs"; then
    unreadable "a malformed job list of run ${run}"
  fi
  if grep -q '^ATTESTED ' <<< "$jobs"; then attested+=("$run"); fi
done
if [[ ${#attested[@]} -eq 0 ]]; then
  echo "::error::No run for the push of v${version} has a successful attestation yet (runs: ${runs//$'\n'/, }). Check" \
    "the original push run: if its attest job failed or has not run, \"Re-run failed jobs\" there, then dispatch again." >&2
  exit 1
fi
if [[ ${#attested[@]} -gt 1 ]]; then
  echo "::error::Several runs for the push of v${version} attested npm files (${attested[*]}); a release is built once," \
    "so this needs a maintainer to decide which run's files are the release's." >&2
  exit 1
fi
run=${attested[0]}
# Every artifact classified from well-typed fields (a positive integer id, a string name, a boolean expired): an
# expired that is null or missing is not expiry, it is an unreadable answer.
if ! rows=$(gh api --paginate "${api}/runs/${run}/artifacts?name=npm-packages&per_page=100" \
    --jq '.artifacts[] | if (.id | type) == "number" and .id > 0 and (.id | floor) == .id and (.name | type) == "string"
      and (.expired | type) == "boolean"
      then (if .name != "npm-packages" then "OTHER \(.id)" elif .expired then "EXPIRED \(.id)" else "LIVE \(.id)" end)
      else "INVALID" end'); then
  unreadable "the artifacts of run ${run}"
fi
if [[ -n "$rows" ]] && grep -qvE '^(LIVE|EXPIRED|OTHER) [1-9][0-9]*$' <<< "$rows"; then
  unreadable "a malformed artifact list of run ${run}"
fi
live=$(awk '$1 == "LIVE" { print $2 }' <<< "$rows")
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
