# ADR-070: Checked casts to a sealed-interface variant

**Status:** Proposed
**Date:** 2026-11-06
**Risk class:** correctness-sensitive, **medium**. Today a downcast to the wrong variant reads another variant's fields
and carries on, where Java throws: a validator can accept a datum its own Java tests reject. The fix changes the bytes
and cost of every program that downcasts.
**Issues:** [#288](https://github.com/bloxbean/julc/issues/288) (found in the ADR-067 review),
[#309](https://github.com/bloxbean/julc/issues/309) (found again as ADR-069's R1)
**Relates to:** [ADR-062](062-jvm-uplc-semantic-consistency-programme.md) (semantic consistency; T9 covers `(T)(Object)`
representation casts), [ADR-063](063-differential-jvm-uplc-test-harness.md) (harness),
[ADR-067](067-switch-guards-record-patterns.md) (guards reject reference casts pending #288),
[ADR-068](068-javac-frontend-migration.md) (javac frontend), [ADR-069](069-pattern-variable-scopes.md) (`instanceof`
everywhere; R1), [ADR-032](032-target-aware-pv11-optimization-catalog.md) (byte compatibility; #146's policy)

## Context

A cast to a record variant of a sealed interface is a downcast that Java checks at run time:

```java
sealed interface S permits A, B, D {}
record A(BigInteger v) implements S {}
record B(BigInteger v, BigInteger w) implements S {}
record D() implements S {}

A q = (A) s;           // s is a B: ClassCastException
return q.v();
```

JuLC lowers every reference cast to its operand (`Casts.cast`, "Java checks such a cast at run time and the script does
not (#288)"). On a `B(10, 4)` the script returns 10, `B`'s first field read as `A`'s; on a `D()` it fails reading a
field that is not there. Pinned on `main` at every level by `Adr069PatternScopesHarnessTest.r1AnUncheckedCastReadsAnotherVariant`.

The idiom is common. In `julc-examples-javac` ten on-chain validators open with
`ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();`, and the MPF code casts
`(ProofStep.Leaf) step`. A redeemer cast such as `Withdraw w = (Withdraw) redeemer;` accepts any other variant whose
fields happen to decode (#288's impact).

### Current behaviour in detail

- `Casts.cast`: a primitive target goes to `primitive`; any other target returns the lowered operand, decoding a pair
  list when the target is a map. `requireNativeView` (ADR-047) checks native boundaries first.
- `PlutusData.cast(x, T.class)` is the same view, documented as unchecked (ADR-068 D6).
- `(T)(Object) x` casts in the stdlib are representation views that throw on the JVM (ADR-062 T9).
- A member read on a cast, `((A) s).v()`, is `JULC0055`: the receiver's type is read as the reference frontend read it,
  not from the cast.
- Switch guards reject every reference cast (`JULC0072`, ADR-067 F61, citing #288).
- `TypeTests.instanceOf` already lowers the tag test this decision needs: `FstPair(UnConstrData(x)) == k`, with the
  variant's tag from the registry, ledger sums included.

## Goals and non-goals

**Goals**

- G1. A downcast to a variant behaves as Java's: the value when it is that variant; a failure otherwise.
- G2. Casts that need no check keep their bytes: upcasts, casts to the same type, primitive casts, map views,
  `PlutusData.cast`, and casts from a type with no representation (`(T)(Object) x`).
- G3. The operand runs once, in Java's order.

**Non-goals**

- `(T)(Object) x` and `PlutusData.cast` stay unchecked views (ADR-062 T9, ADR-068 D6). See Q1.
- Lifting `JULC0055` for a member read on a cast. See Q2.
- Lifting the guard rule against reference casts (`JULC0072`). See Q3.
- Removing a check that an earlier `instanceof` already made (`if (s instanceof A) { A a = (A) s; }`). See Q4.

## Pinned normative references

*The Java Language Specification, Java SE 25 Edition*, fetched 2026-11-06 from
`docs.oracle.com/javase/specs/jls/se25/html/`:

- **15.5** (expressions and run-time checks): "In a cast, when the actual class of the object referenced by the value of
  the operand expression is not compatible with the target type specified by the cast operator (§5.5, §15.16); in this
  case a `ClassCastException` is thrown."
- **15.6**: "A cast expression (§15.16) throws a `ClassCastException` if a cast is found to be impermissible at run
  time."
- **5.5** (casting contexts): a narrowing reference conversion from a sealed interface to a permitted record is checked
  at run time; a widening one is not (paraphrase).
- **15.7.1**: the operand is evaluated before the cast is applied (paraphrase).

## Threat model and trust assumptions

- The transaction is untrusted: an attacker picks the datum or redeemer, and so the variant a cast sees.
- Today a wrong variant is read through the target's layout: a value the attacker shapes can pass checks written for
  another variant. After this ADR it fails the script, as the JVM throws.
- javac is trusted to type the operand and the target; the registry is trusted for each variant's tag.

## Invariants

- **I1. A downcast checks the tag.** `(V) x`, where V is a record variant of sealed interface P and x's static type is
  P, yields x only if x's constructor tag is V's; otherwise the script fails. Constrains D1.
- **I2. One evaluation.** x is evaluated once, before the test, and the value it yields is the value tested.
  Constrains D1.
- **I3. Nothing else moves.** Every cast outside D1's case lowers to the same term as before; the frozen reference rows
  of programs without a D1 cast do not move. Constrains D2.
- **I4. A failure as the JVM's.** A failed check is a script failure that adds no trace of its own, so the trace log
  equals the JVM's up to the failure. Constrains D1.

## Decisions

### D1. A downcast to a variant is a tag check

When the target type T is a record variant V of a sealed interface P (`TypeTests`' notion: one sealed parent, a plain
record), and the operand's static type is P, the cast lowers to:

```
readOnce(x) as #operand;  if FstPair(UnConstrData(#operand)) == tag(V) then #operand else Error     // illustrative
```

- The tag test is `TypeTests`' (the same registry tag, ledger sums included), so a cast and an `instanceof` agree on
  every value.
- `readOnce` (#146) binds the operand unless it is a variable or a literal, so it runs once.
- `Error` carries no trace (I4).
- Every level, including BASELINE: the maintainer's policy since #146 (ADR-032 amendment) is that JuLC is a preview,
  so a correctness fix changes the default rather than sitting behind an opt-in. That policy is confirmed for this issue.

### D2. What stays a view

Unchanged, so their bytes stay (I3):

- an upcast (variant to its interface, anything to `Object`), and a cast to the operand's own type;
- a primitive cast and a map view;
- a cast whose operand's static type is not P: `(V)(Object) x` (an ADR-062 T9 view) and casts from `PlutusData`
  (`PlutusData.cast` and the plain cast alike, an ADR-068 D6 view);
- a cast whose target is no record variant (a sealed interface, a plain record, a ledger type that is no sum variant).

### D3. Diagnostics

No new code. A cast whose check cannot be expressed (no tag in the registry) is impossible for D1's case: `TypeTests`
already rejects such a variant (`JULC0126`, `JULC0127`, two sealed parents), and the same rejection applies.

### D4. Documentation

The Casts class comment, the release notes (with the measured cost of the check), the troubleshooting entry on casts,
and the AI starter pack (a downcast fails on the wrong variant; `instanceof`/`switch` remain the way to branch).

*Alternatives rejected:*

- **Reject downcasts** (`JULC0xxx`, with `instanceof` as the fix). Safe, but it breaks ten example validators and the
  common `ScriptInfo` idiom, for a check that costs little.
- **Trust the ledger for `ScriptInfo`** and check only user sums. A minting script does receive `MintingScript`, but
  a multi-purpose validator's default handler can cast the wrong way, and an exception for one type is a rule users must
  remember.
- **Check `(V)(Object) x` too.** `Object` and `PlutusData` views are documented as unchecked and used as representation
  casts (T9); checking them is ADR-062 S2's work, not this one's.

## Affected stages and modules

| Module | Stage | Change |
|---|---|---|
| `julc-compiler` | javac lowering (`Casts`) | D1's check, reusing `TypeTests`' tag test and `PirHelpers.readOnce` |
| `julc-difftest` | harness, generator | rows per decision, R1's pin flips, a G1 gate for downcasts |
| `docs` | release notes, troubleshooting, starter pack | D4 |

## Compatibility

- **Source:** unchanged; no program that compiles stops compiling.
- **Bytes and cost:** programs with a D1 downcast grow by the check (one `readOnce` binding when the operand is not a
  variable, a tag test and a branch). M0 measures it on the ten example validators. All other programs keep their bytes
  (I3).
- **Behaviour:** a downcast to the wrong variant now fails, where it returned another variant's fields. That is the
  JVM's behaviour.

## Implementation milestones

- **M0. Pins and cost baseline.** Harness rows of D1 and D2 casts as they behave today, R1's pin, #288's reproducer, and
  the size and cost of the ten `ScriptInfo` validators. Exit: green; frozen reference unchanged.
- **M1. D1, D4.** The check, tests, docs; the generator gate. Exit: R1 and #288 agree with the JVM; frozen rows moved
  only for programs with a D1 cast, each reviewed; cost recorded; Jazzer run.

Each milestone: targeted tests, a full build on the exact head, review by Claude reviewer agents; Codex at the end.

## Verification and test strategy

The JVM is the oracle (ADR-063 harness, all four levels), comparing value, failure and traces.

1. Every D1 cast over every variant, so the check passes and fails: a local, a field read after it, a call argument,
   a return, in a loop body, in a lambda, nested (`(A) (S) x`), on a traced operand (I2), on a ledger `ScriptInfo` of each
   purpose, and #288's reproducer.
2. D2's casts unchanged: frozen reference rows of programs without a D1 cast do not move (I3); unit tests that the
   lowered term of an upcast, a same-type cast, `(V)(Object) x` and `PlutusData.cast` is the operand's.
3. I4: on a failing cast the UPLC trace log equals the JVM's.
4. G1 gate `DOWNCASTS`: `V v = (V) s` over sealed subjects (both outcomes), in every statement position; property test
   requiring agreement; a Jazzer run.
5. Cost: the ten example validators' size and CPU before and after, in the evidence.

## Production and audit gates

All of the above green on the merge head; a Codex review with no open P0 or P1.

## Risks

- **Cost.** A small constant per downcast; measured in M0/M1 and reported.
- **A variant with no tag.** Not reachable: D3.
- **Over-reach.** D1 applies only where the operand's static type is the sealed interface, so views keep their bytes.

## Open questions

- **Q1. `(V)(Object) x` and `PlutusData.cast`.** Lean: stay views (documented, T9); revisit with ADR-062 S2.
- **Q2. `((V) x).f()` (`JULC0055`).** Lean: a follow-up; with D1 the receiver's type is known and the read is safe.
- **Q3. Casts in switch guards (`JULC0072`).** Lean: a follow-up; the guard rule exists only because casts were
  unchecked.
- **Q4. A check after an `instanceof`.** Lean: keep it; an optimisation pass can drop a tag test already made, measured
  first.

## Revision history

- r1 (2026-11-06): initial proposal.
