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

> Narrowed 2026-11-01: `Case` and builtin `IfThenElse` agree only on a real boolean, so a
> conditional lowers to `Case Bool` only when `BooleanProof` proves its condition, and to the
> forced/delayed builtin otherwise. See [the condition proof](#o2-the-condition-proof) below.
> The original research follows. Its fixture's first conditional reads a lambda parameter, so it
> keeps the builtin and the other two lower to `Case`; the candidate figures below include that.

Every typed PIR `IfThenElse` lowers to `Case Bool [else, then]` when the
selected level enables safe PV11 rules and the target advertises
`CASE_ON_BUILTIN_CONSTANTS`. `BASELINE` retains the previous forced/delayed
`IfThenElse` bytes. The initial measurement default did too; Milestone 6 later
promoted `PV11_SAFE` to the default after the complete validation gate passed.
The stable rule ID is
`pv11.o2.case-bool`.

The fixture evaluates the condition once through `Trace`, covers both branch
values, selected and unselected `Error`, and exact trace order. Results,
failure text, and traces are identical on Java and Truffle.

Scalus 1.1.0 is additionally pinned as a language-level compatibility
cross-check. Its focused regression confirms False/True branch selection and
selected-branch-only evaluation for `Case Bool`. This does not make the Scalus
adapter a protocol-aware ledger-cost backend; JuLC continues to use target
provenance for canonical evaluation.

Baseline hash: `11c11d8c4bb4027ee56c3277a64c620c738e1d7b9ee3429490696968`

Candidate hash: `79c618e702dc6a5f9c767f113096561244a7770854fc34d08f947f7b`

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 134 | 121 | -13 |
| UPLC term nodes | 119 | 98 | -21 |
| CPU, successful path | 2251555 | 1875457 | -376098 |
| Memory, successful path | 9694 | 8292 | -1402 |
| CPU, selected failure | 2028014 | 1651916 | -376098 |
| Memory, selected failure | 8798 | 7396 | -1402 |

This fixture contains three conditionals, two on `Case`; its CPU/memory delta
is a representative combined result, not a universal per-conditional constant.
Source-map compilation uses the typed lowering and maps the emitted Case term;
the later identity-changing UPLC optimizer remains disabled as before.

### O2: the condition proof

`UplcGenerator` asks `BooleanProof` about the condition of each PIR conditional, on the final PIR
at the point of code generation. The proof reads terms and never a type. Proven forms: a boolean
constant; a saturated builtin whose result is a boolean, so a decoded boolean needs no special case;
a conditional, `IntegerCase` or `DataMatch` whose branches are proven; `PairMatch` over
`UnConstrData`; a `Let` or `Trace` body; `Error`, which produces no value; a variable bound to a
proven value; a saturated call of a let-bound or recursive function whose result is proven, recursion
by greatest fixpoint. A lambda parameter is not proven, and the innermost binder of a name decides.
Any other condition lowers to the builtin, in the shape `BASELINE` emits.

A condition that stays on the builtin costs about 4 FLAT bytes at its site and 188,049 CPU and 701
memory each time it runs. Over the ADR-068 corpus at `PV11_SAFE` (`PV11_COSTED` is within 0.005
points on bytes and equal on CPU and memory), compared with `Case` on every conditional:

| Proof | Sites on `Case` | Sites on the builtin | Bytes, all | Bytes, external | CPU, external | Memory, external |
|---|---:|---:|---:|---:|---:|---:|
| without calls | 326,515 | 26,554 (7.5%) | +1.323% | +2.331% | +1.442% | +1.437% |
| with calls | 347,405 | 5,664 (1.6%) | +0.219% | +0.530% | +0.060% | +0.060% |

The rule keeps the row with calls. CPU and memory are the 124 external evaluations that a corpus case
compiles to, replayed on their recorded arguments; their outcomes, results, traces and failure
messages are unchanged. The largest groups left are a local bound to an unproven value and a call of
a function whose result is unproven; `ListsLib.contains` is one, since its result is a loop's
accumulator, a lambda parameter.

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

> Superseded 2026-09-13 by ADR-041, which pins the failure contract for compiler-generated
> sealed dispatch and enables `pv11.o5.case-integer` at `PV11_SAFE`; see
> `adr/evidence/041-integer-case-dispatch.md`. The record below is the original research.

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

> Closed as rejected 2026-09-13 by ADR-041 after a census of shipped artifacts; see
> `adr/evidence/041-integer-case-dispatch.md`.

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
