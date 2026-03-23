# JuLC Playground

Web-based editor for writing, compiling, and testing JRL (JuLC Rule Language) contracts.

## Installation

### Homebrew (macOS / Linux)

```bash
brew install bloxbean/tap/julc-playground
```

### Direct download

Download from [GitHub Releases](https://github.com/bloxbean/julc/releases).

On macOS, remove the quarantine attribute after download:

```bash
xattr -d com.apple.quarantine ./julc-playground
chmod +x ./julc-playground
```

## Quick Start

```bash
julc-playground
```

Then open http://localhost:8085 in your browser.

## Configuration (optional)

| Environment Variable        | Default | Description              |
|-----------------------------|---------|--------------------------|
| JRL_PLAYGROUND_PORT         | 8085    | HTTP server port         |
| JRL_MAX_COMPILE_THREADS     | 4       | Max concurrent compiles  |
| JRL_COMPILE_TIMEOUT_SECONDS | 30      | Per-compile timeout      |

## Documentation

For full documentation, visit: https://github.com/bloxbean/julc
