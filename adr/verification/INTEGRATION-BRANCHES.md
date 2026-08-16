# Verification Integration Branches

This is a living coordination document for the verification work. It records
branch ancestry and merge order; it does not replace the architectural
decisions in the linked ADRs.

## Shared foundation

- **Branch:** `main`
- **Recorded point:** `321669b` (PR #85), superseded normally by later `main`
  commits.
- **Included foundation:** C.1–C.7, managed local/Docker execution, strict
  `strict-data-v1` compiler boundaries, purpose-indexed CIP-57 blueprints, and
  line-oriented verification progress.
- **Rule:** new verification milestone branches start from current `main`
  after their prerequisite integration PR has landed.

## Active integration branches

### Typed verification DSL

- **Branch:** `feat/typed_verified_dsl`
- **ADR:** [ADR-016](016-typed-verification-dsl-and-profile-catalog.md)
- **Base:** updated from `main` by merge commit `6a58b34`
- **Pull request:** #86
- **Current scope:** E.1 capability inventory, E.2 typed AST prototype, and
  E.3 seller-payment vertical slice are integrated. E.3 has been refreshed
  against `strict-data-v1`; its fixtures contain no handwritten raw-shape
  checks and its four expected classifications reproduce.
- **Next scope:** merge PR #86, then begin E.4a minting on a separate milestone
  branch and pull request.

Milestone work is developed on a dedicated feature branch and merged with a
non-fast-forward merge into this integration branch. Existing examples are:

- `feat/typed-verification-dsl-e1-capability-inventory`
- `feat/typed-verification-dsl-e2-typed-ast`
- `feat/typed-verification-dsl-e3-payment`

## Landed prerequisite branches

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
main (C.1-C.7 + strict boundaries + purpose-indexed blueprints)
  -> feat/typed_verified_dsl (E.1-E.3, PR #86)
      -> merge to main
          -> ADR-016 E.4a minting milestone branch
```

Preferred landing sequence:

1. Review and merge PR #86 after its strict E.3 evidence and CI pass.
2. Update current `main` before creating the E.4a branch.
3. Give E.4a a detailed semantic sub-ADR, positive/vulnerable/malformed/vacuous
   controls, and its own manual review point.
4. Keep later compiler and blueprint work independent of the experimental DSL
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
