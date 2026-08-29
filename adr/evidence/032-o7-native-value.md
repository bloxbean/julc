# ADR-032 O7 evidence: typed native Value boundary

**Issue:** [#95](https://github.com/bloxbean/julc/issues/95)

**Target:** `plutus-v3-pv11-uplc-1.1.0`

**Cost profile:** `cardano-node-11.0.1-plutus-v3-pv11`

**Parameter hash:** `40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`

## Decision and observable behavior

`JulcValue` is an opaque Java marker in `julc-core` and a distinct typed PIR
value. It is neither `PlutusData` nor the ledger API's Data-encoded `Value`.
`NativeValueLib` and the corresponding Builtins surface now require that type
between explicit `UnValueData` and `ValueData` calls.

This milestone is a type-safety correction, not an optimizer rewrite:

- it applies at every optimization level;
- it emits no wrapper, coercion, or new UPLC node;
- explicit `JulcValue` locals and `var` inference produce identical FLAT;
- ledger `Value` and `ValuesLib` encoding and lowering are unchanged;
- Data/native misuse fails compilation with `JULC0041`;
- opaque native values cannot be decoded from external Data arguments
  (`JULC0042`).

The pre-ADR-032 experimental Java signatures used `PlutusData` for native
values. Source that explicitly typed native intermediates as `PlutusData` must
change those locals to `JulcValue`; `var` source needs no change. No automatic
conversion cancellation, motion, or `ValuesLib` replacement is enabled here.

## Cross-backend measurement

Reproduce with:

```text
./gradlew :julc-benchmark:optimizationEvidence
```

The fixture performs Data conversion, insert, scale, union, containment,
conversion round-trip, and lookup. It asserts quantities 48 for a present coin
and 6 for an initially absent coin on both backends.

Baseline script hash: `4ef6a1c10f93d51c37bac7cfba0000e541de0a94be26e16c5a46e82b`; candidate
script hash: `4ef6a1c10f93d51c37bac7cfba0000e541de0a94be26e16c5a46e82b`.

| Metric | Baseline | PV11 safe | Delta |
|---|---:|---:|---:|
| FLAT bytes | 89 | 89 | 0 |
| UPLC term nodes | 96 | 96 | 0 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| Java | present | success | 4709116 | 4709116 | 0 | 10490 | 10490 | 0 |
| Java | absent | success | 5411340 | 5411340 | 0 | 10576 | 10576 | 0 |
| Java | malformed Data | failure | 761318 | 761318 | 0 | 4076 | 4076 | 0 |
| Truffle | present | success | 4709116 | 4709116 | 0 | 10490 | 10490 | 0 |
| Truffle | absent | success | 5411340 | 5411340 | 0 | 10576 | 10576 | 0 |
| Truffle | malformed Data | failure | 761318 | 761318 | 0 | 4076 | 4076 | 0 |

Success values, exact failure text, traces, and budgets match across levels and
backends. `PV11_SAFE` records only the pre-existing generic optimizer passes;
there is no O7 rewrite rule because the marker type erases during lowering.
