# Milestone E.4c — typed rewarding DSL

This directory demonstrates the experimental schema-3 rewarding surface from
[ADR-020](../../adr/verification/020-milestone-e4c-typed-rewarding-dsl.md).
It verifies exact JuLC rewarding UPLC selected through CIP-57 `withdraw`.

The property requires:

1. strict redeemer decoding;
2. the configured authority signer; and
3. at least one raw withdrawal association-list entry for the current
   rewarding credential whose amount is at least 1,000,000.

Withdrawal traversal is structural. Duplicate credential entries are not
collapsed. `exists` means one matching entry is sufficient; it does not prove
uniqueness, a summed amount, or extensional map equality.

Expected controls:

| Validator | Expected result |
|---|---|
| `AuthorizedRewards` | `SMT-VALID` |
| `MissingAuthorityRewards` | `REFUTED` |
| `UnboundedRewards` | `REFUTED` |
| `VacuousRewards` | `COULD-NOT-EVALUATE/property-vacuous` |

Run locally:

```bash
verification/e4c/scripts/verify.sh
```

Run through the Docker proof backend:

```bash
E4C_BACKEND=docker verification/e4c/scripts/verify.sh
```

For native-image acceptance, build with GraalVM 25.0.2 and invoke
`julc-cli/build/native/nativeCompile/julc verify dsl` with the same validator,
purpose, specification class/path, source, fuel, and an
`authorized-native` output directory. The native executable still launches
the trusted specification in an installed child JVM. The accepted E.4c run
binds the same exact UPLC, canonical DSL IR, property IR, generated Lean, and
execution bounds as the local and `authorized-docker` workspaces.

The Java specification is trusted project code executed in a bounded worker
JVM. The proof result covers only the named property, exact artifact, pinned
model and tools, and recorded CEK/solver bounds. It is not a claim that the
contract is safe.
