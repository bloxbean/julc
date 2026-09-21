# ADR-052: pre17 profile freeze, hash stability, and node regression gates

**Date:** 2026-09-21
**Status:** Proposed for maintainer review; test infrastructure implemented on `fix/pre17-release-gates`
**Tracker:** #121
**Governing contracts:** ADR-031 (target legality), ADR-032 including #153's cost-profile
amendment, ADR-045/046/047 (native constants/MSM), ADR-050/051 (correctness exceptions).

## Context and current behavior

The P4 optimizations and loop/switch correctness fixes are merged at
`539c3f157ca74100cc46fa8bf88e053b7660d94d`. The default is `pv11-safe`, the only
compiler target is `plutus-v3-pv11-uplc-1.1.0`, and `pv11-costed` adds structural
O9 promotion without reading numeric costs. Preview implementation changed eligible
script hashes repeatedly. Completing P4 does not itself define a freeze or certify a release.

Two existing opt-in release tests execute Haskell `cardano-cli` via `docker exec`
against a developer-owned DevKit. They do not download/start a node. Their backend
negative-case assertions expected adapter diagnostics (e.g. `Error evaluated`), whereas
another supported deployment returns the Haskell cause (`Caused by: (error)`). The
same invalid transaction is rejected; a change of diagnostic transport is not a change
of script semantics. Native DevKit installations previously needed an ad hoc launcher.

ADR-045/046/047 require node evidence for Value/Array constants and BLS/MSM. Existing
List/Pair tests do not exercise these features. Equality between two evaluators alone
also cannot detect a shared cost regression: both could change together.

## Goals / non-goals

- Freeze the existing rollout contracts for pre17 and make permitted hash changes explicit.
- Exercise the actual compiler output and serialized constants on a PV11 Haskell node.
- Independently detect artifact drift, pinned-model budget drift, and evaluator disagreement.
- Let developers use an already-running Docker or native DevKit without personal paths.
- Reuse existing compiler, VM, testkit, and transaction-building APIs.

No compiler pass, source semantics, ledger encoding, default, or public Java API changes.
No new optimization-profile implementation, live cost-based compilation, node acquisition,
network restart/reset, Scalus certification, mainnet safety claim, or release publication.

## Invariants

1. Production lowering remains byte-identical to the base; tests/docs cannot change scripts.
2. Compilation inputs include compiler and library versions, source, parameters, target,
   level, rule switches and source-map options. Identical inputs must be deterministic.
3. Profile freeze is not a promise to retain a known miscompile. Correctness exceptions
   require diagnosis, semantic/failure regressions, independent review and migration notes.
4. Evaluation costs are separate: network parameter/price changes can affect costs/fees
   without changing script bytes or hashes. Compilation never silently follows those values.
5. Every node gate checks exact protocol 11.0 and the complete pinned V3 parameter array.
   A changed network model fails the gate rather than silently replacing expectations.
6. Only original valid signed transaction bytes are submitted. Invalid variants are
   evaluated but never submitted; budget failure is not evidence of the intended rejection.
7. Direct-node tasks are opt-in, never up-to-date/cache successes, and fail if cases skip.
8. Test subprocesses only execute the selected existing CLI; no shell, download or lifecycle
   command. Ambiguous/incomplete native/Docker configuration fails before account funding.

## Decision

### Frozen rollout and hash contract

Upon acceptance, the pre17 profile freeze starts from the merged compiler at `539c3f15`.
The release evidence must additionally name the actual release commit and toolchain.
The frozen scope includes pass order, eligibility, library lowering and output-affecting
heuristics for programs already supported by this baseline, not merely a list of rule IDs.

| Level | Frozen behavior |
|---|---|
| `none` | No optimizer rewrites; mandatory lowering, validation and correctness fixes still apply |
| `baseline` | Existing pre-PV11-optimization pass family, subject to announced correctness fixes |
| `pv11-safe` (default) | Baseline plus bounded O1/O2/O3/O4/O5/O8/O10/O13/O14/O15 |
| `pv11-costed` (opt-in) | Safe plus ADR-043 structural O9; no numeric compiler cost dependency |

O7 native Values and O11 typed BLS/MSM are language/API lowerings, not newly automatic
rewrites. O6 Unit Case is rejected; O12 ordinary pow/mod recognition and BLS chain fusion
remain absent. The PIR order stays Value folding → Array folding → Value/projection
sharing → O9 promotion → pair destructuring; generator and UPLC passes remain as governed
by their ADRs. Source-map compilation is a distinct configuration, not exempt from review.

After the freeze, a performance-only change must not silently change existing programs'
bytes under these configurations. Put new byte-changing optimizations behind a separately
named/versioned opt-in configuration with its own design decision; adding a protocol target
is not a way to disguise an optimization revision. New APIs/source forms may be additive
only if existing supported programs retain their bytes. Byte-neutral refactors need evidence.

