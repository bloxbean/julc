---
title: "Typed Java DSL"
description: "Compose schema-10 JuLC verification properties over contract and Cardano ledger data"
---

:::caution[Experimental verification feature]
The DSL API and schema-10 meanings are stable as API v1, but the verifier,
compiler, ledger model, and solver integration remain experimental.
:::

The typed DSL workflow generates a contract metamodel, compiles a trusted Java
specification, and runs the bounded worker and verifier. Start with the
[formal-verification overview](../) for backend setup, trust boundaries,
outcomes, fuel, and CI guidance.

## Supported composition

The stable schema-10 DSL supports freely composed, admitted expressions over:

- compiler-projected datum and redeemer records, sealed variants, optionals,
  lists, maps, nested values, and productive recursion;
- spending, minting, rewarding, and certifying contexts;
- ordered transaction inputs, reference inputs, outputs, signatories,
  certificates, withdrawals, datums, and redeemers;
- duplicate-preserving list and association-map operations;
- distinct-identity authorization relations;
- certificate payloads, V3 governance transaction data, and reviewed raw-data
  adapters;
- explicit first-match, strict-summed, structural, and extensional multi-asset
  value meanings; and
- closed ledger-domain choices for the pinned CardanoLedgerApi model.

Voting and proposing validator **selection** is not supported yet. Properties
for those purposes fail closed rather than borrowing a misleading blueprint
interface. Arbitrary Lean text and user-defined AST nodes are not part of the
DSL.

## Use one exact JuLC version

Use one exact JuLC version for the compiler, `julc-verification`, generated
metamodel, property worker, and executing CLI. The commands below use the JVM
shadow JAR built from a source checkout so one artifact supplies both the CLI
and worker classpath:

```bash
JULC_SOURCE=/absolute/path/to/julc
"$JULC_SOURCE/gradlew" -p "$JULC_SOURCE" :julc-cli:shadowJar
JULC_JAR="$JULC_SOURCE/julc-cli/build/libs/julc.jar"
cd /absolute/path/to/your-contract-project
```

The Homebrew native executable does not currently install this worker JAR. A
native-CLI DSL run therefore still needs a matching source-built shadow JAR or
a complete runtime classpath containing the same-version
`julc-verification` artifact and its dependencies.

## 1. Generate the schema-10 model

```bash
java -jar "$JULC_JAR" verify dsl-init . \
  --validator AuthorizedStateValidator \
  --purpose spending \
  --package verification \
  --class AuthorizedStateModel \
  --out build/verification-dsl/src/verification/AuthorizedStateModel.java
```

Schema 10 is the API-v1 default and the only schema covered by the API-v1
canonical-semantics freeze. `--schema-version 3` through `9` remains available
for compatibility and evidence reproduction. Historical certificates remain
hash-bound records, but an old workspace can require regeneration when the
reviewed capability inventory or dependency pins change. Generation refuses to
overwrite the output file.

For an explicit `@MultiValidator`, `--validator` is the base Java class and
`--purpose` selects one exact interface. Supported purpose values are
`spending`, `minting`, `rewarding`, and `certifying`.

## 2. Implement `VerificationSpecification`

```java
package verification;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

public final class AuthorizedStateSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new AuthorizedStateModel();
        var authorized = contract.datum().exists(datum ->
            contract.context().txInfo().signatories().contains(datum.owner()));

        return contract.properties(property(
            "authorized-state.owner-signed",
            DslDomain.NONE,
            authorized));
    }
}
```

The generated model supplies the purpose, contract-schema hash, nominal types,
and field accessors. Prefer its `contract.properties(...)` factory over
constructing property envelopes manually.

`VerificationSpecification` executes project Java. Treat specification source
and every class on `--spec-classpath` as trusted code. The worker is bounded and
the parent process revalidates its returned closed AST, but it is not a sandbox
for hostile Java.

## 3. Compile and verify

The JVM CLI shadow JAR contains the stable DSL API and its dependencies:

```bash
mkdir -p build/verification-dsl/classes

javac -cp "$JULC_JAR" \
  -d build/verification-dsl/classes \
  build/verification-dsl/src/verification/AuthorizedStateModel.java \
  AuthorizedStateSpec.java

java -jar "$JULC_JAR" verify dsl . \
  --validator AuthorizedStateValidator \
  --purpose spending \
  --spec-class verification.AuthorizedStateSpec \
  --spec-classpath "build/verification-dsl/classes:$JULC_JAR" \
  --source AuthorizedStateSpec.java \
  --backend docker \
  --fuel 1500 \
  --force
```

On Windows, use `;` instead of `:` in the classpath. If you substitute a native
`julc` executable for `java -jar "$JULC_JAR"`, it still starts a child JVM for
the trusted property specification, so Java and the exact matching JuLC worker
classpath must remain available on `--spec-classpath`.

For Gradle or Maven, add `com.bloxbean.cardano:julc-verification` at the same
version as the compiler and pass the compiled specification's complete runtime
classpath to `--spec-classpath`.

## Stable construction API

The API-v1 construction surface consists of:

- `VerificationSpecification`;
- `VerificationDsl` literal and property factories;
- typed expression wrappers in
  `com.bloxbean.cardano.julc.verification.dsl`;
- `DslProperty`, `DslPropertySet`, `DslPurpose`, and `DslDomain`; and
- reproducible schema-10 generated contract metamodels.

Concrete classes in `verification.dsl.ir` are serialization infrastructure
unless listed above. Renderers, validators, worker protocol classes, semantic
dependency planners, arbitrary Lean, and custom node kinds are not public DSL
construction APIs merely because some classes need Java-public visibility.

Schema-10 canonical meanings and serialization are frozen for API v1. New
semantic vocabulary requires a new reviewed property schema.

## Property domains

`DslDomain.NONE` asks only about the exact UPLC execution relation. Reviewed
ledger domains add a pinned premise:

| Purpose | Domain |
|---|---|
| spending | `VALID_SPENDING_V3_PINNED` |
| minting | `VALID_MINTING_V3_PINNED` |
| rewarding | `VALID_REWARDING_V3_PINNED` |
| certifying | `VALID_CERTIFYING_V3_PINNED` |

The certificate records the selected domain and whether a kernel-checked bridge
to the pinned ledger model was established. A counterexample in a Blaster
superset is not automatically a ledger-valid Cardano transaction; inspect the
certificate's counterexample qualification.
