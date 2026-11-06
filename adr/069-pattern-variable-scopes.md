# ADR-069: Pattern-variable scopes: `instanceof` beyond the plain `if`

**Status:** Proposed
**Date:** 2026-11-06
**Risk class:** correctness-sensitive, **medium**. The new lowering runs only for programs that read a pattern
variable JuLC cannot bind today, all of which are rejected now (I4); every other program keeps its terms. The risk is
in that new lowering: a pattern variable read on a path where its test failed reads another variant's fields, and a
validator built that way can accept a datum its own Java tests reject.
**Issue:** [#204](https://github.com/bloxbean/julc/issues/204)
**Relates to:** [ADR-068](068-javac-frontend-migration.md) (javac frontend; `Scope` is keyed by javac's elements),
[ADR-050](050-if-loop-continuations.md) (continuations and joins for `if` and loops),
[ADR-060](060-generated-binder-namespace.md) (generated names), [ADR-063](063-differential-jvm-uplc-test-harness.md)
(differential harness), [ADR-066](066-generator-g1-records-sealed-switch.md) (generator G1),
[ADR-067](067-switch-guards-record-patterns.md) (switch guards; `JULC0074`),
[ADR-042](042-value-conversion-sharing.md) and [ADR-044](044-projection-sharing.md) (strict-prefix sharing)

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

JuLC binds an `instanceof` pattern variable in one form: an `if` statement whose whole condition (inside the `if`'s
own parentheses) is `x instanceof T v`, lowered by `Ifs`. That covers method bodies, switch-arm blocks, lambda block
bodies and the bodies of loops that update no variable declared before them (those go through `Statements` to
`Ifs`). It does not cover the body of a loop with accumulators (`LoopBody`), or any other position. A program that
*reads* the variable anywhere else is rejected. A program that declares a variable and never reads it compiles: the
test is lowered and the variable ignored. Nothing is miscompiled: a rejected program is refused, never built. But the
rejections hit common validator code, and the message is misleading (`Undefined variable: c`, for valid Java).

This has been so since the first frontend. The JavaParser frontend at `b5ff39a8` (release 0.1.0-pre20) rejected a
bare type test with the hint "instanceof is supported only with sealed interface variants:
`if (x instanceof Variant v) { ... }`", and its `LoopBodyGenerator` lowered an `if` condition in a loop body as a
plain boolean. The javac frontend ported both limits one for one (ADR-068 parity).

### Current behaviour in detail

Probe on `main` at `3b6d7f5a` (scratch difftest, all four levels, sealed `Sh permits Sq, Ci`; uncommitted). The
`b` rows use loops with an accumulator (`long t` updated in the body). Every row except a01, a05 and n08 is rejected
identically at every level:

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
| b01–b06, b08 | `if (s instanceof Sq q) {...}` in an accumulator loop: `for`, `while`, nested, `break`, `else if`, `?:` | `JULC0011` |
| c01 | `while (!(s instanceof Ci c)) {...} return c...;` | `JULC0011` |
| n01–n07 | `if (s instanceof Sq)` (no variable), in or out of a loop, negated, `else if` | `JULC0077 Unsupported instanceof pattern` |
| n08 | `switch (s) { case Sq q -> ...; case Ci c -> ...; }` in a loop | compiles, agrees |

With a field named like the pattern variable, every row is `JULC0059` instead (the existing guard, unchanged here).

Where the code stands (`main` at `2b705c38`):

- `Ifs.patternIf` is the only place an `instanceof` pattern variable is bound (`Switches` binds case variables).
  `Ifs.pattern(condition)` matches only a condition that *is* an `instanceof` with a binding pattern, after one level
  of parentheses (`Ifs.condition`). `patternIf` builds the else term before the operand (the reference's order).
- `Operators.binary` lowers `&&` and `||` as `IfThenElse(left, right, false)` and `IfThenElse(left, true, right)`,
  `Operators.unary` lowers `!`, and `Operators.conditional` lowers `?:` after checking both arms' representations
  (`requireNativeBoundary`, `requireOneRepresentation`). Each lowers its operands in the same frame, so a variable
  bound on one side is never visible on the other.
- `LoopBody.foldIf`, `breakingIf` and `joined` lower a loop body's `if` condition with `lowering.expr` and never bind.
  `while` conditions (`Loops`) and switch guards (`Guards`) also go through `lowering.expr`.
- Any `InstanceOfTree` reached by `lowering.expr` becomes `TypeTests.instanceOf`, a bare tag test that binds nothing:
  that is why an unread variable compiles. `TypeTests.variantOf` reads the tested type only from a binding pattern,
  so a bare `x instanceof T` is `JULC0077` outside a `switch` guard; `TypeTests.guardVariantOf` already reads it from
  `instanceof`'s type in a guard.
- `Names.identifier` reports a `BINDING_VARIABLE` with no binder as `JULC0011`.
- A switch guard that introduces a variable for its arm, or reads one it declares, is `JULC0074`
  (`GuardRules.requireNoBinding`, ADR-067).
- An assignment to a pattern variable is not rejected as such: in a01's then-branch a loop that assigns `q` makes
  `q` an accumulator (`Accumulators.add` takes any element with a binder), and `ArmUpdates` knows only the
  whole-condition form.

## Goals and non-goals

**Goals**

- G1. Accept the shapes in D1–D4 with Java's meaning: the same value, the same failures and the same traces as the
  JVM, at every optimisation level.
- G2. Keep every program that compiles today on its current lowering: the same PIR, the same bytes, the same
  diagnostics in the same order (I4).
- G3. Give every shape that stays unsupported one coded error that names the variable and shows a rewrite that
  compiles.
- G4. Keep the lowering linear in the source: no continuation is copied once per path.

**Non-goals**

- Bindings introduced by a `while` statement for what follows it (JLS 6.3.2.3 second rule; row c01), and a `while`
  condition's bindings used in its body (JLS 6.3.2.3 first rule). Both need the loop desugaring to carry a binding
  out of or into its recursive function; they stay rejected, under D6's code. (A variable used *inside* the `while`
  condition itself is JLS 6.3.1 and is supported, D3.)
- Switch-guard bindings (`JULC0074`): guards keep their lowering (D2 "When it is used").
- Record (deconstruction) patterns in `instanceof` (`JULC0077`), `do` and basic `for` statements (unsupported in the
  subset), and labeled statements.
- Assigning to a pattern variable that `Conditions` binds (D8 rejects it).
- Lifting `JULC0059` (a pattern variable named like a field). See Q2.
- Unchecked reference casts (R1).

## Pinned normative references

*The Java Language Specification, Java SE 25 Edition*, fetched 2026-11-06 from
`docs.oracle.com/javase/specs/jls/se25/html/`. Quoted text is in quotation marks; the rest is paraphrase, marked so.

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
- **6.3.1.3** `!a`: introduced when true iff introduced by `a` when false, and the reverse (paraphrase).
- **6.3.1.4** `a ? b : c`: "A pattern variable introduced by `a` when true is definitely matched at `b`. A pattern
  variable introduced by `a` when false is definitely matched at `c`." No variable is introduced by `a ? b : c`
  (paraphrase of the note).
- **6.3.1.5** `a instanceof p`: "A pattern variable is introduced by `a instanceof p` when true iff the pattern `p`
  contains a declaration of the pattern variable"; there is no rule for when false.
- **6.3.1.6** A `switch` expression introduces nothing to its enclosing condition (paraphrase): it is a leaf for D2.
- **6.3.1.7** `(a)`: the same as `a` (paraphrase).
- **6.3.2.1** Blocks: "A pattern variable introduced by S is definitely matched at all the block statements
  following S, if any, in the block."
- **6.3.2.2** `if (e) S`: a variable introduced by `e` when true is definitely matched at S; one is introduced by the
  statement iff "(i) it is introduced by `e` when false and (ii) S cannot complete normally". `if (e) S else T`: when
  true at S, when false at T; introduced by the statement iff "introduced by `e` when true, and S can complete
  normally, and T cannot complete normally; or introduced by `e` when false, and S cannot complete normally, and T can
  complete normally".
- **6.3.2.3** `while (e) S`: when true at S; introduced by the statement iff introduced by `e` when false and S has no
  reachable `break` to it (paraphrase).
- **6.4** A pattern variable may not shadow a local variable (paraphrase).
- **14.22** "Can complete normally": in the subset, `return`, `yield` and `break` cannot; an expression statement
  (an `error()` call included) can; a condition's constant value is ignored for `if` (paraphrase). JuLC does not
  re-derive this (D4).
- **15.7.1, 15.23, 15.24, 15.25** Left operand first; `&&`, `||` evaluate the right operand only when needed; `?:`
  evaluates the condition, then exactly one arm (paraphrase). I2 rests on these.
- **15.20.2** `instanceof` with a pattern: "a side effect of a true result is that all the pattern variables declared
  in the pattern will be initialized"; the operand is evaluated once, before the test (paraphrase, with 14.30.2).

One consequence carries the design, and it is local. **A continuation that two paths of a sub-condition reach
receives no variable introduced by that sub-condition**: `a && b` introduces nothing when false, `a || b` nothing when
true, and `?:` nothing at all. It may still need variables introduced by *enclosing* operands: in
`s instanceof Sq q && (q.s() > 1 || q.s() < -1)` the `||`'s when-true continuation is reached by two paths and reads
`q` from the enclosing `&&` (javac accepts it). D2 places every join where its sub-condition starts, under every
enclosing binder.

## Threat model and trust assumptions

- The validator's source is trusted: its author means what Java says.
- The transaction is untrusted: an attacker chooses the datum, the redeemer, and so which variant reaches each test.
- javac is trusted to apply JLS 6.3 and 14.22 and resolve names; JuLC does not re-implement those rules.
- The hazard: a variable read on a path where its test was false. Its binder holds the operand's Data, so a field
  read decodes another variant's fields: a wrong value, or a failure the JVM would not have. An attacker who controls
  the variant can steer that. I1 and I5 close it by construction; the property check of I1 (verification 5) runs on
  every compile in the test suite, and the differential tests look for it from outside.
- Later passes must not move a field read of a pattern variable above its tag test. O8 and O15 share only through a
  strict prefix ("Branch bodies never lead", ADR-042 Decision 2; ADR-044 applies the same rule to projections); the nullary-variant rows below check the result at
  every level.

## Invariants

- **I1. A variable is bound only under its test.** The binder of `v` in `x instanceof T v` is a `let` at the head of
  the then-branch of the `IfThenElse` whose condition is that tag test of the same operand term, and nowhere else.
  Constrains D2, D3, D4.
- **I2. One evaluation, in Java's order.** The operand of each `instanceof` runs exactly once, at the point Java
  evaluates it, after everything to its left and before everything to its right, and an operand Java skips by
  short-circuit is not run (JLS 15.7.1, 15.20.2, 15.23–15.25; #146). Constrains D1–D4.
- **I3. javac decides scope.** A use of `v` resolves to the binder of the element javac bound it to (`Scope` is keyed
  by `Element`, ADR-068 I3). JuLC adds no scoping rule and no reachability rule of its own: where a variable is in
  scope, and whether a branch falls through, are read from javac's resolution of the reads. Constrains D2–D5.
- **I4. Nothing that compiles today changes.** A condition takes the new lowering only if it declares a pattern
  variable that is *read* (D2, "When it is used") and it is not the existing whole-condition `if` form. Every such
  program is rejected today, so for every program that compiles today the PIR, the UPLC, the diagnostics and their
  order are unchanged. Constrains D1, D2, D8.
- **I5. Fail closed.** Where JuLC has not bound a variable that javac resolved, the program is rejected with D6's
  code. No read is ever lowered against a binder that is not in scope, and no branch that D4 judges not to fall
  through is ever given a continuation silently (D4's sentinel). Constrains D4, D6.
- **I6. Linear lowering.** Every statement and expression is lowered once, and a continuation reached from k paths
  is either rebuilt per path when trivial or one shared join, so the term grows linearly with nesting. Constrains
  D2, D3, D4.
- **I7. Fresh, distinct generated names.** Every join is a generated binder with a per-instance number
  (`#cond-join-N`) chosen while the term is built (ADR-060); no two joins in scope share a name. Constrains D2.

## Decisions

### D1. A bare type test

`x instanceof T`, with no pattern, is the same tag test as `x instanceof T v`, wherever an expression may appear.
`TypeTests.variantOf` reads the type from `instanceof`'s type tree when there is no pattern, as `guardVariantOf`
already does. The operand rules stay: the type is a record variant of one sealed interface and the operand is that
variant or that interface (`JULC0126` and the existing variant rejections for anything else), and record patterns stay
`JULC0077`, whose message and fix are reworded since the bare test is no longer the unsupported form.

A bare test binds nothing, so it never takes D2's new lowering; it changes no program that compiles today (each was
`JULC0077`).

*Alternative rejected:* keep the bare test unsupported and point users to the binding form. The bare test is legal
Java, costs nothing to support, and its only lowering is the existing one.

### D2. One component lowers conditions: `Conditions`

A new class in `frontend/javac/lower`, `Conditions`, lowers a boolean condition *with its two continuations*:

```
PirTerm branch(ExpressionTree condition, Frame frame,
               Function<Frame, PirTerm> whenTrue, Function<Frame, PirTerm> whenFalse)   // illustrative
```

The frame passed to `whenTrue` is the incoming frame plus the variables the condition introduces when true; the one
passed to `whenFalse`, the incoming frame plus those it introduces when false. Every other part of the frame (exit,
patterns, `inGuard`, `bindsUpdates`) is the incoming frame's. Each continuation function is called exactly once.
Operands are lowered in Java's evaluation order, left to right. Over JLS 6.3.1's forms:

| Condition | Lowering |
|---|---|
| `x instanceof T v` | `readOnce(x)` as `#operand`; `if tag(#operand) == k then (let v = #operand in whenTrue(frame+v)) else whenFalse(frame)` (the term `Ifs.patternIf` builds) |
| `x instanceof T` | `if tag(x) == k then whenTrue(frame) else whenFalse(frame)` |
| `(a)` | `a`'s lowering |
| `!a` | `a` with the two continuations swapped |
| `a && b` | `a` with whenTrue = `b`'s lowering (in `a`'s when-true frame) with the original continuations, and whenFalse = the join of the original whenFalse |
| `a \|\| b` | `a` with whenFalse = `b`'s lowering (in `a`'s when-false frame) and whenTrue = the join of the original whenTrue |
| `a ? b : c` (boolean) | `a` with whenTrue = `b` lowered as a condition, whenFalse = `c` lowered as a condition; both original continuations joined |
| a `switch` expression, or any other boolean `e` | `if e then whenTrue(frame) else whenFalse(frame)` |

**Joins.** A continuation that two paths reach (`whenFalse` of `&&`, `whenTrue` of `||`, both of a boolean `?:`) is
lowered once, in the frame where its sub-condition starts (so under every enclosing binder; the JLS consequence
guarantees it needs none of the sub-condition's own). If the result is trivial (a constant, a variable, `Error`, or
an application of a join to unit), each path gets **a fresh copy of that node**, never the same object, so source
positions and provenance stay per node. Otherwise it is let-bound once, at the sub-condition's start, as a generated
thunk `#cond-join-N = \#unit -> term` (I7; `N` from a per-compilation counter, as `#if-join-N` and `#joinN`; the
thunk's `FunType` recorded as `ifWithLoop` records its join's), and each path applies it to unit. This is ADR-050's
join, applied to conditions. A join captures the frame where it is bound, which in a loop body includes the carried
accumulators as they stand at that point; the condition updates nothing, so no update is lost.

**When it is used.** One predicate, shared by every hook:

1. the condition is in a switch guard (`frame.inGuard()`): no; the guard's lowering is unchanged;
2. the condition is the whole condition of an `if` lowered by `Ifs` and is `x instanceof T v`: no; `Ifs.patternIf`
   lowers it exactly as today (same term, same build order);
3. the condition's JLS 6.3.1 structure (through `&&`, `||`, `!`, parentheses and `?:`) declares a pattern variable
   that javac resolves at least one read of (an `IdentifierTree` whose element is that `BINDING_VARIABLE`, anywhere in
   the method): **yes**;
4. otherwise: no; the condition keeps its current lowering.

Case 3 is the only new path, and every program it applies to is rejected today: the read has no binder (`JULC0011`) or
is a guard read (`JULC0074`, excluded by case 1). So I4 holds by construction, and a condition whose variable is never
read (`return s instanceof Sq q && flag;`) keeps today's bare tag test.

**Where it is called:**

- `Ifs.withContinuation` and `Ifs.nested` (method bodies, switch-arm blocks, lambda block bodies, accumulator-free
  loop bodies);
- `LoopBody.foldIf`, `breakingIf` and `joined` (accumulator loop bodies; rows b01–b08);
- `Lambdas.function`'s conditional argument (`cond ? x -> ... : x -> ...`);
- `Operators.binary` (`&&`, `||`), `Operators.unary` (`!`) and `Operators.conditional`, through the value form below.
  This also reaches `while` conditions, which `Loops` lowers with `lowering.expr`.

**Value form.** In an expression position (a local's initialiser, a `return`, an argument, a lambda body, a `while`
condition), a condition is `branch(e, frame, _ -> true, _ -> false)`. A leaf whose continuations are `true` and
`false` is the leaf's own test; with `false` and `true` it is today's `!` term, `IfThenElse(test, false, true)`. So
`x instanceof T v && v.f() > 1` is `if tag(#operand) == k then (let v = #operand in v.f() > 1) else false`: the shape
`&&` has today, plus the binding. A value `?:` (`a ? b : c` of any type) is `branch(a, frame, f -> lower(b, f),
f -> lower(c, f))`, and keeps `Operators.conditional`'s checks: `requireNativeBoundary` and `requireOneRepresentation`
run on the two arm terms as lowered in their own frames, each arm lowered once.

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
- **Re-implement JLS 6.3 in JuLC** (compute when-true and when-false sets by name, as `GuardRules` does for guards).
  javac already does it; a second copy can only disagree with it. `Conditions` derives each frame from the
  structure it lowers, and D4 reads javac's resolution.
- **Gate on "declares a pattern variable"** (r1). Unread variables compile today in every position, so that gate
  would reroute them and move their bytes (review r1, F2/C1).

### D3. Variables in expressions (JLS 6.3.1)

With D2 in place, every JLS 6.3.1 form is supported in method bodies, switch-arm blocks, loop bodies, lambda bodies
and `while` conditions: `&&`, `||`, `!`, parentheses and `?:`, nested to any depth, with `instanceof` operands of any
expression JuLC lowers. Rows a02, a04 (its condition), a06, a07, a07b, a08, b02, b08, and
`while (s instanceof Sq q && q.s() > t)` with `q` read only in the condition.

### D4. Variables introduced by an `if` statement (JLS 6.3.2.2)

**Trigger, read from javac.** An `if` statement introduces a variable into the rest of its block exactly when its
condition declares a pattern variable that javac resolves a read of *outside the `if` statement* (in a following
statement of the same block, or deeper in one). javac has then proved JLS 6.3.2.2 and 14.22 for it: exactly one of
the `if`'s two outcomes falls through to the rest of the block, the one whose continuation binds that variable. JuLC
does not use `Exits` to decide this (I3).

**Lowering.** The `if` is lowered by `Conditions.branch` with:

- the falling-through continuation: its branch (or nothing, for a missing `else`) followed by the rest of the block,
  lowered **once, in the frame where that branch ends**, so it sees the variable and any accumulator the branch
  rebinds. The continuation is threaded through the branch's statement lowering as a frame-taking function, not a
  term built in advance;
- the other continuation: its branch, followed by a **sentinel** instead of the rest of the block. Lowering that
  branch must end every path in `return`, `yield` or `break`; if any path reaches the sentinel, compilation stops
  with an internal error naming the statement (never `unit`, never a silent value). Since javac has proved the branch
  cannot complete normally, the sentinel firing is a JuLC defect, and it fails closed (I5).

Which outcome falls through is the one whose continuation frame binds the read variable
(`frame.scope().lookup(element) != null`). If the variable is bound in neither, the read later fails with D6's code.

**Where.** One path reaches the rest of the block, so no join is needed, and D4 replaces the existing routing for
these `if`s only: `Ifs.ifWithExit` and `Ifs.ifWithLoop` (ADR-050 join) in method, arm and lambda block bodies; and
`LoopBody.breakingIf`'s three break shapes (no `else` and the then-branch breaks; the `else` breaks; the then-branch
breaks and the `else` falls through), including those that today go through `LoopBody.joined`. An `if` that
introduces nothing keeps today's routing, including ADR-050's joins.

