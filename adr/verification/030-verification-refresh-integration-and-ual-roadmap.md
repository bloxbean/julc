# ADR-030: Verification Refresh, Build Integration, AI-Agent Support and UAL Export Roadmap

- **Status:** Proposed
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
  default options, i.e. `OptimizationLevel.DEFAULT = PV11_SAFE`.
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

Verification work resumes with the milestones below. R.1 must finish before
R.2, and R.2 before R.3–R.6. After R.2, R.3, R.4 and R.5 may proceed in
parallel. R.6 depends on R.4's function targets.

```text
R.1 re-baseline --> R.2 stack refresh --+--> R.3 PV11 builtins
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
- Add a scheduled CI job (nightly or weekly) that runs a representative set:
  `c5`, `c6`, `c7`, `e4b`, `e4h` and `strict-boundaries`. `verification/blaster`
  stays on pull requests.
- Record a per-suite table (outcome, runtime, refresh reason) in this ADR.

Exit: every suite reproduces its expected classifications on one recorded
main commit, and the scheduled job is green.

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
7. Whether to ask IOG to include JuLC in BL.03 Language Integrations once R.4
   works. This is the maintainer's decision.
8. Whether specifications live in `src/verification/java` or in the test
   source set. A separate source set keeps trusted specification code out of
   tests; the test source set is more familiar. Lean: a separate source set.
9. Which mutation operators to ship first, and the default mutant budget.
10. Whether CI should fail on unapproved properties by default, or only warn.

## Revision history

- r1 (2026-11-14): initial version.
