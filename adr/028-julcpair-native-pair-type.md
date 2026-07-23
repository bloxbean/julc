# ADR-028: `JulcPair` — a Native-Pair Java Type for Map Iteration

**Date**: 2026-07-22
**Status**: Proposed (design only — implementation in a separate, later PR)
**Extracted from**: ADR-027 review round 2; split out so ADR-027's scope stays
limited to the stdlib conveniences and the typed-lookup fix.

---

## Context

ADR-027's review established that JuLC has two "pair-like" runtime shapes that
must not share a Java type:

- **`Tuple2<A,B>`** is registered as a `RecordType`
  (TypeResolver.java:316) — its on-chain representation is **Data**:
  `Constr 0 [a', b']`, constructed via `ConstrData`, read via `UnConstrData` +
  fields-list traversal, accessors `.first()`/`.second()`. This contract is
  load-bearing (`MathLib.divMod`/`quotRem` construct it; Tuple2 values can live
  in Data lists, be `equalsData`-compared, serialised, stored in datums).
- **Map entries** are **native UPLC pairs** (`pair⟨data,data⟩`) — builtin CEK
  values, NOT Data. Read via `FstPair`/`SndPair`; cannot be consed into Data
  lists, `equalsData`-compared, or serialised. Data-ness exists only at the
  container level (`MapData` wraps the whole pair list). The
  `MapType.head()` registration (TypeMethodRegistry.java:678) already returns
  `PairType` at the PIR level — but `JulcMap.head()` is typed `Object` on the
  Java side because no Java type for native pairs exists.

The compiler lowers accessor calls from the static Java type alone, so one type
cannot carry both representations: typing map entries as `Tuple2` would emit
`UnConstrData` against a native pair (crash), and vice versa.

`JulcMap` also has no lossless iteration surface: `keys()` + `get()` collapses
duplicate keys (legal in on-chain assoc maps; `get` returns the first match),
and `head()`/`tail()` requires `Object` casts.

## Decision

Introduce a dedicated core-level type whose contract is *always a native UPLC
pair*:

```java
package com.bloxbean.cardano.julc.core.types;

/** Native-pair view of a map entry.
 *  On-chain: .key()/.value() → FstPair/SndPair (+ wrapDecode per K/V type). */
public interface JulcPair<K, V> {
    K key();
    V value();
}
```

- **`JulcMap.entries()`** returns `JulcList<JulcPair<K,V>>` — on-chain
  **identity** (the map already IS a pair list), typed
  `ListType(PairType(K,V))`; off-chain `JulcAssocMap` exposes its ordered
  internal pair list. Order and duplicate keys preserved.
- **`JulcMap.head()`** is retyped from `Object` to `JulcPair<K,V>`, eliminating
  the cast-based traversal (the PIR side already returns `PairType`; the Java
  type was the gap).
- On-chain accessor lowering: `p.key()` → `wrapDecode(FstPair(p), K)`,
  `p.value()` → `wrapDecode(SndPair(p), V)` — one builtin per access, cheaper
  than Tuple2's `UnConstrData` + traversal.
- Off-chain: a `JulcPairImpl` record backing `JulcAssocMap` entries.

## List-operation contract for `JulcList<JulcPair<K,V>>` (ListType(PairType))

Native pairs are not Data, so `JulcList` operations must be explicitly
classified. The contract is an **allowlist: operations not listed as supported
are rejected at compile time** with a clear diagnostic (not left to fail at
eval).

| Category | Operations | Status |
|---|---|---|
| Structural | `head`, `tail`, `size`, `isEmpty`, `take`, `drop`, `get`, `nth`, `reverse`, `concat` (pair-list + pair-list), `prepend` (of a `JulcPair`, via `MkCons`) | **Supported** — operate on list spines / move pair values without assuming Data elements |
| Conversion | `toArray` | To validate during implementation (`ListToArray` on a pair list; PV11) — supported only if the VM accepts it |
| Data-assuming | `contains` (`EqualsData`), `containsInt`/`containsBytes`, `hasDuplicate*`, `toPlutusData`/`ListData` wrapping, element auto-wrap (`JulcList.of` of a pair) | **Rejected at compile time** — no valid lowering for native pairs |
| Higher-order | `filter` (predicate over the pair) | Likely supported — elements pass through unchanged; validate |
| Higher-order | `find` | **Rejected as-is** — current lowering wraps the result in a Data-encoded `Optional` (`ConstrData(0,[x])`), invalid for a native pair; needs a dedicated design if wanted |
| Higher-order | `map` | Depends on the lambda result type: pair → Data-encodable result is fine (result list is a normal Data list); pair → pair needs `MkPairData` reconstruction; classify during implementation, reject unsupported shapes |
| Higher-order | `any`, `all`, `foldl`, `zip` | Classify during implementation with the same rule: safe iff no Data-encoding of a native pair is implied |

## Required negative tests (compile-time rejection, clear diagnostics)

- `map.entries().toPlutusData()`
- `map.entries().contains(pair)`
- `JulcList.of(map.head())` (auto-wrap of a native pair)
- `ListsLib.find(map.entries(), p -> ...)` (Data-encoded Optional of a pair)
- `map.entries().map(p -> p)` (pair → pair without reconstruction)

Positive tests: `entries()` identity round-trip (order + duplicate keys),
`head()`/`key()`/`value()` typed access for each K/V primitive combination,
structural ops on entries, `filter` + `size` composition.

## Compatibility

- **`JulcMap.head()` descriptor change**: `Object head()` →
  `JulcPair<K,V> head()` changes the JVM method descriptor — source- and
  binary-incompatible for external callers of `head()`. Acceptable pre-1.0;
  release-noted. (Erasure keeps it source-compatible for callers that assigned
  to `Object`, but binary compatibility is not preserved.)
- `entries()` is additive; `JulcAssocMap` is the sole `JulcMap` implementor
  in-repo.

## Rejected alternatives (from ADR-027 review)

- `entries(): JulcList<Tuple2<K,V>>` as identity — unimplementable
  (representation mismatch above).
- `entries()` converting each native pair to a Data-encoded `Tuple2` — works
  and composes with all list ops, but costs O(n) `ConstrData` construction per
  call and hides the native representation; may be revisited as a separate
  `entriesAsTuples()` if demand appears.
- Callback-style `iterate(...)` — lambdas do not compile on-chain.
- Making `JulcMap` `Iterable` — deferred; interacts with the existing
  for-each-on-MapType desugaring.

## Out of scope

`JulcMap.toPlutusData()` does NOT depend on this ADR: on-chain the
`wrapEncode` fallback already maps `MapType` → `MapData`; off-chain
`JulcAssocMap` walks its private ordered entries. That lands with ADR-027's
implementation PR. This ADR is only about giving users a typed, lossless
iteration surface.
