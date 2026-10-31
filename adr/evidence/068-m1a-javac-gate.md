# ADR-068 M1a: the javac gate

The M1a evidence of [ADR-068](../068-javac-frontend-migration.md): `JavacFrontend` (D3), D5's diagnostics, and the
gate that runs javac before the JavaParser frontend on every entry point (M1). The dialect rewrite (M1b), D6's
comparison rules and `JULC0091` (M1c) are not part of M1a. Numbers below were measured on the machine and toolchains
listed; a number that is derived says so.

## Revisions

| Item | Value |
|---|---|
| Base | `42e7bd51` (PR #267 head after M0) |
| M1a, first round | `e361460a`, `193a121b`, `bf985b8a`, `aa7fc6bc`, `6c57289f` |
| M1a, review round 1 (F400–F403, F500–F505) | `d96d8350` (fixes), `e13eb24c` (probe, timing), `1d24008e` (this file) |
| M1a, review round 2 (F405–F406, F510–F513, two boundary gaps) | `27161eb8`, `81365fb1` (this file) |
| M1a, review round 3 (F520 by D8's "Whole units" amendment, F521) | `8172eb41`, and the commit updating this file |
| Evidence measured at | `8172eb41` for tests, byte identity, gate totals and the Web Image probe; native CLI and `wasmSmokeFull` at `27161eb8`; timing at `e13eb24c` (rounds 2 and 3 did not change the analysis path's cost) |

## Toolchains

| Item | Value |
|---|---|
| Machine | Apple M4 Max, 16 cores, 128 GB, macOS 26 (arm64) |
| JDK (Gradle daemon, toolchain, tests, blob generation) | BellSoft Liberica 25.0.2+12-LTS |
| Gradle | 9.2.0 (wrapper), `--offline` |
| Native CLI | Oracle GraalVM 25.0.2+10.1 (`~/.sdkman/candidates/java/25.0.2-graal`) |
| Web Image | Oracle GraalVM 25.3.4.1-dev (JDK 25.0.4.1.1), the build ADR-065's spike used; Node v22.12.0; wasm-opt 132 |
| Corpus | ADR-068 M0's recording, regenerated at `42e7bd51` by M0's command 2: 11,196 case keys, the M0 count |

## What M1a adds

- **`JavacFrontend`** (`julc-compiler/.../frontend/javac/`): a `JavacTask` from `ToolProvider.getSystemJavaCompiler()`,
  options `-proc:none -source 21 -implicit:none -Xlint:none`, locale `en`, over `EmbeddedFileManager`:
  `SYSTEM_MODULES` is the signature-only `java.base` blob, `CLASS_PATH` the API stubs, `SOURCE_PATH` the pool. It
  checks the host javac (`JULC0089` unless its highest source version is `RELEASE_25`), runs the ownership check by
  provenance (`JULC0081`) and maps javac errors to `JULC0085` with key, position (character-offset columns) and D5's
  hints. `JavacEnvironment.current()` exposes the javac version and the two digests (F401).
- **The blob and stubs**, generated at build time, embedded uncompressed:
  - `platform.bin`: 69 class files (module-info included), 136,047 bytes, sha256
    `8e5e498ecf0f9aeefd36284cf89629ee0ac9ec39ab9cc80ff26226866d9dc53f`. The list
    (`julc-compiler/javac-platform/java-base.list`) is derived: the `java.base` classes javac opens while attributing
    49 in-repo source groups (stdlib libraries, ledger, verification fixtures) and the 11,196 corpus cases against the
    whole exported `java.base`, closed under supertypes. The module-info is synthesized (`java.base` exporting exactly
    the blob's packages): the JDK's own records module hashes, its version and qualified exports, which differ between
    builds. With it the blob is byte-identical on six JDK 25 builds: 25.0.1 and 25.0.2 GraalVM, 25.0.2 Liberica, 25.0.3
    Liberica NIK, 25.0.4 Liberica (FX) and 25.0.4 Oracle GraalVM 25.3. `check` runs `verifyJavacPlatform` (digest
    drift) and `checkJavacPlatformDerivation` (in-repo sources needing a class outside the list).
  - `stubs.bin`: 251 class files, 257,392 bytes, sha256
    `37b9434d1f0708ccbc2ff03e190f5778ada0e95c67c1fdf7ae88080872616050`: all of julc-core and julc-ledger-api,
    julc-stdlib's annotations, `Builtins`, `CryptoProvider` and `test.Test`, and
    `org.julclang.verification.annotation`; no `@OnchainLibrary` class, no `StdlibRegistry`, `ListsLibHof` or
    `StdlibLibraryProvider`. Method bodies and private members are dropped; `PermittedSubclasses`, `Record`,
    `Signature`, annotations and constant values are kept (tested; the order of all 12 sealed ledger types equals the
    JavaParser frontend's).
  - julc-stdlib ships `META-INF/julc/bundled-sources.sha256`, the 16 bundled sources with their digests.
- **The gate**: every `JulcCompiler` entry point and `JavaLibraryProvider` runs javac first. Mode:
  `CompilerOptions.setJavacGate`, else `-Djulc.javacGate=off|report|enforce`, else `report`. `report` never fails a
  compilation; with `-Djulc.javacGate.report=<dir>` it records each would-be rejection, best-effort. Results are cached
  by an unambiguous digest of the inputs (length-prefixed UTF-16), inside the frontend instance whose immutable
  environment (platform, stubs, bundled set) produced them; the empty provider's metadata-only task is built once and
  synchronizes its own queries (`JavacMetadata.read`).
- **Pool precedence** (D8, with its "Whole units" amendment): precedence is decided before javac over every
  top-level declaration of every unit. A pool unit declaring a root's name is *shadowed*: it is not read for that
  name, and a compilation that needs another of its declarations is rejected with `duplicate class`, naming both
  declarations. Two pool units of equal standing declaring one qualified name are rejected before javac runs,
  referenced or not, in either order. The gate's pool carries no origin: hosts apply same-project over JAR when they
  build the pool (keyed by primary name), so every unit a host passes is of equal standing; the bundled sources come
  last, and a host unit declaring a bundled name is `JULC0081`. Identical texts are one unit.
  `LibrarySources.readJars` checks declared names, not only entry paths, the same way.
- **Diagnostics**: `JULC0084`–`JULC0091` (0085, 0089 emitted; 0084 internal; the rest planned); `JULC0081`
  generalized.

## Commands

```sh
# Tests (the run below also wrote the gate report; the report property changes nothing else)
./gradlew test --offline --continue --rerun -Pjulc.javacGate.report=<dir>
./gradlew :julc-compiler:javacGateSummary -Pjulc.javacGate.report=<dir>

# The corpus: M0's command 2 at 42e7bd51, then the gate over it and the byte-identity baseline
./gradlew :julc-compiler:test :julc-stdlib:test :julc-testkit:test :julc-difftest:test :julc-examples:test \
    --offline --continue --rerun -Pjulc.corpus.record=<corpus>          # at 42e7bd51
./gradlew :julc-compiler:javacGateCorpus -Pjulc.javacGate.corpus=<corpus> -Pjulc.javacGate.out=<dir>
./gradlew :julc-difftest:adr068Baseline -Padr068.corpus=<corpus> -Padr068.out=<dir> --offline   # at 42e7bd51 and at M1a

# Platform checks and re-derivation
./gradlew :julc-compiler:verifyJavacPlatform :julc-compiler:checkJavacPlatformDerivation --offline
./gradlew :julc-compiler:deriveJavacPlatform :julc-compiler:updateJavacPlatformDigest -Pjulc.javacPlatform.corpus=<corpus>

# Timing (E6) and hosts
./gradlew :julc-difftest:adr068GateTiming -Padr068.corpus=<corpus> -Padr068.out=<dir>
GRAALVM_HOME=<GraalVM 25.0.2> ./gradlew :julc-cli:nativeCompile
./gradlew :julc-wasm:wasmSmokeFull -PgraalvmHome=<GraalVM 25.3>
./gradlew :julc-wasm:wasmGateProbe -PgraalvmHome=<GraalVM 25.3>
```

## Tests

`./gradlew test --offline --continue --rerun` at `8172eb41` (with the report property): **12,491 tests, 0 failures,
0 errors, 530 skipped** (XML totals), `BUILD SUCCESSFUL in 13m 53s`. M0's head had 12,411; the first M1a round
12,469; review round 1, at `e13eb24c`, 12,483; review round 2, at `27161eb8`, 12,489.

The gate's own suites: `JavacFrontendTest` 24, `JavacGateTest` 9, `JavacGateReviewTest` 17, `JavacGateEnvironmentTest`
3, `JavacStubsTest` 6, `JavacNativeImageMetadataTest` 3, one adapter test class per host (testkit, Gradle plugin,
annotation processor, CLI, playground, `julc-wasm` on the JVM; 2 tests each) and `JavacGateProbeTest` 1.

## Byte identity

`adr068Baseline` over the corpus at the base (gate absent), at `e13eb24c`, `27161eb8` and `8172eb41` (gate in
`report`, the default): the
ledger (`ledger-b0.tsv`: FLAT digest per case at the four levels, 10,960 cases), budgets, rejection inventory, totals,
corpus listing and equality scan are **byte-identical**, and equal to the checked-in M0 files. One row of
`ledger-b0-semantic.tsv` differs, the JVM-side failure digest of `reject-guard-reference-cast`: it took three different
values in six runs, including two runs of the unchanged base, so it is a nondeterministic JVM observation of that
case, not a gate effect. The JavaParser frontend's output does not depend on the gate.

## Gate totals over the corpus

`javacGateCorpus` over the 11,196 corpus cases, with the shipped blob, stubs and bundled set; the summary and the
per-case file are byte-identical at `e13eb24c`, `27161eb8` and `8172eb41`: no corpus case declares a name in two pool
units, so F521 changes no recorded outcome (generated:
[`068/m1a-gate-corpus-summary.txt`](068/m1a-gate-corpus-summary.txt), and per case, with its entry point, keys, first
diagnostic and originating tests, [`068/m1a-gate-corpus.tsv`](068/m1a-gate-corpus.tsv)):

- **6,385 cases rejected** (5,929 distinct root sources), 4,811 accepted.
- **0 cases** whose diagnostics differ when the derived blob is replaced by the whole exported `java.base`: the
  derived platform changes nothing on the corpus.

| javac key, or JuLC code | cases | roots | diagnostics |
|---|---:|---:|---:|
| `compiler.err.cant.resolve.location` | 3,658 | 3,277 | 27,375 |
| `compiler.err.cant.resolve` | 3,002 | 2,649 | 3,026 |
| `compiler.err.non-static.cant.be.ref` | 2,220 | 2,211 | 3,905 |
| `compiler.err.prob.found.req` | 1,706 | 1,633 | 1,950 |
| `compiler.err.cant.resolve.location.args` | 1,401 | 1,391 | 1,442 |
| `compiler.err.pattern.dominated` | 718 | 718 | 978 |
| `compiler.err.operator.cant.be.applied.1` | 285 | 257 | 556 |
| `compiler.err.duplicate.default.label` | 143 | 143 | 163 |
| `compiler.err.not.exhaustive` | 140 | 140 | 140 |
| `compiler.err.guard.has.constant.expression.false` | 44 | 43 | 44 |
| `compiler.err.doesnt.exist` | 43 | 41 | 49 |
| `compiler.err.int.number.too.large` | 27 | 27 | 27 |
| `compiler.err.already.defined`, `compiler.err.cant.deref` | 18 each | 18 each | 18 each |
| `compiler.err.cant.ref.non.effectively.final.var`, `foreach.not.applicable.to.type` | 10 | 10, 6 | 10, 22 |
| `compiler.err.cant.apply.symbols` | 9 | 9 | 9 |
| `compiler.err.undef.label` | 7 | 7 | 7 |
| `illegal.esc.char`, `illegal.unicode.esc`, `incomparable.types`, `match.binding.exists`, `switch.mixing.case.types`, `cant.infer.local.var.type` | 6 each | 6 each | 6–7 |
| `premature.eof`, `unclosed.str.lit` | 5 each | 5 each | 5, 7 |
| `flows.through.from.pattern`, `illegal.digit.in.octal.literal`, `rule.completes.normally` | 4 each | 4 each | 4 each |
| `cant.apply.symbol`, `duplicate.unconditional.pattern`, `flows.through.to.pattern`, `not.def.public.cant.access` | 3 each | 3 each | 3–11 |
| `cant.inherit.from.sealed`, `feature.not.supported.in.source.plural`, `switch.expression.completes.normally`, `unreachable.stmt` | 2 each | 1–2 | 2–4 |
| `annotation.missing.default.value`, `cannot.assign.not.declared.guard`, `cant.assign.val.to.var`, `duplicate.class`, `illegal.char`, `illegal.text.block.open`, `not.stmt` | 1 each | 1 each | 1 each |
| **`JULC0081`** | 1 | 1 | 2 |

The `JULC0081` case is a `Javac21ParityTest` probe declaring `org.julclang.ledger.Value`; the `duplicate.class` case
is `LibraryDescriptionsTest#duplicateSourceOwnershipRejected`, a negative fixture. Most of the
`non-static.cant.be.ref` cases (2,163) come from `BinderNameIndependenceTest`'s renamings. Negative fixtures are among
the rejections on purpose; M1b classifies the migration cases.

The test-suite run's own report (every input the suites compile, not only the frozen corpus) at `27161eb8`: 6,003
rejected inputs, 5,997 distinct roots (one more than round 1: the new tests' inputs), 0 internal gate failures, the
same key distribution; the same at `8172eb41`. No existing test or fixture relied on a pool holding two units of one
name: the full suite and the corpus are unchanged by F521.

## Hosts

All built without `--add-exports`/`--add-opens`; the one file naming JDK internals is julc-compiler's
`META-INF/native-image/org.julclang/julc-compiler/reachability-metadata.json`, whose 4 types, 1 method and 2 bundles
`JavacNativeImageMetadataTest` checks against the build JDK (with a negative test).

| Host | Result |
|---|---|
| JVM library, testkit, Gradle plugin (TestKit), annotation processor (nested javac), CLI, playground, `julc-wasm` API | Adapter tests green: each accepts Java with its own pool under `enforce` and rejects a javac error with `JULC0085` |
| Native CLI (`:julc-cli:nativeCompile`) | Builds: **107,565,176 bytes** at `27161eb8` (107,548,632 at `e13eb24c`), against 90,570,776 at the base: +16,994,400 (+18.8%). `julc -Djulc.javacGate=enforce build` accepts a project with a stdlib-using, `@RequiresSigner` validator and a project library; the dialect `fee() > minimum()` fails the build with `JULC0085 … (compiler.err.operator.cant.be.applied.1)`; with the default mode it builds as before |
| `julc-wasm` full image (`wasmSmokeFull`) | Builds: **35,234,313 bytes raw, 12,316,460 gzip-9, 8,746,973 brotli-11** (at `e13eb24c`), against 24,790,854 / 8,659,188 / 6,119,533 at the base: +10.4 MB raw (+42%), +2.63 MB brotli (+43%). The full smoke passes, also at `27161eb8`; at `e13eb24c` one of three runs hit the smoke's request timeout on its last step (VM evaluation, no compilation) while the machine's load average was about 80; the two reruns passed |
| Web Image gate probe (`wasmGateProbe`) | `JavacGateProbe` (julc-wasm test sources), built as a Web Image (26,603,980 bytes at `27161eb8`; rerun green at `8172eb41`) from julc-wasm's class path and run under Node, gives the JVM's lines exactly: environment `supported=true`, the two digests above; `FeeGate` (stdlib) accepted, 249 bytes, FLAT `df968e25…`; `OwnerGate` (`@RequiresSigner`) accepted, 269 bytes, FLAT `46a2c36b…`; the dialect rejected `JULC0085 FeeGate.java:13:35 … (compiler.err.operator.cant.be.applied.1)` |
| Elci native shape | Not run (external) |

## Timing (E6)

`adr068GateTiming`: 472 sampled corpus cases (every 20th accepted string-entry case), 3 rounds after a warm-up.

| Run | Warm: JavaParser-only / javac gate per case | Gated / JavaParser-only | Cold, first compile in a fresh JVM, gate off → report | Cold `new LedgerTypeProvider()`, off → report |
|---|---|---|---|---|
| 1 (first round, quiet machine) | 8.5 ms / 2.2 ms | **1.26** | 243–312 ms → 389–560 ms | — |
| 2 (review round, load ≈ 70) | 11.8 ms / 2.8 ms | **1.24** | 318–597 ms → 584–917 ms | 314–356 ms → 404–420 ms |
| 3 (load ≈ 88) | 16.3 ms / 3.9 ms | 1.24 | noisy (277–1,721 → 501–1,025) | noisy |

Warm compile is 1.24–1.26× the JavaParser-only path (E6: ≤ 2×). Cold, the gate adds about 150–300 ms to a process's
first compilation (javac's classes and the blob load); the review sets the cold threshold.

**F402, the cost of `report` by default.** Every compilation pays javac's analysis (warm +24–26%, about 2–4 ms per
case) and every process its first-use cost; the empty provider's metadata task adds about 60–100 ms once per process
(run 2), and is now built once. `report` returns nothing to a user who sets no report directory. It stays the
default until M1b's rewrite lands, when M1 switches the default to `enforce`.

## Deviations, with the review's assessment

| # | Deviation | Review (round 1) |
|---|---|---|
| 1 | D3's supported javac is checked as "the highest source version is `RELEASE_25`" (the brief said "includes") | D3 r3's wording: accepted |
| 2 | D3's compile metadata was only logged and on `JavacResult` | Acceptable in report mode; expose it additively before enforce (F401: `JavacEnvironment.current()` now; per-result metadata is an M1 exit item, below) |
| 3 | The pool is the libraries an entry point receives, then the bundled sources (the stdlib lookup resolves some `ContextsLib`/`ListsLib`/`MathLib` methods without sources) | Acceptable for the gate; M1-only, M3 uses D8's per-entry-point pools (F404, recorded in the ADR) |
| 4 | The blob's module-info is synthesized | Sound: accepted |
| 5 | javac's `getRecordComponents()` does not complete a class symbol | Accepted; M2's I11 tests must reach elements through `Elements.getTypeElement` |
| 6 | Secondary top-level types were found only after their unit was read | Was javac's own source-path behaviour; Codex's F502 asked for every declaration to be found, F511 for precedence per qualified name: both done (below) |

## Review round 1 (F400–F403, F500–F505)

| Finding | Change | Evidence |
|---|---|---|
| F500 cache-key collision | `JavacGate.key`: group and unit counts, null-path tags, length-prefixed path and text bytes | `JavacGateReviewTest#cacheKeyDistinguishesUnitBoundariesAndPaths`, `#aCollidingPrimedResultCannotAcceptAProviderInEnforceMode` (Codex's `A//` + `B` probe through `JavaLibraryProvider`) |
| F501 report writing fails compiles | `JavacGateReport`: directory parsing, `record` and `recordFailure` best-effort; first failure printed to stderr | `#anUnwritableOrInvalidReportPathNeverFailsACompilation` (`/dev/null`, a file, below a file, a NUL path) |
| F502 secondary declarations | Pool units listed under every top-level name (one URI, read once); precedence over all declarations, roots first | `#aSecondaryTopLevelTypeOfAPoolUnitResolves`, `#aRootWinsOverEveryDeclarationOfAPoolUnitWithoutDuplicateClass`, `#compileMethodWithASecondaryPoolTypeIsAcceptedInEnforceMode` |
| F503 malformed units | Only well-formed names; synthetic `Unit_<digest>`; URIs built by the quoting constructor; javac's parse errors are `JULC0085` | `#malformedDeclarationsAreJulc0085` (`class {}`, `package 1x;`, `@interface {}`, `package a.;`, a malformed pool unit) |
| F504 metadata test checked no method | Structural JSON reading; counts 4 types, 1 method, 2 bundles | `JavacNativeImageMetadataTest#everyEntryExistsInThisJdk`, `#aMissingMethodFailsTheCheck` |
| F505 hints | From javac's parsed unit: annotation-type spans and JDK wildcard imports | `#wildcardImportsAndCommentedAnnotationsGetHints` |
| F400 evidence | This file, the two generated files, `wasmGateProbe` + `JavacGateProbe` in the repo | Above |
| F401 compile metadata | `JavacEnvironment.current()` (additive, I5-safe) | `#environmentReportsVersionAndDigests` |
| F402 metadata task cost | Built once per process; cost documented above; default stays `report` | `#metadataTaskIsCachedPerProcess` |
| F403 failure paths | Missing blob: `BrokenJulcBuildException` ("broken JuLC build"), never `JULC0089`; julc-stdlib without its manifest, or a bundled source missing or off its digest: loud failure; an empty bundled set is looked up again | `#aMissingBlobIsABrokenBuildNotAnUnsupportedJavac`, `#stdlibWithoutItsManifestFailsLoudly` |
| F404 | No code change; the coordinator records deviation 3's scope (M1-only pool; M3 uses D8's per-entry-point pools) in the ADR | — |

## Review round 2 (F405–F406, F510–F513, boundary gaps)

| Finding | Change | Evidence |
|---|---|---|
| F510 / F405 results cached across a changed environment | The result cache lives in each `JavacFrontend` instance, whose environment (platform, stubs, bundled set) is immutable; `JavacGate` selects the frontend before the lookup, so an analysis running in a replaced environment fills only that environment's cache | `JavacGateEnvironmentTest#resultsAreScopedToTheEnvironmentThatProducedThem`: a JVM without julc-stdlib, then julc-stdlib through the context loader (bundled 0 → 16); a stdlib use goes from `doesnt.exist` to accepted, an `org.julclang.stdlib.lib` declaration from accepted to `JULC0081` |
| F511 whole-unit drop | Precedence per qualified name (`Precedence`), superseded in round 3 by D8's "Whole units" rule (below) | Round 3's tests |
| F512 surrogate collision | Gate keys and `JavacUnits` identities hash length-prefixed UTF-16 code units (`JavacPlatform.identity`); `CorpusRecorder.bytes` stores a text whose UTF-8 starts with its marker in the marker form, so stored forms identify texts (no source starts with the marker: no M0 digest changes, the M0 files above are reproduced) | `#aSurrogateTextAndItsStoredFormNeverShareAKey` (Codex's `Probe500` probe) |
| F513 hint wording | Under a JDK wildcard import, only the known `java.util` collection types get the categorical hint; other names get "If X is a JDK type …, it is not available on-chain …; otherwise check the import or the source that declares it" | `#anUnknownNameUnderAJdkWildcardImportGetsConditionalWording`, `#wildcardImportsAndCommentedAnnotationsGetHints` |
| F406 metadata locking | `JavacMetadata` exposes only `read(BiFunction<Elements, Types, T>)`, synchronized on itself; model objects are used inside the query | `#metadataTaskIsCachedPerProcessAndLocksItself` (64 queries on 8 threads); `JavacFrontendTest#metadataTaskExposesOnlyElementsAndTypes` (the surface) |
| Empty manifest | A manifest listing no source, with julc-stdlib present, is `BrokenJulcBuildException` | `JavacGateEnvironmentTest#anEmptyManifestWithStdlibPresentIsABrokenBuild` |
| Loader denying the compiler API | The no-compiler fallback no longer references `JavacFrontend` | `JavacGateEnvironmentTest#aLoaderWithoutTheCompilerApiIsJulc0089`: an isolated loader denying `javax.tools`, `com.sun.source` and `javax.lang.model`; report returns `JULC0089`, enforce throws it |

## M1 exit items

- **Per-compilation javac metadata (D3).** `CompileResult` is an I5 record, so a component cannot be added. Proposal:
  record `JavacEnvironment` in the outputs that already carry compiler identity once the default is `enforce` (the CLI
  artifact metadata and the blueprint's `preamble.compiler`), which changes those outputs and so belongs to the
  switch, and keep `JavacEnvironment.current()` for API users.
- **The cold-compile threshold** (E6), set in review from the table above.
- **Default `enforce`** after M1b.

## Review round 3 (F520, F521)

| Finding | Change | Evidence |
|---|---|---|
| F520 shadowed units (answered by D8's "Whole units" amendment) | A pool unit declaring a root's name is shadowed and not read for it; a compilation needing another of its declarations is rejected with javac's `duplicate class`, whose message now names both declarations ("declared by Main.java and Helper.java; the root's declaration shadows the library source's, and the program needs another declaration of that source"), at javac's position | `JavacGateReviewTest#needingAnotherDeclarationOfAShadowedUnitIsRejectedNamingBoth` (root `Shared`, pool `{Helper, Shared}`: rejected when the root uses `Helper`, accepted when it does not); `#aRootWinsOverEveryDeclarationOfAPoolUnitWithoutDuplicateClass` |
| F521 equal-standing duplicates | Two pool units declaring one qualified name are rejected before javac, over every top-level declaration, referenced or not; `LibrarySources.readJars` checks declared names (read by the gate's parser) as well as entry paths | `#twoPoolUnitsDeclaringOneNameAreRejectedInEitherOrder` (pool `{Foo, Shared}` and `{Bar, Shared}`, both orders, roots `class Main {}`, using `Bar`, using `Foo`); `#readJarsRejectsTwoJarsDeclaringOneName` (both jar orders, and a non-overlapping pair accepted); `JavacFrontendTest#rootWinsOverAPoolUnitOfTheSameName` |

