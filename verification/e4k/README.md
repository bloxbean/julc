# Experimental E.4k typed governance transaction-data evidence

This evidence exercises schema 9 over transaction votes and proposals while
verifying an ordinary spending artifact. It does **not** add voting or
proposing script-purpose verification.

The positive theorem states the non-tautological condition that every accepted
context has a first proposal deposit of at least 10. The exact validator also
requires that proposal to look like a hard-fork initiation for major protocol
version 11 and a public-key return credential. That richer strict-action
theorem is deliberately not promoted as positive evidence: the pinned
non-proposing valid-context domain does not globally validate proposal action
data, and a malformed-action calibration produces a conservative Blaster
counterexample. The vulnerable fixture accepts every context; the vacuous
fixture accepts none.

Run locally:

```bash
bash verification/e4k/scripts/verify.sh
```

Run the positive workflow in Docker:

```bash
E4K_BACKEND=docker bash verification/e4k/scripts/verify.sh
```

The retained positive local, Docker, and GraalVM-native runs bind identical
compiled artifact, DSL IR, property IR, and generated Lean hashes. The native
run uses the same Java specification worker boundary as earlier typed-DSL
milestones.

`ChangedParameters` and `Quorum` remain raw and inaccessible. Vote maps retain
their list-backed order and duplicates. Results remain relative to the pinned
model, selected valid-context bridge, fuel, recursive depth, and exact script.
