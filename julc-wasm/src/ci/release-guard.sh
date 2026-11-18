#!/usr/bin/env bash
# ADR-077 D8a: a release is built once. Web Image output is not byte-identical across builds, and no native image or jar
# is reproducible either, so building again for a tag that already has a GitHub release would make different bytes
# from those already attached (and perhaps on npm). Every job that builds a release file runs this first, before
# building: a whole-workflow re-run, or a targeted re-run of one job after the release exists, then builds nothing.
# A retry of a failed build before the release exists still builds. Read-only token: it sees published releases;
# this workflow never makes drafts (github-release, with its write token, refuses one).
#
#   release-guard.sh <version>        environment: GH_TOKEN, GITHUB_REPOSITORY
set -euo pipefail
version=$1
: "${GITHUB_REPOSITORY:?}"
err=$(mktemp)
if ! releases=$(gh api --paginate "repos/${GITHUB_REPOSITORY}/releases?per_page=100" \
    --jq ".[] | select(.tag_name == \"v${version}\") | \"\(.id) \(.assets | length)\"" 2> "$err"); then
  cat "$err" >&2
  echo "::error::Could not read the GitHub releases; refusing to build without knowing." >&2
  exit 1
fi
if [[ -z "$releases" ]]; then
  echo "No GitHub release of v${version} yet: building."
  exit 0
fi
echo "::error::v${version} already has a GitHub release (id and asset count: ${releases//$'\n'/; })." \
  "A release is built once (ADR-077 D8a): rebuilding would make different bytes. Use 'Re-run failed jobs' in the" \
  "original run only before the release exists; afterwards see 'Recovery' in adr/evidence/077-m4/RUNBOOK.md." >&2
exit 1
