# ADR-030: Verification Refresh, Build Integration, AI-Agent Support and UAL Export Roadmap

- **Status:** Proposed; R.1 implemented on this branch
- **Date:** 2026-11-14
- **Related:**
  [ADR-001 — IOG Blaster Verification Strategy](001-iog-blaster-verification-strategy.md),
  [ADR-009 — Verification Product Roadmap](009-verification-product-roadmap.md),
  [ADR-014 — Post-C.7 Verification Hardening Roadmap](014-post-c7-verification-hardening-roadmap.md),
  [ADR-016 — Typed Verification DSL](016-typed-verification-dsl-and-profile-catalog.md),
  [ADR-028 — E.5 State-Machine Experiment (rejected)](028-milestone-e5-exact-artifact-state-machine-experiment.md),
  [ADR-029 — E.6 Stable Verification DSL](029-milestone-e6-stable-verification-dsl-public-api.md)

## Context

Verification work paused after E.6 (August 2026). Since then the compiler
changed substantially: the javac frontend replaced JavaParser (ADR-068), the
PV11 optimizer waves added passes O5–O15 (ADR-041 to ADR-047), and pattern
scopes and checked casts changed lowering (ADR-069, ADR-070). The upstream
Blaster stack also moved.

### Current JuLC state (main, 2026-11-14)

- C.1–C.7, strict data boundaries (ADR-015), purpose-indexed blueprints
  (ADR-017), the typed DSL E.1–E.4l and stable API v1 (ADR-029) are merged.
  E.5 was rejected at its calibration gate (ADR-028).
- Only `verification/blaster` runs in CI (`blaster-verification.yml`). Its
  fixtures compile at `--optimization baseline`. It is green on recent main
  merges.
- Nineteen other evidence suites (`verification/{c2,c3,c5,c6,c7,e3,e4a,e4b,
  e4c,e4d,e4f,e4g,e4h,e4i,e4j,e4k,e4l,p4,strict-boundaries}`) regenerate their
  artifacts from the current compiler and are not run in CI. They have not
  been re-run as a set since the javac migration.
- The annotated workflow (`AnnotatedVerificationWorkflow`) compiles with
  default options, i.e. `OptimizationLevel.DEFAULT = PV11_SAFE`, and `julc
  verify` has no option to choose another level.
- `PV11_SAFE` became the default on 2026-08-29 (`69540a2a`), six days after
  E.6 merged. Every C.5–E.4l result was therefore produced at `baseline`.
  ADR-032 recorded that a `PV11_SAFE` smoke proof "remained compute-bound
  beyond ten minutes in Lean preprocessing", pinned `verification/blaster` to
  `baseline`, and stated that Blaster results "must not be cited as formal
  coverage of PV11-safe". The per-milestone suites were not pinned (see the
  R.1 findings).
- The pinned stack is Lean-blaster `083bae79`, PlutusCoreBlaster `7cf5a78c`,
  CardanoLedgerApiBlaster `5dab3c43`, Lean 4.24.0 and Z3 4.15.2. These values
  are repeated in `VerificationProjectGenerator` (`BLASTER_REV`,
  `PLUTUS_CORE_REV`, `LEDGER_API_REV`, `z3Version`), `Z3Provisioner`,
  `LocalVerificationBackend`, `DockerVerificationBackend`, the verification
  `Dockerfile`, `verification/blaster/scripts/{bootstrap-z3.sh,verify.sh}`,
  `verification/blaster/config/verification-profile.json` and several READMEs.
- The supported builtin set is FLAT tags 0–88 and 92–93. It is defined three
  times: `VerificationRunner.supportedBuiltins()`,
  `VerificationProjectGenerator.supportedBuiltins()` and
  `verification/blaster/config/blaster-builtins.txt`. Artifacts with tags
  89–91 (arrays) or 94–100 (native Value) are `COULD-NOT-EVALUATE`.
- ADR-014 H.3 (stdlib helper conformance), H.4 (PV11 builtins) and H.6
  (compiler certification) have not started.

### Current developer workflow

Verification is reachable only through the `julc` CLI.

- **Annotations.** `@RequiresSigner`, `@ControlledMint` and `@PreservesValue`
  with `@Monotonic` sit on the validator class. `julc verify . --validator X`
  builds the artifact, generates the theorem and checks it.
- **Typed DSL.** Three manual steps:
  1. `julc verify dsl-init` writes a contract-specific model class;
  2. the developer compiles the model and a `VerificationSpecification` class
     with `javac`;
  3. `julc verify dsl` runs it, with required `--spec-class` and
     `--spec-classpath` options and a `--source` option.

  The model is a generated file, so it goes stale when the contract changes.
- **Raw Lean.** `julc verify init` writes a pinned workspace with a
  deliberately false placeholder property that an expert replaces by hand.
- The Gradle plugin registers only `compileJulc` and `bundleJulcSources`. The
  annotation processor does not read verification annotations, so an invalid
  path such as `@RequiresSigner("datum.ownr")` is reported only when
  `julc verify` runs. The testkit and `julc-testkit-jqwik` have no
  verification hooks.
