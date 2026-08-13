# Strict-boundary downstream audit

Audit date: 2026-08-13

## This repository

`julc-examples` is part of the Gradle build and compiles/evaluates validators
rather than pinning generated script hashes. It is included in the final
repository regression run. C.5-C.7 fixture hashes are intentionally regenerated
and their exact strict replacements are recorded in `measurements.json`.

## `bloxbean/julc-examples`

The sibling checkout was inspected read-only. Searches found no committed
`plutus.json`, compiled-code, or validator script-hash golden that needs a
mechanical replacement; the hexadecimal constants found in tests are contract
data/MPF fixtures, not generated validator identities.

That checkout already had unrelated modifications and untracked files, so
ADR-015 deliberately did not edit or clean it. A temporary copy was built
against the strict compiler published under an isolated local snapshot. With
blueprint generation disabled, its full Gradle suite passed: 420 tests, zero
failures, zero errors, and 68 skips. No validator source needed a strict-boundary
migration. In particular, its typed `BigInteger` redeemer already used the
decoded value directly, while explicit `PlutusData` roots and nested raw ledger
values correctly retained their `unIData`/`unBData` operations.

Blueprint-enabled compilation separately fails for purpose-indexed
`@MultiValidator` aggregation. That pre-existing CIP-57 limitation is unrelated
to strict decoding and is planned in
[ADR-017](../../adr/verification/017-purpose-indexed-multivalidator-blueprints.md).
Until ADR-017 is implemented, downstream builds containing those validators
must use the documented blueprint opt-out. Projects that persist script
addresses outside source control must still update them during deployment
migration.

## Release coordination checklist

- publish the strict-boundary breaking change and migration guide with the
  compiler release;
- rebuild examples against the released version;
- review script hash, size, and execution budget changes for each deployable
  validator;
- canonically re-encode state before moving it to a new script address; and
- regenerate `julc verify` workspaces so certificates record
  `boundarySemantics: strict-data-v1`.
