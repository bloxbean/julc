# ADR-047: Typed BLS12-381 values, native scalar and point lists, and explicit multi-scalar multiplication (O11)

**Date:** 2026-09-14
**Status:** Implemented and locally validated on `feat/117-bls-types` (stacked on ADR-046's `feat/116-array-literals`); independent agent reviews applied; maintainer review pending
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
over blst) and the conformance suite already pin the semantics this ADR needs. MSM validates
every scalar first (`multiScalarMul: scalar too large (N bytes, max 512)`: a scalar must fit
512 bytes of two's complement, so the range is −2^4095 to 2^4095−1), then zips the two lists
to the shorter length (extra entries are ignored, conformance `multiScalarMul-09/10`); an
empty list on either side gives the identity (`06`–`08`); the scalar bounds are `13a`–`13d`.

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
value where Data is required. `Builtins.g1Points`/`g2Points` require every element to be a
point of their group.

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
   element is not what the list claims.
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
  the same bytes. The two MSM signatures change from `PlutusData` to the typed lists; no
  program could call the old ones from source.
- **Hash impact.** None for any program in the censused corpus or the golden suites (no BLS
  users), and none for a BLS program that compiles under both APIs (the types erase, no
  library method was added).
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
- **On-chain.** No artifact with a BLS constant or an MSM call has been submitted to a node
  in this repository yet; the pre-release on-chain gate should include one.

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

The builtin costs about 325 million CPU to enter plus 78 million per point; the chain costs
130 million per point. MSM is smaller from three points and cheaper from seven on this
profile; below that the chain wins. The choice is the author's; nothing rewrites either way.

## Implementation milestones

One milestone on `feat/117-bls-types`, stacked on ADR-046:

1. Probes: MSM equals the chain on Java, Truffle and Scalus; the converters from Data lists;
   empty and uneven lists; the scalar bounds (max and min succeed, one beyond each fails on
   all three VMs); G2; pairing; the diagnostics for every misuse shape; census of the corpus.
2. The markers, the PIR types, the resolver, inference, the boundary and schema exclusions.
3. The retyped `Builtins`/`BlsLib`, the producers, the signature table in the registry.
4. Fixture matrix (12 fixtures, 25 inputs, 4 levels, 3 VMs), the diagnostics test, the
   producer-shape test, the requirements test, the crossover benchmark.
5. Two independent agent reviews; full build, Blaster lock check, publish, external examples
   (additivity census); stacked PR; release-plan update.

## Verification

- `O11BlsTypesTest` (`pair-case-backends`): every fixture at every level, compile-twice
  determinism, source maps; every input on Java, Truffle and Scalus with the expected
  outcome, `true` on every successful path, the pinned failure text on Java and Truffle
  (`scalar too large (513 bytes, max 512)` for both bounds, `UnIData`/`UnBData`/`uncompress`
  for the converters, the O9 `IndexArray` text where the costed profile promoted the chain's
  own list), trace order around MSM, equal budgets on all three backends. Misuse: fourteen
  shapes with their codes and message fragments (`requires G1`, `requires
  NativeList[Integer]`, `received List[Integer], but requires NativeList[Integer]`, the
  boundary, `==`, a Data list, a record). Producers: constants for all-literal lists, `MkCons`
  chains otherwise, the decoders in the converters, never a Data encoder. Requirements: the
  PV11 builtins, `BLS_CONSTANTS`, `uncompress`; a pre-PV11 target fails closed.
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
