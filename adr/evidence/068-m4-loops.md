# ADR-068 M4: for-each, `while`, `break` and accumulators

M4 is the loops' milestone of [ADR-068](../068-javac-frontend-migration.md), taken before the libraries and Elci at the maintainer's request, so M3 is not fully closed. For-each, `while`, `break`,
accumulators and the join of an `if` around a loop now compile through `frontend=JAVAC`, ported from the reference, `pir/LoopBodyGenerator` and `pir/PirGenerator`. ADR-061 owns the design of loops, and this
slice redesigns nothing. A library unit still stops at `a library unit`, nothing falls back (I8), and the JavaParser frontend did not change.

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md). Branch `wip/068-m4`, from `e0d6eefc`. Machine and toolchain as in M0's evidence, with `25.0.2-librca`.

## What was delivered

| Piece | Where | Lines |
|---|---|---:|
| For-each and `while`: rules, iterable, accumulators, condition, what follows | `lower/Loops` | 344 |
| A body with accumulators, one walk for the reference's four generators | `lower/LoopBody` | 235 |
| The accumulators as one value: one binder or a tuple | `lower/Carried` | 114 |
| Which variables a loop or an `if` around a loop carries, as elements | `lower/Accumulators` | 78 |
| What a Data accumulator holds in a tuple | `lower/AccumulatorTypes` | 218 |
| The reads a pre-loop rebinding is ordered by | `lower/PreLoopReads` | 72 |
| What a loop must be, in the reference's order | `subset/LoopRules` | 320 |
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
| The binder's type | One type, what it holds, so its reads and every encoding of it agree with what was decoded (`Typing.holding`). The same goes for a `var` and a lambda's parameter. Where the reference sees a value of the same representation, as a list of Data for a list of integers, its reading stands, and with it its term |
| What a member read sees | `Binder.seen`, kept apart from the type: the element of the iterable as the reference sees it. It reads a name, or a call or chain of calls rooted at one, by the reference's walk of the tree, so an element of `(xs)`, a conditional, a `switch` or a call on any of those, as `(xs).tail()`, is Data, and a member read stays `JULC0055` or `JULC0007` there (I2d) |
| Which variables are accumulators | The elements a statement of the body assigns that are visible before the loop, in order of first assignment. No name decides it |
| What a tuple holds | `AccumulatorTypes`: what reaches a Data accumulator, in a `while` and a for-each. The next section says how |
| What the body reads an accumulator as | Its declared type. Only the tuple, the condition and what follows read the refined one, as in the reference |
| What a nested loop's result is read at | The types it travelled at, for each accumulator what follows reads. One nothing reads keeps its declared type, so its term stands as in the reference |

### What a tuple carries

A `PlutusData` variable can hold Data, a list or a pair list, and Java's type does not say which. `AccumulatorTypes` reads what reaches it, from what javac bound: its start and every assignment to it in the method, at any depth of loops, through parentheses, a conditional, a `switch`, another variable and the `return`s of a helper. A variable is a list only if its start and every value that reaches it are shown to be a list of one kind. Anything else is Data, and travels as Data. The reference's analysis reads direct assignments in the body of a `while` of the loop's own statement list, and takes those for a list whatever the start is.

| What reaches the variable | It travels as |
|---|---|
| `unListData`, `mkNilData`, `constrFields`, `mkCons` onto a list | A list |
| `unMapData`, `mkNilPairData`, a declared `MapData`, `mkCons` of a pair or onto a pair list | A pair list |
| `tailList` of a list or a pair list, or of the variable itself | The same |
| `tailList` of what nothing says, a declared `ListData` | A list of either kind: a list, unless its head is read as a pair or a pair list travels beside it. A cursor shown to be a list stays one beside a map cursor |
| Anything else: a parameter, a `@Param`, a read of a list or a record, a helper's Data, `iData` and the like | Data |
| A list and Data, or a list and pairs | Data |

So a list that arrives in a variable of Data fails on-chain. Where the start is the list, it fails on entry. `JavacLoopsTest` holds the rows that match the reference, and `Adr068LoopRepresentationsTest` the rest.

A variable declared at another type keeps it. Nothing else changed: the body reads the accumulator at its declared type, as before, and no corpus row moved.

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
| A label, wherever it is in the loop | `JULC0066` | `LoopRules` | First, after what `SubsetScan` reads of the labelled statement |
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

### Stops and differences to name

