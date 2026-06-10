# ADR-025: JuLC Test-Automation Skills

**Date**: 2026-05-21
**Status**: Proposed

---

## Context

JuLC's unit and integration tests cover the compiler, VM, annotation processor,
Gradle plugin internals, and stdlib in detail, but several recent regressions
slipped past the in-repo suite and only surfaced through manual sanity checks
against the external `julc-examples` project:

- The `bundleJulcSources` task wrote into `build/resources/main`, which Gradle's
  implicit-dependency validator rejects when `compileTestJava` runs (ADR-024).
- The annotation processor failed to discover stdlib `@OnchainLibrary` source
  files (`Undefined variable: ValuesLib`) when invoked under Gradle, even
  though direct `JulcCompiler` use worked. This regression is currently being
  diagnosed.
- A validator whose Javadoc mentioned `@OnchainLibrary` was misclassified as a
  library (ADR-023, MpfRegistryValidator).
- Legacy `@Validator` / `@MintingPolicy` removal affected downstream projects
  that depend on JuLC via Maven local.

What the in-repo suite does not exercise:

- End-to-end "publish to mavenLocal → consume from a fresh Gradle project"
  flow, including Gradle daemon caching behavior.
- CLI commands invoked as a user would invoke them (`julc init`, `julc check`,
  `julc build`, `julc test`).
- Library jar produced by project A consumed by project B (cross-jar bundling
  and FQCN resolution through annotation processor classpath).
- Configuration-cache compatibility and Gradle 9 deprecation warnings under
  realistic project layouts.
- The full `julc-examples` build against the local SNAPSHOT.

These gaps are repeatedly the place where regressions land. They are also the
gaps that are tedious to test by hand: each round of manual verification
involves publishing to mavenLocal, stopping Gradle daemons, creating temp
projects, writing throwaway sources, and inspecting jar contents.

## Decision

Introduce a layered set of Claude Code skills under
`.claude/commands/` that automate these manual flows. Skills
compose so that foundation work (build + publish) runs once per test session,
and higher-level scenarios reuse it.

Each skill is a self-contained markdown slash command invoked directly (e.g.
`/julc-publish-local`). These are dev-facing skills for JuLC language
developers, distinct from the user-facing `julc` skill at
`tools/claude-skill/julc/` (which targets end users writing validators).
Skills are not new Gradle tasks or JUnit tests; they exist precisely to cover
the gap between in-repo unit tests and "did I break something downstream?"

## Skill Catalog

### Foundation

1. **`julc-publish-local`**
   - Runs a fast subset of unit tests
     (`:julc-stdlib :julc-compiler :julc-gradle-plugin :julc-annotation-processor`).
   - `./gradlew publishToMavenLocal -PskipSigning`.
   - `./gradlew --stop` and verifies no stale daemon classes (the ADR-024
     gotcha).
   - Echoes the published version (read from `gradle.properties`).
   - Used as a prerequisite step by every downstream skill. Skips if a marker
     file in the session workspace indicates the same SHA was already
     published.

### Smoke (basic happy paths, ~30–60s each)

2. **`julc-smoke-gradle-plugin`**
   - Temp Gradle project applying `id 'com.bloxbean.cardano.julc'`.
   - Minimal `@SpendingValidator` returning `true`.
   - `./gradlew clean build --configuration-cache --warning-mode fail`.
   - Asserts `build/plutus/<Name>.json` exists.

3. **`julc-smoke-gradle-stdlib-usage`**
   - Same shape but the validator uses `ValuesLib`, `OutputLib`, `ContextsLib`.
   - This is the smoke test that would have caught the current
     "Undefined variable: ValuesLib" regression.

4. **`julc-smoke-cli-init-build`**
   - Uses the built CLI distribution (`./gradlew :julc-cli:installDist`).
   - `julc init my-project`, then `julc check`, then `julc build`.

5. **`julc-smoke-cli-test`**
   - Continuation of init-build: writes a `@Test` method, runs `julc test`.

### Integration (multi-step, cross-jar)

6. **`julc-test-library-roundtrip`** — the highest-value catcher
   - Project A bundles an `@OnchainLibrary`, publishes its jar to mavenLocal.
   - Project B depends on A via mavenLocal and uses the library in a
     `@SpendingValidator`.
   - Verifies B compiles, produces UPLC, and exits clean.