This covers `return` in method and lambda block bodies, `yield` in switch-arm blocks, and `break` in accumulator loop
bodies. `throw` and `continue` are not in the subset, and an expression statement (an `error()` call) can complete
normally, so javac never brings a variable into scope after one.

### D5. What stays rejected

- A `while` condition's variable used in its body, and a `while` statement's variable used after it (non-goals).
- A switch guard's variable, declared or read in the guard (`JULC0074`, unchanged).
- Any use that D2–D4 do not bind, by I5 (D6).

### D6. One coded error for an unbound pattern variable

A new code, `JULC0139` (`PATTERN_VARIABLE_SCOPE_UNSUPPORTED`, category `CONTROL_FLOW`), replaces `JULC0011` when
the name javac resolved is a `BINDING_VARIABLE` and no binder is in scope. The message names the variable and the
construct (after a `while`, in a `while` body), and the fix is code that compiles, in the developer's own names:

```
JULC0139: 'c' is in scope here in Java, but JuLC does not keep a pattern variable alive after the loop that tests it.
  Fix: test the value again where you use it:
      if (s instanceof Ci c) { return c.r(); }
```

The wording follows the project's diagnostic rules: the developer's names, a fix that compiles, no internal
references or ADR numbers. Guards are not affected: their reads are `JULC0074` before lowering.

