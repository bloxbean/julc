# ADR-068 M3c: locals, `if`, `return` and `instanceof`

M3c is the fourth and fifth rows of M3 in the ADR's order ([ADR-068](../068-javac-frontend-migration.md)). A program of locals, `if`/`else`,
`return`, expression statements and `instanceof` now compiles through `frontend=JAVAC`, to the reference's FLAT bytes, at all four
levels. Records, fields and object creation, `switch`, lambdas, loops and libraries stay `JULC0084`, and a method that needs one stops
there. Nothing falls back (I8), and the JavaParser frontend did not change.

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md). Machine and toolchain as in M0's evidence, with
`25.0.2-librca`. Branch `wip/068-m3c`, based at `7c68db52`, which is M3b.

## What was delivered

| Piece | Where |
|---|---|
| Statement lists in the reference's continuation style: a local and an expression statement are strict `let`s, `cont` a plain term | `lower/Statements` |
| The reference's three shapes as named methods: `ifWithExit`, `ifWithLoop` (a stop until M4), `ifForEffect` | `lower/Ifs` |
| What a statement's shape says about leaving it, walked by hand, since lint 1 keeps walkers out of `lower/` | `lower/Exits` |
| `instanceof`: a tag comparison, on the variant javac bound | `lower/TypeTests` |
| `Terms.let` and `Terms.afterEffect`, the only builders of those binders | `lower/Terms` |
| `Frame.in`, a frame with a binder's scope | `lower/Frame` |
| The scan reads statements where lowering reaches them, and takes `JULC0052`, `JULC0053`, `JULC0059` and the reference's warning on code after `Builtins.error()` | `subset/SubsetScan`, which now returns warnings |
| `JULC0061` for an assignment, `JULC0066` for a label, `Undefined variable` for a pattern variable out of place | `lower/Lowering`, `lower/Statements`, `lower/Names` |

### Rules worth stating

- **Generation order is the reference's, shape by shape.** It decides which of two rejections comes first (I2a). In `ifWithExit` what
  follows is built first, then the condition, then the branches. In `ifForEffect` what follows is built last. With a pattern, the `else`
  branch is built before the condition. `JavacStatementsTest.theFirstOfTwoRejections` pins each.
- **Where a rejection lives.** What the reference rejects before generating (`SubsetValidator`) is `SubsetScan`'s. What it rejects while
  generating stays in the family that reaches it, which is the plan's "or leave those checks in place": moving it would change the order.
- **The `if` condition is `PARENTHESIZED`.** `Ifs.condition` takes off that one level. A user's second pair stays, so
  `if ((s instanceof A a))` is the test with no binding, and reading `a` is `Undefined variable: a`, as in the reference.
- **`int a = 1, b = 2;`** is two trees at one start, and the last one's source text is the whole declaration, so `JULC0053` has the
  reference's message.
- **A pattern variable binds only where it is the whole condition.** Everywhere else the test lowers and the variable is
  `Undefined variable`. Java accepts those programs; the subset does not (I2).
- **A bare block in a statement list is `Unsupported statement: BlockStmt`,** as in the reference. A branch's block is fine. The plan's
  "bare blocks splice" is what the reference does in loop bodies, so it is M4's.
- **`allPathsReturn` is the reference's, not javac's flow analysis.** They differ on a bare block (`{ return 1; }`), which it does not read,
  so that program is rejected for two reasons, in the reference's order. A label counts as a path here, because the reference throws at the
  label while generating, which goes before what this check collected.
- **The type of a local.** What the source wrote, by `facts.pirType`. For `var`, javac's, with one exception.
- **A `var` of a stdlib call binds what the term says.** The reference does not know a stdlib call's type, so `Optional.of(a).isPresent()` is
  `JULC0055` there and in M3b. A `var` of that call must not widen it (I2), so its binder takes the term's type and a member read
  dispatches on the binder: `var o = Optional.of(a); o.isPresent()` is `JULC0055` on both. What a list's reads decode to stays javac's,
  so the same rule leaves the D13 row below in place. This also makes the `Var` annotations equal to the reference's.
