# Milestone E.4g — typed non-value transaction context

This directory is reproducible evidence for
[ADR-023](../../adr/verification/023-milestone-e4g-typed-non-value-transaction-context.md).
Experimental property schema 5 adds a closed, parent-validated view of the
pinned Cardano ledger context without changing JuLC compilation or UPLC.

The authorized property composes reference-input traversal, complete payment
credential and output-datum checks, optional reference-script state, integer
fee comparison, ordered datum/redeemer maps, the complete current script
purpose, plus separately tested own-input resolution and continuing outputs
selected by full address equality. The vulnerable and vacuous fixtures retain the expected REFUTED and
COULD-NOT-EVALUATE controls.

Association maps remain ordered lists with observable duplicate keys.
`lookupFirst`, `lookupAll`, counts, and structural equality are distinct.
Raw datum/redeemer payloads remain opaque: schema 5 does not reinterpret them
as contract records and does not admit raw-Data equality.

The generated Lake project also compiles `LedgerContextSemanticsTests.lean`.
Those kernel-reduced controls pin first-match input resolution, credential
filters, complete-address distinction, strict output-datum constructors,
missing own input, full-address continuing-output selection, complete
`ScriptInfo.toScriptPurpose` conversion for spending, first/all/count
`ScriptPurpose` lookup, and preservation of opaque voting keys. The authorized
solver evidence additionally establishes that the complete current purpose is
the spending constructor; the generic redeemer-map witness claim remains
separate. The CLI exact VM suite separately executes the committed validator
against canonical and malformed raw V3 contexts.

Run local evidence:

```bash
verification/e4g/scripts/verify.sh
```

Run the positive control through Docker:

```bash
E4G_BACKEND=docker verification/e4g/scripts/verify.sh
```

For the native launcher, build with GraalVM 25.0.2 and invoke
`julc-cli/build/native/nativeCompile/julc verify dsl` with the same absolute
project, specification source/classpath, purpose, fuel, recursion depth, and an
`authorized-native` output directory. The native executable still launches
the trusted property worker in an installed child JVM. The native certificate
records the authenticated `local` proof backend, not the launcher flavor or a
native-executable digest.

The reviewed positive artifact uses fuel 5000 and recursive depth 8. Local,
Docker, and native runs bind identical exact UPLC, script hash, canonical DSL
IR, property IR, and generated Lean hashes. The vulnerable control is
`REFUTED`; the always-failing control is
`COULD-NOT-EVALUATE/property-vacuous`.

The Java specification is trusted project code executed in a bounded worker.
Results cover only the named properties, exact script artifacts, recorded fuel,
and pinned tool/model revisions; they are not whole-contract safety claims.
