# ADR-068 M3b: calls and operators

M3b is the second slice of M3 ([ADR-068](../068-javac-frontend-migration.md)). A program of `return` statements over calls and
operators now compiles through `frontend=JAVAC`, to the reference's FLAT bytes, at all four levels. Locals, `if`, records,
`instanceof`, `switch`, lambdas and loops stay `JULC0084`, and a method that needs one stops there.

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md); the foundation is
[`068-m3a-lowering-foundation.md`](068-m3a-lowering-foundation.md). Machine and toolchain as in M0's evidence. Branch `wip/068-m3b`,
based at `f3752147`, which is M3a.

This file states the code at `9f3a71d1`, which merges M3a's final head `3bcd0dc9`. Every task in the results ran there. `5b1a6c8d` and
`7767cfe1` then add tests only, and the mutants of the merge ran on them.

## What was delivered

| Piece | Where |
|---|---|
| Resolve, then lower: a call becomes a sealed target by a function of the tree, then a family lowers the target | `lower/CallTarget`, `lower/CallTargets`, `lower/Calls` |
| The exact key of a method's owner, in place of `dispatch`'s `rt.name() + "." + method` | `lower/TypeKeys`; `TypeMethodRegistry.dispatchExact`, `requirementsExact` |
| Operators, with D6's `compareTo` forms and minimal binding in one place, and each family's evaluation order in its Javadoc | `lower/Operators` |
| Types of expressions: javac's, then what the term says where javac says only Data | `lower/Typing`, `pir/TermTypes` (moved from `TypeInferenceHelper`) |
| D6's rules: `JULC0086`, `JULC0087`, `JULC0090`, all now emitted | `subset/EqualityRules`, run by `SubsetScan` |
| A kind allowed where lowering reaches it | `SubsetScan.WHERE_LOWERED`, `JavacDiscovery.lowered` |
| `JULC0057` on javac, at step 11b | `JavacCompilation.ClassParts.requireNoMethodCall` |
| `frontend` on hosts (D4) | Gradle `julc { frontend }`, `julc build --frontend`, `-Ajulc.frontend`, `JulcEval.frontend`, `ValidatorTest.compile(source, frontend)` |
| The stdlib lookup reaches the lowering | `JulcCompiler.onJavac`, `JavacCompilation` |
| Mechanical moves out of `PirGenerator`, proved by the gate-off baseline | `PirHelpers.swappedInOrder`, `equality`, `fieldExtraction`; `CompilerTargetDiagnostics.requireAvailable` |

### Targets

| Target | What it is | How it is identified |
|---|---|---|
| `Intrinsic` | `BigInteger.valueOf`, a ledger type's `fromPlutusData`, `PlutusData.cast`, `toPlutusData` | The owner's identity, and for a static call a simple-name qualifier bound to a type |
| `Stdlib` | A static method of an API class | The owner's identity, mapped to the lookup's key |
| `Library` | A static method of a pool unit: the stdlib lookup first, as the reference composes them, then a call of its binder | `JavacFacts.inLibraryUnit` |
| `Local` | A static method of the class being lowered, by simple name | The enclosing class is the owner |
| `Accessor` | A record component read from Data | The method has a component's name and no parameter, and javac generated it |
| `Wrapper` | The accessor of a ledger hash or `@NewType`, whose value is its component | As `Accessor`, on a receiver that is not a `RecordType` |
| `TypeMethod(key)` | An instance method in `TypeMethodRegistry` | `TypeKeys` of the owner, checked against the receiver |
| `Equals` | `equals(Object)` | The method's parameter; the key is the receiver's type, since on `byte[]`, a sealed interface or `PlutusData` the element is `Object.equals` |
| `Hof` | A list method that takes a function | Stops at its argument: lambdas are a later slice |
| `Unresolved`, `Rejected`, `NotLowered` | The reference's `JULC0055`, the reference's words, and `JULC0084` | Targets, so resolving stays a function of the tree |

### Rules worth stating

- **Library calls.** The call site only. A signature comes from the element, and the coercions are `LibraryMethodRegistry`'s. A
  program that needs a body stops at `a library unit`, so I1b compares the method and I1a the program does not.
