# ADR-038: Native pair destructuring for DataMatch dispatch

**Date:** 2026-09-06
**Status:** Implemented, validated and independently reviewed (PR #129 at 7f398b2f)
**Issue:** [#125](https://github.com/bloxbean/julc/issues/125)
**Governing decisions:** ADR-032 O4 and ADR-036

## Context and current behavior

`DataMatch` represents sealed-interface pattern dispatch. Its expansion in
`UplcGenerator.generateDataMatch` happens after `PairDestructuringPass`, so the
compiler-owned `UnConstrData` pair still uses separate projections. Merely
correcting its type cannot make the earlier pass visit this expansion.

## Goals and non-goals

Extend O4 to this producer, preserve observable semantics and historical
NONE/BASELINE bytes, and measure the incremental improvement over existing
PV11_SAFE. Do not change integer tag dispatch (#112), Java subset support,
boundary validation, Data encoding, arbitrary pair/map operations, or the
existing proof pass.

## Invariants and proof

1. Keep the outer strict `Let data = scrutinee`. Its value is evaluated once,
   before any match-local binder enters scope.
2. `UnConstrData(data)` is evaluated exactly once at the original position.
   Failure prevents all dispatch and decoding. Success proves a native pair
   `(Integer, List<Data>)`; both projections are then total and effect-free.
3. Replace only the pair binding and its two projections with a one-branch
   `PairMatch` binding tag first, raw fields second. Preserve the dispatch PIR
   and field extraction builder without substitution or code motion.
4. Keep branch selection, strict selected-field decoding (including unused
   fields), pattern-variable binding, traces, errors and recursion unchanged.
   Single-branch matches currently omit a tag test; this behavior stays intact.
5. Removing the private pair binder shifts de Bruijn indices; generate the
   unchanged body through the ordinary lexical scope stack to recompute them.
   No user variable renaming or free-variable substitution is introduced.
   If the dispatch has a free reference to the historical `__match_pair`
   binder, retain the legacy expansion. Direct PIR may depend on that binding;
   deleting it would change capture or produce an unbound variable.
6. Require the exact PLUTUS_V3_PV11 target, safe optimization level, and
   CASE_ON_BUILTIN_CONSTANTS capability. Other levels retain the historical
   expansion verbatim. Final target validation remains mandatory.

## Decision

Use candidate B from #125: construct a typed `PairMatch` directly in
`generateDataMatch`, then use the existing generator for its semantics, source
position inheritance and `pv11.o4.case-pair` provenance. This is the same O4
operation with a producer-by-construction proof; no new rule ID or public API
is needed. Share the gate between this producer and PairMatch consumption.

Include all DataMatch cardinalities: empty, singleton and fieldless PIR matches
passed strictness/outcome and non-increasing budget checks. The complete
singleton source fixture saves 10 bytes and 491,887 CPU / 1,364 memory per
executed match compared with the previous safe output. Both strict projections
remain in the historical expansion even when the dispatch does not use them.
The lexical compatibility fallback above is the only additional shape gate.

## Alternatives

- Earlier typed decomposition would work but moves a compiler stage and risks
  historical bytes, source maps and unrelated PIR consumers unnecessarily.
- A raw UPLC projection peephole cannot establish the native-pair invariant.
- Retyping the late pair alone does not change pipeline timing.
- Integer Case changes dispatch failure/default semantics and is separate work.

## Affected stages and modules

Production: julc-compiler UPLC generation only. Validation: compiler source and
PIR tests, Java/Truffle/Scalus evaluation, decompiler, compiler consumers,
external examples, and available Haskell-node integration. No new IR variant
or exhaustive visitor changes.

## Compatibility and risks

NONE/BASELINE must remain byte-identical to main f27f1ca0. Safe profiles,
including the default, change newly compiled scripts containing DataMatch;
record hashes and migration guidance before completion. Existing deployed
scripts and Data encodings do not change. Public direct-PIR compilation also
receives the new safe lowering. JuLC remains experimental.

Primary review risks are lexical capture through nested matches/LetRec,
selected-branch decoding, strictness on malformed input, source maps, and
unprofitable matches whose components are unused. Existing compiler-generated
names are retained; this change does not redesign naming conventions.

## Milestones and verification

- [x] Read governing design and inspect producer/consumer; self-review proof.
- [x] Capture source fixtures on unmodified main compiler before implementation.
- [x] Implement bounded producer and focused semantic/structural tests.
- [x] Compare actual source switches, nested/recursive sums, guards, unused
      fields, unknown tags, malformed fields/arity, closures and binder shadowing.
- [x] Compare Java/Truffle results, errors, traces and pinned costs; Scalus
      language-level cross-check (not ledger certification).
- [x] Verify deterministic bytes, historical compatibility, source maps,
      provenance and decompiler behavior.
- [x] Run affected suites, full build/conformance, local-Maven external examples
      and available direct Haskell-node evidence.
- [x] Self-review final diff, record measurements and limitations.
- [x] Independent correctness review before merge (Claude review supplied by the maintainer; approved with notes).

## Open questions

Independent review approved the implementation at `7f398b2f` with no correctness
findings. Dedicated decompiler recognition is tracked in
[#130](https://github.com/bloxbean/julc/issues/130). See
[validation evidence](evidence/038-switch-pair-case.md) for the review scope,
measurements, reproduction and the unrelated pre-existing nested-yield finding.
