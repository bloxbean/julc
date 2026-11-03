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

| Class | Lines | JavaParser use | Callers outside the compiler |
|---|---:|---|---|
| `JavaSourceIntrospector` | 342 | `inspect`, `declaresNoJulcRole`, `declaresNoTestMethod`, and methods typed on JavaParser nodes | gradle plugin (`CompileJulcTask`, `BundleJulcSourcesTask`), testkit `SourceDiscovery`, cli `ProjectScanner`, `TestDiscovery`'s JavaParser reading, `JavaMetadataExtractor`'s |
| `LibrarySourceResolver` | 611 | `parseSourceContext` (behind `resolve`, `resolveLibrarySources`) and `extractTopLevelTypeName` | annotation processor, testkit, cli (`TestRunner`, `CheckCommand`, `ProjectSourceResolver`, REPL, `ProjectScanner`), tools, Elci (through `LibrarySources.resolve`) |
| `JavaSourceParser`, `UnicodeEscapes`, `IntegerLiterals`, `StringLiterals` | 669 | the reader | every JavaParser reading above |
| `LedgerSourceLoader`, `JavaLibraryProvider`'s JavaParser branch | 77, ~150 | the reference's registry | the JavaParser frontend only; Elci reaches `JavaLibraryProvider` |

## What was built

| Piece | Where | Lines |
|---|---|---:|
| One source read by javac's parser alone, with positions as I7 gives them | `julc-compiler` `frontend/javac/JavacSyntax`, `JavacFrontend.parse` | 131, 27 |
| JavaParser's notions on javac's trees: method, field, declarator, name expression, a declarator's name position | `julc-cli` `mcp/lint/LintTrees` | 250 |
| A `check(JavacSyntax)` beside each rule's `check(CompilationUnit)` | the 15 rules, `LintRule`, `LintEngine` | |
| `TestDiscovery`'s javac reading | `TestDiscovery.discoverWithJavac` | |
| `expectedMethodArity`'s javac reading | `EvaluateTool` | |
| The metadata extractor's javac reading | `julc-tools` `java/JavacMetadataReading` | 281 |
| What the resolvers read of a source, one model from either parser | `julc-verification` `SourceAnnotations` | 235 |

**The switch.** Each tool has one `DEFAULT_READER` constant (`TestDiscovery`, `LintEngine`, `JavaMetadataExtractor`,
`SourceAnnotations`; `EvaluateTool` follows `LintEngine`). It is `JAVAPARSER` unless the system property
`julc.tools.reader` says `JAVAC`, which the build passes from `-Pjulc.tools.reader=JAVAC` to every test task, so each
module's own tests run on either reading. M5 flips the default; M6 deletes the property, the enum and the JavaParser
half of each tool.

**Design.** The lint rules and `TestDiscovery` keep both readings side by side in the same class, so a reviewer sees the
JavaParser reading and its port together, and M6 deletes one method per class. The resolvers had three copies of the
same JavaParser walking (imports, package, annotation members, positions); they now read a small model
(`SourceAnnotations.Unit`) that either parser fills, so the resolvers have no parser in them and M6 deletes one reader.
All readings are syntactic, as the JavaParser ones were: no attribution, so a tool's answer does not depend on the
classpath.

**ADR deviation to raise.** D12 says the verification resolvers move to `javax.lang.model`. Resolving the annotations by
element (identity) would change what they accept: today `@RequiresSigner` is matched by spelling, import and package. The
equivalence gate needs the syntactic port; resolving by element is a behaviour change for its own decision.

RESULTS_PLACEHOLDER

## The M6 deletion plan

M6 starts when M5 has made `frontend=JAVAC` the default and its gate has passed. Each step keeps the build green and
ends with the steps' own tests; the order is set by who calls whom.

1. **Flip the tools (with M5, or first in M6).** Default `julc.tools.reader` to `JAVAC` and re-run this slice's gates
   against M5's outputs (`:julc-cli:adr068Tools`, `:julc-tools:adr068Tools`, `:julc-verification:adr068Tools`, and the
   three modules' tests with `-Pjulc.tools.reader=JAVAC`). Before that, decide `JavaMetadataExtractor`'s subset
   diagnostics (open item 1).
2. **Delete the tools' JavaParser halves.** `LintRule.check(CompilationUnit)` and each rule's JavaParser method (a public
   interface change for any rule written outside JuLC: note it in the release notes), `LintEngine.Reader`,
   `TestDiscovery`'s and `EvaluateTool`'s JavaParser readings, `JavaMetadataExtractor`'s JavaParser path and its
   `SubsetValidator` call, `SourceAnnotations.readWithJavaParser`, the `julc.tools.reader` property and its line in the
   root `build.gradle`. Then the `javaparser-core` lines of `julc-cli`, `julc-tools`, `julc-verification` and
   `julc-playground`, and the 119 JavaParser entries in each of `julc-cli`'s and `julc-tools`' native-image
   `reachability-metadata.json`.
3. **Port the shared discovery in `julc-compiler`**, each with its own equivalence gate on the corpus (this slice did not
   do these): `JavaSourceIntrospector` (`inspect`: package, top-level names, the validator and library types, role
   conflicts, on `JavacSyntax`; `declaresNoJulcRole` and `declaresNoTestMethod` disappear, since javac's is the only
   reading), and `LibrarySourceResolver.parseSourceContext` and `extractTopLevelTypeName` (on `JavacUnits` or
   `JavacSyntax`). The gradle plugin, the annotation processor, the testkit, the CLI's `ProjectScanner` and Elci's
   `LibrarySources.resolve` follow through these APIs without a change of their own.
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
