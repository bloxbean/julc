# ADR-063: Differential JVM/UPLC test harness

**Status:** Accepted (2026-10-28). M1 and M2 are implemented in `julc-difftest` (same PR), revised in r4 and r5
after review; M2b and M3–M5 are not started
**Date:** 2026-10-23
**Label:** `semantic-consistency`
**Programme:** step S1 of [ADR-062](062-jvm-uplc-semantic-consistency-programme.md)
**Relates to:** [ADR-061](https://github.com/bloxbean/julc/pull/203) ("Independent differential program
testing" and its milestone 2, which this ADR makes concrete)

## Context

ADR-062 catalogues divergences between running JuLC source on the JVM and running its compiled UPLC
(T1–T23 as of its r4). ADR-061 argues that control-flow correctness needs generated programs compared against an
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
  and configuration (the D8 record). Replaying it runs the current compiler; the observations of the run that
  found it are kept beside it as history.
- **H5. Inconclusive is not a pass.** A timeout, a budget exhaustion or a JVM stack overflow is recorded as
  inconclusive. An inconclusive case, explained by the registry or not, satisfies no coverage quota (D9).
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
- **UPLC:** compile the same source with JuLC under the configuration, evaluate on `julc-vm-java` with a bounded
  budget (`UplcExecutor.BUDGET`, 10^10 CPU and 1.4·10^7 memory units), and record the result, the trace log, the
  budget used and the script's FLAT size. The comparison never uses the budget or the size; the D8 record keeps
  them.

Configurations cover every `OptimizationLevel` (`NONE`, `BASELINE`, `PV11_SAFE`, `PV11_COSTED`) on `julc-vm-java`
and the default `CompilerTarget`. Varying the target is M2b. Other VMs (Scalus, Truffle) can be added as
additional UPLC executions; agreement between VMs is a separate property and does not validate the frontend.

### D3. The observation model

Each execution yields one `Observation`:

| Kind | JVM | UPLC |
|---|---|---|
| `Value(data)` | The return value, converted by the mapping in D4 | The result term, converted by the same mapping (`ValueMapping.fromTerm`) |
| `Failure(traces)` | Any exception from the entry method, with the traces emitted before it | Evaluation error, with the trace log |
| `Rejected(code)` | — | JuLC compile error with its diagnostic code |
| `Crash` | — | A compiler exception that is not a diagnostic |
| `Inconclusive(reason)` | Timeout or stack overflow | Budget exhausted |

Agreement means both executions return `Value` with equal Data and equal trace sequences, or both return
`Failure` with equal trace sequences. Failure kinds (for example `ArithmeticException` versus a builtin error)
are not compared in the first version, so two `Failure`s that agree do not show that they failed for the same
reason; the trace prefix is compared (ADR-062 T12, open question 3). `Crash` is always a defect.

A trace event is one call of `Builtins.trace`. Off-chain, it prints `[TRACE] message` with one `println`; the
JVM side replaces standard output with a sink that records each such call, made directly by `Builtins.trace`,
as one event with its message unchanged. So a message keeps its line breaks and any `[TRACE] ` it contains, and
other output is never taken for a trace. The UPLC trace list is never normalized to match.

### D4. One value mapping

JVM results are converted to `PlutusData` by a single documented mapping (`ValueMapping`), which also converts
UPLC result terms and hand-written arguments:

- `BigInteger`, `int` and `long` become `IntegerData`;
- `boolean` becomes the Bool constructor;
- `byte[]` becomes `BytesData`;
- `String` becomes its UTF-8 bytes;
- `PlutusData` is itself; `Optional`, `JulcList` and `JulcMap` of these map element by element.

Not implemented yet, and rejected by the mapping rather than guessed:
- user records, by field order and the constructor tags the compiler documents (M2b);
- ledger types, through their own `toPlutusData` (`julc-ledger-api`), which D10 needs (M4).

The comparison is structural equality on `PlutusData`. The mapping is a trusted component and has its own
unit tests against hand-written expected Data.

### D5. Program generators

Programs are generated as typed ASTs and printed as Java source, never as random text (ADR-061). The
grammar grows in named stages:

- **G0 (M2, as implemented in `gen/G0`, version `G0 r1`):**
  - one static method `m(BigInteger a, BigInteger b, JulcList<BigInteger> xs)` returning `BigInteger`;
  - method-level `BigInteger` declarations, assigned only inside loops (ADR-048);
  - `while` loops whose `long` counter counts up to a bound of at most 3, and for-each over `xs`, nested at
    most two deep;
  - in loop bodies: plain assignments, `if`/`else` with assignments, `if (c) break;`, and `BigInteger` locals;
  - expressions: `add`, `subtract`, `multiply` by a small constant, the ternary operator, constants from −5 to
    5; conditions: `compareTo` against 0, `&&` and `||`.

  The grammar never uses `==` on reference types, has no `int` or `long` arithmetic beyond the counters, and has
  no division, which keeps it inside the semantic intersection (ADR-062 T3–T6, T11).

  Planned for G0 and not generated yet (M2b): `if`/`else` and loops inside an `if` at method level, calls to
  non-recursive helper methods, `boolean` locals, a local that shadows a field, and decreasing counters. Java has
  no other shadowing. Compound assignment does not apply to `BigInteger`.

  JuLC rejects these, so they belong to the negative grammar G0⁻ (M5) and are saved as `REJECT` cases today:
  `continue`; `return` inside a loop; reassigning a variable outside a loop; `break` in a for-each that updates
  nothing; a loop with `break` that contains a loop.
- **G1:** `switch` expressions over sealed interfaces and records; Optional; `JulcMap`.
- **G2:** list functions with lambdas (`any`, `all`, `find`, `map`, `filter`, `foldl`); recursion with a
  decreasing measure.
- **G3:** validator entry points with generated datums, redeemers and script contexts from
  `CardanoArbitraries`.
- **G0⁻ and later negative grammars:** constructs JuLC must reject (assignment to an enclosing binding from a
  lambda, unsupported operators, the forms ADR-048 and ADR-061 exclude), each paired with the expected
  diagnostic category.

Termination holds by construction. Every loop has a bounded counter or iterates over a finite list, every
recursion has a decreasing argument, and nesting depth and size are bounded.

### D6. Allowed divergences

*Hand-written and saved cases (M1, implemented):* each `.diffcase` file states its own expectation. A known
divergence is `DIVERGE <catalogue id> <JVM kind>/<UPLC kind>`, for example `DIVERGE T12 Value/Value`, followed by
the observation each side must produce:

```
expect: DIVERGE T12 Value/Value
jvm: Value 01 []
uplc: Value 01 ["checked"]
```

The pins fix the value and traces of a `Value`, the traces of a `Failure`, and the reason of an `Inconclusive`;
they cannot name a `Rejected` or a `Crash`. Failure details are not pinned, because they are not compared (open
question 3). So a saved divergence passes only while it produces exactly those observations: a different value,
an extra or reordered trace, a trace before a failure, or another inconclusive reason fails it. Per-level pins
(`uplc.<LEVEL>:`) cover a divergence that differs by optimization level. When the owning step resolves the
entry, the expectation changes to `AGREE` or `REJECT`.

*Generated cases (M2, implemented as `KnownDivergences`):* a generated mismatch passes only if the registry
explains every configuration that disagrees, and every explanation appears in the run statistics. How it may be
explained depends on what the UPLC side produced:

- **A conclusive UPLC side (a `Value` or a `Failure`) is explained only by an exact pin.** A pin is a saved
  `DIVERGE` case with the same program (its source, up to the class name), method and arguments, whose pinned
  observations the result meets at that optimization level, traces included. Any other wrong value, and any
  change of traces, fails the run and is written out for triage, even in the shape of a known divergence. The
  saved cases under `src/test/resources/regressions` are the pins, so a saved case and the registry cannot
  disagree. T23 is explained this way: the two corpus runs it affects (D7) are saved as
  `diverge-t23-corpus-input-1` and `-input-3`.
- **An inconclusive UPLC side is explained by a rule.** A rule names its ADR-062 catalogue ID, a structural
  feature the generator records (for T21, `WHILE_BREAK_WITH_IF_UPDATE`), the JVM kind, and the exact
  inconclusive reason. A rule cannot explain a conclusive side, and one without a reason is refused. Rules are
  removed when the owning step resolves them; a run reports how often each one matched, so a stale rule is
  visible.

A rule is weaker than a pin: another non-termination with the same shape and reason passes as T21. That is
acceptable because an inconclusive case is no evidence and counts towards no quota (H5, D9), and because each
rule has pinned saved reproducers. A wrong value is a correctness result, so it gets no rule.

### D7. Exhaustive small programs

Alongside random generation, enumerate every program of a bounded sub-grammar of G0 (`gen/G0Enumeration`,
version `G0 enumeration r1`) on fixed inputs. This covers all small combinations, which random generation can
miss. Every program declares `v0 = a` and `v1 = b`, runs one or two loops, and returns `v0 + 3·v1`, so both
accumulators reach the result. A loop body reads its loop variable `X` in each of nine statements:

| Statement | Body text |
|---|---|
| `ADD` | `v0 = v0.add(X);` |
| `SUBTRACT_OTHER` | `v1 = v1.subtract(v0);` |
| `IF_UPDATE` | `if (X > a) { v0 = v0 + X; }` |
| `IF_ELSE_UPDATE` | `if (X < b) { v0 = v0 + 1; } else { v1 = v1 + X; }` |
| `BREAK` | `if (X > b) { break; }` |
| `LOCAL` | `BigInteger t = X * 2; v1 = v1 + t;` |
| `TERNARY` | `v0 = (X < a ? v0 - X : v0 + 2);` |
| `AND_UPDATE` | `if (X > a && X < 4) { v1 = v1 + 1; }` |
| `OR_UPDATE` | `if (X < a \|\| X > 2) { v1 = v1 + X; }` |

The bound:
- one loop (`while` run once, `while` run three times, or for-each): a body of one or two statements;
- two loops, one after the other, a `while` around a for-each, or a for-each around a `while`: one statement in
  each body, plus the inner loop in the outer body;
- a `while` counts its loop variable from 1; a for-each runs over `xs`;
- bodies that update nothing, and outer bodies that `break`, are left out, because JuLC rejects them (G0⁻).

That is 376 programs: 8 one-statement bodies and 80 two-statement bodies, so 8 + 88 + 88 for the single-loop
shapes and 3 × 64 for the two-loop shapes. They run on four inputs:

| `a` | `b` | `xs` | Under `X > b` |
|---|---|---|---|
| 0 | 3 | `[]` | for-each runs zero times; three-iteration `while` never breaks |
| −2 | 1 | `[-1, 2, 4]` | `while` breaks at its second iteration; for-each at its second element |
| 2 | 1 | `[1]` | for-each never breaks |
| −2 | −3 | `[-4, 3, 1, 2]` | `while` breaks at once; for-each at its second element |

The last three were chosen by a greedy search over 210 candidate inputs as the fewest under which every erasure
below is observable.

The corpus is checked for what it exercises, not only for its size (`G0ExhaustiveTest`):
- **Bound and features:** exactly 376 distinct programs, with at least 20 for each G0 feature.
- **Observability:** each program is also compiled without its loops, and once without each single statement
  (1,104 variants). Every variant changes the JVM result on at least one input. So a lowering that drops a loop
  or a statement, or never takes a branch, produces a UPLC result that differs from the JVM, and the run fails.
- **Agreement:** all 1,504 runs agree at every optimization level, or are explained by the registry: by the T21
  rule, or by the two exact T23 pins.

### D8. Shrinking and regression capture

jqwik shrinks the choice sequence and the inputs together; every decoded program is valid, so each candidate
stays typed, in scope and terminating. A failure is written as a versioned record (`format: 2`, `RegressionStore`):

| Lines | Content |
|---|---|
| `name`, `class`, `method`, `arg`, source | The case: its source and inputs |
| `expect`, `jvm`, `uplc`, and `.<LEVEL>` overrides | What a replay must produce (D6) |
| `found.by`, `found.generator`, `found.plan`, `found.seed`, `found.compiler` | Provenance: the test, the generator version, the choice sequence, the jqwik seed, and the project version with `git describe --dirty` |
| `recorded.jvm`, `recorded.<config>`, `recorded.<config>.cost` | History: what each side observed when the record was written, under each configuration, with the budget and script size |

`recorded.` lines are never checked. A replay against a later compiler may contradict them; the `expect` lines
say what the replay must produce. A generated failure is written to `build/difftest/` and uploaded by the
nightly job; saving it under `julc-difftest/src/test/resources/regressions/` makes it permanent. A regression
suite replays every saved case on every run. Fixing the defect turns the case into a plain regression test; it
is never deleted. Hand-written cases from M1 carry no provenance.

### D9. Coverage quotas and statistics

Each generated case is labelled with the interactions its program contains, using the dimensions in ADR-061's
composition table.

*Implemented (M2):* the G0 features `WHILE`, `FOR_EACH`, `NESTED_LOOP`, `IF_IN_LOOP`, `IF_ELSE_IN_LOOP`, `BREAK`,
`BODY_LOCAL`, `TERNARY`, `SHORT_CIRCUIT`, `ACCUMULATOR_UPDATED` and `SEVERAL_ACCUMULATORS`. The features
`WHILE_BREAK_WITH_IF_UPDATE` and `FOR_EACH_BREAK_WITH_SPLIT_IF_ELSE` mark known bugs' shapes and have no quota;
the T21 rule matches on the first, and the second is statistics only, since T23 is explained by pins.
`Coverage` records each case's outcome:
- conclusive: every configuration agrees;
- allowed divergence: no configuration is inconclusive and every mismatch is explained;
- inconclusive: some configuration is inconclusive, whether explained or not.

A feature counts towards its quota only from conclusive cases, and the PR campaign fails when a feature has
fewer than 3% of all tries. The report gives each outcome per configuration. A case with an unexplained mismatch
fails the run instead.

These features are syntactic: they say a program contains a loop or a `break`, not that the loop iterated or the
`break` was taken. For the exhaustive corpus, D7's observability check establishes that. For random campaigns,
coverage of what was exercised is M2b.

*Not implemented (M2b):* zero, one or many iterations as measured; each exit kind, early or on the last
iteration; loop-in-if nesting; shadowing; helper calls inside loops.

Discards do not occur, because every decoded program is valid. The PR campaign has a fixed seed, so its
inconclusive and allowed-divergence counts are fixed. The nightly campaign reports them.

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
- the D4 value mapping (`ValueMapping`), in both directions;
- the JVM trace sink (D3);
- `julc-vm-java`, which is conformance-tested against the official Plutus test suite (`PlutusConformanceTest`);
- jqwik.

The JVM side also runs whatever off-chain library code a program calls. Until ADR-062 S2 is done, that code
is not trusted, which is why G0 and G1 use only `BigInteger`, `boolean`, `JulcList` and user records on the JVM
side, and why D10 treats off-chain library differences as findings.

## Affected modules

- New: `julc-difftest`, which is not published. Its build runs `git describe` for the D8 compiler revision.
- `julc-compiler`: optionally, test-only hooks for D11 mutations, disabled by default and unreachable from public
  APIs. Whether such hooks are acceptable is open question 2.
- No change to `julc-stdlib`, `julc-vm*`, `julc-testkit` or any published artifact.

## Compatibility

None. No public API, compiled output, script hash or cost changes.

## Milestones

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M1** | `DiffRunner`, observation model (D3), value mapping (D4) with its unit tests, regression store (D8), and about 15 hand-written cases: the `LoopFieldUpdateTest` program, the ADR-061 probes, and reproducers for T3, T5, T7, T10, T11 and T15 | Every known divergence reproduces and is registered (D6) or recorded as a finding; T15 is confirmed or refuted |
| **M2** | G0 generator, printer, shrinking, exhaustive enumeration (D7), statistics and quotas (D9), PR and nightly CI (D12) | Quotas met from conclusive cases with a measured PR-run time; inconclusive and allowed-divergence rates reported; the D7 corpus observability check passes; failures saved as D8 records |
| **M2b** | The rest of G0 (D5): method-level `if`/`else` and loops inside an `if`, helper calls, `boolean` locals, a local shadowing a field. User-record mapping (D4); target profiles (D2); exercised-coverage dimensions for random campaigns (D9) | Each construct generated and covered by a quota; the D7 bound extended to it with the observability check; T14 confirmed or refuted |
| **M3** | Self-check (D11): rediscovery and mutation | Every listed historical failure and every injected fault is detected |
| **M4** | Value-level mode (D10) over every public `Builtins` method and the stdlib libraries, with the ledger-type mapping (D4) | A conformance report listing each method as agree, diverge or unsupported, which becomes the input to S2's ADR |
| **M5** | G0⁻ negative grammar (starting from the saved `REJECT` cases), then G1 | Both H7 properties hold; G1 quotas met |

ADR-061's control-flow migrations (ADR-062 S4) may start only after M3. M2b does not gate M3: the mutations of
D11 target constructs that G0 already generates.

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
   not in M1; revisit after S2 defines how off-chain failures map to UPLC errors. Until then, saved divergences
   pin a failure's traces but not its detail (D6), and D8 records keep the details as history.
4. **Which VMs are in the PR run?** Lean: `julc-vm-java` on PRs; Scalus and Truffle in the nightly campaign.
5. **Should users get the harness?** A published `julc-testkit` API for differential tests of user contracts is
   ADR-062 S3's question. Lean: decide there, after M4.

## M1 results (2026-10-23, at `ebaf028c` plus this PR)

`./gradlew :julc-difftest:test --rerun`: 37 tests pass (24 saved cases at all four optimization levels on
the Java VM, plus unit tests of the value mapping, the comparison rules and the JVM executor).

| Outcome | Cases |
|---|---|
| Agree at every level | 10: for-each with `break`; `break` then `return` after a while; two accumulators updated in `if`/`else`; nested `while` with an inner local; a helper called in a loop; an entry method updating a static field; `&&` skipping a failing operand; truncating `/` and `%`; `boolean` and `byte[]` boundary values; the T15 probe |
| Known divergence reproduced | 9: T3 (`==` on `BigInteger`), T5 (`==` on `byte[]`, and a record with a `byte[]` component), T6 (`long` overflow), T7 (`any`: the JVM returns true, UPLC fails), T9 (`AddressLib.credentialHash`: the JVM throws `ClassCastException`), T10 (`JulcMap` with a `byte[]` key), T11 (`mod` with a negative modulus: the JVM throws), T12 (`ContextsLib.trace` is a no-op off-chain, so the trace logs differ) |
| Rejected | 5: the ADR-061 helper probe (`h(n) + h(n)`) with `JULC0056`; `continue`; `return` inside `while`; reassigning a local outside a loop; `JulcMap.of` in contract code |

Findings:
- **T15 is refuted for the probed shape.** An unused `BigInteger.TEN.divide(BigInteger.valueOf(n))` binding
  still fails at every level when `n = 0`, as on the JVM. Other unused failing expressions have not been examined.
- **The ADR-061 field-state probes are now rejected, not miscompiled.** `h(n) + h(n)` is rejected with
  `JULC0056` (ADR-060), so the case is a negative regression.
- **Four rejections have no diagnostic code:**
  - `Unsupported statement: ContinueStmt`;
  - `'return' is not supported inside while loop body`;
  - `Unsupported expression: AssignExpr`;
  - `Undefined variable: JulcMap`.
  
  The last one does not say that `JulcMap.of` is available off-chain only. Saved cases record these as
  `REJECT none`.
- **`ContextsLib.trace` does nothing off-chain,** although its section comment says it prints. This is T12's
  first confirmed instance.

## M2 results (2026-10-23)

Implemented:
- **G0:** the generator (`gen/G0`) decodes a jqwik-shrunk choice sequence (`gen/Choices`), so every
  decoded program is valid by construction and shrinks as a whole.
- **PR campaign:** `G0PropertyTest`, 150 tries with a fixed seed, plus coverage quotas of at least 3% of
  tries for each G0 feature.
- **Nightly campaign:** `nightlyDiffTest`, 3,000 tries with a fresh seed, run by
  `.github/workflows/difftest-nightly.yml`, which uploads the shrunk failing case.
- **Exhaustive mode:** `G0ExhaustiveTest` runs every program from choice sequences of length 6 over
  {0, 1, 2} (183 distinct programs) on 3 boundary inputs. Replaced in r4 by the D7 grammar: the review showed
  that every `while` bound in it was zero and no result read an accumulator.
- **Registry:** `KnownDivergences`.
- **Harness changes:**
  - UPLC evaluation now runs under a bounded budget (`UplcExecutor.BUDGET`, 10^10 CPU and 1.4·10^7 memory
    units). Unlimited evaluation of a non-terminating script ran the test JVM out of memory.
  - An `OutOfMemoryError` while compiling or evaluating is reported as `Crash`.
  - `-Pdifftest.cases=<dir>` replays another directory of saved cases.

G0 had to be narrowed to the subset JuLC accepts. Each narrowing is a saved negative case:
- a for-each whose body updates nothing cannot `break` (`reject-break-in-for-each-without-accumulator`; the
  diagnostic says "break statement outside of a loop");
- a loop with `break` cannot contain a loop (`reject-nested-loop-in-break-loop`).

**Finding: T21, a silent non-termination.** The first nightly run found a program the JVM finishes and UPLC
never does. Reduced by hand:
- in a `while` loop whose body contains `break`, a taken `if` branch that updates a variable makes the loop
  run until the budget is exhausted;
- this happens at every optimization level, even when the `break` is never taken or comes first;
- a `while` whose `if` is not taken agrees. The for-each form of these reproducers agrees too, but a for-each
  whose `if`/`else` assigns different variables does not: r4 found T23 (below);
- three reproducers are saved as `diverge-t21-*`.

On-chain this is a script that always fails where Java returns a value. It is registered as T21 so campaigns
keep running, and it needs a fix (ADR-062 S4/ADR-061, or a targeted fix first).

Measured:
- `./gradlew :julc-difftest:build --rerun-tasks`: 44 tests, 0 failures, 27 s;
- `G0ExhaustiveTest`: 19 s;
- nightly campaign: 3,000 tries in about 2 minutes, 2,998 agreeing at every level and 2 explained by T21.

## Review round r4 results (2026-10-28)

What changed, against the review of `ac20ac2d`:
- **Saved divergences are pinned (D6).** All 12 `DIVERGE` cases now pin both observations. Each pin was checked
  against the divergence its case documents, for example T11 as UPLC −1 against the JVM's
  `ArithmeticException`, and T12 as `["checked"]` on UPLC against no trace on the JVM. No case differs by
  optimization level. `VerdictTest` covers the reviewer's counterexamples: a wrong value with the same traces,
  an extra or reordered trace, and another inconclusive reason.
- **The exhaustive corpus is the D7 grammar.** The 183 programs decoded from G0 choice lists are replaced by the
  376 programs and four inputs of D7.
- **Quotas count conclusive cases only (D9, H5).** `Coverage` keeps per-configuration outcomes. `CoverageTest`
  shows that a feature present only in budget-exhausted cases fails its quota.
- **JVM traces are whole events (D3).**
- **Failures are D8 records**, with the budget and script size of each configuration.

Two new divergences, both found by the r4 changes:
- **T22, string literal escapes.** The trace-payload case compiled `"a\n[TRACE] b"` into a script that traces a
  backslash and an `n` where Java has a line break. `PirGenerator` lowers a `StringLiteralExpr` with `getValue()`,
  JavaParser's source text with its escapes, instead of the decoded `asString()`. Any string constant with an
  escape therefore has different bytes on-chain. The case is saved as `diverge-t22-string-literal-escapes`. The
  trace-payload case now passes its messages as arguments and agrees.
- **T23, a silent wrong value.** The D7 corpus found `FOR_EACH [IF_ELSE_UPDATE, BREAK]`. A for-each whose body
  has an `if`/`else` assigning different variables, and a `break`, returns a wrong value at every optimization
  level, even when the `break` is never taken. For example, the JVM returns 2 and UPLC 29.

  An `if`/`else` that assigns one variable agrees, and so do an `if` without `else`, a `break` before the
  `if`/`else`, and no `break`. It is probably the lowering behind T21, which shows in a `while` as
  non-termination instead. Two saved cases pin it, and the registry entry needs the new feature
  `FOR_EACH_BREAK_WITH_SPLIT_IF_ELSE` (replaced in r5 by exact pins). Random G0 campaigns, including the 3,000-try
  nightly, never generated a failing instance.

The first version of the D7 corpus had 15 erasures that changed no result: a `while` counter from 0 makes the
loop variable 0, and conditions tied to `b` cannot fire after a `break` on `b`. The observability check reported
them; the counter now starts at 1, and the inputs were chosen by search.

Checks that the new mechanisms fail when they should, each made by editing the code and reverting it:
- with the pin comparison disabled, `VerdictTest` fails;
- with a 30% quota, the PR campaign fails with the features below it listed;
- with failures forced, the record carries `found.seed: 20261023`. This showed that `@AddLifecycleHook` needs
  `propagateTo = ALL_DESCENDANTS`, or jqwik never runs the hook.

Measured at the r4 head:
- `./gradlew :julc-difftest:build verifyMavenReleasePublicationScope -PskipSigning=true --rerun-tasks`: 66 tests,
  0 failures, the publication-scope check passing with 20 projects, 31 s. The saved cases are 34 (24 from M1,
  three for T21, two for trace payloads, one for T22 and two for T23, plus the two subset rejections of M2).
- `G0ExhaustiveTest`: 1,504 runs and 1,104 erasure variants, 20 s. 1,481 runs are conclusive, 21 are inconclusive
  under T21, and 2 are allowed under T23.
- `./gradlew :julc-difftest:nightlyDiffTest`: 3,000 tries with seed `3288857217423497629`. 2,996 are conclusive and
  4 inconclusive under T21; 1 min 35 s.

## Review round r5 results (2026-10-28)

What changed, against the review of `d47e7bf4` (F7):
- **A wrong value is explained only by an exact pin (D6).** The r4 T23 entry accepted any `Value`/`Value`
  mismatch in its shape. The registry now has two mechanisms: rules for an inconclusive UPLC side (T21 only),
  and pins for everything else, loaded from the saved cases. The T23 rule is removed.
- **The two corpus runs T23 affects are saved.** Program 135, `FOR_EACH [IF_ELSE_UPDATE, BREAK]`, on input 1
  (JVM 8, UPLC 38) and input 3 (JVM −1, UPLC 26), at every optimization level. With the T23 rule removed and
  before these were saved, `G0ExhaustiveTest` failed on exactly these two runs and wrote them out; that is the
  triage path for any new wrong value.
- **`KnownDivergencesTest`** covers the reviewer's counterexamples: on an empty list the program returns 9 on
  the JVM, and neither UPLC `Value 999 []` nor `Value 9 ["unexpected trace"]` is explained. So are a pinned
  program and input with another UPLC value, another trace or another JVM value, the pinned observations on
  another input or another program, and a conclusive side under the T21 rule's feature. The four T23
  reproducers are explained, under the saved class name and under the corpus class name. With the pin's
  observation check replaced by `true`, this test fails.

Measured at the r5 tree:
- `./gradlew :julc-difftest:build verifyMavenReleasePublicationScope -PskipSigning=true --rerun-tasks`: 72 tests,
  0 failures, 23 s. The saved cases are 36.
- `G0ExhaustiveTest`: unchanged outcomes, 1,481 conclusive, 21 inconclusive under T21, and 2 allowed by T23 pins.
- `./gradlew :julc-difftest:nightlyDiffTest`: 3,000 tries with seed `7009084437455657768`. 2,996 are conclusive and
  4 inconclusive under T21; 1 min 37 s.

## Revision history

- **r1** (2026-10-23): initial proposal.
- **r2** (2026-10-23): M1 implemented in the same PR. D6 now describes the per-case expectations of saved cases
  (`DIVERGE <id> <JVM kind>/<UPLC kind>`), with the registry kept for generated cases from M2. M1 results and
  findings added.
- **r3** (2026-10-23): M2 implemented: G0 generator, PR and nightly campaigns, exhaustive mode, the
  `KnownDivergences` registry, a bounded UPLC budget, and the nightly workflow. M2 results added, including
  finding T21.
- **r4** (2026-10-28, responds to review round r1 of `ac20ac2d`):
  - F1: saved divergences pin both observations, and registry entries match an exact inconclusive reason; D6
    states what each guarantees.
  - F2: D7 is a bounded grammar of 376 programs on four inputs, with bound, feature and observability checks.
  - F3: quotas count conclusive cases only, with per-configuration outcomes (`Coverage`); D9 separates syntactic
    from exercised coverage, and H5 is restated.
  - F4: the JVM side records each `Builtins.trace` call as one event (D3).
  - F5: D8 records are format 2, with provenance, recorded observations, and budget and size per configuration.
    D2 now states the bounded budget and the measurements.
  - F6: D5 states the implemented G0 and moves the rest to M2b (supported) or G0⁻ (rejected). D4 states the
    implemented mapping. D2 states the configurations. D3 and the trusted components name `ValueMapping`, not
    `TermExtractor`. M2b is new, and M4 and M5 are extended.
  - New findings T22 and T23; the T21 note on the for-each form is corrected. History reordered.
- **r5** (2026-10-28, responds to review round r2 of `d47e7bf4`):
  - F7: a conclusive mismatch passes only as an exact pin, taken from the saved cases; registry rules explain an
    inconclusive UPLC side only. The T23 rule is replaced by four pins, two of them the corpus runs. D6, D7 and D9
    revised.
- **Accepted** (2026-10-28): design accepted at `12f7e4e5` (review round r3), with the stated review of M1 and M2.
