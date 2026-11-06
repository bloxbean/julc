# ADR-070: Checked casts to a sealed-interface variant

**Status:** Accepted (2026-11-06): approved in design review by Codex at `dde2a487` and by the semantics and code
reviewers at `b6af2c48` (their P2/P3 points addressed in r3 and r4). The implementation follows in the same PR.
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
- `PlutusData` is itself a sealed interface (`MapData`, `ListData`, `IntData` and `BytesData` are records; `ConstrData`
  is a final class), and the stdlib
  downcasts to them: `(PlutusData.MapData) Builtins.sndPair(...)` and `(PlutusData.BytesData) token` in `ValuesLib`.
  These are views of Data's own representation, not of a `Constr`-encoded sum; the registry holds `PlutusData` as no
  `SumType`.
- `(T)(Object) x` casts in the stdlib are representation views that throw on the JVM (ADR-062 T9).
- A member read on a cast, `((A) s).v()`, is `JULC0055`: the receiver's type is read as the reference frontend read it,
  not from the cast.
- Switch guards reject every reference cast (`JULC0072`, ADR-067 F61, citing #288).
- `TypeTests.instanceOf` already lowers the tag test this decision needs: `FstPair(UnConstrData(x)) == k`, with the
  variant's tag from the registry, ledger sums included. `TypeTests.isOf` already reads an operand's type through
  intersection bounds (`c ? new A(..) : new B(..)` is `Record & S`). Both are tied to an `InstanceOfTree` today.

## Goals and non-goals

**Goals**

- G1. A downcast to a variant behaves as Java's: the value when it is that variant; a failure otherwise.
- G2. Casts that need no check, or that JuLC cannot check, keep their bytes: upcasts, casts to the same type, primitive
  casts, map views, `PlutusData`'s own variants, `PlutusData.cast`, and casts from a type with no representation
  (`(T)(Object) x`).
- G3. The operand runs once, in Java's order.

**Non-goals**

- `(T)(Object) x` and `PlutusData.cast` stay unchecked views (ADR-062 T9, ADR-068 D6). See Q1.
- Lifting `JULC0055` for a member read on a cast. See Q2.
- Lifting the guard rule against reference casts (`JULC0072`). See Q3.
- Removing a check that an earlier `instanceof` already made (`if (s instanceof A) { A a = (A) s; }`). See Q4.
- Casts to a type JuLC represents with no tag of its own: a sealed sub-interface of a sealed interface, an open
  interface, or a variant that `TypeTests` rejects (`JULC0126`, `JULC0127`, two sealed parents). They stay views, as
  today; no program that compiles stops compiling (D3, Q5).
- `JulcList.any`/`all` evaluating the predicate on every element (a known divergence): see Risks.

## Pinned normative references

*The Java Language Specification, Java SE 25 Edition*, fetched 2026-11-06 from
`docs.oracle.com/javase/specs/jls/se25/html/`:

- **15.5** (expressions and run-time checks): "In a cast, when the actual class of the object referenced by the value of
  the operand expression is not compatible with the target type specified by the cast operator (§5.5, §15.16); in this
  case a `ClassCastException` is thrown."
- **15.6**: "A cast expression (§15.16) throws a `ClassCastException` if a cast is found to be impermissible at run
  time."
- **5.1.6.1** (allowed narrowing reference conversions): a narrowing exists from S to T unless they are provably
  disjoint; a type variable is read through its upper bound and an intersection through each of its components
  (paraphrase).
- **5.1.6.3** (run-time checks): a narrowing reference conversion is checked at run time, and a value that is not
  null and not of a class assignable to T's erasure throws `ClassCastException` (paraphrase). JuLC has no `null`.
- **15.16** (cast expressions): the cast operator applies a casting conversion to its operand (paraphrase); the run-time
  rule is 15.5's, quoted above.
- **15.7.1**: the operand is evaluated before the cast is applied (paraphrase).

## Threat model and trust assumptions

- The transaction is untrusted: an attacker picks the datum or redeemer, and so the variant a cast sees.
- Today a wrong variant is read through the target's layout: a value the attacker shapes can pass checks written for
  another variant. After this ADR it fails the script, as the JVM throws.
- javac is trusted to type the operand and the target; the registry is trusted for each variant's tag.

## Invariants

- **I1. A downcast checks the tag.** A cast in D1's case yields its operand only if the operand's constructor tag is
  the target variant's; otherwise the script fails. A cast and an `instanceof` of the same variant agree on every value.
  Constrains D1.
- **I2. One evaluation.** x is evaluated once, before the test, and the value it yields is the value tested.
  Constrains D1.
- **I3. Nothing else moves.** Every cast outside D1's case lowers to the same term as before. No frozen-reference row
  is expected to move: the only compiled-or-pinned compiler tests with a variant downcast are rejections whose first
  error must stay (`Adr069CompatibilityPinsTest.theBuildOrder`), and the stdlib's casts are D2's. Constrains D2.
- **I4. A failure as the JVM's.** A failed check is a script failure that adds no trace of its own, so the trace log
  equals the JVM's up to the failure. Constrains D1.

## Decisions

### D1. A downcast to a variant is a tag check

**The case.** A cast `(T) x` is checked when:

1. the erasure of T is a record V that is a constructor of a sum the registry holds as a `SumType` (a `Constr`-encoded
   sealed interface P: the program's own, a library's, or a ledger sum such as `ScriptInfo`). `PlutusData`'s variants
   are not: the registry holds no `SumType` for `PlutusData` (D2); and
2. x's static type, as javac attributes it, is a member of P's family but not statically V: P itself, an intersection
   with a bound in P's family (`TypeTests.isOf`, e.g. `Record & S` from a `?:` of two variants), or a type variable or
   captured wildcard read through its upper bound, as JLS 5.1.6.1 reads it. The decision uses javac's types and the
   erasure of T only, never an ADR-059 specialisation's environment: the JVM checks against the erasure (5.1.6.3), so a
   specialised copy checks exactly where its template does. JuLC rejects bounded type parameters today (ADR-059's
   bounded-template rule, `JULC0012` for an unspecialised type variable), so the type-variable clause matters for
   captured wildcards (`xs.head()` on `JulcList<? extends S>`) and keeps the rule complete if that subset grows.

An operand that is statically V is a same-type cast (D2). An operand of another variant of P cannot reach a cast:
javac rejects `(B) a` on records.

**The term:**

```
readOnce(x) as #operand;  if FstPair(UnConstrData(#operand)) == tag(V) then #operand else Error    // illustrative
```

- The tag test is `TypeTests`', refactored so both use one, with the cast's own rule kept apart:
  - `TypeTests.constructorOf(TypeElement V)`: V's constructor when (1) holds (exactly one sealed parent that the registry
    holds as a `SumType`, a plain record that is no `@NewType`, a non-negative index among the permitted types), and
    nothing otherwise. It never rejects.
  - `TypeTests.tagTest(PirTerm scrutinee, Constructor c)`: the comparison.
  - `instanceof` keeps its own operand rule (P or V, `JULC0126` otherwise), its diagnostics and its terms; only the
    construction is shared, so its frozen rows (e.g. `a instanceof S.A b` with `a` typed `S.A`) do not move.
  - `Casts` adds (2) through a cast-only predicate, `isOfThroughBounds`: the operand statically V first (a same-type
    cast, no check), then the operand in P's family. It reads a type variable or captured wildcard through its upper
    bound, recursively (a bound may be an intersection or a variable). `instanceof` keeps its own `isOf`, which reads
    no bound, so its diagnostics stay: `((JulcList<? extends S>) xs).head() instanceof S.A` is still `JULC0126`,
    pinned. `Casts` gains `TypeTests` as a collaborator.
