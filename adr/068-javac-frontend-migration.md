# ADR-068: Replace JavaParser with javac as JuLC's only Java frontend

**Status:** Proposed (r1)
**Date:** 2026-10-30
**Label:** `semantic-consistency`
**Risk class:** correctness-sensitive, **high**. The frontend decides what every validator means; a wrong
binding, type or literal is a silent miscompile.
**Programme:** a step of [ADR-062](062-jvm-uplc-semantic-consistency-programme.md) (JVM–UPLC semantic
consistency). It follows the spike of [ADR-065](065-javac-frontend-spike.md), whose decision it takes.
**Relates to:** [ADR-060](060-generated-binder-namespace.md) (name binding),
[ADR-061](https://github.com/bloxbean/julc/pull/203) (control flow, proposed),
[ADR-063](063-differential-jvm-uplc-test-harness.md) (differential harness),
[ADR-066](066-generator-g1-records-sealed-switch.md) (generator G1),
[ADR-067](067-switch-guards-record-patterns.md) (switch guards and record patterns),
[ADR-056](056-language-neutral-compiler-backend.md) and [ADR-059](059-generic-backend-revision-2.md) (backend),
[ADR-054](054-playground-webassembly-engine.md), [ADR-057](057-julc-wasm-distribution.md) and
[ADR-064](https://github.com/bloxbean/julc/pull/252) (browser engine)
**Base:** **B0** = `5a72d7a6`, the head of PR #267 after merging #281 (ADR-067 M1, with #253 and #250), #278
(ADR-066 G1 M1–M3 part 1), #271 (T22, T24, T25, with #264 and #269) and #272 (T20, T26). Code citations are at
B0 unless marked.

## Context and problem

A JuLC contract is read twice. javac compiles it for the user's JVM tests and IDE; JuLC parses the same text
again with JavaParser (`com.github.javaparser:javaparser-core:3.26.3`, `julc-compiler/build.gradle:22`) and does
its own import, name, type and constant resolution before lowering the JavaParser AST to PIR. Wherever the two
readers disagree, the JVM tests and the script are different programs.

The ADR-063 harness, which compares javac/JVM against JuLC/UPLC, has found that class of defect repeatedly. Each
one below was found or reproduced by the harness, and each is a disagreement about **what the source means**,
not about how it is lowered:

| Area | Issues | What JuLC does today |
|---|---|---|
| Lexical reading | #256, #268, #270 (T22, T24, T25) | Fixed by hand-written copies of javac's rules: `JavaSourceParser`, `UnicodeEscapes`, `StringLiterals`, `IntegerLiterals` (669 lines at B0) |
| Names and scopes | #209, #284, #286, #291 | String-keyed scopes (`SymbolTable`, a `Deque<Map<String, PirType>>`) and AST renaming (`LoopBodyGenerator.withLocalsRenamed`, `withBindingRenamed`) decide what a name means |
| Types | #287, #290, #292 | No nominal type check for expressions; receiver typing by JuLC's own inference (`TypeInferenceHelper`) |
| Java rules replicated | ADR-067 D4 | `SwitchPatternChecker` (1,077 lines) re-implements dominance and exhaustiveness; `JavaConstants` (434 lines) re-implements JLS §15.29 for guards |

The spike (ADR-065; [`evidence/065-javac-frontend-spike.md`](evidence/065-javac-frontend-spike.md), code on branch
`spike/065-javac-frontend`) measured the cost:

- javac's `JavacTask.analyze()` and `Trees` work on the JVM, in a GraalVM native image (the `julc` CLI grows by
  16.3 MB, +18%), and in the GraalVM Web Image (`julc-wasm` full image +2.6 MB brotli, +43%).
- Every target needs the same thing: a custom `JavaFileManager` that serves an embedded, signature-only platform
  (`java.base` stubs, about 54 KB for what JuLC needs), because javac's standard file manager cannot run without a
  JDK image. With it, javac reads nothing else (negative control in the evidence file, Step 2).
- A real validator (`Cip68Nft.java`) and all 16 stdlib `@OnchainLibrary` sources analyze with no errors. Output
  was identical across JVM, native and Wasm, and across JDK 25.0.2 and 25.0.4.
- javac's diagnostics replace JuLC's uncoded parse errors with the messages the IDE shows (demonstrated in the
  browser demo).

The maintainer has decided to migrate: JuLC is a preview with no production users, so script hashes may change.
This ADR records how, under which invariants, and in which order.

## Goals and non-goals

**Goals**

- One reader: javac parses and attributes every Java source JuLC compiles, on every target (JVM library, Gradle
  plugin, annotation processor, testkit, CLI and its native image, MCP lint, playground, `julc-wasm`, Elci's
  `JavaLibraryProvider`).
- Names, types, overload resolution and literal values come from javac. JuLC's replicas of those rules are
  deleted.
- Lowering semantics stay JuLC's own and stay correct: every change in generated behaviour is attributed,
  issue-linked and harness-checked (I1).
- KISS: javac trees are lowered to PIR directly, as JavaParser trees are today. No new intermediate
  representation.
- Remove JavaParser from every module once the javac frontend is the default.

**Non-goals**

- Widening the Java subset. javac accepting a construct never makes it a JuLC construct (I2).
- Redesigning loop lowering. ADR-061 owns that; this ADR ports the lowering that exists when M4 starts.
- Changing integer semantics outside what ADR-067 already decided (I9; T6 stays an ADR-062 S5 decision).
- Changing the backend (`compiler/backend/`), PIR optimization, UPLC generation, serialization or the VMs.
- Hosting the lowering inside the user's javac (ADR-065 option B). Deferred (open question 3).

## Current behavior

### JavaParser use at B0

Counted with `grep -rl com.github.javaparser <module>/src/main` and `wc -l` at B0:

| Module | Main files | Lines |
|---|---|---|
| `julc-compiler` | 24 | 14,127 |
| `julc-cli` (lint rules, MCP) | 20 | 2,332 |
| `julc-tools` (`JavaMetadataExtractor`, REPL) | 2 | 1,107 |
| `julc-verification` (resolvers) | 3 | 677 |

The largest compiler files are `PirGenerator` (3,351), `JulcCompiler` (1,902), `SwitchPatternChecker` (1,077),
`LoopBodyGenerator` (860), `TypeResolver` (777), `LibrarySourceResolver` (583), `TypeRegistrar` (562),
`AccumulatorTypeAnalyzer` (535), `PirHelpers` (496), `DebugMetadataCollector` (466), `TypeInferenceHelper` (443) and
`JavaConstants` (434). No JavaParser symbol solver is used: all resolution is JuLC's.

### The backend boundary is already clean

The Java frontend hands a `PirTerm` with its positions and provenance to `PirBackend.lower`
(`JulcCompiler.java:673`). `FrontendProgram` and `LibraryProvider` are PIR-only. UPLC FLAT
uses de Bruijn indices, so the names JuLC chooses for binders do not reach the bytes.

### JuLC's dialect beyond Java

JuLC accepts source that javac rejects. A probe at B0 (`JulcCompiler.compile` on a `@SpendingValidator` with a
helper method, scratch program, JDK 25.0.2) accepts all of:

| Source | javac | JuLC at B0 |
|---|---|---|
| `x > y`, `x + y` for `BigInteger x, y` | `operator.cant.be.applied` | accepted, lowered to integer builtins |
| `BigInteger f() { return 5; }` | `incompatible types: int cannot be converted to BigInteger` | accepted |
| `x == y` for `BigInteger` or `byte[]` | accepted: **reference** equality on the JVM | accepted, lowered to value equality |

The first two are dialect; the third is a JVM–UPLC divergence of the same kind as #289 (`String ==`).

### Constructor tags

A sealed interface's variant tags follow its `permits` clause (`TypeResolver.registerSealedInterface`,
`TypeResolver.java:159`). Without one, they follow the records nested in the interface, in declaration order
(`TypeRegistrar.java:243`). Datum and redeemer encodings depend on that order.

