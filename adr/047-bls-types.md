# ADR-047: Typed BLS12-381 values, native scalar and point lists, and explicit multi-scalar multiplication (O11)

**Date:** 2026-09-14
**Status:** Merged in PR #150; G1/G2 native point-list constant and MSM node acceptance and pinned-budget regressions recorded in ADR-052 evidence
**Issues:** [#117](https://github.com/bloxbean/julc/issues/117) (O11), research decision [#96](https://github.com/bloxbean/julc/issues/96), parent [#77](https://github.com/bloxbean/julc/issues/77)
**Governing decisions:** ADR-032 O11 (a typed BLS API before any fusion; never fuse over `byte[]`/`PlutusData`), the O11 deferral evidence (`adr/evidence/032-o11-bls-msm-deferral.md`), ADR-032 O7 (the opaque native-type discipline this ADR reuses), ADR-045/046 (intrinsic producers keep library bindings additive)

## Context and current behavior

`Builtins` and `BlsLib` modelled every BLS12-381 value as `byte[]`: G1 and G2 points,
Miller-loop results and compressed encodings were one Java type and one PIR type
(`ByteStringType`), so `g1Add(compressedBytes, point)` or `g2Add(g1, g1)` compiled and failed
only at runtime (`Bls12_381_G1_add: expected bls12_381_G1_element, got bytestring`). The two
PV11 multi-scalar multiplications took `PlutusData` for both lists, but the builtins need a
native `list integer` and a native `list bls12_381_G1_element`; a `JulcList<BigInteger>` is a
`list data` of `I` nodes, so the API could not be called from source at all. The O11 deferral
recorded exactly this: retyping the signatures alone would look typed while emitting the
wrong representation, and recognising chains over interchangeable `byte[]` could mix groups
and reorder failures.

The VM (`Bls12381Builtins`, shared by Truffle, delegating to `julc-bls`'s `BlsOperations`
over blst) and the conformance suite already pin the semantics this ADR needs.
Multi-scalar multiplication (MSM) validates every scalar first (`multiScalarMul: scalar too large (N bytes, max 512)`: a scalar must fit
512 bytes of two's complement, so the range is −2^4095 to 2^4095−1), then zips the two lists
to the shorter length (extra entries are ignored, conformance `multiScalarMul-09a/09b/10a/10b`);
an empty list on either side gives the identity (`06a/06b/07/08`); the scalar bounds are
`13a`–`13d`.

## Goals and non-goals

Goals:
- Distinct PIR and Java types for G1 points, G2 points and Miller-loop results, so that a
  wrong group, a compressed byte string where a point is required, or a point where Data is
  required is a compile-time error (the O7 discipline: `JULC0041`/`JULC0042`).
- Native scalar lists and point lists with typed producers that can only build the
  representation the builtins take: no `IData`/`BData` wrapper can be inserted.
- Explicit `g1MultiScalarMul`/`g2MultiScalarMul` over those types, with the pinned scalar,
  empty-list and unequal-length semantics stated and tested on Java, Truffle and Scalus, and
  the cost crossover against the manual scalar-multiply-and-add chain measured on the pinned
  profile.
- A migration path for the experimental `byte[]` API.

Non-goals:
- Automatic fusion of `scalarMul`/`add` chains into MSM (a later gate, ADR-032 O11).
- Any inference of a group from a `byte[]` or `PlutusData`.
- BLS values in datums, redeemers, records or lists of Data (they have no Data encoding;
  compress to `byte[]` at a boundary and uncompress on the other side).
- Any change to the cost or bytes of a program that does not use the BLS surface.

## Invariants and proof

**Types.** `JulcG1`, `JulcG2` and `JulcMlResult` are opaque Java markers in `julc-core` (as
`JulcValue` is); `JulcScalars`, `JulcG1Points` and `JulcG2Points` are the native lists. PIR:
`NativeG1Type`, `NativeG2Type`, `NativeMlResultType`, and `NativeListType(elem)` with `elem`
one of `IntegerType`, `NativeG1Type`, `NativeG2Type`. All are native-opaque
(`PirType.isNativeOpaque`/`containsNativeOpaque`), so every existing isolation rule applies
unchanged: no assignment to or from Data or `byte[]`, no `==`, no Data container (list,
array, record, `Optional`), no `compileMethod` or validator boundary (`JULC0042`), no
`wrapEncode`/`wrapDecode`; the blueprint schema, the strict boundary generator and the
verification type projection treat them as unsupported exactly as they treat `JulcValue`.

**Representation.** A `NativeG1Type` term evaluates to a `bls12_381_G1_element` constant and
nothing else, because every producer is a builtin whose UPLC result type is that universe
(`uncompress`, `hashToGroup`, `add`, `neg`, `scalarMul`, MSM) and no conversion from a byte
string exists except `uncompress`. Likewise G2 and Miller results. A `NativeListType(Integer)`
term evaluates to a `list integer` constant because its only producers are the `scalars`
intrinsic (an integer-list constant when every element is a constant, otherwise `MkCons` of
the elements over the empty `list integer` constant) and `scalarsFromList` (a recursion that
decodes each `I` node with `UnIData` and conses it onto the empty native list, in order);
point lists likewise over `uncompress`/`hashToGroup` results and `UnBData` then `uncompress`
of each element. The producers are `Builtins` intrinsics, so no Data encoder can be inserted
between an element and its list.

**Builtin typing.** `TypeInferenceHelper` gives every BLS builtin its group type (add, neg,
scalarMul, hashToGroup, uncompress, MSM: the group; millerLoop, mulMlResult: the Miller
result; compress: `ByteString`; equal, finalVerify: `Bool`) and a BLS or native-list
constant its type. The registry's argument check (`validateNativeValueArguments`, now a
per-builtin signature table) rejects at compile time a byte string, Data, a Data list or a
wrong-group value where a point, a Miller result or a native list is required, and a native
value where Data is required. `Builtins.scalars` requires every element to be an integer and
`g1Points`/`g2Points` a point of their group (the all-constant path checks the constants'
universes once more before it builds the list constant); the converters require a Data list
whose static element type is the one they decode (`List[Integer]`, `List[ByteString]`) or
untyped Data. The same comparison guards the two routes that are internal to a class: an
argument to a same-class helper against the helper's parameter type, and a `return`
expression against the declared return type (a lambda's own `return` is exempt), so a point
cannot be laundered into a `byte[]` helper parameter or out of a `byte[]` method.

**Semantics pinned.** MSM: all scalars validated before any zip (the ones beyond the shorter
list included), then `Σ scalar_i · point_i` over `min(len)` pairs, the empty sum being the
identity; the compile-time types make "point must be a G1 element" and "scalar must be an
integer" unreachable from source. Evaluation order and failure points are the builtins' own;
this ADR adds no rewrite and no pass.

**Additivity.** No program in the example corpus or the golden suites uses `BlsLib` or a BLS
builtin. Retyping the Java signatures changes no PIR for a program that compiled before
(the types erase), the producers are `Builtins` intrinsics (no library binding is added,
ADR-045's lesson), and `BlsLib` gains no method, so the `NONE`-level and source-map bytes of
programs importing `BlsLib` are unchanged.

## Decision

1. Six opaque marker types in `julc-core`'s `core.types`: `JulcG1`, `JulcG2`, `JulcMlResult`,
   `JulcScalars`, `JulcG1Points`, `JulcG2Points`; four PIR types: `NativeG1Type`,
   `NativeG2Type`, `NativeMlResultType`, `NativeListType(elem)`. All native-opaque.
2. `Builtins` BLS signatures retyped: `bls12_381_G1_add/neg/scalarMul/hashToGroup/uncompress`
   return `JulcG1` and take `JulcG1` where a point is required (`compress` takes `JulcG1`
   and returns `byte[]`; `equal` takes two `JulcG1`); G2 likewise; `millerLoop(JulcG1,
   JulcG2)` and `mulMlResult` return `JulcMlResult`, `finalVerify` takes two;
   `bls12_381_G1_multiScalarMul(JulcScalars, JulcG1Points)` and the G2 form. `BlsLib` mirrors
   them.
3. Six producers as `Builtins` intrinsics: `scalars(BigInteger...)`,
   `scalarsFromList(JulcList<BigInteger>)`, `g1Points(JulcG1...)`,
   `g1PointsFromCompressed(JulcList<byte[]>)`, `g2Points(JulcG2...)`,
   `g2PointsFromCompressed(JulcList<byte[]>)`. Literal forms become a native list constant
   when every element is a constant and a `MkCons` chain otherwise; the converters decode a
   Data list element by element (`UnIData`; `UnBData` then `uncompress`) and fail where the
   element is not what the list claims. An element or list whose static type is wrong is
   rejected at compile time (`JULC0041`).
4. No fusion, no pass, no optimization rule, no switch: this is a typing and lowering ADR;
   the crossover is documented so that authors choose MSM knowingly.
5. The experimental `byte[]` BLS API is replaced; see Compatibility.

## Alternatives rejected

- **Retyping only the Java signatures** (the deferral's own warning): the MSM lists would
  still be Data lists and the point types would still be byte strings in PIR, so the API would
  look typed while the isolation checks had nothing to check.
- **`JulcList<JulcG1>` with a native representation inferred from the element type.** Two
  representations behind one Java type at every list use site (`get`, `map`, `filter`, the
  boundary), each needing its own lowering; a distinct marker per native list keeps the
  representation visible in the type and the existing `JulcList` lowering untouched.
- **A typed marker for compressed encodings** (`JulcG1Compressed`). Compressed points are
  ordinary byte strings on chain and in datums; a marker would only move the `uncompress`
  failure, not remove it.
- **Fusion of `scalarMul`/`add` chains in this ADR.** ADR-032 gates fusion on this typed
  surface existing first; the measured crossover (seven points on the pinned profile) also
  means a fusion rule would need a cost model, so it is a costed-profile decision for a later
  ADR.
- **Deriving the scalar list from a `JulcList<BigInteger>` implicitly** at the MSM call. The
  decode is a loop with its own failure (`UnIData` on a non-integer element) and cost; it
  must be visible in the source.

## Affected stages and modules

- `julc-core`: `JulcG1`, `JulcG2`, `JulcMlResult`, `JulcScalars`, `JulcG1Points`,
  `JulcG2Points` (new markers).
- `julc-compiler`: `PirType` (four native records; `isNativeOpaque`/`containsNativeOpaque`),
  `TypeResolver` (the six names), `LibraryMethodRegistry.pirTypeName` (`G1`, `G2`,
  `MlResult`, `NativeList[...]` in diagnostics), `TypeInferenceHelper` (builtin and constant
  typing), `StrictBoundaryGenerator` (unsupported at the boundary);
  `O11BlsTypesFixtures`/`O11BlsTypesTest`.
- `julc-blueprint`: `SchemaGenerator` (no schema for a native BLS value).
- `julc-verification`: `ContractTypeProjection` (unsupported).
- `julc-stdlib`: `Builtins` (retyped signatures, the six producer stubs), `BlsLib` (retyped),
  `StdlibRegistry` (the per-builtin native signature table, the producers and their
  requirements, the typed lookup for the producers).
- `julc-benchmark`: `OptimizationEvidenceMain.o11MsmCrossoverComparison(n)`,
  `O11BlsMsmBenchmarkTest`.
- Docs: stdlib guide (`BlsLib` section), compiler developer guide, release notes, ADR-032
  catalog row and deferral notes, this ADR and its evidence.

## Compatibility and risks

- **Source compatibility (breaking for the experimental API).** A program that named a BLS
  value as `byte[]` (`byte[] p = Builtins.bls12_381_G1_hashToGroup(...)`, a `byte[]`
  parameter of a helper that receives a point) now fails to compile with `JULC0041`; change
  the declaration to `JulcG1`/`JulcG2`/`JulcMlResult` or use `var`. Programs that used `var`
  for every BLS value (the repository's own `BlsLibTest` style) compile unchanged and produce
  the same bytes. The two MSM signatures change from `PlutusData` to the typed lists; the old
  ones compiled but could not evaluate successfully (the builtins reject Data lists).
- **Isolation coverage and residuals.** Compile-time isolation is enforced at `Builtins`
  and library call sites, variable initializers, record construction, containers, `==`, the
  boundaries, same-class helper arguments and `return` expressions, and (PR #150 review) the
  branches of a conditional expression, loop-local declarations and loop assignments. One
  check, `PirGenerator.checkNativeBoundary`, serves every site: a native type on either side
  of a boundary requires the two types to be equal. A conditional is typed by its `then`
  branch, so its `else` branch is checked against that type (`Conditional else branch
  received G2, but requires G1`), in both orders and for native/Data, native/bytes,
  scalar-list/point-list and native-list/Data-list pairs; a declaration inside a loop body
  (plain, nested or break-aware) is checked as an ordinary initializer; an assignment to an
  accumulator (the loop's only one, or before a `break`) or to a loop-body local is checked
  against the declared type (`Assignment to 'acc' received G2, but requires G1`); a native
  accumulator among several is rejected earlier, at the Data pack of the multi-accumulator
  loop (`Data encoding received G1, but requires Data`), so a point or a native list is
  carried by a loop of its own. A bare nested block inside a loop body is lowered as its
  statements spliced into the body with the block's own declarations renamed apart first.
  The only thing such a block does in Java is end the scope of its declarations; a fresh name
  (`amount'1`: legal in UPLC, impossible in Java) can neither capture a later reference to a
  name the block shadowed (a class constant redeclared as a block local) nor be referenced
  after the block, while every update the block makes to an enclosing variable (an
  accumulator, a loop-body local) and a `break` inside it behave as if the braces were
  absent. The lowering is that of the braceless body byte for byte, which
  `LoopBlockAssignmentTest` asserts at every level for six shapes. Before the review the
  block was delegated to the generic statement generator, which lowered an accumulator
  assignment to its right-hand side and dropped the update (`for (x : xs) { { acc =
  acc.add(x); } }` summed to zero: a miscompile of valid Java); two intermediate lowerings of
  the review were also wrong (splicing without renaming leaked the block's locals; lowering
  the block as a value, the way an `if` branch is, carried only the accumulator out) and are
  recorded in the evidence. An assignment the loop body generators do not bind is
  rejected rather than dropped: in
  expression position (`g2Compress(acc = ...)`), inside a statement delegated to the
  generic generator, or to a name with no declaration in scope (`Assignment to undeclared
  variable 'x'`). Inherited from ADR-032 O7 and unchanged here: a cast (`(JulcG1) data`) is trusted and fails in the CEK;
  a `switch` expression's inferred type is Data, so a native-typed local, argument or return
  fed by one is rejected as a mismatch (spell the switch as a helper method); an instance call
  on a native receiver (`p.equals(q)`) fails as an unbound variable rather than with a native
  diagnostic; the message for a native value in a non-native slot names `Data` as the
  expected type even where the slot is `byte[]` or `BigInteger`.
- **Hash impact.** None for any program in the censused corpus or the golden suites (no BLS
  users), and none for a BLS program that compiles under both APIs (the types erase, no
  library method was added). The loop-body fixes of the review (above) change only programs
  that were miscompiled or accepted with an update dropped: a loop body assigning inside a
  bare nested block now compiles to the bytes of its braceless body, and an assignment in
  expression position or to an undeclared name is rejected.
- **Resolved by ADR-048 (#155): updates to a loop-body local inside an `if` branch.** Found
  while fixing the block lowering and reproduced on `main` (tree `5d9340ad`): inside a loop,
  `BigInteger step = ZERO; if (c) { step = step.add(ONE); } acc = acc.add(step);` compiles
  and the update to `step` is lost (0 instead of 3 over three elements; for-each and while,
  one or several accumulators). An `if` branch is lowered as a value that yields the
  accumulator(s) only. It is a pre-existing miscompile of valid Java, independent of BLS and
  of this change, and is now rejected explicitly by ADR-048. Join points taking the
  assigned locals remain a future design; accumulators declared before the loop are not affected.
- **Generated names in the converters.** The decoding loop of `scalarsFromList`,
  `g1PointsFromCompressed` and `g2PointsFromCompressed` is bound under a fixed name
  (`go__scalars`, `go__g1Points`, `go__g2Points`); the caller's list is applied to the loop
  *outside* that binding, so a user variable of the same name referenced by the argument
  resolves to the user's binding (PR #150 review: the first shape captured it, and a parameter
  named `go__scalars` made the converter receive the loop itself). The `NAME_CAPTURE` fixture
  pins all three converters with same-named parameters, locals and free variables in the
  argument expression.
- **Boundary.** A native BLS value cannot cross `compileMethod`, a validator entrypoint, a
  datum, a redeemer, a record or a Data list (`JULC0042`/`JULC0041`); compress to `byte[]`
  and uncompress on the other side, or send the compressed points in a `JulcList<byte[]>`
  and use `g1PointsFromCompressed`.
- **Failure contract.** The converters add the only new runtime failures: `UnIData` on a
  non-integer scalar element, `UnBData` on a non-bytes point element, and `uncompress` on an
  invalid encoding, each at the element's position in list order, before the multiplication
  runs. MSM's own failures (a scalar outside 512 bytes) are the builtin's and are reachable
  from source through a runtime scalar; a literal scalar outside the bound is not rejected at
  compile time (no fold exists to notice it), it fails at runtime as before.
- **Target legality.** MSM needs the PV11 builtins (a pre-PV11 target fails closed at the
  target check, `JULC0031`); the point-list producers need `BLS_CONSTANTS` (the empty
  `list bls12_381_G1_element` constant), which every target with the BLS builtins has.
- **Scalus.** Evaluates every fixture with the same results and budgets as Java and Truffle,
  including the scalar-bound failures (its failure text differs and is not asserted).
- **`compileMethod` results.** A method whose return type is a BLS value compiles (as for
  `JulcValue`) and its result is a native constant the VM returns; the testkit's Java-value
  extraction does not know these constants. Validators return `boolean`, so this is a
  test-harness matter only.
- **On-chain.** G1/G2 MSM artifacts using source-lowered empty typed native point-list
  constants have been evaluated by Java, backend and direct Haskell and confirmed on the
  developer PV11 node at baseline, safe and costed levels. These do not claim individual
  embedded point literals. Scalar failures, hash/size/budget pins and transaction evidence
  are recorded in [ADR-052 evidence](evidence/052-pre17-release-gates.md).

## Measurements

The explicit G1 MSM against the manual `g1ScalarMul`/`g1Add` chain over the same hashed
points, `cardano-node-11.0.1` PV11 costs, Java VM (Truffle asserted equal), both programs
returning the compressed sum (`O11BlsMsmBenchmarkTest`; full rows in the evidence file):

| Points | Chain CPU / mem / bytes | MSM CPU / mem / bytes |
|---:|---:|---:|
| 1 | 132,232,605 / 2,974 / 31 | 402,916,206 / 3,806 / 41 |
| 2 | 262,466,625 / 4,828 / 52 | 480,810,048 / 5,056 / 56 |
| 3 | 392,700,645 / 6,682 / 74 | 558,703,890 / 6,306 / 72 |
| 6 | 783,258,705 / 11,344 / 130 | 792,385,416 / 10,056 / 118 |
| 7 | 913,444,725 / 12,898 / 149 | 870,279,258 / 11,306 / 134 |
| 8 | 1,043,630,745 / 14,452 / 167 | 948,173,100 / 12,556 / 149 |

Both programs hash every point first (`hashToGroup`, 52.5 million CPU each on this profile),
so the measured increments are 78 million CPU per point for MSM and 130 million for the
chain. Net of the hashing, the builtin's own cost is its pinned intercept of 322 million plus
25 million per point, and the chain's own cost about 77 million per point (`scalarMul` 76.4
million plus `add`). MSM is smaller from three points and cheaper in CPU from seven on this
profile whether or not the points are hashed; below that the chain wins. The choice is the
author's; nothing rewrites either way.

## Implementation milestones

One milestone on `feat/117-bls-types`, stacked on ADR-046:

1. Probes: MSM equals the chain on Java, Truffle and Scalus; the converters from Data lists;
   empty and uneven lists; the scalar bounds (max and min succeed, one beyond each fails on
   all three VMs); G2; pairing; the diagnostics for every misuse shape; census of the corpus.
2. The markers, the PIR types, the resolver, inference, the boundary and schema exclusions.
3. The retyped `Builtins`/`BlsLib`, the producers, the signature table in the registry.
4. Fixture matrix (18 fixtures, 34 inputs, 4 levels, 3 VMs), the diagnostics test, the
   producer-shape test, the requirements test, the crossover benchmark.
5. Two independent agent reviews (element typing of `scalars` and the converters, the
   same-class helper and return routes, a `false`-valued fixture, the validator boundary,
   empty converters, a negative literal scalar, nested producers, the cost attribution in
   prose); full build, Blaster lock check, publish, external examples (additivity census);
   stacked PR; release-plan update.

## Verification

- `O11BlsTypesTest` (`pair-case-backends`): every fixture at every level, compile-twice
  determinism, source maps; every input on Java, Truffle and Scalus with the expected
  outcome, `true` on every successful path, the pinned failure text on Java and Truffle
  (`scalar too large (513 bytes, max 512)` for both bounds, `UnIData`/`UnBData`/`uncompress`
  for the converters, the O9 `IndexArray` text where the costed profile promoted the chain's
  own list), trace order around MSM, equal budgets on all three backends; a disagreeing
  pair returns `false`; empty converters against a non-empty other list; a negative literal
  scalar on the constant path; a producer nested in another producer's element and a
  converter used twice in one method. Misuse: twenty-eight shapes with their codes and
  message fragments (`requires G1`/`G2`/`Integer`, `requires NativeList[Integer]`, `received
  List[Integer], but requires NativeList[Integer]`, `received List[ByteString], but requires
  List[Integer]`, the `compileMethod` boundary for points, scalars, Miller results and point
  lists, `==`, a Data list, a record, a helper parameter, a return), a validator entrypoint
  with a point datum and a native-list redeemer (`JULC0042`), and a native-typed method that
  uses a block lambda with its own `return`. Producers: constants for all-literal lists,
  `MkCons` chains otherwise, the decoders in the converters, never a Data encoder.
  Requirements: both PV11 MSM builtins, `BLS_CONSTANTS`, `uncompress`; a pre-PV11 target
  fails closed.
- `O11BlsMsmBenchmarkTest`: equivalence and the crossover for one to eight points.
- Existing suites: `BlsLibTest` (unchanged, `var` style), `LoweringRequirementsTest`,
  `NativeValueTypingTest`, `LibraryDiscoveryCleanupTest`, `julc-blueprint`,
  `julc-verification`; full build; Blaster lock; external `julc-examples` at the default and
  costed levels.

## Open questions

1. Whether a compile-time check of literal scalars against the 512-byte bound is worth a rule
   (it would be the first BLS fold); left out, the runtime failure is exact.
2. Whether fusion of `scalarMul`/`add` chains should ever be automatic given the crossover;
   if so it belongs to the costed profile with the fixture shapes here as its equivalence
   corpus (ADR-032 O11, still deferred).
3. A boundary helper that compresses a point list back to a `JulcList<byte[]>` (the inverse
   of `g1PointsFromCompressed`) has no user yet.
