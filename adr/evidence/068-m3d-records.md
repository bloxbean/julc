# ADR-068 M3d: records, fields and object creation

M3d is the slice of M3 ([ADR-068](../068-javac-frontend-migration.md)) that follows calls. `new` of a record, a sealed variant, a tuple, a
`@NewType` and a ledger hash now compiles through `frontend=JAVAC`, with `x.length` of a byte string, to the reference's FLAT bytes at all
four levels. Accessors were M3b's. `switch`, lambdas, loops and library bodies stay `JULC0084`, and nothing falls back (I8).

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md). Branch `wip/068-m3d`, from `00633141`: B3 at `e34e19be`, merged
with `wip/068-m3c` at `8f9f0f8c`. M3c's final head, `f1e33bc5`, was merged in at `02389726`, with no conflict. This file states head `02b03481`.
Machine and toolchain as in M0's evidence.

## What was delivered

| Piece | Where |
|---|---|
| `new`: what is built is read from the `TypeElement` of the constructor javac bound | `lower/Records` |
| A tuple's type: what it writes, or by component from its destination or its argument | `lower/Tuples` |
| A member select: an API constant, a byte string's length, or the reference's rejection | `Records.member`, `Names.constant` |
| `NEW_CLASS` where lowering reaches it; a class body stops | `SubsetScan` |
| What javac wrote into a constructor is not source, and a `;` between members is no member (F908) | `SubsetScan.visitBlock`, `visitClass` |
| A record is read by the type its binder holds, so a diamond's kept type reaches an accessor | `Calls.accessor` |
| A diamond's type is kept with its term | `Typing.keep`, `TermTypes.recorded` |
| A `var` around parentheses or a conditional binds what the term says for a record or a sum | `Statements.varType` |
| The reference's generation-time rejection text, with its position in the words | `JavacFacts.locatedRejection` |

### What a `new` builds

| Constructor's type | Term | Decided by |
|---|---|---|
| A `@NewType` record | Its argument | The annotation's identity, D7 |
| A ledger hash | Its argument | The registry, by identity |
| `BigInteger` with a string literal | A constant | The owner's identity. A text block or any other argument is the reference's rejection |
| A variant | `DataConstr` with its tag and the sum | The sealed interface javac reads off the element; the tag is the variant's place in `facts.permitted`, which is the order the registry took (I11) |
| `Tuple2`, `Tuple3` | `DataConstr` tag 0 with `Tuples`' type | Identity |
| Any other registered record | `DataConstr` tag 0 | Identity |
| Anything else | `Cannot construct non-record type`, the reference's words | |

### Rules worth stating

- **A variant is its element.** A tag, a field type and an encoding come from the `TypeElement`. `permits X.A, Y.A` gives two constructors
  named `A`, and each has its own (F807). The reference finds a constructor by name; the gate rejects that shape on the default frontend.
- **A tuple's component.** With a written type argument, that. In a diamond, the destination's where one is written (a declared local or
  field, a `return`, an argument of a method of this class, through parentheses and conditional branches), else the argument's type as
  `Typing.of` reads it, so `Builtins.tailList` is a list although javac says `ListData`. A component read back as Data must be Data, so a raw
  tuple or a raw destination takes only that. An assignment's destination comes with loops; the `switch` slice must look through arms (F722).
- **A record's constructor is the canonical one.** A record's other constructors must hold a `this(...)`, which `JULC0099` rejects, and no
  record the registry holds has one. So lowering has no check of its own (I12).
- **A member select.** `r.a` is `JULC0055`, as in the reference, which reads a record through its accessor. `C.K`, `Other.K` and
  `java.math.BigInteger.ONE` are `Undefined variable`, also the reference's. `BigInteger.X` and `PlutusData.X` on a type of that simple name
  that is not the API's stop, because the reference reads those two qualifiers by spelling.
- **A read on `new` itself stays rejected.** `new R(x).a()`, `(new R(x)).a()`, `new Tuple2<>(x, y).second()`, and a `var` of `(new R(x))` or of
  a conditional of two `new`s, are `JULC0055` (I2). `new R(x).equals(y)` lowers, as in the reference.
- **`@Param` outside the compiled class (F909).** Nothing changes here. It is inert on both frontends: it is no parameter, and a read through
  the type is `Undefined variable`, so the field has no value to be wrong about. `JavacRecordsTest` pins that. The ADR can state it in D14.

### Where the JVM and the reference disagree

The rule of M3b holds: where the shape of the tree shows the disagreement, `JAVAC` stops at `JULC0084`, with a pin, and the frozen frontend does
not change. `JavacStopsTest` lists each, with what the default frontend does.