## Invariants

- **I1. Behaviour is preserved, or the difference is attributed.** For every corpus program (M0) at every
  optimization level and target, the javac frontend's output is compared with the JavaParser frontend's at the
  reference base (B0, or the head the milestone is stacked on):
  - **I1a, whole programs:** FLAT bytes and script hash equal.
  - **I1b, single methods:** the PIR term and type of every lowered method equal (`PirTerm`/`PirType` equality,
    as `OnchainOverloads` uses). Libraries are lowered whole (`LibraryCompiler`), so I1b covers library methods.
  - **Attribution.** A difference is allowed only when it is on the milestone's attribution list, with (i) the
    issue it closes or the decision that causes it, and (ii) for a behaviour change, a saved `.diffcase` on which
    the javac frontend agrees with the JVM. A difference that only changes cost (for example D6's comparison
    rule) carries the cost and size delta instead. An unattributed difference blocks the milestone.
- **I2. The subset does not widen.**
  - **I2a.** Every construct the JavaParser frontend rejects with a `JULC` code is rejected by the javac
    frontend with the same code, unless javac itself now rejects it (I2b) or this ADR names the change. Positions
    are derived from javac's `SourcePositions` for the node that corresponds to JuLC's anchor (ADR-065 I2a).
  - **I2b.** Source javac rejects is rejected with javac's diagnostic, mapped to a JuLC diagnostic with javac's
    message, key and position.
  - **I2c.** The subset validator is an allow-list over `Tree.Kind`: a kind it does not know is rejected. New Java
    syntax therefore stays rejected until a decision adds it.
