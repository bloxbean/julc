# ADR-072: The node-diversity UPLC scenarios as a release gate and an on-demand benchmark

**Status:** Proposed (r2: review round on #321)
**Date:** 2026-11-10 (r2: 2026-11-11)
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
  differs. That holds for the cache too: it keeps only the downloaded file, whose digest is checked on every use, and
  each run unpacks it afresh, so nothing extracted is reused. The expectations and the reference reports come from one
  pinned revision of the node-diversity repository.
- **I2. Untrusted inputs.** A download is refused on a non-200 status before its body is read, and stopped once it
  passes a per-pin size limit (64 MiB for the tarballs, 16 MiB for the JSON files). Only regular files and directories
  are extracted; links, devices, entries whose path would leave the target directory and archives that extract to more
  than 1 GiB are refused.
- **I3. Scored as the site scores.** The conformance score uses the site's rule (`build.py`): Plutus and protocol
  versions match, the evaluation matches up to the pretty-printer's whitespace, and an expected budget matches exactly.
- **I4. Ledger-like evaluation, and the Flat decoder actually tested.** Programs are checked against what the protocol
  and Plutus versions allow (`ProgramValidator`), Flat programs are decoded with the target's decoding limits, and
  evaluation uses the corpus's cost model (conformance) or the VM's default cost model for the target (turbo), with no
  tracing.
  - *The reported outcome* follows the reference report: a vector's Flat program first, and its textual program if the
    Flat one does not decode or does not evaluate successfully. That fallback hides a Flat decoder that rejects valid
    programs: in review, a decoder made to reject every `delay` still scored 1064/1064, and one that rejects every
    term 1063/1064.
  - *JuLC's own check, which gates too:* when a vector has `.flat.expected` (876 of the 888 Flat programs: 628 results
    as Flat, 248 error labels), its Flat program alone must give exactly that: the same result up to bound-variable
    names, or the same kind of failure. One exception is built in: `.flat.expected` is computed without the scenario's
    10B / 14M budget, so a Flat program that runs out of that budget passes when the scenario itself expects
    "out of budget" (the six `dropList` vectors with huge counts). One vector is allowed by name with its reason
    (`constr-07`: its `.flat.expected` repeats its text's outcome, a negative tag that does not parse, while its Flat
    program holds the valid Word64 tag 2^64 - 5).
  - *No crash counts as a failure:* the evaluation API reports every exception as a failure, so an expected
    "evaluation failure" would be met by a `NullPointerException`. A failed evaluation is re-run step by step, and
    anything thrown other than a machine or builtin error (`CekEvaluationException`) fails the gate.
- **I5. Turbo never gates.** Timings are machine-dependent; the turbo task fails only on decode or evaluation failures
  and on budgets that differ from cardano-node's. A script that does not decode counts as failed in the score.
- **I6. Default cost models are reported, not gated.** The conformance budgets use the cost models the corpus ships.
  Each successful vector is also evaluated with the VM's default cost model of its target, and the summary lists any
  vector priced differently. This does not fail the gate: the corpus's protocol 11 cost model can be newer than the
  VM's protocol 11 defaults, whose parameters are not final, so a difference is a finding to review, not necessarily
  a VM error. (At r2, 31 PV11 V3 vectors of the Value builtins `insertCoin`, `scaleValue`, `unionValue`,
  `unValueData` and `valueData` differ.) V3 defaults are also covered by `PlutusConformanceTest`, and V1/V2 defaults
  by the turbo budgets, which must equal cardano-node's.

## Decision

1. **Harness** in `julc-vm-java`'s test sources, package `org.julclang.vm.java.ndc`, using JuLC's APIs directly
   (`JavaVmProvider`, `PreparedProgram`, `UplcFlatDecoder`, `ProgramValidator`, `ProtocolFeatureRegistry`). Test-scope
   dependencies only: Jackson (JSON), commons-compress (tar) and zstd-jni (zstd).
2. **Corpora downloaded at run time** (`NdcCorpus`): the two tarballs the scenario pages link to, and, from the pinned
   repository revision, `expectations.json` and cardano-node's and Amaru's turbo reports. Each pin has an ordered list
   of URLs: the tarballs' upstream location is an unversioned `r2.dev` URL, so each also has a mirror, an asset of the
   GitHub release `ndc-corpora-v1` of this repository, tried when upstream is unreachable or serves other bytes.
   Downloads are cached as `<sha256>.download` under `julc-vm-java/build/ndc/cache` (`-PndcCacheDir` moves it; CI keeps
   it with `actions/cache`, keyed by the file that holds the pins). The corpora carry no licence of their own, so they
   are not vendored in the source tree.