- **The receiver's shape is part of what the reference accepts.** It reads a record through a variable or a call whose type its
  inference sees, and rejects the rest with `JULC0055`. Parentheses are not looked through. `CallTargets.typedCall` is that rule.
  So `box(x).amt()`, `(x).a()` and `Optional.of(a).isPresent()` stay `JULC0055`, although javac knows each type (I2).
- **A `Data` static type is not the last word.** `Builtins.tailList` is declared `PlutusData.ListData` and leaves a list, so where
  javac says Data the term is read, as the reference does. That is `TermTypes`, moved, not copied.
- **An inferred type is not a written one.** `JulcList.of(1)` is a `JulcList<Integer>`, and `JulcList.of(1, "a")` a
  `JulcList<Serializable>`. `JavacFacts.valueType` reads the first as integers and an unrepresentable argument as Data, as a raw
  container's. Written in a declaration the same types stay rejected, by `pirType`.
- **`equals` is the receiver's.** The key comes from the receiver's type. Between unrelated static types it stops at `JULC0084`,
  because Java says false and the script would compare representations. `JULC0078` comes with ADR-067 M4's port.
- **What does not lower though the reference does.** Each is `JULC0084`, and each is a shape on which the JVM and the reference differ:
  a declared accessor or `toPlutusData` (T31), a `@NewType` component named like a method of its value (`abs` lowers to an absolute
  value), a no-argument method on bytes that is not a component (`hash.toString()`), a wrapper read from a variable (below), a call
  of a varargs helper or library method, and the negation of a type's minimum value.
- **A hash in a variable.** The reference's `hash` handler is `UnBData`: it takes a variable to hold Data, as a loop's and a lambda's
  do. Every variable that reaches this slice holds bytes, so `static byte[] h(PubKeyHash p) { return p.hash(); }` fails in
  evaluation where Java returns them, as a byte `@NewType`'s accessor does. `PubKeyHash.fromPlutusData(d)` is the same thing the other way: an
  identity, so Data goes on where bytes are expected. Those stop at `JULC0084`. A read from a typed call, `r.p().hash()`, is decoded,
  and lowers to the reference's bytes.
- **`contains`** encodes its target by its static type unless the elements decode to integers or bytes (D6, #297). A target that
  was Data is the same term, so its bytes do not move.

### Where the JVM and the reference disagree

The rule of this slice: **where the shape of the tree shows the disagreement, `JAVAC` stops at `JULC0084`, with a pin, and the frozen
frontend does not change. Where it takes a run-time value or an analysis of what a body does, it is ported as it is and pinned.** The
second kind is a row for the calls slice.

| Shape | JVM | Reference | `JAVAC` |
|---|---|---|---|
| `p.hash()` on a parameter, static or `@Param`; a byte `@NewType`'s accessor there; `(r.p()).hash()` | The bytes | Fails: `UnBData` on bytes | Stops |
| `PubKeyHash.fromPlutusData(d)`, used as bytes | The bytes | Fails: Data where bytes are expected | Stops |
| `-0x80000000`, `-(-2147483648)`, `-0x8000000000000000L` | The same negative value | The positive one | Stops |
| `a.mod(b)` with `b < 0` | Throws | A value | Ported, pinned |
| `take(-1)`, `drop(-1)` | Throws | A list | Ported, pinned |
| A helper with no parameter, whose body fails or traces | Runs when called, each time | Runs once, first, called or not | Ported, pinned |
| `intValue()`, `longValue()` past their width | Truncated | The whole value | Ported: T6 |
| `String.length()` beyond ASCII | UTF-16 units | UTF-8 bytes | Ported: T17 |

### Evaluation order

`Operators` and `Calls` state it. Java's order holds for arithmetic, `==`, `!=`, `<`, `<=`, `>`, `>=` (D6's binding, in
`PirHelpers.swappedInOrder`), `compareTo`'s twelve forms, a helper's arguments, and a registry method that applies its receiver
first. `&&`, `||` and `?:` run what Java runs. `Adr068CallsHarnessTest` traces each one against the JVM.

Two families do not hold it, on the reference too, and are ported as they are. The tests pin them, so a change shows:

| Family | Java | UPLC | Catalogued |
|---|---|---|---|
| `prepend`, `contains`, `insert` | receiver, argument | argument, receiver | No. New here; see the follow-ups |
| `abs`, `min`, `max`, `signum`, `compareTo` outside D6 | each operand once | an operand twice | `compareTo` is T13. The rest are its family |

## The I1 gate

The corpus is M3a's, with nine fixtures of this slice (`m3b-fixtures/`), because the recorded corpus holds few of a family's shapes
in a program that can compile yet. A freeze ran against M3a's table without `allowShrink`: no row lost coverage.

| Class | M3a | M3b |
|---|---:|---:|
| `i1a-identical` | 145 | 1,105 |
| `not-ported` | 9,617 | 8,634 |
| `reference-rejects-not-ported` | 496 | 484 |
| `javac-rejects` | 5,359 | 5,359 |
| `both-reject-javac-first` | 1,288 | 1,288 |
| `both-reject` | 49 | 62 |
| `ledger-source` | 38 | 38 |
| `unreplayable` | 17 | 17 |
| `DIFFERENT-javac-accepts` | 19 | 19 |
| `DIFFERENT-javac-rejects` | 10 | 35 |
| `DIFFERENT-rejection` | 10 | 10 |
| `DIFFERENT-bytes` | 4 | 6 |
| `DIFFERENT-provenance` | 2 | 4 |
| `DIFFERENT-diagnostics` | | 2 |
| Total | 17,054 | 17,063 |

The M3a column is its final head, `3bcd0dc9`. 3,721 rows moved against it, and none lost coverage, a total or its place in the corpus.
Of the 17,054 recorded rows, 3,712 moved: 956 from `not-ported` to `i1a-identical`, 25 to a policy rejection, 12 from
`reference-rejects-not-ported` to `both-reject`, 2 to a provenance row, 1 to a bytes row, 1 to a diagnostics row, and 2,715 that kept their
class and covered more methods or stopped at a later construct. The 9 rows new to the table are M3b's fixtures. `lowering-reference.tsv`
gained those 9 rows and changed none, and the freeze needed no `allowShrink`. Two fixtures changed a key when their component became
`value`, before any table held them.

### Coverage

| | Covered | Of | | M3a |
|---|---:|---:|---|---:|
| Programs the reference accepts | 1,105 | 15,145 | 7.3% | 145 |
| Their distinct scripts | 330 | | | 85 |
| Programs of more than one method | 808 | | | 11 |
| Methods the reference lowers, library methods included | 6,663 | 172,424 | 3.9% | 320 |
| Distinct covered methods | 1,133 | | | 160 |

**What a number means.** Most of the 1,105 are small: the ADR-060 families compile one small validator under many names, so distinct
scripts is the fairer count. 172,424 counts library bodies, which this slice does not lower. Of the 1,105, source maps have equal
fragments in 745 and differ only in a fragment's text in 358; every line and column is equal.

### The worklist

`first-construct`, what stops each case first. The total of a row counts a case once, by the first thing that stopped it.

| First construct | Cases | M3a |
|---|---:|---:|
| `VARIABLE` | 7,375 | 5,938 |
| `SWITCH_EXPRESSION` | 688 | 178 |
| `a library unit` | 317 | 7 |
| `IF` | 249 | 215 |
| `EXPRESSION_STATEMENT` | 181 | 40 |
| `NEW_CLASS` | 163 | 8 |
| `the exports of library sources` | 35 | 35 |
| `TYPE_CAST` | 28 | 8 |
| `reference equality` | 21 | |
| `LAMBDA_EXPRESSION` | 17 | |
| `ENHANCED_FOR_LOOP` | 15 | 14 |
| `a contract schema` | 10 | 9 |
| `METHOD_INVOCATION` | 9 | 1,122 |
| `WHILE_LOOP` | 7 | 7 |
| `MEMBER_SELECT` | 6 | |

The rows that grew are not regressions: a method that used to stop at its first call now reaches what stops it next. `METHOD_INVOCATION`'s
9 are calls outside what lowering reaches, such as a `@Param` initializer. `reference equality` is where a method alone stops; in a
compilation those are the `JULC0086` rows. Locals are the next slice by a wide margin.

### The differences

Every row is listed, by key, in [`068/lowering-accepted-differences.tsv`](068/lowering-accepted-differences.tsv). New in M3b:

| Rows | Class | D13 class | What |
|---:|---|---|---|
| 21 | `DIFFERENT-javac-rejects` | Policy rejection | `JULC0086`: `==` between references, where the reference compares values (T3, T4) |
| 4 | `DIFFERENT-javac-rejects` | Policy rejection | `JULC0087`: `equals` that reaches an array by identity, or a record that declares `equals` (T4, T5) |
| 2 | `DIFFERENT-provenance` | Provenance change | M3a's rule: for a source with no file, the file is named after the primary type |
| 1 | `DIFFERENT-bytes` | Representation only | D8: an unused library the reference keeps, as M3a's row |
| 1 | `DIFFERENT-bytes` | Semantic fix | `M3bSemanticFixes`: #297, and reads of a list literal typed by javac |
| 2 | `DIFFERENT-diagnostics` | Diagnostic change | `JULC0102`, on `M3aConcatenation` and `M3bFoldedStrings`. A `+` of string constants warns on `JAVAC`. On the JavaParser frontend the warning comes from the gate, so it is absent with the gate off, which is how the reference's table is taken. The bytes are compared and equal |

D13 asks a policy rejection for docs and release notes too; those are not written yet. The semantic fix's evidence is in-code, as stated
below.

## Results

Counted from JUnit XML.

| Task | Result |
|---|---|
| `./gradlew test --offline --continue --rerun --no-watch-fs` | 15,776 tests, 0 failures, 0 errors, 530 skipped, 27 modules |
| `:julc-compiler:test` | 5,100 tests, 0 failures, before the rejection-sites row; no compiler source has changed since |
| `:julc-stdlib:test` | 527 tests, 0 failures |
| `:julc-difftest:test` | 735 tests, 0 failures |
| `:julc-compiler:pairCaseTest --rerun` | 71 tests, 0 failures |
| `:julc-e2e-tests:onChainHarnessTest --rerun` | 16 tests, 0 failures |
| `:julc-wasm:wasmSmokeFull --rerun` (GraalVM 25.3.4.1) | `BUILD SUCCESSFUL`; 56 `ok` lines, no `not ok`. It runs the default frontend |
| `:julc-difftest:adr068Lowering` | `BUILD SUCCESSFUL`; 0 rows moved, 0 shrunk, after the freeze |
| `:julc-difftest:adr068Types` | `BUILD SUCCESSFUL` |
| `:julc-difftest:adr068Baseline`, gate off | `BUILD SUCCESSFUL`: as below |

On the first run after the merge, `:julc-difftest:test` failed in `Adr068RejectionSitesTest`, since `JULC0102` had no row in the catalogue,
and `adr068Lowering` failed on the 9 fixtures with no reference row. Both are closed at `9f3a71d1`. The `JulcPluginTest` failure and the two
`wasmSmokeFull` timeouts of the pre-merge runs were at a load of 200, and did not recur.

**The default path against B2.** `adr068Baseline` compares what it writes with M3a's B2 tables, byte for byte, with the gate off, and passed:
ledger, semantic, budget, instantiations, rejection inventory and corpus. The one exception it allows is the totals' `coverage` line,
which counts the rejection catalogue: 161 sites, not 157, of which 44 are not source rejections, not 40. Those four are `JULC0086`,
`JULC0087`, `JULC0090` and `JULC0102`, none a rejection of the frozen frontend.

| Check | Result |
|---|---|
| Byte identity against the reference | 1,105 programs, at all four levels, with and without a source map |
| I1b | 6,663 methods equal the reference's term and type up to alpha-equivalence, 1,133 of them distinct |
| I13's binding check | On, on every compilation in tests and each method lowered on its own. No violation. A read of a class's static method carries its element and is checked as a variable's is. A library call site's read is still exempt, as the follow-ups say |
| ADR-060 | `GeneratedBinderNameLintTest`, `BinderNameIndependenceTest` and `julc.verifyBinderNamespace` stay green |
| The three lints | Green. `lower/` looks nothing up by a made-up name: `facts.identity` is the only way to a registry |
| Evaluation against the JVM | `Adr068CallsHarnessTest`, 125 tests: each operator and call on the JVM and through both frontends at all four levels |
| Evaluation order | Each operator, the twelve `compareTo` forms over three orderings, a failing operand either side, a helper's arguments, and a registry method's receiver and argument, by traces the JVM produces |
| Short-circuiting | `&&`, `||` and `?:`, with the side that must not run traced or failing |
| Hosts | One test per host: `JulcPluginFrontendTest`, `BuildCommandFrontendTest`, `FrontendOptionTest`, `JulcAnnotationProcessorFrontendTest`. Each shows the script equals the default's, and that a construct not ported stops `javac` and not the default |

