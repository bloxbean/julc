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
- Data/native misuse fails compilation with `JULC0041`, including explicit
  assignments, equality, Data-backed list/optional elements, and record fields;
- opaque native values, including nested container/record graphs, cannot be
  decoded from external method or validator Data arguments (`JULC0042`);
- final Data codec and UPLC-constructor checks remain fail-closed if an earlier
  source-level check is bypassed.

The pre-ADR-032 experimental Java signatures used `PlutusData` for native
values. Source that explicitly typed native intermediates as `PlutusData` must
change those locals to `JulcValue`; `var` source needs no change. No automatic
conversion cancellation, motion, or `ValuesLib` replacement is enabled here.

These checks apply at every optimization level, including `baseline`. They are
a correction to the experimental native-Value type contract, not a
`pv11-safe` rewrite. Existing code that passed Data to native builtins or put a
native Value in a Data-backed container must migrate to explicit
`NativeValueLib.fromData(...)`/`toData(...)` boundaries.

## Cross-backend measurement

Reproduce with:

```text
./gradlew :julc-benchmark:optimizationEvidence
```

The fixture performs Data conversion, insert, lookup, scale, union, conversion
round-trip, and containment. It asserts successful containment for present and
initially absent lookup keys on both backends without introducing an unrelated
conditional lowering into the O7 measurement.

Baseline script hash: `9ff21a6e8d3a3e85e6596103a99bdac629796629b1af6979c0f1fd07`; candidate
script hash: `9ff21a6e8d3a3e85e6596103a99bdac629796629b1af6979c0f1fd07`.

| Metric | Baseline | PV11 safe | Delta |
|---|---:|---:|---:|
| FLAT bytes | 77 | 77 | 0 |
| UPLC term nodes | 82 | 82 | 0 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| Java | present | success | 4365018 | 4365018 | 0 | 9288 | 9288 | 0 |
| Java | absent | success | 5067242 | 5067242 | 0 | 9374 | 9374 | 0 |
| Java | malformed Data | failure | 761318 | 761318 | 0 | 4076 | 4076 | 0 |
| Truffle | present | success | 4365018 | 4365018 | 0 | 9288 | 9288 | 0 |
| Truffle | absent | success | 5067242 | 5067242 | 0 | 9374 | 9374 | 0 |
| Truffle | malformed Data | failure | 761318 | 761318 | 0 | 4076 | 4076 | 0 |

Success values, exact failure text, traces, and budgets match across levels and
backends. `PV11_SAFE` records only the pre-existing generic optimizer passes;
there is no O7 rewrite rule because the marker type erases during lowering.
