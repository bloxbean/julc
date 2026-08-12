# ADR-010: Milestone C.4 — Managed Verification Runner

- **Status:** Implemented; Docker runtime validation deferred by review decision
- **Date:** 2026-08-12
- **Feature branch:** `feat/verification-c4-managed-runner`
- **Related:**
  [ADR-004 — Reusable Verification Integration](004-milestone-c-reusable-verification-integration.md),
  [ADR-009 — Verification Product Roadmap](009-verification-product-roadmap.md)

## Context

`julc verify init` generates a pinned Lean/Blaster workspace, but a developer
must still install or locate tools, run `lake update`, invoke the generated
script, and interpret exit codes and terminal text. The Milestone A/B evidence
suite has a separate two-script acquisition/offline workflow. GitHub Actions
recently demonstrated why this is unsafe to leave implicit: a warm local Lake
cache concealed missing `PlutusCore` and `CardanoLedgerApi` build artifacts
that failed on a fresh runner.

C.4 makes execution a JuLC-owned workflow while preserving the trust boundary:
the runner establishes and reports verification prerequisites and results; it
does not turn compilation into a proof or specialize a contract property.

## Decision

Add:

```bash
julc verify run <workspace> [--backend auto|local|docker]
```

The command loads a versioned `verification-runner.json`, validates the
workspace's `verification-manifest.json`, prepares pinned tools and Lake
dependencies in an online acquisition phase, and then executes a distinct
proof phase without dependency-update commands.

The runner produces:

```text
<workspace>/verification-result.json
<workspace>/verification-results/acquire.log
<workspace>/verification-results/verify.log
```

The JSON result and logs are written atomically. A failed run replaces an older
result with an explicit current non-success result; it must not leave a stale
successful result appearing current.

## Module boundary

C.4 belongs in `julc-cli` under `cmd.verify` because it is orchestration and
artifact verification, not source compilation or UPLC generation. It may read
compiler-produced artifacts but must not modify `julc-compiler`, optimizer,
PIR, or UPLC code paths.

Runner data structures must be independent of Picocli so they can be unit
tested and reused by local and Docker execution backends. Picocli owns only
argument parsing, terminal output, and exit-code mapping.

## Execution backends

### Backend selection

- `auto` uses an exact local Lean/Z3 toolchain when available and otherwise
  uses Docker when the daemon is available;
- `local` requires or provisions the local tools described below; and
- `docker` requires Docker but does not require host Lean, Lake, Z3, Git, or
  ripgrep.

The selected backend and its identity are recorded in the result. Backend
selection must not alter verification semantics, pins, plan steps, or result
classification.

### JuLC-owned Docker image

C.4 will not depend on an unversioned community Lean image. The CLI owns a
Dockerfile that starts from a digest-pinned, multi-architecture JDK 25 base and
installs only pinned verification prerequisites. Lean 4.24.0 and Z3 4.15.2 are
downloaded from their official immutable release assets and checked against
hard-coded SHA-256 digests. The image also contains Git, ripgrep, `xxd`, and
archive/TLS utilities required by the evidence scripts.

The runner builds/caches an image under a versioned JuLC tag and records the
resolved image content ID. The workspace is bind-mounted at `/workspace` with
the current user's UID/GID where supported so generated files do not become
root-owned. No Docker socket, host home directory, SSH agent, or credential
directory is mounted.

Acquisition runs with normal container networking. Verification runs in a new
container with `--network none`, the same image ID, the same workspace mount,
and the prepared `.lake` directory. Thus the Docker backend provides a real
network boundary for the proof phase, while remaining an execution boundary—not
a claim that arbitrary Lean macros are otherwise sandboxed.

The JDK base, Lean archive, and Z3 archive are immutable digest/checksum inputs.
Ubuntu utility packages (`git`, `ripgrep`, `jq`, archive and TLS tools) come
from the base distribution's signed repositories during the image build; their
resolved bytes are captured by the final image content ID but are not claimed
to make separately rebuilt images byte-identical.

The first Docker backend supports generated product workspaces. The committed
Milestone A/B repository evidence, whose acquisition script deliberately
rebuilds Java fixtures outside its workspace directory, continues through the
local backend until it is migrated to a self-contained typed plan.

## Runner plan

`verification-runner.json` schema version 1 contains:

- a plan kind (`generated-workspace` or `evidence-suite`);
- the manifest path;
- an optional post-run result-manifest path for the legacy evidence adapter;
- acquisition steps;
- verification steps;
- the expected classified outcome and reason for each verification step;
- required output markers used only as fail-closed protocol checks; and
- the support/build targets required on a cold Lake cache.