- `readOnce` (#146) binds the operand unless it is a variable or a literal, so it runs once.
- `Error` carries no trace (I4). It is typed as the then-branch is (`typing.ofTerm` of the operand term), so the two
  branches agree for any typed pass and no type variable's PIR type is ever asked for; and it is located at the cast
  (`terms.at`), so a source map and the debugger point at the cast that failed.
- Every level, including BASELINE: the maintainer's policy since #146 (ADR-032 amendment) is that JuLC is a preview,
  so a correctness fix changes the default rather than sitting behind an opt-in. That policy is confirmed for this issue.

### D2. What stays a view

Unchanged, so their bytes stay (I3):

- an upcast (variant to its interface, anything to `Object`), and a cast to the operand's own type (including a
  pattern variable already typed V);
- a primitive cast and a map view;
- `PlutusData`'s own variants: `(PlutusData.MapData) d`, `(PlutusData.BytesData) b`. They view Data's representation;
  a `Constr` tag test would reject every map and bytestring. They stay an unchecked view, a JVM divergence ADR-068 D6
  already records;
- a cast from a type with no representation of the family: `(V)(Object) x` (an ADR-062 T9 view), an operand of an
  unbounded type variable (bound `Object`), an operand typed as an open super-interface of P (such as
  `PlutusDataConvertible`), and `PlutusData.cast(x, V.class)` (an ADR-068 D6 view). A plain `(V) d` from a `PlutusData`
  value does not compile;
