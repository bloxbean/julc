# ADR-052 release regression evidence

Date: 2026-09-21. Compiler baseline:
`539c3f157ca74100cc46fa8bf88e053b7660d94d` (merged #167).
The `fix/pre17-release-gates` changes are tests, build configuration and documentation;
compiler, VM, serialization, stdlib and ledger production code are unchanged.
This is development-snapshot evidence, not release publication or production certification.

## Portable launcher and native-constant evidence

Fresh run of the checked-in test implementation (not the earlier audit's throwaway
launcher/diagnostic patch):

```sh
./gradlew :julc-e2e-tests:onChainHarnessTest \
  :julc-e2e-tests:listCaseOnChainTest \
  :julc-e2e-tests:switchPairCaseOnChainTest \
  :julc-e2e-tests:nativeConstantsOnChainTest -Pe2e --continue
```

Native CLI/socket variables were supplied as described in the E2E README. No personal
paths are needed by the implementation. No node/container was downloaded, started,
stopped or reset. The admin API funded test accounts and valid transactions were submitted.

Environment:

- macOS, OpenJDK 25.0.2, repository Gradle wrapper 9.2.0.
- Native Haskell `cardano-node 11.0.1`, `cardano-cli 11.0.0.0`, GHC 9.6;
  both report revision `97036a66bcf8c89f687ae57a048eecc0389977ef`.
- Testnet magic 42, active protocol **11.0**, 350 V3 cost parameters. Confirmed by
  direct CLI protocol-parameter query and the tests' exact backend parameter checks.
  A latest-block header advertising 12.0 is not used to infer active protocol.
- Immutable evaluation snapshot `plutus-v3-pv11-costs-v1`, parameter SHA-256
  `40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`.

| Gate | Parameterized tests | Confirmed spends | Invalid variants rejected by all three evaluators |
|---|---:|---:|---:|
| List Case, baseline/safe | 2 | 8 | 8 |
| Switch Pair Case, baseline/safe | 2 | 4 | 8 |
| Value/Array/G1/G2, baseline/safe/costed | 12 | 24 | 39 |
| Total direct-node | 16 | 36 | 55 |

All passed with zero failures/errors/skips. The separate offline harness task passed
16 tests (four launcher/diagnostic tests plus twelve shared-fixture cases).
Java, HTTP backend and direct Haskell costs agree exactly, including the signed witness
budgets. Native fixtures also match the absolute hash/size/CPU/memory pins in
[`native-constants-pv11.properties`](../../julc-e2e-tests/src/test/resources/native-constants-pv11.properties).
This prevents a shared evaluator drift from being mistaken for stability.

Invalid cases include intended validator false results, malformed integer Data, negative
Value containment, negative/out-of-range Array indices, and oversized MSM scalars. Tests
check the expected error category, not merely any failure. Invalid witness variants are
never submitted; only the original valid signed bytes are submitted and confirmed.

Structural checks require embedded Value/Array constants and runtime Array indexing,
or typed G1/G2 list constants and MSM builtins, in FLAT-decoded output. The BLS constants
are the empty typed point lists emitted by source lowering, not individual embedded point
literals. Valid fixtures exercise zero/nonzero values and first/last Array entries.
Repeated compilation and FLAT round trips are byte-identical.

[Transaction references and rejected-case counts](052-node-transactions.txt) contain the
actual lock/spend hashes. These belong to a disposable developer network, not a public
explorer. Docker argument compatibility is unit-tested; this fresh node run used native
mode, not a second Docker deployment.

## Repository and artifact validation

Fresh `./gradlew build --rerun-tasks -PskipSigning=true`: **successful**, all 220 tasks
executed. XML totals: **11,172 cases, zero failures/errors, 530 skipped**. This excludes
the separately counted opt-in node tests. It includes compiler 1,665, compiler backend
task 71, stdlib 411, testkit 193, in-repo examples 81 and the new offline harness 16,
all without skips. The skips are Java/Truffle conformance availability (262 each) and
external AI integrations (analysis 2, analyzer CLI 4). Six environment-dependent census/
CLI cases skipped in the earlier isolated audit ran successfully with the live sibling
examples present; that is not a production-code change.

Fresh `./gradlew :julc-e2e-tests:test -Pe2e`: **10 tests, zero failures/errors/skips**,
including confirmed spending/minting transactions and Java evaluator integration.

`verification/blaster/scripts/prepare-artifacts.sh` without `--update-lock`: **exit 0**;
all seven committed artifact locks and compiled-code files reproduced unchanged. This
is exact-artifact baseline compatibility, not a fresh Lean proof run or formal coverage
of the safe optimizer. No locks were refreshed. `git diff --check` is clean.

## Documentation advisory triage

The original locked tree reported **12 vulnerable packages** (not twelve distinct CVEs):
one critical, eight high, one moderate, two low. They were Astro, devalue, esbuild,
js-yaml, nanoid, postcss, postcss-selector-parser, sharp, smol-toml, Svelte, SVGO and Vite.
All were resolved by a compatible dependency/lockfile refresh; no advisories were ignored.

| Surface | Applicability and disposition |
|---|---|
| Astro/sharp image optimization | Build-time image decoding remains relevant even for static hosting. Upgrade Astro to 7.3.3 and sharp to 0.35.4, beyond the fixes in the published AVIF/libheif advisory. |
| Astro/Svelte rendering and serialization, SVGO | Source/content are reviewed repository inputs, but static output is not intrinsically immune to generated XSS. Update rendering/sanitization dependencies rather than declaring the findings harmless. |
| Server islands, server routing, SSR request handling | No SSR adapter or deployed Node server in this site's GitHub Pages configuration. Upgrade nonetheless; no blanket claim about future server-side usage. |
| Vite/esbuild dev server | Development-only exposure, including Windows-specific findings. Upgrade; keep local dev servers off untrusted networks. |
| YAML/TOML/CSS/selectors, devalue/nanoid | Build/parser/serialization dependency exposure depends on supplied input. Upgrade the locked transitive tree; do not suppress findings based only on current trusted content. |

Primary references: [Astro AVIF advisory](https://github.com/withastro/astro/security/advisories/GHSA-26w7-cxv4-gfx2)
and [Astro 7 migration guide](https://docs.astro.build/en/guides/upgrade-to/v7/).
The review checks `astro.config.mjs`, content configuration, custom integrations/components
and the Pages workflow; it is not an exploit test or proof of the absence of vulnerabilities.

Resolved key versions: Astro 7.3.3, Starlight 0.42.2, Astro/Svelte integration 9.0.1,
sharp 0.35.4, Svelte 5.57.1, Vite 8.3.0. Node >=22.19.0 is now documented and declared
because the resolved undici 8.10.2 requires it; CI's Node 22 selection obtains a current patch.

Fresh validation with Node 22.19.0: `npm ci`, `npm run build`, `npm audit` all passed;
**34 pages**, generated AI catalog with 47 examples, **zero reported advisories**.
No force/legacy-peer flags or dependency overrides were used. This is a point-in-time
audit result, not a future guarantee. Catalog generation used the sibling examples at
`113dedd6e29b34af412103bd70f0c24f7d6f7504`.

Generated root-relative HTML asset/link checks found one pre-existing missing transcript
JSON link (`/ai/transcripts/closed-loop-walkthrough.json`); neither its referring page nor
the missing asset was introduced by this change. The new policy/release-note/sidebar
links and generated assets resolve. This unrelated content gap is not claimed fixed.

## Earlier final-evidence audit, kept separate from this branch's runs

An isolated audit of exact compiler baseline `539c3f15` inspected focused semantic,
failure/trace, determinism, size/budget and cross-backend evidence for the merged changes:

| Area | Focused evidence reviewed |
|---|---|
| List/Pair Case (ADR-034/036/038) | O3ListCaseLoweringTest, pair/backend suites, ListCaseOnChainTest, SwitchPairCaseOnChainTest |
| Lossless maps and serialiseData | MapDecoderProgramFidelityTest, SerialiseDataLoweringTest, SerialiseDataSourceEvalTest, builtin metadata tests |
| Conditional yield and loop/switch joins | ConditionalYieldLoweringTest, LoopConditionalLocalTest, IfLoopContinuationTest, locked controlled-mint fixtures |
| Library discovery and rejected Unit Case | LibraryDiscoveryCleanupTest/resolver tests, O6SequencingCensusTest and documented no-rule decision |
| O5/O8/O9/O15 | Integer-case, Value-sharing, list-promotion and projection-sharing suites and per-change ADR benchmark evidence |
| O14/O10/O11 | Literal-fold/semantics/BLS suites, benchmarks and FLAT round trips; missing node evidence now supplied above |
| Bool/String field decoding | SwitchFieldDecodeTest, malformed/unused selected fields, unselected-arm laziness and opaque-type rejection |
| Cost-profile decoupling | Compiler/options/report tests; external costed build without a compiler cost profile |

That audit's fresh build had 11,156 tests, zero failures/errors and 536 documented skips.
It also checked 41 validators from the **committed**, isolated external examples revision
above: two clean safe-profile recompilations had identical script bytes/hashes/sizes.
Full external tests: 418 tests, one DevKit funding HTTP 500 before script execution,
11 skips; the unchanged escrow rerun passed all three tests and confirmed transactions.
The separate costed off-chain run passed 363 tests with 11 skips. These are historical
baseline runs, not fresh branch runs or the reviewer's larger dirty 58-validator corpus.
The old `julc-helloworld` pre11 coordinates do not establish current compatibility.

The audit's initial direct-node tests failed diagnostic-text assertions. Subsequent
throwaway adaptations were probes only; the fresh checked-in gates above replace that
qualified evidence. Historical ADR evidence stays historical; aggregate green tests do
not constitute a proof of semantic preservation. Scalus compatibility does not override
ADR-033's fail-closed ledger-certification support matrix.

## Release disposition

ADR-052's freeze/correctness-exception policy requires maintainer acceptance in this PR.
Choose and validate the actual non-SNAPSHOT release commit/version through the normal
release process after review. No release tag, signing, staging, remote artifact publication,
new Scalus certification, or automatic closure of #121 is performed by this change.
