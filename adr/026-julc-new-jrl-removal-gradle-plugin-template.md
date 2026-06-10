# ADR-026: Hide JRL from `julc new` and Apply the Gradle Plugin in the Scaffolded Gradle Template

Date: 2026-06-08

## Status

Accepted and implemented.

## Goal

Two scaffolding (`julc new`) improvements:

1. Stop advertising the experimental **JRL (JuLC Rule Language)** project type from the CLI, since
   JRL is not being publicly announced yet.
2. Make a scaffolded **Gradle** project able to publish a reusable `@OnchainLibrary` out of the box,
   by applying the official JuLC Gradle plugin in the generated `build.gradle`.

The two changes are independent and are bundled in one ADR because both touch the `julc new`
scaffolding surface only.

## Decision 1 — Remove the `--jrl` option from `julc new`

`julc new --jrl` scaffolded a `.jrl` starter contract instead of a `.java` validator. We remove the
flag and the now-dead `.jrl` scaffolding branch:

- `NewCommand.java`: delete the `@Option(names = {"--jrl"})` field; call the existing two-argument
  `ProjectScaffolder.scaffold(projectRoot, name)`; drop the `"JRL project"` success message variant.
- `ProjectScaffolder.java`: collapse to a single `scaffold(Path, String)` method, removing the
  `boolean jrl` parameter and the `if (jrl) { … AlwaysSucceeds.jrl … }` branch. The Java validator
  path is retained unchanged.

### Scope boundary

This is a **CLI-surface-only** change. We intentionally keep:

- `.jrl` scanning and compilation in `julc build` / `julc check`
  (`ProjectScanner.java`, `BuildCommand.java`) — hand-written `.jrl` files still build.
- The `julc-jrl` module, its `settings.gradle` include, and the `julc-cli` dependency on it.

So re-enabling the option later (when JRL is announced) is a small, additive change: restore the flag
and the scaffolding branch. Nothing in the JRL compiler pipeline is deleted.

### Known follow-up

`docs/src/content/docs/experimental/jrl-guide.md` still instructs `julc new my-contract --jrl`, which
no longer works. The guide lives under `experimental/`; updating or dropping that instruction is a
minor, non-blocking follow-up tracked separately.

## Decision 2 — Apply the Gradle plugin in the generated Gradle template

The scaffolded Gradle `build.gradle` previously applied only `id 'java'` and relied solely on the
JuLC **annotation processor**. The annotation processor compiles validators and writes the blueprint
to `META-INF/plutus/`, but it **does not bundle `@OnchainLibrary` source files**. Only the Gradle
plugin's `bundleJulcSources` task writes them to `META-INF/plutus-sources/`, which is exactly where
`LibrarySourceResolver` (used by downstream consumers' annotation processors) looks. Consequently, a
scaffolded Gradle project could not publish a reusable on-chain library.

We add the plugin to the generated `build.gradle`:

```gradle
plugins {
    id 'java'
    // Bundles @OnchainLibrary sources into META-INF/plutus-sources/ in the jar
    // so downstream projects can discover and reuse them.
    id 'com.bloxbean.cardano.julc' version '<julcVersion>'
}
```

The version is the same `JulcVersionProvider.VERSION` already injected for the dependency
coordinates, substituted as a literal in the `plugins {}` block. The generated `settings.gradle`
already declares `pluginManagement` repositories (`mavenLocal()`, the Sonatype snapshots repo, and
`gradlePluginPortal()`), so the plugin marker resolves with no `settings.gradle` change.

### Why this is safe (no regression to `./gradlew build`)

Verified against the plugin source:

- `CompileJulcTask.getSourceDir()` is annotated `@SkipWhenEmpty` and defaults to `src/main/plutus`.
  The template never creates that directory, so `compileJulc` is **skipped** (not failed), and
  `build.dependsOn(compileJulc)` treats a skipped task as success.
- `BundleJulcSourcesTask` scans `src/main/java`. With only the `AlwaysSucceeds` validator (which is
  not `@OnchainLibrary`), it logs "No `@OnchainLibrary` sources found" and writes nothing. When the
  user later adds an `@OnchainLibrary` class, its source is bundled automatically.
- The plugin applies `JavaPlugin` internally; combining it with `id 'java'` is idempotent and matches
  how `julc-examples` applies it.

This matches the canonical pattern documented in
`docs/src/content/docs/reference/library-developer-guide.md` and exercised by the
`julc-test-library-roundtrip` and `julc-test-multimodule` skills.

### Scope boundary

The Maven template is unchanged — there is no Maven equivalent of the JuLC Gradle plugin, so Maven
projects still cannot auto-bundle `@OnchainLibrary` sources. The BASIC (`julc.toml`/CLI) template is
unaffected.

## Verification

```bash
# Unit tests for the scaffolders (no Gradle execution required)
./gradlew :julc-cli:test
```

`JulcIntegrationTest.scaffoldGradleProject` is extended to assert the generated `build.gradle` applies
`id 'com.bloxbean.cardano.julc' version '<JulcVersionProvider.VERSION>'`. The existing
`scaffoldBuildCheck`, `scaffoldContext`, and `scaffoldMavenProject` tests continue to pass after the
`ProjectScaffolder` signature change.

End-to-end (manual / skill-driven):

```bash
# Plugin marker + artifacts must be in Maven local first
./gradlew publishToMavenLocal
./gradlew --stop

# Task 1: --jrl is gone
julc new demo            # scaffolds AlwaysSucceeds.java
julc new demo --jrl      # now fails with "Unknown option: '--jrl'"

# Task 2: gradle template builds with the plugin applied (compileJulc SKIPPED)
julc new gdemo -t gradle
cd gdemo && ./gradlew build
```

Bundling can be confirmed by adding a minimal `@OnchainLibrary` under `src/main/java` and asserting the
jar contains `META-INF/plutus-sources/<pkg>/<Lib>.java` plus `index.txt` — the same assertion used by
`julc-test-library-roundtrip`.