- a cast whose target has no tag of its own: a sealed interface (including a sealed sub-interface, which JuLC does not
  represent as a sum of its own), an open interface, a plain record, and a variant `TypeTests` would reject (D3).

### D3. Diagnostics

No new code and no new rejection. A cast whose check JuLC cannot express (D2's last bullet, a library-jar sum with no
registry entry) stays a view, as today: no program that compiles stops compiling. Each such case is listed in D2 and
Q5. The declaration-level rejections that exist already (`JULC0079` for two sealed parents, `JULC0127` for a variant
that is no plain record) are unchanged.

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
| `julc-compiler` | javac lowering (`Casts`, `TypeTests`, `Lowering` wiring) | D1's check; `TypeTests.variant` and `tagTest` shared with `instanceof`; `PirHelpers.readOnce` |
| `julc-difftest` | harness, generator | rows per decision, R1's pin flips, a G1 gate for downcasts |
| `docs` | release notes, troubleshooting, starter pack | D4 |

## Compatibility

- **Source:** unchanged; no program that compiles stops compiling.
- **Bytes and cost:** programs with a D1 downcast grow by the check (one `readOnce` binding when the operand is not a
  variable, a tag test and a branch). M0 and M1 measure it. All other programs keep their bytes (I3), the stdlib's
  included (`ValuesLib`'s `PlutusData` casts are D2's).
- **Downstream pins that move:** blueprint hashes and sizes of validators that downcast, in `julc-examples-javac` (14
  on-chain validators cast `ScriptInfo`), `julc-examples`, and Elci's pinned hashes; budget assertions over those
  validators. The release notes name the change; the repinning is the downstream repositories' own step.
- **Behaviour:** a downcast to the wrong variant now fails, where it returned another variant's fields. That is the
  JVM's behaviour.

## Implementation milestones

- **M0. Pins and cost baseline.** Harness rows of D1 and D2 casts as they behave today (a downcast agrees on its own
  variant and on a nullary one, where the unchecked read fails too, and is a MISMATCH on a variant with fields), R1's
  pin, #288's reproducer, D2 compatibility pins (frozen terms) for each D2 bullet, and the FLAT size of the 14
  `julc-examples-javac` validators that cast `ScriptInfo`, compiled from a clone at a recorded commit with this
  repository's compiler at every level. Exit: green; frozen reference unchanged.
- **M1. D1, D4.** The check, tests, docs; the generator gate. Exit: R1 and #288 agree with the JVM; frozen rows moved
  only for programs with a D1 cast, each reviewed; cost recorded; Jazzer run.

Each milestone: targeted tests, a full build on the exact head, review by Claude reviewer agents; Codex at the end.

## Verification and test strategy

The JVM is the oracle (ADR-063 harness, all four levels), comparing value, failure and traces. A newly checked cast
must agree with the JVM on every variant.

1. **D1 positions**, each over every variant (a passing and a failing check): a local, an argument, a return, a loop
   body, a lambda body, a `?:` arm, a switch-arm value, an ADR-069 D4 continuation, a traced operand (I2), `(A)(S) x`
   (one check: the inner cast to S is a view), a ternary operand (`Record & S`), a `var` of one, an accessor or record
   component typed S (`(A) xs.head()`, `(A) box.value()`), a captured wildcard (`JulcList<? extends S>`), a ledger
   `ScriptInfo` of each purpose, a multi-purpose validator's default handler casting the wrong `ScriptInfo`, and #288's
   reproducer. The harness counts the JVM's `ClassCastException` as a failure, as it counts any exception.
