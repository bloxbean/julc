# ADR-068 M3a: the lowering's foundation

M3a is the first slice of M3 ([ADR-068](../068-javac-frontend-migration.md)). It is where javac starts to determine compiled
output: a program written in literals, names and static constants now compiles through `frontend=JAVAC`, to the reference's
FLAT bytes. Everything else stops at `JULC0084`, naming the construct and its position, and never falls back (I8).

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md). Machine and toolchain as in M0's evidence. Branch
`wip/068-m3a`, based at `a0527fb1`, with M2's close merged in at `baef03a7`.

This file states the code at `b9de05ad`. The full suite and the I1 gate ran there. `adr068Baseline`, `adr068Types`, `pairCaseTest`, `onChainHarnessTest` and `wasmSmokeFull` ran at `59fc6488`, whose main sources are the same:
the two commits between add tests. Later commits change documentation only.

## What was delivered

| Piece | Where |
|---|---|
| The assembly seam (F846): statics, helpers, library lets, wrapper, `@Param` lambdas and backend take PIR and resolved types only. The JavaParser path calls the moved code, and `compileMethod`'s copy of the backend's passes is gone | `codegen/ValidatorAssembly.java` |
| The model's discipline (F847): one lock per program, a query returns JuLC's own values, a holder may keep a program and query later | `frontend/javac/JavacProgram.java` (its Javadoc states it), `JavacProgramLifetimeTest` |
| The walking skeleton: every `JulcCompiler` entry point reaches one fork, on `context` | `JulcCompiler.onJavac`, `frontend/javac/JavacCompilation.java`, `JavacDiscovery.java` |
| I2c at its first size: an allow-list of tree kinds, with contextual rules, over the whole root unit | `frontend/javac/subset/SubsetScan.java` |
| The lowering: one dispatch, an immutable `Frame`, a `Scope` keyed by element, one builder of source binders, and the families | `frontend/javac/lower/`: `Lowering`, `Frame`, `Scope`, `Binder`, `Terms`, `Literals`, `Names`, `Statements` |
| I13's check, on construction-time provenance | `lower/BindingCheck.java`, run on every compilation under `julc.verifyBinderNamespace` |
| ADR-060's namespace check on this frontend | `JavacCompilation.verifyBinderNamespace` |
| D14: `JULC0088` is emitted. New: `JULC0093`, an unpaired surrogate in a literal | `diagnostics.json`, `JavacDiscovery`, `SubsetScan` |
| The I1 tool | `julc-difftest`: `adr068Lowering`, `Adr068Lowering`, `Adr068LoweringTest`, `PirCanonical`, and the tables `068/lowering-i1.tsv`, `068/lowering-i1-totals.tsv`, `068/lowering-accepted-differences.tsv` |
| D10's frontend axis: a verdict is per frontend | `DiffConfig.frontend`, `UplcExecutor`'s cache key, `Adr068LoweringHarnessTest` |
| The three lints | `lower/JavacLoweringLintTest`, each with negative controls |

`JavacFacts` stays stateless (r10): binder names live in `Scope`, provenance and positions in `Terms`.

### Rules worth stating

- **What is lowered.** Integer, long, boolean and string literals; `new byte[]{...}` over integer literals; a name javac bound to a
  parameter, `@Param` or static field with an initializer; `BigInteger.ZERO`, `ONE`, `TWO`, `TEN` and `PlutusData.UNIT`;
  parentheses; a method body that is empty or a single `return`. A local declaration, like `if`, calls and operators, is a later
  slice. The API constants and `byte[]` literals are a reading of "literals, names, static constants" in the ADR's order; I1's
  coverage would be poor without them, and each rests on an element, not a spelling.
- **A folded minus.** javac folds a minus sign and a non-zero decimal literal into one literal: `-5`, `- 5`, `-/*c*/5`,
  `-2147483648`. `-0`, `-0x10`, `-017` and `-(5)` stay `UNARY_MINUS`, which the plan's note did not say. The reference lowers all of
  them as `0 - n`, so the folded ones keep that term. A negative value does not identify them, since `0xFFFFFFFF` is -1, so the
  test is the literal's first character as javac reads it, Unicode escapes included (`\u002d5`).
