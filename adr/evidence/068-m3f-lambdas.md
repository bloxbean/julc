# ADR-068 M3f: lambdas and the list methods that apply them

M3f is the slice of M3 ([ADR-068](../068-javac-frontend-migration.md)) after `switch`. A lambda passed to `map`, `filter`, `any`, `all` or `find` now compiles through
`frontend=JAVAC`. `switch`, a parallel slice, stays a stop inside a body, as do loops and libraries, and nothing falls back (I8). The JavaParser frontend did not change.

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md). Branch `wip/068-m3f`, from `edfa707b`, Group 1 merged. Machine and toolchain as in M0's evidence, with
`25.0.2-librca`.

## What was delivered

| Piece | Where |
|---|---|
| A lambda at the method that applies it: parameter, body, result | `lower/Lambdas` |
| The call: receiver, function, handler, what a read of the result is | `Calls.hof` |
| A decoded parameter is one name over two binders | `Scope.rebind`, `Terms.decoded` |
| A lambda's frame: its `return` leaves the lambda | `Frame.lambda` |
| A hash read from a lambda's parameter | `CallTargets.isLambdaParameter` |
| `LAMBDA_EXPRESSION` where lowering reaches it, a method reference's and a functional local's rejections | `SubsetScan` |
| `compareTo` in D6's form reads its receiver as the reference does | `Operators.CompareToZero` |
| `JULC0091` on a stdlib call names no value, as in the reference | `Lowering.located` |

### What a lambda lowers to

`PirHofBuilders` applies the function to each element as Data, so:

```
map(x -> body)          Lam x:Data. let x = decode(x) in encode(body)
filter(x -> body)       Lam x:Data. let x = decode(x) in body
```

| Question | Answer |
|---|---|
| What the parameter holds | The type javac gave it, read through `JavacFacts`, written or inferred (`valueType` for an inferred box) |
| What the body sees | The written type. If none, the element type of the receiver as the reference sees it (`CallTargets.seen`), which is the type javac gave it wherever the reference has one |
| What is decoded | `BigInteger`, `int`, `long`, `byte[]` and a hash, `boolean`, `String`, a list, a map. A record, a sum, `Optional`, a tuple and `PlutusData` are Data already |
| What `map` encodes | javac's element type of the result. Where that says only Data, what the term says, since a builtin can leave a list under `PlutusData` |
| Rejections inside | The body's own. A `return` is the lambda's, so `requireNativeBoundary` does not apply, as in the reference |

The split between "sees" and "holds" is the same as a `var` of a decoded read after Group 1. `var bs = JulcList.of(true); bs.any(x -> x)` runs, where the reference fails on
`IfThenElse`. `var ns = JulcList.of(a); ns.any(x -> x.signum() > 0)` stays `JULC0055`, as in the reference, so the subset does not widen (I2).

### `find`

Java declares the element, or null. `PirHofBuilders.find` leaves an `Optional` in Data.

| Where the call stands | Term | Why |
|---|---|---|
| Anywhere a value is read | The `Optional`'s field, decoded by javac's type | F1023. A miss fails at `HeadList`, where Java's null fails at its first use |
| First argument of `PlutusData.cast(..., Optional.class)` | The reference's `Optional` | D6's unchecked view, and M1b's rewrite of `.isPresent()`: the corpus holds it 1,200 times |
| An expression statement | The reference's `Optional` | Nothing reads it, so a miss goes on, as in Java |

What `find` is to the reference stays what it records, an `Optional`, so `xs.find(p).add(k)`, `var r = xs.find(p); r.add(k)`, `r.equals(k)`, `xs.find(p).a()` and `xs.find(p).hash()` are `JULC0055` on
both frontends (I2).

### Evaluation order

`Lambdas` states it. Java evaluates the receiver, makes the lambda, which evaluates nothing, and calls it once per element from the first. A `Lam` runs at each application, and what it
captures is the value bound around it, which javac has shown to be effectively final.

| Method | Java | Script |
|---|---|---|
| `map`, `filter` | Every element, in order | The same |
| `find` | Stops at the first match | The same |
| `any` | Stops at the first match | Runs over the whole list (T7) |
| `all` | Stops at the first refusal | Runs over the whole list (T7) |