### D7. Catalogue, diagnostics plumbing and documentation

- `julc-compiler/src/main/resources/diagnostics.json` (from which `DiagnosticCodes.java` is generated and checked):
  `JULC0139` and `JULC0140` (D8) added; `JULC0011`'s summary and fix, `JULC0059`'s summary, `JULC0074`'s summary and
  `JULC0077`'s title, summary and fix reworded where they describe the old limit.
- The docs catalogue (`docs/scripts/generate-catalog.mjs`), `adr068/rejection-sites.tsv` (rows for the new codes),
  and `DiagnosticTextTest`'s rules.
- Comments that state the old limit: `Ifs` (class comment), `Names.identifier`, `TypeTests` (class comment).
- The language-subset guide gains a section on `instanceof` (where a variable can be used, the bare test, the
  early-exit guard with `return`, `yield` or `break`, what stays rejected and the rewrite); the release notes describe
  the change.

### D8. A pattern variable that `Conditions` binds cannot be assigned

Java allows assigning to a non-final pattern variable. In a loop body JuLC would lower such an assignment as a
`let` in one branch, and a later read outside that branch would see the old value: a miscompile (review r1, F1). D8
rejects an assignment, or compound assignment, to a `BINDING_VARIABLE` that the new path binds, with a new code
`JULC0140` (`PATTERN_VARIABLE_REASSIGNED`) and the fix "copy it into a local, `Sq r = q;`, and update `r`". The check
runs where the scan sees assignments, by element. `ArmUpdates` learns the same set by element, so in a switch arm the
code is `JULC0140`, not `JULC0121`.