- **Scope and I13.** `Scope` is an immutable chain from `Element` to `Binder`, so a name cannot resolve by spelling. It is seeded
  with `@Param` and static binders before any body. A parameter shadowing a static takes `K'1`; the reference keeps `K`, and I1b
  compares up to alpha-equivalence, so this is not a difference.
- **Provenance over the strict transform.** `StrictRecordEntrypoint` rebuilds an entry point's lambdas, so their identity-keyed
  provenance would be lost. `BindingCheck` found this on its first run. `Terms.rebuiltLambdas` carries it across.
- **Rejection order.** `JavacCompilation`'s steps are the reference's, numbered as in `JulcCompiler`: the type registration moved
  out of `JavacProgram.analyze` so a missing validator annotation still comes first. The discovery rejections have the reference's
  code, words and position. `SubsetScan` runs first, in the subset validator's place, so `JULC0084` comes before them in a source
  with both.
- **Annotations by identity.** `@Entrypoint`, `@Param` and the validator annotations are matched by `TypeElement`, as `@NewType` is
  (D7). An `Optional` datum is `PirType.OptionalType`, not the spelling `Optional...`.
- **`@Param` initializers are not lowered,** as in the reference, but the scan reads them. So D14's remedy `Boolean.valueOf(true)`
  is `JULC0084` until calls are ported; a non-`final` `@Param` needs no such initializer.

## Commands

```sh
# M0 = /Users/satya/work/bloxbean/julc-068-m0/build

# The I1 gate. Every row is compared with adr/evidence/068/lowering-i1.tsv, in both directions.
./gradlew :julc-difftest:adr068Lowering -Padr068.corpus=<M0>/julc-corpus -Padr068.corpus.external=<M0>/corpus-external \
    -Padr068.corpus.elci=<M0>/corpus-elci -Padr068.corpus.head=<M0>/m2-corpus-head -Padr068.out=<dir> --offline --no-watch-fs

# -Padr068.freeze=true writes the tables, and fails if a row lost coverage or left the corpus, unless -Padr068.allowShrink=true.

# The default path against B1.
./gradlew :julc-difftest:adr068Baseline -Padr068.corpus=<M0>/julc-corpus -Padr068.corpus.external=<M0>/corpus-external \
    -Padr068.corpus.elci=<M0>/corpus-elci -Padr068.external=<M0>/ext-examples -Padr068.out=<dir> --offline
```

## The I1 tool

For each case the tool compiles with the reference (the JavaParser frontend, gate off) and with `frontend=JAVAC`:

- **I1a.** Where both accept: FLAT digest and size at all four levels, diagnostics, `@Param` infos, FLAT again with a source map, and
  each located term's line, column and file.
- **I1b.** Every method the reference lowers, library methods included, lowered on its own on javac, so a method is compared even
  where another one stops the program. Term and type must agree up to alpha-equivalence: `PirCanonical` replaces bound names by their
  depth, and is exhaustive over `PirTerm` by the compiler.
- **The reference is checked as it runs.** Each reference cell must equal the committed `ledger-b1.tsv`; a difference is
  `REFERENCE-DRIFT`, which fails.

M2's lessons are its shape:

- Each case gets a class, and every row must equal the committed table, in both directions. So a case that moves class, gains or loses
  a covered method, changes its first construct, or leaves the corpus fails.
- No class is tolerant. Each says what happened; `reference-rejects-not-ported` is a row like any other, so a frontend that rejects
  more moves rows.
- Every `DIFFERENT` row must be listed by key in `lowering-accepted-differences.tsv` with its D13 class, and a stale entry fails.
- A freeze refuses to lose coverage or cases without `-Padr068.allowShrink=true`.

The corpus is M2's, the frozen recordings and the head recording, plus four fixtures of M3a's, because the recorded corpus holds few of
the slice's shapes in a program it covers.

