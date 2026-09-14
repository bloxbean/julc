# ADR-045: Typed native Value literals and literal folding (O14)

**Date:** 2026-09-13
**Status:** Implemented and locally validated on `feat/119-value-literals` (stacked on ADR-044's `feat/120-projection-sharing`); two independent agent reviews applied (one blocking finding fixed); maintainer review pending
**Issues:** [#119](https://github.com/bloxbean/julc/issues/119) (O14), research decision [#108](https://github.com/bloxbean/julc/issues/108), parent [#77](https://github.com/bloxbean/julc/issues/77)
**Governing decisions:** ADR-032 O7/O14 (typed native Value boundary, literal folding without algebraic identities), ADR-042/044 (PIR-to-PIR rule placement, per-rule switches), ADR-036 (pass placement before UPLC generation), ADR-015 (strict typed boundaries)

## Context and current behavior

ADR-032 O7 gave native Values the opaque `JulcValue` type and `NativeValueLib` the seven
PV11 builtins behind it: `insertCoin`, `lookupCoin`, `union`, `contains`, `scale`, `fromData`
(`UnValueData`) and `toData` (`ValueData`). A native Value can only be *obtained*: from ledger
Data through the partial `UnValueData`, or by inserting into a Value one already has. There
is no way to write one down. The idiom for the empty Value is
`Builtins.unValueData(Builtins.mapData(Builtins.mkNilPairData()))`: three builtin calls at
runtime for a constant, and a requirement such as "one NFT of policy P plus two ADA" is a
chain of `insertCoin` calls over it, executed on every evaluation.

ADR-032 deferred O14 (folding literal Value operations) for two reasons, both fixed here: no
typed literal producer existed, and the compiler had no pinned reference semantics for the
builtins that it could share with the VM. The UPLC optimizer's constant folder does not touch
Value builtins, and ADR-032 forbids algebraic identities (scale by one, union with empty,
containment as equality) without a proof against zero and negative quantities, ordering,
duplicates and strict evaluation.

UPLC has a Value constant (`(con value [...])`, uni tag 13, `Constant.ValueConst` in
`julc-core`), the FLAT encoder and decoder round-trip it, the Java, Truffle and Scalus VMs
evaluate programs that embed it, and `UplcTargetValidator` requires the
`VALUE_CONSTANTS` capability (the PV11 target) for any program that does. Nothing compiled
today embeds one.

## Goals and non-goals

Goals:

- A typed, source-level way to write a native Value literal, with the builtins' own
  semantics for zero and negative quantities and key limits, legal exactly on the PV11 target.
- Fold a call of a Value builtin whose arguments are all literals into the literal it
  evaluates to, at the safe profile, exactly when the result and the absence of failure are
  statically certain, with the VM's own code deciding both.
- Keep every program compiled today byte-identical at every level (Wave 4 is additive).
- State the artifact objective a fold must satisfy and measure it, not assume it.

Non-goals (recorded so they are not reintroduced without their own proof):

- Algebraic identities on non-literal operands (`scale(1, v)`, `union(v, empty())`,
  `lookupCoin(p, t, empty())` with a runtime key, `contains(a, b) && contains(b, a)` as
  equality). Each discards or reorders nothing today only because none is applied.
- Folding the empty-Value idiom `unValueData(mapData(mkNilPairData()))` or any other Data
  constructor on constants. The generic constant folder runs at `BASELINE`; extending it
  moves historical bytes. `Builtins.emptyValue()` is the replacement.
- Inferring a Value from an arbitrary Data literal. A Data literal is decoded only by running
  the strict `UnValueData` semantics on it; a literal the builtin would reject is left as the
  call it is.
- Data-side `ValuesLib` folding, Array literals (O10), BLS (O11).

## Invariants and proof

**The pinned semantics.** `org.julclang.core.NativeValueSemantics` implements the seven
builtins over `Constant.ValueConst`, `byte[]`, `BigInteger` and `PlutusData`: entries sorted
by policy then token (unsigned lexicographic), zero quantities removed, quantities in the
signed 128-bit range, keys of at most 32 bytes, `UnValueData` strict (no normalisation: any
deviation fails). A failing call throws `EvaluationFailure` with the builtin's exact failure
text. `ValueBuiltins` in the Java VM (shared by Truffle) unwraps its arguments and delegates;
the compiler fold calls the same methods. The delegation changed no behaviour: the VM suites
and the 999-case conformance run are unchanged, and `NativeValueSemanticsTest` pins the
messages and the canonical properties. The quirks that matter are preserved exactly:
`InsertCoin` skips the key-length and range checks when the quantity is zero (the reference's
`long-key-zero` vectors), `ScaleValue` returns the empty Value for a zero scalar before any
range check, `ValueContains` rejects a negative quantity in either operand before comparing.

**Literal producers.** Three `Builtins` intrinsics, registered in `StdlibRegistry` and
inlined at the call site. `Builtins.emptyValue()` lowers to `PirTerm.Const(ValueConst([]))`,
typed `NativeValueType`, with `LoweringRequirements.capability(VALUE_CONSTANTS)`, so it fails
closed on a target without Value constants. `Builtins.singletonValue(policyId, tokenName,
quantity)` lowers to `InsertCoin(policyId, tokenName, quantity, emptyValue)`;
`Builtins.lovelaceValue(quantity)` is the singleton with the empty policy and token name
(both also require the `InsertCoin` builtin). With runtime arguments they are the builtin
call they spell, with literal arguments they fold. Their semantics are therefore the
builtins' (a zero quantity yields the empty Value even for a 33-byte key; a non-zero quantity
needs keys of at most 32 bytes and a quantity in range, and fails with `InsertCoin`'s text
otherwise). They are intrinsics, not `NativeValueLib` source methods, because every method
of a referenced `@OnchainLibrary` class is compiled and bound in the program (the optimiser
drops the unused ones at `BASELINE` and above): three new library methods add 28 bytes to
the `NONE`-level and source-map artifacts of every program importing `NativeValueLib`, which
Wave 4's additivity does not allow. `TypeInferenceHelper` maps a `ValueConst` constant to
`NativeValueType`, so the Data/native isolation checks of ADR-032 O7 see a literal as a
native Value.

**Literal calls.** In PIR after generation, a *literal* is a `Const`, or a variable bound
exactly once in the program by a `Let` whose value is a literal (a local such as
`JulcValue base = Builtins.singletonValue(...)` after its value has folded, or
`JulcValue f = base` aliasing such a local). A *literal call* is a saturated application
of one of the seven builtins to literals, spelled as the bare builtin or through a *wrapper*:
a variable bound exactly once to a lambda chain, every parameter of which occurs in the
body, whose body is that builtin applied, by position, to the chain's parameters and
constants. Every `NativeValueLib` method is exactly such a wrapper; the producers are bare
builtin spines at the call site. A name bound twice anywhere is neither a wrapper nor a
literal. At a wrapper call site every argument must be a literal, whether or not the body
uses it: the strict application evaluates them all, so an unused non-literal argument (a
decode of runtime Data, a trace, an error) must keep running. The first review found the
wrapper path substituting only the used arguments; both guards now hold.

**The fold.** `ValueLiteralFoldPass` runs first among the PIR-to-PIR passes, bottom-up, to a
fixed point. A literal call is replaced by `Const(result)` exactly when
`NativeValueSemantics` succeeds on the literals and the objective below holds. A call the
semantics reject is left exactly as written; a call with a non-literal argument is never
touched. Provenance `pv11.o14.value-literal-fold` is recorded when a fold fires. Gate: the
exact PV11 target, `pv11SafeRulesEnabled()`, the `VALUE_CONSTANTS` capability and the
per-rule switch.

**Soundness.** Every argument of a folded call is a value: no evaluation, trace or failure is
skipped or reordered by replacing the call with its result (strictness is vacuous over
values). The result is what the builtin would compute at runtime, by the same code, so the
result value is identical and, since the fold only fires on success, no failure is hidden or
introduced. A wrapper applied to values is a fixed number of beta steps around the builtin
(the UPLC optimizer inlines the same wrappers), so folding through a wrapper is the same
fact. A literal local substituted into a call is the value the `Let` would have bound
(strict `Let` of a constant). A `Trace` or `Error` in argument position is not a literal and
blocks the fold, so trace order and failure points are untouched. Budgets: a literal costs one
constant step (16,100 CPU at the pinned profile); every fold removes at least one builtin
evaluation and its applications, so CPU and memory never increase on any path.

**Objective.** A fold fires only if the FLAT encoding of the literal is not longer, in
bits, than the encoding of the term it replaces, both measured with the FLAT encoder at fold
time. For a bare builtin call the replaced term is the builtin spine over the literals,
exactly what stands in the artifact. For a wrapper call it is the wrapper variable (at the
smallest index) applied to the call-site literals: the shape that stays in the artifact when
the wrapper remains live because another site uses it; if the optimiser inlines the wrapper
instead, the artifact also loses the wrapper body, so this is the conservative bound. A
consequence is that a user wrapper carrying constants in its body (`mk(q) =
insertCoin(P, T, q, empty)`) rarely folds at `mk(5)`, since `mk 5` is shorter than the
resulting literal; `NativeValueLib`'s wrappers carry no constants, and the producers inline
the builtin spine, so the corpus shapes fold. Bits rather than bytes, so a sequence of folds
cannot grow the artifact through rounding; only the artifact's final padding can differ, by
at most seven bits. Value-to-Value, `LookupCoin` and `ValueContains` folds only remove
applications and constant headers and always pass. The Data conversions can go either way:
a Value literal byte-aligns every byte string in FLAT while a Data literal is one CBOR byte
string, so `ValueData` of the empty Value (a 40-bit literal against a 26-bit call) and
`UnValueData` of a Data literal with five or more tokens stay calls. The tests assert that
every fixture whose calls fold is strictly smaller.

**Additivity.** No program compiled before this ADR contains a Value literal or a Data
literal argument to a Value builtin (there is no source route to either), so the rule cannot
fire on it; the new producers are new API. Every earlier golden suite, the Blaster lock and
the external example corpus are byte-identical with the rule enabled (the census in the
evidence document's repository-validation section).

## Decision

1. `NativeValueSemantics` in `julc-core` is the single implementation of the seven Value
   builtins; `ValueBuiltins` delegates to it.
2. `Builtins.emptyValue()`, `Builtins.singletonValue(policyId, tokenName, quantity)` and
   `Builtins.lovelaceValue(quantity)` (intrinsics; a Value constant and `InsertCoin` into it;
   PV11 capability) are the typed literal producers.
3. `ValueLiteralFoldPass` folds literal calls of the seven builtins at `PV11_SAFE` and
   `PV11_COSTED` on the PV11 target under the objective above, recording
   `pv11.o14.value-literal-fold`; it is the fourth switchable rule
   (`CompilerOptions.disableOptimizationRule`).
4. No algebraic identity, no Data-constructor folding, no change at `NONE`/`BASELINE`.

## Alternatives rejected

- **Folding in `UplcOptimizer.constantFold`.** It runs at `BASELINE` and would move historical
  bytes; it also loses the wrapper and literal-local structure that PIR still has.
- **Folding the empty-Value idiom** (`unValueData(mapData(mkNilPairData()))`). Sound and
  cheap, but it changes the bytes of every existing program that uses the idiom at the default
  level; Wave 4 promises additivity. `Builtins.emptyValue()` replaces the idiom for new code.
- **A CPU objective instead of a size objective.** Folding `unValueData` of a large Data
  literal saves its runtime decode but grows the script; the preview keeps the conservative
  script-size objective, as O13 did, and records the trade-off as an open question.
- **Producers as `NativeValueLib` source methods.** The natural home for the API, but every
  method of a referenced library class is compiled and bound, so `empty`, `singleton` and
  `lovelace` in `NativeValueLib.java` added 28 bytes to the `NONE`-level and source-map
  artifacts of the thirteen ADR-042 fixtures (the deployable `BASELINE`/`PV11_SAFE` artifacts
  were unchanged, the optimiser drops the dead bindings). Rejected for Wave 4 additivity;
  intrinsics in `Builtins` add nothing to a program that does not call them. Moving them to
  `NativeValueLib` once binding is reachability-pruned is an open question.
- **Recognising negated integer literals.** `BigInteger.valueOf(-5)` lowers to a runtime
  subtraction in PIR today, so a negative quantity must be spelled `new BigInteger("-5")` to
  be a literal; making the generator fold the negation is a generator change outside O14.
- **Algebraic identities.** Forbidden by ADR-032 without proof; none is needed by the corpus.

## Affected stages and modules

- `julc-core`: `NativeValueSemantics` (new), `NativeValueSemanticsTest`.
- `julc-vm-java`: `ValueBuiltins` delegates (Truffle shares it through `BuiltinTable`).
- `julc-stdlib`: `Builtins.emptyValue/singletonValue/lovelaceValue` and their
  `StdlibRegistry` intrinsics with the capability and builtin requirements;
  `NativeValueLib` unchanged.
- `julc-compiler`: `ValueLiteralFoldPass` (new), pipeline placement in `JulcCompiler`
  (three sites, first among the PIR passes), `TypeInferenceHelper` constant typing,
  `CompilationContext` switchable rule list.
- `julc-benchmark`: `OptimizationEvidenceMain` O14 comparisons, `O14ValueLiteralBenchmarkTest`.
- Docs: stdlib guide, compiler developer guide, release notes, ADR-032 catalog row.

## Compatibility and risks

- **Hash impact.** None for existing programs (additivity, proven by the census). Programs
  that adopt the producers get a Value constant in their artifact at every level and, at the
  safe and costed profiles, folded literals.
- **Target legality.** A Value constant needs the PV11 target; `emptyValue()` on another
  target fails at compilation with the capability diagnostic. The fold never runs there.
- **Failure contract.** Unchanged: a literal call the builtin would reject stays the call and
  fails at runtime with the builtin's text; the fold only replaces successful calls.
- **Public API.** `Builtins.emptyValue/singletonValue/lovelaceValue`,
  `ValueLiteralFoldPass.RULE`, `NativeValueSemantics`; additive.
- **Scalus.** Scalus decodes Value constants from FLAT and agrees with Java and Truffle on
  results and budgets for every fixture; the encoding is also pinned by the FLAT round trip
  and the conformance vectors that spell Value constants in program text.
- **On-chain.** No artifact with an embedded Value constant has been submitted to a node in
  this repository yet; the pre-release on-chain gate should include one.

## Measurements

Java VM, `cardano-node-11.0.1` PV11 costs, source maps off, `PV11_SAFE` with the rule off
versus on (the only difference is O14). Truffle and Scalus budgets are asserted equal to
Java. Full table in `adr/evidence/045-value-literals.md`.

| Fixture | Bytes | CPU before → after |
|---|---:|---:|
| SINGLETON_LOOKUP (`lookupCoin(P, T, singletonValue(P, T, 100))`) | 40 → 7 | 883,863 → 16,100 |
| LOVELACE_UNION (`union(lovelaceValue(5), lovelaceValue(7))`, looked up) | 43 → 6 | 1,789,059 → 16,100 |
| INSERT_CHAIN (two inserts, `toData`) | 44 → 21 | 1,228,092 → 16,100 |
| CONTAINS_TRUE (two literal singletons) | 46 → 5 | 1,746,605 → 16,100 |
| ROUND_TRIP (`fromData(toData(singleton))`, looked up) | 43 → 6 | 1,467,712 → 16,100 |
| LOCAL_LITERAL (`base` local unioned with itself) | 46 → 6 | 1,352,135 → 16,100 |
| KEY_MAX (32-byte key) | 97 → 6 | 883,863 → 16,100 |
| KEY_TOO_LONG (33-byte key, fails at runtime) | 99 → 99 | unchanged |
| QUANTITY_OVERFLOW (2^127, fails at runtime) | 57 → 57 | unchanged |
| UNION_OVERFLOW (singletons fold, union stays and fails) | 79 → 65 | 1,534,220 → 564,372 (failing) |
| RUNTIME_VALUE (`contains(fromData(d), singletonValue(...))`) | 33 → 27 | 1,870,371 → 1,385,447 (holds) |
| MIXED_KEY (runtime key against a literal union) | 57 → 40 | 1,994,645 → 604,525 |
| UNION_CANCEL (`toData(empty)` stays under the objective) | 48 → 9 | 1,487,220 → 97,100 |
| RUNTIME_QUANTITY / EMPTY_LOOKUP (nothing literal) | unchanged | unchanged |

Benchmark shape (`requires(minted)`: containment of runtime Data against a literal
requirement of one NFT plus two ADA): the requirement folds to one constant; the containment
and the Data decode remain; every path is cheaper and the malformed-Data failures are
identical.

## Implementation milestones

One milestone on `feat/119-value-literals`, stacked on ADR-044:

1. Probes: Value constants round-trip FLAT and evaluate identically on Java, Truffle and
   Scalus; every `NativeValueLib` wrapper is one builtin spine over its parameters by position;
   `new byte[]{}` is a constant and `new byte[0]` is rejected by the subset; library methods
   are bound whether or not they are called (which moved the producers into `Builtins`).
2. `NativeValueSemantics` with `ValueBuiltins` delegating; VM suites and conformance unchanged.
3. Producers, the constant typing fix, the fold pass, the switch.
4. Fixture matrix (29 fixtures × 4 levels × rule off/on × 3 VMs), direct-PIR probes,
   semantics test, benchmark comparisons.
5. Two independent agent reviews (the wrapper argument hole, the objective, docs); full build, Blaster lock check, publish, external examples
   (additivity census); stacked PR; release-plan update.

## Verification

- `O14ValueLiteralFoldTest` (`pair-case-backends`): 29 fixtures at every level with the rule
  off and on; NONE/BASELINE byte-identical either way; provenance exactly where expected;
  Value builtin call sites counted before and after; strictly smaller bytes and a different
  hash when a fold fired, identical bytes otherwise; the expected result value of every
  successful path; result, trace and failure-text equality and equal budgets on Java, Truffle
  and Scalus; CPU and memory never higher; compile-twice determinism; source maps carry the
  same folds. Direct-PIR probes: a canonical Data literal folds to exactly the VM's decoding,
  thirteen non-canonical literals stay and fail identically, a wide canonical literal stays
  under the size objective, bare builtins and once-bound wrappers (parameters substituted by
  position) fold, a wrapper carrying constants stays under the call-site objective, a chain
  with an unused parameter is not a wrapper (an error, a trace, a runtime variable or a
  literal in that position all stay, the error and trace observed), shadowed names,
  unsaturated calls, a trace, an error or a runtime variable in argument position block,
  literal locals and aliases feed calls, nested calls fold in one pass, failing literal calls
  (overflow, negative containment, long key) stay, positions move to the literal, the
  objective decision matches the encoder (in bits) for the Data conversions across entry
  counts; a pre-PV11 target fails closed with `JULC0031`. Fixtures `UNUSED_PARAM` (a helper
  with an unused parameter fed a runtime decode: nothing folds, the decode still fails on
  bad Data) and `USER_WRAPPER` (a user wrapper over a bare builtin folds like a library one).
- `NativeValueTypingTest`: a literal assigned to `PlutusData` and a literal inside
  `equalsData` are rejected with `JULC0041`.
- `NativeValueSemanticsTest`: canonical ordering, replacement and removal, key and range
  checks only for non-zero quantities, total lookup, union add/cancel/overflow, containment
  and negative rejection on either side, scale and zero short-circuit, canonical `ValueData`
  and round trip, fourteen `UnValueData` rejections with their exact text.
- `OptimizationConfigurationTest`: the fourth switchable id.
- `O14ValueLiteralBenchmarkTest`: rule off versus on with Java and Truffle on the
  requirement shape and the all-literal shape.
- Existing suites: `julc-core`, `julc-vm-java` (with `PlutusConformanceTest`), `julc-stdlib`,
  `julc-compiler` (goldens of O5/O8/O9/O15, `GoldenUplcTest`), benchmark, decompiler; full
  build; Blaster lock; external `julc-examples` at the default and costed levels.

## Open questions

- A CPU-aware objective for `UnValueData` of larger Data literals (saves the runtime decode
  at the price of script bytes); needs a declared size budget.
- `ValueData(empty)` stays a call because the empty map's Data literal is two bytes larger;
  a source-level `PlutusData` map literal would make this moot.
- Negated integer literals lower to a runtime subtraction; folding the negation in the
  generator would let `BigInteger.valueOf(-5)` be a literal at every level (generator change,
  hash-moving at `BASELINE`).
- Folding the empty-Value idiom for existing programs once the hash-stability policy allows a
  default-level change.
- `NativeValueLib.empty/singleton/lovelace` as the ergonomic spelling once library bindings
  are pruned to what the program reaches (a `NONE`/source-map byte change for every program
  that imports a library).
- On-chain submission of an artifact with an embedded Value constant (pre-release gate).
- The decompiler lifts a Value constant as an opaque constant (`UplcLifter.liftConstant` has
  no `ValueConst` case); it does not fail, but a folded literal decompiles without structure.
- The target registry accepts only the PV11 target today, so the producers' capability gate
  is a second layer behind the target check (a pre-PV11 target fails closed with `JULC0031`
  before any lowering, which is what the tests pin); the capability layer itself is defended
  by `UplcTargetValidator` and the generic lowering-requirement tests, not by an O14 test.
