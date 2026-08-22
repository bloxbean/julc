# E.4a typed minting DSL evidence

ADR-018 adds the first typed minting-property slice. A developer writes a
trusted Java property specification; JuLC generates and validates a closed
schema-2 IR, selects the exact minting interface, generates Lean, runs the
pinned Blaster stack, and publishes a certificate. No raw Lean is accepted by
the DSL.

The committed property is:

```text
validMintingContext && exactUplcSucceeds ->
  redeemerStrictlyDecodes
  && authority is in the complete signatory list
  && the configured anchor TxOutRef is consumed
  && the current policy has exactly the configured token with quantity 1
```

`validMintingContext` is represented in SMT by a reviewed superset that omits
only pinned clauses the translator cannot handle. An ordinary Lean theorem
kernel-checks that every pinned ledger-valid minting context belongs to this
superset. A successful result is therefore meaningful for ledger-valid
contexts. A refutation is conservatively labeled as a solver-domain
counterexample unless full ledger validity is separately established.

## Try it from this checkout

Run all Java tests and the local positive/refuted/vacuous evidence:

```bash
verification/e4a/scripts/verify.sh
```

The driver builds the CLI, generates the disposable metamodel, compiles
`OneShotMintSpec.java`, requires an intentionally strengthened ledger-domain
bridge to fail kernel elaboration, and verifies these controls:

| Fixture | Expected result | Purpose |
|---|---|---|
| `authorized` | `SMT-VALID` | Checks authority, anchor, and exact asset |
| `missing-anchor` | `REFUTED` | Accepts without requiring the anchor |
| `missing-authority` | `REFUTED` | Accepts without requiring authority |
| `wrong-asset` | `REFUTED` | Requires authority/anchor but accepts arbitrary mint assets |
| `vacuous` | `COULD-NOT-EVALUATE/property-vacuous` | Never succeeds |

Generated workspaces and dependency caches live under
`verification/e4a/generated/` and are gitignored.

The reviewed positive artifact is 632 bytes and uses CEK fuel 5000. On the
2026-08-20 arm64 macOS review machine, a clean local workspace spent 9.2s
acquiring dependencies, 2m50s building them, 19.0s checking non-vacuity, and
5.8s proving the property. A warm local run reduced the dependency build to
under one second. The Docker positive used image
`sha256:e4fd68fd9a03e1d91bd7af14dc2cdb149a7f3e98600e5934447aef005b7df4da`
and spent 14.7s acquiring dependencies, 4m44s building them, 34.4s checking
non-vacuity, and 23.0s proving. These timings are diagnostics, not semantic
certificate inputs.

## Use it in a JuLC project

Generate the minting metamodel:

```bash
julc verify dsl-init . --validator TokenPolicy --purpose minting \
  --package evidence --class TokenPolicyModel \
  --out build/verification-dsl/src/evidence/TokenPolicyModel.java
```

Write a trusted specification like `verification/e4a/OneShotMintSpec.java`,
then compile both it and the generated model:

```bash
javac -cp /path/to/julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/TokenPolicyModel.java \
  OneShotMintSpec.java
```

Run locally:

```bash
julc verify dsl . --validator TokenPolicy --purpose minting \
  --spec-class evidence.OneShotMintSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source OneShotMintSpec.java --backend local --fuel 5000 --force
```

Or use Docker for Lean/Blaster without installing them on the host:

```bash
julc verify dsl . --validator TokenPolicy --purpose minting \
  --spec-class evidence.OneShotMintSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source OneShotMintSpec.java --backend docker --fuel 5000 --force
```

Including the JuLC JAR in `--spec-classpath` is required when `julc` is the
GraalVM native executable because the trusted property builder still runs in a
bounded JVM. It is harmless for the JVM CLI. Replace `:` with the platform
classpath separator on Windows.

The Java property worker still executes locally in both cases and must be
treated as trusted source. Docker isolates the proof backend, not the property
builder.

For a mixed `@MultiValidator`, `--validator` is the base class name and
`--purpose minting` is mandatory. The selected `.mint` blueprint entry shares
compiled code and script hash with the other purpose entries, while its schema
and certificate identity remain purpose-specific.

## Raw mint semantics and limits

Mint values remain raw ordered association lists. The admitted
`exactOwnPolicyAsset` predicate filters by the raw current-policy key and then
requires exactly one matching policy entry containing exactly one token entry.
Duplicate current-policy entries, duplicate token entries, malformed matching
values, wrong tokens, and wrong quantities reject. Entries for other policies
are permitted. This is deliberately stronger and safer than first-match map
lookup.

E.4a supports only the reviewed controlled-mint and one-shot shapes. It does
not expose arbitrary assumptions, raw Lean, general value equality, token
folds, quantity ranges, validator parameters, or other script purposes. The
positive fixture has both solver-domain non-vacuity and a separate concrete VM
success test; it does not claim that the VM fixture itself is a fully
ledger-valid witness. The general-purpose runner does not execute or bind that
repository-only VM test, so its certificate field
`concreteVmSuccessfulWitnessReproduced` remains `false`.