| Class | Cases | Meaning |
|---|---:|---|
| `i1a-identical` | 145 | Both accept: FLAT at every level, diagnostics, `@Param` infos, FLAT and positions with a source map, and every method's term and type |
| `not-ported` | 9,617 | The reference accepts; `JULC0084`. M3 and M4's worklist |
| `reference-rejects-not-ported` | 498 | The reference rejects; `JULC0084` stands in front of the rule that will reject it |
| `javac-rejects` | 5,359 | The reference accepts; javac rejects: M0's dialect sources, each with a rewrite (M1) |
| `both-reject-javac-first` | 1,288 | Both reject, javac first (I2b) |
| `both-reject` | 49 | Both reject with one code and message, and one position where the reference gave one |
| `ledger-source` | 38 | A ledger file's row, which compares nothing itself |
| `unreplayable` | 17 | A custom lookup or target, as in the ledger |
| `DIFFERENT-javac-accepts` | 18 | Listed below |
| `DIFFERENT-rejection` | 8 | Listed below |
| `DIFFERENT-bytes` | 2 | Listed below |
| `DIFFERENT-provenance` | 2 | Listed below |
| Total | 17,041 | |

### Coverage

| | Covered | Of | |
|---|---:|---:|---|
| Programs the reference accepts | 145 | 15,125 | 0.96% |
| Their distinct scripts | 85 | | |
| Programs of more than one method | 11 | | |
| Methods the reference lowers, library methods included | 307 | 170,997 | 0.18% |
| Distinct covered methods | 156 | | |

**What a number means.** M5's gate is zero `JULC0084`, and this is where that starts: under 1% of programs. Most covered programs are
`return true` or `return x`.
Of the 125 distinct recorded sources among them, 71 only return `true` or `false`, 42 return a string literal, and 7 return a name; 5
declare a `@Param`, one a static field, one a `byte[]` literal, and one reads an API constant.
Static fields, `@Param` and most literal shapes are in the recorded corpus only a handful of times, so those rest on M3a's fixtures
and tests. `first-construct` in the totals is the worklist: what stops the most methods.

| First construct | Cases |
|---|---:|
| `VARIABLE` | 5,903 |
| `LESS_THAN_EQUAL` | 2,172 |
| `METHOD_INVOCATION` | 1,074 |
| `GREATER_THAN` | 224 |
| `IF` | 215 |
| `SWITCH_EXPRESSION` | 178 |
| `NEW_CLASS` | 94 |
| `EXPRESSION_STATEMENT` | 43 |
| `the exports of library sources` | 35 |
| `EQUAL_TO` | 31 |
| `CONDITIONAL_AND` | 28 |
| `CONDITIONAL_EXPRESSION` | 20 |

`VARIABLE` is a local declaration. `LESS_THAN_EQUAL` is `a.compareTo(b) <= 0`, nearly all of it one family of ADR-060 tests that compile
one small validator under many names, so that count says little about variety. `the exports of library sources` is a provider's.

### The differences

All listed, by key, in [`068/lowering-accepted-differences.tsv`](068/lowering-accepted-differences.tsv).

