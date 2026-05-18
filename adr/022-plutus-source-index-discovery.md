# ADR-022: Plutus Source Index Discovery

**Date**: 2026-05-18
**Status**: Accepted

---

## Context

JuLC libraries distribute `@OnchainLibrary` Java sources in dependency JARs under:

```text
META-INF/plutus-sources/
```

The compiler resolver needs those sources during annotation processing and direct compiler usage. JAR directory listing is not portable through `ClassLoader` APIs, so reliable JAR discovery requires a manifest:

```text
META-INF/plutus-sources/index.txt
```

The Gradle plugin previously copied source files but did not generate this index. The resolver also used `getResourceAsStream(...)`, which only reads the first matching `index.txt` on the classpath.

## Decision

1. `bundleJulcSources` generates `META-INF/plutus-sources/index.txt`.
2. Each index entry is a path relative to `META-INF/plutus-sources/`, for example:

```text
com/example/Groth16BLS12381.java
```

3. The resolver reads every `META-INF/plutus-sources/index.txt` via `ClassLoader.getResources(...)`.
4. JARs must ship `index.txt` for reliable source discovery.
5. Loose file-system `META-INF/plutus-sources/` directories are still scanned without an index for IDE, test, and development classpaths.
6. Indexed entries are loaded before loose file-system fallback entries. Existing `putIfAbsent` behavior means indexed entries win and loose directories fill gaps.

## Consequences

- Multiple dependency JARs can each contribute on-chain library sources.
- The Gradle plugin now produces JARs that satisfy the resolver contract.
- Hand-built JARs without `index.txt` remain unsupported for reliable discovery.
- Existing development workflows that use exploded file-system resources continue to work.
- The resolver still keys discovered libraries by simple class name. That can collide when two packages contain the same class name.

## Follow-Up

The simple-name collision issue should be fixed in a separate Phase 2 PR. That PR should migrate resolver internals and all call sites to an FQCN-aware `LibrarySource` model, remove the simple-name scan API, reject or fully support wildcard imports explicitly, and route ambiguity errors through the compiler diagnostic pipeline.
