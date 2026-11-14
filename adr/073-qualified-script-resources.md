# ADR-073: Compiled scripts keyed by the validator's qualified name

**Status:** Proposed
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

- A class loads its own script and source map, or an error. It never loads another class's.
- Modules compiled before this change keep loading where that is unambiguous.
- Loaders older than this change keep finding scripts that this version writes.

## Non-goals

- The Gradle plugin's `compileJulc` task and the CLI's `julc build`, which write to `build/plutus/` rather than the
  class path. They are not read by `JulcScriptLoader`.
- Allowing two validators with one simple name in one compilation: the CIP-57 blueprint names each validator by its
  simple name and already rejects duplicate titles.
- Integrity checks against a hostile class path. A dependency that runs on the class path can replace the class as
  well as its script.

## Invariants

1. **I1.** For a class `C`, `JulcScriptLoader` returns a script whose JSON is the only distinct copy of `C`'s qualified
   resource. When `C` has none, the script is the only distinct copy of its simple-name resource, and that copy does
   not record a class other than `C`. In every other case it throws `IllegalArgumentException`.
2. **I2.** A source map is read only from beside the copy of the script that was loaded (same URL with the
   `.sourcemap.json` suffix), never from another copy.
3. **I3.** Script bytes, hashes, the blueprint and the simple-name file's location are unchanged. The JSON gains only
   the `validator` field.
4. **I4.** Output is deterministic: the processor writes the same files with the same content for the same input.

## Decision

1. **Layout** (`ScriptResourcePaths` in `julc-cardano-client-lib`, shared by writer and reader). Each script and
   source map is written to `META-INF/plutus/<binary name with '.' as '/'><suffix>`, for example
   `META-INF/plutus/com/b/VaultValidator.plutus.json` or `META-INF/plutus/com/b/Outer$VaultValidator.plutus.json`,
   and to `META-INF/plutus/<SimpleName><suffix>`. For a class in the unnamed package the two paths are the same and
   the file is written once. The binary name is `Elements.getBinaryName`, which is what `Class.getName()` returns.
2. **Recorded class.** `ValidatorOutput` gains a last component, `validator`, holding the binary name. It is empty
   for older metadata. The old seven-argument constructor is kept, and `toJson()` writes `"validator"` after
   `"description"` only when it is non-empty, so output without it is byte-identical to before.
3. **Lookup.** `JulcScriptLoader` reads every copy of the qualified path from the class's class loader, using the
   system loader when the class has none, and merges identical contents. When there are none it does the same for
   the simple-name path. No copy means "not found". More than one distinct copy is refused, and the error lists each
   URL with the class it records. A single copy that records another class is refused. Every public entry point
   (`load`, `loadOutput`, `scriptHash`, `loadWithSourceMap`, `loadSourceMap`) goes through this lookup.
4. **Source map pairing** (I2): the loader takes the source-map resource whose URL is the script URL with the
   `.sourcemap.json` suffix. If there is none it returns `null`.
5. **Processor diagnostic.** When a second validator with an already-seen simple name is processed, the processor
   reports an error that names both classes and stops before compiling it.

## Alternatives considered

- **Read from the class's own code source** (JOUST's workaround: resolve the resource inside the class's
  `CodeSource`). Rejected as the primary rule. Code-source URLs differ by packaging (directories, jars, nested jars,
  the module path, custom loaders), and some are unavailable under a security manager or a custom loader. A
  qualified name gives the same isolation through the ordinary class loader lookup.
- **Write only the qualified file.** Rejected: older `JulcScriptLoader`s, and tools that read
  `META-INF/plutus/<SimpleName>.plutus.json` directly, would stop finding new scripts.
- **Take the first copy with a warning** (the old behaviour plus logging). Rejected: the caller still gets another
  contract's script.
- **Refuse any second copy, even an identical one.** Rejected: the same module twice on a class path, such as a
  classes directory and its jar, is harmless, and the merged copies are one script.
- **Accept a simple-name copy that records no class when another copy records a different class.** Rejected: with
  more than one visible copy the loader cannot tell which one, if any, belongs to the class (issue #326's rule).

## Affected modules

- `julc-cardano-client-lib`: `ScriptResourcePaths` (new), `ValidatorOutput`, `JulcScriptLoader`.
- `julc-annotation-processor`: `JulcAnnotationProcessor`.
- Docs: getting started, troubleshooting 5.5–5.7, release notes.

## Compatibility

- **Old module, new loader:** loads through the simple-name fallback when exactly one distinct copy is visible. If
  several are visible, which used to return the first silently, it now throws.
- **New module, old loader:** finds the simple-name copy as before. An old parser ignores the extra field, because it
  reads fields by name.
- **`ValidatorOutput`:** a new record component. The canonical constructor now takes eight arguments; the
  seven-argument constructor remains. Record patterns that deconstruct `ValidatorOutput` need the new component.
- **Relocated (shaded) validator classes:** a class whose package was relocated without its resources finds no
  qualified copy, and the simple-name copy records the original name, so it is refused. Relocate
  `META-INF/plutus/<package path>/` with the classes.
- **One compilation with two same-named validators:** failed before (`FilerException`) and still fails, now with a
  diagnostic that names both.

## Risks

- A build that has two different copies of one class's script on its class path, such as a stale jar beside fresh
  classes, used to pick one and now fails. The error names both URLs.
- Tools that list `META-INF/plutus/` without recursing see only the simple-name files, which are still complete for
  every compilation, because one compilation cannot hold two validators with one simple name.

## Milestones

1. M1 (this change): layout, recorded class, loader lookup, source-map pairing, processor diagnostic, tests, docs.

## Verification

- `SameSimpleNameValidatorsTest` (processor module) compiles `com.a.VaultValidator` and `com.b.VaultValidator` into
  two class output directories and covers:
  - each class loads its own script in either class path order (the issue's repro);
  - each class loads its own source map, checked through the source lines its terms map to;
  - a legacy-only module still loads its script and source map;
  - two different simple-name copies are refused, and so is a simple-name copy recorded for another class (through
    every entry point);
  - two different qualified copies are refused, and identical copies are accepted;
  - the processor rejects one compilation holding both classes;
  - an unnamed-package validator is written once.
- `ValidatorOutputTest`: the field round-trips, older JSON reads as empty, and output without it is byte-identical.
- `ScriptResourcePathsTest`: packaged, nested and unnamed-package paths.
- Module suites (`julc-cardano-client-lib`, `julc-annotation-processor`), the full `./gradlew build`, and the external
  `julc-examples` build against the local snapshot.

## Open questions

- Whether a future release should stop writing the simple-name copy, once no supported loader needs it.
