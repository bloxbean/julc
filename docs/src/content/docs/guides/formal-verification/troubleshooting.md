---
title: "Troubleshooting"
description: "Troubleshoot JuLC formal-verification workspaces, toolchains, solver runs, and results"
---

:::caution[Experimental verification feature]
Formal verification remains experimental. Preserve fail-closed results and
their logs; do not reinterpret a timeout, unknown outcome, or incomplete model
as successful verification.
:::

## Output directory is not empty

`julc verify` generates a managed, hash-bound workspace and refuses to
overwrite existing files implicitly.

Choose the operation that matches your intent:

```bash
# Source or property changed: rebuild and regenerate generator-owned files.
julc verify . --validator MyValidator --backend local --force

# Reproduce an existing current workspace without rebuilding it.
julc verify run verification/my-validator --backend local
```

Do not edit or delete hash-bound files to bypass the check. Preflight rejects a
workspace whose artifact, property, runner plan, or generated Lean no longer
matches its manifest. Workspaces produced with unreleased experimental DSL
schemas must be regenerated with canonical schema 1.

## Verification appears to be stuck

The first run may spend several minutes acquiring and building pinned Lean
dependencies. The CLI prints each long-running stage and elapsed time:

```text
  Acquiring pinned Lean dependencies ... OK [7.9 s]
  Building pinned Lean dependencies ... OK [1m 55s]
  Checking property non-vacuity ... DONE [12.5 s] - non-vacuous
```

Subsequent runs reuse the workspace dependency build. A solver step can still
take much longer than dependency setup. Supported DSL syntax does not
guarantee solver termination; preserve the recorded timeout or undetermined
result instead of weakening the property merely to obtain success.

## Expected Lean 4.24.0

The local backend requires the pinned Lean/Lake toolchain. Install it with
elan and keep the shims on `PATH`:

```bash
export PATH="$HOME/.elan/bin:$PATH"
elan toolchain install leanprover/lean4:v4.24.0
lean --version
lake --version
```

Run from the project or workspace containing `lean-toolchain`; elan then
selects the pinned version. Alternatively use `--backend docker`.

## Z3 is missing or has the wrong version

JuLC uses the exact pinned Z3 version. When a compatible executable is not
available, the local backend downloads the official archive into `.julc/tools`,
validates its checksum, and rechecks its version. Ensure the first acquisition
run has network access and that the workspace is writable.

## Docker backend is unavailable

Check both the client and daemon:

```bash
docker version
```

The backend requires Linux container target `amd64` or `arm64`. The first run
builds from a digest-pinned base and checksum-pinned Lean/Z3 inputs, records the
resulting image ID, and needs network access for dependency acquisition. Image
distribution packages are not individually version-pinned. Proof commands run
without container network access afterward.

## Native `julc verify dsl` still asks for Java

This is expected. The native CLI does not execute project specification
classes inside the native image. `julc verify dsl` launches a bounded child JVM
because the specification is trusted project Java. Install Java 25 and put the
JuLC JAR, generated model, and compiled specification classes on
`--spec-classpath`.

## `REFUTED`

Blaster found a symbolic countermodel to the translated obligation in the
modeled domain. This is not a tool failure, but it does not by itself establish
either a concrete exact-VM execution or a ledger-valid Cardano transaction.
Inspect:

- `verification-result.json` for the property and domain qualification;
- the raw model path printed by the CLI;
- whether `ledgerValidCounterexampleEstablished` is true; and
- whether `concreteVmCounterexampleReproduced` is true.

A counterexample in a recorded Blaster superset is not automatically a
ledger-valid Cardano transaction. Unless the corresponding certificate flags
are true, it neither establishes a counterexample in the narrower pinned
ledger-valid domain nor records a concrete VM reproduction. It always requires
investigation.

## `COULD-NOT-EVALUATE: property-vacuous`

The separate bounded non-vacuity SMT check established that no successful
execution exists under the selected modeled premise/domain and CEK fuel. An
implication over that empty successful-path set would be misleading, so JuLC
does not classify the main theorem as established. If the separate check
cannot decide whether a successful execution exists, the reason is
`non-vacuity-undetermined` instead.

Add or correct an executable intended path and use VM tests to confirm it.
Increase `--fuel` only when evidence shows that this path exhausted the
recorded execution bound.

## `UNDETERMINED` or another `COULD-NOT-EVALUATE`

Exit code 2 is not success. Typical causes include solver timeout, unsupported
symbolic behavior, non-vacuity uncertainty, an incomplete custom property, or
a backend limitation. Keep the result and logs; do not translate it to
`SMT-VALID` in CI.

## Unsupported validator purpose

The typed DSL currently verifies spending, minting, rewarding, and certifying
interfaces. Voting/proposing validator selection fails closed. For an explicit
multi-validator, pass the base Java title and one supported purpose:

```bash
julc verify dsl-init . --validator Protocol --purpose spending \
  --package verification --class ProtocolModel \
  --out verification/ProtocolModel.java
```

## Unsupported schema shape, field, literal, or builtin

The DSL is a closed language. JuLC rejects unknown fields, forged nominal type
IDs, invalid binders, raw-data equality, unknown node kinds, unsupported
purpose operations, malformed literals, and unmodeled builtins before proof
generation when possible. Do not bypass the diagnostic with a raw Lean string;
arbitrary Lean is not part of the stable DSL.

See the [Formal Verification overview](../) for the complete workflow and
result scope, and [API and DSL Reference](../api-reference/) for the admitted
public surface.
