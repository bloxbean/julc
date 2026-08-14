# Milestone C.7 controlled-mint evidence

This suite exercises `julc.controlled-mint/v1`. A Java annotation fixes a
28-byte authority, token name, positive magnitude, and `MINT` or `BURN`
direction. The generated property links the exact artifact to the policy ID in
`MintingScript`, requires the authority anywhere in the complete signatory
list, strictly decodes the redeemer, and requires exactly one raw token entry
under exactly one raw entry for the current policy. Other policies are allowed.

Run from the repository root:

```bash
verification/c7/scripts/verify.sh
```

Both conforming mint and burn fixtures are concrete-VM-tested, non-vacuous, and
`SMT-VALID` at fuel 5,000. The three broad always-accepting controls admit
unauthorized, wrong-asset, and wrong-quantity cases and must be `REFUTED`. The
always-failing policy must stop as `COULD-NOT-EVALUATE/property-vacuous`.

The authority and token are property literals, not untrusted redeemer choices.
The annotation does not change UPLC. Results remain fuel-bounded and do not
assume ledger-valid map normalization or claim that the entire policy is safe.