| Rows | Class | D13 class | What |
|---:|---|---|---|
| 8 | `DIFFERENT-javac-accepts` | Accepted by decision | The ADR-065 I2c escape shapes M0 scheduled. The reference's reader rejects each; `Adr068LoweringHarnessTest` runs each on the JVM and through JAVAC at every level, and they agree. They are in-code cases, not saved `.diffcase` files, which D13 asks for |
| 4 | `DIFFERENT-javac-accepts` | Accepted by decision | D8's precedence: a root and a pool unit declare one name, so the root's wins and the shadowed unit is not read. The reference says `Duplicate record type`. The program needs nothing of that unit |
| 4 | `DIFFERENT-javac-accepts` | **Needs a decision** | A rejection of a library unit the root does not reference, so javac never reads it (D8): `Library source must not contain validator annotation` (3), `must not combine` (1). It cannot reach the script, but no decision names it |
| 2 | `DIFFERENT-javac-accepts` | **Needs a decision** | A lone surrogate in a comment. M0's inventory marks these permanent, and the plan was to reject them in a literal, which a comment is not. The JVM agrees |
| 5 | `DIFFERENT-rejection` | Diagnostic change | A lone surrogate in a literal: uncoded in the reader, now `JULC0093` at the literal |
| 3 | `DIFFERENT-rejection` | Diagnostic change | F809: a type rejection with javac's spelling of a name (`java.lang.String`), and a position the reference lacks. Uncoded both ways; the code comes with the subset port |
| 1 | `DIFFERENT-bytes` | Semantic fix | #286, T33: `M2Sealed` nests a user record `PolicyId`. The reference reads it as a ledger hash, so its datum check expects bytes; javac binds the record. M2's fixture; the saved pin `diverge-t33-packageless-pubkeyhash-record` needs records to run, so this is not yet observed against the JVM |
| 1 | `DIFFERENT-bytes` | Representation only | D8: the reference binds a library it is given and the root does not use, and at `NONE` keeps the unused `let`; 44 against 37 bytes. The other levels agree |
| 2 | `DIFFERENT-provenance` | Provenance change | For a source with no file, the reference names the file after its first class or interface, the javac frontend after its primary type |

**Provenance (I7).** Of the 145 identical programs, 86 have equal source maps and 57 differ only in a fragment's text, by M2's rule (the source as written, where the reference prints its own tree); every line and column is equal. 2 are providers, which have no map.

## Results

| Check | Result |
|---|---|
| Byte identity against the reference | 145 programs, at all four levels, with and without a source map. The 2 `DIFFERENT-bytes` rows are classified above |
| I1b | 307 methods equal the reference's term and type up to alpha-equivalence, 156 of them distinct |
| I13's binding check | Runs on every compilation in tests, the gate included, and on each method lowered on its own, over construction-time provenance. No violation at the head. On its first run it found the strict transform's lost provenance |
| ADR-060 | `GeneratedBinderNameLintTest`, `BinderNameIndependenceTest` and `julc.verifyBinderNamespace` stay green, the last on this frontend too |
| Evaluation against the JVM | `Adr068LoweringHarnessTest`, 58 tests. 46 run a covered program on the JVM and, at all four levels, through both frontends on the VM: each agrees with the JVM, and value, traces, cost and script size agree between the frontends, the last two being what equal bytes give. 10 are accepted shapes the reference rejects, which agree with the JVM on JAVAC alone. 2 check that a program not ported is rejected, and that the frontend is in the cache key |
| D14 | `@Param` constant variables are `JULC0088`, with tests for each kind, instance fields and multiple declarators; a `@Param` javac cannot fold compiles to the reference's bytes |
| The three lints | In `JavacLoweringLintTest`, each with negative controls that make it fail |
| The gate's determinism | 2 threads and the default wrote the same table, at `7b8137e1` |
| The default path, gate off, against B1 | `ledger-b0.tsv`: 11,415 rows shared, 7 M1b's re-keyed Blaster fixtures, 0 rows differ under a shared key. `corpus-b0.tsv` alike. `ledger-b0-semantic.tsv` (1,872 lines), `-budget` (1,871), `-instantiations` (50) and `rejection-inventory-b0.tsv` (1,835) are byte-identical. `totals-b0.tsv` differs in one line, the rejection catalogue: 149 sites, not 146, which are `JULC0088`, `JULC0092` and `JULC0093`, none a reference rejection |
| M2's gate, `adr068Types` | 0 rows moved |


### Tests

Counted from JUnit XML.

| Task | Result |
|---|---|
| `./gradlew test --offline --continue --rerun --no-watch-fs` | 13,055 tests, 0 failures, 0 errors, 530 skipped. M2's close had 12,923 |
| `:julc-compiler:pairCaseTest --rerun` | 71 tests, 0 failures |
| `onChainHarnessTest --rerun` | 16 tests, 0 failures |
| `:julc-wasm:wasmSmokeFull --rerun` (GraalVM 25.3.4.1) | `BUILD SUCCESSFUL`; `wasmImageFull` rebuilt; 56 `ok` lines, no `not ok`. It runs the default frontend |
| `adr068Lowering` | `BUILD SUCCESSFUL`; 0 rows moved, 0 shrunk |
| `adr068Types` | `BUILD SUCCESSFUL`; 0 rows moved |
| `adr068Baseline`, gate off | As in the table above |

