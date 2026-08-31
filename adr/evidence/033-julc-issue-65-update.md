# Draft update for issue #65

## Scalus ADR-033 outcome

PR #122 implements ADR-033's protocol-aware Scalus candidate pipeline and all
eight implementation milestones. The evidence does **not** add Scalus to the
ledger-parity claim: `CERTIFIED_TARGETS` remains empty and public explicit
targets fail closed with zero consumed budget.

The supplied-profile conformance matrix is cost-exact for every executable
numeric fixture:

| Profile | Applicable | Structural BLS literals | Results | Expected failures | Numeric exact | Certification |
|---|---:|---:|---:|---:|---:|---|
| V3/PV10/C | 737 | 63 | 479 matches + 3 pinned DST mismatches | 137 | 482/482 | Blocked upstream |
| V3/PV11/E | 999 | 111 | 614 matches + 3 pinned DST mismatches | 216 | 617/617 | Blocked upstream |

V3/PV10 is blocked by `SCALUS_HASHTOGROUP_DST_HIGH_BYTE` and
`SCALUS_SLICEBYTESTRING_INT64_NARROWING`. V3/PV11 is blocked by those plus
`SCALUS_MISSING_CARDANO_INTEGER_BOUND_E`,
`SCALUS_MISSING_CARDANO_BYTESTRING_BOUND_E`, and
`SCALUS_MISSING_WRITEBITS_4096_BOUND_E`.

The Scalus 1.1.0 bundled epoch-645 snapshot is exact when interpreted as
V3/PV11/E (617/617 numeric budgets), but that route stays disabled until the
target itself is certified. It is not exact as V3/PV10/C (445/482; 37 budget
mismatches caused by seven snapshot coefficients), so explicit PV10 requires a
matching configured model even after semantic blockers are resolved.

V1/V2 language-only compatibility now passes the live node arrays into
target-bound Scalus machines, and provider-path perturbations prove mapped
legacy prices propagate for all four PV10/PV11 profiles. Their explicit
targets remain uncertified under `SCALUS_V1V2_PV11_REFERENCE_FILL`: Scalus
ignores supplied Constr/Case positions and fills PV11-only builtin prices from
its reference model, and JuLC has no pinned V1/V2 corpus.

ADR-030 now points to ADR-033's Certification evidence for the exact matrix and
committed reproducers. This completes #74's evidence-producing work without
expanding #65's Java/Truffle parity claim. Keep this umbrella open only for the
ADR-029/PR #58 disposition and any explicitly chosen upstream-follow-up work.