- **`var` of an array of two element types** keeps the reference's `JULC0012`: `Calls` records the element types with the term.

### `instanceof`

`TypeTests` reads the variant from the pattern's type and the operand's static type from javac, and never a spelling.

| Shape | Answer |
|---|---|
| Operand is the variant, or its one sealed interface | The tag comparison, with the tag at the variant's position in `facts.permitted`, by identity |
| No type pattern, a record pattern, or a type that is no variant of a registered sum | The reference's `Unsupported instanceof pattern`, located |
| Operand of any other static type | `JULC0084`: `an instanceof of an operand outside the variant's sealed interface` |
| A variant of two sealed interfaces | `JULC0084`: a tag is per interface, and the reference takes the last registered. No rule on the javac frontend names it yet |
| A variant that is a class, or a `@NewType` record | `JULC0084`: the reference accepts these; a `@NewType` is not Constr-encoded, so a tag read of it is not Java's test |

The third row is the gate's `JULC0100` rule as a stop, since the gate's rules do not run on `JAVAC`. javac rejects an unrelated operand, an interface
that is not sealed is no type of the subset, and `Object` comes only by a cast, which stops first. `(c ? a : b) instanceof S.B x`, with `S.A a, S.B b`,
reaches it with no cast: the operand's type is the lub `Record & S`, and the default frontend's answer is `JULC0100`. It also stands in front of
`(Object) r instanceof S.A a` when casts are ported. `LoweringAloneTest` reaches it past the scan.

**Evaluation order.** `TypeTests`, `Ifs` and `Statements` state it. As in the reference, `if (e instanceof V v)` lowers `e` twice, so where the
test holds it runs twice. It is pinned, not changed: the second evaluation gives the same value, and only a trace or the budget shows it.

### I13

`Scope.bind` chooses a binder's name while the `let` is built. What follows an `if` is built once, in the frame around the `if`, so its free
variables are all visible where a branch's binder is chosen, and a branch local of a static's name takes `K'1`. Every copy of it is the
same term by reference (`LoweringAloneTest.whatFollowsAnIfIsOneTerm`), so no element is bound twice, and the per-method table the follow-ups
asked for was not added. A later slice that lowers a statement twice needs it. `verifyBinderNamespace` is on in both modules' tests, so
`BindingCheck` walks every copy of every compiled term here.

## The I1 gate

The corpus is M3b's, with no fixture added: this slice's shapes are JUnit tests, so no row joins B2's tables. The tables stay as they are. The
integrator freezes them, and the 30 rows below are in [`068/m3c-accepted-differences.tsv`](068/m3c-accepted-differences.tsv), in the format of
`lowering-accepted-differences.tsv`. Until then the compare reports moved rows and fails by design.

| Class | M3b | M3c |
|---|---:|---:|
| `i1a-identical` | 1,105 | 1,414 |
| `not-ported` | 8,634 | 8,297 |
| `reference-rejects-not-ported` | 484 | 463 |
| `javac-rejects` | 5,359 | 5,359 |
| `both-reject-javac-first` | 1,288 | 1,288 |
| `both-reject` | 62 | 81 |
| `ledger-source` | 38 | 38 |
| `unreplayable` | 17 | 17 |
| `DIFFERENT-javac-accepts` | 19 | 19 |
| `DIFFERENT-javac-rejects` | 35 | 60 |
| `DIFFERENT-rejection` | 10 | 12 |
| `DIFFERENT-bytes` | 6 | 7 |
| `DIFFERENT-provenance` | 4 | 6 |
| `DIFFERENT-diagnostics` | 2 | 2 |
| Total | 17,063 | 17,063 |

`shrunk` is 0. 7,827 rows moved:

| Rows | From | To | What |
|---:|---|---|---|
| 309 | `not-ported` | `i1a-identical` | They compile now, to the reference's bytes |
| 7,090 | `not-ported` | `not-ported` | A method stops at a later construct, or more methods are covered |
| 377 | `reference-rejects-not-ported` | the same | The same |
| 19 | `reference-rejects-not-ported` | `both-reject` | The reference's rejection, now reached: `JULC0052`, `JULC0053`, `JULC0059`, `JULC0061`, `JULC0066` and the statement rules |
| 25 | `not-ported` | `DIFFERENT-javac-rejects` | D6, reached through a local: below |
| 2 | `DIFFERENT-javac-rejects` | the same | The same rule; a method stops later |
| 2 | `reference-rejects-not-ported` | `DIFFERENT-rejection` | Below |
| 2 | `not-ported` | `DIFFERENT-provenance` | Below |
| 1 | `not-ported` | `DIFFERENT-bytes` | Below |

### Coverage

| | Covered | Of | | M3b |
|---|---:|---:|---|---:|
| Programs the reference accepts | 1,414 | 15,145 | 9.3% | 1,105 |
| Their distinct scripts | 610 | | | 330 |
| Programs of more than one method | 885 | | | 808 |
| Methods the reference lowers, library methods included | 8,845 | 172,424 | 5.1% | 6,663 |
| Distinct covered methods | 1,725 | | | 1,133 |

Most of the rest now stop at what was behind a local. Of the 1,414, source maps have equal fragments in 861 and differ only in a fragment's text
in 551. Every line and column is equal, but for the provenance rows below.

### The worklist

| First construct | Cases | M3b |
|---|---:|---:|
| `ENHANCED_FOR_LOOP` | 3,459 | 15 |
| `WHILE_LOOP` | 1,914 | 7 |
| `a library unit` | 1,177 | 317 |
| `SWITCH_EXPRESSION` | 792 | 688 |
| `NEW_CLASS` | 738 | 163 |
| `LAMBDA_EXPRESSION` | 379 | 17 |
| `TYPE_CAST` | 210 | 28 |
| `reference equality` | 37 | 21 |
| `VARIABLE` | 2 | 7,375 |
| `IF` | 0 | 249 |
| `EXPRESSION_STATEMENT` | 0 | 181 |

The rows that grew are not regressions: a method that used to stop at its first local now reaches what stops it next. The 2 `VARIABLE` rows are
locals in an instance method, which nothing lowers: the scan reads only `return` there (the follow-ups' "Scan broader than lowering").

### The differences

| Rows | Class | D13 class | What |
|---:|---|---|---|
| 23 | `DIFFERENT-javac-rejects` | Policy rejection | `JULC0086`: `==` between references, reached through a local (T3, T4) |
| 2 | `DIFFERENT-javac-rejects` | Policy rejection | `JULC0087`: `equals` on `byte[]` in the external examples, reached through a local (T4) |
| 2 | `DIFFERENT-rejection` | Diagnostic change | `JULC0086` where the reference says `JULC0041`: `==` between two locals of a native type. D6's rule runs in the scan, before the lowering's. I2a needs a decision for a coded rejection that changes, and D6 is that decision |
| 1 | `DIFFERENT-bytes` | Semantic fix | `BigInteger Shadow = a; return Shadow.compareTo(b) > 0;` beside a library `Shadow`. The local is the local here, and the reference calls the library's method (T33, D4's `Shadow` case). `SpelledProgramsOnJavacTest` meets the JVM on it. The default gate rejects it, `JULC0100` |
| 1 | `DIFFERENT-provenance` | Provenance change | M3a's rule: a source with no file is named after its primary type |
| 1 | `DIFFERENT-provenance` | Provenance change | The reference locates one term less: the read in `return LIMIT;` of a local named like a static. Renaming the local gives the reference that location too, so the difference is the binder's name. The located set here is a superset, with equal lines and columns |

The same differences with no row, since no corpus case has them:

| D13 class | What | Evidence |
|---|---|---|
| Semantic fix | `var xs = JulcList.of(...); xs.head()` reads by javac's element type. The reference keeps Data, and evaluation fails or the result is not the declared type | `Adr068StatementsHarnessTest.aVarOfAListLiteralIsTypedByItsElements` |
| Semantic fix | `instanceof` on one of two variants of one simple name takes its own tag (F807). The reference compares the first's | `Adr068StatementsHarnessTest.twoVariantsOfOneSimpleNameHaveTwoTags` |
| Diagnostic change | A rejection the reference raises with no diagnostic (`Unsupported instanceof pattern`, `EmptyStmt` in a branch, `Undefined variable: x`) is located here, with its words. Types as M2's row says | `JavacStatementsTest` |

**Widening.** `DIFFERENT-javac-accepts` is 19, as it was. M3c's first run had none either, but a shape the corpus lacks did widen: `var o =
Optional.of(a); o.isPresent()`, and `var a = JulcArray.of(BigInteger.ONE, "x")`, each agreeing with the JVM where the reference rejects. Both closed by the
rules above. Parentheses, a conditional and `var p = o` around the first stayed open past this slice, and Group 1 closes them with one rule: `CallTargets.seen`.

## Results

Counted from JUnit XML. Lowering code last changed at `a9f7069e`, and the commits after it hold tests and this evidence alone. The suites ran at `aebac10d`, and I1 at `a9f7069e`.

| Task | Result |
|---|---|
| `:julc-compiler:test --offline --continue --rerun --no-watch-fs` | 5,259 tests, 0 failures, 0 errors, 0 skipped, 356 classes |
| `:julc-difftest:test`, the same | 817 tests, 0 failures, 0 errors, 0 skipped, 43 classes |
| `:julc-difftest:adr068Lowering` | Fails by design until the freeze: 7,827 moved, 0 shrunk. Every `DIFFERENT` row is in `lowering-accepted-differences.tsv` or in `m3c-accepted-differences.tsv`, with no stale entry either way |
| `:julc-difftest:fuzz`, 3 minutes | 1,993 programs, coverage 7,005, no finding, at `aebac10d` |

Of the new classes: `JavacStatementsTest` 143, `SpelledProgramsOnJavacTest` 67, `Adr068StatementsHarnessTest` 78, `Adr068StatementsPropertyTest` 1, `Adr068StatementsFuzzTest` 8, and `JavacStopsTest` 46, 6 more than M3b's.

The full suite, the gate-off baseline, native images and a freeze are the integrator's, and were not run. No file under `pir/`, `resolve/`, `validate/` or `codegen/`, and not `JulcCompiler`, changed in this slice, so the JavaParser frontend is as it was.

| Check | Result |
|---|---|
| Byte identity against the reference | 1,414 programs at all four levels, with and without a source map, and each `JavacStatementsTest` program with its source map's lines and columns |
| I1b | 8,845 methods equal the reference's term and type up to alpha-equivalence, 1,725 of them distinct |
| I13's binding check | On in every compilation in both modules' tests. No violation. `LoweringAloneTest` shows a branch local takes a mark, and `BindingCheck` over that term |
| ADR-060 | `GeneratedBinderNameLintTest`, `BinderNameIndependenceTest` and `julc.verifyBinderNamespace` stay green |
| The three lints | Green, and not weakened. `PIR_HELPERS` gains `freeVariables` and `hygienicName`, and lint 2 now requires `hygienicName`'s first argument to be a `#` literal, with a negative control |

### What the tests really evaluate

A rejection is not a pass, so these count only programs that compiled and ran.

| Test | What runs |
|---|---|
| `Adr068StatementsHarnessTest` | Each program on the JVM and through both frontends at all four levels, on values, traces and failures, at boundary inputs. An `assertAgree` fails on `REJECTED`, and it also compares the frontends' value, cost and size |
| `Adr068StatementsPropertyTest` | 250 generated programs, seed `20261102`, against a plain Java model, the JVM, and both frontends at all four levels. An `@AfterProperty` fails if a shape falls below its quota. One run held 100 programs with an `if`, 65 with an `else`, 59 with `instanceof`, 26 with a local that shadows a static, 79 with traces and 13 that fail |
| `Adr068StatementsFuzzTest` | The same check under Jazzer, over the same plan. 7 inputs replay in `test`, and the empty one. Below |
| `SpelledProgramsOnJavacTest` | 57 programs of `SpelledReadingsTest` and 8 of this slice. Of the review's sources `SpelledReadingsTest` did not hold 2: one is a row here, and the other reaches no statement. 9 evaluate, each against a real class's answer. A test fails if fewer do |

