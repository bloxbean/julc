# ADR-067: Switch guards, record patterns, cross-type record `equals`, records with two sealed parents, and record members

**Status:** Accepted (2026-10-30): approved by the review comment at `f965d061` and by Codex at `189bb4fa`. The implementation follows in the same PR. M0 and M1 are implemented (Implementation results).
**Date:** 2026-10-30
**Label:** `semantic-consistency`
**Risk class:** correctness-sensitive, **high**. Every defect below is a silent miscompile: a validator built with
it can accept a transaction that its own Java tests reject.
**Programme:** step S5 of [ADR-062](062-jvm-uplc-semantic-consistency-programme.md) (close catalogue entries at
their source), with evidence from [ADR-063](063-differential-jvm-uplc-test-harness.md)
**Issues:** [#275](https://github.com/bloxbean/julc/issues/275) (guards ignored),
[#276](https://github.com/bloxbean/julc/issues/276) (record patterns dropped),
[#277](https://github.com/bloxbean/julc/issues/277) (cross-type record `equals`),
[#279](https://github.com/bloxbean/julc/issues/279) (a record with two sealed parents),
[#282](https://github.com/bloxbean/julc/issues/282) (record members ignored; added in r2),
[#283](https://github.com/bloxbean/julc/issues/283) (colon-form fall-through; added in r3),
[#284](https://github.com/bloxbean/julc/issues/284) (a default-package user record replaced by a ledger type of the
same simple name; added in r4),
[#291](https://github.com/bloxbean/julc/issues/291) (a `default` local outliving the switch; added in M1 round 3)
**Relates to:** [ADR-015](verification/015-strict-on-chain-data-boundaries.md) (strict boundaries),
[ADR-038](038-switch-pair-case-lowering.md) and [ADR-041](041-integer-case-dispatch.md) (switch dispatch),
[ADR-048](048-loop-conditional-local-updates.md) and [ADR-050](050-if-loop-continuations.md) (switch arms in
loops, join lambdas), [ADR-051](051-switch-field-decoding.md) (field decoding), [ADR-060](060-generated-binder-namespace.md)
(generated names), [ADR-066](https://github.com/bloxbean/julc/pull/278) (generator G1, which depends on this ADR),
[#204](https://github.com/bloxbean/julc/issues/204) (`instanceof` binding scope),
[#287](https://github.com/bloxbean/julc/issues/287) (expression typing, ADR-065),
[#292](https://github.com/bloxbean/julc/issues/292) (`String.equals` with another type, T34)
**Base:** `fd605e77` (head of PR #253, `feat/063-jazzer-fuzzing`). Every code citation is at this commit.

## Context

JuLC lowers a `switch` expression over a sealed interface to one `PirTerm.DataMatch` with one branch per
constructor (`PirGenerator.generateSwitchExpr`, `PirGenerator.java:2370`). Four reported defects come from this
area and from record encoding, a fifth (#282, added in r2) from records whose bodies JuLC ignores, and a sixth
(#283, added in r3 from review finding F12) from colon-form statement groups, and a seventh (#284, added in r4 from
F19) from name resolution on D9's boundary; each was reproduced at the base with the ADR-063 harness (Evidence,
below).

| Issue | Construct | JVM | JuLC (all four optimization levels) | Cause at the base |
|---|---|---|---|---|
| #275 | `case A x when cond ->` | 2 | 1 | JavaParser parses the guard (`SwitchEntry.getGuard()`), but nothing in the repository reads it (`grep -rn getGuard` finds no caller). `PatternMatchDesugarer.buildDataMatch` keeps the first entry per variant (`PatternMatchDesugarer.java:34-37`); later entries for the same variant are dropped |
| #276 | `case A(var v) ->` | -3 | -99 (the `default` arm) | The label loop handles only `TypePatternExpr` (`PirGenerator.java:2401`); a `RecordPatternExpr` label is skipped without a diagnostic, so `default` covers the variant. Without `default` the error is a misleading "not exhaustive. Missing cases: A" |
| #277 | `new R1(a).equals(new R2(a))` | false | true | `equals` on `RecordType`, `SumType` and `DataType` lowers to `EqualsData` (`TypeMethodRegistry.java:364-375`). Every standalone record is `Constr 0`, so two record classes with equal fields are equal |
| #279 | `record B(...) implements S, T` | 21 | 11 | A variant has one constructor tag. `TypeResolver` maps each variant to one sum type, and a second sealed parent overwrites the first (`variantToSumType.put`, `TypeResolver.java:184`, `:222`). `B` is built with its position in one parent and read with its position in the other |
| #283 | `case A x: x.v().negate(); default: yield TWO;` (colon form, falling through into `default`) | 2 | -5 | `generateSwitchEntryBody` returns a statement group's single expression statement as the arm's value (`PirGenerator.java:2493-2494`) and never continues into the next group, so Java's fall-through is replaced by "the discarded expression is the result" |
| #284 | A packageless source declaring top-level `record Rational(BigInteger denominator, BigInteger numerator)`; `new Rational(5, 7).numerator()` | 7 | 5 | `ImportResolver.resolve` checks same-package types only when `packageName != null` (`ImportResolver.java:129`), so a top-level type of the unnamed package falls through to the implicit `org.julclang.ledger.*` import, and the ledger `Rational(numerator, denominator)` replaces the user's record |
| #282 | A record body: explicit accessor, `equals`, compact or secondary constructor, methods | -5 (accessor), 1 (`equals`), 5 (compact constructor) | 5, 0, -5; other members rejected uncoded or with misleading messages | `TypeResolver.registerRecord` keeps only the components (`TypeResolver.java:70-83`); nothing in `julc-compiler/src/main` reads a user record's member declarations. Accessor calls resolve to the component (`PirGenerator.resolveRecordFieldAccess`), `equals` to `EqualsData`, and `new R(a)` builds the components directly, so constructor bodies never run |

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
- **Colon form (r3).** A statement group is compiled on its own (`generateSwitchEntryBody`, `:2486`): a single
  expression statement becomes the arm's value, a block must yield on every path (`:2499`), and nothing continues into
  the next group (#283). Grouped labels without bindings (`case X0(): case Y0(): yield 3;`, legal Java) are rejected
  with a misleading "not exhaustive" message.
- **Single-constructor dispatch (r3).** `generateDataMatch` emits the only branch of a one-constructor sum type with no
  tag test (`UplcGenerator.java:539-540`), so any `Constr` value is accepted as that constructor.
- **Field decoding (r3).** `PirHelpers.wrapDecode` decodes a `boolean` as "tag equals 1" (`PirHelpers.java:162-168`);
  any other tag is `false`, and the field list's length is not checked. ADR-051 keeps this contract on purpose. The
  strict boundary (`StrictBoundaryGenerator`, `checkBoolean` and the `NullList` arity checks) validates typed
  datum and redeemer roots of validators only; `compileMethod` arguments and Data cast inside a body are not validated.
- **Typing (r3).** JuLC has no Java type checker. A conditional is typed by its then branch (`PirGenerator.java:933-935`),
  a `var` local takes its initializer's inferred type, and type arguments of parameterized sealed interfaces are
  dropped (`TypeResolver` resolves a parameterized type by its erasure). Ledger hash types are also resolved by
  simple name (`TypeResolver.java:484`).
- **Parser.** Every JuLC entry point parses at `LanguageLevel.JAVA_21` (`JulcCompiler.java:265`, `:743`, `:839`,
  and the library, introspection, CLI and tools parsers). JavaParser 3.26.3 has `RecordPatternExpr`,
  `TypePatternExpr` and `SwitchEntry.getGuard()`.

## Goals and non-goals

**Goals**

- Guards and record patterns in `switch` expressions over sealed interfaces behave as in Java SE 21, at every
  optimization level and target, on every VM.
- JuLC rejects, with coded diagnostics, every switch shape that javac 21 rejects **under the switch-specific rules**
  within the constructs it accepts, because JuLC compiles source without javac (testkit `forSource`, playground,
  Elci's library provider). *(M1 round 3.)* Expression-level javac parity inside a non-constant guard (the legality of
  call arguments and receivers) is the general type-checking gap of every `if` condition, owned by #287 and ADR-065.
- `equals` between values whose static types provably cannot hold the same class (always `false` in Java) is
  rejected with a code (decided in r4), and never decided from an imprecise type (r3).
- A record that cannot be represented with one constructor tag is rejected.
- A user record whose body would change its meaning in Java (members JuLC does not compile) is rejected (r2).
- Programs that use none of these constructs compile to byte-identical scripts.
- Nothing is skipped silently: every case label is lowered or rejected with a code.

**Non-goals**

- `switch` statements (`SwitchStmt`), `case null`, constant labels, the total type pattern of the selector's own
  type (`case S s`), and switches over types other than sealed interfaces: unchanged in this ADR (see Related
  findings), except where D3 opens record-typed selectors as an open question.
- Unnamed patterns and variables (`_`). They are not Java 21: javac `--release 21` rejects them ("unnamed variables
  are not supported in -source 21"), and JuLC's parser rejects them as a parse error.
- Generic records (rejected today: "Unknown type: V"), and switches over parameterized sealed interfaces, which
  D4 rejects with a code (r3, F14).
- Colon-form statement-group fall-through (#283): rejected with a code, not implemented (r3, D4).
- Strict validation of field contents for Data that no strict boundary checked (r3, threat model and open
  question 9).
- `instanceof` binding scope in general (#204). D3 decides only `instanceof` with a record pattern.
- `==` on records (ADR-062 T4) and `byte[]` components (T5): unchanged, owned by S5's reference-equality decision.
- Optimizing pattern dispatch (merging tests across arms into a decision tree).

## Pinned normative references

- **JLS** — *The Java Language Specification, Java SE 21 Edition*,
  `https://docs.oracle.com/javase/specs/jls/se21/html/`, chapters 6, 14 and 15, fetched with `curl` on 2026-10-30
  (chapters 4, 7 and 8 added in r2–r4).
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
  - **§8.10.3 (record members)**, chapter 8 fetched the same way in r2: "for each record component, a record class has
    a method with the same name as the record component and an empty formal parameter list. This method, which is
    declared explicitly or implicitly, is known as an accessor method." An explicit accessor is therefore what every
    call `x.c()` and every record pattern (§14.30.2) invokes; D9 relies on this.
  - **§4.12.4 (final variables)**, r3: "A constant variable is a final variable of primitive type or type String that
    is initialized with a constant expression (§15.29)." A local declared with an initializer "is effectively final if
    … It never occurs as the left hand side in an assignment expression (§15.26) … It never occurs as the operand of a
    prefix or postfix increment or decrement operator." §15.29 counts "Simple names (§6.5.6.1) that refer to constant
    variables (§4.12.4)" and "Qualified names … of the form TypeName . Identifier that refer to constant variables" as
    constant expressions, so `when ON` with `static final boolean ON = true` is unguarded and `when OFF` is an error.
  - **§6.3, §6.4.1, §7.5.1 (scope, shadowing, imports)**, chapters 6 and 7, r4 (F19): "The scope of a top level class
    or interface (§7.6) is all class and interface declarations in the package in which the top level class or
    interface is declared." "A type-import-on-demand declaration never causes any other declaration to be shadowed."
    "If a single-type-import declaration imports a class or interface whose simple name is x, and the compilation unit
    also declares a top level class or interface whose simple name is x, a compile-time error occurs."
- **`java.lang.Record.equals`**, Java SE 21 API,
  `https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Record.html`, fetched 2026-10-30: "The
  implicitly provided implementation returns true if and only if the argument is an instance of the same record
  class as this record, and each component of this record is equal to the corresponding component of the
  argument; otherwise, false is returned."
- **Reference implementation for oracle tests (r3, F2):** **javac 21.0.3 (Eclipse Temurin)**, invoked by explicit
  path, not the JDK that runs the build. I9 means the JLS 21 text; javac 21.0.3 is its operational oracle. `--release
  21` on a newer javac is *not* equivalent: it restricts language features but runs the newer exhaustiveness
  algorithm. One shape differs (Evidence, javac table): `Pair(C c, var t)`, `Pair(var t, C c)`, `Pair(D d, D d2)` is
  "does not cover all possible input values" for javac 21.0.3 and accepted by javac 25.0.2 `--release 21`. By the
  JLS 21 rewrite rules no subset of those three patterns has a distinguished component whose other component sets are
  "equivalent to a single pattern", so the set does not reduce and the switch is not exhaustive under JLS 21; JuLC
  follows JLS 21 and rejects it (`JULC0005`). This is fail-closed: a user on a newer toolchain sees a JuLC error, not a
  wrong arm. Where javac 21.0.3 and the JLS 21 text disagree, the text governs and the case is recorded.
- **`java.lang.Record`** class javadoc (r3, F18): "During deserialization the record's canonical constructor is
  invoked to construct the record object." Java serialization therefore runs a compact constructor; whether a
  datum decoded on-chain should is a question for a future record-members ADR (D9).

## Threat model and trust assumptions

- **Untrusted:** every transaction field, datum and redeemer. An attacker chooses which variant reaches a `switch`
  and the values its guards read. A guard is usually an authorization check (`case Withdraw w when
  signedByOwner(ctx) -> true`), so ignoring it (#275) or running `default` instead of an arm (#276) is a direct
  authorization bypass.
- **Malformed Data (narrowed in r3, F10, F11).** Java has no malformed values (the JVM side cannot build one), so
  Java says nothing about them. JuLC's guarantees differ by where the value came from:
  - *Validated values:* typed datum and redeemer roots of a validator pass ADR-015's strict boundary (constructor
    index, arity, canonical `Bool`, recursively). A value built inside the script with `new` is well-formed *if its
    components are* (r4, F31): construction encodes each component as given and does not re-validate a component
    that came from unvalidated Data (`new Outer((P)(Object) raw)` keeps a malformed child; `PirGenerator.java:955-974`,
    `:1800-1808`, `PirHelpers.java:213-214`). For validated values and values built only from validated or literal
    parts, every guarantee of this ADR is exact; a value with an unvalidated part falls under the next item.
  - *Unvalidated Data:* `compileMethod`/`JulcEval` arguments, and Data cast to a record or sealed type inside a body
    (for example an inline datum read from a `TxOut`), are not validated. On the new path (D2) JuLC still fails closed
    where it is cheap and local: every constructor test it emits, top-level or nested, including single-constructor
    types and nested standalone records, rejects a tag that is not the expected one (I5); `instanceof` in a guard does
    the same (D1). Field *contents* follow ADR-051's existing decoding contract, which is lenient: a `Bool` field with
    a tag other than 0 or 1 decodes as `false`, the field list's length is not checked, and a field of the wrong Data
    kind fails in its decoding builtin. Such a value can therefore select an arm that no well-formed value would
    select. This ADR does not change that contract: the same lenient decoding applies to every other field access
    (`x.c()`, `instanceof`, today's switch arms), so a stricter decoder only on the new path would make one record
    read differently depending on how it is accessed, and strict boundaries are the designated validation point.
    Contracts that read untrusted Data inside a body must cast it through a typed boundary or validate it; strict
    in-body decoding is open question 9.
- **Trusted:** javac 21.0.3 as the reference for compile-time verdicts on Java 21 source; the JVM as the reference for
  run-time results on well-formed values (ADR-063 H1); the VMs' conformance (ADR-030).
- **Compile-time inputs** (source text) are trusted to be the contract author's, but JuLC must not accept a switch
  that javac rejects under the switch-specific rules (I9), because the author's JVM tests could not have run it.
  *(M1 round 3.)* Expression-level legality inside non-constant guards is the same gap as in any `if` (#287); the
  guard subset (D4) fails closed wherever that gap could change a guard's value.

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
- **I5. No fail-open on malformed tags** (D1, D2; widened in r3). On the new path, every constructor test JuLC
  emits — top-level, nested, a single-constructor sum type, a nested record-typed component (checked against the
  *construction tag* of the record class the pattern names: its position for a sealed variant, 0 for a standalone
  record; r4, F20), and `instanceof` on a sealed type inside a guard — is a total dispatch: a tag that is not one of the type's constructors fails; it never
  counts as "no match" and never reaches a later arm, a `#case_default` join or `default`. Field contents are outside
  I5 (threat model).
- **I6. Linear size** (D2). Each arm body, guard and `default` body is emitted once. Script size grows linearly in
  the number of arms (for nested patterns, in arms × constructors of the tested component), never exponentially.
  The pre-existing emission of `default` once per uncovered constructor is unchanged only for switches that keep
  the old lowering (I8).
- **I7. No silent skip** (D3, D7). Every case label, guard and pattern form is either lowered with Java's meaning or
  rejected with a coded diagnostic. No label is ignored. *(M1 round 3.)* A guard outside D4's guard subset, including
  a constant-looking guard outside the verified constant subset, is rejected (`JULC0072`), never lowered with a
  different meaning or reclassified as non-constant.
- **I8. Byte stability** (D2, D4, D5, D6, D9). A `switch` with no guard and no record pattern compiles to the same PIR
  and the same script bytes as at the base, at every optimization level and target, unless D4 now rejects it (a
  colon-form group that falls through, #283; a parameterized sealed selector; javac-rejected shapes), or unless a
  local declared in its `default` body shadows a name read after the switch (#291, M1 round 3), or unless it is
  nested inside a guard (M1 round 4): a guard's whole expression, an unguarded inner switch included, is guarded code,
  where R3a lowers `int`/`long` constants with Java's width. An `equals`
  call that D5 does not classify as disjoint compiles as before. Programs not affected by D6 or D9 are unchanged.
- **I9. Compile-time parity with JLS 21** (D4; r3; **revised in M1 round 3**). For an accepted switch, the
  switch-specific rules match the JLS 21 text exactly, as operationalized by javac 21.0.3: guard type, constant guards
  (in the verified constant subset), effectively-final guard variables (by declaration identity), guard binding scope
  and conflicts, exhaustiveness, dominance, duplicate `default`, case kinds and colon forms, pattern-variable
  conflicts, applicability (by nominal declaration) and arity; and **every accepted guard's value is Java's**. Where
  JuLC supports less than Java (colon-form fall-through, parameterized sealed selectors, the guard subset), it rejects
  with a code, never accepts with another meaning. Expression-level javac parity for non-constant call arguments and
  receivers inside a guard (F73) is not claimed: it is #287 and ADR-065, as for every `if` condition. "Java's value"
  is relative to the values of the variables the guard reads: a local computed before the switch carries the general
  gaps of its own expression (ADR-062 T6 and the rest), as in any `if`. Within the guard, `int` and `long` constant
  subexpressions are lowered as their Java values, and fixed-width operations JuLC cannot evaluate with Java's width
  (non-constant arithmetic, non-constant narrowing casts) are rejected (D4, round 3).
- **I10. Bindings** (D3). A record-pattern component variable is bound under its source name (ADR-060), to the
  value `x.c()` yields in JuLC for that component, decoded to its declared type; it shadows a field of the same
  name exactly as in Java. Generated binders use `#` names chosen with `PirHelpers.hygienicName` where terms built
  elsewhere are in scope. *(r3, F9)* An arm's bindings scope over that arm's nested tests, guard and body only: a
  later arm, a failure continuation and `default` resolve every name in the switch's enclosing scope, never under an
  earlier arm's binding.
- **I11. Loops and value boundaries** (D2). A switch with guards or record patterns obeys the same ADR-048
  restrictions as one without: arms export only their value; guards update nothing; record-pattern component
  variables are case bindings for #162.
- **I12. Equals agrees with Java** (D5; r3). D5 acts only when both operands' class sets are *known* by D5's source
  rules (declarations, `new`, declared returns), never from JuLC's inference (a conditional or switch typed by a
  branch, a `var` initialized from either, a generic call, an accessor on an unknown receiver, an inferred
  `DataType`). When both are known and disjoint (the call is constant `false` in Java), the program is rejected with
  `JULC0078` (decided in r4); otherwise the existing structural comparison is used, unchanged. Structural comparison is not claimed
  exact for overlapping classes: nested nominal differences (`Tuple2<R1, R1>` vs `Tuple2<R2, R2>`) remain a
  documented divergence (T29).
- **I13. One constructor tag per record class** (D6). A record class is a variant of at most one sealed interface;
  its tag is its position in that interface's `permits` list (or implicit order) everywhere: construction,
  dispatch, schema and boundary checks.
- **I14. Determinism** (all). The same source and configuration give the same PIR, bytes and diagnostics.
- **I15. A record means its components** (D9, r2). Every record JuLC compiles from user or library source has a body
  that contains nothing except nested type declarations. Its construction, accessors, `equals` and pattern matching
  are therefore exactly the implicit ones Java declares (JLS §8.10.3, `Record.equals`), which are what JuLC
  lowers. Ledger types bundled from `julc-ledger-api` are outside this rule: their on-chain meaning is defined by
  the compiler's registries, not by their bodies. *(r3, F8, F15)* "Bundled" means the compilation units returned by
  `LedgerSourceLoader`, by provenance, never by package or name; a user declaration of a bundled ledger FQCN, or of a
  ledger hash simple name, is rejected. *(r4, F19, #284)* A simple name resolves as javac resolves it: a type
  declared in the compilation unit or its package — including the unnamed package — shadows the implicit ledger
  wildcard, so a user record named like a (non-hash) ledger type means the user's record.
- **I16. Statement groups do not fall through** (D4, r3). A colon-form switch is compiled only when every statement
  group has one label and yields on every path; anything else is rejected with a code, so no group's statements are
  ever reinterpreted as another group's value (#283).

## Decisions

### D1. Guard semantics

A guarded arm applies when its pattern matches and its guard evaluates to `true` (JLS §14.11.1.2). Arms are tried
in source order. A false guard moves to the next arm that can match the same value: a later guarded or unguarded
arm for the same variant, a later record pattern for it, and finally `default`. A guard is evaluated after the
pattern's variables are bound and may read them, method parameters, effectively final locals, static fields and
`@Param`s. A guard must have type `boolean` or `Boolean` (JLS §14.11.1; D4; r4, F33). JuLC maps `Boolean` to
`BoolType` (`TypeResolver.java:405`) and has no `null`, so a `Boolean` guard needs no unboxing check. A guard that is a constant expression with value `true` (a
literal, a constant variable such as `static final boolean ON = true`, or a compound such as `1 == 1`; JLS §4.12.4,
§15.29) makes the label unguarded (JLS §14.11.1); one with value `false` is rejected (D4). A guard does not count towards exhaustiveness (JLS §14.11.1.1: only "unguarded
case label[s]" are considered).

Pattern variables introduced *by a guard* (JLS §6.3.3, e.g. `case P p when p.t() instanceof C c -> c.w()`) are
rejected with `JULC0074`. JuLC does not bind `instanceof` pattern variables outside an `if` condition (#204), so
today this fails closed with "Undefined variable: c" (Evidence G12); the code makes it actionable, and a nested
record pattern (`case P(C c) -> c.w()`) expresses the same test. A guard may still use `instanceof` as a plain
type test. *(r3, F3)* Inside a guard on the new path, `instanceof` on a sealed type is lowered as a total dispatch
over that type's constructors (a `DataMatch` whose branches are `true` and `false`), not as today's tag comparison
(`generateInstanceOf`, `EqualsInteger`), so an out-of-range tag fails instead of selecting a later arm (I5). Outside
guards, `instanceof` keeps today's lowering (Related findings).

*Alternatives:* reject guards (keeps the subset small, but the maintainer decided to implement them, and guards
are the idiomatic way to write authorization arms); accept guards only as the last arm before `default` (a partial
semantics with its own diagnostic surface and no simpler lowering).

### D2. Lowering

The switch keeps one `DataMatch` over the selector with one branch per constructor in tag order, so ADR-038's pair
destructuring and ADR-041's integer `Case` dispatch apply unchanged for sum types with two or more constructors.
*(r3, F11)* For a one-constructor sum type, today's `DataMatch` emits the branch with no tag test
(`UplcGenerator.java:539-540`); on the new path the branch is guarded by `tag == 0`, failing otherwise (I5). Today's
path keeps its bytes (I8); its missing check is recorded in Related findings. Inside each constructor's branch:

1. The constructor's fields are decoded once, as today (ADR-051), and bound under one set of generated names for
   that constructor. Every arm for that constructor reads them; nothing is decoded twice.
2. The arms that can match the constructor, in source order, form a chain. Each arm binds its own variables (a
   type pattern's variable to the scrutinee, a record pattern's component variables to the decoded fields), then
   tests its nested patterns (step 3), then its guard, and then runs its body. Every failure point of arm *i* goes
   to the continuation "try arm *i+1*". An unguarded arm with no nested test ends the chain; arms after it are
   still compiled (for diagnostics) but not emitted, because they can never run.
3. A nested pattern on a component of sealed type `T` is a nested `DataMatch` over that component with one branch
   per constructor of `T` (I5). The constructor(s) the pattern names continue the arm; every other constructor
   goes to the arm's continuation. A nested pattern on a component whose declared type is a record class — a
   standalone record or a sealed variant used as a component type (`record W(B b)`) — checks that the component's
   tag equals the **construction tag** of that record class, failing otherwise (r3, F11; corrected in r4, F20): 0 for
   a standalone record, the variant's position for a sealed variant (a `W(new B(a))` component carries `B`'s tag;
   Evidence F20). The tag comes from the same registry query as D3's eligibility rule. It then decodes that record's
   fields as field access does. Arity is not checked (threat model). Type patterns and `var` on a component of
   exactly the component's type are unconditional.
4. When the chain is exhausted, control goes to `default`, or, for a switch javac proves exhaustive, to the
   existing terminal `Error` (unreachable for well-formed values).
5. **Joins and scope (revised in r3, F9).** An arm's bindings (its type-pattern variable and record-pattern
   component variables, all source names) scope over that arm's nested tests, guard and body only. The arm's failure
   continuation ("try arm *i+1*", eventually `default`) must resolve names in the switch's enclosing scope, so it is
   never placed under the arm's binders. Concretely: the continuation is bound as a unit lambda *before* the arm's
   binders (`let #case_next = \_ -> <rest> in let limit = … in if g then b else #case_next ()`), and each failure
   point calls it. It is inlined only when it has exactly one call site *and* none of the arm's bound names occurs
   free in it (`PirHelpers.freeVariables`); otherwise it is a join. `UplcGenerator` resolves a name to the nearest
   binder (`UplcGenerator.java:648`), so hygienic join names alone would not stop a source-named binder from
   capturing; the placement rule does. Join binders are named `#case_next` and `#case_default` via
   `PirHelpers.hygienicName` (ADR-060), because user terms are in their scope. A `default` join is bound outside the
   `DataMatch`; creating a lambda evaluates nothing, so I1 holds. Example of the hazard: with a static field `limit =
   99`, `case A(var limit) when limit.signum() > 0 -> ONE; case A a -> limit;` returns 99 in Java for `A(-1)`
   (Evidence F9); a continuation nested under the first arm's `let limit` would return -1.
6. **Scope of the new lowering.** Only a switch that has at least one guard or record pattern uses steps 2–5. Any
   other switch takes today's path unchanged, including the duplication of `default` per uncovered constructor
   (I8). In a switch on the new path, uncovered constructors call the `default` join.

Guards are ordinary boolean PIR in an `IfThenElse`; arm bodies (expressions, `yield` blocks, colon groups that
yield on every path) are compiled by `generateSwitchEntryBody` as today. Statement-group fall-through is not a
continuation of arm selection: D4 rejects any colon form that would need it (r3, F12, #283), so D2's continuations
only ever mean "this arm did not apply". Switches inside loop bodies need no new lowering:
`LoopBodyGenerator` treats a switch expression as a value boundary, and its validation is extended to record-pattern
variables and guards (I11). `PatternMatchDesugarer.buildDataMatch` stops choosing "the first entry per variant";
the per-constructor chain is built before it, so its one-branch-per-constructor contract with ADR-041 is kept.

*Illustrative only* (not the implementation): for `case A x when g -> b1; case A(var v) -> b2; default -> d`
the `A` branch is roughly `let #case_next = \_ -> (let v = f0 in b2) in let x = data in if g then b1 else
#case_next ()` — or, since `x` is not free in the continuation and it has one call site, the inlined
`let x = data in if g then b1 else (let v = f0 in b2)`; the other branches call `#case_default ()`, and
`#case_default = \_ -> d` is bound before the `DataMatch`.

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

- *(Revised in r3, F4, and r4, F30.)* A record pattern naming a type that does not have the *record layout*
  encoding. Eligibility is decided by layout, not by the outer Data kind: a type is eligible iff the registry says
  its values are `Constr <construction tag> [component_1, …, component_n]`, with the components in declaration
  order and that fixed tag. The implementation adds one registry query that returns this layout (or "none"),
  answered by the same source the codecs use (`wrapEncode`/`wrapDecode`, the strict boundary); D2 step 3 takes its
  construction tag from it (F20). Being `ConstrData` is not enough: a `@NewType record Flag(boolean value)` is
  encoded as the `Bool` itself, `Constr 0 []` or `Constr 1 []` (`TypeRegistrar.java:265-297`,
  `PirGenerator.java:1741-1747`, `PirHelpers.java:193-201`), which has no component list. Not eligible today: every
  `@NewType` record, whatever its underlying type (including `boolean`); the ledger hash types (byte strings); and
  `org.julclang.ledger.Value`, registered as `RecordType("Value", [inner])` but encoded as a Map
  (`TypeMethodRegistry.java:861`). A test asserts, for every ledger record type, that the layout query agrees with
  the off-chain `toPlutusData` of a sample value (tag and field count), an oracle independent of the registry; M2a
  adds rejection tests for a `boolean` `@NewType` at top level and nested.
- *(r3, F14; code aligned in r4.)* A record pattern on a component whose type is a parameterized sealed interface:
  `JULC0076` (a component pattern). A switch *selector* of such a type is `JULC0007` (D4).
- A component type pattern that would need a run-time test JuLC cannot perform on Data, or that javac accepts but
  can never match: for example `IntData i` on a `PlutusData` component (a Data-kind test), or `PlutusData d` on a
  `BigInteger` component.
- *(Subsumed by D9 in r2.)* A record pattern over a record that declares an explicit accessor method for a
  component. Java's record pattern invokes the accessor (JLS §14.30.2), and JuLC ignores explicit accessors (#282).
  D9 now rejects such a record at its declaration, so no record pattern can name one; D3 needs no check of its own,
  and `JULC0076` has no accessor reason. A future ADR that admits record members must revisit D3 together with D9.
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
lowering, so the diagnostics do not depend on the optimization level. *(r3, F13)* The checks form a matrix; each row
is a parity-corpus dimension (Verification 3):

- **Exhaustiveness** (§14.11.1.1), with guarded labels excluded and `when true` counted as unguarded, including the
  record rules and the rewrite/reduce rules for record patterns. JuLC's type universe is small: sealed interfaces
  whose permitted subtypes are records (interface `extends` is rejected by `SubsetValidator`), record types, and
  leaf types that only an unconditional pattern covers. Diagnostic: `JULC0005`, promoted from planned to emitted,
  with the missing variant or pattern in the message.
- **Dominance** (§14.11.1, §14.30.3): a label dominated by a preceding unguarded label; a guarded label after the
  same unguarded pattern; a label after `when true` on the same pattern; `default` before a pattern label.
  Diagnostic `JULC0071`.
- **Guard type and constant guards** (§14.11.1, §15.29, §4.12.4): a guard whose type is neither `boolean` nor
  `Boolean` (`when 3`, accepted today; Evidence F13a; `Boolean` accepted per r4, F33), or a constant expression with value `false` (a literal, a constant variable such as a
  `static final boolean` or a `final` local initialized with a constant, a compound constant), is rejected with
  `JULC0072`. A constant `true` guard, in any of those forms, makes the label unguarded for exhaustiveness and
  dominance. *(M1 decision, review F28 and F52, agreed by both reviewers.)* The type is the guard's **Java type,
  established from the source** before casts are erased and constants folded: literals, operators (a conditional by
  both branches, so `true ? true : 0` is not `boolean`), casts, the declared types of locals, parameters and fields,
  and the declared return types of the unit's methods; names and calls those rules leave open are typed from the symbol
  table (generic returns such as `JulcList<Boolean>.head()` included). A guard whose type cannot be established as
  `boolean`/`Boolean` is rejected with `JULC0072` (fail closed), and the message suggests a typed local or helper.
  Constant expressions follow §15.29 exactly (casts, shifts, `String ==`, numeric promotion); a constant JuLC does not
  model (floating point, division by zero) is rejected rather than classified. *(Review round 2.)* The guard subset is further restricted, each with
  `JULC0072`: operands of comparisons and the condition of a conditional must be established as legal; reference casts
  (#288), non-constant `==` on strings and `==` on other references (#289, T4), `char`/`byte`/`short` values, instance
  fields, constant declarations javac rejects, `java.lang` names shadowed by a user type, and `instanceof` whose operand
  is not, by declaration (FQCN), the tested variant or its sealed interface are rejected. Unqualified case-pattern
  types are resolved to their declaration like qualified ones (`JULC0076`). *(Round 3.)* Constant folding is kept exact for a **verified
  subset**: `boolean`, `int`, `long` and `String` constants with JLS fixed-width semantics, a constant variable taking
  its declared type after the assignment check (F70); every constant declaration a guard reads is validated, constant
  guard or not (F71); a constant-looking guard outside the subset is rejected, never reclassified as non-constant. Also
  rejected: a non-constant narrowing cast `(int) e` (T6, F72), a guard pattern variable that conflicts with a variable
  in scope or another binding of the guard, checked before the unused-binding exemption (F74, `JULC0083`), and
  `String.equals` with an argument not established as a `String` (#292, F75). Every switch arm body, `default`
  included, is compiled in its own scope on both lowerings (#291). The declaration check follows qualified names
  (`Probe.N`) and the initializers of the constants a guard reads. *(Round 3, found while implementing I9.)* Inside a
  guard that is not constant, an `int` or `long` constant subexpression is lowered as its Java value (so
  `(2147483647 + 1) < x.v().signum()` and a `static final int N = 2147483647 + 1` read by a guard keep Java's
  width), and `int`/`long` arithmetic that is not a constant expression (`+`, `-`, `*`, `/`, `%`, shifts, bitwise
  operators, unary `-` and `~`) is rejected with `JULC0072`, because JuLC evaluates it without Java's overflow
  (ADR-062 T6). **The expression-typing boundary:** the legality of call arguments and receivers inside a non-constant
  guard (`ok(true)` for a `long` parameter, `signum()` on a `long`; F73) is the type-checking gap every `if`
  condition has today; it is #287 and ADR-065, not D4. D4 owns the switch-specific rules and the guard subset, which
  fails closed wherever that gap could change a guard's value (the guard's type, comparisons, casts, `==`,
  `instanceof`, `String.equals`, fixed-width arithmetic). *(M1 round 4.)* A `@Param` field is never a constant:
  it is read as the applied parameter, in whole guards, in R3a's subexpressions and through other constants'
  initializers, so `when ENABLED` on a `@Param` field is a guarded label (F41 = F80; whether such a `static final`
  declaration should be rejected, since javac inlines it, is #293). `intValue()` and `longValue()`, which JuLC lowers to
  the identity where Java narrows, are rejected in a guard (F81, #294). A referenced constant declaration, or a part of
  a guard, that the checker cannot evaluate exactly (an out-of-range literal such as `2147483648`, floating point) is
  rejected, never left to the ordinary lowering; Java's minimum-value forms `-2147483648` and
  `-9223372036854775808L` are classified exactly (F82, JLS 3.10.1).
- **Effectively final guard variables** (§14.11.1, §4.12.4; revised in r3, F7): each name used but not declared in a
  guard is resolved to its declaration (by identity and scope, not by name). If that declaration is a local or
  parameter that occurs as the target of an assignment anywhere in its scope (in JuLC, only loops may reassign), the
  guard is rejected with `JULC0073`. A for-each variable is effectively final (javac accepts it; Evidence, javac
  table). Sibling blocks that declare the same name do not affect each other. Today a guard over a reassigned local
  compiles and reads the updated value (Evidence H1).
- **Duplicate `default`** (§14.11.1, "No more than one default label"): rejected with `JULC0082` (r3). Today the
  second `default` replaces the first (Evidence F13b).
- **Pattern-variable conflicts** (§6.4, §14.30.1): a pattern variable with the name of a local or parameter in scope,
  or two pattern variables with one name in a pattern, is rejected with `JULC0083` (r3). Today `case A a` where `a` is
  a parameter compiles and shadows it (Evidence F13c). A pattern variable may shadow a field, as in Java.
- **Applicability** (§14.30.3): a pattern whose type is not a variant of the selector's sealed interface (or of the
  component's type) is rejected with `JULC0076` (today: uncoded "Unknown variant in switch"). *(r6, F26)* The total
  type pattern `case S t` on a selector of type `S` is applicable and unconditional in Java; it is only unsupported
  here (non-goals). It is rejected with `JULC0076`, reason "unsupported: total type pattern", so the message does not
  claim the Java is invalid.
- **Colon form (revised in r3, F12, #283).** The r1 wording ("fall-through into or from a pattern") was wrong: Java
  allows a group to fall through into a following group whose labels declare no pattern variables, including
  `default` (`case A x: x.v().negate(); default: yield TWO;` is legal and returns 2). JuLC does not implement
  statement-group fall-through. A colon-form switch is accepted only if every group has exactly one label and yields
  on every path (it cannot complete normally); otherwise it is rejected with `JULC0075`: a group that can complete
  normally (#283, legal Java, today a silent miscompile: JVM 2, JuLC -5), a group with several labels (`case X0():
  case Y0(): yield 3;`, legal Java, today a misleading "not exhaustive"), and javac's own "illegal fall-through to /
  from a pattern" errors. This applies to today's path as well: it is the fix for #283. The last group must yield too
  (a switch expression cannot complete normally).
- **Selector type:** a switch on anything other than a sealed interface keeps its rejection, now as `JULC0007`
  (promoted). *(r3, F14)* A switch whose selector type is a sealed interface that declares type parameters (`sealed
  interface G<X> permits GC, GD`) is also rejected with `JULC0007`, reason "parameterized sealed interface" (a
  record pattern on a component of such a type is `JULC0076`, D3): JuLC
  drops type arguments, so it cannot decide applicability (`case GC c` is invalid for `G<BigInteger>` when `GC
  implements G<String>`) or exhaustiveness (`case GD d` alone is exhaustive in Java). Today JuLC rejects the valid
  program and accepts the invalid one (Evidence F14a, F14b). No parameterized sealed interface exists in the corpora
  (Evidence, scan), so the rejection breaks nothing known; supporting them needs type-argument tracking.

*Alternatives:* rely on javac (Gradle builds run javac on the same source, but testkit `forSource`, the
playground and Elci's library provider do not, and ADR-065's javac frontend is a spike); accept dominated labels
(their meaning is well defined by first-match, but the program is not Java, and G1's oracle cannot judge it).

### D5. Cross-type `equals`

For a call `r.equals(a)`, JuLC computes, for each operand, the set of runtime classes its Java static type can denote
— but only from sources where JuLC knows that type exactly (r3, F1). A class set is **known** only when the operand
is one of:

- a `new R(...)` expression;
- a name whose declaration states its type: a parameter, a local declared with an explicit type (not `var`), a static
  field or `@Param`, a switch type-pattern variable (`case A q`), or a record-pattern component with an explicit type;
- a call of a user or library method whose declared return type is not generic, or a record component accessor
  `x.c()` whose component's declared type is not generic *and whose receiver `x` is itself known by these rules*
  (r4, F21; otherwise `.c()` would be resolved against a then-branch type);
- a `var` local whose initializer is itself known by these rules (recursively);
- a cast `(T) e` to a non-generic type.

Everything else is **unknown**: a conditional (`c ? x : y`) and a switch expression, which JuLC types by one branch
(`PirGenerator.java:933-935`) while Java uses their least upper bound; a `var` initialized from either; a generic
call or accessor (`list.get(i)`, `opt.get()`, `t.first()`); and any type JuLC inferred rather than read from a
declaration, including an inferred `DataType`. (Computing the least upper bound of the branches' sets would also be
sound; the ADR does not require it.) The reviewer's three counterexamples (Evidence F1a–F1c) agree today and must
stay structural. For a known operand, the class set is:

- a standalone record `R` (user or ledger): `{R}`;
- a sealed variant `V`: `{V}`;
- a sealed interface `S`: its permitted records (flat in JuLC, D6 makes the sets of different interfaces disjoint);
- `PlutusData` and its subtypes: the `PlutusData` classes. `PlutusData` is a sealed interface in `julc-core`
  (`PlutusData.java:22`), so no user record can be one;
- anything else, or a type JuLC cannot determine from the source: *unknown*.

If both sets are known and disjoint, the call is always `false` in Java, and **JuLC rejects it with the error
`JULC0078`** (decided in r4: open question 1, option (b), recommended by both reviewers and the author and adopted
by the coordinator for the maintainer; the maintainer may still veto it before acceptance). The message names both
operand types and gives the remediation: compare through one typed value (for example decode the datum to the
record type first), or compare the components explicitly. A program that relied on the old structural `true`, such
as a deny check, stops compiling instead of silently changing its meaning; a residual classification error becomes
a compile error, not a wrong value.

Otherwise (overlapping or unknown) the existing `EqualsData` lowering is kept, unchanged. *(r3, F16)* This is not a claim that
structural comparison is exact for overlapping sets: with D6, two top-level classes in one sealed family have
distinct tags, but nominal differences *inside* components are erased (`new Tuple2<>(new R1(a), new R1(a))` vs
`new Tuple2<>(new R2(a), new R2(a))`, and the same with `Optional`, are false in Java and true on-chain; Evidence
F16b, F16d). That residual divergence stays in T29 with saved pins; D5 decides only top-level disjointness.

With D9 (r2), an explicit `equals` can no longer be silently replaced by `EqualsData`: every user record JuLC
accepts has the implicit `equals`. *(Reworded in r4, F16.)* That removes one source of divergence; it does not make
same-type comparison exact, which keeps T5's `byte[]` components and T29's nested nominal erasure.

The known-source rules above exist because PIR inference is not Java's type: it types a conditional by its then
branch, and it falls back to `DataType` where the Java type is a record (the comment at `PirGenerator.java:2376`
describes one such case). Deciding `false` from either would turn a true comparison into a new miscompile (I12).

**Scope.** This decision covers what #277 reports: user and ledger records, sealed interfaces and variants, and
`PlutusData`. The same Java rule makes `@NewType` and ledger hash comparisons across types false (Evidence E8, E11,
E12: `new Amt(a).equals(new Price(a))`, `PubKeyHash.of(a).equals(ScriptHash.of(a))` and
`PubKeyHash.of(a).equals(a)` are false on the JVM and true on-chain). *(Reworded in r3, F1.)* For operands whose
type D5's rules can read from a declaration, those types are just as decidable; what differs is (i) that the PIR
erases them to `IntegerType`/`ByteStringType`, so the classifier must carry the declared nominal type alongside the
PIR type, and (ii) that most such values in validators come from generic ledger accessors (`signatories()` elements,
map keys), which D5 classifies as unknown, so the extension would catch little while touching common comparison
code. That extension is open question 2.

*Alternatives:*
- *(a) Agree: fold to `false` with a `JULC0078` warning* (r1's default). Rejected in r4: it changes an existing
  contract's run-time meaning on recompile, in the unsafe direction for deny checks, with a warning the testkit never
  shows (`JulcEval.java:302-305`, `ValidatorTest.java:150-154`), and a classifier mistake would become a silent wrong
  `false`. See open question 1 (decided).
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
  families now share tags with different meanings for their *other* variants, so the top-level part of D5's argument
  ("overlapping class sets ⇒ one family ⇒ distinct, consistent tags") fails: an `S` holding `A` and a `T` holding `C` at the same position with
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
| `JULC0007` | (existing, planned → emitted) | error | Switch selector that is not a sealed interface, or is a parameterized sealed interface (reason argument; r3, F14; r5) |
| `JULC0071` | `SWITCH_LABEL_DOMINATED` | error | Dominated label, `default` before a pattern label |
| `JULC0072` | `SWITCH_GUARD_INVALID` (renamed in r3) | error | Guard that is neither `boolean` nor `Boolean`, or a constant `false` (literal, constant variable, compound) |
| `JULC0073` | `SWITCH_GUARD_VARIABLE_NOT_EFFECTIVELY_FINAL` | error | Guard reading a local or parameter whose resolved declaration is reassigned |
| `JULC0074` | `SWITCH_GUARD_PATTERN_VARIABLE_UNSUPPORTED` | error | Pattern variable introduced by a guard |
| `JULC0075` | `SWITCH_GROUP_UNSUPPORTED` (renamed in r3) | error | A colon-form group that can complete normally or has several labels (#283), incl. javac's fall-through errors |
| `JULC0076` | `CASE_PATTERN_UNSUPPORTED` | error | Pattern outside D3's subset, with the reason |
| `JULC0077` | `INSTANCEOF_RECORD_PATTERN_UNSUPPORTED` | error | `instanceof` with a record pattern |
| `JULC0078` | `EQUALS_ALWAYS_FALSE` | error (decided in r4) | `equals` between operands with known, disjoint class sets (D5); names both types and suggests comparing components or one typed value |
| `JULC0079` | `RECORD_MULTIPLE_SEALED_PARENTS` | error | A record implementing two sealed interfaces (D6) |
| `JULC0080` | `RECORD_MEMBER_UNSUPPORTED` | error | A user record body member other than a nested type (D9, r2), with the member kind |
| `JULC0081` | `LEDGER_TYPE_REDECLARED` | error | A user declaration of a bundled ledger FQCN, or of one of the seven ledger hash simple names (D9, r3); other ledger simple names follow Java's shadowing (r4) |
| `JULC0082` | `SWITCH_DUPLICATE_DEFAULT` | error | More than one `default` label (D4, r3) |
| `JULC0083` | `PATTERN_VARIABLE_CONFLICT` | error | A pattern variable named like a local or parameter in scope, or twice in one pattern (D4, r3) |

**Code reservation.** The base's highest code is `JULC0059`. Open PRs use `JULC0060`–`JULC0066` (#272,
`fix/257-coded-loop-diagnostics`) and `JULC0070` (#271, `fix/t25-integer-literals`); `JULC0067`–`JULC0069` are
used by no branch but were skipped by #271, so this ADR does not take them. It reserves `JULC0071`–`JULC0083`
(`JULC0080` added in r2, `JULC0081`–`JULC0083` in r3; re-checked on every origin branch at r3, unchanged). Code numbers
are stable; r3 renamed the constants of `JULC0072` and `JULC0075`, which no release has shipped.
Codes are re-checked against every origin branch before the implementation commit.

### D8. Catalogue and documentation

ADR-062 gets one row per issue, provisionally **T27** (#275), **T28** (#276), **T29** (#277, with the erased-nominal
cases of E8/E11/E12 noted), **T30** (#279), **T31** (#282: record members ignored, Evidence R6, H5, N1–N10), and **T32** (#283, colon-form
fall-through; r3), and **T33** (#284, default-package resolution, and user records reaching ledger handlers by simple name, F45; r4, r6), owned by S5. T24 and T25 are taken by #268 and #270. Final IDs are assigned when the rows merge. *(M0 re-check.)* #272 (#257) added a T26 row of its own after acceptance, so this ADR's rows take the next free IDs, T27–T33; every ID in this ADR was shifted by one (the proposal's T26–T32).
Documentation: `getting-started.md` §8.2/§8.3 (guards, record patterns, the `instanceof` limit), the advanced
guide's "Switch Best Practices", troubleshooting entries for each code, both AI starter packs, and release notes.

### D9. Record members (#282, r2)

JuLC compiles a record as its components only. Everything else in a record body is either ignored, which silently
changes the program's meaning, or rejected with an uncoded or misleading message (Evidence N1–N10):

| Member | Java | JuLC at the base |
|---|---|---|
| Explicit accessor (`v()` returning `v.negate()`), called on a local, a helper parameter or a switch binding | -5 | 5, silently (R6, N9) |
| Explicit `equals` | 1 | 0, silently (`EqualsData`, H5) |
| Compact constructor normalizing a component (`R { x = x.abs(); }`) | 5 | -5, silently (N3) |
| Secondary constructor delegating to the canonical one | 10 | fails on-chain ("HeadList: empty list"), because the record is built with one field (N5) |
| Explicit canonical constructor | 6 | rejected: "'this' is not supported on-chain" (N4) |
| Static method, static field | 10, 15 | rejected: "Undefined variable: R" (N1, N8) |
| Instance method | 10 | rejected: `JULC0055` "Cannot resolve twice" (N2, N10) |
| `toString`, `hashCode` declared and unused | 5 | 5 (N7) |

**Decision.** Fail closed. A record declared in source that JuLC compiles as user or library code (validator
classes, `@OnchainLibrary` libraries, testkit and playground sources, Elci library sources) may contain only nested
type declarations in its body. Every other member is rejected at the record's declaration with `JULC0080`, naming
the record and the member kind:

- an explicit accessor (a method named after a component, with no parameters);
- `equals`, `hashCode` or `toString`;
- a compact canonical constructor or an explicit canonical constructor;
- any other constructor;
- any other instance or static method;
- a static field or an initializer block.

The check runs before any use, so it does not depend on whether or how the record is used (a declared but unused
`toString` is rejected too). *(r3, F17)* It is a declaration preflight over every non-ledger compilation unit that
runs before `SubsetValidator` (which today runs before type registration, `JulcCompiler.java:309`, `:849`, and would
otherwise report, for example, "'this' is not supported on-chain" from inside a canonical constructor,
`SubsetValidator.java:262`). `SubsetValidator` and later stages do not descend into the body of a record the
preflight rejected, so `JULC0080` is the one diagnostic for it. It replaces today's messages for these members ("Undefined
variable: R", "'this' is not supported on-chain", and `JULC0055` where the receiver is a user record that declares the
method). `@NewType` records are included: their constructor and `.of(...)` are compiler intrinsics, and a declared
`of` or accessor would be a second, unused definition.

**Scope: ledger types are excluded, by provenance (r3, F8, F15).** The `julc-ledger-api` types are loaded from bundled
sources by `LedgerSourceLoader` and registered first (`JulcCompiler.java:350`, `:878`; `JavaLibraryProvider.java:69`),
through the same `TypeRegistrar.registerAll` as user units. The exemption is therefore a property of the compilation
units `LedgerSourceLoader` returned, never of a package prefix or a simple name. A user unit that declares the FQCN of a
bundled ledger type (today skipped silently, `TypeRegistrar.java:215-218`: Codex's probe kept the bundled `Value`
although the user `org.julclang.ledger.Value` declared an accessor) is rejected with `JULC0081` at every entry point,
consistent with `JavaLibraryProvider.java:70`, which already rejects such conflicts (uncoded). So is a user type
whose simple name is a ledger hash name (`PubKeyHash` …), because `TypeResolver` resolves those by simple name
(`TypeResolver.java:484`), bypassing import resolution; removing that shortcut is a wider change, left as a
follow-up.

*Other ledger simple names (decided in r4, F19, #284).* A user type named like a non-hash ledger type (`Interval`,
`TxOutRef`, `Rational`, …) follows **Java's rule**: a top-level type declared in the compilation unit, or elsewhere in
its package, is in scope throughout the package (JLS §6.3: "The scope of a top level class or interface (§7.6) is all
class and interface declarations in the package in which the top level class or interface is declared"), and "A
type-import-on-demand declaration never causes any other declaration to be shadowed" (JLS §6.4.1). JuLC's implicit
ledger wildcard is an on-demand import, so the user's type wins — also in the **unnamed package**, which
`ImportResolver.resolve` skips today (`ImportResolver.java:129`, `if (packageName != null)`). Packageless sources are
the norm in the testkit (`forSource`), the playground and the difftest harness. For the unnamed package, "the
package" means the compilation units compiled together (the validator and its library units), as javac treats them.
A single-type import of the ledger type next to a same-named top-level declaration stays a compile error, as in Java
(JLS §7.5.1). Lean and reasons: Java's rule rather than rejecting every ledger simple name, because (i) the program is
valid Java with an unambiguous meaning, and rejection would refuse it for no semantic reason; (ii) the defect is in
resolution order, and fixing the order also fixes the nested-type and library cases consistently; (iii) the ledger
API grows, and a name-based rejection list would turn every new ledger type into a breaking change for user code.
Evidence (F19 rows): JVM 7, JuLC 5 for `Rational` with or without an explicit `import org.julclang.ledger.*;`; the
same record nested in `Probe` agrees. M0 pins the `Rational` program as `DIVERGE`; M3b flips it to `AGREE`.

*Consequence and its owner (r5, F23, #287).* JuLC has no nominal check when a value is assigned, passed or returned
as a different record type. Once #284 is fixed, a written `Rational` means the user's type while values from ledger
APIs keep the ledger layout, so `Rational q = new org.julclang.ledger.Rational(a, b)` — a javac error ("incompatible
types") — is accepted and reads the ledger value through the user's layout (JuLC 7; Evidence F23). This is the
threat model's "JuLC must not accept source javac rejects", not an I2 miscompile, and it is owned by **#287** (a
nominal FQCN check on assignment, argument passing and return). M3b records it as a known parity exception and pins
today's value. Two keying rules are decided here: **D3's layout query is new code and must key on the record's FQCN
and registration provenance, never on the `RecordType` simple name**, so a user `record Value` is classified by its
own layout. **The existing ledger method handlers are fixed in M3b, not deferred (r6, F45).**
`TypeMethodRegistry` tries a "named key" `rt.name() + "." + method` for every `RecordType` receiver before the generic
handlers (`TypeMethodRegistry.java:95-99`, `:108-111`, `:128-131`), and `Value` is the only type registered that way
(`lovelaceOf`, `isEmpty`, `containsPolicy`, `assetOf`; `:861-904`). r5 deferred this to #287 on the premise that
reaching it needs javac-invalid source; that premise was wrong. An *implicit* accessor survives D9, so `record
Value(boolean isEmpty) {}` with `new Value(a.signum() > 0).isEmpty()` is valid Java: JVM `true`, JuLC fails with
"UnMapData: not MapData" at all four levels; through a local it agrees, because field access resolves first
(Evidence F45). M3b keys named handlers on the receiver's ledger identity (FQCN plus `LedgerSourceLoader`
provenance, the same key as D3's layout query), so a user record never reaches them and its implicit accessor
applies. #287 keeps the nominal checks on assignment, argument passing and return.

The ledger types' bodies are
off-chain conveniences whose on-chain meaning the compiler defines itself: the hash types (`PubKeyHash`, `ScriptHash`,
`ValidatorHash`, `PolicyId`, `TokenName`, `DatumHash`, `TxId`) declare compact constructors, cloning accessors,
`equals`, `hashCode`, `toString` and static `of`/`fromPlutusData`, and are byte strings on-chain with `.of` as
identity; sum variants declare `toPlutusData`; `Interval` declares static factories; `Value` declares instance
methods (`assetOf`, `lovelaceOf`, `isEmpty`, `containsPolicy`) that `TypeMethodRegistry` implements. Rejecting them
would reject every validator, and their semantics are an S2 concern (one implementation per semantic fact), not a
source-body concern.

**Corpus check.** Apart from the ledger types, no record with a non-empty body exists in the main sources of
`julc-stdlib` (4 records), `julc-core/.../types` (4), in-repo `julc-examples` (16 records, all trees), `../julc-examples`
(146), `../julc-helloworld` (1) or `../julc-cip113-example` (15) (Evidence, scan). In JuLC's own tests, one source
would change: `SubsetValidatorTest.acceptsRecordApply` (`SubsetValidatorTest.java:180-194`) declares a record with an
`apply()` method to check that `SubsetValidator` does not flag it; with D9 that source is rejected, so the test must
be rewritten (the `apply` check can use a top-level method instead). `FqcnResolutionTest` (`:361`) nests a record
inside a record, which D9 allows.

**Interaction with D3 and D5.** D3's rejection of record patterns over records with explicit accessors is subsumed:
no such record survives registration. D5 relies on D9: an accepted user record has the implicit `equals`.

*Alternatives:*
- *Implement record members* (accessors as methods, `equals` overrides as calls, constructor bodies at `new`): the
  right long-term answer, but it touches construction, field access, pattern matching, boundaries (does a decoded
  datum run the compact constructor? Java's deserialization *does* invoke the canonical constructor, per the `Record`
  javadoc; corrected in r3, F18) and equality everywhere; it needs its own ADR (open question 8).
- *Reject only the members that change meaning silently* (accessors, `equals`, constructors) and keep today's
  messages for the rest: leaves uncoded and misleading rejections, and a member that is harmless today (an unused
  `toString`) would need its own rule when members are supported. One rule for the whole body is simpler to explain
  and to test.
- *Reject records with bodies only when the member is used*: depends on use analysis across methods and libraries;
  an unused accessor in a library becomes used by a later caller.

## Affected stages and modules

| Module | Stage | Change |
|---|---|---|
| `julc-compiler` | Validation (`SubsetValidator` or a new switch checker) | D4 checks (incl. r3's matrix and colon form), `JULC0074`/`0077` |
| `julc-compiler` | Declaration preflight (new, before `SubsetValidator`; r3) | D9 record bodies (`JULC0080`), ledger redeclaration (`JULC0081`), by unit provenance |
| `julc-compiler` | Type registration (`TypeRegistrar`, `TypeResolver`) | D6: detect a second sealed parent instead of overwriting; a "constructor-encoded?" query for D3 (r3) |
| `julc-compiler` | PIR generation (`PirGenerator`, `PatternMatchDesugarer`, `LoopBodyGenerator`) | D2/D3 lowering; guard and record-pattern variables in the ADR-048 validation |
| `julc-compiler` | Instance methods (`TypeMethodRegistry`, `PirGenerator`) | D5 classification from known sources only (r3), before the `EqualsData` handler |
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
  (wrong arm) or rejected with a misleading message; after this ADR it runs Java's arm. Every program with an
  `equals` whose operands have known, disjoint class sets (D5) returned the structural result; it is now rejected
  with `JULC0078` (r4). Every program with a record that has two sealed parents is now rejected. Colon-form switches
  that fall through between groups (#283) are now rejected (r3). A packageless top-level user record named like a
  non-hash ledger type now means the user's record (#284, r4). A user record whose implicit accessor or method call
  reached a named ledger handler by simple name (`Value`'s `isEmpty`, `lovelaceOf`, `containsPolicy`, `assetOf`)
  now uses its own accessor (F45, r6). A local declared in a `switch`'s `default` body no longer stays visible after
  the switch (#291, M1 round 3): a later read of a same-named field or variable, which used the local's type, now
  reads the field Java reads.
  Deployed scripts are unaffected (their hashes are fixed); recompiling changes their hash only if they use one of
  these constructs.
- **Newly rejected programs** are those javac 21.0.3 rejects (D4), colon-form fall-through and grouped labels and
  switches over parameterized sealed interfaces (r3, D4), user declarations of bundled ledger types (r3, `JULC0081`),
  patterns outside D3's subset (previously silently
  dropped or rejected uncoded), D6's records, and (r2) user records with body members (D9). Of the last, those with
  accessors, `equals`, compact or secondary constructors were silently miscompiled; the others were already
  rejected, now with `JULC0080` instead of uncoded or misleading messages. The corpus scan found none outside the
  ledger types, which D9 excludes.
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
| **M0** | Baselines | ADR accepted | *(Scoped in r5, F24/F40.)* For every outcome-matrix row whose checker is **D**: a `.diffcase` under `julc-difftest/src/test/resources/regressions/` with its **actual** base verdict (`AGREE`, `DIVERGE <provisional id>` or `REJECT <code>|none`), replaying green on the base. Rows with checker **P** are entered in the parity suite's case list with javac 21.0.3's verdict and JuLC's base verdict; rows with checker **U** are JuLC-only tests asserting the base behaviour. ADR-062 rows T27–T33 added so the `DIVERGE` IDs exist; codes re-checked (D7); the pinned javac 21.0.3 available to the build (r3) | Evidence G1, R1, E1, M1, R6, F12a, F16b, F16d, F19 |
| **M1** | Guards on type patterns: D1 (incl. `instanceof` in guards as total dispatch), D2 (steps 1, 2, 4, 5, 6, incl. the single-constructor tag check and r3's continuation placement), D4 checks except record-pattern exhaustiveness (`JULC0071`–`0075`, `0082`, `0083`; colon-form rule, #283), ADR-048 validation of guards | M0 | #275 pin flips to `AGREE`; #283 pin becomes `REJECT` (`JULC0075`); oracle tests for: guard reads its binding, a parameter, a static field; several guarded arms per variant with different binding names; guarded arm then unguarded; guarded arm then `default`; failing guard; guard traces in order; `when true`, `when ON` (constant variable), `when 1 == 1`; colon form that yields in every group; nested switch in a guarded arm; switch with guards inside for-each (the for-each variable read by the guard) and while bodies; **F9 scope:** a failed guard whose arm binds a name that a later arm or `default` reads as a field or `@Param` (Java's value, e.g. 99); **malformed tags (r3, F5a, F11, F3):** an out-of-range selector tag in a switch with guards and `default` fails and never reaches `#case_default`, a wrong tag on a one-constructor sum type fails, and a guard `instanceof` over a malformed component fails; rejection tests for every D4 matrix row; byte-stability check (I8) green | I1–I3, I5, I6, I8, I9, I10, I11, I16 |
| **M2a** | Flat record patterns: D3 for `V(var x, T y)`, guards on them, mixing with type patterns, exhaustiveness and dominance for flat patterns, the "constructor-encoded?" registry query, `JULC0076`, `JULC0077` | M1 | #276 pin flips; oracle tests incl. `case A(var v) -> …; case A x -> …` (legal, second arm dead), record pattern before `default`, explicit component types, field shadowing by a component name, a component name equal to a field that a later arm reads (F9); negative tests for every `JULC0076` reason, including `Value` (r3, F4); the registry-vs-`toPlutusData` encoding test over every ledger record | I2, I4, I7, I9, I10 |
| **M2b** | Nested record patterns: D2 step 3, the JLS rewrite/reduce exhaustiveness rules, nested dominance; parameterized sealed selectors rejected with `JULC0007` and record patterns on parameterized sealed components with `JULC0076` (r3, F14; aligned in r5) | M2a | Oracle tests for `P(C(var w))`/`P(D(var w))`, `Pair(C c, T t)`, a nested pattern on a component declared as a sealed variant at position ≥ 1 (`X(W(B(var v)))` with `record W(B b)`) plus a malformed-tag test for it (r4, F20), a malformed child inside a parent built with `new` from raw Data (r4, F31, pinned as documented behaviour), the JLS `Box` and `IPair` examples (exhaustive / not exhaustive), the javac 21/25 divergence shape (rejected, `JULC0005`), guards on nested patterns; malformed-tag tests (I5) on a component cast from raw Data: an out-of-range tag fails and never reaches `default`, and a nested standalone record with tag ≠ 0 fails; the lenient field-content cases pinned as documented behaviour (malformed `Bool`, extra fields, wrong Data kind, a failed guard falling to `default`; threat model); F14a/F14b rejected with `JULC0007` (selector) and a parameterized-sealed component pattern rejected with `JULC0076` (r4); size measurement linear in arms (I6) | I4–I6, I9 |
| **M3** | D6: reject a second sealed parent, `JULC0079` | M0 (independent of M1/M2) | #279 pin becomes a `REJECT` case; javac accepts and runs it (21), JuLC rejects with `JULC0079`; equal positions also rejected; a record with one sealed parent and a plain interface still compiles byte-identically | I8, I13 |
| **M3b** | D9 (r2): reject user record body members, `JULC0080`, as a declaration preflight before `SubsetValidator` (r3, F17); ledger redeclaration, `JULC0081` (r3, F8, F15); Java's shadowing rule for other ledger simple names, including the unnamed package (r4, F19, #284); rewrite `SubsetValidatorTest.acceptsRecordApply` | M0 (independent of M1/M2) | Pins for #282 (accessor through a local, a parameter and a switch binding; `equals` by sign and always-true; compact constructor; secondary constructor) become `REJECT` cases with `JULC0080`; javac accepts and runs each (N1–N10 values); every member kind in D9's list has a rejection test, including on a sealed variant and a `@NewType`; a canonical constructor with `this.x = x` reports `JULC0080`, not "'this' is not supported"; a user record with a body in package `org.julclang.ledger`, one with the exact FQCN of a bundled ledger type, and one named like a ledger hash type are rejected (`JULC0080`/`JULC0081`) at the validator, `compileMethod` and library-provider entry points; the #284 `Rational` pin flips to `AGREE`; the F45 user-`Value` accessor pin flips to `AGREE` with named ledger handlers keyed on ledger identity (r6); the mixed user/ledger `Rational` case (F23) is entered in the parity suite as a known exception owned by #287 and its JuLC value pinned by a U test, and the layout query classifies a user `record Value` by its own FQCN (r5); for **every** non-hash ledger simple name, a top-level user record of that name in a packageless source, in a named package, with and without `import org.julclang.ledger.*;`, and in a library unit of the unnamed package, compared against javac 21.0.3 (the user's type wins), and a single-type import plus a same-named declaration rejected as javac does; a record with a nested type declaration and every ledger type still compile byte-identically; the corpus scan is repeated and attached | I8, I15 |
| **M4** | D5 cross-type `equals`, `JULC0078` | M3 and M3b; open question 1 (decided in r4: reject; still open to a maintainer veto); **the corpus scan for known-disjoint `equals`** (in-repo and external examples, helloworld, CIP-113, stdlib) run with a classification-only build and attached, before any behaviour ships (r3, F6) | #277 pin becomes `REJECT` (`JULC0078`, r4); rejection tests for E1, E2, E6, E7, E9 (known, disjoint) whose message names both types; E3, E5 (known, disjoint, but already `false` on-chain) also rejected; E4 (same family) unchanged; E10's failing operand is a compile error now, not a run-time failure; **must-stay-structural** tests for F1a–F1c (ternary, ternary with a variant, switch expression), for `var` from a generic call, and for an accessor on an unknown receiver (r4, F21); unknown types keep structural equality; I8 for unclassified calls | I8, I12 |
| **M5** | Documentation, catalogue rows, release notes, AI starter packs; full validation. Troubleshooting includes "parenthesise a conditional guard" (F38) and, for `JULC0072` on `int`/`long` arithmetic in a guard, "use `BigInteger` arithmetic, or compute the value in a local before the switch, where T6 applies as in any `if`" (F42) | M1–M4, M3b | `./gradlew build -PskipSigning=true` green; external `../julc-examples` built against the published snapshot; byte-stability report attached to the PR; ADR status line and evidence updated | All |

M1, M3 and M3b can proceed in parallel. ADR-066's G1 generates guards, record patterns and cross-type `equals` only once
M1, M2 and M4 are below it in the stack (ADR-066 open question 1).

## Verification and test strategy

Evidence must not come from the code under test: the oracle is javac and the JVM (ADR-063 H1).

1. **Oracle tests** (above) in `julc-compiler` tests, in the style of `LoopFieldUpdateTest` and ADR-051's
   `SwitchFieldDecodeTest`: each case asserts the JVM result and the JuLC result at every level and VM.
2. **Outcome matrix (authoritative; r4, F32; columns added in r5, F24/F40, rows in F25).** Every case the ADR
   decides has one row; nothing else in this ADR overrides it. Columns: the **baseline** expectation at the base
   (`fd605e77`, Evidence), the **final** expectation after the milestone, and the **checker**:
   - **D** — a `.diffcase` replayed by `RegressionCasesTest` (ADR-063 D8). Only for sources the harness's javac (the
     build JDK, default release) accepts, because `JvmExecutor` throws `HarnessException` on a javac rejection
     (`JvmExecutor.java:164-167`) and the format holds only `AGREE`, `REJECT <code>|none` and
     `DIVERGE <id> <JVM>/<UPLC>` (`RegressionStore.java:85-106`), and a `DIVERGE` expectation fails on agreement.
     Baselines are therefore the real verdicts: `AGREE`, `DIVERGE` (with the provisional catalogue ID) or `REJECT`.
   - **P** — the javac 21.0.3 parity suite (Verification 3), for sources javac 21.0.3 rejects: it records javac's
     verdict and JuLC's (accepted with its value, or rejected with its code).
   - **U** — a JuLC/UPLC-only test in `julc-compiler`, for malformed Data, which has no JVM counterpart: it asserts
     the evaluation outcome directly.

   Extending the ADR-063 format with a "javac 21.0.3 rejects" expectation was considered and not chosen: it would
   change ADR-063 for cases the parity suite already owns.

   | Case (Evidence) | Baseline | Final | Milestone | Checker |
   |---|---|---|---|---|
   | #275 guards: G1, G3, G4, G5, G11, H4, H7; F33 `Boolean` guard | `DIVERGE` T27 | `AGREE` | M1 | D |
   | G2 (guard true), G8b (`when true` only arm) | `AGREE` | `AGREE` | M1 | D |
   | F9 (a failed arm's binding shadows a field a later arm reads) | `AGREE` (99; guards ignored) | `AGREE` | M1, M2a | D |
   | #283 colon fall-through (F12a) | `DIVERGE` T32 | `REJECT JULC0075` | M1 | D |
   | Grouped labels without bindings (F12b) | `REJECT none` | `REJECT JULC0075` | M1 | D |
   | Guard-introduced pattern variable (G12) | `REJECT none` | `REJECT JULC0074` | M1 | D |
   | Total pattern `case S t` (K3) | `REJECT none` | `REJECT JULC0076` (reason "unsupported: total type pattern"; r6) | M1 | D |
   | javac-rejected guard and label shapes: G6 (guarded only), G7 (dominated), G8 (`when false`), H1 (non-final local), F13a (`when 3`), F13b (two `default`s), F13c (binding clashes with a parameter) | JuLC accepts (values in Evidence) | `REJECT` `JULC0005`, `0071`, `0072`, `0073`, `0072`, `0082`, `0083` | M1 | P |
   | Malformed top-level tag on the new path (F5a), wrong tag on a one-constructor sum type (F11), guard `instanceof` over a malformed component (F3) | F5a fails; F11 accepted as the constructor; F3 guard ignored (the guarded arm runs; the `instanceof` is never evaluated; r6) | evaluation fails; never reaches `#case_default` or a later arm | M1 | U |
   | #276 record patterns: R1, H2 | `DIVERGE` T28 | `AGREE` | M2a | D |
   | R2, R7 (record patterns, no `default`) | `REJECT none` | `AGREE` | M2a | D |
   | Nested record patterns (R4) | `REJECT none` | `AGREE` | M2b | D |
   | `instanceof` record pattern (R3) | `REJECT none` | `REJECT JULC0077` | M2a | D |
   | Record pattern on `Value` (`case VA(Value(var m))`) or on a `boolean` `@NewType` (F30) | `REJECT none` (patterns dropped, not exhaustive) | `REJECT JULC0076` | M2a | D |
   | F20: a sealed-variant-typed component keeps its tag | `AGREE` | `AGREE` | M2b | D |
   | Nested pattern on a variant-typed component, `X(W(B(var v)))` | `REJECT none` | `AGREE` | M2b | D |
   | Malformed nested tags: a component with an out-of-range tag; a variant-typed component with another tag (F20) | runs `default` or a later arm (patterns dropped) | evaluation fails | M2b | U |
   | Lenient field contents of unvalidated Data (malformed `Bool`, extra fields, wrong kind, failed guard to `default`); a malformed child inside a parent built with `new` (F31) | today's lenient result | same, documented (threat model) | M2b | U |
   | Parameterized sealed selector, valid Java (F14a) | `REJECT none` | `REJECT JULC0007` | M2b | D |
   | Parameterized sealed selector, javac-invalid (F14b) | JuLC accepts (5) | `REJECT JULC0007` | M2b | P |
   | Parameterized sealed component pattern | `REJECT none` | `REJECT JULC0076` | M2b | D |
   | javac 21/25 `Pair` shape | `REJECT none` (patterns dropped) | `REJECT JULC0005` | M2b | D (the harness's javac 25 accepts it) and P (javac 21.0.3 rejects it) |
   | Record-typed selector (K2) | `REJECT none` | `AGREE` if open question 4 is (a); `REJECT JULC0007` if (b) | M2b | D |
   | #279 two sealed parents: M1, M6 | `DIVERGE` T30 | `REJECT JULC0079` | M3 | D |
   | M2 (equal positions) | `AGREE` | `REJECT JULC0079` | M3 | D |
   | M5 (a sealed variant also implementing a plain interface) | `AGREE` | `AGREE` | M3 | D |
   | #282 record members: R6, H5, N3, N5, N9 | `DIVERGE` T31 | `REJECT JULC0080` | M3b | D |
   | N1, N2, N4, N6, N8, N10 | `REJECT none` or `REJECT JULC0055` | `REJECT JULC0080` | M3b | D |
   | N7 (unused `toString`, `hashCode`) | `AGREE` | `REJECT JULC0080` | M3b | D |
   | #284 `Rational` packageless, with and without the wildcard import (F19, F19b) | `DIVERGE` T33 | `AGREE` | M3b | D |
   | F19c (the same record nested) | `AGREE` | `AGREE` | M3b | D |
   | Packageless user record named like a ledger hash type (`record PubKeyHash(byte[] h) {}`) | as found in M0 | `REJECT JULC0081` | M3b | D |
   | User source declaring a bundled ledger FQCN (`package org.julclang.ledger; record Value…`) | as found in M0 | `REJECT JULC0081` | M3b | P (javac accepts; JuLC rejects on purpose) or a `julc-compiler` unit test: `JvmExecutor` writes `<className>.java` and loads the class by simple name (`JvmExecutor.java:57`, `:158`), so it cannot run a packaged source (r6, F26) |
   | User `record Value(boolean isEmpty)` nested in `Probe`, `new Value(…).isEmpty()` (F45) | `DIVERGE` T33 (JVM `true`; JuLC fails "UnMapData") | `AGREE` | M3b | D |
   | The same record at top level in a packageless source, on the constructor and through a local (F45c) | `DIVERGE` T33 (JVM `true`; JuLC fails "MapData: expected list", #284 resolves it to the ledger type) | `AGREE` | M3b | D |
   | Mixed user/ledger `Rational`: a ledger value assigned or passed to the user's `Rational` (F23, #287) | JuLC accepts, 7 (javac: incompatible types) | unchanged: JuLC accepts, recorded as a known parity exception owned by #287 | M3b | P (exception entry) and U (value pinned) |
   | #277 known-disjoint `equals`: E1, E2, E6, E7, E9 | `DIVERGE` T29 | `REJECT JULC0078` | M4 | D |
   | E3, E5 (known-disjoint, already `false`), E10 (failing operand) | `AGREE` | `REJECT JULC0078` | M4 | D |
   | E4, F1a–F1c (same family; unknown operands) | `AGREE` | `AGREE` (must stay) | M4 | D |
   | Unknown operand disjoint in Java: `var x = c ? new R1(a) : new R1(b); x.equals(new R2(a))` (Evidence D5u) | `DIVERGE` T29 | `DIVERGE` T29 (retained; closed only if the optional least-upper-bound rule is adopted) | — | D |
   | Nested nominal `equals` (F16b, F16d) | `DIVERGE` T29 | `DIVERGE` T29 (retained) | — | D |
   | Erased nominal `equals` (E8, E11, E12) | `DIVERGE` T29 | `DIVERGE` (retained; open question 2) | — | D |
   | Out of scope, unchanged: generic record (K1), `switch` statement (G9), unnamed pattern (R5), nested sealed interfaces (M3, M4) | `REJECT none` | `REJECT none` (retained) | — | D (R5: the harness's javac 25 accepts `_`; javac 21.0.3 rejects it, P) |
   | *M1 review rows.* A guard whose `signum` receiver is a traced call (F36; the receiver is evaluated twice, #146) | `DIVERGE` T12 (traces) | `DIVERGE` T12 (retained until #146) | — | D |
   | A guard `Probe.ON` whose qualifier is a local named like the class (F50) | runs the first arm (M1 folded the field) | `REJECT JULC0072` (not folded; the component read cannot be typed, fail closed) | M1 | D |
   | Guard `(Object) x instanceof C`, `C` in another family (F51) | runs the first arm (tags compared across families) | `REJECT JULC0072` | M1 | D |
   | Guard `x instanceof C` across families, uncast (F51; javac rejects) | JuLC accepts | `REJECT JULC0072` | M1 | P |
   | `default` declaring a local named like a field a guard reads (F53) | `REJECT JULC0072` (the local leaked) | `AGREE` | M1 | D |
   | A colon group ending in `if` without `else` (F34) | `REJECT JULC0075` (unpinned) | `REJECT JULC0075` | M1 | D |
   | Guards typed `PlutusData`, `Builtins.iData(...)`, `(Object) true`, `true ? true : 0`, `c ? true : 0` (F28, F52; javac rejects) | JuLC accepts | `REJECT JULC0072` | M1 | P |
   | Constant guards `(boolean) false`, `"a" == "b"` (javac rejects) and `((true ? 2147483647 : 0L) + 1) > 0` (javac accepts, true) (F54) | accepted / `JULC0072` | `REJECT JULC0072` / accepted, unguarded | M1 | P and the parity corpus |
   | Colon-group locals in nested switches: a pattern-variable conflict, a reassigned local in a guard (F55; javac rejects) | JuLC accepts | `REJECT JULC0083` / `JULC0073` | M1 | P |
   | `case Other.A x` naming another declaration called `A` (F56; javac rejects) | JuLC accepts | `REJECT JULC0076` | M1 | P |
   | Arrow rules and colon groups mixed, both orders (F57; javac rejects) | JuLC accepts | `REJECT JULC0075` | M1 | P |
   | *M1 round 3 rows.* Constant guards with int overflow: `N + 1 < 0` (`static final int N = 2147483647`), `(2147483647 + 1) < 0`, `((int) 2147483648L) < 0` | `AGREE` | `AGREE` (kept exact by the verified subset) | M1 | D |
   | `(N << 32) == 1` with `static final long N = 1` (F70; javac: constant false) | JuLC accepted | `REJECT JULC0072` | M1 | P |
   | A narrowing declaration read by a non-constant guard (F71; javac rejects the declaration) | JuLC accepted | `REJECT JULC0072` | M1 | P |
   | Guard `((int) n) >= 0` with a `long n` (F72) | runs the first arm (narrowing erased) | `REJECT JULC0072` | M1 | D |
   | The same cast in an `if` (F72, T6) | `DIVERGE` T6 | `DIVERGE` T6 (retained) | — | D |
   | A guard call whose argument or receiver javac rejects (`ok(true)` for a `long` parameter; `signum()` on a `long`; F73) | JuLC accepts | documented gap (#287, ADR-065), as in an `if` | — | none |
   | Guard bindings conflicting with a local or with each other (F74; javac rejects) | JuLC accepted | `REJECT JULC0083` | M1 | P |
   | Guard `"a".equals(true)` (F75) | fails on-chain (`EqualsString`; JVM 2) | `REJECT JULC0072` (#292) | M1 | D |
   | `"a".equals(true)` in an `if` (#292) | `DIVERGE` T34 (JVM `false`, UPLC fails) | `DIVERGE` T34 (retained) | — | D |
   | A `default` local named like a field read after the switch (#291) | `DIVERGE` (JVM 1, JuLC 2) | `AGREE` | M1 | D |
   | A `default` block with two counted loops in a switch that is an instance-method argument (#291, second symptom) | `REJECT none` ("Unbound variable: i1") | `AGREE` | M1 | D |
   | A narrowing declaration read qualified (`Probe.N`) or through another constant (F71; javac rejects) | JuLC accepted | `REJECT JULC0072` | M1 | P |
   | `int` constants with overflow inside a non-constant guard (round 3) | first arm skipped (JVM 1, JuLC 2) | `AGREE` | M1 | D |
   | Non-constant `int` arithmetic in a guard, `x.v().signum() + 2147483647 < 0` (round 3) | first arm skipped (JVM 1, JuLC 2) | `REJECT JULC0072` | M1 | D |
   | The same arithmetic in an `if` (T6) | `DIVERGE` T6 | `DIVERGE` T6 (retained) | — | D |
   | *M1 round 4 rows.* `@Param static final boolean ENABLED = true` / `long LIMIT = 5` read by a guard, applied with another value (F41 = F80), directly, negated and through `M = LIMIT`; a minting validator | guard folded the initializer (JVM-inlined value; the `if` control read the applied one) | the guard reads the applied value, as the `if` control does; `when ENABLED` is guarded (`JULC0005` without `default`) | M1 | U |
   | Guard `x.v().intValue() < 0` / `longValue()` (F81, #294) | narrowing erased (JVM 1, JuLC 2 at 2^31) | `REJECT JULC0072` (#294) | M1 | D |
   | `intValue()`/`longValue()` in an `if` at the sign boundaries (#294) | `DIVERGE` T6 above `Integer.MAX_VALUE`, below `Integer.MIN_VALUE` and above `Long.MAX_VALUE`; `AGREE` at the boundaries | retained | — | D |
   | Out-of-range literals in a referenced declaration (field, `long` field, final local, `M = N`) or in the guard (F82; javac rejects) | JuLC accepted | `REJECT JULC0072` | M1 | P |
   | `-2147483648`, `-9223372036854775808L` beside a binding (F82) | `AGREE` | `AGREE` | M1 | D |
   | *Interim (F35):* every record-pattern row above (R1, H2, R2, R7, R4, F9, F30 ×2, `X(W(B(var v)))`, the parameterized component, `Pair`) | as listed | `REJECT JULC0076` ("not yet supported") between M1 and M2a, which sets the final verdicts listed | M1 → M2a | D |

   Every new reproducer from review is added as a row with its baseline, final expectation and checker.
3. **Compile-time parity property (I9; revised in r3, F2, F5b, F13).** A generated corpus of small switches over a
   fixed universe (sealed `S` with two records, sealed `T` with two records, `P(T)`, `Pair(T, T)`, a one-constructor
   sealed type, a parameterized sealed type): every sequence of up to four labels drawn from type patterns, flat and
   nested record patterns, with and without guards — literal `true`/`false`, constant variables (`static final`,
   `final` local), compound constants (`1 == 1`), a non-`boolean` guard, a non-constant guard reading a parameter, an
   effectively final local, a reassigned loop variable and a for-each variable — with zero, one or two `default`s, in
   arrow and colon form (including groups that fall through and grouped labels), and with pattern variables that
   collide with a parameter, plus a `Boolean`-typed guard (r4, F33). *(M1 rounds 1–3.)* Also: JLS 15.29 constants of
   the verified subset (casts, shifts, `String ==`, numeric promotion, int and long width, constant variables), shapes
   outside it (`char`, `byte`, `short`, floating point, reference casts, non-constant `String ==`, record `==`),
   guard bindings introduced when true or false and conflicting with a parameter, `instanceof` on siblings and across
   families, comparisons with operands of the wrong kind, and mixed case kinds. *(Round 3.)* Also: constant
   overflow and narrowing beside a binding, non-constant `int` arithmetic, a guard binding declared twice, and
   `String.equals` of a boolean. Calls whose argument or receiver javac rejects are not generated (#287, F73). For each, **javac 21.0.3 invoked by explicit path** (not ADR-063's `JvmExecutor`, which
   uses the build JDK's default release) gives accept/reject; JuLC must agree, except where the ADR rejects legal
   Java on purpose (colon fall-through, parameterized sealed types), which must carry the expected code. Disagreements
   between javac 21.0.3 and javac 25 `--release 21` are listed in the report, not used as verdicts. Accepted programs
   are also run both ways on a few inputs.
4. **Property tests** (jqwik) over guard thresholds and component values for the M1/M2 shapes.
5. **Byte stability (I8; revised in r4, F32).** Compile every program in the corpora listed under Compatibility at the
   base and at the head, at every level and target. Bytes are compared only for programs that (i) compile at *both*
   revisions and (ii) fall outside the approved behaviour-changing categories: a guard or record pattern; an `equals`
   that D5 classifies (now a rejection); D4's new rejections (colon fall-through, grouped labels, duplicate `default`,
   pattern-variable conflicts, parameterized sealed selectors, javac-rejected guards); D6; D9 (record bodies, ledger
   redeclaration); #284's resolution change; named ledger handlers keyed on ledger identity with the
   implicit-accessor correction (F45); and *(M1 round 3)* #291, a local declared in a `default` body that shadows a
   name read after the switch (every arm body now has its own scope). The report classifies each changed program under one of these. Programs satisfying (i) and (ii) must be byte-identical; every program in a category
   must appear in the outcome matrix or the report. Programs accepted at the base and rejected at the head are listed
   with their code and must all fall in an approved category. Counts per category go in the PR.
6. **Mutation checks.** Each of these deliberate faults must make at least one test fail: ignore guards; keep only
   the first arm per variant; evaluate the guard before binding; decode a nested component before its tag test;
   treat an unknown nested tag as "no match"; duplicate instead of join (size test); compile same-family `equals` to
   `false`; derive `false` from an inferred `DataType`; accept a second sealed parent; accept a compact constructor
   or an explicit accessor in a user record (r2); apply D9 to ledger types (r2, must break the ledger suites).
   Added in r3: classify a `var` (or a conditional or switch) from its then-branch type (F1); route an unknown
   top-level tag to the default join (F5a); omit the single-constructor tag check (F11); lower guard `instanceof`
   with `EqualsInteger` (F3); inline a continuation under an earlier arm's binders (F9); apply the D9 ledger exemption
   by package or simple name instead of provenance (F8, F15); key the effectively-final check on names (F7); treat
   colon-form fall-through as a value (#283). Added in r4: skip same-package resolution when the package is unnamed
   (F19, #284); check tag 0 instead of the construction tag for a record-typed component (F20); accept a record
   pattern on any `ConstrData`-encoded type, ignoring layout (a `boolean` `@NewType`; F30); treat a known accessor on
   an unknown receiver as known (F21); reject a `Boolean` guard (F33). Added in r6: dispatch a named ledger handler
   by the receiver's simple name instead of its ledger identity (F45).
7. **Long run.** ADR-066's G1 generates these constructs once the milestones are below it and is the continuing
   check; its rediscovery of #275–#277 on the pre-fix base is ADR-066's own gate.

## Production and audit gates

- Accepting this ADR is design acceptance only; it does not certify the implementation.
- Before merge: independent review of the diff against D1–D9 and I1–I16, the byte-stability report, and the
  external examples run. JuLC remains experimental; this ADR makes no production-safety claim.
- Blaster fixtures: if any fixture uses a construct whose bytes change, follow the artifact refresh procedure; the
  expectation (to be confirmed in M5) is that none does.

## Risks

- **Exhaustiveness and dominance drift from javac.** The JLS rewrite rules are intricate, and javac itself changed
  them after 21 (Evidence: the `Pair` shape). Mitigation: implement the JLS 21 text, pin javac 21.0.3 as its oracle,
  and the parity property (Verification 3). Accepting a non-exhaustive switch would still fail closed at run time
  (terminal `Error`), not pick an arm. Users on newer toolchains may see JuLC reject a switch their javac accepts;
  the message names JLS 21.
- **Join placement and ADR-060.** A `#case_default` bound outside the `DataMatch` must not capture user names, and
  its body must resolve names in the switch's enclosing scope. A continuation placed under an arm's source-named
  binders would be captured by them (F9). Mitigation: `hygienicName`, D2 step 5's placement rule, the F9 scope tests
  and mutation, and the binder lint tests.
- **Interaction with PV11 passes** (ADR-042/044 sharing, ADR-043 promotion) on the new shapes. Mitigation: all four
  levels in every oracle test.
- **Decompiler output.** Guarded chains will decompile as generic `if`/`Case` code rather than a Java `switch` with
  guards; acceptable, noted for the decompiler.
- **D5 false positives.** A wrong class-set computation now turns a valid comparison into a compile error (r4:
  rejection), which is fail-closed and visible. Mitigation: known sources only (r3), unknown ⇒ structural, the
  F1 must-stay-structural tests and mutation check 6.
- **Lenient decoding of unvalidated Data (r3).** The threat model's narrowing leaves field contents of unvalidated
  Data outside the guarantees. Mitigation: documentation in the guides and troubleshooting (cast untrusted Data through
  a typed boundary), pinned tests of the lenient cases, open question 9.
- **Simple-name collisions.** `RecordType` names are simple names; two records with the same simple name in
  different packages must not be classified as one class. The implementation uses fully qualified names for D5 and
  D6.

## Related findings (out of scope)

Found while writing this ADR; each reproduced at the base with the ADR-063 harness (Java VM, all four levels) unless
stated. Issues are to be filed.

- **R6, H5.** Explicit record accessors and explicit `equals` are ignored. *Moved into scope in r2* as #282 and D9.
- **`@NewType` accessor and `.of`.** On a `@NewType` local, the accessor (`t.v()`) is rejected with `JULC0055`
  (Evidence N6). The getting-started guide (§5.3) shows `AssetId.of(someBytes)` for a `@NewType record AssetId(byte[]
  hash) {}`, which JuLC compiles as an intrinsic but javac rejects, because the record declares no `of`; D9 keeps
  user-declared `of` out, so the guide should show `new AssetId(bytes)` for code that must also run on the JVM.
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
  it (code reading; not reproduced, as it needs a malformed value cast from raw Data). r3 fixes this inside guards
  only (D1).
- **Single-constructor `DataMatch` on today's path (r3, F11)** accepts any tag (`UplcGenerator.java:539-540`); fixed on
  the new path only, for byte stability.
- **`Value.inner()` (r3, F4).** On a `Value` field of a record, `q.val().inner().isEmpty()` fails with "UnConstrData:
  not ConstrData" at every level (JVM 2; Evidence F4b); `q.val().isEmpty()` agrees.
- **Nested nominal `equals` (r3, F16).** `Tuple2`/`Optional` of different record types compare equal on-chain
  (Evidence F16b, F16d); kept in T29 with pins. `JulcList.of(r1).equals(JulcList.of(r2))` is rejected with
  `JULC0055` "Cannot resolve equals" (F16c). `new Tuple2<>(new R1(a), a)` fails at run time ("MkCons: element type
  Integer[] does not match list element type Data[]", F16, first shape).
- **No nominal check across record types (r5, F23): #287.** `R1 r = new R2(a, b);` and a ledger `Rational` assigned to
  a user `Rational` are accepted (javac rejects both). D9 records the interaction with #284; M3b pins the mixed case.
  A user `record Value` reaching the ledger `Value` handlers by simple name is valid-Java reachable (F45) and is
  fixed in M3b (r6), not left to #287.
- **User record named like a ledger type (r3, F8) — now in scope as #284 (r4, F19).** My r3 probes nested the record
  in the probe class (member types resolve first), so they agreed (F8b, F8c). The reviewer's exact source is a
  top-level record in a packageless file; it and the `Rational` variant reproduce (Evidence F19). Decided in D9.
- **Top-level `instanceof` leniency, tracked (r4, F22).** D1's guard-only total dispatch is a deliberate exception to
  open question 9's "one access path, one reading" principle: it is local, cheap, and closes a fail-open path the new
  lowering would otherwise route arms through. The same leniency of `instanceof` outside guards should be fixed for
  all positions together (with #204); to be filed as an issue by the coordinator.
- **Casts lose their run-time check (#288; M1 review round 2, F61).** `A x = (A) s` on a `B` throws
  `ClassCastException` on the JVM but returns a value on-chain, because a reference cast lowers to its operand
  (`PirGenerator.java:964`). Inside guards ADR-067 rejects reference casts (fail closed); the general fix is #288.
- **`String ==` compares values (#289; F67).** Java compares the identity of two non-constant strings; JuLC compares
  their bytes. Inside guards ADR-067 accepts `==` on strings only when both are compile-time constants (interned, so
  identity is value equality); the general fix is #289.
- **Container equality.** `contains`, `indexOf` and map lookups compare elements with `EqualsData`, so D5's
  cross-type cases also arise inside collections (code reading).
- **`Builtins.trace` with one argument** crashes the compiler (`IllegalArgumentException`, an ADR-063 `Crash`)
  instead of reporting a diagnostic; javac rejects the call anyway.
- **`default` duplication.** A `default` covering *k* uncovered constructors is emitted *k* times on today's path
  (code reading, `PirGenerator.java:2454-2465`); D2 keeps it for byte stability.

## Open questions

1. **Known-disjoint `equals`: (a) `false` plus a warning, or (b) an error? — Decided in r4: (b), reject with
   `JULC0078`** (both reviewers, the author and the coordinator; the maintainer may still veto before acceptance). The
   M4 corpus scan stays an entry gate and now measures what the rejection breaks. History of the question: *(Rationale revised in r3, F6.)* "Rejects
   Java that javac accepts" is not a reason against (b): D6 and D9 do exactly that, on fail-closed grounds, and so
   does D4 for colon-form fall-through. The real trade-off:
   - (a) agrees with the JVM, but recompiling a deny check such as `if (x.equals(blocked)) return false;` that was
     true on-chain silently turns it into "never deny"; the only signal is a warning. Where a warning surfaces: the
     compiler returns it in `CompileResult.diagnostics()`, but the testkit prints diagnostics only when compilation
     fails (`JulcEval.java:304`, `ValidatorTest.java:152`), so a test-driven user never sees it; the Gradle plugin,
     annotation processor, playground and Elci must be checked in M4's entry gate.
   - (b) fails closed: such code stops compiling, with a message naming the two types, and a misclassification
     (F1-style) becomes a compile error instead of a wrong value.
   Lean (changed in r3): **(b)**, because the warning is invisible in the testkit and (b) turns any residual
   classification error into a visible error. The corpus scan is now an M4 entry gate (it previously came after M4).
2. **Erased nominal types** (`@NewType`, ledger hash types, and those against `BigInteger`/`byte[]`). Options:
   (a) extend D5 here, carrying the declared nominal type alongside the PIR type; (b) a follow-up issue and catalogue
   row. Lean: (b). *(Reworded in r3, F1.)* For declared operands these types are as decidable as records, so this is
   not about availability: the classifier must carry nominal types that the PIR erases, and most such values in
   validators come from generic ledger accessors that D5 classifies as unknown, so the extension would catch little
   while touching common code; ADR-065's javac frontend would supply exact types for all operands. The evidence (E8,
   E11, E12) is recorded so the follow-up starts from reproducers.
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
8. **Record members (r2).** Options: (a) reject every body member except nested types (D9); (b) implement members
   (accessors, `equals`, constructor bodies) in a later ADR, which must also decide whether a datum decoded at a
   boundary runs a compact constructor. Lean: (a) now, (b) only on demand; no example or library needs members today.
   A narrower allowance worth considering later is a `@NewType` `static of(x) { return new T(x); }`, which would let the
   guide's `.of` example compile with javac.
9. **Strict in-body decoding (r3, F10).** Options: (a) keep ADR-051's lenient field-content contract and narrow the
   threat model (r3's choice); (b) strict decoding (canonical `Bool`, arity) for every in-body cast of raw Data to a
   typed value, everywhere, in its own ADR; (c) strict decoding only on the new path. (c) is rejected: one record
   would read differently by access path. *(r4, F22)* D1's guard-only `instanceof` dispatch is a recorded, deliberate
   exception (tags only, local to guards); see Related findings. Lean: (a) here, (b) as a follow-up if the maintainer wants in-body Data
   to carry the same guarantee as boundaries.
10. **The javac 21/25 exhaustiveness divergence (r3, F2).** JuLC follows JLS 21 and rejects the `Pair` shape that javac
    25 `--release 21` accepts. Option: accept it (it is exhaustive at run time). Lean: follow JLS 21 until JuLC
    targets a newer language level; record each such divergence.

## Evidence

All at `fd605e77` on macOS, JDK 25.0.2, Java VM (`DiffConfig.allLevels()`), via a temporary probe test in
`julc-difftest` that called `DiffRunner.standard().run(new DiffCase(...))` and was deleted afterwards:

```bash
./gradlew :julc-difftest:test --tests 'org.julclang.difftest.Adr067ProbeTest'   # temporary, deleted
# r1 also: Adr067Probe2Test (M1-M6); r2: Adr067Probe3Test (N1-N10);
# r3: Adr067Probe4Test and Adr067Probe5Test (F-rows); same harness, all deleted
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
| N1 | Static factory `R.twice(a)` declared in the record (r2) | 10 | rejected: "Undefined variable: R" |
| N2 | Instance method `r.twice()` (r2) | 10 | rejected: `JULC0055` "Cannot resolve twice" |
| N3 | Compact constructor `R { x = x.abs(); }`, `new R(-a).x()` (r2) | 5 | -5 |
| N4 | Explicit canonical constructor adding one (r2) | 6 | rejected: "'this' is not supported on-chain" |
| N5 | Secondary constructor `R(x) { this(x, TEN); }`, `.y()` (r2) | 10 | fails: "HeadList: empty list" |
| N6 | `@NewType Amt` with a declared `static of`, `Amt.of(a).v()` (r2) | 5 | rejected: `JULC0055` "Cannot resolve v" |
| N7 | `toString` and `hashCode` declared, unused (r2) | 5 | 5 |
| N8 | Static field `R.K` (r2) | 15 | rejected: "Undefined variable: R" |
| N9 | Explicit accessor called through a helper's parameter (r2) | -5 | 5 |
| N10 | Instance method on a sealed variant, called on a switch binding (r2) | 10 | rejected: `JULC0055` "Cannot resolve d" |
| F1a | `var x = a > 0 ? new R1(a) : new R2(a); x.equals(new R2(a))`, `a = -5` (r3) | 1 | 1 (agrees; must stay structural) |
| F1b | `var x = a > 0 ? new A(a) : s;` (`s` holds `B`), `x.equals(new B(a))` (r3) | 1 | 1 (agrees) |
| F1c | `var x = switch (s) { case A p -> new R1(a); case B q -> new R2(a); }; x.equals(new R2(a))` (r3) | 1 | 1 (agrees) |
| F4 | `record VA(Value val)`, `q.val().isEmpty()` (r3) | 2 | 2 |
| F4b | Same, `q.val().inner().isEmpty()` (r3) | 2 | fails: "UnConstrData: not ConstrData" |
| F8b | Nested user `record Interval(BigInteger lo)`, no ledger import, `iv.lo()` (r3) | 5 | 5 |
| F8c | Same, importing `org.julclang.ledger.Value` (r3) | 5 | 5 (an earlier probe with `org.julclang.ledger.*` also gave 5) |
| F9 | Static field `limit = 99`; `case A(var limit) when limit.signum() > 0 -> ONE; case A q -> limit;`, `A(-1)` (r3) | 99 | 99 (guards are ignored today, so the first arm's binding is never used) |
| F12a | #283: `case A x: x.v().negate(); default: yield TWO;` (r3) | 2 | -5 |
| F12b | `case X0(): case Y0(): yield 3;` (r3) | 3 | rejected: "not exhaustive. Missing cases: X0, Y0" |
| F13b | Two `default` labels (r3) | javac: "duplicate default label" | accepted, 10 (the second `default`) |
| F13c | `case A a` where `a` is a parameter (r3) | javac: "variable a is already defined" | accepted, 1 |
| F14a | `G<BigInteger> g`, `case GD d` only (`GD implements G<BigInteger>`, `GC implements G<String>`) (r3) | 5 | rejected: "not exhaustive. Missing cases: GC" |
| F14b | Same with `case GC c` added (r3) | javac: "incompatible types: G<BigInteger> cannot be converted to GC" | accepted, 5 |
| F16 | `new Tuple2<>(new R1(a), a).equals(new Tuple2<>(new R2(a), a))` (r3) | false | fails: "MkCons: element type Integer[] does not match list element type Data[]" |
| F16b | `new Tuple2<>(new R1(a), new R1(a)).equals(new Tuple2<>(new R2(a), new R2(a)))` (r3) | false | true |
| F16c | `JulcList.of(new R1(a)).equals(JulcList.of(new R2(a)))` (r3) | false | rejected: `JULC0055` "Cannot resolve equals" |
| F16d | `Optional.of(new R1(a)).equals(Optional.of(new R2(a)))` (r3) | false | true |
| F19 | #284: packageless file, top-level `record Rational(BigInteger denominator, BigInteger numerator)`, `new Rational(a, b).numerator()`, a=5, b=7 (r4) | 7 | 5 |
| F19b | Same with `import org.julclang.ledger.*;` (r4) | 7 | 5 |
| F19c | Same record nested in `Probe` (r4) | 7 | 7 |
| F20 | `record W(B b)` (`B` at position 1 of `S`), `S s = new W(new B(a)).b()`, switch over `s` (r4) | 2 | 2 (the component carries `B`'s tag) |
| F45 | `record Value(boolean isEmpty) {}` nested in `Probe`, `new Value(a.signum() > 0).isEmpty()`, a=5 (r6) | `true` | fails: "UnMapData: not MapData" |
| F45b | Same, nested, through a local, `Value v = new Value(…); v.isEmpty()` (r6) | `true` | `true` |
| F45c | The record at top level of a packageless source, on the constructor and through a local (review round 5, F27) | `true` | fails: "MapData: expected list, got VCon(Bool[])" (#284) |
| D5u | `var x = a > 0 ? new R1(a) : new R1(b); x.equals(new R2(a))` (r5) | 0 | 1 (structural; the operand is unknown under D5) |
| F23 | JuLC-only (javac: incompatible types): member `record Rational(BigInteger denominator, BigInteger numerator)`, `Rational q = new org.julclang.ledger.Rational(a, b); q.numerator()`, a=5, b=7 (r5) | — | 7 at all four levels |
| F33 | Guard of type `Boolean` (`static Boolean big(BigInteger x)`), false for the value (r4) | 2 | 1 (guard ignored, #275) |

**javac, r3 (F2, F5b, F13, F14):** each shape compiled with javac 21.0.3 (Temurin,
`~/.sdkman/candidates/java/21.0.3-tem/bin/javac`) and javac 25.0.2 `--release 21`:

| Shape | javac 21.0.3 | javac 25.0.2 `--release 21` |
|---|---|---|
| `Pair(C c, var t)`, `Pair(var t, C c)`, `Pair(D d, D d2)` | error: does not cover all possible input values | accepted |
| `case A x when ON` (`static final boolean ON = true`), `case B y` | accepted | accepted |
| `when OFF` (`static final boolean OFF = false`) | error: constant expression with value 'false' | same |
| `final boolean on = false; … when on` | error: constant expression with value 'false' | same |
| `case A x when 1 == 1`, `case B y` | accepted | accepted |
| `when 3` | error: incompatible types: int cannot be converted to boolean | same |
| Two `default` labels | error: duplicate default label | same |
| `case A a` with a parameter `a` | error: variable a is already defined | same |
| `G<BigInteger>`, `case GD d` only | accepted | accepted |
| `G<BigInteger>`, `case GC c`, `case GD d` | error: incompatible types | same |
| Guard reads the for-each variable | accepted | accepted |
| `case A x: x.v().negate(); default: yield 2;` | accepted | accepted |

**javac `--release 21`** (javac 25.0.2; r1), each a one-class file:

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

**Record-body scan (r2).** A script that finds each `record` declaration (comments and string literals removed)
and lists the ones with a non-empty body, run over the main sources of `julc-ledger-api`, `julc-stdlib`,
`julc-core/src/main/java/org/julclang/core/types`, and over all sources (main and test) of in-repo `julc-examples`,
`../julc-examples/src`, `../julc-helloworld` and `../julc-cip113-example`. Only `julc-ledger-api` has records with
bodies (74: the seven hash types with constructors, accessors, `equals`, `hashCode`, `toString`, `of` and
`fromPlutusData`; the sum variants with `toPlutusData`; product types with static `fromPlutusData`/`toPlutusData`;
`Interval` static factories; `Value` instance methods). Record counts in the others: 4, 4, 16, 146, 1, 15, none with a
body. A `grep` over the text-block sources in every module's tests found two records with bodies:
`SubsetValidatorTest.acceptsRecordApply` (a method, rejected by D9) and `FqcnResolutionTest` (a nested record,
allowed).

**Searches.** `grep -rn "getGuard\|RecordPatternExpr"` over all Java sources at the base: no match outside
JavaParser itself. No on-chain source in in-repo `julc-examples`, `../julc-examples` or `../julc-cip113-example`
uses a guard or a record pattern in a `switch` (the matches are JuLC's own off-chain code in `julc-core` and test
utilities), and none declares a record implementing two interfaces; `julc-ledger-api`, `julc-stdlib` and
`julc-core` declare none either. Diagnostic codes were read from `diagnostics.json` on every origin branch
(`git show <branch>:julc-compiler/src/main/resources/diagnostics.json`).

## Implementation results

### M0: baseline pins (2026-10-30)

- **Catalogue IDs.** Re-checked on every origin branch: #269 and #271 add T24 and T25, and #272 (#257) added a T26
  row of its own after this ADR was accepted. The rows therefore take T27–T33 (#275 T27, #276 T28, #277 T29, #279
  T30, #282 T31, #283 T32, #284 T33), and every ID in this ADR was shifted by one. ADR-062 has the seven rows.
- **Diagnostic codes.** `diagnostics.json` re-read on every origin branch: `JULC0060`–`JULC0066` (#272) and
  `JULC0070` (#271) are the only codes above `JULC0059`, so `JULC0071`–`JULC0083` remain free.
- **D rows.** 78 new `.diffcase` files under `julc-difftest/src/test/resources/regressions/`, one per D row and
  reproducer (several rows have more than one), with the base's actual verdict: 37 `DIVERGE` (T27 8, T28 2, T29 11,
  T30 2, T31 7, T32 1, T33 6), 16 `AGREE` and 25 `REJECT` (22 `none`, 3 `JULC0055`). Every JVM and JuLC value equals
  the Evidence table, with three findings: the accessor-on-a-constructor shapes of N3, N5, N7 and F20 are rejected with
  `JULC0055` (as #282 and #284 report), so those pins read through a local, as the Evidence probes did; and the
  packageless `record PubKeyHash(byte[] h)` ("as found in M0") diverges (JVM the bytes, JuLC fails "UnBData"), pinned
  as T33. Recorded with `DiffRunner.standard()` (Java VM, all four levels; every verdict was level-independent).
- **P suite.** `julc-difftest` `Javac21ParityTest` holds the twelve P rows (G6, G7, G8, H1, F13a, F13b, F13c, F14b,
  the `Pair` shape, the bundled-FQCN source, F23, R5) with javac 21.0.3's verdict, JuLC's base verdict and its
  current expectation. javac 21.0.3 (Temurin, `~/.sdkman/candidates/java/21.0.3-tem`) runs by explicit path
  (`Javac21`): `-Pjulc.javac21=<javac>`, else `JULC_JAVAC21`, else the SDKMAN path; a javac whose `-version` is not
  `javac 21.0.3` is refused; without one the suite is skipped unless `-Pjulc.javac21.required=true`. JuLC's class files
  target Java 25, which javac 21 cannot read, so P sources are self-contained and F23 compiles against a stub of the
  ledger `Rational`; a one-file driver runs every case in one JVM of that JDK. Findings: javac 21.0.3 reports R5 as
  "unnamed variables are a preview feature" (javac 25 `--release 21`: "not supported in -source 21"); the only 21/25
  verdict difference is the `Pair` shape, as recorded; the bundled-FQCN source is rejected at the base with
  `JULC0055` (the user's `Value` is replaced by the ledger one).
- **U suite.** `julc-compiler` `SwitchMalformedDataTest` (eleven tests, every level, Java and Scalus VMs) pins the
  base: F5a fails; F11 accepted as the constructor (1); F3 runs the guarded arm (1); the M2b nested-tag rows run a
  later arm or `default`; lenient field contents (malformed `Bool` is `false`, extra fields ignored, wrong kind
  fails, a guard over a malformed `Bool` ignored, F31's malformed child kept); F23 returns 7.
- **Commands.** `./gradlew :julc-difftest:test --tests '*RegressionCasesTest*' --rerun` (116 cases, 0 failures),
  `./gradlew :julc-difftest:test --tests '*Javac21ParityTest*' --rerun` (12, 0 failures),
  `./gradlew :julc-compiler:test --tests '*SwitchMalformedDataTest*' --rerun` (11, 0 failures).

### M1: guards, and D4's compile-time checks (2026-10-30)

- **Lowering (D1, D2).** `SwitchPatternChecker` (new, `julc-compiler/.../pir`) runs D4 on the source of every
  switch expression before it is lowered and returns the type-pattern arms. A switch with at least one guard takes
  `PirGenerator.generateGuardedSwitch`: one `DataMatch` over the selector, one branch per constructor in tag order;
  in a constructor's branch its fields are decoded once (ADR-051's `wrapDecode`, unchanged) under names chosen with
  `PirHelpers.hygienicName("#case_<C>_<i>", names in scope)` and shared by that constructor's arms; the arms form a
  chain in source order (`let x = #case_data in if g then b else <next>`); a continuation is bound as a unit lambda
  (`#case_next`) before the arm's binder and inlined only when the arm's variable is not free in it (F9); `default`
  is one join (`#case_default`) bound outside the `DataMatch`; a constructor with no arm calls it and decodes
  nothing. Every other switch keeps the base lowering (I8). Constant `true` guards are unguarded, so their arms emit
  no test.
- **Deviation (implementation choice, same semantics):** D2's single-constructor tag check (F11) is a check on the
  selector (`let #case_selector = s in if fstPair(unConstrData #case_selector) == 0 then #case_selector else
  error`) placed before the `DataMatch`, not a change to `UplcGenerator`'s one-branch dispatch, so no other
  `DataMatch` (ADR-038/041 passes, decompiler) sees a new shape. The tag is checked before any field is decoded.
  It costs one `unConstrData` and one `fstPair` per evaluation, only for one-constructor sum types on the new path.
- **`instanceof` in guards (D1, F3):** a plain type test `x instanceof V` inside a guard is a `DataMatch` over V's
  sealed interface with `true`/`false` branches (and the same tag check for one constructor); `instanceof V v` in a
  guard is `JULC0074`. Outside guards, `instanceof` is unchanged.
- **D4 and D7.** New codes `JULC0071`–`JULC0076`, `JULC0082`, `JULC0083` emitted; `JULC0005` and `JULC0007` promoted
  to emitted; `JULC0077`–`JULC0081` added to the catalogue as `planned` (their milestones emit them). Codes were
  re-checked on every origin branch before the commit. The checks: guard type (`boolean`/`Boolean`; constant
  non-boolean guards on the source, others on the inferred PIR type), constant `false` (literals, constant variables
  — `final` locals and `static final` fields of primitive or `String` type, simple or `Type.NAME` — and compound
  constants), effectively final by resolved declaration (sibling blocks independent, for-each variables accepted),
  guard-introduced pattern variables, dominance (a preceding unguarded type pattern of the same type, including over
  a later record pattern; `default` before a pattern), duplicate `default`, pattern-variable conflicts (parameter,
  local, for-each variable, enclosing pattern variable, or twice in one pattern; a field is allowed), applicability
  and the total type pattern (`JULC0076`), colon-form groups that can complete normally or share labels (`JULC0075`,
  also on the base lowering: #283), non-sealed and parameterized sealed selectors (`JULC0007`), exhaustiveness
  without guarded labels (`JULC0005`). ADR-048's arm validation now visits guards (I11).
- **Scope notes.** Record patterns are not lowered yet (M2a): their labels are still dropped, as at the base, and
  a switch whose only new construct is a record pattern stays on the base lowering. Their labels do take part in the
  checks above, so the R2, R7, R4, F30, `X(W(B(var v)))`, parameterized-component and `Pair` pins moved from
  `REJECT none` to `REJECT JULC0005` (still a rejection; M2a/M2b set the final verdict), and K2 from `REJECT none`
  to `REJECT JULC0007` (open question 4 decides it in M2b). F14a/F14b's `JULC0007` is done here although the matrix
  places it in M2b: it is the same selector check.
- **Pins (D).** The eight T27 pins flipped from `DIVERGE` to `AGREE` at all four levels; F12a (T32) and F12b are
  `REJECT JULC0075`, G12 `REJECT JULC0074`, K3 `REJECT JULC0076` with reason "unsupported: total type pattern". Each
  flipped file keeps the base observations as `recorded.jvm` / `recorded.base` and a history note.
- **P suite.** G6 `JULC0005`, G7 `JULC0071`, G8 `JULC0072`, H1 `JULC0073`, F13a `JULC0072`, F13b `JULC0082`, F13c
  `JULC0083`, F14b `JULC0007`, `Pair` `JULC0005`; javac 21.0.3 rejects each. The bundled-FQCN, F23 and R5 rows are
  unchanged (M3b and out of scope).
- **Parity corpus (Verification 3, M1 dimensions):** `SwitchParityCorpusTest` generates 1,002 programs (every
  sequence of up to four labels from `A`, `A when g`, `B`, `B when g`, `default` in arrow form and up to three in
  colon form; sixteen guard kinds in three shapes; colon fall-through, grouped and multi-pattern labels;
  pattern-variable collisions; one-constructor and parameterized sealed types). Result: 114 accepted by javac 21.0.3
  and JuLC, 884 rejected by both (every JuLC rejection carries a D4 code, except one JavaParser parse error for
  `case A x, B y`, which javac rejects too), 4 rejected by JuLC on purpose (colon fall-through, grouped labels without
  bindings, a parameterized selector); 35 accepted programs run on the JVM and on JuLC at all four levels on four
  inputs each, all agreeing. javac 21.0.3 and javac 25 `--release 21` agree on all 1,002 (the known `Pair` difference
  needs record patterns, M2b).
- **Oracle tests.** `SwitchGuardTest` (javac + JVM vs JuLC at all four levels on the Java and Scalus VMs, values and
  traces): a guard reading its binding, a parameter and a static field; several guarded arms per variant with
  different binding names; guarded then unguarded; guarded then `default`; a failing guard; guard and body traces in
  Java's order; `when true`, `when ON`, `when 1 == 1`; colon form that yields in every group; nested switches in a
  guard and in a guarded arm; guard `instanceof`; a `Boolean` guard; a one-constructor sum type; guards in for-each
  (reading the loop variable) and while bodies; F9 scope (a failed arm's binding named like a static field that a later
  arm, `default` and another guard read: Java's 99); size linear in guarded arms (1 to 12 pairs of join-forcing arms: every
  added pair costs the same within 8 bytes); `default` emitted once. `SwitchDiagnosticsTest`: one rejection test per D4 row
  plus accepted neighbours. `SwitchMalformedDataTest` (U): F5a fails without reaching `default`, F11 and F3 now fail;
  the guard over a malformed `Bool` now falls to `default` (2).
- **Byte stability (I8, Verification 5).** Compiled at the base (`fd605e77`, a separate worktree) and at the head,
  every level, with the annotation processor's library resolution, and compared by SHA-256 of the FLAT bytes: 3,764
  (program, level) rows; **784 programs compile at both revisions and all are byte-identical** (external
  `../julc-examples` at 22b4e00 via the clean worktree `../julc-examples-cmp`: 42; in-repo `julc-examples`: 32,
  mostly text-block validators; `julc-compiler` test text blocks containing `switch`: 697, one row per static method;
  `julc-stdlib` tests: 1; Blaster fixtures: 7; `../julc-cip113-example`: 4; `../julc-helloworld`: 1). 0 changed, 0
  newly rejected, 0 newly accepted; 157 programs are rejected at both revisions for reasons unrelated to this ADR
  (test files and off-chain demos without a validator class, stale CIP-113 API names, `return` in `while`, arrays).
  No approved behaviour-changing category occurs in these corpora (none uses a guard, a record pattern or a D4
  rejection). The Blaster fixtures' bytes are unchanged, so no artifact refresh is needed. `GoldenUplcTest`,
  `Adr060ByteSnapshotTest` and the O-series fixtures pass unchanged in `:julc-compiler:test`.
- **Mutation checks** (each fault injected in the working tree, the focused tests run, the file restored; none
  committed): all eleven M1 mutations are detected.
  | Mutation (Verification 6) | Detected by |
  |---|---|
  | ignore guards | 13 tests, e.g. `SwitchGuardTest.guardReadsItsBinding` |
  | keep only the first arm per variant | 12 tests, e.g. `severalGuardedArmsWithDifferentBindingNames` |
  | evaluate the guard before binding | `guardPassesItsWholeBindingToAHelper` (a guard reading `x.v()` uses the decoded field, so this test passes the binding itself) |
  | duplicate instead of join (`default` copied to each use) | `defaultBodyIsEmittedOnce` |
  | route an unknown top-level tag to the default join (F5a; injected in the legacy tag chain) | `SwitchMalformedDataTest.outOfRangeTagWithGuardsAndDefaultFails`, `guardInstanceofOverMalformedComponent` |
  | omit the single-constructor tag check (F11) | `wrongTagOnSingleConstructorSumType` |
  | lower guard `instanceof` with `EqualsInteger` (F3) | `guardInstanceofOverMalformedComponent` |
  | inline a continuation under an earlier arm's binders (F9) | `failedArmBindingNeverShadowsLaterArmOrDefault` |
  | key the effectively-final check on names (F7) | `sameNameReassignedInAnotherMethodIsAnotherVariable` (JuLC renames block locals apart within a method, ADR-060, so the discriminating case is another method's variable) |
  | treat colon-form fall-through as a value (#283) | `colonGroupThatFallsThrough`, `lastColonGroupMustYield` |
  | reject a `Boolean` guard (F33) | `booleanGuard` |

  The first run left two mutations undetected (guard before binding; names for F7); the two tests above were added
  for them. The M2a/M2b/M3/M3b/M4 mutations belong to those milestones.
- **Commands.** `./gradlew :julc-compiler:test :julc-difftest:test --rerun`: julc-compiler 2,041 tests, 0
  failures (new: `SwitchGuardTest` 17, `SwitchDiagnosticsTest` 27, `SwitchMalformedDataTest` 11); julc-difftest 208,
  0 failures (`RegressionCasesTest` 116, `Javac21ParityTest` 12, `SwitchParityCorpusTest` 1 over 1,002 programs).
  `./gradlew build -PskipSigning=true`: success; 12,156 tests in all modules, 0 failures, 530 skipped (pre-existing
  skips). Byte stability and mutations as above (harness and mutants in a scratch directory, not committed).
- **Related findings (new, out of scope).** `x.signum()` evaluates its receiver twice when the receiver is a call
  (`said(m, ONE).signum()` traces twice on-chain, once on the JVM); `compareTo` lowers the same way. A divergence in
  traces and cost (T12-like), not in values; tracked on #146, pinned in the review round below. JavaParser's JAVA_21 grammar rejects `case A x, B y` before
  JuLC's checks, as an uncoded parse error.
- **Open points.** (1) *Superseded in the review round below:* a guard whose type cannot be established is now
  rejected (M1 decision, D4). (2) The P suite and the parity
  corpus skip when javac 21.0.3 is not installed (CI); `-Pjulc.javac21.required=true` makes that a failure. (3) Truffle
  is not in the oracle tests (the existing switch suites run it only in `pairCaseTest`). (4) The F9 test uses a static
  field; an `@Param` field is the same name-resolution path but is not exercised (`compileMethod` has no `@Param`).
  (5) Docs, troubleshooting entries and release notes are M5.

### M1 review round 1 (2026-10-30; Codex F50–F59, review F28, F29, F34–F36)

Both reviews of `0e42ee9e` requested changes. Every finding is fixed; none needed a change to the accepted design
beyond the deviation-4 decision, which both reviewers and the coordinator agreed (fail closed; recorded in D4).

| Finding | Change |
|---|---|
| F28 (P2), F52 (P1): guards typed by the lowered PIR value | Guard type is now the **Java type from the source** (`SwitchPatternChecker.sourceType`): literals, operators (a conditional by both branches), casts before erasure, declared types of locals, parameters and fields, declared returns of the unit's methods; open names and calls typed from the symbol table. Not established ⇒ `JULC0072` with a "typed local or helper" suggestion. Checked before constants are folded, so `true ? true : 0` no longer folds to `true`. Accepted neighbours: `JulcList<Boolean>` `head()`/`get(i)`, a `Boolean` local, `boolean` parameter, `Boolean` helper, a conditional of booleans. |
| F50 (P1): `Probe.ON` folded with a local named `Probe` | `JavaConstants` folds `T.F` only when `T` is a type in scope and no variable of that name obscures it (JLS 6.4.2), resolved by declaration. The probe is no longer folded; since JuLC cannot type a record component read as a field, it now fails closed (`JULC0072`). Pinned. |
| F51 (P1): guard `instanceof` across sealed families | The operand's static type (a cast's target, else its resolved type) must be the target's sealed interface or one of its variants; another family is "incompatible types", an unknown or erased type (`Object`, `PlutusData`) is "not established". Both `JULC0072`. The target is resolved by declaration. Pinned (`(Object) x instanceof C`); the uncast form is a P row. |
| F53 (P1): `default` locals leaked into later arms | `default` is compiled in its own scope on the guard-aware path. Oracle tests with a boolean field and with a default local of another type than the field the arms use. |
| F54 (P1), F29 (P3): constant guards | New `JavaConstants` (JLS §15.29): primitive and `String` casts, shifts, `String ==` (interned constants), binary numeric promotion in arithmetic and conditionals, typed int/long overflow, `final` locals and fields (simple or type-qualified). Floating point and division by zero are rejected as unclassifiable (`JULC0072`), never guessed. `JULC0074` now applies only to pattern variables a guard introduces **when true** (JLS 6.3.1) or reads; `!(o instanceof A a2)` is accepted and its type test is a total dispatch. |
| F55 (P1): colon-group locals | `resolveLocal` gives a colon group's locals Java's scope: the rest of its group, nested switches inside it, and later groups of the same switch. Conflicts, reassignment and `final` constants follow. |
| F56 (P1): applicability by simple name | A qualified pattern type is resolved to its declaration and must be a permitted variant of exactly the selector's sealed interface (`JULC0076` otherwise), on both lowerings. An unqualified name keeps the base rule (I8). |
| F57 (P2): mixed arrow and colon entries | `JULC0075` ("different case kinds"), both orders. |
| F58 (P2): outer fields decoded again under an inner same-named field | On the guard-aware path, `x.f()` reuses the hygienic binding of the pattern scope that owns `x`, whatever inner scopes bind a field `f` (an inner `x` is `JULC0083`). The base path's lookup is unchanged (I8). Test: the PIR has as many `unIData` with a same-named inner field as with a differently named one. |
| F59 (P2): two tests that did not exercise their hazard | The trace test traces inside the selector (`switch (pick(a))`). The size test now forces one `#case_next` join per `A limit` arm and asserts the join count from the PIR (1 to 12). |
| F34 (P3): `if` without `else` in a colon group | Pinned (`REJECT JULC0075`), a diagnostics test, a corpus shape and a mutation. |
| F35 (P3): record patterns still dropped | Interim: every record-pattern label is rejected with `JULC0076` ("not yet supported", M2a). Eleven pins moved to `REJECT JULC0076` with their earlier expectation kept as history; the `Pair` P row too; the two M2b U tests now assert the rejection. M2a replaces this. |
| F36 (P3): receiver evaluated twice | Tracked on #146. A retained `DIVERGE` T12 pin: a guard `said(x.v()).signum() > 0` traces once on the JVM and twice on-chain. |

**Evidence.**
- **Tests** (`./gradlew :julc-compiler:test :julc-difftest:test --rerun -Pjulc.javac21.required=true`): julc-compiler 2,055,
  0 failures (`SwitchGuardTest` 20, `SwitchDiagnosticsTest` 38, `SwitchMalformedDataTest` 11); julc-difftest 227, 0
  failures (`RegressionCasesTest` 121 pins, five new; `Javac21ParityTest` 26 rows, fourteen new; `SwitchParityCorpusTest`).
  `./gradlew build -PskipSigning=true`: success; 12,189 tests in all modules, 0 failures, 530 skipped (pre-existing).
- **Parity corpus:** 1,044 programs (thirteen new guard kinds, an `if`-without-`else` group, both mixed orders): 126
  accepted by javac 21.0.3 and JuLC (47 also run both ways at all four levels on four inputs, all agreeing), 909 rejected
  by both, 9 rejected by JuLC on purpose: colon fall-through, grouped labels, the parameterized selector, the
  `if`-without-`else` group, a guard binding introduced when true (`JULC0074`, D1), and the floating-point constant
  `1.5 > 1.0` (`JULC0072`; **known over-rejection**: javac accepts it as the constant `true`). javac 21.0.3 and javac 25
  `--release 21` agree on all of them.
- **Known over-rejections (fail closed):** floating-point constant guards; `(Boolean)` or `(boolean)` casts of an
  operand that is not boolean (a downcast from `Object`); guards whose leaf JuLC cannot type (a record component read
  as a field, a nested switch whose arms read their own bindings); `instanceof` on an operand of unknown type.
- **Byte stability** re-run against `fd605e77` exactly as before: 3,764 rows, 784 programs byte-identical, 0 changed,
  0 newly rejected or accepted.
- **Mutations:** all 17 detected — the eleven M1 mutations again, and six new ones: F50 fold a qualified constant
  without resolving its qualifier (`qualifiedConstantNeedsATypeQualifier`); F51 drop the family check
  (`guardInstanceofAcrossFamiliesOrOnAnUnknownType`); F52 type the guard by its lowered value (3 tests); F53 compile
  `default` in the switch's scope (`defaultLocalsDoNotLeakIntoArms`, after its type-differing case was added: the
  fail-closed typing masks the review's exact shape); F34 treat an `if` without `else` as unable to complete normally
  (`colonGroupEndingInIfWithoutElse`); F58 re-extract the outer field (`nestedSwitchReusesTheOuterDecodedField`, after
  its count was corrected to the PIR's `unIData` spelling).

### M1 review round 2 (2026-10-30; Codex F60–F67, review F37–F39)

The review comment approved `d1f1783b` with P3s F37–F39; Codex requested changes (F60–F67, all P1). Following the
coordinator's direction, every Codex finding is fixed **fail closed**, by restricting the guard subset rather than
implementing Java's typing in general; each restriction is a known over-rejection below. No design change beyond
D4's guard subset was needed.

| Finding | Change |
|---|---|
| F60: comparison operands and conditional conditions unchecked | The type lattice distinguishes `boolean`/`Boolean`, primitive and boxed integers, `BigInteger`, `String`, `char`-like values and other references. `==`/`!=` need two booleans or two numbers with at least one primitive (or two constant strings), `<`… need numbers, `&&`/`||`/`!` need booleans, a conditional's condition must be boolean; anything not established is `JULC0072`. `BigInteger` results (record components declared `BigInteger`, integer calls outside a known list of `int`/`long`-valued methods) are references. |
| F61: casts erased without their check | A guard may cast only to a primitive type or to `java.lang.Boolean` of a boolean operand; every reference cast is `JULC0072`, citing #288. Pinned: `((S)(Object) new C(a)) instanceof A` (the JVM throws). |
| F62, F37: `instanceof` by family and structure | The operand's declared type (parameter, local, pattern variable, record component, by declaration) and the target are resolved to FQCNs; the operand must be the target itself or the target's sealed interface. A sibling variant, another family, a same-named declaration elsewhere, or an operand without a declared type is `JULC0072`. P rows for the sibling and same-name forms. |
| F63: `char` narrowing in constant conditionals | `char`, `byte` and `short` values (literals, casts, declared types) are outside the guard subset (`JULC0072`). Pinned (`("x" + (true ? 'a' : 98)) == "xa"`, which Java accepts as `true`) and a P row for the `"x97"` form. |
| F64: unqualified pattern types by simple name | Resolved to the declaration and checked as a permitted variant (`JULC0076`). TypeResolver now records every family a variant was registered under, so a record with two sealed parents (#279) keeps its base behaviour until M3 (D6) rejects it. Byte stability re-checked: unchanged. |
| F65: `Boolean`/`String` by spelling | A `java.lang` (or `java.math.BigInteger`) name counts only when written with its package or not shadowed by a type of the unit or a single-type import; otherwise it is another reference type. P row for a user `Boolean`. |
| F66: constant fields assumed legal | A field is a constant variable only if `static final` and its initializer is assignable without narrowing (JLS 5.2); a narrowing declaration is `JULC0072` (unclassifiable), and an instance field read in a guard is `JULC0072`. P rows for both. |
| F67: non-constant `String ==` | Rejected (`JULC0072`, citing #289) unless both operands are compile-time constants; pinned (`(a + "b") == "5b"`: Java 2, JuLC returned 1). `==` on other references (records) is rejected too (T4). |
| F38: unparenthesised conditional guard | JavaParser parses `when c ? d : e ->` as a lambda: an uncoded parse error, javac accepts it. Known over-rejection; an M5 troubleshooting note ("parenthesise a conditional guard"). |
| F39: surviving mutant (`UNKNOWN && BOOLEAN`) | Diagnostics test and P row with `Builtins.iData(...)` (typed `Data` by the symbol table) inside `&&` and `||`; the mutant is now detected. |

**Known over-rejections of the guard subset (fail closed; javac accepts each):** floating-point constants;
`char`/`byte`/`short` values (F63); reference casts, including `(Boolean)` of a non-boolean operand (F61);
`==` on non-constant strings (F67) and on records or other references (T4); relational operators on integer calls
outside the known `int`/`long`-valued list (taken as `BigInteger`); `instanceof` whose operand has no declared type
(a cast or a call result other than a record component); guards whose leaf JuLC cannot type (a record component read
as a field); an unparenthesised conditional guard (F38, a JavaParser parse error); a guard binding introduced when
true (`JULC0074`, D1).

**Evidence.**
- **Tests** (`./gradlew :julc-compiler:test :julc-difftest:test --rerun -Pjulc.javac21.required=true`): julc-compiler 2,063,
  0 failures (`SwitchDiagnosticsTest` 46, `SwitchGuardTest` 20, `SwitchMalformedDataTest` 11); julc-difftest 240, 0
  failures (`RegressionCasesTest` 124 pins, three new; `Javac21ParityTest` 36 rows, ten new; `SwitchParityCorpusTest`).
  `./gradlew build -PskipSigning=true`: success; 12,210 tests in all modules, 0 failures, 530 skipped (pre-existing).
- **Parity corpus:** 1,077 programs (eleven new guard kinds): 128 accepted by javac 21.0.3 and JuLC (49 run both ways at
  all four levels, all agreeing), 928 rejected by both, 21 rejected by JuLC on purpose (the over-rejections above and
  the earlier deliberate ones); javac 21.0.3 and javac 25 `--release 21` agree on all.
- **Byte stability** against `fd605e77`: 3,764 rows, 784 programs byte-identical, 0 changed, 0 newly rejected.
- **Mutations:** all 28 detected: the 17 of round 1 (the F51 mutant now targets the checker, where the family check
  moved) and 11 new: F60 comparisons without operand checks; F61 reference casts allowed; F62 instanceof by family
  membership; F63 `char`/`byte`/`short` allowed; F64 unqualified names by spelling; F65 `java.lang` names by spelling;
  F66 a narrowing initializer folded, and an instance field read; F67 `String ==` on non-constants; F39 an unknown
  leaf inside `&&`/`||` counted as boolean.

### M1 review round 3 (2026-10-30; Codex F70–F75, #291, and the I9 scope revision)

Codex requested changes at `a4a7d834` (F70–F75). The author proposed narrowing I9 and dropping `JavaConstants`; the
review comment agreed with conditions, and Codex agreed with the expression-typing boundary but not with dropping
constant folding (JLS 14.11.1's constant-false rule and constant-true dominance and exhaustiveness are switch-specific,
and `(2147483647 + 1) < 0` and `((int) 2147483648L) < 0` would have become wrong values). The revised plan in the
author's last note is applied here:

- **I9 revised.** For an accepted switch, the switch-specific rules match javac 21 exactly, and every accepted guard's
  value is Java's. Expression-level parity for non-constant call arguments and receivers (F73) is #287 and ADR-065.
  Goals, the threat model, I7, I9, D4, Verification 3 and the outcome matrix are updated together.
- **`JavaConstants` is kept, restricted to a verified subset:** `boolean`, `int`, `long` and `String` with JLS
  fixed-width semantics. A constant-looking guard outside it is rejected (`JULC0072`), never treated as non-constant.

While implementing the revised I9, the implementer found two more places where an accepted guard's value was not
Java's (R3a, R3b below; both are ADR-062 T6 inside a guard). They are fixed with D4's existing guard-subset
mechanism, as F72 is, and need no other design change. **Reviewers should check that they fit the plan.**

| Finding | Change |
|---|---|
| Scope (author note; both reviewers) | I9 as above. F73 is not changed: it is a documented gap, owned by #287 and ADR-065, as in every `if`. The outcome-matrix rows for F54, F63 and F66 keep `REJECT`, since folding is kept. |
| F70: constant width lost | `JavaConstants.initializer` applies the assignment conversion to the declared type after the assignability check (JLS 5.2), widening included. `static final long N = 1` with `(N << 32) == 1` is the constant `false` (`JULC0072`); `static final long N = 2147483647` with `N + 1 > 0` is the constant `true` and covers `A`. |
| F71: declarations unchecked in non-constant guards | `JavaConstants.invalidDeclaration` validates every constant declaration a guard reads, constant guard or not: by simple name, qualified (`Probe.N`), and through the initializers of the constants it reads (`M = N`). `static final int N = 4294967296L` with `N > x.v().signum()` is `JULC0072`. P rows for all three forms. |
| F72: narrowing erased | A cast to `int` of an operand that is not a constant is `JULC0072` (T6). The same cast in an `if` is pinned `DIVERGE T6`. A constant cast keeps Java's value (R3a). |
| F73: argument and receiver legality | Documented gap (#287, ADR-065). Not generated in the corpus. |
| F74: binding conflicts | `checkGuardPatternVariables` checks, before the unused-binding exemption, that a guard's pattern variable redeclares neither a variable in scope (a local, a parameter, the arm's own binding) nor another pattern variable of the same guard (`JULC0083`). P rows for `!(s instanceof B v)` beside a local `v` and `(s instanceof A a2) \|\| (s instanceof A a2)`; corpus kinds. |
| F75: `String.equals` of another type | In a guard, `String.equals` with an argument not established as a `String` is `JULC0072`, citing #292. In an `if`, `"a".equals(true)` is pinned `DIVERGE T34` (JVM 2, UPLC fails); ADR-062 has the new T34 row. |
| #291: a `default` local outlives the switch | On the ordinary (unguarded) lowering, the `default` body is now compiled in its own symbol-table scope, as type-pattern arms already were and as the guard-aware path did since F53. Oracle tests: the issue's shape, inside a helper, a nested switch's `default`, a colon-form `default` group, and the guard-aware path. Pin `agree-default-local-scope-ordinary-path` (`DIVERGE` at `a4a7d834`, JVM 1, JuLC 2; now `AGREE`). The second symptom (a `default` block with two counted loops, in a switch that is an instance-method argument, rejected with "Unbound variable: i1") is fixed by the same change: oracle test and pin `agree-default-two-loops-instance-argument`. A new approved byte-changing category in Verification 5. |
| Conditions of the review comment and Codex | `N + 1 < 0` (`static final int N = 2147483647`), `(2147483647 + 1) < 0` and `((int) 2147483648L) < 0` are pinned `AGREE`; `when ON`/`when DEBUG` flags still fold. |
| R3a (implementer): constant subexpressions of a non-constant guard were evaluated without Java's width | Inside a guard, every `int` or `long` constant expression of the verified subset is lowered as its Java value (`PirGenerator.guardFixedWidthConstant`): `(2147483647 + 1) < x.v().signum()`, a `static final int N = 2147483647 + 1` read by a guard, `((int) 2147483648L) < x.v().signum()` and the hex literal `0xFFFFFFFF` (Java -1) now agree; at `a4a7d834` each skipped the arm (JVM 1, JuLC 2). Oracle test and pin `agree-guard-constant-overflow-beside-binding`. Only guards are affected, so I8 is unchanged. |
| R3b (implementer): non-constant `int`/`long` arithmetic | `+`, `-`, `*`, `/`, `%`, shifts and bitwise operators on numbers, and unary `-` and `~`, are `JULC0072` in a guard unless the operation is a constant expression; JuLC evaluates them without Java's overflow (T6). `x.v().signum() + 2147483647 < 0` gave 2 where Java gives 1. Pinned `REJECT JULC0072`, and the same expression in an `if` as `DIVERGE T6`. |
| Plan item 7: existing protections | `existingProtectionsHold` re-asserts F67 (#289), the boolean type, F50, F51/F62, F56, F61, F64 and F65 in one place; each keeps its own test as well. |

**Known over-rejections added in round 3 (fail closed; javac accepts each):** non-constant `int`/`long` arithmetic
in a guard, even where it cannot overflow (`x.v().signum() + 1 > 0`, `-x.v().signum() < 0`); a cast to `int` of any
non-constant operand, an `int` one included; `String.equals` whose argument JuLC cannot type as a `String` (a
`Data`-typed or unknown leaf); one pattern-variable name in two separate call arguments of a guard
(`f(s instanceof A q, s instanceof A q)`, the only duplicate-binding shape javac 21.0.3 accepts among the seven
checked). The round-2 list stands.

**Evidence.**
- **Tests** (`./gradlew :julc-compiler:test --rerun :julc-difftest:test --rerun -Pjulc.javac21.required=true`):
  julc-compiler 2,078, 0 failures (`SwitchDiagnosticsTest` 53, `SwitchGuardTest` 23, `SwitchMalformedDataTest` 11,
  new `JavaConstantsTest` 5); julc-difftest 258, 0 failures (`RegressionCasesTest` 136 pins, twelve new;
  `Javac21ParityTest` 42 rows, six new; `SwitchParityCorpusTest`). `./gradlew build -PskipSigning=true`: success;
  12,243 tests in all modules, 0 failures, 530 skipped (pre-existing).
- **New pins** (twelve): `AGREE` for the three constant guards of the plan (`agree-guard-int-constant-overflow`,
  `agree-guard-literal-overflow`, `agree-guard-constant-narrowing-cast`), R3a
  (`agree-guard-constant-overflow-beside-binding`) and #291 (`agree-default-local-scope-ordinary-path`,
  `agree-default-two-loops-instance-argument`); `REJECT JULC0072` for F72, F75 and R3b; `DIVERGE T6` for F72 and R3b in
  an `if`, `DIVERGE T34` for F75 in an `if`. Each pin that records a base (`recorded.base`) was replayed with its fix
  reverted in the working tree, and reproduces the recorded JVM and JuLC observations.
- **Parity corpus:** 1,104 programs (nine new guard kinds): 136 accepted by javac 21.0.3 and JuLC (57 run both ways at
  all four levels on four inputs, all agreeing), 943 rejected by both (one by JuLC's parser, `case A x, B y`), 25
  rejected by JuLC on purpose (the over-rejections above and earlier); javac 21.0.3 and javac 25 `--release 21` agree
  on all.
- **Byte stability** against `fd605e77` (the same harness and corpora as M1: external `../julc-examples-cmp` at
  22b4e00, in-repo examples, compiler test text blocks, stdlib tests, Blaster fixtures, `../julc-cip113-example`,
  `../julc-helloworld`): 3,764 (program, level) rows, **784 programs byte-identical, 0 changed, 0 newly rejected, 0
  newly accepted**. No program in these corpora has a `default` local that shadows a name read after the switch, so
  the #291 category has zero members here; its effect is shown by the pins and oracle tests above.
- **Mutations:** all 38 detected. The 27 earlier mutants of the harness again (round 2 reported 28, but its harness holds 27;
  F66's narrowing-fold mutant, masked by the new declaration check, is now caught by `JavaConstantsTest`), and eleven
  new: F70 keep the initializer's type; F71
  validate declarations only in constant guards, ignore qualified references, and do not follow initializers; F72
  allow non-constant narrowing casts; F74 skip the binding-conflict checks; F75 allow `String.equals` of another type;
  #291 compile `default` without its own scope on the ordinary path (two tests, including the second symptom); R3a
  lower guard constants without Java's width; R3b allow non-constant binary arithmetic, and unary minus.
- **Open point for review.** `JavaConstants` treats a `static final` primitive field with a constant initializer as
  its Java constant even when it is annotated `@Param` (pre-existing for whole constant guards; R3a extends it to
  subexpressions). No corpus program declares a `@Param` field `final`; whether such a field should be rejected in a
  guard is left to the reviewers. *(Resolved in round 4, F41 = F80.)*

### M1 review round 4 (2026-10-30; review F41, F42, Codex F80–F82)

Both reviews of `bbf815a1` requested changes; they overlap on F41 = F80. Both found R3a and R3b consistent with the
revised I9. Every finding is fixed within D4's guard subset. The declaration-level `@Param` question is #293 and
the general `intValue()`/`longValue()` lowering is #294; neither is fixed here.

| Finding | Change |
|---|---|
| F41 = F80 (P1): a `@Param static final` field with a constant initializer was folded in a guard (whole guards since M1, R3a subexpressions since round 3), so the applied parameter was ignored while an `if` honoured it; a minting validator with `LIMIT` applied as 0 succeeded | `JavaConstants.constantField` treats a field annotated `@Param` (simple or qualified annotation name) as not constant, everywhere `JavaConstants` is used: whole-guard classification, constant `true`/`false` for dominance and exhaustiveness, R3a's lowering, and other constants' initializers (`static final long M = LIMIT`). A guard reads the applied binding, as an `if` does (at `a4a7d834` only a guard that was not constant as a whole did). `when ENABLED` on a `@Param` field is a guarded label. Tests (`SwitchGuardParamTest`): `@Param static final boolean ENABLED = true` and `@Param static final long LIMIT = 5`, applied with another value through `compileMethod`, directly, negated and through `M = LIMIT`, each against the same read in an ordinary `if`; the guarded label's exhaustiveness (`JULC0005`) and non-dominance; and a `@MintingValidator` with `program.applyParams` on a minting context (`LIMIT` 5 accepts, 0 rejects, guard and `if` alike). No `.diffcase` pin is possible: javac inlines the constant (#293) and the ADR-063 harness does not apply parameters, so the oracle is the `if` control (outcome-matrix row, checker U). Declaration validation (F71, F82) still covers `@Param` fields: it only rejects declarations javac rejects. |
| F81 (P1): `intValue()` and `longValue()` lower to the identity (`TypeMethodRegistry`), where Java narrows | In a guard, a zero-argument `intValue()` or `longValue()` call is `JULC0072`, citing #294. Pinned `REJECT` (`x.v().intValue() < 0` at 2^31: JVM 1, JuLC 2 at `bbf815a1`). The `if` form is pinned at the sign boundaries: `DIVERGE T6` at 2^31, at -2^31-1 and (for `longValue()`) at 2^63, `AGREE` at `Integer.MAX_VALUE`, `Integer.MIN_VALUE` and `Long.MAX_VALUE`. |
| F82 (P2): invalid literals in referenced declarations fell back to the ordinary lowering | `JavaConstants` classifies `-2147483648` and `-9223372036854775808L` exactly, only when the decimal literal is the direct operand of unary minus (JLS 3.10.1; `-(2147483648)` is not, as javac 21.0.3 confirms); any other out-of-range literal is unclassifiable. `invalidDeclaration` rejects every reachable constant declaration whose initializer the checker cannot evaluate exactly (naming the deepest one, the cause), and `checkGuard` rejects any unclassifiable part of a guard, not only an unclassifiable whole. Covered: a field, a `long` field, a final local, `M = N`, and literals in the guard; P rows for the four declaration forms; an `AGREE` pin and corpus kinds for the minimum-value forms. |
| F42 (P3): R3b's usability | R3b is kept. Its `JULC0072` message now suggests `BigInteger` arithmetic, or computing the value in a local before the switch (where T6 applies as in any `if`) and comparing that. M5's troubleshooting entry carries the same remedy (milestone table). |
| Codex's I8 note: an unguarded switch nested inside a guard changes bytes under R3a | I8 now states that a guard's whole expression, an unguarded inner switch included, is guarded code (the program-level guarded-code exclusion). |

**Known over-rejections added in round 4 (fail closed):** a `@Param` boolean guard counts as guarded, so a switch
that javac finds exhaustive through it (the field being a constant `true` in Java) is `JULC0005`; `intValue()` and
`longValue()` on values in range, and a record accessor named `intValue` or `longValue`; R3b's arithmetic (F42: 8 of
the review's 10 arithmetic guards).

**Related finding (not fixed here).** An integer literal with underscores (`1_000`) crashes the ordinary lowering
with an uncoded `NumberFormatException` (`PirGenerator.generateExpression` parses the digits with `new
BigInteger`); `0xFFFFFFFF` lowers as 4294967295 where Java's `int` is -1 (T6). Inside a guard both are exact, since R3a
lowers them from `JavaConstants`; outside guards they are the ordinary lowering's.

**Evidence.**
- **Tests** (`./gradlew :julc-compiler:test --rerun :julc-difftest:test --rerun -Pjulc.javac21.required=true`):
  julc-compiler 2,087, 0 failures (`SwitchDiagnosticsTest` 55, `SwitchGuardTest` 23, new `SwitchGuardParamTest` 5,
  `SwitchMalformedDataTest` 11, `JavaConstantsTest` 7); julc-difftest 270, 0 failures (`RegressionCasesTest` 144 pins,
  eight new; `Javac21ParityTest` 46 rows, four new; `SwitchParityCorpusTest`). `./gradlew build -PskipSigning=true`:
  success; 12,264 tests in all modules, 0 failures, 530 skipped (pre-existing).
- **Parity corpus:** 1,116 programs (four new guard kinds): 140 accepted by javac 21.0.3 and JuLC (61 run both ways at
  all four levels, all agreeing), 949 rejected by both, 27 rejected by JuLC on purpose; javac 21.0.3 and javac 25
  `--release 21` agree on all.
- **Base replays:** the `reject-guard-intvalue` pin, with F81's check reverted, reproduces its recorded JVM 1 and
  JuLC 2.
- **Byte stability** against `fd605e77` (same harness and corpora): 784 programs byte-identical, 0 changed, 0 newly
  rejected or accepted (the only head-only rows are the new test file's validator text block).
- **Mutations:** all 43 detected: the 38 of round 3 (the F71, F72 and R3b mutants re-anchored to the restructured
  code) and five new: F41 fold a `@Param` constant (six `SwitchGuardParamTest`/`JavaConstantsTest` tests); F81 allow
  `intValue()`/`longValue()`; F82 leave unclassifiable declarations to the ordinary lowering, check only the whole
  guard for unclassifiable constants, and drop the minimum-value forms.

## Revision history

- **r1** (2026-10-30): initial proposal, including #279 (D6), added at the coordinator's request from the ADR-066
  review.
- **r6** (2026-10-30, responds to review round 4 of `af12265d`: the review comment's approval with F26, and Codex's
  F45):
  - **F45** → the r5 premise ("reaching the `Value` handlers needs javac-invalid source") is withdrawn: an implicit
    accessor reaches them from valid Java (Evidence F45, reproduced: JVM `true`, JuLC "UnMapData" at all four
    levels; through a local it agrees). Fixed in M3b: named ledger handlers key on ledger identity (FQCN plus
    provenance, as D3's layout query); outcome-matrix row (`DIVERGE` T31 → `AGREE`, D); mutation; Related findings
    updated. #287 keeps the assignment, argument and return checks.
  - **F26** → bundled-FQCN redeclaration rows checked by P or `julc-compiler` unit tests (packaged sources cannot run
    in `JvmExecutor`), packageless hash-name records stay D; F3's baseline is "guard ignored"; K3's `JULC0076` reason
    is "unsupported: total type pattern" (D4 and the matrix).
- **r5** (2026-10-30, responds to review round 3 of `6b019308`: the review comment F23–F25 and Codex's F40 with partial
  notes on F14 and F32):
  - **F24 = F40** → the outcome matrix has baseline, final and checker columns (D `.diffcase` for sources the
    harness's javac accepts, P the javac 21.0.3 parity suite for javac-invalid sources, U JuLC-only tests for malformed
    Data); real baselines (`AGREE` for F1a–F1c, E4, G2, G8b, M2, M5, N7, F19c, F20); M0 scoped to the D rows, with P and
    U rows recorded in their own suites; extending the ADR-063 format considered and not chosen.
  - **F25** → rows added: F9, F20 and the variant-typed nested pattern, F30/`Value`, the malformed-tag tests (F3, F5a,
    F11, nested), G2/G8b, M5, K2 (both open question 4 outcomes), K3 (now `JULC0076`), the retained rejections (K1, G9,
    R5, M3, M4), and D5's retained divergence for an unknown operand (Evidence D5u, reproduced: JVM 0, JuLC 1).
  - **F14 (partial)** → M2b's scope column and D7's `JULC0007` row aligned (`JULC0007` selectors, `JULC0076`
    components).
  - **F23 / #287** → D9 records the consequence of #284 and assigns it to #287; D3's layout query keys on FQCN and
    provenance; the `Value` handlers' simple-name dispatch is deferred to #287 with the reason; M3b pin via P and U;
    Related findings and Evidence F23 (reproduced JuLC-only: 7).
- **r4** (2026-10-30, responds to review round 2 of `1118727e`: the review comment F19–F22 and Codex's F30–F33 with
  partial notes on F10, F13, F14, F16):
  - **F19 / #284** → added to scope; D9 decides Java's shadowing rule (the user's type wins, unnamed package
    included) for non-hash ledger names, keeps `JULC0081` for FQCNs and hash names; I15 extended; M0 pin, M3b tests
    for every ledger simple name against javac 21.0.3; mutation; Related findings corrected; T33.
  - **F20** → D2 step 3 and I5 use the construction tag from the registry (variant position or 0); M2b test with a
    variant at position ≥ 1; mutation.
  - **F21** → D5: a known accessor result needs a known receiver; M4 test; mutation.
  - **F22** → recorded as a deliberate exception in open question 9 and in Related findings, to be tracked as an issue.
  - **F30** → D3 eligibility by record layout (construction tag and component list), `@NewType` (including `boolean`)
    excluded; M2a rejection tests; mutation.
  - **F31** → threat model: construction preserves well-formedness only of well-formed components; M2b pin.
  - **F32** → one authoritative outcome matrix (Verification 2); byte comparison only for programs accepted at both
    revisions and outside the approved categories (Verification 5); M0 references the matrix.
  - **F33** → guards accept `boolean` and `Boolean` (D1, D4, D7); parity corpus and outcome matrix; mutation.
  - **F14 (partial)** → `JULC0007` for selectors, `JULC0076` for component patterns (D3, D4, M2b).
  - **F16 (partial)** → the remaining exactness sentence in D5 removed.
  - **Open question 1 decided:** (b), reject with `JULC0078`; option (a) kept only as a rejected alternative in D5;
    the maintainer may still veto. M4's exit criteria rewritten for rejection.
- **r3** (2026-10-30, responds to review round 1 of `1629d857`: the review comment F1–F8 and Codex's F9–F18):
  - **F1** → D5 classifies only operands whose type D5's source rules know (declarations, `new`, declared returns;
    never conditionals, switch expressions, `var` from them, generic calls or inferred types); I12 rewritten; F1a–F1c
    as must-stay-structural tests in M4; mutation added; open question 2's rationale reworded.
  - **F2** → oracle pinned to javac 21.0.3 by explicit path; I9 means the JLS 21 text; the javac 21/25 `Pair`
    divergence recorded, JuLC follows JLS 21 (rejects); parity corpus calls the pinned javac; open question 10.
  - **F3** → `instanceof` inside a guard is a total dispatch (D1); I5 widened; malformed test in M1; mutation.
  - **F4** → D3's non-Constr rule is defined by encoding through a registry query, `Value` named, registry checked
    against `toPlutusData`; `Value.inner()` failure in Related findings.
  - **F5** → (a) malformed top-level tag test in M1 and mutation; (b) constant variables and compound constants in D1,
    D4 and the parity corpus, with JLS §4.12.4/§15.29 quoted; (c) mutations added.
  - **F6** → open question 1's rationale made consistent with D6/D9, warning visibility stated (invisible in the
    testkit), lean changed to (b), corpus scan moved to M4's entry gate; D5 specifies both options.
  - **F7** → effectively-final check keyed on the resolved declaration; for-each variables accepted.
  - **F8, F15** → D9's ledger exemption is by `LedgerSourceLoader` provenance; user redeclaration of a bundled ledger
    FQCN or a ledger hash simple name rejected with new `JULC0081` at every entry point; M3b tests; the `Interval`
    report recorded as not reproduced in two shapes and pinned in M3b.
  - **F9** → D2 step 5: continuations are placed outside an arm's binders and inlined only when no bound name is free
    in them; I10 extended; F9 tests and mutation.
  - **F10** → threat model narrowed: exact guarantees for validated and in-script values; for unvalidated Data, tags
    fail closed and field contents follow ADR-051's lenient contract, with rationale; open question 9; pinned tests.
  - **F11** → on the new path, single-constructor sum types and nested standalone records check the tag; today's
    path unchanged (I8), recorded in Related findings.
  - **F12** → #283 added to scope; D4's colon-form wording corrected; colon form accepted only when every group has
    one label and yields; everything else `JULC0075` (renamed `SWITCH_GROUP_UNSUPPORTED`), on today's path too; new
    I16; T32.
  - **F13** → D4 is a matrix: guard type (`JULC0072`, renamed `SWITCH_GUARD_INVALID`), duplicate `default` (new
    `JULC0082`), pattern-variable conflicts (new `JULC0083`), applicability, declaration-identity effective finality.
  - **F14** → switches over, and record patterns on, parameterized sealed interfaces rejected with `JULC0007`.
  - **F16** → D5's exactness claim dropped; nested nominal erasure kept in T29 with pins.
  - **F17** → D9 runs as a declaration preflight before `SubsetValidator`; diagnostic precedence stated.
  - **F18** → the deserialization claim corrected (the canonical constructor *is* invoked).
  - Reservation extended to `JULC0083`; #283 in the header and context. No ID renumbered.
- **r2** (2026-10-30, before any review; responds to the coordinator's request after filing #282 from this ADR's
  related finding R6/H5): new D9 (reject every user record body member except nested types, `JULC0080`, ledger types
  excluded), I15, M3b, mutation checks for D9, Evidence N1–N10 and the record-body scan, open question 8. D3's
  explicit-accessor rejection marked subsumed by D9; D5 notes its reliance on D9; D7 reservation extended to
  `JULC0080`; D8's T31 now names #282; R6/H5 moved from Related findings into scope. No ID renumbered.
- **r6, editorial** (after approval at `f965d061`): Codex's F46 (P2) → the F45 handler and accessor change added to
  Compatibility and to Verification 5's byte-stability exclusions, with the report classifying each changed
  program; the review comment's F27 (P3) → F45 and F45b marked "nested in `Probe`", new row and evidence F45c for the
  top-level shape (#284), catalogue ID T33 instead of T31 for the F45 rows. Status set to Accepted.
- **r6, editorial 2:** Codex's F47–F49 (P3): Verification 5's byte-identical sentence names (i) and (ii); T33's
  description includes the F45 handler-identity cause; the status line credits Codex's approval at `189bb4fa`.
