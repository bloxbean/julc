# Draft update for issue #74

## Implementation outcome

ADR-033 and its eight milestones are implemented on PR #122. The outcome is a
reviewed fail-closed matrix, not a Scalus ledger-parity claim.

Completed work includes:

- immutable, atomic per-language configuration records;
- canonical registry/program validation before the Scalus bridge;
- one plain/argument-bearing explicit-target candidate pipeline;
- target/model mismatch and unsupported-profile failures with zero budget;
- enforced `ExBudget` limits and `BudgetExhausted` mapping;
- V3/PV10/C and V3/PV11/E availability/semantics tests;
- Array and Value result conversion;
- a no-skip, content-classified 999-case runner using pinned C/E cost vectors;
- a direct V1/V2 supplied-cost and fallback audit;
- detailed provider/ADR documentation and ready-to-file upstream reproducers.

## Final support matrix

| Target | Status | Evidence |
|---|---|---|
| V3/PV10/C | Candidate, public gate closed | Supplied costs 482/482 exact; blocked by high-byte DST and SliceByteString narrowing |
| V3/PV11/E | Candidate, public gate closed | Supplied costs 617/617 exact; blocked by all five reason-coded upstream divergences |
| V1/V2 PV10/PV11 | Explicitly unsupported | No pinned corpus; PV11-only costs are reference-filled or ignored |
| Above PV11 | Unsupported | Canonical registry rejects it |

The bundled Scalus 1.1.0 snapshot is exact for PV11/E but cannot be enabled
before target certification. It is not exact for PV10/C (37 budget mismatches),
so an eventual PV10 target still requires configured matching parameters.

## Verification

- `:julc-vm-scalus:test --rerun-tasks --no-daemon`: 297 tests, 0 failures,
  0 errors, 0 skipped after Milestone 7.
- Final repository-wide build totals are recorded in the PR #122 draft and
  ADR-033 after Milestone 8 validation.

## Resolution recommendation

Close #74 when PR #122 merges: its scope explicitly allowed certification
gates to fail while retaining fail-closed behavior and recording the blockers.
Track actual Scalus certification through the upstream issue(s) and a focused
follow-up after a fixed, reviewed Scalus release exists. If the project instead
wants #74 to remain the upstream-watch issue, leave it open but mark all ADR-033
implementation milestones complete; do not treat it as a pre17 code blocker.