Assignments in a01's whole-condition form keep today's handling (I4); the harness pins what that does today, and Q5
asks whether to reject them too in a follow-up.

## Affected stages and modules

| Module | Stage | Change |
|---|---|---|
| `julc-compiler` | javac frontend lowering (`frontend/javac/lower`) | new `Conditions`; `Ifs`, `LoopBody`, `Operators`, `Lambdas`, `TypeTests`, `Names`, `Statements` (frame-taking continuation for D4) call it or are adjusted |
| `julc-compiler` | subset scan (`frontend/javac/subset`) | D8's assignment check; `ArmUpdates` |
| `julc-compiler` | diagnostics (`resources/diagnostics.json`, generated `DiagnosticCodes`) | D7 |
| `julc-compiler` | test-time checks (`julc.verifyBinderNamespace`) | I1's property check (verification 5) |
| `julc-difftest` | harness, generator G1, Jazzer target | new harness tests, G1 gates and a steer, a fuzz target, diffcases, rejection sites |
| `docs` | guide, diagnostics catalogue, release notes | D7 |

No change to PIR, the optimiser, UPLC, the ledger API, the stdlib, `julc-core` or the VMs.

## Compatibility

- **Source:** additive. Every shape D1–D4 accepts is rejected today (`JULC0011`, `JULC0077`). D8 rejects only
  assignments in shapes that are rejected today.
