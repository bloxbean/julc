# Plutus conformance test snapshot

This directory is a byte-identical mirror of the official Plutus conformance
suite (`plutus-conformance/test-cases/uplc/evaluation`) from the source of
truth:

- **Repository:** <https://github.com/IntersectMBO/plutus>
- **Pinned commit:** [`643ddd135`](https://github.com/IntersectMBO/plutus/tree/643ddd135/plutus-conformance/test-cases/uplc/evaluation)
  (master, 2026-04-10)
- **Last synced:** 2026-08-01 (7 stale `unValueData`/`valueData` budget files
  refreshed to the post-[#7617](https://github.com/IntersectMBO/plutus/pull/7617)
  values; the rest of the tree dates from the original project snapshot and was
  verified identical to the pinned commit)

Contents: 999 test cases, each with `<name>.uplc` (input),
`<name>.uplc.expected` (result), and `<name>.uplc.budget.expected` (CPU/mem
budget). The 728 numeric budget files were generated upstream with the default
cost model at the pinned commit (semantics variant C). Under the real V3/PV10
ledger profile, 236 fixtures using the PV11-only Batch 6 builtins are marked
unavailable, as are 26 fixtures that use PV11-only `Case` on builtin constants;
the remaining 737 fixtures include 545 exact numeric budget comparisons. This
prevents an expected-failure fixture from passing at PV10 merely because a
protocol gate rejected it first.

Everything under this directory except this file comes verbatim from upstream —
do not hand-edit test data. To verify the mirror:

```bash
diff -rq julc-vm/src/test/resources/conformance \
    <plutus-checkout>/plutus-conformance/test-cases/uplc/evaluation -x README.md
```

## Verified against cardano-node 11.0.1

cardano-node `11.0.1` (mainnet PV11) ships plutus `1.63.0.0` at commit
[`f92b7d7d8`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-conformance/test-cases/uplc/evaluation)
(2026-05-02). Blob-level comparison against that commit (2026-08-01): the file
set is identical (3001/3001, nothing missing, nothing extra); 65 files differ
in content, all attributable to the PV11 semantics-variant work tracked by
issue #61:

- 61 `.budget.expected` regenerated under the 1.63 default cost model
  (variant D/E string costing — e.g. `appendString` cpu 680670 → 141057 —
  plus recalibrated division/expModInteger/hash constants);
- 4 `.uplc.expected` semantic flips: `shiftByteString`/`rotateByteString` by
  more than `maxBound::Int64` now fail
  ([#7754](https://github.com/IntersectMBO/plutus/pull/7754)).

This snapshot intentionally remains the pre-D/E profile (variant C / PV10).
The 65 expectations that differ in the pinned node release are stored as the
adjacent `conformance-pv11` overlay and are run as the V3/PV11/E profile. The
shared inputs and all unchanged expectations continue to come from this base,
so neither profile can drift independently or silently stop asserting budgets.

## Known upstream drift since the pinned commit

The PV11 overlay is intentionally pinned to the Plutus commit shipped by
cardano-node 11.0.1. Later Plutus `master` is outside issue
[#61](https://github.com/bloxbean/julc/issues/61) and ADR-030. Upstream changes
after the base commit include:

- Budget files renamed `<name>.uplc.budget.expected` → `<name>.budget.expected`
  and `.flat`/`.flat.expected` encodings added
  ([#7853](https://github.com/IntersectMBO/plutus/pull/7853)). The runner's
  budget-file lookup must be updated on re-sync — and must assert a nonzero
  budget-comparison count, or the rename silently disables every budget check.
- ~57 budgets regenerated for semantics variants D/E
  ([#7756](https://github.com/IntersectMBO/plutus/pull/7756)); PV11 string
  builtins are costed by byte length under D/E.
- `shiftByteString`/`rotateByteString` by more than `maxBound::Int64` now fail
  ([#7754](https://github.com/IntersectMBO/plutus/pull/7754)).
- Value constants require ascending keys
  ([#7816](https://github.com/IntersectMBO/plutus/pull/7816)); BLS tests
  refactored ([#7837](https://github.com/IntersectMBO/plutus/pull/7837)); new
  duplicate-currency-ID case ([#7850](https://github.com/IntersectMBO/plutus/pull/7850)).

When changing either profile, update its pinned commit, date, expected file
counts, and this drift list in the same change.
