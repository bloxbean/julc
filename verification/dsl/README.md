# Typed verification DSL prototype

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
  --package evidence --model SaleModel \
  --out build/verification-dsl/src/evidence/SaleModel.java

javac -cp julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/SaleModel.java \
  SellerPaymentSpec.java

julc verify dsl . --validator Sale \
  --spec-class evidence.SellerPaymentSpec \
  --spec-classpath build/verification-dsl/classes \
  --seller-field seller --price-field price \
  --source SellerPaymentSpec.java --backend local --force
```

The accepted E.3 AST shape is intentionally fixed and reviewed. General DSL
expressions can be represented by the prototype but are not automatically
promoted to verification claims. See `verification/e3/README.md` for the
positive, refuted, vacuous, and multi-satisfaction evidence controls.
