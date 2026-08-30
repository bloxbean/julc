# ADR-032 O1 evidence — native `DropList`

- **Issue:** [#94](https://github.com/bloxbean/julc/issues/94)
- **Compiler target:** `plutus-v3-pv11-uplc-1.1.0`
- **Candidate level:** `pv11-safe`
- **Rule ID:** `pv11.o1.drop-list`
- **Cost profile:** `cardano-node-11.0.1-plutus-v3-pv11`
- **Parameter SHA-256:** `40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`

Reproduce the checked-in fixtures with:

```bash
./gradlew :julc-benchmark:optimizationEvidence
```

The evidence runner compiles the same Java source at `baseline` and
`pv11-safe`, evaluates identical inputs on the Java and Truffle VMs, and fails
on any difference in outcome, result term, failure text, or trace order. A
separate bounded matrix covers list lengths 0–8 and counts -3–12 on both VMs.
This evidence was captured while `baseline` was the implementation-window
default. After the complete ADR-032 validation gate passed, Milestone 6
promoted `pv11-safe` to the default. Explicit `baseline` remains byte-identical
to the pre-ADR-032 lowering.

## Direct call

Baseline script hash: `f01735934d14844d41bf818597fb74063f6cdf5f64219f788dfd84bf`; candidate script hash: `48c322f1d8cb4e2d62c89b870c2b0dd33c63a4517da7ffa54a6e2e36`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 162 | 109 | -53 |
| UPLC term nodes | 164 | 103 | -61 |

| Case | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---:|---:|---:|---:|---:|---:|
| negative | 2,681,062 | 2,311,844 | -369,218 | 13,162 | 10,864 | -2,298 |
| zero | 2,681,062 | 2,309,887 | -371,175 | 13,162 | 10,864 | -2,298 |
| one | 3,886,301 | 2,311,844 | -1,574,457 | 17,931 | 10,864 | -7,067 |
| equal length | 6,296,779 | 2,315,758 | -3,981,021 | 27,469 | 10,864 | -16,605 |
| over length | 6,639,261 | 2,319,672 | -4,319,589 | 28,702 | 10,864 | -17,838 |
| empty | 3,023,544 | 2,313,801 | -709,743 | 14,395 | 10,864 | -3,531 |
| receiver failure | 1,129,970 | 1,129,970 | 0 | 6,166 | 6,166 | 0 |
| count failure | 2,079,783 | 1,839,783 | -240,000 | 10,832 | 9,332 | -1,500 |

Java and Truffle reported the same ledger budgets for every row. The explicit
receiver/count lets preserve the required trace sequences:

- ordinary call: `receiver`, then `count`;
- receiver failure: no trace from the count expression;
- count failure: `receiver` only, followed by failure.

## Two composed calls

Baseline script hash: `a0b95c5867b9d02b0c0258048a2edf2a29cc2d869e07a67f6b1987f7`; candidate script hash: `810a65fca43ca2a2e2e0b92bee086e3389b9827ae823f87c10e49855`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 152 | 40 | -112 |
| UPLC term nodes | 171 | 43 | -128 |

| Case | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---:|---:|---:|---:|---:|---:|
| one then one | 4,367,623 | 1,122,709 | -3,244,914 | 19,870 | 5,136 | -14,734 |
| zero then two | 4,367,623 | 1,122,709 | -3,244,914 | 19,870 | 5,136 | -14,734 |
| over then one | 6,257,826 | 1,130,537 | -5,127,289 | 27,105 | 5,136 | -21,969 |
| negative then one | 3,162,384 | 1,122,709 | -2,039,675 | 15,101 | 5,136 | -9,965 |

## Decision

Ship O1 at the `pv11-safe` and `pv11-costed` levels. It is a legality-only
target-aware rewrite: all tested sizes and ledger budgets improve. It began
opt-in; the evidence-backed Milestone 6 release decision promoted `pv11-safe`
to the default, with `baseline` retained as the migration path.
