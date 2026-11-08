# ADR-071: On-chain library meta-annotations and a robust library-source scan

**Status:** Proposed (r14)
**Date:** 2026-11-07 (r2–r13: 2026-11-07; r14: 2026-11-08)
**Label:** `joust`
**Risk class:** medium. It changes which sources the annotation processor and the library resolver hand to the
compiler. A wrong rule could compile a class into a validator that should not be on-chain, or miss one and fail the
build. It does not change how a compiled source lowers.
**Motivation:** [JOUST](https://github.com/bloxbean/joust) (JuLC State Contracts) ADR-0001 §12, items 1–2: two
problems found while building a framework that generates validators from user classes on top of JuLC.

## Context

### 1. Frameworks cannot make their own annotation imply `@OnchainLibrary`

A framework on top of JuLC (JOUST's `@StateContract`) generates a validator that calls the user's class, so that class
must be an on-chain library. JuLC recognises a same-project library only by a literal `@OnchainLibrary` on it
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

Three facts of the current code shape the design:

- **Rounds.** javac calls a processor only from the first round that holds one of the annotations it supports. After
  that, it calls the processor in every later round. JuLC's processor supports the validator annotations and
  `@OnchainLibrary`. A framework user's class carries only `@StateContract`, and the framework generates the validator
  in a later round. So JuLC's processor never sees the round that entered the user's class. It also misses a round
  whose annotations another processor claimed: javac stops offering a round once every annotation in it is claimed.
- **The marker must compile with the library.** JuLC compiles the library's source with its own javac, which must
  resolve the marker annotation. A same-package marker may be used without an import. The resolver follows imports
  and `Class.member` references, not annotations.
- **The pool is keyed by name.** The processor keys same-project libraries by javac's qualified name, while
  `librarySourceFromSimpleName` reads the package from the source text. `LibrarySourceResolver.resolve` re-keys the
  pool by the latter.

### 2. One unreadable library index hides every library source

`LibrarySourceResolver.scanClasspathSources` reads `META-INF/plutus-sources/index.txt` from every entry of the
processor's class loader. Two things go wrong:

- The loop over index URLs sits inside a single `try`, and its `catch (IOException)` swallows the exception silently.
- `URL.openStream()` uses the JDK's shared `JarFile` cache. In a long-lived Gradle daemon, a jar on the processor path
  that is rebuilt between builds leaves a stale cached `JarFile`, and reading from it throws.

JOUST hit this. The stale jar was its own API module, and it threw before julc-stdlib's index was read. The validator
then failed with `Undefined variable: OutputLib`, a misleading error on code that compiles fine on a cold daemon.
The cause took a long time to find, because nothing was reported.

## Goals and non-goals

Goals:

- A framework can make its annotation imply `@OnchainLibrary`, and its users write one annotation.
- That holds for the framework case: the user's class in one round, the validator generated in a later one, whichever
  processor runs first and whatever it claims.
- A broken or stale jar costs only its own sources, and says so.
- Nothing changes for a project that uses `@OnchainLibrary` directly.

Non-goals:

- Recursive meta-annotations (an annotation annotated with a marker).
- Recognising markers in tools that read source text without a class path: the Gradle plugin's `bundleJulcSources`
  and role check (`CompileJulcTask`), the testkit's `SourceDiscovery`, the CLI's `ProjectScanner`, julc-tools'
  `JavacMetadataReading`, and `JavaLibraryProvider`'s export check (`JavacLibraryTypes.requireLibraries`). They
  can't resolve an annotation's meta-annotations, or (the last) export a library only as `@OnchainLibrary` today.
  A framework that ships its users' library sources in a jar bundles them itself, as JOUST does.
- Widening JuLC's java.base subset so a marker may carry `@Documented`, `@Inherited` or `@Repeatable` (Q1).

## Invariants

- **I1. No change for direct users.** For a project in which no annotation interface carries `@OnchainLibrary`, the
  processor and every resolver host give the compiler the same library sources, in the same order, as before. So
  the validators' bytes, diagnostics and warnings are unchanged. There are two exceptions, each a build that used to
  fail and now compiles:
  - A direct library that the processor never collected, such as one found only on `-sourcepath`, when a validator
    imports it. It used to fail with `JULC0085` (D1).
  - A library whose package line JuLC's text reading misreads (`package q ;`) and that a validator imports
    explicitly (D1). The source javac checked is the one compiled.
- **I2. The rule is one rule.** A class is an on-chain library if it carries `@OnchainLibrary`, or an annotation whose
  annotation interface carries `@OnchainLibrary`. One level, no recursion. `JavacFacts.libraryMarker` implements it.
  The processor, the compiler's JULC0134 check, the nested-class check and `CallTargets`' library-call check all call
  it.
- **I3. Same bytes.** A library marked through a marker, and imported, compiles to the same script as the same class
  marked `@OnchainLibrary`, at every optimization level and with source maps: it takes the same place in the library
  order (r10). The exception is a class that a jar on the processor path also ships: the jar's source is compiled
  for a marked class, as before this ADR, with a note (r14). A collected `@OnchainLibrary` class still beats the
  jar's copy, as before.
- **I4. Only libraries are added.** Nothing new reaches the compiler except:
  - a top-level class that is a library by I2 and whose source is in this compilation;
  - a marker of the library pool: javac's class of that name is an annotation interface carrying `@OnchainLibrary`
    (the processor only, r8).
  A class that is merely named in a comment or a string is never added. javac is asked only about names it has
  already resolved as classes: imports the pool lacks and annotation names (r11).
- **I5. Locality.** What a validator is compiled with depends only on its own references, the `@OnchainLibrary`
  classes collected in rounds and the class path's sources. It never depends on which other validators are in the
  compilation, or on which were compiled first.
- **I6. Claims unchanged.** The processor supports and claims the same annotations as before. A framework's processor
  receives its annotation whatever the processor order.
- **I7. The scan isolates failures and never fails the build.** An index, source or directory listing that cannot be
  read costs only itself and is reported as a note. The scan's result is otherwise as before, `putIfAbsent` order
  included.

## Decision

### D1. Meta-annotated on-chain libraries

A class is an on-chain library if it carries `@OnchainLibrary` **directly**, or carries an annotation whose
annotation interface carries `@OnchainLibrary` (**one level** of meta-annotation, no recursion). `JavacFacts.libraryMarker(Element)`
returns that annotation, preferring `@OnchainLibrary` itself. It reads annotation mirrors only, so the annotation
processor (on the build's javac) and the compiler (on JuLC's javac) share it.

Where it applies:

- **Round collection is unchanged.** In each round it is called, `JulcAnnotationProcessor` collects the round's
  `@OnchainLibrary` elements, as before. r2 also collected the round's marked classes. r3 drops that: admission
  finds every marked class a validator can name, and collecting them per round made two things depend on whether
  JuLC's processor saw the class's round:
  - whether an unreferenced nested marked class was rejected;
  - whether a class-path source of the same name won (review of r2, design P2-1, P3-1).
- **Admission by import (r2, r11).** `LibrarySourceResolver.resolve(source, pool, host)` asks the host about a name
  the source **imports** (a single-type import) when the pool lacks it. Nothing else is asked about. A bare name
  (`Counter.ok()`), the source's own package, and a wildcard import (`import app.*`) resolve in the pool only, exactly
  as before this ADR.
  - **Why imports only (r11).** javac resolves every single-type import while entering the sources, before any
    processor runs. So an imported name is certainly a class, and asking about it reads nothing new.
  - **Why not bare names or wildcards.** r2–r10 also asked about bare names and wildcard packages. Whether a bare name
    is a class or a variable is decided only by javac's attribution, which comes after the processor. Every
    approximation found a new case that changed the libraries compiled, their order, or whether a valid build
    passed. That held whether the approximation worked by text, or by deferring and relaxing the resolution
    (reviews R3-1 to R9-1). Guessing is withdrawn.
  - **The cost.** A validator or library reaches a framework-marked class through a single-type import, even from
    its own package (`import app.Counter;`, legal Java). A framework that generates validators writes that import. A
    directly annotated `@OnchainLibrary` class, the stdlib and library jars are unaffected: they are in the pool, and
    every form resolves them as before. A marked class in the default package cannot be imported, so it cannot be
    reached.
  - **The hint (r12).** When JuLC's compilation fails on a name that is a marked class or a marker of this
    compilation, the processor appends the import to add, for example `add import lib.MetaMath;`. It looks in the
    package and wildcard-imported packages of each source compiled. The check runs only after the compilation has
    failed, so it costs a passing build nothing.

  The processor answers with the class javac names, if it is a top-level library of this compilation (I2,
  `Trees.getPath` non-null). The source is keyed by javac's qualified name, not the source's text.

  For any class of this compilation it is asked about, library or not, the processor rejects the nested library
  classes of every top-level class in that class's file (r3, r4). That includes a nested class imported by its own
  name (`import app.Holder.Inner`).

  A library whose marker has no source JuLC can read is reported, at the library, with the remedy (r3, see the
  marker contract). This covers a marker that is neither in this compilation nor in a jar's
  `META-INF/plutus-sources`.

  What the host admits is not added to the pool, so a validator's libraries never depend on another validator's (I5).
  The resolver follows admitted sources like any other, in the same single pass and in first-named order. So a
  library that imports a marked library is found, and the library order is what it would be if the class were a
  pool entry (I3). Markers, and what only they reach (a `Class<?>` default's class, say), are resolved after the
  libraries. So a marker never moves a library ahead of the place its callers give it (r12).

  **A class-path copy of a project library (r14).** Sometimes a jar on the processor path ships a source for a class
  that this compilation also declares as an on-chain library, typically a stale jar of the project's own module.
  - The pool's entry, the jar's source, is compiled, exactly as before this ADR. The host is never asked about a
    name the pool holds.
  - The processor then prints a note, once per class: "JuLC compiles X from the on-chain library source … on the
    annotation processor path, not from this project's X. If that jar is a stale copy of this project, remove it
    from the processor path." A note never fails `-Werror`.
  - r3–r13 made the project's class replace the jar's. Every way of choosing when to replace (on any path, on
    imports only, holding the choice for the whole resolution) let a jar copy reached through a variable's name, or
    an import inside a superseded jar source, change the libraries or fail a valid build (reviews R10-1 to R12-2).
    The maintainer chose `main`'s behaviour and a note.

  This is what makes the framework case work. javac never calls the processor in the round that entered the user's
  class, but the validator that imports it resolves it.
- **Markers are references (r2, r8).** The resolver treats every annotation a source uses as a reference, whatever its
  spelling (`@mark` too). It is resolved by its qualified name, a single-type import or the source's package, in
  Java's order. It is never resolved through a wildcard import (r9): a plain annotation of that name in the source's
  package, which the pool need not hold, would be the one javac binds. Nor is it resolved through the pool-wide
  fallback, and it gives no diagnostic. It is added only if the host says javac's
  class of that name is a marker: an annotation interface carrying `@OnchainLibrary` (r8). An annotation whose
  simple name the source declares as a type itself (a nested `@interface`, say) names that type and is not
  followed (r10).
  - **The host decides by javac's model** (`Elements.getTypeElement`, `JavacFacts.libraryMarker`), not by reading the
    marker's source. r4–r7 read the annotation's identity from text (imports, qualified names, same-package
    shadowing), and each version missed a way Java binds a name.
  - **Only the annotation processor answers.** For the CLI, testkit and tools hosts no annotation is followed, exactly
    as before, so a plain annotation in the CLI's pool can never be pulled in (I1, non-goals).
  - **Where markers come from:** a same-project marker is collected like any `@OnchainLibrary` class, since it carries
    the annotation itself. A class-path marker is a library source in its jar's `META-INF/plutus-sources`.

  So the marker's source is compiled with each library and validator that uses it, by an import, a qualified name or
  from its own package; never through a wildcard import. All of a class's
  markers come along, and so do the libraries a marker's member defaults read.
- **Conflict check.** `JavacDiscovery.rejectRoleConflict` uses the rule, so a meta-annotated library that is also a
  validator is JULC0134. The message names the marker: "must not combine @StateLike (which implies @OnchainLibrary)
  with validator annotation(s): @SpendingValidator". For `@OnchainLibrary` itself the text is unchanged.
- **Nested classes.** A nested `@OnchainLibrary` class is rejected by round collection, as before. A nested class
  that is a library through a marker is rejected in two cases:
  - when a validator's resolution asks about any class of its file;
  - when round collection collects a library of its file (r5), for example a marked class nested in a direct
    library.

  For `@OnchainLibrary` the message is unchanged. For a marker it reads "@StateLike makes Inner an on-chain
  library, and an on-chain library must be a top-level class. Move Inner into a file of its own."

The **marker contract**: JuLC compiles the marker's source, so the marker must be a top-level annotation interface
that JuLC can compile.
- Members: primitives, `String` or `Class<?>`, with constant defaults.
  - A default may read a constant of an on-chain library.
  - A `Class<?>` default that names an on-chain library (`Limits.class`) must import it, even from the marker's own
    package. The resolver follows imports and member accesses, not class literals.
- Its own annotations: only `@Retention`, `@Target` and `@OnchainLibrary`.
- A marker in a jar ships its source in `META-INF/plutus-sources`. The marker carries `@OnchainLibrary` itself, so
  the Gradle plugin's `bundleJulcSources` bundles it. When the source is missing, the processor says so for every
  marker on the class, on both the admission and the collection path (r3, r5).
- Arrays, enum members, and `@Documented`, `@Inherited` or `@Repeatable` fail with the compiler's usual diagnostic.
  The library developer guide states this contract.

### D2. The library-source scan reads each entry independently, and reports what it skips

`scanClasspathSources` reads each `index.txt` and each indexed source independently:

- **Failures are isolated.** A failure skips only that entry. That covers `IOException`, and also
  `IllegalArgumentException` from building an entry's URL (r2).
- **Failures are reported.** Each failure goes to a new overload, `scanClasspathSources(ClassLoader, Consumer<String>
  problems)`. That includes a directory that cannot be listed (r2). The existing signature delegates with a no-op
  consumer.
- **No URL cache.** Resources are opened with `URLConnection.setUseCaches(false)`, so a rebuilt jar is read from disk,
  not from a stale cached `JarFile`. The connection closes the `JarFile` when the stream closes.

`JulcAnnotationProcessor` scans once: when the first validator needs it, or earlier, when a library carries a marker
whose source is neither in this compilation nor yet known (D1's missing-marker check). It prints each problem as a
compiler **note**
naming the URL and the cause (r2). A note is printed like a warning, but `-Werror` does not turn it into an error. So
a build that passed before still passes (I1), and the problem is still printed just above whatever error it causes.

## Alternatives considered

- **Discover libraries from the validator's references by regex (r1).** Rejected in r2. It read imports and
  `X.member` by pattern, unlike the resolver. So it missed wildcard imports, two imports on one line and qualified
  references. It also stopped at a library already known, so a library reaching a marked library was missed. And it
  added what it found to the pool, so a validator compiled or failed depending on other validators (review of r1, F2
  and F3). Admission by exact name (D1) is that idea driven by the resolver's own reading.
- **Find marked classes by bare names and wildcard imports too (r2–r10).** Rejected in r11. javac decides whether a
  bare name is a class or a variable only when it attributes, after the processor. Each approximation made a valid
  build fail, reordered libraries or picked a different class from javac's. That held whether it read the source's
  scopes from text (r4, r5), or deferred and relaxed the resolution of guessed classes (r7–r10). Imports cost a
  framework one line per generated validator.
- **Support `"*"` so javac calls the processor in every round.** Rejected. javac stops offering a round to processors
  once every annotation in it is claimed. So a framework that claims its own annotation and runs first still hides the
  round. And claiming under `"*"` claims every annotation of a round, so the processor would have to stop claiming,
  which changes what other processors receive and what `-Xlint:processing` reports (against I6).
- **A processor option listing marker annotations** (`-Ajulc.libraryMarkers=...`). Rejected. Every user's build would
  need configuring, and it still has the round problem.
- **Give JuLC's javac the marker's class file instead of its source.** Not chosen now. It would lift the marker
  contract, but it needs a class-path channel into JuLC's javac, which ADR-068 keeps to on-chain sources and the
  digest-pinned platform blob. Recorded as Q1.
- **Recursive meta-annotations.** Rejected. It is not needed, and it is harder to read.
- **Report scan problems as warnings (r1).** Rejected in r2. A jar on the processor path with a broken index would
  fail a `-Werror` build that passes today (review of r1, F8).

## Affected stages and modules

- **julc-annotation-processor** (`JulcAnnotationProcessor`): admission by exact name, the pool's precedence, the
  nested check, the missing-marker message, the lazy scan and notes.
- **julc-compiler:**
  - `LibrarySourceResolver`: annotation references, the admission hook, the scan, and
    `librarySourceFromQualifiedName`;
  - `JavacFacts.libraryMarker`;
  - `JavacDiscovery` (JULC0134);
  - `CallTargets` (the library-call check);
  - `diagnostics.json`, with the generated `DiagnosticCodes` (the JULC0134 template takes the marker as `{2}`).
- **Docs:** the library developer guide (a framework's own annotation), and troubleshooting (the skipped-source note).
- Lowering, PIR, UPLC and every pass are untouched.

## Compatibility

- **Direct `@OnchainLibrary` projects: no change (I1).** Checked on:
  - the external julc-examples (identical `META-INF/plutus`, diagnostics and warnings);
  - the processor test suite;
  - the full build.
- **Annotation references** are followed only for the annotation processor, which asks javac whether a name is a
  marker (r8). The CLI, testkit and tools hosts resolve exactly as before.
- **JULC0134** keeps its text for `@OnchainLibrary`. The template now has a third argument, which only
  `JavacDiscovery` fills.
- **The scan** reports through notes. `-Werror` builds behave as before.

## Risks

- **Admission asks javac for names** (`Elements.getTypeElement`), but only names javac has already resolved: imports
  the pool lacks and annotation names. After resolution, the jar-copy note also asks about the names of the
  class-path libraries compiled. None of this reads a source javac would not read.
  - An annotation inside a method body (on a local variable) is resolved by javac only when it attributes. If its
    name matches an unrelated source on an explicit `-sourcepath`, javac may read that source early and warn. Gradle's
    default empty source path never reads such sources.
- **A framework's generated validators must import the marked classes they call** (D1). A bare same-package name or a
  wildcard import does not find one. The outcome is the same as before this ADR:
  - usually the build fails with `JULC0085`, and the processor names the import to add;
  - if a library of the same simple name is in the pool (a wildcard-imported one, or a jar's copy of the class), that
    library compiles instead. For a jar's copy the processor prints a note (r14).
  The import avoids both.
- **The marker contract** constrains framework authors (Q1). A marker JuLC cannot compile fails every library that
  uses it, with the compiler's usual diagnostic at the marker.
- **Tools that read source text** still see only `@OnchainLibrary` (non-goals). A testkit test in which one marked class
  calls another needs `@OnchainLibrary` on the callee, or a source list given explicitly.

## Implementation milestones

- **M1 (r1, `566ea845`):** D1 with regex discovery, and D2 with warnings.
- **M2 (r2–r12):**
  - admission by import through the resolver (r2, imports only from r11), replacing regex discovery; round
    collection unchanged (r3);
  - markers as references, decided by javac in the processor only (r8), never through a wildcard import (r9), never
    a type the source declares itself (r10);
  - `JavacFacts.libraryMarker` as the one rule;
  - the JULC0134 and nested-class messages;
  - a note when a class-path copy of a project library is compiled (r14; r3–r13 replaced the copy);
  - markers resolved after the libraries, and the import hint (r12);
  - the missing-marker message (r3, r5);
  - scan notes, `IllegalArgumentException` isolation and directory-listing reports;
  - docs.
  - Exit: the tests below are green, the full build is green, the julc-examples outputs match the merge base, and
    JOUST builds against it.

## Verification

Processor tests (`JulcAnnotationProcessorTest`). The framework cases use a stand-in framework processor and a
class-path marker whose source ships in `META-INF/plutus-sources`.

| Test | What it checks |
|---|---|
| `metaAnnotatedLibraryIsCompiledIntoTheValidator` | the same-compilation, same-package marker |
| `metaAnnotatedLibraryIsDiscoveredForAValidatorGeneratedInALaterRound` | the framework case, with the framework claiming its annotation and running first |
| `aWildcardImportDoesNotFindAMarkedClass` | a framework validator must import, r11 |
| `aBareSamePackageCallToAMarkedClassFailsWithTheImportToAdd` | the import rule and its hint; with the import it compiles, r12 |
| `aLibraryCallingAMarkedLibraryImportsIt` | the rule for libraries, r12 |
| `aMarkerReachedThroughAWildcardImportFailsWithTheImportToAdd` | r12 |
| `aGeneratedLibraryReachesAMetaAnnotatedLibraryOfAnEarlierRound` | transitive, through a direct library |
| `aRoundWithAFrameworkAnnotationIsLeftToTheFramework` | JuLC runs first, and the framework still receives its annotation (I6) |
| `aNestedMetaAnnotatedLibraryIsRejectedWhenTheValidatorIsGeneratedInALaterRound` | |
| `rejectsNestedMetaAnnotatedLibrary` | |
| `aNestedDirectLibraryKeepsItsMessage` | I1 |
| `aLibraryWithTwoMarkersCompilesWithBoth` | |
| `aMarkerDefaultThatReadsALibraryConstantCompiles` | |
| `aValidatorMarkedThroughASamePackageMarkerIsJulc0134` | |
| `aMetaAnnotatedLibraryCompilesToTheSameScriptAsADirectOne` | I3 |
| `librariesWhosePackageLineHasASpaceStillResolve` | the r1 regression (`package q ;`) |
| `aLibraryOnlyCompilationClaimsItsAnnotationsAsBefore` | `-Xlint:processing -Werror`, I6 |
| `aClassPathCopyOfAMarkedClassIsCompiledAsBeforeWithANote` | r14 |
| `aMarkerWithoutASourceIsReportedWithTheRemedy` | r3 |
| `aLibraryOnlyOnTheSourcePathIsAdmitted` | the I1 exception |
| `aValidatorCompilesAloneAsItDoesWithOthers` | I5 |
| `aLowercaseMarkerIsFollowed` | r4 |
| `aMarkerClassDefaultThatImportsALibraryCompiles` | the marker contract |
| `aFieldQualifierNeverReadsTheSourcePath` | `-Werror`, r4 |
| `aMarkedLibraryKeepsItsPlaceInTheLibraryOrder` | I3 at `none` and with source maps, r10 |
| `aNestedLibraryImportedByItsOwnNameIsRejected` | r4 |
| `aNestedLibraryInAnotherClassOfTheSameFileIsRejected` | r4 |
| `aVariableInAnotherMethodDoesNotHideAMarkedClass` | an imported class, with a parameter and a block local of its name |
| `anUnusedClassPathLibraryNeverReadsTheSourcePath` | `-Werror`, r5 |
| `aMarkedClassNestedInADirectLibraryIsRejected` | r5 |
| `everyMarkerWithoutASourceIsReported` | r5 |

`rejectsNestedMetaAnnotatedLibrary` covers an unmarked outer class that a validator names (r3).

Resolver tests (`LibrarySourceResolverTest`):

| Test | What it checks |
|---|---|
| `resolve_followsAMarkerAnnotationOfTheLibrarysOwnPackage` | |
| `resolve_annotationReferencesNameOnlyAnnotationInterfacesAndNeverFallBack` | I4 |
| `resolve_asksTheCompilationOnlyAboutImportedNames` | only imports are asked about; admitted sources are not pooled, r11 |
| `resolve_followsOnlyAnnotationsTheHostCallsMarkers` | the CLI's pool; lookalikes; other hosts follow none, r8 |
| `resolve_aMarkersDependenciesNeverReorderTheLibraries` | I3 with a marker's `Class<?>` default, r12 |
| `resolve_anAnnotationIsNeverFollowedThroughAWildcardImport` | r9 |
| `resolve_keepsTheOrderTheSourceNamesLibrariesIn` | r10 |
| `resolve_anAnnotationNamingTheSourcesOwnTypeIsNotFollowed` | r10 |
| `resolve_aPooledNameIsNeverAskedAbout` | an imported name the pool holds resolves as before, r14 |
| `scanClasspathSources_skipsAnUnreadableEntryReportsItAndReadsTheRest` | |
| `scanClasspathSources_aMalformedEntryUrlCostsOnlyThatEntry` | |
| `scanClasspathSources_readsAJarRewrittenAfterAnEarlierScan` | fails with the URL cache on |

Compiler test (`DiagnosticTextTest.aClassIsALibraryOrAValidatorEvenWhenMarkedThroughAMetaAnnotation`): JULC0134 and
the marker in its text.

Regression evidence:
- The external julc-examples compiled with the processor at the merge base and at the head, then compared (I1).
  - Plain, `-Werror`, `-Xlint:processing -Werror` and `-Xlint:all`: `META-INF/plutus` and javac's output were
    byte-identical.
  - Each validator's resolved library list through the CLI host was identical.
- The full build.
- JOUST built against the head (`-PuseLocalJulc`).

## Open questions

- **Q1. The marker contract.** Should JuLC's javac see the marker's class file instead of its source? That would
  allow any member type and any meta-annotation. Lean: only if a framework needs it; it touches ADR-068's class-path
  gate.
- **Q2. Tools that read source text.** Should `bundleJulcSources`, `SourceDiscovery` and `ProjectScanner` learn the
  marker rule (for example from a list of marker names)? Lean: when a framework asks; JOUST bundles its own sources.

## Revision history

- **r1** (2026-11-07): D1 (collection and regex discovery), D2 (warnings). PR #311 at `566ea845`.
- **r2** (2026-11-07, responds to the review of `566ea845` by Codex, a design reviewer and a regression reviewer):
  - **Regex discovery** is replaced by admission by exact name through the resolver (F2, F3, F6).
  - **Markers are annotation references** (F4, F5, F7). The processor keeps `resolve` and its pool re-keying, which
    removes the `package q ;` regression (F1).
  - **The rule lives in `JavacFacts.libraryMarker`** (F10). The nested message is unchanged for `@OnchainLibrary`
    (F11), and JULC0134 names the marker (F7).
  - **The scan** reports through notes rather than warnings (F8). It isolates `IllegalArgumentException` (F12) and
    reports unlistable directories (F13).
  - **The ADR** gains goals, invariants, alternatives, affected modules, compatibility, risks, milestones and open
    questions, and lists the source-text tools as non-goals (F8, F9).
  - Style (F14). Tests for each finding (F15).
- **r3** (2026-11-07, responds to the review of r2, `5230d1df`, by Codex, a design reviewer and a regression
  reviewer):
  - **Round collection** goes back to `@OnchainLibrary` only. Admission is the one way a marked class is found
    (design P3-1).
  - **The compilation's class** wins over a class-path source of the same name (design P2-1).
  - **Nested library classes** are rejected under any top-level class of the compilation asked about (design P2-2).
  - **A missing marker source** is reported with the remedy (design P2-3, F4).
  - **JULC0134** is changed in `diagnostics.json`, its source of truth (regression P1-1).
  - **The host** is asked only for Java names (regression P3-1).
  - **`CallTargets`** uses the one rule (regression P3-3).
  - **I1, Compatibility and Risks** are made precise: the CLI host, `package q ;` and the class-path parse
    (regression P3-2, design P3-6).
  - **The parser** reads annotation interfaces (design P3-5).
  - **Tests and docs** (design P3-3, P3-4, P3-7; regression P3-4 to P3-6).
- **r4** (2026-11-07, responds to Codex's review of r2, `5230d1df`; R2-1 and R2-2 were already fixed in r3):
  - **Only markers are followed.** An annotation reference resolves only to a marker, never to a plain annotation of
    the CLI's pool (R2-3).
  - **Fields and variables are never asked about** (R2-4).
  - **Nested library classes** are rejected when asked about by their own name, and in every top-level class of the
    file (R2-5).
  - **An annotation of any spelling** is a reference (R2-6).
  - **`Class<?>` defaults** must import the library they name; the marker contract says so (R2-7).
  - **Risks** call the implicit-compilation message a warning (Codex's ADR notes).
- **r5** (2026-11-07, responds to Codex's review of r4, `1a2d4a96`; the design and regression reviewers approved r4):
  - **Guessing is scope-aware.** A variable hides a bare name only where it is in scope, so a parameter in another
    method no longer selects a wrong library (R3-1).
  - **Static on-demand imports:** no bare name is guessed under one (R3-3).
  - **The compilation's class beats a class-path entry** only when the source names that entry exactly; unnamed
    entries are never looked up (R3-2).
  - **A marker** must carry JuLC's own `@OnchainLibrary` (R3-4).
  - **Round collection rejects marked nested classes** in a direct library's file (R3-5).
  - **Every marker without a source** is reported, on both paths (design P3-A).
- **r6** (2026-11-07, responds to Codex's review of r5, `a34d087d`; the regression reviewer approved r5):
  - **Bare qualifiers are always asked about.** The textual variable model of r4 and r5 is withdrawn: block-scoped
    locals and static wildcard imports could still make it compile a different class from javac's (R4-2, R4-3).
  - **One source per name.** Every path to a class-path entry goes through the same choice, so a jar source reached
    first never blocks the compilation's class (R4-1).
  - **An explicit lookalike import** of `OnchainLibrary` wins over a wildcard import of JuLC's annotations (R4-4).
- **r7** (2026-11-07, responds to Codex's review of r6, `0ac17118`, which resolved R4-1..R4-4 and accepted the
  `-sourcepath` trade-off):
  - **Wildcard candidates the compilation admits** are all handed to javac rather than reported as an ambiguity by
    the resolver, so a variable named like two such classes compiles as before (R5-1).
  - **A pool type named `OnchainLibrary`** in the marker's package shadows a wildcard import of JuLC's annotations
    (R5-2).
  - **ADR wording:** I1, I4, D1, D2 and Risks.
- **r8** (2026-11-07, responds to Codex's review of r7, `6b4817ea`, where the R4 fixes held and no wrong script was
  found):
  - **Markers are decided by javac.** Whether an annotation is a marker is the processor's answer from javac's model,
    and no other host follows annotations. The textual identity check of r4–r7 is withdrawn, and with it R5-2's
    testkit and secondary-type variants.
  - **Speculative wildcard candidates** and what they reach are resolved in a separate, lenient pass, so an ambiguity
    inside an unused candidate no longer fails the build (R6-1).
- **r9** (2026-11-07, responds to Codex's review of r8, `ae752091`, where R5-2, R6-1 and every earlier fix held):
  - **The speculative pass takes every source the compilation supplies,** not only several wildcard candidates: a
    single guessed class, and a same-package one, too (R7-1).
  - **Annotations are never followed through a wildcard import,** so a plain same-package annotation keeps its Java
    precedence (R7-2).
- **r10** (2026-11-07, responds to the regression reviewer's and Codex's reviews of r9, `c07548e7`):
  - **Library order is kept.** One pass in first-named order. r9 reordered marked libraries, which changed the
    script at `none` and with source maps (regression reviewer P2; Codex R8-1).
  - **An annotation naming the source's own type** is not followed (R8-2).
- **r11** (2026-11-07, after Codex's review of r10, `d2b99966`, found R9-1, and the maintainer's decision):
  - **Admission by import only.** The host is asked only about a source's single-type imports. Bare names and
    wildcard imports resolve in the pool as before this ADR, so a framework's generated validator imports the marked
    classes it calls.
  - **Removed:** the guessed-name machinery of r6–r10: bare-qualifier guessing, lenient and speculative resolution,
    and per-source leniency. R5-1 to R9-1 do not arise, since nothing is guessed.
  - **Kept:** the compilation's class over a jar entry, javac's word on markers, and annotations never followed
    through wildcards or to the source's own types.
- **r12** (2026-11-07, responds to Codex's and the design reviewer's reviews of r11, `60d3a705`; the regression
  reviewer approved r11):
  - **The compilation's class replaces a jar entry only for an imported name.** A jar entry reached by a bare name
    or a wildcard could be a variable's, and asking about it reintroduced a guess (R10-1).
  - **Markers, and what only they reach, are resolved after the libraries,** so a marker's `Class<?>` default never
    reorders the libraries (R10-2).
  - **A failed compilation names the import to add** for a marked class or marker of the compilation. The guide says
    libraries import too, and that the default package cannot be imported from (design reviewer P2, P3).
- **r13** (2026-11-07, responds to Codex's review of r12, `9a412caa`, R10-1 and R10-2 resolved, and the design and
  regression reviewers' approvals of r12):
  - **An import's choice holds for the whole resolution.** When an import finds the compilation's class in place of a
    jar entry, the resolution runs again with that class in the pool. A jar copy reached earlier by a variable's name
    no longer wins (R11-1).
  - **The marker hint is given only for a wildcard-imported package,** not for the marker's own package (design P3-1).
  - **Risks state that an unimported marked class can silently compile a same-named pool library,** as before this ADR
    (design P3-2, Codex P2).
- **r14** (2026-11-08, after Codex's review of r13, `aac43d63`, found R12-1 and R12-2 in the jar-replacement rule,
  and the maintainer's decision):
  - **No replacement.** A class-path copy of a project library is compiled exactly as before this ADR, and the
    processor notes it.
  - **Removed:** the replacement rule (r3, r6, r12, r13) and its rerun loop. The host is asked only about imported
    names the pool lacks.