| Shape | JVM | Reference | `JAVAC` |
|---|---|---|---|
| `new Value(m)` | A `Value`, whose Data is a map | `Constr 0 [map]`: `lovelaceOf`, `isEmpty` fail on `UnMapData`, and `toPlutusData` is the wrong Data | Stops |
| A variant of two sealed interfaces | Its class | The last parent's tag | Stops. `JULC0079` comes with ADR-067's port |
| A `final class` as a variant | Its object | `Constr tag []`, and any argument unencoded | Stops |
| A `@NewType` as a variant | Its object | The bare value where a sum's `Constr` is expected | Stops |
| `new PubKeyHash(b).hash()` | The bytes | `UnBData` on bytes: fails | Stops, M3b's wrapper rule |
| A `PlutusData` component given `Builtins.tailList(...)` or `unListData(...)` | Goes on | Fails at `MkCons`: the native list is not encoded | Stops |
| `new BigInteger("abc")` | `NumberFormatException` at run time | `NumberFormatException` out of the compiler | Stops |
| `new X() { ... }` | A subclass | Rejected at the gate | Stops |
| `var s = c ? new S.A(a) : new S.B()` | An intersection type | Data | Stops: JuLC has no such type. A declared `S s` lowers |

`new Value` is a misreading in the frozen frontend alone, so it belongs on the follow-ups' "Closed by M5" list. It is pinned on both sides.

### Evaluation order

`Records` states it: each argument once, left to right, then the value. `DataConstr` lowers to nested `MkCons`, which applies in that order.
`Adr068RecordsHarnessTest` traces it, for records, variants, nested records and tuples, and fails an argument at each position, so what runs
before the failure is what Java runs. An identity is its argument.

## The I1 gate

Compared with a run of the same tool on the merged tree without this slice: `00633141` with the code and tests of `8f9f0f8c..f1e33bc5` applied,
in a scratch worktree, with the same corpora. `f1e33bc5` alone is not that base, because it does not hold B3: it has 17,063 rows, without B3's two
pins. The checked-in tables are older than both, so the tool itself reports 8,000 moved rows and fails. That is expected until the integrator
freezes. Nothing shrank: `lowering-i1-shrunk.tsv` is empty.

| Class | Merged base | M3d |
|---|---:|---:|
| `i1a-identical` | 1,416 | 1,532 |
| `not-ported` | 8,297 | 8,173 |
| `reference-rejects-not-ported` | 463 | 443 |
| `javac-rejects` | 5,359 | 5,359 |
| `both-reject-javac-first` | 1,288 | 1,288 |
| `both-reject` | 81 | 97 |
| `ledger-source` | 38 | 38 |
| `unreplayable` | 17 | 17 |
| `DIFFERENT-javac-accepts` | 19 | 19 |
| `DIFFERENT-javac-rejects` | 60 | 63 |
| `DIFFERENT-rejection` | 12 | 16 |
| `DIFFERENT-bytes` | 7 | 11 |
| `DIFFERENT-provenance` | 6 | 7 |
| `DIFFERENT-diagnostics` | 2 | 2 |
| `REFERENCE-DRIFT` | | 11 |
| Total | 17,065 | 17,076 |

### Every class of moved row

681 rows moved, and each of the 17,065 rows is still present.

| Rows | From | To | Why |
|---:|---|---|---|
| 401 | `not-ported` | `not-ported` | The program now stops at a later construct |
| 126 | `reference-rejects-not-ported` | `reference-rejects-not-ported` | The same |
| 116 | `not-ported` | `i1a-identical` | Compiles to the reference's bytes |
| 100 | `not-ported` | `not-ported` | Covers more methods |
| 16 | `reference-rejects-not-ported` | `both-reject` | Reaches the reference's rejection |
| 4 | `not-ported` | `DIFFERENT-bytes` | T33's pins, below |
| 4 | `DIFFERENT-javac-rejects` | `DIFFERENT-javac-rejects` | Covers more methods before the same rejection |
| 4 | `reference-rejects-not-ported` | `DIFFERENT-rejection` | Below |
| 3 | `not-ported` | `DIFFERENT-javac-rejects` | Below |
| 1 | `reference-rejects-not-ported` | `reference-rejects-not-ported` | Covers more methods |
| 1 | `not-ported` | `DIFFERENT-provenance` | Below |
| 11 new | | `REFERENCE-DRIFT` | The saved cases of F953, which no frozen table holds yet |

`DIFFERENT-javac-accepts` did not move, so no row widened.

### Coverage

