# ADR-066: Generator G1 — records, sealed interfaces, switch and fields

**Status:** Proposed (2026-10-30)
**Date:** 2026-10-30
**Label:** `semantic-consistency`
**Programme:** step S1 of [ADR-062](062-jvm-uplc-semantic-consistency-programme.md), as a generator stage of
[ADR-063](063-differential-jvm-uplc-test-harness.md) (D5's G1, and M2b's record mapping and field shadowing)
**Relates to:** [ADR-067](067-switch-guards-record-patterns.md) (switch guards, record patterns and cross-type
record `equals`), which G1 depends on for those constructs
**Stacked on:** #253 (ADR-063 D13); restacked on the ADR-067 PR before M3 (open question 1)

## Context

ADR-063's generator G0 produces loops over `BigInteger` values. It found ADR-062 T21 and T23, and G0's PR and
nightly campaigns, its exhaustive corpus and about 300,000 fuzzed evaluations have agreed since. Several silent
miscompiles in the same period were found by review or by hand-written probes, not by G0, because G0 never
generates the constructs they need:

| Defect | Construct | Found by |
|---|---|---|
| #209 | A local in an `if` branch of a loop body, shadowing a static field of another record type | Release review |
| #201 | `break` naming a label | Review of ADR-060 |
| #275 | `switch` case guards (`when`) are ignored | Probe while writing this ADR |
| #276 | Record patterns in `switch` cases are dropped, and `default` runs | Probe while writing this ADR |
| #277 | `equals` between two different record types with equal fields is true on-chain | Probe while writing this ADR |
| #166 (fixed) | A `boolean` record component bound by a switch case pattern was not decoded | Review of PR #165 |
| #162 (fixed) | Reassigning a switch case-pattern variable in a loop left stale field reads | Review of PR #157 |
| #47, #50 (fixed) | Switch case bindings collided with method parameters | Users |

Records, sealed interfaces, `switch` and fields are how validators model datums, redeemers and their checks, so
this is where a silent miscompile is most likely to change what a validator accepts. ADR-063 already plans this
work: D5 names G1 ("`switch` expressions over sealed interfaces and records; Optional; `JulcMap`"), and M2b
names the user-record value mapping (D4), "a local shadowing a field", method-level `if`/`else` and helper calls.
This ADR makes that plan concrete, and adds what the harness needs to show that G1 would find such defects.

**Risk:** low for the product, since nothing shipped changes, but correctness-sensitive as evidence (ADR-063,
"Risk"). The risk this ADR manages is false confidence: a generator that never produces the shape of a defect,
inputs that never take the branch where it shows, or a value mapping that is wrong in the same way as the
compiler.

## Goals and non-goals

**Goals**
- Generate well-typed programs over user records, sealed interfaces with record variants, `switch` expressions
  with type patterns, record patterns, guards and `default`, record component access, static fields, locals
  that shadow fields, record and sealed arguments and results, and record-typed loop state.
- Map record and sealed values between the JVM and Data independently of JuLC's encoders (ADR-063 H1, D4).
- Show, with recorded evidence, that the campaigns find this ADR's defect classes: rediscovery of the defects
  above, an exhaustive corpus with an observability check, and a mutation score.
- Keep G0, its saved plans and its corpus unchanged (ADR-063 H4).

**Non-goals**
- Fixing divergences. ADR-067 fixes #275–#277; #209 has its own fix.
- `Optional` and `JulcMap` (D5 lists them under G1). They have known differences that need their own decisions
  (open question 4) and are deferred to a stage G1b.