T7 is unchanged. `anyDoesNotStopAtTheFirstMatch` and `allDoesNotStopAtTheFirstRefusal` pin it: where a later element fails or traces, both frontends give the same
observation and it is not the JVM's. T7 is on D4's accepted list, so this is no D13 row.

### Stops and rejections

| Shape | Reference | `JAVAC` |
|---|---|---|
| `xs.any((x -> ...))`, `xs.any(c ? x -> a : x -> b)` | Lowers, with the parameter as Data | `JULC0084`, `a function that is no lambda at the call`: it reads the parameter as Data whatever javac says, and `Calls` reads by javac's type |
| `xs.map(Function.identity())`, `Predicate.isEqual(k)` | `Undefined variable` | The same stop |
| `(var x) -> ...` | The parameter as Data | `JULC0084`, `a lambda parameter written var` |
| `x -> ...` on a raw `JulcList` | The parameter as Data | `JULC0084`, `a lambda parameter of type java.lang.Object` |
| A lambda in code nothing lowers | Not read | `LAMBDA_EXPRESSION`, the scan's rule |
| A lambda anywhere else but a list method | No such program: its target type is rejected first | `LAMBDA_EXPRESSION` |
| `Predicate<T> p = x -> ...` | `Unknown type: Predicate` | The same, with javac's name |
| `f.apply(a)` on a local | `Functional interface .apply() is not supported on-chain` | The same, by identity. A qualified `java.util.function.Function` local says the same here, and `Unknown type: Function` there |
| `C::h` | `Method references (::) are not supported on-chain` | The same, anywhere the scan reads |
| `var f = x -> x`, `ListsLib.any`, `ListsLib.foldl` | `JULC0085` | `JULC0085` (D6) |
| `PlutusData.cast(c ? q : d, JulcMap.class)`, and a `switch` or a `var` of either, where one arm is a map and the other is not | The then branch's type decides for both | `JULC0084`: one term cannot decode one arm and leave the other, and a held map must not be decoded twice. [Follow-up](068-followups.md) |

A body holding a loop stops at its construct, and one holding a `switch` lowers it. `yield` and `return` in `Frame.lambda` behave as a lambda's, with and without a source map.

## The I1 gate

`build/m3f/i1.sh` against the tables frozen at `edfa707b`, whose compare fails on moved rows by design until the integrator freezes. Nothing shrank: `lowering-i1-shrunk.tsv` is empty.

| Class | `edfa707b` | M3f |
|---|---:|---:|
| `i1a-identical` | 1,527 | 1,579 |
| `not-ported` | 8,175 | 8,085 |
| `reference-rejects-not-ported` | 443 | 437 |
| `javac-rejects` | 5,359 | 5,359 |
| `both-reject-javac-first` | 1,288 | 1,288 |
| `both-reject` | 97 | 103 |
| `ledger-source` | 38 | 38 |
| `unreplayable` | 17 | 17 |
| `DIFFERENT-javac-accepts` | 27 | 27 |
| `DIFFERENT-javac-rejects` | 68 | 70 |
| `DIFFERENT-rejection` | 16 | 16 |
| `DIFFERENT-bytes` | 12 | 15 |
| `DIFFERENT-pir` | 0 | 33 |
| `DIFFERENT-provenance` | 7 | 7 |
| `DIFFERENT-diagnostics` | 2 | 2 |
| Total | 17,076 | 17,076 |

### Every class of moved row

1,480 rows moved, and each of the 17,076 is still present.

| Rows | From | To | Why |
|---:|---|---|---|
| 1,098 | `not-ported` | `not-ported` | The same first construct, more methods covered |
| 276 | `not-ported` | `not-ported` | `LAMBDA_EXPRESSION` to `a library unit` |
| 6 | `not-ported` | `not-ported` | `LAMBDA_EXPRESSION` to `TYPE_CAST` |
| 1 | `not-ported` | `not-ported` | `LAMBDA_EXPRESSION` to `WHILE_LOOP` |
| 52 | `not-ported` | `i1a-identical` | Compiles to the reference's bytes |
| 33 | `not-ported` | `DIFFERENT-pir` | Below |
| 3 | `not-ported` | `DIFFERENT-bytes` | Below |
| 2 | `not-ported` | `DIFFERENT-javac-rejects` | Below |
| 6 | `reference-rejects-not-ported` | `both-reject` | The reference's rejection, now reached: `JULC0055`, `JULC0091`, a method reference |
| 3 | `reference-rejects-not-ported` | `reference-rejects-not-ported` | `LAMBDA_EXPRESSION` to `WHILE_LOOP` (2), `SWITCH_EXPRESSION` (1) |

