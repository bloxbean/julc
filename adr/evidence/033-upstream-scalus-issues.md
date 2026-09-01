# Draft upstream Scalus 1.1.0 issue reproducers

These drafts are evidence only. They have not been posted. All reference
expectations are pinned to cardano-node 11.0.1 / Plutus 1.63.0.0 at
`f92b7d7d82622a26caf456a6be33859f697e2cfc`; actual outcomes use
`org.scalus:scalus_3:1.1.0` on Java 25.

## Draft 1 — BLS hashToGroup changes DST bytes >= 0x80

### Suggested title

`BLS12-381 hashToGroup does not preserve high-byte DST input on JVM`

### Description

Scalus's JVM implementation converts the DST byte array with
`new String(dst.bytes, "Latin1")` before calling BLST:

- `JVMPlatformSpecific.scala:146-154` for G1;
- `JVMPlatformSpecific.scala:198-206` for G2.

This matches the Plutus result for ASCII and empty DST controls, but not when
the DST contains bytes >= `0x80`. Plutus passes the raw `ByteString` to the BLS
hash-to-curve implementation.

### Reproducer

From JuLC PR #122:

```bash
./gradlew :julc-vm-scalus:test \
  --tests '*ScalusKnownUpstreamDivergenceTest.highByteDstHashToGroupDivergenceIsExplicitAndPinned*' \
  --tests '*ScalusKnownUpstreamDivergenceTest.derivedHighByteDstSignatureDivergenceIsExplicitAndPinned*' \
  --rerun-tasks --no-daemon
```

The tests load the pinned Plutus `hash-dst-len-255` inputs, apply the matching
G1/G2 `compress` builtin so the final result is ledger-serializable, and compare
against the ledger golden.

G1 compressed result:

```text
expected 931bd1f65dd2d34a55c93d82c20dcacd3a91afa5932fdd7fed06119f8574520c9609d337d680060b4bd2c59f0b60bb54
actual   b842ac8cc7c022e4c59934611a452c38d49835af61b9e29bbefb3eb56ebbbb433ba675e836729a871e0c9e240520e1a4
```

G2 compressed result:

```text
expected 9028b507444b4283faf2f85e7f7d3890b67e9bcf84c7de2f75fe603996ab1b12a25b4637d68f310b7bd6d47ec11e3fa60d0f8f9d1dc880746105b4d7e9b5bba86abfdef96dfda303b1fb00b5d866b5d7f67883efb39efca301ae44a7f1322a33
actual   ab85034bbbffb6cc4af2ed9a1c717c4e98b2783547fb711c73fa747e12603d55f17aaef5a7e9d9846d17d8e8378ae45904793f155168d39087c400823ad443e256084b749749ba55cad01dd51e296403b38d9e486c90d6cf0eeeb1ec5438bdb9
```

The pinned `large-dst` signature fixture likewise expects `True` and evaluates
to `False`; its internally compressed Scalus G1 point is
`b980667e641c56abb15943e6e25e682b80ca1c5c3310009291822dec5c7cf412fc7e56b18fc06b600fe4c0ca57896e66`.

Reason code in JuLC: `SCALUS_HASHTOGROUP_DST_HIGH_BYTE`.

### Expected fix

Preserve the exact DST bytes when invoking the underlying BLS implementation.
A fix should make the two high-byte tests and the derived signature test match
while retaining the passing ASCII and empty-DST controls.

## Draft 2 — sliceByteString narrows signed Int64 arguments to Int32

### Suggested title

`sliceByteString truncates Plutus Int arguments with BigInt.toInt`

### Description

Pinned Plutus unlifts `start` and `length` as signed machine `Int` (Int64 on the
supported node platform) in both pre-D/E and D/E semantics. Scalus accepts
unrestricted `BigInt` in `Builtin.scala:229-239`, then calls `.toInt` for both
arguments in `Builtins.scala:227-228`. This creates two divergences:

1. values within Int64 but outside Int32 wrap and return the wrong slice;
2. values outside Int64 should fail unlifting but instead wrap and succeed.

### Minimal semantic examples

For input bytes `#0102` under both V3/PV10/C and V3/PV11/E:

```uplc
[(builtin sliceByteString) (con integer 2147483648) (con integer 1) (con bytestring #0102)]
```

Reference result: `#` (drop beyond the end). Scalus 1.1.0 result: `#01`
because `2147483648.toInt == -2147483648`.

```uplc
[(builtin sliceByteString) (con integer 0) (con integer 2147483648) (con bytestring #0102)]
```

Reference result: `#0102`. Scalus 1.1.0 result: `#`.

Values `Long.MAX_VALUE + 1` and `Long.MIN_VALUE - 1` should fail reference
unlifting but succeed in Scalus.

