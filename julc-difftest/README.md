# julc-difftest

The differential JVM/UPLC test harness of [ADR-063](../adr/063-differential-jvm-uplc-test-harness.md),
step S1 of the [ADR-062](../adr/062-jvm-uplc-semantic-consistency-programme.md) programme. This module is
not published.

The harness runs the same Java source in two ways:
- with javac, in a fresh class loader for each evaluation;
- with JuLC on the Java VM, at every optimization level.

It then compares the two results. A difference in value, outcome kind or trace sequence counts as a mismatch.

```sh
./gradlew :julc-difftest:test                          # saved cases, exhaustive G0, fixed-seed G0 and G1 campaigns
./gradlew :julc-difftest:nightlyDiffTest               # 3000 G0 and 3000 G1 programs with fresh seeds
./gradlew :julc-difftest:test -Pdifftest.cases=<dir>   # replay another directory of .diffcase files
./gradlew :julc-difftest:rediscoveryDiffTest -Pdifftest.profile=REDISCOVER_209   # G1 under a historical profile
./gradlew :julc-difftest:rediscoveryDiffTest -Pdifftest.profile=REDISCOVER_209 -Pdifftest.form=value   # only value mismatches (or =failure, =runtime)
```

## Generated programs

`gen/G0` decodes a list of choices into one static method:
- `while` loops with a bounded counter, and for-each loops;
- nesting up to two loops deep;
- accumulator updates, including inside `if`/`else`;
- `break`, loop-body locals, ternaries, `&&` and `||`.

`gen/G1` ([ADR-066](../adr/066-generator-g1-records-sealed-switch.md)) adds records, sealed interfaces, static
fields, helpers, method-level `if` and `instanceof`, `switch` expressions with type patterns, `default`, `yield`
blocks and loops in arms, traces and failures, with record and sealed arguments and results. Constructs whose
defect is open are gated by a generation profile, printed in every record; the historical profiles
`REDISCOVER_209`, `REDISCOVER_280`, `REDISCOVER_146`, `REDISCOVER_162`, `REDISCOVER_291` and `REDISCOVER_291_LOOPS`
turn one gate on each and steer generation to its construct. Once a defect is fixed below, its gate is on in the
default profile (`DEFAULT_BLOCK_SCOPE`, #291, since G1 r8).

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

A case whose program has sealed interfaces carries their constructor tags as `tag: <record> <parent> <tag>`
lines, such as `tag: Probe$A Probe$S 0`, and a generated case may carry a `profile:` line; both need
`format: 3` ([ADR-066](../adr/066-generator-g1-records-sealed-switch.md) D3, D5). Records map to
`Constr tag [components]`, with tag 0 for a record without a sealed parent; the tags come only from these lines.
A case without tags or a profile is written as `format: 2`.

When the owning step fixes a divergence, change its expectation. Never delete a case.