- **I3. Names bind as Java binds them.** Each source name lowers to the binder of the `Element` javac binds it to
  (ADR-060). Generated binders keep the `#` namespace; `GeneratedBinderNameLintTest`,
  `BinderNameIndependenceTest` and `julc.verifyBinderNamespace` stay green.
- **I4. Determinism.** The same sources and JuLC version give the same bytes on every host and every supported
  JDK. The language level is pinned (D3), javac sees only the closed compilation environment (I10), and CI checks
  the corpus on two JDK releases.
- **I5. Public API shape.** These keep their signatures and source-text inputs:
  - `JulcCompiler.compile(String, List<String>)`, `compileMethod`, `compileContract`, and the path-based entry
    points;
  - `JavaLibraryProvider(List<String>, StdlibLookup, CompilerOptions)`, which Elci uses;
  - the testkit's `JulcEval.forSource` and `forClass`.
- **I6. Exported APIs only.** `javax.tools`, `javax.lang.model` and `com.sun.source`, through
  `ToolProvider.getSystemJavaCompiler()`. No `com.sun.tools.javac.*`, `--add-exports` or `--add-opens`.
- **I7. Source provenance.** Source-map and debug-metadata locations (file, line, column) are attached while the
  term is built (ADR-060), from javac's `SourcePositions` and `LineMap`. Each difference from the JavaParser
  frontend's locations on the corpus is listed under I1.
- **I8. One reader, no silent fallback.** While both frontends exist, the frontend is chosen explicitly (D4). The
  javac frontend never hands a program, or part of one, to the JavaParser frontend. After M6 no target contains
  JavaParser.
