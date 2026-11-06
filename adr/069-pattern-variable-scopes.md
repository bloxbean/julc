# ADR-069: Pattern-variable scopes: `instanceof` beyond the plain `if`

**Status:** Proposed
**Date:** 2026-11-06
**Risk class:** correctness-sensitive, **medium**. Every shape this ADR adds is rejected today, so no program that
compiles now changes. The risk is in the new lowering: a pattern variable read on a path where its test failed reads
another variant's fields, and a validator built that way can accept a datum its own Java tests reject.
**Issue:** [#204](https://github.com/bloxbean/julc/issues/204)
**Relates to:** [ADR-068](068-javac-frontend-migration.md) (javac frontend; `Scope` is keyed by javac's elements),
[ADR-050](050-if-loop-continuations.md) (continuations and joins for `if` and loops),
[ADR-060](060-generated-binder-namespace.md) (generated names), [ADR-063](063-differential-jvm-uplc-test-harness.md)
(differential harness), [ADR-066](066-generator-g1-records-sealed-switch.md) (generator G1),
[ADR-067](067-switch-guards-record-patterns.md) (switch guards; `JULC0074`)

## Context

Java's `instanceof` with a type pattern tests a value and names it in one step:

```java
if (shape instanceof Circle c) {
    return c.radius();
}
```

Java defines *where* `c` may be used by flow rules (JLS 6.3.1, 6.3.2): on the right of `&&`, in the arms of `?:`,
after `!`, in the rest of a block after `if (!(x instanceof T v)) return ...;`, inside loops, and in lambdas. javac
checks these rules and resolves every use of `c` to its declaration.

JuLC lowers exactly one of these forms: an `if` statement, outside a loop body, whose whole condition is
`x instanceof T v`. Everything else is rejected. Nothing is miscompiled: a rejected program is refused, never built.
But the rejections hit common validator code, and the message is misleading (`Undefined variable: c`, for code
that is valid Java).

This has been so since the first frontend. The JavaParser frontend at `b5ff39a8` (release 0.1.0-pre20) rejected a
bare type test with the hint "instanceof is supported only with sealed interface variants:
`if (x instanceof Variant v) { ... }`", and its `LoopBodyGenerator` lowered an `if` condition in a loop body as a
plain boolean. The javac frontend ported both limits one for one (ADR-068 parity).

### Current behaviour in detail

Probe on `main` at `3b6d7f5a` (scratch difftest, all four levels, sealed `Sh permits Sq, Ci`; uncommitted). Every
row except a01, a05 and n08 is rejected identically at every level:

| Id | Shape | Today |
|---|---|---|
| a01 | `if (s instanceof Sq q) { return q...; }` | compiles, agrees with the JVM |
| a05 | `if (s instanceof Ci c) {...} else if (s instanceof Sq q) {...}` | compiles, agrees |
| a02 | `if (s instanceof Sq q && q.s() > 1)` | `JULC0011 Undefined variable: q` |
| a03 | `if (!(s instanceof Sq q)) { return 0; } return q...;` | `JULC0011` |
| a04 | `if (!(s instanceof Sq q) \|\| q.s() == 0) { return 0; } return q...;` | `JULC0011` |
| a06 | `long v = s instanceof Sq q ? q.s() : 5;` | `JULC0011` |
| a07, a07b | `return s instanceof Sq q && q.s() > 1 ? 1 : 0;`, the same into a `boolean` local | `JULC0011` |
| a08 | `shapes().any(x -> x instanceof Sq q && q.s() > 5)` | `JULC0011` |
| b01–b06, b08 | `if (s instanceof Sq q) {...}` inside `for`, `while`, nested loops, with `break`, `else if`, `?:` | `JULC0011` |
| c01 | `while (!(s instanceof Ci c)) {...} return c...;` | `JULC0011` |
| n01–n07 | `if (s instanceof Sq)` (no variable), in or out of a loop, negated, `else if` | `JULC0077 Unsupported instanceof pattern` |
| n08 | `switch (s) { case Sq q -> ...; case Ci c -> ...; }` in a loop | compiles, agrees |

With a field named like the pattern variable, every row is `JULC0059` instead (the existing guard, unchanged here).

Where the code stands (`main` at `2b705c38`):

- `Ifs.patternIf` is the only place a pattern variable is bound. `Ifs.pattern(condition)` matches only a condition
  that *is* an `instanceof` with a binding pattern.
- `Operators.binary` lowers `&&` and `||` as `IfThenElse(left, right, false)` and `IfThenElse(left, true, right)`,
  `Operators.unary` lowers `!`, and `Operators.conditional` lowers `?:`. Each lowers its operands in the same frame,
  so a variable bound on one side is never visible on the other.
- `LoopBody.foldIf`, `breakingIf` and `joined` lower a loop body's `if` condition with `lowering.expr` and never bind.
- `TypeTests.variantOf` reads the tested type only from a binding pattern, so a bare `x instanceof T` is `JULC0077`
  outside a `switch` guard. `TypeTests.guardVariantOf` already reads it from `instanceof`'s type in a guard.
- `Names.identifier` reports a `BINDING_VARIABLE` with no binder as `JULC0011`.
- A switch guard that introduces a variable for its arm is `JULC0074` (`GuardRules.requireNoBinding`, ADR-067).

## Goals and non-goals

**Goals**

- G1. Accept the shapes in D1–D4 with Java's meaning: the same value, the same failures and the same traces as the
  JVM, at every optimisation level.
- G2. Keep every program that compiles today byte-identical.
- G3. Give every shape that stays unsupported one coded error that names the variable and shows a rewrite that
  compiles.
- G4. Keep the lowering linear in the source: no continuation is copied once per path.

**Non-goals**

- Bindings introduced by a `while` statement for what follows it (JLS 6.3.2.3 second rule; row c01), and a `while`
  condition's bindings used in its body (JLS 6.3.2.3 first rule). Both need the loop desugaring to carry a binding
  out of or into its recursive function; they stay rejected, under D6's code.
- Switch-guard bindings (`JULC0074`). D3's lowering could serve them later; ADR-067's rules stay as they are.
- Record (deconstruction) patterns in `instanceof` (`JULC0077`), `do` and basic `for` statements (unsupported in the
  subset), and labeled statements.
- Lifting `JULC0059` (a pattern variable named like a field). See Q2.
- Unchecked reference casts (R1).

## Pinned normative references

- **JLS SE 25**, *The Java Language Specification, Java SE 25 Edition*, chapter 6, fetched 2026-11-06 from
  `docs.oracle.com/javase/specs/jls/se25/html/jls-6.html`:
  - **6.3.1** "Only certain kinds of boolean expressions are involved in introducing pattern variables ... If an
    expression is not a conditional-and expression, conditional-or expression, logical complement expression,
    conditional expression, `instanceof` expression, `switch` expression, or parenthesized expression, then no scope
    rules apply."
  - **6.3.1.1** `a && b`: "A pattern variable introduced by `a` when true is definitely matched at `b`. A pattern
    variable is introduced by `a && b` when true iff either (i) it is introduced by `a` when true or (ii) it is
    introduced by `b` when true." There is no rule for `a && b` when false.
  - **6.3.1.2** `a || b`: "A pattern variable introduced by `a` when false is definitely matched at `b`. A pattern
    variable is introduced by `a || b` when false iff either (i) it is introduced by `a` when false or (ii) it is
    introduced by `b` when false." There is no rule for `a || b` when true.
  - **6.3.1.3** `!a`: introduced when true iff introduced by `a` when false, and the reverse.
  - **6.3.1.4** `a ? b : c`: "A pattern variable introduced by `a` when true is definitely matched at `b`. A pattern
    variable introduced by `a` when false is definitely matched at `c`." There are no rules for introducing a
    variable by `a ? b : c`.
  - **6.3.1.5** `a instanceof p`: introduced when true iff `p` declares it; no rule when false.
  - **6.3.1.7** `(a)`: the same as `a`.
  - **6.3.2.1** Blocks: "A pattern variable introduced by S is definitely matched at all the block statements
    following S, if any, in the block."
  - **6.3.2.2** `if (e) S`: introduced by `e` when true is definitely matched at S; introduced by the statement iff
    "(i) it is introduced by `e` when false and (ii) S cannot complete normally". `if (e) S else T`: when true at S,
    when false at T; introduced by the statement iff "introduced by `e` when true, and S can complete normally, and T
    cannot complete normally; or introduced by `e` when false, and S cannot complete normally, and T can complete
    normally".
  - **6.3.2.3** `while (e) S`: when true at S; introduced by the statement iff introduced by `e` when false and S has
    no reachable `break` to it.
- **JLS SE 25 15.20.2** (`instanceof`): the operand is evaluated once, then tested; the pattern variable is
  initialised to the operand's value when the test succeeds.

One consequence carries the design. **In every rule, a continuation that can be reached from more than one place
in a condition receives no variable from that condition**: `a && b` introduces nothing when false, `a || b` nothing
when true, and `?:` nothing at all. So a variable only ever flows down a single path, from its test to its uses.

## Threat model and trust assumptions

- The validator's source is trusted: its author means what Java says.
- The transaction is untrusted: an attacker chooses the datum, the redeemer, and so which variant reaches each test.
- javac is trusted to apply JLS 6.3 and resolve names; JuLC does not re-implement those rules.
- The hazard: a variable read on a path where its test was false. Its binder holds the operand's Data, so a field
  read decodes another variant's fields: a wrong value, or a failure the JVM would not have. An attacker who controls
  the variant can steer that. I1 and I5 close it by construction; the differential tests in the verification
  strategy look for it from outside.

## Invariants

- **I1. A variable is bound only under its test.** The binder of `v` in `x instanceof T v` is created inside the
  term that runs when the tag test of `x` passed, and nowhere else. Constrains D2, D3, D4.
- **I2. One evaluation, in Java's order.** The operand of each `instanceof` runs exactly once, at the point Java
  evaluates it, after everything to its left and before everything to its right (JLS 15.20.2, #146). Constrains D1–D4.
- **I3. javac decides scope.** A use of `v` resolves to the binder of the element javac bound it to (`Scope` is keyed
  by `Element`, ADR-068 I3). JuLC adds no scoping rule of its own. Constrains D2–D5.
- **I4. Nothing that compiles today changes.** A method whose conditions declare no pattern variable, and the
  existing `if (x instanceof T v)` form, lower to the same terms as before: the frozen reference does not move.
  Constrains D2, D3.
- **I5. Fail closed.** Where JuLC has not bound a variable that javac resolved, the program is rejected with D6's
  code. No read is ever lowered against a binder that is not in scope. Constrains D6.
- **I6. Linear lowering.** Every statement and expression is lowered once, and a continuation reached from k paths
  is either a trivial term or one shared join, so the term grows linearly with nesting. Constrains D3, D4.

## Decisions

### D1. A bare type test

`x instanceof T`, with no pattern, is the same tag test as `x instanceof T v`, wherever an expression may appear.
`TypeTests.variantOf` reads the type from `instanceof`'s type tree when there is no pattern, as `guardVariantOf`
already does. The operand rules stay: the type is a record variant of one sealed interface and the operand is that variant or
that interface (`JULC0126` and the existing variant rejections for anything else), and record patterns stay `JULC0077`. `JULC0077`'s hint is reworded, since the bare test is no longer the
unsupported form.

*Alternative rejected:* keep the bare test unsupported and point users to the binding form. The bare test is legal
Java, costs nothing to support, and its only lowering is the existing one.

### D2. One component lowers conditions: `Conditions`

A new class in `frontend/javac/lower`, `Conditions`, lowers a boolean condition *with its two continuations*:

```
PirTerm branch(ExpressionTree condition, Frame frame,
               Function<Frame, PirTerm> whenTrue, Function<Frame, PirTerm> whenFalse)   // illustrative
```

The frame passed to `whenTrue` holds the variables the condition introduces when true; the one passed to `whenFalse`
those it introduces when false. Over JLS 6.3.1's forms:

| Condition | Lowering |
|---|---|
| `x instanceof T v` | `readOnce(x)` as `#s`; `if tag(#s) == k then (let v = #s in whenTrue(frame+v)) else whenFalse(frame)` (exactly `Ifs.patternIf` today) |
| `x instanceof T` | `if tag(x) == k then whenTrue(frame) else whenFalse(frame)` |
| `(a)` | `a`'s lowering |
| `!a` | `a` with the two continuations swapped |
| `a && b` | `a` with whenTrue = `b`'s lowering (in a's when-true frame) with the original continuations, and whenFalse = the join of the original whenFalse |
| `a \|\| b` | `a` with whenFalse = `b`'s lowering (in a's when-false frame) and whenTrue = the join of the original whenTrue |
| `a ? b : c` (boolean) | `a` with whenTrue = `b` lowered as a condition, whenFalse = `c` lowered as a condition, both continuations joined |
| any other boolean `e` | `if e then whenTrue(frame) else whenFalse(frame)` |

**Joins.** A continuation that two paths reach (`whenFalse` of `&&`, `whenTrue` of `||`, both of a boolean `?:`) is
lowered once, in the incoming frame. By the JLS consequence above it needs no variable from this condition. If it is
trivial (a constant, a variable, `Error`) the term is placed at each path; otherwise it is let-bound once as a
generated `#cond-join` thunk (`\#unit -> term`, named with `PirHelpers.hygienicName`, ADR-060) and each path applies
it. This is ADR-050's join, applied to conditions.

**When it is used.** `Conditions` lowers a condition only if the condition's JLS 6.3.1 structure (through `&&`,
`||`, `!`, parentheses and `?:`) contains an `instanceof` with a binding pattern, outside a switch guard. Every other
condition keeps its current lowering, so I4 holds by construction for them; for the plain `if (x instanceof T v)`
form, `Conditions` produces `patternIf`'s term, and the frozen reference checks it.

