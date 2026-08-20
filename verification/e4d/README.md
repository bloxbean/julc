# Milestone E.4d — typed certifying DSL

This directory demonstrates the experimental schema-3 certifying surface from
[ADR-021](../../adr/verification/021-milestone-e4d-typed-certifying-dsl.md).
It verifies exact JuLC certifying UPLC selected through CIP-57 `publish`.

The property requires strict redeemer decoding, an `UpdateDRep` current
certificate, the current certificate at the ledger-provided index in the raw
ordered transaction certificate list, and the configured authority signer.
Certificate list membership is structural: order and duplicates are retained.

Expected controls:

| Validator | Expected result |
|---|---|
| `AuthorizedCertificates` | `SMT-VALID` |
| `MissingAuthorityCertificates` | `REFUTED` |
| `AnyCertificate` | `REFUTED` |
| `VacuousCertificates` | `COULD-NOT-EVALUATE/property-vacuous` |

Run locally:

```bash
verification/e4d/scripts/verify.sh
```

Run the positive proof through Docker:

```bash
E4D_BACKEND=docker verification/e4d/scripts/verify.sh
```

For native-image acceptance, build with GraalVM 25.0.2 and invoke
`julc-cli/build/native/nativeCompile/julc verify dsl` with the same absolute
project, specification classpath/source, purpose, fuel, and an
`authorized-native` output directory. The native executable launches the
trusted property builder in an installed child JVM. The accepted local,
Docker, and native runs bind identical exact UPLC, canonical DSL IR, property
IR, generated Lean, and execution bounds.

The Java specification is trusted project code executed in a bounded worker
JVM. A result covers only the named property, exact artifact, pinned model and
tools, and recorded execution bounds. It is not a claim that the whole
contract is safe.
