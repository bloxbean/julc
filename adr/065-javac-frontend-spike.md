# ADR-065: javac frontend spike (replace JavaParser)

**Status:** Proposed (spike; design only). This ADR proposes a time-boxed investigation with exit criteria. It
does not commit JuLC to a migration. A migration, if the spike recommends one, needs its own ADR.
**Date:** 2026-10-30
**Label:** `spike`
**Programme:** candidate step of [ADR-062](https://github.com/bloxbean/julc/pull/250) (JVM–UPLC semantic
consistency, accepted on PR #250, not yet on `main`). It is not one of ADR-062's steps S1–S5 today. Adding it
would be an ADR-062 revision.
**Relates to:** [ADR-063](https://github.com/bloxbean/julc/pull/250) (differential harness, PR #250),
[ADR-061](https://github.com/bloxbean/julc/pull/203) (control flow, PR #203),
[ADR-054](054-playground-webassembly-engine.md) and [ADR-057](057-julc-wasm-distribution.md) (browser engine),
[ADR-064](https://github.com/bloxbean/julc/pull/252) (embeddable Wasm engine, PR #252),
[ADR-056](056-language-neutral-compiler-backend.md) and [ADR-059](059-generic-backend-revision-2.md)
(language-neutral backend), [ADR-058](058-playground-java-source-debugger.md) (source debugger),
[ADR-060](060-generated-binder-namespace.md) (name binding)

All repository facts below were checked at `origin/main` `b5ff39a8` (2026-10-30) unless another commit is named.
Counts give the command that produced them. Estimates are labelled as estimates.

## Context and problem

A JuLC contract is compiled twice, by two different readers of the same text:

1. **javac** compiles it to class files. The user's JVM tests and IDE debugging run those classes (ADR-062,
   "Context and problem").
2. **JuLC** parses the same text again with JavaParser (`com.github.javaparser:javaparser-core:3.26.3`), then
   does its own import, name and type resolution (`ImportResolver`, `SymbolTable`, `TypeRegistrar`,
   `TypeResolver`, `LibraryMethodRegistry` in `julc-compiler/.../compiler/resolve/`) and lowers the
   JavaParser AST directly to PIR (`PirGenerator.generateMethod(MethodDeclaration)`, `PirGenerator.java:378`;
   `generateExpression(Expression)`, `:864`).

Any place where the two readers disagree about what the text means is a JVM-vs-chain divergence, before any
lowering question arises. ADR-062 catalogues divergences by area; most come from lowering or the off-chain
libraries, but some come from reading the source:

- **Unicode escapes.** JLS 21 §3.3 makes unicode-escape translation the first lexical step: "A compiler for the
  Java programming language ("Java compiler") first recognizes Unicode escapes in its raw input". Every JuLC
  parse site builds a default `ParserConfiguration` (sites listed below), and its `preprocessUnicodeEscapes`
  defaults to `false` (probed on 3.26.3, see Verification evidence). So unicode-escape preprocessing differs
  between javac and JuLC's parsers. A finding in this area is under maintainer review; this ADR treats it only
  as a class of divergence.
- **Lexer acceptance.** JLS 21 §3.3 gives `UnicodeMarker: u {u}`, so `\uuu0041` is a legal escape. javac
  accepts it; JavaParser's lexer rejects it (probed). This one fails closed: JuLC rejects source javac compiles.
- **Literal decoding by hand.** `PirGenerator` lowered `StringLiteralExpr.getValue()`, the source text with its
  escapes, so `"a\nb"` held a backslash and an `n` on-chain (ADR-062 T22, issue #256). PR #264 (open) fixes it
  by reimplementing javac's string-literal decoding (`StringLiterals.decode`) and pins it to javac with a
  45-literal oracle test. Integer literals are decoded by hand as well (`PirGenerator.java:865-883`); see
  "Related findings".
- **Name resolution, overloads and constants.** JuLC resolves names itself. On-chain methods are bound by name
  only, so `OnchainOverloads` (ADR-060) rejects overloads unless they lower to the same PIR (`JULC0054`).
  Static-field initializers are lowered as `let`-bound expressions (`JulcCompiler.java:72`,
  `LibraryCompiler.java:146-166`), not taken from javac's constant values (JLS §15.29). Each of these is a
  second implementation of a rule javac already applies.
- **Language level.** Every parse site sets `LanguageLevel.JAVA_21`, while javac reads the user's source at
  whatever level the user's build sets. JuLC itself compiles at Java 25 (`sourceCompatibility` and toolchain 25,
  `build.gradle:85-89`), and the annotation processor declares `RELEASE_24` (`JulcAnnotationProcessor.java:63`).
  The two readers need not accept the same language.

Patching each of these one at a time (as PR #264 does for strings) leaves JuLC carrying a hand-written copy of
javac's lexer and resolver. The alternative is to have one reader. Scalus does this for Scala: its README
describes a "Scala 3 compiler plugin that powers `@Compile` and turns supported Scala code into Scalus
intermediate representation", so the JVM classes and the on-chain IR come from scalac's single typed tree.
JDK 25 exposes the corresponding pieces for Java: `JavacTask.analyze()` ("Completes all analysis") without
`generate()`, `Trees.getElement(TreePath)` and `Trees.getTypeMirror(TreePath)` for bindings, `LiteralTree.getValue()`
for decoded literal values ("a boxed primitive value, a String, or null"), and `Plugin` / `TaskListener` for
running inside the user's own javac.

Replacing the frontend is large and constrained by targets that may not be able to host javac: the browser
engine (`julc-wasm`, GraalVM Web Image), the native CLI and playground images, and Elci's native CLI. Whether
it is feasible, and at what cost, is not known. This ADR proposes to find out with a bounded spike.

## Goals and non-goals

**Goals**

- Decide, with measured evidence, whether JuLC should replace JavaParser with javac's frontend, keep JavaParser
  and cross-check it against javac, or keep the status quo.
- Measure the work: what depends on JavaParser, which javac APIs are needed, and whether any are internal.
- Establish whether each shipping target (JVM library, Gradle plugin, annotation processor, CLI native image,
  playground native image, `julc-wasm` full image, Elci native CLI) can host a javac frontend.
- Establish whether a javac-tree frontend can produce byte-identical UPLC to the JavaParser frontend on a fixed
  corpus, which is the only acceptable migration gate.

**Non-goals**

- Migrating anything. No production code, published artifact or default behaviour changes in the spike.
- Fixing lowering bugs. Loop and branch lowering (ADR-062 T1, T21, T23) belongs to ADR-061; a new frontend reads
  the source correctly but does not lower it more correctly.
- Widening the Java subset. javac accepting a construct never makes it a JuLC construct.
- Changing the backend contract (ADR-056, ADR-059). A javac frontend would emit the same frontend program as
  today's, into the same backend.
- Resolving the unicode-escape finding under review. The spike measures the class; the maintainers decide its
  handling separately.

## Current behavior

### Parse sites

`grep -rn --include='*.java' -E "new JavaParser\(|StaticJavaParser\.|new ParserConfiguration" . | grep -v /src/test/`
at `b5ff39a8`, plus `LedgerSourceLoader`'s method reference `StaticJavaParser::parse`. Every site uses language level `JAVA_21` and otherwise default configuration.

| # | Module | Site | Purpose |
|---|---|---|---|
| 1 | julc-compiler | `JulcCompiler.java:1213` (`parseSource`, `StaticJavaParser.parse`), reached from `doCompileOutcome` (`:265`, `:270-273`), `compileOutcomeFromPaths` (`:743`, `:747-751`) and `compileMethod` (`:839`, `:842-845`) | Validator, library and `compileMethod` snippet sources. Each entry point mutates the global `StaticJavaParser` configuration first |
| 2 | julc-compiler | `resolve/LedgerSourceLoader.java:37` (`StaticJavaParser::parse`), called from `JulcCompiler.java:348` and `:876` | Bundled ledger type sources (`META-INF/ledger-sources/`) |
| 3 | julc-compiler | `JavaLibraryProvider.java:49-54`, and `:66-68` for ledger sources | `@OnchainLibrary` sources for the generic backend (used by Elci) |
| 4 | julc-compiler | `JavaSourceIntrospector.java:109-111` | Validator role and purpose detection (Gradle plugin, annotation processor) |
| 5 | julc-compiler | `LibrarySourceResolver.java:526-528` | Library descriptors and transitive library resolution |
| 6 | julc-tools | `java/JavaMetadataExtractor.java:66-68` | Contract metadata for the playground and Wasm engine |
| 7 | julc-cli | `mcp/lint/LintEngine.java:80-82` | MCP lint rules (16 rule classes) |
| 8 | julc-cli | `mcp/tools/EvaluateTool.java:246-248` | MCP evaluate tool |
| 9 | julc-cli | `check/TestDiscovery.java:66-68` | `julc check` test discovery |
| 10 | julc-verification | `RequiresSignerResolver.java:33` | Verification annotation resolution |
| 11 | julc-verification | `StatefulSpendingResolver.java:33` | Verification annotation resolution |
| 12 | julc-verification | `ControlledMintResolver.java:31` | Verification annotation resolution |

That is 12 parse call sites, 14 if `JulcCompiler`'s three entry points are counted separately. Test sources add
74 more lines matching `loadLedgerSources|StaticJavaParser|new JavaParser` (`grep -rn ... julc-*/src/test | wc -l`).

### JavaParser dependency size

Files importing `com.github.javaparser`, with their total line counts (`wc -l` of each such file), at
`b5ff39a8`:

| Module | Source set | Files | Lines in those files | `import com.github.javaparser` lines |
|---|---|---|---|---|
| julc-compiler | main | 19 | 11,460 | 102 |
| julc-compiler | test | 10 | 2,765 | 38 |
| julc-cli | main | 20 | 2,011 | 69 |
| julc-verification | main | 3 | 677 | 21 |
| julc-tools | main | 1 | 349 | 6 |
| **Total main** | | **43** | **14,497** | |

`julc-compiler` main has 102 Java files and 25,296 lines in all, so the JavaParser-dependent files are 19 of
102 files and 45% of its lines. The largest are `pir/PirGenerator.java` (2,855), `JulcCompiler.java` (1,910),
`pir/LoopBodyGenerator.java` (846), `resolve/TypeResolver.java` (746), `LibrarySourceResolver.java` (587),
`resolve/TypeRegistrar.java` (562), `pir/AccumulatorTypeAnalyzer.java` (535), `pir/PirHelpers.java` (496),
`debug/DebugMetadataCollector.java` (466), `pir/TypeInferenceHelper.java` (443), `JavaLibraryProvider.java`
(393) and `validate/SubsetValidator.java` (364). The backend package (`compiler/backend/`) imports nothing from
JavaParser: the ADR-056 boundary already separates frontend from backend, and Elci uses it with its own
frontend.

Line counts overstate the rewrite, because many lines in these files do not touch the AST. They also
understate it, because the files encode JuLC's resolution and subset rules. They are an upper bound on the
files a migration would touch, not an estimate of effort.

### javac inside JuLC today

- **The annotation processor already runs inside javac.** `JulcAnnotationProcessor` gets `Trees` in `init`
  (`:90`), and for each validator and same-project library calls `trees.getPath(element)`, then
  `getCompilationUnit().getSourceFile().getCharContent(true)` (`:182-183`, `:206-212`), and passes the *text*
  to `JulcCompiler`, which parses it again with JavaParser. javac's tree is available and discarded.
- **The Gradle plugin does not use javac.** `CompileJulcTask` reads `.java` files from disk
  (`Files.readString`, `CompileJulcTask.java:109`) and calls `JulcCompiler` directly.
- **Library dependencies arrive as source text.** Libraries in dependency JARs are read from
  `META-INF/plutus-sources/` (`LibrarySourceResolver.scanClasspathSources`); ledger types from
  `META-INF/ledger-sources/`. The user's javac run never compiles these sources.
- **Tests already use javac as an oracle.** `LoopFieldUpdateTest`, ADR-063's `julc-difftest` (on PR #250's
  branch, not on `main`) and PR #264's `StringLiteralEscapeTest` compile with the system javac. Main code uses
  no `javax.tools` or `com.sun.tools.javac` API; its only `com.sun.source` use is `Trees` in the annotation
  processor.

### Targets that compile Java source

| Target | Build | Compiles Java source today? | Evidence |
|---|---|---|---|
| JVM library (`julc-compiler`, testkit `JulcEval.forSource`) | JDK 25 | Yes | — |
| Annotation processor | inside the user's javac | Yes | `JulcAnnotationProcessor.java:227-233` |
| Gradle plugin | Gradle worker JVM | Yes | `CompileJulcTask.java:100` |
| `julc` CLI native image | `graalvmNative`, `--no-fallback` | Yes (`julc build`, `check`) | `julc-cli/build.gradle:158-167`; release CI `native-image-release.yml:119` |
| Playground server native image | `graalvmNative` | Yes | `julc-playground/build.gradle:135-144`; `native-image-release.yml:303` |
| `julc-wasm` **full** image | GraalVM 25.3 Web Image (`--tool:svm-wasm`) | Yes: `compiler.check`, `compiler.compile`, `compiler.evaluate`, `compiler.evalExpression` | `FullApi.java:35-45`; `julc-wasm/build.gradle` |
| `julc-wasm` **VM** image | Web Image | No: the build aborts if `org.julclang.compiler.*` or `com.github.javaparser.*` is reachable | `julc-wasm/build.gradle:131-133` (`-H:AbortOnTypeReachable`) |
| Elci native CLI | GraalVM 25.3.4.1 native image | Yes, Java library sources (below) | Elci `build.gradle:55-70`, `docs/RELEASING.md` |

So the in-browser playground compiles Java from source inside a closed-world Web Image. A Web Image "cannot load
classes later" (ADR-064, "Context and problem"), and a javac frontend would have to be part of that image.

### Elci, a downstream consumer

Elci (`bloxbean/elci-lang`, checked at `8542617`, "Pin Julc 0.1.0-pre20 from Maven Central") is an Elm-syntax
language with its own frontend; it emits PIR into JuLC's generic backend, so its own frontend is unaffected by
this ADR. It does use JuLC's Java frontend for Java libraries:

- `ElciCompiler.javaProvider` (`src/main/java/org/elci/driver/ElciCompiler.java:92-94`) resolves sources with
  `org.julclang.compiler.LibrarySources.resolve` and builds `new JavaLibraryProvider(sources,
  StdlibRegistry.defaultRegistry(), options)`. `driver/Libraries.java` wraps that provider for the backend.
- The sources are the JuLC stdlib plus user library JARs carrying `META-INF/plutus-sources/`
  (`cli/Project.java:164` calls `LibrarySources.readJars`). Elci's `docs/JAVA_LIBRARIES.md` states: "A compiled
  `.class` file alone is insufficient ... These JARs are source inputs; their classes, static initializers and
  service providers are not loaded or executed. This mechanism is also available to the native CLI."
- Elci ships a GraalVM native-image CLI (`build.gradle` task `nativeCompile`; `docs/RELEASING.md`: "No Java
  installation is required for the native executable"). Its reachability metadata
  (`src/main/resources/META-INF/native-image/org.elci/elci-lang/reachability-metadata.json`) has 119 entries
  naming `com.github.javaparser` classes.
- Elci has a custom-library example (`examples/custom-library`). Its release smoke script
  (`.github/scripts/cli-smoke.sh`) runs `elci-library`, `vesting` and `treasury`, not `custom-library`.
- ADR-064 (PR #252) exists so that Elci can build its own browser image from `julc-wasm`.

A javac frontend therefore has to work inside Elci's native image from source text alone, or JuLC has to keep
a JavaParser path for `JavaLibraryProvider`.

## How a javac frontend would work

Illustrative only; the spike decides the shape.

1. Build a `JavacTask` (`JavaCompiler.getTask(...)` cast to `JavacTask`) over the validator and library sources,
   with a class path that contains `julc-stdlib`, `julc-ledger-api` and `julc-core` classes.
2. Call `analyze()`. javac parses, enters and attributes every tree and reports its own diagnostics. No class
   files are written, because `generate()` is never called.
3. Walk the attributed `CompilationUnitTree`s. Each identifier and call resolves through
   `Trees.getElement(path)` to the `Element` javac bound it to; each expression has a `TypeMirror`; each literal
   has its decoded value; compile-time constants have `VariableElement.getConstantValue()`.
4. Run JuLC's subset validator over the attributed trees, then lower them to the same frontend program
   (`FrontendProgram`, PIR) that the JavaParser frontend produces, through the same backend.

Hosted variant: the same walk runs from a javac `Plugin` (located by `ServiceLoader` on the processor path,
`autoStart()` since 14) that registers a `TaskListener` and acts when an `ANALYZE` event finishes, so the user's
own javac run produces both the class files and the script.

What this removes: lexing, unicode-escape and literal decoding, import and name resolution, overload resolution
and constant folding all become javac's. `ImportResolver`, most of `SymbolTable` and the source-side parts of
`TypeResolver` and `TypeRegistrar` would go, and diagnostics for invalid Java would be javac's, matching the IDE.
What it does not remove: the subset validator, every JuLC diagnostic code, the type mapping to PIR, and all of
lowering.

## Invariants

These constrain the spike prototype and any later migration ADR.

- **I1. Byte identity is the migration gate.** For every program in the spike corpus (M0), the javac-tree
  frontend produces UPLC whose FLAT bytes and script hash equal the JavaParser frontend's at the pinned base
  commit, at every optimization level and target the corpus is compiled at. A difference is acceptable only when
  it is attributed to a catalogued source-reading divergence where javac's reading is the JVM's meaning, and is
  listed. (D2, M2)
- **I2. The subset does not widen.** Every program the JavaParser frontend rejects with a JuLC diagnostic is
  rejected by the javac frontend with the same diagnostic code. javac accepting a construct is never sufficient.
  (D2, M2)
- **I3. Names bind as Java binds them.** A source name resolves to the `Element` javac binds it to (ADR-060).
  Generated binders keep the `#` namespace; `GeneratedBinderNameLintTest` and `BinderNameIndependenceTest`
  still pass. (D2, M2)
- **I4. Determinism.** Two runs over the same inputs and JDK give identical bytes. The spike records whether
  bytes change across JDK 25 patch releases. (D2, M2)
- **I5. Public API shape.** `JulcCompiler.compile(String, List<String>)`, `compileMethod`, `compileContract`,
  the path-based entry points and `JavaLibraryProvider(List<String>, StdlibLookup, CompilerOptions)` keep their
  signatures and their source-text inputs. A prototype that needs complete compilation units or a class path says
  so per entry point. (D2, D3, M1, M2)
- **I6. Exported APIs first.** The prototype uses `javax.tools`, `javax.lang.model` and `com.sun.source`
  (module `jdk.compiler`, exported) only. Each use of `com.sun.tools.javac.*` or of `--add-exports` /
  `--add-opens` is recorded with its call site and reason. (D3, M1)
- **I7. Source provenance.** For the corpus, source-map and debug-metadata locations (file, line, column) and
  the set of located PIR nodes equal the JavaParser frontend's. Provenance keys on PIR node identity, so
  locations are attached while the term is built, never by renaming afterwards (ADR-060). (D2, M2)
- **I8. No production change.** During the spike, `main`'s compiler output, published artifacts and defaults are
  unchanged. Prototype code is not published. (D1)

## Decision

### D1. Run a time-boxed spike, not a migration

Investigate for a fixed time box, then decide. Proposed box: **15 working days** (an estimate), split by the
milestones below, with a stop after M1 if no target-critical path is viable. The spike is done on a branch
(`spike/065-javac-frontend`), not merged, and its results are recorded in `adr/evidence/065-javac-frontend-spike.md`
with commands and commits, in the style of the existing `adr/evidence/` files. Only that evidence file and an
ADR revision are merged.

### D2. Prototype option A, a programmatic `JavacTask` frontend, on a bounded subset

The prototype builds a `JavacTask`, calls `analyze()` and lowers attributed trees to PIR through the existing
backend (I1–I7). To keep it inside the box, it covers the constructs the corpus uses: records and sealed
interfaces, static methods, `if`/`else`, `switch` over sealed types, for-each without `break`, local variables,
literals, stdlib and library calls. It does not port loop lowering with `break`, multi-accumulator loops or
anything ADR-061 is redesigning. Corpus programs outside the prototype's subset are reported as "not covered",
not as passes.

Option A comes first because every other target depends on it: option B needs the same lowering, and only A can
serve `compileMethod`, the testkit and the Gradle plugin, which do not run inside the user's javac.

### D3. Answer the platform questions first

Before lowering work (M2), M1 establishes, for each target in "Targets that compile Java source", whether
`jdk.compiler` can run there and what it needs: the system modules javac reads for `java.lang` and
`java.math.BigInteger` (normally the `jrt:` file system or `ct.sym`), the class files of `julc-stdlib`,
`julc-ledger-api` and `julc-core`, the reachability metadata, and the image size. If the `julc-wasm` full image
cannot host javac, the spike also measures option C for that target (D4).

### D4. Measure option C alongside

Independently of D2, measure the cheaper alternative: keep JavaParser, enable `preprocessUnicodeEscapes` where
the maintainers' handling of the finding under review allows it, and add a build-time javac cross-check on
targets with a JDK. The cross-check compares, per name and call that JuLC resolves, JuLC's binding against
`Trees.getElement`, and per literal and constant, JuLC's value against javac's. Its output is a count of
disagreements over the corpus, which is evidence for or against the migration on its own.

### D5. Exit criteria and outcomes

The spike ends with one of three recommendations, recorded in the evidence file and an ADR revision:

- **GO (migrate)**: requires all of
  - E1. I1 holds for every corpus program the prototype covers, and the prototype covers at least the in-repo
    `julc-examples` validators, the stdlib `@OnchainLibrary` classes and the Blaster fixtures.
  - E2. I2 holds for the rejection corpus.
  - E3. I6: no internal javac API is needed, or each needed one is listed with a JDK-version risk the
    maintainers accept.
  - E4. Every shipping target has a viable plan: the JVM library, Gradle plugin and annotation processor run
    javac; the CLI and playground native images either run javac or keep a documented path; the `julc-wasm`
    full image has a decided strategy (open question 1).
  - E5. Elci's native CLI still compiles `examples/custom-library` (from source JARs, no class loading),
    either through a javac `JavaLibraryProvider` in its native image or through a retained JavaParser path
    whose divergence from javac is bounded by option C's cross-check.
  - E6. Compile time on the corpus, JVM, warm and cold, is within a factor the maintainers accept. Proposed
    threshold: no worse than 2x the JavaParser frontend for a warm compile (an estimate to be confirmed in M0).
- **GO-C (keep JavaParser, add the cross-check)**: E4 or E5 fails for a target that matters, or E1 needs a
  rewrite of loop lowering that ADR-061 should own first, but the D4 cross-check finds disagreements worth
  catching.
- **NO-GO**: neither A nor C justifies its cost. The divergences stay as targeted fixes in the ADR-062 catalogue.

## Options and alternatives

| Option | What | For | Against | Status |
|---|---|---|---|---|
| **A. `JavacTask` frontend in `julc-compiler`** | JuLC runs javac programmatically to `analyze()` and lowers attributed trees | One reading of the source on the JVM; covers every JVM entry point (`compile`, `compileMethod`, testkit, Gradle plugin, annotation processor); javac diagnostics | Needs `jdk.compiler` at compile time; snippets and libraries need a class path and complete compilation units; unknown native-image and Web Image feasibility; large rewrite (above) | **Prototyped by the spike** (D2) |
| **B. javac plugin or processor-hosted lowering** | Lowering runs inside the user's javac, from a `Plugin` `TaskListener` on `ANALYZE` | Closest to Scalus: the user's class files and the script come from the same attributed tree, no second javac run | Does not cover the Gradle plugin task, `compileMethod`, the testkit, the CLI or the playground; dependency libraries arrive as source text and still need their own attribution; a processor sees trees before attribution (to be confirmed in M1: the `Trees` javadoc does not state the attribution state during processing) | Deferred: built on A if A succeeds |
| **C. Keep JavaParser, add unicode-escape preprocessing and a javac cross-check** | JavaParser stays the reader; javac checks it where a JDK exists | Small; no platform risk; keeps the Web Image and native images as they are | Still two readers; the cross-check only runs where a JDK is present, so the browser and native CLIs stay unchecked; preprocessing may change the positions JavaParser reports, which the source map relies on (to measure) | **Measured by the spike** (D4); fallback outcome GO-C |
| **D. Status quo with targeted fixes** | Fix each reading divergence as it is found (as PR #264 does) | No new risk | Keeps a hand-written copy of javac's lexer, literal decoding and resolution; the history in ADR-062 shows that scenario-by-scenario fixes do not converge | Fallback outcome NO-GO |
| Use `com.sun.tools.javac.tree.JCTree` directly | Walk javac's internal tree classes | Full access to symbols and constant folding results | Not exported from `jdk.compiler`; needs `--add-exports` and breaks across JDK releases | Rejected unless M1 shows the exported API is insufficient (I6) |
| Eclipse JDT (ECJ) as the frontend | A second compiler with its own attributed AST, pure Java | Runs without `jdk.compiler` | A third reader, not the one users' builds use (javac); does not achieve "one reading" | Rejected |
| Translate javac trees into JavaParser AST and keep the lowering | Adapter in front of `PirGenerator` | Smallest lowering change | Throws away the bindings that motivate the change, or re-encodes them in a foreign AST; a parallel representation (AGENTS.md) | Rejected |

## Affected stages and modules (if migrated; the spike changes none)

- **Stages:** parsing, subset validation, import/name/type resolution, library resolution, PIR generation from
  source, debug metadata collection. Not affected: the backend (`compiler/backend/`), PIR optimization, UPLC
  generation, serialization, the VMs.
- **julc-compiler:** the 19 JavaParser-dependent main files above; `JavaLibraryProvider` (public API used by
  Elci); `JavaSourceIntrospector` and `LibrarySourceResolver` (used by the Gradle plugin and annotation
  processor).
- **julc-annotation-processor:** would pass trees or a `JavacTask` instead of text (option B).
- **julc-gradle-plugin:** `CompileJulcTask` would need a class path for javac.
- **julc-testkit:** `JulcEval.forSource` and other source-string entry points.
- **julc-tools, julc-wasm, julc-playground:** `JavaMetadataExtractor`; the full Web Image; the playground
  native image.
- **julc-cli:** the native image; `TestDiscovery`; MCP `LintEngine` (16 rules) and `EvaluateTool`.
- **julc-verification:** three resolvers parse with `StaticJavaParser`.
- **Downstream:** Elci (`JavaLibraryProvider`, native CLI, planned Wasm image via ADR-064); the external
  `julc-examples` repo.

## Compatibility

- **The spike changes nothing shipped** (I8).
- **A migration could change script bytes** only where the two readers disagree today; I1 makes every such change
  explicit and attributed. Under ADR-062 P6 it would ship opt-in first, with release notes.
- **Programs could become rejected or accepted.** Source that JavaParser accepts and javac rejects becomes a javac
  error; source that javac accepts and JavaParser rejects (for example `\uuu0041`, newer language levels) reaches
  the subset validator, which must reject it unless it is a supported construct (I2).
- **API:** `JulcCompiler` and `JavaLibraryProvider` keep their source-text signatures (I5). If javac needs a
  class path, it is derived from the compiler's own class loader by default, as `LibrarySourceResolver` does today
  for sources.
- **Runtime requirement:** compiling would need the `jdk.compiler` module. Gradle, the annotation processor and
  tests already run on a JDK; a jlinked runtime or a native image may not have it.
- **Elci:** `JavaLibraryProvider` from source strings in Elci's native image must keep working (E5), or JuLC must
  keep a JavaParser `JavaLibraryProvider` for Elci and say so. Either way, Elci's 119 JavaParser reachability
  entries change or stay deliberately; Elci would need a coordinated release.

## Risks

- **Rewrite size.** 43 main files and 14,497 lines depend on JavaParser (upper bound). `PirGenerator` and
  `LoopBodyGenerator` lower JavaParser nodes directly, so the lowering is rewritten, not wrapped. Mitigation: the
  spike prototypes a bounded subset (D2) and reports coverage.
- **Overlap with ADR-061.** Loop lowering is being redesigned under ADR-061. Porting today's pattern-specific loop
  generators to a new tree would duplicate that work. Mitigation: the prototype excludes them; a migration ADR
  must sequence itself with ADR-061.
- **Internal APIs and JDK brittleness.** If `com.sun.source` is insufficient (for example for some constant or
  position information), `com.sun.tools.javac` and `--add-exports` tie JuLC to JDK internals that change between
  releases. Mitigation: I6 and E3.
- **Browser and native images.** javac in a Web Image or native image is unproven here: it needs system module
  data and class files that a closed-world image does not have by default, and it may be large. Unverified;
  M1 measures it. If it fails, the playground keeps JavaParser (two readers remain there) or changes strategy
  (open question 1).
- **Elci.** Elci's native CLI compiles Java library sources from JARs without loading classes. A javac path needs
  class files for everything those sources reference. Unverified; M1 measures it (E5).
- **Compile speed.** javac attribution may be slower than JavaParser plus JuLC's resolution, especially cold, and
  the testkit compiles many small sources. Unmeasured; E6.
- **Snippets and incomplete units.** `compileMethod` and `ExpressionEvaluator` wrap expressions into synthetic
  classes (`ExpressionEvaluator.java:194`); with javac those classes must type-check in full against a class
  path, so snippet errors become javac errors with synthetic positions.
- **Source positions.** Source maps and the ADR-058 debugger depend on JavaParser positions
  (`DebugMetadataCollector`). javac reports character offsets through `SourcePositions` and `LineMap`; whether
  columns match JavaParser's (tabs, unicode escapes) is not established. I7 makes any difference visible.
- **Determinism.** javac is deterministic for fixed inputs, but iteration order over elements, and output across
  JDK patch releases, must be checked (I4).
- **False confidence.** Byte identity on a corpus is evidence, not proof (ADR-062 non-goals). Programs outside
  the corpus can still differ.

## Implementation milestones (spike)

| ID | What | Entry gate | Exit criteria | Invariants / tests |
|---|---|---|---|---|
| **M0** | Freeze the corpus and baselines. Corpus: in-repo `julc-examples` validators (13 files with validator annotations), stdlib `@OnchainLibrary` classes (17 files), ledger sources, `verification/blaster/fixtures` (7 fixture directories), the ADR-063 regression cases if PR #250 has merged, the external `julc-examples` repo, and Elci's `examples/custom-library` Java library. Rejection corpus: the compiler's diagnostic tests. Record FLAT bytes, hashes, source maps and warm/cold compile times with the JavaParser frontend at the base commit | This ADR accepted as a spike | Baselines committed to the evidence file with the base SHA | I1, I2, I7 baselines |
| **M1** | Platform feasibility and API inventory. Minimal `JavacTask.analyze()` over one stdlib-using validator on: JVM; a GraalVM native image (CLI-like); the `julc-wasm` full Web Image; Elci's native image shape (source JARs, no class loading). Record what each needs (system module data, class files, metadata), image size, and every non-exported API used. Confirm whether annotation-processor trees are attributed during processing | M0 | A table per target: works / works with listed costs / does not work, with evidence. **Stop gate:** if javac runs nowhere but the JVM, skip M2's port and finish D4 only | I5, I6; E3, E4, E5 |
| **M2** | Lowering prototype (D2) behind a spike-only entry point. Compare against M0 baselines | M1 shows the JVM path works | Coverage and byte-identity results per corpus program; every difference attributed or listed as a defect; rejection-corpus codes compared; generated-binder tests pass | I1, I2, I3, I4, I7; E1, E2 |
| **M3** | Option C measurement (D4): the javac cross-check over the corpus, with and without unicode-escape preprocessing | M0 (independent of M2) | Count and classification of binding, literal and constant disagreements | D4; GO-C evidence |
| **M4** | Report and recommendation | M1–M3 done or time box spent | Evidence file and ADR-065 revision recording GO, GO-C or NO-GO against E1–E6, with measured compile times (E6) | D5 |

## Verification strategy

- **Independent oracle.** javac is the reference for what the source means; the JavaParser frontend at the base
  commit is the reference for the bytes. Neither is derived from the prototype.
- **Byte identity** (I1): FLAT bytes and script hashes compared per program, level and target; differences
  shown as PIR diffs.
- **Rejections** (I2): same diagnostic codes on the rejection corpus.
- **Name binding** (I3): ADR-060's lint and independence tests, plus D4's per-name comparison.
- **Platform** (M1): each target built and run, not inferred; failures recorded with the command and error.
- **Downstream** (E5): Elci's native CLI built from a local JuLC snapshot compiles `examples/custom-library`,
  without modifying elci-lang in the spike; the external `julc-examples` build passes.
- **Negative controls:** a deliberately wrong binding in the prototype (for example binding a call to a different library method) must fail I1 and D4's comparison, so the checks are shown to detect what they claim.

### Verification evidence for this ADR (r1)

- Parse sites and counts: the commands in "Current behavior", at `b5ff39a8`.
- JavaParser 3.26.3 probe (scratch program on the jar from the Gradle cache, JDK 25.0.2): a default
  `ParserConfiguration` with language level `JAVA_21` reports `isPreprocessUnicodeEscapes() == false`; a string
  literal containing `\uuu0041` fails to parse; javac 25.0.2 compiles the same class.
- Elci facts: read at elci-lang `8542617` (local checkout, unmodified).
- PR #264 facts: its description and file list at head `14881225`.

### Pinned references

- JLS, Java SE 21 edition, §3.2 (lexical translations, step 1) and §3.3 (Unicode escapes, `UnicodeMarker: u {u}`),
  `https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html`, fetched 2026-10-30. Same page, §3.10.1
  (integer literals: "Underscores are allowed as separators between digits"; `0xffff_ffff` represents `-1`).
- JLS, Java SE 21 edition, §15.29 (constant expressions; title and the §15.2 cross-reference only),
  `https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html`, fetched 2026-10-30.
- JDK 25 API, module `jdk.compiler`: `com.sun.source.util.JavacTask` (`parse`, `analyze`, `generate`,
  `addTaskListener`), `Trees` (`getPath`, `getElement`, `getTypeMirror`, `getSourcePositions`), `Plugin`
  (`init`, `autoStart`), `TaskEvent.Kind`, `com.sun.source.tree.LiteralTree.getValue`,
  `https://docs.oracle.com/en/java/javase/25/docs/api/jdk.compiler/`, fetched 2026-10-30.
- Scalus README, `https://github.com/nau/scalus`, fetched 2026-10-30 (the compiler-plugin sentence only).
- Not fetched, therefore unverified: GraalVM documentation on running javac in a native image or Web Image.

## Related findings (out of scope)

- **Integer literals are decoded by hand.** `PirGenerator.java:865-883` parses `IntegerLiteralExpr.getValue()`
  itself: a hex literal is read as an unbounded `BigInteger` (javac reads `0xFFFFFFFF` as the `int` -1, JLS
  §3.10.1), underscores (`1_000`) reach `new BigInteger(...)` unstripped, and a `long` literal strips only the
  suffix, so `0xFFL` reaches `new BigInteger("0xFF")`. Found by reading the code; not reproduced. It belongs in the
  ADR-062 catalogue (to confirm with the ADR-063 harness) and is the same class as T22.
- **Global parser state.** `JulcCompiler` sets `StaticJavaParser`'s global configuration on each compile
  (`:265`, `:743`, `:839`), while other sites use local parsers; `LedgerSourceLoader.loadLedgerSources(ClassLoader)`
  uses the global parser. Not a divergence today, since every site sets the same level, but shared mutable state.
- **Elci's smoke script does not run `examples/custom-library`**, the path this ADR's E5 depends on.

## Open questions

1. **Playground and Wasm strategy if javac cannot run in a Web Image.** Options: (a) keep a JavaParser frontend
   for the browser only, labelled as a second reader and cross-checked in CI by D4; (b) compile in the browser
   only through a server; (c) Scalus-like split, where the frontend runs at build time and the browser only lowers
   prepared IR (does not fit a playground where users type Java). Lean: (a), if M1 shows javac is impractical
   there, because the playground is a learning tool and (b) changes its offline design (ADR-054).
2. **Keep JavaParser for `JavaLibraryProvider` (Elci) only?** Same trade-off as question 1, for a published API.
   Lean: decide after M1's Elci-shape measurement; coordinate with Elci either way.
3. **Should option B be the primary path for annotation-processor users** once A exists, so the script and the
   class files come from one javac run? Lean: yes, but only after A; it does not serve the other entry points.
4. **Which JDK range does JuLC support for compiling?** A javac frontend ties the accepted language to the host
   JDK, and constant folding and diagnostics to its version. Lean: the JDK the build uses (25), with `--release`
   pinned.
5. **Does this ADR join ADR-062 as a step?** Lean: add it to ADR-062 as a candidate step after the spike reports,
   not before.
6. **Time box.** 15 working days is an estimate. Should M1's stop gate be allowed to end the spike early with
   GO-C? Lean: yes.

## Revision history

- **r1** (2026-10-30): initial proposal.