Acquisition steps may declare a bounded retry count from one to three.
Verification steps are never retried.

Commands are JSON arrays of tokens. They are passed directly to
`ProcessBuilder`; no shell parsing, interpolation, `eval`, or joined command
string is used. Relative executable paths must resolve inside the workspace,
must be regular files, and must not traverse through symlinks. Bare executable
names are restricted to the tools required by the typed plan.

The plan is executable project input, like Gradle, Lake, and Lean source. The
CLI must document that developers should run only workspaces they trust. Docker
removes proof-phase networking and avoids host tool installation; it does not
make malicious Lean macros or project scripts safe to run.

## Manifest and artifact preflight

Before running any workspace command, the runner validates:

1. supported plan and verification-manifest schema versions;
2. normalized paths remain within the workspace;
3. the declared compiled-code artifact exists and contains valid hexadecimal;
4. decoded artifact bytes match `compiledCodeSha256`;
5. required identity/profile fields are present;
6. Lean code (excluding comments, literals, qualified option names, and pinned
   `.lake` dependencies) contains no project admission tokens (`sorry`,
   `admit`, `axiom`, `unsafe`, or `partial`) under the current admission policy;
7. tool and dependency revisions are full hexadecimal commit IDs; and
8. builtin coverage was already accepted by generation and has not been
   broadened in the runner plan.

The runner must never accept a plan-supplied artifact hash in place of the
verification manifest. The manifest is the identity boundary.

## Tool and dependency acquisition

### Lean and Lake for the local backend

The local backend searches `PATH` and the standard `elan` bin directory. The
workspace's committed `lean-toolchain` selects Lean 4.24.0. If `elan` is
installed, its normal shim may acquire that exact toolchain during the online
phase. If neither the exact toolchain nor `elan` is available, the result is
`COULD-NOT-EVALUATE`; C.4 does not install `elan` itself.

### Z3 for the local backend

The runner accepts a system Z3 only when its version is exactly 4.15.2.
Otherwise it downloads the official platform archive into a workspace-local
tool cache, verifies a hard-coded SHA-256 checksum, safely extracts it without
zip traversal, and rechecks the binary version. Initially supported platforms
match the working Milestone A script:

- macOS arm64;
- macOS x86_64; and
- Linux x86_64; and
- Linux arm64.

Unsupported platforms fail closed. Downloads occur only during acquisition.

### Lake dependencies

Acquisition runs `lake update` with bounded retries, validates every package
revision against `verification-manifest.json`, and builds complete pinned
libraries required by direct imports:

```text
@PlutusCore/PlutusCore
@CardanoLedgerApi/CardanoLedgerApi
@Blaster/Blaster
```

It also builds the plan's JuLC support target. Package-level targets are
required because building one imported leaf is insufficient on a clean runner.

## Offline proof phase

The verification phase never runs `lake update` and uses only the acquired
manifest/package directories. `lake build` itself does not update dependencies.
The local backend adds proxy and Git URL guards so accidental dependency
download attempts fail. The Docker backend additionally runs the entire phase
with `--network none`. These are dependency-download controls, not proof that
malicious project code is otherwise safe.

The environment includes the verified workspace-local Z3 before any system Z3.
The phase captures stdout and stderr together in order and imposes a
configurable positive timeout. Timeout, process-start failure, unexpected exit
code, missing result marker, or revision drift is `COULD-NOT-EVALUATE`.

## Result model

The versioned result contains at least:

- result schema version and generator;
- overall classification and stable reason code;
- compiled-code and Cardano script hashes;
- validator, purpose, protocol, semantics variant, fuel, and recursive depth;
- Lean/Z3 versions and dependency commits;
- selected backend and local tool paths or Docker image content ID;
- verification-manifest, runner-plan, generated-Lean, and log hashes;
- per-phase status and exit code;
- per-property classifications copied only after their proof/control steps
  satisfy the plan protocol; and
- explicit flags describing dependency-download and ledger-validity coverage.

Absolute local tool paths and wall-clock timestamps are excluded from the canonical
result so equivalent local/CI runs are comparable. Durations may be terminal
diagnostics but are not certificate inputs.

Classifications remain:

- `SMT-VALID`;
- `KERNEL-PROVED`;
- `REFUTED`;
- `UNDETERMINED`; and
- `COULD-NOT-EVALUATE`.

