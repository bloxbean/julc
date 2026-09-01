# Pinned V3 cost-model vectors

These vectors pin the Plutus V3 models used by the ADR-033 conformance matrix:

- `v3-pv10-C-f92b7d7d8.txt`: protocol 10, semantics/model C, 297 values.
- `v3-pv11-E-f92b7d7d8.txt`: protocol 11, semantics/model E, 350 values.

The source is Plutus commit
`f92b7d7d82622a26caf456a6be33859f697e2cfc` (Plutus 1.63.0.0, as
shipped with cardano-node 11.0.1). Each vector was derived by looking up the
constructors in `PlutusLedgerApi.V3.ParamName` declaration order in:

- `plutus-core/cost-model/data/builtinCostModel{C,E}.json`
- `plutus-core/cost-model/data/cekMachineCosts{C,E}.json`

The corresponding `julc-vm-java` synchronization test also requires these
values to match `DefaultCostModel` flattened by `CostModelParser`. This gives
the resources two independent checks: the pinned upstream JSON/parameter order
and JuLC's Java reference backend.