**Where it is called**, replacing the current split of "lower the test, then the branches":

- `Ifs.withContinuation` and `Ifs.nested` (method bodies);
- `LoopBody.foldIf`, `breakingIf` and `joined` (loop bodies; rows b01–b08);
- `Lambdas.function`'s conditional argument (`cond ? x -> ... : x -> ...`);
- `Operators.binary` (`&&`, `||`), `Operators.unary` (`!`) and `Operators.conditional`, through the value form below.

**Value form.** In an expression position (a local's initialiser, a `return`, an argument, a lambda body), a
condition is `branch(e, frame, _ -> true, _ -> false)`, and `?:` is `branch(a, frame, f -> lower(b, f), f -> lower(c, f))`.
A leaf whose continuations are `true` and `false` is the leaf itself, so `x instanceof T v && v.f() > 1` is
`if tag(x) == k then (let v = x in v.f() > 1) else false`: the shape `&&` has today, plus the binding.

*Alternatives rejected:*

- **Hoist each binding to the statement.** Bind `v` before the whole condition so that every part sees it. This
  evaluates the operand out of order (`f() > 0 && g() instanceof T v` would run `g()` first, or when Java would not
  run it at all), breaking I2. Restricting it to variable operands would leave method-call operands, which validators
  use (`out.datum() instanceof OutputDatumInline d`), unsupported.
- **Bind the alias early and trust javac's scope.** `v` holds the operand's Data unchanged, so binding it before the
  test is harmless *if* every use runs only after the test passed. That moves the safety of I1 from the lowering's
  structure to an argument about every consumer of the term, including later passes. The continuation form makes I1
  structural: the binder does not exist on the failing path.
- **Desugar in the AST** (`if (a && b) S else T` to nested `if`s). The else branch is copied once per operand
  (2^n for nested `||`/`&&`), or it needs the same joins anyway, at a level where `Exits` and `Accumulators` also
  have to be re-run on the rewritten tree.
- **Re-implement JLS 6.3 in JuLC.** javac already does it; a second copy can only disagree with it.

### D3. Variables in expressions (JLS 6.3.1)

With D2 in place, every JLS 6.3.1 form is supported in method bodies, loop bodies and lambda bodies: `&&`, `||`, `!`,
parentheses and `?:`, nested to any depth, with `instanceof` operands of any expression JuLC lowers. Rows a02, a04
(its condition), a06, a07, a07b, a08 and b02, b08.

### D4. Variables introduced by an `if` statement (JLS 6.3.2.2)

When javac's rules introduce a variable by an `if` statement into the rest of its block (the early-exit guard,
rows a03 and a04), exactly one branch of the `if` falls through. The rest of the block (`after` in
`Ifs.ifWithExit`, the following statements in `LoopBody`) is then lowered **once, inside that branch's
continuation**, in its frame. Today `after` is lowered once in the enclosing frame and placed in each branch that
falls through; that stays the lowering whenever the `if` introduces nothing.

