# Draft update for issue #121

## P1 / release-gate update

PR #122 completes ADR-033's implementation and establishes an explicit,
documented Scalus support matrix. No unproven profile is enabled:

- V3/PV10/C and V3/PV11/E remain fail-closed because committed tests reproduce
  upstream semantic divergences;
- V1/V2 remain explicitly unsupported because supplied PV11-only costs are not
  faithfully represented and there is no pinned corpus;
- protocol versions above PV11 remain rejected by the shared registry.

The release-gate wording “#74 has an explicit, documented support matrix and
continues to fail closed for every unproven profile” is therefore satisfied by
the PR's outcome. After PR #122 merges, update the tracker as follows:

```markdown
- [x] #74 — ADR-033 implementation complete; no Scalus profile certified.
  Both V3 targets remain fail-closed on reason-coded upstream blockers, and
  V1/V2 remain unsupported pending faithful supplied-cost handling plus a
  pinned corpus.
```

The Scalus upstream fixes should not become a pre17 blocker unless the release
explicitly requires Scalus ledger parity. #40 remains independent and
non-blocking; ADR-033 removes torn target/model snapshots but does not claim
complete provider lifecycle thread safety.
