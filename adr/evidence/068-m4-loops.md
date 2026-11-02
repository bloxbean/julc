# ADR-068 M4: for-each, `while`, `break` and accumulators

M4 is the loops' milestone of [ADR-068](../068-javac-frontend-migration.md), taken before the libraries and Elci at the maintainer's request, so M3 is not fully closed. For-each, `while`, `break`,
accumulators and the join of an `if` around a loop now compile through `frontend=JAVAC`, ported from the reference, `pir/LoopBodyGenerator` and `pir/PirGenerator`. ADR-061 owns the design of loops, and this
slice redesigns nothing. A library unit still stops at `a library unit`, nothing falls back (I8), and the JavaParser frontend did not change.

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md). Branch `wip/068-m4`, from `e0d6eefc`. Machine and toolchain as in M0's evidence, with `25.0.2-librca`.

## What was delivered

| Piece | Where | Lines |
|---|---|---:|
| For-each and `while`: rules, iterable, accumulators, condition, what follows | `lower/Loops` | 299 |
| A body with accumulators, one walk for the reference's four generators | `lower/LoopBody` | 235 |
| The accumulators as one value: one binder or a tuple | `lower/Carried` | 115 |
| Which variables a loop or an `if` around a loop carries, as elements | `lower/Accumulators` | 78 |
| What a Data accumulator of a `while` holds in a tuple | `lower/AccumulatorTypes` | 180 |
| The reads a pre-loop rebinding is ordered by | `lower/PreLoopReads` | 72 |
| What a loop must be, in the reference's order | `subset/LoopRules` | 286 |
| `JULC0056`, a field updated in a loop | `subset/FieldUpdates` | 111 |
| ADR-050's join | `Ifs.ifWithLoop` | |
| An assignment and a compound assignment as a stored value, in Java's order | `Operators.assigned` | |
| An assignment no loop bound | `Lowering.reassignment` | |
| Loops as statements, and `local` shared with a body | `Statements` | |
| What a loop built by `LoopDesugarer` binds, for I13 | `Terms.loop`, `unpacked`, `rebound` | |
| `Frame.bindsUpdates` for the reference's accumulator state | `lower/Frame` | |
| An assignment's target in a tuple's destination | `Tuples.writtenAt` | |
| A hash read from a for-each's variable | `CallTargets.isLoopVariable` | |
| `break` outside a loop, `for`, `do`, `++` and `--` | `SubsetScan`, `Operators.unary` | |

The reference's four generators differ by one accumulator or several and by `break` or none. `Carried` holds the first difference and `LoopBody.Shape` the second, so one walk has each difference at one site, and each is
the reference's. `shadowingRenamed` and `withLocalsRenamed` have no port: a binder takes another name from `Scope` where a name is visible, and a bare block is spliced with nothing renamed.

### What a loop lowers to

```
for (x : xs) { fold }     letrec loop = \xs acc. if null xs then acc else loop (tail xs) (let x = decode(head xs) in body) in loop items acc
for (x : xs) { break }    letrec loop = \xs acc. if null xs then acc else let x = decode(head xs) in body in loop items acc
while (c) { fold }        letrec loop = \acc. if c then loop body else acc in loop acc
while (c) { break }       letrec loop = \acc. if c then body else acc in loop acc
```

In the `break` shapes the body makes the recursive call itself, and a `break` is the accumulator alone. Several accumulators travel as a list of Data under one name, `#__acc_tuple`, each encoded by its type.

| Question | Answer |
|---|---|
| What the variable holds | The type javac gave it, as written or inferred. An element decodes by that |
| What a read of the variable sees | The element of the iterable as the reference sees it (`CallTargets.seen`), so a member read stays `JULC0055` there (I2d) |
| Which variables are accumulators | The elements a statement of the body assigns that are visible before the loop, in order of first assignment. No name decides it |
| What a `while`'s tuple holds | `AccumulatorTypes`: a Data accumulator's builtins say a list or a pair list. The call is the element javac bound |
| What the body reads an accumulator as | Its declared type. Only the tuple, the condition and what follows read the refined one, as in the reference |
| What a nested loop's result is read at | The enclosing frame's types, which are not refined, as in the reference |