Which variables the `if` introduces is computed from JLS 6.3.1's when-true and when-false sets of its condition
(the walk `GuardRules.introducedWhenTrue` already does for guards, moved where both can use it) and from whether each
branch falls through. In the subset a branch cannot complete normally when every path through it ends in `return`,
`yield` or `break`; `Exits` decides that, as it does for continuations today. javac decides with JLS 14.22, which can
say "cannot complete" where `Exits` says "can" (a `while (true)` in the branch). Then `after` stays in the enclosing
frame, the use of the variable finds no binder, and D6 rejects it: the disagreement can only refuse a program, so I5
holds.

This covers `return` in method bodies and `break` in loop bodies (`if (!(s instanceof Sq q)) break;` then `q`).

### D5. What stays rejected

- A `while` condition's variable used in its body, and a `while` statement's variable used after it (non-goals).
- A switch guard's variable (`JULC0074`, unchanged).
- Any use that D2–D4 do not bind, by I5.

### D6. One coded error for an unbound pattern variable

A new code, `JULC0139` (`PATTERN_VARIABLE_SCOPE_UNSUPPORTED`, category `CONTROL_FLOW`), replaces `JULC0011` when
the name javac resolved is a `BINDING_VARIABLE` and no binder is in scope. The message names the variable and the
construct, and the fix is code that compiles, in the developer's own names. For example:

```
JULC0139: 'c' is in scope here in Java, but JuLC does not keep a pattern variable alive after the loop that tests it.
  Fix: test the value again where you use it:
      if (s instanceof Ci c) { return c.r(); }
```

The wording follows the project's diagnostic rules: the developer's names, a fix that compiles, no internal
references or ADR numbers. `Names.identifier`'s comment on unbound pattern variables is updated.

### D7. Catalogue and documentation

The language-subset guide gains a section on `instanceof` (where a variable can be used, the bare test, what stays
rejected and the rewrite), the diagnostics catalogue gains `JULC0139` and the reworded `JULC0077`, and the release
notes describe the change.

## Affected stages and modules

| Module | Stage | Change |
|---|---|---|
| `julc-compiler` | javac frontend lowering (`frontend/javac/lower`) | new `Conditions`; `Ifs`, `LoopBody`, `Operators`, `Lambdas`, `TypeTests`, `Names` call it or are adjusted |
| `julc-compiler` | diagnostics (`error/DiagnosticCodes`) | `JULC0139`; `JULC0077` hint |
| `julc-difftest` | harness, generator G1, Jazzer targets | new harness tests, a G1 gate and productions, a fuzz target, diffcases |
| `docs` | guide, diagnostics, release notes | D7 |

No change to PIR, the optimiser, UPLC, the ledger API, the stdlib, `julc-core` or the VMs. The subset scan
(`SubsetScan`) is unchanged: the shapes it accepts today reach the lowering, which is where they fail now.

