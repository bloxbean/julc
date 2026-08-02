# Plutus V3/PV11 conformance overlay

This directory contains only the expectations that differ between julc's
frozen V3/PV10/C conformance snapshot in `../conformance` and the exact Plutus
release shipped by the target Cardano node:

- **cardano-node:** `11.0.1`
- **Plutus package:** `1.63.0.0`
- **Plutus commit:**
  [`f92b7d7d82622a26caf456a6be33859f697e2cfc`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-conformance/test-cases/uplc/evaluation)
- **Ledger profile:** Plutus V3, protocol version 11, semantics variant E
- **Last verified:** 2026-08-01

The overlay has exactly 65 upstream expectation files: 61 budget expectations
(57 numeric budgets and four evaluation failures) and four result expectations.
All 999 inputs and every unchanged result/budget expectation are selected from
the base directory. `PlutusConformanceTest` runs both profiles and asserts these
counts. The real V3/PV10/C ledger profile runs 737 applicable fixtures and 545
exact numeric budget comparisons; 236 Batch 6 fixtures and 26 fixtures using
PV11-only `Case` on builtin constants are explicitly marked unavailable.
V3/PV11/E runs all 999 fixtures and 724 exact numeric budget comparisons. These
counts are asserted before constructing the dynamic tests.

Everything below this README comes verbatim from the pinned Plutus tree. Do not
hand-edit it. To verify the overlay, compare the pinned tree to the base and
confirm that these are exactly the 65 content differences (ignoring trailing
newline representation).

Later Plutus `master` is deliberately not the reference for this profile.
Changing the node or Plutus pin requires an explicit compatibility decision and
an update to ADR-030 and issue #61.
