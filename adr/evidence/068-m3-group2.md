# ADR-068 M3 Group 2: closing `switch` and lambdas

Group 2 is M3e (`068-m3e-switch.md`) and M3f (`068-m3f-lambdas.md`) merged on `wip/068-m3g2`, with `DelayedMethodPass`
(`068-m3-reference-b4.md`). Independent reviews read it, then a confirmation round. This file states what changed after them.
The two slices' files hold what each delivered.

## The rules after review

| Rule | Where |
|---|---|
| A lambda's body in a guard is ordinary code for D6: its identity comparison is `JULC0086` | `SubsetScan.visitLambdaExpression` |
| An inferred lambda parameter has the type javac gives it, in a guard too | `Lambdas`, `Guards`; `068-m3f-lambdas.md` |
| A cast of a value with no representation, a conditional or a `switch` of mixed values, or a `var` of one, stops at `JULC0084`. A value is decoded once | `Calls` |
| Work counts each constant, read, function, `let` and branch as 1, a constructor's value by its size, and a Data constant by its CBOR bytes | `DelayedMethodPass` |
| A branch that may fail ends the reading of a body | `DelayedMethodPass` |
| A `DIFFERENT-pir` row pins the type and term of every method that differs | `Adr068Lowering` |

## What moved

- **I1.** After the fixes, 33 rows moved, all `DIFFERENT-pir`, and only in their text and digest: 14 `CallbackHelper`, 18
  `HelperCallers`, 1 `CallbackValidator`. No new `DIFFERENT-*` row, and no bytes moved. The freeze took
  `-Padr068.allowShrink=true`, because a row's digest changed.
- **MPF.** 72 rows, 0 bytes and 0 budget against the last head. `nullHash2` reads 141 against `WORK_LIMIT` 1024.
- **Accepted programs.** 458 new programs over 3 inputs, 1,374 runs: 1,329 equal the JVM. The other 45 are T7, a later
  element failing because `any` does not stop. The grid of accepted programs has 394 `BYTES≠`, before and after.
- **The work limit.** The figures are in `068-m3-reference-b4.md`, per method. They are the largest found, not a ceiling.
- **Compile time.** `poly-6-30` compiles in 74 ms, from 11.7 s.

## Mutation evidence

30 mutants of the fix lines, with a clean no-op control. 28 killed. Two survive with a reason:

| Mutant | Why it survives |
|---|---|
| `condition read late` | With the `then` check in place, a null from an inner branch ends the reading at the next branch, so the result is the same |
| `no branch reads as Data` | With no branch in the argument, the term's own type says Data on every probe that reached the line |

## What this does not establish

- Group 2's lowering holds only for programs with no library call. A library call stays a stop until Group 3.
- The work limit's figures are the largest found, and they are per method. Many small strict bodies add up.
- The over-rejections in "Closed by M5" and `068-followups.md` remain, and M5 reads them with the corpus.

## Gates
