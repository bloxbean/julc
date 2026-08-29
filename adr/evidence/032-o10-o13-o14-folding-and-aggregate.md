# ADR-032 O10/O13/O14 evidence — literal folding and aggregate validation

- **Issues:** [#106](https://github.com/bloxbean/julc/issues/106),
  [#107](https://github.com/bloxbean/julc/issues/107), and
  [#108](https://github.com/bloxbean/julc/issues/108)
- **Compiler target:** `plutus-v3-pv11-uplc-1.1.0`
- **Candidate level:** `pv11-safe`
- **Cost profile:** `cardano-node-11.0.1-plutus-v3-pv11`
- **Parameter SHA-256:**
  `40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`

Reproduce all tables with:

```bash
./gradlew :julc-benchmark:optimizationEvidence
```

The runner compiles the same source at `baseline` and `pv11-safe`, evaluates
both artifacts on the Java and Truffle VMs, and compares the result or exact
failure, trace sequence, target, script bytes, script hash, CPU, and memory.

## O10 — Array literal folding deferred

No O10 rule ships. JuLC's supported Java subset can construct a `JulcArray`
only by converting a runtime list. It does not currently lower a Java array or
`JulcList.of(...)` expression to a native UPLC `ArrayConst`. Consequently the
compiler has no typed, statically known array region on which
`LengthOfArray`/`IndexArray` folding would apply.

Folding the recognizable untyped UPLC sequence
`IndexArray(ListToArray(list), index)` would be unsafe: the optimizer cannot
prove that the list is a literal native list of the required element universe,
and an invalid index must retain its runtime failure text and evaluation point.
O9 also established that runtime list indexing and `IndexArray` have different
failure behavior. O10 is therefore deferred until a typed PIR array literal
exists. Adding a raw-only rewrite with no supported source producer would add
maintenance surface without improving generated JuLC contracts.

## O13 — `ExpModInteger` literal folding enabled

The static-cost rule `pv11.o13.exp-mod-literal-fold` runs only at
`pv11-safe`/`pv11-costed`, only when the resolved target provides
`ExpModInteger`, and only when all three arguments are integer constants. It
recognizes both a direct builtin application and JuLC's lexically scoped,
shared `MathLib.expMod` builtin binding. Alias tracking follows de Bruijn scope
and has explicit shadowing/non-literal regressions; it does not perform general
inlining.

For a positive modulus, successful positive exponents use `modPow`; negative
exponents first require `modInverse`, exactly matching the pinned builtin.
Zero/negative moduli and non-invertible negative exponents remain runtime
builtin calls. They are not converted to diagnostics or generic `Error`, so
failure text and timing remain unchanged. A successful result is non-negative
and smaller than the positive modulus, so replacing the three-constant
application cannot grow the embedded integer or FLAT artifact.

The successful fixture evaluates `2^5 mod 13`, `2^-1 mod 5`, and `0^0 mod 7`
and returns their sum. Java and Truffle produce identical results and budgets.

Baseline hash: `85383998ed559fe182787749b8be221bcf763e874378937f13e4617e`

Candidate hash: `4d54bb358c2df3a5a181ebb6f07706fd99ffa18fe1329507fba325e2`

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 42 | 6 | -36 |
| UPLC term nodes | 33 | 1 | -32 |
| CPU | 3,406,498 | 16,100 | -3,390,398 |
| Memory | 3,407 | 200 | -3,207 |

The zero-modulus failure fixture remains byte-identical:

- hash:
  `adafee145f262550d4d1571d6106cfe14d0cc7ebc1ad6b7a22802578`;
- FLAT bytes: 16 before and after;
- failure CPU/memory: 1,036,094 / 1,001 before and after;
- exact failure equivalence on Java and Truffle.

## O14 — native Value literal folding deferred

No O14 rule ships. Milestone 2 introduced the typed `JulcValue` boundary, but
the supported source API obtains a native Value through the partial
`UnValueData` conversion; it cannot construct a native `ValueConst` literal.
The compiler also has no pinned, shared reference-semantic helper for all
native Value operations and their canonical Data conversion.

Implementing Value normalization independently inside the optimizer would risk
changing policy/token ordering, duplicate aggregation, removal of zero
quantities, negative-quantity behavior, and malformed-Data failure timing.
Algebraic identities are also not safe when they discard evaluation of a
partial conversion or another strict argument. O14 remains deferred until a
typed native Value literal producer and one shared reference implementation
can test compiler folding and VM behavior against the same canonical rules.

## Aggregate `PV11_SAFE` result

The aggregate fixture is validator-like rather than a synthetic raw term. It
decodes a Data list, drops a dynamic count, branches for empty/minimum cases,
decodes the head, and adds a literal modular-exponentiation bonus. It therefore
combines every shipped code-changing ADR-032 rule: O1, O2, and O13. Inputs cover
accepted/rejected paths, negative/overlong drops, empty input, malformed list
Data, and a malformed head. Outcome, returned Data, failure text, and traces
are identical on both VMs.

Baseline hash: `3d1e9ac3561e68d3d0864705686adca6105ff5fc15f5a43e904de1f2`

Candidate hash: `2f2ea7f79a5dd083ed51e573dc247267ae7cb978ecc615692479c594`

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 169 | 88 | -81 |
| UPLC term nodes | 185 | 84 | -101 |
| CPU, accepted path | 5,346,583 | 1,935,936 | -3,410,647 |
| Memory, accepted path | 19,605 | 8,533 | -11,072 |
| CPU, empty after drop | 5,328,905 | 1,111,835 | -4,217,070 |
| Memory, empty after drop | 23,070 | 5,464 | -17,606 |
| CPU, malformed list | 643,521 | 643,521 | 0 |
| Memory, malformed list | 3,796 | 3,796 | 0 |

Every measured aggregate row is equal or lower in both CPU and memory. The
numbers are fixture-specific ledger budgets, not universal percentage claims.
Default compilation remains `baseline`; users select `pv11-safe` explicitly,
and recompilation at that level intentionally changes the script hash.
