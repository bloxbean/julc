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
  hierarchies (a sealed interface that extends another: "class inheritance is not supported on-chain").
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
- **G1-I5. Reproducibility.** A G1 case is a function of its plan, `G1.VERSION` and its generation profile
  (D6). Any change that decodes a plan differently bumps the version. The complete case (source, tags,
  arguments, features, profile) is deterministic, also for empty and truncated plans and during shrinking.
  G0's version and decoding do not change. (D1, D6)
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

G1 is a new class next to G0, sharing `Choices` and the plan model: a `List<Integer>` decoded left to right,
choice 0 always the smallest valid construct, shrinking by jqwik on the plan, no discards. G0's decoding is not
touched, so every saved G0 plan, the G0 corpus and G0's fuzz inputs keep their meaning.

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
| `LOOP_BREAK_SHAPES` | G0's T21 and T23 shapes inside G1 programs | #254, #255 | Both are fixed on the base |

**Must-reject checks.** A defect fixed by rejecting a construct cannot be found by the positive grammar. For
each such construct, G1 has a small fixed list of programs that must be rejected with the expected code at every
level (`G1MustRejectTest`):
- #162's case-pattern reassignment in a loop, rejected since its fix (`LoopBodyGenerator.java:130-137`);
- once ADR-067 lands: #279's shared variant (ADR-067 D6) and each record member kind (#282, ADR-067 D9).

In addition, the historical profiles of `ASSIGN_PATTERN_BINDING` and `SHARED_VARIANT` generate these constructs
for rediscovery (D10.1); a run under such a profile on a fixed compiler must end in the expected rejection. The
positive analogue of each construct is generated by the default grammar.

Turning a switch on is a one-line change to the default profile plus a `G1.VERSION` bump, and D10.1 records
that the campaign found the defect while it was present. G0 keeps generating the T21 and T23 shapes; its registry
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
| **M6** | CI (D11): PR workload with fixed bounds, nightly with both engines and the Scalus VM; ADR-063 and ADR-062 updates; results section here | M5 | PR and nightly jobs green with measured times |

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
