# ADR-066: Generator G1 — records, sealed interfaces, switch and fields

**Status:** Accepted (2026-10-30), revision r4; approved at `4da7964b` by the review comment and by Codex
**Date:** 2026-10-30
**Label:** `semantic-consistency`
**Programme:** step S1 of [ADR-062](062-jvm-uplc-semantic-consistency-programme.md), as a generator stage of
[ADR-063](063-differential-jvm-uplc-test-harness.md) (D5's G1, and M2b's record mapping and field shadowing)
**Relates to:** ADR-067 (#281: switch guards, record patterns, cross-type record `equals`, records with two
sealed parents and record members), which G1 depends on for those constructs
**Stacked on:** #253 (ADR-063 D13); restacked on the ADR-067 PR before M3 (open question 1)

## Context

ADR-063's generator G0 produces loops over `BigInteger` values. It found ADR-062 T21 and T23, and G0's PR and
nightly campaigns, its exhaustive corpus and about 300,000 fuzzed evaluations have agreed since. Several silent
miscompiles in the same period were found by review or by hand-written probes, not by G0, because G0 never
generates the constructs they need:

| Defect | Construct | Found by |
|---|---|---|
| #209 | A local in an `if` branch of a loop body, shadowing a static field of another record type | Release review |
| #275 | `switch` case guards (`when`) are ignored | Probe while writing this ADR |
| #276 | Record patterns in `switch` cases are dropped, and `default` runs | Probe while writing this ADR |
| #277 | `equals` between two different record types with equal fields is true on-chain | Probe while writing this ADR |
| #279 | A record implementing two sealed interfaces gets one tag; a `switch` over the other takes the wrong arm | Review of this ADR (r1) |
| #280 | A zero-parameter helper runs once at script start, even if never called | Probe while revising this ADR |
| #166 (fixed) | A `boolean` record component bound by a switch case pattern was not decoded | Review of PR #165 |
| #162 (fixed) | Reassigning a switch case-pattern variable in a loop left stale field reads | Review of PR #157 |
| #47, #50 (fixed) | Switch case bindings collided with method parameters | Users |

(#201, a `break` naming a label, was found the same way. Its fix, open PR #272, rejects the construct, so it
belongs to a negative grammar, not to G1. #282, record members such as explicit accessors and `equals` that JuLC
silently ignores, was found while writing ADR-067; ADR-067 D9 rejects them, so G1 never generates record
members, and its must-reject list (D6) takes them once that lands.)

Records, sealed interfaces, `switch` and fields are how validators model datums, redeemers and their checks, so
this is where a silent miscompile is most likely to change what a validator accepts. ADR-063 already plans this
work: D5 names G1 ("`switch` expressions over sealed interfaces and records; Optional; `JulcMap`"), and M2b
names the user-record value mapping (D4), "a local shadowing a field", method-level `if`/`else` and helper calls.
This ADR makes that plan concrete, and adds what the harness needs to show that G1 would find such defects.

**Risk:** low for the product, since nothing shipped changes, but correctness-sensitive as evidence (ADR-063,
"Risk"). The risk this ADR manages is false confidence: a generator that never produces the shape of a defect,
inputs that never take the branch where it shows, results that cannot reveal evaluation order or failure, or a
value mapping that is wrong in the same way as the compiler.

**What G1 can and cannot establish.** G1, its corpus and its self-check give bounded evidence: JuLC agrees with
javac on the programs of a stated grammar, on the inputs drawn, and the harness demonstrably detects the listed
defects and mutation operators. It does not prove the frontend correct, and constructs outside the grammar are
not covered (residual risks, below).

## Goals and non-goals

**Goals**
- Generate well-typed programs over user records, sealed interfaces with record variants, `switch` expressions
  with type patterns, record patterns, guards and `default`, `instanceof` patterns, record component access,
  static fields, locals that shadow fields, record and sealed arguments and results, record-typed loop state,
  traces and failures.
- Map record and sealed values between the JVM and Data independently of JuLC's encoders (ADR-063 H1, D4).
- Show, with recorded evidence, that the campaigns find this ADR's defect classes: rediscovery of the defects
  above, an exhaustive corpus with an observability check, exercised coverage, and a mutation score.
- Keep G0, its saved plans and its corpus unchanged (ADR-063 H4).

**Non-goals**
- Fixing divergences. ADR-067 fixes #275, #276, #277 and #279; #209 and #280 have their own fixes.
- `Optional` and `JulcMap` (D5 lists them under G1). They have known differences that need their own decisions
  (open question 4) and are deferred to a stage G1b.
- Validator entry points, ledger types, script contexts and malformed boundary Data (ADR-063's G3).
- A full negative grammar for record constructs JuLC rejects (G1⁻). D6 adds only the must-reject checks that the
  defects above need.
- `==` on records (ADR-062 T4) and `byte[]` components (T5): known differences, excluded by construction.

## Current behavior

All evidence in this section is from `fd605e77` (#253's head) with probe tests run through
`DiffRunner.standard()`, four optimization levels, `julc-vm-java`, and was reproduced independently by the r1
review at `5c358dfa` (same code). The probe sources are not committed; their programs are quoted in the PR.

**Harness.**
- `ValueMapping` (`julc-difftest/.../ValueMapping.java:41-70`, `:73-124`) maps `BigInteger`, `int`, `long`,
  `boolean`, `byte[]`, `String`, `PlutusData`, `Optional`, `JulcList` and `JulcMap`. A record or sealed value, as
  an argument or a result, is a `HarnessException` ("No Data mapping for …", `:68`, `:123`).
- `fromTerm` (`:127-146`) is lenient about representation: a native `Constr` term and a `ConstrData` constant map
  to the same Data, and a builtin bool maps to `Constr 0/1`. A result in the wrong representation for its
  declared type would therefore still agree.
- `JvmExecutor` converts each argument with `ValueMapping.fromData` and the method's generic parameter type
  (`:58-64`), and the result with `toData` (`:84`). `UplcExecutor` passes the argument Data unchanged
  (`:110`) and maps the result term with `fromTerm` (`:116-126`).
- G0 is coupled to the registry and the property test: `KnownDivergences.Rule.requires` is a
  `Set<G0.Feature>` (`gen/KnownDivergences.java:71`); `G0PropertyTest.verify` takes a `G0.Program` and builds
  G0's arguments and provenance (`G0PropertyTest.java:162-215`); exact pins match on source, method and
  arguments (`KnownDivergences.java:204-213`).
- Quotas divide the conclusive cases containing a feature by **all** cases (`Coverage.java:71-78`).
- `nightlyDiffTest` runs only the jqwik engine (`julc-difftest/build.gradle:42-50`); `DiffConfig.allLevels()`
  uses `julc-vm-java` only (`DiffConfig.java:15-20`).
- There is no fault injection and no mutation measurement (ADR-063 D11 and M3 are not started).

**Compiler, for the constructs G1 needs.** Compiled by `compileMethod`:
- *Agree with javac:* record-typed and sealed-typed loop accumulators, including a `switch` that rebuilds the
  accumulator in a `while` with `break`; a ternary of record type and chained component access
  (`q.p().x()`); nested `switch` expressions and `yield` blocks; a `switch` value inside a loop body;
  `static final` record and `BigInteger` fields read in a loop, and a field initialized from another field;
  same-type record `equals`; method-level `if` with an early `return`, and `if`/`else` where both branches
  return; a loop inside a method-level `if`; non-recursive helper methods with parameters, including record
  parameters; `boolean` locals and components in conditions; a variant typed as itself and then as its sealed
  type; zero-component records and single-variant sealed interfaces; two independent sealed families in one
  program; `if (s instanceof A x)` as the whole condition of a method-level `if`; a local shadowing a
  `static final` field in an early-return branch, in a `switch` arm block, and directly in a loop body.
- *Traces and failures agree:* with a one-parameter helper
  `t(String k, BigInteger v) { return Builtins.unIData(Builtins.trace(k, Builtins.iData(v))); }` and
  `fail(BigInteger z) { return Builtins.unIData(Builtins.error()); }`: traced `switch` arms and constructor
  arguments (order `[first, second]`), traced loop bodies, a failing unselected arm or ternary branch (not
  evaluated), and a failure after a trace (both fail with `[before]`).
- *Rejected, so outside the positive grammar:* reassigning a variable outside a loop ("Unsupported expression:
  AssignExpr", ADR-062 T20), so method-level `if`/`else` cannot assign; a declaration without an initializer;
  `instanceof` without a binding in an expression, and `instanceof` with a binding inside `&&` ("Undefined
  variable", #204); `switch` statements ("Unsupported statement: SwitchStmt"; when every arm returns, the message
  is "Method 'm' may not return a value on all execution paths");
  a component accessor called directly on a constructor expression (`new P(a).v()`, JULC0055), record instance and
  static methods (JULC0055, "Undefined variable: P"), and explicit canonical constructors ("'this' is not
  supported on-chain"); a sealed interface without `permits` whose variants are top-level siblings ("Unknown type"); nested sealed
  hierarchies (a sealed interface that extends another: "class inheritance is not supported on-chain"). (M3:)
  a method called on a `switch` expression, `(switch (s) { … }).subtract(…)` (JULC0055 "Cannot resolve subtract",
  #290, with `new P(a).v()`); a `switch` over a value typed as a variant ("switch expression requires a sealed
  interface type").
- *Silently different, at every level:* #275 (guard ignored: JVM 2, UPLC 1), #276 (record pattern dropped: JVM
  −3, UPLC −99), #277 (`new R1(a).equals(new R2(a))`: JVM false, UPLC true; also through an `Object`-typed
  argument), #279 (JVM 21, UPLC 11), #280 (a never-called zero-parameter helper that traces and fails: JVM
  returns, UPLC fails with the trace), and #209 (a field shadowed by a local of another record type declared in
  an `if` branch inside a loop body: UPLC fails with `HeadList: empty list` or returns a wrong value), and #282
  (record members are ignored: an explicit accessor that negates, called on a local, a parameter or a `switch`
  binding, gives JVM −5 and UPLC 5; a compact constructor `P { v = v.negate(); }` gives the same; an explicit
  `equals` is replaced by structural equality; a secondary constructor fails on-chain). An explicit accessor is rejected only when called directly on a constructor expression;
  non-accessor instance methods, static methods and explicit canonical constructors are rejected on any receiver,
  and `equals` lowers structurally, as above.

**Representation the mapping must match.** A standalone record is `ConstrData 0 [components]`; a variant of a
sealed interface is `ConstrData i [components]`, where `i` is its position in the `permits` clause, also when
it is constructed and typed as the variant itself (`TypeResolver.java:153-187`, `:283-290`;
`PirGenerator.java:1771-1808`; `docs/src/content/docs/guides/advanced-guide.md:107-130`). Components are encoded
as `IData`, `BData`, the Bool constructors (`Constr 0 []` false, `Constr 1 []` true), `BData` of UTF-8 for
`String`, or nested Data (`PirHelpers.wrapEncode`, `PirHelpers.java:182-215`). A `compileMethod` parameter of
record or sealed type is passed through as Data without a shape check (`JulcCompiler.java:986-1005`); ADR-015's
strict boundary applies to validator entry points only. A record result comes back as a `DataConst`.

**Emitted UPLC for these constructs** (printed at every level, r1 review): there is no native `constr` term for
user records at any level; a record is a `constrData` application over a `mkCons` chain, or a folded Data
constant; component access is a `headList`/`tailList` chain over `sndPair (unConstrData x)`; sealed dispatch is
an `ifThenElse` chain over `equalsInteger tag k` at `NONE` and `BASELINE` and an integer `case` at the PV11
levels (`UplcGenerator.java:176-205`, `:249-274`, `:535-560`).

**javac.** `Class.getPermittedSubclasses()` returned the classes in `permits` order for
`sealed interface Z permits Z2, Z1, Z3` (JDK 25, probe). Its javadoc does not promise this: "The order of such
elements is unspecified" (`java.lang.Class.getPermittedSubclasses`, JDK 25 `src.zip`). D3 does not rely on it.

## Invariants

ADR-063's H1–H7 apply unchanged. In addition:

- **G1-I1. Well-typed and inside the subset.** Every program the positive profile emits compiles with javac (H6)
  and is accepted by JuLC at every level. A JuLC rejection of such a program is a finding, not a skip (H7). (D2)
- **G1-I2. Termination by construction.** Loops are G0's (bounded counters, finite `xs`), helpers are
  non-recursive and called only from later declarations, record types are acyclic, and every size is bounded.
  (D2)
- **G1-I3. Independent mapping.** The record and sealed mapping uses only Java reflection, the explicit tag table
  of D3 and the rule stated there. It never calls JuLC's encoders, decoders or type registry, nor any product
  converter. (D3)
- **G1-I4. Explicit constructor tags everywhere.** Mapping a sealed value always uses a persisted tag table,
  validated as in D3; a case that maps sealed values without one is a harness error. (D3)
- **G1-I5. Reproducibility.** A G1 program is a function of its plan, `G1.VERSION` and its generation profile
  (D6); a G1 case adds the input sequence its arguments are decoded from (D4, M2). Any change that decodes a plan
  or an input sequence differently bumps the version. The complete case (source, tags, arguments, features,
  profile) is deterministic, also for empty and truncated plans and during shrinking; `G1DecodeTest` checks this
  within one JVM process, and G1 iterates only lists and enum sets. G0's version and decoding do not change. (D1,
  D6)
- **G1-I6. Exclusions are explicit.** A construct left out because of an open defect is listed in D6 with its
  issue, gated by one named switch in the profile, and checked by a test that fails if the generator emits it
  while gated. (D6)
- **G1-I7. Every arm, guard and binding can be observed.** In the exhaustive corpus, each applicable
  observability edit (D7) changes some observation on the corpus inputs, and each edit that does not apply or is
  equivalent is accounted for. (D7)
- **G1-I8. Coverage evidence comes from the compared behaviour.** Exercised coverage counts only executions
  whose instrumented twin produced the same observation as the untouched program. (D8)
- **G1-I9. Mutation sensitivity is measured, not assumed.** Every operator of D10 has eligible sites on the
  corpus, an agreeing baseline, and recorded applied, killed, surviving, crashing and exhausted counts per
  level; every surviving mutant from the corpus is classified. (D10)
