# ADR-067: Switch guards, record patterns, cross-type record `equals`, and records with two sealed parents

**Status:** Proposed (2026-10-30). Design only; the implementation will follow in the same PR after acceptance.
**Date:** 2026-10-30
**Label:** `semantic-consistency`
**Risk class:** correctness-sensitive, **high**. Every defect below is a silent miscompile: a validator built with
it can accept a transaction that its own Java tests reject.
**Programme:** step S5 of [ADR-062](062-jvm-uplc-semantic-consistency-programme.md) (close catalogue entries at
their source), with evidence from [ADR-063](063-differential-jvm-uplc-test-harness.md)
**Issues:** [#275](https://github.com/bloxbean/julc/issues/275) (guards ignored),
[#276](https://github.com/bloxbean/julc/issues/276) (record patterns dropped),
[#277](https://github.com/bloxbean/julc/issues/277) (cross-type record `equals`),
[#279](https://github.com/bloxbean/julc/issues/279) (a record with two sealed parents)
**Relates to:** [ADR-015](verification/015-strict-on-chain-data-boundaries.md) (strict boundaries),
[ADR-038](038-switch-pair-case-lowering.md) and [ADR-041](041-integer-case-dispatch.md) (switch dispatch),
[ADR-048](048-loop-conditional-local-updates.md) and [ADR-050](050-if-loop-continuations.md) (switch arms in
loops, join lambdas), [ADR-051](051-switch-field-decoding.md) (field decoding), [ADR-060](060-generated-binder-namespace.md)
(generated names), [ADR-066](https://github.com/bloxbean/julc/pull/278) (generator G1, which depends on this ADR),
[#204](https://github.com/bloxbean/julc/issues/204) (`instanceof` binding scope)
**Base:** `fd605e77` (head of PR #253, `feat/063-jazzer-fuzzing`). Every code citation is at this commit.

## Context

JuLC lowers a `switch` expression over a sealed interface to one `PirTerm.DataMatch` with one branch per
constructor (`PirGenerator.generateSwitchExpr`, `PirGenerator.java:2370`). Four reported defects come from this
area and from record encoding; each was reproduced at the base with the ADR-063 harness (Evidence, below).

| Issue | Construct | JVM | JuLC (all four optimization levels) | Cause at the base |
|---|---|---|---|---|
| #275 | `case A x when cond ->` | 2 | 1 | JavaParser parses the guard (`SwitchEntry.getGuard()`), but nothing in the repository reads it (`grep -rn getGuard` finds no caller). `PatternMatchDesugarer.buildDataMatch` keeps the first entry per variant (`PatternMatchDesugarer.java:34-37`); later entries for the same variant are dropped |
| #276 | `case A(var v) ->` | -3 | -99 (the `default` arm) | The label loop handles only `TypePatternExpr` (`PirGenerator.java:2401`); a `RecordPatternExpr` label is skipped without a diagnostic, so `default` covers the variant. Without `default` the error is a misleading "not exhaustive. Missing cases: A" |
| #277 | `new R1(a).equals(new R2(a))` | false | true | `equals` on `RecordType`, `SumType` and `DataType` lowers to `EqualsData` (`TypeMethodRegistry.java:364-375`). Every standalone record is `Constr 0`, so two record classes with equal fields are equal |
| #279 | `record B(...) implements S, T` | 21 | 11 | A variant has one constructor tag. `TypeResolver` maps each variant to one sum type, and a second sealed parent overwrites the first (`variantToSumType.put`, `TypeResolver.java:184`, `:222`). `B` is built with its position in one parent and read with its position in the other |

The maintainer's decision for #275 and #276 is to implement guards and record patterns with Java's meaning,
not only to reject them. #277 and #279 are representation problems: #277 has a Java-exact answer that needs no
runtime type information (the static types decide it), and #279 cannot be represented with one tag per value.

### Current behaviour in detail

- **Lowering.** `generateSwitchExpr` evaluates the selector once, compiles each `TypePatternExpr` arm with the
  variant's fields bound under internal names (`#__pfield-<var>-<i>`, `PirGenerator.java:1616`), compiles a
  `default` body once (`:2395`) and places that same term in every uncovered constructor's branch (`:2454-2465`),
  so `UplcGenerator` emits it once per uncovered constructor. `UplcGenerator.generateDataMatch`
  (`UplcGenerator.java:520`) lets the scrutinee once and dispatches by tag: an equality chain ending in `Error`,
  or one integer `Case` under PV11_SAFE (ADR-041, which relies on exactly one branch per constructor, in tag order).
  `buildBranchFieldExtraction` (`:595`) decodes every field of the selected constructor with
  `PirHelpers.wrapDecode` (`:612`) before the branch body (ADR-051), and binds the pattern variable to the
  scrutinee Data.
- **Checks.** Exhaustiveness is "every constructor has a matched entry, or there is a `default`" (`:2454-2481`), with
  an uncoded message; the catalogue has a planned, never-emitted `JULC0005` for it. Nothing checks dominance, a
  constant-false guard, the effectively-final rule for guard variables, or fall-through into a pattern. A selector
  that is not a sealed interface is rejected uncoded (catalogue: planned `JULC0007`).
- **`instanceof`.** `generateInstanceOf` (`:2515`) compares the constructor tag with `EqualsInteger` and accepts only
  a `TypePatternExpr`; anything else is "Unsupported instanceof pattern" (`:2540`), uncoded. The pattern variable is
  bound only when the `instanceof` is the whole condition of an `if` outside a loop (#204).
- **Loops.** `LoopBodyGenerator.validateSwitchExpressionUpdates` (`LoopBodyGenerator.java:105`) collects the arm's
  `TypePatternExpr` names and rejects updates of enclosing variables from an arm (ADR-048, #162). Guards are not
  visited.
- **Parser.** Every JuLC entry point parses at `LanguageLevel.JAVA_21` (`JulcCompiler.java:265`, `:743`, `:839`,
  and the library, introspection, CLI and tools parsers). JavaParser 3.26.3 has `RecordPatternExpr`,
  `TypePatternExpr` and `SwitchEntry.getGuard()`.

## Goals and non-goals

**Goals**

- Guards and record patterns in `switch` expressions over sealed interfaces behave as in Java SE 21, at every
  optimization level and target, on every VM.
- JuLC rejects, with coded diagnostics, every switch shape that javac 21 rejects within the constructs it accepts,
  because JuLC compiles source without javac (testkit `forSource`, playground, Elci's library provider).
- `equals` between values whose static types cannot hold the same class is `false`, as in Java.
- A record that cannot be represented with one constructor tag is rejected.
- Programs that use none of these constructs compile to byte-identical scripts.
- Nothing is skipped silently: every case label is lowered or rejected with a code.

**Non-goals**

- `switch` statements (`SwitchStmt`), `case null`, constant labels, the total type pattern of the selector's own
  type (`case S s`), and switches over types other than sealed interfaces: unchanged in this ADR (see Related
  findings), except where D3 opens record-typed selectors as an open question.
- Unnamed patterns and variables (`_`). They are not Java 21: javac `--release 21` rejects them ("unnamed variables
  are not supported in -source 21"), and JuLC's parser rejects them as a parse error.
- Generic records (rejected today: "Unknown type: V").
- `instanceof` binding scope in general (#204). D3 decides only `instanceof` with a record pattern.
- `==` on records (ADR-062 T4) and `byte[]` components (T5): unchanged, owned by S5's reference-equality decision.
- Optimizing pattern dispatch (merging tests across arms into a decision tree).

## Pinned normative references

- **JLS** — *The Java Language Specification, Java SE 21 Edition*,
  `https://docs.oracle.com/javase/specs/jls/se21/html/`, chapters 6, 14 and 15, fetched with `curl` on 2026-10-30.
  The quotations below are copied from those pages.
  - **§14.11.1 (switch blocks).** "A case label with a case pattern may have an optional when expression, known as
    a guard, which represents a further test on values that match the pattern. A case label is said to be
    unguarded if either (i) it has no guard, or (ii) it has a guard that is a constant expression (§15.29) with
    value true; and guarded otherwise." Guard rules: "A guard must have type boolean or Boolean. Any local
    variable, formal parameter, or exception parameter used but not declared in a guard must either be final or
    effectively final (§4.12.4). … A guard cannot be a constant expression (§15.29) with the value false."
    Dominance: "It is a compile-time error if any switch label in a switch block is dominated. … A case label with a
    case pattern q is dominated if there is a preceding unguarded case label in the switch block with a case
    pattern p, and p dominates q (§14.30.3)." "A guarded case label with a case pattern is dominated by a case
    label with the same pattern but without the guard." "On the other hand, a guarded case label with a case
    pattern is not considered to dominate an unguarded case label with the same case pattern." "It is a
    compile-time error if any of the following holds: There is a default label in the switch block that precedes
    a case label with a case pattern. …" Fall-through: "It is a compile-time error if, in a switch block that
    consists of switch labeled statement groups, a statement is labeled with a case pattern that declares one or
    more pattern variables, and either: An immediately preceding statement in the switch block can complete
    normally (§14.22), or The statement is labeled with more than one switch label."
  - **§14.11.1.1 (exhaustive switch blocks).** "The set containing all the case constants and case patterns
    appearing in an unguarded case label … is non-empty and covers the type of the selector expression." Covering
    rules for sealed interfaces ("for every permitted direct subclass or subinterface D of C … P covers U"), for
    record classes ("P contains a record pattern p with a type that names R and for every record component of R of
    type U, if any, the singleton set containing the corresponding component pattern of p covers U"), and the
    rewrite rule ("P rewrites to a set Q and Q covers T", with "reduces to" and "equivalent to" defined there,
    including the `IPair` counter-example that is *not* exhaustive).
  - **§14.11.1.2 (which label applies at run time).** "If the value is not the null reference, then we determine
    the first (if any) case label in the switch block that applies to the value … If pattern matching succeeds and
    the case label is guarded, then the guard is evaluated. … If evaluation of the guard … completes abruptly for
    some reason, the process of determining which switch label applies completes abruptly for the same reason.
    Otherwise, if the resulting value is true then the case label applies. … If the value is not the null
    reference, and no case label applies according to the rules of step 2, but there is a default label associated
    with the switch block, then the default label applies."
  - **§14.30.1 (kinds of patterns).** "A record pattern is used to test whether a value is an instance of a record
    class type and, if it is, to recursively perform pattern matching on the record component values." "If the
    LocalVariableType is var then the type pattern must appear directly in the pattern list of a record pattern
    … The type of the pattern variable is the upward projection of T." "The length of the record pattern's
    pattern list must be the same as the length of the record component list." "It is a compile-time error if a
    record pattern contains more than one declaration of a pattern variable with the same name."
  - **§14.30.2 (pattern matching).** "A value v that is not the null reference matches a record pattern with type R
    and pattern list L if (i) v can be cast to R without raising a ClassCastException; and (ii) each record
    component of v matches the corresponding component pattern in L … Each record component of v is determined by
    invoking the accessor method of v corresponding to that component."
  - **§14.30.3 (properties of patterns).** Applicability ("A record pattern with type R and pattern list L is
    applicable at type T if (i) T is checked cast convertible to R, and (ii) for every component pattern p …
    applicable at the type of the corresponding component field in R"); unconditionality ("Note that no record
    pattern is unconditional because the null reference does not match any record pattern"); dominance ("A pattern
    p dominates a record pattern with type R if p is unconditional for R. A record pattern with type R and pattern
    list L dominates another record pattern with type S and pattern list M if (i) R and S name the same record
    class, and (ii) every component pattern, if any, in L dominates the corresponding component pattern in M").
  - **§6.3.3 (scope for pattern variables in case labels).** "If a case pattern in a guarded case label contains a
    declaration of a pattern variable then the pattern variable is definitely matched in the associated guard. A
    pattern variable is introduced by a guarded case label if it is introduced by the associated guard when true."
  - **§15.20.2 (`instanceof`).** "If the operand to the right of the instanceof keyword is a Pattern, then the
    instanceof keyword is the pattern match operator. … the result is true if the value matches the Pattern
    (§14.30.2)."
  - **§15.28.1 / §15.28.2 (switch expressions).** "It is a compile-time error if a switch expression is not
    exhaustive (§14.11.1.1)." "A switch expression is evaluated by first evaluating the selector expression. …
    If no switch label applies … a MatchException is thrown."
  - **§15.12.4 (run-time evaluation of method invocation).** Its subsections are, in order, "15.12.4.1. Compute
    Target Reference (If Necessary)" and "15.12.4.2. Evaluate Arguments": the receiver is evaluated before the
    argument, and both before the method runs.
- **`java.lang.Record.equals`**, Java SE 21 API,
  `https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Record.html`, fetched 2026-10-30: "The
  implicitly provided implementation returns true if and only if the argument is an instance of the same record
  class as this record, and each component of this record is equal to the corresponding component of the
  argument; otherwise, false is returned."
- **Reference implementation for oracle tests:** javac 25.0.2 with `--release 21` (the JDK on the development
  machine, `javac -version`). Where the JLS and javac disagree, the JLS is normative and the disagreement is
  recorded; none was found for the shapes in the Evidence section.

## Threat model and trust assumptions

- **Untrusted:** every transaction field, datum and redeemer. An attacker chooses which variant reaches a `switch`
  and the values its guards read. A guard is usually an authorization check (`case Withdraw w when
  signedByOwner(ctx) -> true`), so ignoring it (#275) or running `default` instead of an arm (#276) is a direct
  authorization bypass.
- **Malformed Data:** values reaching a `switch` from a typed boundary are validated by ADR-015's strict decoder.
  Values cast from raw Data inside a body are not. Java has no malformed values (the JVM side cannot build one), so
  Java says nothing about them; the requirement is *fail closed*: a malformed value may make the script fail, never
  select an arm that a well-formed value of some variant would not select, and never fall through to `default`
  because its tag is unknown.
- **Trusted:** javac as the reference for compile-time verdicts on Java 21 source; the JVM as the reference for
  run-time results on well-formed values (ADR-063 H1); the VMs' conformance (ADR-030).
- **Compile-time inputs** (source text) are trusted to be the contract author's, but JuLC must not accept source
  javac rejects, because the author's JVM tests could not have run it.

## Invariants

Each invariant names the decisions it constrains and is testable.

- **I1. One selector evaluation** (D2). The selector is evaluated exactly once, before any pattern test, guard or
  body; a failing selector fails the switch.
- **I2. Java arm selection** (D1, D2, D3). For every well-formed selector value, the arm that runs is the first arm
  in source order whose pattern matches and whose guard (if any) is true; otherwise `default`. An exhaustive
  switch without `default` has no other outcome for well-formed values.
- **I3. Guard evaluation discipline** (D1, D2). A guard runs at most once per evaluation of the switch, only after
  its pattern has matched and its bindings are initialized, and only if no earlier arm applied. Guards of arms whose
  pattern cannot match the value are never evaluated. A failing guard fails the switch; traces from guards and
  bodies appear in Java's order.
- **I4. Decoding never changes a well-formed outcome** (D2). Fields of the selected constructor are decoded once,
  before its first guard (ADR-051's strict selected-field decoding, extended); fields of a nested component are
  decoded only after that component's constructor test succeeds. Unselected constructors are never decoded.
- **I5. No fail-open on malformed tags** (D2). Every constructor test, top-level or nested, is a total dispatch
  over the sealed type's constructors (a `DataMatch`): an out-of-range tag fails; it never counts as "no match" and
  never reaches `default` or a later arm.
- **I6. Linear size** (D2). Each arm body, guard and `default` body is emitted once. Script size grows linearly in
  the number of arms (for nested patterns, in arms × constructors of the tested component), never exponentially.
  The pre-existing emission of `default` once per uncovered constructor is unchanged only for switches that keep
  the old lowering (I8).
- **I7. No silent skip** (D3, D7). Every case label, guard and pattern form is either lowered with Java's meaning or
  rejected with a coded diagnostic. No label is ignored.
- **I8. Byte stability** (D2, D5, D6). A `switch` with no guard and no record pattern compiles to the same PIR and
  the same script bytes as at the base, at every optimization level and target. An `equals` call whose operands'
  static class sets intersect (D5) compiles as before. A program without a record that has two sealed parents is
  unaffected by D6.
- **I9. Compile-time parity with javac 21** (D4). Within the supported constructs (D3), JuLC accepts a switch iff
  javac `--release 21` accepts it, for exhaustiveness, dominance, constant guards, effectively-final guard
  variables, fall-through into or from a pattern, duplicate pattern variables and arity.
- **I10. Bindings** (D3). A record-pattern component variable is bound under its source name (ADR-060), to the
  value `x.c()` yields in JuLC for that component, decoded to its declared type; it shadows a field of the same
  name exactly as in Java. Generated binders use `#` names chosen with `PirHelpers.hygienicName` where terms built
  elsewhere are in scope.
- **I11. Loops and value boundaries** (D2). A switch with guards or record patterns obeys the same ADR-048
  restrictions as one without: arms export only their value; guards update nothing; record-pattern component
  variables are case bindings for #162.
- **I12. Equals agrees with Java** (D5). If the static types of receiver and argument have disjoint class sets,
  the result is `false` after evaluating the receiver, then the argument, each once; a failure in either fails.
  Otherwise the existing structural comparison is used. A `false` is never derived from a PIR type that was
  inferred as `DataType` rather than declared as `PlutusData`.
- **I13. One constructor tag per record class** (D6). A record class is a variant of at most one sealed interface;
  its tag is its position in that interface's `permits` list (or implicit order) everywhere: construction,
  dispatch, schema and boundary checks.
- **I14. Determinism** (all). The same source and configuration give the same PIR, bytes and diagnostics.

## Decisions

### D1. Guard semantics

A guarded arm applies when its pattern matches and its guard evaluates to `true` (JLS §14.11.1.2). Arms are tried
in source order. A false guard moves to the next arm that can match the same value: a later guarded or unguarded
arm for the same variant, a later record pattern for it, and finally `default`. A guard is evaluated after the
pattern's variables are bound and may read them, method parameters, effectively final locals, static fields and
`@Param`s. A guard that is the constant `true` makes the label unguarded (JLS §14.11.1); a guard that is the
constant `false` is rejected (D4). A guard does not count towards exhaustiveness (JLS §14.11.1.1: only "unguarded
case label[s]" are considered).

Pattern variables introduced *by a guard* (JLS §6.3.3, e.g. `case P p when p.t() instanceof C c -> c.w()`) are
rejected with `JULC0074`. JuLC does not bind `instanceof` pattern variables outside an `if` condition (#204), so
today this fails closed with "Undefined variable: c" (Evidence G12); the code makes it actionable, and a nested
record pattern (`case P(C c) -> c.w()`) expresses the same test. A guard may still use `instanceof` as a plain
type test.

*Alternatives:* reject guards (keeps the subset small, but the maintainer decided to implement them, and guards
are the idiomatic way to write authorization arms); accept guards only as the last arm before `default` (a partial
semantics with its own diagnostic surface and no simpler lowering).

### D2. Lowering

The switch keeps one `DataMatch` over the selector with one branch per constructor in tag order, so ADR-038's pair
destructuring and ADR-041's integer `Case` dispatch apply unchanged. Inside each constructor's branch:

1. The constructor's fields are decoded once, as today (ADR-051), and bound under one set of generated names for
   that constructor. Every arm for that constructor reads them; nothing is decoded twice.
2. The arms that can match the constructor, in source order, form a chain. Each arm binds its own variables (a
   type pattern's variable to the scrutinee, a record pattern's component variables to the decoded fields), then
   tests its nested patterns (step 3), then its guard, and then runs its body. Every failure point of arm *i* goes
   to the continuation "try arm *i+1*". An unguarded arm with no nested test ends the chain; arms after it are
   still compiled (for diagnostics) but not emitted, because they can never run.
3. A nested pattern on a component of sealed type `T` is a nested `DataMatch` over that component with one branch
   per constructor of `T` (I5). The constructor(s) the pattern names continue the arm; every other constructor
   goes to the arm's continuation. A nested pattern on a component of a record type decodes that record's fields
   directly, as field access does today (no tag test exists for a standalone record). Type patterns and `var` on a
   component of exactly the component's type are unconditional.
4. When the chain is exhausted, control goes to `default`, or, for a switch javac proves exhaustive, to the
   existing terminal `Error` (unreachable for well-formed values).
5. **Joins.** A continuation reached from more than one site (an arm with several failure points, or `default`
   reached from more than one branch) is bound once as a unit lambda and called at each site, as ADR-050 does for
   branch joins. A continuation with one site is inlined. Join binders are named `#case_next` and `#case_default`
   via `PirHelpers.hygienicName` (ADR-060), because user terms are in their scope. A `default` join is bound
   outside the `DataMatch`; creating a lambda evaluates nothing, so I1 holds.
6. **Scope of the new lowering.** Only a switch that has at least one guard or record pattern uses steps 2–5. Any
   other switch takes today's path unchanged, including the duplication of `default` per uncovered constructor
   (I8). In a switch on the new path, uncovered constructors call the `default` join.

Guards are ordinary boolean PIR in an `IfThenElse`; arm bodies (expressions, `yield` blocks, colon groups ending in
`yield`) are compiled by `generateSwitchEntryBody` as today. Switches inside loop bodies need no new lowering:
`LoopBodyGenerator` treats a switch expression as a value boundary, and its validation is extended to record-pattern
variables and guards (I11). `PatternMatchDesugarer.buildDataMatch` stops choosing "the first entry per variant";
the per-constructor chain is built before it, so its one-branch-per-constructor contract with ADR-041 is kept.

*Illustrative only* (not the implementation): for `case A x when g -> b1; case A(var v) -> b2; default -> d`
the `A` branch is roughly `let x = data in if g then b1 else (let v = f0 in b2)`, the other branches call
`#case_default ()`, and `#case_default = \_ -> d` is bound before the `DataMatch`.

*Alternatives:*
- *Lower each arm to a separate tag test (`if tag == k && guard …`)*: re-evaluates the tag per arm, gives up
  ADR-041's `Case` dispatch, and treats an unknown tag as "no match", which falls through to `default` (violates I5).
- *Duplicate `default` and continuations instead of joins*: exponential in nested patterns, and #161's history
  (ADR-050: 107 → 5,283 bytes for six guarded loops) shows duplication is not acceptable.
- *Decode fields lazily per arm*: changes ADR-051's strictness for unused fields and decodes twice when two arms
  read the same field.
- *Desugar guards to nested switch expressions in the frontend*: duplicates bodies and loses source positions for
  source maps and the debugger (ADR-058).

### D3. Record patterns: the supported subset

Supported in `switch` expressions over sealed interfaces:

- A record pattern `V(p1, …, pn)` naming a variant `V` of the selector's sealed interface, with exactly `V`'s arity
  (JLS §14.30.1).
- Component patterns: `var x`; a type pattern whose type is the component's declared type (or, for a component of
  sealed type `T`, `T` itself); a type pattern naming a variant of the component's sealed type (a test); a nested
  record pattern naming the component's record type or a variant of its sealed type. Nesting depth is unbounded.
- Guards on record-pattern arms (D1), and record-pattern and type-pattern arms mixed in one switch.
- Components are bound in order, under their source names (I10); duplicate names are rejected (javac does, JLS
  §14.30.1).

Rejected with `JULC0076` (with a reason argument):

- A record pattern naming a type that is not constructor-encoded: `@NewType` records and the ledger hash types
  (`PubKeyHash`, `ScriptHash`, `ValidatorHash`, `PolicyId`, `TokenName`, `DatumHash`, `TxId`), which are byte
  strings or integers on-chain.
- A component type pattern that would need a run-time test JuLC cannot perform on Data, or that javac accepts but
  can never match: for example `IntData i` on a `PlutusData` component (a Data-kind test), or `PlutusData d` on a
  `BigInteger` component.
- A record pattern over a record that declares an explicit accessor method for a component. Java's record pattern
  invokes the accessor (JLS §14.30.2); JuLC's `x.c()` ignores explicit accessors today (Related findings, R6), so
  supporting both would create a second path to the same divergence. The general accessor defect needs its own fix.
- Generic records, and any other `PatternExpr` form.

`instanceof` with a record pattern stays rejected, now with `JULC0077` (pointing at `switch` and #204), not with
the uncoded "Unsupported instanceof pattern". #204 governs where `instanceof` binds variables at all; adding record
patterns to today's single supported `if` shape would add a second special case to a mechanism scheduled for
redesign. When #204 lands, `instanceof` record patterns can reuse D2's matcher (open question 3).

*Alternatives:* support only flat record patterns (`A(var v)`) first and reject nested ones (smaller, but nested
patterns are the Java 21 replacement for the guard-introduced bindings rejected in D1, and D2's matcher handles both
with the same mechanism; the milestones still deliver flat patterns first); reject record patterns entirely (the
maintainer decided against this).

### D4. Compile-time checks that JuLC owns

JuLC implements the JLS 21 rules itself, because source reaches it without javac. Each check runs on the AST before
lowering, so the diagnostics do not depend on the optimization level.

- **Exhaustiveness** (§14.11.1.1), with guarded labels excluded and `when true` counted as unguarded, including the
  record rules and the rewrite/reduce rules for record patterns. JuLC's type universe is small: sealed interfaces
  whose permitted subtypes are records (interface `extends` is rejected by `SubsetValidator`), record types, and
  leaf types that only an unconditional pattern covers. Diagnostic: `JULC0005`, promoted from planned to emitted,
  with the missing variant or pattern in the message.
- **Dominance** (§14.11.1, §14.30.3): a label dominated by a preceding unguarded label; a guarded label after the
  same unguarded pattern; a label after `when true` on the same pattern; `default` before a pattern label.
  Diagnostic `JULC0071`.
- **Constant-false guard** (§14.11.1): `JULC0072`. Constant-ness follows §15.29 for the boolean expressions JuLC
  supports.
- **Effectively final guard variables** (§14.11.1, §4.12.4): a local or parameter used but not declared in a guard
  that is assigned anywhere in the method (in JuLC, only loops may reassign) is rejected with `JULC0073`. Today such a
  guard compiles and reads the updated value (Evidence H1).
- **Fall-through into or from a pattern** (§14.11.1): in colon-form groups, a pattern label that declares variables
  and is reachable from a preceding group that can complete normally, or a group with more than one pattern label
  (javac reports "illegal fall-through to a pattern" / "from a pattern"). Diagnostic `JULC0075`.
- **Selector type:** a switch on anything other than a sealed interface keeps its rejection, now as `JULC0007`
  (promoted).

*Alternatives:* rely on javac (Gradle builds run javac on the same source, but testkit `forSource`, the
playground and Elci's library provider do not, and ADR-065's javac frontend is a spike); accept dominated labels
(their meaning is well defined by first-match, but the program is not Java, and G1's oracle cannot judge it).

### D5. Cross-type `equals`

For a call `r.equals(a)` whose receiver has a record, sealed-interface, sealed-variant or `PlutusData` static type,
JuLC computes the set of runtime classes each operand's *Java static type* can denote:

- a standalone record `R` (user or ledger): `{R}`;
- a sealed variant `V`: `{V}`;
- a sealed interface `S`: its permitted records (flat in JuLC, D6 makes the sets of different interfaces disjoint);
- `PlutusData` and its subtypes: the `PlutusData` classes. `PlutusData` is a sealed interface in `julc-core`
  (`PlutusData.java:22`), so no user record can be one;
- anything else, or a type JuLC cannot determine from the source: *unknown*.

If both sets are known and disjoint, the call compiles to `false`, after evaluating the receiver and then the
argument once each (JLS §15.12.4), so a failing operand still fails. Otherwise (overlapping or unknown) the existing
`EqualsData` lowering is kept. With D6 in place, overlapping sets mean the same sealed family or one of its variants,
whose tags are distinct and consistent, so structural comparison is exact (E4, E5). Each statically false call also
emits the warning `JULC0078` ("always false in Java"), because a validator almost never means it.

The classification must use the source-level type, not the PIR type: PIR inference falls back to `DataType` where
the Java type is a record (the comment at `PirGenerator.java:2376` describes one such case), and deciding `false`
from that would be wrong (I12).

**Scope.** This decision covers what #277 reports: user and ledger records, sealed interfaces and variants, and
`PlutusData`. The same Java rule makes `@NewType` and ledger hash comparisons across types false (Evidence E8, E11,
E12: `new Amt(a).equals(new Price(a))`, `PubKeyHash.of(a).equals(ScriptHash.of(a))` and
`PubKeyHash.of(a).equals(a)` are false on the JVM and true on-chain), but those types are erased to
`IntegerType`/`ByteStringType` in JuLC's type model, so the check needs nominal types that the PIR does not carry.
That extension is open question 2.

*Alternatives:*
- *Reject statically false `equals` as an error* (fail closed; a negated deny check such as
  `if (x.equals(blocked)) return false;` that was true on-chain becomes an error instead of silently never denying).
  Not chosen as the default because it rejects Java that javac accepts and the maintainer's stated expectation for
  #277 is agreement; the warning plus release note covers the risk. Open question 1.
- *Make records carry their class in the encoding* (a type tag field): changes every record's Data and schema; ruled
  out by ADR-015 and CIP-57 compatibility.
- *Compare tags and fields but add a class check at run time*: Data has no class to check.

### D6. Records with more than one sealed parent (#279)

A record that implements more than one sealed interface (whether listed in `permits` or discovered implicitly) is
rejected with `JULC0079`, naming the record and both parents. Only sealed interfaces registered as JuLC sum types
count; a record that also implements a plain interface (Evidence M5) is unaffected.

Rationale:

- A Data value has one tag. If the record's positions differ, one parent's `switch`, `instanceof`, `equals` and
  boundary check read it as another variant (Evidence M1: JVM 21, JuLC 11).
- Rejecting only differing positions is not enough. With equal positions the switch works (Evidence M2), but the two
  families now share tags with different meanings for their *other* variants, so D5's "overlapping class sets ⇒ same
  family ⇒ structural comparison is exact" fails: an `S` holding `A` and a `T` holding `C` at the same position with
  equal fields compare equal on-chain and unequal in Java (the mechanism of Evidence E9 and M6). Accepting equal
  positions would also make a later edit to one `permits` list turn an accepted program into a rejected one.
- No record in `julc-ledger-api`, `julc-stdlib`, `julc-core`, in-repo `julc-examples`, `../julc-examples` or
  `../julc-cip113-example` implements two interfaces (Evidence: `grep`), so the rule rejects nothing known.

Nested sealed hierarchies (`sealed interface U extends S permits B, C`) do not interact today: `SubsetValidator`
rejects every `extends` on an interface (`SubsetValidator.java:352-362`, "class inheritance is not supported
on-chain", uncoded; Evidence M3, M4). If a later ADR admits them, it must give `U`'s records one tag consistent with
`S` (I13); D6's check must then treat `S` and `U` as one family rather than two parents.

*Alternatives:* reject only differing positions (above); re-tag at an upcast (impossible: an upcast is the identity
in Java and invisible in the source); encode one tag per parent (a value would need several representations).

### D7. Diagnostics

All new rejections are coded in `julc-compiler/src/main/resources/diagnostics.json`, with `DiagnosticCodes`
regenerated, troubleshooting entries and an example each.

| Code | Constant | Severity | Emitted for |
|---|---|---|---|
| `JULC0005` | (existing, planned → emitted) | error | Non-exhaustive switch, guards and record patterns included |
| `JULC0007` | (existing, planned → emitted) | error | Switch selector that is not a sealed interface |
| `JULC0071` | `SWITCH_LABEL_DOMINATED` | error | Dominated label, `default` before a pattern label |
| `JULC0072` | `SWITCH_GUARD_CONSTANT_FALSE` | error | Guard that is the constant `false` |
| `JULC0073` | `SWITCH_GUARD_VARIABLE_NOT_EFFECTIVELY_FINAL` | error | Guard reading a reassigned local or parameter |
| `JULC0074` | `SWITCH_GUARD_PATTERN_VARIABLE_UNSUPPORTED` | error | Pattern variable introduced by a guard |
| `JULC0075` | `SWITCH_PATTERN_FALLTHROUGH` | error | Fall-through into or from a pattern label in colon form |
| `JULC0076` | `CASE_PATTERN_UNSUPPORTED` | error | Pattern outside D3's subset, with the reason |
| `JULC0077` | `INSTANCEOF_RECORD_PATTERN_UNSUPPORTED` | error | `instanceof` with a record pattern |
| `JULC0078` | `EQUALS_ALWAYS_FALSE` | warning | `equals` between types with disjoint class sets (D5) |
| `JULC0079` | `RECORD_MULTIPLE_SEALED_PARENTS` | error | A record implementing two sealed interfaces (D6) |

**Code reservation.** The base's highest code is `JULC0059`. Open PRs use `JULC0060`–`JULC0066` (#272,
`fix/257-coded-loop-diagnostics`) and `JULC0070` (#271, `fix/t25-integer-literals`); `JULC0067`–`JULC0069` are
used by no branch but were skipped by #271, so this ADR does not take them. It reserves `JULC0071`–`JULC0079`.
Codes are re-checked against every origin branch before the implementation commit.

### D8. Catalogue and documentation

ADR-062 gets one row per issue, provisionally **T26** (#275), **T27** (#276), **T28** (#277, with the erased-nominal
cases of E8/E11/E12 noted), **T29** (#279), and **T30** (record explicit accessors and explicit `equals`, Related
findings R6/H5), owned by S5. T24 and T25 are taken by #268 and #270. Final IDs are assigned when the rows merge.
Documentation: `getting-started.md` §8.2/§8.3 (guards, record patterns, the `instanceof` limit), the advanced
guide's "Switch Best Practices", troubleshooting entries for each code, both AI starter packs, and release notes.

## Affected stages and modules

| Module | Stage | Change |
|---|---|---|
| `julc-compiler` | Validation (`SubsetValidator` or a new switch checker) | D4 checks, D6 check at type registration, `JULC0074`/`0077` |
| `julc-compiler` | Type registration (`TypeRegistrar`, `TypeResolver`) | D6: detect a second sealed parent instead of overwriting |
| `julc-compiler` | PIR generation (`PirGenerator`, `PatternMatchDesugarer`, `LoopBodyGenerator`) | D2/D3 lowering; guard and record-pattern variables in the ADR-048 validation |
| `julc-compiler` | Instance methods (`TypeMethodRegistry`, `PirGenerator`) | D5 classification before the `EqualsData` handler |
| `julc-compiler` | Diagnostics catalogue | D7 |
| `julc-compiler` | Debug metadata (`DebugMetadataCollector`) and source maps | Positions and `PATTERN` scopes for guards and component variables |
| `julc-decompiler` | `DataMatchRecognizer` | Guarded chains decompile as generic code; recognition is not required (Risks) |
| `julc-cli` | Lint rules (`SwitchFieldShadowRule`) | Record-pattern component names, if the rule should cover them |
| `julc-difftest` | Regression cases | Pins and flips (Verification) |
| `docs` | Guides, troubleshooting, release notes | D8 |

No VM, ledger, stdlib, boundary-encoding or optimizer-rule change. UPLC dispatch primitives are unchanged; the new
shapes are ordinary `DataMatch`, `IfThenElse`, `Let`, `Lam` and `App`.

## Compatibility

- **Programs that change meaning.** Every program with a guard or a record pattern in a `switch` was miscompiled
  (wrong arm) or rejected with a misleading message; after this ADR it runs Java's arm. Every program with a
  statically false `equals` between records, sealed types or `PlutusData` returned the structural result; it now
  returns `false`, with `JULC0078`. Every program with a record that has two sealed parents is now rejected.
  Deployed scripts are unaffected (their hashes are fixed); recompiling changes their hash only if they use one of
  these constructs.
- **Newly rejected programs** are those javac 21 rejects (D4), patterns outside D3's subset (previously silently
  dropped or rejected uncoded), and D6's records.
- **Byte stability (I8).** Everything else compiles byte-identically. The implementation must show this against the
  base for in-repo `julc-examples`, `../julc-examples`, the stdlib, the ADR-038/041 switch goldens and the Blaster
  fixtures under `verification/blaster/fixtures`.
- **ADR-062 P6** asks for an opt-in release before a byte-changing or rejecting change becomes the default.
  Precedent for miscompile fixes (#207 `JULC0059`, #264 T22, #271 T25) ships them on by default with a release note,
  and this ADR proposes the same: an opt-in release for a known wrong arm would keep the wrong arm as the default.
  Open question 5.
- **Public API.** None. Diagnostics are additive; `JULC0005`/`JULC0007` change status from planned to emitted.

## Implementation milestones

Each milestone is one or more commits in this PR, reviewed against this ADR. "Oracle tests" means: the same source
compiled with javac and run on the JVM, compared with JuLC at `NONE`, `BASELINE`, `PV11_SAFE` and `PV11_COSTED`, on
the Java VM and the Scalus VM (and Truffle where the existing switch suites run it).

| Milestone | Scope | Entry gate | Exit criteria | Invariants / tests |
|---|---|---|---|---|
| **M0** | Pins | ADR accepted | `DIVERGE` `.diffcase` pins for #275, #276, #277 and #279 saved under `julc-difftest/src/test/resources/regressions/` on the base, replaying as recorded; codes re-checked (D7) | Evidence G1, R1, E1, M1 |
| **M1** | Guards on type patterns: D1, D2 (steps 1, 2, 4, 5, 6), D4 checks except record-pattern exhaustiveness, `JULC0071`–`0075`, ADR-048 validation of guards | M0 | #275 pin flips to `AGREE`; oracle tests for: guard reads its binding, a parameter, a static field; several guarded arms per variant with different binding names; guarded arm then unguarded; guarded arm then `default`; failing guard; guard traces in order; `when true`; colon form with `yield`; nested switch in a guarded arm; switch with guards inside for-each and while bodies; byte-stability check (I8) green | I1–I3, I6, I8, I9, I11 |
| **M2a** | Flat record patterns: D3 for `V(var x, T y)`, guards on them, mixing with type patterns, exhaustiveness and dominance for flat patterns, `JULC0076`, `JULC0077` | M1 | #276 pin flips; oracle tests incl. `case A(var v) -> …; case A x -> …` (legal, second arm dead), record pattern before `default`, explicit component types, field shadowing by a component name; negative tests for every `JULC0076` reason | I2, I4, I7, I9, I10 |
| **M2b** | Nested record patterns: D2 step 3, the JLS rewrite/reduce exhaustiveness rules, nested dominance | M2a | Oracle tests for `P(C(var w))`/`P(D(var w))`, `Pair(C c, T t)`, the JLS `Box` and `IPair` examples (exhaustive / not exhaustive), guards on nested patterns; malformed-tag tests (I5) on a component cast from raw Data: an out-of-range tag fails and never reaches `default`; size measurement linear in arms (I6) | I4–I6, I9 |
| **M3** | D6: reject a second sealed parent, `JULC0079` | M0 (independent of M1/M2) | #279 pin becomes a `REJECT` case; javac accepts and runs it (21), JuLC rejects with `JULC0079`; equal positions also rejected; a record with one sealed parent and a plain interface still compiles byte-identically | I8, I13 |
| **M4** | D5 cross-type `equals`, `JULC0078` | M3 (D5's exactness relies on disjoint families) | #277 pin flips; oracle tests for E1–E10 (R1/R2, record vs tag-0 variant, record vs tag-1 variant, sealed vs its variant, two variants, sealed vs record, record vs `PlutusData` with the same encoding, two sealed families, failing argument); unknown static type keeps structural equality; I8 for intersecting types | I8, I12 |
| **M5** | Documentation, catalogue rows, release notes, AI starter packs; full validation | M1–M4 | `./gradlew build -PskipSigning=true` green; external `../julc-examples` built against the published snapshot; byte-stability report attached to the PR; ADR status line and evidence updated | All |

M1 and M3 can proceed in parallel. ADR-066's G1 generates guards, record patterns and cross-type `equals` only once
M1, M2 and M4 are below it in the stack (ADR-066 open question 1).

## Verification and test strategy

Evidence must not come from the code under test: the oracle is javac and the JVM (ADR-063 H1).

1. **Oracle tests** (above) in `julc-compiler` tests, in the style of `LoopFieldUpdateTest` and ADR-051's
   `SwitchFieldDecodeTest`: each case asserts the JVM result and the JuLC result at every level and VM.
2. **Regression cases** in `julc-difftest`: the M0 pins flip to `AGREE` (or `REJECT` for #279) in the commit that
   fixes them, and every new reproducer from review is saved (ADR-063 D8).
3. **Compile-time parity property (I9).** A generated corpus of small switches over a fixed universe (sealed `S`
   with two records, sealed `T` with two records, `P(T)`, `Pair(T, T)`): every sequence of up to four labels drawn
   from type patterns, flat and nested record patterns, with and without guards (`when true`, `when false`, a
   non-constant guard), with and without `default`. For each, javac `--release 21` accept/reject must equal JuLC's
   accept/reject, and a JuLC rejection must carry the expected code. Accepted programs are also run both ways on a
   few inputs.
4. **Property tests** (jqwik) over guard thresholds and component values for the M1/M2 shapes.
5. **Byte stability (I8).** Compile every program in the corpora listed under Compatibility at the base and at the
   head, at every level and target, and require identical bytes for programs without guards, record patterns,
   statically false `equals` or two-parent records; record the counts in the PR.
6. **Mutation checks.** Each of these deliberate faults must make at least one test fail: ignore guards; keep only
   the first arm per variant; evaluate the guard before binding; decode a nested component before its tag test;
   treat an unknown nested tag as "no match"; duplicate instead of join (size test); compile same-family `equals` to
   `false`; derive `false` from an inferred `DataType`; accept a second sealed parent.
7. **Long run.** ADR-066's G1 generates these constructs once the milestones are below it and is the continuing
   check; its rediscovery of #275–#277 on the pre-fix base is ADR-066's own gate.

## Production and audit gates

- Accepting this ADR is design acceptance only; it does not certify the implementation.
- Before merge: independent review of the diff against D1–D7 and I1–I14, the byte-stability report, and the
  external examples run. JuLC remains experimental; this ADR makes no production-safety claim.
- Blaster fixtures: if any fixture uses a construct whose bytes change, follow the artifact refresh procedure; the
  expectation (to be confirmed in M5) is that none does.

## Risks

- **Exhaustiveness and dominance drift from javac.** The JLS rewrite rules are intricate; JuLC could accept a
  switch javac rejects. Mitigation: implement the rules as written, and the parity property (Verification 3).
  Accepting a non-exhaustive switch would still fail closed at run time (terminal `Error`), not pick an arm.
- **Join placement and ADR-060.** A `#case_default` bound outside the `DataMatch` must not capture user names, and
  its body must resolve names in the switch's enclosing scope. Mitigation: `hygienicName`, the binder lint tests.
- **Interaction with PV11 passes** (ADR-042/044 sharing, ADR-043 promotion) on the new shapes. Mitigation: all four
  levels in every oracle test.
- **Decompiler output.** Guarded chains will decompile as generic `if`/`Case` code rather than a Java `switch` with
  guards; acceptable, noted for the decompiler.
- **D5 false positives.** A wrong class-set computation would turn a true comparison into `false`. Mitigation: unknown
  ⇒ structural; never from inferred `DataType`; mutation check 6.
- **Simple-name collisions.** `RecordType` names are simple names; two records with the same simple name in
  different packages must not be classified as one class. The implementation uses fully qualified names for D5 and
  D6.

## Related findings (out of scope)

Found while writing this ADR; each reproduced at the base with the ADR-063 harness (Java VM, all four levels) unless
stated. Issues are to be filed.

- **R6. Explicit record accessors are ignored.** `record Neg(BigInteger v) { public BigInteger v() { return
  v.negate(); } }`, `new Neg(a).v()`: JVM -5, JuLC 5. Proposed T30.
- **H5. Explicit `equals` in a record is ignored.** A record whose `equals` compares absolute values: JVM 1, JuLC 0
  (`EqualsData`). Proposed T30.
- **H6. For-each over `JulcList.of(a, b)` of `BigInteger`s.** `acc = acc.add(k)` fails on-chain ("AddInteger:
  expected integer, got VCon(Data[])"); JVM 7.
- **G9. A `switch` statement** (`switch (s) { case A x -> { return …; } … }`) is rejected with the misleading,
  uncoded "Method 'm' may not return a value on all execution paths".
- **K3.** The total pattern `case S t` on a selector of type `S` is rejected uncoded as "Unknown variant in switch".
- **K1.** Generic records are rejected uncoded ("Unknown type: V. Did you mean: A?").
- **M3/M4.** Nested sealed interfaces are rejected uncoded ("class inheritance is not supported on-chain").
- **Unnamed patterns.** JuLC's parse error is uncoded. The harness's `JvmExecutor` compiles with the JDK's default
  release (25), where `_` is legal, so a generator must not emit it (ADR-066).
- **Top-level `instanceof` is lenient on malformed tags.** `generateInstanceOf` compares the tag with
  `EqualsInteger`, so an out-of-range tag is "not an instance" and runs the `else` branch, while a `switch` fails on
  it (code reading; not reproduced, as it needs a malformed value cast from raw Data).
- **Container equality.** `contains`, `indexOf` and map lookups compare elements with `EqualsData`, so D5's
  cross-type cases also arise inside collections (code reading).
- **`Builtins.trace` with one argument** crashes the compiler (`IllegalArgumentException`, an ADR-063 `Crash`)
  instead of reporting a diagnostic; javac rejects the call anyway.
- **`default` duplication.** A `default` covering *k* uncovered constructors is emitted *k* times on today's path
  (code reading, `PirGenerator.java:2454-2465`); D2 keeps it for byte stability.

## Open questions

1. **Statically false `equals`: `false` plus a warning, or an error?** Options: (a) `false` with `JULC0078` as a
   warning (D5); (b) an error. (b) fails closed for code that relied on the on-chain structural result, including
   negated deny checks, but rejects Java that javac accepts. Lean: (a), as the issue's expected behaviour; revisit
   if the M5 corpus scan finds any statically false `equals` in the example repositories.
2. **Erased nominal types** (`@NewType`, ledger hash types, and those against `BigInteger`/`byte[]`). Options:
   (a) extend D5 here, carrying nominal types from the source; (b) a follow-up issue and catalogue row. Lean: (b);
   the type model erases them, and ADR-065's javac frontend would supply exact static types. The evidence (E8, E11,
   E12) is recorded so the follow-up starts from reproducers.
3. **`instanceof` with record patterns.** Options: (a) reject with `JULC0077` until #204 (D3); (b) support them now
   in the one shape where `instanceof` binds today. Lean: (a).
4. **Record-typed selectors** (`switch (r) { case R1(var x) -> x; }`, accepted by javac, rejected by JuLC as not a
   sealed interface). Options: (a) include them in M2b, since nested record components already need the same
   matcher; (b) leave them rejected (`JULC0007`). Lean: (a), if M2b's review agrees the scope is small.
5. **ADR-062 P6 opt-in.** Lean: no opt-in release for these fixes, following the precedent above, with release
   notes; the maintainer may prefer an explicit statement in ADR-062.
6. **#279: all second sealed parents, or only differing positions?** Lean: all (D6), for the `equals` reason and
   stability under edits to `permits`.
7. **Catalogue IDs and diagnostic codes** may collide with work merged in parallel (ADR-066 and the open fix PRs).
   They are provisional until the implementation commit re-checks them.

## Evidence

All at `fd605e77` on macOS, JDK 25.0.2, Java VM (`DiffConfig.allLevels()`), via a temporary probe test in
`julc-difftest` that called `DiffRunner.standard().run(new DiffCase(...))` and was deleted afterwards:

```bash
./gradlew :julc-difftest:test --tests 'org.julclang.difftest.Adr067ProbeTest'   # temporary, deleted
```

Declarations shared by the probes: `sealed interface S permits A, B`, `record A(BigInteger v)`, `record
B(BigInteger v)`; `sealed interface T permits C, D` with `C(BigInteger w)`, `D(BigInteger w)`; `sealed interface U
permits P, Q` with `P(T t)`, `Q(BigInteger z)`; standalone `R1(BigInteger x)`, `R2(BigInteger x)`; `@NewType`
records `Amt`, `Price`. JuLC's result was the same at `NONE`, `BASELINE`, `PV11_SAFE` and `PV11_COSTED` in every row.

| ID | Shape | JVM | JuLC |
|---|---|---|---|
| G1 | #275 reproducer (`a = -3`) | 2 | 1 |
| G2 | Same, guard true (`a = 20`) | 1 | 1 |
| G3 | Guarded `A` arm, then `default` | 2 | 1 |
| G4 | Guard reads a parameter (`b < 0`) | -1 | 5 |
| G5 | Two guarded `A` arms, different binding names, then unguarded | -3 | 1 |
| G6 | Only a guarded arm for `A`, no `default` | javac: "does not cover all possible input values" | accepted, 1 |
| G7 | Guarded arm after the same unguarded pattern | javac: "dominated by a preceding case label" | accepted, 1 |
| G8 | `when false` | javac: "guard that is a constant expression with value 'false'" | accepted, 1 |
| G8b | `when true` as the only `A` arm | 1 | 1 |
| G9 | Guards in a `switch` statement | 2 | rejected: "may not return a value on all execution paths" |
| G11 | Colon form, `yield`, guarded | 2 | 1 |
| G12 | Guard introduces `instanceof C c`, arm reads `c` | 7 | rejected: "Undefined variable: c" |
| H1 | Guard reads a loop-updated local | javac: "must be final or effectively final" | accepted, 1 |
| H2 | `case A(var v) -> v; case A x -> 10` | 3 | 10 |
| H4 | Guard divides by zero | `ArithmeticException` | 1 (guard never evaluated) |
| H7 | Guarded switch inside a for-each | 111 | 102 |
| R1 | #276 reproducer, with `default` | -3 | -99 |
| R2 | Record pattern, no `default` | -3 | rejected: "not exhaustive. Missing cases: A" |
| R3 | `s instanceof A(var v)` | -3 | rejected: "Unsupported instanceof pattern" |
| R4 | Nested `P(C(var w))`, `P(D(var w))`, `Q(var z)` | -5 | rejected: "Missing cases: P, Q" |
| R5 | `case A(var _)` | 1 | parse error: "'_' is a reserved keyword" |
| R6 | Explicit accessor `v()` returning `v.negate()` | -5 | 5 |
| R7 | `A(BigInteger v) when …`, `A(BigInteger v)`, `B(BigInteger v)` | 4 | rejected: "Missing cases: A, B" |
| E1 | #277: `R1` vs `R2` | false | true |
| E2 | `R1` vs variant `A` (tag 0) | false | true |
| E3 | `R1` vs variant `B` (tag 1) | false | false |
| E4 | `S` holding `A` vs `A` | true | true |
| E5 | `A` vs `B` | false | false |
| E6 | `S` vs `R1` | false | true |
| E7 | `R1` vs `PlutusData` `Constr 0 [a]` | false | true |
| E8 | `@NewType Amt` vs `@NewType Price` | false | true |
| E9 | `S` holding `A` vs `T` holding `C` (both tag 0) | false | true |
| E10 | `R1` vs `R2(a / 0)` | `ArithmeticException` | `QuotientInteger: division by zero` (agree) |
| E11 | `PubKeyHash.of(a)` vs `ScriptHash.of(a)` | false | true |
| E12 | `PubKeyHash.of(a)` vs `a` (`byte[]`) | false | true |
| H5 | Record with an explicit `equals` | 1 | 0 |
| H6 | For-each over `JulcList.of(a, b)` summing | 7 | fails: "AddInteger: expected integer, got VCon(Data[])" |
| K1 | Generic `record Box<V>(V value)` | 3 | rejected: "Unknown type: V" |
| K2 | `switch (r1) { case R1(var x) -> x; }` | 3 | rejected: "requires a sealed interface type" |
| K3 | `case A x -> 1; case S t -> 2` | 1 | rejected: "Unknown variant in switch: S" |
| M1 | #279 reproducer (`B` in `S` at 1, in `T` at 0) | 21 | 11 |
| M2 | Same, `B` at position 0 in both | 21 | 21 |
| M3 | `sealed interface U extends S permits B, C` | 202 | rejected: "class inheritance is not supported on-chain" |
| M4 | Same hierarchy, `case U q` on `S` | 2 | rejected: same |
| M5 | Variant also implementing a plain `interface Marker` | 5 | 5 |
| M6 | #279 declarations, `S` holding `B` vs `A` | false | true |

**javac `--release 21`** (javac 25.0.2), each a one-class file:

| Shape | javac |
|---|---|
| `case A x -> …; case A x when g -> …` | error: dominated |
| `case A x when false -> …` | error: constant expression with value 'false' |
| `case A x when true -> …; case A x -> …` | error: dominated |
| Only a guarded arm for `A`, no `default` | error: does not cover all possible input values |
| Guarded arm, then `default` | accepted |
| Guard reads a reassigned local | error: must be final or effectively final |
| `case A(var v) -> …; case A x -> …` | accepted |
| `case A x -> …; case A(var v) -> …` | error: dominated |
| `case A(var v)`, `case B(BigInteger w)` | accepted (exhaustive) |
| `P(C(var w))`, `P(D(var w))`, `Q(var z)` | accepted (exhaustive) |
| `P(C(var w))`, `Q(var z)` | error: does not cover |
| `Pair(C, C)`, `Pair(D, D)` | error: does not cover |
| `Pair(C c1, T t2)`, `Pair(D d1, T t2)` | accepted |
| `case A(var _)` | error: unnamed variables are not supported in -source 21 |
| Guard introduces `instanceof C c`, arm reads `c` | accepted |
| `default` before a pattern label | error: dominated |
| `case P(T t)` before `case P(C(var w))` | error: dominated |
| Guarded nested pattern, then same unguarded, then `P(D d)`, `Q q` | accepted |
| `s instanceof A(var v) && v.signum() > 0` | accepted |
| `case A(Integer v)` (component is `BigInteger`) | error: incompatible types |
| `Pair(C(var w), C(var w))` | error: variable w is already defined |
| Colon form, pattern group that falls through | error: illegal fall-through to a pattern |
| `case A x, B y ->` | error: illegal fall-through from a pattern |
| Colon form with guards, every group ends in `yield` | accepted |
| `switch (w)` on a record type with `case W(var x)` | accepted |
| `new R1(a).equals(new R2(a))` | accepted |

**Searches.** `grep -rn "getGuard\|RecordPatternExpr"` over all Java sources at the base: no match outside
JavaParser itself. No on-chain source in in-repo `julc-examples`, `../julc-examples` or `../julc-cip113-example`
uses a guard or a record pattern in a `switch` (the matches are JuLC's own off-chain code in `julc-core` and test
utilities), and none declares a record implementing two interfaces; `julc-ledger-api`, `julc-stdlib` and
`julc-core` declare none either. Diagnostic codes were read from `diagnostics.json` on every origin branch
(`git show <branch>:julc-compiler/src/main/resources/diagnostics.json`).

## Revision history

- **r1** (2026-10-30): initial proposal, including #279 (D6), added at the coordinator's request from the ADR-066
  review.