- **Bytes and PIR:** unchanged for every program that compiles today (I4), including programs that declare a pattern
  variable and never read it. The frozen reference and the library-discovery snapshot must not move, and M0 pins
  the unread-variable shapes.
- **Diagnostics:** `JULC0011` for a pattern variable becomes `JULC0139`; `JULC0077` no longer fires for a bare type
  test. Tests that assert the old codes on these shapes flip (M1, M2): `JavacStatementsTest.aTypeTestThatIsNoVariantPattern`,
  `JavacStatementsTest.aPatternVariableBindsOnlyWhereItIsTheWholeCondition`,
  `JavacSwitchesTest.anInstanceofWithNoPatternHasNoVariant`.
- **Blueprints, datum and redeemer encoding:** unchanged.

## Implementation milestones

Each milestone ends with: targeted tests, a full `./gradlew build -PskipSigning=true --continue` on the exact head
with no frozen-reference movement, review by Claude reviewer agents, and the fixes from that review, before the next
one starts. The PR stays one PR.

- **M0. Baseline pins.** Entry: this ADR accepted. (a) The probe matrix as harness tests that expect today's codes
  (to flip in M1–M3). (b) Compatibility pins: the unread-variable shapes at every hook
  (`return !(s instanceof Sq q);`, `return s instanceof Sq q && n > 0;`, `if (s instanceof Sq q && n > 0) return 1;`,
  `if ((s instanceof Sq q))`, a binding test in an accumulator loop, `cond ? x -> ... : x -> ...` with an unread
  variable), and a01 and a05: their PIR text, UPLC at all four levels, and first-diagnostic order. (c) R1 recorded as a
  pinned divergence. Exit: pins green; frozen reference unchanged.
