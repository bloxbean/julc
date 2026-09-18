---
title: "Cost model profiles"
description: "Immutable cost snapshots for reproducible evaluation and benchmarks"
---

Compilation does not require a cost model. Select the target and optimization
level independently; `pv11-costed` enables the structural list-to-array rule
without reading numeric costs. Its performance evidence is measured against a
pinned model, but its benefit still depends on runtime list lengths and indexes.

```groovy
julc {
    optimization = 'pv11-costed'
}
```

Equivalent choices are `OptimizationLevel.PV11_COSTED` in Java,
`julc build --optimization pv11-costed`, and `-Ajulc.optimization=pv11-costed`
for the annotation processor. The default remains `pv11-safe`.

## Immutable profile catalog

| Profile ID | Target | Exact upstream source | Parameters |
|---|---|---|---|
| `plutus-v3-pv11-costs-v1` | Plutus V3 / PV11 | Cardano node **11.0.1**, Plutus **1.63.0.0**, revision [`f92b7d7d82622a26caf456a6be33859f697e2cfc`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc) | 350, canonical V3 `ParamName` order |

The snapshot combines `builtinCostModelE.json` and `cekMachineCostsE.json` from
that revision. Its parameter SHA-256 is
`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`.
The hash covers each trimmed decimal parameter followed by LF, in order,
including the final LF; blank lines and `#` comments are excluded. The registry
verifies this hash when loading the bundled resource.

This source mapping identifies the snapshot, not a requirement to run that node
version. An ID's parameters never change. A new table requires a new versioned
ID; there is no `latest` or unversioned `plutus-v3-pv11` alias.

## Evaluation and benchmarks

For reproducible local evaluation, explicitly configure the model:

```java
var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
var vm = JulcVm.create("Java");
vm.setCostModelParams(profile.costModelParameters(), profile.target());
var result = vm.evaluate(program, profile.target());
```

`OptimizationBenchmarkRunner` requires a profile and records its resolved ID and
parameter hash with the measurements. The checked-in evidence runners select
the fixed `PLUTUS_V3_PV11_COSTS_V1` snapshot, so running those tasks needs no
extra configuration. Exact-budget and conformance tests pin their models
independently of compilation. Evaluator conformance is a separate contract
from an optimization's benchmark evidence.

For actual transaction evaluation, supply the network's cost parameters and
ledger target via `JulcVm.setCostModelParams`. `JulcTransactionEvaluator` gets
them from the caller's `ProtocolParamsSupplier`. Neither path requires a named
JuLC profile. The network's active model determines on-chain execution costs.
Existing VM defaults remain available for local convenience; they are not a
substitute for current network parameters when estimating transaction budgets.

## Compatibility and provenance

The previous ID, `cardano-node-11.0.1-plutus-v3-pv11`, still resolves to the same
canonical object. The `CARDANO_NODE_11_0_1_PLUTUS_V3_PV11`, `_ID`, and
`_PARAMETER_HASH` Java constants are deprecated; use
`PLUTUS_V3_PV11_COSTS_V1`, `_ID`, and `_PARAMETER_HASH`. Resolution through either
ID reports the canonical neutral ID. Existing `source()` and constructor APIs
remain available; bundled source text is now `bundled:plutus-v3-pv11-costs-v1`.

Optional Java `setOptimizationCostProfile`, Gradle `costProfile`, CLI
`--cost-profile`, annotation-processor `-Ajulc.costProfile`, and MCP `costProfile`
configuration remain accepted for compatibility. Each accepts the canonical ID
(or the deprecated alias). Explicit unknown IDs and target mismatches are still
errors, but these options do not configure evaluation or affect generated code.

Compilation reports record compiler version, target (on `CompileResult`), level
and applied rules. Cost identity/hash fields are absent when no numeric model
was consumed, including when an unused optional profile was supplied. Benchmark
reports retain evaluation identity/hash independently. A future compiler rule
that calculates numeric profitability must declare its consumer-specific model
requirement and record the model actually used; no such rule ships today.

Changing the name or omitting a compiler profile does not change UPLC, script
hashes, source maps, failure behavior or budgets under the same evaluation model.