**`SpelledProgramsOnJavacTest` by answer.** 9 agree with the JVM. 46 stop at `JULC0084`. 2 take `JULC0087` and `JULC0092`, which both frontends have. 8 are a rejection in the
reference's words with no code, `Undefined variable: Rules`: a call of a class that is no library, as M3b left it. So those 8 neither stop nor run, which is more than "stops or agrees" says. None compiles to another answer. The `instanceof` programs that build their value with `new` stop at `NEW_CLASS`, so each has a row here that takes it as Data: `S.X` beside a member `X`, the sibling of it,
and a widening cast, which stops at `TYPE_CAST`.

**Jazzer's one finding was the generator's.** An earlier 3-minute run stopped at a program javac rejected: `if (s instanceof S.B y) { ... } else { return 1; }`, then a second `y`. By JLS 6.3.2.2
`y` is in scope after that `if`. The generator took the name to be free, so the fault was in the test, and the compiler was right to reject it. The name is taken now, and the input replays as
`pattern-variable-after-its-if`. Reading `y` there is a program of its own: `Undefined variable`, as in the reference, in `aPatternVariableBindsOnlyWhereItIsTheWholeCondition`.

## Mutation evidence

Every mutation ran in a throwaway worktree, after a no-op control in that worktree: the `lower` and `subset` tests below, then the harness and property test, then I1 compared with the control's table. A later
step runs only if the earlier ones passed, so a row names what caught the mutant, and how many tests would have is not measured. `*JavacStatementsTest*`, `*LoweringAloneTest*`, `*JavacStopsTest*`,
`*SpelledProgramsOnJavacTest*`, `*ScopeTest*`, the lints, and the operators, rejections, calls, names and literals tests are the unit step.

**74 mutants on the slice's lines: 72 killed, 67 by a unit test, 4 by the harness and 1 by the gate alone; 2 left, each equivalent for now and said why below.**

**The controls.** Unit and harness exit 0 at `a9f7069e`, `4c57182c` and `aebac10d`. The gate ran twice with no change and gave one table of 17,063 rows.

**A pass was discarded.** A first pass ran at `56359547`, and stopped after E7 when its worktree was removed by another agent's cleanup, which matched its path. The lowering had moved past that base by then, in `SubsetScan` and `Exits`, so
none of its results are used, though they showed two gaps that are fixed here (below). The pass reported here is a new one over every mutant, in a directory of this slice's own, at `a9f7069e`.

**Survivors.** The pass left 9, and each is accounted for. E5 and E4 of the discarded pass went a better way: they showed that `Exits` read a list's rest twice and had a case for a label that every shape rejects, so those lines are gone, and so are those mutants.

| Mutant | Outcome |
|---|---|
| S8, E3 (discarded pass) | Each had a real gap, and has a test. In the reported pass a unit test kills each |
| E8, T9, C2, A6, A9, A11 | Each had a real gap, and now has a test. Rerun, and killed by a unit test |
| A13 | Passed unit and harness. The gate kills it: a corpus row moves |
| T7 | **Equivalent.** `TypeRegistry` holds a `SumType` only for a sealed interface, so the second half of the filter decides. The check stays, since it states the rule |
| Te2 | **Equivalent for now.** `#_if` is hygienic against terms of another builder, and no term a Java source reaches holds that name free. Kept, by ADR-060 |

**Statements**