7. **`julc-test-multimodule`**
   - Single multi-project Gradle build with `:lib` and `:validator` modules.
   - Cheaper than roundtrip; exercises same-build library discovery.

8. **`julc-test-blueprint`**
   - Verifies CIP-57 `plutus.json` generation: schema presence, validator
     listing, hash format.

9. **`julc-test-testkit`**
   - Temp project using `julc-testkit` to compile + evaluate a validator from
     JUnit.

### Negative (error-path verification)

10. **`julc-test-error-paths`**
    Single skill exercising several misuse scenarios; each subcase asserts a
    specific error message:
    - `@OnchainLibrary` only in Javadoc → silently not bundled (no error).
    - Nested `@OnchainLibrary` → "must be top-level" error.
    - `@OnchainLibrary` + `@SpendingValidator` → "must not combine" error.
    - Legacy `@Validator` / `@MintingPolicy` → migration error.
    - Misnamed file (class `Foo` in `Bar.java`) → path-mismatch error.
    - Library source with a validator annotation → clear rejection.

### External-project verification

11. **`julc-test-julc-examples`**
    - `julc-publish-local`, then
      `cd /Users/satya/work/bloxbean/julc-examples && ./gradlew --stop && ./gradlew clean test --refresh-dependencies`.
    - Reports per-example pass/fail. This is the skill that would have caught
      every recent downstream regression in this branch's history.

12. **`julc-test-yaci-e2e`** (requires Yaci Devkit)
    - Probes `http://localhost:10000/local-cluster/api/admin/devnet`; skips
      with a clear note if not running.
    - If running: resets devnet, builds a validator, submits a transaction,
      verifies success.

### Maintenance

13. **`julc-cleanup-test-artifacts`**
    - Removes the skill workspace directory tree.

## Composite Skills (user-facing entry points)

14. **`julc-test-quick`** (~2 min): `julc-publish-local` +
    `julc-smoke-gradle-plugin` + `julc-smoke-gradle-stdlib-usage` +
    `julc-test-error-paths`. Catches ~80% of recent regression classes.

15. **`julc-test-full`** (~8 min, no Yaci): quick + `library-roundtrip` +
    `multimodule` + `blueprint` + `testkit` + `julc-examples`.

16. **`julc-test-release`** (~15 min, requires Yaci): full + CLI smokes +
    `yaci-e2e`. Pre-release gate.

## Conventions

The following conventions apply to every skill in the catalog. They are
mandatory; deviations should be called out explicitly in the skill's
description.

- **Workspace root**: `~/.julc-test-workspace/<skill-name>/<timestamp>/`.
  Survives reboots (unlike `/tmp` on macOS) so failure artifacts remain
  available for post-mortem. The maintenance skill cleans it up.

- **Version pinning**: each skill reads `version` from
  `/Users/satya/work/bloxbean/julc/gradle.properties` and substitutes it into
  generated `build.gradle` / `pom.xml`. No hardcoded versions. The version is
  echoed at the start of every run so failure logs are unambiguous.

- **Daemon hygiene**: any skill that depends on a freshly published artifact
  calls `./gradlew --stop` in both the JuLC repo and the temp project before
  running. Documented in ADR-024 — stale daemons keep stale annotation
  processor class definitions in memory and silently mask regressions.

- **Structured outcome**: each skill exits with `0` on PASS and non-zero on
  FAIL. Last line of stdout is exactly `PASS` or `FAIL: <reason>`. This makes
  composition trivial (composite skills check exit codes, not parse text).

- **Idempotency**: re-running a skill on the same workspace must work without
  manual cleanup. Skills create timestamped subdirectories rather than
  overwriting.

- **Composition over duplication**: composite skills invoke leaf skills via
  the `Skill` tool. Leaf skills do not duplicate publish/setup logic.

- **Skip with note**: when a precondition is unmet (Yaci not running, CLI
  distribution not built), skills exit with `SKIP: <reason>` and exit code 0.
  Composite skills treat SKIP as a non-failure but report it in the summary.

## File Layout

