# Generated JuLC compositional DSL verification

This generator-owned workspace checks `VulnerableValue` against `1` independently
named schema-3 properties from `/Users/satya/work/bloxbean/julc/verification/e4j/VulnerableValueSpec.java`. The worker-produced AST was strictly
decoded, authoritatively type-checked, normalized, and hash-bound before
this workspace was published.

Each property has its own reviewed domain selector, exact-artifact
execution premise, guarantee hash, semantic capability set, non-vacuity
query, proof/counterexample query, logs, and certificate result. A vacuous
property skips only its own proof. Overall success requires every requested
property to be established.

`SMT-VALID` covers executions within the recorded CEK fuel bound and the
recorded solver domain. Where a pinned ledger-valid domain is selected, a
separate generated Lean theorem kernel-checks its inclusion in the solver
domain. Refutations remain solver-domain counterexamples unless a separate
concrete ledger-valid witness is recorded.

The result proves only the explicitly named normalized formulas. It does
not claim that the complete validator or protocol is safe.

Schema-8 value claims keep raw structural, upstream first-match,
strict-summed, and finite-support extensional meanings separate. The
certificate records the meanings and aggregation scopes used by each
claim. Strict-summed absence is `none`, explicit zero is `some 0`, and
malformed value structure also fails closed. A balance-only claim is
labeled domain-implied. Transaction-local payment claims retain
`globalMultiInputLinkageModeled: false`; they do not establish one
payment per consumed script input.
