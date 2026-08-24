# Experimental E.4l reviewed raw-data adapter evidence

Schema 10 adds closed, reviewed views over raw fields in the pinned ledger
model. It does not expose arbitrary `Data` or permit user-selected Lean
decoders.

The positive property composes three independently reviewed meanings:

- the transaction validity range contains the deadline decoded from the
  contract datum;
- a fixed contract authority signed the transaction; and
- exact UPLC execution succeeds under the pinned spending-domain bridge.

Run the full local evidence matrix:

```bash
bash verification/e4l/scripts/verify.sh
```

Run the positive workflow through Docker:

```bash
E4L_BACKEND=docker bash verification/e4l/scripts/verify.sh
```

The retained positive JVM, Docker, and GraalVM-native certificates bind the
same artifact, canonical DSL IR, property IR, and generated Lean tree.

The separate treasury calibration is deliberately `REFUTED`. The exact Java
validator checks `currentTreasuryAmount == Some(100)` and
`treasuryDonation == None`, but the pinned model's treasury boundary is weaker
than the reviewed strict optional decoder. This result is evidence of that
model discrepancy, not evidence that the time-and-authority theorem failed.

Changed-parameter values remain opaque: only integer keys, order, duplicates,
and counts are visible. Quorum exposes normalized exact-rational semantics;
decoder-valid, canonical encoding, and Conway unit-interval validity remain
separate predicates. The base raw fields remain classified `RAW_DATA_ONLY`.
