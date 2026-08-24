# E.5 exact-artifact state-machine calibration

E.5 was stopped at the mandatory calibration gate in
[ADR-028](../../adr/verification/028-milestone-e5-exact-artifact-state-machine-experiment.md).
No state-machine Java API or CLI command is shipped.

The minimum calibration used a 396-byte spending validator whose Java body
returns true, typed record datum and redeemer, no ledger-validity premise,
depth 1, 3,000 CEK fuel per step, and a five-minute process timeout. Strict
generated boundaries remain part of the artifact. The generated Lean
workspace and pinned dependencies compiled. The first target-depth
reachability query timed out, so BMC and k-induction were not run.

This result means direct symbolic exact-CEK composition is not currently a
useful temporal-verification backend for JuLC. It does not mean state-machine
properties are impossible in principle. A future design must establish a
reviewed transition summary or another scalable link to exact UPLC and must be
accepted under a new ADR.

The machine-readable calibration record is
[`calibration-result.json`](calibration-result.json).

## Retained audit bundle

[`generated/authorized-local`](generated/authorized-local) retains the exact
compiled artifact, canonical property IR, generated Lean transition and
reachability query, dependency pins, hash-bound scripts, runner plan, manifest,
and classified result. Downloaded `.lake` dependencies, provisioned Z3 files,
and transient logs are intentionally excluded.

To inspect the same query with Lean 4.24.0 and Z3 4.15.2:

```bash
cd verification/e5/generated/authorized-local
lake update
lake build @PlutusCore/PlutusCore \
  @CardanoLedgerApi/CardanoLedgerApi @Blaster/Blaster \
  GeneratedVerificationSupport
lake env lean StateMachineReachability.lean
```

The final command requires an external five-minute process limit to reproduce
the recorded timeout; it is expected not to complete within that bound. The
bundle is retained for audit, not as a supported `julc verify` workflow.
