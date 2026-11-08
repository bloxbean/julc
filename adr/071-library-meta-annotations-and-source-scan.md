# ADR-071: An alias of `@OnchainLibrary` for JOUST, and a robust library-source scan

**Status:** Proposed (r18)
**Date:** 2026-11-07 (r2–r13: 2026-11-07; r14–r18: 2026-11-08)
**Label:** `joust`
**Risk class:** low. D2 changes only how the processor reads jars' library sources, and what it reports. D3 adds one
annotation name to the set JuLC treats as `@OnchainLibrary`. Neither changes how a compiled source lowers.
**Motivation:** [JOUST](https://github.com/bloxbean/joust) (JuLC State Contracts) ADR-0001 §12, items 1–2: two
problems found while building a framework that generates validators from user classes on top of JuLC.

## Context

### 1. A framework's own annotation cannot stand for `@OnchainLibrary`

JOUST generates a validator that calls the user's contract class, so that class must be an on-chain library. JuLC
recognises a library only by a literal `@OnchainLibrary` on it. Every JOUST contract therefore carries two annotations:

```java
@StateContract
@OnchainLibrary            // only so JuLC compiles it; @StateContract already says it is one
public class Counter { ... }
```

JuLC's processor is called only from the first round that holds one of the annotations it supports. A class carrying
only `@StateContract` is entered in a round JuLC never sees, so it cannot be collected there.

### 2. One unreadable library index hides every library source

`LibrarySourceResolver.scanClasspathSources` reads `META-INF/plutus-sources/index.txt` from every entry of the
processor's class loader. Two things go wrong:

- The loop over index URLs sits inside a single `try`, and its `catch (IOException)` swallows the exception silently.
- `URL.openStream()` uses the JDK's shared `JarFile` cache. In a long-lived Gradle daemon, a jar on the processor path
  that is rebuilt between builds leaves a stale cached `JarFile`, and reading from it throws.

JOUST hit this. The stale jar was its own API module, and it threw before julc-stdlib's index was read. The validator
then failed with `Undefined variable: OutputLib`, a misleading error on code that compiles fine on a cold daemon.

## Goals and non-goals

Goals:

- JOUST's users write `@StateContract` alone, and their classes are on-chain libraries everywhere JuLC decides that:
  the annotation processor, a jar's bundled sources, the embedder API (JOUST's npm toolchain calls `JavacFrontend` and
  `LibrarySourceResolver` directly), and the tools that read source text.
- JOUST's own processor still receives `@StateContract`, whichever processor runs first.
- A broken or stale jar costs only its own sources, and says so.
- Nothing changes for a project that uses `@OnchainLibrary` alone.

Non-goals:

- A general mechanism by which any framework's annotation stands for `@OnchainLibrary` (D1, withdrawn in r18).
- A processor option listing aliases. It can be added when a second framework needs one (Q2).

## Invariants

- **I1. No change for direct users.** For a project that uses no alias (D3), the processor and every resolver host
  give the compiler the same library sources, in the same order, as before; the validators' bytes, diagnostics and
  warnings are unchanged. The processor claims its annotations as before.
