# Milestone E.4i — guarded certificate payloads

This directory contains reproducible evidence for the schema-7 portion of
[ADR-025](../../adr/verification/025-milestones-e4i-e4j-certificate-payloads-and-value-algebra.md).
The DSL exposes all 11 pinned V3 `TxCert` payload shapes plus nested `Delegatee`
and `DRep` sums through guarded eliminators. It does not change validator UPLC.

The positive property proves that every successful execution of the exact
`AuthorizedDRepRegistration` certifying validator selects a DRep-registration
certificate whose deposit payload equals one. The vulnerable validator
checks only the outer constructor and is expected to be refuted. The
always-false validator is the vacuity control. A separate exact-VM test retains
pool-retirement pool/epoch payload coverage, including malformed inputs.

Run local evidence with:

```bash
verification/e4i/scripts/verify.sh
```

Run the positive control through Docker with:

```bash
E4I_BACKEND=docker verification/e4i/scripts/verify.sh
```

For the native launcher, build `:julc-cli:nativeCompile` with GraalVM 25.0.2
and invoke the same `julc verify dsl` command with the generated schema-7 model,
specification classpath, certifying purpose, fuel 5000, recursive depth 4, and
an `authorized-native` output directory. The native executable still needs an
installed JVM to execute the trusted Java property builder.

Certificate payload access is strict and guarded. Wrong tags, arities, payload
kinds, or use outside the matching eliminator fail closed. Credential aliases
for DRep and committee roles share the pinned credential representation but
retain role-specific Java wrappers and parent validation. An `SMT-VALID` result
covers only the named implication, exact artifact, selected pinned domain,
recorded bounds, and pinned tools; it is not a whole-contract safety claim.

The retained local outcomes are `SMT-VALID` for the registration-deposit
property, `REFUTED` for the constructor-only validator, and
`COULD-NOT-EVALUATE/property-vacuous` for the always-failing validator. JVM,
Docker, and GraalVM-native launchers bind the same compiled-code, script, DSL
IR, property IR, and generated Lean hashes recorded in ADR-025.

Calibration deliberately did not promote the richer nested-credential or
pool/epoch examples to positive SMT evidence: despite concrete VM successes,
their non-vacuity queries were vacuous at the attempted fuel bounds. Their
semantics remain kernel- and VM-tested, and the solver limitation is reported
instead of weakening the formula.
