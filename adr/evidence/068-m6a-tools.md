# ADR-068 M6a: the tools that read Java source, on javac's parser

M6a is the first slice of [ADR-068](../068-javac-frontend-migration.md)'s M6. It does not delete JavaParser or change any
default: M5 has not made javac the default. It gives every tool outside the compiler's JavaParser frontend a second
reading of Java source, on javac's parser, behind one switch per tool, and proves on the corpus that the two readings
give the same output. M6 is then a deletion. Branch `wip/068-m6a`, from `e7603aaa`. Toolchain `25.0.2-librca`.

## Inventory: `com.github.javaparser` outside the compiler's JavaParser frontend

At `e7603aaa`. Lines are whole files.

| Module | Where | Lines | What it reads | Who uses it | This slice |
|---|---|---:|---|---|---|
| `julc-cli` | `check/TestDiscovery` | 78 | `@Test` methods (`julc check`) | `CheckCommand`, `JulcIntegrationTest` | Ported |
| `julc-cli` | `mcp/lint/LintEngine`, `LintRule`, 16 rules | 1,423 | 15 lint rules (and the retired no-op `BigIntegerParamRule`) | `julc check`'s lint pass, MCP `julc_lint`, `julc_lint_rules` | Ported |
| `julc-cli` | `mcp/tools/EvaluateTool.expectedMethodArity` | 1 method | a method's parameter count | MCP `julc_evaluate` | Ported |
| `julc-cli` | `resources/META-INF/native-image/reachability-metadata.json` | 119 of 429 lines | JavaParser reflection | native `julc` | M6 deletes |
| `julc-tools` | `java/JavaMetadataExtractor` | 346 | validator, purpose, `@Param`, datum, redeemer; runs the JavaParser frontend's `SubsetValidator` | playground `/api/check`, `/api/evaluate` (`ToolsService`), Wasm | Ported, except the subset diagnostics (below) |
| `julc-tools` | `META-INF/native-image/.../reachability-metadata.json` | 119 of 761 lines | JavaParser reflection | native tools | M6 deletes |
| `julc-verification` | `RequiresSignerResolver`, `ControlledMintResolver`, `StatefulSpendingResolver` | 677 | the verification annotations on the validator type | `julc verify` (`AnnotatedVerificationWorkflow`) | Ported, through one model |
| `julc-playground` | `build.gradle:26` | 1 | nothing: a declared, unused dependency | — | M6 deletes the line |
| `julc-difftest` | `Adr068Types` (test) | 211 | the reference type registry (M2) | the M2 gate | M6 deletes with the reference |
| `julc-wasm` | `build.gradle:132` | 1 | `-H:AbortOnTypeReachable=com.github.javaparser.*` on the VM-only image | — | Stays; M6 adds it to the full image |

What the tools reach through `julc-compiler` without importing JavaParser themselves. These are compiler classes, not the
frontend proper, and are M6's (or M5's) to port, not this slice's:

| Class | Lines | JavaParser use | Callers outside the compiler | This slice |
|---|---:|---|---|---|
| `JavaSourceIntrospector` | 342 | `inspect`, `declaresNoJulcRole`, `declaresNoTestMethod`, and methods typed on JavaParser nodes | gradle plugin (`CompileJulcTask`, `BundleJulcSourcesTask`), testkit `SourceDiscovery`, cli `ProjectScanner`, `TestDiscovery`'s JavaParser reading, `JavaMetadataExtractor`'s | `inspect` and `declaresNoJulcRole` ported (`JavacSourceIntrospection`); the node-typed methods serve only the JavaParser readings and go with them |
| `LibrarySourceResolver` | 611 | `parseSourceContext` (behind `resolve`, `resolveLibrarySources`) and `extractTopLevelTypeName` | annotation processor, testkit, cli (`TestRunner`, `CheckCommand`, `ProjectSourceResolver`, REPL, `ProjectScanner`), tools, Elci (through `LibrarySources.resolve`) | Ported |
| `JavaSourceParser`, `UnicodeEscapes`, `IntegerLiterals`, `StringLiterals` | 669 | the reader | every JavaParser reading above | M6 deletes |
| `LedgerSourceLoader`, `JavaLibraryProvider`'s JavaParser branch | 77, ~150 | the reference's registry | the JavaParser frontend only; Elci reaches `JavaLibraryProvider` | M6 deletes with the frontend |

## What was built