## Mutation evidence

Every mutation below ran in a throwaway worktree, one runner to a worktree under a lock, after a no-op control in that worktree: the
scoped unit tests, the harness, `Adr068LoweringToolTest` where it is said, and the I1 gate. A later step runs only if the earlier ones
passed, and they stop at the first failure, so a row names what caught the mutant, and how many tests would have is not measured.

**The pass of the merge.** 20 mutants of what the merge added, on `5b1a6c8d`, one runner in one worktree, after a no-op control: `JULC0102`'s
rule and its two paths to a result, a method read's provenance, a lowered field in `SubsetScan`, `methodType`'s varargs, and the
`DIFFERENT-diagnostics` class. 15 were killed. Of the 5 left, 4 had a real gap: a nested join, a comparison of constants, a lone method's
binders, and a signature after a body. Each now has a test at `7767cfe1`, and each was rerun and killed. The fifth, `withDropped` losing
the warnings, is equivalent today: `withDropped` runs in `analyze`, before any warning exists.

**The pass of the slice.** It ran before the merge. Its mutants were not rerun on the merged code, whose rules in front of the lowering
are M3a's and have M3a's own passes.

**99 mutants on that code: 96 killed, 78 of them by a unit test, 11 by the harness and 7 by the gate alone; 3 left, each equivalent for
now and said why below.** The no-op control passed in every worktree, through every step, before any mutant ran, at each commit
(unit 0/391, harness 0/195, gate 0 moved, hosts 15/0, at the last). Those are the controls' counts; the unit step is the `lower` tests and
`GeneratedBinderNameLintTest`.

**A pass was discarded.** The first run was replaced part way by a faster one, and the command meant to stop it matched nothing, so for
about an hour two runners patched the same worktrees. A mutant in that pass could have been caught by another's change, which is
the fault M3a's evidence warns of, so none of its results are used. The numbers here are from a clean pass. The control caught
nothing in that case, since it had passed before the second runner started; the lock is what prevents it now.

**Survivors.** The clean pass left 13, and 2 mutants no longer applied. Each is accounted for:

| Mutant | Outcome |
|---|---|
| C12 | Had a test, in a class the runner did not run. Rerun with it |
| C13, C16, F1, K15, K16, K18, R12, W5 | Each had a real gap, and now has a test. Rerun, and killed |
| C10, C19 | Their code changed between passes. Rewritten, and rerun. C19 then survived, and has a test |
| F3 | Showed that `declaredInSource`'s end-position check said nothing more than the tree. Simplified; F3b replaces it |
| K14, an earlier survivor | Showed that recording a dispatch's type changes nothing here, since a recorded type is read only where javac says Data and the term says so too. The recording is gone, so this mutant is too |
| C9 | **Equivalent for now.** A function argument stops at `JULC0084` by either path, until lambdas are ported |
| O26 | **Equivalent for now.** `requireIntegers` cannot fire: what is not an integer here stops at its operand or at javac. It stays, because casts and locals will reach it |
| R9 | **Equivalent for now.** Nothing nests inside a lowered member yet: its statements are `return`s |

**Operators**

