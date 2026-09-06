# ADR-039: Conservative constructor-pair dispatch recovery

**Date:** 2026-09-06
**Status:** Implemented and validated; independent review pending
**Issue:** #130; stacked on PR #129 / ADR-038

## Context

ADR-038's native pair Case misses the legacy DataMatch recognizer. Generic Case
recovery presents the pair's tag/list as constructor fields. The old recognizer
also accepts unrelated projection variables and loses the final fallback.
Existing HIR Switch has neither explicit tag/list bindings nor a default body.
FLAT erases lambda names, so name-based recovery cannot prove lexical binding.

## Goals and non-goals

Recognize compiler-owned native and legacy UnConstrData decomposition, preserve
raw field extraction and fallback, and improve readable output after FLAT
round-trip. Do not change compiler output, infer Java record schemas, promise
recompilable Java, repair every existing decompiler heuristic, or certify HIR
as a general executable representation of arbitrary UPLC.

## Decision and invariants

- Add a distinct HIR DataMatch with explicit pair/tag/fields names, ordered
  BigInteger tag branches and a residual/default body. The existing SOP Switch
  remains separate. Singleton matches have no invented tag check.
- Native recognition requires exactly one Case branch with two leading lambdas
  and a directly saturated UnConstrData scrutinee. Legacy recognition verifies
  FstPair index 1 then SndPair index 2 of the same once-bound pair.
- Parse only saturated EqualsInteger against the exact tag binder (index 2),
  in correctly shaped lazy IfThenElse or Case Bool dispatch. Preserve the entire
  unmatched remainder as fallback; do not drop errors, traces or computations.
- Require at least one proven tag comparison when the body begins with a
  conditional; otherwise ambiguous lookalikes retain generic recovery. Direct
  singleton bodies and leading field-decode Lets remain valid decomposition.
- Keep raw branch terms unchanged. Lift each body in its original binding
  context, without pulling partial field decoders out of selected branches.
- Resolve names from de Bruijn indices with unique synthetic binder names before
  lifting programs containing a recognized match. This is a names-only copy:
  indices, structure and serialized FLAT bytes stay identical. Other programs
  retain the existing naming path. Unique names protect captures through nested
  matches and the existing naming/type passes.
- Run the strict recognizer before generic SOP recovery. Keep the old public
  recognizer API for compatibility; use the new strict entry point for lifting.
- Render explicit UnConstrData/projection bindings and ordered integer equality
  dispatch, including the fallback. Do not invent record constructor names or
  narrow arbitrary tags to int. Render singleton bodies without a tag guard.

## Alternatives

Extending generic SOP recovery misidentifies native pair Case as a sum type.
Reusing HIR Switch would omit fallback and explicit raw bindings. Guessing names
from lambda debug strings fails after serialization. Expanding compiler scope
or rewriting existing scripts is unnecessary for a decompiler-only correction.

## Affected modules and compatibility

julc-decompiler: recognition, name resolution, HIR, naming/type consumers and
Java rendering; julc-analysis traversal and conditional-guard classification;
docs and tests. A new public sealed HIR variant is deliberate:
external exhaustive visitors must handle DataMatch. Compiler APIs, bytes and
costs are unchanged. Existing legacy recognizer API remains available.

## Risks and verification

Test exact binder identity, reversed comparisons, nested capture/shadowing,
wrong force/arity/producer/projection shapes, huge tags, fallback traces/errors,
unused malformed fields, singleton and fieldless matches. Compile real source
at NONE/BASELINE/PV11_SAFE and repeat after FLAT serialization. Use a bounded
HIR-to-UPLC test interpreter/reconstruction for the new representation to check
results, failures and traces, not just node shapes. Run the decompiler suite,
consumer tests and full build; inspect all HIR consumers and diff against PR129.

## Milestones

- [x] Inspect current representation and review design invariants.
- [x] Implement strict recovery and explicit HIR/rendering.
- [x] Add source, adversarial and semantic regression coverage.
- [x] Run affected and full validation; review and record limitations.
- [ ] Open separate PR targeting feat/125-switch-pair-case.

## Review refinements

Self-review identified two consumer details beyond matching syntax. The
analyzer's recursion heuristic must count a non-empty tag dispatch as a guard,
but not unconditional singleton decomposition. Name/type consumers must restore
match scope separately for each branch and fallback. Both have been handled;
analysis traversal visits the scrutinee, every branch and the fallback.

Rendering uses explicit `BigInteger.equals(new BigInteger("tag"))`, avoiding
both integer narrowing and the general renderer's arithmetic-operator heuristic.
The recovered representation intentionally renders as decomposition plus ordered
if/else dispatch, rather than fictitious record-pattern cases. Schema names,
recompilable Java and general executable-HIR equivalence remain non-goals.

See [validation evidence](evidence/039-decompiler-constructor-pair-dispatch.md).
