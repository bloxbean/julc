# ADR-063: Differential JVM/UPLC test harness

**Status:** Proposed (design only)
**Date:** 2026-10-23
**Label:** `semantic-consistency`
**Programme:** step S1 of [ADR-062](062-jvm-uplc-semantic-consistency-programme.md)
**Relates to:** [ADR-061](https://github.com/bloxbean/julc/pull/203) ("Independent differential program
testing" and its milestone 2, which this ADR makes concrete)

## Context

ADR-062 catalogues divergences between running JuLC source on the JVM and running its compiled UPLC
(T1–T19). ADR-061 argues that control-flow correctness needs generated programs compared against an
independent oracle, not more hand-written scenarios. Both need the same infrastructure, and none of it
exists in general form today.

What exists:

- `LoopFieldUpdateTest` (`julc-compiler` tests) compiles a fixed class with javac through
  `javax.tools.ToolProvider`, runs `m(3)`, and checks that JuLC returns the same result at every
  optimization level on both VMs. This is the pattern; it covers one hand-written program.
- `UplcOptimizerPropertyTest` uses jqwik to compare UPLC before and after optimization. As ADR-061 notes,
  both sides can agree on an already wrong Java-to-PIR translation.
- `julc-testkit-jqwik` provides `CardanoArbitraries` and `SchemaArbitraries` for inputs, but no generator
  varies the source program.
- `TermExtractor` (`julc-vm`) turns an evaluated term back into Java values, and `JulcEval` compiles and
  evaluates single methods.

This ADR specifies a harness that generalizes `LoopFieldUpdateTest`: arbitrary programs from a typed
generator, both executions, a precise model of what counts as agreement, shrinking, and coverage
accounting. It is test infrastructure only. It changes no compiler, stdlib or VM behaviour, and no script
bytes.

**Risk:** low for the product (nothing shipped changes), but correctness-sensitive as evidence: a wrong
oracle or a weak generator produces false confidence that later steps rely on (ADR-062 P5).

## Goals and non-goals

**Goals**

- Run the same Java source on the JVM and as UPLC, and compare the results under an explicit observation model.
- Generate valid programs in a defined grammar, plus programs that must be rejected, with inputs.
- Shrink every failure to a small, readable reproducer and keep it as a permanent regression test.
- Measure which interactions were exercised, and fail when coverage quotas are not met.
- Provide a value-level mode that compares individual stdlib and builtin methods (input to ADR-062 S2).

**Non-goals**

- Fixing any divergence. The harness finds and records them; the fixes belong to S2–S5 and ADR-061.
- A reference interpreter for JuLC Java.
- Coverage-guided fuzzing (for example Jazzer). It can wrap the generator later (ADR-061).
- Comparing costs. The harness records budgets and sizes, but equal results say nothing about acceptable cost.

## Invariants

- **H1. Independence.** The JVM side never uses JuLC's compiler output, and the UPLC side never consults the JVM
  result. The only shared input is the source text and the input values.
- **H2. No normalization.** A difference in value, outcome kind or trace sequence is a mismatch. It passes only
  when an allowed-divergence rule (D6) matches it, and every match is counted and reported.
- **H3. Fresh state per evaluation.** Each top-level evaluation runs in a new class loader, so static fields
  start from their initial values. State is never reset between calls inside one evaluation (ADR-061).
- **H4. Reproducibility.** A failure is reproducible from its saved seed, generator version, compiler revision
  and configuration.
- **H5. Inconclusive is not a pass.** A timeout, a budget exhaustion or a JVM stack overflow is recorded as
  inconclusive and counted against the conclusive-case quota.
- **H6. Generator validity.** Every program the positive generator emits compiles with javac. A javac error is a
  generator bug and fails the run.
- **H7. Two properties.** Positive programs must compile with JuLC and agree. Negative programs must be rejected
  with the expected diagnostic category. A compiler that rejects everything fails the first property.

## Decisions

### D1. A new unpublished module, `julc-difftest`

The harness needs the compiler, the stdlib, the testkit, `julc-vm-java`, javac and jqwik. It lives in its own
Gradle module, which is not published. That keeps `julc-compiler`'s test time and dependencies unchanged and
lets the harness run against every frontend path, including validators.

*Alternatives:* putting it in `julc-compiler` tests, as ADR-061 suggests as a start, is possible (its tests
already depend on the stdlib, the testkit and `julc-vm-java`), but it mixes long-running campaigns into the
compiler's unit tests. Putting it in `julc-testkit` makes it public API too early. (Open question 1.)

### D2. The runner

A `DiffCase` is the source text of one class, the entry method, the input values and a configuration.
`DiffRunner` executes it twice:

- **JVM:** compile with `javax.tools.JavaCompiler` in memory against the off-chain classpath, load it in a
  fresh class loader (H3), and invoke the entry method reflectively. A watchdog bounds the run time.
- **UPLC:** compile the same source with JuLC under the configuration, evaluate on `julc-vm-java` with the
  maximum execution budget, and record the result, the trace log and the budget used.

Configurations cover every `OptimizationLevel` (`NONE`, `BASELINE`, `PV11_SAFE`, `PV11_COSTED`) and the supported
target profiles. Other VMs (Scalus, Truffle) can be added as additional UPLC executions; agreement between VMs
is a separate property and does not validate the frontend.

### D3. The observation model

Each execution yields one `Observation`:

| Kind | JVM | UPLC |
|---|---|---|
| `Value(data)` | The return value, converted by the mapping in D4 | The result term, converted by `TermExtractor` |
| `Failure(traces)` | Any exception from the entry method, with the traces emitted before it | Evaluation error, with the trace log |
| `Rejected(code)` | — | JuLC compile error with its diagnostic code |
| `Crash` | — | A compiler exception that is not a diagnostic |
| `Inconclusive(reason)` | Timeout or stack overflow | Budget exhausted |

Agreement means both executions return `Value` with equal Data, or both return `Failure` with equal trace
sequences. Failure kinds (for example `ArithmeticException` versus a builtin error) are not compared in the
first version; the trace prefix is (ADR-062 T12, open question 3). `Crash` is always a defect.

### D4. One value mapping

JVM results are converted to `PlutusData` by a single documented mapping:

- `BigInteger`, `int` and `long` become `IntegerData`;
- `boolean` becomes the Bool constructor;
- `byte[]` becomes `BytesData`;
- `String` becomes its UTF-8 bytes;
- ledger types use their own `toPlutusData` (`julc-ledger-api`);
- user records use field order and the constructor tags the compiler documents.

The comparison is structural equality on `PlutusData`. The mapping is a trusted component and has its own
unit tests against hand-written expected Data.

### D5. Program generators

Programs are generated as typed ASTs and printed as Java source, never as random text (ADR-061). The
grammar grows in named stages:

- **G0 (first milestone):**
  - static methods over `BigInteger` and `boolean` locals;
  - assignments and compound assignments;
  - `if`/`else` and the ternary operator;
  - `&&` and `||`;
  - `while` loops with a bounded, decreasing counter;
  - for-each over a `JulcList<BigInteger>` parameter;
  - `break`, `continue` and `return` inside loops;
  - nested blocks and shadowing;
  - calls to non-recursive helper methods.
  
  Integers stay within ±2^62 so that no `long` overflows, and the grammar never uses `==` on reference types.
  That keeps G0 inside the semantic intersection (ADR-062 T3–T6).
- **G1:** `switch` expressions over sealed interfaces and records; Optional; `JulcMap`.
- **G2:** list functions with lambdas (`any`, `all`, `find`, `map`, `filter`, `foldl`); recursion with a
  decreasing measure.
- **G3:** validator entry points with generated datums, redeemers and script contexts from
  `CardanoArbitraries`.
- **G0⁻ and later negative grammars:** constructs JuLC must reject (assignment to an enclosing binding from a
  lambda, unsupported operators, the forms ADR-048 and ADR-061 exclude), each paired with the expected
  diagnostic category.

Termination holds by construction. Every loop has a counter that strictly decreases, every recursion a
decreasing argument, and nesting depth and size are bounded.

### D6. Allowed divergences

Divergences that are known and not yet resolved are listed in one registry file. Each entry names its ADR-062
catalogue ID, a predicate over the program's features (for example "uses `any` with a predicate that can fail
on a later element"), and an issue. A mismatch passes only if exactly one entry matches it, and every match
appears in the run statistics. Entries are removed when the owning step resolves them. A run with an unused
entry reports it, so stale rules don't hide regressions.

### D7. Exhaustive small programs

Alongside random generation, enumerate every G0 program up to a small size (initially 6 statements over 2
variables, to be tuned against run time) with fixed boundary inputs (empty, singleton and multi-element
lists; zero, negative and large integers). This covers all small combinations, which random generation can
miss.

### D8. Shrinking and regression capture

jqwik shrinks the AST and the inputs together, keeping each candidate typed, in scope and terminating. A
minimized failure is saved under `julc-difftest/src/test/resources/regressions/` with:
- its source and inputs;
- both observations;
- the seed and generator version;
- the compiler revision;
- the configuration.

A regression suite replays every saved case on every run. Fixing the defect turns the case into a plain
regression test; it is never deleted.

### D9. Coverage quotas and statistics

Each generated case is labelled with the interactions it exercises, using the dimensions in ADR-061's
composition table:
- zero, one or many iterations;
- the number of updated variables;
- if-in-loop, loop-in-if and loop-in-loop nesting;
- shadowing;
- each exit kind, early or on the last iteration;
- short-circuit branches;
- helper calls inside loops.

A run fails when a dimension has fewer conclusive cases than its quota. It reports discard, inconclusive and
allowed-divergence rates (H5).

### D10. Value-level mode

The same runner compares one stdlib or builtin method: the off-chain Java method on the JVM versus `JulcEval`
on the compiled method, over inputs from `CardanoArbitraries` and boundary values. This mode produces the
conformance report that ADR-062 S2 needs (T8–T10, T17, T18). It runs on the existing off-chain libraries and
is expected to find divergences there; those are findings, not harness failures.

### D11. Checking the harness itself

The harness counts as evidence only after it demonstrates that it finds real defects:

- **Rediscovery.** Re-create the known failures as `DiffCase`s and show each is detected: the ADR-061 field-state
  probes; the #155 class (a local updated in an `if` inside a loop) with the ADR-048 guard disabled through a
  test-only hook, or run against the pre-fix JuLC commit built locally (the BloxBean snapshot repository
  holds only `0.1.0-pre20-ebaf028-SNAPSHOT` today, so older builds are not available there); and T3, T7 and
  T11.
- **Mutation.** A test-only compiler hook injects deliberate faults: drop a join argument, use a stale
  accumulator, swap two operand evaluations, jump to the wrong loop exit. Each must be caught by the G0 campaign
  within its PR-run budget.

### D12. CI

- Pull requests run the regression suite, the exhaustive enumeration and a fixed-seed random campaign, within a
  time budget. The budget is an estimate to be measured in M2: a few minutes.
- A scheduled nightly job runs larger campaigns with fresh seeds and uploads saved failures as artifacts.
- The module's tests are part of `./gradlew build`; campaign sizes are Gradle properties.

## Trusted components

The harness's verdicts depend on:
- javac and the JVM, as the oracle for Java's meaning;
- the D4 value mapping;
- `TermExtractor`;
- `julc-vm-java`, which is conformance-tested against the official Plutus test suite (`PlutusConformanceTest`);
- jqwik.

The JVM side also runs whatever off-chain library code a program calls. Until ADR-062 S2 is done, that code
is not trusted, which is why G0 and G1 use only `BigInteger`, `boolean`, `JulcList` and user records on the JVM
side, and why D10 treats off-chain library differences as findings.

## Affected modules

- New: `julc-difftest`, which is not published.
- `julc-compiler`: optionally, test-only hooks for D11 mutations, disabled by default and unreachable from public
  APIs. Whether such hooks are acceptable is open question 2.
- No change to `julc-stdlib`, `julc-vm*`, `julc-testkit` or any published artifact.

## Compatibility

None. No public API, compiled output, script hash or cost changes.

## Milestones

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M1** | `DiffRunner`, observation model (D3), value mapping (D4) with its unit tests, regression store (D8), and about 15 hand-written cases: the `LoopFieldUpdateTest` program, the ADR-061 probes, and reproducers for T3, T5, T7, T10, T11 and T15 | Every known divergence reproduces and is registered (D6) or recorded as a finding; T15 is confirmed or refuted |
| **M2** | G0 generator, printer, shrinking, exhaustive enumeration (D7), statistics and quotas (D9), PR and nightly CI (D12) | Quotas met with a measured PR-run time; discard and inconclusive rates reported |
| **M3** | Self-check (D11): rediscovery and mutation | Every listed historical failure and every injected fault is detected |
| **M4** | Value-level mode (D10) over every public `Builtins` method and the stdlib libraries | A conformance report listing each method as agree, diverge or unsupported, which becomes the input to S2's ADR |
| **M5** | G0⁻ negative grammar, then G1 | Both H7 properties hold; G1 quotas met |

ADR-061's control-flow migrations (ADR-062 S4) may start only after M3.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Extend `LoopFieldUpdateTest`-style hand-written comparisons | That is the scenario-by-scenario approach that has not converged (ADR-062) |
| Random Java text filtered by javac | Most outputs are invalid, and shrinking text keeps it neither typed nor terminating (ADR-061) |
| Compare against a model interpreter instead of the JVM | The JVM is an independent, complete definition of Java's meaning; an interpreter would be new trusted code |
| Compare only success or failure, not values and traces | Misses wrong values and reordered traces, which are the silent miscompiles that matter |
| Compare UPLC across optimization levels only | Both sides share the frontend, so a wrong translation agrees with itself (`UplcOptimizerPropertyTest`'s limit) |

## Open questions

1. **Module location (D1).** A new `julc-difftest` module, or start inside `julc-compiler` tests as ADR-061
   suggests and extract later? Lean: the new module, because the harness needs the testkit and validator
   paths from M1.
2. **Test-only compiler hooks for mutation (D11).** Are hooks inside `julc-compiler` acceptable if they are
   unreachable from public API? The alternative is running rediscovery only against pinned old snapshots,
   which cannot test new faults. Lean: allow hooks, behind a package-private test seam.
3. **Failure kinds (D3).** Should failure categories be compared (division by zero versus a decode error)? Lean:
   not in M1; revisit after S2 defines how off-chain failures map to UPLC errors.
4. **Which VMs are in the PR run?** Lean: `julc-vm-java` on PRs; Scalus and Truffle in the nightly campaign.
5. **Should users get the harness?** A published `julc-testkit` API for differential tests of user contracts is
   ADR-062 S3's question. Lean: decide there, after M4.

## Revision history

- **r1** (2026-10-23): initial proposal.