| Shape | JVM | Reference | `JAVAC` |
|---|---|---|---|
| `for (Object o : xs)`, `for (Number o : xs)`, `for (Comparable<BigInteger> x : b)`, a raw `JulcList` with `for (Object x : xs)` | A value | Agrees | `JULC0084`, `a for-each variable of type ...`. A supertype or a raw element has no type to decode by. No corpus row reaches it, so M5's count will not show it |
| `for (byte b : a)` over an array | A value | Fails on-chain | `JULC0084`, `a for-each over an array` |
| `static long K;` with `K = K + 1` in a loop with no other update | | `Undefined variable: K` | `Unsupported expression: AssignExpr`. Diagnostic change: both reject, and the reference has no position |
| A non-entry method whose loop first assigns a local `K` in a bare block, then the field `K` | | `JULC0056` at the local's assignment | `JULC0056` at the field's. Diagnostic change: the field is what the rule is about, and the reference finds it by spelling |
| `JULC0091` in a tuple | | At 0:0 | At 0:0, the reference's |

## Decisions to check

1. **A binder after a loop that hides a field has its own name.** It may be a local, a for-each's variable or a lambda's parameter. The reference's binder there is `K`, so its pre-loop rebinding writes one `let K = K` for what is that binder's read. Here the binder is `K'1`, as I13 asks. To write
   the same `let`, the port would have to know which blocks the reference renames. Two rows of the corpus show it, each smaller by that `let`, and semantic observations are equal. `JavacLoopsTest.aBinderThatHidesAFieldAfterALoop` pins each shape's saving exactly, at every level, with and without a source map. It saves 2 or 3 bytes, at the first level always, and above it only with a source map.
2. **An element the reference takes for Data decodes, and its binder says so.** `for (var x : JulcList.of(a, b))` and a for-each over `xs.map(...)` hold Data to the reference, and arithmetic on `x` fails on-chain where Java returns. Here the element decodes
   by javac's type, the binder has that type, and a tuple encodes it. `anElementTheReferenceTakesForData` and `Adr068LoopRepresentationsTest`'s 1,260 programs hold it to the JVM. A member read goes by `Binder.seen`, so the subset does not widen. An element nothing reads costs one `UnIData`: one row of the corpus.
3. **A library compiled as a root stops at a call of its own method,** `a call of a library unit's own method`. On a root of a library, `MathLib.floorDiv` was `Undefined variable: MathLib`, a rejection with no decision behind it, which
   ten rows reached once their `while` lowered. The reference finds that method in its pool, so this is the library slice's to answer.
