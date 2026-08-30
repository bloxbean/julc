# ADR-032 O8/O9/O11/O12/O15 evidence: cost/use rewrite deferrals

**Issues:** [#102](https://github.com/bloxbean/julc/issues/102),
[#103](https://github.com/bloxbean/julc/issues/103),
[#96](https://github.com/bloxbean/julc/issues/96),
[#104](https://github.com/bloxbean/julc/issues/104), and
[#105](https://github.com/bloxbean/julc/issues/105)

**Target:** `plutus-v3-pv11-uplc-1.1.0`

**Cost profile:** `cardano-node-11.0.1-plutus-v3-pv11`

**Parameter hash:** `40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`

Reproduce the complete Java/Truffle tables with:

```text
./gradlew :julc-benchmark:optimizationEvidence
```

These fixtures compare two explicit source shapes at `BASELINE`. They measure
the opportunity without claiming that JuLC can infer the candidate safely.
Repository audit found no typed PIR optimization, dominance, alias, escape, or
representation-aware use-analysis stage. The UPLC optimizer has only local
De Bruijn use counts and cannot prove the required typed invariants.

## O8 and native-Value O15 — explicit sharing wins; inference deferred

The baseline converts the same Data value separately for two lookups. The
candidate explicitly binds one `JulcValue` and reuses it. Success, malformed
Data failure text, and traces are exactly equivalent on Java and Truffle.

Baseline hash: `cc4248596a2c7bafb7a66ab0a7e7c7f3643ed8059087a56305a941d1`

Candidate hash: `d4fdd21954619f5f2530ef6c84499e6e4a54699fa679a91ff70c9ac7`

| Metric | Repeated conversion | Explicit shared conversion | Delta |
|---|---:|---:|---:|
| FLAT bytes | 50 | 48 | -2 |
| UPLC term nodes | 52 | 50 | -2 |
| CPU, valid | 2508650 | 1995960 | -512690 |
| Memory, valid | 6080 | 5824 | -256 |
| CPU, malformed | 921318 | 761318 | -160000 |
| Memory, malformed | 5076 | 4076 | -1000 |

No automatic rule ships. `UnValueData` is partial, and moving it can change
whether it runs, its position relative to Trace/Error, and which malformed
input fails first. A correct pass needs typed dominance and use analysis,
deterministic let insertion, and an explicit source-map policy. Developers can
obtain the measured benefit now by binding `JulcValue` once in source.

Conversion cancellation such as `ValueData(UnValueData(d)) -> d` remains
forbidden because it can remove validation or canonicalization. This outcome
also defers the native-Value portion of O15; general CSE remains out of scope.

## O9 and array O15 — valid paths win; failure semantics block promotion

The fixture compares two `JulcList.get` calls with one explicit `toArray()` and
two `JulcArray.get` calls. Valid results match on both VMs, and the sampled
candidate wins even for a one-element list with two index uses.

Baseline hash: `003ade43826ee48df7d2dfe81f05a4615773c2a565446fcab79689a7`

Candidate hash: `f15bb2db87455e776f3ea76325cec5cd475fbe8ddb4dc0829f7b5912`

| Metric | Repeated list traversal | One array conversion | Delta |
|---|---:|---:|---:|
| FLAT bytes | 142 | 51 | -91 |
| UPLC term nodes | 157 | 55 | -102 |
| CPU, length 1 | 2441281 | 1676075 | -765206 |
| Memory, length 1 | 11630 | 6434 | -5196 |
| CPU, length 8 | 13767570 | 1849941 | -11917629 |
| Memory, length 8 | 57598 | 6441 | -51157 |

No promotion rule ships. Current recursive `JulcList.get` and `IndexArray`
both fail for negative/out-of-range indices, but their exact failure text and
failure point differ on both backends. Promotion would also need proof that the
list expression is evaluated once, the array is shared, and no list-typed uses
escape. There is no such typed use/escape analysis today. `MultiIndexArray`
remains illegal for PV11.

The measurements may seed a future guarded threshold after the source contract
defines common index failure semantics. Until then, developers can choose the
explicit `toArray()` API when its different failure contract is acceptable.

## O11 — BLS MSM fusion remains deferred

Milestone 2's [O11 evidence](032-o11-bls-msm-deferral.md) remains controlling.
JuLC still lacks distinct native G1/G2/Miller types and native scalar/point
lists. Cost/use analysis cannot repair a representation-incorrect input, so no
MSM chain fusion is attempted.

## O12 — ordinary pow/mod is not ExpModInteger

The explicit `MathLib.expMod` API already lowers directly to
`ExpModInteger`. The research candidate is much smaller and cheaper for the
sample, but replacing ordinary `MathLib.pow(base, exponent) % modulus` changes
semantics:

- `(2 ^ -1) % 5` follows `MathLib.pow`'s negative-exponent contract and returns
  `1`;
- `MathLib.expMod(2, -1, 5)` computes the modular inverse and returns `3`;
- zero/negative modulus paths fail with different operation-specific failure
  text and timing.

Baseline hash: `5cdafcb65f6ecc05d4b66f7daa03ce7f2d599a6498ce331d023a3cbf`

Candidate hash: `9228e71d990d20f58c5b706a36c28304a2b20ea2ec6894613f1f322c`

| Metric | pow then mod | explicit ExpMod | Delta |
|---|---:|---:|---:|
| FLAT bytes | 204 | 23 | -181 |
| UPLC term nodes | 228 | 22 | -206 |
| CPU, 2^5 mod 13 | 20574640 | 1402326 | -19172314 |
| Memory, 2^5 mod 13 | 82033 | 2997 | -79036 |

No idiom rewrite ships. Developers should use `MathLib.expMod` when that is
their source contract. Recognizing `%` after ordinary `pow` would silently
change supported Java-subset semantics and failure behavior.

## O15 aggregate decision

Pair sharing remains deferred by O4; native Value and array sharing are
covered above; case-bound list decoding remains deferred by O3. A general CSE
pass is still rejected. Revisit individual typed sharing rules only after two
concrete consumers justify a small typed PIR analysis rather than parallel
one-off representations.
