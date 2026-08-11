# JuLC Blaster Verification PoC

This directory implements Milestones A and B of the JuLC verification strategy:

- [`ADR-002`](../../adr/verification/002-milestone-a-blaster-poc.md) establishes
  exact-artifact compatibility; and
- [`ADR-003`](../../adr/verification/003-milestone-b-useful-contract-verification.md)
  establishes bounded, production-shaped spending and minting properties.

It compiles dedicated validators through JuLC's production CLI, exports the
exact double-CBOR blueprint artifacts, validates their script hashes and UPLC
builtin inventories, and builds a pinned Lean/Blaster project.

## Required tools

- JDK 25
- Lean 4.24.0 and Lake
- Z3 4.15.2
- `jq`

`scripts/bootstrap-z3.sh` installs the pinned Z3 release into the ignored
`.tools/` directory after checking its SHA-256 checksum. Lean can be installed
with `elan`; `lean-toolchain` selects the exact version.

All three Blaster dependencies are pinned in `lakefile.lean`; the resolved
`lake-manifest.json` must also remain committed.

## Commands

Prepare and compare artifacts with the committed lock:

```bash
verification/blaster/scripts/prepare-artifacts.sh
```

Intentionally refresh the artifact lock after a reviewed compiler or fixture
change:

```bash
verification/blaster/scripts/prepare-artifacts.sh --update-lock
```

Run the complete available verification:

```bash
verification/blaster/scripts/verify.sh
```

Prepare all pinned dependencies, then reproduce evidence without dependency
downloads:

```bash
verification/blaster/scripts/acquire-dependencies.sh
verification/blaster/scripts/verify-offline.sh
```

The drivers fail closed with exit code 2 and a `COULD-NOT-EVALUATE` message
when tool versions, artifacts, builtin coverage, or verification prerequisites
do not match the pinned profile.

## Milestone B evidence

- Production JuLC compilation fixtures: implemented.
- Exact-title blueprint export and independent script-hash check: implemented.
- UPLC builtin inventory and pinned coverage gate: implemented.
- State-thread owner authorization, strict state increase, raw value
  preservation, and output-reference commitment: `SMT-VALID`.
- State-thread double-satisfaction composition: `KERNEL-PROVED`.
- Controlled-mint authority and exact singleton mint shape: `SMT-VALID`.
- Own-policy singleton composition: `KERNEL-PROVED` under the ledger currency
  membership premise.
- Correct-fixture non-vacuity controls and both vulnerable mutations:
  counterexamples found as expected.
- Counterexample metadata is bound to the committed UPLC and script hashes.
- CI separates pinned acquisition from the offline evidence phase and pins all
  actions by commit SHA.

The first-element transaction discipline is part of the verified contract
domain. This suite does not establish compiler-wide correctness or all Cardano
ledger validity rules. The Milestone A recursive multisig claim remains a
non-gating `COULD-NOT-EVALUATE` legacy result.

Blaster's current solver-valid results trust Z3 and its SMT translation. They
must be reported as `SMT-VALID`, not `KERNEL-PROVED`.

## Important implementation findings

- JuLC record decoding currently permits unexpected constructor tags and
  trailing fields.
- Several typed ledger accessor/coercion expressions did not enforce the full
  source-level equality suggested by their Java types. Milestone B uses raw
  `PlutusData` at those representation boundaries.
- `BigInteger.compareTo(constant) == 0` on decoded constructor tags produced an
  unsatisfiable artifact path during the state-thread iteration. Comparing the
  complete expected `PlutusData` structure restored non-vacuity and made the
  intended constraint explicit.
- Lake does not track `#import_uplc` hex files as ordinary source dependencies.
  The driver directly recompiles every artifact-importing Lean module so stale
  `.olean` files cannot silently certify older bytes.
