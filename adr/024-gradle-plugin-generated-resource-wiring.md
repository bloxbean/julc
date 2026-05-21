# ADR-024: Gradle Plugin Generated Resource Wiring

Date: 2026-05-21

## Status

Accepted and implemented.

## Goal

Allow projects that apply the official JuLC Gradle plugin to package
`@OnchainLibrary` sources into `META-INF/plutus-sources/` without violating
Gradle task ownership or implicit dependency validation.

## Problem

After applying `id 'com.bloxbean.cardano.julc'` in `julc-examples`,
`./gradlew clean build -x test` could compile, but `./gradlew clean build`
failed during `compileTestJava`.

Gradle reported that `compileTestJava` consumed
`build/resources/main`, which was produced by `bundleJulcSources`, without a
declared dependency.

## Root Cause

`bundleJulcSources` wrote directly into `build/resources/main`. That directory
is owned by Gradle's `processResources` task.

The plugin only declared `jar.dependsOn(bundleJulcSources)`. That was enough for
some packaging paths, but not for test compilation. `compileTestJava` uses main
source set outputs, including `build/resources/main`, on its classpath, so
Gradle correctly detected an implicit dependency.

This appeared in `julc-examples` only after the JuLC Gradle plugin was applied;
before that, there was no `bundleJulcSources` task writing into the main
resources output directory.

## Decision

`bundleJulcSources` now writes to a task-owned generated resources directory:

```text
build/generated/julc/resources/main
```

The plugin registers the `bundleJulcSources` task provider as a main resources
source directory:

```java
mainSourceSet.getResources().srcDir(bundleJulcSources);
```

Because `BundleJulcSourcesTask` declares its output via `@OutputDirectory`,
Gradle can infer the task dependency through the source set:

```text
bundleJulcSources -> processResources -> classes -> compileTestJava / jar
```

The plugin no longer relies on `jar.dependsOn(bundleJulcSources)`.

The task output directory remains overridable because the plugin sets the new
path with `convention(...)`, not `set(...)`.

## Notes

`julc-stdlib` already uses a generated resources directory for its own
`bundlePlutusSources` task, so no stdlib build-script change was needed.

For local SNAPSHOT testing, Gradle daemons should be stopped after republishing
JuLC artifacts to Maven local. Otherwise, Gradle can reuse stale annotation
processor classes from an existing daemon.

## Verification

JuLC verification:

```bash
./gradlew :julc-gradle-plugin:test --tests com.bloxbean.cardano.julc.gradle.JulcPluginTest --tests com.bloxbean.cardano.julc.gradle.BundleJulcSourcesTaskTest
./gradlew test --continue
./gradlew publishToMavenLocal
```

The plugin regression suite now covers:

- `clean compileTestJava` with `@OnchainLibrary` sources.
- `jar` packaging of `META-INF/plutus-sources/index.txt`.
- generated resources being copied through `processResources`.
- configuration-cache smoke coverage for the `compileTestJava` path.

External validation in `/Users/satya/work/bloxbean/julc-examples`:

```bash
./gradlew --stop
./gradlew clean build --refresh-dependencies --console=plain
```

Result: build passed, including Yaci Devkit integration tests.
