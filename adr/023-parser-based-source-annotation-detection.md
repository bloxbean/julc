# ADR-023: Parser-Based Source Annotation Detection

**Date**: 2026-05-20
**Status**: Proposed

---

## Context

JuLC source discovery currently has several places where Java source files are classified by scanning raw text for annotation tokens such as `@OnchainLibrary`, `@SpendingValidator`, or `@MintingValidator`.

This is fragile because comments, Javadocs, and string literals can contain the same text without declaring an annotation. A concrete failure was found in `julc-examples`:

```java
/**
 * Uses the MerklePatriciaForestry @OnchainLibrary for on-chain proof verification.
 */
@SpendingValidator
public class MpfRegistryValidator {
}
```

`bundleJulcSources` treated the file as an on-chain library candidate because the source text contained `@OnchainLibrary` in Javadoc. It then failed with a misleading nested-library error even though the validator had no nested `@OnchainLibrary`.

The affected pattern is not isolated to `bundleJulcSources`. Similar text or regex-based classification exists in:

- `julc-gradle-plugin`: library bundling, validator discovery, script type detection
- `julc-testkit`: same-project `@OnchainLibrary` discovery
- `julc-cli`: project validator/library scanning, script type detection, and `@Test` method discovery
- `julc-blueprint`: schema extraction validator detection; this already parses source but then regex-matches rendered AST text, which can reintroduce comments/Javadocs into detection

The core compiler already parses Java source for normal compilation and should remain the semantic authority for compilation itself. This ADR is focused on fixing pre-compilation source discovery and classification.

`@Validator` and `@MintingPolicy` are deprecated aliases for `@SpendingValidator` and `@MintingValidator`. JuLC has not reached a stable release, so this ADR treats them as legacy annotations to remove rather than aliases to keep carrying forward.

## Decision

Use a two-step annotation detection process for source discovery:

1. A cheap text scan may be used only as a negative prefilter.
2. JavaParser-based AST inspection is the only authority for positive annotation classification.

In other words, raw text may answer "definitely no annotation-looking token is present, so skip parsing", but it must never answer "this file is definitely a validator" or "this file is definitely an on-chain library".

Valid use:

```java
if (!source.contains("@OnchainLibrary")) {
    continue;
}

var info = JavaSourceIntrospector.inspect(source);
if (info.topLevelOnchainLibrary().isPresent()) {
    bundleLibrary(info);
}
```

Invalid use:

```java
if (source.contains("@OnchainLibrary")) {
    bundleLibrary(source);
}
```

## Shared Utility

Add a parser-based source inspection utility in `julc-compiler`, because the affected modules already depend on `julc-compiler` and JavaParser is already part of that module's implementation.

The utility should not replace or alter the normal `JulcCompiler` compile path in this PR. It is a source-discovery helper only.

Suggested API shape:

```java
public final class JavaSourceIntrospector {
    public static SourceInfo inspect(String source);
}

public record SourceInfo(
        String packageName,
        List<String> topLevelTypeNames,
        Optional<AnnotatedType> validatorType,
        Optional<AnnotatedType> unsupportedLegacyValidatorType,
        Optional<AnnotatedType> topLevelOnchainLibrary,
        List<AnnotatedType> nestedOnchainLibraries,
        List<RoleConflict> roleConflicts) {
}

public record AnnotatedType(
        String simpleName,
        String packageName,
        String fqcn,
        String annotationName,
        List<String> annotationNames,
        boolean topLevel) {
}

public record RoleConflict(
        String simpleName,
        String fqcn,
        List<String> conflictingAnnotations) {
}
```

The exact class and record names may change during implementation, but the behavior should remain:

- Parse with a fresh `JavaParser(new ParserConfiguration().setLanguageLevel(JAVA_21))` per inspection.
- Do not mutate `StaticJavaParser` global configuration.
- Compare annotation simple names so both imported and fully qualified annotations work.
- Inspect only real AST annotations, not comments, Javadocs, or string literals.
- Return enough metadata from a single parse to avoid repeated parsing of the same source.
- Do not assume one top-level type per file. For validator and library detection, scan `CompilationUnit.getTypes()` in source order, filter for the target annotation, then select the first matching top-level type. Do not select the first type and then check only that type's annotations.
- Ignore unrelated annotations when classifying JuLC roles. For example, `@Getter @Setter @OnchainLibrary class Foo` is a valid top-level on-chain library candidate because `@OnchainLibrary` is a real annotation on the class; Lombok annotations do not change source classification.
- Detect conflicting JuLC role annotations separately from unrelated annotations. A type annotated with both `@OnchainLibrary` and any validator annotation is invalid and should fail with a clear message instead of being silently treated as either a library or validator. This should be exposed by the shared introspector, for example as `SourceInfo.roleConflicts()` and `JavaSourceIntrospector.roleConflictOn(TypeDeclaration<?>)`, so Gradle, CLI, testkit, blueprint, and direct compiler usage can enforce the same rule without reparsing source.

