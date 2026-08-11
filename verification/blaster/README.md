# JuLC Blaster Verification PoC

This directory implements Milestone A of
[`ADR-002`](../../adr/verification/002-milestone-a-blaster-poc.md).

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

The driver fails closed with exit code 2 and a `COULD-NOT-EVALUATE` message
when tool versions, artifacts, builtin coverage, or verification prerequisites
do not match the pinned profile.

## Current implementation status

- Production JuLC compilation fixtures: implemented.
- Exact-title blueprint export and independent script-hash check: implemented.
- UPLC builtin inventory and pinned coverage gate: implemented.
- Checked CEK result preserving step exhaustion: scaffolded.
- Smoke artifact property and schema-shape counterexample: implemented.
- Broken multisig counterexample with a two-key datum: implemented.
- Correct multisig authorization: solver-undetermined at the pinned timeout;
  reported as `COULD-NOT-EVALUATE`.
- CI workflow: pending successful local Lean build.

Blaster's current solver-valid results trust Z3 and its SMT translation. They
must be reported as `SMT-VALID`, not `KERNEL-PROVED`.
