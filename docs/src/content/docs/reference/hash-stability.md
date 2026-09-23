---
title: Script hashes and cost stability
description: Reproducible compilation, the pre17 profile freeze, and on-chain cost regression checks.
---

JuLC remains experimental. This policy does not certify production or mainnet safety.

## Reproduce an artifact

Pin the compiler and bundled/user library versions, all source inputs and compile-time
parameters, compiler target, optimization level, rule switches and source-map options.
Keep the original script bytes, hash and compilation report. The same inputs must generate
the same bytes. Out-of-band compiler-version metadata can differ without changing a script.

The pre17 freeze preserves the existing `plutus-v3-pv11-uplc-1.1.0` target, `pv11-safe`
default and bounded rule set. `pv11-costed` remains opt-in and adds structural list-to-array
promotion; it does not require or consume a numeric compiler cost model. Performance-only
improvements must not silently change existing supported programs under frozen settings.
New byte-changing optimizations require a separately reviewed opt-in configuration.

**Correctness takes priority over hash stability.** An announced fix can change affected
scripts at every level, including `none` and `baseline`, or reject unsafe source. Release
notes must identify the affected shape and migration. `baseline` is not a way to recover
every historical miscompile using a newer compiler: retain the original toolchain/artifact.
An already deployed script never changes merely because JuLC releases a new compiler.

The freeze baseline is merged compiler `539c3f15`; it does not mean pre17 has been published.
See [ADR-052](https://github.com/bloxbean/julc/blob/main/adr/052-pre17-profile-freeze-and-release-regressions.md)
for the complete rule scope and review policy.

## Costs are a separate contract

Two different changes matter:

- **Recompilation changes script bytes:** the hash/address may change, and CPU/memory
  requirements may change even if accepted inputs stay the same.
- **The network changes its cost parameters or execution prices:** unchanged scripts can
  require different budgets or fees without any hash change. Re-evaluate transactions using
  that network's current parameters. Insufficient declared budgets can make validation fail.

A compiler hash freeze does not freeze ledger costs, fees, or transaction limits. Exact
budget comparisons must identify their protocol and cost-model parameter hash. Normal-build
native-constant tests pin hash, size, CPU and memory under `plutus-v3-pv11-costs-v1`; they
do not query a live network. On-chain release gates check the network has that exact model,
compare Java/backend/Haskell budgets, reject invalid variants and confirm valid spends.

If either a golden or the network-model check fails, investigate the cause. Do not silently
refresh numbers or loosen tolerances. Approved changes need before/after evidence and
migration notes; a new ledger model needs separately identified evaluation evidence.

See the [cost profile catalog](/reference/cost-model-profiles/) and
[release notes](/reference/release-notes/) for the existing migrations. Portable DevKit
commands and prerequisites are in the repository's
[E2E README](https://github.com/bloxbean/julc/blob/main/julc-e2e-tests/README.md).