## Compatibility

- **Source:** strictly additive. Every shape D1–D4 accepts is rejected today (`JULC0011` or `JULC0077`).
- **Bytes:** unchanged for every program that compiles today (I4); the frozen reference and the library-discovery
  snapshot must not move.
- **Diagnostics:** `JULC0011` for a pattern variable becomes `JULC0139`; `JULC0077` no longer fires for a bare type
  test. A tool that matched those codes on these shapes sees the new code.
- **Blueprints, datum and redeemer encoding:** unchanged.

## Implementation milestones

Each milestone ends with: targeted tests, a full `./gradlew build -PskipSigning=true --continue` on the exact head,
review by Claude reviewer agents, and the fixes from that review, before the next one starts.

- **M0. Baseline pins.** Entry: this ADR accepted. The probe matrix is saved as harness tests that expect today's
  rejections (to flip in M1–M3); R1 is probed and recorded. Exit: pins green; frozen reference unchanged.
- **M1. D1, D2 for the single-`instanceof` condition, loop bodies, D6.** `Conditions` with its `instanceof`, bare
  test and plain leaves; `Ifs`, `LoopBody` and `Lambdas.function` call it; `TypeTests` reads a bare test; `JULC0139`.
  Rows b01–b08 (b02 and b08 need D3; they flip in M2), n01–n07. G1 gate `PATTERN_FLOW` part 1. Exit: I1, I2, I4, I5
  tests green; rows flipped.
