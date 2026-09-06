# ADR-037 validation and implementer review

Base: `main` at `8af52387`. Branch: `fix/plutus-data-map-decoder-fidelity`.

## Regression evidence

Before changing production code, all 14 initial fixed-vector tests failed:
`./gradlew :julc-core:test --tests '*MapDecodingFidelityTest' -PskipSigning=true`.
The failures covered duplicate associations, all definite map-header widths,
indefinite maps, non-minimal equal integer keys, nested lists/constructors/maps,
and the data used by FLAT read-back. Expected values were constructed directly,
not decoded by the defective implementation.

After the fix, the expanded 62-case core suite passes. It additionally covers:

- Duplicate-containing maps and lists as map keys, with distinct associated values.
- Map sizes 0, 1, 23, 24, 255, 256, 65535 and 65536.
- 500 deterministic recursively generated data trees with duplicate maps,
  constructors, lists, integers and chunked bytes.
- Truncated headers and entries, all truncated prefixes of a nested indefinite
  fixture, odd indefinite maps, reserved headers, misplaced breaks and forged
  unsigned lengths (including Word64 maximum).
- Comparison with the stock parser for 20 fixed duplicate-free fixtures, including
  Word64 integers/constructor tags, bignums, chunked bytes, indefinite containers,
  unknown tags and the historical top-level sequence behavior.

The benchmark adds 48 cases: eight fixed map vectors on Java, Truffle and Scalus,
with serialized-program result/byte/budget equivalence and literal equality
against the original supplied argument. All pass. Expected serialization hex is
fixed independently of the tested encoder. Scalus uses its configured language-only
API; no ledger certification is claimed.

## Review against invariants

- Header peeking resets before delegating; no outer mark is needed once a map
  header is consumed. Array/tag recursion calls virtual `decodeNext`, covering
  maps at arbitrary supported nesting positions.
- Length parsing uses unsigned BigInteger. Only the explicit additional-info 31
  header means indefinite; a large unsigned definite length cannot alias -1.
  The remaining-byte bound is necessary (at least two bytes per pair), not a
  claim that the payload is valid; each member is still parsed and checked.
- Entry lists are authoritative. Reusing `OrderedMap` does build a deduplicated
  compatibility view, but neither conversion nor encoding reads that view.
  Tests exercise keys whose inherited map views compare equal despite different
  association sequences.
- Breaks are accepted only as the terminator in indefinite-map key position.
  Incomplete entries fail before an OrderedMap is returned. Unsupported Plutus
  members fail during conversion rather than being silently skipped as keys.
- All non-map parsing and public conversion signatures remain unchanged. The
  encoder, FLAT encoder, compiler and VM implementations have no edits.
- Existing FLAT limits remain unchanged. The decoder still has no general CBOR
  nesting/total-size limit; this focused fix does not claim comprehensive DoS
  protection or stricter validation of unrelated malformed CBOR.

This is implementer review, not independent approval or a correctness proof.

## Broader verification

`./gradlew build -PskipSigning=true --rerun-tasks`: passed in 3m 46s;
218 tasks executed. Unique test-task XML totals: 10,814 cases, 10,283 passed,
531 existing/profile-inapplicable skips, zero failures/errors. Includes core 683,
compiler 1,483 plus pairCaseTest 13, benchmark 70, ledger API 211, CCL 204,
decompiler 97, in-repository examples 81, testkit 191, AP 20, Gradle plugin 30,
and Scalus 312. Stdlib: 403 cases, one existing skip.

Java and Truffle each ran 1,998 conformance cases across PV10/PV11, with 262
profile-inapplicable skips each; all 999 PV11 cases passed on each backend.
Optional `julc-e2e-tests` and `julc-plugin-test` node suites are not enabled by the
default build and were not run. No cached test-task results were relied on.

`npm run build` in `docs`: passed, 32 pages.

A read-only replay of the 41 existing `julc-examples/build/classes/java/main/
META-INF/plutus/*.plutus.json` artifacts removed their two CBOR byte-string
wrappers, decoded FLAT using the changed core, and re-encoded each program. All
41 retained exact FLAT bytes. This did not modify or rebuild the sibling checkout
and is not a new transaction-suite or Yaci run.

No new compiler lowering or generated encoding is introduced. Cross-VM tests
exercise the affected serialized constants directly; no new node evaluation was
performed. Global parser hardening and recovery from already deduplicated external
DataItem trees remain outside this fix.
