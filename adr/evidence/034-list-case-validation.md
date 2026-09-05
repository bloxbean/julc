# ADR-034 / issue #110 validation and review

Branch: `feat/110-typed-list-case`, based on `8c9f1f63`.

## Scope and semantic argument

This implements the compiler-generated for-each family, not every List helper.
Eligible loops include scalar and record elements, nested lists/loops, effect-only
bodies, multiple accumulators, conditional break, recursion and mutual recursion.
Map/native-pair loops, other traversal builders and loops without a tail use
retain the old form.

The original NullList guard runs first. Its failure is unchanged for non-lists;
its empty branch returns the original accumulator; its non-empty branch proves
that raw head/tail extraction is total and effect-free. Case binds those values
without decoding them. The original wrapDecode and loop body remain at their
original evaluation positions, preserving partial failures, traces and break.
The Case empty branch is unreachable from this producer. Generated binders use
`#` (not a Java identifier character) and the per-loop counter.

No untyped UPLC pattern matching, list-to-array conversion, ledger encoding
change, new source syntax, or VM implementation change is introduced.

## Review findings and corrections

- Checked List branch order against the existing pinned O3 experiment and VM
  implementation: cons is branch 0, nil is branch 1. Empty/singleton tests
  detected the initial reversal and pass after correction.
- Found map iteration shares the builder but carries native pairs. Explicitly
  excluded its PairType path rather than inferring Data representation.
- Found unchecked List casts are no-ops in existing source lowering. Retained
  NullList to preserve the exact non-list failure instead of changing it to a
  Case failure. `unchecked` is now a differential regression fixture.
- Historical PV11_SAFE comparison found a size regression when an unconditional
  break never uses the tail. The producer now checks free tail use and retains
  the old projection for that shape.
- Kept the original loop body by identity in that fallback so source positions
  are preserved; no second bodyBuilder invocation or body-copy substitution.
- Reviewed substitution, free-variable collection, recursion discovery,
  accumulator-reference scanning, strict-boundary replacement, PIR formatting
  and UPLC scope push/pop. Mutual recursion and nested loops exercise these.
- Preserved the ListMatch result type when its nil branch is a typed Error;
  added a focused type-inference regression.
- Isolated benchmark source methods so unrelated strict recursive helper
  bindings do not inflate measured artifacts or obscure the new rule's effect.

This is implementation self-review with executable evidence, not independent
review or a formal compiler-correctness proof. ADR-032/#110's independent review
remains a pre-merge requirement. No merge or release is performed here.

## Test scenarios

`O3ListCaseLoweringTest` covers:

- NONE/BASELINE/PV11_SAFE/PV11_COSTED, deterministic output and the named default;
- source-map mode and source mapping of emitted Cases;
- Java and Scalus language-only success/failure/trace equivalence for empty,
  singleton, multiple, malformed outer and malformed element inputs;
- malformed unused elements still fail decoding; break skips later bad elements;
- head/tail binder scope, substitution and free-variable collection;
- direct match scrutinee Trace runs once; unselected divergence stays unobserved;
- target/profile fail-closed behavior for direct baseline/NONE node lowering;
- exclusions for native map traversal, while loops and unconditional break;
- preservation of failure source locations where the legacy VM supplies them
  (legacy synthetic builtin failures can have no mapped source term).

`O3ListCaseBenchmarkTest` covers 12 independent compiled-source fixtures on
Java and Truffle: sum, traced traversal, conditional stop, multiple accumulators,
selected failure, unchecked representation, effects, nested lists, recursion,
mutual recursion, records and an O1/O2/O3/O13 aggregate. It compares exact
results, failure text, traces, CPU and memory, asserts host-model results and
uses 80 deterministic generated lists for both sum and early termination.
The aggregate includes acceptance, rejection, negative/exhausted drop counts,
and malformed elements that are either skipped or visited.

Existing compiler, stdlib, testkit, tooling, examples and optimizer property
suites remain regression evidence; they are not a formal proof of this rule.

## Historical artifact compatibility

`julc-compiler/src/test/resources/optimization/o3-pre-change-bytes.txt` was
captured with the unmodified compiler at `8c9f1f63` in a separate temporary
checkout. Its exact source is `O3ListCaseLoweringTest.SOURCE`, with methods sum,
first, unused, nested and multi, both source-map modes, and NONE/BASELINE/
PV11_SAFE. The 20 NONE/BASELINE rows must remain byte-identical. The 10 previous
PV11_SAFE rows are executable before-images for size, semantics and budgets.