- **M2. D3.** `&&`, `||`, `!`, parentheses, `?:` in `Conditions` with joins; the value form from `Operators`. Rows
  a02, a06, a07, a07b, a08, b02, b08. G1 part 2. Exit: I1–I6 tests green; a Jazzer run of the new target (below).
- **M3. D4, D7.** Statement introduction in method and loop bodies; documentation. Rows a03, a04. G1 gate on in the
  default profile. Exit: all rows flipped except c01 (`JULC0139`); docs build; a final Jazzer run; Codex review.

## Verification and test strategy

The oracle is the JVM: every positive test runs the program on the JVM and compiles it at all four levels, and
requires the same value, failure and traces (ADR-063 harness, `DiffRunner` with all levels). No expected value is
derived from JuLC's own output.

1. **Harness tests per shape** (`julc-difftest`, new class `Adr069PatternScopesHarnessTest`):
   - every row of the table, with inputs of each variant, so both outcomes of every test run;
   - variants with **different field layouts** (`Sq(BigInteger)`, `Ci(BigInteger, byte[])`, a nullary variant), so a
     read under the wrong test would fail or differ, and variants with the **same** layout, where a wrong read would
     be a plausible value that only the JVM comparison catches (I1);
   - operands that **trace** (`said("x", s) instanceof Sq q && said("y", ...)`), requiring the JVM's trace order
     exactly (I2);
   - operands that are calls, field reads and nested `instanceof` on a variable bound by an outer one;
   - nesting: `||` inside `!` inside `&&`, `?:` inside a condition, conditions inside lambdas inside loops.
2. **No byte change** (I4): the frozen reference and `library-discovery-pre-cleanup.txt` re-recorded and diffed:
   zero moved rows. A unit test compiles the a01 and a05 forms and compares their UPLC with the bytes pinned at M0.
