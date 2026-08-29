# julc-cli - JuLC CLI

Command-line tool for building Cardano smart contracts in Java.

## Installation

### Homebrew (macOS / Linux)

```bash
brew install bloxbean/tap/julc
```

### Direct download

Download from [GitHub Releases](https://github.com/bloxbean/julc/releases).

On macOS, remove the quarantine attribute after download:

```bash
xattr -d com.apple.quarantine ./julc
chmod +x ./julc
```

## Usage

```bash
# Show available commands
julc --help

# Create a new project
julc new <project-name>

# Build validators (compile Java to UPLC)
julc build

# Explicitly pin the compiler profile (also the current default)
julc build --target plutus-v3-pv11-uplc-1.1.0

# Compile raw deployable artifacts when no CIP-57 blueprint is wanted
julc build --no-blueprint

# Generate a pinned Lean/Blaster verification workspace from the blueprint
julc verify init . --validator MyValidator --purpose spending \
  --recursive-depth 4

# Acquire pins, run the proof protocol, and write verification-result.json
julc verify run verification/my-validator

# Avoid installing host Lean/Z3 (optional Docker backend)
julc verify run verification/my-validator --backend docker

# Show version
julc --version
```

`julc verify init` validates the exact blueprint artifact and script hash,
checks builtin coverage, generates strict CIP-57 `IsData` definitions, and
creates a pinned Lean project with reusable security-property predicates. The
generated manifest starts as `COULD-NOT-EVALUATE` until you specialize and
prove the contract property and add a vulnerable negative control.
Generated Lean supports records and variants containing integers, bytes,
booleans, optional values, lists, maps, and nested combinations, including
productive self and mutual recursion. Maps remain ordered association lists
and preserve duplicate keys, matching JuLC's on-chain representation.

`julc verify run` validates the generated artifact, plan, scripts, admissions,
semantics profile, and dependency pins before classifying the result. Its
default `auto` backend uses exact local Lean 4.24.0/Z3 4.15.2 tools when
available and otherwise uses the JuLC-owned Docker image. Docker acquisition
requires network access; the proof container runs with `--network none`.
Generated workspaces initially return exit 2 and `COULD-NOT-EVALUATE` until the
security property is specialized.

`--recursive-depth` bounds generated recursive-domain experiments and is
separate from `--fuel`, which controls CEK execution. Exhausting either bound
is inconclusive, never a successful proof or validator rejection. Unbounded
recursive claims require an explicit Lean induction theorem.

Normal `julc build` derives CIP-57 schemas directly from the compiler's
resolved contract types and fails if it cannot describe the boundary
truthfully. It supports productive recursive records and sealed variants
containing integers, bytes, strings, booleans, lists, maps, optionals, and
nested combinations. The blueprint is validated locally against a pinned
official CIP-57 meta-schema.

Use `julc build --no-blueprint` (alias `--skip-blueprint`) only when you
deliberately need raw `.uplc`, `.compiledCode.hex`, and `.script-hash` outputs
without `plutus.json`. The command removes a stale blueprint so it cannot be
mistaken for the new script. Schema-dependent commands such as
`julc verify init` require a normal blueprint build.

A normal strict build also refreshes those raw files. Compilation and schema
validation finish before any artifacts are published, so a failed strict build
preserves the previous complete build rather than mixing old and new outputs.

Every successful build reports its resolved compiler target. JuLC currently
supports only `plutus-v3-pv11-uplc-1.1.0`; an unknown or future target is an
error and never falls back to PV11. A later protocol version will be introduced
as a new pinned profile after its ledger baseline, compiler lowerings,
optimizations, and conformance tests are reviewed. Adding it will not change
the no-option default automatically.

Optimization rollout is selected independently with `--optimization`. Its
default is `baseline`, which preserves the pre-ADR-032 generated program.
Reviewed target-aware rules can be requested with `pv11-safe`. The
`pv11-costed` level additionally requires an exact `--cost-profile`; this
release pins `cardano-node-11.0.1-plutus-v3-pv11`. Both identifiers are
case-sensitive and fail closed.

## Documentation

For full documentation, visit: https://github.com/bloxbean/julc