## Implementation Plan

### 1. Add parser-based source inspection

Create the shared utility under `julc-compiler/src/main/java/com/bloxbean/cardano/julc/compiler/...`.

It should detect:

- package name
- top-level type names
- top-level validator annotations:
  - `SpendingValidator`
  - `MintingValidator`
  - `WithdrawValidator`
  - `CertifyingValidator`
  - `VotingValidator`
  - `ProposingValidator`
  - `MultiValidator`
- top-level `OnchainLibrary`
- nested `OnchainLibrary`
- role conflicts where the same type declares both `OnchainLibrary` and a supported or legacy validator annotation
- unsupported legacy validator annotations, for diagnostics only:
  - `Validator`
  - `MintingPolicy`
- script type/purpose derived from the actual validator annotation

Do not include legacy `Validator` or `MintingPolicy` in the supported validator discovery list. If a real top-level legacy annotation is found, Gradle, CLI, testkit, blueprint discovery, and the core compiler should report a clear migration error such as "Use @SpendingValidator instead of @Validator" or "Use @MintingValidator instead of @MintingPolicy". They must not silently treat the file as a library or as "no validator found".

Remove the legacy annotation types from `julc-stdlib` and remove their compatibility paths from the compiler. Keeping the names only for explicit migration diagnostics is acceptable; that is not support for the annotations and does not require the annotation classes to exist.

Parser failures should not fall back to substring-based positive classification. If a file passed an annotation-token prefilter, callers must surface the parse problem instead of silently skipping or reclassifying it.

If a prefilter matches an annotation token but JavaParser rejects the file, the caller must not silently reclassify the file as a library, non-validator, or non-test. It must surface an explicit parse diagnostic with the source path or logical source label.

Recommended caller behavior:

- Gradle tasks: fail with a `GradleException` that includes the file path and parse problem.
- CLI project scanning and test discovery: return or print a clear diagnostic if the command has a diagnostic channel; otherwise throw a command failure with the file path.
- Testkit: fail the helper call with an `AssertionError` or equivalent test failure message.
- Blueprint extraction: fail or return an existing parse error response; do not return `null` as if no validator existed.

### 2. Fix Gradle library bundling

Update `BundleJulcSourcesTask`:

- Keep a cheap prefilter such as `if (!source.contains("@OnchainLibrary")) continue;`.
- Parse prefiltered sources.
- Bundle only when a real top-level `@OnchainLibrary` is present.
- Fail clearly when a real nested `@OnchainLibrary` is present.
- Validate package/path and class/path against the annotated top-level library type.
- Ignore comments, Javadocs, and string literals containing `@OnchainLibrary`.
- Allow unrelated annotations on the same type, for example Lombok annotations. Reject conflicting JuLC role annotations on the same type, such as `@OnchainLibrary` plus `@SpendingValidator`.
- Log at `info` level when the prefilter matched but parser inspection found no real `@OnchainLibrary`. This avoids noisy normal builds while giving users a way to debug unexpected skips.

This directly fixes the `MpfRegistryValidator` false positive.

### 3. Fix Gradle validator discovery and script type

Update `CompileJulcTask`:

- Use a cheap validator-token prefilter only to avoid parsing obvious non-candidates.
- Use parser inspection to decide whether a file is a validator.
- Derive script type from the detected validator annotation.
- Keep current behavior where non-validator Java files are passed as library sources.
- Do not classify legacy `@Validator` or `@MintingPolicy` as validator sources in the new discovery path; report a migration error if either is used as a real annotation.

Also remove or update unused `SourceScanner` annotation helpers so future code does not reuse substring-based classification.