3. **Linearity** (I6): a condition nesting 24 levels of `&&`/`||`/`!` over pattern tests compiles, agrees with the
   JVM, and its script size grows linearly (as `aNestOfHandlersIsLinear` does for #146).
4. **Fail closed** (I5, D6): c01, a `while` condition's variable used in its body, and a switch guard's variable are
   rejected with `JULC0139` or `JULC0074`, with the message and fix asserted; a test asserts that no
   `BINDING_VARIABLE` ever reaches `JULC0011`.
5. **Unit tests** (`julc-compiler`): `Conditions` builds the binder only under the passing branch (walk the PIR and
   check that every `Var` of a pattern variable is dominated by its `IfThenElse`'s then-branch); joins are created
   only for non-trivial continuations; the frame passed to each continuation holds exactly JLS 6.3.1's sets.
6. **Generator** (ADR-066 G1): a new gate, `PATTERN_FLOW`, adds productions for conditions over `&&`, `||`, `!`,
   parentheses and `?:` mixing pattern tests, bare tests and integer comparisons, the early-exit guard, and pattern
   tests in loop bodies. The generator tracks JLS 6.3.1's when-true and when-false sets so that it generates only
   uses Java accepts. The G1 property test then requires, under the gate, that every generated program agrees with
   the JVM at every level, or is rejected with an allowed code (never `JULC0011` for a pattern variable).
7. **Jazzer** (ADR-063 D13): a fuzz target, `Adr069PatternFlowFuzzTest.generatedProgramsAgree`, decodes the fuzzer's
   bytes into a G1 plan under `PATTERN_FLOW` and asserts 6's property. Its saved inputs (seeds, and any crash once
   fixed) replay in `test`. At M2 and M3 it runs for at least 30 minutes with `./gradlew :julc-difftest:fuzz`; the
   run's command, duration and result go in the evidence file.
8. **Regression diffcases:** any divergence found by 6 or 7 is saved as a `.diffcase` (`expect: AGREE` once fixed,
   with the recorded JVM and old results), per the harness's convention.

## Production and audit gates

- All of the verification strategy green on the merge head, including the Jazzer runs recorded in
  `adr/evidence/069-pattern-variable-scopes.md`.
- A Codex review of the final head with no open P0 or P1.
- No node run is required: no builtin, cost model or encoding changes, and the VM harness covers the semantics.

## Risks

- **A variable read under a failed test** (I1). Mitigated structurally by D2 and from outside by tests 1, 5, 6 and 7.
- **Operand order or double evaluation** (I2). Mitigated by `readOnce` at the leaf (as #146) and by traced operands.
- **Byte drift for existing programs** (I4). Mitigated by the gate on "declares a pattern variable" and by the frozen
  reference.
- **Join cost.** A shared continuation costs a lambda and an application (an estimate: tens of bytes, a few thousand
  CPU units). It applies only to conditions that declare a pattern variable and have a non-trivial shared
  continuation, all of which are rejected today.
- **`Exits` versus JLS 14.22.** Where they differ, D4 binds less than javac allows and D6 rejects, never the reverse.
- **Interaction with loop accumulators.** Assigning to a pattern variable is not an accumulator update; JuLC rejects
  it as it does today, and a harness test pins that.

## Related findings (out of scope)

- **R1. Unchecked casts to a variant.** `(Sq) s` lowers to `s` (`Casts.cast` erases the check). On the JVM a wrong
  variant throws `ClassCastException`; on-chain the fields of the other variant are read. Guarded by an `instanceof`
  it is correct, and D1 makes the guarded idiom available everywhere. The unguarded case is a divergence to confirm
  in M0 and to file as an issue; it is not changed here.
- **R2.** `JULC0059` (a pattern variable named like a field) exists for parity with the deleted JavaParser frontend,
  whose scopes were keyed by name. With `Scope` keyed by element it may be liftable (Q2).

## Open questions

- **Q1. `while` bindings** (c01 and the `while` condition into its body). Lean: leave rejected under `JULC0139` and
  revisit on demand; both need the loop desugaring to pass a binder through its recursive function.
- **Q2. Lift `JULC0059`?** Lean: a separate issue with its own harness evidence; it changes which programs compile
  in a way unrelated to scope flow.
- **Q3. Join threshold.** Lean: copy only constants, variables and `Error`; bind everything else once. A size-based
  threshold would make the bytes depend on a tuning constant.
- **Q4. Switch-guard bindings** (`JULC0074`). Lean: a follow-up that reuses `Conditions` once this lands.

## Revision history

- r1 (2026-11-06): initial proposal.
