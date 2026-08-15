# ADR-015 strict-boundary evidence

This directory records the reproducible evidence for
[ADR-015](../../adr/verification/015-strict-on-chain-data-boundaries.md).
Strict datum/redeemer validation is compiler behavior; it does not depend on
Lean or on a verification annotation.

Run the complete evidence suite from the repository root:

```bash
verification/strict-boundaries/scripts/verify.sh
```

The suite checks:

- canonical and malformed VM inputs for records, variants, primitives,
  lists, maps, optionals, direct/mutual/container recursion, and spending
  datum handling;
- duplicate map entries and opaque `PlutusData` boundaries;
- root-destructor sharing, guard-before-user-trace ordering, source-located
  unsupported-type/standalone-variant failures, strict multi-validator
  auto-dispatch, and the absence of an executable legacy compiler mode;
- the changed/unchanged UPLC golden inventory;
- large/container-heavy cost ceilings;
- C.3 recursive Lean codec round trips, malformed rejections, and induction;
- the C.5, C.6, and C.7 positive, refuted, and vacuous Blaster controls using
  exact strict artifacts.

[`golden-manifest.json`](golden-manifest.json) classifies every pre-existing
compiler golden. [`measurements.json`](measurements.json) records the local
size, CEK-budget, fuel, and wall-time observations used during activation.
Wall time is machine-dependent and is evidence from one run, not an API
guarantee; the executable regression ceilings live in `StrictBoundaryCostTest`.
Legacy values in the evidence files are immutable observations produced before
the temporary comparison path was deleted. The current compiler cannot
regenerate permissive artifacts; use the corresponding historical compiler
version when exact reproduction is required.

The positive C.5-C.7 Java fixtures intentionally contain no handwritten
datum/redeemer root-shape checks. Their property-specific transaction checks
remain—for example, a successor output datum is not an entrypoint boundary and
must still be validated by the contract/property.
