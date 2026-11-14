# ADR-073: Compiled scripts keyed by the validator's qualified name

**Status:** Proposed (r2, r3: review rounds 1 and 2 on the implementation)
**Date:** 2026-11-14
**Label:** `joust`
**Risk class:** low. No compiler, lowering or VM change: no script's bytes change. It changes which class path
resources the annotation processor writes, one field of their JSON, and which copy `JulcScriptLoader` reads.
**Motivation:** [#326](https://github.com/bloxbean/julc/issues/326), found in JOUST (bloxbean/joust#67).

## Context

The annotation processor writes a validator's compiled script to `META-INF/plutus/<SimpleName>.plutus.json` (and its
source map to `<SimpleName>.sourcemap.json`). `JulcScriptLoader.load(Class)` reads the first copy of that resource
from the class's class loader.

Validators in different packages can share a simple name. With `com.a.VaultValidator` and `com.b.VaultValidator` on
one class path, `load(com.b.VaultValidator.class)` returns `com.a`'s script without any error, so the caller deploys
or evaluates the wrong contract. A shaded jar keeps one of the two files, and a dependency can supply a resource with
that name. Inside one compilation the second `Filer.createResource` fails with a bare `FilerException`.

## Goals

- A class loads its own script and source map, or gets an error. It never loads another class's script, except in
  one case JuLC cannot detect, listed under Risks: a jar that merges two same-named validators compiled before this
  change.
- Modules compiled before this change keep loading their own scripts.
- Loaders older than this change keep finding scripts that this version writes.

## Non-goals

- The Gradle plugin's `compileJulc` task and the CLI's `julc build`, which write to `build/plutus/` rather than the
  class path. They are not read by `JulcScriptLoader`.
- Allowing two validators with one simple name in one compilation: the CIP-57 blueprint names each validator by its
  simple name and already rejects duplicate titles.
- Integrity checks against a hostile class path. A dependency that runs on the class path can replace the class as
  well as its script.

## Invariants

1. **I1.** For a class `C`, `JulcScriptLoader` uses the first of these that exists, and otherwise throws
   `IllegalArgumentException`:
   1. a copy of `C`'s qualified resource. All its copies must be identical, and it must record `C` or no class;
   2. a copy of `C`'s simple-name resource that records `C`. All such copies must be identical;
   3. a copy of `C`'s simple-name resource that records no class. It is used only when every class path root
      (directory or jar) that holds `C.class` holds such a copy, all of them identical. It is also refused when a
      script for a shorter name that still has a package is on the class path, such as `com.b.Vault` for
      `shaded.com.b.Vault`: the class looks relocated.

   A copy that records another class is never used.
2. **I2.** A source map is read only from beside the copy of the script that was used (the same URL with the
   `.sourcemap.json` suffix). Among identical copies, the one in `C`'s own root is used when there is one.
3. **I3.** Script bytes, hashes, the blueprint and the simple-name file's location are unchanged. The JSON gains only
   the `validator` field.
4. **I4.** Output is deterministic: the processor writes the same files with the same content for the same input.

## Decision

1. **Layout** (`ScriptResourcePaths` in `julc-cardano-client-lib`, shared by writer and reader). Each script and
   source map is written to `META-INF/plutus/<binary name with '.' as '/'><suffix>`, for example
   `META-INF/plutus/com/b/VaultValidator.plutus.json` or `META-INF/plutus/com/b/Outer$VaultValidator.plutus.json`,
   and to `META-INF/plutus/<SimpleName><suffix>`. For a class in the unnamed package the two paths are the same, and
   the file is written once. The binary name comes from `Elements.getBinaryName`, which matches `Class.getName()`.
   The files are written as UTF-8, whatever javac's `-encoding`.
2. **Recorded class.** `ValidatorOutput` gains a last component, `validator`, holding the binary name. It is empty
   for older metadata. The old seven-argument constructor is kept. `toJson()` writes `"validator"` after
   `"description"`, and only when the name is non-empty, so output without it is byte-identical to before.
   `fromJson` reads a field only where its name is a key (followed by `:`), so a description such as `"validator"`
   is not mistaken for one.
