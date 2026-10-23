# julc-difftest

The differential JVM/UPLC test harness of [ADR-063](../adr/063-differential-jvm-uplc-test-harness.md),
step S1 of the [ADR-062](../adr/062-jvm-uplc-semantic-consistency-programme.md) programme. This module is
not published.

The harness runs the same Java source in two ways:
- with javac, in a fresh class loader for each evaluation;
- with JuLC on the Java VM, at every optimization level.

It then compares the two results. A difference in value, outcome kind or trace sequence counts as a mismatch.

```sh
./gradlew :julc-difftest:test                          # saved cases, exhaustive G0, fixed-seed G0 campaign
./gradlew :julc-difftest:nightlyDiffTest               # 3000 G0 programs with a fresh seed
./gradlew :julc-difftest:test -Pdifftest.cases=<dir>   # replay another directory of .diffcase files
```

## Generated programs

`gen/G0` decodes a list of choices into one static method:
- `while` loops with a bounded counter, and for-each loops;
- nesting up to two loops deep;
- accumulator updates, including inside `if`/`else`;
- `break`, loop-body locals, ternaries, `&&` and `||`.

jqwik generates and shrinks the choice list, so a failure is reported as a small program. The last failing
case is also written to `build/difftest/last-failure.diffcase`.

A generated mismatch passes only if one entry in `gen/KnownDivergences` explains it. Each entry names its
ADR-062 catalogue ID, a structural feature of the program, and the observation each side produced.

## Saved cases

`src/test/resources/regressions/*.diffcase` holds the saved cases. `RegressionCasesTest` replays each one at
every level and checks the expectation in its header:

| Expectation | Meaning |
|---|---|
| `AGREE` | Both sides return equal Data with equal traces, or both fail with equal traces |
| `REJECT <code>` | JuLC rejects the source with this diagnostic code; `REJECT none` for a diagnostic without one |
| `DIVERGE <id> <JVM>/<UPLC>` | A known divergence from the ADR-062 catalogue, with the observation kind each side must produce (`Value`, `Failure`) |
| `expect.<LEVEL>: …` | An override for one optimization level |

Arguments are given as `arg:` lines, one per parameter, each holding the CBOR of the Data in hex. The JVM
side converts each argument to the parameter's declared type using `ValueMapping`.

When the owning step fixes a divergence, change its expectation. Never delete a case.
