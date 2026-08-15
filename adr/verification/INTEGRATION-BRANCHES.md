# Verification Integration Branches

This is a living coordination document for the verification work. It records
branch ancestry and merge order; it does not replace the architectural
decisions in the linked ADRs.

## Shared foundation

- **Branch:** `feat/verification-product-roadmap`
- **Recorded base:** `a14d214`
- **Purpose:** common implementation through C.7 plus the post-C.7 roadmap.
- **Rule:** integration work that depends on compiler-owned schemas, strict
  Lean codecs, or the managed verification runner starts from this branch
  until that foundation has landed on `main`.

`origin/main` does not contain all of this foundation as of 2026-08-13.
Creating the following integration branches directly from `main` would either
omit required compiler type/schema support or duplicate prerequisite commits.

## Active integration branches

### Typed verification DSL

- **Branch:** `feat/typed_verified_dsl`
- **ADR:** ADR-016, maintained on `feat/typed_verified_dsl`
- **Base:** `feat/verification-product-roadmap`
- **Current scope:** E.1 capability inventory, E.2 typed AST prototype, and
  E.3 seller-payment vertical slice are integrated.
- **Next scope:** E.4 purpose expansion waits for the applicable strict-boundary
  stages below.

Milestone work is developed on a dedicated feature branch and merged with a
non-fast-forward merge into this integration branch. Existing examples are:

- `feat/typed-verification-dsl-e1-capability-inventory`
- `feat/typed-verification-dsl-e2-typed-ast`
- `feat/typed-verification-dsl-e3-payment`

### Strict on-chain data boundaries

- **Branch:** `feat/strict-data-boundaries`
- **ADR:** [ADR-015](015-strict-on-chain-data-boundaries.md)
- **Base:** `feat/verification-product-roadmap`
- **Purpose:** implement strict typed datum/redeemer decoding as the corrected
  preview compiler default without making it depend on the experimental typed
  DSL.

The original branch plan reserved these milestone branches:

- `feat/strict-data-boundaries-s1-records-variants`
- `feat/strict-data-boundaries-s2-containers-optionals`
- `feat/strict-data-boundaries-s3-productive-recursion`
- `feat/strict-data-boundaries-s4-default-activation`

For the current implementation round, S.1–S.4 were completed as one
uncommitted working-tree review unit on `feat/strict-data-boundaries`, as
requested. No milestone branch was merged and no automatic commit was made.
After manual review, the temporary package-private comparison path was deleted;
S.4 makes strict semantics unconditional on every compiler construction path.
ADR-016 E.4 begins from that strict compiler semantics rather than relying on a
partial checker or a public opt-in.

### Purpose-indexed multi-validator blueprints

- **Branch:** `feat/purpose-indexed-multivalidator-blueprints`
- **ADR:**
  [ADR-017](017-purpose-indexed-multivalidator-blueprints.md)
- **Base:** `feat/strict-data-boundaries` at `b986752`.
- **Current state:** P.1–P.4 are implemented and reviewed; the branch is ready
  for commit and its separate pull request.
- **Purpose:** remove the blueprint opt-out for explicit supported-purpose
  `@MultiValidator` contracts while keeping schema capture UPLC-neutral.

The implementation publishes `SPEND`, `MINT`, `WITHDRAW`, and `CERTIFY` as
the standard CIP-57 purposes `spend`, `mint`, `withdraw`, and `publish`.
`VOTE` and `PROPOSE` remain fail-closed because the pinned CIP-57 vocabulary,
Aiken, and Scalus provide no standard blueprint purpose values for them.

ADR-017 is numbered after ADR-016 even though ADR-016's file lives on
`feat/typed_verified_dsl` and is not present on this older branch. Implementation
was intentionally developed as a separate child branch after ADR-015 was
reviewed and committed, so its pull request remains independently reviewable.

Because this branch is based before ADR-016 E.1–E.3, S.1 verification agreement
uses the C.5/C.6 fixtures present on the shared foundation. It must not copy E.3
files from the typed-DSL branch and create parallel histories. After S.4 merges
into `feat/typed_verified_dsl`, update and rerun E.3 there without handwritten
raw-shape checks before starting E.4.

## Dependency and merge order

```text
feat/verification-product-roadmap
    |-- feat/strict-data-boundaries
    |     |-- S.1 feature branch
    |     |-- S.2 feature branch
    |     |-- S.3 feature branch
    |     `-- S.4 feature branch
    |
    `-- feat/typed_verified_dsl
          |-- E.1 feature branch
          |-- E.2 feature branch
          `-- E.3 feature branch

S.1 + S.2 + S.3 coverage, then S.4 activation
    -> strict-boundary integration
    -> typed-DSL integration
    -> ADR-016 E.4a minting
```

Preferred landing sequence:

1. Land or otherwise establish `feat/verification-product-roadmap` on the
   target branch.
2. Complete and review the required ADR-015 stages independently.
3. Land strict-boundary work first, then update `feat/typed_verified_dsl` from
   the target branch; while work remains stacked, merge
   `feat/strict-data-boundaries` into `feat/typed_verified_dsl` explicitly.
4. Refresh E.3 fixtures and evidence against strict compiler output on the
   updated typed-DSL integration branch.
5. Start E.4 milestone branches from that reviewed integration state.

Do not merge `feat/typed_verified_dsl` into `feat/strict-data-boundaries`.
That would make the compiler feature depend on the experimental DSL and would
obscure its compatibility and regression boundary.

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