The first full run, on the commit before, had 4 failures of 13,040, none a flake. One was a regression of this slice: on a runtime with no compiler API, `JavacCompilation` failed to link outside the guard, so the host got `NoClassDefFoundError` and not `JULC0089` (I6). `JavacGateEnvironmentTest` caught it; the guard now wraps the call from `JulcCompiler`. The other three were `Adr068LoweringToolTest`: a wrong expectation, and a tool label that called a source javac rejected "a library method", which had inflated that line of the worklist by thousands.

## Mutation evidence

Each mutation breaks one behaviour in the source, in a throwaway worktree, then runs, with `--no-watch-fs` and cleared XML: the
`frontend.javac` tests with `GeneratedBinderNameLintTest`, the harness test, and the I1 gate. It is reverted after. A gate cell is
`moved rows`.

Every mutation below ran at `fbc37706`, and D16, D19 and L11 again at `b9de05ad`, each pass after a no-op control through the same script (at the last, unit 0/476, harness 0/58, gate moved 0). 65 mutations; **none survives**.

The control is there because an earlier pass misled. It ran at a commit where `JavacGateEnvironmentTest` already failed, so every mutation seemed to fail a unit test. Comparing two passes showed each count off by one, and four mutants had in fact survived: with no provenance recorded I13's check passes everything; the raw-return test stopped at an earlier boundary and never reached the return check; no entry point returned anything but a boolean under a source map; and the scan hid lowering's own guard. Each now has a test (`LoweringAloneTest`, and two others). The controlled pass then found two more, which also have tests: nothing tested the native check at `compileMethod`'s boundary, and the array guard was tested only with a shape the mutant still caught. D16, D19 and L11 show their last run, after those tests.

**Literals**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| L1: folded minus kept as a negative constant, not 0 - n | 11 | 8 | 1 |
| L2: a negative value taken for a folded sign (0xFFFFFFFF) | 7 | 4 | 1 |
| L3: boolean literal inverted | 17 | 3 | 127 |
| L4: string literal altered | 1 | 2 | 7 |
| L5: a negated byte element loses its sign | 1 | 1 | 1 |
| L6: text block accepted | 1 | 0 | 0 |
| L7: a long literal truncated to int | 3 | 3 | 1 |
| L8: first byte element dropped | 3 | 3 | 3 |
| L9: folded negation doubly negative | 11 | 8 | 1 |
| L10: a Unicode-escaped sign or quote not read | 3 | 1 | 0 |
| L11: array literal check relaxed in lowering | 3 | 0 | 0 |

**Names and constants**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| N1: a name found by its simple name | 2 | 0 | 0 |
| N2: no mark on a clash (I13) | 4 | 0 | 0 |
| N3: not the smallest k | 4 | 0 | 0 |
| N4: constant matched by simple name | 1 | 0 | 0 |
| N5: a variable qualifier accepted | 1 | 0 | 0 |
| N6: any spelling of the constant accepted | 2 | 0 | 0 |
| N7: ZERO is one | 1 | 1 | 2 |
| N8: TEN is two | 2 | 3 | 1 |
| N9: UNIT has tag 1 | 1 | 0 | 2 |
| N10: undefined field reported as not ported | 2 | 0 | 0 |
| N11: parentheses not lowered | 6 | 2 | 1 |

**Statements**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| S1: entrypoint return guard off | 11 | 0 | 94 |
| S2: guard on non-boolean return | 3 | 0 | 0 |
| S3: return position lost | 8 | 0 | 33 |
| S4: method position lost | 84 | 0 | 57 |
| S5: native boundary unchecked | 1 | 0 | 0 |
| S6: parameters reversed | 17 | 1 | 114 |
| S7: empty body not unit | 2 | 0 | 23 |
| S8: return; not unit | 1 | 0 | 1 |

