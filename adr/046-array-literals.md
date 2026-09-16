# ADR-046: Typed array literals and literal folding (O10)

**Date:** 2026-09-14
**Status:** Implemented and locally validated on `feat/116-array-literals` (PR #149 against `main`, rebased by merge after ADR-045 merged; the corrected call-site objective of ADR-045's second review applies to this domain through `LiteralFoldPass`: `LOCAL_LIST` no longer folds, `LOCAL_LIST_ONCE` added); two independent agent reviews and the maintainer's review of the merged head applied (`var` element-type inference with `JULC0012` for mixed elements; structural measure of list literals); merge pending
**Issues:** [#116](https://github.com/bloxbean/julc/issues/116) (O10), research decision [#106](https://github.com/bloxbean/julc/issues/106), parent [#77](https://github.com/bloxbean/julc/issues/77)
**Governing decisions:** ADR-032 O10 (array constant folding, "static-cost" class, out-of-range indexes stay runtime failures, no fold beyond the size objective), ADR-043 (`JulcArray`'s element representation and the `IndexArray` failure contract), ADR-045 (the literal-fold machinery, its objective and additivity), ADR-036 (pass placement before UPLC generation), ADR-015 (strict typed boundaries)

## Context and current behavior

`JulcArray<T>` (CIP-138, PV11 only) is a Data-backed array: it is obtained from a
`JulcList<T>` with `list.toArray()` or `JulcArray.fromList(list)` (`ListToArray`), `get`
lowers to `IndexArray` followed by the decode the element type requires
(`PirHelpers.wrapDecode`), `length` to `LengthOfArray`. There is no way to write an array
down: a lookup table is spelled `JulcList.of(a, b, c).toArray()`, which builds the list with
three `MkCons` applications, encodes each element, and converts it, on every evaluation.

ADR-032 deferred O10 because the source subset had no typed producer for an array literal,
and matching the untyped `IndexArray(ListToArray(list), i)` shape at the UPLC level could
prove neither the element universe nor the failure point of an invalid index. ADR-045
supplied the machinery this ADR needs: a literal-fold pass with pinned semantics shared with
the VM, an exact size objective, per-rule switches and an additivity discipline.

UPLC has array constants (`(con (array data) [...])`, uni tag 12), the FLAT encoder and
decoder round-trip them, all three VMs evaluate programs embedding them, and
`UplcTargetValidator` requires the `ARRAY_CONSTANTS` capability (the PV11 target) for any
program that does. Nothing compiled today embeds one.

## Goals and non-goals

Goals:

- A typed, source-level array literal, `JulcArray.of(a, b, ...)`, with exactly the
  representation `JulcArray<T>` has today (Data-encoded elements), legal on the PV11 target.
- Fold `ListToArray` of a literal list into an array constant, `LengthOfArray` of an array
  constant into its length, and `IndexArray` of an array constant at a literal index into the
  element (and the decode `get` wraps around it, when the element has the expected shape), at
  the safe profile, with the VM's own code deciding results and failures.
- Keep every program in the censused corpus and every golden suite byte-identical at every
  level (Wave 4 is additive for what ships today; a program elsewhere that already converts a
  `JulcList.of` literal to an array does gain the constant), and keep an out-of-range index a
  runtime failure at the same point with the same text.

Non-goals (recorded so they are not reintroduced without their own proof):

- A second, native element representation (`array integer`, `array bytestring`) behind the
  same `JulcArray<T>` type. This is a deliberate reading of #116's prerequisite "exact element
  representation with no implicit Data wrapping": `JulcArray<T>` has only ever been an
  `array data`, so the Data encoding of an element is the declared representation, not an
  implicit one, and the literal adds no wrapping that `list.toArray()` did not already have.
  The prerequisite is met by keeping one representation, not by introducing a native one. It would save one decode per access but split `JulcArray<T>` into
  two representations that the type system cannot tell apart at a use site, exactly the
  ambiguity ADR-032 O7 removed for Values; if wanted, it needs its own marker type and ADR.
- `MultiIndexArray`: unreleased at PV11, never emitted (ADR-032, ADR-043).
- Sharing a repeated `LengthOfArray` on one let-bound array (ADR-032's fourth O10 candidate):
  a sharing rule in ADR-044's sense, not a fold; left open.
- Folding decodes of Data constants that were in the program already, or the generic
  `MkCons`/`IData` constant folding those would need: both would move the bytes of existing
  programs at the default level.

## Invariants and proof

**The pinned semantics.** `org.julclang.core.ArraySemantics` implements `LengthOfArray`
(total), `ListToArray` (total, universe-preserving) and `IndexArray` (fails outside
`0 <= i < length`, with `IndexArray: index out of range: I` for an index beyond the machine
integer range and `IndexArray: index I out of bounds for array of size N` otherwise) over
`Constant`. `ArrayBuiltins` in the Java VM (shared by Truffle) delegates the three to it; the
compiler fold calls the same methods. The delegation changed no behaviour (VM suites and the
conformance run unchanged; `ArraySemanticsTest` pins the texts).

**The producer.** `JulcArray.of(a, b, ...)` (a static factory on the core interface, off-chain
a `JulcArrayImpl`) lowers in `StdlibRegistry`'s typed lookup to
`ListToArray(MkCons(wrapEncode(a), MkCons(wrapEncode(b), ... MkNilData ())))`: the list literal
`JulcList.of` emits, converted. Its requirement is the `ListToArray` builtin (the PV11 target),
registered under the simple class name and served for the fully qualified one too (the
registry's requirement lookup now falls back to the simple name, which also fixes
`JulcArray.fromList`'s requirement under the qualified spelling).
A native Value element is rejected by `wrapEncode` with `JULC0041`, as in every Data-backed
container ("mixed-representation rejection"). The element representation is the one
`JulcArray<T>` already has, so `get` and `length` are unchanged, and declaring the element
type (`JulcArray<BigInteger> t = JulcArray.of(...)`) is what makes `get` decode it, exactly
as for `list.toArray()`.

**Literals.** `ArrayLiteralFoldPass` extends the ADR-045 machinery (now the abstract
`LiteralFoldPass`, of which `ValueLiteralFoldPass` is the other domain) with two literal
shapes: a *list literal*, the `MkCons` chain over Data-encoded literal elements exactly as
`wrapEncode` spells them over constants (`IData(i)`, `BData(b)`,
`BData(EncodeUtf8(s))`, the Bool `IfThenElse(c, ConstrData(1, []), ConstrData(0, []))`, a
Data constant, or `ListData` of a nested list literal), read to a `list data` constant with
the Data the runtime would build; and a once-bound local holding one. A negated integer
literal (`BigInteger.valueOf(-5)`) is a runtime subtraction in PIR today and blocks the read,
as it does for ADR-045 (spell `new BigInteger("-5")`).

**The fold.** In the domain `ListToArray`, `LengthOfArray`, `IndexArray`, a saturated call
over literals is replaced by its result when `ArraySemantics` succeeds and the result's FLAT
encoding is not longer, in bits, than the term it replaces (ADR-045's objective in its
call-site form: a constant counts as itself, a list literal chain as it stands, each nested
variable a reference, a literal local as a variable reference unless the call consumes every
remaining occurrence of a local bound directly to a literal, in which case it counts as the
term its binding holds measured the same way, and an alias never as the constant it
names). A call the
semantics reject (an out-of-range or over-wide literal index) stays exactly as written and
fails at runtime with the builtin's text; a runtime index keeps the access and embeds the
array constant. Rule `pv11.o10.array-literal-fold`; gate: exact PV11 target,
`pv11SafeRulesEnabled()`, the `ARRAY_CONSTANTS` capability, the per-rule switch.

**Decodes over produced elements.** `get` wraps `IndexArray` in a decode; folding the access
alone would leave `unIData(<Data constant>)` behind. The pass therefore also folds
`UnIData`, `UnBData`, `UnListData`, `UnMapData`, `UnConstrData`, `FstPair` and `DecodeUtf8`
applied to a constant *this pass produced* (from an `IndexArray` fold or a previous decode of
one; an identity set), and the Bool form's `EqualsInteger(<produced tag>, 1)`, when the
constant has the shape the builtin accepts, by the builtin's structural semantics (the same
checks `DataBuiltins` makes; `DecodeUtf8` with the JDK's strict decoder, as the VM). A
mismatched shape (a byte string under `unIData`, invalid UTF-8) stays and fails at runtime.
Restricting the decode folds to produced constants is what keeps the rule additive: a Data
constant that was in the program before, bare or through a literal local, is never touched
(pinned by probes), so no direct-PIR program and no existing fixture changes.

**Soundness.** Every argument of a folded call is a value, so no evaluation, trace or failure
is skipped or reordered; the result is what the builtin computes at runtime, by the same
code; a list literal read to a constant is the value the chain evaluates to (`MkCons`,
`MkNilData`, `IData`, `BData`, `EncodeUtf8`, `ConstrData`, `ListData` are total on constants
and the Bool form's condition is a constant). A decode folded over a produced element is the
value the decode builtin returns on it. Failure: the only partial builtin in the domain is
`IndexArray`, and a call it rejects is never folded; a decode over a mismatched element is
never folded. Budgets never increase: a fold removes at least one builtin evaluation and its
applications for one constant step.

**Objective.** Every fold in the pass, the Bool comparison included, is measured. Array
literals of small elements are shorter than the chain they replace (the chain holds the same
constants plus a wrapping and a cons per element; probed for zero to eight elements), but an
array constant carries each element as CBOR Data, chunked in FLAT, so a wide byte-string
element outgrows its raw constant plus wrapping: a single byte-string element folds up to
128 bytes and stays from 200 bytes on (probed at 1, 32, 64, 128, 200, 255, 256 and 512
bytes; the decision matches the encoder at every width; fixtures `ELEMENT_ONCE` and
`WIDE_ELEMENT_ONCE`). Decoded elements are
always shorter than the decode of their Data constant; the one case that can go either way is
a list decode of a produced element, which folds for lists of at most one element and stays
from two on (each element of a `list data` constant is a separate FLAT byte string). A
literal local is measured as a reference at the call site (ADR-045's second review
established the measure; this pass inherits it through `LiteralFoldPass`), so a list local
that stays live elsewhere is never copied into an array constant: `LOCAL_LIST` stays whole
(91 bytes either way) while its once-used twin `LOCAL_LIST_ONCE` folds to one constant
(48 → 6). A local whose only remaining occurrences the call consumes dies with the fold (the
optimiser drops the binding; list literal chains are pure) and is credited, once, with the
term its binding holds, measured the same way: a constant as itself, a list literal chain as
it stands with each nested variable a reference or a dying local in turn (a dead binding
takes its own variable occurrences with it, so the set of dying locals is a fixed point).
Nothing is ever measured as an expanded `list data` constant: the review's second round
found the dying list local of `xs = JulcList.of(b, b); a = xs.toArray()` credited as its
expanded constant beside a live 256-byte `b`, 309 → 845 bytes (`SHARED_LIST_ELEMENT` now
stays; `LIST_ELEMENT_ONCE`, where `b` and `xs` both die, folds). The review found the expanded measure counting a shared 256-byte local
twice for `JulcArray.of(b, b)` beside a runtime use of `b` and approving the copy (306 →
825 bytes with identical results); `SHARED_ELEMENT` pins that the conversion now stays
(306 bytes either way), `SHARED_ELEMENT_ONCE` that two elements over one dying binding
still stay (the array would hold two copies against one), `ELEMENT_ONCE` that a once-used
element is credited and folds.

**Additivity.** No program compiled before this ADR contains an array constant, and no
program in the example corpus, the Blaster fixtures, the in-repo example module or any earlier
golden suite converts a list literal to an array (census in the evidence document). The
decode folds only ever see constants this pass produced. A program outside the corpus that
already spells `JulcList.of(...).toArray()` or `JulcArray.fromList(JulcList.of(...))` does
change bytes at the safe profile (the `LOCAL_LIST_ONCE` and `FROM_LIST` fixtures are that
shape; `LOCAL_LIST`, whose list local stays live for `size()`, is left as written, since the
conversion would copy the list into an array constant beside the chain);
the additivity claim is about the censused corpus and the golden suites, whose byte identity
with the rule enabled the evidence document's repository-validation section records.

## Decision

1. `ArraySemantics` in `julc-core` is the single implementation of the three array builtins;
   `ArrayBuiltins` delegates to it (`MultiIndexArray` stays the experimental VM-only stub).
2. `JulcArray.of(a, b, ...)` is the typed array literal: the `JulcList.of` list literal
   converted with `ListToArray`, Data-encoded elements, PV11 only.
3. `ArrayLiteralFoldPass` folds literal calls of the three builtins and the decodes over the
   elements it produces at `PV11_SAFE` and `PV11_COSTED` on the PV11 target, under the
   ADR-045 objective, recording `pv11.o10.array-literal-fold`; the fifth switchable rule.
4. The shared machinery of ADR-045 and this ADR lives in `LiteralFoldPass`; both domains run
   first among the PIR-to-PIR passes (Values, then arrays), before ADR-042/044 sharing,
   ADR-043 promotion and pair destructuring.
5. No native element representation, no `MultiIndexArray`, no change at `NONE`/`BASELINE`.

## Alternatives rejected

- **A native `array integer` literal.** Saves one `unIData` per access but creates a second
  representation of `JulcArray<BigInteger>` the compiler cannot distinguish at a use site; the
  Data-backed literal keeps one representation and folds the decode away for literal indexes
  anyway. Recorded as the shape a future marker type (`JulcIntArray`, say) would need its own
  ADR for.
- **Folding at the UPLC level** (`IndexArray(ListToArray(...), i)`): the deferral's reason
  stands; the PIR fold has the element types and the failure contract.
- **Folding every `unIData(<Data constant>)`.** Sound, but it fires on hand-built PIR and on
  any future source route to Data constants, and it is a different rule; restricting decodes
  to produced constants keeps this ADR's footprint exact.
- **Reading `IfThenElse` on a runtime condition or `MapData` elements.** Not literals; left
  unfolded (a map element keeps the access as a runtime decode).

## Affected stages and modules

- `julc-core`: `ArraySemantics` (new), `JulcArray.of`, `ArraySemanticsTest`.
- `julc-vm-java`: `ArrayBuiltins` delegates (Truffle shares it through `BuiltinTable`).
- `julc-stdlib`: `StdlibRegistry` typed lookup for `JulcArray.of` and its registration
  (the `ListToArray` requirement); `JulcArrayTest` on-chain and off-chain cases.
- `julc-compiler`: `LiteralFoldPass` (new abstract base, the ADR-045 machinery),
  `ValueLiteralFoldPass` (now a domain of it), `ArrayLiteralFoldPass` (new), pipeline
  placement in `JulcCompiler` (three sites), `CompilationContext` switchable rule list;
  `O10ArrayLiteralFixtures`/`O10ArrayLiteralFoldTest`, `NativeValueTypingTest` (array
  element isolation), `OptimizationConfigurationTest`.
- `julc-benchmark`: `OptimizationEvidenceMain` O10 comparisons, `O10ArrayLiteralBenchmarkTest`.
- Docs: stdlib guide, compiler developer guide, release notes, ADR-032 catalog row.

## Compatibility and risks

- **Hash impact.** None for any program in the censused corpus or the golden suites. A
  program elsewhere that already converts a `JulcList.of` literal to an array changes bytes and
  hash at the safe and costed profiles (it gains the array constant); programs that adopt
  `JulcArray.of` are new.
- **Pass ordering.** The array fold runs before ADR-043's promotion, so a `ListToArray` that
  promotion inserts at `PV11_COSTED` is never folded; reordering the pipeline would change
  costed bytes and must be treated as a hash-moving change.
- **Identity-keyed decode folds.** The produced-constant set is keyed by node identity and
  relies on `remember` returning the original node for an equal rebuild; a refactor that
  rebuilt equal nodes would silently stop the decode folds (a correctness-neutral loss, but a
  byte change), which the fixture counts would catch.
- **Target legality.** `JulcArray.of` requires `ListToArray` (PV11); a pre-PV11 target fails
  closed at the target check (`JULC0031`), the capability gate behind it is defended by
  `UplcTargetValidator`.
- **Failure contract.** Unchanged: an invalid index fails at `IndexArray` with the same text
  whether the array is a folded constant or a converted list, at the same point (after the
  index is evaluated; the array is a value either way).
- **Public API.** `JulcArray.of`, `ArrayLiteralFoldPass.RULE`, `ArraySemantics`,
  `LiteralFoldPass` (abstract; `ValueLiteralFoldPass.RULE` unchanged); additive.
- **Scalus.** Decodes array constants from FLAT and agrees with Java and Truffle on results,
  budgets and failure outcomes for every fixture.
- **On-chain.** No artifact with an embedded array constant has been submitted to a node in
  this repository yet; the pre-release on-chain gate should include one (with ADR-045's Value
  constant).

## Measurements

Java VM, `cardano-node-11.0.1` PV11 costs, source maps off, `PV11_SAFE` with the rule off
versus on (the only difference is O10). Truffle and Scalus budgets are asserted equal to
Java. Full table in `adr/evidence/046-array-literals.md`.

| Fixture | Bytes | CPU before → after |
|---|---:|---:|
| LENGTH (`JulcArray.of(1, 2, 3).length()`) | 35 → 6 | 1,105,723 → 16,100 |
| GET_LITERAL (`t.get(1)`) | 42 → 6 | 1,238,594 → 16,100 |
| GET_RUNTIME (`t.get(i)`, constant embedded) | 49 → 34 | 1,435,338 → 625,598 |
| GET_RUNTIME, index 3 / −1 / 2^63 (fail at `IndexArray`, same text) | | 1,414,594 → 604,854 |
| OUT_OF_RANGE_LITERAL (`t.get(5)`, stays, fails) | 42 → 27 | 1,201,850 → 392,110 (failing) |
| BYTES (`k.get(0).length`) | 44 → 14 | 1,059,361 → 118,200 |
| STRING (`s.get(1).equals("cde")`) | 58 → 23 | 1,260,219 → 129,100 |
| BOOL (`if (f.get(1))`) | 75 → 13 | 1,592,357 → 96,100 |
| NESTED_LIST (`rows.get(1).size()`) | 107 → 61 | 3,044,746 → 1,374,987 |
| LOCAL_LIST (`xs.toArray()`, `xs` still walked by `size()`: stays) | 91 → 91 | unchanged |
| LOCAL_LIST_ONCE (`xs.toArray()`, the list's only use) | 48 → 6 | 1,592,057 → 16,100 |
| FROM_LIST (`JulcArray.fromList(JulcList.of(1, 2)).length()`) | 28 → 6 | 881,224 → 16,100 |
| TWO_ARRAYS (`a.get(0).add(b.get(1))`) | 66 → 6 | 2,129,298 → 16,100 |
| HELPER_GET (constant passed to a helper, access stays) | 40 → 31 | 1,110,095 → 572,854 |
| RUNTIME_ELEMENT (nothing literal) | unchanged | unchanged |

Benchmark shape (`fee(tier)`: a literal fee table indexed by a runtime tier): 57 → 46 bytes;
every path, the two failing ones included, saves the list construction and conversion
(1,435,338 → 625,598 CPU on a valid tier); the all-literal `middle()` is one constant.

## Implementation milestones

One milestone on `feat/116-array-literals`, stacked on ADR-045:

1. Probes: array constants evaluate identically on Java, Truffle and Scalus; every producer
   shape compiles to `ListToArray` over the `JulcList.of` chain; a native Value element is
   rejected; census of the corpus.
2. `ArraySemantics` with `ArrayBuiltins` delegating; `JulcArray.of`; the registry lowering.
3. `LiteralFoldPass` extracted from ADR-045's pass; `ArrayLiteralFoldPass` with the list
   literal reader and the produced-element decode folds; the switch.
4. Fixture matrix (26 fixtures × 4 levels × rule off/on × 3 VMs), direct-PIR probes,
   semantics test, stdlib and typing tests, benchmark comparisons.
5. Two independent agent reviews (the Bool fold's objective check, the requirement key under
   the qualified class name, an unchecked cast, failing-path budgets, `MultiIndexArray`
   assertions, wording); full build, Blaster lock check, publish, external examples
   (additivity census); stacked PR; release-plan update.
6. The maintainer's review of the merged head, two rounds. Round one (P1: `var a =
   JulcArray.of(...)` typed the elements Data and the access failed at runtime; P2: a list
   literal measured as its expanded constant approved copying a shared 256-byte local, 306 →
   825 bytes): element-type inference with `JULC0012` for mixed elements, the structural
   measure in `LiteralFoldPass`, fixtures `SHARED_ELEMENT`, `SHARED_ELEMENT_ONCE`,
   `ELEMENT_ONCE`, `WIDE_ELEMENT_ONCE`, the element-width probe, docs. Round two (P1: the
   encodings cannot tell a wrapped integer from a user's `Builtins.iData(x)`, so a Data element
   was decoded; P2: a dying list local was credited as its expanded constant, 309 → 845 bytes):
   the generator records the arguments' source types on the lowered literal, the credit is
   dependency-aware (a dying local measured as the term its binding holds), fixtures
   `SHARED_LIST_ELEMENT` and `LIST_ELEMENT_ONCE`, the `dataElements` case. Round three (a
   nested `JulcList.of` element typed `JulcList<PlutusData>` under `var`): list literals
   record their element types too, read for array-literal elements only. Round four (the
   same through a chained access on the literal): the declared result type of a dispatched
   access is kept with the term it produces.

## Verification

- `O10ArrayLiteralFoldTest` (`pair-case-backends`): 26 fixtures at every level with the rule
  off and on; NONE/BASELINE byte-identical either way; provenance exactly where expected;
  array builtin call sites counted before and after; strictly smaller bytes and a different
  hash when a fold fired, identical bytes otherwise; the expected result of every successful
  path; result, trace and failure-text equality and equal budgets on Java, Truffle and
  Scalus; CPU and memory never higher; compile-twice determinism; source maps carry the same
  folds. Direct-PIR probes: list literals bare and through a local, a runtime element, every
  `wrapEncode` element form, length and in-range index folds, three invalid indexes staying
  and failing identically, decodes over produced elements only (bare and local Data constants
  untouched, mismatched element and invalid UTF-8 staying, the Bool form), BASELINE and the
  switch, positions, the list-decode objective across sizes; a pre-PV11 target fails closed.
- `ArraySemanticsTest`, `JulcArrayTest` (`of` on-chain with a runtime element, all-literal at
  the default level, off-chain), `NativeValueTypingTest` (`JulcArray.of(emptyValue())` is
  `JULC0041`), `OptimizationConfigurationTest` (the fifth switchable id).
- `O10ArrayLiteralBenchmarkTest`: rule off versus on with Java and Truffle on the table shape
  and the all-literal shape.
- Existing suites: `julc-core`, `julc-vm-java` (with `PlutusConformanceTest`), `julc-stdlib`,
  `julc-compiler` (the O8/O9/O14/O15 suites and goldens), benchmark, decompiler; full build;
  Blaster lock; external `julc-examples` at the default and costed levels.

## Open questions

- A native element representation (`array integer`) behind a distinct marker type: saves the
  decode per runtime access; needs a type ADR of its own.
- Sharing a repeated `LengthOfArray` or a repeated literal access on one let-bound array
  (ADR-044's rule over a new unit class).
- Map elements (`JulcArray<JulcMap<...>>`) and negated integer literals are not read as
  literals; the access stays a runtime decode.
- `var t = JulcArray.of(...)` infers the element type from the elements, as javac does: the
  generator records the arguments' source types (the types `wrapEncode` encoded them with) on
  the lowered literal, and `TypeInferenceHelper` types the local and a chained access from
  that record. The encodings are not read back: `IData(x)` is also what a user's
  `Builtins.iData(x)` lowers to, so shape inference would decode a Data element (the review's
  second round). Elements of different types (javac would infer a common supertype the
  subset cannot represent) are rejected with `JULC0012` (the review found the first version
  typing the elements as Data, so `increment(a.get(0))` failed at runtime;
  `varLocalOfAnArrayLiteralInfersTheElementType` pins integer, string, boolean, nested-list
  and Data elements, the explicit declaration, a chained access and the empty literal). An
  empty literal under `var` has Data elements (`length()` works, any access fails at runtime
  as it would for the typed empty array). A nested `JulcList.of(...)` element keeps its Java
  type: the generator records a list literal's source element types at its own lowering and
  reads them when the literal is an element of an array literal (recursively), so `var rows =
  JulcArray.of(JulcList.of(1))` is `JulcArray<JulcList<BigInteger>>` and
  `increment(rows.get(0).get(0))` returns 2 (the review's third round found it returning Data;
  `nested`, `doublyNested`, `nestedData` and the rejected `mixedNested` pin it). The same
  holds through a chained access on the literal itself,
  `JulcArray.of(JulcList.of(1)).get(0).get(0)`: the generator keeps the type the registry
  declares for a dispatched access with the term it produces, so the next access in the chain
  is typed by it and not by the structure of the lowering (the fourth round found the
  intermediate `get` re-inferred as `JulcList<PlutusData>`; `chainedNested` and
  `chainedNestedData` pin it). Only the
  array literal reads those records: a `var` local of a bare `JulcList.of(...)` keeps the
  existing `JulcList<PlutusData>` typing, since retyping it would change every such local and
  every lambda over an inline list literal in existing programs, a decision of its own.
- Conservative gaps: a `ListToArray` that ADR-043's promotion inserts at `PV11_COSTED` runs
  after this pass and is never folded; a decode reached through a `Let` alias of a produced
  element (`PlutusData d = t.get(0); unIData(d)`) is not folded because the decode's argument
  is a variable; the produced-constant set is not cleared between fixed-point rounds (it only
  ever holds constants this pass made, so nothing is gained by clearing it).
- On-chain submission of an artifact with an embedded array constant (pre-release gate).
