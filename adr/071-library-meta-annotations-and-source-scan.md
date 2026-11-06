# ADR-071: On-chain library meta-annotations and a robust library-source scan

**Status:** Proposed
**Date:** 2026-11-07
**Label:** `joust`
**Motivation:** [JOUST](https://github.com/bloxbean/joust) (JuLC State Contracts) ADR-0001 §12, items 1–2: two
problems found while building a framework that generates validators from user classes on top of JuLC.

## Context

### 1. Frameworks cannot make their own annotation imply `@OnchainLibrary`

A framework on top of JuLC (JOUST's `@StateContract`) generates a validator that calls the user's class, so that class
must be an on-chain library. Today JuLC recognises a same-project library only by a literal `@OnchainLibrary` on it
(`JulcAnnotationProcessor.collectSameProjectLibraries`). Every JOUST contract therefore carries two annotations:

```java
@StateContract
@OnchainLibrary            // only so JuLC compiles it; @StateContract already says it is one
public class Counter { ... }
```

Java's usual answer is a **meta-annotation**: the framework annotates its own annotation once, and every use inherits
the meaning.

```java
@OnchainLibrary
public @interface StateContract { ... }
```

`@OnchainLibrary` already allows this. Its target is `ElementType.TYPE`, which includes annotation interfaces, and its
retention is `RUNTIME`, so the meta-annotation is visible on annotation types read from class files. But nothing in
JuLC looks for it.

### 2. One unreadable library index hides every library source

`LibrarySourceResolver.scanClasspathSources` reads `META-INF/plutus-sources/index.txt` from every entry of the
processor's class loader. Two things go wrong:
- The loop over index URLs sits inside a single `try`, and its `catch (IOException)` swallows the exception silently.
- `URL.openStream()` uses the JDK's shared `JarFile` cache. In a long-lived Gradle daemon, a jar on the processor path
  that is rebuilt between builds leaves a stale cached `JarFile`, and reading from it throws.

JOUST hit this. The stale jar was its own API module, and it threw before julc-stdlib's index was read. The validator
then failed with `Undefined variable: OutputLib`, a misleading error on code that compiles fine on a cold daemon.
The cause took a long time to find, because nothing was reported.

## Decision

### D1. Meta-annotated on-chain libraries

A class is an on-chain library if it carries `@OnchainLibrary` **directly**, or carries an annotation whose
annotation interface carries `@OnchainLibrary` (**one level** of meta-annotation, no recursion).

Where it applies:
- **Round collection.** `JulcAnnotationProcessor` collects same-project libraries from each round's root elements by
  this rule.
- **On-demand discovery.** javac invokes a processor only in rounds that hold one of its own annotations. A framework's
  user class carries only the framework annotation, and the validator calling it is typically generated in a *later*
  round. So JuLC's processor never sees the class as a root element. When compiling a validator, the processor
  therefore also follows the validator's references (imports and same-package names) to classes of the current
  compilation (`Elements.getTypeElement` plus `Trees.getPath`), admits those that are on-chain libraries by this rule,
  and repeats for their references.
- **Marker sources.** JuLC's own javac must resolve the marker annotation itself, which a same-package library may use
  without an import. For each resolved library that is a library through a meta-annotation, the processor adds the
  marker's source, from this compilation or from the class-path library sources. A framework whose marker lives in a
  jar ships its source in `META-INF/plutus-sources`, like any on-chain library source. `LibrarySourceResolver`'s
  reference rules are unchanged (ADR-068 gates).
- **Unchanged.** Library sources from the class path (`META-INF/plutus-sources`) are bundled sources and need no
  marker.
- **Conflict check.** `JavacFacts.isOnchainLibrary(Element)` implements the rule once.
  `JavacDiscovery.rejectRoleConflict` uses it, so a meta-annotated library that is also a validator is rejected
  exactly like a directly annotated one (JULC0134).
- **Nested classes.** The rule is unchanged: a meta-annotated nested class is rejected like `@OnchainLibrary` on a
  nested class, and the message names the marker.

Out of scope:
- The Gradle plugin's `bundleJulcSources`, and `SourceDiscovery` in the testkit, still recognise `@OnchainLibrary`
  by source text. They can't resolve an annotation's meta-annotations without a classpath. A framework that ships
  library sources in a jar bundles them itself, as JOUST does.

### D2. The library-source scan reads each entry independently, and reports what it skips

`scanClasspathSources` reads each `index.txt` and each indexed source independently:
- A failure skips only that entry.
- Each failure is reported through a new overload `scanClasspathSources(ClassLoader, Consumer<String> warnings)`. The
  existing signature delegates with a no-op consumer.
- Resources are opened with `URLConnection.setUseCaches(false)`, so a rebuilt jar is read from disk, not from a stale
  cached `JarFile`.

`JulcAnnotationProcessor` passes a consumer that prints each problem as a compiler **warning**, naming the URL and the
cause. The scan stays lenient (a broken jar of someone else's should not fail the build) but no longer stays silent.

## Consequences

- Frameworks such as JOUST can make their annotation imply `@OnchainLibrary`. User classes carry one annotation.
- A stale or broken library jar costs only its own sources, and shows up as a warning instead of an unrelated
  "Undefined variable" error.
- No change for code using `@OnchainLibrary` directly. No change to compilation output.

## Tests

- **Processor (`metaAnnotatedLibraryIsCompiledIntoTheValidator`):** a library marked through a same-compilation,
  same-package marker is compiled into the validator calling it.
- **Processor (`metaAnnotatedLibraryIsDiscoveredForAValidatorGeneratedInALaterRound`):** the framework case. The
  marker comes from the class path, with its source shipped in `META-INF/plutus-sources`, and a second processor
  generates the validator in round 2. JuLC's processor does not run in round 1.
- **Processor (`rejectsNestedMetaAnnotatedLibrary`):** a meta-annotated nested class is rejected, naming the marker.
- **Compiler (`aClassIsALibraryOrAValidatorEvenWhenMarkedThroughAMetaAnnotation`):** a meta-annotated library that is
  also a validator is JULC0134.
- **Scan (`scanClasspathSources_skipsAnUnreadableEntryWithAWarningAndReadsTheRest`):** an index listing a missing
  source costs only that source, is reported, and the other jar is still read.
- **Scan (`scanClasspathSources_readsAJarRewrittenAfterAnEarlierScan`):** a jar rewritten after an earlier scan is read
  from disk. This test fails with the URL cache enabled (checked).
