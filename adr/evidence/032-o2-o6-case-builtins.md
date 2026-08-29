# ADR-032 O2–O6 evidence: case on builtin constants

**Issues:** [#97](https://github.com/bloxbean/julc/issues/97),
[#98](https://github.com/bloxbean/julc/issues/98),
[#99](https://github.com/bloxbean/julc/issues/99),
[#100](https://github.com/bloxbean/julc/issues/100), and
[#101](https://github.com/bloxbean/julc/issues/101)

**Target:** `plutus-v3-pv11-uplc-1.1.0`

**Cost profile:** `cardano-node-11.0.1-plutus-v3-pv11`

**Parameter hash:** `40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`

The pinned [Plutus case-on-constants documentation](https://plutus.cardano.intersectmbo.org/docs/delve-deeper/casing-constants)
defines Bool, Unit, Integer, List, and Pair casing for UPLC 1.1.0. The raw
experiments below use those exact branch conventions and run through both JuLC
Java and Truffle VMs. Reproduce the complete per-backend tables with:

```text
./gradlew :julc-benchmark:optimizationEvidence
```

Raw UPLC evidence demonstrates potential only. A Java/PIR rewrite is shipped
only where the compiler still has enough type and source-semantic information.

## O2 — Bool: enabled at `PV11_SAFE`

Every typed PIR `IfThenElse` lowers to `Case Bool [else, then]` when the
selected level enables safe PV11 rules and the target advertises
`CASE_ON_BUILTIN_CONSTANTS`. `BASELINE` and the default retain the previous
forced/delayed `IfThenElse` bytes. The stable rule ID is
`pv11.o2.case-bool`.

The fixture evaluates the condition once through `Trace`, covers both branch
values, selected and unselected `Error`, and exact trace order. Results,
failure text, and traces are identical on Java and Truffle.

Baseline hash: `11c11d8c4bb4027ee56c3277a64c620c738e1d7b9ee3429490696968`

Candidate hash: `8a7649d2c3d23c9d3da8c504e851f340d04bac3911139a77dbe432e0`

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 134 | 117 | -17 |
| UPLC term nodes | 119 | 91 | -28 |
| CPU, successful path | 2251555 | 1687408 | -564147 |
| Memory, successful path | 9694 | 7591 | -2103 |
| CPU, selected failure | 2028014 | 1463867 | -564147 |
| Memory, selected failure | 8798 | 6695 | -2103 |

This fixture contains three conditionals; its CPU/memory delta is a
representative combined result, not a universal per-conditional constant.
Source-map compilation uses the typed lowering and maps the emitted Case term;
the later identity-changing UPLC optimizer remains disabled as before.

## O3 — List: promising experiment, deferred

The experiment compares a typed integer-list `head` implemented with
`ChooseList`/`HeadList` against `Case List`, including empty-list failure and
singleton/non-empty results. Exact failure text and results match across both
backends.

Baseline hash: `68cf30ef95347b4adfa51d84a7ce79f246333c1ecf978c371adb97f7`

Candidate hash: `511a9ccfdb3b3c94efb3fe680937d281831baafaa00f4e3ebc21aafc`

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 15 | 9 | -6 |
| UPLC term nodes | 16 | 7 | -9 |
| CPU, non-empty | 488244 | 128100 | -360144 |
| Memory, non-empty | 1864 | 900 | -964 |

No rule ships. One head projection does not prove equivalence for recursive
`size`, `map`, `filter`, folds, Data-map traversal, or element decoding. JuLC
needs a typed PIR list-match/destructuring form so head/tail are bound once and
decode/failure locations remain explicit. An untyped global
Null/Head/Tail-pattern rewrite is rejected.

## O4 — Pair: promising experiment, deferred

The experiment sums both fields of one native integer pair, comparing two
projections with one `Case Pair` binding.

Baseline hash: `5875f6f339666897117cb139b09c76a1dd9d87241bf88a894e4381d8`

Candidate hash: `72d5a153b3d39b29e7cc63ddc4b27cf51df98992392643f061911cdd`

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 15 | 13 | -2 |
| UPLC term nodes | 14 | 10 | -4 |
| CPU | 641195 | 293308 | -347887 |
| Memory | 1766 | 1302 | -464 |

No rule ships. JuLC has no local typed use analysis proving that both
projections share one already-evaluated native pair. `Tuple2` and record pairs
are Data-encoded and must never match this rule. A syntactic UPLC projection
rewrite would guess both representation and sharing.

## O5 — Integer: semantically blocked and deferred

A dense three-branch raw case is much smaller, but it is not observably
equivalent to JuLC's explicit equality chain on invalid input:

- baseline negative/out-of-range failure: `Error term encountered`;
- Case failure: `Case: tag -1 out of range for 3 branches` (or the positive
  out-of-range tag).

Java integer switches also require a default/catch-all behavior, which UPLC
integer Case cannot express. JuLC currently reserves switch expressions for
sealed Data variants, not native integers.

Baseline hash: `e7ad7f95b0f25da84d488bff39173dc7f179421b2b986ad4ed86699d`

Candidate hash: `3e7818916854800fa20a2854bdba596f4dde509ff0436d53b8999979`

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 44 | 13 | -31 |
| UPLC term nodes | 44 | 6 | -38 |
| CPU, branch 2 | 1073246 | 96100 | -977146 |
| Memory, branch 2 | 4406 | 700 | -3706 |

No compiler surface or rewrite is added. A later source-semantic ADR would
need an explicit bounded, no-default operation whose failure contract is Case,
not an optimization of existing Java behavior.

## O6 — Unit: promising experiment, deferred

The typed raw experiment preserves a `Trace` continuation and improves the
single legal Unit path.

Baseline hash: `bb2fb762d0c1e76d490e2941b2496f54005deefc84675c4041b572f2`

Candidate hash: `c23890a11bb33e427e7b23b1a1cc2afca5981eca3c0cdf41f33fb917`

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 22 | 19 | -3 |
| UPLC term nodes | 12 | 9 | -3 |
| CPU | 345060 | 235598 | -109462 |
| Memory | 1536 | 1232 | -304 |

No rule ships because JuLC currently exposes no supported high-level
ChooseUnit sequencing construct for the compiler to own. Rewriting an untyped
application would lose the proof that the scrutinee is Unit; case on another
constant has different behavior. Revisit only with a typed sequencing PIR node
or a supported explicit API.