| | Covered | Of | | Merged base |
|---|---:|---:|---|---:|
| Programs the reference accepts | 1,532 | 15,147 | 10.1% | 1,416 |
| Their distinct scripts | 702 | | | 612 |
| Programs of more than one method | 955 | | | 885 |
| Methods the reference lowers, library methods included | 9,104 | 172,426 | 5.3% | 8,847 |
| Distinct covered methods | 1,877 | | | 1,728 |

### The worklist

What stops each `not-ported` or `reference-rejects-not-ported` row first.

| First construct | Rows | Merged base |
|---|---:|---:|
| `ENHANCED_FOR_LOOP` | 3,487 | 3,459 |
| `WHILE_LOOP` | 1,944 | 1,914 |
| `SWITCH_EXPRESSION` | 1,259 | 792 |
| `a library unit` | 1,225 | 1,177 |
| `LAMBDA_EXPRESSION` | 381 | 379 |
| `TYPE_CAST` | 213 | 209 |
| `the exports of library sources` | 35 | 35 |
| `a contract schema` | 13 | 12 |
| `an equals between unrelated types` | 12 | 3 |
| `METHOD_INVOCATION` | 9 | 9 |
| `NEW_CLASS` | 1 | 734 |
| `MEMBER_SELECT` | 0 | 7 |

The one `NEW_CLASS` left is a `new` in a method nothing lowers: a nested type's factory, which the reference rejects with `JULC0055`. Rows that
grew stopped at `new` before, and reach what stops them next. `an equals between unrelated types` is `new A(...).equals(new B(...))` between two
variants, where Java says false: M3b's stop, until `JULC0078` is ported.

### The differences

New against the merged base. The integrator lists these in `068/lowering-accepted-differences.tsv`, which this slice does not freeze.