3. **Gradle tasks** `:julc-vm-java:ndcUplcConformance` and `:julc-vm-java:ndcUplcTurbo` (JUnit test tasks filtered to
   the scenario classes; `test` excludes them). Reports go to `julc-vm-java/build/reports/ndc/`:
   `julc-<version>-<yyyymmdd>-<scenario>.json` in the site's report format, and `<scenario>-summary.txt`.
4. **Conformance gate.** The task fails on any unmet expectation, except entries of an explicit allowlist
   (`UplcConformanceNdcScenario.ALLOWED_MISMATCHES`, empty), each of which must state its reason and issue, and fails if
   an allowlisted entry starts passing. It also fails on JuLC's own checks (I4), with their own allowlist
   (`ALLOWED_PROBLEMS`, one entry). `release.yml`'s `publish` job and `native-image-release.yml`'s first job (and so the
   GitHub release and the Homebrew formulas) `need` a job that calls `ndc-uplc.yml`.
   `ndc-uplc.yml` also runs it on pull requests and pushes that touch `julc-core`, `julc-vm`, `julc-vm-java` or
   `julc-bls`.
5. **Turbo on demand.** `ndc-uplc.yml`'s `workflow_dispatch` with `turbo` checked runs the turbo task and uploads its
   report. Measurement follows the scenario and Yano: decode and prepare before timing; one unmeasured pass over the
   corpus; per script 5 unmeasured and 50 measured evaluations, mean of the best 30; time is `System.nanoTime` around
   evaluation; memory is the thread's allocated bytes; protocol version from the epoch folder (≥644: 11, ≥537: 10,
   ≥507: 9, else 8). The budget is unlimited, which departs from the scenario page's "10B cpu and 10M mem": five
   scripts consume more than 10M memory units, and the cardano-node and Amaru reports carry their whole budgets (Amaru
   evaluates with `ExBudget::max`), so a 10M limit would make JuLC's budgets differ from the reference.
6. **Corpus layout.** Vectors live under `protocol-version-<n>/v1|v2|v3/`. The scenario also allows an `all`
   Plutus-version folder; this corpus has none, so the harness refuses one with a clear error, and how to label and run
   such vectors is decided when a corpus that has them is pinned.

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

- A download outage blocks a release. Mitigations: the CI cache (a cache hit needs no network for the tarballs), and
  the GitHub release mirror. The mirror is a one-time manual upload by a maintainer (commands in #321); until it
  exists, the second URL simply answers 404.
- An upstream file replaced at the same URL fails the digest check: intended (I1). The mirror keeps the pinned bytes,
  so releases continue; a corpus upgrade changes the pins in `NdcCorpus`, uploads new mirror assets under a new
  release tag, and is reviewed as such.
- A VM change that alters a budget fails the gate. That is the point; a deliberate cost-model change updates the pins
  to a corpus that expects it, or, exceptionally, adds a reviewed allowlist entry.

## Implementation milestones

One milestone: harness, unit tests, tasks, workflows, a run of both scenarios on `main`.

## Verification

- Unit tests (in `test`, no network): vector labels, evaluation order and error labels, scoring rule, epoch and
  language mapping, CBOR unwrapping, thresholds, pin checks, archive safety; and (r2) the Flat check, crash
  detection, URL fallback, size limit and cache re-verification against a local HTTP server.
- Both tasks run on `main` at the time of the PR; results recorded in the PR.
- Mutations of the Flat decoder (r2), against the strengthened gate: rejecting every `delay` still scores 1064/1064 on
  the site's rule but fails 18 vectors of JuLC's Flat check; rejecting every term scores 1063/1064 and fails 864
  vectors of the Flat check. Both fail the task. Earlier review mutations of the machine (an off-by-one in
  `addInteger`, a double-charged `delay` step) already failed it on the site's rule.

## Open questions

1. Should the Truffle VM run the same scenarios?
2. Should the default-cost-model differences (I6) fail the gate once protocol 11's parameters are final?

Resolved in r2: `native-image-release.yml` waits for the gate too (it was open question 1).
