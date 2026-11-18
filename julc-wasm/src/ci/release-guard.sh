#!/usr/bin/env bash
# ADR-077 D8a: a release is built once. Web Image output is not byte-identical across builds, and no native image or jar
# is reproducible either, so building again for a tag that already has a GitHub release would make different bytes
# from those already attached (and perhaps on npm). Every job that builds a release file runs this first, before
# building: a whole-workflow re-run, or a targeted re-run of one job after the release exists, then builds nothing.
# A retry of a failed build before the release exists still builds, and "Re-run failed jobs" after it exists re-runs
# only the jobs that failed. Read-only token: it sees published releases; this workflow never makes drafts
# (github-release, with its write token, refuses one).
#
#   release-guard.sh <version>        environment: GH_TOKEN, GITHUB_REPOSITORY
set -euo pipefail
version=$1
: "${GITHUB_REPOSITORY:?}"
err=$(mktemp)
# Every page must be a JSON array (each one marked PAGE) and every release must carry a non-empty string tag_name: a
# malformed answer, or an empty one (HTTP 204, an empty body), is not "no release" (R23-C29, R23-C36). An empty array
# is a valid answer: no release yet.
if ! answer=$(gh api --paginate "repos/${GITHUB_REPOSITORY}/releases?per_page=100" \
    --jq "if type != \"array\" then \"INVALID\" else \"PAGE\", (.[] | if (.tag_name | type) == \"string\"
      and .tag_name != \"\" then select(.tag_name == \"v${version}\") | \"RELEASE \\(.id)\" else \"INVALID\" end) end" \
    2> "$err"); then
  cat "$err" >&2
  echo "::error::Could not read the GitHub releases; refusing to build without knowing." >&2
  exit 1
fi
releases=$(grep -v '^PAGE$' <<< "$answer" || true)
if ! grep -q '^PAGE$' <<< "$answer" || { [[ -n "$releases" ]] && grep -qv '^RELEASE ' <<< "$releases"; }; then
  echo "::error::The GitHub release list is malformed; refusing to build without knowing." >&2
  exit 1
fi
if [[ -z "$releases" ]]; then
  echo "No GitHub release of v${version} yet: building."
  exit 0
fi
ids=${releases//RELEASE /}
echo "::error::v${version} already has a GitHub release (id ${ids//$'\n'/, }), and a release is built once (ADR-077" \
  "D8a): rebuilding would make different bytes. A build job cannot be re-run once the release exists; if another job" \
  "failed, re-run that one; otherwise see 'Recovery' in adr/evidence/077-m4/RUNBOOK.md (the version may have to be" \
  "abandoned)." >&2
exit 1
