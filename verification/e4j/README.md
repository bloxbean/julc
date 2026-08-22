# Milestone E.4j — explicit multi-asset Value algebra

This directory contains reproducible schema-8 evidence for
[ADR-025](../../adr/verification/025-milestones-e4i-e4j-certificate-payloads-and-value-algebra.md).
The value DSL keeps raw structure, upstream first-match lookup, strict summed
lookup, and finite-support extensional relations as different canonical IR
nodes. It does not change validator UPLC.

Run JVM evidence with:

```bash
verification/e4j/scripts/verify.sh
```

Run the positive control with Docker using:

```bash
E4J_BACKEND=docker verification/e4j/scripts/verify.sh
```

For the native launcher, build `:julc-cli:nativeCompile` with GraalVM 25.0.2
and invoke `julc-cli/build/native/nativeCompile/julc verify dsl` with the same
validator, purpose, property class/classpath, source, fuel 5000, recursive
depth 4, and an `authorized-native` output directory. The native CLI still
launches a bounded Java worker to execute the trusted Java property builder;
ordinary annotation discovery does not require that worker. The retained
native positive run binds the same compiled code, script hash, canonical DSL
IR, property IR, and generated Lean as the JVM and Docker runs.

The positive exact-artifact theorem uses the explicitly named upstream
`quantityFirst` meaning. Strict-summed lookup, malformed rejection, duplicate
summation, raw order, zero-sum extensional equality, checked arithmetic, Ada
placement, and pointwise order are kernel-checked in
`ValueAlgebraSemanticsTests.lean`. They are not silently presented as solver
evidence when the recursive formulas are impractical for the pinned solver.
Run the strict-summed calibration independently with:

```bash
E4J_CALIBRATE=strict verification/e4j/scripts/verify.sh
```

Use `E4J_CALIBRATE=extensional` for the whole-value extensional calibration.
The retained implementation run established non-vacuity in 2m42s, but the
proof itself did not finish within a ten-minute calibration ceiling. It is
therefore a bounded solver limitation and is not presented as `SMT-VALID`.
Calibration results do not replace the non-tautological first-match theorem.

`context.isBalanced()` is labeled `domain-implied` under a selected valid V3
context; proving it does not show that validator logic enforces balance.
Complete-address and payment-credential output filters are distinct APIs and
dependency rules. Transaction-local payment claims retain
`globalMultiInputLinkageModeled: false`; they do not by themselves exclude
multi-satisfaction.