| Mutation | | Killed by |
|---|---|---|
| O1 | > binds nothing: right operand first | unit test |
| O2 | >= binds nothing: right operand first | harness |
| O3 | > is < | unit test |
| O4 | <= is < | unit test |
| O5 | - swaps its operands | unit test |
| O6 | / floors | unit test |
| O7 | % is mod | unit test |
| O8 | && tests its right operand first | unit test |
| O9 | \|\| tests its right operand first | unit test |
| O10 | ?: swaps its branches | unit test |
| O11 | ! does nothing | unit test |
| O12 | - is n - 0 | unit test |
| O13 | != is == | unit test |
| O14 | compareTo != is == | unit test |
| O15 | a zero on the left is not reversed | unit test |
| O16 | 0L is D6 zero | unit test |
| O17 | any compareTo is D6 | unit test |
| O18 | one looks through parentheses for D6 | unit test |
| O19 | compareTo operands in the wrong order | unit test |
| O20 | the minimum value negates | unit test |
| O21 | the minimum value of a long negates | unit test |
| O22 | parentheses hide the minimum value | unit test |
| O23 | references compare in lowering | unit test |
| O24 | + ignores strings | unit test |
| O25 | a string + with another type lowers | unit test |
| O26 | no integer check | survived |
| O27 | conditional native boundary off | unit test |

**Classification**

| Mutation | | Killed by |
|---|---|---|
| C1 | a declared accessor is a component | unit test |
| C2 | any receiver of an accessor | unit test |
| C3 | a helper call is typed | unit test |
| C4 | valueOf by name | unit test |
| C5 | any qualifier of a static call | unit test |
| C6 | equals between unrelated types | unit test |
| C7 | equals on an array lowers | unit test |
| C8 | a declared toPlutusData lowers | unit test |
| C9 | a list method with a function is a registry method | survived |
| C10 | a wrapper component named like a method lowers as it | unit test |
| C11 | a varargs helper lowers | unit test |
| C12 | a varargs library method lowers | harness |
| C13 | a generic library method lowers | unit test |
| C14 | a method of this class qualified | unit test |
| C15 | any simple name is a local | unit test |
| C16 | no byte-string guard | unit test |
| C17 | a library unit is not one | gate |
| C18 | stdlib keys are all qualified | unit test |
| C19 | fromPlutusData on any class | unit test |
| C20 | equals by any parameter | unit test |
| C21 | a stdlib call is typed | harness |
| C22 | a user Value is the ledger's | unit test |

**Lowering a target**

| Mutation | | Killed by |
|---|---|---|
| K1 | arguments reversed | harness |
| K2 | the first argument of a helper is dropped when there are two | unit test |
| K3 | the term never types a stdlib receiver | harness |
| K4 | contains does not encode | harness |
| K5b | a hash read from a call decodes again | unit test |
| K6b | value is not the identity of a wrapper | unit test |
| K7 | the wrong field | unit test |
| K8 | Data stays Data | harness |
| K9 | target requirements ignored | gate |
| K10 | a library call skips the stdlib | gate |
| K11 | a subtype of an API type dispatches | unit test |
| K12 | cast does not decode a map | gate |
| K13 | toPlutusData does not encode | gate |
| K15 | no native check on a helper argument | unit test |
| K16 | a non-literal cast class lowers | unit test |
| K17 | JulcMap.of is not rejected | unit test |
| K18 | equals dispatches on the static type | harness |
| K19 | JULC0091 is not located | gate |

**Wrappers**

| Mutation | | Killed by |
|---|---|---|
| W1 | a wrapper read as Data lowers | unit test |
| W2 | fromPlutusData on a hash is an identity | unit test |
| W3 | hash ignores whether its call is typed | unit test |
| W4 | a component read from a variable counts as decoded | unit test |
| W5 | value counts as Data | unit test |

**The subset's rules**

| Mutation | | Killed by |
|---|---|---|
| R1 | JULC0086 off | unit test |
| R2 | JULC0090 off | unit test |
| R3 | JULC0087 off | unit test |
| R4 | an alias is not followed | unit test |
| R5 | one primitive operand is not enough | unit test |
| R6 | a kind is allowed everywhere | unit test |
| R7 | an assignment is allowed everywhere | unit test |
| R8 | null is reference equality | unit test |
| R9 | a nested member keeps the outer context | survived |
| R10 | every static field is reached | unit test |
| R11 | instance methods are reached | unit test |
| R12 | constant strings need no + check | unit test |
| R13 | the collection sites are not element-aware | unit test |
| R14 | only the left operand of + is checked | unit test |

**Facts and coordination**