**Discovery and coordination**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| D1: D14 removed | 6 | 0 | 0 |
| D2: spending parameter count unchecked | 2 | 0 | 0 |
| D3: datum never bound | 1 | 0 | 5 |
| D4: optional datum ignored | 1 | 0 | 3 |
| D6: role conflict accepted | 1 | 0 | 1 |
| D7: banned @Param type accepted | 1 | 0 | 8 |
| D8: purpose tags swapped | 1 | 0 | 9 |
| D9: entrypoint by name | 16 | 0 | 13 |
| D10: compileMethod target may be an interface | 2 | 0 | 1 |
| D11: a read library ignored | 1 | 0 | 7 |
| D12: instance methods lowered as helpers | 0 | 0 | 1 |
| D13: @Param initializer lowered as a static | 4 | 0 | 0 |
| D14: overload check removed | 2 | 0 | 0 |
| D15: mixed purposes accepted | 1 | 0 | 1 |
| D16: native value at compileMethod boundary | 3 | 0 | 0 |
| D17: provenance lost over the strict rebuild | 1 | 0 | 1 |
| D18: @Param not in scope | 9 | 0 | 20 |
| D19: attempts ignore the guard (tool) | 0 | 0 | 8 |
| D20: method bound under a wrong name | 84 | 56 | 63 |
| D21: validator statics not bound | 3 | 0 | 3 |

**The allow-list**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| X1: the allow-list off | 27 | 0 | 3,590 |
| X2: float accepted where not lowered | 5 | 0 | 1 |
| X3: inheritance accepted | 2 | 0 | 3 |
| X4: this accepted where not lowered | 2 | 0 | 1 |
| X5: lone surrogate accepted | 3 | 0 | 5 |
| X6: any statement in unlowered code | 1 | 0 | 5,900 |
| X7: any array in unlowered code | 4 | 0 | 0 |
| X8: generated constructor scanned | 179 | 56 | 10,257 |
| X9: java.lang.Object by spelling | 2 | 0 | 0 |

**Assembly**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| A1: statics bound in reverse | 2 | 1 | 24 |
| A2: @Param lambdas reversed | 0 | 0 | 1,136 |

**Binding check**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| B1: binding check off | 6 | 0 | 0 |
| B2: no provenance recorded | 1 | 0 | 0 |

**Model lifetime**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| E1: model queries not exclusive | 1 | 0 | 0 |

**Rejection anchors**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| F1: JULC0091 anchored at the declaration | 0 | 0 | 13 |