`LibrarySourceResolver.hasTopLevelOnchainLibraryAnnotation(...)` should be removed. The implementation should not leave a public helper that swallows parser failures or a second independent top-level library annotation detector.

### 4. Fix testkit source discovery

Update `SourceDiscovery` places that currently check `src.contains("@OnchainLibrary")`.

Expected behavior:

- Real `@OnchainLibrary` source files are discovered.
- Test files or helper files containing `@OnchainLibrary` only in comments or Java string/text blocks are ignored.
- Parser failures do not become positive library matches.

### 5. Fix CLI project scanning

Update `ProjectScanner`:

- Replace full-source validator regex with two-step prefilter plus parser inspection.
- Replace `resolveScriptType(String source)` substring checks with parser-derived script type.
- Preserve existing scan result shape and keys.
- Do not classify legacy `@Validator` or `@MintingPolicy` as validator sources in the new discovery path; report a migration error if either is used as a real annotation.

### 6. Fix CLI test discovery

Update `TestDiscovery`:

- Replace the `@Test` regex and line-based lookahead with parser inspection.
- Keep a cheap `source.contains("@Test")` negative prefilter if useful.
- Discover real methods annotated with `@Test`.
- Preserve the existing method eligibility contract unless intentionally changed: public static boolean test methods.
- Ignore comments, Javadocs, and string literals containing `@Test`.

### 7. Fix blueprint validator detection

Update `SchemaGenerator` validator detection:

- Stop matching validator annotations against `ClassOrInterfaceDeclaration.toString()`.
- Use AST annotation presence on the candidate class.
- Replace `StaticJavaParser` usage with a fresh `JavaParser` configured for Java 21.
- Keep the existing schema generation logic otherwise unchanged.
- Do not classify legacy `@Validator` or `@MintingPolicy` as validator sources in the new discovery path; report a migration error if either is used as a real annotation.

### 8. Remove legacy validator aliases

Remove `Validator` and `MintingPolicy` as supported annotations:

- Delete `julc-stdlib` annotation types for `Validator` and `MintingPolicy`.
- Remove them from `JulcCompiler.VALIDATOR_ANNOTATIONS`.
- Remove them from `JulcCompiler.getScriptPurpose`.
- Update compiler error text that suggests `@Validator` or `@MintingPolicy`.
- Update docs, examples, test fixtures, and tests touched by this change to use `@SpendingValidator` and `@MintingValidator`.
- Keep only minimal parser/name checks needed to produce a clear migration error when users still write real `@Validator` or `@MintingPolicy`.

### 9. Do not otherwise change core compiler semantics in this PR

Do not rework these paths beyond the legacy alias removal:

- PIR generation
- type registration or resolution
- validator compilation diagnostics unrelated to legacy alias removal

Those paths already operate on parsed ASTs during normal compilation. Reworking them further would increase regression risk and is not required to fix discovery false positives.

## Tests

Add focused tests before or alongside implementation:

### Gradle plugin

- `bundleJulcSources` ignores a validator whose Javadoc contains `@OnchainLibrary`.
- `bundleJulcSources` ignores a non-library file whose string literal contains `@OnchainLibrary`.
- `bundleJulcSources` still bundles a real top-level `@OnchainLibrary`.
- `bundleJulcSources` still rejects a real nested `@OnchainLibrary`.
- `bundleJulcSources` rejects a top-level type that combines `@OnchainLibrary` with a validator annotation.
- `bundleJulcSources` reports a parse error for a prefiltered but unparsable source instead of silently skipping it.
- `compileJulc` ignores comments mentioning validator annotations.
- `compileJulc` derives minting/withdraw/certify/vote/propose script type from real annotations only.
- `compileJulc` reports a parse error for a prefiltered but unparsable validator candidate instead of silently treating it as a library.
- `compileJulc` reports a migration error for real legacy `@Validator` or `@MintingPolicy` annotations instead of treating them as validators or libraries.
- `compileJulc` rejects a validator type that also declares `@OnchainLibrary`.
- Gradle plugin tests use supported annotations (`@SpendingValidator`, `@MintingValidator`, etc.), not `@Validator` or `@MintingPolicy`.

### Testkit

- Same-project library discovery ignores comment-only or string-only `@OnchainLibrary`.
- Same-project library discovery still finds real top-level `@OnchainLibrary`.
- Same-project library discovery surfaces parse errors for prefiltered candidate sources.
- Same-project library discovery rejects a type that combines `@OnchainLibrary` with a validator annotation.

