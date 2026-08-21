# Milestone E.4e — compiler-owned contract type projection

E.4e is the schema-4 type foundation from
[ADR-022](../../adr/verification/022-milestones-e4e-e4f-generic-contract-types-and-collections.md).
It does not add a second source parser. JuLC projects the exact selected
validator's compiler-owned `ContractSchema` into a canonical, recursive type
graph, binds its hash into the property and certificate, and re-derives every
worker-produced type in the parent process before generating Lean. Workspace
publication repeats validation against the fresh compiler schema and checks
that the carried graph agrees with the selected blueprint's names, tags,
arities, field order, roots, and container shapes. A later standalone
`verify run` authenticates that published state by hash; it does not recreate
the compiler schema from workspace files.

The focused controls are:

```bash
./gradlew :julc-verification:test \
  --tests '*ContractTypeProjectionTest' \
  --tests '*TypedSchemaFourAdmissionTest' \
  --tests '*TypedMetamodelV4Test'
```

They cover nested records, strict datum/redeemer roots, sealed variants with
guarded payload access, option/list/map applications, productive recursive
back-references, stable canonical hashes, forged-type rejection, and frozen
schema-1–3 behavior. The exact-VM and cross-backend fixture is shared with E.4f
under [`verification/e4f`](../e4f/), because it deliberately combines the E.4e
type graph with E.4f collection operations in one theorem.

The compiler currently erases `@NewType` identity from `ContractSchema`.
Schema 4 therefore exposes only its authoritative underlying representation;
it does not invent a nominal wrapper or claim that distinct source newtypes
remain distinct. Nominal newtype verification needs a later compiler-schema
ADR and does not require changing UPLC semantics.