### Evaluation order

`Loops` and `LoopBody` state it. Java evaluates a for-each's iterable once, then each iteration's element and body, and a `while`'s condition before each body. Each term here does so: the arguments of the recursive call
are evaluated strictly, so a body runs before the next iteration, and the result is bound by a strict `let` before what follows the loop. A path that does not `break` runs what follows the `if`, then the next iteration
(T21, T23). The target of `x += y` is read before `y`.

### A `break` under an `if`

| Shape | Term |
|---|---|
| No branch breaks | The branches are fold bodies. What they leave is rebound for what follows |
| No branch breaks, nothing follows, one accumulator | The next iteration of the `if`'s value |
| No branch breaks, nothing follows, several | Each branch goes on by itself, as the reference does |
| At most one path reaches what follows | It stands in place |
| Several do | It is bound once, `let #joinN = \acc. rest`, and each such path calls it |

### Where the reference's rules stand

| Rule | Code | Where | Order |
|---|---|---|---|
| A label, wherever it is in the loop | `JULC0066` | `LoopRules` | First |
| `continue`, wherever it is | `JULC0060` | `LoopRules` | Second |
| An update of a body's local across an `if` | `Conditional update to loop-body local` | `LoopRules` | Third |
| `return`, `yield` in a body | uncoded for a for-each, `JULC0003` for a `while` | `LoopRules` | Fourth |
| A field updated in a loop | `JULC0056` | `FieldUpdates` | Once the accumulators are known, and after each method |
| A for-each with no accumulator reassigns its variable or a local | `JULC0058` | `LoopRules` | With no accumulators |
| `break` with no accumulator | `JULC0063`, `JULC0065` | `LoopRules` | After that |
| A nested loop under a `break` with one accumulator | `JULC0064` | `LoopBody.nested` | While lowering |
| An assignment in an expression in a body | `Assignment to ... is not supported at this position` | `Lowering.reassignment` | While lowering |
| A C-style `for`, `do`, `break` outside a loop | `JULC0018`, `JULC0019`, uncoded | `SubsetScan` | Before lowering |
| `i++` and the like | `Unsupported unary operator` | `Operators.unary` | While lowering |

### What javac decides (D11)

| Shape | Reference | `JAVAC` |
|---|---|---|
| A statement after `break` | The warning `Unreachable code after break` | javac rejects it, `JULC0085`. So `warnUnreachable` needs no `break`, and the follow-up's row closes that way |
| An update of a `final` field in a loop | `JULC0056`, `the field is final` | javac rejects it, so `FieldUpdates` has no such line |
| `break` or `continue` outside a loop and a `switch` | Uncoded rejections | javac rejects it, `JULC0085` |

`JULC0056` asks what a method reads. The reference takes free names of the term with identity `let`s removed. Here a read is what javac bound a name to, on the method's tree, so a shadowing local, parameter or pattern variable is
none by construction, and no term is read. `FieldUpdates` has no state beyond what has lowered, and a call is by binder, which overloads share.

## Decisions to check

1. **A local after a loop that shadows a field has its own name.** The reference's binder there is `K`, so its pre-loop rebinding writes one `let K = K` for what is a local's read. Here the binder is `K'1`, as I13 asks. To write
   the same `let`, the port would have to know which blocks the reference renames. Two rows of the corpus show it, each smaller by that `let`, and semantic observations are equal. See `JavacLoopsTest.aLocalThatShadowsAFieldAfterALoop`.
2. **An element the reference takes for Data decodes.** `for (var x : JulcList.of(a, b))` and a for-each over `xs.map(...)` hold Data to the reference, and arithmetic on `x` fails on-chain where Java returns. Here the element decodes
   by javac's type, and `anElementTheReferenceTakesForData` holds it to the JVM. `x.longValue()` on such a variable stays `JULC0055`, so the subset does not widen. An element nothing reads costs one `UnIData`: one row of the corpus.