| Mutation | | Killed by |
|---|---|---|
| S1 | an expression statement is dropped | unit test |
| S2 | the end of a list ignores cont | unit test |
| S3 | a local is not in scope after it | unit test |
| S4 | a var of a stdlib call takes javac type | unit test |
| S5 | a var of two element types lowers | unit test |
| S7 | no all-paths check | unit test |
| S8 | no native check on a local | unit test |
| S9 | a block branch is not opened | unit test |
| S10 | a single expression branch is unit | unit test |
| S11 | a bare block is a stop | unit test |
| S12 | a label in a list lowers its statement | unit test |
| S13 | a label in a branch lowers its statement | unit test |
| S14 | a declared local takes the value type | unit test |
| S15 | a missing initializer is unit | unit test |
| S16 | a nested if in a branch is a stop | unit test |

**Ifs**

| Mutation | | Killed by |
|---|---|---|
| I1 | an if never has an exit | unit test |
| I2 | only the then branch exits | harness |
| I3 | no else means unit | unit test |
| I4 | the branches swap | unit test |
| I5 | an effect is dropped | unit test |
| I6 | a last if ignores cont | harness |
| I7 | what follows is always bound | unit test |
| I8 | the parentheses all come off | unit test |
| I9 | a pattern if swaps its branches | unit test |
| I10 | a pattern variable is not in scope | unit test |
| I11 | a nested if has no else | unit test |
| I12 | a loop is not looked for | unit test |
| I13 | with a pattern the else comes after | unit test |
| I14 | what follows is built after the branches | unit test |
| I15 | an effect comes after what follows | unit test |
| I16 | no position on an effect | unit test |
| I17 | no position on an exit | unit test |
| I18 | a pattern in an effect is not a pattern | unit test |
| I19 | the scrutinee is the condition | unit test |

**Exits**

| Mutation | | Killed by |
|---|---|---|
| E1 | a block holds no return | unit test |
| E2 | a label is no return path | unit test |
| E3 | one returning branch is enough | unit test |
| E6 | an else if holds no return | harness |
| E7 | a nested loop is not found | unit test |
| E8 | a loop in an else if is not found | unit test |

**TypeTests**

| Mutation | | Killed by |
|---|---|---|
| T1 | the tag by simple name | harness |
| T2 | the tag of the first constructor | unit test |
| T3 | two parents take the first | unit test |
| T4 | a class or @NewType variant lowers | unit test |
| T4b | a @NewType variant lowers | unit test |
| T5 | any operand lowers | unit test |
| T5b | only the interface is an operand | unit test |
| T6 | no sum is a stop | unit test |
| T7 | a non-sealed parent counts | survived |
| T8 | the test is not equality | unit test |
| T9 | the operand is not lowered in order | unit test |

**Terms**

| Mutation | | Killed by |
|---|---|---|
| Te1 | a let records no binder | unit test |
| Te2 | a fixed name for the effect | survived |

**Names and Lowering**

| Mutation | | Killed by |
|---|---|---|
| N1 | a pattern variable out of place is a stop | unit test |
| L1 | a reassignment names the whole expression | unit test |
| L2 | no position on instanceof | unit test |
| L3 | a compound assignment stops | unit test |
| L4 | an assignment stops | unit test |

**Calls**

| Mutation | | Killed by |
|---|---|---|
| C1 | a variable dispatches on javac type | unit test |
| C2 | an accessor reads any receiver | unit test |
| C3 | no array literal is recorded | unit test |

**SubsetScan**

| Mutation | | Killed by |
|---|---|---|
| A1 | JULC0053 off | unit test |
| A2 | JULC0059 off | unit test |
| A3 | a component is a field | unit test |
| A4 | JULC0052 off | unit test |
| A5 | no warning | unit test |
| A6 | any error call warns | unit test |
| A7 | unlowered statements are read | unit test |
| A8 | a var type is scanned | unit test |
| A9 | a label body is scanned | unit test |
| A10 | the semicolon stays | unit test |
| A11 | a warning for every statement | unit test |
| A12 | the last statement warns too | unit test |
| A13 | statements read in a lowered block are any | gate |

## The stops for M5

Every `JULC0084` this slice adds that a compilation can reach. `JavacStopsTest` pins each with the default's answer.