```
.claude/commands/
├── julc-publish-local.md
├── julc-smoke-gradle-plugin.md
├── julc-smoke-gradle-stdlib-usage.md
├── julc-smoke-cli-init-build.md
├── julc-smoke-cli-test.md
├── julc-test-library-roundtrip.md
├── julc-test-multimodule.md
├── julc-test-blueprint.md
├── julc-test-testkit.md
├── julc-test-error-paths.md
├── julc-test-julc-examples.md
├── julc-test-yaci-e2e.md
├── julc-cleanup-test-artifacts.md
├── julc-test-quick.md         # composite
├── julc-test-full.md          # composite
└── julc-test-release.md       # composite
```

Each command file follows the template:

1. **Purpose** — one sentence.
2. **When to trigger** — user-visible phrases that should invoke this skill.
3. **Inputs / environment** — required env vars, expected working directory.
4. **Steps** — numbered bash sequence (or `Skill` invocations for composites).
5. **Assertions** — what success looks like.
6. **Cleanup** — what (if anything) is removed at end.
7. **Expected output** — sample PASS line, sample FAIL line.

## Implementation Order

Skills should be added in this order so each unlocks downstream value:

1. `julc-publish-local` — foundation; everything else depends on it.
2. `julc-smoke-gradle-plugin` — proves the foundation works end-to-end.
3. `julc-smoke-gradle-stdlib-usage` — directly catches the current AP
   classpath regression.
4. `julc-test-library-roundtrip` — highest-value integration catcher.
5. `julc-test-error-paths` — locks in ADR-023 negative scenarios.
6. `julc-test-julc-examples` — converts the existing manual sanity check into
   automation.
7. `julc-cleanup-test-artifacts` — quick hygiene win.
8. `julc-test-quick` composite — first "ship it?" button.
9. CLI smokes, multimodule, blueprint, testkit (ordered by perceived risk).
10. `julc-test-yaci-e2e` and the `release` composite last.

## Verification

To validate the skill catalog itself (separate from validating JuLC):

- Run `julc-test-quick` against the current branch. Expected: PASS.
- Intentionally introduce the historical `bundleJulcSources` resource-path bug
  (write to `build/resources/main` directly). Re-run `julc-test-quick`.
  Expected: FAIL with a clear pointer to `compileTestJava`.
- Intentionally re-introduce the Javadoc `@OnchainLibrary` misclassification.
  Re-run. Expected: `julc-test-error-paths` FAILs with a clear pointer.
- Run `julc-test-full` without Yaci. Expected: SKIP for `yaci-e2e`, PASS for
  the rest.

The catalog is considered working once the three reintroduced regressions are
each caught by a distinct skill.

## Consequences

Benefits:

- Regressions that depend on cross-jar / cross-project behavior get a fast,
  reproducible automation layer instead of relying on manual smoke testing.
- The "publish + consume + verify" loop, currently the slowest part of
  validating a release candidate, becomes a single skill invocation.
- Skill names map cleanly to bug classes ("did stdlib discovery break?" →
  `julc-smoke-gradle-stdlib-usage`), so future bug reports can cite the
  catching skill.

Tradeoffs:

- Skills live outside the Gradle test suite, so CI integration is a separate
  question (each skill can be wrapped in a shell job, but is not automatic).
- Maintaining temp-project templates inside markdown skill files is brittle
  compared to keeping them in a normal test source tree. Mitigation: keep
  templates minimal; complex templates can live under
  `.claude/templates/` and be referenced by skills.
- The skill catalog grows the surface area of `.claude/commands/`.
  Documented in this ADR + each skill's "When to trigger" section.
- These dev-facing skills live alongside (not under) the user-facing
  `tools/claude-skill/julc/` skill; the two have different audiences (JuLC
  language developers vs. JuLC end users) and different distribution paths.

## Non-Goals

- Replace existing JUnit / Gradle test suites. Skills complement, not
  duplicate, the in-repo suite.
- Become a CI pipeline. CI integration is a follow-up; this ADR scopes the
  local-development surface only.
- Cover non-JuLC tooling (Yaci Store MCP, etc.).
- Cover Maven plugin flows unless a JuLC Maven plugin actually ships.

## Follow-Up

- Wire the composite skills into a GitHub Actions job that runs on
  pre-release tags. Out of scope here.
- If `.claude/templates/` is created (for shared temp-project skeletons),
  document its contract in a top-level note so users editing skills know
  where to look.
- Consider exporting the failure log structure so CI integrations can grep
  for `FAIL:` deterministically.
