# Milestones E.4e–E.4f — generic contract types and collections

This evidence directory exercises experimental schema 4 from
[ADR-022](../../adr/verification/022-milestones-e4e-e4f-generic-contract-types-and-collections.md).
The property is generated from compiler-owned datum/redeemer types and combines
a nested record, a guarded redeemer variant, an optional integer, list
traversal, duplicate-preserving first-match map lookup, and an existing ledger
signer predicate.

Collection meanings are structural. Lists and association maps preserve order
and duplicates. `lookupFirst` uses the first matching raw entry; `lookupAll`
preserves every matching value; map structural equality is ordered pair-list
equality, not extensional mathematical-map equality. Negative or out-of-range
list indices return an empty symbolic option.

The negative control is not a literal-false theorem: it requires the `Use`
redeemer while the deliberately vulnerable validator also accepts `Stop`, so
the retained refutation is a concrete typed-variant policy counterexample.

Run the local controls:

```bash
verification/e4f/scripts/verify.sh
```

Run the positive control through Docker:

```bash
E4F_BACKEND=docker verification/e4f/scripts/verify.sh
```

The Java specification is trusted project code executed in a bounded worker
JVM. Parent-process admission re-derives every structural type from the exact
compiler schema. A successful result covers only the named property, exact
UPLC artifact, pinned model/tools, and recorded fuel. The evidence pins fuel
2000: lower 1000 and 1500 controls were observed to fail closed as vacuous,
while 2000 establishes an exact successful path. It is not a whole-contract or
compiler-safety claim.