3. **A library compiled as a root stops at a call of its own method,** `a call of a library unit's own method`. On a root of a library, `MathLib.floorDiv` was `Undefined variable: MathLib`, a rejection with no decision behind it, which
   ten rows reached once their `while` lowered. The reference finds that method in its pool, so this is the library slice's to answer.
4. **`if (c) ;`** was a rejection with a diagnostic where the reference has the located text. `Statements.single` now takes the reference's form, and a loop's body uses it too.
5. **`JULC0012` on a tuple of accumulators has the loop's position** (F724), where the reference has none. No row of the corpus reaches it.

## The I1 gate

`build/m4/i1.sh` against the tables frozen at `e0d6eefc`, whose compare fails on moved rows by design until the integrator freezes. Nothing shrank: `lowering-i1-shrunk.tsv` is empty. No row has a loop as its first construct.

| Class | `e0d6eefc` | M4 |
|---|---:|---:|
| `i1a-identical` | 2,513 | 5,950 |
| `not-ported` | 7,132 | 3,647 |
| `reference-rejects-not-ported` | 345 | 101 |
| `javac-rejects` | 5,359 | 5,359 |
| `both-reject-javac-first` | 1,288 | 1,288 |
| `both-reject` | 193 | 435 |
| `ledger-source` | 38 | 38 |
| `unreplayable` | 17 | 17 |
| `DIFFERENT-javac-accepts` | 27 | 27 |
| `DIFFERENT-javac-rejects` | 86 | 131 |
| `DIFFERENT-rejection` | 18 | 20 |
| `DIFFERENT-bytes` | 15 | 18 |
| `DIFFERENT-pir` | 33 | 33 |
| `DIFFERENT-provenance` | 7 | 7 |
| `DIFFERENT-diagnostics` | 5 | 5 |
| Total | 17,076 | 17,076 |

### Every class of moved row

5,635 rows moved, and each of the 17,076 is still present.

| Rows | From | To | Why |
|---:|---|---|---|
| 2,994 | `not-ported`, `ENHANCED_FOR_LOOP` | `i1a-identical` | Compiles to the reference's bytes |
| 443 | `not-ported`, `WHILE_LOOP` | `i1a-identical` | The same |
| 1,426 | `not-ported`, `WHILE_LOOP` | `not-ported`, `a library unit` | Its loops lower, and a library stops it |
| 319 | `not-ported`, `ENHANCED_FOR_LOOP` | `not-ported`, `a library unit` | The same |
| 78 | `not-ported`, `ENHANCED_FOR_LOOP` | `not-ported`, `TYPE_CAST` | A later construct |
| 34 | `not-ported`, `TYPE_CAST` | `not-ported`, `TYPE_CAST` | More methods covered |
| 10 | `not-ported`, `WHILE_LOOP` | `not-ported`, `a call of a library unit's own method` | Decision 3 |
| 7 | `not-ported`, `ENHANCED_FOR_LOOP` | `not-ported`, `an equals between unrelated types` | A later construct |
| 3 | `not-ported`, `ENHANCED_FOR_LOOP`, `WHILE_LOOP` | `not-ported`, `debug metadata`, `TYPE_CAST`, `an expression of type java.lang.Object` | A later construct, one row each |
| 245 | `reference-rejects-not-ported` | `both-reject` | The reference's rejection, now reached: 163 for-each, 76 `while`, and one each of `break`, `for`, `do` |
| 27 | `reference-rejects-not-ported` | `reference-rejects-not-ported` | Stops at a later construct |
| 45 | `not-ported` | `DIFFERENT-javac-rejects` | Below |
| 2 | `DIFFERENT-javac-rejects` | `DIFFERENT-javac-rejects` | More methods covered |
| 3 | `not-ported` | `DIFFERENT-bytes` | Below |
| 2 | `reference-rejects-not-ported` | `DIFFERENT-rejection` | Below |

