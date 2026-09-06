# Issue draft: ADR-036 follow-up — native Pair Case for sealed-interface switches

Status: Filed as https://github.com/bloxbean/julc/issues/125 on 2026-09-06; the issue body supersedes this draft.
Parent: #111; release plan #121. Governing design: ADR-032 O4 / ADR-036.

## Problem

`UplcGenerator.generateDataMatch` creates a strict binding of `UnConstrData`,
then projects both constructor tag and field list. The pair Var is annotated
Data, and this PIR is created inside UPLC generation after PairDestructuringPass
has run. Consequently sealed-interface switch decomposition retains FstPair and
SndPair even under PV11_SAFE. Strict boundary validation for the same source may
independently use O4; that does not mean its switch decomposition is optimized.

## Scope

Design a typed lowering for this specific compiler-owned producer. Evaluate
moving constructor decomposition earlier into typed PIR versus a narrowly
scoped generation path that reuses the existing PairMatch invariant. Retyping
the local variable alone cannot address the pipeline timing. Avoid a raw UPLC
peephole or widening eligibility to arbitrary pair values or map traversal.
Document the chosen stage and compatibility in an ADR before implementation.

## Acceptance criteria

- Prove a once-evaluated successful UnConstrData result is the native pair.
- Preserve tag dispatch, raw field order, branch-local partial decoding,
  scrutinee/body traces, errors, and malformed tag/arity behavior.
- Test actual source switches, nested switches, recursive sums, guarded/unused
  fields where supported, and malformed/unknown-tag inputs against BASELINE.
- Cover LetRec and DataMatch field/pattern-variable shadowing and closure capture.
- Require exact PV11 target/capability and safe profile; preserve historical
  NONE/BASELINE bytes and document safe-profile hash changes.
- Confirm Java/Truffle equivalence, Scalus language-level agreement where
  supported, and pinned complete-source size/CPU/memory including failure paths.
- Preserve source maps, provenance and decompiler behavior; run focused,
  affected-module, aggregate, conformance and full-build checks.
- Obtain independent correctness review before enabling the additional family.