| Mutation | | Killed by |
|---|---|---|
| F1 | boxes have no value type | harness |
| F2 | an unrepresentable argument stops | harness |
| F3b | every accessor counts as declared | unit test |
| F4 | JULC0057 off | unit test |
| F5 | the JULC0091 check runs on inferred types | harness |
| F6 | the wrong method type for a helper | gate |

**Hosts**

| Mutation | | Killed by |
|---|---|---|
| H1 | Gradle ignores the option | unit test |
| H2 | the CLI ignores the option | unit test |
| H3 | JulcEval ignores the option | unit test |
| H4 | ValidatorTest ignores the option | unit test |
| H5 | the processor ignores the option | unit test |
| H6 | the plugin does not tell the processor | unit test |
| H7 | the frontend is not a task input | unit test |

## The merge of M3a

`d99a9cd4` and its follow-up merge M3a through `0d505def`. What was not mechanical:

- **`SubsetScan`.** M3a reads a field for its literals, since `UnloweredInitializers` judges what is not lowered, and M3b allows a kind
  where lowering reaches it. A field the compilation lowers is now a member, as a method is, so `EqualityRules` reads it. Any other
  field, an initializer block and an annotation are read for their literals. An assignment is then on no list, so `inAnnotation` went.
- **A method's type.** M3b's `JavacFacts.methodType` takes a parameter through M3a's `parameterType`, so `byte...` reads as its element's
  type there too, and a local varargs method is a rejection at its signature, not `a call of a varargs method`.
- **A method read.** `Terms.method` records the element, and `BindingCheck` resolves it as it does a variable. A binder goes by class and
  name, so `JavacFacts.binderMethod` names one element for interchangeable overloads. A lone method's check binds the class's methods too.
- **The lints.** `lower/` calls five more `PirHelpers`, none of which takes a source name. A stdlib lookup takes its key from a
  `CallTarget.Keyed`, which `CallTargets` makes from the owner's identity, and lint 3 now reads the first string past a context.
- **Tests that met the other side's rules.** A field nothing lowers is `JULC0095`. `Boolean.valueOf(true)` in a `@Param` is accepted.
  A non-static entry point is rejected before lowering. A `@NewType` of bytes takes the component `value` at the gate, so two fixtures
  renamed theirs and their case keys change. `"a" + "b"` lowers, and `"\uD83D" + "\uDE00"` is rejected on both frontends.
- **The tool.** A row that differs only in diagnostics is `DIFFERENT-diagnostics`, which `Diagnostic change` explains. A row that also
  differs in bytes or params stays `DIFFERENT-bytes`, so a warning cannot cover for bytes.

`M3bFoldedStrings`' accepted entry is gone: M3a's parser option keeps the `+`. Until the gate has run on the merge, the row table in the
branch is M3a's.

**`JULC0102`.** It reads javac's trees, so both frontends report one code, position and text. On the JavaParser frontend it comes through
the gate, in `enforce` and `report` mode, and it is absent with the gate off. That is how the reference's table and B2 are taken, so neither
moved. A constant is a string literal, a `String` variable javac gives a constant value, or a `+` of constants, through parentheses, and one
warning covers a whole expression of constants. An annotation's argument does not warn. The rule reads a compilation's roots, so a join in a library unit does not warn, and a `JavaLibraryProvider` compilation reports none.

## The stops for M5

Every `JULC0084` a compilation can reach through this slice, by what the default frontend does with the same shape behind the default
gate. `JavacStopsTest` pins each shape's construct and the default's answer, so a shape that starts to lower, or a default that changes,
fails there. Stops of a shape the reference gets wrong stay stops on `JAVAC`, and the frozen frontend does not change.

**The default accepts it.** These need a decision: M5 cannot switch the default while they stop.

| Construct | Shapes | JVM against the default frontend |
|---|---|---|
| `a wrapper's component read as Data` | `p.hash()` on a parameter or `@Param`; `(r.p()).hash()`; `t.name()` | Java returns the bytes; the default applies `UnBData` to bytes, and evaluation fails |
| `fromPlutusData on a ledger hash` | `PubKeyHash.fromPlutusData(d)` | The default's identity leaves Data; used as bytes, evaluation fails |
| `the negation of a type's minimum value` | `-0x80000000`, `-(-2147483648)`, `-0x8000000000000000L` | The JVM wraps to the same negative value; the default gives the positive one |
| `an equals between unrelated types` | `PubKeyHash.equals(BigInteger)`, `BigInteger.equals(PlutusData)` | Java says false; the default compares representations, and fails on the first. `JULC0078` comes with ADR-067 M4 |