`DIFFERENT-javac-accepts` and `DIFFERENT-pir` did not move, so no row of the corpus widened, and no loop method lowered to another term. The 33 `DIFFERENT-pir` rows are `HelperCallers`', from lambdas.

### New `DIFFERENT-*` rows

[`068/m4-accepted-differences.tsv`](068/m4-accepted-differences.tsv) holds the 50, one row each, in the table's format. It was checked against the run: stale entries 0, unlisted 0 once added.

| Rows | Class | D13 | Rule |
|---:|---|---|---|
| 39 | `DIFFERENT-javac-rejects` | Policy rejection (D6) | `==` between `BigInteger`s, in a program a loop stopped before |
| 6 | `DIFFERENT-javac-rejects` | Policy rejection (D6) | `.equals` on `byte[]`, which M0's scan lists |
| 2 | `DIFFERENT-bytes` | Cost change | Decision 1. 136 to 133 bytes and 231 to 228 at the lowest level. Smaller |
| 1 | `DIFFERENT-bytes` | Cost change | Decision 2. 153 to 154 bytes: `for (var i : JulcList.of(x))` with `i` unread |
| 2 | `DIFFERENT-rejection` | Diagnostic change | Two errors in one program: the reference collects one and throws the other, and this frontend stops at the first (F946) |

### Coverage

| | Covered | Of | | `e0d6eefc` |
|---|---:|---:|---|---:|
| Programs the reference accepts | 5,950 | 15,117 | 39.4% | 2,513 |
| Their distinct scripts | 1,744 | | | 978 |
| Programs of more than one method | 2,860 | | | 1,634 |
| Methods | 17,468 | 172,429 | 10.1% | 11,708 |
| Distinct methods | 3,556 | | | 2,595 |

What each program first stops at, with `e0d6eefc`'s in brackets: `a library unit` 3,335 (1,566), `TYPE_CAST` 315 (233), `reference equality` 77 (38), `the exports of library sources` 35 (35), `a library method` 23 (17), `an equals
between unrelated types` 21 (14), `a contract schema` 15 (15), `a call of a library unit's own method` 10 (0). `ENHANCED_FOR_LOOP` was 3,594 and `WHILE_LOOP` 2,002, and neither is on the list now.

### Rows a library unit leaves stopped

3,335 rows stop at `a library unit` as their first construct, and 1,769 of them moved there from a loop in this slice. Their loops lower when a method is lowered alone, and I1b compares each such method, but the program waits for Group 3.

I1b reads the methods of a stopped program too, so a loop in one is held to the reference's term wherever it lowers.

## Tests

| Run | What | Result |
|---|---|---|
| `JavacLoopsTest` | Rows against the reference, bytes at all four levels with and without a source map, and rejections by code, message and position | 196 |
| `Adr068LoopsHarnessTest` | Against the JVM, through each frontend at every level, on value, traces, failure, cost and size | 76 tests, 465 runs agreed |
| `Adr068LoopsPropertyTest` | `LoopPrograms`, a plain Java model, the JVM and both frontends, 300 tries, read back on-chain | No disagreement |
| `Adr068LoopsFuzzTest` | The same under Jazzer, 12 seeds | 793 runs in 61 s, no crash |
| `Adr068LoopCasesOnJavacTest` | 50 saved cases with a loop on their expectation, and G0's 376 programs on 4 inputs | No disagreement |
| `LoopBreakContinuationTest` | The loop-break continuation suite, now on both frontends, on two VMs | 3 tests |

Each shape the property needs was reached: `break` 118 tries, `for` 223, `while` 177, an `else` 86, a local that shadows a field 108, traced 187, a failure 42, failed 31. The harness holds:

- `if` with `break` under one and several accumulators, in each of the shapes above, on lists that take and skip it.
- A `switch` in a body, with `yield` in an arm, and in an accumulator's update before a `break`.
- A loop in an arm, a guarded arm, and the then branch of an `instanceof`.
- A loop in a lambda's body, and a loop's variable captured by a lambda, at each iteration's value.
- Each representation in a tuple of accumulators: `String`, `boolean`, `byte[]`, Data, a list, a sum, a `Tuple2`, a builtin's list.
- A failure in a body, with what ran before it.
- A field updated by an entry method, shadowed, and read after.