2. **The failure is observable and placed** (I4): a cast whose result is never read, and one passed to a helper that
   ignores its argument, still fail on the wrong variant at every level (no pass may drop the check); a failing cast
   between two traced calls leaves only the first trace; a cast in an operand that `&&`, `||`, a `?:` arm or an
   unselected switch arm skips does not run; a cast in an `instanceof` operand whose pattern variable is then read.
3. **D2 unchanged** (I3): compatibility pins (frozen bytes and PIR) for an upcast, a same-type cast (and a pattern
   variable typed V, an accessor or component typed V), `(V)(Object) x`, an `Object`-typed accessor result,
   `PlutusData.cast`, `PlutusData`'s variants as `ValuesLib` uses them, a cast to a sealed or open interface, and the
   existing `instanceof` frozen rows, including a V-typed operand. Bounded type parameters stay rejected, pinned. Views that diverge from
   the JVM today are pinned by term, not by JVM agreement. `library-discovery-pre-cleanup.txt` and the frozen reference
   do not move. `theBuildOrder`'s rejections keep their first error.
4. **Guards** keep rejecting reference casts (`JULC0072`), pinned.
5. **Generator** (ADR-066 G1): a gate `DOWNCASTS` (`V v = (V) s` over sealed subjects of either outcome, followed by
   reads of `v`; never `((V) s).f()` or a cast in a guard), a steer toward the wrong variant, a property test requiring
   agreement, and a Jazzer run.
6. **Cost:** the 14 example validators' FLAT size at every level before and after; the check's CPU and memory from
   the harness's measurements of the D1 rows before and after.

## Production and audit gates

All of the above green on the merge head; a Codex review with no open P0 or P1.

## Risks

- **Cost.** A small constant per downcast; measured in M0/M1 and reported.
- **A variant with no tag.** Not reachable: D3.
- **Over-reach.** D1 applies only to a registry `SumType`'s variants from an operand of that family, so `PlutusData`
  views and representation casts keep their bytes; M1 expects no frozen row to move.
- **`any`/`all` do not stop early.** `PirHofBuilders.any` and `all` evaluate the predicate on every element, where
  `JulcList.any` on the JVM stops at the first true. A checked cast in a predicate can therefore fail on an element the
  JVM never reaches: `xs.any(x -> ((A) x)...)` over `[A(5), B(1, 2)]` is true on the JVM and, after D1, a failure on-chain
  (before D1 it read `B` as `A` and returned true). The new divergence fails closed, where the old one read the wrong
  fields; the root cause is the known no-short-circuit divergence, tracked separately (Q6). A harness row pins it.
- **Tags that came through views.** A value obtained through an unchecked view (`PlutusData.cast(d, B.class)`,
  `(B)(Object) x`) carries whatever tag its Data has. After an upcast, a later checked `(B) s` tests that tag. Data built
  with a standalone record's tag 0 for a variant whose sum tag is not 0 used to pass and now fails; typed boundaries
  already reject standalone variants, so only views lead there.

## Open questions

- **Q1. `(V)(Object) x`, unbounded type variables, open super-interfaces, `PlutusData.cast`, and `PlutusData`'s own
  variants.** Lean: stay views (documented, T9, ADR-068 D6); revisit with ADR-062 S2. `PlutusData`'s variants could be
  checked by Data kind (`ChooseData`) rather than by tag: a possible follow-up, so this is a choice, not a limit.
- **Q2. `((V) x).f()` (`JULC0055`).** Lean: a follow-up; with D1 the receiver's type is known and the read is safe.
- **Q3. Casts in switch guards (`JULC0072`).** Lean: a follow-up; the guard rule exists only because casts were
  unchecked.
- **Q4. A check after an `instanceof`.** Lean: keep it; an optimisation pass can drop a tag test already made, measured
  first.
- **Q5. Casts JuLC cannot check** (a sealed sub-interface, an open interface, a variant `TypeTests` rejects, a library
  sum the registry lacks; a nested sealed hierarchy is rejected earlier, `JULC0109`). Lean: stay views, as listed; each would need a tag range or a registry entry first.
- **Q6. `any`/`all` short-circuit.** Lean: a separate issue to make them stop at the first true (and false), which also
  fixes the trace divergence; not a blocker for D1, whose new failure is fail-closed.

## Implementation results

### M0: pins and cost baseline (2026-11-06)

