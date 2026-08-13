# Milestone C.6 stateful-spending evidence

This suite exercises `julc.stateful-spending/v1` from Java annotations through
the exact generated UPLC, Lean ledger predicate, Blaster result, and bound
certificate. The profile requires all three annotations:

```java
@RequiresSigner("datum.owner")
@Monotonic(current="datum.state", next="redeemer.nextState",
    relation=Relation.GREATER_THAN)
@PreservesValue(output=OutputSelection.SINGLE_CONTINUING_OUTPUT)
```

Run from the repository root:

```bash
verification/c6/scripts/verify.sh
```

The conforming fixture uses fuel 3,000, the first tested bound at which its
non-vacuity obligation succeeds. Its exact artifact is `SMT-VALID`. The
missing-signer, decreasing-transition, and value-leak controls are deliberately
broad always-accepting implementations: each admits the named threat (and
others), and each must be `REFUTED`. The always-failing control must be
`COULD-NOT-EVALUATE/property-vacuous`, with the main theorem skipped.

This is not whole-contract or ledger-validity certification. The profile uses
structural `Value` equality, permits unrelated transaction outputs, does not
prove global one-to-one linkage across several state inputs, and covers only
executions completing within the recorded CEK fuel.