21 of them fail unit tests only: L6, L11, N1, N2, N3, N4, N5, N6, N10, S2, S5, D1, D2, D13, D14, D16, X7, X9, B1, B2, E1. That is the limit of the corpus and fixtures: a rule that rejects (D1, D2, the scan's array rule) has no accepted row to move, I13's check (B1, B2, N2, N3) speaks only when something is wrong, and E1 is about threads. They rest on the test alone. D12, D19, A2, F1 fail the gate alone: A2 is in shared assembly, where comparing the frontends sees nothing and the reference's cells against B1 see it; F1 is a rejection's anchor, and D12 a method only the gate's I1b compares.

### The gate's own failure modes

Each simulation is one source change, and every row below fails the gate.

| Simulation | Unit tests | Harness | Rows moved | Rows that lost coverage |
|---|---:|---:|---:|---:|
| Reject everything | 181 | 56 | 10,301 | 143 |
| Reject sources holding `JulcList` | 1 | 0 | 7,518 | 3 |
| Reject sources holding `sealed` | 2 | 0 | 3,148 | 11 |
| Reject sources holding `static final` | 22 | 3 | 1,457 | 2 |
| The frozen reference changes | 17 | 3 | 2,698 | 128 |
| Reject a covered construct with a JuLC-looking error | 20 | 3 | 135 | 126 |

On the tables and inputs:

| Change | Outcome |
|---|---|
| Control: untouched | passes; moved 0, shrunk 0 |
| A stale accepted entry | fails; moved 0, shrunk 0 |
| An accepted entry removed: an unlisted difference | fails; moved 0, shrunk 0 |
| Every accepted entry removed | fails; moved 0, shrunk 0 |
| A frozen row removed | fails; moved 1, shrunk 0 |
| A phantom row: covered and never observed | fails; moved 1, shrunk 1 |
| A frozen row with another class | fails; moved 1, shrunk 0 |
| The head recording left out: the corpus shrinks | fails; moved 5,617, shrunk 5,617 |
| A freeze that loses coverage, the differences unchanged | Compare mode fails on 3 rows; the freeze is refused and the table is untouched; with `-Padr068.allowShrink=true` it is written |

A first attempt at the last row proved nothing: it simulated reject-everything, and the accepted-differences check failed first, so the
shrink check never ran. The row above changes coverage alone, and fails at the shrink check's line.


## What this does not establish

- **A program that computes anything.** A covered program returns a literal, a name or a constant. No operator, call, local or branch has
  been lowered, so byte identity here says nothing about those, and most of what can go wrong (evaluation order, state, exits) is ahead.
- **Most of the corpus.** 99% of programs stop at `JULC0084`, and the recorded corpus holds one covered static field and five `@Param`
  cases. Those rest on M3a's fixtures and tests, which are hand-written and so see only what their author thought of.
- **I2a's inventory.** It is not run on this frontend. `JULC0084` stands in front of `SubsetValidator`'s rules, so a source the subset
  rejects is rejected, usually not with the reference's code. Discovery's rejections are compared in `JavacRejectionsTest` and in the
  gate's `both-reject` rows, whose count shows how few there are yet.
- **Rejection order in general.** The steps are in the reference's order, and `SubsetScan` takes the subset validator's place, but a
  source with two rejections is tested only where a test has one.
- **I13 under pressure.** No continuation is duplicated yet and no element is bound twice, so the check has run on easy terms: the shadowing
  it sees is a parameter over a field. Its negative controls are hand-built terms.
- **F847 in use.** Nothing holds a program past a compilation yet, since specialization is not ported. A later thread's query is tested, and
  exclusion is shown by a blocked thread, but no real provider does this.
- **#284 and #286's values.** The pins need records, so they still end in `JULC0084`. `M2Sealed`'s row is a byte difference with a
  reason, not a JVM observation.
- **D13's evidence for the accepted shapes.** They run on the JVM in `Adr068LoweringHarnessTest`, as in-code cases, not as saved
  `.diffcase` files. The D8 precedence rows have no JVM run of their own.
- **Fragments.** Lines and columns are compared at the default level; a fragment's text is counted, not compared.
- **Images and hosts.** `wasmSmokeFull` runs the default frontend, so the lowering has not run in a native or Web image, and no host
  exposes the option. Elci was not rebuilt.
- **JDK patch releases.** Two thread counts wrote one table; two JDK builds were not compared.
- **DevKit end-to-end tests** were not run. Nothing here changes a script on the default frontend.

## What the next slice inherits

- **Add to `Lowering.expr`** and to `SubsetScan.ALLOWED` together. A kind on the allow-list that lowering does not reach is accepted
  if it is in code nothing lowers; the scan's Javadoc says what that promises.
- **`SubsetScan` must end as `SubsetValidator`'s rules with their codes** (I2c). Today it stops some things the reference accepts, such as a
  loop in an instance method, because it fails closed.
- **I13's per-method table** comes with the first duplicated continuation: `Scope.bind` chooses from what is visible, and no element is
  bound twice yet.
- **`requireNoMethodCall`** has no javac side, since calls are `JULC0084`. Port it with calls, at step 11b.
- **Statements.** `block` handles an empty body or one `return`, and says so; `generateStatements` is next. Keep the `#_` and `#_if` lets.
- **Parameter names at the boundary.** `ValidatorAssembly.Parameter` has a source name and a binder name, because the strict transform
  builds on the binder. Records will exercise that path for real; here it runs only over empty rewrites.
- **The tables move with every construct.** Regenerate them with `-Padr068.freeze=true` and read the diff: a row may only gain.
- New known issues are in [`068-followups.md`](068-followups.md).