### Reproducer

```bash
./gradlew :julc-vm-scalus:test \
  --tests '*ScalusKnownUpstreamDivergenceTest.sliceByteStringInt64NarrowingDivergenceIsExplicitAndPinned*' \
  --rerun-tasks --no-daemon
```

The parameterized test pins both arguments, both protocol profiles, the
Int32/Int64 band, and outside-Int64 inputs through both Scalus adapter paths.
Reason code: `SCALUS_SLICEBYTESTRING_INT64_NARROWING`.

### Expected fix

Apply the reference signed-Int64 unlifting check, then implement take/drop
without narrowing valid Int64 values to Int32. The failure must occur during
builtin unlifting/execution so its timing and budget remain reference-aligned.

## Draft 3 — semantics D/E omit Cardano and writeBits input bounds

### Suggested title

`Semantics D/E do not enforce CInteger, CByteString, or writeBits input bounds`

### Description

Scalus 1.1.0 selects variants D/E at PV11, but its runtime unlifting does not
apply three bounds required by the pinned Plutus evaluator:

- `CInteger`: `[-2^262143, 2^262143 - 1]` (`Cardano.hs:9-15`);
- `CByteString`: at most 65,536 bytes (`Cardano.hs:17-18`) at every wrapped
  D/E builtin position;
- `writeBits`: a separate maximum input length of 4,096 bytes
  (`Builtins.hs:2191-2205`).

These are D/E-only constraints. The same inputs are intentionally accepted by
V3/PV10/C where otherwise semantically valid.

### Representative reproducers

1. `AddInteger(2^262143, 0)` at V3/PV11/E: reference semantic failure; Scalus
   succeeds. The JuLC matrix covers all 18 `CInteger` argument positions and
   both sides of the bound.
2. `AppendByteString(65537-byte value, empty)` at V3/PV11/E: reference semantic
   failure; Scalus succeeds. The matrix covers all 33 `CByteString` positions.
3. `WriteBits(4097-byte value, [], True)` at V3/PV11/E: reference semantic
   failure; Scalus succeeds.

Run the committed probes:

```bash
./gradlew :julc-vm-scalus:test \
  --tests '*ScalusKnownUpstreamDivergenceTest.everyPv11CardanoIntegerPositionMissingBoundIsExplicitAndPinned*' \
  --tests '*ScalusKnownUpstreamDivergenceTest.everyPv11CardanoByteStringPositionMissingBoundIsExplicitAndPinned*' \
  --tests '*ScalusKnownUpstreamDivergenceTest.pv11WriteBits4096ByteLimitDivergenceIsExplicitAndPinned*' \
  --rerun-tasks --no-daemon
```

The corresponding inclusive-bound controls pass. JuLC reason codes are:

- `SCALUS_MISSING_CARDANO_INTEGER_BOUND_E`;
- `SCALUS_MISSING_CARDANO_BYTESTRING_BOUND_E`;
- `SCALUS_MISSING_WRITEBITS_4096_BOUND_E`.

### Expected fix

Apply these checks at the runtime unlifting/denotation positions selected by
semantics D/E, not by scanning program literals before CEK evaluation. Values
may be computed mid-evaluation, and a pre-scan would change failure timing and
consumed budget.

## Draft 4 — semantics C rejects negative Data constructor tags

### Suggested title

`Semantics C rejects negative Data.Constr tags accepted by Plutus`

### Description

Under V3/PV10 semantics C, Plutus `Data.Constr` carries an arbitrary integer
tag. Scalus 1.1.0 requires a non-negative constructor tag in its Data
representation. This affects both values created during CEK evaluation with
`constrData` and negative-tag Data literals crossing the FLAT bridge. The
unsigned restriction at V3/PV11 semantics E is expected and serves as the
control.

### Reproducer

```bash
./gradlew :julc-vm-scalus:test \
  --tests '*ScalusKnownUpstreamDivergenceTest.negativeRuntimeConstructorTagDivergenceIsExplicitAndPinned' \
  --tests '*ScalusKnownUpstreamDivergenceTest.negativeConstructorLiteralDivergenceIsExplicitAndPinned' \
  --rerun-tasks --no-daemon
```

The runtime test evaluates `constrData(-1, [])`: the V3/PV10 ledger result is
`Data.Constr(-1, [])`, while Scalus fails after CEK work has begun. The literal
test pins the separate zero-budget bridge rejection. Reason code:
`SCALUS_NEGATIVE_CONSTR_TAG_C`.

### Expected fix

Represent the arbitrary-integer constructor tags required by semantics C while
retaining the unsigned restriction required by semantics E. Runtime failures
must remain aligned with the selected semantic variant rather than being
implemented as an adapter pre-scan.
