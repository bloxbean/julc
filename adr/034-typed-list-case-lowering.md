# ADR-034: Typed List Case lowering for compiler-generated for-each

Status: Implemented and locally validated on `feat/110-typed-list-case`; independent Claude review reported by the developer: approve with notes. Review follow-ups are recorded below; merge/release approval is separate.

Parent: ADR-032 O3; issue #110. Target: `plutus-v3-pv11-uplc-1.1.0`.

## Context and current behavior

For-each desugaring builds a recursive fold using NullList, HeadList and
TailList. PV11 Case can select empty/non-empty and bind raw head/tail once.
ADR-032's raw head experiment establishes potential, not traversal equivalence.

## Goals and non-goals

Own one source family: compiler-generated for-each over native List<Data>,
including nested loops, multiple accumulators and break. Preserve existing Java
source behavior. Do not rewrite arbitrary while loops, HOF builders, list
helpers, native-pair map traversal, strict boundary validation, or Data field
projection templates. Those require independently reviewed evidence.

## Decision and explicit invariants

Add `PirTerm.ListMatch(scrutinee, headName, tailName, nilBranch, consBranch)`.
Its representation contract is native List<Data>, with raw Data head and native
List<Data> tail. It does not perform UnListData or element decoding. Its producer
must establish that contract; initially the only production producer is
LoopDesugarer, whose existing xs parameter has exactly this representation.

- Evaluate the scrutinee once; evaluate only the selected branch.
- Head/tail bind only in the non-empty branch, head then tail.
- Preserve the existing explicit wrapDecode placement in that branch.
- Preserve body/accumulator/recursive-call order, including break.
- Extracting a raw head/tail of a proven non-empty native list is total and
  effect-free; binding those values earlier does not evaluate a Data decoder.
- The empty branch does not access or decode an element.
- Fresh internal binders cannot collide with Java identifiers or nested matches.
- Preserve binding scope in substitution, free-variable analysis and visitors.
- Keep source mapping on emitted Case and its children.
- No Data, native pair, array, record or ledger boundary representation changes.

UPLC shape:

```
Case xs [(lam head (lam tail consBranch)), nilBranch]
```

Before (ordinary fold):

```
if NullList(xs) then acc
else loop(TailList(xs), let item = decode(HeadList(xs)) in body)
```

After:

```
if NullList(xs) then acc else
  list-match xs [] => error
    [head :: tail] => loop(tail, let item = decode(head) in body)
```

### Validation-driven refinement: retain the runtime representation guard

An adversarial source fixture found existing unchecked `(JulcList)(Object)`
casts are no-ops and can put Data in an apparently List-typed variable. Therefore
static source typing alone does not prove the runtime list representation.
Retain the original NullList guard; it preserves the precise non-list failure
and proves a native non-empty list on the Case path. The match's empty branch
is unreachable through this producer and lowers to Error to avoid embedding an
unused accumulator. Empty traversal retains its original
check and branch costs (modulo the already-enabled O2 Bool lowering).
Do not remove this guard until a separate representation proof exists. This
refines the initial design in response to repository evidence; it does not
change source semantics or silently repair unchecked casts.

Break-capable folds keep `let item = decode(head)` outside the body decision;
only a continuing path calls `loop(tail, newAcc)`. A malformed later element
must remain unobserved after break. Non-list external Data fails at the same
existing UnListData/strict boundary before entering the match.

### Profitability refinement: no tail consumption

The pre-change PV11_SAFE artifact comparison found a one-byte regression for
an unconditional-break loop with only a head projection. The break producer
uses existing PIR free-variable analysis to require a tail use before choosing
ListMatch. If no tail is referenced, restore the original head projection at
its exact position and retain the legacy body. The builder is not invoked twice.
This is a local eligibility check, not generic use/escape analysis.

## Compatibility and rollout

Use the existing PV11_SAFE/PV11_COSTED selection and explicit PV11 target plus
CASE_ON_BUILTIN_CONSTANTS capability, with stable rule `pv11.o3.case-list`.
NONE and BASELINE generate the existing PIR directly; preserve its exact bytes,
including source-map mode. No PV10 source compiler or new optimization level.
Direct unsupported-profile lowering of ListMatch fails closed.