- **M1. D1, the gate, loop bodies, D6, D8.** `Conditions` with its `instanceof`, bare-test and plain leaves; the shared
  gate predicate; `Ifs`, `LoopBody` and `Lambdas.function` call it; `TypeTests` reads a bare test; `JULC0139`,
  `JULC0140` and D7's diagnostics plumbing; I1's property check. Rows b01, b03–b06, n01–n07. G1 gate `PATTERN_LOOPS`
  (VERSION bump). Exit: I1, I2, I4, I5 tests green; M0 compatibility pins unchanged.
- **M2. D3.** `&&`, `||`, `!`, parentheses, `?:` with joins; the value form from `Operators`, with its checks. Rows
  a02, a06, a07, a07b, a08, b02, b08, `while`-condition reads. G1 gate `PATTERN_CONDITIONS` (VERSION bump). Exit: I1–I7
  tests green; a Jazzer run (verification 7).
- **M3. D4, D7 docs.** Statement introduction with the sentinel in method, arm, lambda block and loop bodies. Rows
  a03, a04 and the D4 matrix. G1 gate `PATTERN_EXIT` (VERSION bump); the three gates on in the default profile. Exit:
  every row flipped except c01 (`JULC0139`); docs build; a final Jazzer run; Codex review.

## Verification and test strategy

The oracle is the JVM: every positive test runs the program on the JVM and compiles it at all four levels, and
requires the same value, failure and traces (ADR-063 harness, `DiffRunner` with all levels). No expected value is
derived from JuLC's own output. A newly supported shape must **agree**; a rejection of it fails the test.

