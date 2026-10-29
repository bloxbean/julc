# ADR-065: javac frontend spike (replace JavaParser)

**Status:** Proposed (spike; design only). This ADR proposes a time-boxed investigation with exit criteria. It
does not commit JuLC to a migration. A migration, if the spike recommends one, needs its own ADR.
**Date:** 2026-10-30 (r1); revised 2026-10-30 (r2)
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

**Commits.** Two commits are cited:

- `origin/main` `b5ff39a8` (2026-10-30) for the parse sites and dependency counts. Other facts come from it
  too unless they are marked otherwise.
- **`43927663`**, the head of PR #271, for the reading fixes. That PR is the top of the stack #250 ← #264 ← #269 ←
  #271 (T22, T24, T25), none of which is merged.

Counts give the command that produced them. Estimates are labelled as estimates.

## Context and problem

A JuLC contract is compiled twice, by two different readers of the same text:

1. **javac** compiles it to class files. The user's JVM tests and IDE debugging run those classes (ADR-062,
   "Context and problem").
2. **JuLC** parses the same text again with JavaParser (`com.github.javaparser:javaparser-core:3.26.3`). It then
   does its own import, name and type resolution: `ImportResolver`, `SymbolTable`, `TypeRegistrar`,
   `TypeResolver` and `LibraryMethodRegistry` in `julc-compiler/.../compiler/resolve/`. Finally it lowers the
   JavaParser AST directly to PIR (`PirGenerator.generateMethod(MethodDeclaration)`, `PirGenerator.java:378`;
   `generateExpression(Expression)`, `:864`).

Wherever the two readers disagree about what the text means, the JVM run and the script diverge, before any
question of lowering arises. ADR-062 catalogues divergences by area. Most come from lowering or from the
off-chain libraries. Three of them came from reading the source and are now fixed in JuLC's own reader, in
the stack above:

- **T22, string literals (#256, PR #264).** `PirGenerator` lowered `StringLiteralExpr.getValue()`, the
  source text with its escapes. #264 adds `StringLiterals` (107 lines), which decodes literals as javac does.
  It is pinned to javac by an oracle test over 63 literals (`StringLiteralEscapeTest`).
- **T24, Unicode escapes (#268, PR #269).** JLS 21 §3.3 makes the translation of Unicode escapes the first
  lexical step. JuLC's parsers used JavaParser's default configuration, which leaves escapes untranslated.
  #269 makes every parse go through `JavaSourceParser` (126 lines). It keeps `preprocessUnicodeEscapes` off,
  translates escapes itself in `UnicodeEscapes` (269 lines), and maps every node, comment, token and problem
  position back to the source as written. The oracle is `UnicodeEscapeReadingTest`. After #269, `\uuu0041`
  (legal under `UnicodeMarker: u {u}`) is accepted, as javac accepts it.
- **T25, integer literals (#270, PR #271).** `PirGenerator` decoded integer literals by hand. #271 adds
  `IntegerLiterals` (124 lines), which follows JLS §3.10.1, plus `JULC0070` for literals javac rejects. The
  oracle is `IntegerLiteralTest`.

The fixes are correct, but each one adds a hand-written copy of a piece of javac. Together they come to
**626 production lines** that reimplement javac's lexical rules (`git diff --numstat b5ff39a8 43927663` over the
four files). That code sits beside the resolution JuLC already reimplements:

- **Names.** JuLC resolves imports and names itself.
- **Overloads.** On-chain methods are bound by name only, so `OnchainOverloads` (ADR-060) rejects an overload
  unless it lowers to the same PIR (`JULC0054`).
- **Constants.** Static-field initializers are lowered as `let`-bound expressions (`JulcCompiler.java:72`,
  `LibraryCompiler.java:146-166`), with JuLC's unbounded integers. javac's constant values (JLS §15.29) are not
  used.
- **Language level.** At `b5ff39a8` most sites set `LanguageLevel.JAVA_21`. The three `julc-verification`
  resolvers and the one-argument `LedgerSourceLoader.loadLedgerSources` used the global `StaticJavaParser`
  configuration instead. In a fresh JVM that is `JAVA_11`, which rejects records (probed), and it becomes
  `JAVA_21` only after a `JulcCompiler` entry point has run. #269 removes that dependency. javac reads the
  user's source at whatever level the user's build sets.

The alternative is one reader. Scalus does this for Scala. Its README describes a "Scala 3 compiler plugin that
powers `@Compile` and turns supported Scala code into Scalus intermediate representation", so the JVM classes
and the on-chain IR come from scalac's single typed tree. JDK 25 exposes the corresponding pieces for Java:

- `JavacTask.analyze()` ("Completes all analysis"), without calling `generate()`;
- `Trees.getElement(TreePath)` and `Trees.getTypeMirror(TreePath)` for bindings;
- `LiteralTree.getValue()` for decoded literal values ("a boxed primitive value, a String, or null");
- `JavacTask.instance(ProcessingEnvironment)` and `addTaskListener` for running inside the user's own javac,
  from the annotation processor users already configure.

Replacing the frontend is a large change. It is also constrained by targets that may not be able to host
javac: the browser engine (`julc-wasm`, a GraalVM Web Image), the native CLI and playground images, and Elci's
native CLI. With T22, T24 and T25 fixed, the expected benefit is mostly structural: deleting the javac-replica
code and closing whatever name, overload, constant and language-level disagreements remain. Neither is
measured yet. This ADR proposes a bounded spike to measure the benefit, the cost and the feasibility.

## Goals and non-goals

**Goals**

- Decide, with measured evidence and a stated rule (D5), whether JuLC should replace JavaParser with javac's
  frontend, keep JavaParser and cross-check it against javac, or keep the status quo.
- Measure the benefit: disagreements that a javac cross-check finds, javac-replica code a migration would
  delete, and the ADR-063 corpus results through both frontends.
- Measure the work: what depends on JavaParser, which javac APIs are needed, and whether any of them are
  internal.
- Establish whether each shipping target can host a javac frontend: the JVM library, Gradle plugin, annotation
  processor, CLI native image, playground native image, `julc-wasm` full image and Elci native CLI.
- Establish whether a javac-tree frontend can produce the same PIR and UPLC as the JavaParser frontend on a
  fixed corpus. That is the only acceptable migration gate.

**Non-goals**

- Migrating anything. No production code, published artifact or default behaviour changes in the spike.
- Fixing lowering bugs. Loop and branch lowering (ADR-062 T1, T21, T23) belongs to ADR-061. A new frontend
  reads the source correctly but does not lower it more correctly.
- Widening the Java subset. javac accepting a construct never makes it a JuLC construct.
- Changing integer semantics. Whether constant expressions follow Java's `int` and `long` widths is the T6
  decision (ADR-062 S5), and I9 keeps today's behaviour.
- Changing the backend contract (ADR-056, ADR-059). A javac frontend would emit the same frontend program as
  today's, into the same backend.
- Revisiting T24. Reading Unicode escapes is catalogued as T24 (#268) and fixed in #269. This ADR builds on that
  fix and does not reopen it.

## Current behavior

### Parse sites

At `b5ff39a8`: `grep -rn --include='*.java' -E "new JavaParser\(|StaticJavaParser\.|new ParserConfiguration" . | grep -v /src/test/`,
plus `LedgerSourceLoader`'s method reference `StaticJavaParser::parse`.

| # | Module | Site at `b5ff39a8` | Level at `b5ff39a8` | Purpose |
|---|---|---|---|---|
| 1 | julc-compiler | `JulcCompiler.java:1213` (`parseSource`, `StaticJavaParser.parse`), reached from `doCompileOutcome` (`:265`, `:270-273`), `compileOutcomeFromPaths` (`:743`, `:747-751`) and `compileMethod` (`:839`, `:842-845`) | `JAVA_21`, set on the global configuration by each entry point | Validator, library and `compileMethod` snippet sources |
| 2 | julc-compiler | `resolve/LedgerSourceLoader.java:37` (`StaticJavaParser::parse`), called from `JulcCompiler.java:348` and `:876` | global (`JAVA_11` in a fresh JVM) | Bundled ledger type sources (`META-INF/ledger-sources/`) |
| 3 | julc-compiler | `JavaLibraryProvider.java:49-54`, and `:66-68` for ledger sources | `JAVA_21` | `@OnchainLibrary` sources for the generic backend (used by Elci) |
| 4 | julc-compiler | `JavaSourceIntrospector.java:109-111` | `JAVA_21` | Validator role and purpose detection (Gradle plugin, annotation processor) |
| 5 | julc-compiler | `LibrarySourceResolver.java:526-528` | `JAVA_21` | Library descriptors and transitive library resolution |
| 6 | julc-tools | `java/JavaMetadataExtractor.java:66-68` | `JAVA_21` | Contract metadata for the playground and Wasm engine |
| 7 | julc-cli | `mcp/lint/LintEngine.java:80-82` | `JAVA_21` | MCP lint rules (16 rule classes) |
| 8 | julc-cli | `mcp/tools/EvaluateTool.java:246-248` | `JAVA_21` | MCP evaluate tool |
| 9 | julc-cli | `check/TestDiscovery.java:66-68` | `JAVA_21` | `julc check` test discovery |
| 10 | julc-verification | `RequiresSignerResolver.java:33` | global | Verification annotation resolution |
| 11 | julc-verification | `StatefulSpendingResolver.java:33` | global | Verification annotation resolution |
| 12 | julc-verification | `ControlledMintResolver.java:31` | global | Verification annotation resolution |

That is 12 parse call sites, or 14 if `JulcCompiler`'s three entry points are counted separately. Test sources
add 74 more lines matching `loadLedgerSources|StaticJavaParser|new JavaParser` (`grep -rn ... julc-*/src/test | wc -l`).

**At `43927663`:** every one of these sites calls `JavaSourceParser.parse` or `parseCompilationUnit`
(`git grep -n -E "new JavaParser\(|StaticJavaParser\.|new ParserConfiguration|StaticJavaParser::|JavaSourceParser\.parse" 43927663 -- '*.java' ':!**/src/test/**'`).
`LedgerSourceLoader.java:37` passes `JavaSourceParser::parseCompilationUnit`. The only parser construction left
is `JavaSourceParser.java:60-61`, at `JAVA_21`. A lint in `UnicodeEscapeReadingTest` fails if any
`src/main/java` file outside `JavaSourceParser` constructs `JavaParser` or `ParserConfiguration`, or names
`StaticJavaParser`. So the stack has already reduced JuLC to one JavaParser entry point, which is the seam
a javac frontend would replace.

### JavaParser dependency size

Files importing `com.github.javaparser`, with their total line counts (`wc -l` of each such file), at
`b5ff39a8`:

| Module | Source set | Files | Lines in those files | `import com.github.javaparser` lines |
|---|---|---|---|---|
| julc-compiler | main | 19 | 11,460 | 102 |
| julc-compiler | test | 9 | 2,332 | 38 |
| julc-cli | main | 20 | 2,011 | 69 |
| julc-verification | main | 3 | 677 | 21 |
| julc-tools | main | 1 | 349 | 6 |
| **Total main** | | **43** | **14,497** | |

The r1 test row (10 files, 2,765 lines) also counted `FqcnResolutionTest`, which names JavaParser types fully
qualified but imports none.

- **Share of julc-compiler.** julc-compiler main has 102 Java files and 25,296 lines in all. The
  JavaParser-dependent files are 19 of the 102, and 45% of the lines.
- **Largest files.** `pir/PirGenerator.java` (2,855 lines), `JulcCompiler.java` (1,910),
  `pir/LoopBodyGenerator.java` (846), `resolve/TypeResolver.java` (746), `LibrarySourceResolver.java` (587),
  `resolve/TypeRegistrar.java` (562), `pir/AccumulatorTypeAnalyzer.java` (535), `pir/PirHelpers.java` (496),
  `debug/DebugMetadataCollector.java` (466), `pir/TypeInferenceHelper.java` (443), `JavaLibraryProvider.java`
  (393) and `validate/SubsetValidator.java` (364).
- **The backend is independent.** `compiler/backend/` imports nothing from JavaParser. The ADR-056 boundary
  already separates frontend from backend, and Elci uses it with its own frontend.
- **The stack adds four reader files** (`StringLiterals`, `UnicodeEscapes`, `JavaSourceParser`,
  `IntegerLiterals`, 626 lines). Those are exactly the javac-replica code that option A would delete.

Line counts overstate the rewrite, because many lines in these files do not touch the AST. They also
understate it, because the files encode JuLC's resolution and subset rules. They are an upper bound on the
files a migration would touch, not an estimate of effort.

### javac inside JuLC today

- **The annotation processor already runs inside javac.** `JulcAnnotationProcessor` gets `Trees` in `init`
  (`:90`). For each validator and same-project library it calls `trees.getPath(element)` and then
  `getCompilationUnit().getSourceFile().getCharContent(true)` (`:182-183`, `:206-212`). It passes the *text* to
  `JulcCompiler`, which parses it again with JavaParser, so javac's tree is available and discarded. The
  processor declares no Gradle incremental-processing metadata: its only `META-INF` entry is
  `services/javax.annotation.processing.Processor`.
- **javac can host the lowering from that processor.** JDK 25 exports
  `public static JavacTask instance(ProcessingEnvironment)` and `addTaskListener(TaskListener)` (`javap --module
  jdk.compiler com.sun.source.util.JavacTask`). A scratch probe processor (JDK 25.0.2, `javac -proc:full`,
  not committed) registered an `ANALYZE` listener through `JavacTask.instance(processingEnv)`. After `ANALYZE`
  finished:
  - `Trees.getElement` bound `twice(...)` to `Sample.twice(java.math.BigInteger)` and `x.add` to
    `java.math.BigInteger.add(java.math.BigInteger)`;
  - `getTypeMirror` and `LiteralTree.getValue` were populated;
  - `Filer.createResource(CLASS_OUTPUT, ...)` from inside the listener succeeded.

  This is probe-level evidence for one small class, not a design claim.
- **The Gradle plugin does not use javac.** `CompileJulcTask` reads `.java` files from disk
  (`Files.readString`, `CompileJulcTask.java:109`) and calls `JulcCompiler` directly.
- **Library dependencies arrive as source text.** Libraries in dependency JARs are read from
  `META-INF/plutus-sources/` (`LibrarySourceResolver.scanClasspathSources`), and ledger types from
  `META-INF/ledger-sources/`. The user's javac run never compiles these sources.
- **Tests already use javac as an oracle.** `LoopFieldUpdateTest` (`julc-compiler` tests, `ToolProvider`) on
  `main` compiles with the system javac. So do, on the stack, ADR-063's `julc-difftest` and the three reading
  oracles.
- **Main code uses no compiler API.** It does not use `JavaCompiler`, `ToolProvider` or `JavacTask`. Its only
  `com.sun.source` use is `Trees` in `JulcAnnotationProcessor`. Both annotation processors import `javax.tools`
  for messages and output locations: `JulcAnnotationProcessor` imports `Diagnostic` and `StandardLocation`, and
  `VerificationAnnotationProcessor` imports `Diagnostic`.

### Targets that compile Java source

| Target | Build | Compiles Java source today? | Evidence |
|---|---|---|---|
| JVM library (`julc-compiler`, testkit `JulcEval.forSource`) | JDK 25 | Yes | `JulcEval.java:114` |
| Annotation processor | inside the user's javac | Yes | `JulcAnnotationProcessor.java:227-233` |
| Gradle plugin | Gradle worker JVM | Yes | `CompileJulcTask.java:100` |
| `julc` CLI native image | `graalvmNative`, `--no-fallback` | Yes (`julc build`, `check`) | `julc-cli/build.gradle:158-167`; release CI `native-image-release.yml:119` |
| Playground server native image | `graalvmNative` | Yes | `julc-playground/build.gradle:135-144`; `native-image-release.yml:303` |
| `julc-wasm` **full** image | GraalVM 25.3 Web Image (`--tool:svm-wasm`) | Yes: `compiler.check`, `compiler.compile`, `compiler.evaluate`, `compiler.evalExpression` | `FullApi.java:34-45`; `julc-wasm/build.gradle` |
| `julc-wasm` **VM** image | Web Image | No: the build aborts if `org.julclang.compiler.*` or `com.github.javaparser.*` is reachable | `julc-wasm/build.gradle:130-133` (`-H:AbortOnTypeReachable`) |
| Elci native CLI | GraalVM 25.3.4.1 native image | Yes, on every compile (below) | Elci `build.gradle:55-70`, `docs/RELEASING.md` |

So the in-browser playground compiles Java from source inside a closed-world Web Image. A Web Image "cannot load
classes later" (ADR-064, "Context and problem"), so a javac frontend would have to be part of that image.

### Elci, a downstream consumer

Elci is `bloxbean/elci-lang`, checked at `8542617` ("Pin Julc 0.1.0-pre20 from Maven Central"). It is an
Elm-syntax language with its own frontend, and it emits PIR into JuLC's generic backend, so this ADR does not
affect Elci's own frontend. **Every Elci compile does run JuLC's Java frontend:**

- **A provider for every compile.** Elci builds a `JavaLibraryProvider` each time
  (`ElciCompiler.java:84`, `:153`, `:224`, all through `javaProvider`, `:92-94`). `javaProvider` calls
  `org.julclang.compiler.LibrarySources.resolve`, then `new JavaLibraryProvider(sources,
  StdlibRegistry.defaultRegistry(), options)`, and `driver/Libraries.java` wraps the provider for the backend.
- **The provider always reads ledger sources.** Its constructor parses the bundled ledger sources whatever
  libraries are requested (`JavaLibraryProvider.java:66-68` at `b5ff39a8`). It also parses the stdlib and
  user library sources, which come from JARs carrying `META-INF/plutus-sources/` (`cli/Project.java:164` calls
  `LibrarySources.readJars`).
- **The library JARs are source-only.** Elci's `docs/JAVA_LIBRARIES.md` states: "A compiled `.class` file
  alone is insufficient ... These JARs are source inputs; their classes, static initializers and service
  providers are not loaded or executed. This mechanism is also available to the native CLI."
- **The native CLI needs no JDK.** Elci ships a GraalVM native-image CLI (`build.gradle` task
  `nativeCompile`), and `docs/RELEASING.md` says "No Java installation is required for the native executable".
  Its reachability metadata
  (`src/main/resources/META-INF/native-image/org.elci/elci-lang/reachability-metadata.json`) has 119 entries
  that name `com.github.javaparser` classes.
- **The release smoke set already runs the frontend.** `.github/scripts/cli-smoke.sh` runs `init`, `check`,
  `build`, `eval`, `apply` and `test` over `build/init-smoke`, `examples/vesting`, `examples/elci-library`,
  `examples/treasury` and `build/init-named`. All of these go through JuLC's Java frontend. What the script
  does not cover is user library JARs: `examples/custom-library` is not in it.
- **A browser image is planned.** ADR-064 (PR #252) exists so that Elci can build its own browser image from
  `julc-wasm`.

A javac-only `JavaLibraryProvider` would therefore have to work inside Elci's native image from source text
alone. Otherwise the whole native CLI breaks, not just one example. The other way out is for JuLC to keep a
JavaParser path for `JavaLibraryProvider`.

## How a javac frontend would work

Illustrative only; the spike decides the shape.

1. **Build a `JavacTask`.** `JavaCompiler.getTask(...)`, cast to `JavacTask`, runs over the validator and
   library sources. Its class path contains the `julc-stdlib`, `julc-ledger-api` and `julc-core` classes.
2. **Call `analyze()`.** javac parses, enters and attributes every tree, and reports its own diagnostics. No
   class files are written, because `generate()` is never called.
3. **Walk the attributed `CompilationUnitTree`s.**
   - Each identifier and call resolves through `Trees.getElement(path)` to the `Element` javac bound it to.
   - Each expression has a `TypeMirror`.
   - Each literal has its decoded value. The value is used for literals only, never for constant expressions
     (I9).
4. **Validate, then lower.** JuLC's subset validator runs over the attributed trees. The trees are then lowered
   to the same frontend program (`FrontendProgram`, PIR) that the JavaParser frontend produces, through the
   same backend.

**Hosted variant (option B).** The same walk runs inside the user's own javac. The existing annotation processor
calls `JavacTask.instance(processingEnv)`, registers a `TaskListener`, and acts when an `ANALYZE` event
finishes. That is the route probed above, and it needs no `-Xplugin` flag. A javac `Plugin` (found by
`ServiceLoader` on the processor path; `autoStart()` since JDK 14) is an alternative host.

**Tree shapes differ from JavaParser's.** The probe shows two differences:

- javac's parser folds `-2147483648` into one `INT_LITERAL` with value `-2147483648`, while JavaParser keeps a
  unary minus applied to a literal;
- javac's tree includes the synthesized default constructor and its `super()` call.

The lowering and D4's node alignment must handle both.

**What this removes:**

- The javac-replica reader: `JavaSourceParser`, `UnicodeEscapes`, `StringLiterals` and `IntegerLiterals`
  (626 lines at `43927663`).
- Import and name resolution: `ImportResolver`, most of `SymbolTable`, and the source-side parts of
  `TypeResolver` and `TypeRegistrar`.
- JuLC's own diagnostics for invalid Java, which become javac's and so match the IDE.

**What it does not remove:**

- Overload policy. Overloads stay rejected (`JULC0054`), and the registries still key by `"Class.method"`. javac's
  binding replaces only JuLC's lookup of the declaration.
- Constant folding as JuLC does it (I9).
- The subset validator, every subset diagnostic code, the type mapping to PIR, and all of lowering.

## Invariants

These constrain the spike prototype and any later migration ADR.

- **I1. Equal output is the migration gate.** The base is **B** = `43927663`, or the commit where that stack
  merges into `main` if it lands before the spike starts. The evidence file names the SHA actually used. For
  every corpus input at every optimization level and target in M0, the javac-tree frontend must match the
  JavaParser frontend at B as follows. (D2, M2)
  - **I1a, whole programs.** A program whose every compiled method the prototype covers produces UPLC whose FLAT
    bytes and script hash equal the JavaParser frontend's.
  - **I1b, single methods.** A covered method in a partially covered class produces a PIR term and type that are
    structurally equal to the JavaParser frontend's. This is the same `PirTerm`/`PirType` equality that
    `OnchainOverloads` relies on. It matters because a library is lowered as a whole: `LibraryCompiler.java:106-121`
    calls `generateMethod` on every non-generic static method of every included class.
  - **Attribution.** A difference is acceptable only when it is listed and attributed under I9 or I2c. T22, T24
    and T25 are fixed at B, so they are not grounds for attribution.
- **I2. The subset does not widen.** It has three cases. (D2, M2; open question 7)
  - **I2a, subset rejections.** Every program the JavaParser frontend rejects with a subset or type diagnostic
    (a `JULC` code from `SubsetValidator`, the resolver or the lowering) is rejected by the javac frontend with
    the same code at the same source position. javac accepting a construct is never sufficient. The position
    gate stays, because subset diagnostics are JuLC's own and their positions are part of the user-facing
    contract (IDE markers, the MCP lint, the playground).
    - **Caveat.** Under option A the validator runs on javac trees, so the anchors are corresponding nodes in two
      different trees, and some start positions differ. A declarator starts at its name in JavaParser but at its
      type in javac's `VariableTree`. A negated literal is one javac `INT_LITERAL` starting at the `-`, but in
      JavaParser it is a literal inside a unary minus.
    - **Rule.** The javac frontend derives JuLC's anchor positions from javac's exported position API (for
      example the `SourcePositions` of the subtree that corresponds to JuLC's anchor). Any mismatch that remains
      is a prototype defect, to be fixed or listed. It is not a reason to drop the gate.
  - **I2b, invalid Java.** Where JuLC rejects source that javac also rejects, javac's error replaces JuLC's. That
    covers JuLC's uncoded parse failures (`JulcCompiler.parseSource`, `:1222-1224` at `b5ff39a8`) and the literal
    rejections `JULC0070` (T25). I2b requires only that the program is still rejected. Positions do not gate:
    javac and JavaParser report syntax errors at different columns. In the r2 review's probe (JavaParser 3.26.3
    against javac 25.0.2), a missing semicolon after `return 1` is reported at 3:12 and 3:13, and `return (1;` at
    3:13 and 3:14. The evidence file records both frontends' positions, and the mapping from each JuLC message or
    code to the javac diagnostic key, for information.
  - **I2c, reading rejections.** The T22 and T24 rows list shapes that JuLC rejects although javac accepts them.
    - **Decoded-value shapes.** An unpaired surrogate is still visible in javac's decoded string value, so a
      javac frontend can keep that rejection without reading the raw text.
    - **Raw-text shapes.** Two shapes cannot be seen in javac's tree: escape digits that are not ASCII hex
      digits, and a Unicode-escaped backslash before raw backslashes and `u`. For each, the spike records one
      of two choices. Either a raw-source check keeps the rejection, and is named as a residual second reader.
      Or the program is accepted with javac's meaning, which is the JVM's meaning, and listed as an attributed
      acceptance difference under I1.
- **I3. Names bind as Java binds them.** A source name resolves to the `Element` javac binds it to (ADR-060).
  Generated binders keep the `#` namespace, and `GeneratedBinderNameLintTest` and `BinderNameIndependenceTest`
  still pass. (D2, M2)
- **I4. Determinism.** Two runs over the same inputs and JDK give identical bytes. The spike records whether
  bytes change across JDK 25 patch releases. (D2, M2)
- **I5. Public API shape.** These keep their signatures and their source-text inputs: `JulcCompiler.compile(String,
  List<String>)`, `compileMethod`, `compileContract`, the path-based entry points and
  `JavaLibraryProvider(List<String>, StdlibLookup, CompilerOptions)`. Where the prototype needs complete
  compilation units or a class path, it says so for each entry point. (D2, D3, M1, M2)
- **I6. Exported APIs first.** The prototype uses only `javax.tools`, `javax.lang.model` and `com.sun.source`,
  which module `jdk.compiler` exports. Each use of `com.sun.tools.javac.*`, `--add-exports` or `--add-opens` is
  recorded with its call site and reason. (D3, M1)
- **I7. Source provenance.** For the corpus, source-map and debug-metadata locations (file, line, column) equal
  the JavaParser frontend's, and so does the set of located PIR nodes. Since #269, JavaParser positions refer to
  the source as written, which is what javac's `SourcePositions` reports as character offsets. I7 depends on
  that. Provenance keys on PIR node identity, so locations are attached while the term is built, never by
  renaming afterwards (ADR-060). (D2, M2)
- **I8. No production change.** During the spike, `main`'s compiler output, published artifacts and defaults are
  unchanged. Prototype code is not published. (D1)
- **I9. Integer semantics are unchanged.** (new in r2; D2, M2)
  - The javac frontend takes literal values from javac (`LiteralTree.getValue`), which agrees with T25's fixed
    decoding.
  - It lowers constant expressions and static-field initializers as JuLC does today, with unbounded integers. It
    does not lower `VariableElement.getConstantValue()`, because javac folds at the declared width. For
    `static final int WRAP = 2147483647 + 1`, javac's constant value is `-2147483648`, while JuLC's lowering
    gives `2147483648`. The example uses a literal because JuLC does not resolve `Integer.MAX_VALUE`
    (`Undefined variable: Integer`, r2 review probe). javac folds both spellings to `-2147483648`.
  - Adopting javac's folding is part of the T6 decision (ADR-062 S5), not of this ADR. Any difference I9 permits
    is listed under I1.

## Decision

### D1. Run a time-boxed spike, not a migration

Investigate for a fixed time box, then decide.

- **Box.** **15 working days** (an estimate), budgeted per milestone so that no milestone can consume the box:

  | Milestone | Days |
  |---|---|
  | M0 | 2 |
  | M1 | 4: annotation-processor route 0.5; CLI-like native image 1; `julc-wasm` full Web Image 1.5; Elci native shape 1 |
  | M2 | 5 |
  | M3 | 2 |
  | M4 | 2 |

  A milestone that hits its budget stops and records what it found.
- **Stop gate.** The spike can stop after M1 (see its stop gate).
- **Branch.** The spike runs on `spike/065-javac-frontend`, started from base B (I1). That branch is never
  merged.
- **What merges.** Results go into `adr/evidence/065-javac-frontend-spike.md`, with commands and commits, in the
  style of the existing `adr/evidence/` files. Only that evidence file and an ADR revision are merged.

### D2. Prototype option A, a programmatic `JavacTask` frontend, on a bounded subset

The prototype builds a `JavacTask`, calls `analyze()`, and lowers attributed trees to PIR through the existing
backend (I1–I9).

- **Covered constructs.** Records and sealed interfaces, static methods and static fields, `if`/`else`, `switch`
  over sealed types, for-each without `break`, local variables, literals, and stdlib and library calls.
- **Excluded.** `while` loops, loops with `break`, multi-accumulator loops, and anything else ADR-061 is
  redesigning. Porting today's pattern-specific loop generators would be work that ADR-061 may replace (see
  open question 9).
- **Coverage is measured per method.** A method that uses an excluded construct is reported as not covered, and
  so is a program that calls one. Neither counts as a pass.

Some corpus files are only partly coverable. At `b5ff39a8`, 5 of the 17 `@OnchainLibrary` files contain `while`:
`ContextsLib` (2), `MapLib` (1), `MathLib` (1), `ListsLib` (1) and `ValuesLib` (18), counting matches of
`\bwhile\s*\(`. And 7 of the 13 example files that carry validator annotations reference `ContextsLib` or
`ValuesLib`. These are checked method by method (I1b), and whole-program byte identity (I1a) applies only to
programs whose every compiled method is covered.

Option A comes first because every other route depends on it. Option B needs the same lowering, and only A can
serve `compileMethod`, the testkit and the Gradle plugin, which do not run inside the user's javac.

### D3. Answer the platform questions first

Before any lowering work (M2), M1 establishes, for each target in "Targets that compile Java source", whether
`jdk.compiler` can run there and what it needs. It records:

- the system modules javac reads for `java.lang` and `java.math.BigInteger` (normally the `jrt:` file system or
  `ct.sym`);
- the class files of `julc-stdlib`, `julc-ledger-api` and `julc-core`;
- the reachability metadata;
- the image size.

M1 also tests the annotation-processor route of option B (see the options table). If the `julc-wasm` full image
cannot host javac, the spike falls back to option C for that target (D4).

### D4. Measure option C alongside

Option C, restated for the frontend at B, keeps `JavaSourceParser` as JuLC's reader and adds a javac
cross-check. D4 measures it independently of D2. It has no preprocessing axis: since #269, JuLC translates
escapes itself, and `preprocessUnicodeEscapes` stays off on purpose.

- **What is compared.** For every reference JuLC resolves (a name, field access, method call or type
  reference), the comparison key is the declaration it resolves to. It takes one of five forms:
  - a local variable: its declaration statement's start offset, plus the declarator's index within that
    statement and its name;
  - a parameter: its method's key plus its index;
  - a field, including a library constant: the declaring type's qualified name plus the field name. A field name
    is unique within its type, so no index is needed. javac's side is the `VariableElement`'s enclosing
    `TypeElement` and its simple name;
  - a method: the qualified owner type plus the simple name, since JuLC's registries key by `"Class.method"`
    and overloads are rejected;
  - a type: its qualified name.

  Positions alone would not identify a declarator on the javac side. javac splits a multi-declarator declaration
  such as `BigInteger x = p, y = p;` into two `VariableTree`s, and `SourcePositions.getStartPosition` gives both
  the start of the type (probe on JDK 25.0.2: `x` and `y` both start at offset 81, on a line that starts at 77).
  JavaParser's `VariableDeclarator` begins at the name instead.
  - **Locals are single-declarator today.** JuLC rejects a local declaration with more than one declarator
    (`JULC0053`, `SubsetValidator.java:111-114`), so in an accepted program the local key's index is always 0.
    The index is kept so that the key stays unambiguous if that rule is ever relaxed.
  - **Fields can have several declarators.** Static fields with several declarators are accepted:
    `LibraryCompiler.findStaticFields` iterates every declarator (`:177-189`). Fields key by name, so that is
    not a problem for the key.

  The nodes used for a local key on each side are:
  - **JavaParser:** the enclosing `VariableDeclarationExpr`'s begin position (the type), converted to an offset,
    plus the declarator's index in `getVariables()` and its name;
  - **javac:** the `VariableTree` from `Trees.getPath(element)`, its start position, plus its index among the
    consecutive `VariableTree`s in the enclosing block that share that start position, and its name.

  JuLC's side comes from `SymbolTable` entries and registry keys, mapped to declarations. javac's side comes from
  `Trees.getElement`. For a field, method or type, the key is the enclosing `TypeElement`'s qualified name and
  the element's simple name. Literal
  values are compared directly, and so are static constants under I9's rule (same value expected only where
  JuLC's unbounded lowering and javac's folding agree).
- **How nodes are aligned.** A JavaParser node and a javac tree are aligned by their start offset in the source
  as written. That depends on #269's position mapping. Trees with no JavaParser counterpart are counted as
  unaligned, not as disagreements. Examples are synthesized constructors and folded negative literals.
- **Classification.** Each disagreement is either *silent*, where both compile and the binding or value differs,
  or *fail-closed*, where JuLC rejects what javac accepts within the subset.
- **Hosts.** In JuLC's CI, a test-time harness runs the cross-check over the corpus. In user builds, it would run
  in the annotation processor's `ANALYZE` listener (option B's route). That covers every build that runs the
  processor, but not the native CLIs or the browser.

### D5. Exit criteria, benefit evidence and outcomes

The spike ends with one of three recommendations, recorded in the evidence file and an ADR revision. The rule
below decides between them. Its thresholds are proposals; open question 8 records whether the maintainers would
rather judge.

**Feasibility and cost criteria:**

- **E1.** I1 holds for every corpus input the prototype covers. M4 reports coverage as covered methods, classes
  and programs out of the totals, with an estimate of the work needed to port the uncovered constructs.
- **E2.** I2a and I2b hold for the rejection corpus: subset codes and positions under I2a, and rejection only
  under I2b. I2c has a recorded choice for each raw-text shape.
- **E3.** I6: no internal javac API is needed, or each one that is needed is listed with a JDK-version risk the
  maintainers accept.
- **E4.** Every shipping target has a viable plan:
  - the JVM library, the Gradle plugin and the annotation processor run javac;
  - the CLI and playground native images either run javac or keep a documented path;
  - the `julc-wasm` full image has a decided strategy (open question 1).
- **E5.** Elci's native CLI, built against the candidate JuLC, passes Elci's full release smoke set
  (`cli-smoke.sh`) and also compiles `examples/custom-library` from source JARs without loading classes. It may
  do so through a javac `JavaLibraryProvider` in its native image, or through a retained JavaParser path whose
  divergence from javac is bounded by D4.
- **E6.** Compile time over the corpus, on the JVM:
  - warm: no worse than 2x the JavaParser frontend (an estimate, confirmed or revised in M0);
  - cold, the first compile in a fresh JVM, which dominates `JulcEval.forSource`: recorded with no threshold in
    this spike. M4 proposes one for a migration ADR.

**Benefit evidence:**

- **B1.** The D4 disagreement count over the corpus, split into silent (B1s) and fail-closed (B1f), each with
  its cases.
- **B2.** An inventory, with line counts, of the javac-replica code option A would delete: `JavaSourceParser`,
  `UnicodeEscapes`, `StringLiterals`, `IntegerLiterals`, `ImportResolver`, and the resolution parts of
  `SymbolTable`, `TypeResolver` and `TypeRegistrar`. It is set against the prototype's frontend size,
  extrapolated to the uncovered constructs (an estimate).
- **B3.** ADR-063's positive corpus (the G0 exhaustive programs) and every regression `.diffcase`, run through
  both frontends with each one's AGREE/DISAGREE verdict.
  - **Counted separately.** Covered and uncovered programs are reported as separate counts, for G0 and for the
    `.diffcase` files. G0 is built from loops: `while` with counters, for-each, `if (c) break;`, and two
    accumulators (ADR-063 G0 grammar at `43927663`). So most of it falls outside D2.
  - **Uncovered programs** are reported with their JavaParser verdict only. They neither pass nor fail B3.
  - **In-subset positive corpus.** At `43927663`, 15 of the 38 `.diffcase` files contain `while (` or `break;` in
    their code. The other 23, of which 16 have no loop at all, are B3's in-subset corpus. M0 also lists any G0
    programs that are for-each-only with a single accumulator.

**Decision rule:**

- **R1.** B1s ≥ 1 means the outcome is at least GO-C, because a silent disagreement is worth catching in every
  build that runs the processor, whatever happens to A.
- **R2, GO.** GO, meaning a migration ADR sequenced after ADR-061's representation decision (open question 9),
  requires all of:
  - E1–E6 hold;
  - B3 shows no verdict that differs between the two frontends on **covered** programs, except ones attributed
    under I1; M4 states how many covered programs that rests on;
  - and either B1s ≥ 1, or B2's deletable lines exceed the prototype's extrapolated frontend size.

  E1 sets no coverage floor, so a GO can rest on low coverage, and B2's comparison then rests on an extrapolation
  from a small prototype. That is acceptable only because GO recommends a migration ADR, not a migration. A GO
  recommendation therefore carries its coverage figures (E1) and its covered-program count (B3). The migration
  ADR must re-establish I1 on full coverage of the corpus before any default changes.
- **R3, GO-C.** R1 applies and GO does not. Keep `JavaSourceParser` and ship the D4 cross-check in the
  annotation processor. Fix each B1s case at its source.
- **R4, NO-GO.** B1s = 0 and GO does not apply. B1f cases become targeted fixes in the ADR-062 catalogue, and
  D remains the trajectory.

## Options and alternatives

| Option | What | For | Against | Status |
|---|---|---|---|---|
| **A. `JavacTask` frontend in `julc-compiler`** | JuLC runs javac programmatically to `analyze()` and lowers attributed trees | One reading of the source on the JVM. Covers every JVM entry point (`compile`, `compileMethod`, testkit, Gradle plugin, annotation processor). javac diagnostics. Deletes the javac-replica reader (B2) | Needs `jdk.compiler` at compile time. Snippets and libraries need a class path and complete compilation units. Native-image and Web Image feasibility unknown. Large rewrite (above) | **Prototyped by the spike** (D2) |
| **B. Lowering hosted in the user's javac** | The existing annotation processor gets `JavacTask.instance(processingEnv)`, registers an `ANALYZE` listener and lowers there (a javac `Plugin` is an alternative host) | Closest to Scalus: the user's class files and the script come from one attributed tree, with no second javac run and no `-Xplugin` flag (probe above). The natural host for D4's cross-check in user builds | Does not cover the Gradle plugin task, `compileMethod`, the testkit, the CLI or the playground. Dependency libraries arrive as source text and still need their own attribution. Untested: multi-round processing, generated sources, diagnostics reported after processing, and Gradle's processor handling (the processor declares no incremental-processing metadata) | **Route tested in M1**; lowering built on A if A succeeds |
| **C. `JavaSourceParser` plus a javac cross-check** | Keep the post-#271 reader (escapes translated by `UnicodeEscapes`, positions mapped back) and check its bindings, literals and constants against javac where a JDK exists | Small. No platform risk, so the Web Image and native images stay as they are. The cross-check can run in user builds through option B's listener | Still two readers. The javac-replica code stays and grows with each fix. The browser and native CLIs stay unchecked | **Measured by the spike** (D4); outcome GO-C |
| **D. Status quo with targeted fixes** | Fix each reading divergence as it is found. This is the trajectory the repository is on (#264, #269, #271) | No new risk; each fix is pinned by a javac oracle test | Every fix adds javac-replica code (626 lines so far), and nothing finds the next divergence except the ADR-063 harness | Outcome NO-GO |
| Use `com.sun.tools.javac.tree.JCTree` directly | Walk javac's internal tree classes | Full access to symbols and constant-folding results | Not exported from `jdk.compiler`. Needs `--add-exports` and breaks across JDK releases | Rejected unless M1 shows the exported API is insufficient (I6) |
| Eclipse JDT (ECJ) as the frontend | A second compiler with its own attributed AST, in pure Java | Runs without `jdk.compiler` | A third reader, and not the one users' builds use (javac), so it does not achieve "one reading" | Rejected |
| Translate javac trees into JavaParser AST and keep the lowering | An adapter in front of `PirGenerator` | Smallest lowering change | Throws away the bindings that motivate the change, or re-encodes them in a foreign AST; a parallel representation (AGENTS.md) | Rejected |

## Affected stages and modules (if migrated; the spike changes none)

- **Stages affected:** parsing, subset validation, import/name/type resolution, library resolution, PIR
  generation from source, and debug metadata collection.
- **Stages not affected:** the backend (`compiler/backend/`), PIR optimization, UPLC generation, serialization
  and the VMs.
- **julc-compiler:** the 19 JavaParser-dependent main files above, plus `JavaSourceParser`, `UnicodeEscapes`,
  `StringLiterals` and `IntegerLiterals` from the stack. Also `JavaLibraryProvider` (a public API Elci uses),
  and `JavaSourceIntrospector` and `LibrarySourceResolver` (used by the Gradle plugin and annotation processor).
- **julc-annotation-processor:** would lower from its own `ANALYZE` listener instead of passing text (option B),
  and could host D4.
- **julc-gradle-plugin:** `CompileJulcTask` would need a class path for javac.
- **julc-testkit:** `JulcEval.forSource` and the other source-string entry points.
- **julc-tools, julc-wasm, julc-playground:** `JavaMetadataExtractor`, the full Web Image, and the playground
  native image.
- **julc-cli:** the native image, `TestDiscovery`, and the MCP `LintEngine` (16 rules) and `EvaluateTool`.
- **julc-verification:** three resolvers (`JavaSourceParser.parseCompilationUnit` at B).
- **Downstream:**
  - Elci: every compile goes through `JavaLibraryProvider`; also its native CLI and its planned Wasm image
    (ADR-064);
  - the external `julc-examples` repo.

## Compatibility

- **The spike changes nothing shipped** (I8).
- **Script bytes.** A migration could change script bytes only where I1 lists an attributed difference (I2c, I9).
  Under ADR-062 P6 it would ship opt-in first, with release notes.
- **Programs could become rejected or accepted.**
  - Source that JuLC rejects and javac also rejects stays rejected, now with javac's diagnostic (I2b).
  - The raw-text reading rejections either stay, through a named check, or become accepted with javac's meaning
    (I2c).
  - Subset rejections keep their codes (I2a).
- **API.** `JulcCompiler` and `JavaLibraryProvider` keep their source-text signatures (I5). If javac needs a
  class path, it is derived by default from the compiler's own class loader, as `LibrarySourceResolver` already
  does for sources.
- **Runtime requirement.** Compiling would need the `jdk.compiler` module. Gradle, the annotation processor and
  the tests already run on a JDK. A jlinked runtime or a native image may not have it.
- **Elci.** Every Elci compile, including the CLI's `init`, `check`, `build`, `eval`, `apply` and `test`, runs
  `JavaLibraryProvider` on the bundled ledger and stdlib sources. That must keep working in Elci's native image
  (E5); otherwise JuLC must keep a JavaParser `JavaLibraryProvider` for Elci and say so. Either way, Elci's 119
  JavaParser reachability entries change or stay deliberately, and Elci would need a coordinated release.

## Risks

- **No measured benefit.** T22, T24 and T25 are fixed, so M2 may find byte identity everywhere and D4 may find
  nothing. Mitigation: D5's rule makes that outcome NO-GO or GO-C, decided by B1–B3, rather than leaving it to
  argument.
- **Rewrite size.** 43 main files and 14,497 lines depend on JavaParser (an upper bound). `PirGenerator` and
  `LoopBodyGenerator` lower JavaParser nodes directly, so the lowering would be rewritten, not wrapped.
  Mitigation: the spike prototypes a bounded subset (D2) and reports coverage (E1).
- **Overlap with ADR-061.** ADR-061 §2 (PR #203) proposes a private, typed Java-frontend representation before
  PIR, with "stable declaration identities", which is the role javac `Element`s would play. Porting `PirGenerator`
  to javac trees, and then inserting ADR-061's IR, would port the lowering twice. Mitigation: D2 excludes
  ADR-061's constructs, M4 reports the seam the prototype suggests, and open question 9 records the choice.
- **Internal APIs and JDK brittleness.** If `com.sun.source` is insufficient, for example for some constant or
  position information, `com.sun.tools.javac` and `--add-exports` tie JuLC to JDK internals that change between
  releases. Mitigation: I6 and E3.
- **Browser and native images.** javac in a Web Image or a native image is unproven here. It needs system module
  data and class files that a closed-world image does not have by default, and it may be large. This is
  unverified; M1 measures it. If it fails, the playground keeps JavaParser, so two readers remain there, or it
  changes strategy (open question 1).
- **Elci.** Elci's native CLI compiles Java sources from JARs without loading classes, on every compile. A javac
  path needs class files for everything those sources reference. This is unverified; M1 measures it (E5).
- **Annotation-processor hosting.** Behaviour across processing rounds, with generated sources, and under Gradle's
  processor handling is untested (option B). M1 tests it.
- **Compile speed.** javac attribution may be slower than JavaParser plus JuLC's resolution, especially for a cold
  compile, and the testkit compiles many small sources. Unmeasured; E6 covers it.
- **Snippets and incomplete units.** `compileMethod` and `ExpressionEvaluator` wrap expressions into synthetic
  classes (`ExpressionEvaluator.java:194`). With javac, those classes must type-check in full against a class
  path, so snippet errors become javac errors with synthetic positions.
- **Source positions.** Source maps and the ADR-058 debugger depend on JavaParser positions
  (`DebugMetadataCollector`). Since #269 those refer to the source as written, matching javac's
  `SourcePositions` offsets. It is not yet established whether line and column conventions agree (tabs, and
  ranges that end on an escape). I7 makes any difference visible.
- **Determinism.** javac is deterministic for fixed inputs, but the iteration order over elements, and the output
  across JDK patch releases, must be checked (I4).
- **False confidence.** Equal output on a corpus is evidence, not proof (ADR-062 non-goals). Programs outside
  the corpus can still differ.

## Implementation milestones (spike)

The day budgets are in D1.

| ID | What | Entry gate | Exit criteria | Invariants / tests |
|---|---|---|---|---|
| **M0** | Freeze base B, the corpus and the baselines (details below). Record, for the JavaParser frontend at B: FLAT bytes, hashes, per-method PIR, source maps, and warm and cold compile times | This ADR accepted as a spike; B exists (the stack at `43927663`, or its merge on `main`) | Baselines committed to the evidence file with B's SHA | I1, I2, I7 baselines |
| **M1** | Platform feasibility and API inventory. A minimal `JavacTask.analyze()` runs over one validator that uses the stdlib, on each of: the JVM; a GraalVM native image (CLI-like); the `julc-wasm` full Web Image; Elci's native-image shape (source JARs, no class loading). Record what each needs (system module data, class files, metadata), the image size, and every non-exported API used. Test option B's route: `JavacTask.instance(processingEnv)` plus an `ANALYZE` listener in the existing processor, across multiple rounds, with a generated source, reporting a diagnostic after processing, and under Gradle's `annotationProcessor` configuration | M0 | A table per target: works, works with listed costs, or does not work, with evidence. **Stop gate:** if javac runs nowhere but the JVM, skip M2's port and finish D4 only | I5, I6; E3, E4, E5 |
| **M2** | Lowering prototype (D2) behind an entry point that exists only on the spike branch. Compare against the M0 baselines | M1 shows the JVM path works | Coverage per method, class and program; I1a and I1b results; every difference attributed or listed as a defect; rejection-corpus results under I2a–I2c; generated-binder tests pass | I1, I2, I3, I4, I7, I9; E1, E2 |
| **M3** | Option C measurement (D4): the javac cross-check over the corpus, using the comparison key and offset alignment of D4 | M0 (independent of M2) | B1: silent and fail-closed disagreements, with cases; the count of unaligned nodes | D4; B1 |
| **M4** | Report and recommendation | M1–M3 done, or the box spent | The evidence file and an ADR-065 revision record: E1–E6; B1–B3 (B2's inventory, and B3's corpus run through both frontends, with covered and uncovered counts for G0 and the `.diffcase` files); the outcome under R1–R4, and for a GO its coverage figures; the seam the prototype's code suggests relative to ADR-061 (open question 9) | D5 |

**M0 corpus:**

- **Programs:**
  - the in-repo `julc-examples` validators (13 files with validator annotations);
  - the stdlib `@OnchainLibrary` classes (17 files);
  - the ledger sources;
  - `verification/blaster/fixtures` (7 fixture directories);
  - the external `julc-examples` repo;
  - Elci's examples in `cli-smoke.sh`, plus `examples/custom-library`.
- **Independent vectors** that already pin javac's reading:
  - the literals of `StringLiteralEscapeTest` (63), `UnicodeEscapeReadingTest` and `IntegerLiteralTest`;
  - the 38 `julc-difftest` regression `.diffcase` files at `43927663`, including `agree-t22-*`, `agree-t24-*`
    and `agree-t25-*`;
  - ADR-063's G0 exhaustive corpus.
- **Rejections:** the compiler's diagnostic tests and the `reject-*` regression cases.

## Verification strategy

- **Independent oracles.** javac is the reference for what the source means. The JavaParser frontend at B is the
  reference for the bytes and per-method PIR. The reading oracles and `.diffcase` files are existing vectors
  that pin javac's reading. None of these is derived from the prototype.
- **Equal output** (I1): FLAT bytes and script hashes, compared per program, level and target, and PIR compared
  per method. Differences are shown as PIR diffs.
- **Rejections** (I2): the same codes and positions under I2a; under I2b, rejection by both, with both positions
  and the message mapping recorded but not gating; and the recorded choices under I2c.
- **Name binding** (I3): ADR-060's lint and independence tests, plus D4's per-reference comparison.
- **Integer semantics** (I9): a corpus entry with an `int` constant expression that overflows, such as
  `2147483647 + 1` (or `0x7FFFFFFF + 1`), compiles to JuLC's unbounded value under both frontends.
- **Platforms** (M1): each target is built and run, not inferred. Failures are recorded with the command and
  the error.
- **Downstream** (E5): Elci's native CLI, built against a local JuLC snapshot, passes `cli-smoke.sh` and compiles
  `examples/custom-library`. elci-lang is not modified during the spike. The external `julc-examples` build
  passes.
- **Negative controls.** A deliberately wrong binding in the prototype, for example binding a call to a
  different library method, must fail both I1 and D4's comparison. That shows each check detects what it claims
  to. D4's controls must include a multi-declarator case on a static field, which is the shape JuLC
  accepts. For `static final BigInteger A = BigInteger.ONE, B = BigInteger.TWO;` used as `p.add(A).add(B)`, a use
  of `B` deliberately bound to `A` must be reported as a disagreement, and the correct binding must not be. Local
  multi-declarators are rejected by `JULC0053`, so they are not a control.

### Verification evidence for this ADR

The r1 evidence:

- Parse sites and counts: the commands in "Current behavior", at `b5ff39a8`.
- A JavaParser 3.26.3 probe (a scratch program on the jar from the Gradle cache, JDK 25.0.2):
  - a default `ParserConfiguration` with language level `JAVA_21` reports
    `isPreprocessUnicodeEscapes() == false`;
  - a string literal containing `\uuu0041` fails to parse, and javac 25.0.2 compiles the same class.
- Elci facts: read at elci-lang `8542617`, in a local checkout left unmodified.

Added in r2:

- **Stack facts at `43927663`.**
  - `git diff --stat b5ff39a8 43927663` gives the four reader files (107, 269, 126 and 124 lines);
  - `git grep` gives the parse sites above;
  - `git ls-tree` gives the 38 `.diffcase` files;
  - the PR bodies of #264 (head `6270bb6e`, 63 literals) and #269 (the lint, positions, and acceptance of
    `\uuu0041`);
  - the ADR-062 T22, T24 and T25 rows at `43927663`.
- **JavaParser default level.** In a fresh JVM, `StaticJavaParser.getParserConfiguration().getLanguageLevel()` is
  `JAVA_11`, and `record R(int a) {}` is rejected.
- **Annotation-processor probe** (scratch, JDK 25.0.2). It was compiled with
  `javac -d proc-out AnalyzeProbe.java` and run as
  `javac -proc:full -processorpath proc-out -processor AnalyzeProbe -d sample-out Sample.java`.
  - The `ANALYZE` listener, registered through `JavacTask.instance(processingEnv)`, printed
    `constant MASK = -1` for `0xFFFFFFFF` and `constant WRAP = -2147483648` for `Integer.MAX_VALUE + 1`. That is
    javac's side only: JuLC rejects this spelling, so I9 uses `2147483647 + 1`.
  - It printed `literal INT_LITERAL value=-2147483648` for `-2147483648`, with no unary node.
  - It printed the synthesized `super` call.
  - It printed `call x.add -> java.math.BigInteger.add(java.math.BigInteger)` and
    `call twice -> Sample.twice(java.math.BigInteger)`.
  - `createResource from listener: ok`; `Sample.class` and the resource were both written.
- **The `JavacTask` API.** `javap --module jdk.compiler com.sun.source.util.JavacTask` lists
  `instance(javax.annotation.processing.ProcessingEnvironment)`, `analyze()` and `addTaskListener`.
- **Multi-declarator positions (r3).** A `JavacTask.analyze()` probe on JDK 25.0.2 over
  `BigInteger x = p, y = p;`, on a line starting at offset 77, gives `VariableTree x start=81 end=98` and
  `VariableTree y start=81 end=105`. This reproduces the r2 review's F13 evidence.
- **Loop use in `.diffcase` files (r3).** In the 38 files at `43927663`, 15 contain `while (` or `break;` in
  their code (`grep -c -E '(while *\(|break;)'`), 23 do not, and 16 have no `while`, `break` or `for` at all.
- **Library loops.** The `while` counts come from `grep -c -E '\bwhile\s*\('` over the 17 `@OnchainLibrary` files.
  Whole-class lowering is at `LibraryCompiler.java:106-121`.
- **Elci call sites and smoke set.** `ElciCompiler.java:84`, `:153` and `:224`, and `cli-smoke.sh`, at `8542617`.

### Pinned references

- JLS, Java SE 21 edition, `https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html`, fetched 2026-10-30:
  - §3.2 (lexical translations, step 1);
  - §3.3 (Unicode escapes, `UnicodeMarker: u {u}`);
  - §3.10.1 (integer literals: "Underscores are allowed as separators between digits", and `0xffff_ffff`
    represents `-1`).
- JLS, Java SE 21 edition, §15.29 (constant expressions; the title and the §15.2 cross-reference only),
  `https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html`, fetched 2026-10-30.
- JDK 25 API, module `jdk.compiler`, `https://docs.oracle.com/en/java/javase/25/docs/api/jdk.compiler/`, fetched
  2026-10-30:
  - `com.sun.source.util.JavacTask` (`parse`, `analyze`, `generate`, `addTaskListener`);
  - `Trees` (`getPath`, `getElement`, `getTypeMirror`, `getSourcePositions`);
  - `Plugin` (`init`, `autoStart`);
  - `TaskEvent.Kind`;
  - `com.sun.source.tree.LiteralTree.getValue`.

  `JavacTask.instance(ProcessingEnvironment)` was checked with `javap` against local JDK 25.0.2 in r2, not
  re-fetched.
- Scalus README, `https://github.com/nau/scalus`, fetched 2026-10-30 (the compiler-plugin sentence only).
- Not fetched, and therefore unverified: GraalVM documentation on running javac in a native image or a Web Image.

## Related findings (out of scope)

- **Integer literals (now T25).** The r1 finding, that integer literals were decoded by hand, is catalogued as
  ADR-062 T25 (#270) and fixed in PR #271. That work found more than r1 listed: octal `long` literals were read
  as decimal, and `byte[]` elements were narrowed silently.
- **Global parser state (removed).** `JulcCompiler`'s mutation of the global `StaticJavaParser` configuration,
  and the sites that depended on it, are gone at `43927663`. #269 routes every parse through `JavaSourceParser`,
  and the lint in `UnicodeEscapeReadingTest` keeps it that way.
- **Elci's smoke script does not cover user library JARs.** It covers JuLC's Java frontend through every
  compile, but not `examples/custom-library`. E5 adds it.

## Open questions

1. **Playground and Wasm strategy if javac cannot run in a Web Image.** Options:
   (a) keep `JavaSourceParser` for the browser only, labelled as a second reader and cross-checked in CI by D4;
   (b) compile in the browser only through a server;
   (c) a Scalus-like split, where the frontend runs at build time and the browser only lowers prepared IR. That
   does not fit a playground where users type Java.
   Lean: (a), if M1 shows javac is impractical there. The playground is a learning tool, and (b) changes its
   offline design (ADR-054).
2. **Keep JavaParser for `JavaLibraryProvider` (Elci) only?** The same trade-off as question 1, for a published API
   that every Elci compile uses. Lean: decide after M1 measures the Elci shape, and coordinate with Elci either
   way.
3. **Should option B be the primary path for annotation-processor users** once A exists, so that the script and
   the class files come from one javac run? Its listener route needs no new build configuration (probe). Lean:
   yes, but only after A, and after M1's round, generated-source and Gradle tests. It does not serve the other
   entry points.
4. **Which JDK range does JuLC support for compiling?** A javac frontend ties the accepted language to the host
   JDK, and its diagnostics to the JDK's version. Lean: the JDK the build uses (25), with `--release` pinned.
5. **Does this ADR join ADR-062 as a step?** Lean: add it to ADR-062 as a candidate step after the spike reports,
   not before.
6. **Time box and early stop.** 15 working days is an estimate. Should M1's stop gate end the spike early, with
   the outcome then decided by R1, R3 or R4 on D4's results? An early stop can never be GO, because E1 is unmet;
   it is GO-C if B1s ≥ 1 and NO-GO otherwise. Lean: yes.
7. **Raw-text reading rejections under a javac frontend (I2c).** Options:
   (a) keep them with a raw-source check, named as a residual second reader;
   (b) accept them with javac's meaning, which is the JVM's meaning, and list them as attributed acceptance
   differences.
   Lean: (b). JuLC rejects them because its own reader cannot be sure to read them as javac does, and with javac
   as the reader that reason is gone. The unpaired-surrogate rejection stays, because it is visible in the decoded
   value.
   **Consequence across targets.** Suppose (b) is combined with question 1 (a) or with a retained JavaParser
   provider for Elci (question 2). Then the same source compiles on the JVM and is rejected in the playground or
   by Elci. The difference is fail-closed: no target compiles it to a different program. But JuLC would accept
   different sources on different targets. Lean: acceptable only if the rejecting targets name the cause in their
   diagnostic, and the per-target acceptance difference is documented with the target. Otherwise choose (a)
   wherever any target keeps `JavaSourceParser`, so that every target accepts the same sources.
8. **Decision thresholds.** Should D5's R1–R4, including "one silent disagreement is enough for GO-C" and B2's
   comparison, bind the outcome, or should the maintainers judge B1–B3 directly? Lean: bind it, so the spike
   cannot end in an unfalsifiable recommendation. The maintainers may change the thresholds when they accept this
   ADR.
9. **Where does the javac frontend hand over to lowering, relative to ADR-061's proposed IR?** Options:
   (a) javac trees lowered to PIR directly, as D2 prototypes;
   (b) javac trees lowered through ADR-061's typed frontend representation, with javac `Element`s as its stable
   declaration identities;
   (c) ADR-061's representation built from the JavaParser AST first, with the frontend swapped underneath it
   later.
   Lean: (b) for any migration, because it ports the lowering once. The spike prototypes (a), only because
   ADR-061's representation does not exist yet. M4 reports which seam the prototype's code suggests.

## Revision history

- **r1** (2026-10-30): initial proposal.
- **r2** (2026-10-30; responds to the review of `e0c308f4`):
  - F1 → D2 keeps `while` excluded, and coverage is measured per method. I1 is split into I1a (whole programs)
    and I1b (per-method PIR). E1 becomes I1 on the covered subset plus coverage figures and a porting estimate.
    GO means a migration ADR sequenced after ADR-061.
  - F2 → new benefit evidence B1–B3 and decision rule R1–R4 in D5, with M4 updated, open question 8 added, and a
    new risk "no measured benefit".
  - F3 → base B = `43927663` (or its merge), the spike branch starts from B, and M0 adds the three reading
    oracles, the 38 `.diffcase` files and the G0 corpus. T22, T24 and T25 are no longer grounds for attribution.
  - F4 → I2 split into I2a (subset), I2b (invalid Java, javac's diagnostic) and I2c (reading rejections), with
    E2 updated and open question 7 added.
  - F5 → options C and D restated against `JavaSourceParser`. The preprocessing axis is dropped from D4 and M3.
    D4 defines the comparison key and start-offset alignment, and states the dependency on #269. The non-goal
    now names T24 (#268, #269).
  - F6 → new I9 (literal values from javac; constant expressions lowered as today; folding deferred to T6), with
    How-it-works, the non-goals and Verification updated.
  - F7 → option B restated as the existing processor plus `JavacTask.instance`, with the probe recorded. M1
    tests rounds, generated sources, late diagnostics and Gradle. D4 is hosted in the processor listener.
  - F8 → the Elci section, E5, Compatibility and the related finding now state that every Elci compile runs the
    Java frontend, and E5 requires the full smoke set plus `custom-library`.
  - F9 → new open question 9 on the handover point relative to ADR-061, with M4 reporting the seam and the
    overlap risk rewritten.
  - F10 → language-level claims corrected (global `JAVA_11` at sites 2 and 10–12); test row 9 files and 2,332
    lines; `javax.tools` claim narrowed; #264 head and literal count updated; `\uuu0041`, global parser state
    and the integer finding brought up to date; overload claim narrowed.
  - F11 → per-milestone day budgets in D1, and E6 cold compile time recorded with no threshold.
  - Also from the r2 probe: javac folds `-2147483648` into one literal and synthesizes the default constructor.
    Both are noted for the lowering and D4's alignment.
- **r3** (2026-10-30; responds to the review of `982a20b9`):
  - F12 → I2b requires only that the program is still rejected. Both frontends' positions and the message mapping
    are recorded without gating (the one-column difference, quoted from the review's probe). E2 and Verification
    are updated.
  - F13 → D4's key for a local is now the declaration statement's start offset plus the declarator's index and
    name; a parameter keys by its method key plus its index. The JavaParser node (`VariableDeclarationExpr`) and
    the javac node (`VariableTree`) are named, and the negative control gains a multi-declarator case. The
    javac positions were reproduced.
  - F14 → B3 reports covered and uncovered counts separately, for G0 and the `.diffcase` files. R2's B3 condition
    applies only to covered programs, with the count stated in M4. B3's in-subset corpus is the 23 `.diffcase`
    files with no `while` or `break`.
  - F15 → I9 and Verification use `2147483647 + 1`. The probe line is marked as javac's side only.
  - F16 → open question 6 is reworded against R1, R3 and R4: an early stop can never be GO.
  - F17 → open question 7 records that sources may be accepted on one target and rejected on another, with a
    lean.
  - F18 → R2 states that a GO carries its coverage figures, and that the migration ADR must re-establish I1 on
    full coverage. M4 is updated.
- **r4** (2026-10-30; responds to the review of `e2f5232b`):
  - F19 → D4 gains a field key: the declaring type's qualified name plus the field name, taken on javac's side
    from the `VariableElement`'s enclosing `TypeElement`. The multi-declarator negative control moves to a static
    field, `static final BigInteger A = ..., B = ...;`, with a use of `B` deliberately bound to `A`. The local
    key is kept, with a note that `JULC0053` (`SubsetValidator.java:111-114`) makes locals single-declarator
    today.
  - I2a wording (reviewer note, no finding) → I2a keeps the position gate and now states the caveat: under
    option A some node starts differ (declarators, folded negative literals). The javac frontend derives JuLC's
    anchors from javac's exported position API, and a residual mismatch is a prototype defect, not a reason to
    drop the gate.