Representative isolated comparison with the **previous PV11_SAFE compiler**
(Java, source maps off, pinned PV11 cost model):

| Fixture/input | Old/new bytes | Old/new CPU | Old/new memory |
|---|---:|---:|---:|
| sum `[3,0]` | 70 / 70 | 2,694,862 / 2,301,236 | 12,124 / 11,596 |
| sum `[]` | 70 / 70 | 756,466 / 756,466 | 4,264 / 4,264 |
| first `[3,0]` (excluded) | 58 / 58 | 988,360 / 988,360 | 5,128 / 5,128 |
| unused `[3,0]` | 71 / 70 | 2,694,862 / 2,301,236 | 12,124 / 11,596 |
| nested `[[3]]` | 119 / 117 | 2,977,276 / 2,583,650 | 14,054 / 13,526 |
| multi `[3,0]` | 172 / 171 | 6,406,057 / 6,142,094 | 25,230 / 25,034 |

The sum's CPU improvement is about 14.6% versus the previous default. The
separate [generated tables](034-list-case-measurements.md) compare BASELINE with
PV11_SAFE and therefore also include the benefit of existing O2 and, for the
aggregate, O1/O13. They must not be presented as O3-only gains. These are fixture
measurements, not universal validator savings or cost-model-independent numbers.

Recompiling with PV11_SAFE can change hashes. Historical BASELINE/NONE bytes
remain reproducible; reproducing an old PV11_SAFE artifact requires retaining
the old compiler. Existing deployed scripts and datum/redeemer encodings do not
change. The new sealed PIR variant affects exhaustive PIR consumers only.

## Validation commands and scope

Commands executed with the repository Gradle wrapper and Java 25:

```
./gradlew :julc-compiler:test --tests '*O3ListCaseLoweringTest' --tests '*PirTermTest'
./gradlew :julc-benchmark:test --tests '*O3ListCaseBenchmarkTest'
./gradlew :julc-compiler:test :julc-stdlib:test :julc-benchmark:test :julc-testkit:test
./gradlew :julc-vm-java:test :julc-vm-truffle:test :julc-vm-scalus:test --rerun-tasks
./gradlew :julc-benchmark:listCaseEvidence
./gradlew build -PskipSigning=true
```

Documentation: `npm run build` in `docs`, 32 pages built successfully.

The existing aggregate optimization benchmark tests pass as part of
`:julc-benchmark:test`; the new aggregate separately combines O3 with O1/O2/O13.

Final repository build: **10,674 reported tests, zero failures, zero errors,
531 skipped** (counts from modules in `settings.gradle`; stale reports from
removed modules are excluded). This is 10,143 non-skipped tests. Selected
module totals: compiler 1,473, benchmark 18, stdlib 403 (one existing skip),
testkit 191 and examples 81. CLI, annotation processor, Gradle plugin,
verification and other included modules passed as part of the build.

Fresh VM suite results:

| Module | Reported tests | Failures/errors | Skipped |
|---|---:|---:|---:|
| Java VM | 2,439 | 0 / 0 | 262 |
| Truffle VM | 3,486 | 0 / 0 | 262 |
| Scalus VM | 312 | 0 / 0 | 0 |

Java and Truffle each report 1,998 conformance rows: 737 applicable PV10/C
cases and all 999 PV11/E cases pass; the 262 skips are PV10-inapplicable cases,
not skipped PV11 coverage. Exact pinned budget comparisons remain active.
Scalus runs the 999-case inventory for supplied/bundled cost sources under
PV10/C and PV11/E inside one matrix test, classifying expected parse errors,
inapplicable cases, non-serializable inputs and known upstream divergences.
Passing this matrix does **not** certify Scalus ledger parity. Public target
certification stays fail-closed exactly as documented in ADR-033.

Yaci DevKit E2E is an opt-in external-devnet task and was not enabled. Managed
external verification workflows were not run; no claim of formal coverage for
new Case List output is made. Existing default-build skip conditions remain.

One intermediate build overlapped a focused compiler run and collided writing
XML reports; subsequent validation runs were serialized. No compiler/test
assertion was weakened to address that infrastructure collision.