- **G1-I10. Results have the representation of their declared type.** A UPLC result that maps to the right
  Data in the wrong representation (a native `Constr` for a record, a `Constr` for a `boolean`) is a mismatch.
  (D4)

## Decisions

### D1. A separate generator, `gen/G1`, version `G1 r1`

G1 is a new class next to G0, sharing `Choices` and the plan model: a `List<Integer>` read through `Choices`,
choice 0 always the smallest valid construct, shrinking by jqwik on the plan, no discards. G0's decoding is not
touched, so every saved G0 plan, the G0 corpus and G0's fuzz inputs keep their meaning. (M2: G1's declarations
read the plan's even positions left to right and the entry method its odd positions, so that declarations that use
up their share leave the method its choices; removing one element while shrinking shifts the parity of the rest,
which makes shrinking less local but keeps every candidate valid.)

G1 contains a loop sub-grammar equivalent to G0's (D2), so it can put records and switches inside loops, but it
does not call G0: their plans would otherwise couple, and a change to one would change the other's programs.

*Alternatives:* extending G0 (G0 r3) would change every existing G0 plan's program and invalidate saved
inputs (H4); a single generic generator parameterized by a grammar is more machinery than two generators need
(AGENTS.md: no generic abstraction without two concrete needs, and G0 is not being rewritten).

### D2. G1 r1 grammar

Programs are typed ASTs printed as Java (ADR-063 D5). One `public class Probe` holds the declarations below and
the entry method `m`. The same AST is printed twice: the compared program, and the JVM-only twin of D8.

**Types.** Per program, chosen by the plan:
- zero to two standalone records, and zero to two sealed interfaces with one to three record variants each and an
  explicit `permits` clause, all nested in `Probe`. A record is a variant of at most one sealed interface unless
  the `SHARED_VARIANT` profile switch is on (D6);
- zero to three components each, of type `BigInteger`, `boolean`, or a record or sealed type declared earlier
  (acyclic, nesting depth at most 2), so sealed-typed components are included;
- component names from a small shared pool (`v`, `w`, `x`, `f`, `p`), so that names repeat across records,
  components, locals, fields and helper names. This is where #47 and #50 lived.

**Fields.** Zero to two `static final` fields of `BigInteger` or a declared record type, initialized with a
constant constructor expression that may read earlier fields (no method calls: JULC0057). Field names come from
the local-name pool, so a local can shadow one.

