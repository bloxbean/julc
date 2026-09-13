# ADR-042: Strict-prefix sharing of native Value conversions (O8)

**Date:** 2026-09-13
**Status:** Implemented and locally validated on `feat/114-value-conversion-motion` (stacked on ADR-041's `feat/112-integer-case-dispatch`); independent review pending
**Issues:** [#114](https://github.com/bloxbean/julc/issues/114) (O8), research decision [#102](https://github.com/bloxbean/julc/issues/102), parent [#77](https://github.com/bloxbean/julc/issues/77)
**Governing decisions:** ADR-032 O7/O8/O15 (typed native Value boundary, conversion motion, representation-aware sharing), ADR-036 (PIR-to-PIR pass placement before UPLC generation), ADR-015 (strict typed boundaries)

## Context and current behavior

ADR-032 O7 gave native Values a distinct `JulcValue` type. Converting ledger `Data` into a
native Value is explicit: `NativeValueLib.fromData(d)` or `Builtins.unValueData(d)`, both of
which lower to the PV11 builtin `UnValueData`. The builtin is partial (it validates the map:
ByteString keys of bounded length, strictly ordered and duplicate-free policies and tokens,
non-empty token maps, non-zero `Int128` quantities) and its cost grows with the number of
`Data` nodes.

Every conversion written in source is emitted as written. A program that converts the same
`Data` twice pays the conversion twice:

```java
static BigInteger repeated(PlutusData data, byte[] policy, byte[] token) {
    return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data))
            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data)));
}
```

Issue #102 measured the hand-written alternative (`JulcValue value = fromData(data)` once) as
observationally equivalent and cheaper, deferred automatic sharing because `UnValueData` is
partial and JuLC had no use analysis, and documented explicit binding as the workaround. #114
asks for a typed analysis that hoists one `UnValueData`, sinks `ValueData`, or removes adjacent
conversions without changing partial-conversion failures or observable ordering.

In PIR the two spellings are `App(Builtin(UnValueData), Var d)` and
`App(Var(org.julclang.stdlib.lib.NativeValueLib.fromData), Var d)`, where the library wrapper
is an outer `Let` bound to exactly `(lam mapData : Data [(builtin unValueData) mapData])`.
Native operations are outer `Let`-bound lambda chains
(`lookupCoin = (lam policyId (lam tokenName (lam value …)))`), so a call site is a spine of
applications whose head is a once-bound lambda.

## Goals and non-goals

Goals:

- Share one `UnValueData` conversion of a variable across a scope at the safe profile whenever
  doing so cannot change any result, trace, failure point or failure text, and pin exactly
  when the pass applies.
- Keep NONE and BASELINE byte-identical, and keep every program without repeated conversions
  byte-identical at every level.
- Produce, for the motivating shape, the same bytes as the hand-written workaround.
- State the cost bound on paths that do not benefit.

Non-goals (recorded so they are not reintroduced without their own proof):

- Sinking `ValueData` to a boundary and cancelling `ValueData(UnValueData(d))`. There is no
  consumer shape in the corpus, and ADR-032 forbids the cancellation because `UnValueData`
  canonicalises and fails on non-canonical input while `d` would not.
- Sharing Data-side `ValuesLib` computations (repeated `assetOf`/`lovelaceOf`). That is O15's
  territory and a separate ADR.
- Replacing `ValuesLib` Data operations by native lookups. Rejected by ADR-032 as a blanket
  conversion because failure behaviour on non-canonical Data changes.
- General common-subexpression elimination, or any untyped UPLC peephole guessing that a
  `Data` term holds a valid Value.

## Invariants and proof

Definitions, for a scope term `S` and a variable `x` not rebound between `S` and a use:

- `C(x)` is a conversion of `x`: `[(builtin unValueData) x]`, or `[w x]` where `w` is bound
  exactly once in the program by a `Let` whose value is exactly
  `(lam p : Data [(builtin unValueData) p])`.
- A term is **trivial** when its evaluation cannot fail, trace, or run user code: a variable,
  a constant, a builtin, a lambda, or an application spine whose head is a builtin applied to
  fewer arguments than its value arity (`BuiltinSemantics.find(fun).valueArity()`) or a
  once-bound `Let` lambda chain applied to fewer arguments than its depth, with every argument
  trivial. The CEK machine collects builtin arguments unchecked until saturation (`CekValue.VBuiltin.applyArg` stores the argument; types are checked in `executeBuiltin`), and applying a
  lambda chain to fewer arguments than its depth only builds a closure.
- `C(x)` **leads** `S` when it is the first non-trivial evaluation on every path through `S`:
  `S` is `C(x)`; or `S = [f a]` and `C(x)` leads `f`, or `f` is trivial and `C(x)` leads `a`
  (function position evaluates first); or `S = let y = v in b` and `C(x)` leads `v`, or `v` is
  trivial, `y ≠ x` and `C(x)` leads `b`; or `S` is a conditional or match and `C(x)` leads
  its condition or scrutinee. Lambdas, traces, errors, recursive bindings, constructor
  builds and branch bodies never lead.

**Rule.** For every scope `S` in pre-order, if some variable `x` has at least two
conversions in `S` (outside any rebinding of `x`) and `C(x)` leads `S`, emit
`let v = C(x) in S[C(x) := v]` with a fresh `v` of type `NativeValue`, then continue below
the new binding.

**Soundness.** Let `S'` be the rewritten scope. Evaluating `S` performs a trivial prefix,
then `C(x)`. Evaluating `S'` performs `C(x)` first (a strict `Let` is `[(λv. body) C(x)]`),
then the same trivial prefix with `v` in place of the leading `C(x)`. The trivial prefix
cannot fail, trace, or observe anything, so moving `C(x)` in front of it is unobservable.
If `C(x)` fails, both `S` and `S'` fail at that builtin, on the same input, with the same
message and the same trace prefix. If it succeeds with value `V`, every later `C(x)` in `S`
is the same deterministic builtin on the same immutable `x` and yields `V`; `S'` reads `v`
instead. Later occurrences inside lambdas (loop bodies) are replaced too: they are evaluated
after the scope's binding, and their result is the same `V`. Rebinding is respected: uses
under a `Lam`, `Let`, `LetRec`, match binder or pattern variable named `x` are not visited.
The binding is well-scoped: `C(x)` is free in `x` and, for the wrapper spelling, in the
wrapper name `w`; the only binder the leading-prefix walk crosses is a trivial `Let`, and it
refuses to cross a `Let` binding either `x` or `w`, so the inserted binding always sits inside
the scope of both. (An independent review found the `w` half missing: with `x` bound outside
the library lets, as every `@Param` is, the binding was hoisted above the wrapper and the
generator failed with an unbound variable; pinned by two regression tests.)
Because the inserted binding is itself non-trivial, a second variable can only be shared
below it, so the relative order of distinct conversions is preserved; both `TWO_VARS`
malformation orders pin this.

**Failure-text neutrality.** Unlike ADR-041, no new failure point exists: the only builtin
that can fail is the one that already ran first. The test asserts the failure text is
identical on Java, Truffle and Scalus for every malformed input.

**Cost.** A path that reaches a second occurrence saves one full conversion. A path that
reaches only the leading occurrence (an untaken branch, a short-circuited `&&`, an empty
loop) pays the binding: one lambda, one application and one variable lookup, i.e. three
machine steps (48,000 CPU and 300 memory under `cardano-node-11.0.1`) per shared binding,
minus whatever the removed wrapper call cost. Converting even the smallest valid Value
(`[(builtin unValueData) VALID]` minus a bare constant) costs 512,690 CPU and 256 memory, so
one saved conversion pays for ten missed ones; the fixture deltas below are net of the
binding (−464,690 when the binding is new, −512,690 when a now single-use wrapper let is
inlined away). FLAT size moves by a few bits either way: the binding adds a lambda, an
application and one variable use per site and removes one conversion application per
extra site, and the UPLC optimiser then inlines a wrapper let that became single-use.
Observed: −2 to +1 bytes across the fixtures; the test pins that observed bound, not a law.
Memory on a successful path can rise by up to 100 units per binding (observed +44 to +88)
while CPU drops.

## Decision

1. Add `ValueConversionSharingPass` (`julc-compiler/pir`), a PIR-to-PIR pass with the rule
   above, run immediately before `PairDestructuringPass` in all three compiler entry points
   (`compile`, `compileMethod`, `compilePirToProgram`), threading source positions through.
   Rule provenance `pv11.o8.value-sharing`. Gate: exact `PLUTUS_V3_PV11` target, a level with
   `pv11SafeRulesEnabled()`, and `ProtocolCapability.VALUE_CONSTANTS`.
2. Share only through a strict prefix. Branch bodies never lead: a conversion leading both
   arms of a conditional is evaluated once per path either way, so sharing it above the
   conditional only adds a binding (`BOTH_BRANCHES` stays byte-identical).
3. Count static sites (at least two, outside rebindings); do not require every path to reach a
   second site. This is what makes loop bodies profitable: one leading conversion before a
   loop and one inside it hoist the per-iteration conversion out (`LOOP_HOIST`), at the
   bounded cost above on an empty loop.
4. Keep the pass purely syntactic on typed PIR: no cost model, no protocol-dependent constants
   beyond the gate. `NONE`/`BASELINE` are the identity by gate.
5. `PairDestructuringPass` and the new pass share `PirHelpers.mapChildren`; the ADR-036 pass is
   otherwise unchanged.

The advisor-recommended broadening, treating a saturated **total** builtin over trivial
arguments as trivial (so `lookupCoin(p, t, v)` would not block a later variable), is
deliberately not taken in this slice: it depends on PIR variable types being exact at runtime
(a class of bug JuLC has had at entrypoints before ADR-015), and its only effect on a
correctly typed program is budget. It is recorded as an open question with the exact shape
it would unlock (`INTERLEAVED`).

## Alternatives rejected

- **Both-branch sharing.** Sound, but every path evaluates exactly one of the two
  conversions, so it is a pure loss of three machine steps per path. Rejected by measurement.
- **Path-sensitive counting** (require a second occurrence on every path). Never loses, but
  it forbids hoisting a per-iteration conversion out of a loop, which is the largest gain
  measured (−1,538,070 CPU for three iterations). Rejected; the loss bound is stated instead.
- **UPLC-level CSE on `unValueData` applications.** Untyped UPLC cannot tell a conversion of
  an immutable variable from one of a recomputed term, and de Bruijn indices under inserted
  lambdas make positions fragile. ADR-032 forbids untyped Value peepholes.
- **Sink `ValueData` / cancel adjacent conversions.** No consumer in the corpus; cancellation
  changes failure behaviour on non-canonical Data (ADR-032).
- **Costed profile only.** The rule needs no cost model; its legality is syntactic and its
  loss bound is protocol-independent (three machine steps). Enabled at the safe profile like
  O1–O5.

## Affected stages and modules

- `julc-compiler`: new `pir/ValueConversionSharingPass`; `PirHelpers.mapChildren` (shared
  child mapping, previously private to `PairDestructuringPass`); `JulcCompiler` wiring at the
  three pass sites. No new PIR node, no `UplcGenerator` change, no VM change.
- `julc-benchmark`: `OptimizationEvidenceMain.o8ValueSharingComparison` and the manual
  control replace the BASELINE-only research experiment; `O8ValueSharingBenchmarkTest`.
- Decompiler: unchanged. The shared binding is an ordinary strict `Let` and decompiles as a
  local variable (`ValueConversionSharingDecompileTest` pins it).
- Docs: ADR-032 audit row and Milestone 4 bullet, release notes, stdlib guide, compiler
  developer guide.

## Compatibility and risks

- **Hash impact.** Only safe-profile output of programs that convert the same `Data`
  variable at least twice in leading position changes. No shipped `julc-examples` validator
  uses `NativeValueLib` or `Builtins.unValueData` (`rg` over both example trees: no hits),
  and `ValuesLib` is pure Data, so the external corpus is byte-identical; the Maven-local
  examples run recorded in the evidence document confirms it. The benchmark aggregate and
  every earlier optimisation fixture are unchanged.
- **Failure contract.** Unchanged; asserted textually on all three VMs.
- **Loss bound.** Three machine steps per shared binding on a path that reaches no second
  occurrence; pinned in the test with the bound measured from the pinned profile rather than
  hard-coded.
- **Source maps.** Positions of the root scope map to the new `Let`; replaced sites map to
  the variable. Source-map builds skip the UPLC optimiser and may be a few bytes larger; they
  are not the deployable artifact.
- **Determinism.** Fresh names `#value-N` are allocated in traversal order; the test compiles
  every fixture twice and compares bytes.
- **Direct PIR.** `compilePirToProgram` applies the same pass (ADR-036/038 promise), so a
  consumer that builds PIR with repeated conversions sees them shared at the safe profile.

## Measurements

Java VM, `cardano-node-11.0.1` PV11 costs, source maps off, before = safe-profile output at
`5e32bbcd` (ADR-041 head) captured into `optimization/o8-pre-change-bytes.txt`, so every delta
is O8 alone. Truffle budgets are asserted equal to Java; Scalus agrees on results, traces and
failure text. Full table in `adr/evidence/042-value-conversion-sharing.md`.

| Fixture | Bytes | Path | CPU before → after | Δ CPU |
|---|---:|---|---:|---:|
| REPEATED (two wrapper calls) | 50 → 48 | valid | 2,508,650 → 1,995,960 | −512,690 |
| SHARED (manual control) | 48 → 48 | valid | 1,995,960 → 1,995,960 | 0, same hash as REPEATED after |
| MIXED (raw builtin + wrapper) | 47 → 48 | valid | 2,460,650 → 1,995,960 | −464,690 |
| TRACE_FIRST | 65 → 63 | valid | 2,696,148 → 2,183,458 | −512,690, trace order kept |
| BRANCH_ONLY / BOTH_BRANCHES | unchanged | all | unchanged | 0 (not shared) |
| TWO_VARS | 38 → 39 | same (both pairs run) | 3,860,174 → 2,930,794 | −929,380 |
| TWO_VARS | | different (`&&` short-circuits) | 2,122,137 → 2,218,137 | +96,000 (two bindings) |
| SEQUENTIAL (a at body, b at inner let) | 82 → 83 | valid | 4,726,124 → 3,796,744 | −929,380 |
| INTERLEAVED (a shared, b blocked) | 77 → 77 | valid | 4,630,124 → 4,165,434 | −464,690 |
| SHADOW | 75 → 75 | valid | 3,809,387 → 3,344,697 | −464,690 |
| LOOP_HOIST | 107 → 105 | three tokens | 6,950,215 → 5,412,145 | −1,538,070 |
| LOOP_HOIST | | no tokens | 2,236,279 → 2,236,279 | 0 |
| LOOP_BODY | 114 → 112 | three tokens | 10,368,912 → 8,926,842 | −1,442,070 |
| SINGLE (control) | 33 → 33 | all | unchanged | 0 |

Every malformed and non-canonical input (not a map, zero quantity, unsorted policies) fails
with the same text before and after; most failing paths are cheaper (the wrapper call is
gone), a few pay one binding (+48,000 CPU) when the failure happens before the second
occurrence. Memory on successful paths rises by up to 100 units per binding while CPU drops
(see Cost above).

## Implementation milestones

One milestone, delivered on `feat/114-value-conversion-motion` stacked on ADR-041:

1. Pass and wiring (`ValueConversionSharingPass`, `PirHelpers.mapChildren`, three
   `JulcCompiler` sites) with goldens captured at `5e32bbcd` before any change.
2. Fixture matrix and direct-PIR probes on Java, Truffle and Scalus; benchmark comparison and
   manual control; decompiler check.
3. Two independent agent reviews (pass legality; tests, goldens, evidence, docs), fixes folded
   in: the wrapper-scope guard, observable guard probes, non-canonical inputs, corrected cost
   and size statements.
4. Full build, Blaster lock check, Maven-local publish and external `julc-examples` run;
   stacked PR; release-plan update.

## Verification

- `O8ValueSharingTest` (`pair-case-backends`, Java/Truffle/Scalus): 13 fixtures × 4 levels ×
  source maps off/on against the goldens; rule provenance; conversion-site counts on the
  emitted PIR; byte identity with the manual workaround; shared bindings never wrap a lambda;
  direct-PIR shapes (order, trace and error opacity, `Let`/`LetRec`/`Lam` rebinding, exclusive
  branches, leading and saturated conditions, twice-bound wrapper alias); loss bound
  measured from the pinned profile.
- `O8ValueSharingBenchmarkTest`: BASELINE vs PV11_SAFE with Java and Truffle, byte identity
  with the manual control, strict CPU decrease on both inputs.
- `ValueConversionSharingDecompileTest`: the shared binding decompiles as a local variable.
- Existing suites: compiler, `pairCaseTest`, benchmark, stdlib, testkit, decompiler, full
  build; Blaster lock unchanged (compiles at `baseline`); external `julc-examples` against
  Maven local.

## Open questions

- Total-builtin prefix broadening (see Decision): would share `INTERLEAVED`'s second variable
  and the common `have = lookup(C(a)); need = lookup(C(b)); … C(a) … C(b)` shape when the
  second conversion is not the first step of any enclosing scope. Needs a stated trust
  argument for PIR typing or a runtime-type-preserving proof.
- Reach. The rule fires only where the repeated conversion leads its scope. The natural loop
  shape `contains(fromData(out), fromData(required))` converts the per-iteration `out` first,
  so the loop-invariant `required` is not hoisted, and cannot be under invariant 3: with both
  malformed, hoisting would change which input fails first, and on an empty list it would
  fail where the original succeeds. The stdlib guide tells users to bind once before the
  loop. A rule that hoists a loop-invariant conversion only when the loop body is proven to
  evaluate it on every iteration *after* an unconditional first evaluation would need a
  loop-shape proof and is left open.
- O15 Data-side sharing (`ValuesLib` repeats) is where the shipped corpus has repeated work;
  it needs its own ADR because `ValuesLib` failure and canonicalisation differ from native.
  (ADR-044 generalised this pass to record field projections and the fields prefix, the
  Data-side shapes the corpus repeats most, and made every PIR rule individually
  switchable; `ValuesLib` computations remain unshared.)
- Rule provenance is recorded when the pass fires anywhere, including in a helper that dead
  code elimination later removes; this matches every earlier rule and is noted, not changed.
  (ADR-044 changed this for the sharing pass: units inside a lambda binding the live program
  never references are neither counted nor rewritten, so an uncalled helper records nothing;
  a live helper the optimiser later inlines still does.)
