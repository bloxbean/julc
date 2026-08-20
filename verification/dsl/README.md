# Experimental typed verification DSL

Milestone E.2 provides an experimental Java frontend in `julc-verification`.
It is not yet a stable public API.

The prototype contains:

- sealed, typed symbolic expressions rather than raw Lean strings;
- deterministic contract-specific datum accessors generated from
  compiler-owned `ContractSchema`;
- canonical schema-1 property IR;
- a separate JVM worker with memory, timeout and output-size bounds;
- authoritative post-worker type/path validation; and
- deterministic Lean expression rendering.

The worker executes project Java code. It is therefore opt-in and intended for
trusted local source. Running it in a separate JVM limits accidental resource
use but is not an operating-system security sandbox. A networkless Docker
worker remains a prerequisite before hosted execution of untrusted property
builders.

Run the E.2 tests with:

```bash
./gradlew :julc-verification:test --tests '*TypedDslPrototypeTest'
```

The main equivalence test compiles a generated contract model and a Java
property specification in a temporary directory, executes it in the worker,
revalidates the returned AST, and proves that it has byte-identical canonical
IR and identical generated Lean to the existing `@RequiresSigner` lowering.

Milestone E.3 adds the first exact-UPLC vertical slice. From a built JuLC
project, generate the metamodel and compile a trusted Java property:

```bash
julc verify dsl-init . --validator Sale \
  --package evidence --class SaleModel \
  --out build/verification-dsl/src/evidence/SaleModel.java

javac -cp julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/SaleModel.java \
  SellerPaymentSpec.java

julc verify dsl . --validator Sale \
  --spec-class evidence.SellerPaymentSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --seller-field seller --price-field price \
  --source SellerPaymentSpec.java --backend local --force
```

The JuLC JAR entry is required with the native CLI because the trusted DSL
worker is a child JVM; it is harmless with the JVM CLI. Use the platform
classpath separator (`;` on Windows).

The accepted E.3 AST shape is intentionally fixed and reviewed. General DSL
expressions can be represented by the prototype but are not automatically
promoted to verification claims. See `verification/e3/README.md` for the
positive, refuted, vacuous, and multi-satisfaction evidence controls.

Milestone E.4a adds canonical schema-2 minting IR while retaining schema-1
spending compatibility. It introduces a purpose-aware minting metamodel,
current-policy identity, raw mint structure, consumed-input references, strict
redeemer decoding, and an explicit pinned minting ledger domain. The existing
`@ControlledMint` profile and its equivalent DSL expression now lower through
the same canonical IR and Lean semantic renderer.

The first contract-specific slice is a one-shot authorized mint: every
successful exact-policy execution in the recorded domain must strictly decode
the redeemer, contain the configured authority and consumed anchor, and have
the exact configured current-policy asset. See
[`verification/e4a/README.md`](../e4a/README.md) for commands, controls, raw
association-list semantics, and local/Docker execution.
