# ADR-068 M3 Group 1: closing calls, records, locals, `if`, `return` and `instanceof`

Group 1 is M3b, M3c and M3d merged on `wip/068-m3g1`. Three independent reviews read it. This file states what changed after them. Nothing here changes the
frozen frontend or its tables, apart from 11 saved cases joining them. Machine and toolchain as in M0's evidence.

## What the fixes are

| Rule | Where | What it does |
|---|---|---|
| Relatedness | `EqualityRelation`, used by `subset/EqualityRules` | Whether Java's `equals` can hold between two static types, on javac's types, through type arguments |
| One reading of a type | `CallTargets.seen` and `requireSeen`, used by `Calls`, `Operators`, `Records` and `Statements.varType` | What the reference gives an expression: what a member is looked up on, and what a `var` binds |
| A read keeps what the registry declares | `Calls.typeMethod`, `Typing.recordRead` | The port of `PirGenerator.java:1635-1636`: the result of a registry method carries the type declared on the receiver seen |
| A declaration stops where it stands | `SpelledMethods.checkDeclarations`, from `SubsetRules.check` | `JULC0101` for a declared `toPlutusData` or accessor, on every frontend |
| A generated `toPlutusData` stops at its record | `SpelledMethods.checkRecordMembers`, from `SubsetRules.checkOnJavacFrontend` | `JULC0101` on `frontend=JAVAC`, for a component whose generated accessor implements `ToPlutusData.toPlutusData`, by the element |
| Only a diamond keeps its tuple type | `Records.tuple`, `Tuples.isDiamond` | The reference records a diamond's type, and no other |
| A stable message | `ArrayIdentityEquality.text` | A capture is printed without javac's number, in every message that prints a type |

### Relatedness

Java's `equals` starts from the classes. On-chain a comparison reads representations, so `5` and `5L`, a `String` and its bytes, a hash and `byte[]`, and two records with the
same fields can be equal. `EqualityRelation.between` answers:

| Types | Verdict |
|---|---|
| A primitive | Its box |
| Two declared types | Related if one is a subtype of the other by class, and each type argument of the wider is related to the same argument of the narrower seen as that class |
| `Object`, a raw type, a type variable, a wildcard with no upper bound | Unresolved |
| `Object` as a type argument | Related: only inference gives one, as in `x.equals(Optional.empty())`, since a declared `Optional<Object>` has no representation |
| Two arrays | Related at one type |
| An intersection | By its bounds: one unrelated bound decides |

The rule stays on the types, so there are two places where two values are unrelated although Java can answer true, and those programs fail closed:

- An empty `Optional` or collection equals an empty one of any argument. The property test holds this: it counts such pairs and finds no other.
- A type argument no component uses: with `record X<T>(BigInteger v)`, an `X<String>` and an `X<Long>` of one value are equal, and the reference says true. `JAVAC` says `JULC0078`. `new X<>(a)` against `X<String>` agrees. The property test's types have no such record. See the follow-ups.

A conditional operand is read by its branches, as javac types it by the parameter. A `switch` is typed the same way, and stops at its own construct until its slice reads the arms.

`EqualityRules` takes the verdict at every row of `EqualityOperations`' table, and the table now says which argument each comparison meets:

| Case | On `frontend=JAVAC` |
|---|---|
| A collection operation between unrelated types | `JULC0078` |
| `.equals` of unrelated type arguments | `JULC0078` |
| `.equals` between types with no class in common | `JULC0084`, `an equals between unrelated types`, as since M3b. See [decisions](#decisions) |
| A raw `JulcList` or `JulcMap` receiver | `JULC0087` |
| A raw receiver that is a cast | Stops at `TYPE_CAST`. See [decisions](#decisions) |
| A raw argument of a static wrapper such as `MapLib.member` | Unchanged: its parameter says `PlutusData`, and a library call stops |
| An operand the check cannot read | `JULC0087` |

D6's two codes do not fit an unrelated pair, so this uses `JULC0078`, which ADR-067 D5 reserved for the same concept, and adds no code. Its status is now `emitted`.

### Reading a type

The reference resolves a name, a `new`, and the result of a helper, a library method or an accessor. Of anything else it reads the term. `CallTargets.seen` says that once, so
parentheses, a conditional, `var p = o` and a chained read all follow from one rule, in both directions:

| Program | Reference | `JAVAC` |
|---|---|---|
| `var o = (Optional.of(a)); o.isPresent();`, and a conditional of them | `JULC0055` | `JULC0055` |
| `JulcList.of(x).head().add(y)`, `var q = JulcList.of(x); q.head().add(y)`, `Optional.of(a).get().add(y)` | `JULC0055` | `JULC0055` |
| `var x = (new Tuple2<BigInteger, Boolean>(a, true)); x.first()` | `JULC0055` | `JULC0055` |
| `var x = (l.head()); x.y()` on a `JulcList<P>`, and `get`, map `get` and a conditional | Lowers | Lowers, and agrees with the JVM |
| `var x = (w.l()); x.head().y()` | `JULC0055` | `JULC0055` |

What a read decodes to stays javac's: `var xs = JulcList.of(true); return xs.head();` is still the D13 semantic fix. A `var` of a decoded read is seen as Data, so a member of it is
`JULC0055`, and `equals` on it takes the row of what it holds.

Every member a type decides reads `seen` through `CallTargets.requireSeen`: a registry method, `.length` on bytes, and `compareTo` in the `OP 0` form, which `Operators` lowers without the registry. A wrapper's component is `JULC0055` on a read seen as Data. So these follow the rule too:

| Program | Reference | `JAVAC` |
|---|---|---|
| `var y = JulcList.of(v).head(); return y.length;`, and `JulcList.of(v).head().length` | `JULC0055` | `JULC0055` |
| `var y = JulcList.of(v).head(); return y.compareTo(v) < 0;`, and `0 > y.compareTo(v)`, and direct | `JULC0055` | `JULC0055` |
| `JulcList.of(h).head().hash()` | `JULC0055` | `JULC0055` |
| `var y = JulcList.of(v).head(); return y.equals(v);` with a `boolean`, a list or a map | Evaluates, as Java | `JULC0055` |

**An accepted difference.** The last row is the one place `JAVAC` is narrower than the reference here. The registry has no `equals` for those types, and the term holds a builtin value, which `EqualsData` fails on. It is what `JAVAC` answered at `fc9e9716`. The reference holds the read undecoded, as Data, so it evaluates, and **its answer agrees with Java's**. `JAVAC` fails closed. The D13 class is Policy rejection, under I2d, and the rejection test is `Adr068ReadsHarnessTest.equalsOnADecodedValueWithNoEqualsIsAnUnresolvedMember`. No program of the corpus has this shape, so no row of `lowering-accepted-differences.tsv` names it. It is in the over-rejections for M5.

`var e = c ? a : b` with two variants now binds what the term says, as the reference does, and lowers. A use of it that needs its javac type, `instanceof` or `equals`, stops or is
rejected as on the operand itself.

### Stops

`JavacStopsTest` holds each, with the default frontend's answer.

| Construct | Shape |
|---|---|
| `fromPlutusData on a ledger type` | `Value.fromPlutusData(d)`, and `Address`, `Credential`, `TxOutRef` |
| `an argument of <method> that is Data` | The `PlutusData.BytesData` overload of a builtin given Data |
| `a component of a ledger type whose Data is no constructor` | `v.inner()` on a `Value` |
| `a Data component given a native list` | Also `constrFields`, `unConstrData`, `mkPairData` |
| `an expression of type java.lang.Record&C.S` | A diamond's component of no JuLC type, which was `JULC0012` |
| `an instanceof of an operand outside the variant's sealed interface` | `(c ? a : b) instanceof S.B x` |
| `TYPE_CAST` | A raw receiver through a cast |
| `IDENTIFIER J` | `boolean J = (true \|\| J);` with a static `J` |

Gone: `a declared accessor`, which `JULC0101` now reads first; and the row of a `var` of two variants, which lowers. `JavacStopsTest` compiles every row with the stdlib now, so no row is
an artefact of a missing lookup. `Math.absExact` replaces `Math.floorMod`, which lowers.

## Corpus rows

`EqualityRelation` read over every site of every source, whatever stops its program first, through `Adr068EqualityScan`: the M0 recording, the head recording, the external recording, Elci's, the
in-repo sources and the external examples at `22b4e00f`. The table is `equality-scan-relation.tsv` in the run's output directory.

| Sites | Outside saved pins | Outcome |
|---|---|---|
| Collection operations between unrelated types | 0 | `JULC0078` rejects nothing in any corpus |
| `.equals` of unrelated type arguments | 2 diffcases and their recordings: `diverge-t29-equals-nested-tuple`, `-optional` | `JULC0078` |
| `.equals` of no common class | 1 external validator, 7 recorded test validators | Stays `JULC0084` |
| A raw receiver | `ValuesLib.java:43` | Stays at `TYPE_CAST` |
| A raw argument | 1 recorded test of `MapLib.member` | Unchanged |

Of the rows the gate compares:

| Rows | From | To | Why |
|---:|---|---|---|
| 2 | `i1a-identical` | `DIFFERENT-javac-rejects` | `JULC0078`, the two T29 pins above. Listed as policy rejections (D6). These lose a comparison |
| 5 | `i1a-identical` | `not-ported` | `fromPlutusData on a ledger type`: 4 one-method programs of the head recording, and `credentialFromData` in the fixture `M3bStatics`. These lose a comparison |
| 2 | `not-ported` | `DIFFERENT-javac-rejects` | `JULC0101`: `reject-explicit-accessor-parameter`, `-local`. Listed as policy rejections (D15) |
| 1 | `not-ported` | `DIFFERENT-javac-rejects` | `JULC0101`: `reject-explicit-accessor-switch-binding` |
| 14 | `not-ported` | `not-ported` | `equals` between unrelated types moved to the scan, so `adr068Lowering`'s method-by-method read lowers those methods: covered methods grow |

External and Elci parts: no row moved.

**`DIFFERENT-javac-accepts` stays 27**, so nothing widened.

## The I1 gate

Two commits matter, because a freeze with no `allowShrink` cannot hold the 7 rows that lose a comparison.

| Commit | State | Moved | Shrunk |
|---|---|---:|---:|
| `d2aaa3d9` | Every fix but the two below: the tables are frozen here | 0 | 0 |
| `d3b2d765` | Adds the `fromPlutusData` stop and `JULC0078` on type arguments | 7 | 7 |

Totals at the frozen commit:

| Class | M3d's freeze | Frozen |
|---|---:|---:|
| `i1a-identical` | 1,534 | 1,534 |
| `not-ported` | 8,173 | 8,170 |
| `reference-rejects-not-ported` | 443 | 443 |
| `javac-rejects` | 5,359 | 5,359 |
| `both-reject-javac-first` | 1,288 | 1,288 |
| `both-reject` | 97 | 97 |
| `ledger-source` | 38 | 38 |
| `unreplayable` | 17 | 17 |
| `DIFFERENT-javac-accepts` | 27 | 27 |
| `DIFFERENT-javac-rejects` | 63 | 66 |
| `DIFFERENT-rejection` | 16 | 16 |
| `DIFFERENT-bytes` | 12 | 12 |
| `DIFFERENT-provenance` | 7 | 7 |
| `DIFFERENT-diagnostics` | 2 | 2 |
| Total | 17,076 | 17,076 |

At the head, `adr068Lowering` fails on those 7 rows until they are frozen.

## The tests

Counted from JUnit XML, each through the machine lock with `--rerun --no-watch-fs --offline`.

| Task | Result |
|---|---|
| `:julc-compiler:test` | 5,482 tests in 358 files, 0 failures, 0 errors, 0 skipped, at `88a7333a`. One row changed after it, in `JavacCallsTest`, which was rerun |
| `:julc-difftest:test` | 1,238 tests in 52 files, 0 failures, 0 errors, 0 skipped, at `88a7333a` |
| `:julc-difftest:adr068Baseline`, gate off | 3 tests, 0 failures, at `534fad59`: every B3 table equal, with the 11 saved cases |
| `:julc-difftest:adr068Lowering` | Moved 0, shrunk 0 at `d2aaa3d9`. Moved 7, shrunk 7 at the head, the rows above |

| Class | Tests | What it runs |
|---|---:|---|
| `Adr068EqualityHarnessTest` | 65 | On `frontend=JAVAC`, four levels, the JVM as oracle. 39 refused rows, each with the JVM's answer confirmed, and 26 kept rows, each of which must evaluate and agree. A rejection does not pass a kept row |
| `Adr068EqualityRelationPropertyTest` | 2 | 20,000 pairs of static types, over 80 types with real values, seed 68: 537 related, 19,463 unrelated, 0 unresolved. Related: 776 value pairs equal in Java and on Data, 618 different in both, and none apart. Unrelated: 2,498 pairs where Java says false and Data says true, which is what the rule refuses, and 848 where both say true, each of two containers that hold nothing. Then 300 programs through the compiler at four levels: 564 runs evaluated and agreed, 636 rejected |
| `Adr068ReadsHarnessTest` | 62 | The reference in both directions, and the declarations: the same `JULC0055` or the JVM's value, and `JULC0101` on both frontends for each program of the finding |

`assertFixedOnJavac` now fails on a reference that rejects: a rejection is a decision, and no semantic fix. So `JulcList.of(BigInteger.ONE).head().add(BigInteger.ONE)` moved to the rows that stop, and `04876b6f` stays a semantic fix on its two methods, which reach the reference and get another value.

**Review probes, rerun.** The calls review's `p1` to `p4`, `p6`, `p8` to `p12`, rerun during this round through the JVM and both frontends: every program that gave a wrong value on `JAVAC` and is named for this round is now a rejection or a stop. Those that moved the other way, `Z2`, `Z3`, `D9`, `K40`, `Y3`, were rejected on `JAVAC` only, and now equal the reference and the JVM. `N10f` and the rows of F1107 moved to `JULC0055`, the reference's answer.

## Mutation evidence

One runner in one throwaway worktree. **42 mutants of the new lines: 36 killed on the first pass, and the 6 that survived each killed by a new test.** The no-op control showed 0 failures first, at each commit: 0 of 1,271, 0 of 1,282, and 0 of 396 on the last pass, which ran `JavacCallsTest` alone. The scope was `JavacStopsTest`, `JavacCallsTest`, `JavacStatementsTest`, `JavacOperatorsTest`, `JavacRecordsTest`, `SubsetRulesTest`, and the `Adr068Equality*`, `Adr068Reads*`, `Adr068CallsHarness` and `Adr068StatementsHarness` classes.

| Mutation | Failures | Caught by |
|---|---:|---|
| Type arguments ignored | 14 | Harness, property |
| A primitive not boxed | 8 | Harness, calls |
| One direction of subtype | 8 | Harness, property, stops |
| `Object` as an argument unresolved; wildcard bound ignored | 1, 1 | Harness; calls |
| Unresolved beats unrelated | 4 | Harness, calls |
| One branch of a conditional; parentheses kept | 1, 1 | Harness |
| Raw allowed; a raw cast receiver rejected | 10, 1 | Harness; stops |
| Unrelated collection allowed; `equals` at top level lowers; unresolved allowed | 31, 6, 1 | Harness; stops and calls; calls |
| `containsValue` reads no argument | 1 | Harness |
| `seen`: a stdlib call by javac; a registry read by javac; `new` by the term; everything by javac | 20, 12, 11, 36 | Reads, calls, statements, records |
| A read keeps no declared type; keeps it by javac | 22, 12 | Reads, statements |
| `var` binds by javac | 19 | Reads, statements |
| Every tuple keeps its type; no tuple does | 2, 1 | Reads; records |
| A conditional by javac; parenthesized | 5, 1 | Harness |
| Pair check off; `BytesData` check off | 4, 5 | Stops |
| `Value` component lowers; `fromPlutusData` lowers | 1, 5 | Stops, calls |
| Declared `toPlutusData` allowed; accessor allowed | 7, 8 | Reads, calls |
| Declarations not read on a root; in a library | 13, 2 | Reads; rules |
| Pattern at the top level only; anchored at the variable | 2, 1 | Statements |
| A capture printed with its number | 1 | Operators |

**Survivors of the first pass, with what kills each now.**

| Mutation | Failures | Test added |
|---|---:|---|
| An intersection read by its first bound | 1 | `intersection`, `Optional<R>` against `Optional<Record & S>` |
| Any two arrays related | 1 | `JavacCallsTest`'s two array types |
| A `switch` operand read as itself | 1 | Its stop row |
| A static raw argument rejected | 1 | The `MapLib.member` stop row |
| `equals` on a decoded read as Data | 3 | `a decoded read`, of a value, a `var` and a text |
| A conditional typed by its first operand | 1 | The stop row of two operand types |

## What this does not establish

- **The confirmation matrix on other VMs.** It ran on the Java VM. Scalus and Truffle ran through `pairCaseTest` and the VM modules' tests.
- **Libraries.** Every library unit still stops, so the rules on a library body and `EqualityRelation` over a library's types ran only through the scan's read of those sources.
- **Sealed interfaces that share a variant.** Two such interfaces are unrelated by the subtype test, though a value could be of both. A variant of two sealed interfaces stops, so nothing lowers there, and the words `JULC0078` would use are not reached.
- **A `.equals` with two operand types.** `a.equals(c ? a : b)` with `S a, S.A b` stops, at `an expression of type java.lang.Object`, where the reference lowers it: the conditional is typed by its operands only where they are one type.

## ADR text the integrator must change

- **D6.** Add relatedness: `EqualityRelation`, its table above, `JULC0078` for a collection operation and for type arguments, and the empty-container cost. A raw `JulcList` or `JulcMap` receiver is `JULC0087`, under "any type the check cannot resolve".
- **D6, `PlutusData.cast` sentence.** Add a ledger `fromPlutusData`: `JULC0084` until the entry points' slice.
- **D15.** `JULC0100` and `JULC0101` "have no place on `frontend=JAVAC`" is wrong for two rows of `JULC0101`. A declared `toPlutusData` and an explicit accessor are read from `SubsetRules.check`, on every frontend, because JuLC compiles a record as its components. What stays gate-only: all of `JULC0100`, and the rest of `JULC0101`: `fromPlutusData` and `of` declarations, `equals` on a record or sealed interface, instance methods and calls on a `@NewType`, a component named like a method, the accumulator names, and calls through an `Optional` or on bytes.
- **D15's table.** Add `JULC0078` on `frontend=JAVAC`, and that row of `JULC0101`.
- **ADR-067 D5.** `JULC0078` is emitted on `frontend=JAVAC` from M3, for what relatedness decides, which is wider than class sets. Its template names the operation and both types.
- **D13.** `Policy rejection` lists D6, D11, D14, D15, I11: these rows use D6 and D15, so no change there. The two limits M3d names, the rows of F946 and a `DIFFERENT-rejection` of two errors, are still to add.
- **I2.** State `CallTargets.seen` as the reading of "the subset does not widen" for member reads.
- **I4.** A message holds no capture number.
- **D14.** A `@Param` is read on the compiled class alone (F909). **D7 or I11.** `new` keys a variant by its element (F807). **M3's row.** This group.

## The confirmation round

A confirmation review read the merged group. These are its programs and what each is now. Reference is `frontend=JAVAPARSER`.

| Program | Java | Reference | `JAVAC` before | `JAVAC` now |
|---|---|---|---|---|
| `record W(PlutusData toPlutusData) implements PlutusDataConvertible { }`, each of 16 uses | The component | `JULC0101` | The record's components | `JULC0101` |
| `interface I { BigInteger a(); } record W(BigInteger a) implements I { }` | Agrees | Agrees | Agrees | Agrees |
| `record W(PlutusData toPlutusData) { }`, on no interface | Agrees | `JULC0101`, by the name | Agrees | Agrees |
| `y.equals(v)`, `y` a decoded `boolean`, list or map, 120 rows | true | true | Fails at `EqualsData` | `JULC0055`, the accepted difference above |
| `y.length`, `y.compareTo(v) < 0`, `JulcList.of(h).head().hash()`, 14 rows | Agrees | `JULC0055` | Agrees | `JULC0055` |
| 24 levels of `k_i() { return k_(i-1)().abs(); }`, and `min`, `max`, `signum`, `compareTo`, `Math.abs`, `max`, `min` | Returns | Budget at depth 16 before; now agrees | The same | Agrees |
| 13 doublings of a hash in a method not called, each of the 6 hashes | Returns | Fails at script start before; now agrees | The same | Agrees |
| `f_i(x) { return f_(i-1)(f_(i-1)(x)); }`, depth 36 | Returns | Compile time doubled per level; now agrees | The same | Agrees |

`record X(...)` with a generated accessor of `ToPlutusData.toPlutusData` is keyed on that element, so the rule does not depend on the name, and the gate's rule by the name stays what it was. An interface's default `toPlutusData` in source is a method tree, which `checkDeclarations` reads. The one such default in a class file is `JulcList`'s, whose `Iterable` javac cannot read from the platform image, so a record of it is `JULC0085` first (`JavacRecordMembersTest`).

**The review's 432 differences on `Builtins.sha2_256` were the harness's:** `julc-difftest` installs no `CryptoProvider`, so its JVM side throws before it hashes. No stub of a hash disagrees with Plutus 1.63.0.0: `JvmCryptoProviderTest` runs all six against the VM at 9 sizes. A neighbour did disagree. `JvmCryptoProvider.verifyEd25519Signature` returned false for a key or signature of the wrong length, where `PlutusCore.Crypto.Ed25519` fails. It now throws, and false stays for what then does not verify (`068-m3-reference-b4.md`, "The JVM").

**The review's matrix, rerun at `116fdf08`.** 16 of its input files, the families of these fixes, on the JVM and both frontends at four levels, Java VM only:

| | Inputs | Evaluated | Agree | Other | Rejected | Harness's result mapping |
|---|---:|---:|---:|---:|---:|---:|
| `JAVAC` | 29,834 | 68,720 | 68,224 | 496 | 45,236 | 1,728 |
| Reference | 29,834 | 71,540 | 66,440 | 5,100 | 41,844 | 2,300 |

The 496 `JAVAC` runs, read by family:

| Runs | What |
|---:|---|
| 432 | `sha2_256` and its neighbours: the harness's missing provider |
| 24 | Known and listed: an `instanceof` operand run twice, argument-first `prepend` and `contains` |
| 20 | A `String` parameter holding invalid UTF-8 |
| 16 | An `instanceof` chain past the budget: the follow-up row |
| 4 | `constrData` of a negative tag on the JVM: the follow-up row |

No value differs between `JAVAC` and the JVM. Against the review's own outputs, 174 programs moved from a failure to `JULC0055`, 16 from a value to `JULC0055` beside the reference's, 31 from a failure to agreeing on both frontends, 8 from out of budget to agreeing on both, and 16 of the declarations to `JULC0101`.

**`O8ValueSharingTest`** ran through `:julc-compiler:pairCaseTest`: 8 tests, 0 failures. The class compares Java, Truffle and Scalus itself.

**Mutants of these lines.** One runner in a throwaway clone. 5 no-op controls first, one per scope of tests: 0 failures of 325, 949, 194, 125 and 15. Then 50 mutants: `SpelledMethods` and its callers 8, `requireSeen` and its reads 9, `readOnce` 8 and its handlers 8, `mayFail`'s sizes and memo 11, Ed25519 6.

| Result | Mutants | What |
|---:|---|---|
| Killed on the first pass | 47 | Each with 1 to 120 failures |
| Killed by a new row | 1 | Ripemd's digest sized 16: `eachDigestHasItsOwnSize` had no row from below |
| Showed a redundant test, now removed | 1 | A wrapper seen as Data that also holds Data ends at `JULC0055` anyway, so the rule reads `seen` alone. The mutant of what stayed was then killed |
| Equivalent | 1 | The element rule also at the gate: the gate's rule by the name rejects each such program first, with the same code |

## Decisions

1. **`JULC0078` for `.equals` between types with no class in common.** It would reject `UVerifyProxy.java:70`, `txId.equals(utxoRefTxId)` with `byte[]`, a validator of the external examples, and seven recorded test validators (`sig.equals(redeemer)`, `output.value().equals(redeemer)`). It stays `JULC0084` here, as it was, so no row moves. Java's answer there is false, so a JVM test of that validator does not test the script. Taking the rule is one line in `EqualityRules`, and the example's fix is `new TxId(...)`.
2. **`ValuesLib.java:43`**, `((JulcMap)(Object) value).containsKey(policyId)`, is a raw `JulcMap` in the stdlib. Writing the key's type in that cast moves the reference; exempting it needs a sentence in D6. It waits at `TYPE_CAST`.
3. **The 7 rows that lose a comparison** need `-Padr068.allowShrink=true` at the next freeze, or the fixes held back. Each is listed above.
