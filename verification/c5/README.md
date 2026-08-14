# Milestone C.5 evidence

This directory exercises the Java-only `@RequiresSigner("datum.owner")`
vertical slice described by
[ADR-011](../../adr/verification/011-milestone-c5-requires-signer.md).

Run all three controls with the local pinned Lean/Blaster toolchain:

```bash
verification/c5/scripts/verify.sh
```

The authorized validator must be `SMT-VALID`, the vulnerable validator must be
`REFUTED` with a retained Blaster model, and the always-failing validator must
be classified as vacuous. Generated workspaces and build outputs are
reproducible evidence and are ignored by Git.

The specialized manifest locks the typed property IR, exact compiled artifact,
runner scripts, and generated Lean source tree. The managed runner rejects a
changed property or theorem before invoking Lean.

`SMT-VALID` is bounded by the CEK `fuel` recorded in the certificate. Execution
paths that exhaust that fuel are not covered by the established property.

These controls use `--backend local` and do not require Docker. The optional
Docker backend has also completed a full C.5 proof run with the pinned image,
workspace-only mount, and `--network none` proof phase.