| Construct | Shape | The default |
|---|---|---|
| `an instanceof of a variant of two sealed interfaces` | `record R() implements P1, P2`, tested on either | Accepts it, with the last registered sum's tag. M5 needs a decision: ADR-067 M3's `JULC0079` is the likely answer |
| `an instanceof of a variant that is not a plain record` | A class or a `@NewType` as a variant | Accepts it |

**The operand of a type test.** `an instanceof of an operand outside the variant's sealed interface` is the gate's `JULC0100` on this frontend. A cast or a conditional of two variants reaches it (row above).
**The other stops this slice reaches.** `boolean J = (true || J);` with a static `J`: Java lets a local's initializer read the local in an operand that never runs. It stops at `IDENTIFIER J`, since the binder does not exist yet, and the reference reads the static, also unevaluated. No answer can differ.
`an if around a loop` stands behind the loops' own stops, until M4.

**Constructs a later slice ports.** Prefix and postfix `++` and `--`, `assert`, a local class or record, `try`, `throw`, `synchronized`, `for`, `do`, a `switch` statement and `null`. The reference has a rule
for each, so the row is `reference-rejects-not-ported`, and each takes its code with the subset's port.

## What this does not establish

- **I2a's inventory.** Still not run on this frontend. M3c's rules are the reference's by message, code and position in `JavacStatementsTest`, one rejection each.
  A source with two rejections reports the first the scan or the lowering meets (F946). Where the reference collects one and throws a later one, it reports the thrown one, which
  `Exits.allPathsReturn`'s label is the one case of this slice that holds that in code.
- **Ledger sums on the VM.** The harness has no mapping for them, so `Credential` and `OutputDatum` rest on byte identity with the reference.
- **The semantic fixes as `.diffcase`s.** They run on the JVM in JUnit, since a saved case joins the corpus and moves B2's tables.
- **Library bodies.** Still no program that reads a library unit compiles.
- **Images.** The lowering has still not run in a native or Web image.
- **DevKit.** Not run. Nothing here changes a script on the default frontend.

## What the integrator needs

- Freeze the I1 tables, and add [`068/m3c-accepted-differences.tsv`](068/m3c-accepted-differences.tsv)'s 30 rows to `lowering-accepted-differences.tsv`.
- **Decisions in code, to check:** the `var` rule (`Statements.varType`, `Calls.dispatchType`); that no per-method name table was added; that a bare block stays a rejection outside loops; that the two new
  stops of `instanceof` are stops and not lowerings; `dispatchType` reading a variable's binder rather than javac's type.
- **ADR text:** M3's row can mark locals, `if`/`else`, `return` and `instanceof` ported. The plan's "bare blocks splice" is about loop bodies. D15's "`JULC0100` also rejects ... an `instanceof` whose operand's static type is not the variant" has a stop on `JAVAC`. `JULC0041` on `==`
  between native values is behind `JULC0086` on `JAVAC`, which D6 should say.
- **Rejection catalogue:** no code is new, so `Adr068RejectionSites` does not move.
- `julc-difftest/src/test/resources/.../Adr068StatementsFuzzTestInputs` holds 7 inputs, replayed by `test`.

## What the next slice inherits

- **Records** meet `TypeTests` and `CallTargets.typedCall`: `new S.X(...)` lowers under a tag, which must be the one `TypeTests` reads, by identity. `SpelledProgramsOnJavacTest`'s `NEW_CLASS` rows move to `AGREES` as they do, and each meets the JVM there.
- **Casts** must leave `TypeTests`' operand rule standing: `(Object) r instanceof S.A a` must stop, or be rejected.
- **Loops** implement `Ifs.ifWithLoop`, `Exits.allPathsReturn`'s last-loop case, `break` in `SubsetScan.warnUnreachable`, the assignment that is not a rejection inside a loop, and block splicing in a loop body.
- **`switch`** adds `yield` to `Exits.holdsReturn`, and an arm's body to `Statements`.
- **A new statement kind** goes in `SubsetScan.STATEMENTS`, `Statements.sequence` and `single`, and `Exits`.
- New known issues are in [`068-followups.md`](068-followups.md).