| Piece | Where | Lines |
|---|---|---:|
| One source read by javac's parser alone, positions as I7 gives them, the tools' type printing and qualified names | `julc-compiler` `frontend/javac/JavacSyntax`, `JavacFrontend.parse` | 200, 27 |
| The one switch: `SourceReader` (`JAVAPARSER`, `JAVAC`) and the `julc.tools.reader` property | `frontend/javac/SourceReader` | 25 |
| Where a name or a member select is a value, not a type, a package or a method | `frontend/javac/ExpressionRoles` | 130 |
| The tools' scanning order (a case's body before its guard, an enum's members before its constants) | `frontend/javac/SourceOrderScanner` | 60 |
| `JavaSourceIntrospector.inspect` and `declaresNoJulcRole` on javac | `julc-compiler` `JavacSourceIntrospection` | 165 |
| `LibrarySourceResolver`'s source context and top-level name on javac | `LibrarySourceResolver.sourceContext` | |
| JavaParser's notions on javac's trees for the lint rules: method, field, declarator, a declarator's name position | `julc-cli` `mcp/lint/LintTrees` | 280 |
| A `check(JavacSyntax)` beside each rule's `check(CompilationUnit)` | the 15 rules, `LintRule`, `LintEngine` | |
| `TestDiscovery`'s and `EvaluateTool.expectedMethodArity`'s javac readings | `julc-cli` | |
| The metadata extractor's javac reading | `julc-tools` `java/JavacMetadataReading` | 270 |
| What the resolvers read of a source, one model from either parser | `julc-verification` `SourceAnnotations` | 240 |

**The switch.** One enum, `SourceReader`, and one `DEFAULT_READER = SourceReader.configured()` per tool
(`TestDiscovery`, `LintEngine`, `JavaMetadataExtractor`, `SourceAnnotations`, `JavaSourceIntrospector`,
`LibrarySourceResolver`; `EvaluateTool` follows `LintEngine`). It is `JAVAPARSER` unless the system property
`julc.tools.reader` says `JAVAC` (case-insensitive; any other value fails with a message naming the property). The build
passes `-Pjulc.tools.reader=JAVAC` to every test task, so each module's own tests run on either reading. M5 flips the
default; M6 deletes the property, the enum and every JavaParser reading.