- The certificate (`verification-result.json`) is JSON. Console output is text,
  and counterexamples are printed as Lean terms.

### Upstream state (surveyed 2026-11-14 with `gh api`)

All three pins are ancestors of each repository's `main`.

- **Lean-blaster** is 19 commits ahead (`0df4a469`). The minimum Z3 is now
  5.4.0 (`Blaster/Smt/Env.lean`, `minZ3Version := "5.4.0"`, from `a0691976`).
  Z3 4.15.2 is rejected. Lean stays at 4.24.0; a Lean 4.35 migration exists
  only as unmerged PRs. Proof reconstruction is not on `main`; a Valid goal is
  still closed by an axiom, and proof production is currently disabled
  (`1fe209eb`, #155). #155 also contains optimizer performance work and
  several solver soundness fixes.
- **PlutusCoreBlaster** is 21 commits ahead (`79fce4ac`).
  - The FLAT decoder, evaluator, cost model and SMT tests now cover tags
    89–91 and 94–100 (`69e2caa9` arrays, 2026-11-12; `79fce4ac` Value,
    2026-11-13). Arrays are modelled as lists for now.
  - `State.BudgetExhausted` distinguishes fuel exhaustion from evaluation
    failure (`2396bfc7`, #78).
  - BLS operand order was fixed (`8f76ba59`).
  - `Term.Lam` no longer carries a binder name (`afe08f80`, breaking).
  - A blueprint and assurance importer, `#verify_blueprint`, was added
    (`1c23de8f`).
- **CardanoLedgerApiBlaster** is 8 commits ahead (`3dd048e3`).
  - About 20 type aliases changed from `abbrev` to `def`, each with explicit
    instances (`7b743afc`). The stated reason is that `abbrev` inferred the
    wrong `IsData` instance when two aliases share a base type.
  - ScriptPurpose ordering was fixed (`6908073b`). Before the fix, any context
    with both a spending and a minting redeemer was unsatisfiable, so theorems
    over such contexts held vacuously.
  - V1/V2 datum conditions the ledger does not enforce were removed
    (`b5596bc8`).
- **Universal Annotation Language (UAL) 1.0.0** was released 2026-10-27
  (`input-output-hk/UniversalAnnotationLanguage`).
  - A producer emits a CIP-57 blueprint in a draft "compiled-interface"
    dialect (single-CBOR FLAT), a detached `assurance.json`
    (assurance-v2), one digest-bound checking context per claim, and an
    environment manifest.
  - Properties are complete Lean propositions. Script runtime inputs are raw
    `Data`, and domain premises must be written in the proposition.
  - Function targets (`kind: function`) describe separately compiled helpers.
  - Evidence is written only after a check runs. The `cardano-blaster` CLI
    (`cardano-blaster check plutus.json assurance.json`) re-checks a bundle
    with a bundled Z3.
  - Plinth is the only reference producer.
  - IOG's roadmap item "BL.03 Language Integrations" lists Aiken, Pebble,
    Scalus and Futura (due 2027-03). JuLC is not listed.

These upstream facts are from a read-only survey. None of them has been
tested against JuLC's generated Lean.

## Goals

1. Re-establish that every committed evidence suite reproduces on current
   main.
2. Move to the current Blaster stack without weakening any fail-closed rule,
   and re-check results that the upstream fixes may have affected.
3. Close the PV11 builtin gap (ADR-014 H.4) so that `PV11_COSTED` output and
   native-Value code can be verified.
4. Let third parties re-check JuLC verification claims with IOG's tooling, by
   exporting UAL 1.0 bundles.
5. Start reducing trust in the optimizer through per-artifact translation
   validation (first step of ADR-014 H.6).
6. Produce reusable conformance evidence for selected stdlib helpers
   (ADR-014 H.3).
7. Make verification part of the normal build: Gradle tasks, compile-time
   checking of verification annotations, and testkit integration.
8. Reduce the typed-DSL workflow to writing one specification class, with
   the model kept in sync with the contract automatically.
9. Make the DSL workflow usable by AI coding agents, with checks that stop an
   agent-written property from being weaker than intended without anyone
   noticing.

## Non-goals

- Lean kernel proof reconstruction. It is upstream work (IOG roadmap BL.07,
  due 2027-06) and is not on Lean-blaster `main`.
- State-machine or temporal verification. ADR-028's revival fence still
  applies.
- Making Lean-in-comment annotations the Java authoring surface. The typed
  DSL and annotations remain the user interface.
- Replacing JuLC's `verification-result.json` certificate with UAL.
- Changing the default `plutus.json` format or the CIP-57 double-CBOR
  decision. Both are open questions below.
- Any change to compiled UPLC bytes. This roadmap stays outside lowering.
- Counting an AI-written or tool-generated property as reviewed. Only a
  human approval counts (invariant 10).
- A second, concrete interpretation of DSL properties used as proof.
  Property-based testing of a property (R.7) is labelled as testing only.

## Explicit invariants

1. Unknown, unsupported, timed-out, under-fuelled, vacuous or tampered work is
   never success.
2. Every result is bound to the exact compiled artifact. `SMT-VALID` remains
   scoped to the named property, artifact, model, pins and bounds.
3. A builtin is supported only when its decoding, concrete evaluation and
   symbolic translation all have evidence. Successful decoding alone is not
   enough.
4. Verification annotations and exports do not change compiled bytes. The
   existing annotation-neutrality tests stay green.
5. No producer step writes evidence. A UAL `assurance.json` carries evidence
   only after a check has actually run.
6. Every exported claim states its trusted components (Blaster translation,
   optimizer, CEK model, Z3, ledger model) and makes no kernel-proof claim.
7. Historical certificates are provenance. They are never upgraded, relabelled
   or replayed against a different stack.
8. One source of truth per pinned value: the stack revisions, Z3 version and
   supported builtin set are each defined once.
9. Build integration does not change outcomes. The Gradle task, the CLI and
   any test hook produce the same classification and certificate for the same
   inputs. A cached result is reused only when the artifact hash,
   canonical property hash, pins and bounds all match.
10. A property's review state is explicit. Changing a property's canonical
    form after approval clears its approval, whoever made the change.

## Decision

Verification work resumes with the milestones below. R.1 and R.1b must
finish before R.2, so that a stack change is never blamed for a generator
regression, and R.2 before R.2a and R.3–R.6. After R.2, R.2a, R.3, R.4 and R.5 may
proceed in parallel, with R.2a first in priority. R.6 depends on R.4's
function targets.

```text
R.1 re-baseline --> R.1b refutation fix --> R.2 stack refresh --+--> R.2a PV11 lowering shapes
                                                                +--> R.3 PV11 builtins
                                                                +--> R.4 UAL export --> R.6 helper conformance
                                                                +--> R.5 translation-validation calibration

R.8 DSL workflow --> R.7 build integration --> R.9 AI-agent support
```

R.8 may start alongside R.1, because it changes only the developer workflow
and not the proof pipeline. R.7 builds on R.8's spec discovery. R.9 builds on
both. R.4 and R.9 share the plain-language and JSON renderings.

### R.1: Re-baseline evidence on current main

Run every committed evidence suite on current main with the existing pins,
before any upstream change, so that compiler drift and upstream breakage are
never mixed.

- Run each `verification/*/scripts/verify.sh` with the local backend.
- Classify each failure:
  - expected byte drift from a reviewed compiler change: refresh the artifacts
    with the documented procedure (`prepare-artifacts.sh --update-lock`,
    counterexample rebinding, manifest lock hash) and record which compiler
    change caused it;
  - generator or toolchain breakage: fix it in `julc-cli`/`julc-verification`;
  - a changed proof outcome: stop and investigate as a possible miscompile or
    verification bug before refreshing anything.
- Add a scheduled CI job (weekly, plus manual dispatch) that runs a
  representative set: `c5`, `c7`, `e4b` and `e4h`. `c6`, `e3` and
  `strict-boundaries` join the set when R.1b lands. `verification/blaster` stays
  on pull requests.
- Record a per-suite table (outcome, runtime, refresh reason) in this ADR.

Exit: every suite reproduces its expected classifications on one recorded
main commit, and the scheduled job is green. Status: met for 16 of 20
suites; the other four move to R.1b.

#### R.1 findings (2026-11-14, main `c77ae5f3` plus this branch)

Runs were on a 32-core Linux VM with Temurin 25.0.5, Lean 4.24.0 and Z3
4.15.2, one clone per suite group.

1. **`verification/blaster` reproduces.** `SMT-VALID: all-properties-established`
   in 70 s. Its fixtures compile at `baseline`.
2. **Every other suite stalls at the default level.** Each one's first
   obligation stayed in single-threaded Lean preprocessing at 100% CPU for
   more than 30 minutes, with no Z3 process started. `c5`, the simplest
   `@RequiresSigner` property, ended with the runner's own
   `COULD-NOT-EVALUATE: verification-timeout`. That is the correct
   fail-closed result, but it means `julc verify` on a default build does not
   finish today.
3. **The cause is the `PV11_SAFE` lowering, not artifact size.** Decoding
   e4g's authorized fixture:
   - at the current default it is 681 hex characters, with 18 `case` terms on
     builtin values and no `ifThenElse`;
   - the committed August artifact is 1163 hex characters, with 19
     `ifThenElse` and 38 `delay`.

   The pinned PlutusCoreBlaster parses and evaluates `case` on constants, so
   this is a preprocessing performance cliff, not missing support.
4. **The suites were never pinned to a level.** They regenerate artifacts at
   the current default, so they have silently tested `PV11_SAFE` since
   2026-08-29.

5. **Generated DSL models are now per validator.** Since the javac migration
   (ADR-068, `1f0354c6`), a validator's nested record types are named with
   their class (`AuthorizedRewards.Redeemer`, not `Redeemer`). The contract
   schema hash therefore differs between two validators with the same shape,
   and the DSL validator correctly rejects a model generated for another
   validator: "contract schema hash does not match compiler-owned types".
   The e4c and e4d scripts reused one model for four validators, so they
   failed. A specification shared across validators now needs one generated
   model per validator, which is what R.8's per-run model generation does
   for users.
6. **The `.julc` cache is optional.** It exists only when JuLC provisions Z3
   itself or uses Docker. The c6, c7, e3 and e4a scripts copied it
   unconditionally and failed when Z3 was already on `PATH`.

7. **Two refutation controls no longer complete: a generator regression.**
   e3's `unpaid` and c6's `missing-signer` fixtures are deliberately
   vulnerable and must be `REFUTED` with a counterexample. Both compile to
   the same bytes as in August (`unpaid` SHA-256 prefix `e7f430c883ce`,
   `missing-signer` `f2c70ab021fa`). Both now run Z3 for the runner's full
   30 minutes and end `COULD-NOT-EVALUATE: verification-timeout`, on Linux
   and on macOS.
   - The workspace generated on 2026-08-20, still on the maintainer's
     machine, refutes `unpaid` in 11 s today on macOS with the same pins
     and Z3.
   - Today's generator emits a different property for the same artifact:
     `SecurityProperty.lean` differs by 424 lines, and the obligations are
     now per property. These changes came from E.4b–E.6.
   - ADR-029 already recorded the c6 case ("did not complete within a
     ten-minute review ceiling") as a solver-performance residual.

   The result still fails closed, but a vulnerable contract is reported as
   unevaluated instead of refuted with a counterexample. That makes these
   negative controls useless as evidence, and it would hide a real
   vulnerability from a user in the same way. It has to be fixed, not
   accepted (see R.1b).
8. **Two suites carried pre-E.6 certificate checks.** e3 and e4a looked for
   `validMintingContext/v3-pinned` or `validSpendingContext/v3-pinned` in the
   certificate. Since the schema-1 reset (ADR-029) the certificate records
   the domain per property (`"domain" : "VALID_MINTING_V3_PINNED"`), and the
   hash-bound manifest keeps the assumption list. E.6 confirmed that the
   scripts parse but did not re-run them, so this went unnoticed. The
   verification outcomes themselves were all as expected.

9. **c2's container workspace no longer compiles in reasonable time.** At
   `baseline`, c2's generated `verify init` workspace spent more than 75
   minutes in the `#prep_uplc` step (fuel 20000) without finishing.
   - Its artifact changed since the August workspace (SHA-256 prefix
     `3286d1b18e97` on 2026-08-13, `2f30229c0155` today).
   - That August workspace predates strict data boundaries (ADR-015,
     `bc79531f`) and still completes in 14 s on macOS.
   - c2's fixtures are lists, maps and optionals, so strict container
     decoding is the likely cause. This is not yet confirmed.

Changes made in R.1 as a result:

- `julc verify`, `julc verify dsl` and `julc verify dsl-init` accept
  `--optimization <level>` (default unchanged). The ordinary build and the
  observational compile take it from one value (`VerificationBuild`), so the
  verified bytes are always the built bytes.
- At `pv11-safe` and `pv11-costed`, the commands print a note that Blaster
  preprocessing is very slow at that level and how to verify at `baseline`.
- Every evidence suite builds and verifies with `--optimization baseline`,
  the level its expectations were produced at, as `verification/blaster`
  already does.
- The e4c and e4d scripts generate one model per validator, and the c6, c7,
  e3 and e4a scripts copy the `.julc` cache only when it exists.
- The e3 and e4a scripts check the schema-1 domain field in the
  certificate and the assumption list in the manifest.
- Verifying the level you deploy is the user's responsibility. A
  certificate binds the exact bytes, so verifying `baseline` says nothing
  about a `pv11-safe` deployment.

Per-suite results at `baseline` (branch head plus the R.1 changes, Linux
x86-64 VM, local backend, runner timeout 1800 s per step):

| Suite | Result | Time (s) | Notes |
|---|---|---:|---|
| `blaster` | passes | 70 | CI suite; `SMT-VALID: all-properties-established` |
| `c2` | no result | 4500+ | stopped after 75 min in `#prep_uplc`; finding 9 |
| `c3` | passes | 442 | |
| `c5` | passes | 548 | timed out at `pv11-safe` (finding 2) |
| `c6` | fails | 2477 | authorized `SMT-VALID`; `missing-signer` times out (finding 7) |
| `c7` | passes | 321 | |
| `e3` | fails | 2139 | authorized `SMT-VALID`; `unpaid` times out (finding 7) |
| `e4a` | passes | 170 | after the schema-1 check fix (finding 8) |
| `e4b` | passes | 3863 | |
| `e4c` | passes | 1353 | after the per-validator model fix (finding 5) |
| `e4d` | passes | 1311 | after the per-validator model fix (finding 5) |
| `e4f` | passes | 803 | covers E.4e and E.4f |
| `e4g` | passes | 755 | |
| `e4h` | passes | 785 | |
| `e4i` | passes | 888 | |
| `e4j` | passes | 1272 | |
| `e4k` | passes | 782 | |
| `e4l` | passes | 1014 | |
| `p4` | passes | 74 | |
| `strict-boundaries` | fails | 3727 | runs c3, c5, c6 and c7; fails on c6 (finding 7) |

Sixteen of twenty suites reproduce at `baseline`. The remaining four
depend on R.1b. Every reproduced classification matches the committed
expectation. No outcome changed from `SMT-VALID` to `REFUTED` or the
reverse, so nothing points at a miscompile.

The repository-wide `./gradlew build` passed on the same VM with these
changes: 18,019 tests, 543 skipped, 0 failures.

### R.1b: Restore refutation performance of generated properties

1. Find the generator change that made the e3 `unpaid` refutation stop
   completing. Bisect `julc-cli`/`julc-verification` between 2026-08-20 and
   the ADR-029 merge, regenerating the e3 workspace at each commit; each
   step takes about a minute.
2. Restore a property shape that Z3 refutes quickly, without weakening what
   the property states. Prove in Lean that the new shape is equivalent to
   the current one, or keep the shape the reviewed property used.
3. Make the expected negative controls part of the scheduled CI job, so a
   refutation that stops completing fails CI.

4. Measure c2's container workspace with and without strict container
   decoding. If strict decoding is the cause, keep it (ADR-015 is a
   correctness rule) and make the preprocessing practical instead, for
   example by lowering the init workspace's default fuel or adding a Lean
   lemma for the decoder's shape.

Exit: e3 and c6 reproduce all their classifications at `baseline` within
the runner's default timeout, on Linux and macOS, and c2 completes.

### R.2a: Make PV11 lowering shapes practical in Blaster

The default build must become verifiable. This is now the highest-value
milestone, because without it verification applies only to `baseline`
deployments.

1. Measure the stalled R.1 obligations against the R.2 stack. Lean-blaster
   #155 reworked match reduction and the optimizer, so it may already help.
2. If not, add a Lean lemma that rewrites `case` on a Bool constant to the
   equivalent `ifThenElse` form before preprocessing. The lemma is proved by
   Lean's kernel, so it adds nothing to the trusted base. Propose it
   upstream, or apply it through Blaster's user optimisation rules once those
   land (Lean-blaster PR #472).
3. Repeat for the other PV11 shapes JuLC emits: `case` on integers (O5),
   lists and pairs.

Exit: the c5, c6 and c7 suites reproduce at `pv11-safe` within the runner's
default timeout, and the `pv11-safe` warning is removed.

### R.2: Refresh the Blaster stack

Move to one recorded set of upstream commits and Z3 5.4.0.

- Choose the pins after the batch-6 commits have had time to settle. Record
  the three SHAs, Lean version and Z3 version.
- Define the pins and the Z3 version once in `julc-cli` and derive the
  generator, provisioner, backends and Dockerfile from that definition. The
  evidence scripts read the same values.
- Update `Z3Provisioner` checksums and the Docker image for Z3 5.4.0 on
  `linux/amd64` and `linux/arm64`, and for macOS local use.
- Adapt the generated Lean:
  - ledger aliases that are now `def` (instances, `simp` sets, ByteString and
    list operations on those types);
  - `State.BudgetExhausted` in `CheckedExecution.lean` and the generator's
    copy. Fuel exhaustion must stay distinct from rejection in every result;
  - any reliance on the removed lambda binder name.
- Re-run all suites. Re-check in particular every property whose context has
  both a spending and a minting redeemer, given the corrected ScriptPurpose
  ordering. Record any result that changes.
- Mark historical certificates produced on the old pins as superseded
  provenance in the READMEs. Do not edit them.

Exit: R.1's suite table reproduces on the new stack, or each difference is
explained by a recorded upstream fix and reviewed.

### R.3: Support PV11 batch-6 builtins (ADR-014 H.4)

Widen the supported set to tags 89–91 and 94–100 one builtin at a time.

For each builtin:

- FLAT decoding of well-formed and malformed inputs, including non-canonical
  Value constants;
- concrete evaluation in the Lean model agreeing with JuLC's Java, Truffle and
  Scalus VMs on shared vectors, including failure cases;
- symbolic evidence: one proved, one refuted and one fuel-exhausted case.

Then:

- replace the three builtin-set definitions with one shared definition;
- add fixtures compiled at `PV11_COSTED` (O9 list-to-array) and with native
  Value code (O14 literals, O8 value sharing), each with a positive, a
  vulnerable and a vacuous control.

Arrays are modelled as lists upstream. That is acceptable for halting
behaviour, but cost-dependent claims stay out of scope.

Exit: preflight accepts 0–100, every newly accepted tag has the evidence above,
and a `PV11_COSTED` fixture verifies end to end.

### R.4: Export UAL 1.0 bundles

`julc verify` keeps its own certificate and, on request, also writes a UAL 1.0
bundle that `cardano-blaster` can re-check without JuLC.

**R.4a spike (time-boxed to about a day).** Build a bundle by hand for the C.5
`@RequiresSigner` authorized fixture:

- the compiled-interface blueprint, `assurance.json`, a checking context and an
  environment manifest;
- the claim, its non-vacuity witness and the vulnerable fixture's falsified
  claim as separate properties, plus a step-exhaustion regression (UAL §4).

Check it with `cardano-blaster`. Report what does not round-trip.

**R.4b producer.** If the spike succeeds:

- add a UAL writer to `julc-verification`/`julc-cli`, behind an explicit option
  such as `julc verify --ual-out <dir>`;
- render each typed DSL property as one complete Lean proposition over raw
  `Data`, with the strict-decoding domain premises written explicitly;
- export each non-vacuity witness and each negative control as its own claim,
  so the guarantees JuLC already checks survive the export;
- emit evidence only after the run, with `checkingContextHash` and the
  environment manifest from the same stack JuLC used;
- write the compiled-interface blueprint as a separate file. The default
  `plutus.json` is unchanged.

Lean-in-comment authoring for expert users is not part of R.4. It needs its
own decision.

Exit: for C.5, C.6 and C.7, `cardano-blaster check` reproduces JuLC's verdict
(verified, falsified or inconclusive) from the exported bundle alone, and a
tampered artifact or context is rejected.

### R.5: Translation-validation calibration (ADR-014 H.6, step 1)

Check that, for one fixture, the UPLC before and after a selected optimizer
pass behave the same.

- Relation: for all inputs within a fuel bound, both programs halt with the
  same result, or both fail. Failure text and exact budget are not compared
  (O8 may cost more on some paths).
- Pre-register the calibration as ADR-028 did: one small fixture, one pass
  (O8 value sharing or O5 integer case dispatch), fixed fuel and a fixed
  timeout, and a stop rule if the first equivalence query times out.
- A result is per artifact and per pass. It is not a general proof that the
  pass is correct.

Exit: either one recorded equivalence result with positive and negative
controls (a deliberately broken rewrite must be refuted), or a recorded
rejection with its reason.

### R.6: Stdlib helper conformance (ADR-014 H.3)

Compile selected helpers on their own with `JulcCompiler.compileMethod` and
check them against reviewed Lean specifications, exported as UAL function
targets.

- Candidates: `ContextsLib.signedBy`, `ContextsLib.findOwnInput`,
  continuing-output selection, value comparison, and mint-policy token lookup.
- Each result binds the exact helper bytes, Java method, compiler version,
  specification, pins and fuel, and has positive, malformed and vulnerable
  controls.
- A helper result does not prove a validator that inlines the helper. Linking
  the two needs a validator-level check or R.5-style equivalence.

Exit: at least two helpers have conformance results reproducible with
`cardano-blaster`.

### R.7: Build integration

Verification runs from the normal build, with the CLI's semantics (invariant
9).

- **Gradle.** Add a `julcVerify` task to the plugin with a `julc {
  verification { ... } }` block: backend (`docker`, `local`, `auto`), fuel,
  recursion depth, which validators to verify, and whether `check` depends on
  it (off by default, because a run takes minutes). Certificates go under
  `build/julc/verification/<validator>/`. The task is up to date when the
  artifact hash, canonical property hash, pins and bounds are unchanged, so an
  unchanged contract is not re-proved.
- **Annotation processor.** Check verification annotations at `javac` time
  through the compiler-owned type model, so an unknown field path, a wrong
  field type or an unsupported combination is a compile error in the IDE.
  The checks reuse the resolver `julc verify` uses, not a second copy.
- **Testkit.**
  - Counterexample replay: turn a refuted property's counterexample into a
    `ScriptContext` and evaluate the validator on JuLC's VMs. This confirms
    that a refutation is a real execution, and can be saved as a regression
    test.
  - Optional property-based testing: evaluate a DSL property against random
    transactions from `julc-testkit-jqwik` for fast feedback before a solver
    run. Results are labelled as testing, never as verification.
- **CLI.** `julc verify` without `--validator` verifies every annotated or
  specified validator in the project and prints one summary.

Exit:
- `./gradlew julcVerify` reproduces the CLI's result for the C.5–C.7 fixtures
  and skips unchanged validators;
- an invalid annotation fails `compileJava` with a diagnostic naming the
  field;
- a C.5 counterexample replays as a failing VM evaluation.

### R.8: Simpler DSL workflow

Writing one specification class is the whole job.

- **Convention-based discovery.** Specifications live in
  `src/verification/java`. The CLI and the Gradle task find
  `VerificationSpecification` implementations there and match them to
  validators. `--spec-class`, `--spec-classpath` and `--source` become
  optional overrides.
- **Model generated on every run.** The contract model is regenerated from
  the current contract before each run, as a Gradle-generated source or by the
  CLI, instead of being a hand-run file. The model carries the contract's
  schema hash, and a stale model is rejected with a message saying how to
  regenerate it.
- **In-process compilation.** JuLC compiles the model and specification
  itself with javac (ADR-068), so the user never runs `javac` by hand.
  Compile errors point at the specification's own lines.
- **Diagnostics.**
  - Readable results: each property is shown as plain-language text next to
    its id.
  - Typed counterexamples: shown as Java values (datum and redeemer records,
    signatories, outputs, mint), not as Lean terms.
  - Actionable errors: a vacuous or unsupported property names the part of
    the specification responsible and suggests a fix.
- **`julc verify explain`.** Prints each property in its canonical and
  plain-language forms, for review without running the solver.
- **Starter template.** `julc new` gets an option to scaffold a project with
  one annotated validator, one specification and the Gradle verification block.

The stable construction API version 1 and canonical schema 1 are unchanged.
Only the workflow around them changes.

Exit: starting from `julc new`, a user adds one specification file and gets a
classified result with one command, in both Gradle and the CLI, with no
manual `dsl-init` or `javac` step.

### R.9: Support for AI coding agents

The typed DSL is a good target for agents. It is plain Java against a
generated, closed model; javac and JuLC reject what is not supported; and
results have stable classifications and exit codes. The main risk is
different: an agent can write a property that passes but is weaker than the
developer intended, or weaken a property after it is refuted. Non-vacuity
checking catches only the extreme case. R.9 addresses both the
machine-facing interface and that risk.

**Machine-readable interface.**
- `--json` output for `verify`, `verify dsl` and `verify explain`:
  classification, reasons, diagnostics with file and line, and typed
  counterexamples.
- `julc verify dsl-init --describe`: the contract model's available
  operations, types and ledger fields as JSON, so an agent can see what it can
  express for this specific contract.
- Agent guidance: a section in `llms.txt` and `llms-full.txt` with property
  recipes per purpose, and an agent skill alongside the existing JuLC
  skills. Both are generated from, or tested against, the DSL reference so
  they cannot drift.

**Specification adequacy (mutation checking).**
- `julc verify --mutants` compiles mutants of the validator's Java source:
  remove a conjunct, drop a signer or value check, flip a comparison, widen a
  constant. Each mutant is checked against the same properties.
- The report lists surviving mutants: changes to the contract that no
  property detects. A survivor means a check is unconstrained by the
  specification, or the check is redundant. Either way a human should look.
- Mutation results are review aids. They do not change a property's
  classification.

**Review state (invariant 10).**
- A committed `verification/approvals.json` records, per property id, the
  canonical property hash a human approved.
- The run reports each property as approved, changed since approval, or
  never approved. CI can require approval for merged contracts.
- Because any edit to a property changes its hash, an agent that weakens a
  refuted property shows up as "changed since approval" instead of passing
  silently.
- Agent guidance states the rule plainly: when a property is refuted, report
  the counterexample; do not change the property without a human.

Exit:
- an agent following only the published guidance writes a passing
  property for a C.6-style contract;
- `--mutants` reports a surviving mutant for a deliberately weak property
  and none for the reviewed C.6 property;
- editing an approved property marks it "changed since approval" in both
  JSON and text output.

## Alternatives considered

- **Stay on the August pins.** Rejected: it gives up the batch-6 builtins, the
  ledger-model and solver soundness fixes and UAL interoperability, and
  `cardano-blaster` requires Z3 5.4.0.
- **Bump the pins without re-baselining first.** Rejected: compiler drift and
  upstream breakage could not be told apart.
- **Adopt UAL Lean-in-comment annotations as the authoring surface.** Rejected:
  it asks Java developers to write Lean and gives up the typed DSL's
  compile-time checking.
- **Replace JuLC's certificate with UAL.** Rejected: UAL does not require
  non-vacuity or negative controls. JuLC keeps those checks and exports them as
  claims.
- **Widen the builtin set as soon as decoding works.** Rejected by ADR-014 H.4
  and invariant 3.
- **Integrate only the CLI.** Rejected: verification outside the build is
  easy to skip, and annotation mistakes surface only late.
- **Run the solver on every `check`.** Rejected as a default: runs take
  minutes. It is opt-in, and caching by artifact and property hash keeps
  repeated runs cheap.
- **Let agents write raw Lean properties.** Rejected for the agent path: raw
  Lean has no JuLC type checking, so mistakes are harder to catch. The typed
  DSL is the supported surface for agents.
- **Treat a passing, non-vacuous property as adequate.** Rejected: it says
  nothing about whether the property constrains the checks the developer
  cares about. Mutation reports and review state cover that gap.
- **Wait for proof reconstruction before further work.** Rejected: it is not
  scheduled before mid-2027, and exact-artifact SMT results are already useful
  when their trust base is stated.

## Consequences

- Verification evidence becomes trustworthy again on current main, and stays
  so through the scheduled job.
- JuLC tracks the current Blaster stack and can verify PV11 output, at the
  cost of following upstream churn.
- Third parties can re-check JuLC claims with IOG's tooling, without JuLC.
- Verification becomes part of the normal build and is usable by agents,
  with adequacy and review signals that make weak properties visible.
- The verification surface grows (Gradle, annotation processor, testkit,
  JSON, mutants, approvals), which adds maintenance. Each piece reuses the one
  existing pipeline rather than adding a parallel one.

## Affected modules

- `julc-cli`: `verify` commands, generator, runner, backends, Z3 provisioning,
  verification Dockerfile.
- `julc-verification`: DSL rendering for UAL propositions.
- `julc-blueprint`: possibly a writer for the compiled-interface dialect
  (R.4b), kept separate from the default blueprint.
- `julc-gradle-plugin`: the `julcVerify` task, verification extension and
  generated model sources (R.7, R.8).
- `julc-annotation-processor`: compile-time checking of verification
  annotations (R.7).
- `julc-testkit`, `julc-testkit-jqwik`: counterexample replay and optional
  property-based testing (R.7).
- `julc-cli`: spec discovery, in-process compilation, `explain`, `--json`,
  `--describe` and `--mutants` (R.8, R.9).
- `docs/public/llms*.txt` and an agent skill (R.9).
- `verification/*`: evidence suites, locks and READMEs.
- `.github/workflows`: the scheduled evidence job and Blaster job updates.
- `docs` and `verification/GETTING_STARTED.md`: stack versions, the supported
  builtin set and UAL export.

No compiler, core, ledger API, stdlib or VM source changes are expected.

## Compatibility

- Compiled UPLC and the default `plutus.json` are unchanged.
- Users of the local backend need Z3 5.4.0. `Z3Provisioner` and the Docker
  image supply it.
- Workspaces and certificates generated on the old pins are not replayable on
  the new stack. They must be regenerated, and the CLI must say so instead of
  failing obscurely.
- The canonical DSL schema 1 and construction API version 1 are unchanged.
- `julc verify`, `verify dsl` and `verify dsl-init` gain `--optimization`.
  Its default is the compiler default, so existing invocations build exactly
  as before.
- Existing `julc verify dsl` options keep working. They become overrides of
  convention-based discovery.
- The Gradle verification task is opt-in and is not added to `check` by
  default, so existing builds are unaffected.

## Risks

- Upstream churn: the batch-6 builtins are days old, and the pending Lean 4.35
  migration will force another refresh.
- Generated-proof churn from the `abbrev`-to-`def` change may be larger than
  expected.
- The UAL compiled-interface blueprint dialect is an unpublished CIP draft, and
  `cardano-blaster` pins commits from open PRs. The exported format may need to
  follow upstream changes.
- Translation validation may hit the same solver limits as E.5. R.5's stop rule
  bounds the cost.
- CI time grows with the scheduled evidence job.
- Compile-time annotation checks could diverge from the CLI's checks if
  implemented twice. R.7 requires one shared resolver.
- Mutation checking multiplies solver runs by the number of mutants. It needs
  a mutant budget and caching.
- Plain-language renderings of properties could misstate what the
  canonical property says. They must be generated from the canonical IR and
  tested against it, never written by hand.
- Approval files can be rubber-stamped. They make changes visible; they do
  not make review happen.

## Verification strategy

- R.1 and R.2: per-suite expected classifications (SMT-VALID, REFUTED,
  COULD-NOT-EVALUATE with exact reasons and exit codes), artifact lock
  checks, and the existing annotation-neutrality tests.
- R.3: decoder, concrete-agreement and symbolic tests per builtin, plus
  end-to-end `PV11_COSTED` fixtures with all three controls.
- R.4: `cardano-blaster` reproduces JuLC verdicts from the bundle alone;
  tampered blueprint, context and environment are rejected.
- R.5: an equivalence result and a refuted broken-rewrite control, or a
  recorded rejection.
- R.6: helper results with all three controls, re-checked with
  `cardano-blaster`.
- R.7: Gradle and CLI results compared on the same fixtures; up-to-date
  checks; annotation-processor negative tests; counterexample replay on all
  VMs.
- R.8: an end-to-end test from `julc new` to a classified result with no
  manual steps; stale-model rejection; diagnostics tests for vacuous and
  unsupported properties.
- R.9: JSON schema tests for every output; a mutant report for a known-weak
  and a reviewed property; approval-state transitions; a scripted agent
  exercise using only the published guidance.
- Each milestone runs `:julc-verification:test`, `:julc-cli:test` and a full
  `./gradlew build` before merge.

## Open questions

1. Which upstream SHAs to pin, and how long to wait after the batch-6 commits.
2. Z3 5.4.0 (what Blaster tests) or 5.4.1.
3. Whether the scheduled evidence job runs nightly or weekly, and which suites
   it covers.
4. Where the UAL bundle is written and what the option is called.
5. How the CIP-57 double-CBOR question is settled, given that UAL's dialect uses
   single-CBOR.
6. Whether to offer an expert Lean-annotation escape hatch later.
7. Whether specifications live in `src/verification/java` or in the test
   source set. A separate source set keeps trusted specification code out of
   tests; the test source set is more familiar. Lean: a separate source set.
8. Which mutation operators to ship first, and the default mutant budget.
9. Whether CI should fail on unapproved properties by default, or only warn.

## Revision history

- r1 (2026-11-14): initial version.
- r2 (2026-11-14, maintainer request on r1 `6c52227a`): removed the open
  question about asking IOG to list JuLC in BL.03; JuLC will not ask yet.
  Open questions 8–10 renumbered to 7–9.
- r3 (2026-11-15, R.1 implementation): recorded that `PV11_SAFE` output
  stalls Blaster preprocessing in every suite except `verification/blaster`;
  added `--optimization` to the verify commands and pinned the suites to
  `baseline`; added milestone R.2a. Recorded the e3/c6 refutation regression
  from the E.4b–E.6 generator and added milestone R.1b; recorded the
  per-validator DSL model, optional `.julc` cache and schema-1 certificate
  check fixes.