3. **Lookup** (I1). The loader reads every copy of a path from the class's class loader, or from the system loader
   when the class has none. Every public entry point goes through it: `load`, `loadOutput`, `scriptHash`,
   `loadWithSourceMap` and `loadSourceMap`, with and without parameters.
   - The class's roots are the URLs of every `<binary path>.class` from the same loader, with that path removed, for
     example `file:/app/classes/` or `jar:file:/app.jar!/`. A multi-release jar's `META-INF/versions/<n>/` is removed
     too. URLs are compared after their `%XX` escapes are decoded, because a class loader escapes names outside
     ASCII. A copy is in a root when its URL is the root followed by its resource path. The first root is the one
     preferred for source maps (I2).
   - Step 3 asks every root, not just the first. A loader can define a class from one root while it looks resources
     up in another order, for example child-first for classes and parent-first for resources, so the first `.class`
     resource does not prove where the class came from.
   - When the class path holds two different copies at step 1 or 2, the error lists each URL with the class it
     records.
4. **Source map pairing** (I2): the loader takes the source-map resource whose URL is the chosen script's URL with the
   `.sourcemap.json` suffix. If there is none it returns `null`.
5. **Processor diagnostic.** When a second validator with an already-seen simple name is processed, the processor
   reports an error that names both classes and stops before compiling it.

## Alternatives considered

- **Take a single simple-name copy that records no class, from anywhere** (issue #326's proposed rule, and r1 of this
  ADR). Rejected in review. A class whose own module has no script, for example because the processor did not run,
  would get a script that another old module, or a dependency, left on the class path.
- **Refuse every copy that records no class.** Rejected: every module compiled before this change would stop
  loading.
- **Read from the class's `CodeSource`** (JOUST's workaround). Not adopted as stated. `CodeSource` URLs differ from
  resource URLs (`file:/app.jar` against `jar:file:/app.jar!/`), and some loaders do not set them. Step 3 instead
  uses the class's own `.class` resource URL from the same loader as the script, so both URLs have the same form.
- **Use the first root that holds `C.class` only** (r2). Rejected in review round 2: a loader that is child-first for
  classes but parent-first for resources defines `C` from the child, while the first `.class` resource is the
  parent's.
- **Scan the class's root for another class with the same simple name** (JOUST does this for jars). Rejected for
  now. It needs code for each URL scheme (directories, jars, nested jars), and it serves only modules compiled before
  this change. That case is listed under Risks instead.
- **Write only the qualified file.** Rejected: older `JulcScriptLoader`s, and tools that read
  `META-INF/plutus/<SimpleName>.plutus.json` directly, would stop finding new scripts.
- **Take the first copy with a warning** (the old behaviour plus logging). Rejected: the caller still gets another
  contract's script.
- **Refuse any second copy, even an identical one.** Rejected: the same module twice on a class path, such as a
  classes directory and its jar, is harmless, and identical copies are one script.
- **Skip the recorded-class check on the qualified path** so that a relocated validator could load once its
  resources are moved too. Rejected: shading plugins do not move `META-INF/plutus/<package path>/` with the
  package, and do not rewrite the recorded name. The check costs nothing and catches a resource that was copied to
  the wrong path.

## Affected modules

- `julc-cardano-client-lib`: `ScriptResourcePaths` (new), `ValidatorOutput`, `JulcScriptLoader`.
- `julc-annotation-processor`: `JulcAnnotationProcessor`.
- Docs: getting started, troubleshooting 3.14 and 5.5–5.6, release notes, architecture reference.

## Compatibility

- **Old module, new loader:** each old class loads the simple-name copy in its own jar or directory, as before,
  including when other same-named validators are on the class path. That case used to return the first copy. This
  needs the class loader to serve the class's `.class` file as a resource, which standard, Spring Boot and module
  path loaders do. A copy kept apart from its class, for example by a build that moved `META-INF/plutus/` into
  another jar, is refused until the module is recompiled.
- **New module, old loader:** finds the simple-name copy as before. An old parser ignores the extra field, because it
  reads fields by name.
- **`ValidatorOutput`:** a new record component. The canonical constructor now takes eight arguments; the
  seven-argument constructor remains. Record patterns that deconstruct `ValidatorOutput`, and `equals`, now include
  the new component.
