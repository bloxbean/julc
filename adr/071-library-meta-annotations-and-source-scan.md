# ADR-071: On-chain library meta-annotations and a robust library-source scan

**Status:** Proposed (r8)
**Date:** 2026-11-07 (r2–r8: 2026-11-07)
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
  the validators' bytes, diagnostics and warnings are unchanged, in the default Gradle and Maven setups. The Risks
  section covers an explicit `-sourcepath` that holds unrelated sources. There are two exceptions, each a build
  that used to fail and now compiles:
  - A direct library that the processor never collected, such as one found only on `-sourcepath`. It used to fail
    with `JULC0085` (D1).
  - A library whose package line JuLC's text reading misreads (`package q ;`) and that a validator imports
    explicitly (D1). The source javac checked is the one compiled.
- **I2. The rule is one rule.** A class is an on-chain library if it carries `@OnchainLibrary`, or an annotation whose
  annotation interface carries `@OnchainLibrary`. One level, no recursion. `JavacFacts.libraryMarker` implements it.
  The processor, the compiler's JULC0134 check, the nested-class check and `CallTargets`' library-call check all call
  it.
- **I3. Same bytes.** A library marked through a marker compiles to the same script as the same class marked
  `@OnchainLibrary`. That includes a class-path source of the same name: the compilation's class wins in both cases
  (r3).
- **I4. Only libraries are added.** Nothing new reaches the compiler except:
  - a top-level class that is a library by I2 and whose source is in this compilation;
  - a marker of the library pool: javac's class of that name is an annotation interface carrying `@OnchainLibrary`
    (the processor only, r8).
  A class that is merely named in a comment or a string is never added. javac is never asked about a field named
  through its class (`BigInteger.ZERO`, r4). It may be asked about a bare name that turns out to be a variable
  (r6). A class found that way is handed to JuLC's javac, which binds the variable and never uses the class.
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
- **Admission by exact name (r2).** `LibrarySourceResolver.resolve(source, pool, classesOfTheCompilation)` asks the
  host for a name the pool lacks. Only these names are asked:
  - a qualified name;
  - an import;
  - the source's own package plus the simple name;
  - each wildcard-imported package plus the simple name, when the pool has no wildcard match.
  The pool-wide simple-name fallback never asks.

  A name built from the source's package or a wildcard package is asked about only when the source writes it as a
  bare qualifier, as in `Counter.ok()` (r6). That is the only spelling that can name a top-level class there.
  - A dotted qualifier such as `BigInteger.ZERO` names a field or a nested class, so `app.ZERO` is never asked about.
  - A bare name is asked about even where a variable of that name is in scope. javac decides the binding: if it
    binds a variable, the class that was asked about is never used.
  - r4 and r5 tried to skip names that looked like variables, by text. Every approximation of Java's scoping could
    make JuLC compile a different class from the one javac binds (review R3-1, R4-2, R4-3). Asking too often costs
    a warning, or a syntax error from a malformed unrelated source on an explicit `-sourcepath` (Risks). Asking too
    rarely can compile the wrong code.
  - When the compilation holds several classes of that name in wildcard-imported packages, all of them are handed to
    JuLC's javac, with no ambiguity reported by the resolver (r7). javac ignores them for a variable, and reports
    the ambiguity itself for a class.
  - Those candidates, and what they reach, are resolved after everything else, in a speculative pass (r8). It reports
    nothing and turns an ambiguity into every candidate, for javac to settle. A resolution's non-speculative part,
    with its errors, is exactly what it was without them. The resolver's own ambiguity error stays for pool entries,
    as before.

  The processor answers with the class javac names, if it is a top-level library of this compilation (I2,
  `Trees.getPath` non-null). The source is keyed by javac's qualified name, not the source's text. The host is asked
  only for names that are Java names.

  For any class of this compilation it is asked about, library or not, the processor rejects the nested library
  classes of every top-level class in that class's file (r3, r4). That includes a nested class asked about by its
  own name (`import app.Holder.Inner`).

  A library whose marker has no source JuLC can read is reported, at the library, with the remedy (r3, see the
  marker contract). This covers a marker that is neither in this compilation nor in a jar's
  `META-INF/plutus-sources`.

  What the host admits is not added to the pool, so a validator's libraries never depend on another validator's (I5).
  A library of this compilation also wins over a class-path source of the same name, as a collected one does (r3).
  However a resolution reaches a class-path entry (import, qualified name, package, wildcard or fallback), it asks
  the host whether the compilation has a library of that name, and takes it if so. So a resolution holds one source
  per name, and it is the class javac checked (r6). Entries the source does not reach are never asked about (r5; r3
  asked about every class-path entry up front, which could read unrelated sources).
  The resolver follows admitted sources like any other, so a library that reaches a marked library through another
  library is found.

  This is what makes the framework case work. javac never calls the processor in the round that entered the user's
  class, but the validator that names it resolves it by exact name.
- **Markers are references (r2, r8).** The resolver treats every annotation a source uses as a reference, whatever its
  spelling (`@mark` too). It is resolved by its qualified name, an import, the source's package or a single wildcard
  match, never through the pool-wide fallback, and with no diagnostic. It is added only if the host says javac's
  class of that name is a marker: an annotation interface carrying `@OnchainLibrary` (r8).
  - **The host decides by javac's model** (`Elements.getTypeElement`, `JavacFacts.libraryMarker`), not by reading the
    marker's source. r4–r7 read the annotation's identity from text (imports, qualified names, same-package
    shadowing), and each version missed a way Java binds a name.
  - **Only the annotation processor answers.** For the CLI, testkit and tools hosts no annotation is followed, exactly
    as before, so a plain annotation in the CLI's pool can never be pulled in (I1, non-goals).
  - **Where markers come from:** a same-project marker is collected like any `@OnchainLibrary` class, since it carries
    the annotation itself. A class-path marker is a library source in its jar's `META-INF/plutus-sources`.

  So the marker's source is compiled with each library and validator that uses it, imported or not. All of a class's
  markers come along, and so do the libraries a marker's member defaults read.
