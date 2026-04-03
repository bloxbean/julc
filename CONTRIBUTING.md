# Contributing to JuLC

Thank you for your interest in contributing to JuLC! This guide covers everything you need to get started.

JuLC is licensed under the [MIT License](LICENSE). By contributing, you agree that your contributions will be licensed under the same terms.

> **Note**: JuLC is an experimental/research project. Most of the code is AI-generated with human-assisted design, testing, and verification. Please keep this in mind when contributing.

If you find a bug or have a feature request, please [open a GitHub issue](https://github.com/bloxbean/julc/issues).

## Prerequisites

- **Java 25+** (Temurin recommended)
- **Gradle 9+**
- **Git**
- **Optional**: [Yaci Devkit](https://github.com/bloxbean/yaci-devkit) for end-to-end tests (must be started externally; admin API runs on port 10000)

## Getting Started

1. Fork and clone the repository:

   ```bash
   git clone https://github.com/<your-username>/julc.git
   cd julc
   ```

2. Build the project (skip GPG signing for dev builds):

   ```bash
   ./gradlew build -PskipSigning=true
   ```

3. Run all tests:

   ```bash
   ./gradlew test
   ```

4. **IDE setup**: Import as a Gradle project and ensure the Java 25 toolchain is configured.

## Project Structure

### Core

| Module | Description |
|--------|-------------|
| `julc-core` | UPLC AST, CBOR/FLAT serialization |
| `julc-vm` | VM SPI interface |
| `julc-vm-scalus` | Scalus-based VM backend |
| `julc-ledger-api` | ScriptContext, TxInfo, and ledger types |

### Compiler

| Module | Description |
|--------|-------------|
| `julc-compiler` | Java source to UPLC compiler |
| `julc-stdlib` | On-chain standard library (11 libraries) |

### Tooling

| Module | Description |
|--------|-------------|
| `julc-testkit` | Testing utilities for validators |
| `julc-gradle-plugin` | Gradle build plugin |
| `julc-annotation-processor` | Compile-time annotation processor |

### Integration

| Module | Description |
|--------|-------------|
| `julc-cardano-client-lib` | cardano-client-lib integration |

### Analysis

| Module | Description |
|--------|-------------|
| `julc-analysis` | Static analysis library |
| `julc-analyzer-cli` | CLI for static analysis |
| `julc-decompiler` | UPLC decompiler |

### Testing

| Module | Description |
|--------|-------------|
| `julc-examples` | Example validators |
| `julc-e2e-tests` | End-to-end tests (require Yaci Devkit) |
| `julc-plugin-test` | Gradle plugin integration tests |

All modules use the `com.bloxbean.cardano.julc.*` package convention.

## Development Workflow

### Branching

- `feat/<name>` for features
- `fix/<name>` for bug fixes
- Base all PRs on `main`

### Commit Messages

Use imperative mood with a descriptive subject. Examples from the project:

- "Refactor stdlib to type-friendly methods and delete legacy libraries"
- "Support for arbitrary function ordering"
- "Fix chained .hash() double-UnBData bug"

### Pull Request Process

1. Create a branch from `main`
2. Make your changes and add tests
3. Ensure the build passes locally:
   ```bash
   ./gradlew build -PskipSigning=true
   ```
4. Push your branch and open a PR against `main`
5. CI runs automatically (JDK 25 Temurin, `./gradlew build`). All tests must pass.
6. Test results are uploaded as artifacts with 14-day retention.

## Testing

### Framework

JUnit 5 (Jupiter) with `junit-bom:5.11.4`. Test files follow the `*Test.java` naming convention.

### Test Categories

- **Unit tests**: Per-component, in each module's `src/test/java`
- **Stdlib tests**: `julc-stdlib/src/test/java/.../lib/*Test.java` — 259 tests across 11 library files
- **Compile-eval tests**: `julc-compiler/src/test/java/.../StdlibCompileEvalTest.java` — compiles and evaluates methods through the full pipeline
- **E2E tests**: `julc-e2e-tests/` — require Yaci Devkit running externally

### Testing Utilities

- `JulcEval.forClass(Cls)` / `JulcEval.forSource(src)` — compile and evaluate individual methods
- `ValidatorTest.compile()` — compile full validators
- `BudgetAssertions` — assert on CPU/memory execution budgets

### Guidelines

- Test edge cases, boundary conditions, and error scenarios
- Use test fixtures and helper functions to avoid duplication (e.g., `StdlibTestHelpers.java`)
- Keep tests up to date as the implementation evolves
- Use unit tests for individual components and integration tests for overall system behavior

## Common Contribution Types

### Adding a Stdlib Method

1. Add a `public static` method to the appropriate library class in `julc-stdlib/src/main/java/.../lib/`
2. Add a unit test in the corresponding `*LibTest.java`
3. If the method needs PIR (higher-order functions, recursion), register a builder in `StdlibRegistry.java`
4. Update docs: `docs/stdlib-guide.md`, `docs/api-reference.md`, `docs/getting-started.md`

### Adding a Ledger Type

1. Add a record or sealed interface in `julc-ledger-api`
2. Register in `LedgerTypeRegistry.java`
3. Add compile-eval tests in `julc-compiler`
4. Update `docs/api-reference.md` and `docs/getting-started.md`

### Fixing a Compiler Bug

1. Write a failing test that reproduces the bug
2. Fix the issue in the relevant compiler phase (`PirGenerator`, `UplcGenerator`, `LoopDesugarer`, etc.)
3. Add a regression test
4. Update `docs/troubleshooting.md` if the error message or behavior changes
5. Reference `docs/compiler-developer-guide.md` for architecture understanding

### Adding an On-Chain Library

1. Follow `docs/library-developer-guide.md` — two approaches: Java source or PIR API
2. Annotate with `@OnchainLibrary`
3. Sources are automatically bundled in the JAR under `META-INF/plutus-sources/`
4. Add comprehensive tests

## Code Style

- No enforced formatter — follow existing patterns in the file you're modifying
- UTF-8 encoding for all source files
- Package convention: `com.bloxbean.cardano.julc.*`
- All on-chain methods must be `static`
- Prefer immutable variables and accumulator patterns for loops
- Use meaningful names and minimal complexity; reuse existing utilities

## Documentation

- **User-facing changes**: Update relevant files in `docs/` (api-reference, getting-started, stdlib-guide, etc.)
- **Architectural decisions**: Add an ADR in the `adr/` directory
- **Cross-check consistency**: When adding methods or types, ensure they appear in all relevant docs (getting-started listing, stdlib-guide table, api-reference table)


## Release Process (for maintainers)

- **Snapshots**: Manually triggered via the `snapshot.yml` workflow. Version includes the Git commit hash (e.g., `0.1.0-055d17f-SNAPSHOT`).
- **Releases**: Pushing a `v*` tag triggers the `release.yml` workflow, which publishes to Maven Central via Sonatype.
