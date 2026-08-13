# Milestone E.3 — typed seller-payment DSL

This evidence slice checks the first non-annotation Java DSL property against
exact deployable UPLC. The user-owned property is
[`SellerPaymentSpec.java`](SellerPaymentSpec.java); `SaleModel.java` is
generated from each fixture's compiler-owned `ContractSchema`.

The reviewed property states:

```text
the solver-compatible superset of validSpendingContext(ctx)
and exact UPLC succeeds within recorded fuel
implies
the attached datum strictly decodes and some output has
  PubKeyCredential(datum.seller)
  and lovelace(output.value) >= datum.price
```

Pinned Blaster cannot translate several V3 validity implementation details in
this theorem premise. The solver domain therefore keeps the translatable V3
checks and is deliberately broader, not narrower. Generated Lean separately
kernel-proves that every pinned V3 spending-valid context is in this domain,
then kernel-checks the ledger-valid corollary after Blaster succeeds. Thus an
`SMT-VALID` result still covers every pinned V3 ledger-valid spending context;
the broader SMT premise makes the obligation stronger.

Run the complete evidence suite:

```bash
verification/e3/scripts/verify.sh
```

Expected classifications:

| Fixture | Expected | Purpose |
|---|---|---|
| `authorized` | `SMT-VALID` | Positive exact-payment implementation |
| `unpaid` | `REFUTED` | Exact-shape validator which does not enforce payment |
| `vacuous` | `COULD-NOT-EVALUATE` | Always-failing non-vacuity control |
| `multi-satisfaction` | `SMT-VALID` | Demonstrates that the local property does not establish global input/output linkage |

The final row is deliberately not presented as a safe sale contract. One
seller output can potentially satisfy the local check for more than one
consumed sale input. A future global double-satisfaction property must quantify
over all relevant inputs and outputs.

The experimental DSL worker executes trusted project Java. Its separate JVM,
memory cap, timeout, canonical protocol and post-worker validation do not make
it an OS sandbox; do not run untrusted specifications outside an isolated
environment.
