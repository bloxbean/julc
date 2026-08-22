# Experimental exact-artifact state-machine verification

This workspace checks `ExactCounter` with two deliberately distinct strategies:

- BMC searches for a counterexample through depth `1`;
- solver-backed k-induction searches through depth `1`.

Every admitted step requires strict current datum and redeemer decoding,
exact execution of the hash-bound UPLC within the recorded per-step fuel,
exactly one full-address continuing output, and strict inline successor
decoding. User Java supplies predicates, never the transition function.

`BOUNDED-NO-COUNTEREXAMPLE` is bounded evidence, not an unbounded proof.
`SMT-K-INDUCTIVE` is solver-backed and is not `KERNEL-PROVED`. Symbolic
steps are linked by datum continuity only; the certificate therefore keeps
`ledgerRealizableTraceEstablished: false`.

Domain: `NONE`
Initial state scope: `USER_PREDICATE`
Fuel is per step. Executions that exhaust it are outside the claim.