**Design.** The lint rules and `TestDiscovery` keep both readings side by side in the same class, so a reviewer sees a
JavaParser reading and its port together, and M6 deletes one method per class. The resolvers had three copies of the
same JavaParser walk (imports, package, annotation members, positions); they now read a small model
(`SourceAnnotations.Unit`) that either parser fills, so they hold no parser and M6 deletes one reader. Every reading is
syntactic, as the JavaParser ones were: no attribution, so a tool's answer does not depend on the class path. What the
javac readings share (positions, type printing, qualified names, value-versus-type, order) lives once in
`frontend/javac`, not in each tool. Some of it reproduces JavaParser's habits the tools' outputs depend on (type
arguments printed without a space, an enum's constants visited last); after M6 these are simply the tools' formats, and
each is marked where it is defined.

**ADR deviation to raise.** D12 says the verification resolvers move to `javax.lang.model`. Resolving the annotations by
element (identity) would change what they accept: today `@RequiresSigner` is matched by spelling, import and package. The
equivalence gate needs the syntactic port; resolving by element is a behaviour change for its own decision.

## Equivalence results

**Corpus.** 12,339 `.java` files: the 10,213 case sources of `julc-068-m0/build/julc-corpus/sources`, the 182 sources of
the external examples (`ext-examples`), and the 1,946 sources of this repository at `e7603aaa` (`git archive`), which
bring JUnit tests, `@Test` methods, real validators and verification fixtures. Two files are not UTF-8 and are skipped.
javac's parser rejects 147; JuLC's strict reader (`JavaSourceParser`, ADR-062 T24) rejects 17 that javac reads.

**Commands** (`heavy.sh`, `JAVA_HOME=25.0.2-librca`, `--offline --no-watch-fs --no-daemon`, each in its own process
group under a 30- or 60-minute limit):

```
./gradlew :julc-compiler:adr068Tools :julc-cli:adr068Tools :julc-tools:adr068Tools :julc-verification:adr068Tools --continue \
  -Padr068.tools.sources=<julc-corpus/sources>:<ext-examples>:<repo at e7603aaa> -Padr068.tools.out=build/adr068-tools
```

Each gate writes a summary and every difference, with its file, to `build/adr068-tools/`, and fails on a difference
outside its listed classes.

| Tool | Compared | Same | Listed differences |
|---|---|---:|---|
| Lint (15 rules) | every finding: rule, code, level, message, line, column, suggestion, order | 12,308 files | 30 javac rejects, 1 reader rejects |
| `julc check` discovery | the discovered tests, or the failure | 12,337 (24 with tests) | 2 reader rejects |
| MCP arity | the first four methods of each file and a missing one | 33,546 | 23 reader rejects |
| Project scan (`JavaSourceIntrospector`) | `SourceInfo`, or the failure and the no-role decision | 12,220 | 33 javac rejects, 17 reader rejects, 63 later Java, 6 fail closed |
| Library references (`LibrarySourceResolver`) | package, imports, wildcards, every resolvable reference with its position, in order; top-level name | 12,303; 12,339 | 30 javac rejects, 4 reader rejects, 2 commented qualifiers |
| Metadata extractor | contract, purpose, params, datum, redeemer, own diagnostics, validity | 12,175 (3,492 validators) | 114 both unparsable, 33 javac rejects, 17 reader rejects |
| Verification resolvers (`SourceAnnotations`) | package, imports, every top-level type's annotations: members, values, positions, text | 12,280 (34 with verification annotations) | 33 javac rejects, 17 reader rejects, 9 text of annotations no resolver reads |

Findings on the corpus by rule (JavaParser's, all reproduced): `MUTABLE-VAR` 12,947, `INCREMENT` 1,332,
`RAW-PLUTUSDATA` 1,082, `RETURN-IN-LOOP` 360, `ENTRYPOINT-BIGINT-PARAM` 340, `UNINITIALIZED-VAR` 279,
`BYTESTRINGLIB-OFFCHAIN` 24, `BANNED-PARAM-TYPE` 19, `SWITCH-SHADOW` 15, `LAMBDA-APPLY` 12, `MKCONS-PAIR-LIST` 7,
`DOUBLE-HASH` 5. `OPTIONAL-API` and `TUPLE-SWITCH` have no finding on the corpus; their unit tests cover them on both
readings.

**Module tests on both readings.** `julc-compiler`, `julc-cli` (530 tests, including `LintEngineOnJavacTest`, which runs
every `LintEngineTest` case on both parsers and requires equal findings, and `TestDiscoveryOnJavacTest`), `julc-tools`,
`julc-verification`, `julc-testkit`, `julc-gradle-plugin`, `julc-annotation-processor` and `julc-playground` pass with the
default reading and with `-Pjulc.tools.reader=JAVAC`, except one test that pins JavaParser's reading on purpose
(`BundleJulcSourcesTaskTest.bundleFailsOnACandidateJuLCCannotRead`, the reader-rejects class below). Tests run at the final head: julc-compiler
6,728, julc-cli 531, julc-tools 48, julc-verification 96, julc-testkit 237, julc-gradle-plugin 51,
julc-annotation-processor 30, julc-playground 46. On `JAVAC`, two of `UnicodeEscapeReadingTest`'s tests of JuLC's strict
reader failed because they went through the default reader; they now name JavaParser's reading, the reader they test,
and pass on both (re-run alone with the resolver and introspector tests).

### What differs, and why

1. **The strict reader rejects, javac reads** (`reader-rejects`; 17 files, and the gradle plugin test above). JuLC's
   reader rejects an unpaired surrogate in a literal, full-width hex digits in an escape (`\u００４１`, which javac's
   `Character.digit` takes) and similar; javac's parser reads them. With javac, discovery finds the test, lint and the
   metadata read the source, and compiling it reports the problem with a code (`JULC0093` for a lone surrogate).
   ADR-068 keeps javac's reading (D5): the old reading was stricter than Java. `TestDiscoveryOnJavacTest` pins it.
2. **javac's parser rejects, JavaParser read** (`javac-rejects`; 33 files: an `int` literal out of range, an illegal
   escape in a comment). javac's reading has no tree: no lint findings, arity unknown, discovery and the project scan
   report the parse error. The compiler rejects these sources anyway (D5). The same holds for a source JavaParser
   recovered from (a broken statement): JavaParser linted what it recovered, javac's reading lints nothing.
3. **Later Java in a source with no role** (63 files of this repository using Java 22's unnamed `_`). JavaParser 3.26
   cannot read them at all, so the scan failed; javac reads the whole tree with only language-level errors, sees no
   JuLC role and skips the source, as `declaresNoJulcRole` intends.
4. **Fails closed** (6 files: an illegal Unicode escape in a comment, in a source with no role). JavaParser's lenient
   reading skipped them; javac's error is lexical, so the role cannot be read and the scan reports the error, as javac
   compiling the project would.
5. **JavaParser dropped a library reference next to a comment** (2 files). It attaches an end-of-line comment to a node
   near it and prints the comment into the qualifier's text (`// H` then `Builtins`), which is then no class name, so the
   library was not offered. javac's reading keeps the reference. The old reading was wrong;
   `LibrarySourceResolverReadersTest.aCommentNearAQualifierDoesNotHideTheReference` pins javac's.
6. **The metadata extractor's subset diagnostics** (1,283 files where JavaParser's reading reports them). The JavaParser
   reading runs the JavaParser frontend's `SubsetValidator`; on javac those rules need attribution (`SubsetScan`), which
   has no public entry for a host. javac's reading reports only its own diagnostics; the gate compares everything else.
   Open item 1.
7. **Printed text of annotations no resolver reads** (9 files: picocli's `{...}` arrays). The resolvers read four
   annotations; their text is equal on every file.

Known shapes the corpus does not hold, found by an independent review and left as they are (each is a printing or
traversal detail of an invalid or unusual source): a comment inside an expression is printed into JavaParser's message
text and not javac's; `case (int) Y` prints as `(int) Y` and `(int)Y`; modifier combinations JavaParser's validators
reject and javac's parser accepts (`public private`, a top-level `static class`) are read by javac and rejected when
compiled; `var a = 1, b = 2;` in a source that only mentions `@Test` is skipped by JavaParser and reported by javac.

### Review

An independent Claude review of `0b040b23..a3ae30dd` checked its claims against JavaParser 3.26.3 and javac 25. Fixed:
`var x;` and `int[] a[];` declarator positions, every method-reference qualifier read as a type (as JavaParser does),
enum constants counted as fields, type annotations and variable-arity components in type names, `@A(1, 2)` crashing the
annotation model, a declarator search that recursed (quadratic and stack-deep on long declarations), lint throwing when
the host has no supported javac (it now reports nothing, as compiling reports `JULC0089`), the property's spelling, a
class in an anonymous class's qualified name, and the four reader enums (now `SourceReader`). The rest is listed above.
No Codex review ran: the head is owed one, with the other heads in `068-followups.md`.

### Open items

1. **The metadata extractor's subset diagnostics on javac** need a host entry to the javac frontend's subset scan (with
   the compilation's libraries), or the decision that `/api/check` reports them from the compile. That is the M5 compiler
   slice's to provide; this slice's javac reading is complete otherwise.
2. **D12 says the resolvers move to `javax.lang.model`.** They moved to javac's syntax trees, which keeps their behaviour
   (annotations matched by spelling, import and package). Resolving by element would match by identity, a change of what
   they accept: a decision for M6, not part of an equivalence port.
3. **`JavaSourceIntrospector.declaresNoTestMethod`** keeps JavaParser's lenient reading for `TestDiscovery`'s JavaParser
   path only; javac's reading has no lenient fallback (item 4 above).


## The M6 deletion plan

M6 starts when M5 has made `frontend=JAVAC` the default and its gate has passed. Each step keeps the build green and
ends with the steps' own tests; the order is set by who calls whom.

1. **Flip the tools (with M5, or first in M6).** Default `julc.tools.reader` to `JAVAC` and re-run this slice's gates
   against M5's outputs (`:julc-cli:adr068Tools`, `:julc-tools:adr068Tools`, `:julc-verification:adr068Tools`, and the
   modules' tests with `-Pjulc.tools.reader=JAVAC`, and `:julc-compiler:adr068Tools`). Before that, decide
   `JavaMetadataExtractor`'s subset diagnostics (open item 1).
2. **Delete the tools' JavaParser halves.** `LintRule.check(CompilationUnit)` and each rule's JavaParser method (a public
   interface change for any rule written outside JuLC: note it in the release notes), `SourceReader`,
   `TestDiscovery`'s and `EvaluateTool`'s JavaParser readings, `JavaMetadataExtractor`'s JavaParser path and its
   `SubsetValidator` call, `SourceAnnotations.readWithJavaParser`, the `julc.tools.reader` property and its line in the
   root `build.gradle`. Then the `javaparser-core` lines of `julc-cli`, `julc-tools`, `julc-verification` and
   `julc-playground`, and the 119 JavaParser entries in each of `julc-cli`'s and `julc-tools`' native-image
   `reachability-metadata.json`.
3. **The shared discovery in `julc-compiler`** has its javac readings from this slice (`JavaSourceIntrospector.inspect`
   and `declaresNoJulcRole`, `LibrarySourceResolver`'s source context and top-level name), gated by
   `:julc-compiler:adr068Tools`. M6 deletes their JavaParser halves, `declaresNoTestMethod`, the node-typed
   introspector methods, and `DiagnosticCollector`'s `Node` methods (the resolver now reports by line and column). The
   gradle plugin, the annotation processor, the testkit, the CLI's `ProjectScanner` and Elci's `LibrarySources.resolve`
   follow through these APIs without a change of their own.
4. **Delete the JavaParser frontend** (D12): the frontend classes and `JulcCompiler`'s JavaParser path (keeping the
   shared parts of `JulcCompiler`, `DebugMetadataCollector`, `PirHelpers`, and moving `LibraryCompiler.Specialization`
   first, since the javac path uses it), `ImportResolver`, the source side of `TypeResolver`, `TypeRegistrar` and
   `TypeInferenceHelper`, `LedgerSourceLoader`, `JavaLibraryProvider`'s JavaParser branch, `LibraryTypeDescriptions`,
   `JavaConstants` (ported as `GuardConstants`), `DiagnosticCollector`; `CompilerOptions.frontend` and every host's
   option (`julc build --frontend`, the gradle plugin's and annotation processor's `julc.frontend`, `JulcEval`,
   `ValidatorTest`), D4. With them the gate: `JavacGate.check` and its report, `CompilerOptions.setJavacGate`, the
   `julc.javacGate*` build properties.
5. **Remove `JULC0100` and the gate-only rows of `JULC0101`** (D15), with a release note naming each remedy:
   `SpelledTypes` (all of it), `SpelledMethods.check` and its helpers, `SubsetRules.checkSpellings`,
   `JavacFrontend.reading` and `analyzeWithRules`. `SpelledMethods.checkDeclarations` and `checkRecordMembers` stay
   (the two rows of every frontend). `diagnostics.json` has no status for a retired code (`emitted`, `planned`,
   `lintOnly`, `internal`): a decision is owed, a new `retired` status or deleting the entries. 17 `.diffcase` files
   expect these codes and are re-baselined (10 `JULC0100`, 7 `JULC0101`; the three explicit-accessor cases probably
   keep `JULC0101`).
6. **Tests.** Delete or port the JavaParser frontend's tests (`PirGeneratorTest`, `IfJoinLoweringTest`,
   `LambdaExpressionTest`, `SealedInterfaceTest`, `SubsetValidatorTest`, `TypeResolverTest`, `FqcnResolutionTest`,
   `DiagnosticCollectorTest`, `JavaConstantsTest`, `UnicodeEscapeReadingTest`), the reference comparisons
   (`GuardConstantsAgainstJavaConstantsTest`, `Adr068Types`, `JavacTypesTest`'s reference half), and the gate-only
   tests (`SpelledNameLintTest`, `SpelledBindingsTest`, `SpelledReadingsTest`). Port the ADR-060 guards
   (`BinderNameIndependenceTest`, `BinderNamespaceCheckTest`) to javac trees: their invariant stands.
   `julc-difftest`'s `testImplementation` of JavaParser goes with `Adr068Types`.
7. **The readers last**: `JavaSourceParser`, `UnicodeEscapes`, `IntegerLiterals`, `StringLiterals`, once nothing calls
   them, then `javaparser-core` from `julc-compiler`.
8. **The build check.** A root task `verifyNoJavaParser`, on every subproject's `check` (so CI's `./gradlew build`
   runs it), fails on any `com.github.javaparser` token, import or qualified name (there are qualified uses, e.g.
   `SwitchFieldShadowRule`), in `*/src/**/*.java`, the build scripts and the native-image metadata, and on any
   resolvable configuration that holds the group `com.github.javaparser` ("no JavaParser on any class path"). It
   replaces `JavacSeamTest`'s two string guards. As a backstop, `-H:AbortOnTypeReachable=com.github.javaparser.*` goes
   on the full Wasm image and the native CLI too, as it is on the VM-only image today.

**Elci (read only here).** Elci pins `julcVersion=0.1.0-pre20-ebaf028-SNAPSHOT` (`gradle.properties:2-3`, and the
`julc-version` of eight `examples/*/elci.toml`), a JuLC from before ADR-068. It never names JavaParser, `CompilerFrontend`
or the gate; every `CompilerOptions` it makes is `new CompilerOptions()`, so removing the option is source-compatible
for it, and its compilations move to javac with M5. To coordinate at M6: bump the pin and the eight `elci.toml` files;
remove the 119 `com.github.javaparser` entries from its `reachability-metadata.json`; re-run its JVM and native smoke
and `custom-library`, comparing `JavaLibraryProvider`'s exports, schemes and specializations and `LibrarySources.resolve`
against M5's; make sure its runtime has `jdk.compiler` (otherwise `JULC0089`); and update `docs/GETTING_STARTED.md`
(lines 292-293 say the binary bundles JavaParser metadata).