`DIFFERENT-javac-accepts` did not move, so no row of the corpus widened. Programs outside it do: see [the one deliberate widening](#the-one-deliberate-widening).

### Coverage

| | Covered | Of | | `edfa707b` |
|---|---:|---:|---|---:|
| Programs the reference accepts | 1,579 | 15,117 | 10.4% | 1,527 |
| Their distinct scripts | 719 | | | 700 |
| Programs of more than one method | 956 | | | 954 |
| Methods the reference lowers | 10,578 | 172,429 | 6.1% | 9,115 |
| Distinct covered methods | 2,223 | | | 1,889 |

Most lambdas in the corpus sit in programs that hold a library, so a program stops at `a library unit` with its lambda lowered: 1,463 more methods, and 52 more whole programs.

### The worklist

| First construct | Rows | `edfa707b` |
|---|---:|---:|
| `ENHANCED_FOR_LOOP` | 3,487 | 3,487 |
| `WHILE_LOOP` | 1,947 | 1,944 |
| `a library unit` | 1,534 | 1,225 |
| `SWITCH_EXPRESSION` | 1,260 | 1,259 |
| `LAMBDA_EXPRESSION` | 0 | 381 |
| `TYPE_CAST` | 219 | 213 |

No row stops at one of this slice's new constructs: `a function that is no lambda at the call`, `a lambda parameter written var` and `a lambda parameter of type java.lang.Object` have no
corpus case, so M5's count does not see them.

### The differences

The 38 rows are in [`068/m3f-accepted-differences.tsv`](068/m3f-accepted-differences.tsv), in the format of `lowering-accepted-differences.tsv`.

| Rows | Class | D13 class | What | Evidence |
|---:|---|---|---|---|
| 15 | `DIFFERENT-pir` | Semantic fix (F1042) | `CallbackHelper.callbackHelper` (14), `CallbackValidator.validate`, ADR-043's fixture. A list element reaches the helper as Data in the reference, and the helper fails where Java reads it | `aListParameterPassedOn`. No `.diffcase`: see [For the integrator](#for-the-integrator) |
| 18 | `DIFFERENT-pir` | Cost change | `HelperCallers.helperCallers`, the same decode on a path that does not read the list. Equal answers on well-typed Data. The cost differs by level: [below](#the-helpercallers-rows) | `aListParameterNotRead`. A budget grows, so this needs a review decision, taken with the numbers below |
| 2 | `DIFFERENT-bytes` | Semantic fix (F1023) | `0ca6d2ac`, `62fc817a`: `Builtins.constrTag` of what `find` gave. The JVM throws, the reference returns true, `JAVAC` fails | `findIsItsElement` |
| 1 | `DIFFERENT-bytes` | Semantic fix, on no issue yet | `56dc8d13`, with a source map only. Below | `aLambdasReturnIsNoReturnOfTheEntryPoint` |
| 2 | `DIFFERENT-javac-rejects` | Policy rejection (D6, T4) | `06e57ccf`, `47eaa37f`: `JULC0087`, `equals` on `byte[]` in a lambda of an external example | `theSubsetsRulesReadABody` |

**A lambda's `return` under a source map.** The reference's `booleanReturnGuardActive` stays on inside a lambda, so in a `boolean` entry point with a source map a block lambda's `return false` fails the
script, and a `return` of any other type is applied as a boolean. With no map the same program accepts. That is a misreading in the JavaParser frontend alone, so it goes on the "Closed by M5" list, and
`Frame.lambda` has no guard. `xs.filter(x -> { return false; }).isEmpty()` accepts on the JVM, on `JAVAC` with and without a map, and on the reference without one; the reference with a map fails.

**The same differences with no row**, since no corpus case has them. Each is held by `Adr068LambdasHarnessTest`.

| D13 class | What |
|---|---|
| Semantic fix (F1022) | `xs.map(f).head()`, `var ys = xs.map(f); ys.head()`, `.any(b -> b)` on a `map`'s result, the same on `var bs = JulcList.of(true, false)` |
| Semantic fix (F1023) | `xs.find(p)` returned, or held in a declared local, of each element type |
| Semantic fix (F1024, F1041) | `(BigInteger b) -> ...` and each other written type that decodes |
| Semantic fix (F1042) | `xss.any(l -> l.isEmpty())`, `xss.map(l -> l)`, `l.head()`, `l.map(...)`, a `JulcMap` element |
| Cost change | A lambda over a receiver the reference reads as Data, whose body does not read the parameter: `JulcList.of(a).any(x -> true)` decodes it, 88 bytes against 83 at `NONE` |

### The `HelperCallers` rows

Accepted as D13 Cost change. `helperCallers` over two inner lists, CPU / memory / script bytes against the reference, in `OptimizationLevel`'s order:

| Input | First | Second | Third | Fourth |
|---|---:|---:|---:|---:|
| Inner lists of 1, `k=0` | +211,866 / +1,064 / +4 | +211,866 / +1,064 / +5 | +211,866 / +1,064 / +5 | +602,218 / +2,889 / -78 |
| Inner lists of 6, `k=0` | the same | the same | the same | +850,598 / +2,899 / -78 |
| Inner lists of 2, `k=1` | the same | the same | the same | +396,421 / +690 / -78 |
| Inner lists of 2, `k=2` | the same | the same | the same | -286,783 / -2,145 / -78 |

At the first three levels each element pays one decode, 105,933 CPU and 532 memory. At the fourth, the decode makes the helper's parameter a proven list at the callback's call, so `ListIndexPromotionPass` promotes inside the helper. The two reads sit behind different branches, so `ListToArray` stands at the helper's entry: a path that reads no index pays for the array, which grows with the list, and a path that reads pays less. The script is 78 bytes smaller. `HELPER_CALLERS`' failure out of range reads `IndexArray` on `JAVAC`, within ADR-043's failure contract, and the follow-ups have the fixture's row.

The answer is equal on well-typed Data, which is what the rows claim. A `Data` element that is no list fails at the decode where the reference passes it on unread: Java cannot build that value, so it has no answer to compare, and the script fails closed. The decode cannot be left out here: a helper's convention is a decoded list, application is strict, and whether the helper reads it depends on `k`.

### The one deliberate widening

The reference lowers a lambda at `NAME.hof(...)` once with its parameter untyped, since the name might be a class, drops that term, and keeps a throw. `JAVAC` does not port that pass: an inferred lambda parameter has the type javac gives it, in a body and in a `switch` guard. So every program the reference rejects only because of that pass compiles on `JAVAC` with the JVM's answer. This is the one deliberate widening in Group 2, and it is D13's "Accepted by decision".

| Program, receiver a simple name | Reference | `JAVAC` |
|---|---|---|
| `xs.any(v -> switch (v) {...})` on an element of a sealed interface, through a `var`, nested, and in `map`, `filter`, `all` | `JULC0007` | The JVM's answer |
| `rs.any(r -> switch (r.s()) {...})`, and through a `var q = r.s()`, or a nested `ys.any(...)` | `JULC0007` | The JVM's answer |
| `fs.any(f -> f.ss().any(q -> switch (q) {...}))` | `JULC0007` | The JVM's answer |
| `xs.map(x -> { var a = JulcArray.of(x, BigInteger.ONE); return a.get(0); })`, and of `r.a()` | `JULC0012` | The JVM's answer |
| `var xs = JulcList.of(a); xs.map(x -> switch (s) { case S.A q -> x; ... })` | `JULC0012` | The JVM's answer |
| `l.filter(x -> switch (s) { case A a when x.signum() > 0 -> ... })`, what is read off `x`, a `var` of either, nested | `JULC0072` | The JVM's answer |
| A guard that is one boolean call off a parameter: `p.b()`, `p.isEmpty()`, `p.any(...)`, `p.contains(n)`, `p.second()`, `p.isPresent()`, `p.containsKey(n)` | `JULC0072` | The JVM's answer |
| `var w = c ? p : n` or a `switch` of `p` and `n`, read in a guard | `JULC0072` | The JVM's answer |

Each reads a type javac proved, and porting the rejection means lowering each lambda twice, which is exponential in nesting. `Adr068LambdasHarnessTest.aSwitchOnAnInferredElementAtASimpleName`, `aSwitchOnWhatIsReadOffAParameterAtASimpleName` and `anElementTypeTheFirstPassDoesNotSee`, and `Adr068SwitchHarnessTest.aGuardOnAnInferredParameterAtASimpleName` and `aBareBooleanCallInAGuard`, hold each to the JVM at all four levels and require the reference to go on rejecting. `Adr068GuardShapesPropertyTest` allows this difference at a simple-name receiver and nowhere else. With a call as the receiver the reference accepts the same body, and the two frontends write one term. A tag that is no constructor reads as the written type's does: `switch` fails, and `if (v instanceof A q)`, whose test compares tags, says no (`JavacSwitchesTest.aTagThatIsNoConstructorFailsInAnElement`, `aTagTestOnAnElementReadsAsTheWrittenType`).

What the reference rejects on every receiver is no part of this and stays: an inferred parameter named as a guard, `when b` (`JULC0072`), `instanceof` on one in a guard (`JULC0072`), and a member its reading cannot find (`JULC0055`).

## The tests

Counted from JUnit XML, each through the machine lock with `--rerun --no-watch-fs --offline`.

| Task | Result |
|---|---|
| `:julc-compiler:test` | 5,660 tests in 365 files, 0 failures, 0 errors, 0 skipped, at `fd047ef5` |
| `:julc-difftest:test` | 1,380 tests in 57 files, 0 failures, 0 errors, 0 skipped, at `fd047ef5` |
| `:julc-difftest:adr068Lowering` | Moved 1,480, shrunk 0, at `954e8e6c`, as above. It fails on moved rows, as expected before a freeze. No file under `src/main` changed after that commit |
| Jazzer, `:julc-difftest:fuzz`, 60 s | 960 executions, no finding, at `fd047ef5` |

The first full `:julc-compiler:test` failed 3 tests, all new in this slice, 2 of them written while it ran. Each is fixed, and the count above is the run after.


| Class | Tests | What it runs |
|---|---:|---|
| `JavacLambdasTest` | 85 | Bytes equal the reference's at every level, with and without a source map, and rejections equal |
| `JavacStopsTest` | 128 | 9 more rows: each stop, with what the default frontend does |
| `SpelledProgramsOnJavacTest` | 81 | 14 more rows. Each lambda program agrees with the JVM through a real class or stops. `find read through a view of an Optional` is the one row where Java throws, D6's view |
| `LoweringAloneTest` | 25 | One more: a decoded parameter is one name over two binders, read by I13's check |
| `Adr068LambdasHarnessTest` | 41 | The JVM and both frontends at four levels, through Data. 616 runs of `JAVAC` evaluated and agreed; the class fails below 500 |
| `Adr068LambdasPropertyTest` | 1 | 450 programs, seed `20261102`: 419 returned, 31 failed, 15 traced, against a plain Java model, the JVM and 8 configurations. Alphabet `{-1, 0, 1, 2}` for lists of at most 4 and 3 elements. The shape counts must hold or it fails. The reference is compared on cost wherever it gives the answer |
| `Adr068LambdasFuzzTest` | 7 | Replays 6 seeds and the empty input |

The required harness programs: a failing body on an empty and a non-empty list, for each of the five methods; a capture of a parameter, a local, a static field, an outer lambda's parameter, a lambda's local, a boolean, bytes,
a record's component and a list; a shadowed name; nested lambdas to depth three; a pattern variable, directly and from a nested lambda; records; sealed variants; a filter then a map; and each element type both ways.

**A fold's accumulator.** No method of `JulcList` takes a fold: `foldl` is a future item in the follow-ups, and `ListsLib.foldl` is `JULC0085` (D6). The builders' own folds hold a boolean (`any`, `all`) or a list
(`map`, `filter`), and `eachFoldOfTheBuilders` runs those on 0, 1 and 3 elements. No program can give one an integer or a record.

A rejection is no pass: `assertAgree` needs the same value or failure on all 8 configurations, and `assertFixedOnJavac` fails on a reference that rejects, so a rejection cannot pass as a fix.

**Jazzer.** 960 executions in 60 s, no finding, 6 replayed seeds in `Adr068LambdasFuzzTestInputs/`.

## Mutation evidence

One runner in one throwaway worktree. **45 mutants of the new lines: 37 killed on the first pass, and the 8 that survived each killed by a new test.** None failed to compile. The no-op control showed 0 failures first: 0 of 677 at `954e8e6c`, 0 of 683 at `fd047ef5`. The scope was `JavacLambdasTest`, `JavacStopsTest`, `SpelledProgramsOnJavacTest`, `JavacLoweringLintTest`, `LoweringAloneTest`, `JavacCallsTest`, `JavacRejectionsTest`, and the `Adr068Lambdas*`, `Adr068CallsHarness` and `Adr068ReadsHarness` classes.

| Mutation | Failures | Caught by |
|---|---:|---|
| Never a written type; `var` allowed | 6, 4 | Harness, property, bytes; stops, spelled |
| The body sees javac's type; the receiver's alone | 3, 1 | Harness, bytes; bytes |
| No decode; decode by what is seen | 97, 1 | Everything; harness |
| Decode without list, map, string, boolean, bytes, integer | 7, 1, 5, 6, 9, 83 | Harness, property |
| The `Lam` holds what is seen | 1 | `LoweringAloneTest` |
| No encode; always encode; `map` flag off | 49, 92, 49 | Everything |
| Encode by javac alone | 1 | Bytes |
| `find` always the element; never | 4, 7 | Harness, spelled |
| A statement reads `find` | 1 | Harness |
| A local's type unread | 1 | Bytes |
| No read recorded; a field as Data; the list by javac; a wrapper reads `find` | 27, 5, 3, 1 | Harness, bytes |
| `seen` knows a hof; `typedCall` does not | 7, 1 | Harness, bytes |
| A parameter's hash decodes again; any variable's does not | 4, 7 | Harness; calls, stops |
| `compareTo` on any receiver | 3 | Harness, bytes |
| A second binder takes a new name | 1 | `LoweringAloneTest` |
| A lambda keeps the entry point's guard | 16 | Harness, property, bytes |
| Lambdas allowed everywhere | 1 | Stops |
| Functional rule off; any kind of variable; any type | 3, 1, 1 | Harness, bytes; bytes; reads |
| A method reference stops | 4 | Harness, bytes |

**Survivors of the first pass, with what kills each now.**

| Mutation | Failures | Test added |
|---|---:|---|
| Encode by the term alone | 1 | `whatAMapEncodesIsJavacsType` |
| The encoding's rejection unlocated | 2 | `JULC0091` rows in `rejectionsAreTheReferences` |
| `JULC0091` names a stdlib call | 1 | The same rows |
| Any cast views `find` | 1 | The `PlutusData.class` row of `findIsItsElement` |
| Any call of type `Optional` views `find` | 1 | `anotherCallTakesTheElement` |
| A hash only of the innermost lambda's parameter | 1 | A nested row of `eachElementType` |
| The functional rule on any method | 1 | `anotherMethodOfAFunctionalLocalIsRejectedAtItsType` |
| The decode reads the `let` rather than the `Lam` | 1 | `aDecodedParameterIsOneNameOverTwoBinders` |


## What this does not establish

- **Libraries.** A library unit still stops, so a lambda in a library body runs through this code only when that slice lands.
- **A loop in a body.** It stops, so a `yield` or a `return` there is not exercised.
- **A miss nothing reads.** See [decisions](#decisions).
- **`Value` and the larger ledger records as elements** are covered by I1's identical rows and a few JUnit rows, and not by the harness.
- **Debug metadata** still stops. The reference marks a decoded parameter unsupported in its metadata, which that slice ports.
- **The rejection inventory** is still not run on this frontend.
- **Images and DevKit** were not run. The code holds no thread, reflection, file access or `ServiceLoader`.

## Decisions

1. **A `find` that finds nothing.** Any read of a miss fails where the read's value is computed, on every path. That takes in a strict `let` on a path that does not use it, a declared local read elsewhere (`BigInteger r = xs.find(p); if (c) { return r.longValue(); } return 1;` with `c` false), a helper's argument the helper does not read, and `PlutusData.cast(xs.find(p), PlutusData.class)`. The JVM and the reference return from each. `xs.contains(xs.find(p))` and `xs.find(p) + "b"` go on on the JVM alone. Null checks are rejected on both frontends, so the first of these is the natural form. It fails closed, and it is what `JulcMap.get` does (the follow-ups' `java.util.Map.get` row). F1023's plan is to follow the declared
   result, and null has no representation, so a read has to fail somewhere. `aMissThatNothingReadsFailsInTheScript` pins it. An expression statement is exempt, and so is the cast of decision 2. The alternative is one line: stop at `JULC0084` wherever
   the element is read, which keeps every answer and gives up `find` outside the view.
2. **`PlutusData.cast(xs.find(p), Optional.class)` stays the reference's.** Java throws on every such program, `NullPointerException` or `ClassCastException`, and the script gives a value, so a JVM test does not test it. D6
   calls the cast an unchecked view, and M1b's rewrite of `.isPresent()` wrote these sources, so about 1,200 corpus sources depend on it. A stop keeps them `not-ported` and M5 would have to rewrite them to `any`.
3. **A list or map element decodes (F1042), in place of ADR-043's raw callback parameter.** ADR-043 rests on a callback's list holding raw Data. With the decode it holds a list, so `ListIndexPromotionPass` may now promote
   inside the lambda, and a helper's parameter passed one is proved. All four levels agree with the JVM on both of that ADR's fixtures. The pass did not change. ADR-043's text needs a line once this is the default.
4. **`Operators` looks at the receiver in D6's form.** That closes a widening of Group 1, with no lambda in it: `var y = JulcList.of(a).head(); y.compareTo(b) > 0` was compiled here and is `JULC0055` in the reference. It is one
   check, and no corpus row depended on it.
5. **The binder lint.** `wrapDecode` was on no list. `Terms.decoded` calls it, as a decoded binder reads the binder under it, so the lint allows that one call in `Terms.java`. Everywhere else it is still a violation, and
   the negative control stands unchanged.
6. **`Cost change` on the 18 rows.** The body cannot tell from the types that a helper will not read the list, so the decode stays. `HelperCallers` gets one `UnListData` per element.

## For the integrator

- **Freeze.** `adr/evidence/068/lowering-*.tsv`. No `.diffcase` was added, so no reference row is new.
- **`lowering-accepted-differences.tsv`.** The 38 rows of [`068/m3f-accepted-differences.tsv`](068/m3f-accepted-differences.tsv). Semantic fix asks for a saved `.diffcase`. These rest on JUnit, as asked for this slice: a saved
  `CallbackHelper`, `find` and written-type case each adds a reference row, and the frozen frontend's answer there is the divergence.
- **`DIFFERENT-pir`** is a class no accepted table has held in numbers: 33 rows, all one rule.
- **Conflict with the `switch` slice.** `Lowering.dispatch`, `SubsetScan`'s lists, `Frame` and `CallTargets.seen` are touched by both. `Tuples.destination` is untouched: a `return` in a lambda has no target through `Frame.lambda`.
- **ADR text.**
  - M3's row: this slice.
  - D6, "Local functions": add that a lambda stands only at a list method. D6, the `PlutusData.cast` sentence: add `find`'s view.
  - D13: the Cost change rows have a review decision, on [the numbers by level](#the-helpercallers-rows).
  - ADR-043: decision 3.
- **Follow-ups.**
  - Close F1023, and F1024 and F1041 for lambdas. Close the lambda half of F1022 and F1042. A for-each over a `map` stays for M4.
  - Close the row "A hash read from a variable" for a lambda's parameter: `decodesAgain` knows it. Loops still owe theirs.
  - Add to "Closed by M5": a lambda's `return` under an entry point's source map. Required test: `JavacLambdasTest.aLambdasReturnIsNoReturnOfTheEntryPoint`.
  - Add: a `find` that finds nothing, decision 1, beside `java.util.Map.get`, with a line in `JulcList.find`'s Javadoc.
  - T7 stays open, pinned.
- **Shared code.** Nothing found. No file outside `frontend/javac` changed in `src/main`.
- **Files of Group 1 touched:** `CallTargets.seen`, `typedCall`, `decodesAgain`; `Calls.wrapper`, one check; `Operators.CompareToZero`; `Lowering.located`.
