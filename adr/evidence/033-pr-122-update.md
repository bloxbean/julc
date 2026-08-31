# Draft PR #122 title and body

## Suggested title

`feat(vm-scalus): implement ADR-033 target-aware evaluation and pin certification blockers`

## Suggested body

### Summary

- implement atomic, target-bound Scalus configuration and the canonical
  `LedgerEvaluationTarget` candidate pipeline;
- enforce CPU/memory limits and map Scalus exhaustion to `BudgetExhausted`;
- complete Array/Value result conversion and replace the disabled runner with
  a fixed-count V3/PV10/C + V3/PV11/E conformance matrix;
- audit V1/V2 supplied-cost behavior and keep it explicitly unsupported;
- keep `CERTIFIED_TARGETS` empty because five committed upstream divergences
  fail the runtime-semantics gate;
- update ADR-030/ADR-033 and include ready-to-post issue/upstream drafts.

### Resulting support matrix

| Target | Result |
|---|---|
| V3/PV10/C | Candidate only; 482/482 supplied numeric budgets exact; blocked by high-byte DST and SliceByteString narrowing |
| V3/PV11/E | Candidate only; 617/617 supplied numeric budgets exact; blocked by all five reason codes |
| V1/V2 | Explicitly unsupported; no pinned corpus and PV11 reference-fill/ignored supplied costs |

The bundled epoch-645 snapshot is exact for V3/PV11/E but remains unavailable
until target certification. It is not exact for V3/PV10/C: 445/482 executable
budgets match and 37 differ because of seven snapshot coefficients.

### Safety properties

- public explicit-target calls remain deterministic zero-budget failures;
- target and cost model are published as one immutable snapshot;
- target/model mismatches and unsupported profiles fail before FLAT/CEK work;
- no corpus golden was changed and no semantic mismatch is hidden by a skip;
- runtime bounds are not partially reimplemented in the adapter;
- legacy configured V3 remains compatible, while configured V1/V2 now fail
  explicitly instead of silently ignoring supplied costs.

### Validation

- `:julc-vm-scalus:test --rerun-tasks --no-daemon`: 297/0/0/0 after M7.
- Affected VM suites: `julc-vm` 91/0/0/0; `julc-vm-java`
  2,439/0/0/262; `julc-vm-truffle` 3,486/0/0/262; `julc-vm-scalus`
  297/0/0/0.
- Consumer suites: `julc-testkit` 191/0/0/0 and
  `julc-cardano-client-lib` 204/0/0/0.
- `./gradlew build --no-daemon`: successful; final JUnit XML total
  10,649 tests, 0 failures, 0 errors, 531 intentional skips. Per-module totals
  are retained in `adr/evidence/033-build-validation.md`.

Tracks #74. Related: #65 and #121. Issue #40 remains independent and
non-blocking.