| Rows | Class | D13 class | What |
|---:|---|---|---|
| 3 | `DIFFERENT-bytes` | Semantic fix (#284, #286, T33) | `be426258`, `548f9534`, `de6c50ef`: `reject-packageless-pubkeyhash-record`, `reject-packageless-rational-record`, `-wildcard`: a user record named like a ledger type. The reference, with the gate off, reads the ledger's. On `JAVAC` each agrees with the JVM at all four levels (`Adr068SavedCasesOnJavacTest`) |
| 1 | `DIFFERENT-bytes` | Semantic fix (T33) | `079d0289`: `reject-top-level-user-value-on-local`: `record Value(boolean isEmpty)`. The same test |
| 1 | `DIFFERENT-javac-rejects` | Policy rejection (M0's clarification of D6) | `132e0afc`: `agree-equals-unknown-ternary`: `.equals` at static type `java.lang.Record` is `JULC0087`. `new` was what stopped it before |
| 2 | `DIFFERENT-javac-rejects` | Policy rejection (I2) | `3fd05c4d`, `4bc9400a`: `reject-top-level-user-value-on-constructor`, `reject-user-value-accessor-on-constructor`: a read on `new` itself is `JULC0055`. The reference, with the gate off, reads the name as the ledger's |
| 2 | `DIFFERENT-rejection` | Diagnostic change: F946's family | `58251078`, `cd915c5e`. Two errors in one method. The reference collects `JULC0061` and goes on to `Undefined variable: q`, which it throws; `JAVAC` stops at `JULC0061` |
| 1 | `DIFFERENT-rejection` | Diagnostic change: F946's family | `d439542c`: `box("L", a).amt().compareTo(b) > 0`. Both `JULC0055` at one position. The reference names the outer member, `JAVAC` the inner one |
| 1 | `DIFFERENT-rejection` | Diagnostic change | `e01a0161`: `reject-instanceof-record-pattern`. `new` stopped it before. M3c's rejection prints javac's tree, `A(java.math.BigInteger v)`, with a position, where the reference prints `A(var v)` with its position in the words. `JavacStatementsTest` holds M3c's form, so this slice leaves it |
| 1 | `DIFFERENT-provenance` | Provenance change | `bb217091`. M3a's rule: a source with no file is named after its primary type |

None is byte-different on a case that is not a T33 pin. In the 4 byte rows the script is the JVM's and the reference's is not.

**Rows that only start to run.** 11 rows are new to the corpus: the `.diffcase`s below. They show as `REFERENCE-DRIFT` until a freeze.

## F953: the saved cases

Saved in `regressions/`, so each adds a reference row; the integrator freezes them. `RegressionCasesTest` replays the default frontend's
answer and `Adr068SavedCasesOnJavacTest` the JVM's agreement on `JAVAC`, at all four levels.

| Case | Row | Default frontend |
|---|---|---|
| `reject-068-escape-shape-1` to `-6`, `reject-068-escape-shape-comment-1`, `-2` | The 8 ADR-065 I2c shapes | Rejected by its reader, uncoded |
| `agree-068-record-named-like-optional` | `M3aUserOptionalThing` | Agrees |
| `agree-068-qualified-optional` | `M3aSpelledOptional` | Agrees |
| `reject-068-nested-user-policy-id` | `M2Sealed` | `JULC0100` |

**Two limits of this format.** A `.diffcase` compiles a method, so the two `Optional` rows, which are about a validator's datum, cannot show the
reference diverging. Their cases show `JAVAC` meeting the JVM on those types, and `Adr068TypeIdentityTest.theReferenceReadsBothByTheirSpelling`
keeps the divergence. And a `.diffcase` holds one source, so D8's rows, which need a pool, cannot be saved at all: their oracle stays the
reference's program of the root alone, in the gate. D13's wording needs one of those two sentences.

## The tests

Counted from JUnit XML, each through the machine lock with `--rerun --no-watch-fs --offline`.

| Task | Result |
|---|---|
| `:julc-compiler:test` | 5,436 tests, 0 failures, 0 errors, 0 skipped, at `d2570ef7`. The one later commit changes two difftest classes |
| `:julc-difftest:test` | 1,109 tests, 0 failures, 0 errors, 0 skipped, at `02b03481` |
| `*frontend.javac*` and the lints, after the merge | 2,357 tests, 0 failures |
| `:julc-difftest:adr068Lowering` | As above. It fails on moved rows, as expected before a freeze |
| Jazzer, `:julc-difftest:fuzz`, 60 s | 945 executions, no finding, before the merge |

The first full `:julc-difftest:test` failed 7 tests, all `Adr068NameCasesTest`: its last line pinned `JULC0084`, because those pins could not run before
this slice. That line is gone, and `Adr068SavedCasesOnJavacTest` compares their values with the JVM, which is a stronger check. The binding checks stay.

| Class | Tests | What it runs |
|---|---:|---|
| `JavacRecordsTest` | 126 | Bytes equal the reference's at every level, with and without a source map, and rejections equal |
| `JavacStopsTest` | 72 | 26 more than M3c: each stop, and the default frontend's answer |
| `SpelledProgramsOnJavacTest` | 67 | Extended, not replaced: 28 rows moved from `NEW_CLASS` |
| `Adr068RecordsHarnessTest` | 45 | The JVM and both frontends at four levels. One test observes a stop; every other assertion needs `AGREE` |
| `Adr068LedgerRecordsHarnessTest` | 188 | 124 programs, each ledger record and variant with two samples, against its own `toPlutusData`; 64 checks that the interface is there |
| `Adr068SavedCasesOnJavacTest` | 18 | 16 agree with the JVM at four levels; 2 reads on `new` stay `JULC0055` |
| `Adr068RecordsPropertyTest` | 1 | 250 programs: 236 returned, 14 failed, 70 traced, each against a plain Java model, the JVM and 8 configurations, with fixed seed. The shape counts must hold or it fails |
| `Adr068RecordsFuzzTest` | 7 | Replays 6 seeds and the empty input |

All 47 programs of the review's sources, run on `frontend=JAVAC` against a real JVM in scratch: 12 agree, 35 stop or are rejected, none gives another
answer. That was before the merge, whose code change is `Exits` alone.

## Mutation evidence

One runner in one throwaway worktree. **34 mutants of the new lines: 33 killed by JUnit, 1 by the I1 gate alone.** The no-op control showed 0 failures
first, at each commit: 0 of 732, 0 of 746, 0 of 752 tests. The scope was `JavacRecordsTest`, `JavacStopsTest`, `SpelledProgramsOnJavacTest`,
`JavacStatementsTest`, `JavacLiteralsTest`, and the `Adr068Records*`, `Adr068LedgerRecords*` and `Adr068SavedCasesOnJavac*` classes. The pass ran
at `26df38fd`, with the reruns below at `99682e8f` and `d2570ef7`. The merge's code change is in `Exits`, which no mutant touches.

| Mutation | Failures | Caught by |
|---|---:|---|
| Tag always 0 | 112 | Bytes, harness, model |
| Tags reversed | 132 | Bytes, harness, model |
| Arguments right to left | 129 | Bytes, harness, model |
| `@NewType` is not an identity | 12 | Bytes, model |
| A hash is not an identity | 98 | Ledger encoders above all |
| A text block counts as a literal | 1 | Rejection |
| `NumberFormatException` escapes | 1 | Stops |
| Length of any type | 5 | Rejection |
| A type qualifier is not rejected | 2 | Rejection |
| The API owner's stop off | 1 | `JavacLiteralsTest` |
| Value, two parents, class variant, `@NewType` variant stops off | 1 each | Stops |
| Data component check off | 3 | Stops |
| Data component check, lists only or maps only | 1, 3 | Stops |
| Class body allowed | 2 | Stops, spelled table |
| Generated statements read | 2 | Bytes |
| Tuple: no destination, no Data check, no raw check | 2, 4, 2 | Bytes, rejection |
| Tuple component from javac alone | 3 | Bytes, harness |
| Kept type dropped; accessor by javac's type | 2, 2 | Bytes, harness |
| `var` wrapped rule off | 2 | Rejection |
| `var` wrapped rule for records only | 0 | **I1 alone** |
| After new tests: native check off | 4 | Rejection |
| After new tests: array check off | 1 | Rejection |
| After new tests: destination through a conditional, a `return`, an argument | 1, 2, 1 | Rejection |
| After new tests: class literal lowers | 1 | Stops |
| After a fix: a `;` after a type read | 1 | Bytes |

**Survivors of the first pass.** 8 survived. 6 were gaps, each with a test now, and were rerun and killed. The test for `class literal lowers` used
`BigInteger.class`, which the API owner's stop also catches, so it survived the rerun, and the test moved to `String.class`. The `;` mutant showed that
the rule was in the wrong place: javac keeps that `;` among a unit's types, not a class's members. The code moved and has a test.

**`var` wrapped rule for records only** changes a `Var`'s type and no byte, so no JUnit test can see it. The first I1 run, before that line existed, showed
exactly this as a `DIFFERENT-pir` row on `agree-equals-unknown-ternary-variant`. That run is the evidence. The mutant itself was not put through I1.

Two mutants of the plan were dropped because their code is gone: the canonical-constructor check and the `instanceof` message.

## What this does not establish

- **Library bodies.** A record of a library unit is constructed by the same code, and a program that reads a library still stops there.
- **Ledger records that hold a `Value`.** `TxOut`, `TxInInfo` and what holds them are not built in `Adr068LedgerRecordsHarnessTest`, since
  `new Value` stops and `Value.zero()` is no on-chain method. Their bytes rest on the same code path and on I1.
- **`.equals` on a record that a program built** is held to the reference's bytes, and to the JVM on the shapes in the harness. T29's forms stop or
  are rejected as before.
- **I2a's inventory** is still not run on this frontend. The rejections here are tested one by one against the reference.
- **Widenings.** `var l = JulcList.of(new P(a)); l.head().x()` compiles on `JAVAC` and agrees with the JVM, and the reference is `JULC0055`,
  because a list's reads are typed by javac (M3c's `varType`). Same shape with a parameter, so this slice did not bring it. It wants a line with
  F906's.
- **`PlutusData.cast(new P(a), PlutusData.class)`** is `Constr 0 [a]` on both frontends, and `ClassCastException` on the JVM. D6 states that
  `cast` is an unchecked view. It is reachable now where it was not.
- **Images and DevKit** were not run. The code holds no thread, reflection, file access or `ServiceLoader`.

## For the integrator

- **Freeze.** `adr/evidence/068/lowering-*.tsv` with M3c's. 11 new rows, listed above, and their keys join the corpus tables. `adr068Baseline`'s
  corpus table gains them too.
- **`lowering-accepted-differences.tsv`.** The rows of [the differences](#the-differences), with M3c's in `068/m3c-accepted-differences.tsv`.
- **ADR text.** D14: a `@Param` is read on the compiled class alone (F909). D13: the two limits above; a `DIFFERENT-rejection` of two errors in one
  method (F946). D7 or I11: `new` keys a variant by its element (F807). M3's row: this slice.
- **Follow-ups.** Close F807 (object creation; M3c's row names this slice), F908's three shapes, F909, F953 and the varargs row (pinned in
  `JavacRecordsTest`; no corpus case reaches it, so it has no D13 row). Update #284 and #286: values agree on `JAVAC`. Add `new Value` to "Closed by M5". Note under F722 that `Tuples.destination`
  must look through `switch` arms, and under loops that it must take an assignment's target.
- **Shared code.** One read method on `TermTypes`, `recorded`, for the javac side. `PirGenerator` does not call it.
- **Files of M3c's touched:** `Statements.varType`, a few lines. `TypeTests` is unchanged, and holds the same few lines as `Records.sealedParents`;
  one of them can go.
- **`Adr068NameCasesTest`** lost its last assertion, as [the tests](#the-tests) says.
