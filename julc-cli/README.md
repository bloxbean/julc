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

## Documentation

For full documentation, visit: https://github.com/bloxbean/julc