**Helpers.** A fixed trace helper `t(String k, BigInteger v)` and failure helper `fail(BigInteger z)` (bodies as
in "Current behavior"), plus zero to two generated non-recursive static helpers with one to three parameters,
whose bodies are an expression of the grammar, called from `m` or from later helpers. Zero-parameter helpers are
gated (#280, D6).

**Entry method.** `public static R m(params)`, where:
- the parameters are `a`, `b` (`BigInteger`) and `xs` (`JulcList<BigInteger>`) as in G0, plus zero to two
  parameters of declared record or sealed types;
- the return type `R` is `BigInteger`, `boolean`, or a declared record or sealed type.

**Bindings.** The generator tracks three sets per scope:
- *readable:* every variable in scope;
- *assignable:* method-level variables that loops may update (as G0);
- *guard-eligible:* parameters, pattern bindings, fields, and locals that are never assigned anywhere in their
  scope. The generator decides at a local's declaration whether it is final (never assigned) or assignable, so
  this is known when a later guard is generated. A guard that needs an accumulator reads an immutable snapshot
  (`BigInteger s0 = acc;`, declared final for the generator) taken in the same block. Guards are never
  compile-time constants (they always call `compareTo` or read a binding), so javac's constant-false guard rule
  does not arise (JLS 25 §14.11.1).

**Statements.**
- Declarations of any generated type, with an initializer.
- G0's loops: `while` with a bounded counter and for-each over `xs`, nested at most two deep; in loop bodies,
  assignments, `if`/`else` with assignments, `if (c) break;`, locals, and `if` branches that declare a local
  before their assignments (the #209 composition: a branch-local declaration followed, after the `if`, by a read
  of a field with the same name). Accumulators may be of any generated type.
- Method-level `if` whose branch declares locals and returns, `if`/`else` where both branches return, and
  `if (s instanceof A x) { … return …; }` with the pattern as the whole condition, outside loops. No method-level
  reassignment (rejected, ADR-062 T20).
- Expression statements are not generated; traces appear through `t(…)` inside expressions.

**Expressions,** generated for a requested type:
- `BigInteger`: G0's expressions (constants −5..5, `add`, `subtract`, `multiply` by 1–3, ternary), component
  access, chained access, helper calls, `t(label, e)` with a label unique per site, and `fail(e)` in positions
  where it is not always evaluated (an arm, a ternary branch, a guarded operand of `&&`/`||`) or, with low
  probability, anywhere;
- `boolean`: comparisons, `&&`, `||`, `!`, `boolean` components, `boolean` locals, and `equals` between two
  values of declared types (same type; same sealed family; and, gated, distinct types including types with
  identical components, so tags and payloads coincide);
- a record type: its constructor, a variable, a component of that type, a ternary, a helper call, or a `switch`;
- a sealed type: one of its variants' constructors, a variable, a component, a ternary or a `switch`.

**`switch` expressions,** over a value of a sealed type, producing any requested type:
- labels: type patterns (`case A q ->`), record patterns (`case A(var v, B(var w, var f)) ->`, nested at most
  one level, with `var` or explicit component types), and `default`;
- guards (`when` with a generated `boolean` over guard-eligible bindings);
- arms as expressions, or as blocks that declare locals, may contain a loop over an arm-owned accumulator
  (declared in the arm block, never an enclosing variable), and `yield`;
- several arms per variant (guarded arms first), only in orders that javac's dominance rules allow, and
  exhaustive coverage either by every variant or by `default`;
- nested `switch` expressions, and `switch` inside loop bodies as a value. An arm never assigns an enclosing
  variable (ADR-048 rejects it); the supported analogue, copying a binding into an arm-owned accumulator, is
  generated instead (#162's positive form, `LoopConditionalLocalTest.java:286-293`).

**Bounds:** at most 5 top-level statements, 3 statements per loop or arm body, expression depth 2, `switch`
nesting 2, 3 arms per variant, 2 sealed families, comparison nesting bounded as G0 r2 (`MAX_COMPARISON_NESTING`,
#146), plan length 200.

**Excluded by construction:** `==` and `!=` on non-numeric values (T4), `byte[]` and `String` components (T5,
T17), `int` and `long` arithmetic other than counters (T6), division and `mod` (T11), `Optional`, `JulcMap`
(G1b), `@Param` fields (they become outer parameters the harness would have to supply), record members other
than components (#282; ADR-067 D9 rejects them), and every construct listed as rejected in "Current behavior".

### D3. Record and sealed values in the value mapping (ADR-063 D4)

`ValueMapping` gains user records and sealed interfaces, in both directions, by this rule:
- a record value maps to `ConstrData(tag, [components in declaration order, each mapped recursively])`;
- `tag` is the record's tag from the case's tag table if the record implements a sealed interface, and 0
  otherwise, whatever the static type at the use site (the variant-typed case above);
- `boolean` components map to the Bool constructors, as at the top level;
- `fromData` for a sealed parameter type chooses the variant with the Constr tag, and fails with a
  `HarnessException` if none does or the arity differs.

**The tag table.** G1 knows each variant's position in the `permits` clause it printed. The `DiffCase` carries
that table as a field `tags`, keyed by (record binary name, sealed parent binary name), saved in `.diffcase`
format 3 as `tag: Probe$A Probe$S 0` lines sorted by record then parent, and it is part of the case's
identity: `verify`, replay, D8 records and exact-pin matching all carry and compare it. The table has one entry
per (record, sealed parent) pair. Lookups are parent-aware: mapping a value at a use site typed by sealed
interface `S` uses the entry for `S`; mapping a variant typed as itself uses its only entry, and is a boundary
error if it has two with different tags. A format round-trip test and a case-identity test cover a record with
unequal tags under two parents.

Two validations, at different points:
- *Source validation, before any run:* every record that implements a sealed interface has an entry for each
  parent; tags within a family are distinct and cover `0..n-1`; arities match the source.
- *Boundary validation, when a value is mapped:* a value whose record has two sealed parents with different tags
  has no single Data encoding (#279), so mapping one, as an argument, a result or a component of either, is a
  `HarnessException`. A program in which such a record only lives inside `m`, with a scalar result, runs
  normally: that is the shape that shows #279 on a compiler that has it, and that ADR-067's rejection turns into
  a must-reject case (D6).

Every hand-written case that maps sealed values must carry `tag:` lines too; there is no reflection fallback
(G1-I4).

*Alternatives:* `getPermittedSubclasses()` order matches on JDK 25 but is unspecified (javadoc), and a mapping
that shares reflection order with a compiler that uses declaration order could agree with it while both are
wrong; deriving tags by parsing the source on the JVM side would add a second Java parser to the trusted
components; asking JuLC's `TypeResolver` would violate H1. `ReflectivePlutusDataConverter`
(`julc-cardano-client-lib`, `:401-424`) uses reflection order, so it is not a cross-check.

The mapping stays trusted (ADR-063 "Trusted components") and gets its own tests against hand-written Data:
standalone records; variants typed as themselves and as the sealed type, including nonzero tags; nested records
and sealed components; zero-component records; `boolean` components; round trips; error cases (missing,
duplicate or gapped tags, wrong arity, unknown tag); and families whose declaration order, `permits` order and
switch-arm order are permuted independently, so a table that followed the wrong order would fail.

### D4. Arguments and results

- **Arguments.** Record and sealed arguments are generated as values of the generated types, mapped to Data by
  D3, and passed to both sides: the JVM side rebuilds them with `fromData`, the UPLC side receives the Data. Only
  well-formed values are generated; malformed boundary Data is G3's subject (ADR-062 T16).
- **Inputs** cover each variant of each sealed argument, and values on both sides of each generated guard's
  comparisons, by drawing integers from −6..6 as G0 does and variants uniformly. D7 checks the effect for the
  corpus; D8 measures it for random campaigns.
- **Results.** A record or sealed result maps by D3 on the JVM side. On the UPLC side, G1 checks the result
  term against the declared return type before mapping (G1-I10): `BigInteger` must be an integer constant,
  `boolean` a bool constant, a record or sealed type a Data constant holding a `Constr`. A violation is a
  `Crash("result: representation …")`, which fails the run as a defect.

### D5. Harness changes

- **Features as names.** `KnownDivergences.Rule.requires` and the property-test plumbing take feature names
  (`Set<String>`), as `Coverage` already does, so rules can match G0 and G1 features. G0's features keep their
  names, so existing rules and saved cases are unchanged.
- **A generator-neutral `verify`.** `G0PropertyTest.verify` becomes a shared helper over
  `(source, className, method, args, tags, features, generator version, profile, plan)`. G0 passes no tags and
  the default profile; its behaviour and D8 records are unchanged.
- **New tests.** `G1PropertyTest` (jqwik; PR: fixed seed, 150 tries; nightly: fresh seed, 3,000 tries,
  tag `nightly`), `G1ExhaustiveTest` (JUnit, D7), `G1FuzzTest` (Jazzer, as ADR-063 D13: bytes to arguments then
  plan; committed seeds; nightly job), `G1GatesTest` (JUnit, D6), `G1MustRejectTest` (JUnit, D6),
  `G1TwinTest` (JUnit, D8), `G1SelfCheckTest` (JUnit, D10; a fixed sample on PRs, the full run tagged
  `nightly`).
- **Nightly task.** `nightlyDiffTest` includes the JUnit Jupiter engine as well as jqwik, filtered by tag
  `nightly`, so JUnit self-checks run there (today it runs jqwik only).
- **Regression store.** `.diffcase` format 3 adds `tag:` lines and a `profile:` line. Format 2 files are read
  unchanged and have neither.

### D6. Generation profile and switches

A *generation profile* is an immutable value: `G1.VERSION` plus the value of every switch below. It is printed
in each D8 record and `.diffcase` (`profile: G1 r1 -GUARDS -RECORD_PATTERNS …`), replay uses the recorded
profile, and the default profile is a constant. A rediscovery run (D10.1) uses a named non-default profile, so
the same plan and version with a different profile is a different, recorded case (G1-I5).

A construct whose defect is open is off in the default profile, so that campaigns stay meaningful (an
unexplained wrong value fails the run, and ADR-063 D6 gives wrong values no rule). `G1GatesTest` decodes 20,000
random plans under the default profile and fails if any program contains a gated construct.

| Switch | Construct | Issue | On in the default profile when |
|---|---|---|---|
| `GUARDS` | `when` guards | #275 | ADR-067 is implemented below this PR |
| `RECORD_PATTERNS` | Record patterns | #276 | ADR-067 is implemented below this PR |
| `CROSS_TYPE_EQUALS` | `equals` between distinct record types | #277 | ADR-067 is implemented below this PR |
| `SHARED_VARIANT` | A record implementing two sealed interfaces, with different positions, kept inside `m` | #279 | Never in the default profile: ADR-067 D6 rejects every second sealed parent, so after it the construct is must-reject only |
| `ASSIGN_PATTERN_BINDING` | Reassigning a `switch` case-pattern binding inside a loop | #162 | Never: a historical profile only; the fix rejects it |
| `ZERO_ARG_HELPERS` | Zero-parameter helpers | #280 | #280 is fixed on the base |
| `SHADOW_IN_LOOP_BRANCH` | A local in an `if` branch inside a loop body shadowing a field of another type | #209 | #209 is fixed on the base |
| `LOOP_BREAK_SHAPES` | Loop bodies with `break` and conditional updates (M2: broadened from G0's T21 and T23 shapes) | #254, #255 | Both are fixed on the base |
| `TRACE_IN_COMPARISON` | A trace in an operand of `compareTo`, or in a receiver of `signum`, directly or through a helper whose body traces (added in M2) | #146 | #146 is fixed on the base |

`TRACE_IN_COMPARISON` (M2): #146's lowering evaluates a `compareTo` operand, and a `signum` receiver, more than
once, so a traced operand traces more often on UPLC than on the JVM. G1 emits no `signum`; the other operations it
emits evaluate their operands once (probed in M2). ADR-063's T13 rule covers only #146's size and budget effect; a
duplicated trace is a conclusive mismatch, so the construct is gated.

**Must-reject checks.** A defect fixed by rejecting a construct cannot be found by the positive grammar. For
each such construct, G1 has a small fixed list of programs that must be rejected with the expected code at every
level (`G1MustRejectTest`):
- #162's case-pattern reassignment in a loop, rejected since its fix (`LoopBodyGenerator.java:130-137`);
- once ADR-067 lands: #279's shared variant (ADR-067 D6) and each record member kind (#282, ADR-067 D9).

In addition, the historical profiles of `ASSIGN_PATTERN_BINDING` and `SHARED_VARIANT` generate these constructs
for rediscovery (D10.1); a run under such a profile on a fixed compiler must end in the expected rejection. The
positive analogue of each construct is generated by the default grammar.

Turning a switch on is a one-line change to the default profile plus a `G1.VERSION` bump, and D10.1 records
that the campaign found the defect while it was present.

**After a fix (M2 review, F68).** A gate alone only allows its construct; generating a defect's shape often enough
to find it is the job of its `REDISCOVER_*` profile, which adds a steer (M2 results). The default campaign at the
default rate rarely reaches that shape: `DEFAULT.with(SHADOW_IN_LOOP_BRANCH)` generated #209's construct in 3 of
3,000 plans, all agreeing. So:
- the fix PR runs its defect's `REDISCOVER_*` profile at the PR bounds (seed, tries) and it must agree, without a
  `form=` filter; the defect's pins flip to `AGREE` (D9); a defect fixed by a rejection instead runs its historical
  profile expecting the rejection (as `ASSIGN_PATTERN_BINDING`);
- the gate is then turned on in the default profile as above, and the steered profile becomes a permanent nightly
  guard (D11). G0 keeps generating the T21 and T23 shapes; its registry
entries are unchanged.

### D7. Exhaustive corpus `G1E r1`

A bounded enumeration over the shapes where the defects above live, versioned `G1E r1`, with pinned counts in
`G1ExhaustiveTest` (as `G0ExhaustiveTest.java:60-108` does). Its subgrammar is finite by definition:

- **Switch shapes.** Sealed type `S` with variants `A(BigInteger v)` and `B(BigInteger v, boolean f)`;
  selector: a sealed parameter. Arms per variant: one or two, each from {type pattern, record pattern} ×
  {no guard, guard `v.compareTo(c) > 0` on the binding, guard `a.compareTo(c) > 0` on a parameter, guard `f` on
  `B`'s boolean binding}, with `c` in {0, 2}; with or without `default`; every arm order javac accepts, minus
  combinations in which an arm is unreachable (a later arm for the same variant whose guard implies an earlier
  arm's guard, decided over the finite threshold set); arm bodies `t("armK", e)` where `e` reads the binding
  (for `B`, `f ? e1 : e2`), or `fail(e)` in at most one arm; placed at method level or as a value inside a
  for-each.
- **Field shapes.** A `static final P p` field read after a local `Q p` (another record type) is declared in: a
  method-level early-return branch, a `switch` arm block, a loop body, and (gated) a loop `if` branch.
- **Equality shapes.** Same type, same sealed family, variant against its sealed type, and (gated) distinct types
  with identical components.
- **Shared-variant and zero-parameter shapes,** gated.
- **Boundary shapes.** Each sealed argument variant, a nested record argument, and record and sealed results.

**Inputs:** each variant of the selector, with `f` in {false, true}, and `v` and `a` each taking one value in
every interval of the threshold partition: thresholds {0, 2} give {−1, 1, 3}, so each guard sequence admitted
by the corpus has an input reaching each of its arms (an arm guarded `a > 2` followed by one guarded `a > 0` is
reached by `a = 1`). The inputs are derived from the thresholds, so adding a threshold adds its partition.
**Reachability witnesses:** the twin (D8) must show, on these inputs, every arm entered and every guard both
true and false; a combination where it does not is a corpus error, not an equivalence.

**Observability edits** run on the JVM only. Each edit has a precondition, and is applied only where the edited
program is type- and scope-correct and javac accepts it:
- *drop a guard*, only where no later arm for the same variant would become dominated;
- *negate a guard*;
- *replace a binding's use by a constant of its type* (for `f`, its negation);
- *replace an arm body by the body of another arm for the same variant*, only where every binding it uses exists;
- *replace a field read by a constant of its type*, for each field read, including the read after a shadowing
  local's scope (the read #209 corrupts);
- *replace an `equals` argument by a value of the same type with one component changed* and, under the gate, *by
  a value of a different type with identical components*.

Every targeted site (arm, guard, binding, field read, `equals`) must have at least one applicable edit with a
witness.

Each applicable edit must change some observation (value, trace or failure) on the corpus inputs; the input
that does so is recorded as its witness. An edit whose precondition fails is counted "not applicable"; an edit
that javac accepts but that is provably equivalent (for example, swapping arms of disjoint variants, which is
therefore not an edit) is not in the list. Not-applicable counts are pinned per shape, so a change in the corpus
that silently removes edits fails the test (G1-I7). The corpus size and run time are measured in M4 and
recorded; the target is under 60 s on the PR run.

### D8. Coverage and exercised coverage

**Syntactic features** (quotas as ADR-063 D9: the conclusive cases containing the feature, divided by **all**
tries, at least 3% on the PR campaign): `RECORD`, `SEALED`, `TWO_FAMILIES`, `ZERO_COMPONENTS`,
`NESTED_RECORD`, `SEALED_COMPONENT`, `RECORD_ARG`, `SEALED_ARG`, `RECORD_RESULT`, `SEALED_RESULT`,
`COMPONENT_ACCESS`, `CHAINED_ACCESS`, `STATIC_FIELD`, `FIELD_FROM_FIELD`, `FIELD_SHADOWED`, `RECORD_EQUALS`,
`SWITCH`, `NESTED_SWITCH`, `YIELD_BLOCK`, `LOOP_IN_ARM`, `SWITCH_IN_LOOP`, `RECORD_ACCUMULATOR`,
`TYPE_PATTERN`, `DEFAULT_ARM`, `INSTANCEOF_PATTERN`, `METHOD_LEVEL_IF`, `HELPER_CALL`, `TRACE`, `FAILURE`, and,
once their switches are on, `RECORD_PATTERN`, `NESTED_RECORD_PATTERN`, `GUARD`, `CROSS_TYPE_EQUALS`.

**Exercised coverage** (ADR-063 M2b's open item, for G1), from a JVM-only twin printed from the same AST:
- **Instrumentation contract.** Each site gets a stable ID from its AST position. Entry is recorded *before*
  anything else runs, because a method's arguments are evaluated before the call (JLS §15.12.4.2):
  - an arm `case P -> e` is printed as `case P -> { Cov.enterArm(id); yield e; }`, and an arm that is already a
    block gets `Cov.enterArm(id);` as its first statement;
  - a guard `g` is printed as `(Cov.guardStart(id) && Cov.guard(id, g))`, where `guardStart` records the start
    and returns true, and `guard` records the value and returns it; a guard that started but recorded no value
    threw;
  - loops record their iteration count per loop invocation and whether they exited by `break` or normally.

  Each wrapper evaluates the original expression exactly once, in the same order and scope, and passes exceptions
  through unchanged, so evaluation count, order, scope and abrupt completion are those of the untouched program.
  `G1TwinTest` asserts the counters directly on hand-written programs with a throwing arm, a throwing guard,
  nested arms and loops, since observation parity alone cannot validate counters.
- **Counters** live in a static field of the twin class, so a fresh class loader per invocation (H3) resets
  them. A guard site distinguishes "not evaluated", "threw", true and false. Inconclusive runs contribute no
  coverage (H5).
- **Check.** For each input, the twin's observation (value, traces, failure) must equal the untouched program's
  JVM observation; otherwise the twin's counts for that input are discarded and the case is reported as a twin
  defect (G1-I8). The twin is never compiled by JuLC.
- **Exercised quotas,** counted per case (a case counts once per item) and divided by all tries, at least 3%
  each: a switch arm entered; a guard evaluated true; a guard evaluated false; a loop with zero, one and several
  iterations; a loop exited by `break`.

### D9. Known divergences and pins

ADR-063 D6 applies. #275, #276, #277 and #279 get saved `DIVERGE` cases (pins) on the base before ADR-067's fix
and flip to `AGREE` (or `REJECT`) with it, in the ADR-067 PR. A G1 mismatch is never explained by a rule unless
the UPLC side is inconclusive; the existing T13 rule (`DEEP_COMPARISON`) applies to G1 programs with the same
marker. Pins compare the tag table as part of the case (D3).

### D10. Showing that G1 finds what it should

ADR-063 D11 asks for rediscovery and mutation before a harness counts as evidence. For G1:

1. **Rediscovery.** On a compiler that still has the defect, with the named profile that enables its construct,
   the campaign must report a mismatch whose shrunk reproducer contains the defect's construct and whose cause is
   that defect; an earlier unrelated mismatch does not count. The cause check depends on the defect's state:
   - *fixed by a code change* (#275, #276, #277, #166, #47, #50): with the fix applied, the reproducer agrees;
   - *fixed by a rejection* (#162, #279, #282): with the fix applied, the reproducer is rejected with the
     expected code;
   - *not yet fixed* (#209, #280): a constrained counterfactual, with inputs and all unrelated source held fixed,
     that changes only the defect's trigger and keeps the JVM observation equal, must agree. For #209, rename the
     shadowing local apart (alpha-renaming, which Java's meaning ignores); for #280, give the zero-parameter
     helper an unused parameter and pass a pure argument at each call. Until the fix-based check succeeds in the
     fix's PR, the discovery is recorded as provisional.

   | Defect | Compiler | Found by |
   |---|---|---|
   | #275, #276, #277, #279 | the ADR-067 base, before its fix | PR campaign (M3), `G1E` (M4) |
   | #209 | the current base | PR campaign (M2), `G1E` (M4) |
   | #280 | the current base | PR campaign (M2) |
   | #166 | a scratch revert of its fix (`git revert` of the fix commits, recorded) | PR campaign and `G1E` (M5) |
   | #162 | a scratch revert of its fix, under the `ASSIGN_PATTERN_BINDING` profile | PR campaign (M5) |
   | #47, #50 | a scratch revert of their fixes, if they still apply; otherwise only the `VAR_SWAP` operator in D10.2 covers their class, which is weaker evidence than a rediscovery and is recorded as such | M5 |

   The command, commits, profile, seed, time to first failure and the shrunk reproducer are recorded in this ADR.
   A defect that the campaign does not find within the PR budget is a generator gap and blocks the milestone.
2. **Mutation score.** `G1SelfCheckTest` applies mutation operators to the compiled UPLC of `G1E` programs
   before evaluation, through a test-only transform in `UplcExecutor` (no compiler change). Operators are defined
   over the forms JuLC emits ("Current behavior"):
   - `TAG`: change the tag of a `constrData` application or of a folded `Constr` Data constant;
   - `FIELD_SWAP`: swap two adjacent elements of a `mkCons` chain feeding `constrData`, or of a folded constant;
   - `PROJECTION`: replace a component read `headList (tailList^k …)` by `k ± 1`;
   - `DISPATCH`: swap two branches of an integer `case` (PV11 levels) or of an `ifThenElse` over
     `equalsInteger tag k` (`NONE`, `BASELINE`);
   - `BRANCH`: swap the branches of an `ifThenElse` or bool `case` (covers guard inversion);
   - `GUARD_BYPASS`: replace the condition of an `ifThenElse`, or the scrutinee of a bool `case` (PV11 levels),
     by `True` or `False`;
   - `BOOL_DECODE`: replace the decode of a Bool component by `False`;
   - `EQUALS_DATA`: replace an `equalsData` application by `True`;
   - `EAGER`: make an unselected branch run. For `force (ifThenElse c (delay t) (delay e))`, replace each branch
     by one that first evaluates the other branch and discards it: `delay [(\_ -> t') e]` and
     `delay [(\_ -> e') t]` (primes: de Bruijn indices shifted for the new binder), so the selected result is
     kept when the extra evaluation succeeds, `c` is evaluated once, and scopes are preserved; for a bool `case`
     at the PV11 levels, the same rewrite of its two branches. Removing a `delay`/`force` pair is not used: the
     emitted conditional shares one `force`, and cancelling an adjacent pair is already an optimizer step
     (`UplcOptimizer.java:119-128`). `G1SelfCheckTest` validates the operator first: it must kill a program whose
     unselected branch traces or fails, and must not change a pure control program;
   - `VAR_SWAP`: replace a variable reference by another variable bound at the same point (covers wrong binding
     ownership and stale accumulators).

   For each operator and level the test reports eligible sites, mutants applied, killed (some input's
   observation differs from the JVM's), surviving, crashing (`Crash`) and exhausted (`Inconclusive`); crashes
   and exhaustion are not counted as kills. The unmutated program must agree first. Applicability is a fixed
   table, not inferred from the corpus: each operator × level cell is *applicable* or *not applicable*
   (`DISPATCH`'s `case` form at `NONE`, for example). The test fails if an applicable cell has no eligible site
   on the corpus, or a not-applicable cell has one; the table is recorded in this ADR in M5. M5 measures the scores and records a floor per
   operator and level, below which the test fails; every surviving mutant from the corpus is listed and
   classified (equivalent, with the reason, or a gap fixed by a corpus or input change).
3. **Compiler-level fault injection** (ADR-063 open question 2) stays open; D10.1 and D10.2 do not need it.

*Alternatives:* trusting feature counts alone shows the constructs were generated, not that a defect in them
would be seen (#209 and #275 show the gap). Mutating Java source instead of UPLC would mostly produce programs
that javac or JuLC reject, or that change both sides equally.

### D11. CI

- **PR** (`./gradlew build`): G1 regression cases, `G1E` with its observability edits, the fixed-seed property
  (150 tries), the mapping tests, `G1GatesTest`, `G1MustRejectTest`, and a fixed mutation sample (one mutant per
  operator and level on a fixed subset of `G1E`). M6 records the measured time of this whole workload, with its
  bounds (tries, corpus count, mutants); the target is under 3 minutes added.
- **Nightly** (`difftest-nightly.yml`, `nightlyDiffTest` with both engines): the 3,000-try G1 property with a
  fresh seed on `julc-vm-java` and, from M6, the Scalus VM (an extra `DiffConfig`); `G1FuzzTest` for 20 minutes;
  and the full mutation run. Failures upload `build/difftest/`.
- **Steered guards for fixed defects** (M2 review, F68; implemented in M6): for each gated defect whose fix has
  landed, the nightly job runs its steered `REDISCOVER_*` profile as a permanent regression guard, in two parts: a
  short reproducible run (the PR seed and bounds) and a run with a fresh seed. Either fails on any mismatch. No
  `form=` filter is used. A defect fixed by a rejection runs its historical profile expecting the rejection
  (G1MustRejectTest's cases, D6). The three layers of protection are then: the saved reproducers on every PR, the
  steered campaigns nightly, and the broad unsteered default campaign.
- **Open defects:** the steered profiles of defects still open stay out of nightly, since they fail by design
  until their fix lands; `rediscoveryDiffTest` runs them on demand.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Hand-written javac-oracle tests per construct (as `LoopBreakContinuationTest`) | Necessary for each fix, but scenario by scenario; ADR-062 records that this has not converged |
| Wait for ADR-061's typed frontend representation and generate against it | ADR-061 is a proposal; G1 needs only source text and is useful now |
| Random Java text filtered by javac | Rejected in ADR-063 for G0; the same reasons apply |
| Generate everything, rely on pins for open defects | Pins are exact (ADR-063 D6): every new program in a defect's shape fails the run, so campaigns stop being informative until the fix lands |
| Reflection order for sealed tags | Unspecified (D3), and not independent of a compiler that might use the same order |
| Coverage from traces in the compared program | Changes the program under test; the twin keeps it untouched and is itself checked (D8) |

## Affected modules

- `julc-difftest` only: `gen/G1`, its printer and twin printer, `ValueMapping`, `DiffCase` and `RegressionStore`
  (format 3), `KnownDivergences` (feature names, tags in pin identity), `UplcExecutor` (result representation
  check, test-only transform hook for D10), `julc-difftest/build.gradle` (`nightlyDiffTest` engines, Scalus
  runtime dependency for the nightly configuration), new tests and seeds.
- `.github/workflows/difftest-nightly.yml`: G1 jobs.
- ADR-063: D5 and M2b point here for G1 and the items G1 covers; ADR-062: S1 status.
- No change to the compiler, stdlib, VMs or any published artifact.

## Compatibility

None for users. Inside the harness: G0 plans, saved cases and corpus are unchanged; `.diffcase` format 2 files
are read as before; the registry rules keep their behaviour with feature names.

## Risks

- **A wrong mapping that agrees with a wrong compiler.** Mitigated by G1-I3, G1-I4, the permuted-order vectors
  and the representation check (G1-I10).
- **Gates that outlive their defects.** Each gate names its issue; the ADR-067 PR and the #209 and #280 fixes
  are responsible for turning theirs on, with a `G1.VERSION` bump.
- **PR run time.** Records, switches, twins, edits and mutants cost more than G0; the whole workload is measured
  with fixed bounds (D11).
- **#146 growth.** Comparisons inside records and switches can nest; G1 uses G0 r2's bound and the T13 rule.
- **Equivalent mutants.** Some mutants cannot change any result; D10 classifies survivors rather than demanding
  a perfect score.

**Residual risks** (not addressed by G1):
- *Permissive record-argument boundary.* `compileMethod` passes record arguments without a shape check, so a
  malformed argument (for example `Constr 2 []` in a `boolean` component, which reads as false) is accepted
  differently from the JVM, which cannot build it. G1 generates well-formed arguments only; validator entry
  points have ADR-015's strict boundary; malformed Data is G3's subject.
- Constructs outside the grammar (`Optional`, `JulcMap`, strings, byte arrays, ledger types, generics, lambdas)
  are not covered by G1.
- Agreement on generated inputs is not a proof for all inputs.

## Implementation milestones

| Milestone | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| **M1** | D3, D4 mapping and representation check; D5 harness generalization (feature names, shared `verify`, format 3 with tags and profile, `nightlyDiffTest` engines) | ADR accepted | Mapping tests including the permuted-order vectors (G1-I3, G1-I4, G1-I10); all G0 tests unchanged and green; G0 plan → program identical for 20,000 random plans |
| **M2** | G1 r1 grammar without `switch` (types, two families, fields, arguments, results, loops, method-level `if` and `instanceof`, helpers, traces, failures, same-type `equals`); profile and gates; `G1PropertyTest`, `G1GatesTest`, `G1FuzzTest` | M1 | G1-I1, G1-I2, G1-I5, G1-I6; quotas met; rediscovery of #209 and #280 under their profiles (D10.1); PR time measured; every mismatch found is fixed, pinned with an issue, or gated |
| **M3** | `switch`: type patterns, `default`, `yield` blocks, loops in arms, nesting, inside loops, guard-eligible bindings; then guards, record patterns, cross-type `equals` and shared variants once ADR-067 is in the stack below; `G1MustRejectTest` | M2; for the gated part, ADR-067's implementation | Quotas; rediscovery of #275, #276, #277 and #279 by the PR campaign on the pre-fix base |
| **M4** | `G1E r1` with its observability edits and pinned counts; exercised coverage with the twin contract (D8) | M3 | G1-I7, G1-I8; corpus rediscovery of #209, #275, #276, #277, #279; corpus size and time recorded; exercised quotas met |
| **M5** | D10: rediscovery of #166, #162, #47 and #50 at reverted commits; mutation operators, scores and floors; survivor classification | M4 | G1-I9; floors recorded here; no unclassified survivor |
| **M6** | CI (D11): PR workload with fixed bounds, nightly with both engines and the Scalus VM, and the nightly steered-profile guards for fixed defects (F68); ADR-063 and ADR-062 updates; results section here | M5 | PR and nightly jobs green with measured times |

## Verification strategy

- **Independent oracle:** javac and the JVM (ADR-063); nothing in G1's verdict comes from JuLC's own output.
- **Hand-written vectors** for the mapping (D3), written from the documented encoding, including permuted orders.
- **Rediscovery** of real defects at real commits, under recorded profiles (D10.1).
- **Mutation score** over the emitted forms, with eligibility and classified survivors (D10.2).
- **Exhaustive corpus** with preconditioned observability edits and witnesses (D7), and exercised coverage from a
  checked twin (D8).
- **Traces and failures** in generated programs, so evaluation order and strictness are observable (D2).
- **Negative checks:** gate tests (G1-I6), must-reject tests (D6), mapping error tests.
- **Review:** each milestone is reviewed independently (including Codex) before the next starts.

## Related findings (out of scope)

- Record members are silently ignored (#282) or rejected with uncoded or misleading diagnostics (a member called
  on a constructor expression reports JULC0055 "Cannot resolve x"; a static method reports "Undefined variable:
  P"; an explicit canonical constructor reports "'this' is not supported on-chain"); ADR-067 D9 replaces all of
  these with one coded rejection. Other uncoded rejections: a sealed interface without `permits` over sibling
  records ("Unknown type: T") and `instanceof` with a binding inside `&&` ("Undefined variable", #204). These
  belong to a G1⁻ negative grammar and to coded diagnostics (ADR-062 T20's family).
- `JulcMap` results cannot be mapped by `ValueMapping.fromTerm` today (a native pair list, no `PairConst` case),
  and `JulcMap.get` of a missing key returns `null` on the JVM but fails on-chain. Both are G1b's subject.

## Open questions

1. **Order of the stack.** G1's M3 needs ADR-067's implementation below it. Lean: keep the stack #253 → ADR-067
   → ADR-066 and implement ADR-067 first; G1's M1 and M2 do not need it and can proceed in parallel.
2. **Mutation floors (D10.2).** Fixed numbers now, or measured in M5? Lean: measured in M5, then fixed.
3. **G1b scope.** `Optional` and `JulcMap` need decisions on `get` of a missing key, key equality (T10) and
   map results. Lean: a short ADR-063 revision when G1 is done.
4. **Helpers.** JuLC binds helper methods through a different path from the entry method; should G1 weight
   helper calls higher? Lean: measure in M2 first.
5. **Shared variants (#279).** ADR-067 D6 proposes rejecting every second sealed parent; D6 here follows it
   (must-reject only). If ADR-067's review changes that to accept coinciding tags, `SHARED_VARIANT` gains a
   positive form with coinciding tags.

(r1's open question on the tag source is answered by D3: tags are always carried. r1's open question on VMs is
answered by D11: Scalus joins the nightly run in M6.)

## M1 results (2026-10-30)

Implemented, in `julc-difftest` only (no compiler, stdlib, VM or published module changes; `gen/G0`,
`gen/G0Enumeration` and `gen/Choices` are untouched):

- **D3, tag table.** `TagTable` holds the `(record binary name, parent binary name, tag)` entries, sorted by
  record, then parent. On construction it rejects duplicate entries and, per parent, duplicate tags and tags that
  do not cover `0..n-1`. `TagTable.validate(classes)` is the source validation, over the case's top-level
  declarations and their member classes, recursively (top-level siblings of the entry class included, recorded per
  source by a javac task listener in `JvmExecutor`, also when cases are precompiled together). Local and anonymous
  classes are not visited; javac rejects a sealed family declared in them, and G1 declares its families in `Probe`
  (review F60):
  - every entry names a record and a sealed parent of the program, never a class from elsewhere such as the
    harness;
  - each entry's record directly implements its parent;
  - every record of the program that implements a sealed interface has an entry for each such parent;
  - each parent's entries are exactly its permitted subclasses, so a family with a variant outside the program
    fails.

  `JvmExecutor.run` and `JvmExecutor.resultRepresentation` both validate, and `DiffRunner.run` calls the latter
  before any run. Both are public entry points (`G0ExhaustiveTest` calls `JvmExecutor.run` directly), and
  `JvmExecutorTest` pins each one.
- **D3, value mapping.** `ValueMapping` maps user records and sealed interfaces both ways by D3's rule, with the
  case's table (`toData(value, declaredType, tags)`, `fromData(data, type, tags)`). `TagTable.tagFor` is the
  parent-aware lookup and the boundary validation: a record whose parents give it different tags is a
  `HarnessException` whenever it is mapped (argument, result or component, under either parent); equal tags under
  two parents map. `fromData` for a sealed type picks the permitted subclass whose table tag is the `Constr`'s,
  then checks the arity. A case whose program has sealed variants and no table fails (G1-I4).
- **D4, representation check.** `ValueMapping.Representation` (`INTEGER` for `BigInteger`, `BOOL` for
  `boolean`, `CONSTR_DATA` for a user record or sealed interface, `ANY` otherwise) and
  `fromTerm(term, representation)`; a violation is `Crash("result: representation: …")`. `DiffRunner.run` and
  `rerun` pass the declared type. A package-private test seam exercises that wiring:
  - `UplcExecutor(UnaryOperator<Term>)` rewrites each evaluated result term before mapping;
  - `DiffRunner(configs, timeout, uplc)` runs over such an executor.
- **D5.** `KnownDivergences.Rule.requires` and `explain` take feature names (`KnownDivergences.names` converts
  G0's enum); pins also compare the tag table, with the class name aside (decision 7). The shared helper is `CampaignVerifier.verify(finder, source,
  className, method, args, tags, features, generator, profile, plan)` (test sources), which now owns the runner,
  the registry, the failure record and the seed hook; `G0PropertyTest.verify` is G0's adapter (no tags, no profile,
  `G0.VERSION`). `DiffCase` carries `tags` and `profile`. `.diffcase` format 3 adds `tag:` and `profile:` lines;
  `nightlyDiffTest` runs the `junit-jupiter` and `jqwik` engines, filtered by tag `nightly`.

Decisions made in M1:

1. **Where the declared return type comes from.** From the JVM side: `JvmExecutor.resultRepresentation`
   reflects on the entry method of the class javac compiled, and `DiffRunner` passes the result to
   `UplcExecutor.run` for `run` and for confirmation reruns. javac is the oracle (ADR-063), so this reads the
   source the way the comparison does, never JuLC's output; it needs no new persisted field that could disagree
   with the source; and it applies to format-2 cases unchanged. `UplcExecutor.run` without a representation stays
   unchecked (`ANY`); only `UplcExecutorCacheTest` calls it that way. Carrying the type in `DiffCase` was the
   alternative: one more field to persist, generate and keep consistent with the source.
2. **Which types are checked.** Exactly D4's list. `int`, `long`, boxed `Boolean`, `byte[]`, `String`,
   `PlutusData` and collections are `ANY`, so ADR-063's saved cases are checked only where D4 asks. All of them
   still agree.
3. **What source validation's arity means.** Each sealed parent's entries are exactly its permitted subclasses
   (as a set, never in reflection order), so a family has as many tags as the source has variants. The arity of a
   record value (components against `Constr` fields) is checked when it is mapped.
4. **What reflection is used for:** a record's direct sealed interfaces, the set of permitted subclasses, record
   components in declaration order (`Class.getRecordComponents` specifies that order) and the canonical
   constructor. No tag ever comes from reflection.
5. **Library types: a denylist.** Records and sealed interfaces in `org.julclang.core.`, `org.julclang.ledger.`
   and `org.julclang.stdlib.`, and `PlutusData` subtypes, are not user types. They stay unmapped, as before:
   ledger types map through their own encoders (ADR-063 D4, M4). Every other record is a user record, JDK records
   (`java.`, `jdk.`) included, which G1 programs never use. Ledger records get G1-I10's representation check only
   when ADR-063 M4 maps them; until then `Representation.of` gives them `ANY`.
6. **Format 2 is still written** for a case without tags and without a profile, so G0's D8 records are
   byte-identical to before (`RegressionStoreTest.aCaseWithoutTagsIsWrittenAsFormat2`); any other case is written
   as format 3. The reader accepts both; `tag:` or `profile:` in a format-2 file is an error.
7. **Pin identity, and the profile.** A pin matches the program, method, arguments and tag table, with the
   class name aside in both the program and the table. `KnownDivergences.program` renames the class declaration,
   and `KnownDivergences.tags` rewrites a leading `<className>$` in every record and parent name to `_$`. So a
   tagged pin saved under `Probe` explains the same program compiled as `E135` with `E135$…` tags, as the
   exhaustive corpus needs (review F46/F50); swapped tags, or the pin's `Probe$…` names under `E135`, still do not
   match. The profile is one trimmed line, or null for none; G0's "default profile" is none. It is part of the
   `DiffCase` and of its record, but not of pin matching, since it decides how a program was generated, not what
   it does.
8. **The case name** of a generated case is the generator version's first word in lower case plus the plan
   (`g0 [...]`, as before), so `verify` takes the generator version rather than a name; it also takes `finder` for
   the provenance, as G0's did.

Tests (all new tests are JUnit, written from `advanced-guide.md`'s encoding, not from JuLC output):
- `RecordMappingTest` (11): standalone and zero-component records, `boolean` components, variants typed as
  themselves and as the sealed type with nonzero tags, a table in neither declaration nor `permits` order,
  nested records and sealed components to depth 2, records in lists, round trips under two tables, missing tags
  with and without a table, malformed Data (wrong arity, unknown tags, wrong component kinds), unequal and equal
  tags under two parents, library records.
- `TagTableTest` (4): sorting and the `tag:` text; duplicate, gapped and negative tags; source validation of
  valid tables, including unequal tags under two parents. It also covers what validation rejects:
  - missing variants and a missing second parent;
  - extra entries;
  - a family whose variants are outside the program;
  - entries that name classes outside the program, such as harness classes;
  - a parent that is not sealed, and unknown names.
- `ResultRepresentationTest` (6): `Representation.of`; accepted and rejected terms (an integer as Data, a bool as a
  `Constr`, a record as a native `Constr` or as non-`Constr` Data); the executor's
  `Crash("result: representation …")`; and `BigInteger`, `boolean` and `long` results still agreeing through
  `DiffRunner`. Through the seam, `boolean`, `BigInteger` and sealed results with the right Data in the wrong
  representation crash in both `DiffRunner.run` and `rerun` at every level, and an unchecked `long` still agrees.
- `JvmExecutorTest` (+3): javac's diagnostics are still reported. A sibling family with missing tags fails in
  `JvmExecutor.run` and in `DiffRunner.run` before any run, and runs with its tags. Cases precompiled together
  keep their own classes, so a table cannot name another source's classes.
- `RecordDiffTest` (5): `DiffRunner` end to end with a record argument holding a sealed component and a record
  result, for six families whose declaration, `permits` and switch-arm orders are pairwise different, on every
  variant, at every level; the JVM results against hand-written Data; a sealed result and a variant result typed as
  itself; a table in declaration order instead of `permits` order shows as a mismatch; an incomplete or absent table
  fails before any run.
- `RegressionStoreTest` (+3): format 3 round trip with unequal tags under two parents and a profile, the exact
  header text, case identity under swapped tags; format 2 still written for untagged cases; invalid `tag:` and
  `profile:` lines.
- `KnownDivergencesTest` (+1): a pin matches only its tag table, whatever the profile; with tags, it matches under
  a renamed class, and swapped tags under the renamed class do not match.

Commands and counts:
- `./gradlew :julc-difftest:test --rerun`: 150 tests, 0 failures, 0 errors, 0 skipped (117 before M1, all unchanged in
  meaning; +33).
- G0 is unchanged across M1 (review F53). `G0BoundTest` compares bounded and unbounded decoding at one revision,
  so it is no before-and-after evidence. The evidence is blob identity. `G0.java`, `G0Enumeration.java` and
  `Choices.java` have the same git blobs at `0c09ca01` and at the M1 head (`a0b82ab0`, `2ff50e2e`, `c647a3d8`),
  and they import only `java.util`, so every plan decodes to the same program:
  ```
  for f in G0 G0Enumeration Choices; do p=julc-difftest/src/main/java/org/julclang/difftest/gen/$f.java;
    echo "$f $(git rev-parse 0c09ca01:$p) $(git rev-parse HEAD:$p)"; done
  git diff --quiet 0c09ca01 -- julc-difftest/src/main/java/org/julclang/difftest/gen/{G0,G0Enumeration,Choices}.java \
    julc-difftest/src/test/resources
  ```
- Mutation check (review round 1): the reviewer's 16 mutants of the main sources, plus 3 new ones, were run one
  at a time against the 8 targeted test classes (`RecordMappingTest`, `TagTableTest`, `ResultRepresentationTest`,
  `RecordDiffTest`, `RegressionStoreTest`, `KnownDivergencesTest`, `ValueMappingTest`, `JvmExecutorTest`). The
  new mutants are: pin tags not renamed; validation of the entry class only; `JvmExecutor.run` without
  validation, replacing the reviewer's M11 at its new site. All 19 are killed, including the four that had
  survived:
  - M6, the permitted-set check;
  - M11, `JvmExecutor.run` without validation;
  - M12 and M13, `DiffRunner.run` and `rerun` passing `ANY`.
- `./gradlew :julc-difftest:nightlyDiffTest --rerun`, with a temporary probe class holding one JUnit test tagged
  `nightly` and one untagged (removed afterwards): 2 tests ran, 0 failures, in 1 min 52 s. They were the
  3,000-try `G0PropertyTest.generatedProgramsAgreeNightly` (jqwik, 111 s) and the tagged JUnit probe. Neither
  the untagged probe nor any other test ran.

Findings: JuLC tags each variant by its `permits` position for all six permutations at every level, and returns a
record or sealed result as a Data constant holding a `Constr` (the check passes). No deviation from the ADR.

## M2 results (2026-10-30)

These are the results at `98c47c78` (G1 r1). The review round below changed the decoding to G1 r2 and supersedes
the quota table, the rediscovery table, the saved cases and the counts.

Implemented, in `julc-difftest` only (G0, its corpus and its saved inputs untouched):

- **`gen/G1`, version `G1 r1`:** D2's grammar without `switch`. It generates:
  - up to two standalone records and two sealed families of one to three variants, with `permits` orders, and so
    tags, that are permutations independent of the declaration order; components from the pool `v, w, x, f, p`;
  - up to two `static final` fields, initialized from constructors, constants and earlier fields;
  - the fixed helpers `t` and `fail`, and up to two generated helpers of one to three parameters;
  - G0's loops with accumulators of any generated type;
  - method-level `if` with an early return, a final `if`/`else` that returns from both branches, and
    `if (s instanceof V y)` as a whole condition outside loops;
  - same-type and same-family `equals`, traces, and failures in conditionally evaluated positions;
  - record and sealed parameters and results.
- **Binding model:** each variable records whether it is assignable (a method-level accumulator) and whether it is
  guard-eligible (a parameter, pattern binding, field or never-assigned local), for M3's guards.
- **Profiles (D6):** `G1.Gate` defines every switch, and `G1.Profile` prints and parses
  `G1 r1 ±GATE …`. The profile is written in each record as `profile:`, and `G1PropertyTest.profile` replays it. The
  default profile has every gate off.
- **Arguments:** decoded from the program's types and a second choice sequence. `a`, `b` and the elements of `xs`
  are drawn from −6..6; each sealed argument picks its variant uniformly. Each value is mapped to Data by D3's rule
  with the generator's tag table.
- **Tests:**
  - `G1PropertyTest`: the PR campaign (fixed seed `20261030`, 150 tries, quotas), the nightly campaign (3,000
    tries, tag `nightly`), and the rediscovery campaign (tag `rediscovery`). The rediscovery campaign runs only in the
    new task `rediscoveryDiffTest`, and `test` excludes it.
  - `G1GatesTest`: 20,000 default-profile plans contain no gated construct, judged by the markers and by an
    independent reading of the printed source; each historical profile generates its construct; the M3 switches
    change no program.
  - `G1DecodeTest`: determinism of the complete case for whole, truncated and empty plans; ADR-063 H6 for 400
    programs through `JvmExecutor` (javac, source validation, argument mapping, a run).
  - `G1FuzzTest`: Jazzer, bytes to inputs then plan, 12 committed seeds.
  - `G1RediscoveryTest`: the cause checks below.

Decisions and deviations:

1. **A new switch, `TRACE_IN_COMPARISON` (#146),** added to D6's table. The default campaign found that a traced
   `compareTo` operand traces twice on UPLC. Under the gate off, `compareTo` operands contain no `t(...)` and no call
   of a helper whose body traces.
2. **`LOOP_BREAK_SHAPES` is broader than G0's markers:** loop bodies with `break` and conditional updates are gated
   (`LOOP_BREAK_SHAPES`, #254, #255).
3. **The arguments come from a second choice sequence**, jqwik's second list and Jazzer's first bytes, rather than
   from the plan. The program is a function of the plan, `G1.VERSION` and the profile (G1-I5). The complete case
   adds the input sequence, as G0's `a`, `b` and `xs` are jqwik inputs. Records carry `found.inputs`.
4. **The declarations read the plan's even positions and the entry method its odd ones,** so that a plan used up by
   types, fields and helpers still leaves `m` its choices. Without this, about half of all programs had a trivial
   `m`.
5. **The M3 switches are defined and inert:** `GUARDS`, `RECORD_PATTERNS`, `CROSS_TYPE_EQUALS`, `SHARED_VARIANT` and
   `ASSIGN_PATTERN_BINDING`. M2's scope is same-type `equals`, and the M3 row takes cross-type `equals` and shared
   variants.
6. **Each historical profile generates its construct often enough** to be found within the PR budget. The default
   profile's decoding is unaffected:
   - `REDISCOVER_209`: one or two fields of a declared type, more loops and conditional updates, a branch local that
     shadows a record field with another record type, and a component read of that field after the `if`;
   - `REDISCOVER_280`: half of the helpers take no parameter, and most such helpers' bodies trace;
   - `REDISCOVER_146`: a third of the comparisons trace their left operand.
7. **Subset rules the grammar keeps** (G1-I1):
   - a pattern variable never has a field's name (JULC0059);
   - a local's initializer never reads the field the local shadows (Java scoping);
   - field initializers use no ternaries or comparisons, and `boolean` components there are literals;
   - an accessor is called only on a variable or a chain from one (JULC0055);
   - an atom calls no helper. An earlier draft nested calls in atoms, and #146's duplication grew one script to
     347 KB and exhausted the budget.
8. **Bounds:** five top-level statements, counted as G0 counts them (a loop with its accumulator declarations is one);
   expression depth 2; plan length 200; 40 inputs. In the default profile a loop-branch local never takes any field's
   name, which is stricter than D6's "of another type".
9. **No `DEEP_COMPARISON` marker in G1:** depth 2 keeps `compareTo` nesting shallow, so a budget exhaustion would be
   unexplained. None occurred.
10. **Quotas apply to D8's 22 features without the `switch` ones;** `LOOP` and `BREAK` are statistics.

Quotas (conclusive cases containing the feature, divided by all tries; quota 3%). Every PR and nightly case was
conclusive at every level.

| Feature | PR, 150 tries | Nightly, 3,000 tries |
|---|---|---|
| `RECORD` | 82 (54.7%) | 1826 (60.9%) |
| `SEALED` | 79 (52.7%) | 1615 (53.8%) |
| `TWO_FAMILIES` | 38 (25.3%) | 767 (25.6%) |
| `ZERO_COMPONENTS` | 66 (44.0%) | 1231 (41.0%) |
| `NESTED_RECORD` | 27 (18.0%) | 768 (25.6%) |
| `SEALED_COMPONENT` | 23 (15.3%) | 393 (13.1%) |
| `RECORD_ARG` | 39 (26.0%) | 903 (30.1%) |
| `SEALED_ARG` | 29 (19.3%) | 673 (22.4%) |
| `RECORD_RESULT` | 24 (16.0%) | 613 (20.4%) |
| `SEALED_RESULT` | 22 (14.7%) | 448 (14.9%) |
| `COMPONENT_ACCESS` | 34 (22.7%) | 777 (25.9%) |
| `CHAINED_ACCESS` | 9 (6.0%) | 221 (7.4%) |
| `STATIC_FIELD` | 63 (42.0%) | 1568 (52.3%) |
| `FIELD_FROM_FIELD` | 21 (14.0%) | 394 (13.1%) |
| `FIELD_SHADOWED` | 30 (20.0%) | 687 (22.9%) |
| `RECORD_EQUALS` | 14 (9.3%) | 333 (11.1%) |
| `RECORD_ACCUMULATOR` | 33 (22.0%) | 681 (22.7%) |
| `INSTANCEOF_PATTERN` | 17 (11.3%) | 339 (11.3%) |
| `METHOD_LEVEL_IF` | 46 (30.7%) | 966 (32.2%) |
| `HELPER_CALL` | 17 (11.3%) | 323 (10.8%) |
| `TRACE` | 19 (12.7%) | 427 (14.2%) |
| `FAILURE` | 29 (19.3%) | 365 (12.2%) |
| `LOOP` (statistics, no quota) | 51 (34.0%) | 1159 (38.6%) |
| `BREAK` (statistics, no quota) | 3 (2.0%) | 76 (2.5%) |

Rediscovery (D10.1). The base is 744ab54f, the defects are open, and the command is
`./gradlew :julc-difftest:rediscoveryDiffTest -Pdifftest.profile=<profile>`. Each run uses the PR seed `20261030`
and bounds and starts afresh (`AfterFailureMode.PREVIOUS_SEED`); each command takes about 12 s.

| Defect | Profile | First failing try | Time to first failure | Shrunk reproducer | JVM | UPLC, every level |
|---|---|---|---|---|---|---|
| #209 | `REDISCOVER_209` | 2 | 0.8 s | below | `Value -5 [k0]` | `Rejected JULC0055` "Cannot resolve v" |
| #280 | `REDISCOVER_280` | 5 | 1.5 s | `static BigInteger v() { return t("k0", BigInteger.valueOf(-5)); }`, never called; `m` returns −5 | `Value -5 []` | `Value -5 [k0]` |
| #146 | `REDISCOVER_146` | 12 | 1.7 s | `return (t("k0", BigInteger.valueOf(-5)).compareTo(BigInteger.valueOf(-5)) < 0 ? … : …).add(…);` | `Value -10 [k0]` | `Value -10 [k0, k0]` |

The #209 reproducer is in a loop body:

```java
record R0(BigInteger v) {}
static final R0 f = new R0(BigInteger.valueOf(-2).multiply(BigInteger.valueOf(2)));
// in m(a = 6, b = 3, xs = [2]):
BigInteger w = t("k0", a);
for (var w1 : xs) {
    w = BigInteger.valueOf(4);
    if (BigInteger.valueOf(-5).compareTo(BigInteger.valueOf(-5)) < 0) {
        BigInteger f = BigInteger.valueOf(-5);
        w = BigInteger.valueOf(-5);
    }
    w = f.v();
}
```

The branch local `BigInteger f` keeps its type after the `if`, so `f.v()` resolves against `BigInteger` and the
program is rejected.

Cause checks: all three defects are not yet fixed, so each check is a constrained counterfactual. Each discovery is
**provisional** until the fix-based check succeeds in the fix's PR. The reproducers of #280 and #146 are saved as
`DIVERGE` pins; each counterfactual is saved as an `AGREE` case. `G1RediscoveryTest` asserts that every
counterfactual keeps the JVM observation and agrees, and that every reproducer still diverges or, for #209, is
rejected with JULC0055.

| Defect | Counterfactual | Saved as |
|---|---|---|
| #209 | The branch local is renamed `f1` (alpha-renaming) | `agree-g1-209-counterfactual` |
| #280 | The helper takes an unused parameter; there is no call site to give an argument | `diverge-g1-280-uncalled-zero-arg-helper`, `agree-g1-280-counterfactual` |
| #146 | The traced operand is bound to a local before the comparison | `diverge-g1-146-traced-compare-operand`, `agree-g1-146-counterfactual` |

Commands and counts:
- `./gradlew :julc-difftest:test --rerun`: 180 tests, 0 failures, 0 errors, 0 skipped, in 67 s (M1's 146 tests,
  unchanged; +34).
  - The G1 PR workload takes 9.8 s: `G1PropertyTest` 6.2 s, `G1GatesTest` 1.4 s, `G1DecodeTest` 1.2 s,
    `G1FuzzTest` seeds 0.8 s and `G1RediscoveryTest` 0.3 s.
  - `RegressionCasesTest` replays 43 cases, 5 of them new.
- `./gradlew :julc-difftest:nightlyDiffTest --tests '*G1PropertyTest' --rerun`, run twice with fresh seeds:
  3,000 tries each, all conclusive at every level, in 134 s and 132 s.
- `./gradlew :julc-difftest:fuzz --tests '*G1FuzzTest' -Pfuzz.duration=4m`: 4,670 runs and no finding.

Findings:
- #146 has an observable consequence: a traced `compareTo` operand traces more than once at every level. It is
  recorded in #146 and gated by `TRACE_IN_COMPARISON`.
- Loop bodies with `break` and conditional updates are gated (`LOOP_BREAK_SHAPES`, #254, #255).
- No other mismatch was found by the default-profile campaigns: PR, two nightly runs and Jazzer.

### M2 review round 1 (2026-10-30, G1 r2)

(Round 2 below supersedes this round's quota table, rediscovery table and saved cases with G1 r3's.)

The round answers the review comment's F61–F64 and Codex's F70–F73.

| Finding | Change |
|---|---|
| F72 (P2), typed AST | `G1Ast` holds the types, bindings, expressions, statements and declarations, and `G1Printer` prints them. Every production builds nodes. Committed alone (`f555b5e7`): over 20,000 random plans under five profiles (default, the three rediscovery profiles, `LOOP_BREAK_SHAPES`), source, tags, features and arguments were byte-identical to `98c47c78`, so that commit is still G1 r1. |
| F70 (P1), variant-typed declarations | Variants enter type selection: a declaration, field, accumulator, parameter or result can be typed as a variant. `declaredType` picks a kind first (standalone record, family, variant) so variants do not crowd out families. New quota features `VARIANT_ARG` and `VARIANT_RESULT` (a variant with a nonzero tag) and `VARIANT_ACCUMULATOR`, each with a quarter-of-the-time witness production. `G1ShapesTest` reads nonzero-tag variant parameters and results from the printed source and the tag table. |
| F71 (P1), boolean helper calls | Conditions call boolean helpers (two cases of ten, bounded like other calls, respecting `TRACE_IN_COMPARISON` through `Ctx.callable`). Half of the helpers return `boolean`. New quota feature `BOOLEAN_HELPER_CALL`. `G1ShapesTest.aBooleanHelperCallDecidesTheOutcome`: negating the boolean helpers' bodies changes the JVM observation on some input for at least 5 of 40 calling programs. |
| F73 (P2), recorded case identity | `G1DecodeTest.aFailureRecordRegeneratesItsCase` takes a case that fails under `REDISCOVER_280`, with inputs, through `G1PropertyTest.verify`, `CampaignVerifier` and `RegressionStore`. It reads the record back, regenerates from its `profile:`, `found.plan` and `found.inputs`, and compares source, tags and arguments. Replacing the adapter's profile with `DEFAULT` fails it (mutation run). `equalSourcesUnderDifferentProfilesAreDifferentCases`: equal sources under `DEFAULT` and `+GUARDS` are different, recorded cases. |
| F61 (P2), #209 evidence | Below: runtime and silent forms, both with counterfactuals, and the outcome distribution. |
| F62 (P3), quota check after a failure | `G0PropertyTest` and `G1PropertyTest` skip coverage for the failing try and every shrinking call after it, and skip the quota check when the property failed. |
| F63 (P3), shapes hidden behind sibling quotas | New quota features `PERMITS_PERMUTED` (a family whose `permits` order differs from both its declaration order and its variants' name order) and `FAMILY_EQUALS`. `LOOP_LOCAL_SHADOWS_FIELD` is about 4% raw, too rare for a stable PR quota, so `G1ShapesTest` reads it from the printed source over 4,000 plans, together with `PERMITS_PERMUTED`. Loop-body locals take a free field's name two times in three. The review's three surviving mutants are now killed: `permits` equal to the creation order (by the `PERMITS_PERMUTED` quota and `G1ShapesTest`), no `equals` against the family (by the `FAMILY_EQUALS` quota), and loop-body locals that never shadow a field (by `G1ShapesTest`). |
| F64 (P3), wording | G1-I5 names the input sequence and says the determinism test is a same-process check. D1 describes the even/odd split. |
| #146 and `signum` (coordinator) | `signum` evaluates its receiver twice like `compareTo`. G1 emits no `signum`. Probes with traced operands showed that `add`, `subtract`, `multiply`, `equals`, constructors, helper calls (integer and boolean), ternaries, `&&`, `||` and `!` evaluate them once at every level. The gate's definition and `G1GatesTest`'s source reading now cover `signum` receivers (`theSourceReadingCoversSignumReceivers`). |

G1 r2 decodes differently from r1 because of F70, F71, F63 and the #209 steering. So the version is bumped, and the
saved G1 cases are re-recorded from r2 rediscovery runs.

#209's profile now steers towards the issue's own shape:
- R0 and R1 are two records with the same components in different orders;
- two out of three top-level statements are loops, and loop bodies have no `break`;
- a branch local shadows a field of the other record;
- after the `if`, a component of the field is read into an accumulator that the result prefers.

Quotas (G1 r2):

| Feature | PR, 150 tries | Nightly, 3,000 tries |
|---|---|---|
| `RECORD` | 82 (54.7%) | 1912 (63.7%) |
| `SEALED` | 79 (52.7%) | 1634 (54.5%) |
| `TWO_FAMILIES` | 38 (25.3%) | 749 (25.0%) |
| `ZERO_COMPONENTS` | 66 (44.0%) | 1193 (39.8%) |
| `NESTED_RECORD` | 27 (18.0%) | 829 (27.6%) |
| `SEALED_COMPONENT` | 23 (15.3%) | 437 (14.6%) |
| `RECORD_ARG` | 31 (20.7%) | 726 (24.2%) |
| `SEALED_ARG` | 10 (6.7%) | 345 (11.5%) |
| `RECORD_RESULT` | 39 (26.0%) | 883 (29.4%) |
| `SEALED_RESULT` | 36 (24.0%) | 472 (15.7%) |
| `COMPONENT_ACCESS` | 39 (26.0%) | 1005 (33.5%) |
| `CHAINED_ACCESS` | 11 (7.3%) | 320 (10.7%) |
| `STATIC_FIELD` | 63 (42.0%) | 1538 (51.3%) |
| `FIELD_FROM_FIELD` | 19 (12.7%) | 352 (11.7%) |
| `FIELD_SHADOWED` | 25 (16.7%) | 721 (24.0%) |
| `RECORD_EQUALS` | 18 (12.0%) | 329 (11.0%) |
| `RECORD_ACCUMULATOR` | 28 (18.7%) | 675 (22.5%) |
| `INSTANCEOF_PATTERN` | 15 (10.0%) | 326 (10.9%) |
| `METHOD_LEVEL_IF` | 45 (30.0%) | 899 (30.0%) |
| `HELPER_CALL` | 18 (12.0%) | 330 (11.0%) |
| `TRACE` | 14 (9.3%) | 330 (11.0%) |
| `FAILURE` | 16 (10.7%) | 354 (11.8%) |
| `VARIANT_ARG` | 12 (8.0%) | 429 (14.3%) |
| `VARIANT_RESULT` | 13 (8.7%) | 376 (12.5%) |
| `VARIANT_ACCUMULATOR` | 6 (4.0%) | 230 (7.7%) |
| `BOOLEAN_HELPER_CALL` | 6 (4.0%) | 172 (5.7%) |
| `PERMITS_PERMUTED` | 10 (6.7%) | 223 (7.4%) |
| `FAMILY_EQUALS` | 6 (4.0%) | 142 (4.7%) |
| `LOOP_LOCAL_SHADOWS_FIELD` (decode-checked in `G1ShapesTest`) | 0 (0.0%) | 113 (3.8%) |
| `LOOP` (statistics, no quota) | 44 (29.3%) | 1157 (38.6%) |
| `BREAK` (statistics, no quota) | 2 (1.3%) | 73 (2.4%) |

Rediscovery (G1 r2). The command is `./gradlew :julc-difftest:rediscoveryDiffTest -Pdifftest.profile=<profile>`, with
`-Pdifftest.form=value` for the silent form. Each run uses seed `20261030`, 150 tries, the 744ab54f compiler and
about 10 s per command.

| Defect | Profile, form | First failing try | Time to first failure | JVM | UPLC, every level | Saved as |
|---|---|---|---|---|---|---|
| #209 | `REDISCOVER_209` | 5 | 1.3 s | `Value R0(false, -5)` | `Failure UnIData: not IntData` | `diverge-g1-209-runtime-misread` |
| #209 | `REDISCOVER_209`, `form=value` | 5 | 1.3 s | `Value R0(-1, -1)` | `Value R0(2, 2)` | `diverge-g1-209-silent-misread` |
| #280 | `REDISCOVER_280` | 18 | 2.0 s | `Value -5 []` | `Value -5 [k0]` | `diverge-g1-280-uncalled-zero-arg-helper` |
| #146 | `REDISCOVER_146` | 12 | 1.7 s | `Value -5 [k0]` | `Value -5 [k0, k0]` | `diverge-g1-146-traced-compare-operand` |

The silent #209 reproducer is the issue's shape:

```java
record R0(BigInteger v, BigInteger w) {}
record R1(BigInteger w, BigInteger v) {}
static final R1 x = new R1(BigInteger.valueOf(-1), BigInteger.valueOf(2));
// in m, in the second loop:
if (BigInteger.valueOf(-5).compareTo(BigInteger.valueOf(-5)) < 0) {
    R0 x = new R0(BigInteger.valueOf(-5), BigInteger.valueOf(-5));
    v = new R1(BigInteger.valueOf(-5), BigInteger.valueOf(-5));
}
f = x.w();   // Java: the field's w, -1. JuLC: R0's index of w, 1, on R1's Data: v, 2.
```

Every reproducer has an `agree-…-counterfactual` case: the shadowing local renamed apart (#209), an unused
parameter (#280), or the operand bound to a local (#146). `G1RediscoveryTest` checks each pair: the JVM observation
is equal, the reproducer diverges and the counterfactual agrees. G1 r1's rejection form of #209 stays as
`agree-g1-209-rejection-counterfactual`, and the test still checks it. All are provisional until the fixes land.

#209 outcomes:

| Run | Marked plans | Agree | Rejected JULC0055 | JVM value, UPLC failure | Value against value |
|---|---|---|---|---|---|
| G1 r1 profile (the review, seed 7) | 101 | 79 | 18 | 4 | 0 |
| G1 r2 profile (seed 7, random inputs) | 200 of 1,412 decoded | 183 | 0 | 9 | 8 |

Under r1 no silent value-against-value read was seen. It needs a leaked type with a same-named component at
another index and the same decoding; the r1 grammar could produce that but rarely did. The r2 profile produces the
issue's two-record shape and reads the field into the result, and 4% of its marked plans show the silent form.
Under the r2 profile, rejections no longer occur: both records share their component names, so the leaked type
always resolves the component. The first form the default campaign shrinks to is therefore the runtime one, and
`-Pdifftest.form=value` keeps shrinking within the silent form.

Commands and counts (G1 r2):
- `./gradlew :julc-difftest:test --rerun`: 190 tests, 0 failures, 0 errors, 0 skipped, in 68 s.
  - The G1 workload takes 12.3 s: property 5.9 s, shapes 2.7 s, gates 1.5 s, decode 1.1 s, fuzz seeds 0.7 s,
    rediscovery checks 0.5 s.
  - `RegressionCasesTest` replays 47 cases.
- `./gradlew :julc-difftest:nightlyDiffTest --tests '*G1PropertyTest' --rerun`: 3,000 tries, all conclusive at
  every level, 133 s.

Deviations from the ADR, added in this round:
- Variant-typed declarations get witness productions: a quarter of the time a nonzero-tag variant as a parameter
  and as the result, and a variant accumulator.
- `LOOP_LOCAL_SHADOWS_FIELD` is decode-checked rather than given a quota.
- The #209 historical profile is steered to the issue's own shape.

### M2 review round 2 (2026-10-30, G1 r3)

(Round 3 below supersedes this round's rediscovery table and saved cases with G1 r4's.)

The round answers the review comment's F65–F67 and Codex's F80, and extends the #146 gate to `signum`.

| Finding | Change |
|---|---|
| F65 (P2), steering keyed to the gate | Rediscovery emphasis is now a separate `G1.Steer` (`ISSUE_209`, `ISSUE_280`, `ISSUE_146`). Each steer requires its gate and is printed in the profile text as `steer=209`, `steer=280` or `steer=146` (`… +SHADOW_IN_LOOP_BRANCH … steer=209`). `REDISCOVER_x` is `DEFAULT.with(gate).steered(x)`. A gate alone only allows its construct: under `SHADOW_IN_LOOP_BRANCH`, a branch local shadows a field half of the time it can; under `ZERO_ARG_HELPERS`, a quarter of the helpers have no parameter; under `TRACE_IN_COMPARISON`, traces enter operands by the default productions. The mirrored records, the extra loops, the absence of `break`, the result's preference, half of the helpers zero-argument and most of their bodies traced, and the traced left operands are all steering. `G1GatesTest.aGateAloneKeepsTheDefaultDistribution` checks each implemented gate over 4,000 plans: the shares of programs with a `break`, a loop, a zero-parameter helper (except under its own gate) and mirrored records stay within 2 points of the default. The `REDISCOVER_*` profiles differ, and `steer=209` with the gate off is refused. |
| F80 (P2), variant-typed components | A component may be a variant of a family declared earlier (acyclic, within the depth limit). A quarter of the components are a nonzero-tag variant when one fits (witness). New quota feature `VARIANT_COMPONENT`, and `G1ShapesTest` reads it from the source and tag table. Argument mapping goes through D3 with the variant's own tag, and accessor chains pass through variant components. |
| F66 (P3), thin quotas | Variant accumulators half of the time (was a quarter), boolean helper calls in 3 of 12 condition cases, `equals` in boolean atoms a third of the time, and receivers that can compare against a family three times in four. A `permits` order is never the creation (name) order. The PR minimum is now 8 (`SEALED_ARG`); the three former 4.0% quotas are at 9 to 16. |
| F67 (P3), loop-body shadowing in the PR run | `agree-g1-loop-local-shadows-field`, from a default-profile decoding: a `boolean f` declared directly in a `while` body shadows the field `R0 f`, and `f.equals(x)` reads the field after the loop. It agrees at every level. |
| `signum` (coordinator) | Covered in round 1's table. |

G1 r3 decodes differently from r2 because of F80, F66 and the steering split, so the version is bumped and the G1
pins and counterfactuals are re-recorded from r3 rediscovery runs.

Quotas (G1 r3):

| Feature | PR, 150 tries | Nightly, 3,000 tries |
|---|---|---|
| `RECORD` | 82 (54.7%) | 1872 (62.4%) |
| `SEALED` | 79 (52.7%) | 1667 (55.6%) |
| `TWO_FAMILIES` | 39 (26.0%) | 788 (26.3%) |
| `ZERO_COMPONENTS` | 65 (43.3%) | 1271 (42.4%) |
| `NESTED_RECORD` | 43 (28.7%) | 1118 (37.3%) |
| `SEALED_COMPONENT` | 15 (10.0%) | 347 (11.6%) |
| `RECORD_ARG` | 30 (20.0%) | 678 (22.6%) |
| `SEALED_ARG` | 8 (5.3%) | 360 (12.0%) |
| `RECORD_RESULT` | 39 (26.0%) | 874 (29.1%) |
| `SEALED_RESULT` | 36 (24.0%) | 480 (16.0%) |
| `COMPONENT_ACCESS` | 38 (25.3%) | 932 (31.1%) |
| `CHAINED_ACCESS` | 16 (10.7%) | 358 (11.9%) |
| `STATIC_FIELD` | 60 (40.0%) | 1506 (50.2%) |
| `FIELD_FROM_FIELD` | 16 (10.7%) | 363 (12.1%) |
| `FIELD_SHADOWED` | 30 (20.0%) | 691 (23.0%) |
| `RECORD_EQUALS` | 36 (24.0%) | 770 (25.7%) |
| `RECORD_ACCUMULATOR` | 25 (16.7%) | 713 (23.8%) |
| `INSTANCEOF_PATTERN` | 16 (10.7%) | 308 (10.3%) |
| `METHOD_LEVEL_IF` | 42 (28.0%) | 918 (30.6%) |
| `HELPER_CALL` | 22 (14.7%) | 345 (11.5%) |
| `TRACE` | 19 (12.7%) | 347 (11.6%) |
| `FAILURE` | 10 (6.7%) | 272 (9.1%) |
| `VARIANT_ARG` | 14 (9.3%) | 376 (12.5%) |
| `VARIANT_RESULT` | 13 (8.7%) | 386 (12.9%) |
| `VARIANT_ACCUMULATOR` | 9 (6.0%) | 323 (10.8%) |
| `VARIANT_COMPONENT` | 14 (9.3%) | 300 (10.0%) |
| `BOOLEAN_HELPER_CALL` | 14 (9.3%) | 197 (6.6%) |
| `PERMITS_PERMUTED` | 11 (7.3%) | 284 (9.5%) |
| `FAMILY_EQUALS` | 16 (10.7%) | 400 (13.3%) |
| `LOOP_LOCAL_SHADOWS_FIELD` (decode-checked; saved case, F67) | 0 (0.0%) | 102 (3.4%) |
| `LOOP` (statistics, no quota) | 42 (28.0%) | 1129 (37.6%) |
| `BREAK` (statistics, no quota) | 3 (2.0%) | 72 (2.4%) |

Rediscovery (G1 r3). The command is `./gradlew :julc-difftest:rediscoveryDiffTest -Pdifftest.profile=<profile>
[-Pdifftest.form=failure]`, with seed `20261030`, 150 tries and the 744ab54f compiler.

| Defect | Profile, form | First failing try | Time to first failure | JVM | UPLC, every level | Saved as |
|---|---|---|---|---|---|---|
| #209 | `REDISCOVER_209` (`steer=209`) | 20 | 2.4 s | `Value R0(-5, -5)` | `Value R0(-4, -4)` (silent) | `diverge-g1-209-silent-misread` |
| #209 | `REDISCOVER_209`, `form=failure` | 20 | 2.1 s | `Value R0(-5, false)` | `Failure UnIData: not IntData` | `diverge-g1-209-runtime-misread` |
| #280 | `REDISCOVER_280` (`steer=280`) | 18 | 1.8 s | `Value -5 []` | `Value -5 [k0]` | `diverge-g1-280-uncalled-zero-arg-helper` |
| #146 | `REDISCOVER_146` (`steer=146`) | 2 | 0.9 s | `Value -5 [k0]` | `Value -5 [k0, k0]` | `diverge-g1-146-traced-compare-operand` |

The default form of the #209 run now shrinks to the silent form, the issue's own shape:

```java
record R1(BigInteger w, BigInteger v) {}
record R0(BigInteger v, BigInteger w) {}
static final R0 w = new R0(BigInteger.valueOf(-5), BigInteger.valueOf(-4));
// in m, a for-each over xs = [-5, -1, -2, 1]:
if (BigInteger.valueOf(-5).compareTo(BigInteger.valueOf(-5)) < 0) {
    R1 w = new R1(BigInteger.valueOf(-5), BigInteger.valueOf(-5));
    v = new R0(BigInteger.valueOf(-5), BigInteger.valueOf(-5));
}
v1 = w.v();   // Java: the field's v, -5. JuLC: R1's index of v, 1, on R0's Data: w, -4.
```

`G1RediscoveryTest` checks the four r3 pairs and r1's rejection-form counterfactual. All are provisional.

Unsteered gate: `DEFAULT.with(SHADOW_IN_LOOP_BRANCH)` is the profile that will guard #209 once D6 turns it on
after the fix. It did not find #209 within the PR budget (150 tries, seed `20261030`) on the base that has the defect.
The guard after the fix is therefore the default campaign at the default rate, plus the saved #209 cases, which flip
to `AGREE` with the fix. The steered profile is what finds the defect quickly.

#209 outcomes under G1 r3's steered profile (seed 7, random inputs): of 200 marked plans out of 1,296 decoded, 180
agree, 10 are JVM value against UPLC failure, and 10 are silent value against value.

Commands and counts (G1 r3):
- `./gradlew :julc-difftest:test --rerun`: 192 tests, 0 failures, 0 errors, 0 skipped, in 69 s.
  - The G1 workload takes 12.7 s.
  - `RegressionCasesTest` replays 48 cases.
- `./gradlew :julc-difftest:nightlyDiffTest --tests '*G1PropertyTest' --rerun`: 3,000 tries, all conclusive at
  every level, 142 s.

Deviations from the ADR, added in this round:
- Steers are a second component of a profile, next to its gates. D6 names only gates. They are printed and parsed
  like the gates, and the default profile has none.
- A record component may be a variant, with a quarter-of-the-time witness.

### M2 review round 3 (2026-10-30, G1 r4)

The review comment approved M2 at `86496b66`, with one P3 (F68). Codex raised one P2 (F85) and asked for a semantic
witness for F80.

| Finding | Change |
|---|---|
| F85 (P2), variant components filtered by the family's depth | `components()` now lists a family's variants before the family's own depth check and filters each variant by its own depth. A shallow variant of a family with a deep sibling is therefore a candidate, whatever its tag. `G1ShapesTest.shallowVariantsOfDeepFamiliesAreComponents` reads, from the printed source, components typed as a variant whose family has depth 2, and requires both tag-0 and nonzero-tag cases over 4,000 plans. With the old order it fails: tag 0 had 0 cases, nonzero 95. |
| F80, semantic witness | `G1ShapesTest.aNonzeroTagVariantComponentIsReadThroughAChain` finds generated programs with a record parameter whose component is a nonzero-tag variant and whose source reads it through a chain (`p.c().d()`). For each, it changes only that variant's integer component in the argument Data, runs both arguments through `DiffRunner`, and requires agreement at every level on both. It requires at least 3 programs where the JVM result changes, so the mapping and the chained read carry the value. |
| F68 (P3), guarding a fixed defect | Text now, code in M6. D6 adds the post-fix procedure: the fix PR runs its `REDISCOVER_*` profile at the PR bounds, it must agree, and the pins flip. D11 adds the nightly steered guards for fixed defects (a reproducible run and a fresh-seed run, no `form=` filter, expected-rejection runs for defects fixed by rejection), and keeps open defects' steered profiles out of nightly. The M6 row includes this job. |

G1 r4 decodes differently from r3 because of F85, so the version is bumped. The G1 pins and counterfactuals are
re-recorded from r4 rediscovery runs, with seed `20261030`, 150 tries and the 744ab54f compiler:

| Defect | Profile, form | First failing try | Time to first failure | JVM | UPLC, every level |
|---|---|---|---|---|---|
| #209 | `REDISCOVER_209` | 20 | 2.3 s | `Value R0(-5, -5)` | `Value R0(-4, -4)` (silent) |
| #209 | `REDISCOVER_209`, `form=failure` | 20 | 2.1 s | `Value R0(-5, false)` | `Failure UnIData: not IntData` |
| #280 | `REDISCOVER_280` | 18 | 2.0 s | `Value -5 []` | `Value -5 [k0]` |
| #146 | `REDISCOVER_146` | 2 | 0.8 s | `Value -5 [k0]` | `Value -5 [k0, k0]` |

The reproducers have the same shapes as in r3. `agree-g1-loop-local-shadows-field` (F67) keeps its G1 r3 record:
its provenance names the version it was found with.

PR quotas (G1 r4, 150 tries): the minimum is `SEALED_ARG` at 8. Then `VARIANT_ACCUMULATOR` at 10, `FAILURE` and
`PERMITS_PERMUTED` at 11, `BOOLEAN_HELPER_CALL`, `VARIANT_ARG` and `VARIANT_COMPONENT` at 14, and `FAMILY_EQUALS`
at 15. In the nightly run (3,000 tries) every quota feature is at 7.5% or above (`BOOLEAN_HELPER_CALL`, 224);
`VARIANT_COMPONENT` is at 335, `LOOP_LOCAL_SHADOWS_FIELD` (decode-checked) at 111 and `BREAK` (statistics) at 77.

Commands and counts (G1 r4):
- `./gradlew :julc-difftest:test --rerun`: 194 tests, 0 failures, 0 errors, 0 skipped, in 86 s.
  - The G1 workload takes 15.5 s.
  - `RegressionCasesTest` replays 48 cases.
- `./gradlew :julc-difftest:nightlyDiffTest --tests '*G1PropertyTest' --rerun`: 3,000 tries, all conclusive at
  every level, 142 s.

## M3 part 1 results (2026-10-30, G1 r5)

M3 part 1 is everything in M3 that does not need ADR-067's implementation (PR #281). `GUARDS`, `RECORD_PATTERNS`,
`CROSS_TYPE_EQUALS` and `SHARED_VARIANT` stay defined and inert, and `G1GatesTest.inertGatesChangeNothing` pins them.

Implemented, in `julc-difftest` only:

- **`switch` expressions** (D2) over a value of a sealed family's type: a variable, or a component read from one.
  They can produce any requested type.
  - **Arms:**
    - type patterns (`case V q ->`), one per variant until guards;
    - arm order: a permutation of the variants, independent of the `permits` and declaration orders;
    - coverage: every variant, or (when the family has more than one) some variants followed by `default`, which is
      always last;
    - an arm is an expression, or, half of the time, a block of one or two statements (final locals, or a loop over
      an accumulator the arm owns) that ends in `yield`;
    - in a block, enclosing variables are read-only, so an arm never assigns them (ADR-048);
    - the arm-owned accumulator is, two times in three, a copy of the case-pattern binding: #162's positive form.
  - **Where switches appear:**
    - nested in arms, up to nesting 2;
    - as method-level locals and results;
    - as loop-body locals and assigned values (`SWITCH_IN_LOOP`);
    - as call and constructor arguments;
    - in helper bodies, whose arm loops are `while` loops because `xs` is not in scope there.
  - **Traces and failures:** arms are evaluated only when selected, so `fail(...)` may appear in them; traces appear
    in arms. Where traces are not allowed (a `compareTo` operand, `TRACE_IN_COMPARISON`), arms are expressions in the
    same trace-free context, never blocks.
  - **`break`:** in a loop body that contains `break`, an arm contains no loop (as G0's break-aware bodies). Loop
    bodies inside arms follow the same `break` rules as other loop bodies, `LOOP_BREAK_SHAPES` included.
- **Guard-eligible bindings:** case-pattern bindings and arm-local finals are guard-eligible, and arm-owned
  accumulators are not, ready for M3 part 2's guards.
- **D8 features with quotas:** `SWITCH`, `NESTED_SWITCH`, `YIELD_BLOCK`, `LOOP_IN_ARM`, `SWITCH_IN_LOOP`,
  `TYPE_PATTERN`, `DEFAULT_ARM`, and `BINDING_ACCUMULATOR` (#162's positive form).
- **`ASSIGN_PATTERN_BINDING` and `REDISCOVER_162`:**
  - The gate lets an arm's loop assign the case-pattern binding itself instead of copying it: #162's historical
    construct.
  - `REDISCOVER_162 = DEFAULT.with(ASSIGN_PATTERN_BINDING).steered(ISSUE_162)` does so in every arm loop that can.
  - The marker is `ASSIGN_PATTERN_BINDING`. `G1GatesTest` reads it from the source (a block arm that assigns its
    binding), and checks that the default profile never generates it and that the gate alone keeps the default
    distribution.
- **`G1MustRejectTest` (D6):** two fixed #162 programs (for-each and `while`, reading the binding after the loop or
  not), and ten programs generated under `REDISCOVER_162` with the construct. Each must be rejected at every level
  with #162's diagnostic ("Reassignment of switch case-pattern variable …", which has no code: `REJECT none`).
  `rediscoveryDiffTest -Pdifftest.profile=REDISCOVER_162` ends in that rejection at try 2 (0.8 s).
- **AST and printer:** a `Switch` node with its `Arm`s. Statements and expressions print at their indent, and a
  switch that is a method receiver is parenthesized.

Findings and decisions:
1. **A method called on a switch expression is rejected** (JULC0055, #290). G1 generates no `switch` inside a method
   receiver (the left side of `add`, `subtract`, `multiply` or `compareTo`), and "Current behavior" lists it under
   "rejected". It fails closed.
2. **An oracle limitation: javac 21.0.3 and 25.0.2 emit unverifiable bytecode.** This valid program fails JVM
   verification ("Inconsistent stackmap frames"), with plain `java Probe.java` as well:

   ```java
   if (switch (x) { case A0 f1 -> f1.x(); default -> c; }) { return c; }
   return x.equals(switch (x) { case A0 p -> new B0(); default -> new B0(); });
   ```

   It needs a pattern switch used as a test (an `if` or ternary condition) followed by a pattern switch passed as an
   argument. Each alone runs, and so do a switch inside `&&` and a switch assigned to a local. OpenJDK JDK-8358801
   (a pattern `instanceof` in a conditional, fixed in 2025) is a similar but different report.
   - `JvmExecutor` now turns a `LinkageError` from javac's class into a `HarnessException` ("the JVM rejects javac's
     class"), an oracle fault (ADR-063 H6), instead of letting it escape or be read as a JVM failure.
     `JvmExecutorTest.aClassTheJvmRejectsIsAHarnessError` pins this.
   - G1 generates no `switch` inside a test (an `if`, `break` or ternary condition). Switches stay in value
     positions.
3. **F62 extended:** the property tests stop counting coverage after any failing try, a `HarnessException`
   included, not only an assertion.
4. **The #209 steer** is integers-only a third of the time, so that both its silent and its failure forms are found
   within the PR budget.

No other mismatch was found by the default-profile campaigns. Every case of every run was conclusive at every
level:
- the PR run;
- three nightly runs of 3,000 tries during development (the first two stopped on the two findings above);
- a final nightly run of 3,000 tries.

Quotas (G1 r5):

| Feature | PR, 150 tries | Nightly, 3,000 tries |
|---|---|---|
| `SWITCH` | 21 (14.0%) | 641 (21.4%) |
| `NESTED_SWITCH` | 7 (4.7%) | 283 (9.4%) |
| `YIELD_BLOCK` | 21 (14.0%) | 548 (18.3%) |
| `LOOP_IN_ARM` | 18 (12.0%) | 486 (16.2%) |
| `SWITCH_IN_LOOP` | 7 (4.7%) | 205 (6.8%) |
| `TYPE_PATTERN` | 21 (14.0%) | 641 (21.4%) |
| `DEFAULT_ARM` | 15 (10.0%) | 328 (10.9%) |
| `BINDING_ACCUMULATOR` | 15 (10.0%) | 412 (13.7%) |
| `RECORD` | 82 (54.7%) | 1837 (61.2%) |
| `SEALED` | 79 (52.7%) | 1675 (55.8%) |
| `TWO_FAMILIES` | 39 (26.0%) | 787 (26.2%) |
| `ZERO_COMPONENTS` | 65 (43.3%) | 1230 (41.0%) |
| `NESTED_RECORD` | 43 (28.7%) | 1125 (37.5%) |
| `SEALED_COMPONENT` | 14 (9.3%) | 362 (12.1%) |
| `RECORD_ARG` | 30 (20.0%) | 663 (22.1%) |
| `SEALED_ARG` | 8 (5.3%) | 338 (11.3%) |
| `RECORD_RESULT` | 39 (26.0%) | 783 (26.1%) |
| `SEALED_RESULT` | 36 (24.0%) | 469 (15.6%) |
| `COMPONENT_ACCESS` | 41 (27.3%) | 986 (32.9%) |
| `CHAINED_ACCESS` | 11 (7.3%) | 398 (13.3%) |
| `STATIC_FIELD` | 60 (40.0%) | 1504 (50.1%) |
| `FIELD_FROM_FIELD` | 13 (8.7%) | 370 (12.3%) |
| `FIELD_SHADOWED` | 23 (15.3%) | 706 (23.5%) |
| `RECORD_EQUALS` | 32 (21.3%) | 691 (23.0%) |
| `RECORD_ACCUMULATOR` | 36 (24.0%) | 880 (29.3%) |
| `INSTANCEOF_PATTERN` | 10 (6.7%) | 249 (8.3%) |
| `METHOD_LEVEL_IF` | 44 (29.3%) | 828 (27.6%) |
| `HELPER_CALL` | 14 (9.3%) | 319 (10.6%) |
| `TRACE` | 9 (6.0%) | 298 (9.9%) |
| `FAILURE` | 9 (6.0%) | 282 (9.4%) |
| `VARIANT_ARG` | 14 (9.3%) | 402 (13.4%) |
| `VARIANT_RESULT` | 13 (8.7%) | 392 (13.1%) |
| `VARIANT_ACCUMULATOR` | 25 (16.7%) | 609 (20.3%) |
| `VARIANT_COMPONENT` | 14 (9.3%) | 294 (9.8%) |
| `BOOLEAN_HELPER_CALL` | 9 (6.0%) | 250 (8.3%) |
| `PERMITS_PERMUTED` | 7 (4.7%) | 288 (9.6%) |
| `FAMILY_EQUALS` | 15 (10.0%) | 374 (12.5%) |
| `LOOP_LOCAL_SHADOWS_FIELD` (decode-checked; saved case) | 2 (1.3%) | 91 (3.0%) |
| `LOOP` (statistics, no quota) | 49 (32.7%) | 1307 (43.6%) |
| `BREAK` (statistics, no quota) | 3 (2.0%) | 58 (1.9%) |

Rediscovery (G1 r5). The command is `./gradlew :julc-difftest:rediscoveryDiffTest -Pdifftest.profile=<profile>
[-Pdifftest.form=…]`, with seed `20261030`, 150 tries and the 744ab54f compiler. The pins and counterfactuals are
re-recorded:

| Defect | Profile, form | First failing try | Time to first failure | JVM | UPLC, every level |
|---|---|---|---|---|---|
| #209 | `REDISCOVER_209` (and `form=value`) | 12 | 2.4 s (2.1 s) | `Value R0(5, 5, 5)` | `Value R0(-2, -2, -2)` (silent) |
| #209 | `REDISCOVER_209`, `form=failure` | 62 | 5.2 s | `Value R0(-5, false)` | `Failure UnConstrData: not ConstrData` |
| #280 | `REDISCOVER_280` | 2 | 1.1 s | `Value -5 []` | `Value -5 [k0]` |
| #146 | `REDISCOVER_146` | 35 | 2.9 s | `Value A0() [k0]` | `Value A0() [k0, k0]` |
| #162 | `REDISCOVER_162` | 2 | 0.8 s | `Value` | `Rejected` (#162's diagnostic, expected) |

Commands and counts (G1 r5):
- `./gradlew :julc-difftest:test --rerun`: 197 tests, 0 failures, 0 skipped, in 70 s (the G1 classes 14.4 s);
  `RegressionCasesTest` runs 48 cases.
- `./gradlew :julc-difftest:nightlyDiffTest --tests '*G1PropertyTest' --rerun`: 3,000 tries, all conclusive at
  every level, 135 s.

Deviations from the ADR:
- One arm per variant until guards: D2's "several arms per variant" needs guards (M3 part 2).
- `default` only when some variant has no arm; there is no `default` after every variant.
- No switch in method receivers (#290) or in tests (the javac limitation above).
- `BINDING_ACCUMULATOR` is a quota feature beyond D8's list.

Open for M3 part 2, once ADR-067 is below this PR:
- guards (with snapshots of accumulators);
- record patterns;
- cross-type `equals`;
- shared variants;
- `G1MustRejectTest` for #279 and #282;
- rediscovery of #275, #276, #277 and #279 on the pre-fix base.

## Revision history

- **r1** (2026-10-30): initial proposal.
- **r2** (2026-10-30, responds to the reviews of `5c358dfa`: the review comment F1–F10 and Codex's F11–F21):
  - F1 → two sealed families per program; `SHARED_VARIANT` switch for #279 (filed from the review); D3's rule for
    two parents; #279 in rediscovery and pins.
  - F2, F20 → traces (`t`) and failures (`fail`) in the grammar, verified to agree in "Current behavior"; traces in
    the observability check; the `EAGER` mutation operator.
  - F3 → `instanceof` patterns as a whole method-level condition.
  - F4, F16 → mutation operators defined over the emitted forms per level, with eligibility, kill accounting
    separate from crashes and exhaustion, an agreeing baseline, and targeted operators (`GUARD_BYPASS`,
    `BOOL_DECODE`, `PROJECTION`, `VAR_SWAP`, `EAGER`).
  - F5 → G1-I10 and the result representation check in D4.
  - F6 → zero-component records, single-variant sealed interfaces, sealed components, fields from fields, helper
    names from the shared pool.
  - F7 → #201 moved out of the motivating table; #47 and #50 in rediscovery (D10.1).
  - F8, F13 → tags always carried and validated, in pin identity and replay; no reflection fallback; permuted
    vectors.
  - F9, F14 → `G1E r1` is a finite, versioned subgrammar with pinned counts; observability edits have
    preconditions, witnesses and not-applicable accounting.
  - F10 → ADR-067 is referenced by PR rather than by a file link; rejected forms completed (switch statements,
    sealed hierarchies, `&&` instanceof); the `Object`-typed form of #277; residual risks paragraph.
  - F11 → must-reject checks (D6) and the positive analogue for #162; #162 rediscovery at a revert (D10.1).
  - F12 → guard-eligible bindings and accumulator snapshots (D2).
  - F15 → the twin's instrumentation contract and per-input observation check (D8, G1-I8).
  - F17 → explicit productions for the #209 composition, loops in arms, nested record patterns and
    identical-payload distinct types; rediscovery must identify the intended defect.
  - F18 → generation profiles, persisted and replayed (D6, G1-I5).
  - F19 → quota denominator is all tries; exercised quotas stated.
  - F21 → milestone order fixed (corpus rediscovery in M4); `nightlyDiffTest` runs both engines; Scalus decided
    for the nightly run in M6; the whole PR workload is measured with fixed bounds.
  - New: #280 (zero-parameter helpers, filed while revising) gets the `ZERO_ARG_HELPERS` switch and rediscovery.
- **r3** (2026-10-30, responds to round 2 at `480eff97`: the review comment F22–F27 and Codex's F31–F34, and the
  partial items of F1, F4, F11, F14, F15, F16, F17):
  - F22, F33 (F1) → D3 splits source validation from boundary validation; a two-parent record with different
    tags may live inside `m`, so #279 can be rediscovered, and its fixed form is must-reject.
  - F23 (F11) → `ASSIGN_PATTERN_BINDING`, a historical profile for #162; on a fixed compiler such runs must end in
    the expected rejection.
  - F24 (F4, F16) → a fixed operator × level applicability table; `GUARD_BYPASS` covers the PV11 bool `case`.
  - F25, F31 (F14) → `B`'s `boolean f` is read by guards and arms and varied in the inputs; unreachable arm
    combinations are excluded; reachability witnesses from the twin; precise `equals` replacements; a constant
    replacement for every field read, including after the shadowing scope; every targeted site needs a
    distinguishing edit.
  - F26 (F17) → the cause check per defect state: fixed by code, fixed by rejection, not yet fixed.
  - F27 → #201's status; the switch-statement message; #47/#50's fallback marked as weaker evidence.
  - F32 (F15) → arm entry and guard start recorded before evaluation; "threw" is distinct; `G1TwinTest` asserts
    counters directly.
  - F34 (F16) → `EAGER` is a defined rewrite that evaluates the unselected branch and keeps the selected result,
    for both forms, validated on a control pair before scoring.
  - New: #282 (record members, from ADR-067's author) is excluded from the grammar and joins the must-reject list
    with ADR-067 D9.
- **r4** (2026-10-30, responds to round 3 at `639324f0`: F35, F36 and Codex's F40, F41, and the remaining part of
  F31):
  - F35 → record members moved from "rejected" to "silently different (#282)", with the call-site split (only a
    member called on a constructor expression is rejected); "Related findings" reconciled.
  - F36, F31 → corpus inputs derived from the threshold partition ({−1, 1, 3} for `v` and `a`), so every admitted
    guard sequence has an input reaching each arm.
  - F40 → the tag table is keyed by (record, parent), serialized as `tag: Probe$A Probe$S 0`, with parent-aware
    lookup, deterministic order, and round-trip and identity tests for unequal tags.
  - F41 → for defects not yet fixed, a constrained counterfactual (alpha-renaming for #209, an unused parameter
    for #280) with equal JVM observations; discoveries stay provisional until the fix-based check.
- **r4, editorial** (after approval at `4da7964b`): F42 and F45 (P3) — D3's tag-table paragraph reordered and
  wrapped; the record-member call-site sentence narrowed to component accessors on constructor expressions.
  Status set to Accepted.