The runner does not infer `SMT-VALID` from exit code zero alone. The plan must
name the expected property/control protocol, and all required steps and output
markers must agree. C.4's generated unspecialized workspace truthfully remains
`COULD-NOT-EVALUATE (property-not-specialized)` after successful compilation.

## CLI exit codes

- `0`: every requested property is `SMT-VALID` or `KERNEL-PROVED` and all
  required controls passed;
- `1`: invalid CLI invocation or an internal I/O/configuration defect prevented
  creation of a trustworthy result;
- `2`: `UNDETERMINED` or `COULD-NOT-EVALUATE`; and
- `3`: at least one property is `REFUTED`.

An expected negative control is a control success inside a positive result; it
is not reported as the contract property being refuted.

## Compatibility and migration

`julc verify init` will emit the runner plan and update generated documentation
to recommend `julc verify run`. Its existing `scripts/verify.sh` remains a
transparent expert/audit surface.

The committed Milestone A/B workspace receives a tracked preflight manifest
that binds its runner plan and artifact lock. Its plan separately names the
post-run `generated/run-manifest.json`, whose properties are consumed only
after the reviewed offline script passes. This is a bounded legacy adapter so
the same CLI/result pipeline exercises established positive and negative
evidence. Newly generated product workspaces use the standard typed Lake/Lean
plan rather than arbitrary custom scripts.

## Implementation validation status

The managed local backend has passed the generated C.3 workspace from a copied
workspace with no `.lake` or `.julc` cache, the complete C.2 and C.3 evidence
drivers, and the Milestone A/B suite starting without its generated run
manifest. The Docker command/isolation behavior is unit-tested. A full Docker
image build was attempted and failed closed because the local Docker VM had 0
bytes free; the full Docker execution gate remains pending after Docker storage
is freed. No signature verification was disabled to bypass that environment
failure.

Regeneration with `--force` may replace the generator-owned runner plan but
must continue preserving `SecurityProperty.lean` and unknown user files.

## Test and review plan

### Unit tests

- strict parsing and rejection of unknown schema versions;
- path traversal, symlink, invalid command, and malformed hash rejection;
- artifact-byte hash validation;
- deterministic canonical result serialization;
- all five result classifications and CLI exit mappings;
- unexpected exit, missing marker, timeout, and partial-result handling;
- exact dependency revision checking;
- admission scanning without requiring ripgrep; and
- safe Z3 platform selection, checksum rejection, and zip traversal rejection.
- backend auto-selection, Docker command construction, mount containment, and
  image-ID recording;

### Integration tests

- CLI help exposes `verify run`;
- a generated unspecialized workspace compiles and returns exit 2 with a JSON
  `COULD-NOT-EVALUATE` result;
- the Milestone A/B suite runs through the CLI and produces an established
  structured result;
- a cold Lake-cache run builds all direct dependency libraries;
- the proof phase succeeds with dependency-download guards enabled; and
- a generated workspace runs with Docker `--network none` and no host Lean/Z3;
- artifact/revision tampering fails before a proof claim.

### Regression gates

- existing `julc-cli`, compiler, blueprint, C.2, and C.3 tests;
- Milestone A/B offline evidence;
- `git diff --check`; and
- review of logs/results for absolute paths, stale success, admissions, and
  accidental claim promotion.

## Exit criteria

C.4 is complete when a developer can run one JuLC command on a trusted
generated workspace and receive a stable, artifact-bound structured result;
the same runner reproduces Milestone A/B evidence; cold-cache and offline-phase
behavior pass locally and in CI; every incomplete or unsupported condition is
non-success; documentation accurately describes prerequisites and trust; and
the implementation has passed manual review before commit.

## Review disposition

The local backend, clean-cache generated workspace, C.2/C.3 evidence, managed
Milestone A/B suite, structured failure paths, and affected Java regression
suites passed. The Docker backend implementation and isolation command are
unit-tested. Its full image/runtime exercise is deliberately carried as a
post-C.5 follow-up because the available Docker VM had no free space; the
failed attempt remained fail-closed and no image/signature checks were
disabled. This deferred operational gate does not affect C.5's module boundary
or Java property-IR implementation.

## Non-goals

- Java security annotations or typed property IR (C.5);
- automatic property specialization;
- installation of host `elan` or publication of a prebuilt JuLC image;
- an OS sandbox for arbitrary workspace code;
- proof reconstruction for Blaster/Z3;
- complete PV11 builtin coverage; and
- compiler semantic-preservation claims.
