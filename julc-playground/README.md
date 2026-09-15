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

## Engines

Compilation and evaluation run on the playground server by default. Builds that include the WebAssembly engine
also offer **Browser (WebAssembly)** in the toolbar's Engine selector, which runs the same compiler and VM inside
the browser tab. BLS12-381 builtins are not available in the browser engine. See
[BUILD_FROM_SOURCE.md](BUILD_FROM_SOURCE.md#in-browser-engine-webassembly).

## UPLC evaluator and debugger

The **UPLC** tab (next to **Contract** in the toolbar, or `?mode=uplc`) works with any compiled Plutus script, not
only JuLC contracts:

- Paste CBOR hex, a `plutus.json` blueprint, a text envelope or UPLC text. The tab shows the script hash, language,
  size and builtins, readable UPLC, and a decompiled Java preview.
- Run it against a mock transaction that is prefilled for the chosen purpose (spend, mint, reward, certify, vote,
  propose). Edit only the inputs, outputs, signers, validity or other fields your script checks.
- See whether the script accepts, its CPU and memory against protocol limits, the script fee, traces and the exact
  script context.
- Debug it step by step, with step back, step over, step out, continue, a timeline scrubber, and line, trace and
  builtin breakpoints. The current term is highlighted, with its environment and continuation frames.

Everything runs in the browser with the WebAssembly engine, or on the server engine.

## Configuration (optional)

| Environment Variable        | Default | Description              |
|-----------------------------|---------|--------------------------|
| JRL_PLAYGROUND_PORT         | 8085    | HTTP server port         |
| JRL_MAX_COMPILE_THREADS     | 4       | Max concurrent compiles  |
| JRL_COMPILE_TIMEOUT_SECONDS | 30      | Per-compile timeout      |

## Documentation

For full documentation, visit: https://github.com/bloxbean/julc