- **I9. Integer semantics are unchanged.** Literal values come from `LiteralTree.getValue()`, which matches T25's
  decoding. Constant expressions and static-field initializers are lowered with unbounded integers, as today.
  Inside `switch` guards, ADR-067's rule stands: the constant's value is javac's
  (`VariableElement.getConstantValue()` and javac's folding replace `JavaConstants`), limited to ADR-067's
  verified kinds (boolean, `int`, `long`, `String`), with the same rejections (`JULC0072`).
- **I10. A closed compilation environment.** javac sees exactly:
  - the user's sources and the `@OnchainLibrary` sources they use, on the source path;
  - signature-only class files for JuLC's on-chain API (`julc-core`, `julc-ledger-api`, `julc-stdlib`);
  - a signature-only platform: the `java.base` types that API needs (D3).

  It never reads the host's `jrt:/`, `ct.sym` or the user's class path. Classpath visibility never implies
  on-chain support.
- **I11. Constructor tags and encodings are unchanged.** For every sum type in the corpus, the variant order,
  field order, `@NewType` set and encoding chosen by the javac frontend equal the JavaParser frontend's (M2 gate).
  Tags follow the `permits` clause, as today. Without a `permits` clause, today's rule stands: the records nested
  in the interface, in declaration order (`TypeRegistrar.java:243`, `TypeResolver.registerSealedInterfaceFromVariants`).
  javac permits every subclass in the compilation unit, so where javac's `getPermittedSubclasses()` is not exactly
  that list (a top-level record in the same file implementing the interface), the interface is rejected with a
  diagnostic asking for an explicit `permits` clause, rather than given a tag order JuLC did not choose.

## Decisions

### D1. Migrate: javac is JuLC's only Java reader

javac (`JavacTask.parse()` and `analyze()`, never `generate()`) reads and attributes all Java source JuLC
compiles, on every target. JavaParser is removed (M6). This answers ADR-065's question with GO: the spike showed
every target can host javac (E4), the benefit is measured by the harness (the table in Context), and the
maintainer accepts hash changes.

### D2. The seam: javac trees lowered to PIR directly, behind one facts service

The lowering walks javac's attributed `com.sun.source.tree` trees and produces PIR, as it walks JavaParser trees
today. All access to javac's attribution goes through one JuLC class, `JavacFacts`:

- tree → `Element`, and `Element` → JuLC binder name (stable per element, chosen at first use, ADR-060);
- tree → `TypeMirror`, and `TypeMirror` → `PirType`;
- literal values (`LiteralTree.getValue()`) and guard constant values (I9);
- source positions, lines and columns.

`JavacFacts` is a service over javac's own tree, not a representation: no tree is copied or rewritten.
Statement lowering takes the rest of the block as a continuation, so the AST rewrites that JavaParser lowering
uses for shadowing (`withLocalsRenamed`, `withBindingRenamed`) are not needed: binding is by `Element`.

Alternatives (ADR-065 OQ9 answered as (a)):

- **A typed JuLC source IR between javac and PIR** (proposed in consultation). Rejected for now. It would
  duplicate javac's attributed tree (AGENTS.md: "Avoid parallel representations"); javac's `Element`s already give
  the stable declaration identities it would add; and ADR-061, which might want a control-flow IR, has not
  decided its representation. If ADR-061 adopts one, it is inserted for statements between javac trees and PIR.
  The expression lowering, about 60% of `PirGenerator`, does not change either way.
- **JavaParser lowering fed with javac's facts** (ADR-065 option C, two parsers, one authority). Rejected. It
  needs an alignment layer between two trees (folded negative literals, synthesized constructors,
  multi-declarator starts, implicit `var` and lambda types), and a wrong alignment is a new silent mis-binding.
  The string-keyed scopes and renames that cause #209 and #291 would remain. Both parsers would stay in every
  image.
- **Building a JuLC IR from JavaParser first** (ADR-065 OQ9 (c)). Rejected: a transitional frontend that delays
  removing the second reader.
- **`com.sun.tools.javac.tree.JCTree`.** Rejected (I6).

### D3. One compilation environment for every host: `JavacFrontend`

`JavacFrontend` builds the `JavacTask` for every entry point:

- **File manager.** A custom `JavaFileManager` (the spike's `EmbeddedFileManager`) serves:
  - `SYSTEM_MODULES`: a signature-only `java.base` platform blob, generated at JuLC build time from the build JDK
    (`tools/MakePlatformBlob.java` in the spike), stored uncompressed (no `Inflater` in Web Image). Its
    allow-list is the `java.base` types referenced by the public signatures of `julc-core`, `julc-ledger-api` and
    `julc-stdlib`, plus `java.lang.Object`, `String`, `Record`, `Enum`, the annotation types and
    `java.math.BigInteger` (open question 1);
  - `CLASS_PATH`: signature-only class files of JuLC's on-chain API, generated at build time the same way;
  - `SOURCE_PATH`: the user's sources and `@OnchainLibrary` sources (from `META-INF/plutus-sources` in JARs,
    from the project, and the stdlib's own sources).
- **Options.** `-proc:none`, `--source 25` (the JDK JuLC is built with; pinned, open question 2), `-implicit:none`,
  `-Xlint:none`, locale `en`.
- **Host independence.** The same blob and options on the JVM, the Gradle plugin, the annotation processor
  (a nested `JavacTask`, the processor still passes source text), the native CLI, the playground, `julc-wasm`
  and Elci. I10 makes acceptance identical across hosts.
- **A negative control** (as in the spike): a test removes `BigInteger` from the blob and requires
  `package java.math does not exist`, so javac provably reads nothing else.
- **Caching.** The parsed blob is cached per process, so `JulcEval.forSource` does not reload it per compile.

### D4. Explicit frontend selection during the migration

`CompilerOptions.frontend` takes `JAVAPARSER` or `JAVAC`, and the Gradle plugin, CLI and testkit expose it. The
default is `JAVAPARSER` until M5 and `JAVAC` after it; M6 removes the option.

- During M2–M4 the javac frontend rejects a construct it has not yet ported with `JULC0084`
  (`FRONTEND_CONSTRUCT_NOT_PORTED`, internal status), naming the construct. It never falls back (I8).
- The harness compares both frontends on every program (D10).
- **While both exist:** a lowering fix lands in the JavaParser frontend first and is mirrored in the javac
  frontend in the same PR; no new language feature is added to the JavaParser frontend.

### D5. javac's diagnostics are JuLC's diagnostics for invalid Java

`analyze()` diagnostics with kind `ERROR` stop the compile. Each becomes a `CompilerDiagnostic` with code
`JULC0085` (`JAVAC_ERROR`), javac's message, its key (for example `compiler.err.cant.resolve.location`) and its
position. A `cant.resolve` for a JDK type outside the platform blob gets a JuLC hint ("`java.util.ArrayList` is
not available on-chain; use `JulcList`"). Warnings are not reported.

This replaces JuLC's uncoded parse errors, `JULC0070` (T25), and the replicated Java rules: ADR-067's dominance,
exhaustiveness and constant checks that javac makes (`SwitchPatternChecker`'s Java half; JuLC's subset rules in
it stay, with their codes).

### D6. JuLC's dialect beyond Java is removed

- **Operators on `BigInteger` and `int` literals as `BigInteger`.** javac rejects them (D5). Every in-repo source
  that uses them (tests, docs, examples) is rewritten to Java (`add`, `compareTo`, `BigInteger.valueOf`) in M1.
- **`compareTo` comparisons are lowered to builtins.** `a.compareTo(b) OP 0` and `0 OP a.compareTo(b)`, for `<`,
  `<=`, `>`, `>=`, `==` and `!=`, lower to one `LessThanInteger`, `LessThanEqualsInteger` or `EqualsInteger` (with
  `not` for `!=`) on `a` and `b` evaluated once, in source order. `a.compareTo(b)` elsewhere lowers as today.
  Without this, Java-correct source costs more than today's dialect. The rewrite is checked with I1 against the
  dialect form's PIR (equal terms), and with harness cases at the boundary values.
- **`==` and `!=` on references.** With javac types, `==` on `BigInteger`, `byte[]`, `String`, records and other
  reference types is rejected with `JULC0086` (`REFERENCE_EQUALITY_UNSUPPORTED`): the JVM compares identities,
  which have no on-chain meaning, so value equality would diverge (#289). The fix: `.equals` (or
  `Arrays.equals`, which JuLC lowers to `EqualsByteString`). `==` on `boolean`, `int` and `long` stays.

### D7. Types from javac

`TypeRegistrar` and `TypeResolver` register records, sealed interfaces, `@NewType` and ledger types from
`TypeElement`s and `TypeMirror`s, not from source text. Ledger types come from the signature class files (record
components, `PermittedSubclasses`), so ledger sources are no longer parsed on every compile. Constructor tags
follow I11. This closes #284 and #286 (type names by simple name) by construction.

### D8. Libraries

- **Discovery.** `@OnchainLibrary` sources are on the source path. The trees to lower are those of the
  compilation units javac attributes because the user's code references them, collected from `ANALYZE` events
  (`-implicit:none` keeps javac from generating anything). That replaces `LibrarySourceResolver`'s text-based
  closure (`extractReferencedClassNames`). A type must not be both on the source path and on the class path.
- **Lowering.** `LibraryCompiler` lowers every included non-generic static method, as today.
- **`JavaLibraryProvider` (Elci).** Same constructor and exports; javac inside. Elci's native image gains the
  blob and the reachability metadata, and drops its JavaParser entries after M6.
- **Overloads.** Still rejected (`JULC0054`); registries key by `"Class.method"`. javac's binding replaces only
  JuLC's lookup of the declaration.

### D9. Snippets and REPL

`compileMethod`, `ExpressionEvaluator` and `JulcEval.forSource` wrap snippets in synthetic classes, which now
type-check in full. Wrappers are written so that positions map back to the user's text by a fixed offset, and
javac diagnostics are reported at the user's position.

### D10. The harness compares frontends

`julc-difftest` compiles each case with both frontends while both exist:

- `UplcExecutor`'s cache key gains the frontend (today it is source, method and optimization level).
- A case's verdict is per frontend; a disagreement between the frontends is reported even when both agree with
  the JVM or both diverge.
- Each milestone's gate runs: every saved `.diffcase`; the G0 exhaustive corpus; the G1 fixed-seed campaign;
  the mutation and rediscovery checks; `Javac21ParityTest`; and I1's byte and PIR comparison over the M0 corpus.

### D11. ADR-067's remaining milestones move to the javac frontend

ADR-067 M2a and M2b (record patterns), M3 (two sealed parents), M3b (record members, ledger names), M4 (cross-type
`equals`) and M5 are implemented on the javac frontend after this ADR's M3, not on JavaParser. The JavaParser
frontend keeps rejecting them as it does at B0 (`JULC0076`). ADR-067 gets a revision saying so.

### D12. Deletion

After M5: the JavaParser frontend classes, the four reader classes, `ImportResolver`, `JavaConstants`, the Java
half of `SwitchPatternChecker`, the source-side parts of `TypeResolver`/`TypeRegistrar`/`TypeInferenceHelper`,
and JavaParser from `julc-cli` (lint rules on `TreePathScanner`), `julc-tools` and `julc-verification` (on
`javax.lang.model`). A build check fails on any `com.github.javaparser` import.

## Options and alternatives

| Option | Status |
|---|---|
| Keep JavaParser, fix each reading divergence (ADR-065 D) | Rejected: every fix adds javac-replica code, and the name/type class (#209, #284, #286, #287, #290, #291) has no bounded fix |
| JavaParser plus a javac cross-check (ADR-065 C) | Rejected: two readers remain on every target |
| javac trees → JuLC source IR → PIR | Rejected for now (D2) |
| javac trees → PIR through `JavacFacts` | **Chosen** (D2) |
| Lowering inside the user's javac (ADR-065 B) | Deferred (open question 3); does not serve the Gradle task, testkit, CLI or browser |
| Eclipse JDT | Rejected (ADR-065): a third reader |
| Keep JavaParser in the browser only | Rejected: the spike shows javac runs in the Web Image; one reader everywhere |

## Affected stages and modules

- **Stages:** parsing, subset validation, import/name/type resolution, library discovery, PIR generation, debug
  metadata. Not affected: the backend, PIR optimization, UPLC generation, serialization, VMs.
- **julc-compiler:** new `frontend/javac/` package (`JavacFrontend`, `EmbeddedFileManager`, `JavacFacts`, the
  lowering); `JulcCompiler`, `LibraryCompiler`, `JavaLibraryProvider`, `CompilerOptions`; the build generates
  the platform and API blobs.
- **julc-stdlib, julc-ledger-api, julc-core:** signature stubs generated from their classes (no source change).
- **julc-testkit, julc-gradle-plugin, julc-annotation-processor, julc-cli, julc-tools, julc-playground,
  julc-wasm, julc-verification:** the option (D4), the blob in their images, and the M6 ports.
- **julc-difftest:** D10.
- **Downstream:** Elci (native image, provider), the external `julc-examples`, `julc-helloworld`.

## Compatibility

- **Script bytes change** where I1's attribution lists say so; each list is in the PR and the release notes.
- **Source that stops compiling:** JuLC's dialect (D6), reference `==` (D6), and a sealed interface without a
  `permits` clause whose permitted subclasses are not exactly its nested records (I11). Release notes and docs give the
  rewrite. `int` literals as `BigInteger` were never Java.
- **Source that starts compiling:** shapes JuLC's readers rejected but javac accepts, where they are inside the
  subset (ADR-065 I2c: escape digits that are not ASCII hex digits, a Unicode-escaped backslash before raw
  backslashes and `u`). Accepted with javac's meaning, on every target.
- **API:** I5; `CompilerOptions.frontend` is new and is removed in M6.
- **Runtime:** compiling needs the `jdk.compiler` module, which every JDK has; native and Web images embed it.
  The CLI grows by about 16 MB and the full Wasm image by about 2.6 MB brotli (spike), less JavaParser's share.

## Risks

1. **Correct bindings, wrong lowering.** Evaluation order, state threading, exit targets and failure behaviour
   stay JuLC's responsibility. Mitigation: I1 per method from the first ported construct, the harness gates
   (D10), and porting the lowering rule by rule rather than redesigning it.
2. **Byte-identity long tail.** Tree-shape differences (folded negative literals, synthesized constructors,
   implicit types) and `TypeMirror` → `PirType` refinements will produce many small differences. Mitigation:
   per-method PIR diffs, attribution lists, and fixing differences at the javac side, not by rewriting PIR.
3. **Drift while two frontends exist.** Mitigation: D4's mirroring rule, and a short M3–M5 window.
4. **Hosts.** Library resolution through the source path, the nested javac in the annotation processor,
   Elci's native image and Web Image (preview) are proven by M1 before lowering work.
5. **Cold-start cost.** javac attribution may slow `JulcEval.forSource`. Mitigation: blob cache (D3); E6
   measured in M1 with a threshold proposed there.
6. **Platform drift.** The blob must match the javac that reads it. Mitigation: generated at build time from the
   build JDK; I4's two-JDK check.

## Implementation milestones

Each milestone is one or more commits on this PR's branch, reviewed (Claude reviewer and Codex) before the next
starts. "Harness gate" means D10's full set at all four optimization levels on the Java VM.

| ID | Scope | Entry gate | Exit criteria | Invariants / tests |
|---|---|---|---|---|
| **M0** | Base B0 and the corpus: in-repo `julc-examples` validators, the 16 stdlib libraries, ledger sources, `verification/blaster/fixtures`, every compiler/stdlib/testkit test source string, the `.diffcase` set, G0, the G1 fixed-seed corpus, the external `julc-examples`, Elci's smoke and `custom-library`. Record FLAT bytes, hashes, per-method PIR, source maps, CPU/memory budgets and script sizes at B0 | This ADR accepted | Baselines in `adr/evidence/068-javac-frontend-baseline.md` (with B0) and a generated, checked-in index | I1, I7, I11 baselines |
| **M1** | `JavacFrontend` (D3), D5 diagnostics, D6 dialect removal and the `compareTo` rule; javac runs as a **gate before the JavaParser frontend** on every compile: a javac error rejects, nothing else changes. Hosts: JVM, Gradle plugin, annotation processor, testkit, native CLI, playground, `julc-wasm` (a stdlib-using validator in the real image), Elci native shape | M0 | I1a byte identity on 100% of the corpus programs still accepted (the `compareTo` rule is attributed); every rejection newly caused by javac is a D6 dialect use, now rewritten, or listed; each host smoke passes; cold and warm compile times recorded (E6) | I2b, I4, I6, I10; negative control |
| **M2** | D7: types from javac behind `frontend=JAVAC`; `JavacFacts` | M1 | The registered types, variant order, fields, `@NewType` set and encodings are equal on the whole corpus (I11); #284 and #286 cases agree under the javac frontend | I3, I11 |
| **M3** | Port the non-loop lowering, in order: literals, names, static constants; calls (stdlib, library, instance dispatch); records, fields, object creation; locals, `if`/`else`, `return`; `instanceof`; `switch` with ADR-067 M1 guards (D5 replaces the Java-rule replicas); lambdas; whole-library lowering; entry points and `@Param`; debug metadata. Then the subset validator on javac trees | M2 | Per construct: I1b on every covered method and I1a on fully covered programs, with attribution; the harness gate on covered programs; binder lints green | I1, I2a, I2c, I3, I7, I9 |
| **M4** | Port loop lowering: for-each, `while`, `break`, accumulators | M3, and #254 and #255 resolved on the JavaParser frontend (ADR-062: "targeted fix first"), so the port starts from the corrected lowering | I1 against the then-current JavaParser frontend; the loop regression tests; T21 and T23 cases agree; the harness gate, including G0's loop shapes | I1, I3 |
| **M5** | Default `frontend=JAVAC`; ADR-067 M2a–M5 on the javac frontend (D11) | M4 | Full `./gradlew build`; external `julc-examples` and `julc-helloworld`; Elci's release smoke against a local snapshot; native and Wasm smokes; a cost and size report against M0; docs and release notes | All |
| **M6** | Delete JavaParser (D12): compiler, readers, CLI lint and MCP, tools, verification, reachability metadata; the import check | M5 | No `com.github.javaparser` on any class path; full build; harness gate; Elci coordinated | I8 |

## Verification strategy

- **Independent oracle.** javac/JVM is the reference for meaning (ADR-063). The JavaParser frontend at the
  reference base is the reference for bytes and PIR (I1); neither is derived from the javac frontend.
- **Equal output** per program, level and target (I1a) and per method (I1b), with PIR diffs for differences.
- **Attributed differences** each come with an issue and a saved `.diffcase` the javac frontend agrees on, or a
  cost delta.
- **Rejections:** the compiler's diagnostic tests and `reject-*` cases, by code and position (I2a); javac's
  rejections by key (I2b).
- **Binding:** ADR-060's lint and independence tests; a negative control that binds one library method to
  another must fail I1b and the harness.
- **Hosts:** each target is built and run, not inferred (M1).
- **Determinism:** two runs, and two JDK releases, give equal bytes on the corpus (I4).
- **Encodings:** I11's registry comparison, plus the existing boundary and blueprint tests.
- **Downstream:** Elci and the external examples (M5).

## Open questions

1. **The platform allow-list.** Only what JuLC's on-chain API references (about 54 KB; `Math.max` becomes
   `cannot find symbol` with D5's hint), or the whole exported `java.base` API (about 3.9 MB raw, 0.4 MB
   brotli in Wasm) with JuLC's subset rules rejecting the rest? Lean: the allow-list, because it is the subset
   policy stated as data and it keeps images small.
2. **Language level.** `--source 25` (the build JDK) or `--source 21` (JavaParser's `JAVA_21` today)? Lean: 25,
   with I2c rejecting syntax the subset does not support.
3. **Hosted lowering in the user's javac** (ADR-065 option B, OQ3). Lean: defer; reconsider after M6.
4. **When ADR-061 decides its representation,** is it inserted between javac trees and PIR for statements (D2)?
   Lean: yes, as ADR-061's own migration.

## Revision history

- **r1** (2026-10-30): initial proposal, after the ADR-065 spike and consultation on the seam.
