# ADR-068 M3: the reference change B4 → B5

A binder now hides a method of its own name in its scope in `DelayedMethodPass`. Two binders of one method's name reach the pass
when a root is also compiled as a library, and the reference's resolution does that. The pass took a read of the root's helper
for the library copy, which a field that may fail delays, and applied it to `()`, so the script failed where Java returns. The fix
is in code both frontends use (D4, "After M3a": a defect there is fixed at once), and it moves the reference's bytes and results
for such programs, so it is a reference change: **B5** (I1, "Reference changes"; D4, exception 2). It moves no corpus row.

| Change | Commit |
|---|---|
| A `let`, `letrec`, lambda, pair, list or Data match binder hides an outer delayed or total binder of its name in its scope | `5e90734e` |
| `agree-068-b5-root-and-its-library-copy.diffcase` and its class-path copy, `META-INF/plutus-sources/B5SelfLibrary.java` in `julc-difftest`'s test resources | `0f04d6c8` |

B4 is the previous reference ([`068-m3-reference-b4.md`](068-m3-reference-b4.md)); its rows are not edited. B5 is not frozen
here: the integrator freezes it with Group 3's tables.

## The rule

PIR is scoped by name. `DelayedMethodPass` walks the assembled term with the delayed binders in scope, and the bound ones it has
shown total; a read of a delayed binder becomes a call of it with `()`. Before B5 a binder of the same name did not take the name
out of either set, so in its scope a read of the inner binder was rewritten as a read of the outer one. Now:

- a `let` takes its name out of the delayed set and the total map in its body, and puts it back only if it is itself a method
  the pass delays;
- a `letrec` takes all its names out, and puts back those of its bindings it delays;
- a lambda's parameter, a pair or list match's two names and a Data match branch's bindings and pattern variable take their
  names out of both in their bodies.

A source cannot write a binder of a method's name: a method binder is qualified (`Owner.method`) and a local holds no dot (ADR-060).
The only pair is the one the reference's resolution makes, a root's method and its library copy, both `Owner.method`. Taking a
name out of the total map can only make a method more delayed, never less.

## What Java and the script do

The review's probes (`ZzG3aRev2ProbeTest`, uncommitted in `julc-068-rev-lam`, run B with the pass at `5e90734e^`, run D at the
head; pool from `LibrarySourceResolver.resolve` over the root). Bytes are FLAT digests and sizes; the reference runs with the gate
off. `JAVAC` has the reference's bytes in both runs.

| Program | Level | Reference at B4 | Reference at B5 | JVM |
|---|---|---|---|---|
| `d1`: `HALF = valueOf(100).divide(TWO)`, `f(x) = R.g(x).add(HALF)`, `f(4)` | `NONE` | `fda921a8`/76, Failure `AddInteger: expected integer` | `eb934e48`/73, 58 | 58 |
| `d1` | `BASELINE`, `PV11_SAFE`, `PV11_COSTED` | `4e6ade22`/40, Failure | `3e01f8d4`/37, 58 | 58 |
| `d3`: a tracing field, a total `k()`, `f(x) = x + R.k()`, `f(5)` | `NONE` | `48c1cf96`/87, Failure `Cannot apply non-function value`, trace `init` | `cc6eae49`/83, 12, trace `init` | 12, `init` |
| `d5`: `h() = HALF`, `h1() = R.h() + 1`, `f(3)` | `NONE` | `fffee4ed`/103, Failure | `ecfa02a1`/101, 104 | 104 |
| `d8`: a list-index promotion candidate, `f([1, 2, 3])` | `PV11_COSTED` | `df9b828e`/252, Failure `HeadList` | `ea238d4f`/77, 57 | 57 |

Each moves from a failure to the JVM's value. The old outcome was always a failure: an integer function applied to `()`. The
review's other probes of the shape (`d2`, a zero-argument may-fail method on one branch; `d6`, a root's and a library's method of
one simple name; `d7`, a validator; `d8c`, an explicit pool) agree with the JVM at all four levels on both frontends at B5.

**The pin.** `agree-068-b5-root-and-its-library-copy.diffcase` is `d1`'s shape: the class path offers the case's own text, so
`compileMethod(String)`'s resolution compiles the root as a library too. `f(4)` is 58 on the JVM and on both frontends at all
four levels (`RegressionCasesTest`, `Adr068SavedCasesOnJavacTest`). With the pass as at B4 the case fails: see "Mutants".

## Deltas

**Corpus: 0 rows moved.** The review ran `adr068Baseline` at `122332f9` with a scratch output (`baseline-gate.log`, `bg-out/`,
`bgdiff.py` in the review's scratch directory): every frozen B4 file is identical for all 11,456 old cases (ledger, semantics,
budgets, instantiations, rejection inventory). The gate's only failures are the new saved cases, which have no frozen row yet.
I1's `lowering-reference.tsv` equals the committed one in each of its 17,094 old rows, in the review's run and in this
change's (scratch `i1-7`), which adds two rows, each `REFERENCE-DRIFT` with no committed row: `diverge-068-narrowing-cast` and
this change's case (`d45d7efd`, 73 bytes at `NONE`).

**MPF on the reference: identical.** All 72 rows (17 methods and the registry validator, four levels) have the same bytes,
budgets and result digests with the B4 and the B5 pass (the review's `mpf-B-oldDMP.tsv` against `mpf-D.tsv`).

**Size.** Only programs that compile a root as its own library move, and they move from a script that fails to a smaller one
that answers (3 bytes less for `d1`, 175 for `d8`).

## Mutants

| Mutant | Result |
|---|---|
| `DelayedMethodPass` as at B4 (`5e90734e^`) | Killed: `agree-068-b5-root-and-its-library-copy` fails on both frontends, and `JavacLibrariesTest.aDelayedCopyDoesNotDelayTheHelperOfItsName` |
| A `let` that does not hide a delayed name | Killed (`JavacLibrariesTest`) |
| A lambda that does not hide a delayed or total name | Killed (`DelayedMethodPassTest`'s parameter rows) |

## Proposed ADR text (r15)

For the revision history:

> - **r15** (2026-11-03, M3 Group 3a, on repository evidence and independent reviews, `068-m3-group3a.md`):
>   - B5: in `DelayedMethodPass` a binder hides a delayed or total method of its own name in its scope, on both frontends,
>     under exception 2. The reference's resolution compiles a root as a library too, and the pass took the root's read of its
>     helper for the delayed library copy, so the script failed where Java returns. This is the fifth reference change
>     (`068-m3-reference-b5.md`); it moves no corpus row, and `agree-068-b5-root-and-its-library-copy.diffcase` pins it.
>   - Group 3a: libraries and Elci's provider on `frontend=JAVAC`, with each host's pool as the reference resolves it, and casts.
>     `(int)` of a `long` keeps Java's value (T6's narrowing, a Semantic fix); a cast to a map reads a variable through its
>     initializer only for an effectively final `var`, reads a variable with a written type as that type, and stops for a
>     variable assigned after its declaration.

## What this does not establish

- **A shape outside the review's probes.** The rule is general; the evidence is the probes, the pin, the corpus and MPF.
- **An on-chain run.** DevKit was not run; the harness's VM is the evidence.
- **The freeze.** B5's tables are written by the integrator.