1. **Harness tests per shape** (`julc-difftest`, new class `Adr069PatternScopesHarnessTest`):
   - every row of the table, with inputs of each variant, so both outcomes of every test run;
   - variants with **different field layouts** (`Sq(BigInteger)`, `Ci(BigInteger, byte[])`, and a **nullary**
     variant, which every row includes on a failing path), so a read under the wrong test would fail or differ, and
     variants with the **same** layout, where a wrong read is a plausible value only the JVM comparison catches (I1);
   - operands that **trace** (`said("x", s) instanceof Sq q && said("y", ...)`), requiring the JVM's trace order
     exactly, including operands that short-circuit skips (I2);
   - operands that are calls, field reads and nested `instanceof` on a variable bound by an outer one;
   - the join cases: `s instanceof Sq q && (q.s() > 1 || q.s() < -1)` used in the then-branch,
     `!(!(s instanceof Sq q) || q.s() == 0) && q.s() > 1`, a boolean `?:` as a condition with each arm a pattern
     test, and the same inside lambdas with captures;
   - D4: `return` (method, lambda block), `yield` (switch arm), `break` (each of the three loop shapes), with an
     accumulator updated in the falling-through branch and by a loop in it (the `ifWithLoop` route), nested `if`s,
     `else if` chains, and two early-exit guards in a row;
   - `while (s instanceof Sq q && q.s() > t)` with one and with several accumulators;
   - D8: an assignment to a bound pattern variable in each position is `JULC0140`; a01's form keeps today's result.
2. **No change for what compiles** (I4): M0's pins stay green at every milestone; the frozen reference and
   `library-discovery-pre-cleanup.txt` re-recorded and diffed: zero moved rows.
3. **Linearity** (I6): conditions nesting 6, 12 and 24 levels of `&&`/`||`/`!`/boolean `?:` over pattern tests,
   with non-trivial shared continuations, and 12 early-exit guards in a row, compile, agree with the JVM, and their
   script size grows linearly with depth.
4. **Fail closed** (I5, D6): c01, a `while` condition's variable used in its body, and a guard's variable are
   rejected with `JULC0139` or `JULC0074`, with the message and fix asserted; a test asserts that no
   `BINDING_VARIABLE` read ever reaches `JULC0011`; a unit test drives D4's sentinel with a branch that falls through
   and asserts the internal error.
