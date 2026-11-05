# Plutus V3/PV11 conformance overlay

This directory contains only the evaluation expectations that differ between
julc's V3/PV10/C conformance snapshot in `../conformance` and the exact Plutus
release shipped by the target Cardano node:

- **cardano-node:** `11.0.1`
- **Plutus package:** `1.63.0.0`
- **Plutus commit:**
  [`f92b7d7d82622a26caf456a6be33859f697e2cfc`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-conformance/test-cases/uplc/evaluation)
- **Ledger profile:** Plutus V3, protocol version 11, semantics variant E
- **Last verified:** 2026-08-01

The shared base also forward-ports the text-parser changes from Plutus
[#7816](https://github.com/IntersectMBO/plutus/pull/7816): the fixtures under
`builtin/constant/value/` and `builtin/semantics/valueContains/ccy-missing/`
come verbatim from commit `b1db04cc4` (2026-06-17). Literal `value`s must be in
canonical form. This parser-only update applies to both profiles; the
evaluation and cost-model pin above and the 65 overlay files are unchanged.

The overlay has exactly 65 upstream expectation files: 61 budget expectations
(57 numeric budgets and four evaluation failures) and four result expectations.
All 1003 inputs and every unchanged result/budget expectation are selected from
the base directory. `PlutusConformanceTest` runs both profiles and asserts these
counts. The real V3/PV10/C ledger profile runs 741 applicable fixtures and 542
exact numeric budget comparisons; 236 Batch 6 fixtures and 26 fixtures using
PV11-only `Case` on builtin constants are explicitly marked unavailable.
V3/PV11/E runs all 1003 fixtures and 721 exact numeric budget comparisons. These
counts are asserted before constructing the dynamic tests.

Everything below this README comes verbatim from the pinned Plutus tree. Do not
hand-edit it. To verify the overlay, compare the pinned tree to the base,
excluding the two forward-ported subtrees listed above, and confirm that these
are exactly the 65 content differences (ignoring trailing newline
representation). Verify the two excluded subtrees separately against
`b1db04cc4`.

Later Plutus `master` is deliberately not the reference for this profile.
Changing the node or Plutus pin requires an explicit compatibility decision and
an update to ADR-030 and issue #61.
