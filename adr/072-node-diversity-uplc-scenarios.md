# ADR-072: The node-diversity UPLC scenarios as a release gate and an on-demand benchmark

**Status:** Proposed
**Date:** 2026-11-10
**Risk class:** low. Test harness and CI only: no compiler, VM or published artifact changes. It adds a release gate,
which can stop a release.
**Relates to:** [#259](https://github.com/bloxbean/julc/issues/259) (VM allocation on small scripts), Yano's
node-diversity reports (Yano's ADR-060), which already run both scenarios on JuLC's Java VM as a downstream consumer.

## Context

The Cardano node-diversity site ([pragma-org/node-diversity-conformance](https://github.com/pragma-org/node-diversity-conformance))
scores node implementations on shared scenarios. Two of them exercise a Plutus VM:

- **uplc-conformance** (functional): 1,064 vectors (the Plutus conformance tests plus regression vectors) at protocol
  versions 10 and 11, Plutus V1 to V3, each with an expected result and, for most, an expected budget. The site scores
  a report against `scenarios/uplc-conformance/expectations.json`.
- **uplc-turbo** (performance): 3,997 Flat-encoded mainnet scripts, timed. The site passes a script when its time and
  allocation stay within twice its budget (one CPU unit taken as a picosecond, one memory unit as a byte, with small
  absolute tolerances), and when its consumed budget agrees with the majority of nodes.

JuLC already runs the upstream Plutus conformance tests in `PlutusConformanceTest` (1,003 cases, V3 at PV10 and PV11,
vendored). It does not run the node-diversity corpus, which adds V1 and V2, the per-(protocol, language) cost models
the vectors ship, Flat-first evaluation and the regression vectors, and it has no performance corpus of real scripts.
Yano runs both on JuLC's VM, so a JuLC regression would first be seen downstream.

## Goals and non-goals

Goals:

- A release is published only if every uplc-conformance vector gives the result and budget the site expects.
- The turbo corpus can be run on demand, locally and in CI, producing the site's report and a readable summary that
  names the scripts over the site's thresholds.
- `./gradlew build` stays free of network downloads and still covers the harness code.

Non-goals:

- Fixing VM performance (tracked separately).
- Publishing JuLC reports to the site (Yano publishes node reports; JuLC is a VM, not a node).
- Running the Truffle VM on the scenarios (possible later with the same harness).

## Invariants

- **I1. Pinned inputs.** Every downloaded input is pinned by SHA-256 and refused, before it is unpacked, if its digest
  differs. The expectations and the reference reports come from one pinned revision of the node-diversity repository.
- **I2. Untrusted archives.** Only regular files and directories are extracted; links, devices and entries whose path
  would leave the target directory are refused.
- **I3. Scored as the site scores.** The conformance score uses the site's rule (`build.py`): Plutus and protocol
  versions match, the evaluation matches up to the pretty-printer's whitespace, and an expected budget matches exactly.
- **I4. Ledger-like evaluation.** Programs are checked against what the protocol and Plutus versions allow
  (`ProgramValidator`), Flat programs are decoded with the target's decoding limits, and evaluation uses the corpus's
  cost model (conformance) or the VM's default cost model for the target (turbo), with no tracing.
- **I5. Turbo never gates.** Timings are machine-dependent; the turbo task fails only on decode or evaluation failures
  and on budgets that differ from cardano-node's.

## Decision

1. **Harness** in `julc-vm-java`'s test sources, package `org.julclang.vm.java.ndc`, using JuLC's APIs directly
   (`JavaVmProvider`, `PreparedProgram`, `UplcFlatDecoder`, `ProgramValidator`, `ProtocolFeatureRegistry`). Test-scope
   dependencies only: Jackson (JSON), commons-compress (tar) and zstd-jni (zstd).
2. **Corpora downloaded at run time** (`NdcCorpus`): the two tarballs the scenario pages link to, and, from the pinned
   repository revision, `expectations.json` and cardano-node's and Amaru's turbo reports. They are cached under
   `julc-vm-java/build/ndc/cache/<sha256>` (`-PndcCacheDir` moves it). The corpora carry no licence of their own, so
   they are not vendored.
3. **Gradle tasks** `:julc-vm-java:ndcUplcConformance` and `:julc-vm-java:ndcUplcTurbo` (JUnit test tasks filtered to
   the scenario classes; `test` excludes them). Reports go to `julc-vm-java/build/reports/ndc/`:
   `julc-<version>-<yyyymmdd>-<scenario>.json` in the site's report format, and `<scenario>-summary.txt`.
4. **Conformance gate.** The task fails on any unmet expectation, except entries of an explicit allowlist
   (`UplcConformanceNdcScenario.ALLOWED_MISMATCHES`, empty), each of which must state its reason and issue, and fails if
   an allowlisted entry starts passing. `release.yml`'s `publish` job `needs` a job that calls `ndc-uplc.yml`.
   `ndc-uplc.yml` also runs it on pull requests and pushes that touch `julc-core`, `julc-vm`, `julc-vm-java` or
   `julc-bls`.
5. **Turbo on demand.** `ndc-uplc.yml`'s `workflow_dispatch` with `turbo` checked runs the turbo task and uploads its
   report. Measurement follows the scenario and Yano: decode and prepare before timing; one unmeasured pass over the
   corpus; per script 5 unmeasured and 50 measured evaluations, mean of the best 30; time is `System.nanoTime` around
   evaluation; memory is the thread's allocated bytes; unlimited budget, so the reported budget is the whole consumed
   budget; protocol version from the epoch folder (≥644: 11, ≥537: 10, ≥507: 9, else 8).

## Alternatives

- **Vendor the corpora.** Rejected: no licence is attached to the tarballs, and 4,000 binary scripts would grow the
  repository for an on-demand task. The download is about 6 MB.
- **Run in `./gradlew build`.** Rejected: network access in the default build, and the turbo run takes many minutes.
- **A new Gradle module.** Rejected: the harness tests `julc-vm-java` only, and its existing conformance resources and
  test setup are there.
- **zstd command-line tool.** Rejected: not on every developer machine; the Java decompressor keeps the task portable.
- **Gate releases on turbo thresholds.** Rejected: timings depend on the runner.

## Affected modules

`julc-vm-java` (test sources, build script), `.github/workflows/release.yml`, `.github/workflows/ndc-uplc.yml` (new).

## Compatibility

No change to any published artifact, API, compiled script or VM behaviour.

## Risks

- A download outage blocks a release. Mitigation: re-run, or run the task locally against a cache (`-PndcCacheDir`).
- An upstream file replaced at the same URL fails the digest check: intended (I1). A corpus upgrade changes the pins
  in `NdcCorpus` and is reviewed as such.
- A VM change that alters a budget fails the gate. That is the point; a deliberate cost-model change updates the pins
  to a corpus that expects it, or, exceptionally, adds a reviewed allowlist entry.

## Implementation milestones

One milestone: harness, unit tests, tasks, workflows, a run of both scenarios on `main`.

## Verification

- Unit tests (in `test`, no network): vector labels, evaluation order and error labels, scoring rule, epoch and
  language mapping, CBOR unwrapping, thresholds, pin checks, archive safety.
- Both tasks run on `main` at the time of the PR; results recorded in the PR.

## Open questions

1. Should `native-image-release.yml` (the GitHub release and Homebrew formulas, triggered by the same tag) also wait
   for the gate?
2. Should the Truffle VM run the same scenarios?
