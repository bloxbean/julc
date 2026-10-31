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
| for-each over an inline list | M1b review | `for (var x : JulcList.of(a, b))` loses the element type: `x` stays Data, so `acc.subtract(x)` fails on-chain (`SubtractInteger: expected integer`) though javac types `x` as `BigInteger`. A declared local (`JulcList<BigInteger> xs = ...`) works. Fails closed | M3 lowers from javac's types |
| `.equals` between unrelated types | M1b review (F751) | `PubKeyHash.equals(BigInteger)` is `false` on the JVM and fails on-chain (`UnBData: expected data`). Fails closed. Same family as T34 (`String.equals`, #292) | M3 lowers `equals` from javac's types; a comparison javac can prove false could be diagnosed |
| chained read on a new object | M1c review | `new Tuple2<>(x, y).second()` is rejected with `JULC0055` (also in the explicit form, as at B0) | M3 lowers from javac types; revisit then |

## Deferred review findings (P2/P3)

| ID | Milestone | Finding | Plan |
|---|---|---|---|
| F604 | M1c | Diamond inference does not use a method-argument target; the wording says "as javac infers them" | Wording fix in flight in M1c round 3; parameter-type targets come with M3 |
| F710 (fallback) | M1c | `switch` expressions lose their result type in the frozen frontend, so `JULC0091`/#299 do not see through them | M1c round 3: preserve the type, or reject those shapes. Either way, the javac frontend's real types resolve it in M3 |
| F720 | M1c (Codex confirmation) | Pre-existing, also at B0: a second `switch` erases the "unknown" result type (`LinkedHashSet` merges the `UNKNOWN` sentinel with `DataType`), so a mixed-type switch value fed through another switch reaches `JulcList.of` / a diamond and fails on-chain (`MkCons`). Fails closed (the script rejects what the JVM accepts) | Resolved by javac types in M3; until then an accepted residual (a mixed-type switch inside a switch, then Data-encoded) |
| F721 | M1c (Codex confirmation) | Pre-existing: `Optional.of` builds `DataConstr` directly and encodes an `UNKNOWN`-typed value without the `JULC0012` check; fails on-chain at `MkCons` | M3 (javac types); or a one-line `requireDataEncodable` in `StdlibRegistry`'s `Optional.of`, if needed earlier |
| F722 | M1c (Codex confirmation) | Pre-existing: the raw-destination rule (F711) does not look through `switch` arms, so `Tuple2 t = switch … new Tuple2<>(x, y)` then `(BigInteger) t.second()` fails on-chain (`AddInteger: expected integer`). Fails closed | M3 (javac types); until then an accepted residual |
| F724 | M1c (Codex confirmation) | An unknown-valued multi-accumulator packing reports `JULC0012` at `0:0` (no position; `LoopBodyGenerator.java:231`) | Position it at M4's loop port |
| M1 pool simplification | M1a (F404) | The gate's pool is each entry point's resolved library list plus the bundled stdlib, not D8's per-entry-point scan | M3 replaces it with D8's pools |
| F530 note | M1a | `readJars` without a supported javac checks entry paths only (report and off modes, with a warning) | Revisit when the javac frontend becomes the default (M5) |
| Plugin test flake (broader) | M1c integration | `JulcPluginBuildLifecycleTest` failed intermittently under heavy file creation (a corpus recording beside it), not under CPU load alone: `compileJulc` was `UP_TO_DATE` after an output was deleted, or failed `@InputDirectory` validation after the source directory was. Reproduced on #267 without M1c. Controlled A/B under file churn: 3/20 failed with file watching, 0/20 with `--no-watch-fs`; that test now passes `--no-watch-fs` | Other plugin tests that change files between builds use the same runner shape and were not stress-tested; revisit if one flakes |
| Per-case observation of the `compareTo` rule | M1c (F730) | 5,626 of 7,596 byte-changed cases have no observation attached (`068-m1c-reference-b1.md`). V146 shows the rule is the only byte change, so they rest on the rule's own tests. `compare-evaluations.py` matches 12,826 evaluations only as groups | Pair old and new scripts through the ledger's case keys when M3 compares frontends; the same comparison is M3's gate |
| `DialectGate` | M1b review | The test helper that turns the gate off changes process-global state with one saved slot, so it is neither nesting-safe nor concurrency-safe. Current callers restore it | Removed with the dialect tests at M6; pass `CompilerOptions.setJavacGate` where a test needs it sooner |
| Explicit decode cost | M1b review | Seven rewrites and two Blaster fixtures grow by writing out a conversion the dialect made implicitly (985 → 1,039 bytes at `PV11_SAFE`, +3.3% CPU). M1's exit allows it, listed. Whether a cheaper lowering exists was not shown | Look at the decode and re-encode pair when M3 lowers typed calls |
| Dialect tests with no acceptance value | M1b review | Tests that compile with the gate off exercise the old spelling; they are not migration acceptance. `DialectRemovalAlternativesTest` pins the alternatives under `enforce` | Delete the dialect tests at M5, when the JavaParser frontend stops being default |
| External `ByteStringLibEvalTest` | M1 close | The test's wrapper source in `julc-examples` needs `import org.julclang.stdlib.annotation.OnchainLibrary;`. With it all 10 tests pass (checked in a scratch clone) | Add it in `julc-examples` before the release that carries the gate |
| Tasks outside `test` | M1b review | `wasmSmokeFull`, `pairCaseTest` and `onChainHarnessTest` are outside `test`, so a green suite says nothing about them; `wasmSmokeFull` failed under `enforce` while `test` was green | Run them at every milestone close; consider a task that aggregates them |
| wasm smoke flakiness | M1a | `wasmSmokeFull` timed out once at load average ~80 (a VM-only evaluation step) | Monitor; raise the timeout if it recurs |
| evaluations ledger size | M0 | The evaluations ledger is 6 MB (stored as gzip) and grows each milestone | Consider trimming its columns, or storing only the rows that differ, after M1 |
| F210 scope | M0 | The class-file rule for implicit `equals` without code is sound only for API stubs (guarded by `ApiRecordEqualsTest`) | Keep the guard; do not reuse the rule for user class files |
| ADR-067 revision | ADR-068 D11 | ADR-067's I9 oracle (javac 25), outcome matrix, M2b gate and the retirement of `Javac21ParityTest` are still to be written | With the M3 switch port |
| ADR-066 G1 | ADR-066 | M3 part 2 onward (guard and record-pattern generation) waits for the javac frontend's switch port | After M3 |
| Illustrative doc blocks | M1b | 16 whole-unit doc blocks stay invalid alone on purpose (WRONG/CORRECT pairs, compact source files, cross-file examples, sketches); 408 fragments were scanned only by the BigInteger-operator heuristic | Compile docs' examples in a test (as `ValueOrientedExamplesTest` does) when the docs are next reworked |

## Closed here

Entries move here, with the commit that resolves them, once they are fixed.

| ID | Resolved by |
|---|---|
| F723 | `7eb301f0`: the release note also covers a single-typed `switch` followed by a method call |
| F401 | `ed07fb87`: `JavacProvenance` on `CompileResult`. ADR-068 r9 withdraws the note's `preamble.compiler`, which CIP-57 closes |
| E6 cold start | M1 close: about 1.6× and +150 ms; threshold 2× (ADR-068, "M1: closed") |
| Elci native shape | M1 close: JVM CLI and native images (GraalVM 25.0.2, 25.3.4.1) pass against the snapshot |
| F731, F732 | `compare-ledgers.py` reports a run only the reference has as `lost observation`, keeps each part of a difference, and calls a change a fix only if more levels agree with the JVM. The committed diff is unchanged, so no regression was hidden |