5. **I1 as a property on every compile** (`julc-compiler`): a check beside `BindingCheck`, under the same
   `julc.verifyBinderNamespace` switch that every test compile sets, walks the lowered PIR (with its `Provenance`):
   every `Let` whose binder is an `instanceof` `BINDING_VARIABLE` (case binders excluded) must head the then-branch
   of an `IfThenElse` whose condition is `EqualsInteger(FstPair(UnConstrData(r)), k)`, where `r` is the `Var` (or, for
   a trivial operand, the same term) that the `Let` binds. With `BindingCheck`'s resolution this proves I1 on every
   compile the suite performs, G1 and Jazzer included. Unit tests also cover join creation (trivial versus not),
   fresh copies, join names (sibling and nested joins, I7), and the frames passed to continuations (incoming frame
   plus JLS 6.3.1's sets).
6. **Generator** (ADR-066 G1): three gates, one per milestone (`PATTERN_LOOPS`, `PATTERN_CONDITIONS`, `PATTERN_EXIT`),
   each with a VERSION bump, marker features in `GATED`, `G1GatesTest` updated, and a `Steer.ISSUE_204` that
   concentrates generation on them. Two modes:
   - *accepted shapes:* the generator tracks JLS 6.3.1's sets and emits only uses Java accepts and the gates support;
     every program must **agree** with the JVM at every level. `JULC0139` is a failure here, not an allowed code.
   - *javac-filtered:* the generator also emits uses outside its own model (after a `while`, in `else` branches of
     constant conditions, after branches that end in a `while (true)`); programs javac rejects are skipped; the
     property is that no program **diverges**: each agrees or is rejected with a coded diagnostic. This reaches the
     javac-versus-JuLC boundary that the first mode cannot.
7. **Jazzer** (ADR-063 D13): a fuzz target, `Adr069PatternFlowFuzzTest.generatedProgramsAgree`, decodes the fuzzer's
   bytes into a G1 plan under the gates and `Steer.ISSUE_204` and asserts 6's properties. Its saved inputs
   (`Adr069PatternFlowFuzzTestInputs/generatedProgramsAgree/`: seeds, and any crash once fixed) replay in `test`. At
   M2 and M3 it runs for at least 30 minutes with `./gradlew :julc-difftest:fuzz`; the command, duration and result go
   in the evidence file.
8. **Regression diffcases:** any divergence found by 6 or 7 is saved as a `.diffcase` (`expect: AGREE` once fixed,
   with the recorded JVM and old results), per the harness's convention.

## Production and audit gates

- All of the verification strategy green on the merge head, including the Jazzer runs recorded in
  `adr/evidence/069-pattern-variable-scopes.md`.
- A Codex review of the final head with no open P0 or P1.
- No node run is required: no builtin, cost model or encoding changes, and the VM harness covers the semantics.

## Risks

- **A variable read under a failed test** (I1). Mitigated structurally by D2, checked on every compile by
  verification 5, and from outside by 1, 6 and 7.
- **Operand order or double evaluation** (I2). Mitigated by `readOnce` at the leaf (as #146) and by traced operands.
- **Byte drift for existing programs** (I4). Mitigated by the read-based gate and M0's pins.
- **D4 judged wrong.** D4 reads javac's proof; the other branch ends in a sentinel, so a JuLC defect stops the
  compile rather than building a fall-through to nothing.
- **Join cost.** A shared continuation costs a lambda and an application (an estimate: tens of bytes, a few thousand
  CPU units). It applies only to case-3 conditions with a non-trivial shared continuation, all rejected today.
- **Assignment to a pattern variable.** D8 rejects it on the new paths; a01's form is pinned as it is today.

## Related findings (out of scope)

- **R1. Unchecked casts to a variant.** `(Sq) s` lowers to `s` (`Casts.cast` keeps the operand). Measured on `main`
  at `2b705c38` (scratch harness): `Sq q = (Sq) s; return q.s();` with `s` a `Ci(10, ...)` returns 10 on-chain, where
  the JVM throws `ClassCastException`; with `s` a nullary variant both fail; `((Sq) s).s()` inline is rejected
  (`JULC0055`). Guarded by an `instanceof` it is correct, and D1 makes the guarded idiom available everywhere. The
  unguarded case is an existing divergence, to be tracked as its own issue; it is not changed here, because a checked
  cast changes the bytes and failures of programs that compile today.
- **R2.** `JULC0059` (a pattern variable named like a field) exists for parity with the deleted JavaParser frontend,
  whose scopes were keyed by name. With `Scope` keyed by element it may be liftable (Q2).

## Open questions

- **Q1. `while` bindings** (c01 and the `while` condition into its body). Lean: leave rejected under `JULC0139` and
  revisit on demand; both need the loop desugaring to pass a binder through its recursive function.
- **Q2. Lift `JULC0059`?** Lean: a separate issue with its own harness evidence; it changes which programs compile
  in a way unrelated to scope flow.
- **Q3. Join threshold.** Lean: copy only constants, variables, `Error` and join applications; bind everything else
  once. A size-based threshold would make the bytes depend on a tuning constant.
- **Q4. Switch-guard bindings** (`JULC0074`). Lean: a follow-up that reuses `Conditions` once this lands.
- **Q5. Assignments in a01's form.** They compile today (a loop in the then-branch can make the variable an
  accumulator). Lean: pin them now, and decide in a follow-up whether D8 should cover them too, since that would
  reject programs that compile.

## Revision history

- r1 (2026-11-06): initial proposal.
- r2 (2026-11-06, responds to the review of `4f30b8fb` by Codex (F1–F8), a semantics reviewer (S1–S9) and a code
  reviewer (C1–C12)):
  - Gate (F2, C1, C5, C12): the new path is case 3 of one shared predicate: a pattern variable javac resolves a read
    of, outside guards and outside the existing whole-condition `if`. Context and current behaviour corrected
    (unread variables compile; accumulator-free loop bodies already bind; `#operand`). M0 pins unread-variable shapes,
    a01 and a05 (PIR text, bytes, diagnostic order, C8).
  - Joins (F6, S1, C2, F7, S8, C6): the consequence stated per sub-condition; joins bound at the sub-condition's start
    under enclosing binders; per-instance names `#cond-join-N` (I7); fresh copies of trivial nodes; join applications
    count as trivial; the join's `FunType` recorded; the negated leaf specified.
  - D4 (F3, F4, S2, S3, C3, C4): javac decides through its resolution of reads after the `if`; the falling-through
    continuation is lowered once in the frame where its branch ends; the other branch ends in a fail-closed sentinel;
    `Exits` and the name-keyed walk dropped; `ifWithLoop` and all three `LoopBody` break shapes covered.
  - Value `?:` keeps `requireNativeBoundary` and `requireOneRepresentation` (F5, C6).
  - D8 added: assignment to a pattern variable bound on the new path is `JULC0140`; `ArmUpdates` by element (F1, C7);
    Q5 added.
  - `while` conditions (C5, S4): reads inside the condition are supported (D3); guards excluded explicitly.
  - Diagnostics plumbing (C10): D7 names `diagnostics.json`, the catalogue, rejection sites, `DiagnosticTextTest`,
    the hints and comments; Compatibility lists the tests that flip.
  - Verification (F8, S5, S6, C9, C11): agreement required for supported shapes; I1 as a property on every compile;
    linearity with non-trivial joins, boolean `?:` and sequential guards; nullary variant on every failing path;
    strict-prefix passes cited; G1 split into three gates with VERSION bumps and a steer; a javac-filtered generator
    mode with a no-divergence property.
  - References (S7): paraphrases marked; 6.3.1.6, 6.4, 14.22, 15.7.1, 15.23–15.25 added; 15.20.2 quoted.
  - S4 named cases (lambda block, switch-arm `yield`, `while` condition, outer variable in a nested loop) added to the
    harness list; S9's frame wording and D4's `return`/`yield`/`break` note applied.
  - R1 measured and recorded.