- Validator entry points, ledger types and script contexts (ADR-063's G3).
- A negative grammar for record constructs JuLC rejects (G1⁻); listed under "Related findings".
- `==` on records (ADR-062 T4) and `byte[]` components (T5): known differences, excluded by construction.

## Current behavior

All evidence in this section is from `fd605e77` (#253's head) with probe tests run through
`DiffRunner.standard()`, four optimization levels, `julc-vm-java`. The probe sources are not committed; their
programs are quoted in the PR.

**Harness.**
- `ValueMapping` (`julc-difftest/.../ValueMapping.java:41-70`, `:73-124`) maps `BigInteger`, `int`, `long`,
  `boolean`, `byte[]`, `String`, `PlutusData`, `Optional`, `JulcList` and `JulcMap`. A record or sealed value, as
  an argument or a result, is a `HarnessException` ("No Data mapping for …", `:68`, `:123`).
- `JvmExecutor` converts each argument with `ValueMapping.fromData` and the method's generic parameter type
  (`:58-64`), and the result with `toData` (`:84`). `UplcExecutor` passes the argument Data unchanged
  (`:110`) and maps the result term with `fromTerm` (`:116-126`).
- G0 is coupled to the registry and the property test: `KnownDivergences.Rule.requires` is a
  `Set<G0.Feature>` (`gen/KnownDivergences.java:71`), and `G0PropertyTest.verify` takes a `G0.Program` and builds
  G0's arguments and provenance (`G0PropertyTest.java:162-215`).
- There is no fault injection and no mutation measurement (ADR-063 D11 and M3 are not started).

**Compiler, for the constructs G1 needs.** Compiled by `compileMethod`:
- *Agree with javac:* record-typed and sealed-typed loop accumulators, including a `switch` that rebuilds the
  accumulator in a `while` with `break`; a ternary of record type and chained component access
  (`q.p().x()`); nested `switch` expressions and `yield` blocks; a `switch` value inside a loop body;
  `static final` record and `BigInteger` fields read in a loop; same-type record `equals`; method-level `if`
  with an early `return`, and `if`/`else` where both branches return; a loop inside a method-level `if`; a
  non-recursive helper method with record parameters; `boolean` locals and `boolean` components in conditions; a
  variant record typed as itself and then as its sealed type; and a local shadowing a `static final` field
  in an early-return branch, in a `switch` arm block, and directly in a loop body.
- *Rejected, so outside the positive grammar:* reassigning a variable outside a loop ("Unsupported expression:
  AssignExpr", ADR-062 T20), so method-level `if`/`else` cannot assign; a declaration without an initializer;
  `instanceof` without a binding in an expression ("Unsupported instanceof pattern"); compact and explicit
  canonical constructors, overridden accessors, record instance and static methods, and secondary constructors
  (JULC0055, JULC0016, "'this' is not supported on-chain" or "Undefined variable"); a sealed interface without
  `permits` whose variants are top-level siblings ("Unknown type").
- *Silently different:* #275, #276 and #277 above, at every level. A field shadowed by a local of another record
  type declared in an `if` branch inside a loop body is #209.

**Representation the mapping must match.** A standalone record is `Constr 0 [components]`; a variant of a sealed
interface is `Constr i [components]`, where `i` is its position in the `permits` clause, also when it is
constructed and typed as the variant itself (`TypeResolver.java:153-187`, `:283-290`;
`docs/src/content/docs/guides/advanced-guide.md:107-130`). Components are encoded as `IData`, `BData`, the Bool
constructors (`Constr 0 []` false, `Constr 1 []` true), `BData` of UTF-8 for `String`, or nested Data
(`PirHelpers.wrapEncode`, `PirHelpers.java:182-215`). A `compileMethod` parameter of record or sealed type is
passed through as Data without a shape check (`JulcCompiler.java:986-1005`); ADR-015's strict boundary applies
to validator entry points only. A record result comes back as a `DataConst`, which `fromTerm` already maps.

**javac.** `Class.getPermittedSubclasses()` returned the classes in `permits` order for
`sealed interface Z permits Z2, Z1, Z3` (JDK 25, probe). Its javadoc does not promise this: "The order of such
elements is unspecified" (`java.lang.Class.getPermittedSubclasses`, JDK 25 `src.zip`). D3 does not rely on it.

## Invariants

ADR-063's H1–H7 apply unchanged. In addition:

- **G1-I1. Well-typed and inside the subset.** Every G1 program compiles with javac (H6) and is accepted by JuLC
  at every level. A JuLC rejection of a G1 program is a finding, not a skip (H7). (D2, D4)
- **G1-I2. Termination by construction.** Loops are G0's (bounded counters, finite `xs`), helpers are
  non-recursive, record types are acyclic, and every size is bounded. (D2)
- **G1-I3. Independent mapping.** The record and sealed mapping uses only Java reflection and the rule stated in
  D3. It never calls JuLC's encoders, decoders or type registry. (D3)
- **G1-I4. Explicit constructor tags.** The tag of every generated variant is taken from the generator's own
  type declarations, not from reflection order. (D3)
- **G1-I5. Reproducibility.** A G1 program is a function of its plan and `G1.VERSION`; any change that decodes a
  plan differently bumps the version (H4). G0's version and decoding do not change. (D1)
- **G1-I6. Exclusions are explicit.** A construct left out because of an open defect is listed in D6 with its
  issue, gated by one named switch, and checked by a test that fails if the generator emits it while gated. (D6)
- **G1-I7. Every arm can be observed.** In the exhaustive corpus, each `switch` arm, each guard outcome and each
  record-pattern binding is shown to affect some observation on the chosen inputs. (D7)
- **G1-I8. Mutation sensitivity is measured, not assumed.** The mutation score of D10 is recorded per operator,
  and every surviving mutant from the exhaustive corpus is classified. (D10)

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
the entry method `m`.

**Types.** Per program, chosen by the plan:
- zero to two standalone records, and zero or one sealed interface with two or three record variants and an
  explicit `permits` clause, all nested in `Probe`;
- one to three components each, of type `BigInteger`, `boolean`, or a record or sealed type declared earlier
  (acyclic, nesting depth at most 2);
- component names from a small shared pool (`v`, `w`, `x`, `f`, `p`), so that names repeat across records,
  components, locals and fields. This is where #47 and #50 lived.

**Fields.** Zero to two `static final` fields of `BigInteger` or a declared record type, initialized with a
constant constructor expression (no method calls: JULC0057). Field names come from the local-name pool, so a
local can shadow one (D6 gates the loop-branch case).

**Entry method.** `public static R m(params)`, where:
- the parameters are `a`, `b` (`BigInteger`) and `xs` (`JulcList<BigInteger>`) as in G0, plus zero to two
  parameters of declared record or sealed types;
- the return type `R` is `BigInteger`, `boolean`, or a declared record or sealed type.

**Statements.**
- Declarations of any generated type, with an initializer.
- G0's loops: `while` with a bounded counter and for-each over `xs`, nested at most two deep; in loop bodies,
  assignments, `if`/`else` with assignments, `if (c) break;` and locals. Accumulators may be of any generated
  type, so a loop can rebuild a record or switch over a sealed accumulator.
- Method-level `if` whose branch declares locals and returns, and `if`/`else` where both branches return. No
  method-level reassignment (rejected, ADR-062 T20).
- Zero to two non-recursive static helper methods with generated bodies of the same grammar, without loops that
  update fields (JULC0056), called from `m` or from each other in declaration order.

**Expressions,** generated for a requested type:
- `BigInteger`: G0's expressions (constants −5..5, `add`, `subtract`, `multiply` by 1–3, ternary), component
  access, chained access and helper calls;
- `boolean`: comparisons, `&&`, `||`, `!`, `boolean` components, `boolean` locals, and `equals` between two
  values of the same record type;
- a record type: its constructor, a variable, a component of that type, a ternary, a helper call, or a `switch`;
- a sealed type: one of its variants' constructors, a variable, a component, a ternary or a `switch`.

**`switch` expressions,** over a value of the sealed type, producing any requested type:
- labels: type patterns (`case A q ->`), record patterns (`case A(var v, B(var w, var f)) ->`, nested at most
  one level, with `var` or explicit component types), and `default`;
- guards (`when` with a generated `boolean` over the bindings, locals and fields);
- arms as expressions, or as blocks that declare locals and `yield`;
- several arms per variant (guarded arms first), arms in any order javac accepts (the generator emits only
  orders that javac's dominance rules allow), and exhaustive coverage either by every variant or by `default`;
- nested `switch` expressions, and `switch` inside loop bodies as a value (never assigning an enclosing
  variable from an arm, which ADR-048 rejects).

Record patterns, guards and cross-type `equals` (between two declared types that cannot be the same class) are
generated only on a compiler that implements ADR-067; until then their generation switch is off (D6).

**Bounds:** at most 5 top-level statements, 3 statements per loop body, expression depth 2, `switch` nesting 2,
3 arms per variant, comparison nesting bounded as G0 r2 (`MAX_COMPARISON_NESTING`, #146).

**Excluded by construction:** `==` and `!=` on non-numeric values (T4), `byte[]` and `String` components (T5,
T17), `int` and `long` arithmetic other than counters (T6), division and `mod` (T11), `Optional`, `JulcMap`,
traces (G1b), `@Param` fields (they become outer parameters that the harness would have to supply), and every
construct listed as rejected in "Current behavior".

### D3. Record and sealed values in the value mapping (ADR-063 D4)

`ValueMapping` gains user records and sealed interfaces, in both directions, by this rule:
- a record value maps to `ConstrData(tag, [components in declaration order, each mapped recursively])`;
- `tag` is the record's position in its sealed parent's `permits` clause if it implements a sealed interface,
  and 0 otherwise. This holds whatever the static type at the use site (the variant-typed case above);
- `boolean` components map to the Bool constructors, as at the top level.

For a sealed parameter type, `fromData` chooses the variant whose tag equals the Constr tag, and fails with a
`HarnessException` if none does or if the arity differs.

The tag does not come from `getPermittedSubclasses()` order (G1-I4). G1 knows each variant's position in the
`permits` clause it printed, and the `DiffCase` carries that table as a new optional field, `tags` (binary class
name to tag), saved in `.diffcase` format 3 as `tag: Probe$A 0` lines. `ValueMapping` takes the tag from that
table. A hand-written case without `tags` falls back to `getPermittedSubclasses()` order, and only if that order
matches a check of the source's `permits` clause done by the test author; the regression store records which
source was used.

*Alternatives:* `getPermittedSubclasses()` order alone matches on JDK 25 (probe) but is unspecified (javadoc); deriving tags by parsing the source on the JVM side would add a second Java parser to the trusted
components; asking JuLC's `TypeResolver` for tags would violate H1. ReflectivePlutusDataConverter in
`julc-cardano-client-lib` uses the same encoding rule and is a useful cross-check in tests, but it is not used
by the harness (it is product code under test elsewhere).

The mapping stays trusted (ADR-063 "Trusted components") and gets its own tests against hand-written Data:
standalone records, variants typed as themselves and as the sealed type, nested records, `boolean` components,
round trips through `fromData` and `toData`, and the error cases.

### D4. Arguments and results

- **Arguments.** Record and sealed arguments are generated as values of the generated types, mapped to Data by
  D3, and passed to both sides: the JVM side rebuilds them with `fromData`, the UPLC side receives the Data. Only
  well-formed values are generated; malformed boundary Data is G3's subject (ADR-062 T16).
- **Inputs** cover each variant of each sealed argument, and values on both sides of each generated guard's
  comparisons, by drawing integers from −6..6 as G0 does and variants uniformly. D7 checks the effect for the
  corpus; D8 measures it for random campaigns.
- **Results.** A record or sealed result maps by D3 on the JVM side and by `fromTerm` on the UPLC side; a
  difference in a tag or a component is a value mismatch like any other.

### D5. Harness changes

- **Features as names.** `KnownDivergences.Rule.requires` and the property-test plumbing take feature names
  (`Set<String>`), as `Coverage` already does, so rules can match G0 and G1 features. G0's features keep their
  names, so existing rules and saved cases are unchanged.
- **A generator-neutral `verify`.** `G0PropertyTest.verify` becomes a shared helper over
  `(source, className, method, args, features, generator version, plan)`. G0's behaviour and its D8 records are
  unchanged.
- **New tests.** `G1PropertyTest` (PR: fixed seed, 150 tries; nightly: fresh seed, 3,000 tries, tag `nightly`),
  `G1ExhaustiveTest` (D7), `G1FuzzTest` (Jazzer, as D13: bytes to arguments then plan; committed seeds;
  nightly job), and `G1SelfCheckTest` (D10).
- **Regression store.** `.diffcase` format 3 adds the optional `tag:` lines of D3. Format 2 files are read
  unchanged.

### D6. Known open defects: generation switches

A construct whose defect is open is not generated, so that campaigns stay meaningful (an unexplained wrong value
fails the run, and ADR-063 D6 gives wrong values no rule). Each switch names its issue and is a constant in
`G1.Gates`; `G1GatesTest` checks that the generator never emits a gated construct while its switch is off.

| Switch | Construct | Issue | Off until |
|---|---|---|---|
| `GUARDS` | `when` guards | #275 | ADR-067 is implemented below this PR |
| `RECORD_PATTERNS` | Record patterns | #276 | ADR-067 is implemented below this PR |
| `CROSS_TYPE_EQUALS` | `equals` between distinct record types | #277 | ADR-067 is implemented below this PR |
| `SHADOW_IN_LOOP_BRANCH` | A local in an `if` branch inside a loop body shadowing a field of another type | #209 | #209 is fixed on the base |
| `LOOP_BREAK_SHAPES` | G0's T21 and T23 shapes inside G1 programs | #254, #255 | Both are fixed on the base |

G0 keeps generating the T21 and T23 shapes; its registry entries are unchanged. Turning a switch on is a
one-line change plus a `G1.VERSION` bump, and D10's rediscovery evidence shows that the campaign finds the
defect while it is still present.

### D7. Exhaustive corpus `G1E`

A bounded enumeration, as ADR-063 D7 does for loops, over the shapes where the defects above live:
- **switch shapes:** a sealed type with two variants, `A(BigInteger v)` and `B(BigInteger v, boolean f)`; one or
  two arms per variant from {type pattern, record pattern} × {no guard, guard on a binding, guard on a local},
  with or without `default`, in every order javac accepts; inside or outside a loop;
- **field shapes:** a `static final` field read after a local of the same name and another record type is
  declared in a method-level branch, a `switch` arm block, a loop body, and (gated, D6) a loop `if` branch;
- **equality shapes:** same type, same sealed family, cross type (gated), variant against its sealed type;
- **boundary shapes:** each sealed argument variant, and record and sealed results.

Inputs: each variant of the selector, with component values on both sides of each guard's threshold. The
observability check runs on the JVM only: removing one guard, swapping two arms, replacing a record pattern's
binding by a constant, or replacing a field read by the shadowing local must change some observation on the
inputs; otherwise the corpus or its inputs are too weak and the test fails (G1-I7). The corpus size and run time
are measured in M4 and recorded here; the target is under 60 s on the PR run.

### D8. Coverage and exercised coverage

**Syntactic features** (quotas as ADR-063 D9: at least 3% of conclusive PR tries each): `RECORD`, `SEALED`,
`NESTED_RECORD`, `RECORD_ARG`, `SEALED_ARG`, `RECORD_RESULT`, `SEALED_RESULT`, `COMPONENT_ACCESS`,
`CHAINED_ACCESS`, `STATIC_FIELD`, `FIELD_SHADOWED`, `RECORD_EQUALS`, `SWITCH`, `NESTED_SWITCH`, `YIELD_BLOCK`,
`SWITCH_IN_LOOP`, `RECORD_ACCUMULATOR`, `TYPE_PATTERN`, `DEFAULT_ARM`, `METHOD_LEVEL_IF`, `HELPER_CALL`, and,
once their switches are on, `RECORD_PATTERN`, `NESTED_RECORD_PATTERN`, `GUARD`, `CROSS_TYPE_EQUALS`.

**Exercised coverage** (ADR-063 M2b's open item, for G1): G1 prints, alongside each program, a JVM-only twin
in which every `switch` arm and every guard records which way it went through a harness-owned static counter
(not `Builtins.trace`, so the compared program is unchanged). The twin runs on the JVM for each input, and the
counts give: arms taken, guards true and false, loop iteration counts (zero, one, many), and early versus normal
loop exits. Quotas apply to taken arms, guard outcomes in both directions and loops with zero, one and several
iterations. The twin is never compiled by JuLC, and the comparison uses the untouched program.

### D9. Known divergences and pins

ADR-063 D6 applies. #275, #276 and #277 get saved `DIVERGE` cases (pins) on the base before ADR-067's fix and
flip to `AGREE` with it, in the ADR-067 PR. A G1 mismatch is never explained by a rule unless the UPLC side is
inconclusive; the existing T13 rule (`DEEP_COMPARISON`) applies to G1 programs with the same marker.

### D10. Showing that G1 finds what it should

ADR-063 D11 asks for rediscovery and mutation before a harness counts as evidence. For G1:

1. **Rediscovery, recorded per milestone.** With the relevant switch forced on, the G1 PR campaign and `G1E`
   must report, on a compiler that still has the defect:
   - #275, #276 and #277, on the base below ADR-067's fix;
   - #209, on the current base;
   - #166 and #162, on a scratch revert of their fixes.
   The command, commit, seed, time to first failure and the shrunk reproducer are recorded in this ADR. A
   defect that the campaign does not find within the PR budget is a generator gap and blocks the milestone.
2. **Mutation score, in CI.** `G1SelfCheckTest` (nightly, and on the PR for a fixed sample) applies mutation
   operators to the compiled UPLC of `G1E` programs before evaluation, through a test-only transform in
   `UplcExecutor` (no compiler change): swap two branches of a `Case`; change a `Constr` tag; swap two fields of
   a constructed `Constr`; swap the branches of an `ifThenElse`; replace an `EqualsData` result by `True`;
   replace a field projection by its neighbour. A mutant is killed when some input's observation differs from
   the JVM's. The score per operator is reported; M5 measures it and records a floor per operator, below which the
   test fails. Every surviving mutant from the corpus is listed and classified (equivalent, or a gap with a
   corpus or input fix).
3. **Compiler-level fault injection** (ADR-063 open question 2) stays open; D10.1 and D10.2 do not need it.

*Alternatives:* trusting the generator's feature counts alone shows the constructs were generated, not that a
defect in them would be seen (#209 and #275 show the gap). Mutating Java source instead of UPLC would mostly
produce programs that javac or JuLC reject, or that change both sides equally.

### D11. CI

- PR (`./gradlew build`): G1 regression cases, `G1E`, the fixed-seed property (150 tries), the mapping tests,
  `G1GatesTest`, and the fixed mutation sample. Target added time: under 2 minutes, measured in M2 and M4.
- Nightly (`difftest-nightly.yml`): the 3,000-try G1 property with a fresh seed, `G1FuzzTest` for 20 minutes,
  and the full mutation run; failures upload `build/difftest/`.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Hand-written javac-oracle tests per construct (as `LoopBreakContinuationTest`) | Necessary for each fix, but scenario by scenario; ADR-062 records that this has not converged |
| Wait for ADR-061's typed frontend representation and generate against it | ADR-061 is a proposal; G1 needs only source text and is useful now |
| Random Java text filtered by javac | Rejected in ADR-063 for G0; the same reasons apply |
| Generate everything, rely on pins for open defects | Pins are exact (ADR-063 D6): every new program in a defect's shape fails the run, so campaigns stop being informative until the fix lands |
| Reflection order for sealed tags | Matches today but is unspecified (D3) |

## Affected modules

- `julc-difftest` only: `gen/G1` and its printer, `ValueMapping`, `DiffCase` and `RegressionStore` (format 3),
  `KnownDivergences` (feature names), `UplcExecutor` (test-only transform hook for D10), new tests and seeds.
- `.github/workflows/difftest-nightly.yml`: G1 jobs.
- ADR-063: D5 and M2b point here for G1 and the items G1 covers; ADR-062: S1 status.
- No change to the compiler, stdlib, VMs or any published artifact.

## Compatibility

None for users. Inside the harness: G0 plans, saved cases and corpus are unchanged; `.diffcase` format 2 files
are read as before; the registry rules keep their behaviour with feature names.

## Risks

- **A wrong mapping that agrees with a wrong compiler.** Mitigated by G1-I3, G1-I4, hand-written mapping tests
  and the cross-check against `ReflectivePlutusDataConverter`.
- **Gates that outlive their defects.** Each gate names its issue; the ADR-067 PR and the #209 fix are
  responsible for turning theirs on, with a `G1.VERSION` bump.
- **PR run time.** Records and switches compile and evaluate more slowly than G0 programs; budgets are measured
  in M2 and M4, and campaign sizes stay Gradle properties.
- **#146 growth.** Comparisons inside records and switches can nest; G1 uses G0 r2's bound and the T13 rule.
- **Equivalent mutants.** Some mutants cannot change any result; D10 classifies survivors rather than demanding
  a perfect score.

## Implementation milestones

| Milestone | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| **M1** | D3, D4 mapping; D5 harness generalization (feature names, shared `verify`, format 3) | ADR accepted | Mapping tests against hand-written Data (G1-I3, G1-I4); all G0 tests unchanged and green; G0 plan → program identical for 20,000 random plans |
| **M2** | G1 r1 grammar without `switch` (types, fields, arguments, results, loops, method-level `if`, helpers, same-type `equals`); `G1PropertyTest`, `G1GatesTest`, `G1FuzzTest` | M1 | G1-I1, G1-I2, G1-I5, G1-I6; quotas met from conclusive cases; PR time measured; every mismatch found is fixed, pinned with an issue, or gated |
| **M3** | `switch`: type patterns, `default`, `yield` blocks, nesting, inside loops; then guards, record patterns and cross-type `equals` once ADR-067 is in the stack below | M2; for the gated part, ADR-067's implementation | Quotas; rediscovery of #275, #276 and #277 on the pre-fix base (D10.1) |
| **M4** | `G1E` with the observability check; exercised coverage (D8) | M3 | G1-I7; corpus size and time recorded; exercised quotas met |
| **M5** | D10: rediscovery evidence for #209, #166 and #162; mutation operators and floors; survivor classification | M4 | G1-I8; floors recorded here; no unclassified survivor |
| **M6** | CI wiring (D11), ADR-063/ADR-062 updates, results section here | M5 | PR and nightly jobs green with measured times |

## Verification strategy

- **Independent oracle:** javac and the JVM (ADR-063); nothing in G1's verdict comes from JuLC's own output.
- **Hand-written vectors** for the mapping (D3), written from the documented encoding, not from JuLC output.
- **Rediscovery** of real defects at real commits (D10.1), recorded with commands and seeds.
- **Mutation score** with classified survivors (D10.2).
- **Exhaustive corpus** with an observability check (D7), and exercised-coverage quotas (D8).
- **Negative cases:** gate tests (G1-I6) and mapping error tests.
- **Review:** each milestone is reviewed independently (including Codex) before the next starts.

## Related findings (out of scope)

- Record constructs that JuLC rejects do so with uncoded or misleading diagnostics: a compact constructor that
  normalizes a component and an overridden accessor report JULC0055 "Cannot resolve x"; a static method on a
  record reports "Undefined variable: P"; an explicit canonical constructor reports "'this' is not supported
  on-chain"; a sealed interface without `permits` over sibling records reports "Unknown type: T". These belong
  to a G1⁻ negative grammar and to coded diagnostics (ADR-062 T20's family); an issue should be filed.
- `JulcMap` results cannot be mapped by `ValueMapping.fromTerm` today (a native pair list, no `PairConst` case),
  and `JulcMap.get` of a missing key returns `null` on the JVM but fails on-chain. Both are G1b's subject.

## Open questions

1. **Order of the stack.** G1's M3 needs ADR-067's implementation below it. Lean: keep the stack #253 → ADR-067
   → ADR-066 and implement ADR-067 first; G1's M1 and M2 do not need it and can proceed in parallel.
2. **Tag source (D3).** Carry tags in the `DiffCase` (as proposed) or accept `getPermittedSubclasses()` order
   with a startup self-test? Lean: carry them; the cost is a format bump.
3. **Mutation floors (D10.2).** Fixed numbers now, or measured in M5? Lean: measured in M5, then fixed.
4. **G1b scope.** `Optional` and `JulcMap` need decisions on `get` of a missing key, key equality (T10) and
   map results. Lean: a short ADR-063 revision when G1 is done.
5. **Helpers.** JuLC binds helper methods through a different path (library-style method bindings); should G1
   weight helper calls higher to cover it? Lean: measure in M2 first.
6. **VMs.** Lean: `julc-vm-java` on PRs, Scalus added in the nightly G1 run (ADR-063 open question 4).

## Revision history

- **r1** (2026-10-30): initial proposal.