- `Adr070CheckedCastsHarnessTest` (julc-difftest, 11 tests, all four levels against the JVM):
  - a downcast to `A` in seven positions (local, argument, return, traced operand, loop, lambda, and a cast to `C`, which
    has `A`'s layout), over every variant. Today it agrees on its own variant and on the nullary `D` (the unchecked read
    fails too), and is a MISMATCH on a variant with fields;
  - four casts that need no check (upcast, same type, a downcast guarded by `instanceof`, the pattern form) agree on every
    variant.
- `Adr070CompatibilityPinsTest` (julc-compiler): nine D2 casts frozen (bytes, diagnostics, positions, PIR at every level):
  an upcast, two same-type casts, a component typed V, `(V)(Object) x`, `(S)(Object) a`, `PlutusData`'s `IntData` and
  `MapData`, and `PlutusData.cast`. Two rejections that stay are frozen too: a bounded type parameter (`JULC0012`) and an
  `instanceof` of a captured wildcard (`JULC0126`). 110 new rows; none changed.
- Cost baseline: the FLAT size, at every level, of the `julc-examples-javac` validators (commit `1263de1`) that
  downcast `ScriptInfo`, compiled with this repository's compiler from the clone. 13 compile on their own; three need
  sibling sources and are left out. The numbers are in the M1 table.

### M1: the check (D1), docs (D4), the generator gate (2026-11-07)

- **Code** (`julc-compiler`):
  - `Casts.checked` decides D1 from javac's types and the cast's erasure. It needs `TypeTests.constructorOf` (V's parent
    and constructor, never a rejection), then `isOfThroughBounds` (same type first, then V's family; the cast's alone).
    The term is `readOnce(operand)`, then `if tagTest then operand else Error`, the `Error` typed as the operand and
    located at the cast.
  - `TypeTests.tagTest` is shared with `instanceof`, whose terms and diagnostics are unchanged. `constructorOf` returns
    nothing for a sum that permits a non-record (a nested sealed interface, which the subset rejects as `JULC0109`;
    review M1-S1).
  - `Lowering` wires `TypeTests` into `Casts`.
- **Tests:**
  - `Adr070CheckedCastsHarnessTest` (25 tests), every row over every variant at every level:
    - a downcast in 17 positions, all agreeing: local, argument, return, traced operand, loop, lambda, unused result,
      ignored argument, between two traces, a `?:` arm, `(A)(S) s`, a component typed S, a list head typed S, a pattern
      operand, an ADR-069 early-exit rest, and C's layout;
    - a ternary operand (`Record & S`) and its `var`, casts that `&&`, `||` and `?:` skip, #288's reproducer, a ledger
      `ScriptInfo` (minting passes, rewarding fails), and the D2 rows;
    - `any`, pinned as the fail-closed divergence (Q6).
  - ADR-069's R1 pin flips to agreement.
  - `Adr070CastsTest`:
    - `constructorOf` gives user and ledger variants and a sub-interface's own variant;
    - it gives nothing for `PlutusData`'s variants, a `@NewType`, or a sum permitting a sealed interface;
    - `isOfThroughBounds` reads `T extends S`, `U extends T` and `X extends S.A`, but not unbounded `W` or `Object`.
  - Generic entry methods and wildcard-typed locals are rejected before lowering (`JULC0012`), so the type-variable path
    has unit tests and no end-to-end row.
- **Frozen reference: no row moved** (I3), over all 5,608 compiler tests.
- **Cost:**
  - Measured on `S.A a = (S.A) s; return a.v()` with an `A`: at PV11_SAFE, CPU 606,574 → 1,065,390 (+458,816), memory
    2,328 → 3,893 (+1,565), and 20 → 34 bytes. With a call operand (`(S.A) pick(s)`): +458,816 CPU, +1,565 memory,
    27 → 42 bytes.
  - The 13 example validators grow by 14–19 bytes per cast at PV11_SAFE/COSTED, and by 18–19 at BASELINE and NONE.
    `Cip68Nft` casts twice and grows by 29–38. Sizes are 649 to 4,435 bytes at PV11_SAFE, so the growth is 0.3% to 2.3%.

    | Validator (PV11_SAFE) | Before | After |
    |---|---|---|
    | `OneShotMintPolicy` | 649 | 664 |
    | `CfProductValidator` | 1,189 | 1,204 |
    | `CfSimpleWalletValidator` | 1,228 | 1,242 |
    | `CfAuctionValidator` | 3,078 | 3,093 |
    | `CfLotteryValidator` | 3,449 | 3,463 |
    | `CfProxyValidator` | 4,174 | 4,189 |
    | `Cip68Nft` (two casts) | 2,076 | 2,105 |