- **Conflict check.** `JavacDiscovery.rejectRoleConflict` uses the rule, so a meta-annotated library that is also a
  validator is JULC0134. The message names the marker: "must not combine @StateLike (which implies @OnchainLibrary)
  with validator annotation(s): @SpendingValidator". For `@OnchainLibrary` itself the text is unchanged.
- **Nested classes.** A nested `@OnchainLibrary` class is rejected by round collection, as before. A nested class
  that is a library through a marker is rejected in two cases:
  - when a validator's resolution asks about any class of its file;
  - when round collection collects a library of its file (r5), for example a marked class nested in a direct
    library. For
  `@OnchainLibrary` the message is unchanged. For a marker it reads "@StateLike makes Inner an on-chain
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

- **Admission asks javac for names** (`Elements.getTypeElement`). javac may parse a source to answer: from
  `-sourcepath`, or from the user class path when no source path is given, which is javac's default. It then prints
  its usual "implicitly compiled files were not subject to annotation processing" **warning**, which fails a
  `-Werror` build.
  - This happens for a name the source writes as a bare qualifier. Usually that is a type, which javac would
    resolve itself.
  - When that class is a library, the build used to fail with `JULC0085`; now it compiles.
  - A field named through its class (`BigInteger.ZERO`) is never asked about, and neither are class-path entries the
    source does not reach (r4, r5).
  - A bare name that is a variable may still be asked about (r6). If an unrelated source of that name sits on an
    explicit source path, javac reads it and warns, or reports its syntax errors if it is malformed. Gradle's default
    empty source path never reads such sources, and Maven's source roots are compiled anyway. This is the price of
    never compiling a class javac did not bind.
  - **Cost:** measured over 1,000 validators. About 29,000 lookups of 196 distinct names took 74 ms cold, and the
    processor's total time did not grow.
- **The marker contract** constrains framework authors (Q1). A marker JuLC cannot compile fails every library that
  uses it, with the compiler's usual diagnostic at the marker.
- **Tools that read source text** still see only `@OnchainLibrary` (non-goals). A testkit test in which one marked class
  calls another needs `@OnchainLibrary` on the callee, or a source list given explicitly.

## Implementation milestones

- **M1 (r1, `566ea845`):** D1 with regex discovery, and D2 with warnings.
- **M2 (r2–r8):**
  - admission by exact name through the resolver, replacing regex discovery; round collection unchanged (r3);
  - markers as references;
  - `JavacFacts.libraryMarker` as the one rule;
  - the JULC0134 and nested-class messages;
  - the compilation's class winning over a class-path source;
  - the missing-marker message (r3);
  - markers only, guessed names only for bare identifiers, nesting across the file (r4);
  - precedence at resolution, JuLC's own `@OnchainLibrary`, every marker reported (r5);
  - bare qualifiers asked without variable guessing, one source per name, import precedence (r6);
  - admitted wildcard candidates left to javac (r7);
  - the speculative pass, and markers decided by javac in the processor only (r8);
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
| `aGeneratedValidatorReachesAMetaAnnotatedLibraryThroughAWildcardImport` | |
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
| `aClassPathSourceDoesNotShadowAMarkedClassOfTheCompilation` | r3 |
| `aMarkerWithoutASourceIsReportedWithTheRemedy` | r3 |
| `aLibraryOnlyOnTheSourcePathIsAdmitted` | the I1 exception |
| `aValidatorCompilesAloneAsItDoesWithOthers` | I5 |
| `aLowercaseMarkerIsFollowed` | r4 |
| `aMarkerClassDefaultThatImportsALibraryCompiles` | the marker contract |
| `aFieldQualifierNeverReadsTheSourcePath` | `-Werror`, r4 |
| `aNestedLibraryImportedByItsOwnNameIsRejected` | r4 |
| `aNestedLibraryInAnotherClassOfTheSameFileIsRejected` | r4 |
| `aVariableInAnotherMethodDoesNotHideAMarkedClass` | a parameter and a block local, r5, r6 |
| `anUnusedClassPathLibraryNeverReadsTheSourcePath` | `-Werror`, r5 |
| `aMarkedClassNestedInADirectLibraryIsRejected` | r5 |
| `everyMarkerWithoutASourceIsReported` | r5 |

`rejectsNestedMetaAnnotatedLibrary` covers an unmarked outer class that a validator names (r3).

Resolver tests (`LibrarySourceResolverTest`):

| Test | What it checks |
|---|---|
| `resolve_followsAMarkerAnnotationOfTheLibrarysOwnPackage` | |
| `resolve_annotationReferencesNameOnlyAnnotationInterfacesAndNeverFallBack` | I4 |
| `resolve_asksTheCompilationOnlyForExactNamesThePoolLacks` | the names asked; the fallback never asks; admitted sources are not pooled |
| `resolve_followsOnlyAnnotationsTheHostCallsMarkers` | the CLI's pool; lookalikes; other hosts follow none, r8 |
| `resolve_asksOnlyBareQualifiersAsClassesAndAVariableDoesNotHideOne` | r6 |
| `resolve_aJarSourceGivesWayHoweverItIsReached` | r6 |
| `resolve_leavesSeveralAdmittedWildcardCandidatesToJavac` | r7 |
| `resolve_anAmbiguityInsideASpeculativeCandidateIsLeftToJavac` | r8 |
| `resolve_aJarSourceGivesWayToTheCompilationsClassOfTheSameName` | r5 |
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
