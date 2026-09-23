# ADR-051: Reuse typed Data decoding for switch-pattern fields

**Status:** Implemented and locally validated; independent review pending
**Issue:** [#166](https://github.com/bloxbean/julc/issues/166)

## Context and current behavior

Switch patterns cache constructor fields in typed PIR bindings. Their late
DataMatch expansion uses a private decoder in UplcGenerator which covers Integer,
ByteString, List and Map, but not Bool or String. The result is raw Data in a
binding declared native Bool/String. Native consumers fail at every optimization
level. Direct record and instanceof field access use PirHelpers.wrapDecode,
which already handles both types. This duplicate implementation predates #164/165.

## Goals, non-goals and invariants

- A projected field must have its declared runtime representation.
- Reuse the existing codec; do not create another type-to-decoder table.
- Evaluate the scrutinee once, select one arm, then strictly decode its fields in
  source order before executing its body, including unused bound fields.
- Preserve constructor tags, field order, Data encoding and switch dispatch.
- Preserve #162 case-binding immutability, ADR-048 ownership and ADR-050 joins.
- Do not add strict in-body Data validation or general mutable-local support.

## Decision

Call PirHelpers.wrapDecode from buildBranchFieldExtraction and remove the private
pirWrapDecode. Bool uses the existing constructor-tag-equals-one decoder; String
uses DecodeUtf8(UnBData). Other supported types get the same PIR as before.
Native opaque types remain forbidden at Data boundaries by the shared helper;
direct PIR can no longer silently claim that such a field is native.

This corrects the field-decoding premise of ADR-038/041, without changing their
pair/dispatch strategy. Their historical byte guarantees describe those earlier
optimization changes, not a requirement to retain this incorrect decoding.

## Compatibility and failure behavior

Affected Bool/String switches change bytes, hashes and budgets on recompilation
at every level, including NONE and BASELINE. Previously deployed scripts do not
change. Previously ignored malformed Bool/String fields may now fail during strict
selected-field decoding, before the arm body; this is intentional. Unselected arms
are not decoded. Other field types retain their lowering.

The shared decoder is not a strict boundary validator: its historical Bool rule
tests tag == 1 without validating constructor arity or rejecting other tags.
This fix preserves that contract. Wrong Data kinds and invalid UTF-8 fail via the
existing builtins. Strict typed validator-boundary checks are unchanged.

## Alternatives

- Add only Bool to the private decoder: leaves String broken and preserves drift.
- Decode at each accessor: duplicates work and changes unused-field strictness.
- Redesign strict Bool validation here: expands the source/boundary contract and
  would diverge from existing access paths; track separately if desired.

## Affected stages and modules

julc-compiler PIR-to-UPLC DataMatch expansion, source/direct-PIR tests and docs.
No VM, ledger, public API, frontend scope or optimizer changes.

## Milestone and verification

1. Reproduce Bool and String failures at every level before the production edit;
   verify the corrected integer example for #165 and file the separate issue.
2. Share decoding; test source patterns, direct records/instanceof controls,
   locally constructed values, nested switches, multi-field and loop composition.
3. Pin strict selected-field decoding, unselected-arm laziness, malformed inputs,
   trace order, native-type rejection, determinism and unaffected field bytes.
4. Run focused and complete compiler/backend suites, downstream build and docs;
   document script migration and limits of evidence before review.

## Risks and open questions

The principal risks are moving a decode outside its selected branch, changing
failure order, or making codec coverage diverge again. Tests exercise the public
source pipeline and direct DataMatch separately. This is a scoped repair, not
evidence that every loop/switch composition is correct. No new open design
question is required for the existing codec semantics.

## Validation evidence

Before editing production code, source tests reproduced Bool and String runtime
failures at every optimization level on parent `fe97f9a9`. Its UplcGenerator is
identical to main `dbfc8ff5`. The corrected integer example for #165 passed at all
four levels before this fix. Issue #166 records the separate defect.

`./gradlew build -PskipSigning=true` passed (218 actionable tasks): compiler 1,665,
cross-backend 71, stdlib 411, testkit 193 and examples 81, with no failures/errors/
skips in these suites. Existing switch dispatch byte goldens and #162/#165
rejection regressions passed. A final unused-Bool strictness assertion was added
after the full build; the complete SwitchFieldDecodeTest suite was then rerun on
Java, Truffle and Scalus successfully. Its final matrix performs 436 evaluations
across all four levels plus 16 opaque-native rejection checks; Scalus uses its
compatibility API, not protocol certification.

The docs build produced 33 pages and diff checks passed. Blaster artifact
preparation reproduced all seven committed baseline artifact bytes and lock
metadata without refreshing the lock. This is an artifact comparison, not a new
Lean/Blaster proof run. No fresh external validator corpus, DevKit/on-chain or
native-image validation was performed.