- **Relocated (shaded) validator classes:** a relocated class has no qualified copy under its new name, and its
  copies record the original name, so it is refused, with an error that names relocation as the cause, whether or not
  its resources were moved with it. Exclude validator packages from relocation, or recompile them in their new
  package.
- **One compilation with two same-named validators:** failed before (`FilerException`) and still fails, now with a
  diagnostic that names both.

## Risks

- **Undetected:** a root that holds a simple-name copy written before this change serves it to every class with that
  simple name in the same root that has no script of its own and does not look relocated. Examples: a fat jar
  merging two such older modules, which keeps one copy, or a class in the jar that the processor skipped. origin/main
  did the same. Recompiling the older module with this version writes the qualified copies, which fixes it.
- Two different builds of one class's script on the class path, such as a stale jar beside fresh classes, used to
  pick one silently and now fail; the error names both URLs. When the stale jar predates this change, it has no
  qualified copy, so the fresh build's qualified copy is used, even when the class itself was loaded from the stale
  jar. origin/main used the first copy in class path order. Either way the setup holds two builds of one class.
- Tools that list `META-INF/plutus/` without recursing see only the simple-name files. That list is still complete
  for every compilation, because one compilation cannot hold two validators with one simple name.

## Milestones

1. M1 (this change): layout, recorded class, loader lookup, source-map pairing, processor diagnostic, tests, docs.

## Verification

- `SameSimpleNameValidatorsTest` (processor module) compiles real validators into separate class output
  directories. Some cases also package them as jars. It covers:
  - each class loads its own script and source map in either class path order, from directories and from jars (the
    issue's repro);
  - old modules (simple-name copy only, no recorded class), alone, next to each other in either order, and next to a
    new module, each load their own script;
  - two old modules whose script JSON is identical but whose source maps differ each read their own map;
  - a class without a script refuses another class's copy, recorded or not, through all eight entry points;
  - two different qualified copies are refused in either order;
  - identical copies are accepted, and the source map comes from the class's own root, including when a root holding
    only a copy of the script comes first;
  - the processor rejects one compilation that holds both classes;
  - an unnamed-package validator is written once, and loads beside a packaged validator of the same name;
  - a nested validator loads by its binary name;
  - a relocated class is refused, and the error names relocation, whether its resources were moved or not, including
    in a fat jar that also holds an older same-named validator's copy;
  - two different copies that both record the class, with no qualified copy, are refused;
  - an old module twice on the class path (directory and jar) still loads;
  - a loader that is child-first for classes and parent-first for resources does not take an old copy that sits
    beside another build of the class;
  - an old module in a multi-release jar, with its class in a version directory, still loads;
  - non-ASCII package and class names, old and new, as directories and jars, load their own scripts and source
    maps.
- Mutations of the lookup, each caught by at least one of those tests:
  - an unrecorded copy accepted from any root;
  - the own-root preference dropped;
  - a copy that records another class counted as the class's own;
  - the recorded-class check on the qualified path dropped;
  - step 3 asking only the first root;
  - URLs compared without decoding;
  - the relocation check dropped;
  - the multi-release version directory kept in the root.
- `ValidatorOutputTest`: the field round-trips, older JSON reads as empty, output without the field is
  byte-identical, and a value that spells a key is not read as that key.
- `ScriptResourcePathsTest`: packaged, nested and unnamed-package paths. `JulcScriptLoaderDecodeTest`: `%XX`
  decoding as UTF-8, `+` kept, malformed escapes kept.
- Module suites (`julc-cardano-client-lib`, `julc-annotation-processor`), the full `./gradlew build`, and the external
  `julc-examples` build against the local snapshot.

## Open questions

- Whether a future release should stop writing the simple-name copy, once no supported loader needs it.
- Follow-ups from review, not done here:
  - The processor's duplicate check is case-sensitive. `com.a.Vault` and `com.b.vault` in one compilation write
    `Vault.plutus.json` and `vault.plutus.json`, which overwrite each other on a case-insensitive file system. That
    affects only older loaders. Making the check case-insensitive would fail builds that pass today.
  - "Different copies" compares the whole JSON. Two builds of one script whose metadata differs, for example after a
    future field is added, count as different.
