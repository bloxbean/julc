# Milestone E.3 — typed seller-payment DSL

This evidence slice checks the first non-annotation Java DSL property against
exact deployable UPLC. The user-owned property is
[`SellerPaymentSpec.java`](SellerPaymentSpec.java); `SaleModel.java` is
generated from each fixture's compiler-owned `ContractSchema`.
The fixtures use ordinary typed datum parameters. JuLC's compiler-owned
`strict-data-v1` boundary rejects malformed constructor tags, arities, and
field encodings before their entrypoints run; no handwritten raw-`Data` shape
checks remain in the validator bodies.

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
| `unpaid` | `REFUTED` | Strictly decoded validator which does not enforce payment |
| `vacuous` | `COULD-NOT-EVALUATE` | Always-failing non-vacuity control |
| `multi-satisfaction` | `SMT-VALID` | Demonstrates that the local property does not establish global input/output linkage |

The final row is deliberately not presented as a safe sale contract. One
seller output can potentially satisfy the local check for more than one
consumed sale input. A future global double-satisfaction property must quantify
over all relevant inputs and outputs.

## Strict-boundary refresh evidence

The 2026-08-16 refresh intentionally changed every artifact because the
compiler now inserts the typed boundary guard and the fixtures no longer
duplicate it manually. These hashes classify that expected migration:

| Fixture | Pre-integration SHA-256 | `strict-data-v1` SHA-256 | Current script hash | Bytes |
|---|---|---|---|---:|
| `authorized` | `e21fa8f6fa8c2a1d19a457fbb13a8b958b2ac30f741f28bfa18f366da384a389` | `6ef7f0b115bae0e92cfe60ad6b2e82438550f33e3982f1df5fcd3ffc93de21c3` | `0c2bc7d032d3e8a4ee39eac5218d284a6fdca2ef1c7bddcb0102b454` | 632 |
| `unpaid` | `69a64159b75d6491c224fdb5a3004382c50b472cb02b5283c3c4a3b2a0a924b5` | `e209b17a568a9c073ad072563682a7ba76b75b4eee49249e13bf160753f66066` | `cf1de1f54ce6bb8835bb90a1fdcebe9acd8ed80d7a066672facddaed` | 333 |
| `vacuous` | `0b02f75f0bcba129ce361ca3dceec442646719606f73d6b02001c1e33cc005f1` | `1cc3a9b5dcb687f78882bef01342ef893b116f6f4617c44955cafd640b21d3bd` | `ba459b97a7d5248b6dd06193667e67ae0e470c6eb6435b4984cbb23b` | 333 |
| `multi-satisfaction` | `e21fa8f6fa8c2a1d19a457fbb13a8b958b2ac30f741f28bfa18f366da384a389` | `6ef7f0b115bae0e92cfe60ad6b2e82438550f33e3982f1df5fcd3ffc93de21c3` | `0c2bc7d032d3e8a4ee39eac5218d284a6fdca2ef1c7bddcb0102b454` | 632 |

Generated workspaces remain ignored and reproducible through the evidence
driver; the table records the reviewed identity transition without treating
cache-dependent proof time as a stable semantic input.

For operational context, one warm-cache local run on 2026-08-16 observed
non-vacuity/property times of `14.0s/4.2s` (authorized), `11.4s/5.4s`
(unpaid), `12.9s/skipped` (vacuous), and `12.2s/4.3s`
(multi-satisfaction). These measurements are diagnostic only; the certificate
binds fuel and artifacts, not host wall-clock performance.

The experimental DSL worker executes trusted project Java. Its separate JVM,
memory cap, timeout, canonical protocol and post-worker validation do not make
it an OS sandbox; do not run untrusted specifications outside an isolated
environment.
