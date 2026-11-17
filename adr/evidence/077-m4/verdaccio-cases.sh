#!/usr/bin/env bash
# ADR-077 M4: npm-publish.mjs and npm-verify.mjs with npm 11.21.1 against Verdaccio 6.0.4 on 127.0.0.1:48731,
# on the real M3 tarballs (package.json version rewritten to 0.1.0-pre22). Verdaccio has no provenance support.
#   verdaccio-cases.sh <scratch dir> <julc-wasm/src/ci> V1|V2|V3|V4|V5
# <scratch dir> holds npm11/ (npm 11.21.1 installed with --prefix), verdaccio.npmrc (a Verdaccio user's token,
# fetch-retries=0), real/release/ (the three tarballs, two archives and julc-npm-0.1.0-pre22-SHA256SUMS.txt) and
# real/other/ (the same with a rebuilt client). Verdaccio's config has no uplinks, publish: $authenticated, and
# max_body_size 10mb (V1: the full engine is refused) or 100mb (the rest).
set -uo pipefail
S=$1; CI=$2; V=0.1.0-pre22; REG=http://127.0.0.1:48731
NPM=$S/npm11/node_modules/.bin/npm
export NPM_CONFIG_USERCONFIG=$S/verdaccio.npmrc NPM_CONFIG_CACHE=$S/real/cache-v
publish() { node "$CI/npm-publish.mjs" --dir "$1" --version $V --mode publish --registry $REG --no-provenance \
  --visibility-timeout 10 --npm "$NPM"; }
show() { grep -E '^(skip|published|already|output|ok|note)|::error|^  - |cannot publish|E413|E409|E403' | sed "s#$S#\$S#g"; }
versions() { for n in wasm-client julc-vm-wasm julc-wasm; do
  printf '  registry @julclang/%s: ' $n
  curl -s $REG/@julclang%2f$n | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const p=JSON.parse(s);console.log(p.versions?JSON.stringify(Object.keys(p.versions))+" "+JSON.stringify(p["dist-tags"]):p.error)})'
done; }
echo "== $3"
case $3 in
  V1) publish $S/real/release 2>&1 | show; echo "exit=${PIPESTATUS[0]}"; versions ;;
  V2) publish $S/real/release 2>&1 | show; echo "exit=${PIPESTATUS[0]}"; versions ;;
  V3) "$NPM" publish $S/real/release/julclang-wasm-client-$V.tgz --tag latest --registry $REG 2>&1 | grep -E 'error|cannot' ;;
  V4) node "$CI/npm-verify.mjs" --dir $S/real/release --version $V --registry $REG --no-provenance --work $S/real/verify-$RANDOM \
        --npm "$NPM" 2>&1 | show; echo "exit=${PIPESTATUS[0]}" ;;
  V5) publish $S/real/other 2>&1 | show; echo "exit=${PIPESTATUS[0]}" ;;
esac