- **I2. One rule.** A class is an on-chain library if it carries `@OnchainLibrary` or an alias of it:
  `JavacNames.LIBRARY_ANNOTATIONS` for the javac paths (by qualified name, javac's own binding), and
  `JavaSourceIntrospector.LIBRARY_ANNOTATIONS` for the tools that read source text (by simple name, as they read
  `@OnchainLibrary` today).
- **I3. Same bytes.** A class marked with an alias compiles to the same script as the same class marked
  `@OnchainLibrary`, at every optimization level and with source maps.
- **I4. The framework keeps its annotation.** JuLC never claims a round that holds an alias, so the framework's
  processor receives it whatever the processor order.
- **I5. The scan isolates failures and never fails the build.** An index, source or directory listing that cannot be
  read costs only itself and is reported as a note. The scan's result is otherwise as before, `putIfAbsent` order
  included.

## Decision

### D1. Meta-annotated on-chain libraries (withdrawn in r18)

r1–r17 let any annotation interface carrying `@OnchainLibrary` (a "marker") make the classes it marks libraries. It is
withdrawn: see Alternatives for what it needed and why it was dropped. D3 serves JOUST, the one framework that needs it.

### D2. The library-source scan reads each entry independently, and reports what it skips

`scanClasspathSources` reads each `index.txt` and each indexed source independently:

- **Failures are isolated.** A failure skips only that entry. That covers `IOException`, and also
  `IllegalArgumentException` from building an entry's URL (r2).
- **Failures are reported.** Each failure goes to a new overload, `scanClasspathSources(ClassLoader, Consumer<String>
  problems)`. That includes a directory that cannot be listed (r2). The existing signature delegates with a no-op
  consumer.
- **No URL cache.** Resources are opened with `URLConnection.setUseCaches(false)`, so a rebuilt jar is read from disk,
  not from a stale cached `JarFile`. The connection closes the `JarFile` when the stream closes.

`JulcAnnotationProcessor` scans once, when the first validator needs it, and prints each problem as a compiler
**note** naming the URL and the cause (r2). A note is printed like a warning, but `-Werror` does not turn it into an
error. So a build that passed before still passes (I1), and the problem is still printed just above whatever error it
causes.

### D3. JOUST's `@StateContract` is an alias of `@OnchainLibrary` (r18)

`org.julclang.state.api.StateContract` stands for `@OnchainLibrary` wherever JuLC decides that a class is an on-chain
library. It is one constant, so the processor, the embedder API and the tools agree:

- **Processor.** `StateContract` joins the supported annotation types, so javac calls JuLC in the round that holds the
  contracts. JuLC collects the round's classes carrying it, by javac's binding of the annotation on the element (taken
  from the round's annotations, so its name is never looked up), exactly as it collects `@OnchainLibrary` classes:
  top-level only, keyed by qualified name, joined to the pool where they override a jar's copy. A nested one is
  rejected: "@StateContract must be declared on a top-level class. Nested on-chain libraries are not supported."
- **Claiming.** The processor returns `true` (claims) as before, except for a round that holds an alias, which it
  leaves unclaimed (I4). JOUST's processor never claims it either, as today, when JuLC does not support it.
- **Compiler** (`JavacFacts.isOnchainLibrary`): the library-call check (`CallTargets`), the export check
  (`JavacLibraryTypes`) and the role check (`JavacDiscovery`, JULC0134, whose message names the annotation: "Class V
  must not combine @StateContract with validator annotation(s): @SpendingValidator").
- **Tools that read source text** (`JavaSourceIntrospector`, `JavacSourceIntrospection`: Gradle `bundleJulcSources`,
  the testkit's `SourceDiscovery`, julc-tools' `JavacMetadataReading`): `StateContract` by simple name, as they match
  `@OnchainLibrary`.
- **The annotation's own source.** JuLC compiles the contract with the library sources it imports. A contract imports
  `StateContract`, whose source JOUST ships in its API jar's `META-INF/plutus-sources` (and its npm toolchain adds to
  the pool), so it is compiled alongside, as on `main` for any imported pooled source. Without it, JuLC reports
  "Annotation @StateContract is not available to JuLC".

Nothing else changes: a validator reaches a contract as any library, by its simple name from the same package or
through an import, and the resolver is as on `main`.

## Alternatives considered

- **D1, meta-annotations (r1–r17), withdrawn in r18.** Any annotation interface carrying `@OnchainLibrary` would make
  the classes it marks libraries. JuLC cannot see the round that enters such a class, so r2–r17 admitted those classes
  later, by asking javac about names the validator mentions (single-type imports only, from r11), and followed marker
  annotations from every library to compile the marker's source. Seventeen revisions and many review rounds (an
  external reviewer, Codex, a regression reviewer) each found real, valid setups that broke:
  - asking javac about a name it had not resolved made it compile an unrelated source from the source path (or from
    the class path, with no `-sourcepath`) and warn "Implicitly compiled files were not subject to annotation
    processing", failing `-Werror`: through a variable named like a library, a commented-out import, a jar's import of
    a plain annotation, or a class and a newer copy of its source in one directory;
  - following a marker annotation needs Java's binding: scope (nested and local types hiding a name), a same-package
    annotation named `OnchainLibrary` shadowing a wildcard import, the module path, `--patch-module`, a module's class
    over a class-path copy. Every approximation missed a case;
  - the final r17 design was safe but had sharp edges (a jar library marked only through a jar marker needed the
    validator to import the marker), required an import in every generated validator, and kept about 700 lines of
    delicate code. The tools that read source text never learned markers.

  The maintainer chose D3: a fixed alias for JOUST, the one framework that needs one. D1's design and review history
  stay in the revision history below and in the PR, should a general mechanism be wanted.
- **JOUST's contracts also carry `@OnchainLibrary`.** Works on `main` with no JuLC change. Kept as the fallback; JOUST
  preferred one annotation.
- **A processor option listing aliases** (`-Ajulc.libraryAnnotations=...`). Not now (Q2): the embedder API and the
  tools would each need the list passed, and JOUST is the only user. A hard-coded name covers every path at once.
- **Report scan problems as warnings (r1).** Rejected in r2. A jar on the processor path with a broken index would
  fail a `-Werror` build that passes today (review of r1, F8).

## Affected stages and modules

- **julc-annotation-processor** (`JulcAnnotationProcessor`): the alias in the supported types, its collection, the
  claim rule, the nested message; the lazy scan and notes (D2).
- **julc-compiler:** `JavacNames` (`STATE_CONTRACT`, `LIBRARY_ANNOTATIONS`), `JavacFacts.isOnchainLibrary` and
  `libraryAnnotation`, `JavacDiscovery` (JULC0134), `JavacLibraryTypes`, `CallTargets`, `JavaSourceIntrospector` and
  `JavacSourceIntrospection`, `diagnostics.json` with the generated `DiagnosticCodes` (the JULC0134 template takes the
  annotation as `{2}`), and `LibrarySourceResolver`'s scan (D2).
- **julc-gradle-plugin** (`BundleJulcSourcesTask`), **julc-testkit** (`SourceDiscovery`): the nested message names the
  annotation. **julc-tools** (`JavacMetadataReading`): the alias.
- **Docs:** the library developer guide (the alias), troubleshooting (the skipped-source note).
- Lowering, PIR, UPLC and every pass are untouched.

## Compatibility

- **Direct `@OnchainLibrary` projects: no change (I1).** JULC0134 keeps its text for `@OnchainLibrary`, and so do the
  nested messages.
- **JOUST:** a contract may drop `@OnchainLibrary`, and `StateContract` may drop its `@OnchainLibrary` meta-annotation;
  keeping either is harmless. Generated validators need no import (keeping one is harmless).
- **A project with an unrelated annotation of its own named `StateContract`:** the javac paths match the qualified
  name only, so nothing changes there; the tools that read source text match the simple name, as they do
  `@OnchainLibrary`, and would treat such a class as a library.
- **The scan** reports through notes. `-Werror` builds behave as before.

## Risks

- **Coupling.** JuLC names a JOUST annotation. JOUST is BloxBean's own framework and the name is settled; Q2 covers a
  second framework.
- **Unclaimed rounds.** A round holding `@StateContract` is not claimed by JuLC, so with `-Xlint:processing` javac may
  list that round's annotations as unclaimed, JuLC's own among them (`@OnchainLibrary`, a validator annotation), and a
  later processor now receives them. JOUST's processor does not claim `@StateContract` either, so a JOUST build already
  sees that warning for it today, and JOUST does not build with `-Xlint:processing -Werror`. A build in which a third
  processor claims `@StateContract`, so that nothing warned before, can now warn about JuLC's annotations of that round
  and fail `-Werror` (Codex, review of `ae347783`). Keeping JuLC's claims there would need a processor of its own for
  the alias; not done for a case no known build has.
- **Text tools match by simple name** (Compatibility).

## Implementation milestones

- **M1 (r1–r16, `566ea845`…`1776f872`):** D1 and D2, as reviewed; D1 withdrawn in r18.
- **M2 (r18):** D1 removed, D2 kept, D3 added, with the tests below and the docs. Exit: the tests are green, the full
  build is green, the julc-examples outputs match the merge base, and JOUST builds against it.

## Verification

Processor tests (`JulcAnnotationProcessorTest`). The test resources ship a stand-in for JOUST's `StateContract` in
`META-INF/plutus-sources`, without `@OnchainLibrary`.

| Test | What it checks |
|---|---|
| `aStateContractClassIsALibrary` | D3: a bare same-package call, no import; the annotation's source compiled alongside |
| `aStateContractClassCompilesToTheSameScriptAsAnOnchainLibraryOne` | I3, at the default level, `none` and with source maps |
| `aFrameworkReceivesStateContractAndItsGeneratedValidatorCompiles` | I4 and the framework case, either processor first |
| `aNestedStateContractClassIsRejected` | the nested message |
| `aLibraryOnlyCompilationClaimsItsAnnotationsAsBefore` | I1: `-Xlint:processing -Werror` |
| `aStateContractModuleWithoutTheStdlibOnItsClassPathCompiles` | an alias-only module, julc-stdlib absent |

Compiler tests: `DiagnosticTextTest.aClassIsALibraryOrAValidatorEvenThroughAnAliasOfOnchainLibrary` (JULC0134 names
`@StateContract`), `JavaSourceIntrospectorTest.inspectTreatsStateContractAsAnOnchainLibrary` and
`aRoleConflictNamesTheLibraryAnnotationTheClassCarries` (the text tools).

Resolver tests (`LibrarySourceResolverTest`), D2:

| Test | What it checks |
|---|---|
| `scanClasspathSources_skipsAnUnreadableEntryReportsItAndReadsTheRest` | I5 |
| `scanClasspathSources_aMalformedEntryUrlCostsOnlyThatEntry` | I5 |
| `scanClasspathSources_readsAJarRewrittenAfterAnEarlierScan` | fails with the URL cache on |

Every D3 test fails on the merge base; the framework test also fails if JuLC claims every round.

Regression evidence: the external julc-examples compiled with the processor at the merge base and at the head, then
compared (I1); the full build; JOUST built against the head.

## Open questions

- **Q1 (closed in r18 with D1).** Whether JuLC's javac should read a marker's class file instead of its source.
- **Q2. More frameworks.** If another framework needs an alias, replace the constant with a list that a processor
  option (`-Ajulc.libraryAnnotations=...`) and the embedder API can extend, or that a framework jar declares in a
  resource JuLC reads during its class-path scan. Lean: when a second framework asks.

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
- **r15** (2026-11-08, responds to Codex's review of r14, `e53ec608`, where R10-1 to R12-2 matched the merge base, and
  the regression reviewer's approval of r14):
  - **A marker refers to no on-chain library.** Its references are not followed, so an unused library named only by
    a marker's `Class<?>` default no longer changes the script at `none` and with source maps (R13-1). JOUST's
    `@StateContract`, with primitive members only, is unaffected.
  - **Risks:** for a jar's copy, the remedy is removing the stale jar; an import does not avoid it.
- **r16** (2026-11-08, responds to Codex's review of r15, `8d3bdd39`, where R13-1 and R10–R12 held and 140 marked/direct
  comparisons of contract-following markers were byte-identical):
  - **A marker's file declares the marker alone.** The processor reports a companion or nested type with the remedy,
    so no other type's code joins the script (R14-1).
  - **ADR accuracy:** the stale text about following a marker's dependencies is gone; the member types are stated
    precisely; the "refers to no library" rule is documented as unchecked.

- **r17** (2026-11-08, local drafts, never pushed, responding to reviewer1's review of r16, `1776f872`, F1 and F2, and
  to Codex's reviews of each draft, R16-1 to R21-2): javac asked only about names it resolved; hiding by Java's scope;
  then several ways of classifying a jar's markers, each of which a valid setup defeated. Superseded by r18.
- **r18** (2026-11-08, responds to reviewer1's review of `1776f872`: F1 and F2 are in D1's code, which r18 removes):
  - **D1 withdrawn** (the maintainer's decision, with JOUST's agreement): the reasons are under Alternatives.
  - **D3 added:** `org.julclang.state.api.StateContract` is an alias of `@OnchainLibrary` on every path that decides a
    class is a library; JuLC does not claim a round holding it.
  - **D2 unchanged.**
  - From Codex's review of the local `ae347783`: a module of `@StateContract` classes without julc-stdlib on its class
    path no longer fails in the processor; the text tools' role-conflict message names the annotation the class
    carries; Risks states the mixed-round `-Xlint:processing` case.
