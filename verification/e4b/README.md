# E.4b compositional typed DSL evidence

ADR-019 removes whole-formula recognition from the schema-3 DSL path. A
trusted Java specification may freely compose the supported typed nodes, while
JuLC still owns the theorem envelope:

```text
reviewed ledger domain, when selected
and exact UPLC succeeds within the recorded fuel
implies
the developer's normalized typed guarantee
```

The guarantee cannot contain raw Lean, an execution predicate, or an arbitrary
assumption. The parent process strictly decodes, type-checks, purpose-checks,
normalizes, and hashes the worker's candidate IR before generating Lean.

## Try the repository controls

Run the Java suites and all local positive, mixed, refuted, and vacuous
evidence:

```bash
verification/e4b/scripts/verify.sh
```

Use the Docker proof backend instead of a host Lean installation with:

```bash
E4B_BACKEND=docker verification/e4b/scripts/verify.sh
```

The property builder still executes in a bounded local JVM. Docker isolates
the Lean/Blaster proof backend, not trusted project Java.

The evidence set contains:

| Run | Expected | What it checks |
|---|---|---|
| `spending` | `SMT-VALID` | Two independent, domain-aware spending claims composed from outputs, credentials, lovelace, datum fields, signatories, `AND`, `OR`, and `exists` |
| `minting` | `SMT-VALID` | A novel minting conjunction with strict redeemer decode, authority, consumed anchor, exact raw current-policy asset, comparison, and nested `OR` |
| `mixed` | `REFUTED` aggregate | One claim is established and a second is independently refuted without hiding either result |
| `vacuous` | `COULD-NOT-EVALUATE/property-vacuous` | A never-successful validator skips only its own proof |

Generated metamodels, classes, workspaces, dependency caches, logs, raw
countermodels, and certificates live below `verification/e4b/fixtures/*/build`
and `verification/e4b/generated`; both locations are gitignored and reproduced
by the script.

## Use schema 3 in a project

Generate a contract model from the compiler-owned schema:

```bash
julc verify dsl-init . --validator Sale --purpose spending \
  --package evidence --class SaleModel \
  --out build/verification-dsl/src/evidence/SaleModel.java
```

Write a trusted specification. The following two properties are independent;
adding the second requires no JuLC resolver or fixed profile:

```java
import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

public final class SaleProperties implements VerificationSpecification {
    public DslPropertySet properties() {
        var sale = new SaleModel();
        var paid = sale.context().txInfo().outputs().exists(output ->
            output.address().credential().matchesKeyHash(sale.datum().seller())
                .and(output.value().lovelace().ge(sale.datum().price())));
        var fallbackSigned = sale.context().txInfo().signatories()
            .contains(keyHash("4a554c435f5645524946595f415554484f524954595f303030303031"));

        return DslPropertySet.composed(DslPurpose.SPENDING,
            property("sale.paid", DslDomain.VALID_SPENDING_V3_PINNED, paid),
            property("sale.paid-or-fallback",
                DslDomain.VALID_SPENDING_V3_PINNED,
                paid.or(fallbackSigned)));
    }
}
```

Compile the generated model and specification, then verify:

```bash
javac -cp /path/to/julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/SaleModel.java SaleProperties.java

julc verify dsl . --validator Sale --purpose spending \
  --spec-class evidence.SaleProperties \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source SaleProperties.java --backend local --fuel 5000 --force
```

Replace `local` with `docker` to use the container backend. Replace `:` with
the platform classpath separator on Windows. Including the JuLC JAR in
`--spec-classpath` is required when `julc` is the GraalVM native executable,
because specification execution still requires an installed Java runtime.

`--source` is recorded as provenance shared by the claims produced by that
one worker invocation. The canonical property IR—not source-file contents—is
the bound semantic input.

## Result interpretation

Each property has its own non-vacuity step, proof step, formula hash, theorem
envelope hash, capability list, domain, and result entries. A vacuous or
undetermined non-vacuity result skips only that property's proof. Overall
success requires every requested property to be established.

`SMT-VALID` applies only to exact executions within the recorded CEK fuel and,
when selected, the pinned reviewed ledger domain. A `REFUTED` result is a
counterexample in the recorded Blaster symbolic domain. It is not called
ledger-valid or VM-reproduced unless an independent future witness gate
establishes that fact; E.4b certificates conservatively record both flags as
false.

This remains experimental formal-verification evidence for named properties,
not a claim that the validator or contract is generally safe.
