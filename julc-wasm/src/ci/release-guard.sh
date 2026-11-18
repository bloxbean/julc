#!/usr/bin/env bash
# ADR-077 D8a: a release is built once. Web Image output is not byte-identical across builds, and no native image or jar
# is reproducible either, so building again for a tag that already has a GitHub release would make different bytes
# from those already attached (and perhaps on npm). Every job that builds a release file runs this first, before
# building: a whole-workflow re-run, or a targeted re-run of one job after the release exists, then builds nothing.
# A retry of a failed build before the release exists still builds, and "Re-run failed jobs" after it exists re-runs
# only the jobs that failed. Read-only token: it sees published releases; this workflow never makes drafts
# (github-release, with its write token, refuses one).
#
#   release-guard.sh <version>          environment: GH_TOKEN, GITHUB_REPOSITORY
#   release-guard.sh <version> --list   prints "<id><TAB><draft>" for each release of the tag, from the same validated
#                                       reading (github-release, with its write token, which also sees drafts)
set -euo pipefail
version=$1
list=${2:-}
: "${GITHUB_REPOSITORY:?}"
err=$(mktemp)
malformed() {
  if [[ -n "$list" ]]; then
    echo "::error::The GitHub release list is malformed ($1). Stop: preserve the published release and its tag, change" \
      "nothing, and investigate (ADR-077 D8a)." >&2
  else
    echo "::error::The GitHub release list is malformed ($1); refusing to build without knowing." >&2
  fi
  exit 1
}
# Read page by page, each page validated before the next is asked for (R23-C29, C36, C44, C45): every page must be a
# JSON array (an empty answer, such as HTTP 204, is not "no release"), every release must carry a non-empty string
# tag_name, and a release of this tag a positive integer id and a boolean draft. An empty array is a valid answer: no
# (more) releases. A page of fewer than 100 releases is the last.
filter="if type != \"array\" then \"INVALID\" else \"PAGE \\(length)\", (.[] | if (.tag_name | type) == \"string\"
  and .tag_name != \"\" then select(.tag_name == \"v${version}\") | if (.id | type) == \"number\" and .id > 0
  and (.id | floor) == .id and (.draft | type) == \"boolean\" then \"RELEASE \\(.id) \\(.draft)\" else \"INVALID\" end
  else \"INVALID\" end) end"
releases=
for page in $(seq 1 100); do
  if ! answer=$(gh api "repos/${GITHUB_REPOSITORY}/releases?per_page=100&page=${page}" --jq "$filter" 2> "$err"); then
    cat "$err" >&2
    echo "::error::Could not read the GitHub releases (page ${page}); stopping without knowing, changing nothing." >&2
    exit 1
  fi
  head=$(grep '^PAGE ' <<< "$answer" || true)
  rows=$(grep -v '^PAGE ' <<< "$answer" || true)
  [[ "$head" =~ ^PAGE\ [0-9]+$ ]] || malformed "page ${page} is not a JSON array"
  if [[ -n "$rows" ]] && grep -qvE '^RELEASE [1-9][0-9]* (true|false)$' <<< "$rows"; then
    malformed "page ${page} has a release without a valid tag_name, id or draft"
  fi
  releases+=${rows:+$rows$'\n'}
  if [[ "${head#PAGE }" -lt 100 ]]; then break; fi
  if [[ "$page" -eq 100 ]]; then malformed "more than 10,000 releases"; fi
done
releases=${releases%$'\n'}
if [[ -n "$list" ]]; then
  if [[ -n "$releases" ]]; then sed 's/^RELEASE \([^ ]*\) \(.*\)$/\1\t\2/' <<< "$releases"; fi
  exit 0
fi
if [[ -z "$releases" ]]; then
  echo "No GitHub release of v${version} yet: building."
  exit 0
fi
ids=$(cut -d' ' -f2 <<< "$releases")
echo "::error::v${version} already has a GitHub release (id ${ids//$'\n'/, }), and a release is built once (ADR-077" \
  "D8a): rebuilding would make different bytes. A build job cannot be re-run once the release exists; if another job" \
  "failed, re-run that one; otherwise see 'Recovery' in adr/evidence/077-m4/RUNBOOK.md (the version may have to be" \
  "abandoned)." >&2
exit 1