- **Generator** (G1 r13):
  - gate `DOWNCASTS`, on by default (`V v = (V) s` in method bodies, loop bodies and switch arms, sometimes unused);
    steer `ISSUE_309` (half the subjects are fresh locals, three times in four of another variant);
  - `Adr070DowncastsPropertyTest`: 300 tries, all AGREE, 26% ending in a JVM `ClassCastException` matched by a UPLC
    failure with equal traces;
  - Jazzer `Adr070DowncastsFuzzTest`, 30 minutes: 32,812 executions, no crash;
  - all G1 tests and ADR-069's property and fuzz tests green under the new default.
- **Reviews:**
  - Code reviewer: APPROVED. Applied: M1-C1 (the docs and ADR list every cast that stays a view), M1-C2 (the
    bound-reading unit test), M1-C3 (`constructorOf` returns the parent), M1-C4 (cost in the release notes).
  - Semantics reviewer: APPROVED. Applied: M1-S1 (no check for a sum that permits a non-record), M1-S2 (the operand keeps
    its own type), M1-S3 and M1-S4 (docs). It confirmed that nested sealed hierarchies cannot reach lowering
    (`JULC0109`).
- **Docs:** release notes ("a cast to a sealed-interface variant is checked", with the cost), a troubleshooting
  question, AI starter pack 6.21, and the `Casts` class comment.

## Revision history

- r1 (2026-11-06): initial proposal.
- r2 (2026-11-06, responds to the review of `76c54a97` by Codex (F1, F2), a semantics reviewer (S1–S8) and a code
  reviewer (C1–C9)):
  - D1's case redefined: a target whose erasure is a variant of a registry `SumType` (so `PlutusData`'s variants are D2's,
    C1 P1), from an operand of that family including intersections and bounded type variables (F1 P1, S1 P1, C2).
  - D1 names the `TypeTests` refactor (`variant`, `tagTest`) and the located, typed `Error` (C3, C4).
  - D2 and D3: `PlutusData` variants, sealed sub-interfaces, open interfaces and rejected variants stay views; no new
    rejection; Q5 (S3, S4, S5, C7).
  - Risks: `any`/`all` widen to a fail-closed divergence (S2, Q6); tags through views (S8).
  - Compatibility: downstream pins listed; measurement method (C5). I3 expects no frozen row to move (C6).
  - Verification: unused casts, traces around a failing cast, skipped operands, D2 compatibility pins, guards, ternary
    and type-variable rows, multi-purpose default handler (F2, S6, C8, C9).
  - References: 5.1.6.1, 5.1.6.3 and 15.16 added (S7).
- r3 (2026-11-06, responds to round 2 of `b6af2c48`: semantics APPROVED (S9–S12), code APPROVED (C10–C12), Codex
  F3–F5):
  - `TypeTests` split: `constructorOf` and `tagTest` are shared; `instanceof` keeps its own operand rule, diagnostics and
    terms, so its frozen rows stay; the same-type exemption is the cast's (F3 P1).
  - D1 decides from javac's types and the erasure only, never an ADR-059 environment; bounded type parameters stay
    rejected and are pinned; `isOf` reads type variables and captured wildcards through their bounds (F4, C10, S10, C12).
  - The `Error` is typed as the then-branch is (C11).
  - Verification: `(A)(S) x` is one check; accessor and component rows split by attributed type; the harness counts
    `ClassCastException` as a failure (F5, C9).
  - D2 and Q1: unbounded type variables and open super-interfaces stay views; a `ChooseData` check of `PlutusData`'s
    variants recorded as a follow-up; `PlutusData`'s wording (S9, S11, S12).
- r4 (2026-11-06, responds to Codex's round 3 of `dde2a487`: APPROVED, F3–F5 resolved, F6 P2): the bound-aware
  predicate is the cast's alone; `instanceof` keeps `isOf`, and a captured-wildcard `instanceof` stays `JULC0126`,
  pinned (F6).
- Accepted (2026-11-06).