4. **`if (c) ;`** was a rejection with a diagnostic where the reference has the located text. `Statements.single` now takes the reference's form, and a loop's body uses it too.
5. **`JULC0012` and `JULC0041` on a tuple of accumulators have the loop's position** (F724), where the reference has none, and `JULC0041` on a for-each's native element has the iterable's. `JULC0091` keeps the reference's. No row of the corpus reaches them.
6. **A list shown to be one is carried on `JAVAC`, and stays a failure on the reference where its analysis does not read it** (D4's cap, "Closed by M5"). Its analysis lives on the JavaParser side, so that frontend is not changed. `diverge-068-cursor-*` pin both answers.
7. **What is not shown to be a list is Data, on `JAVAC`.** So the two frontends part in both directions. `diverge-068-data-start-in-a-while` runs here and fails on the reference, which takes the variable for a list. A list that arrives in a parameter by a direct `tailList`, and a list start with Data assigned on a path not taken, run on the reference and fail here: `anAssignmentAloneShowsNothing` and `aMixIsData` pin them. No row of the corpus holds either shape.
8. **A pair list stays a map on the JVM, as on-chain** (the maintainer's decision). `Builtins.tailList` of a `MapData` and `mkCons` of a pair onto one leave a `MapData`, and `mkNilPairData` is an empty one, so every encoding of a pair list agrees with the JVM at all four levels, on zero, one and many iterations and an empty remainder. `aWalkedPairListIsAMap`, `aBuiltPairListIsAMap` and the jqwik family `aPairListInEveryEncoding` hold it. The reference's `while` forms now agree as well. Its for-each stays a failure, a Semantic fix row, `diverge-068-pair-list-in-a-for-each`. A single pair is a `Constr` on the JVM and a native pair on-chain, and encoding one fails on both frontends: 354 runs of 396 over every encoding, none a value, and the rest read the pair or stop before it. It stays so, pinned by `aSinglePairFailsOnBoth`.
9. **What a variable holds wins over what the reference sees wherever the second is not decoded.** `var y = xs.find(p)` is an `Optional` to the reference, and holds the element. `diverge-068-found-element-in-a-tuple` pins it.

## Proposed text for the integrator

### ADR r14

> **r14** (2026-11-03, M4, on the maintainer's decision). A pair list stays a map on the JVM, as on-chain. `Builtins.tailList` of a `MapData` is a `MapData` of the rest, `mkCons` of a pair onto a `MapData` is a `MapData` with the pair in front, and `mkNilPairData` is an empty `MapData`. `tailList` and `mkCons` of a `PlutusData` return `PlutusData`, their overloads on a `ListData` return a `ListData`, and `mkNilPairData` returns `PlutusData.MapData`. What a `PlutusData` accumulator carries (D4's cap): a variable is a list only if its start and every value that reaches it are shown to be a list of one kind, and anything else is Data. A variable's one type is what it holds wherever the reference's view of it is not decoded. An iterable's element is read by the reference's walk: a name, or a call or chain of calls rooted at one. A label's statement is read before the label's rejection.

### Public API note

> `Builtins.tailList(PlutusData)` and `Builtins.mkCons(PlutusData, PlutusData)` return `PlutusData`, where they returned `PlutusData.ListData`, and `Builtins.mkNilPairData` returns `PlutusData.MapData`. New overloads take a `PlutusData.ListData` and return one, so a list Java has as a list stays typed as one. A source that takes a `ListData` from a call on a `PlutusData`, or from `mkNilPairData`, no longer compiles: write `PlutusData`. Off-chain, a map walked or built by these methods stays a `MapData`, and `mkCons` onto a map takes a pair. Scripts are unchanged.

## The I1 gate

`build/m4/i1.sh` against the tables frozen at `e0d6eefc`. The run fails by design at `assertListed` until the integrator adds the rows below, and moved rows are checked at the freeze. Nothing shrank: `lowering-i1-shrunk.tsv` is empty. No row has a loop as its first construct.

The tables below are at `68a4f0b7`. The merges of `wip/068-m3g2` moved no row of this slice's. The fixes after them moved one, `f759f988`, a label's rejection: the other 17,075 cases have the same row before and after them, bytes included, so the corpus holds no program that reaches what they changed. That includes the change to `Builtins`, and no reference row moved, so the reference table needs only the new cases. No row stops at a rule of this round.

The 14 new `.diffcase`s are cases 17,077 to 17,090. They have no row in `lowering-reference.tsv`, so I1 reports them as `REFERENCE-DRIFT`, `no committed row`, and the freeze must add them. With that table extended in a throwaway worktree, and nothing frozen here, all 14 are `DIFFERENT-bytes`, every other row is as below, and nothing shrank.

| Class | `e0d6eefc` | M4 |
|---|---:|---:|
| `i1a-identical` | 2,513 | 5,950 |
| `not-ported` | 7,132 | 3,647 |
| `reference-rejects-not-ported` | 345 | 100 |
| `javac-rejects` | 5,359 | 5,359 |
| `both-reject-javac-first` | 1,288 | 1,288 |
| `both-reject` | 193 | 436 |
| `ledger-source` | 38 | 38 |
| `unreplayable` | 17 | 17 |
| `DIFFERENT-javac-accepts` | 27 | 27 |
| `DIFFERENT-javac-rejects` | 86 | 131 |
| `DIFFERENT-rejection` | 18 | 20 |
| `DIFFERENT-bytes` | 15 | 18 |
| `DIFFERENT-pir` | 33 | 33 |
| `DIFFERENT-provenance` | 7 | 7 |
| `DIFFERENT-diagnostics` | 5 | 5 |
| `REFERENCE-DRIFT` | 0 | 14 |
| Total | 17,076 | 17,090 |

### Every class of moved row

5,636 rows moved, and each of the 17,076 is still present.

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
| 242 | `reference-rejects-not-ported` | `both-reject` | The reference's rejection, now reached: 163 for-each, 76 `while`, and one each of `break`, `for`, `do` |
| 27 | `reference-rejects-not-ported` | `reference-rejects-not-ported` | Stops at a later construct |
| 1 | `reference-rejects-not-ported`, `a library unit` | `both-reject` | `f759f988`. A label on a `while`, `JULC0066` with `'break done'` at the `break`, as the reference has it, from `SubsetScan` |
| 45 | `not-ported` | `DIFFERENT-javac-rejects` | Below |
| 2 | `DIFFERENT-javac-rejects` | `DIFFERENT-javac-rejects` | More methods covered |
| 3 | `not-ported` | `DIFFERENT-bytes` | Below |
| 2 | `reference-rejects-not-ported` | `DIFFERENT-rejection` | Below |

`DIFFERENT-javac-accepts` and `DIFFERENT-pir` did not move, so no row of the corpus widened, and no loop method lowered to another term. The 33 `DIFFERENT-pir` rows are `HelperCallers`', from lambdas.

### New `DIFFERENT-*` rows

[`068/m4-accepted-differences.tsv`](068/m4-accepted-differences.tsv) holds 64, one row each, in the table's format: the corpus's 50 and the new cases' 14. It was checked against the run with the extended reference table: stale entries 0, unlisted 0, no class differs. The reference table goes first, or the 14 are entries the tool cannot find.

| Rows | Class | D13 | Rule |
|---:|---|---|---|
| 39 | `DIFFERENT-javac-rejects` | Policy rejection (D6) | `==` between `BigInteger`s, in a program a loop stopped before |
| 6 | `DIFFERENT-javac-rejects` | Policy rejection (D6) | `.equals` on `byte[]`, which M0's scan lists |
| 2 | `DIFFERENT-bytes` | Cost change | Decision 1. 136 to 133 bytes and 231 to 228 where the identity `let` survives optimization. Smaller |
| 1 | `DIFFERENT-bytes` | Cost change, **grows**, accepted | Decision 2. `for (var i : JulcList.of(x))` with `i` unread. The table below |
| 2 | `DIFFERENT-rejection` | Diagnostic change | Two errors in one compilation, in one method and in two (D13): the reference collects one and throws the other, and this frontend stops at the first. With the first removed, both say the second alike |
| 12 | `DIFFERENT-bytes` | Semantic fix | New cases. Each `diverge-068-` the reference fails on-chain or answers otherwise, and `JAVAC` agrees with the JVM at all four levels |
| 2 | `DIFFERENT-bytes` | Cost change, **grows**, accepted | New cases. `agree-068-inline-element-in-a-tuple` and `-mapped-`. The second table below |

Row `64e378e9`, the one budget that grows in the corpus, on inputs 3 and 4, one iteration, value and traces agreeing at all four levels. The type's fix did not change it:

| Levels | Reference | `JAVAC` | Delta |
|---|---|---|---|
| NONE, BASELINE | cpu 4,880,786, memory 19,790, 153 bytes | cpu 4,933,530, memory 20,022, 154 bytes | +52,744 (+1.1%), +232 (+1.2%), +1 |
| PV11 levels | cpu 4,259,875, memory 17,824, 145 bytes | cpu 4,360,619, memory 18,356, 150 bytes | +100,744 (+2.4%), +532 (+3.0%), +5 |

The cost is per iteration, and only where the variable is unread. The extra decode is the rule of one representation per binder, not a need: the reference agrees with the JVM without it. All three rows are accepted on that ground, and the follow-up "A decode no read needs" stays open and can remove this one.

The two new rows that grow, each on its case's input, value and traces agreeing at all four levels. A `var` copied from an element holds the type it has, so a tuple of accumulators encodes what was decoded, where the reference carries the element as Data, and agrees with the JVM. The extra decode and encode are the rule that makes `diverge-068-assigned-element-in-a-tuple` run, not a need of these programs, and no row of the corpus reaches them:

| Case | Levels | Reference | `JAVAC` | Delta |
|---|---|---|---|---|
| `inline` | NONE | cpu 17,507,843, memory 71,347, 218 bytes | cpu 18,530,053, memory 75,987, 229 bytes | +1,022,210 (+5.8%), +4,640 (+6.5%), +11 |
| `inline` | BASELINE | cpu 17,075,843, memory 68,647, 208 bytes | cpu 18,098,053, memory 73,287, 219 bytes | +1,022,210 (+6.0%), +4,640 (+6.8%), +11 |
| `inline` | PV11 levels | cpu 14,989,776, memory 61,810, 199 bytes | cpu 16,011,986, memory 66,450, 210 bytes | +1,022,210 (+6.8%), +4,640 (+7.5%), +11 |
| `mapped` | NONE | cpu 33,438,827, memory 142,383, 439 bytes | cpu 34,355,549, memory 146,559, 451 bytes | +916,722 (+2.7%), +4,176 (+2.9%), +12 |
| `mapped` | BASELINE | cpu 31,854,827, memory 132,483, 409 bytes | cpu 32,771,549, memory 136,659, 421 bytes | +916,722 (+2.9%), +4,176 (+3.2%), +12 |
| `mapped` | PV11 levels | cpu 25,308,626, memory 110,172, 379 bytes | cpu 26,513,348, memory 116,148, 395 bytes | +1,204,722 (+4.8%), +5,976 (+5.4%), +16 |

### Coverage

| | Covered | Of | | `e0d6eefc` |
|---|---:|---:|---|---:|
| Programs the reference accepts | 5,950 | 15,117 | 39.4% | 2,513 |
| Their distinct scripts | 1,744 | | | 978 |
| Programs of more than one method | 2,860 | | | 1,634 |
| Methods | 17,464 | 172,429 | 10.1% | 11,708 |
| Distinct methods | 3,554 | | | 2,595 |

What each program first stops at, with `e0d6eefc`'s in brackets: `a library unit` 3,334 (1,566), `TYPE_CAST` 315 (233), `(rejected)` 90 (50), `the exports of library sources` 35 (35), `a library method` 23 (17), `an equals
between unrelated types` 21 (14), `a contract schema` 15 (15), `a call of a library unit's own method` 10 (0). `ENHANCED_FOR_LOOP` was 3,594 and `WHILE_LOOP` 2,002, and neither is on the list now.

### Rows a library unit leaves stopped

3,334 rows stop at `a library unit` as their first construct, and 1,769 of them moved there from a loop in this slice. By the ADR's M4 row as of r13, a loop that calls a library gets its I1 row when libraries are lowered.

### What "every loop method has a row" covers

Every case has a row, and every method is attempted alone, but a row is not always a comparison. Over methods whose reference term holds a `letrec`, by an independent review's tally at `7c52bbc7`, before the merge:

| Methods | Outcome |
|---:|---|
| 7,843 (798 distinct) | Compared with the reference's term |
| 536 (75 distinct) | Stop at `TYPE_CAST` before a comparison |
| 74 | Stop at an `equals` on an array |
| 39 | Stopped at reference equality. The merge makes that rule a rejection, `JULC0086` |
| 4 | Rejected |
| 1 | Stops at an expression of type `Object` |
| 60,993 | No attempt: library methods, and sources javac rejects first |

So the 536 and their like reach no comparison of their loops yet, external validators among them. `CfBetValidator.findContinuingOutput` is one. They wait for the construct that stops them, not for loops.

### D10's set

This slice ran its own suites and the two modules. `Javac21ParityTest`, ADR-066's rediscovery and the G1 campaign on `JAVAC` are the integrator's, in `gates.sh`. In I1, 122 G1 programs are `i1a-identical` and one is `not-ported`, and G0's 376 are identical.

## Tests

| Run | What | Result |
|---|---|---|
| `JavacLoopsTest` | Rows against the reference, bytes at all four levels with and without a source map, and rejections by code, message and position | 265 |
| `Adr068LoopsHarnessTest` | Against the JVM, through each frontend at every level, on value, traces, failure, cost and size | 78 tests, 489 runs agreed |
| `Adr068LoopsPropertyTest` | `LoopPrograms`, a plain Java model, the JVM and both frontends, 300 tries, read back on-chain | No disagreement |
| `Adr068LoopsFuzzTest` | The same under Jazzer, 12 seeds, replayed by `test` | 793 runs in 61 s, no crash |
| `Adr068LoopCasesOnJavacTest` | 52 saved cases with a loop on their expectation, and G0's 376 programs on 4 inputs | No disagreement |
| `LoopBreakContinuationTest` | The loop-break continuation suite, now on both frontends, on two VMs | 3 tests |
| `Adr068LoopRepresentationsTest` | 10 element types, 9 iterables, a loop variable written two ways, 7 shapes: 1,260 programs, each the JVM's at all four levels. Then each form a list reaches a Data accumulator by, what stays Data, and each encoding of a pair list on no, one and many iterations with an empty remainder and `equals` both ways, on the reference and `JAVAC` | No disagreement on `JAVAC` beyond the rows decision 7 names |
| `Adr068LoopRepresentationsPropertyTest` | Both families composed further, against the JVM: 150 elements under chains of variables and loops, 200 cursors by any form in any loop beside any accumulator, 200 starts from Data a list may reach on a path not taken, and 300 random maps at random iteration counts through every encoding | No disagreement |
| `BuiltinsPairListTest` | `julc-stdlib`: what `tailList`, `mkCons`, `mkNilPairData` and `mapData` leave on the JVM, a list and a map, and `ValuesLib` on a map | 10 tests |

Each shape the property needs was reached: `break` 118 tries, `for` 223, `while` 177, an `else` 86, a local that shadows a field 108, traced 187, a failure 42, failed 31. The harness holds:

- `if` with `break` under one and several accumulators, in each of the shapes above, on lists that take and skip it.
- A `switch` in a body, with `yield` in an arm, and in an accumulator's update before a `break`.
- A loop in an arm, a guarded arm, and the then branch of an `instanceof`.
- A loop in a lambda's body, and a loop's variable captured by a lambda, at each iteration's value.
- Each representation in a tuple of accumulators: `String`, `boolean`, `byte[]`, Data, a list, a sum, a `Tuple2`, a builtin's list.
- A failure in a body, with what ran before it.
- A field updated by an entry method, shadowed, and read after.

I13's check ran over all of it, the suites and the corpus: `julc.verifyBinderNamespace` is on in the test tasks, and a loop's accumulator, variable and join are told to it by `Terms.loop`.

Of the 1,260, the reference agrees on 1,056. It fails the other 204 with one defect: a value of another representation than its binder's type says. 132 are `MkCons`, 72 an encoding of what it took for decoded. The 84 that also failed on `JAVAC` before this fix were that `MkCons`, and they are in the same class as the cursors, on "Closed by M5".

### T21, T23 and G0

| Set | On `frontend=JAVAC` |
|---|---|
| T21: `agree-t21-while-break-first`, `-if-update`, `-never-taken` | Agree at all four levels |
| T23: `agree-t23-for-each-split-if-else-break-taken`, `-never-taken`, `corpus-input-1`, `-3` | Agree at all four levels |
| Every saved case with a loop, 53, beside the 12 `diverge-068-`, which the reference answers alone and `Adr068SavedCasesOnJavacTest` holds | 52 on the case's expectation. `reject-g1-291-local-after-loop` stops at `an expression of type java.lang.Object`, `v.equals(switch ...)`, in `JavacStopsTest`, before its loop |
| G0's enumeration, 376 programs on 4 inputs at all four levels | No unexplained verdict |
| `LoopBreakContinuationTest`'s three tests | Pass on both frontends |

### The loop suites with the default set to `JAVAC`

The other loop suites compile by the default frontend, so they were run once in a throwaway worktree with `CompilerOptions`' default set to `JAVAC`. 164 tests, 87 pass, 77 fail, and no failure is a defect of the loops:

| Tests | Why they do not run yet |
|---:|---|
| 45 | The source reads a library, and stops at `a library unit` |
| 24 | The source compares `BigInteger`s with `==`, which is `JULC0086` (D6). Written with `.equals`, all 24 pass on `JAVAC` |
| 3 | `TYPE_CAST`: `(JulcList<PlutusData>) (Object) redeemer` |
| 3 | `an equals between unrelated types` |
| 1 | `LoopFieldUpdateTest.readByAnotherMethodIsRejected`: two errors, and this frontend reports `JULC0061` first (F946) |
| 1 | `LoopStatementDiagnosticsTest.continueInCodeTheCompilerNeverLowersIsAccepted`: the scan reads code nothing lowers |

`LoopConditionalLocalTest`, `PostLoopVariableTest` and `ReturnInLoopTest`'s while and pair-list tests pass in full.

### Whole modules

Under the machine lock, `--rerun`, from the XML, at `01471217`:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `julc-stdlib` | 568 | 0 | 0 | 0 |
| `julc-compiler` | 6,487 | 0 | 0 | 0 |
| `julc-difftest` | 2,061 | 0 | 0 | 0 |
| `julc-testkit` | 236 | 0 | 0 | 0 |
| `julc-vm` | 114 | 0 | 0 | 0 |
| `julc-vm-java` | 2,485 | 0 | 0 | 262 |
| `julc-vm-scalus` | 312 | 0 | 0 | 0 |
| `julc-examples` | 100 | 0 | 0 | 0 |
| `julc-gradle-plugin` | 50 | 0 | 0 | 0 |

The in-repo `julc-examples`. Its tests reach no DevKit. The sibling repository was not touched, and a grep of it finds no source that holds these results as `ListData`.

## Mutants

One runner per throwaway detached worktree, each mutant applied, tested in stages and reverted: the rows and lints first, then the harness, the suites of other slices, and the generator with the saved cases. Each line mutated is new in this slice.

| Pass | Commit | Control | Mutants | Killed | Survived |
|---|---|---|---:|---:|---:|
| First | `179083fd` | No-op: 0 failures of 986 and 128 | 128 | 67 | 61 |
| The survivors, with rows added and dead lines removed | `4a94e718` | Rows at that commit: 0 failures of 192 | 53 | 46 | 7 |
| Their survivors, with rows added | `e78c4d33` | Rows at that commit: 0 failures of 195 | 4 | 4 | 0 |
| The last row | `5b83b4fd` | Rows at that commit: 0 failures of 196 | 1 | 1 | 0 |
| Through I1, on the three types below | `e78c4d33` | The run above | 3 | 3 | 0 |

Twelve survivors of the first pass showed code no program reaches, and each was removed or narrowed, which is why they left the list:

| Line | Why no program reaches it |
|---|---|
| `-=` on a `String`, and an integer target's operand type | javac rejects both (D11) |
| The field rule at an assignment | A field a body updates is an accumulator of that loop, so `Loops.accumulators` has met the rule. Mutating that one now kills |
| `inLoop` in the walk of an `if`'s branches | An assignment in a branch outside a loop is rejected where it lowers, so the two walks are one |
| An alias in `pairLists` | `startsAsPairList` follows the chain of initializers |
| The accumulators' names among what a tuple's name avoids, twice | A generated name is never a source name (ADR-060) |
| A member select in `FieldUpdates` | `C.K` and `C.h()` are `Undefined variable: C` first |
| A `PairMatch`'s binders in `PreLoopReads` | They are generated, and no candidate is one |

`header not read` and `no declared map local` came back as new mutants on the new lines, and both are killed.

Three lines cannot show in bytes. A body, a condition and what follows a `while` of several accumulators read a Data accumulator at the reference's type, and that type is on the `Var` alone, so `DIFFERENT-bytes` stays 18 under each. I1b compares the `Var`'s type:

| Mutant | `DIFFERENT-pir` | `i1a-identical` |
|---|---:|---:|
| None | 33 | 5,950 |
| A body reads the type it travels at | 77 | 5,947 |
| A condition reads the declared type | 52 | 5,950 |
| What follows reads the declared type | 57 | 5,948 |

So every mutant of these lists is killed. That is a claim about the lists, and not that every loop line is tested: an independent review wrote twelve more, and four survived.

### The second round, on what a variable holds and what a tuple carries

Three runs at `52ec2855`, `c3e22523` and `a299169f`, each with four throwaway worktrees and the stages rows, harness and representations, other slices' suites, the generator and saved cases.

| Run | Control | Mutants | Killed | Left |
|---|---|---|---:|---:|
| First, 64 mutants of the new lines | 0 failures through three stages. The last stage failed 9 saved cases, which `Adr068LoopCasesOnJavacTest` read on the wrong expectation, so what reached it counts as survived | 64 | 43 | 21 |
| The 21, less what was removed, with rows, and the review's survivors | 0 failures in all four | 26 | 23 | 3 |
| The last 3, with rows | The runs above | 3 | 3 | 0 |

Removed for a reason: `held` of a `var` read `seen` first, and the check changed nothing, and a `mkCons` onto a pair list read its tail, and no program of the rows or the corpus needs it, as an initial pair list decides.

Every run of the second round tests both directions of a rule where a rule has two: the value that must carry and the value that must stay, the read that must be rejected and the one that must go on.

The review's four survivors:

| Mutant | Now |
|---|---|
| A `while` with no accumulator that ends a branch loses what follows | Killed by rows on both sides of the `if`, for a `while` and a for-each, and held to the JVM in the harness. The code was right |
| A cursor read as pairs though `mkNilData` reaches it | Killed by a row. The rule is `AccumulatorTypes.variable`'s |
| A written loop variable read as inferred | Killed by `for (Long x : ...)` in `JavacStopsTest`, whose `Object` and `Number` rows pin the stop too |
| `Scope.binders` taking the outermost binder of a name | Stands, for a reason: it changes a `Var`'s type in an identity `let`, and only after a second loop in a refined tuple's rest. No script differs, and no corpus row holds the shape. The innermost is the reference's |

### The third round, on what a variable shows and the map

Each of these reran the review's product at the merged head, then swept wider.

| Sweep | Runs | Result |
|---|---:|---|
| What a pair list encodes to: 22 reads, sizes and iterations, four kinds of value | 352 | Every run on `JAVAC` meets the JVM. The reference's `while` forms too. Its for-each, and `contains`, fail on the reference |
| A single pair through every encoding, from nine sources | 396 | 354 fail on both frontends, none is a value that differs. `return` of a native pair has no Data mapping for the harness, on both |
| Every receiver shape: statics with and without a class, nested parentheses, casts, a `switch`, a conditional, `new`, accessors, in the iterable, a `var` and a `head()` | 840, with the review's 1,216 and 160 | No widening. Casts stop at `TYPE_CAST` |
| Every registry method that gives a result, by six element types and seven carrying uses | 1,344, with the review's 644 | No disagreement that names `JAVAC` alone. 20 widened rows are the `var` of a `switch` of mixed arms, on the follow-ups |
| `ContextsLib`, `MapLib`, `ValuesLib` | The modules above, and `BuiltinsPairListTest` | `ValuesLib` on a map off-chain, and every library through its suites |

Two samples of "a construct not ported" were a `while`, in `FrontendOptionTest` and `JulcPluginFrontendTest`. A loop lowers now, so they are casts.

`Builtins` takes overloads beyond the brief. With only `PlutusData` as the return type, `ContextsLib.listIndex` no longer compiled as written. Changing its text moved 1,824 rows to `JULC0081`, because the recorded corpus holds the shipped text, and two recorded programs to `JULC0085`. `tailList` and `mkCons` of a `PlutusData.ListData` return one, so no shipped text moved and those rows came back.

### Mutants of the third round

Four throwaway worktrees at `68a4f0b7`, then `01471217`, with `julc-stdlib`'s `BuiltinsPairListTest` and `BuiltinsTest` as a first stage. A no-op control reached the last stage with no failure. 44 mutants of the new lines, both directions of each rule:

| Outcome | Mutants |
|---:|---|
| 30 | Killed in the first pass |
| 1 | Would not compile as written, and killed when corrected |
| 2 | Hung, and counted as killed: a `tailList` of a map that does not shorten it, and an `mkNilPairData` that is not empty, leave `ValuesLib`'s loop running. A single test method also fails each of them |
| 2 | Killed in a separate JVM under a hard timeout: `tailList` of a `ListData` read backwards, and `mkCons` of one that appends, the second after a row was added |
| 7 | Survived, then killed by rows: `whatShowsAList`, `aConstantMixesToo`, and three rows of `whatATupleOfAWhileHolds` |
| 2 | Survived, and their lines went: `tailList` of the variable itself needs no case of its own, and a `@Param`'s initializer can only be a literal (`JULC0095`) |

`theLibrariesOnAMap` now has a timeout, so a wrong map fails it rather than running on. The last three mutants ran only against `BuiltinsPairListTest`, each in one JVM, and each failed there.

## What this does not establish

- **Loops in a library's body.** A library unit stops, so `ValuesLib`'s and `ContextsLib`'s `while` loops are compared as methods alone, where the corpus compiles them as a root, and never run on this frontend.
- **Which programs the join's rule has never met.** The generator's nested loops reach two levels, and its accumulators three.
- **`AccumulatorTypes` beyond what it reads.** A list that reaches a Data accumulator through a helper's parameter, a lambda or a tuple's component says nothing, so the accumulator packs as Data and fails on-chain. On the reference that holds for each form but a direct assignment in a `while`, which it reads (decision 7). A helper's `return` is read, its arguments are not.
- **A local at a method's level after a loop that hides a field, by cost.** Both frontends agree on value and failure, and `Adr068LoopsPropertyTest` does not compare cost on that shape alone. Its size is pinned in `JavacLoopsTest`.
- **The 14 new `.diffcase`s in the committed tables.** `lowering-reference.tsv` has no row for them, so I1 lists them as `REFERENCE-DRIFT`, `no committed row`, until the freeze. Their classes come from a scratch table. See [the I1 gate](#the-i1-gate).
- **I2a's inventory** is still not run on this frontend as an inventory. `JavacLoopsTest` holds each rule to the reference.
- **Images and DevKit** were not run. The code holds no thread, reflection, file access or `ServiceLoader`.

## For the integrator

- **Freeze.** `adr/evidence/068/lowering-*.tsv`, the reference table first for the 14 new cases, and add [`068/m4-accepted-differences.tsv`](068/m4-accepted-differences.tsv)'s 64 rows to `lowering-accepted-differences.tsv`. Nothing shrank.
- **ADR text.**
  - M4's row: this slice. Its entry gate and its line on a loop that calls a library are r13's, and M3's library, entry-point and debug items stay open.
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
  - `Scope.Binder.seen` keeps the reference's reading for a member read. It goes when M5 ends that reading.
  - The native-list family: `JulcList<JulcG1> x = JulcList.empty();`, the same with `JulcG2`, `JulcMlResult`, `JulcValue`, `JulcMap<BigInteger, JulcG1>`, and `return x` of one, are `JULC0041` on the reference and accepted here, with the values right, because `Statements.local` takes the initializer's type from javac. `Statements.local` needs the reference's reading before M5, or the family needs a decision.
  - "A bare block in a statement list" stands: a bare block in a body with no accumulator is `Unsupported statement: BlockStmt` on both frontends, as at a method's level.
- **Shared code.** Nothing found. No file outside `frontend/javac` changed in `src/main`.
- **Files of Group 1 and 2 touched:** `Ifs`, `Exits`, `Frame`, `Statements.local` and `single`, `Lowering.dispatch`, `Operators`, `Tuples`, `CallTargets`, `SubsetScan`, `JavacCompilation`.
- **Tests outside this slice's files:** `JavacRejectionsTest`, `JavacLambdasTest`, `LoweringAloneTest` and `JavacStatementsTest` held rows that said a loop stops, and those moved to the positive form.