The default PV11_SAFE output can change on recompilation. Existing deployed
scripts are unchanged. Keep BASELINE for historical reproduction. Enablement
requires the evidence below and independent correctness review before merge.

## Affected modules and stages

- julc-compiler: for-each desugaring, typed PIR/visitors, UPLC generation.
- julc-benchmark: compiled-source cross-backend evidence, explicit List Case semantic vectors and budget fixtures.
- julc-decompiler: recognize the guarded List Case traversal alongside historical projections.
- compiler/stdlib/testkit/tooling/examples: regression validation.
- Java/Truffle/Scalus VMs: conformance and differential validation; no VM changes.
- docs: rollout, scope and script-hash migration.

## Alternatives rejected

Untyped Null/Head/Tail peepholes lose representation and decode timing proof.
Changing every list/HOF builder at once would infer unproven traversal-family
semantics. General CSE/use analysis and Pair lowering belong to separate work.
Adding a PV10 compiler fallback contradicts ADR-031/032; baseline is an
optimization choice under the supported PV11 target.

## Risks and verification strategy

Test empty/singleton/many/nested lists, recursive calls, early break, multiple
accumulators, malformed outer/element/nested Data, unused decoded items,
selected/unselected failures and exact traces. Check expected values separately
from differential equality. Test free-variable/binder scope, source positions,
rule provenance, deterministic bytes and baseline goldens. Measure FLAT and
pinned CPU/memory, including empty and break paths; do not assume the raw head
microbenchmark predicts full-validator savings.

Run focused tests, affected modules, compiled-source Java/Truffle comparisons,
Scalus language-only comparisons where supported, full VM conformance matrices,
aggregate optimization evidence, repository build and documentation build.
Scalus conformance is a classified known-divergence matrix, not ledger parity.
External Yaci tests require an available developer devnet and are separate from
VM conformance. Review all changed visitors and diff against these invariants.

## Milestones

1. Typed node and for-each producer/lowering, baseline retained.
2. Adversarial semantic, representation, binding and compatibility tests.
3. Cross-backend cost/hash evidence and conformance/build validation.
4. Review/iterate and record evidence; independent review before merge.

## Open questions

Future traversal families need their own source patterns and verification.
No shared traversal abstraction or broader rewrite is authorized by this ADR.

## Implementation evidence

See the [validation and review report](evidence/034-list-case-validation.md) and
[reproducible size/budget/hash tables](evidence/034-list-case-measurements.md).
Focused tests, affected modules, fresh VM/conformance suites, repository build
and documentation build passed. The developer supplied an independent Claude
review approving with notes; see the validation report for its fresh-run scope
and the follow-up changes. These follow-ups have local self-review and tests,
not a second independent review.

## Review follow-ups

The decompiler recognizes the specific O3 shape: a two-branch Case on NullList
whose false branch immediately cases on the same variable, with head/tail
lambdas in branch 0 and Error in branch 1. Compare de Bruijn indices, not names,
so FLAT roundtrips preserve recognition. Unrelated constructor Cases, mismatched
scrutinees and reversed branches do not qualify. Historical recognition remains.
This restores loop classification only: the existing lifter emits LetRec and
does not use that classification to reconstruct a Java for-each statement.

Explicit serialized term-level tests pin List Case branch order, head/tail
binding, laziness and selected failures on Java/Truffle PV11 and Scalus's
language-only API. The 999-case builtin/example conformance inventory does not
itself cover term-level List Case; it is separate regression evidence.

Retain the two-branch List Case. Dropping the unreachable nil branch is deferred
to a separately reviewed ADR change with backend verification and measurements.
Retain the explicit O3 target gate; its asymmetry with O2 is harmless while the
compiler registry accepts only the exact PV11 target.

A for-each validator executed on an available Yaci DevKit remains an outstanding
pre-release gate, not a result implied by local VM or conformance tests.
