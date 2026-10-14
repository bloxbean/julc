# ADR-030: ZeroJ BLS12-381 Provider Integration

**Date**: 2026-07-24
**Status**: Proposed (planning only; no runtime implementation is authorized by this ADR)
**Initial dependency target**: ZeroJ `0.1.0-pre10`
**Upstream tracking**:
[ZeroJ issue #22 — Add provider-neutral G1/G2 multi-scalar multiplication to
`Bls12381Provider`](https://github.com/bloxbean/zeroj/issues/22)

---

## Context

JuLC currently implements the 17 original Plutus BLS12-381 builtins and the two
PV11 multi-scalar multiplication (MSM) builtins through
`foundation.icon:blst-java:0.3.2`.

That implementation is functionally useful but tightly coupled to one native
binding:

- `julc-bls` exposes `blst-java` as an `api` dependency;
- `BlsOperations` imports native SWIG types directly;
- hash-to-group has a second, hand-written FFM path because the SWIG string
  binding corrupts arbitrary binary domain separation tags;
- Miller-loop results are serialized to byte arrays but can only be used while
  their native `PT` objects remain in a thread-local cache;
- the parser's `BlsConstantValidator` is also implemented directly with native
  types;
- the Java VM and CLI therefore require the native BLS module even when users
  prefer portability over native performance.

ZeroJ `0.1.0-pre10` now provides two relevant artifacts:

- `com.bloxbean.cardano:zeroj-bls12381:0.1.0-pre10`: pure Java field, curve,
  codec, hash-to-curve, pairing, and provider primitives;
- `com.bloxbean.cardano:zeroj-blst:0.1.0-pre10`: an explicit optional
  `Bls12381Provider` backed by BLST for point and pairing operations, plus
  separate FFM Pippenger MSM implementations.

Both ZeroJ and JuLC target Java 25. The release is published to Maven Central
under the same MIT license family as JuLC.

This ADR evaluates whether ZeroJ can replace JuLC's direct native binding,
identifies the remaining Plutus-specific gaps, and defines an incremental
integration plan.

## Decision summary

JuLC should adopt ZeroJ's BLS primitives and provider boundary, subject to the
acceptance gates in this ADR.

The intended end state is:

1. pure Java BLS through `zeroj-bls12381` is the required, portable default;
2. BLST through `zeroj-blst` is an explicit optional provider;
3. provider selection is immutable and evaluation-scoped, never an implicit
   classpath-dependent switch;
4. JuLC keeps a small Plutus semantics adapter around ZeroJ instead of exposing
   ZeroJ details throughout the CEK machine;
5. Miller-loop results become genuinely opaque, evaluation-local values rather
   than byte arrays backed by a native-object cache;
6. the portable MSM path composes the ZeroJ provider operations, while the
   optional BLST provider may use native Pippenger after JuLC applies the exact
   Plutus bounds, zip, and scalar-reduction rules;
7. the compiler may later fuse eligible PV11 scalar-multiply/add expressions
   into the MSM builtins, but only under a PV11 target and after cost-model
   comparison.

`0.1.0-pre10` is suitable for an integration spike and portable implementation.
It is not a literal dependency swap for all 19 builtins: five operations need a
JuLC adapter or a future extension of the ZeroJ SPI.

---

## Verified ZeroJ `0.1.0-pre10` capabilities

The analysis used the published `v0.1.0-pre10` source tag and Maven Central
metadata, not an unreleased branch.

### Provider behavior

`Bls12381Provider` supplies:

- G1/G2 identity and generators;
- add, negate, and scalar multiplication;
- compressed and uncompressed codecs with curve and subgroup validation;
- SHA-256 and SHAKE-256 hash/encode-to-curve functions;
- pairing-product identity checks;
- a stable provider identifier.

Signed scalar multiplication is normalized modulo the BLS12-381 scalar-field
order by both the pure Java and BLST providers.

Provider choice is deliberately explicit. ZeroJ does not select BLST merely
because `zeroj-blst` is present on the classpath:

```java
var pure = Bls12381Providers.pureJava();
var nativeProvider = BlstBls12381Provider.createDefault();
```

The BLST provider uses the shared pure Java defaults for codecs and
hash-to-curve. Selecting BLST in `pre10` therefore accelerates its overridden
point operations and pairing-product check, but does not make every BLS
operation native.

### Published dependency shape

`zeroj-bls12381` has no runtime dependencies. `zeroj-blst` depends on:

- `zeroj-api:0.1.0-pre10`;
- `zeroj-bls12381:0.1.0-pre10`;
- `foundation.icon:blst-java:0.3.2`.

The BLST module also bundles FFM-accessed native libraries for Linux
`amd64`/`aarch64` and macOS `aarch64`. Its FFM path requires
`--enable-native-access=ALL-UNNAMED`.

This is an improvement over JuLC owning its own FFM loader, but it means BLST
cannot be the only supported configuration or silently selected on every
platform.

### Upstream verification

The following tests passed from the exact `v0.1.0-pre10` checkout on
2026-07-24:

```text
./gradlew :zeroj-bls12381:test :zeroj-blst:test --no-daemon

zeroj-bls12381: 1818 tests, 0 failures
zeroj-blst:       13 tests, 0 failures
```

The suites include codec round trips and invalid-point rejection, RFC 9380
hash-to-curve vectors, field and pairing tests, signed scalar normalization,
and pure-Java/BLST provider parity for representative operations.

These are strong library-level results. They do not replace JuLC's
Plutus-specific conformance suite.

---

## Coverage against the 19 Plutus BLS builtins

| Plutus capability | ZeroJ `pre10` provider coverage | Integration assessment |
|---|---|---|
| G1 add, negate, scalar multiply | Direct | Good fit |
| G1 equality | Common decoded `G1Point` equality | Good fit after validated decode |
| G1 compress/uncompress | Direct codec methods | Good fit |
| G1 hash-to-group | Direct provider default | Good fit; JuLC must retain the 255-byte DST rejection |
| G2 add, negate, scalar multiply | Direct | Good fit |
| G2 equality | Common decoded `G2Point` equality | Good fit after validated decode |
| G2 compress/uncompress | Direct codec methods | Good fit |
| G2 hash-to-group | Direct provider default | Good fit; JuLC must retain the 255-byte DST rejection |
| Miller loop | Available in provider-specific lower-level classes, not the provider SPI | Needs an opaque JuLC representation |
| Multiply Miller-loop results | Available in provider-specific lower-level classes, not the provider SPI | Needs an opaque JuLC representation |
| Final verify of two Miller-loop results | Provider exposes pairing-product identity, not this exact signature | Semantically expressible through a JuLC adapter |
| G1 MSM (PV11) | Generic composition possible; native FFM API exists outside the SPI | Portable fallback plus optional capability |
| G2 MSM (PV11) | Generic composition possible; native FFM API exists outside the SPI | Portable fallback plus optional capability |

The common provider surface directly covers the 14 point, codec, equality, and
hash builtins. The three pairing builtins and two PV11 MSM builtins account for
the remaining integration work.

---

## Plutus semantic gaps that the adapter must close

ZeroJ implements general BLS12-381 primitives. JuLC remains responsible for the
ledger builtin contract.

### Domain separation tag bound

Plutus rejects `bls12_381_G1_hashToGroup` and
`bls12_381_G2_hashToGroup` when the DST is longer than 255 bytes.

ZeroJ correctly implements RFC 9380's oversized-DST reduction. That broader
behavior must not leak into Plutus evaluation. JuLC must check the Plutus bound
before calling the selected provider.

### Scalar domains

Ordinary G1/G2 scalar multiplication accepts arbitrary Plutus integers and
acts modulo the group order. ZeroJ's provider methods match this behavior.

PV11 MSM has a separate input limit:

```text
-2^4095 <= scalar <= 2^4095 - 1
```

Every integer in the scalar list is checked against that signed 512-byte range
before the lists are zipped. Therefore an out-of-range extra scalar must fail
even if the point list is shorter.

JuLC currently validates only the paired prefix. This is a pre-existing
conformance bug and must be fixed before either the pure or native ZeroJ MSM
path is accepted.

After the bound check, each paired scalar must be reduced modulo the group
order. The `pre10` BLST FFM MSM helpers write only 32 scalar bytes and do not
perform the Plutus 512-byte validation. Raw Plutus `BigInteger` values must
never be passed directly to that path.

### Zip and empty-list behavior

MSM uses the shorter of the scalar and point lists. Extra points are ignored;
extra in-range scalars do not contribute; either empty list produces the group
identity.

The adapter, not the provider, owns these rules.

### Point encoding and validation

Program constants use the canonical 48-byte G1 and 96-byte G2 compressed
encodings. Decoding must reject:

- wrong lengths and invalid flag combinations;
- non-canonical field elements;
- off-curve points;
- points outside the prime-order subgroup.

ZeroJ's checked compressed codecs cover these conditions. JuLC should use them
for both parsing and evaluation so pure and native configurations share the
same validation behavior.

### Miller-loop results are ephemeral

Upstream Plutus describes `bls12_381_mlresult` values as opaque and ephemeral.
It explicitly rejects their parsing, Flat encoding, and Flat decoding.

JuLC currently stores them as 576-byte `Constant` values and permits text and
Flat serialization. The native implementation then depends on a thread-local
map from those bytes to `PT` instances. The bytes alone are not sufficient to
reconstruct the native object.

This is both a ledger mismatch and the largest obstacle to provider
independence.

---

## Proposed architecture

### 1. Keep a JuLC Plutus semantics boundary

Introduce an internal `PlutusBlsRuntime` (name subject to implementation
review) owned by `julc-bls`. It wraps a selected ZeroJ
`Bls12381Provider` and optional acceleration capabilities.

The VM calls this JuLC boundary for all 19 builtins. It is responsible for:

- Plutus argument bounds and failure mapping;
- canonical constant decode/encode;
- MSM zip and scalar normalization;
- opaque Miller-loop value construction;
- provider diagnostics and lifecycle.

This keeps the pre-release ZeroJ API out of `julc-core` and prevents provider
exceptions from becoming part of JuLC's public VM contract.

### 2. Make selection immutable and evaluation-scoped

The provider belongs in the protocol-aware evaluation configuration introduced
by ADR-029. It must not be held in mutable global static state.

The supported choices should be:

```text
pure-java  -> always available; default
blst       -> available only when the optional adapter/artifact is installed
```

Merely adding the optional artifact does not change behavior. A user or CLI
must explicitly request `blst`. If BLST is requested but unavailable or its
native self-check fails, VM construction fails with a diagnostic before script
evaluation. It must not switch provider halfway through an evaluation.

Provider choice affects host performance only. It must never change Plutus
results, failure behavior, or charged execution budgets.

### 3. Isolate the native dependency

Recommended module shape:

```text
julc-bls
  -> zeroj-bls12381:0.1.0-pre10
  -> pure Java runtime and constant validator
  -> no native dependency

julc-bls-blst (optional)
  -> julc-bls
  -> zeroj-blst:0.1.0-pre10
  -> explicit BLST runtime/capability factory
```

If a new JuLC module is considered too costly, an equivalent explicitly
registered downstream provider adapter is acceptable. The invariant is that
the base `julc-bls`, Java VM, and CLI must be usable without
`foundation.icon:blst-java`, native libraries, or native-access flags.

`zeroj-bls12381` should normally be an `implementation` dependency behind the
JuLC facade. It should become `api` only if JuLC intentionally promises ZeroJ's
provider type as part of its public compatibility contract.

### 4. Replace the Miller-loop byte/cache model

Add an evaluation-only CEK value for an opaque BLS Miller-loop result. Do not
represent it as a serializable `Constant`.

The preferred first design is a persistent symbolic pairing product:

```text
millerLoop(P, Q)      -> one pairing term
mulMlResult(a, b)     -> persistent concatenation of the two term sets
finalVerify(a, b)     -> check product(a * inverse(b)) == identity
```

The inverse can be represented by negating one point in each term from `b`.
The selected ZeroJ provider then executes one `pairingProductIsIdentity` call.

This design:

- uses the common pure/BLST provider operation;
- eliminates serialization of an unserializable Plutus type;
- eliminates the native `PT` cache and its thread/evaluation lifetime hazard;
- can batch pairing work until `finalVerify`;
- keeps `mulMlResult` concatenation cheap when implemented as a persistent
  tree rather than repeated list copying.

The proof obligation is semantic equality with Plutus `ptMult` and
`ptFinalVerify`, including identity, infinity, nesting, and negation cases.
Provider-parameterized conformance tests must establish this before cutover.

If the symbolic design fails that obligation or has unacceptable resource
behavior, the fallback is a provider-specific opaque object behind the same
CEK value. Returning to public byte-array Miller-loop constants is not an
acceptable fallback.

JuLC's text parser and Flat codec should reject external Miller-loop constants,
matching upstream Plutus. Any non-ledger legacy support must be explicitly
profiled and must not be enabled by the ledger-compatible evaluator.

### 5. Provide portable and accelerated MSM

The baseline MSM implementation uses the selected provider:

1. validate every scalar against the signed 512-byte Plutus range;
2. use `min(scalars.size(), points.size())`;
3. decode and validate the paired points;
4. reduce paired scalars modulo the BLS group order;
5. accumulate provider scalar multiplication and addition;
6. return identity for an empty paired prefix.

For the optional BLST runtime, a Pippenger capability may call ZeroJ's
`BlstG1Msm` and `BlstG2Msm` after steps 1–4. Inputs must use checked,
uncompressed common encodings and explicitly reduced non-negative scalars.

The preferred long-term ZeroJ enhancement is to add provider-level G1/G2 MSM
methods with portable default implementations and BLST overrides. Until such a
release exists, JuLC may use a small optional capability interface rather than
forking or reflecting into ZeroJ.

### 6. Use pure Java constant validation

Replace `BlstConstantValidator` with a ZeroJ checked-codec validator in the base
module. Parsing valid ledger constants must not depend on a native library.

The existing `BlsConstantValidator.getInstance()` behavior silently skips
validation when provider initialization throws. Ledger-compatible parsing
should fail closed when BLS validation is required. Optional-provider failure
must not disable the portable validator.

---

## Enhancements enabled by the migration

### Native PV11 MSM acceleration

The clearest PV11-specific runtime benefit is a single native Pippenger call
for G1/G2 MSM instead of `n` JNI scalar multiplications and additions.

Benchmark at least `n = 0, 1, 2, 4, 8, 16, 32, 64` for both groups, including
decode, scalar normalization, native marshalling, and result encode. Use the
native path only where measured end-to-end performance justifies it.

Cardano execution budgets remain the ledger costs. Host timing must never be
used to alter or refund the charged budget.

### Deferred multi-pairing

The symbolic Miller-loop representation can turn a chain of
`millerLoop`/`mulMlResult` calls into one provider pairing-product check. This
removes the current thread-local cache and may reduce native transitions.

This is an implementation optimization, not an optimizer rewrite: the CEK
machine must still charge each builtin exactly as the program invoked it.

### Evaluation-local decoded-point values

A later optimization may keep validated `G1Point` and `G2Point` objects in
evaluation-local CEK values and encode only at `compress` or external result
boundaries. This can avoid repeated compressed decode/subgroup checks across
chains of point operations.

It should land only after the provider cutover, with:

- no change to Flat program representation;
- no cross-evaluation mutable cache;
- defensive conversion at public boundaries;
- exact memory-budget behavior preserved.

### PV11 compiler fusion

The PV11 compiler roadmap's MSM-fusion item can be implemented after runtime
parity:

```text
s1*P1 + s2*P2 + ... + sn*Pn
    -> bls12_381_G1_multiScalarMul scalars points
```

and equivalently for G2.

The compiler must require a PV11 target, preserve evaluation order and failure
behavior, and compare the actual PV11 cost functions before selecting the
rewrite. The stdlib should expose typed scalar/point lists rather than requiring
users to construct `PlutusData`-shaped values.

---

## Delivery plan

### Phase 0 — Freeze the semantic oracle

Deliverables:

- pin the ZeroJ integration spike to exactly `0.1.0-pre10`;
- create one provider-parameterized test contract for all 19 Plutus builtins;
- import or reference authoritative Cardano/Plutus BLS vectors;
- snapshot current Java/Truffle result and budget behavior;
- add explicit tests for all rules listed in the acceptance section.

Exit gate:

- the test contract fails clearly for every known current gap and can execute
  against the legacy BLST implementation.

### Phase 1 — Correct existing JuLC boundary behavior

Deliverables:

- enforce the exact signed MSM range;
- validate all scalar-list elements before zip truncation;
- add empty and unequal-list tests;
- reject text/Flat Miller-loop constants in the ledger-compatible profile;
- make constant validation fail closed where the ledger requires it.

These fixes are independent of the provider migration and should not wait for
performance work.

Exit gate:

- the legacy backend matches the upstream Plutus edge cases and retains exact
  budget behavior.

### Phase 2 — Introduce the runtime boundary and pure Java provider

Deliverables:

- add `zeroj-bls12381:0.1.0-pre10` to `julc-bls`;
- introduce immutable evaluation-scoped BLS selection;
- implement the 14 directly covered builtins through the ZeroJ provider;
- implement the portable MSM fallback;
- replace native constant validation with ZeroJ checked codecs;
- translate provider exceptions into stable builtin failures.

Keep the legacy implementation available as a test oracle during this phase.

Exit gate:

- the pure-only test runtime contains no native BLS dependency and passes the
  point, codec, hash, and MSM portions of the Plutus contract.

### Phase 3 — Make pairing provider-neutral

Deliverables:

- add the opaque CEK Miller-loop result;
- implement and prove the symbolic pairing-product model;
- remove public serialization from the ledger path;
- add nested multiplication, identity, infinity, negation, and concurrency
  tests;
- verify no evaluation-local state survives evaluation completion.

Exit gate:

- all 19 builtins pass under the pure Java provider and the full bundled Plutus
  conformance suite remains green in Java and Truffle.

### Phase 4 — Add explicit optional BLST

Deliverables:

- add the isolated optional adapter/module;
- expose explicit provider selection and diagnostics;
- run the same 19-builtin suite against BLST;
- add native MSM with correct pre-validation and scalar reduction;
- test supported platform/native-access failure messages;
- add CI jobs that prove the pure distribution has no native dependency.

Exit gate:

- pure and BLST providers produce identical results and failures for the full
  corpus, while the base distribution remains native-free.

### Phase 5 — Benchmark and cut over

Deliverables:

- JMH benchmarks for point operations, hashes, pairing chains, and G1/G2 MSM;
- end-to-end script benchmarks including conversion overhead;
- make pure Java the default after conformance and performance limits are
  documented;
- remove JuLC's direct SWIG imports, custom FFM loader, and `PT` cache;
- remove `foundation.icon:blst-java` from the base JuLC dependency graph;
- document BLST installation, native-access flags, supported platforms, and
  provider diagnostics.

Exit gate:

- no current BLS implementation code remains except a time-bounded rollback
  path, and the dependency/packaging assertions pass in release CI.

### Phase 6 — PV11 optimizations

Deliverables:

- cost-directed compiler fusion to G1/G2 MSM;
- typed stdlib/compiler surfaces;
- optional decoded-point CEK values;
- provider-specific native MSM threshold selected from benchmarks;
- before/after UPLC size and ledger-budget reports.

Exit gate:

- each optimization is separately switchable/versioned, semantics-tested, and
  demonstrably improves representative PV11 programs.

---

## Acceptance criteria

### Semantic parity

- [ ] All 17 original and both PV11 BLS builtins pass the authoritative Plutus
  conformance corpus with pure Java.
- [ ] The same corpus passes with optional BLST.
- [ ] Pure and BLST produce identical compressed G1/G2 results.
- [ ] Arbitrary-size ordinary scalars, including negative values, reduce
  modulo the group order.
- [ ] MSM accepts exactly the signed 512-byte range and rejects both adjacent
  out-of-range values.
- [ ] MSM checks out-of-range extra scalars even when the point list is shorter.
- [ ] MSM preserves zip semantics and returns identity for either empty input.
- [ ] Binary DST values, including bytes above `0x7f`, match Plutus.
- [ ] A 255-byte DST succeeds and a 256-byte DST fails.
- [ ] Invalid flags, non-canonical fields, off-curve points, and non-subgroup
  points fail consistently.
- [ ] Miller-loop results cannot be parsed or Flat encoded/decoded in the
  ledger-compatible path.
- [ ] Nested `mulMlResult` and `finalVerify` behavior matches Plutus for
  identity, infinity, inverse, and non-equal products.

### VM and budget parity

- [ ] Java and Truffle run the same provider-parameterized suite.
- [ ] Provider choice does not change the charged CPU or memory budget.
- [ ] The full Java and Truffle conformance suites pass with pure Java only.
- [ ] BLST is exercised in a separate optional CI matrix.
- [ ] Repeated and concurrent evaluations do not share Miller-loop state.
- [ ] Provider initialization failure occurs before evaluation and cannot
  silently disable constant validation.

### Dependency and packaging

- [ ] Base `julc-bls` has no transitive `foundation.icon:blst-java`.
- [ ] Base Java VM and CLI run with no native libraries and no native-access
  flags.
- [ ] Adding the BLST artifact alone does not silently change the provider.
- [ ] Explicit BLST selection reports its provider ID and native readiness.
- [ ] Unsupported BLST platforms fail clearly before evaluation.
- [ ] ZeroJ is pinned exactly and dependency checksums/locking are updated.

### Performance and operability

- [ ] Pure Java performance and memory limits are documented.
- [ ] BLST benchmarks include conversion and native-call overhead.
- [ ] Native MSM uses reduced scalars and is parity-tested at boundary values.
- [ ] Provider selection is visible in diagnostics without exposing script
  data or affecting evaluation output.

---

## Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| `0.1.0-pre10` is a pre-release API | Upgrade churn | Pin exactly and isolate it behind the JuLC runtime boundary |
| Pure Java pairing is slower | Lower evaluator throughput | Keep BLST as explicit acceleration and benchmark full scripts |
| Provider SPI lacks Miller-loop result operations | Cannot directly map three builtins | Use an opaque symbolic product; retain a provider-specific opaque fallback |
| Provider SPI lacks MSM | PV11 fast path is not polymorphic | Portable default plus optional capability; propose MSM upstream |
| BLST FFM scalar helpers truncate to 32 bytes | Wrong result for large/negative Plutus scalars | Validate then reduce modulo `r` before native marshalling |
| ZeroJ accepts oversized RFC 9380 DSTs | Ledger semantic divergence | Enforce Plutus's 255-byte limit in the adapter |
| Native availability differs by platform | Startup/runtime failure | Pure default, explicit BLST opt-in, startup self-check |
| Symbolic Miller-loop products grow with term count | Host memory/latency pressure | Persistent representation, budget-aware tests, flatten once |
| Silent constant-validation fallback remains | Invalid program accepted locally | Install portable validation and fail closed |
| Compiler MSM fusion changes script hashes | Deployment/reproducibility impact | Target gate and version/flag the optimizer |

---

## Alternatives considered

### Keep JuLC's direct BLST implementation

This avoids migration work but retains the mandatory native dependency, custom
FFM loading, static provider choice, and native-object cache. It does not
deliver the requested pure Java configuration.

### Use only ZeroJ pure Java

This is the simplest portable result but removes an important throughput option
for pairing-heavy and PV11 MSM workloads. The provider architecture exists to
avoid that forced choice.

### Auto-select BLST when present

This makes deployments sensitive to incidental classpath changes and can
change startup behavior across platforms. It also conflicts with ZeroJ's
explicit-provider design. JuLC will use explicit selection.

### Expose ZeroJ types directly throughout the VM

This reduces adapter code but couples CEK internals and public APIs to a
pre-release library and still does not define Plutus-specific errors, bounds,
MSM, or ephemeral Miller-loop values. JuLC will keep a narrow semantics
boundary.

---

## Non-goals

- Implementing the migration as part of accepting this ADR.
- Treating ZeroJ library tests as sufficient proof of Plutus conformance.
- Changing Cardano execution prices based on Java/native wall-clock speed.
- Auto-enabling native code.
- Supporting serialization of Miller-loop results as a JuLC ledger extension.
- Enabling PV11 compiler rewrites before ADR-029 target gating exists.

---

## References

### ZeroJ `0.1.0-pre10`

- [ZeroJ `v0.1.0-pre10` source](https://github.com/bloxbean/zeroj/tree/v0.1.0-pre10)
- [`zeroj-bls12381` module](https://github.com/bloxbean/zeroj/tree/v0.1.0-pre10/zeroj-bls12381)
- [`Bls12381Provider` SPI](https://github.com/bloxbean/zeroj/blob/v0.1.0-pre10/zeroj-bls12381/src/main/java/com/bloxbean/cardano/zeroj/bls12381/spi/Bls12381Provider.java)
- [`PureJavaBls12381Provider`](https://github.com/bloxbean/zeroj/blob/v0.1.0-pre10/zeroj-bls12381/src/main/java/com/bloxbean/cardano/zeroj/bls12381/spi/PureJavaBls12381Provider.java)
- [`zeroj-blst` module](https://github.com/bloxbean/zeroj/tree/v0.1.0-pre10/zeroj-blst)
- [`BlstBls12381Provider`](https://github.com/bloxbean/zeroj/blob/v0.1.0-pre10/zeroj-blst/src/main/java/com/bloxbean/cardano/zeroj/blst/BlstBls12381Provider.java)
- [`BlstG1Msm`](https://github.com/bloxbean/zeroj/blob/v0.1.0-pre10/zeroj-blst/src/main/java/com/bloxbean/cardano/zeroj/blst/ffm/BlstG1Msm.java)
- [`BlstG2Msm`](https://github.com/bloxbean/zeroj/blob/v0.1.0-pre10/zeroj-blst/src/main/java/com/bloxbean/cardano/zeroj/blst/ffm/BlstG2Msm.java)
- [Maven Central `zeroj-bls12381` metadata](https://repo1.maven.org/maven2/com/bloxbean/cardano/zeroj-bls12381/maven-metadata.xml)
- [Maven Central `zeroj-blst` metadata](https://repo1.maven.org/maven2/com/bloxbean/cardano/zeroj-blst/maven-metadata.xml)

### Plutus semantics

- [Plutus BLS G1 implementation](https://github.com/IntersectMBO/plutus/blob/fdbe32b20bd02a4f27a9654ecc3648a2c8fa2968/plutus-core/plutus-core/src/PlutusCore/Crypto/BLS12_381/G1.hs)
- [Plutus BLS G2 implementation](https://github.com/IntersectMBO/plutus/blob/fdbe32b20bd02a4f27a9654ecc3648a2c8fa2968/plutus-core/plutus-core/src/PlutusCore/Crypto/BLS12_381/G2.hs)
- [Plutus MSM scalar bounds](https://github.com/IntersectMBO/plutus/blob/fdbe32b20bd02a4f27a9654ecc3648a2c8fa2968/plutus-core/plutus-core/src/PlutusCore/Crypto/BLS12_381/Bounds.hs)
- [Plutus ephemeral Miller-loop result](https://github.com/IntersectMBO/plutus/blob/fdbe32b20bd02a4f27a9654ecc3648a2c8fa2968/plutus-core/plutus-core/src/PlutusCore/Crypto/BLS12_381/Pairing.hs)
- [CIP-133: BLS12-381 MSM](https://cips.cardano.org/cip/CIP-133)

### Current JuLC surfaces

- `julc-bls/build.gradle`
- `julc-bls/.../BlsOperations.java`
- `julc-bls/.../BlstConstantValidator.java`
- `julc-core/.../BlsConstantValidator.java`
- `julc-core/.../Constant.java`
- `julc-core/.../flat/UplcFlatEncoder.java`
- `julc-core/.../flat/UplcFlatDecoder.java`
- `julc-vm-java/.../CekValue.java`
- `julc-vm-java/.../builtins/Bls12381Builtins.java`
