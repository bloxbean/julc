# ADR-068 known issues and follow-ups

This file tracks known issues and deferred review findings for the javac migration (ADR-068, PR #267), so a later
iteration can pick them up.

Review policy since 2026-10-31, approved by the maintainer: a milestone closes when it introduces **no new
regression** and has **no open P0/P1**. Remaining P2/P3 findings, and known issues found along the way, are listed
here instead of blocking the milestone.

Each entry gives:
- an ID: the review finding or issue;
- where it was found;
- what is wrong;
- the planned fix, with the milestone that resolves it by construction where there is one.

## Pre-existing JuLC defects (found during the migration; open on the frozen JavaParser frontend until M5)

| ID | Found in | Problem | Plan |
|---|---|---|---|
| #296 | ADR-068 review (F61) | `"a" + 1` compiles but fails on-chain (`appendString` over an integer) | The javac frontend rejects it with `JULC0090` (M3); a decimal conversion could follow later |
| #297 | ADR-068 review (F110) | `contains` on a nested JuLC collection fails on-chain (target not Data-encoded) | Fixed in the javac frontend's lowering (M3): encode the target by its static type |
| #146 / T13 | ADR-062 | `compareTo` outside the `OP 0` form still evaluates operands twice | Fixed for `OP 0` in both frontends at M1; other forms remain, to be revisited after M3 |
| T3–T5, T29–T34 | ADR-062 / ADR-067 | Equality, record-member, two-parent and ledger-name divergences on the default frontend | Accepted until M5 (D4). The javac frontend rejects or fixes them (D6, D11) |
| `a` names | M0 (F301 work) | `a` used as a parameter name is accepted, while the same text as a local is rejected: a reading divergence | Disappears with javac reading (M3); confirm then |
| intersection cast | M0 | `(BigInteger & Serializable) a` compiles without resolving the intersection type (harmless; marked defensive in the rejection catalogue) | Decided by javac typing in M3 |
| `MathLib.divMod(...).first()` | M1c review (Codex r2) | `ClassCastException` on the JVM, while UPLC returns the quotient (raw `Tuple2` with Data components) | Investigate in the stdlib; M3's typed lowering may change it |
| chained read on a new object | M1c review | `new Tuple2<>(x, y).second()` is rejected with `JULC0055` (also in the explicit form, as at B0) | M3 lowers from javac types; revisit then |

## Deferred review findings (P2/P3)

| ID | Milestone | Finding | Plan |
|---|---|---|---|
| F604 | M1c | Diamond inference does not use a method-argument target; the wording says "as javac infers them" | Wording fix in flight in M1c round 3; parameter-type targets come with M3 |
| F710 (fallback) | M1c | `switch` expressions lose their result type in the frozen frontend, so `JULC0091`/#299 do not see through them | M1c round 3: preserve the type, or reject those shapes. Either way, the javac frontend's real types resolve it in M3 |
| F401 | M1a | The javac version and blob digests are not yet in per-compilation metadata (CLI artifact metadata, blueprint `preamble.compiler`) | M1 exit item, added without breaking I5 |
| E6 cold start | M1a | Cold compile overhead is +150–300 ms and no threshold has been set yet | Set at the M1 review, with measurements on an idle machine |
| M1 pool simplification | M1a (F404) | The gate's pool is each entry point's resolved library list plus the bundled stdlib, not D8's per-entry-point scan | M3 replaces it with D8's pools |
| F530 note | M1a | `readJars` without a supported javac checks entry paths only (report and off modes, with a warning) | Revisit when the javac frontend becomes the default (M5) |
| Elci native shape | M1a | Elci's native-image host smoke was not run (external repo) | M1 exit / M5 gate: Elci release smoke against a local snapshot |
| wasm smoke flakiness | M1a | `wasmSmokeFull` timed out once at load average ~80 (a VM-only evaluation step) | Monitor; raise the timeout if it recurs |
| evaluations ledger size | M0 | The evaluations ledger is 6 MB (stored as gzip) and grows each milestone | Consider trimming its columns, or storing only the rows that differ, after M1 |
| F210 scope | M0 | The class-file rule for implicit `equals` without code is sound only for API stubs (guarded by `ApiRecordEqualsTest`) | Keep the guard; do not reuse the rule for user class files |
| ADR-067 revision | ADR-068 D11 | ADR-067's I9 oracle (javac 25), outcome matrix, M2b gate and the retirement of `Javac21ParityTest` are still to be written | With the M3 switch port |
| ADR-066 G1 | ADR-066 | M3 part 2 onward (guard and record-pattern generation) waits for the javac frontend's switch port | After M3 |
| Dialect with no Java form | M1b | Constructs the JavaParser frontend accepts that D6 does not list and javac rejects, with no Java replacement today: a for-each over a `JulcMap` and `head()` entries (`JulcMap` is not `Iterable`, `head()` returns `Object`), `ListsLib.foldl` and `zip`, lambdas bound to `var`, implicit `Data`/bytes coercions at calls, the dialect `byte[]`/`BigInteger` methods, and a user `fromPlutusData`. Their tests compile with the gate off (`068-m1b-dialect-rewrite.md`, table U). Users now get `JULC0085`; the docs point to `keys()`/`values()` and accumulator loops | Decide per construct before M3: an API (for example `JulcMap.entries()`), a D6 amendment naming the removal, or both |
| `JulcPluginBuildLifecycleTest` flake | M1b | `deletingPlutusSourceDirectoryRemovesStaleOutputs` failed once in a full run (`sourceDir` does not exist, `@InputDirectory` + `@SkipWhenEmpty`) and passed in isolation; gate-independent | Watch; investigate if it recurs |
| Illustrative doc blocks | M1b | 16 whole-unit doc blocks stay invalid alone on purpose (WRONG/CORRECT pairs, compact source files, cross-file examples, sketches); 408 fragments were scanned only by the BigInteger-operator heuristic | Compile docs' examples in a test (as `ValueOrientedExamplesTest` does) when the docs are next reworked |

## Closed here

Entries move here, with the commit that resolves them, once they are fixed.