**The default rejects it at the gate (`JULC0101`).** The gate's spelling rules run only for the JavaParser frontend. At M5 `JAVAC` takes
one of two paths for each: lower what javac bound, or take the rule's code.

| Construct | Shapes |
|---|---|
| `a wrapper's component read as Data` | `h.v()` on a `@NewType` of bytes whose component is not `value` |
| `the method toString of a byte string`, `the method hashCode of a byte string` | `p.toString()`, `p.hashCode()` |
| `a wrapper's component named like a method of its value` | `@NewType record A(BigInteger abs)`, `@NewType record A(byte[] length)` |
| `a declared accessor`, `a declared toPlutusData` | An explicit accessor or `toPlutusData` on a record (T31) |

**The default rejects it, with no code.** `JAVAC` needs that rejection, with a code, at M5.

| Construct | Shapes |
|---|---|
| `a static call not qualified by a simple class name` | `java.math.BigInteger.valueOf(1)` |
| `a method with no stdlib lowering` | `Math.floorMod(a, 2L)` |
| `a text block` | Any text block. The rule that lifts it is a slice's, since `leadingSource` is what reads it |

**A guard behind another rule.** No program reaches these past the rule named, and they stay as fail-closed lines: `an equals on an array`
(`JULC0087`), `a call of a varargs method` on a local (a `byte...` parameter is rejected at the signature, and any other at its array
type; a library method's call site reaches it only in I1b), `a method of a subtype of an API type` (`inheritance` stops first, and
`LoweringAloneTest` reaches it past the scan), and `a component the type does not register`, which no test reaches.

**Constructs a later slice ports.** `VARIABLE` and the other kinds of the worklist, a function argument to `map`, `filter`, `any`, `all`
and `find`, `a library unit`, `a contract schema` and `debug metadata`. These are not M5 decisions unless a slice leaves one.

## What this does not establish

- **Programs with locals.** A covered program is a set of single `return`s. No element is bound twice, and no continuation is
  duplicated, so I13 is still on easy terms.
- **The rows of the table above.** The ported ones are pinned, and go to the calls slice. The stops are `JULC0084`, and M5's zero-count
  gate does not accept that code, so each is listed in [the stops](#the-stops-for-m5) for its decision.
- **Library bodies.** Call sites are compared by I1b. No program that reads a library unit compiles, so no library call has run on
  this frontend.
- **Ledger types on the VM.** The harness has no mapping for `PubKeyHash`, `Value` or a `@NewType` argument, so those paths rest on
  byte identity with the reference, not on the JVM.
- **The D13 evidence for the semantic fixes.** They run on the JVM in `Adr068CallsHarnessTest`, as in-code cases, not as saved
  `.diffcase` files, because a saved case joins the corpus and moves B1's tables.
- **I2a's inventory.** Still not run on this frontend. `JULC0084` stands in front of `SubsetValidator`'s other rules, and D6's three
  rules run in the scan's order, so `"a" + 1 == "a1"` is `JULC0086`, the outer rule.
- **A contract schema.** `compileContract` is still `JULC0084`, so a host's blueprint is. The hosts' tests turn it off.
- **Images.** `wasmSmokeFull` runs the default frontend, so the lowering has not run in a native or Web image.
- **DevKit end-to-end tests** were not run. Nothing here changes a script on the default frontend.

## What the next slice inherits

- **Add to `Lowering.expr`, and to `SubsetScan`'s `ALLOWED` or `WHERE_LOWERED`, together.** A new kind that lowering reaches belongs in
  `WHERE_LOWERED`.
- **`JavacDiscovery.lowered`** is the one list of what lowering reaches; a slice that lowers more of a unit extends it.
- **Records** meet `typedCall`: `new R(...)` as a receiver is `JULC0055` on the reference, and stays so unless a decision says otherwise.
- **Locals** meet `Typing.of(tree, term)`: a `var`'s type comes from javac, and a local's binder from `Scope`.
- New known issues are in [`068-followups.md`](068-followups.md).