### CLI

- `ProjectScanner.scan` does not classify a file as a validator when the annotation text only appears in a comment.
- `ProjectScanner.resolveScriptType` uses real annotations and ignores comments.
- `ProjectScanner` surfaces parse errors for prefiltered candidate sources.
- `ProjectScanner` reports a migration error for real legacy `@Validator` or `@MintingPolicy` annotations.
- `ProjectScanner` rejects a type that combines `@OnchainLibrary` with a validator annotation.
- `ProjectScanner` tests use supported annotations.
- `TestDiscovery` ignores comment-only and string-only `@Test`.
- `TestDiscovery` still finds real `@Test` methods.
- `TestDiscovery` surfaces parse errors for prefiltered candidate test sources.

### Blueprint

- Schema extraction ignores comment-only validator annotation text.
- Schema extraction still works for a real validator annotation.
- Schema extraction uses fresh JavaParser configuration rather than `StaticJavaParser`.
- Schema extraction reports a migration error for real legacy `@Validator` or `@MintingPolicy` annotations.
- Blueprint tests use supported annotations.

### Compiler and Stdlib

- `Validator.java` and `MintingPolicy.java` are removed from `julc-stdlib`.
- Core compiler no longer accepts `@Validator` or `@MintingPolicy` as validator annotations.
- Core compiler reports a clear migration error when real legacy annotations are present.
- Core compiler rejects a type that combines `@OnchainLibrary` with a validator annotation, both in the validator source and in library sources.
- Compiler tests and fixtures use supported annotations unless the test specifically verifies the migration error.

### Shared utility

- Imported annotation names work.
- Fully qualified annotation names work.
- Javadoc/comment/string literal annotation text is ignored.
- Nested `@OnchainLibrary` is reported separately from top-level `@OnchainLibrary`.
- Parser failures do not return positive annotation matches.
- Multiple top-level types are handled by filtering annotated top-level types in source order.
- Unrelated annotations on the same type do not affect JuLC role classification.
- `@OnchainLibrary` plus a supported or legacy validator annotation on the same type is reported as a role conflict.
- Legacy `Validator` and `MintingPolicy` are detected for diagnostics but are not classified as supported validator annotations by the new introspector.

## Verification

Run:

```text
./gradlew :julc-compiler:test :julc-gradle-plugin:test :julc-testkit:test :julc-cli:test :julc-blueprint:test :julc-stdlib:test --continue
```

Then run a production-source audit:

```text
rg 'source\.contains\("@|src\.contains\("@|Pattern\.compile\("@' \
  julc-*/src/main/java -g '*.java'
```

Any remaining production match must be either:

- removed,
- converted to a negative-only prefilter with parser confirmation, or
- explicitly documented as out of scope because it is not source annotation classification.

Expected out-of-scope example:

- `MethodDocExtractor` may keep annotation regexes used to strip annotations while formatting documentation. That code is not classifying source files by annotation presence, and comments/Javadocs are part of its intended input.

Finally, publish locally and validate the external example project:

```text
./gradlew publishToMavenLocal -PskipSigning
cd /Users/satya/work/bloxbean/julc-examples
./gradlew clean test --continue --refresh-dependencies
```

## Consequences

Benefits:

- Comments, Javadocs, and string literals no longer affect source classification.
- `bundleJulcSources` produces fewer misleading failures.
- Gradle, CLI, testkit, and blueprint agree on annotation semantics.
- Performance stays reasonable because text scanning remains a negative prefilter.

Tradeoffs:

- Prefiltered files that mention annotation tokens in comments will still be parsed.
- Source discovery now depends on successful parsing for positive classification.
- A shared utility in `julc-compiler` creates a small API surface that should stay narrow and discovery-focused.
- Project-wide scans remain faster than parse-everything because ordinary files without annotation tokens are filtered out before parsing. Files with annotation text in comments still pay the parse cost by design, because correctness requires AST confirmation.

## Non-Goals

- Do not otherwise change the compiler's core annotation resolution semantics beyond removing legacy `@Validator` and `@MintingPolicy` support.
- Do not add support for nested `@OnchainLibrary`; continue rejecting it clearly.
- Do not remove all JavaParser regex fallback usage unrelated to annotation classification.
- Do not change generated project scaffolding in the same PR.