A correctness fix may change bytes at any level, including `none` and `baseline`, or
reject formerly accepted unsafe source. It must document the affected shape, old/new
semantics, hashes/sizes/budgets where applicable, failure/trace behavior and migration.
Do not preserve incorrect output merely to satisfy a golden. Golden updates are reviewed
changes, never an automatic approval mechanism. Exact reproduction of a historical artifact
requires the original compiler and dependencies, not just selecting `baseline` on a new build.
Existing deployed script bytes do not change when a compiler release changes.

This supersedes overly broad historical statements in ADR-031/032 about baseline byte
compatibility across correctness fixes, not their legality or semantic contracts.

### Portable direct-node testing

Select exactly one mode:

- `JULC_E2E_CARDANO_CONTAINER`: retain the existing DevKit container layout and `docker exec`.
- `JULC_E2E_CARDANO_CLI` plus `JULC_E2E_CARDANO_SOCKET`: explicit executable and socket for
  a native POSIX installation. No personal path or automatic PATH search is committed.

Both modes use the existing testnet-magic-42 test environment. HTTP backend/admin and
CLI/socket must refer to the same developer network. CLI budget evaluation alone is not
ledger acceptance: tests separately submit and confirm the original transaction.
The shared negative-diagnostic matcher requires `EvaluationFailure` plus either the
existing adapter category or the corresponding Haskell cause. Transport failures, wrong
causes and budget exhaustion do not match. Pure configuration/matcher tests run in `check`.

### Native artifact and cost regressions

One source/input corpus is reused by normal-build regressions and an explicit on-chain task:

- Value: embedded literal requirement with runtime containment; zero/exact quantity,
  non-contained quantity, negative containment and wrong Data kind.
- Array: literal table with runtime indexing; first/last, wrong expected result,
  negative/out-of-range index and wrong Data kind.
- G1 and G2: native point-list constants and explicit MSM compared with a separate
  scalar-multiply/add expression; zero/nonzero scalars, unequal result, scalar overflow
  and wrong Data kind. These are the empty **typed point-list constants** produced by
  source lowering, not a claim that source embeds individual nonempty point literals.

Each runs at baseline, safe and costed levels. Structural assertions require the intended
constant/builtin to survive FLAT decoding, so folding the entire fixture to `true` cannot
satisfy the gate. Repeat compilation and FLAT round trips must preserve bytes.

`native-constants-pv11.properties` pins hash, FLAT size, CPU and memory for every positive
scenario under `plutus-v3-pv11-costs-v1`. Offline evaluation uses a fixed spending context;
the node task checks these same budgets on the transaction, backend and direct Haskell
results, then confirms spends. It rejects bad variants on all three evaluators. Parameter
drift requires review and a new immutable evaluation snapshot where appropriate, not a
compiler-profile change or an unexplained golden refresh.

## Alternatives

- HTTP-only evaluation: retains useful integration coverage but cannot independently check
  the direct Haskell CLI; keep both, as the existing gates do.
- Always launch Docker/download a pinned node: adds lifecycle ownership and can disrupt a
  user's network. The developer supplies the environment; gates validate its parameters.
- Personal native paths or silently preferring one configured mode: nonportable/ambiguous.
- Accept any negative response: an HTTP failure or exhausted budget is not the expected fault.
- Parity-only budgets: misses drift shared by Java and Haskell; retain absolute pinned rows.
- Freeze forever including wrong results: rejected; semantics outrank hashes.
- Refresh snapshots whenever tests fail: defeats regression detection and review.

## Affected stages/modules and compatibility

Only `julc-e2e-tests`, ADRs and the docs site/dependencies change. Compiler/PIR/UPLC stages,
serialization, ledger APIs and VM production code do not. Existing Docker invocation remains
valid; native invocation is additive. Ordinary backend-only E2E tests remain separately opt-in.
Normal builds gain pure local harness and semantic/hash/budget regressions, never node calls.
Docs dependency remediation is a build-tool change, with separate advisory/build evidence.

## Risks and verification

- Environment mismatch: require pinned protocol/model, compare script hashes and confirm spends.
- Brittle diagnostic strings: support two observed formats, pin non-matches; do not erase category.
- Weak golden fixtures: exercise runtime inputs, constants and MSM, positive and negative paths.
- Misinterpreting cost pins as universal fees: document model dependence and live transaction sizing.
- Overclaiming freeze: compatibility applies to unchanged source/configuration inputs; no global proof.

Milestones: launcher + pure tests; native fixtures + offline pins; independent node confirmation;
policy/docs and advisory remediation; full build/docs/locked artifacts; reviewed single PR.
Validation and exact node provenance/transactions are recorded in
`evidence/052-pre17-release-gates.md`. No automatic release completion is inferred from merge.

## Open questions

Future optimization configuration naming is deferred until a concrete new rule needs it.
The freeze and correctness exception need maintainer acceptance in this PR. Deferred compiler
research and Scalus ledger certification remain outside pre17's selected gates.
