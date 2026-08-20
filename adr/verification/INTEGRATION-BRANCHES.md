# Verification Integration Branches

This is a living coordination document for the verification work. It records
branch ancestry and merge order; it does not replace the architectural
decisions in the linked ADRs.

## Shared foundation

- **Branch:** `main`
- **Recorded point:** `bcfc3c7` (PR #86), superseded normally by later `main`
  commits.
- **Included foundation:** C.1–C.7, managed local/Docker execution, strict
  `strict-data-v1` compiler boundaries, purpose-indexed CIP-57 blueprints, and
  line-oriented verification progress. ADR-016 E.1 capability inventory, E.2
  typed AST prototype, and E.3 seller-payment vertical slice are also landed.
- **Rule:** new verification milestone branches start from current `main`
  after their prerequisite integration PR has landed.

## Active integration branches

### Typed verification DSL E.4

- **Integration branch:** `feat/typed-verification-dsl-e4`
- **Parent ADR:** [ADR-016](016-typed-verification-dsl-and-profile-catalog.md)
- **Base:** `bcfc3c7`, the PR #86 merge on `main`
- **Current milestone branch:**
  `feat/typed-verification-dsl-e4a-minting`
- **Current milestone ADR:**
  [ADR-018](018-milestone-e4a-typed-minting-dsl.md)
- **Current scope:** E.4a purpose-aware minting metamodel, shared
  controlled-mint semantics, explicit minting ledger domain, and a one-shot
  minting vertical slice.
- **Current state:** ADR-018 E.4a.1–E.4a.4 are implemented; manual review,
  local/Docker evidence, and GraalVM native verification are complete. The
  milestone branch is ready for its scoped commit and non-fast-forward merge.
- **Next scope:** complete manual review, then merge E.4a into this integration
  branch and use a separate branch/ADR for the next purpose slice.

Milestone work is developed on a dedicated feature branch and merged with a
non-fast-forward merge into this integration branch. Existing examples are:

- `feat/typed-verification-dsl-e1-capability-inventory`
- `feat/typed-verification-dsl-e2-typed-ast`
- `feat/typed-verification-dsl-e3-payment`
- `feat/typed-verification-dsl-e4a-minting`

## Landed prerequisite branches

### Typed verification DSL E.1–E.3

- **Branch:** `feat/typed_verified_dsl`
- **ADR:** [ADR-016](016-typed-verification-dsl-and-profile-catalog.md)
- **Landed:** PR #86 (`bcfc3c7`)
- **Outcome:** E.1 capability inventory, E.2 typed AST prototype, and E.3
  seller-payment vertical slice are on `main`. E.3 uses `strict-data-v1`, its
  fixtures contain no handwritten raw-shape checks, and its four expected
  classifications reproduce.

### Strict on-chain data boundaries

- **Branch:** `feat/strict-data-boundaries`
- **ADR:** [ADR-015](015-strict-on-chain-data-boundaries.md)
- **Landed:** PR #83
- **Outcome:** strict typed datum/redeemer decoding is the compiler default and
  remains independent of the experimental DSL.

The original branch plan reserved these milestone branches:

- `feat/strict-data-boundaries-s1-records-variants`
- `feat/strict-data-boundaries-s2-containers-optionals`
- `feat/strict-data-boundaries-s3-productive-recursion`
- `feat/strict-data-boundaries-s4-default-activation`

S.1–S.4 were reviewed and landed together. The temporary comparison path was
deleted; strict semantics are unconditional on every compiler construction
path. ADR-016 E.3 and later milestones therefore rely on the public compiler
semantics rather than a partial checker or public opt-in.

### Purpose-indexed multi-validator blueprints

- **Branch:** `feat/purpose-indexed-multivalidator-blueprints`
- **ADR:**
  [ADR-017](017-purpose-indexed-multivalidator-blueprints.md)
- **Landed:** PR #84
- **Current state:** P.1–P.4 are implemented and merged.
- **Purpose:** remove the blueprint opt-out for explicit supported-purpose
  `@MultiValidator` contracts while keeping schema capture UPLC-neutral.

The implementation publishes `SPEND`, `MINT`, `WITHDRAW`, and `CERTIFY` as
the standard CIP-57 purposes `spend`, `mint`, `withdraw`, and `publish`.
`VOTE` and `PROPOSE` remain fail-closed because the pinned CIP-57 vocabulary,
Aiken, and Scalus provide no standard blueprint purpose values for them.

ADR-017 deliberately remained independent of ADR-016. Its compiler schema
model is now consumed by the typed DSL through `ContractSchema.Purpose`; the
post-merge DSL compatibility fix is part of PR #86.

## Dependency and merge order

```text
main (C.1-C.7 + strict boundaries + purpose-indexed blueprints + E.1-E.3)
  -> feat/typed-verification-dsl-e4 (E.4 integration)
      -> feat/typed-verification-dsl-e4a-minting (ADR-018)
          -> merge to E.4 integration after manual review
```

Preferred landing sequence:

1. Complete affected Java, exact-UPLC, kernel-bridge, local, and Docker
   evidence for ADR-018.
2. Manually review E.4a without auto-committing the milestone branch.
3. Merge the reviewed E.4a branch into `feat/typed-verification-dsl-e4` and use
   a separate PR for the next E.4 purpose slice.
4. Keep compiler and blueprint work independent of the experimental DSL
   unless a separate accepted ADR changes that module boundary.

## Maintenance rules

- Update this document when an integration branch, base, dependency, or
  landing order changes.
- Keep milestone commits scoped; do not include unrelated working-tree files.
- Do not auto-commit a milestone before its requested manual review point.
- Record intentional UPLC/script-hash changes separately from verification-only
  changes. ADR-015 changes affected UPLC when strict semantics are activated
  as the preview compiler default; ADR-016 property declarations must remain
  UPLC-neutral.
- After prerequisite branches land, prefer rebasing or updating from the new
  target branch over preserving obsolete stacking solely for history.