I13's check ran over all of it, the suites and the corpus: `julc.verifyBinderNamespace` is on in the test tasks, and a loop's accumulator, variable and join are told to it by `Terms.loop`.

### T21, T23 and G0

| Set | On `frontend=JAVAC` |
|---|---|
| T21: `agree-t21-while-break-first`, `-if-update`, `-never-taken` | Agree at all four levels |
| T23: `agree-t23-for-each-split-if-else-break-taken`, `-never-taken`, `corpus-input-1`, `-3` | Agree at all four levels |
| Every saved case with a loop, 51 | 50 on the case's expectation. `reject-g1-291-local-after-loop` stops at `an expression of type java.lang.Object`, `v.equals(switch ...)`, in `JavacStopsTest`, before its loop |
| G0's enumeration, 376 programs on 4 inputs at all four levels | No unexplained verdict |
| `LoopBreakContinuationTest`'s three tests | Pass on both frontends |

### The loop suites with the default set to `JAVAC`

The other loop suites compile by the default frontend, so they were run once in a throwaway worktree with `CompilerOptions`' default set to `JAVAC`. 164 tests, 87 pass, 77 fail, and no failure is a defect of the loops:

| Tests | Why they do not run yet |
|---:|---|
| 45 | The source reads a library, and stops at `a library unit` |
| 24 | The source compares `BigInteger`s with `==`, which is `JULC0086` (D6). The saved form of these runs in the harness |
| 3 | `TYPE_CAST`: `(JulcList<PlutusData>) (Object) redeemer` |
| 3 | `an equals between unrelated types` |
| 1 | `LoopFieldUpdateTest.readByAnotherMethodIsRejected`: two errors, and this frontend reports `JULC0061` first (F946) |
| 1 | `LoopStatementDiagnosticsTest.continueInCodeTheCompilerNeverLowersIsAccepted`: the scan reads code nothing lowers |

`LoopConditionalLocalTest`, `PostLoopVariableTest` and `ReturnInLoopTest`'s while and pair-list tests pass in full.

### Whole modules

Both under the machine lock, `--rerun`, from the XML, at `5b83b4fd`'s tree:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `julc-compiler` | 6,345 | 0 | 0 | 0 |
| `julc-difftest` | 1,902 | 0 | 0 | 0 |

## Mutants

One runner per throwaway detached worktree, each mutant applied, tested in stages and reverted: the rows and lints first, then the harness, the suites of other slices, and the generator with the saved cases. Each line mutated is new in this slice.

| Pass | Commit | Control | Mutants | Killed | Survived |
|---|---|---|---:|---:|---:|
| First | `179083fd` | No-op: 0 failures of 986 and 128 | 128 | 67 | 61 |
| The survivors, with rows added and dead lines removed | `4a94e718` | Rows at that commit: 0 failures of 192 | 53 | 46 | 7 |
| Their survivors, with rows added | `e78c4d33` | Rows at that commit: 0 failures of 195 | 4 | 4 | 0 |
| The last row | `HEAD_ROW` | Rows at that commit: 0 failures of 196 | 1 | 1 | 0 |
| Through I1, on the three types below | `e78c4d33` | The run above | 3 | I1_KILLED | I1_SURVIVED |

Thirteen survivors of the first pass showed code no program reaches, and each was removed or narrowed, which is why they left the list:

| Line | Why no program reaches it |
|---|---|
| `in the loop iterable`, a for-each's header in `requireNoBreak` | The iterable lowers before the rule runs, so `Lowering.reassignment` rejects an update there first |
| `-=` on a `String`, and an integer target's operand type | javac rejects both (D11) |
| The field rule at an assignment | A field a body updates is an accumulator of that loop, so `Loops.accumulators` has met the rule. Mutating that one now kills |
| `inLoop` in the walk of an `if`'s branches | An assignment in a branch outside a loop is rejected where it lowers, so the two walks are one |
| An alias in `pairLists` | `startsAsPairList` follows the chain of initializers |
| The accumulators' names among what a tuple's name avoids, twice | A generated name is never a source name (ADR-060) |
| A member select in `FieldUpdates` | `C.K` and `C.h()` are `Undefined variable: C` first |
| A `PairMatch`'s binders in `PreLoopReads` | They are generated, and no candidate is one |

`header not read` and `no declared map local` came back as new mutants on the new lines, and both are killed.

Three lines cannot show in bytes. A body, a condition and what follows a `while` of several accumulators read a Data accumulator at the reference's type, and that type is on the `Var` alone, so no script differs. I1b compares the `Var`'s type:

I1_TABLE


## What this does not establish

- **Loops in a library's body.** A library unit stops, so `ValuesLib`'s and `ContextsLib`'s `while` loops are compared as methods alone, where the corpus compiles them as a root, and never run on this frontend.
- **Which programs the join's rule has never met.** The generator's nested loops reach two levels, and its accumulators three.
- **`AccumulatorTypes` beyond the corpus.** Each rule has a corpus row or a mutant's verdict, and the rule is the reference's, not a proof that the type is right. A Data accumulator holding a pair list by any other route packs as Data, on both frontends.
- **A local that shadows a field after a loop, by size.** Both frontends agree on value and failure, and `Adr068LoopsPropertyTest` does not compare cost on such a program.
- **`.diffcase`s.** The fixes rest on JUnit, so no reference row is new. A saved case for decision 2 adds one, and the frozen frontend's answer there is the divergence.
- **I2a's inventory** is still not run on this frontend as an inventory. `JavacLoopsTest` holds each rule to the reference.
- **Images and DevKit** were not run. The code holds no thread, reflection, file access or `ServiceLoader`.

## For the integrator

- **Freeze.** `adr/evidence/068/lowering-*.tsv`, and add [`068/m4-accepted-differences.tsv`](068/m4-accepted-differences.tsv)'s rows to `lowering-accepted-differences.tsv`. Nothing shrank.
- **ADR text.**
  - M4's row: this slice, with the note that M3's library, entry-point and debug items are open.
  - D6: `==` and `.equals` rows that a loop stopped now show.
  - D11: the table under [what javac decides](#what-javac-decides-d11).
  - I13: a loop binds no element twice, so the per-method table stays unneeded.
- **Follow-ups to close.**
  - "`Adr068Lowering` and warnings": javac rejects a statement after `break`, so no line is added.
  - "A hash read from a variable", for a for-each's variable: `CallTargets.isLoopVariable`.
  - "for-each over an inline list", and the for-each half of F1022 and F1042: decision 2.
  - F724: decision 5.
  - F722: an assignment's target, `Tuples.writtenAt`.
  - "One name per element": no slice lowers one statement twice.
  - "Loops": `Ifs.ifWithLoop`, `Exits.allPathsReturn`'s last loop, the assignment that is no rejection, block splicing.
- **Follow-ups to add.**
  - `Statements.sequence` has no `BreakTree` case: nothing reaches one, and its default stops.
  - "A bare block in a statement list" stands: a bare block in a body with no accumulator is `Unsupported statement: BlockStmt` on both frontends, as at a method's level.
- **Shared code.** Nothing found. No file outside `frontend/javac` changed in `src/main`.
- **Files of Group 1 and 2 touched:** `Ifs`, `Exits`, `Frame`, `Statements.local` and `single`, `Lowering.dispatch`, `Operators`, `Tuples`, `CallTargets`, `SubsetScan`, `JavacCompilation`.
- **Tests outside this slice's files:** `JavacRejectionsTest`, `JavacLambdasTest`, `LoweringAloneTest` and `JavacStatementsTest` held rows that said a loop stops, and those moved to the positive form.
