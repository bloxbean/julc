# ADR-068 M3e: `switch`, with ADR-067 M1's guards and the ported `JavaConstants`

M3e is the row of M3 that follows `instanceof` ([ADR-068](../068-javac-frontend-migration.md)). A `switch` expression over a sealed interface now compiles through
`frontend=JAVAC` to the reference's FLAT bytes at all four levels, guards included. Lambdas, loops, casts outside a guard and library bodies stay `JULC0084`, and nothing
falls back (I8). The JavaParser frontend did not change.

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md). Branch `wip/068-m3e`, from `edfa707b`, which is Group 1 at the B4 reference. Machine and toolchain as in
M0's evidence, with `25.0.2-librca`.

## What was delivered

| Piece | Where |
|---|---|
| The reference's two shapes as named methods: `unguarded`, the base lowering, and `guarded`, ADR-067 D2's chain. An arm's body, `yield` in it, the colon form | `lower/Switches` |
| A guard's term: Java's width for a constant, what the reference types a leaf by | `lower/Guards` |
| `instanceof` in a guard is a total dispatch, and the tag check of a one-constructor sum | `lower/TypeTests` |
| What arms hold, so `x.f()` on a pattern variable reads the decoded field | `lower/PatternScope`, `Calls.accessor`, `Frame` |
| A `yield` is an exit; `allPathsYield` | `lower/Exits`, `Statements.sequence` |
| The type a `switch` keeps with its term (F710) | `Typing.recordSwitch` |
| `javac` types a `switch` argument by its target, so what it can be is read off its arms | `SwitchResults`, `Typing.of`, `EqualityRelation.operands` |
| A tuple's destination through arms and `yield` (F722) | `Tuples.destination` |
| The labels' model, the colon form, applicability | `subset/SwitchRules` |
| A guard's rules, its Java type, its constants | `subset/GuardRules`, `GuardTypes`, `GuardConstants` |
| What an arm may update | `subset/ArmUpdates` |
| The scan takes `SWITCH_EXPRESSION`, `CASE`, `YIELD` and the labels where lowering reaches them, what only a guard holds in a guard, and `JULC0017` for `case null` | `subset/SubsetScan` |

### Rules worth stating

- **A variant is its element.** A branch is the variant's place in `facts.permitted`, which is the order the registry took (I11). `PatternMatchDesugarer` keys a branch by simple name, so
  `Switches` builds its branches, with the same shape. `permits X.A, Y.A` gives two branches (F807), and `Adr068SwitchHarnessTest.twoVariantsOfOneSimpleName` meets the JVM there.
- **Where a rule lives, and when it runs.** The rules are in `subset/`, and the lowering calls them where the reference calls `SwitchPatternChecker`: `requireLocalUpdates` before the
  selector is lowered, `check` after, and `beforeGuard` and `afterGuard` around a guard. Run from the scan they would change which of two errors comes first (I2a). This reads I12 as: lowering
  builds no term on what a rule rejects, not that every rule ran before lowering began.
