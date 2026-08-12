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

# Compile raw deployable artifacts when no CIP-57 blueprint is wanted
julc build --no-blueprint

# Generate a pinned Lean/Blaster verification workspace from the blueprint
julc verify init . --validator MyValidator --purpose spending

# Show version
julc --version
```

`julc verify init` validates the exact blueprint artifact and script hash,
checks builtin coverage, generates strict CIP-57 `IsData` definitions, and
creates a pinned Lean project with reusable security-property predicates. The
generated manifest starts as `COULD-NOT-EVALUATE` until you specialize and
prove the contract property and add a vulnerable negative control.

Normal `julc build` derives CIP-57 schemas directly from the compiler's
resolved contract types and fails if it cannot describe the boundary
truthfully. It supports nonrecursive records and sealed variants containing
integers, bytes, strings, booleans, lists, maps, optionals, and nested
combinations. The blueprint is validated locally against a pinned official
CIP-57 meta-schema.

Use `julc build --no-blueprint` (alias `--skip-blueprint`) only when you
deliberately need raw `.uplc`, `.compiledCode.hex`, and `.script-hash` outputs
without `plutus.json`. The command removes a stale blueprint so it cannot be
mistaken for the new script. Schema-dependent commands such as
`julc verify init` require a normal blueprint build.

A normal strict build also refreshes those raw files. Compilation and schema
validation finish before any artifacts are published, so a failed strict build
preserves the previous complete build rather than mixing old and new outputs.

## Documentation

For full documentation, visit: https://github.com/bloxbean/julc
