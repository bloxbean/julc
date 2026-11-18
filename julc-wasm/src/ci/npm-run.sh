#!/usr/bin/env bash
# ADR-077 D8a: the npm files a recovery dispatch on a release tag publishes: the one npm-packages artifact of the run of
# native-image-release.yml for the tag's push (event push, head branch v<version>, head commit the tag's commit) whose
# attest job succeeded, in any attempt; its github-release must have succeeded too (a complete release). Exactly one
# such run must exist (a release is built once, so a second run stops
# before attest), and it must have exactly one npm-packages artifact that has not expired (artifacts are kept 14
# days). Every API answer is checked: a history that cannot be read completely stops recovery, rather than choosing
# among what could be read, or calling it expired.
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
# A paginated collection, read completely or not at all (R23-C8, C15, C22, C30): every page must be an object with a
# numeric total_count, the same on every page, and an array <key>; every element is classified by <row> from
# well-typed fields into "<KIND> <id>[ <field>]", anything else INVALID; and the distinct ids must be exactly
# total_count, each read once (pages can overlap or shift while runs are added). GitHub returns at most 1,000 results
# for a filtered query, so a larger total cannot be read completely. Prints the rows.
#   collection <what> <url> <key> <row>
collection() {
  local out totals rows reads ids reported
  out=$(gh api --paginate "$2" --jq 'def valid_id: (.id | type) == "number" and .id > 0 and (.id | floor) == .id;
      (.total_count | if type == "number" then "TOTAL \(.)" else "INVALID" end),
      (.'"$3"' | if type == "array" then .[] | '"$4"' else "INVALID" end)') || unreadable "$1"
  totals=$(grep '^TOTAL ' <<< "$out" | sort -u || true)
  rows=$(grep -v '^TOTAL ' <<< "$out" || true)
  if [[ -n "$rows" ]] && grep -qvE '^[A-Z]+ [1-9][0-9]*( [^ ]+)?$' <<< "$rows"; then unreadable "$1: a malformed answer"; fi
  reads=$(grep -c . <<< "$rows" || true)
  ids=$({ cut -d' ' -f2 <<< "$rows" | grep . || true; } | sort -u | wc -l | tr -d ' ')
  if [[ ! "$totals" =~ ^TOTAL\ [0-9]+$ || "${totals#TOTAL }" -gt 1000 || "$ids" -ne "${totals#TOTAL }" \
      || "$reads" -ne "$ids" ]]; then
    reported=${totals//TOTAL /}
    unreadable "$1: incomplete or inconsistent, ${ids} distinct of ${reads} rows read, ${reported//$'\n'/ and } reported"
  fi
  if [[ -n "$rows" ]]; then printf '%s\n' "$rows"; fi
}
listed=$(collection "the workflow's runs" \
  "${api}/workflows/native-image-release.yml/runs?event=push&head_sha=${SOURCE_DIGEST}&per_page=100" workflow_runs \
  'if valid_id and (.head_branch | type) == "string" and (.head_branch | test("^[^\\s]+$"))
    then "RUN \(.id) \(.head_branch)" else "INVALID" end')
runs=$(awk -v branch="v${version}" '$3 == branch { print $2 }' <<< "$listed")
if [[ -z "$runs" ]]; then
  echo "::error::No run of native-image-release.yml for the push of v${version} (${SOURCE_DIGEST}) was found: the tag's" \
    "release run is needed to publish its npm files." >&2
  exit 1
fi
attested=()
released=()
for run in $runs; do
  # A job's conclusion must be present: a string once it completed, null only while it has not. The attest and
  # github-release jobs, which decide the choice, must also have a status and conclusion GitHub documents (R23-C41).
  jobs=$(collection "the jobs of run ${run}" "${api}/runs/${run}/jobs?filter=all&per_page=100" jobs \
    'def deciding: .name == "Attest the npm tarballs and engine archives" or .name == "Create GitHub release";
      def known: (.status | IN("queued", "in_progress", "completed", "waiting", "requested", "pending"))
        and (.conclusion == null or (.conclusion | IN("success", "failure", "neutral", "cancelled", "skipped",
          "timed_out", "action_required")));
      if valid_id and (.name | type) == "string" and (.status | type) == "string" and has("conclusion")
      and (if .status == "completed" then (.conclusion | type) == "string" else .conclusion == null end)
      and (if deciding then known else true end)
      then (if .conclusion != "success" then "OTHER \(.id)"
        elif .name == "Attest the npm tarballs and engine archives" then "ATTESTED \(.id)"
        elif .name == "Create GitHub release" then "RELEASED \(.id)" else "OTHER \(.id)" end)
      else "INVALID" end')
  if grep -q '^ATTESTED ' <<< "$jobs"; then attested+=("$run"); fi
  if grep -q '^RELEASED ' <<< "$jobs"; then released+=("$run"); fi
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
# Only a complete release reaches npm (R23-F10): the run's github-release succeeded, in any attempt, which checked that
# the release has its whole inventory.
if [[ " ${released[*]-} " != *" ${run} "* ]]; then
  echo "::error::The GitHub release of v${version} was never completed (run ${run}'s github-release did not succeed):" \
    "re-run that run's failed jobs, or abandon the version." >&2
  exit 1
fi
# An expired that is null or missing is not expiry: it is an unreadable answer. Expiry is decided only on a completely
# read list.
rows=$(collection "the artifacts of run ${run}" "${api}/runs/${run}/artifacts?name=npm-packages&per_page=100" artifacts \
  'if valid_id and (.name | type) == "string" and (.expired | type) == "boolean"
    then (if .name != "npm-packages" then "OTHER \(.id)" elif .expired then "EXPIRED \(.id)" else "LIVE \(.id)" end)
    else "INVALID" end')
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
