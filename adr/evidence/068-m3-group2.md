# ADR-068 M3 Group 2: closing `switch` and lambdas

Group 2 is M3e (`068-m3e-switch.md`) and M3f (`068-m3f-lambdas.md`) merged on `wip/068-m3g2`, with `DelayedMethodPass`
(`068-m3-reference-b4.md`). Independent reviews read it, then a confirmation round. This file states what changed after them.
The two slices' files hold what each delivered.

## The rules after review

| Rule | Where |
|---|---|
| `==` on references is D6's at every position that reaches a term, in a guard too: `JULC0086`, unless both operands are constant strings of D6's plain form. A guard constant as a whole has Java's value and reaches no term | `Operators.equality` |
| An inferred lambda parameter has the type javac gives it, in a guard too | `Lambdas`, `Guards`; `068-m3f-lambdas.md` |
| A cast to a map reads through parentheses, a conditional, a `switch`, a cast that does not decode and a variable to each value it may hold. Where those are a map and another type it stops at `JULC0084`, so no value is decoded twice | `Calls` |
| Work counts each constant, read, function, `let` and branch as 1, a constructor's value by its size, and a Data constant by its CBOR bytes | `DelayedMethodPass` |
| A branch that may fail ends the reading of a body | `DelayedMethodPass` |
| A `DIFFERENT-pir` row pins the type and term of every method that differs | `Adr068Lowering` |

## What moved

- **I1, the first freeze.** 33 rows moved, all `DIFFERENT-pir`, and only in their text and digest: 14 `CallbackHelper`, 18
  `HelperCallers`, 1 `CallbackValidator`. No new `DIFFERENT-*` row, and no bytes moved. The freeze took
  `-Padr068.allowShrink=true`, because a row's digest changed.
- **I1, the second freeze.** 42 rows moved, all `DIFFERENT-javac-rejects`, with the same diagnostic, text and digest, since a
  method's attempt now ends at the rule on `==`. 38 change `reference equality` to `(rejected)`. 4 lose a covered method,
  `198a8c12` 2/2 to 1/2, and `6f3e6de1`, `707c4a0e`, `f191c4dc` 1/1 to 0/1, so covered methods go from 11,708 to 11,704. No
  bytes moved.
- **MPF.** 72 rows, 0 bytes and 0 budget against the last head. `nullHash2` reads 141 against `WORK_LIMIT` 1024.
- **Accepted programs.** 458 new programs over 3 inputs, 1,374 runs: 1,329 equal the JVM. The other 45 are T7, a later
  element failing because `any` does not stop. The grid of accepted programs has 394 `BYTES≠`, before and after.
- **The work limit.** The figures are in `068-m3-reference-b4.md`, per method. They are the largest found, not a ceiling.
  The largest strict body is 12.98% of a transaction's CPU and 15.04% of its memory. A body newly past the limit is delayed,
  by the delay rule, so a program that calls it in a loop pays it at each call, and its bytes and cost move on the reference
  too: no corpus program does.
- **Compile time.** `poly-6-30` compiles in 74 ms, from 11.7 s.

## Mutation evidence

30 mutants of the fix lines, with a clean no-op control. 28 killed. Two survive with a reason:

| Mutant | Why it survives |
|---|---|
| `condition read late` | With the `then` check in place, a null from an inner branch ends the reading at the next branch, so the result is the same |
| `no branch reads as Data` | With no branch in the argument, the term's own type says Data on every probe that reached the line |

The later fixes ran 26 more, each set with a clean control: 22 killed, one that did not compile and was rewritten and is among those killed, and one that showed a catch was not needed, which went. Two survive: `no branch reads as Data` in its new place, and `param read`, since a `@Param`'s initializer holds a constant of the field's own type, so reading it says what the field says.

## What this does not establish

- Group 2's lowering holds only for programs with no library call. A library call stays a stop until Group 3.
- The work limit's figures are the largest found, and they are per method. Many small strict bodies add up.
- The over-rejections in "Closed by M5" and `068-followups.md` remain, and M5 reads them with the corpus.

## Gates

At `9ab85bc3`, the last code of the group, through `build/g2/gates.sh`, with the tree clean before and after:

| Gate | Result |
|---|---|
| `:julc-difftest:adr068Baseline`, gate off | 0 |
| `:julc-difftest:adr068Lowering`, I1 against the frozen table | 0, no row moved |
| `:julc-difftest:adr068Types` | 0 |
| `./gradlew test --rerun --continue` | 18,041 tests, 0 failed, 0 errors, 530 skipped, in 1,000 result files |
| `:julc-compiler:pairCaseTest` | 0 |
| `:julc-e2e-tests:onChainHarnessTest` | 0, offline |
| `verification/blaster/scripts/prepare-artifacts.sh` | 0 |

MPF: 72 rows, 0 bytes and 0 budget, at the last change to `DelayedMethodPass`. No DevKit, Elci or image ran on Group 2. The group merged `5a1dd06f`, a change to the Java VM's Ed25519, after these gates; that merge took the VM, Scalus, Truffle and testkit suites, and CI.

Three confirmation rounds followed the reviews. The last fix round, a memo in `Calls.valuesOf` and a stop at a `@Param`, had a read of its diff, its mutants and its tests, and no fourth review.
