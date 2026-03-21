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

# Show version
julc --version
```

## Documentation

For full documentation, visit: https://github.com/bloxbean/julc
