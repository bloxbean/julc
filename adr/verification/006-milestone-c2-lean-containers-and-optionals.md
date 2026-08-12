# ADR-006: Milestone C.2 — Lean Containers and Optional Values

- **Status:** Implemented
- **Date:** 2026-08-12
- **Parent:**
  [ADR-004 — Milestone C Reusable Verification Integration](004-milestone-c-reusable-verification-integration.md)
- **Predecessor:**
  [ADR-005 — Compiler-Owned Blueprint Schemas](005-milestone-c1-compiler-owned-blueprint-schema.md)

## Context

Milestone C.1 made JuLC's resolved compiler type model the source of CIP-57
datum and redeemer schemas. `julc verify init` still rejects the resulting
boolean, optional, list, and map schemas, so contracts using those otherwise
supported boundary types need handwritten Lean encoders and decoders.

C.2 closes that gap without adding a second Java-to-Lean metadata path. The
generated verifier continues to consume the exact `plutus.json` produced by
the build and interprets only its CIP-57 schema graph.

## Decision

### Schema translation

`julc verify init` will recursively translate these CIP-57 forms:

| CIP-57 schema | Generated Lean type | Plutus `Data` encoding |
| --- | --- | --- |
| JuLC `False`/`True` constructor sum | `Bool` | `Constr 0 []` / `Constr 1 []` |
| JuLC `Some`/`None` constructor sum | `Option α` | `Constr 0 [a]` / `Constr 1 []` |
| `dataType: list` | `JulcList α` | `Data.List [a...]` |
| `dataType: map` | `JulcMap κ υ` | `Data.Map [(k, v)...]` |
| named record or variant | generated named Lean type | existing strict constructor encoding |

The translator recognizes boolean and optional types by their complete
constructor shape, not by a definition-key spelling. This keeps CIP-57 as the
portable boundary while still rejecting malformed tags, arities, and fields.
Container item, key, and value schemas are translated recursively, allowing
arbitrary nonrecursive nesting such as:

```text
Option (JulcList (JulcMap ByteString Integer))
```

Named recursive references remain rejected until Milestone C.3.

### Lean representation

The generated module defines:

```lean
structure JulcList (α : Type) where
  items : List α

structure JulcMap (κ υ : Type) where
  entries : List (κ × υ)
```

Dedicated wrappers are intentional. `CardanoLedgerApiBlaster` already has
special-purpose `IsData (List T)` instances for ledger types, while a Plutus
map is also represented in Lean as an association list. Separate wrappers
make the wire distinction explicit and avoid overlapping global `IsData List`
instances.

Generated `IsData` instances recursively decode every element, key, and value.
They return `none` for the wrong outer `Data` constructor or for any malformed
nested value. Encoding preserves list and map order.

The pinned `CardanoLedgerApi.IsData.Class` instances for `Bool` and `Option`
already implement JuLC's exact strict constructor encodings. Generated types
use those audited instances rather than shadowing them with competing orphan
instances.

### Duplicate map keys

Duplicate keys are **preserved**, including their original order.

This follows JuLC's actual representation rather than `java.util.Map`
intuition:

- the compiler decodes a map boundary with `UnMapData`, producing the original
  association list;
- `JulcAssocMap` stores an ordered list and permits duplicate entries through
  construction and insertion; and
- lookup scans from the front, so the first matching entry wins.

The Lean decoder therefore validates the type of every key and value but does
not impose uniqueness, sorting, or normalization. Re-encoding a successfully
decoded map retains duplicate entries byte-for-`Data` structurally. Equality
in Lean properties is association-list structural equality, not extensional
map equality: entry order and duplicates remain observable even when lookup
results agree.

### Failure model

Generation remains fail closed. Unsupported inline schemas, malformed
constructor definitions, unknown references, and recursive references abort
before a workspace is published. Existing `--no-blueprint` build options are
unaffected; they intentionally cannot feed `julc verify init`.

## Implementation plan

1. Refactor schema traversal so references and inline container children share
   one recursive type translation.
2. Classify exact boolean and optional constructor sums before treating other
   sums as generated named types.
3. Generate strict `JulcList` and `JulcMap` codecs and expose resolved Lean type
   expressions in the verification manifest.
4. Add Java tests for schema translation, arbitrary nesting, malformed shapes,
   recursive rejection, and deterministic generation.
5. Compile generated Lean codec tests covering:
   - boolean tags and arity;
   - optional tags, arity, and malformed payloads;
   - valid and invalid list elements;
   - valid and invalid map keys and values;
   - duplicate-key preservation; and
   - nested failure paths.
6. Generate both spending and minting workspaces from real JuLC contracts and
   compile them against the pinned Blaster, PlutusCore, and CardanoLedgerApi
   revisions.
7. Run the full Gradle suite and the Milestone A/B artifact-lock suite to prove
   that C.2 changes verification generation rather than compiled scripts.

## Exit criteria

Milestone C.2 is complete when:

- supported boolean, optional, list, and map contract schemas require no
  handwritten Lean codecs;
- arbitrary nonrecursive nesting generates deterministic, compiling Lean;
- strict positive, malformed, and duplicate-map tests pass;
- generated spending and minting workspaces compile with the pinned stack;
- existing generated record/variant workspaces remain compatible;
- the compiler and artifact-lock regression suites pass unchanged; and
- the getting-started guide describes the new supported subset and Lean
  wrappers.

## Non-goals

- Recursive or mutually recursive schemas; these remain C.3.
- Converting association-list maps to Lean balanced maps or enforcing unique
  keys.
- Changing JuLC compiler lowering, Java collection APIs, or on-chain bytes.
- Claiming a contract security theorem merely because its generated schema
  workspace compiles.

## Implementation outcome

Milestone C.2 was implemented on 2026-08-12 on the feature branch
`feat/verification-c2-lean-containers`.

The schema translator now walks inline and referenced CIP-57 forms through one
recursive dependency analysis. Exact JuLC boolean and optional sums resolve to
the pinned strict `Bool` and `Option` instances. Lists and maps resolve to
generated `JulcList` and `JulcMap` wrappers with total recursive decoders that
fail if any nested value has the wrong shape.

The committed `verification/c2/CodecTests.lean` evidence includes concrete Lean
round trips, all malformed cases listed in the plan, duplicate-key preservation,
and a nested failure. The evidence script reproducibly compiles real spending
and minting Java fixtures, passes them through `julc verify init`, checks them
with the generated artifact-hash and dependency-pin driver, and compiles the
reproduced workspaces against the pinned Blaster stack.

Verification completed with:

- `verification/c2/scripts/verify.sh` — established C.2 codec and workspace
  compatibility for spending and minting;
- `./gradlew test` — all repository unit and integration tests passed; and
- `verification/blaster/scripts/verify-offline.sh` — Milestone A/B artifact
  locks, positive properties, and negative controls remained established.

No compiler lowering or generated UPLC bytes were changed by C.2.