- **What a guard reads is what the reference reads (I2).** ADR-067's guard type is established from declarations, and a leaf is asked of the lowering. `GuardTypes` keeps that shape: it reads the element javac
  bound where the reference reads a declaration, and asks `leaf` where the reference does, in the same places. So `when Builtins.equalsData(d, e)` lowers and `when !Builtins.equalsData(d, e)` is `JULC0072`,
  though javac knows both are boolean. `Guards.referenceType` is that leaf: a binder, a `new`, a helper's, a library method's and an accessor's result, what the registry declares on the
  receiver it saw, a list method that takes a function included. A component is read off a receiver the reference sees, so a variable of a `switch` in the guard, which has no binder when the guard's type is read, says nothing.
  An inferred lambda parameter names no declared type, so `when b` is `JULC0072` on every receiver. A simple name as the receiver of the list method gives the reference a first pass with the parameter untyped, and a throw from it stands: a leaf
  that roots in such a parameter, through an accessor or a `var`, is unknown (`CallTargets.untypedInFirstPass`). A switch's selector is not held to that pass, which is [the lambdas' one widening](068-m3f-lambdas.md#the-one-deliberate-widening).
  `PRIMITIVE_INTEGER_METHODS` stays a list of names for the same reason: a `long` from a method off the list is taken for a `BigInteger` there, so `Math.abs(n) > 0` is `JULC0072`.
  Two shapes stay wider than the reference, and are [listed](#where-a-guard-accepts-more-than-the-reference).
- **A name in a guard's binding rule goes by name.** `JULC0074` for reading a variable named like a guard's pattern variable reads the name, as the reference does, so a field of that name is held to it.
- **The placement of a continuation decides by source name.** The reference asks whether the arm's variable is free in what follows. `Scope` marks a binder beside a static of the name (`a'1`),
  so `Switches.readsName` compares source names, which keeps the join, and the bytes, where F9's program needs them.
- **Constants.** A leaf comes from `LiteralTree.getValue()` or from the initializer of what javac bound the name to, read by the reference's rules. `getConstantValue()` alone would make `Integer.MAX_VALUE`, a
  `(short) 5` initializer and a `char` a constant here, and each is a rejection there. See [the integrator's list](#for-the-integrator).
- **`-2147483648`.** javac folds the literal, so `JavaConstants`' minimum-value cases have no port.
- **A switch statement** is the reference's `Unsupported statement: SwitchStmt`, once what it holds has passed the scan. A `break` in it stops at `BREAK` first.
- **Field binder names.** `#__pfield-<binder>-<i>` as in the reference, and in a guarded switch one generated name per field, chosen against the names of the arms around it, so an inner guarded switch cannot shadow an outer arm's field. Binder names do not reach the bytes.

### What javac decides, and what stays (D11)

| Code | On `frontend=JAVAC` |
|---|---|
| `JULC0005` not exhaustive | javac: `not.exhaustive`. Retired from the rules |
| `JULC0007` selector | Stays, in `Switches`: javac accepts these selectors |
| `JULC0071` dominated, `default` before a pattern | javac: `pattern.dominated`. Retired |
| `JULC0072` guard | Stays for what javac accepts: reference casts, `(int)` of what is not constant, arithmetic that is not constant, `intValue`, `char` and the small types, constants JuLC cannot evaluate, a constant `false`, `==` on references, `instanceof` of an operand no declaration types, `String.equals`. A guard's own type is javac's |
| `JULC0073` not effectively final | javac: `cant.ref.non.effectively.final.var`. Retired |
| `JULC0074` a guard's binding | Stays |
| `JULC0075` colon groups | Stays. Mixed kinds and several patterns are javac's |
| `JULC0076` | Stays for the total pattern and a record pattern (ADR-067 M2a). A pattern javac finds inapplicable is javac's |
| `JULC0082` duplicate `default` | javac: `duplicate.default.label`. Retired |
| `JULC0083` | One shape stays: one name twice in a guard's call arguments, which javac accepts. The rest are javac's `already.defined` |

### Where the JVM and the reference disagree

`JavacStopsTest` holds each with the default frontend's answer.

| Shape | `JAVAC` |
|---|---|
| `case R r` of a variant of two sealed interfaces | Stops, as `instanceof` and `new` do. `JULC0079` comes with ADR-067's port |
| `case W v` of a variant that is a class or a `@NewType` | Stops, the same |
| `Tuple2 t = switch (s) { ... new Tuple2<>(a, b) ... }`, a raw destination | `JULC0012`, as without the `switch` (F722). The reference accepts it, and fails on-chain where the component is read back |
| `var v = switch (s) { case A a -> a.n(); case B b -> d(b.n()); }; JulcList.of(v)` (F720) | Stops at the expression's type. The reference's `JULC0012` |

## The I1 gate

`build/m3e/i1.sh`, through the machine lock, at `a64bda07`, over the same corpora as Group 1's freeze. It fails on moved rows, as it must until the integrator freezes. `lowering-i1-shrunk.tsv` is empty.

| Class | `edfa707b` | M3e |
|---|---:|---:|
| `i1a-identical` | 1,527 | 2,461 |
| `not-ported` | 8,175 | 7,222 |
| `reference-rejects-not-ported` | 443 | 352 |
| `javac-rejects` | 5,359 | 5,359 |
| `both-reject-javac-first` | 1,288 | 1,288 |
| `both-reject` | 97 | 187 |
| `ledger-source` | 38 | 38 |
| `unreplayable` | 17 | 17 |
| `DIFFERENT-javac-accepts` | 27 | 27 |
| `DIFFERENT-javac-rejects` | 68 | 84 |
| `DIFFERENT-rejection` | 16 | 17 |
| `DIFFERENT-bytes` | 12 | 12 |
| `DIFFERENT-provenance` | 7 | 7 |
| `DIFFERENT-diagnostics` | 2 | 5 |
| Total | 17,076 | 17,076 |

### Every class of moved row

1,294 rows moved, and each of the 17,076 is still present.

| Rows | From | To | Why |
|---:|---|---|---|
| 934 | `not-ported` | `i1a-identical` | Compiles to the reference's bytes at every level |
| 103 | `not-ported` | `not-ported` | Stops at a later construct |
| 57 | `not-ported` | `not-ported` | Stops at a later construct, and covers more methods |
| 33 | `not-ported` | `not-ported` | The same construct, and covers more methods |
| 89 | `reference-rejects-not-ported` | `both-reject` | Reaches the reference's rejection: code, message and position |
| 56 | `reference-rejects-not-ported` | `reference-rejects-not-ported` | Stops at a later construct |
| 16 | `not-ported` | `DIFFERENT-javac-rejects` | Below |
| 3 | `not-ported` | `DIFFERENT-diagnostics` | Below |
| 1 | `reference-rejects-not-ported` | `both-reject` | A switch statement: `Unsupported statement: SwitchStmt` |
| 1 | `reference-rejects-not-ported` | `DIFFERENT-rejection` | Below |
| 1 | `DIFFERENT-javac-rejects` | `DIFFERENT-javac-rejects` | `d7b92820`, `JULC0101` on an accessor: covers one more method. The row's class and reason stand |

`DIFFERENT-javac-accepts` and `DIFFERENT-bytes` did not move: on the corpus nothing widened, and no script differs that did not differ. Outside it, see [what a guard accepts](#where-a-guard-accepts-more-than-the-reference).

### Coverage

| | Covered | Of | | `edfa707b` |
|---|---:|---:|---|---:|
| Programs the reference accepts | 2,461 | 15,150 | 16.2% | 1,527 |
| Their distinct scripts | 959 | | | 700 |
| Programs of more than one method | 1,632 | | | 954 |
| Methods the reference lowers | 10,245 | 172,429 | 5.9% | 9,115 |
| Distinct covered methods | 2,261 | | | 1,889 |

### The worklist

What stops each `not-ported` or `reference-rejects-not-ported` row first, where it moved.

| First construct | Rows | `edfa707b` |
|---|---:|---:|
| `ENHANCED_FOR_LOOP` | 3,592 | 3,487 |
| `WHILE_LOOP` | 1,999 | 1,944 |
| `a library unit` | 1,257 | 1,225 |
| `LAMBDA_EXPRESSION` | 385 | 381 |
| `TYPE_CAST` | 225 | 213 |
| `reference equality` | 38 | 37 |
| `a library method` | 17 | 11 |
| `a contract schema` | 15 | 13 |
| `an expression of type java.lang.Object` | 8 | 6 |
| `(rejected)` | 7 | 6 |
| `fromPlutusData on a ledger type` | 6 | 5 |
| `a new of a variant of two sealed interfaces` | 3 | 1 |
| `an expression of type java.lang.Record` | 2 | 1 |
| `BREAK` | 1 | 0 |
| `SWITCH_EXPRESSION` | 0 | 1,259 |
| `SWITCH` | 0 | 2 |

### The differences

New against `edfa707b`, in [`068/m3e-accepted-differences.tsv`](068/m3e-accepted-differences.tsv), each row in the format of `lowering-accepted-differences.tsv`. None is a difference of bytes.

| Rows | Class | D13 class | What |
|---:|---|---|---|
| 8 | `DIFFERENT-javac-rejects` | Policy rejection (D14) | `JULC0088`: M0's recordings of `SwitchGuardParamTest`, with `@Param static final` and a constant. The tests moved to accepted spellings at M1. `switch` stopped these before |
| 6 | `DIFFERENT-javac-rejects` | Policy rejection (D6) | `JULC0087`: `.equals` on `byte[]` in the external examples, which M0's scan lists |
| 1 | `DIFFERENT-javac-rejects` | Policy rejection (M0's clarification of D6) | `33ee1cb2`, `agree-equals-unknown-switch`: `.equals` at static type `java.lang.Record`. M0 names this row |
| 1 | `DIFFERENT-javac-rejects` | Policy rejection (D6) | `27b87de9`: `result == result` of two `PlutusData` |
| 1 | `DIFFERENT-rejection` | Diagnostic change (D14) | `2bdf6c70`: `JULC0088` goes before the reference's `JULC0005`, on the `final` recording |
| 3 | `DIFFERENT-diagnostics` | Diagnostic change (M3b's family) | `5719befc`, `6fbfe645`, `78017745`: `JULC0102` on `"a" + "b"` in a guard. On the JavaParser frontend it comes from the gate, which the reference's table takes off |

### Rows that do not move, and should be named

- **`reject-guard-qualifier-is-a-local`, `6aef9d89`, `b4c4d2ae`,** `Probe.ON` on a local record: the reference collects `JULC0055` and throws `JULC0072`. `Guards` keeps that order, so they are `both-reject`.
- **`e62d161f`,** a `switch` with a guard of its own inside a guard: its label's pattern variable is no binding of the outer guard. The first I1 run showed it as `JULC0074`, a defect of this slice, fixed in `GuardRules`.

## Mutants

One runner in a throwaway worktree, each mutant applied, tested in two stages, and reverted. The no-op control ran first on each pass.

| Pass | Commit | Control | Mutants | Killed | Survived |
|---|---|---|---:|---:|---:|
| First | before `052fe6f4` | 0 failures of 944 and 110 | 96 | 72 | 23, and 1 did not compile |
| Rerun of the survivors, with new mutants | `a64bda07` | 0 failures of 975 and 111 | 25 | 19 | 6 |
| The rules that follow the Group 2 review, first run | `ae7c03c9` | 0 failures of 809 and 99 | 33 | 28 | 5 |
| Their survivors, with rows added and four more mutants | `7871a460` | 0 failures of 815 and 102 | 9 | 4 | 5 |

Five of the 6 survivors sit behind something that stops first. They were probed through `frontend=JAVAC` against the reference, and no program reached the mutated line. The sixth has its row now:

| Mutant | Why no program reaches it |
|---|---|
| `constant false passes` | javac rejects a guard that is a constant `false` first, with `(1L << 1L) > 5` and `(1 << 1L) > 5` too. The check stays so such a guard still stops if a constant ever reaches it |
| `operand of any family` | A record is final, so javac rejects the unrelated operand. A non-sealed variant is `JULC0100` at the gate, and a variant of two interfaces is `JULC0084` |
| `a variant read against its first parent` | Reached: `case S.A a when x instanceof V0`, with `x` of the second parent, passes this line and stops at `JULC0084`, where the mutant gives `JULC0072`. `JavacSwitchesTest.anOperandOfEitherParentOfAVariant` kills it |
| `unary is numeric` | `byte`, `short`, `char` and the boxed types are rejected before the guard. `-` and `~` on anything but a constant are `JULC0072` first. `+` on an operand that is no constant, as in `+Math.abs(n) > 0`, stops at `UNARY_PLUS`, `JULC0084`, before the guard's type is read, where the reference gives `JULC0072`. So no accepted program depends on the line |
| `@Param a constant` | `@Param static final` with a constant is `JULC0088` first (D14), and any other initializer is not constant either way |
| `a class file's constant` | A class-file field has no declaration, so the check is reached only from a second source unit, and a library unit stops. **Group 3 adds the row when library units lower** |

The 5 survivors of the last pass, each tried on both frontends:

| Mutant | Why no program reaches it |
|---|---|
| `final alone is constant` | A `final` field that is not `static` is read only from an instance method, and a `switch` there stops at `SWITCH_EXPRESSION`. From a static method javac rejects the read |
| `a class file's field is a constant` | As `a class file's constant` above |
| `a null type is not inferred` | javac gives an implicit parameter and a `var` a generated type at attribution, and lowering reads attributed trees. The check matches the same check in `GuardTypes` and `GuardConstants` |
| `a stub's variable has a declaration` | `untypedInFirstPass` reaches a stub's variable only through `var w = ONE` under a static import, which is `Undefined variable: ONE` on both frontends first |
| `a lambda has a receiver` | A lambda at an unqualified call stops first: `Unknown type: Predicate` at the helper's parameter on both frontends |

## Where a guard accepts more than the reference

Each program here is outside the corpus, and `Adr068SwitchHarnessTest` holds a row of each to the JVM at all four levels.

| Shape | Reference | `JAVAC` |
|---|---|---|
| A read of a local or parameter in a guard under a `new`'s argument: `new X(switch (s) { case S.A a when t instanceof S.A -> ...` with `t` a parameter, `when n > 0` with a `long n`, `(z + 1) < a.v().signum()` with a `final int z` | `JULC0072`: `resolveLocal` stops at a `new` | Reads what javac bound, with the JVM's answer |
| An unparenthesized conditional, `when c ? d : true` | JavaParser does not parse it | Compiles, with the JVM's answer |

The rule the reference applies at a `new` is that it treats every `new` as an anonymous class body. Three shapes under a `new` stay the other way:

- **A guard that is constant by a local through a `new`** (`final boolean on = true; new X(switch (s) { case S.A a when on -> ...`), which both accept, with other bytes: the reference reads the guard at run time, and it is a constant on `JAVAC`. `JAVAC` stops at `JULC0084`, and where the reference rejects such a guard (`final long z = 5; ... when z > 0`) the rejection is that `JULC0084`. A guard that holds the local and is read at run time on both, `on && c`, compiles to one script.
- **A local that shadows a constant field**, `boolean ON = c; new X(switch (s) { case S.A a when ON -> ...` beside `static final boolean ON = true`. The reference reads the field, and `JAVAC` the local, as Java does, so `c` false gives the reference another value. `aLocalThatShadowsAConstantFieldUnderANew` pins it, and the follow-ups have the row.
- **Two `Boolean` parameters compared**, `when p == q` under a `new`: the reference types each by its binder and accepts, and `JAVAC` keeps `JULC0072`, as everywhere else (D6).

## What this does not establish

- **Ledger sums on the VM.** The harness has no mapping for them, so `Credential` and `OutputDatum` as selectors rest on byte identity with the reference.
- **Library bodies.** A switch in a library unit stops with the unit. The rules ran on a library's types only through what a program of the root reads.
- **I2a's inventory.** Still not run on this frontend as an inventory. `JavacSwitchesTest` holds each rule to the reference's code, message and position, and `Adr068SwitchCorpusOnJavacTest` holds 1,119 generated
  programs to it, one first error each. A source with two errors reports what this frontend reaches first (F946).
- **Message text.** A rule quotes the source as written, with white space joined, where the reference prints its own tree. A guard written with other spacing, or a literal it would print otherwise, gives another message. No row of the corpus moved for it. M3a's rule on fragments.
- **A malformed `Data` value** is checked on one VM and one level, in `JavacSwitchesTest`, and otherwise rests on byte identity with the reference, whose outcomes `SwitchMalformedDataTest` holds on two VMs at every level.
- **Images and DevKit** were not run. The code holds no thread, reflection, file access or `ServiceLoader`.

## For the integrator

- **Freeze.** `adr/evidence/068/lowering-*.tsv`, and add [`068/m3e-accepted-differences.tsv`](068/m3e-accepted-differences.tsv)'s rows to `lowering-accepted-differences.tsv`. No new `.diffcase`, so no corpus table grows. Nothing shrank.
- **ADR text, I9.** It says a guard constant's leaves come from `LiteralTree.getValue()` and `getConstantValue()`. This port takes literals so, and reads a constant variable's **initializer**, by the reference's rules, and a constant
  of a class file is no constant. `getConstantValue()` would widen the subset: `Integer.MAX_VALUE` in a guard, which is `Undefined variable` on the reference, and `static final int Y = (short) 5`, `static final int X = 'c'`, which are `JULC0072`. `GuardConstantsAgainstJavaConstantsTest` meets `getConstantValue()` on every value. The sentence needs that wording, or a decision to widen.
- **ADR text, D11.** The table under [what javac decides](#what-javac-decides-and-what-stays-d11) is M3's list of retired codes. ADR-067's revision, its `Javac21ParityTest` and its outcome matrix stay to do. `SwitchParityCorpusTest` is unchanged: `Adr068SwitchCorpusOnJavacTest` reads its corpus on both frontends, and needs no javac 21.
- **ADR text, D6.** "Inside `switch` guards, ADR-067's evaluator decides": `==` in a guard is `JULC0072`'s, and in an arm of a `switch` in a guard it is D6's, as ordinary code. `JavacSwitchesTest.equalityInAndAroundAGuard` pins each.
- **ADR text, I12.** Lowering calls rules of `subset/` where the reference calls its checker. I2a needs that order. `SwitchRules` says so.
- **Follow-ups to close:** F710, F720, F722 for arms, F807 for `switch`, "A `switch` as a compared operand", and the row of `ADR-067 revision` to narrow. An assignment's target in `Tuples.destination` stays with loops.
- **Follow-ups the brief names and I could not find.** "Rows of the switch slice" is no heading or row at `edfa707b`. F1024 and F1041 are about lambdas, so they stay with that slice. #297 reaches a guard through `Calls.encodedTarget`, tested with a list of integers.
- **Follow-ups to add.** A record's field read in a guard, `f.on`, is `JULC0072` here because the reference collects `JULC0055` and goes on: `Guards` holds that one order.
- **The parallel slice.** `Frame` is now `Frame(scope, exit, patterns, inGuard)`, built by `Frame.body` and `initializer`. A lambda's frame keeps both, as the reference keeps `patternScopes` and `guardDepth` in one. `Lowering`, `Terms` and `SubsetScan`'s `WHERE_LOWERED` are the likely merge points.
- **Shared code.** None of it changed: `TermTypes`, `PirHelpers`, `TypeMethodRegistry` and the JavaParser frontend are as they were. `JavacFacts` gained `declaration`, `unitOfTree`, `encloses` and `text`. `EqualityRelation.operands` reads a `switch`'s arms; only `EqualityRules` calls it, on `frontend=JAVAC`, so no fact reaches the other frontend (I8).
