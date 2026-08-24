# Typed verification DSL (experimental)

JuLC exposes Java construction API version 1 and canonical DSL schema 1. The
verification product remains experimental even though this construction
surface now has one reviewed public format.

Canonical payloads identify themselves explicitly:

```json
{
  "format": "julc.verification.dsl",
  "schemaVersion": 1
}
```

The E.2–E.4 milestone formats formerly numbered 1 through 10 were never
released. They are design history, not compatibility formats: the current CLI
does not generate or replay them. Regenerate an old workspace with the current
`julc verify dsl-init` command. Existing certificates remain hash-bound records
of their original run.

## Supported surface

The current schema includes:

- compiler-owned datum and redeemer types;
- spending, minting, rewarding, and certifying contexts;
- guarded records and variants, optionals, lists, and duplicate-preserving
  association maps;
- closed integer, Boolean, collection, authorization, certificate, value,
  governance, and reviewed raw-data-adapter operations; and
- explicit reviewed ledger domains.

Voting and proposing validator selection, parameter-derived authorities,
arbitrary Lean, custom IR nodes, and the bounded E.5 temporal experiment are
not part of API version 1.

## Workflow

Generate the contract-specific model:

```bash
julc verify dsl-init . --validator Sale --purpose spending \
  --package verification --class SaleModel \
  --out build/verification-dsl/src/verification/SaleModel.java
```

Implement `VerificationSpecification` and return properties through the
generated model:

```java
var contract = new SaleModel();
return contract.properties(property(
    "sale.owner-signed",
    DslDomain.NONE,
    contract.datum().exists(datum ->
        contract.context().txInfo().signatories().contains(datum.owner()))));
```

Compile the generated model and trusted specification, then run:

```bash
julc verify dsl . --validator Sale --purpose spending \
  --spec-class verification.SaleSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source SaleSpec.java --backend docker --force
```

The worker executes project Java and is not a hostile-code sandbox. The parent
process revalidates the closed AST against the fresh compiler-owned contract
schema before generating Lean.

See [the verification getting-started guide](../GETTING_STARTED.md) and
[ADR-029](../../adr/verification/029-milestone-e6-stable-verification-dsl-public-api.md).
