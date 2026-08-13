# Pinned ledger capability inventory

JuLC verification classifies the CardanoLedgerApi V3 surface pinned by the
verification evidence suite. The machine-readable inventory is bundled in the
`julc-verification` artifact at:

```text
com/bloxbean/cardano/julc/verification/cardano-ledger-api-v3-capabilities.json
```

Each entry distinguishes typed property support from raw-`Data`, missing
property IR, and missing solver support. `LedgerCapabilityCompatibilityGate`
checks the full dependency revision and the audited Lean declaration
signatures. An upstream revision or signature change therefore fails closed
until the inventory is reviewed.

Run the gate and its regression tests with:

```bash
./gradlew :julc-verification:test \
  --tests '*LedgerCapabilityInventoryTest'
```

The inventory is a coverage statement, not a verification certificate.
`TYPED` means JuLC has a typed representation for that surface; it does not
mean every property using it is decidable or that the upstream ledger model is
complete.
