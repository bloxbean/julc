# ADR-068: Replace JavaParser with javac as JuLC's only Java frontend

**Status:** Proposed (r8)
**Date:** 2026-10-30 (r1); revised 2026-10-31 (r2–r8)
**Label:** `semantic-consistency`
**Risk class:** correctness-sensitive, **high**. The frontend decides what every validator means; a wrong
binding, type or literal is a silent miscompile.
**Programme:** a new step of [ADR-062](062-jvm-uplc-semantic-consistency-programme.md) (JVM–UPLC semantic
consistency), added by this ADR (ADR-065 open question 5). It follows the spike of
[ADR-065](065-javac-frontend-spike.md).
**Relates to:** [ADR-060](060-generated-binder-namespace.md) (name binding),
[ADR-061](https://github.com/bloxbean/julc/pull/203) (control flow, proposed),
[ADR-063](063-differential-jvm-uplc-test-harness.md) (differential harness),
[ADR-066](066-generator-g1-records-sealed-switch.md) (generator G1),
[ADR-067](067-switch-guards-record-patterns.md) (switch guards and record patterns),
[ADR-056](056-language-neutral-compiler-backend.md) and [ADR-059](059-generic-backend-revision-2.md) (backend),
[ADR-054](054-playground-webassembly-engine.md), [ADR-057](057-julc-wasm-distribution.md) and
[ADR-064](https://github.com/bloxbean/julc/pull/252) (browser engine)
**Base:** r1 cited code at `5a72d7a6`. Since then PR #267 also merged ADR-067 M1 round 5 (F85), ADR-066's latest
round, the loop-break continuation fix (#254, #255; GHSA-p9v9-f3g2-cqv9) with its T21/T23 harness updates, and
the reconciliation of all of them. **B0** = `86ebffcd`, the reference base for I1: the #267 head where that
integration's full test run is green (`./gradlew test --offline --continue --rerun`: 12,395 tests, 0 failures, 530
skipped). Line citations below are at `5a72d7a6` unless marked; the integration
did not move the cited lines except where noted.

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
| Lexical reading | #256, #268, #270 (T22, T24, T25) | Fixed by hand-written copies of javac's rules: `JavaSourceParser`, `UnicodeEscapes`, `StringLiterals`, `IntegerLiterals` (669 lines) |
| Names and scopes | #209, #284, #286, #291 | String-keyed scopes (`SymbolTable`, a `Deque<Map<String, PirType>>`) and AST renaming (`LoopBodyGenerator.withLocalsRenamed`, `withBindingRenamed`) decide what a name means |
| Types | #287, #290, #292 | No nominal type check for expressions; receiver typing by JuLC's own inference (`TypeInferenceHelper`); method dispatch by representation and simple name (`TypeMethodRegistry.java:95`) |
| Java rules replicated | ADR-067 D4 | `SwitchPatternChecker` (1,077 lines) re-implements dominance and exhaustiveness; `JavaConstants` (434 lines) re-implements JLS §15.29 for guards |

The spike (ADR-065; [`evidence/065-javac-frontend-spike.md`](evidence/065-javac-frontend-spike.md), code on branch
`spike/065-javac-frontend`) measured the platform cost:

- javac's `JavacTask.analyze()` and `Trees` work on the JVM, in a GraalVM native image (the `julc` CLI grows by
  16.3 MB, +18%), and in the GraalVM Web Image (`julc-wasm` full image +2.6 MB brotli, +43%).
- Every target needs the same thing: a custom `JavaFileManager` that serves an embedded, signature-only platform,
  because javac's standard file manager cannot run without a JDK image. With it, javac reads nothing else
  (negative control in the evidence file, Step 2).
- A real validator (`Cip68Nft.java`) and all 16 stdlib `@OnchainLibrary` sources analyze with no errors; the
  probe's attribution output was identical across JVM, native and Wasm, and across JDK 25.0.2 and 25.0.4.

**The decision to migrate is the maintainer's** (ADR-065 open question 8: the maintainers may judge instead of
applying D5's rule). JuLC is a preview with no production users, so script hashes may change. ADR-065's GO
criteria were not all measured by the spike; each unmet one becomes a gate of this ADR:

| ADR-065 criterion | Where it is gated here |
|---|---|
| E1 (I1 on the covered subset) | I1 at every milestone; M5 entry requires full coverage |
| E2 (rejections) | I2 and the rejection inventory (M0, M3) |
| E3 (exported APIs) | I6; M1 builds every image without `--add-exports` |
| E4, E5 (targets, Elci) | M1 host smokes; M5 Elci release smoke |
| E6 (compile time) | M1: warm compile ≤ 2× the JavaParser frontend on the corpus; cold recorded, threshold set in M1's review |
| B1–B3 (benefit) | The I1 ledger (D13) records every behaviour difference and its cause |
| Sequencing after ADR-061 | Not adopted: D2 explains why the migration does not wait for ADR-061 |

## Goals and non-goals

**Goals**

- One reader: javac parses and attributes every Java source JuLC compiles, on every target (JVM library, Gradle
  plugin, annotation processor, testkit, CLI and its native image, MCP lint, playground, `julc-wasm`, Elci's
  `JavaLibraryProvider`).
- Names, types, overload resolution and literal values come from javac. JuLC's replicas of those rules are
  deleted, except the guard constant evaluator (I9).
- Lowering semantics stay JuLC's own and stay correct: every change in generated behaviour is recorded, classified
  and evidenced (I1, D13).
- KISS: javac trees are lowered to PIR directly, as JavaParser trees are today. No new intermediate
  representation.
- Remove JavaParser from every module once the javac frontend is the default.

**Non-goals**

- Widening the Java subset. javac accepting a construct never makes it a JuLC construct (I2).
- Redesigning loop lowering. ADR-061 owns that; M4 ports the lowering as it is at B0, which includes the
  loop-break continuation fix.
- Changing integer semantics outside what ADR-067 already decided (I9; T6 stays an ADR-062 S5 decision).
- Changing the backend (`compiler/backend/`), PIR optimization, UPLC generation, serialization or the VMs.
- Hosting the lowering inside the user's javac (ADR-065 option B). Deferred (open question 3).
- Fixing defects in the JavaParser frontend during the migration (D4).

## Current behavior

### JavaParser use

Counted over `.java` files only (`grep -rl com.github.javaparser <module>/src/main --include='*.java'`, `wc -l`):

| Module | Main files | Lines | Notes |
|---|---|---|---|
| `julc-compiler` | 24 | 14,127 | |
| `julc-cli` | 19 | 1,903 | lint rules, MCP `EvaluateTool`, `TestDiscovery` |
| `julc-tools` | 1 | 346 | `JavaMetadataExtractor`; the REPL (`ExpressionEvaluator`) does not import JavaParser |
| `julc-verification` | 3 | 677 | resolvers that feed security properties |
| `julc-playground` | 0 | — | declares `javaparser-core` (`build.gradle:26`) without using it |

The largest compiler files are `PirGenerator` (3,351), `JulcCompiler` (1,902), `SwitchPatternChecker` (1,077),
`LoopBodyGenerator` (860), `TypeResolver` (777), `LibrarySourceResolver` (583), `TypeRegistrar` (562),
`AccumulatorTypeAnalyzer` (535), `PirHelpers` (496), `DebugMetadataCollector` (466), `TypeInferenceHelper` (443) and
`JavaConstants` (434). No JavaParser symbol solver is used: all resolution is JuLC's.

### The backend boundary is already clean

The Java frontend hands a `PirTerm` with its positions and provenance to `PirBackend.lower`
(`JulcCompiler.java:673`). `FrontendProgram` and `LibraryProvider` are PIR-only. UPLC FLAT uses de Bruijn indices,
so the names JuLC chooses for binders do not reach the bytes. PIR itself is scoped by name (`PirTerm.java:15`):
a binder captures every same-named `Var` in its body.

### Hygiene today

Statement lowering already passes the rest of the block as a continuation (`generateBranchWithCont`,
`PirGenerator.java:663-677`) and places it inside the branch's `let`s. Where a block-local shares a name with
something the continuation reads, the block-local is renamed with a `'` mark (`LoopBodyGenerator.java:693-711`):
a static `K` and a block-local `K` lower to `(let K = 10 … (let K'1 = [addInteger a a] … else [addInteger a K]))`.
That rename is what prevents capture.

### JuLC's dialect beyond Java

JuLC accepts source that javac rejects or gives another meaning. Probes at `5a72d7a6`:

| Source | javac | JuLC |
|---|---|---|
| `x > y`, `x + y` for `BigInteger x, y` | `operator.cant.be.applied` | accepted, lowered to integer builtins |
| `BigInteger f() { return 5; }` | `int cannot be converted to BigInteger` | accepted |
| `x == y` for `BigInteger`, `byte[]`, `String` | accepted: **reference** equality | accepted, lowered to value equality (T3, T4, #289) |
| `p.equals(q)` for `byte[]` | accepted: `Object.equals`, identity | `EqualsByteString` (`TypeMethodRegistry.java:308`) (T4) |
| Ledger and stdlib types without imports | `cant.resolve` | implicit on-demand imports of `org.julclang.ledger`, `.stdlib`, `.stdlib.lib` (`ImportResolver.java:93`) |
| `MyHash.of(bytes)` for a `@NewType record MyHash` | `cant.resolve` (no such method) | synthesized identity factory (`JulcCompiler.java:1224-1273`) |
| `java.util.Arrays.equals(p, q)` | value equality | rejected: `Undefined variable: Arrays` |

Comparison operators also lower with swapped operand terms: `a > b` becomes `[[lessThanInteger b] a]`
(`PirGenerator.java:1102-1103`), so `said("X",x) > said("Y",y)` evaluates Y first, for `int`/`long` and for the
dialect alike. `a.compareTo(b)` evaluates its receiver and argument more than once (`TypeMethodRegistry.java:259`,
#146, T13).

### Constructor tags and encodings

A sealed interface's variant tags follow its `permits` clause (`TypeResolver.registerSealedInterface`,
`TypeResolver.java:159`). Without one, they follow the records nested in the interface, in source member order
(`TypeRegistrar.java:73`, `:243`). All 12 ledger sealed interfaces use implicit permits. Ledger encodings also
contain policy beyond record components: `Credential` fixes tags 0 and 1, `Value` encodes as a map, `PubKeyHash` as
bytes (`Credential.java:12`, `Value.java:108`, `PubKeyHash.java:22`).

### `@Param` and guards

ADR-067 treats a `@Param` field, and constants that depend on it, as runtime input even when its initializer is a
constant (ADR-067 D4, `SwitchGuardParamTest.java:71`, `:110`). javac treats a `static final` field with a constant
initializer as a constant variable, so `case A x when ENABLED -> …; case A x -> …` is `pattern.dominated` to javac.
#293 tracks the conflict.

### Elci's surface

Elci uses `JavaLibraryProvider` (schemes for generic library methods, specialization by
`LibraryRequest.Instantiate` with producer-supplied types, `JavaLibraryProvider.java:164`, `:273`), plus
`LibrarySources.resolve`/`readJars`, `LedgerTypeProvider` (`types()`, `namedDefinitions()`), `LibraryType` and
`PirLinker`. Specializations are explicit PIR-type environments (`LibraryCompiler.java:136`). Overloads that lower
to the same PIR and type are accepted (`OnchainOverloads.java:12`); others are `JULC0054`.

## Invariants

- **I1. Behaviour is preserved, or the difference is recorded and evidenced.** Every corpus case (M0) is compiled by
  both frontends at every optimization level and target, and each milestone's result is written to the frozen
  ledger of D13. The reference is the JavaParser frontend at B0, frozen (D4) and re-frozen only by D4's
  exceptions.
  - **I1a, whole programs:** FLAT bytes and script hash.
  - **I1b, single methods:** the PIR term and type of every lowered method (libraries are lowered whole by
    `LibraryCompiler`), compared up to renaming of bound variables (alpha-equivalence). Binder names do not reach
    the bytes; I13 governs them.
  - A case may differ only with a D13 classification and that class's evidence. An unclassified difference, or a
    case that enters or leaves the accepted set without a classification, blocks the milestone. No class waives the
    harness observation check of the classes that require it.
  - **Reference changes.** The reference changes only by D4's two exceptions (the `compareTo` rule at M1, and
    security-class fixes). Each change re-freezes the reference (B0 → B1 → …), re-runs every reference row, and
    records the transition in the ledger as reference-change rows; ledger rows are never edited.
- **I2. The subset does not widen.**
  - **I2a.** M0 builds a **rejection inventory**: every rejection the JavaParser frontend makes, coded (`JULC`) or
    uncoded (for example floating point, `SubsetValidator.java:336`, and inheritance, `:385`), each with a test
    source. The javac frontend rejects every one, with the same code and at the same anchor position for coded
    ones (positions from javac's `SourcePositions` for the node corresponding to JuLC's anchor), unless javac
    rejects it first (I2b) or this ADR names the change. Uncoded rejections get codes when ported.
  - **I2b.** Source javac rejects is rejected with javac's diagnostic (D5), with javac's key and position.
  - **I2c.** The subset validator is an allow-list of tree kinds **and** of contextual predicates per kind (type,
    modifier, member and position rules, as today's `SubsetValidator`), with a rejection test per predicate. The
    language level is `-source 21` (D3), so javac itself rejects Java 22–25 syntax (module imports, unnamed `_`
    variables and patterns, compact source files, statements before `this(...)`); M1 adds one rejection test per
    such feature.
- **I3. Names bind as Java binds them.** Each source name lowers to a `Var` that, under PIR's name scoping, resolves
  to the binder allocated for the `Element` javac binds the name to. Tested by I13's binding check.
- **I4. Determinism.** For a given JuLC release, the same sources give the same bytes on every host, under D3's
  supported-javac rule. Traversal and registration follow source order (`ClassTree.getMembers()`), library
  units follow FQCN order, and no lowering decision depends on `ANALYZE` event order or hash iteration. CI checks
  the shipped blobs across hosts (JVM, native, Wasm) separately from blobs regenerated on two JDK 25 patch
  releases (D3).
- **I5. Public API shape.** These keep their signatures and behaviour contracts:
  - `JulcCompiler.compile(String, List<String>)`, `compileMethod`, `compileContract`, the path-based entry points;
  - the testkit's `JulcEval.forSource` and `forClass`;
  - Elci's surface: `JavaLibraryProvider(List<String>, StdlibLookup, CompilerOptions)` with its exports, schemes,
    `LibraryRequest.Instantiate` specialization and fingerprints; `LibrarySources.resolve` and `readJars`;
    `LedgerTypeProvider.types()` and `namedDefinitions()`; `LibraryType`; `PirLinker`.

  M2 and M3 gate each with an equality test between the frontends (D8).
- **I6. Exported APIs only.** JuLC code uses only `javax.tools`, `javax.lang.model` and `com.sun.source`, through
  `ToolProvider.getSystemJavaCompiler()`: no reference to `com.sun.tools.javac.*`, and no `--add-exports` or
  `--add-opens` anywhere, image builds included. Native-image configuration may name internal classes where javac
  needs them at run time (the spike's reflection entry for `com.sun.tools.javac.api.JavacTrees.instance` and the
  `com.sun.tools.javac.resources.*` bundles); those entries are listed in one file, and a build check verifies
  each against the JDK the image is built with. A runtime without `jdk.compiler` (`ToolProvider` returns null)
  fails with `JULC0089` (D5).
- **I7. Source provenance.** Source-map and debug-metadata locations are attached while the term is built
  (ADR-060). Lines come from javac's `LineMap`; **columns are character offsets** (offset − line start + 1), not
  `LineMap.getColumnNumber`, which expands tabs (9 where JavaParser gives 2 in a tab-indented method). Remaining
  location differences are D13 entries.
- **I8. One reader, no silent fallback.** While both frontends exist, the frontend is chosen explicitly (D4). The
  javac frontend never hands a program, or part of one, to the JavaParser frontend. After M6 no target contains
  JavaParser.
- **I9. Integer semantics are unchanged.** Literal values come from `LiteralTree.getValue()`, which matches T25's
  decoding. Constant expressions and static-field initializers are lowered with unbounded integers, as today.
  Inside `switch` guards, ADR-067's rule stands: a guard constant has Java's value, limited to ADR-067's verified
  kinds (boolean, `int`, `long`, `String`), with the same rejections (`JULC0072`). No exported javac API yields
  the value of an expression tree (only `VariableElement.getConstantValue()` exists), so `JavaConstants` is
  **ported** to javac trees, not deleted: its leaves come from `LiteralTree.getValue()` and
  `getConstantValue()`, and its operators stay JuLC's verified evaluator. A `@Param` field is never a constant
  (D14).
- **I10. A closed compilation environment.** javac sees exactly:
  - the compiled roots and the sources the host's pool offers (D8), on the source path;
  - signature-only class files for JuLC's **on-chain API**: `julc-core` (all of it, off-chain classes such as
    `DebugMetadata` included: visibility never implies support, and I2 rejects their use), `julc-ledger-api`, the `julc-stdlib`
    classes on-chain code may reference (annotations, `Builtins`, the collection and value types; not its
    `@OnchainLibrary` classes, and not compiler-facing classes such as `StdlibRegistry`, `ListsLibHof` or
    `StdlibLibraryProvider`), and the annotation types of `julc-verification`
    (`org.julclang.verification.annotation`);
  - a signature-only platform (D3).

  It never reads the host's `jrt:/`, `ct.sym` or the user's class path. **Visibility never implies on-chain
  support**: a type javac can see is still subject to I2. Annotations from any other package are `cant.resolve`
  with D5's hint (Compatibility).
- **I11. Constructor tags and encodings are unchanged.**
  - Source types: tags follow the `permits` clause; without one, the records nested in the interface in
    `ClassTree.getMembers()` order (today's rule). If javac's `getPermittedSubclasses()` is not exactly that list
    (any other class of the compilation unit permitted implicitly, such as a top-level or sibling-nested record),
    the interface is rejected with a diagnostic asking for an explicit `permits` clause.
  - Class-file types (the ledger, through the signature blob): tags follow the `PermittedSubclasses` attribute,
    which the stub generator must keep, together with `Record`, `Signature` and runtime-visible annotations. A
    build-time test checks, for every sealed ledger type, that `PermittedSubclasses` order equals the source member
    order the JavaParser frontend used.
  - Special layouts (`Credential`'s fixed tags, `Value` as a map, `@NewType` as its underlying value, and every
    other ledger special case) come from JuLC's registries keyed by FQCN, never inferred from stub metadata.
  - M2 gates on both registry equality (types, variants, fields, `@NewType` set, `LedgerTypeProvider` output) and
    **independent** encoding tests: the actual Data of every ledger and corpus type, and the blueprint schemas,
    against B0.
- **I12. Validated input to lowering.** The lowering only sees trees javac attributed without error. It never
  falls back to a simple name or to a `PirType` representation where an `Element` exists: calls dispatch on the
  resolved `ExecutableElement` (owner FQCN, name, erased signature), field and record access on the
  `VariableElement` or `RecordComponentElement`, and type tests on the `TypeElement`. Requirements per tree kind:
  identifiers, member selects, method invocations, `NEW_CLASS` (its constructor) and declarations must have a
  non-null element; `NEW_ARRAY` (the supported `byte[]` literal) has no element and must have its attributed array
  type; every expression must have a non-error `TypeMirror`; statements and javac-synthesized trees (the
  default constructor and its `super()` call) need neither and are handled by kind. Literals and operators have no
  element (`Trees.getElement` is null for them by contract). The attribution invariant tests cover every kind the
  subset accepts, including `NEW_CLASS` and `NEW_ARRAY`. A violation is an internal error, never lowered.
- **I13. Binder hygiene.** Each source `Element` gets one binder name, chosen when its binder is built (ADR-060:
  names are never changed afterwards): its source name, unless a binder of a different element with that name is
  visible at that point in the PIR being built, in which case `name'k` with the smallest `k ≥ 1` not visible.
  Generated binders keep the `#` namespace (`PirHelpers.hygienicName`). Where the lowering duplicates a term (a
  continuation placed in several branches), every copy reuses the element's name. A test-time PIR check walks
  every lowered term and requires each `Var` built for an element to resolve, by PIR scoping, to a binder built
  for that element; it covers every copy and the rebinding of state across iterations and joins. It needs
  construction-time provenance: an identity-keyed side table, recorded as `Var`s and binders are built, maps each
  to its element, without changing `PirTerm`. It runs over the whole corpus at every milestone. `GeneratedBinderNameLintTest`, `BinderNameIndependenceTest` and
  `julc.verifyBinderNamespace` stay green.

## Decisions

### D1. Migrate: javac is JuLC's only Java reader

javac (`JavacTask.parse()` and `analyze()`, never `generate()`) reads and attributes all Java source JuLC
compiles, on every target. JavaParser is removed (M6). This is the maintainer's decision under ADR-065 open
question 8; the ADR-065 criteria it does not meet yet are gates here (Context).

### D2. The seam: javac trees lowered to PIR directly, behind one facts service

The lowering walks javac's attributed `com.sun.source.tree` trees and produces PIR, as it walks JavaParser trees
today. All access to javac's attribution goes through one JuLC class, `JavacFacts`:

- tree → `Element`, and `Element` → binder name (I13);
- tree → `TypeMirror`, and `TypeMirror` → `PirType`, with nominal identity (the `TypeElement`) kept alongside for
  dispatch and checks (I12);
- literal values and guard constants (I9);
- positions (I7).

`JavacFacts` is a service over javac's own tree, not a representation: no tree is copied or rewritten. Statement
lowering keeps today's continuation style; hygiene comes from I13, not from AST renaming. The safeguards a
separate typed IR would provide are stated as I12 (nominal identity, no fallbacks, no error types) and I13
(capture-free binders), and I3 makes binding testable; evaluation order is stated per construct in the port (M3)
and tested by the harness.

Alternatives (ADR-065 OQ9 answered as (a)):

- **A typed JuLC source IR between javac and PIR.** Rejected for now. It would duplicate javac's attributed tree
  (AGENTS.md: "Avoid parallel representations"); javac's `Element`s already give stable declaration identities;
  and I12/I13 put the IR's safeguards on the lowering itself. ADR-061 has not decided its representation; if it
  adopts a control-flow IR, that IR is inserted for statements between javac trees and PIR as ADR-061's own
  migration. The expression lowering, about 60% of `PirGenerator`, does not change either way.
- **JavaParser lowering fed with javac's facts** (ADR-065 option C). Rejected: an alignment layer between two trees
  (folded negative literals, synthesized constructors, multi-declarator starts, implicit `var` and lambda types)
  is a new silent mis-binding risk, the string-keyed scopes stay, and both parsers stay in every image.
- **A JuLC IR built from JavaParser first** (ADR-065 OQ9 (c)). Rejected: a transitional frontend.
- **`com.sun.tools.javac.tree.JCTree`.** Rejected (I6).

### D3. One compilation environment for every host: `JavacFrontend`

`JavacFrontend` builds the `JavacTask` for every entry point.

- **File manager.** A custom `JavaFileManager` (from the spike's `EmbeddedFileManager`) serves:
  - `SYSTEM_MODULES`: a signature-only `java.base` blob, stored uncompressed (no `Inflater` in Web Image). Its
    contents are **derived**, not hand-listed: the set of platform classes javac loads (`-verbose` class
    loading) while attributing JuLC's in-repo sources (stdlib, ledger, in-repo examples and test sources), closed
    under supertypes; external repositories are checked against it, never used to derive it. The spike showed that this includes `HashMap`, `TreeSet`, `Stream`, `java.util.function`,
    `Arrays`, `Objects` and `Collections`; visibility never implies support (I10). The derivation and a digest are
    checked in, and a build check fails when they drift;
  - `CLASS_PATH`: the API stubs of I10, generated at build time (keeping the attributes I11 needs);
  - `SOURCE_PATH`: the host's roots and pool (D8). `@OnchainLibrary` classes, including the stdlib's, are
    **only** sources, never stubs.
- **Options.** `-proc:none`, `-source 21` (open question 2 decided: today's level, `JavaSourceParser`'s
  `JAVA_21`), `-implicit:none`, `-Xlint:none`, locale `en`. No `--release`: the blob is the platform.
- **Supported javac.** On JVM hosts, `ToolProvider` returns the host JDK's javac, which reads a blob built from
  JuLC's build JDK. The supported range is **javac feature release 25**, the release JuLC is built and tested
  with. `JavacFrontend` checks the host compiler's `JavaCompiler.getSourceVersions()` (its highest version must
  be `RELEASE_25`) and fails with `JULC0089` (`JAVAC_UNSUPPORTED`) otherwise; the full compiler version
  (`Runtime.version()` of the host) and the blob digest are recorded in the compile result's metadata.
  - **Supported builds.** The JDK 25 patch releases in CI's matrix are supported; any other 25 patch runs, with
    its version recorded. A difference in output between two patch releases on the corpus is **release-blocking**:
    it is resolved by narrowing the supported builds, or by a versioned decision recorded in this ADR's revision
    history (which output is right, from the JVM agreement of the affected cases), never left to the host.
  - Widening the range to another feature release needs a corpus run and the same comparison (I4). The oracle
    suite keeps its cross-version comparison when retargeted (D11).
- **Ownership check before javac**, by provenance, on every host, before `analyze()`:
  - **Bundled sources** are exactly the entries of `julc-stdlib`'s shipped `META-INF/plutus-sources` manifest (16
    today: the `org.julclang.stdlib.lib` libraries and `org.julclang.stdlib.test.TestContextLib`, which
    `julc check` uses), each with a build-time digest; the set is defined by that manifest, not by package. A
    source offered with a bundled name is accepted as bundled only if its digest matches; JuLC's own tests
    (`JulcEval.forClass(MapLib.class)`) therefore compile them as roots, as today.
  - Any other source is rejected with `JULC0081` (generalizing ADR-067's ledger-redeclaration code) if its
    qualified name is in the stub set or the bundled set, or its package is one the stubs or bundled sources
    define. So no source can replace or extend an API type or a bundled library.
  - A qualified name offered twice is handled by D8's precedence rule, never by javac's `-Xprefer`.
- **A negative control** (as in the spike): a test removes `BigInteger` from the blob and requires
  `package java.math does not exist`, so javac provably reads nothing else.
- **Caching.** The parsed blob is cached per process (`JulcEval.forSource` cold starts).

### D4. Explicit frontend selection; the JavaParser frontend is frozen

`CompilerOptions.frontend` takes `JAVAPARSER` or `JAVAC` and is marked experimental. The Gradle plugin, CLI and
testkit expose it. The default is `JAVAPARSER` until M5 and `JAVAC` after it; M6 removes the option.

- During M2–M4 the javac frontend rejects a construct it has not yet ported with `JULC0084`
  (`FRONTEND_CONSTRUCT_NOT_PORTED`, internal status), naming the construct. It never falls back (I8).
- **The JavaParser frontend is frozen at B0.** It is the I1 reference, so it changes only to keep the build green,
  and by two declared exceptions. Other defects found during the migration, including those this ADR fixes (D6,
  D11), are fixed in the javac frontend only, with a D13 entry. This replaces r1's mirroring rule.
- **Known divergences accepted until M5 (maintainer decision, 2026-10-31).** The maintainer decided that the
  silent divergences already known and public when this ADR was accepted stay open on the default frontend until
  M5, rather than being fixed or rejected in the frozen frontend: JuLC is a preview with no production users, each
  is public (an issue, an ADR-062 row, a saved pin), and M5 closes the window. The list is every ADR-062 row open
  at B0 and every issue filed against JuLC's semantics by then; M0 freezes it with each pin's ID. Among them: T3–T5
  (equality), T6 (`int`/`long` width), T7 (`any`/`all`), T11, T12, #146's remaining forms (T13), T29 (cross-type
  `equals`), T30 (two sealed parents), T31 (record members), T32, T33 (ledger names), T34, #280, #288, #289, #295
  for the forms D4's exception 1 does not cover, and #296 (`"a" + 1`). The release notes of any release before M5 list them. `SECURITY.md` puts such
  bugs in scope; M0 adds a sentence there that, during the preview, divergences catalogued publicly in ADR-062
  are tracked as public issues, and only undisclosed ones go through the private advisory process.
- **Exception 1: D6's comparison rules go into both frontends at M1.** M1's javac gate forces dialect
  comparisons into the `compareTo` form; with today's lowering that form duplicates operands (#146) and costs
  more (41 against 68 bytes for `a.add(b) > c.multiply(b)`; 60 against 128 for three comparisons). So D6's
  `compareTo` rule, and its left-to-right rule for relational operators, are implemented in the JavaParser
  frontend too, as a reference change (I1). The two frontends match the call differently, and both are sound:
  - **javac frontend:** the call must resolve to `java.math.BigInteger.compareTo(java.math.BigInteger)` (nominal,
    I12).
  - **JavaParser frontend:** its typing holds only `PirType`s, so it cannot tell `BigInteger` from an
    integer-backed `@NewType` (both `IntegerType`, `TypeResolver.java:518`), and adding Java-type inference there
    would put a javac replica into the frozen reference. So the rule is applied **at the point where that
    frontend's existing dispatch has already selected the integer `compareTo` instance handler**
    (`TypeMethodRegistry.java:259-273`, reached from `PirGenerator.java:1483-1527`), with the call in an `OP 0`
    comparison. Every earlier dispatch decision stays as it is: in particular static-library dispatch, which runs
    first and treats the receiver's spelling as a class name (`PirGenerator.java:1304-1315`, `:1379-1430`), so
    `BigInteger Shadow = …; Shadow.compareTo(y) > 0` next to an `@OnchainLibrary class Shadow` still calls
    `Shadow.compareTo`, as today (a name-resolution divergence of the T33 kind, on the accepted list). Where
    the integer handler is selected, the frozen frontend already lowers `compareTo` to the integer comparison: a `@NewType` that declares its own `compareTo` is
    lowered to the builtin comparison today, ignoring the declared method (probe at B0; that is T31, on the
    accepted list above). So the rule changes only how many times the operands are evaluated (#146), never which
    comparison is made. No javac fact flows into that frontend (I8). Tests: a `@NewType` with a declared
    `compareTo` (same comparison before and after), an accessor receiver (`p.amt().compareTo(y) > 0`), a
    method-call receiver, the `Shadow` case (unchanged from B0), and a chained accessor the frozen frontend rejects
    today (`box("L", 2).amt().compareTo(…) > 0`, still rejected).
  M1 gates that every rewritten corpus source costs no more, in size and budget, than the **order-correct**
  lowering of its dialect original, which is the dialect form plus D6's single binding where one is needed.
- **Exception 2: security-class fixes go into both frontends.** A security-class defect is any silent divergence
  in a validator's accept/reject outcome from its own Java code, in **either** direction: the script accepts
  what the Java rejects, or rejects (fails, or never terminates) what it accepts, as GHSA-p9v9-f3g2-cqv9 did both.
  The maintainers classify a defect under `SECURITY.md`. A security-class defect **newly found** during M1–M5
  (not on the accepted list above) is fixed in both frontends, with a regression case, as a reference change (I1).
  A new shape of an accepted row (another form of T29, say) belongs to that row, unless the maintainers classify
  it as a separate defect. Releases between M1 and M5 are therefore
  allowed; their notes list the open DIVERGE pins of the default frontend.

### D5. javac's diagnostics are JuLC's diagnostics for invalid Java

`analyze()` diagnostics of kind `ERROR` stop the compile. Each becomes a `CompilerDiagnostic` with code `JULC0085`
(`JAVAC_ERROR`), javac's message, its key (for example `compiler.err.cant.resolve.location`) and its position. A
`cant.resolve` or `doesnt.exist` for a JDK type or package outside the blob, or for an annotation outside I10, gets
a JuLC hint (for example "`java.util.ArrayList` is not available on-chain; use `JulcList`"). Warnings are not
reported. A missing `jdk.compiler` is `JULC0089`.

This replaces JuLC's uncoded parse errors, `JULC0070` (T25), and the Java rules that javac makes, with the
exceptions D11 lists.

### D6. JuLC's dialect beyond Java is removed; Java's equality is enforced

- **Dialect.** Operators on `BigInteger`, `int` literals as `BigInteger`, implicit imports of the ledger and
  stdlib packages, and synthesized `@NewType` `of(...)` factories are not Java: javac rejects them (D5). Every
  in-repo source that uses them (tests, docs, examples, AI starter packs) is rewritten to Java in M1: `add`,
  `compareTo`, `BigInteger.valueOf`, explicit imports, `new MyHash(bytes)`.
- **Comparisons through `compareTo` (closes #146 for this form).** Where the javac frontend sees
  `a.compareTo(b) OP 0` or `0 OP a.compareTo(b)`, with the call resolved to
  `java.math.BigInteger.compareTo(java.math.BigInteger)`, `OP` one of `<`, `<=`, `>`, `>=`, `==`, `!=`, and `0`
  the `int` literal zero (`OP 0L` and other zero constants fall outside the rule and keep today's lowering):
  - it evaluates `a`, then `b`, each exactly once, with the **minimal binding** that keeps that order: none for
    `<`, `<=`, `==` and `!=`, whose builtin already evaluates its first argument first; for `>` and `>=`, whose
    builtin takes `b` first, one `#`-named `let` binding `a`, and only when both `a` and `b` are non-trivial
    (neither a variable nor a literal). Call the evaluated operands `x` and `y`;
  - a zero-left form is first normalized by reversing the operator (`0 < c` is `c > 0`, `0 <= c` is `c >= 0`,
    and so on; `==` and `!=` are unchanged), so evaluation order stays `a` then `b`;
  - then it applies one builtin:

    | Form (after normalizing) | Lowering |
    |---|---|
    | `a.compareTo(b) < 0` | `LessThanInteger x y` |
    | `a.compareTo(b) <= 0` | `LessThanEqualsInteger x y` |
    | `a.compareTo(b) > 0` | `LessThanInteger y x` |
    | `a.compareTo(b) >= 0` | `LessThanEqualsInteger y x` |
    | `a.compareTo(b) == 0` | `EqualsInteger x y` |
    | `a.compareTo(b) != 0` | `not (EqualsInteger x y)` |

    The six zero-left forms (`0 > a.compareTo(b)` is the `< 0` row, `0 < a.compareTo(b)` the `> 0` row, and so
    on) make twelve.

  This is a **behaviour change**: today the operands are evaluated more than once, so traces and failures
  differ (#146). The flipped pins (`diverge-g1-146-traced-compare-operand*`) are its D13 evidence. It goes into
  both frontends at M1 (D4, exception 1), so ADR-066's `REDISCOVER_146` profile becomes a fixed-defect guard for
  this form on both; `TRACE_IN_COMPARISON` and T13 stay open for every other use of `compareTo`, which keeps
  today's lowering. Tests cover all twelve forms with `a > b`, `a = b` and `a < b`, traced operands (one trace
  each, in source order), and a failure in either operand.
- **Comparison operators evaluate left to right.** For `>` and `>=` on `int`/`long` (and the dialect forms in the
  JavaParser frontend), the operands are evaluated in source order by the same minimal binding; `<` and `<=`
  already are. This fixes the existing order divergence (#295; an ADR-062 row in M1) and is a D13
  semantic fix.
- **Equality follows JLS §15.21**, as subset rules of the javac frontend (M3):
  - `==`/`!=` is numeric or boolean equality (§15.21.1, §15.21.2) only when at least one operand has a primitive
    type; it stays. `Boolean == Boolean` and `Integer == Integer` are reference equality.
  - **Constant strings.** `==`/`!=` where both operands are constant `String` expressions stays **only** when
    each operand is built from string literals, constant `String` variables and string `+` alone, with no numeric,
    `char`, boolean or conditional subexpression, **and** the same holds, recursively, for the initializer of every
    constant variable it reaches (alias chains, static, local and imported constants): those have Java's value
    without integer semantics. A constant from a class file (a stub) has no initializer tree; it contributes its
    `getConstantValue()`, which is Java's exact value. Inside
    `switch` guards, ADR-067's evaluator decides as today (Java's widths, I9), so `"a" + "b" == "ab"` keeps its
    meaning there. Any other constant-string comparison (`(2147483647 + 1 < 0 ? "a" : "b") == "a"`, which is true
    on the JVM and false with unbounded lowering), including one reached only through a constant alias
    (`static final String ALIAS = BAD;`), is rejected with `JULC0086`. Tests cover overflow and conditional
    operands, and static, local and imported alias chains.
  - **String `+` with a non-`String` operand** (`"a" + 1`) is valid Java, but JuLC lowers it to `appendString`
    over an integer, which fails at run time (#296). The javac frontend rejects it with
    `JULC0090` (`STRING_CONCAT_NON_STRING`) until a decimal conversion is specified; the fix is to concatenate
    `String`s only. **Exception:** a `switch` guard that is **as a whole** a constant expression ADR-067's
    evaluator computes with Java's values (`when "a" + (2147483647 + 1) == "a-2147483648"`, `JavaConstants.java:174`,
    `:225`) keeps its meaning (I9), because the lowering replaces the whole guard by its value, so no
    concatenation reaches run time. A concatenation inside a non-constant guard (`when a.ok() && ("a" + (2147483647
    + 1) == "a-2147483648")`, or `("a" + 1).equals("a1")`) is lowered as ordinary code (`guardFixedWidthConstant`
    substitutes only `int` and `long`, `PirGenerator.java:2890`) and is `JULC0090`. Regressions cover both, and a
    short-circuit case.
  - Other reference equality (§15.21.3: `BigInteger`, `byte[]`, `String`, boxed values, records) is rejected with
    `JULC0086` (`REFERENCE_EQUALITY_UNSUPPORTED`); the fix is `.equals`, or `ByteStringLib.equals(a, b)` for
    bytes.
  - **Equality that would compare an array by identity** is rejected with `JULC0087`
    (`ARRAY_EQUALS_UNSUPPORTED`; T4, T5). The check applies to **every operation whose JVM implementation compares
    elements or keys**, and M3 derives the table from that rule over the supported signatures. Today that is:
    `.equals`; `JulcList.contains`; `JulcMap`'s `get`, `lookup`, `containsKey` and `delete`; `TypedMapLib`'s
    `lookup`, `member`, `remove` (which deletes) and `insert` (which deletes first); and the same operations on `java.util.List` and
    `java.util.Map`. Plain `JulcMap.insert` only prepends, on the JVM and on-chain (`JulcAssocMap.java:75-78`,
    `TypeMethodRegistry.java:807-816`), so it is not checked; a regression keeps comparison-free insertion
    accepted. The check is decided on the static type `T` being compared (the receiver for `.equals`, the element
    or key type for collections), after type substitution, and asks whether the JVM's equality for some value of
    `T` reaches an array compared by identity. M3's table is checked against every supported signature, wrapper
    methods included:
    - an array type: yes, except as an element, key or value type of a JuLC collection (`JulcList`, `JulcMap`,
      `JulcArray`), whose JVM equality M1 makes deep for `byte[]` (below);
    - a JuLC collection whose element, key or value type is itself a JuLC collection: recursion and acceptance
      are bounded by **Data encodability**. A `JulcArray` (a native array, not Data-encodable:
      `PirHelpers.wrapEncode`, `:182-214`, and `TypeReferences.dataEncodable`, `:96-104`, have no array case) as an
      element, key or value type of a `JulcList` or `JulcMap` cannot even be constructed on-chain today
      (`MkCons: element type … ProtoArray … does not match`). The same failure occurs in **every Data-encoded
      position** (#298): a record or sealed-variant component (`record R(JulcArray<BigInteger> arr)`, which fails
      as soon as `R` is constructed, `UplcGenerator.java:249-267`), an `Optional`, `Tuple2` or `Tuple3` type
      argument, a `JulcList`/`JulcMap` element, key or value, a `JulcArray` element (ADR-046: array elements are
      Data-encoded, so `JulcArray<JulcArray<BigInteger>>` fails), and a datum, redeemer or `@Param` boundary type,
      at any depth. `TypeReferences.dataEncodable` cannot decide this (it accepts every record without looking at
      its fields), so the check recurses through record and variant components itself. Arrays of Data-encodable
      types stay supported (`JulcArray<JulcList<BigInteger>>` works today and keeps working). So `JULC0091` (`NATIVE_ARRAY_NOT_DATA_ENCODABLE`) rejects a
      `JulcArray` type in any such position, checked recursively where the type is written, with regressions for
      the record, `Optional`, collection and nested-array cases, and a positive control for an array of lists. #298 is a newly found divergence in which the script fails
      where the Java succeeds, so under D4's exception 2 the rejection goes into **both** frontends at M1 (in the
      frozen one, over its `PirType`s: an `ArrayType` under a record, sum, list, map or `Optional` encoding). For nested lists and maps, the
      JVM side is safe once M1's change makes every JuLC collection's equality deep, but the on-chain side is
      **not** today: `contains`
      passes a nested-list target to `EqualsData` without encoding it (`PirHelpers.java:271`), and fails at run
      time (#297, a pre-existing defect on D4's accepted list). The javac frontend fixes it in M3 by encoding the
      target by its static type (`ListData`, `MapData`) in every collection comparison, as a D13 semantic fix with
      differential tests for nested lists and maps of `byte[]` and `BigInteger`;
    - a record with the implicit `equals`: if any component's type can (each component checked, after
      substitution);
    - a sealed interface: if any permitted variant can (each variant checked);
    - `Optional`, `Tuple2`, `Tuple3`, `JulcList`, `JulcMap`, `JulcArray`, `java.util.List` and `java.util.Map`:
      if any type argument can (an array type argument of `java.util.List` or `Map` is unsafe; of a JuLC
      collection, safe after M1's change);
    - a type on the **value-equality allow-list**: no. The list is explicit (`BigInteger`, `String`, `Boolean`;
      julc-core's `PlutusData` variants: `BytesData` compares with `Arrays.equals`, and `ConstrData`, `ListData` and
      `MapData` compare their tag and fields, which are themselves `PlutusData`; the
      ledger types that declare value `equals`, such as `PubKeyHash`), and each entry has a test that its
      `equals` compares every component by value. A type that declares `equals` but is not on the list is
      rejected: declaring `equals` is not enough;
    - a type variable, or any type the check cannot resolve: rejected, conservatively, except in generic library
      methods (below).

    The recursion follows finite paths only: a type reaches an array if some finite component path does, so
    recursive types (`PlutusData.ListData`, user recursive sealed types) terminate. The fix is to compare the
    bytes: `ByteStringLib.equals(a, b)`, or for a `@NewType` byte wrapper (which cannot declare `equals`,
    ADR-067 M3b) `ByteStringLib.equals(a.hash(), b.hash())`. M0 scans the corpus and the external examples for
    affected call sites (as ADR-067 M4 did; `CfPaymentSplitterValidator.java:51,83,94` calls `.equals` on `byte[]`)
    and lists them. Tests cover direct, nested record, sealed-component, container, `java.util`, type-argument and
    recursive-type paths.
  - **Generic library methods (Elci).** ADR-059 rejects Java calls to generic library templates ("cannot be
    called from Java yet", `LibraryMethodRegistry.java:75-100`) and calls between templates (`JULC0051`), tested
    in `GenericExportsTest.java:322-360`. Both rejections are unchanged and are permanent entries of I2a's
    inventory. So the only instantiations of a generic library method are those a non-Java producer requests
    through `LibraryRequest.Instantiate` (Elci), and those never run on the JVM: there is no JVM equality to
    diverge from. They are **exempt** from `JULC0087`, whatever their type arguments (canonical builtins such as
    `Bytes`, producer-owned types, or Java types), and equality is the producer's, Data equality (`EqualsData`) for
    Elci. The exemption is decided by who requested the instantiation, kept with the request, not by the erased
    `Map<String, PirType>` environment (`JavaLibraryProvider.java:288-317`). Tests: `GenericExportsTest`'s
    specializations (`member` and `direct` at `Bytes`, `items.head().equals(target)`, and the producer-owned sum,
    `:43-46`, `:147-163`, `:242-263`) stay accepted, with requests at the Java `Hash` and `PubKeyHash` types and a
    foreign sum; Java calls to templates and template-to-template calls stay rejected. ADR-059's provider contract
    is unchanged.
    - **For a future change.** An ADR that lets Java code call generic templates must check equality at the
      javac-bound types through **comparison obligations**: per generic method, a set of pairs of an equality
      operation (with its container) and a compared type expression over the type variables, taken from the body,
      from callees with their type arguments substituted (`h<Optional<T>>`) and from lambdas, to a fixed point,
      then checked by the rule above after substitution (so `member` at `byte[]` is safe after M1 and `direct` is
      not). This ADR does not implement it.
  - **JuLC's JVM collections compare `byte[]` by value (M1).** On-chain, every JuLC collection compares by Data
    equality, so `JulcList<byte[]>.contains(p)` is true for an equal array. On the JVM, `JulcArrayList`,
    `JulcAssocMap` and `JulcArrayImpl` use `Objects.equals` and `List.equals`, so the same call is `false` for a
    distinct equal array, and the user's JVM tests do not test the validator. M1 changes all three, wherever a
    `byte[]` appears as an element, key or value:
    - membership and lookup (`contains`, `get`, `lookup`, `containsKey`) and `delete` compare by content
      (`Arrays.equals` for `byte[]`; a nested JuLC collection compares by its own, equally deep, `equals`); duplicate keys keep their
      prepend semantics, so the first matching entry wins, as on-chain (`JulcMap.of(a, 1).insert(b, 2).get(a)`
      becomes 2 for equal `a` and `b`, as on-chain, where it was 1);
    - `equals` and `hashCode` of the collections compare and hash `byte[]` contents (`Arrays.hashCode`), keeping
      the equals/hashCode contract;
    - arrays are not copied: a collection holds the caller's arrays, and mutating one after insertion changes the
      collection's hash, as with any mutable key; the JavaDoc says so.

    Contract tests cover membership, lookup, delete, duplicates, whole-collection equality and hashing (including
    in a `HashSet`), and mutation. This is an **off-chain behaviour change of julc-core** (Compatibility, release
    notes); a JVM test that relied on identity matching changes its result, towards the on-chain one.
  - These are policy rejections in D13, with their rejection tests; ADR-062 T3, T4 and T5 are updated in M3.

### D7. Types from javac

`TypeRegistrar` and `TypeResolver` register records, sealed interfaces, `@NewType` and ledger types from
`TypeElement`s and `TypeMirror`s, under I11. Ledger types come from the signature stubs, so ledger sources are no
longer parsed on every compile. This closes #284 and #286 by construction.

### D8. Libraries and the source pool

- **The source pool, per host.** Each host gives `JavacFrontend` its **roots** (the units it compiles) and a
  **pool** of discoverable sources. javac attributes the roots and, through the source path, exactly the pool
  units they reference; those units' trees are collected from `ANALYZE` events and lowered in FQCN order (I4).
  **Each entry point's pool equals the one it has today**, with the same class loader; each adapter has a test,
  and the library-roundtrip and multimodule scenarios run on the javac frontend:
  - Gradle plugin: the validator as root; every other non-validator file of the source directory, annotated or
    not (`CompileJulcTask.java:102-113`, `:137`), and `META-INF/plutus-sources` of the compile classpath's JARs, as
    pool;
  - annotation processor: the round's validators as roots; same-project libraries collected per round, plus the
    processor class loader's `META-INF/plutus-sources`, as pool (`JulcAnnotationProcessor.java:129`, `:197`);
  - testkit: the given sources as roots; `SourceDiscovery.buildLibraryPool` as today (the thread context
    loader's `META-INF/plutus-sources`, plus same-project sources found through import paths, which take
    precedence);
  - `JulcCompiler.compile(String)` and `compileMethod(String)`: the given source as root; the class-loader scan
    they do today (`JulcCompiler.java:126-133`, `:806-814`) as pool;
  - CLI, playground, `julc-wasm`: the project or editor sources as roots; the pools and class loaders they scan
    today, not only the bundled stdlib (`ProjectSourceResolver.java:23`, the REPL's thread context loader at
    `ReplEngine.java:35`, `JulcPlaygroundServer.java:46`, `FullApi.java:36`); the CLI build and REPL adapter tests
    include an external source JAR;
  - Elci: the units `JavaLibraryProvider` is given are **roots** (today it compiles every supplied unit's
    non-generic methods, `JavaLibraryProvider.java:45-88`), and `LibrarySources.readJars` dependencies are the
    pool. A test exports an otherwise unreferenced library, with its schemes and specialization.
  - **Empty input.** `new JavaLibraryProvider(List.of(), …)` stays valid (`JavaLibraryProvider.java:42`, `:204`).
    It builds a **metadata-only task**: a `JavacTask` over the same file manager and stubs with no compilation
    units, wrapped in a small JuLC type that exposes only its `Elements` and `Types`, so `parse()` and `analyze()`
    **cannot be called** on it (a test checks the wrapper's surface) (with no units they throw
    `IllegalStateException: no source files`); ledger metadata is read through the task's `Elements`, the same
    path every other input uses for stubs (D7, I11), and the exports are empty. `LedgerTypeProvider` is this
    empty provider, and Elci uses it: a regression through the real embedded file manager and shipped stubs checks
    its `types()` and `namedDefinitions()` (34 types and 79 named definitions at B0) against the JavaParser
    frontend's.
- **Precedence and duplicates.** A qualified name in the roots wins over the pool. Within the pool, same-project
  sources win over JAR sources (today's processor rule, `JulcAnnotationProcessor.java:333-335`). Two JAR sources
  with one name are an error (`LibrarySources.readJars`, today). Every choice is made before javac, so javac
  never sees a name twice.
- **Lowering.** `LibraryCompiler` lowers every included non-generic static method, as today.
- **Generic library methods (Elci).** `JavaLibraryProvider` keeps its schemes and `Instantiate` specialization.
  Specialization stays an explicit PIR-type environment: `JavacFacts` maps a method's type variables to that
  environment's entries, so producer-defined types need no Java declaration. Tests: schemes, specialization with
  producer types, recursive calls, fingerprints, unsupported exports, and same-PIR overloads (accepted) against
  different-PIR overloads (`JULC0054`).
- **Overloads.** Registries still key by `"Class.method"`; javac's binding replaces only JuLC's lookup of the
  declaration.

### D9. Complete units, and REPL expressions

- `compileMethod`, `JulcEval.forSource` and the path-based entry points take **complete compilation units**, which
  now type-check in full (Compatibility).
- **REPL expressions.** Two REPLs wrap expressions today, each with its own code: `ExpressionEvaluator`
  (julc-tools) and the CLI's `ExpressionWrapper` (`ReplEngine.java:67`, `ExpressionWrapper.java:61-88`), both
  typing integer literals as `BigInteger`, which is not Java. Both move to one shared typed-wrapper service:
  - it attributes `var v = (expr);` (with `v` an identifier that does not occur in the expression) in a probe
    wrapper to learn the expression's type `T`;
  - it then generates `static R eval() { return E; }`, with `R = T` for supported on-chain types, and
    `R = BigInteger`, `E = BigInteger.valueOf(expr)` for `int` and `long`;
  - it adds the stdlib and ledger imports, and maps positions back by the wrapper's fixed prefix.

  **Integer arithmetic in the REPL is JuLC's, not Java's:** it is unbounded on-chain (I9, T6), so
  `2147483647 + 1` evaluates to 2147483648 where the JVM gives -2147483648. The wrapper fixes only the types
  javac checks; the REPL docs state the T6 difference. Tests: numeric (including an overflow case, pinned as
  T6), boolean, bytes, library calls, imports, a packaged unit, diagnostic positions, and the CLI's evaluation,
  `:type` and `:pir` commands.

### D10. The harness compares frontends

`julc-difftest` compiles each case with both frontends while both exist:

- `UplcExecutor`'s cache key gains the frontend.
- A case's verdict is per frontend; a disagreement between the frontends is reported even when both agree with
  the JVM or both diverge.
- Each milestone's gate (exact commands, toolchains, corpus counts, profiles and seeds pinned in the M0 evidence
  file) runs: every saved `.diffcase`; the G0 exhaustive corpus; the G1 fixed-seed campaign; ADR-066's
  rediscovery checks; `Javac21ParityTest` with `-Pjulc.javac21.required=true` (never skipping) until D11 retires
  it; and I1 over the corpus. ADR-066's mutation checks are part of the gate once ADR-066 M4/M5 provide them;
  until then they are not prerequisites.

### D11. ADR-067 on the javac frontend

- **Semantics.** ADR-067 I9 pins JLS 21 with javac 21.0.3 as its oracle. JuLC's oracle becomes the supported javac
  (25, `-source 21`, D3): the user's own build compiles their tests with it. Where javac 25 and javac 21.0.3
  differ (the `Pair(C c, var t)` / `Pair(var t, C c)` / `Pair(D d, D d2)` switch, which javac 25 accepts even with
  `--release 21`), JuLC follows javac 25 and must compile the program correctly. ADR-067 gets a revision: I9's
  oracle, the outcome-matrix rows that change, M2b's gate for that shape, and the retirement of
  `Javac21ParityTest` in favour of the same suite against the supported javac. The retargeted suite keeps a
  cross-version comparison: where javac 21.0.3 is available it still runs, and every 21-vs-25 difference is
  reported against a checked-in list, so a new one fails the suite.
- **Codes.** javac's `pattern.dominated`, `not.exhaustive` and constant checks replace the JuLC checks that
  replicate them; M3 lists which of `JULC0071`–`JULC0075`, `JULC0082` and `JULC0083` are retired and which stay as
  subset rules (for example colon-form fall-through, `JULC0075`). `SwitchPatternChecker` keeps only subset rules.
- **Remaining milestones.** ADR-067 M2a and M2b (record patterns), M3 (two sealed parents), M3b (record members,
  ledger names), M4 (cross-type `equals`) and M5 are implemented on the javac frontend in this ADR's M3, not on
  JavaParser (D4: the exposure until M5 is accepted).

### D12. Deletion

After M5: the JavaParser frontend classes, the four reader classes, `ImportResolver`, the Java half of
`SwitchPatternChecker`, the source-side parts of `TypeResolver`, `TypeRegistrar` and `TypeInferenceHelper`, and
JavaParser from `julc-cli` (lint rules on `TreePathScanner`), `julc-tools`, `julc-verification` (on
`javax.lang.model`) and `julc-playground`'s build. `JavaConstants` survives, ported (I9). A build check fails on any
`com.github.javaparser` import.

### D13. The I1 ledger

M0 freezes, per corpus case: its source digest, and the JuLC reference frontend's acceptance, diagnostic (code,
message, position), FLAT digest per level and target, source locations, and harness observations. Observations
are split in two:

- **semantic observations:** the value, the traces and the failure, compared with the JVM (and, for UPLC-only
  cases such as malformed Data, with the case's expected UPLC outcome);
- **budgets:** CPU, memory and script size.

Each milestone appends the javac frontend's row and, for every difference, one classification with its evidence:

| Class | When | Evidence required |
|---|---|---|
| **Semantic fix** | Semantic observations differ, and the javac frontend agrees with the JVM where the reference did not | An issue; a saved `.diffcase` on which the javac frontend agrees and the reference diverges |
| **UPLC-only semantic fix** | Semantic observations differ on a case with no JVM counterpart (malformed Data, ADR-067's UPLC-only checks) | The decision that defines the outcome; the UPLC-only test asserting it |
| **Policy rejection** | The javac frontend rejects a case the reference accepted, by a decision of this ADR (D6, D11, D14, I11) | The decision; a rejection test with the code; docs and release notes |
| **javac rejection** | javac rejects a migration case (dialect, D6) | The rewritten Java source, which gets its own row |
| **Accepted by decision** | The javac frontend accepts a case the reference rejected (escape shapes, javac 25's switch shapes, ADR-067 M2a/M2b record patterns) | The decision; a `.diffcase` per shape on which the javac frontend agrees with the JVM |
| **Diagnostic change** | Both reject, with a different code, message or position | For a coded JuLC rejection, only a decision this ADR names or I2b's javac-first rejection; for an uncoded one, its new code (I2a) |
| **Provenance change** | Only source locations differ (I7) | The location pairs, and the rule that explains them |
| **Representation only** | Bytes differ; semantic observations and budgets equal | The PIR diff |
| **Cost change** | Semantic observations equal; budgets differ | The budget delta; a review decision if any budget grows |
| **Reference change** | The reference itself changed (D4's exceptions) | The new frozen base, and every reference row re-run |

- A difference in semantic observations is never "representation only" or "cost", whatever the cause.
- **Negative fixtures** (cases the corpus expects to be rejected, such as ADR-063's `reject-*` cases and the I2a
  inventory) are never rewritten. M0's inventory marks each one either **permanent** (a subset rejection: its rows
  must stay rejections, and a changed diagnostic is a "diagnostic change") or **scheduled for acceptance** (a
  frontend limitation a decision lifts, such as `reject-javac-21-25-pair-shape` under D11 or ADR-067 M2a/M2b's
  record patterns: its source and historical rows stay, and it gains an "Accepted by decision" row with that
  class's evidence). Only migration cases (sources meant to compile) are rewritten for javac.
- A case may move between classes only by a new row; rows are never edited. The ledger is a checked-in,
  generated file per milestone, and its totals (accepted, rejected, each class) appear in the milestone's
  evidence.

### D14. `@Param` fields are never constant variables

A `@Param` field (an applied script parameter) is runtime input. If it is a constant variable in Java's sense
(`static final`, primitive or `String`, constant initializer), javac would fold it, and the program the user's
JVM tests run would not be the script (ADR-067's `when ENABLED` case is `pattern.dominated` to javac). The javac
frontend rejects such a declaration with `JULC0088` (`PARAM_CONSTANT_VARIABLE`); the fix is to drop `final`, or to
initialize from a non-constant expression (`Boolean.valueOf(true)`). `BigInteger` and other reference types other
than `String` are never constant variables, so their `@Param` fields are unaffected. This closes #293;
ADR-067's `@Param` guard tests move to the accepted spellings. Constants depending on a `@Param` are then not
constants for javac either.

## Options and alternatives

| Option | Status |
|---|---|
| Keep JavaParser, fix each reading divergence (ADR-065 D) | Rejected: every fix adds javac-replica code, and the name/type class (#209, #284, #286, #287, #290, #291) has no bounded fix |
| JavaParser plus a javac cross-check (ADR-065 C) | Rejected: two readers remain on every target |
| javac trees → JuLC source IR → PIR | Rejected for now (D2) |
| javac trees → PIR through `JavacFacts`, with I12 and I13 | **Chosen** (D2) |
| Lowering inside the user's javac (ADR-065 B) | Deferred (open question 3) |
| Eclipse JDT | Rejected (ADR-065): a third reader |
| Keep JavaParser in the browser only | Rejected: javac runs in the Web Image |
| Mirror every fix into both frontends (r1's D4) | Rejected in r2: the reference must stay fixed for I1, and the JavaParser frontend is deleted at M6 |
| Keep JLS 21 checks where javac 25 differs | Rejected: JuLC would reject programs the user's own javac compiles and runs |

## Affected stages and modules

- **Stages:** parsing, subset validation, import/name/type resolution, library discovery, PIR generation, debug
  metadata. Not affected: the backend, PIR optimization, UPLC generation, serialization, VMs.
- **julc-compiler:** new `frontend/javac/` package (`JavacFrontend`, `EmbeddedFileManager`, `JavacFacts`, the
  lowering, the ported `JavaConstants`); `JulcCompiler`, `LibraryCompiler`, `JavaLibraryProvider`,
  `LibrarySources`, `CompilerOptions`; build tasks for the platform blob and the API stubs.
- **julc-stdlib, julc-ledger-api, julc-verification:** stubs generated from their classes (no source change).
- **julc-core:** stubs; and the JVM collections' `byte[]` equality (D6, M1), a source change.
- **julc-testkit, julc-gradle-plugin, julc-annotation-processor, julc-cli, julc-tools, julc-playground,
  julc-wasm:** host adapters (D8), the option (D4), the blob in their images, and the M6 ports.
- **julc-difftest:** D10, D13.
- **Downstream:** Elci (native image, provider), the external `julc-examples`, `julc-helloworld`.

## Compatibility

- **Script bytes change** where the D13 ledger says so; each milestone's ledger totals go into the release notes.
- **Source that stops compiling:**
  - JuLC's dialect (D6): `BigInteger` operators, `int` literals as `BigInteger`, implicit imports, `@NewType`
    `of(...)`;
  - reference equality (outside D6's narrow constant-string case), and `.equals` that would compare an array by
    identity, through records, sealed variants, containers or type arguments (D6);
  - a constant-variable `@Param` (D14);
  - string `+` with a non-`String` operand (`JULC0090`, #296), and a `JulcArray` in any Data-encoded position
    (`JULC0091`, #298); both fail at run time today;
  - a sealed interface without `permits` whose implicit permitted subclasses are not exactly its nested records
    (I11);
  - annotations outside I10 (for example a third-party annotation on a validator);
  - sources in JuLC API packages (D3);
  - Java 22–25 syntax (`-source 21`).
- **Source that starts compiling:** shapes JuLC's readers rejected but javac accepts inside the subset (ADR-065
  I2c: escape digits that are not ASCII hex digits, a Unicode-escaped backslash before raw backslashes and `u`), with
  javac's meaning on every target; record-pattern switches that javac 25 accepts (D11).
- **Off-chain behaviour of julc-core's collections:** `JulcList`, `JulcMap` and `JulcArray` compare `byte[]`
  elements, keys and values by content on the JVM from M1, as on-chain (D6); membership, lookup, delete,
  `equals` and `hashCode` change for distinct equal arrays.
- **Whole units type-check:** a test source that used an undeclared helper, a missing import, or another invalid
  construct JuLC never looked at, is now a javac error.
- **API:** I5; `CompilerOptions.frontend` is new, experimental, and removed in M6.
- **Runtime:** compiling needs `jdk.compiler` from JDK 25 (D3); native and Web images embed it. The CLI grows by
  about 16 MB and the full Wasm image by about 2.6 MB brotli (spike), less JavaParser's share.

## Risks

1. **Correct bindings, wrong lowering.** Evaluation order, state threading, exit targets and failure behaviour stay
   JuLC's responsibility. Mitigation: I1 and the D13 ledger per method from the first ported construct, I13's
   binding check, the harness gates (D10), and porting rule by rule.
2. **Byte-identity long tail.** Tree-shape differences and `TypeMirror` → `PirType` refinements produce many small
   differences. Mitigation: D13's classes force each to be explained by evidence, not by declaration.
3. **Exposure on the frozen default frontend until M5** (D4). Accepted: preview, pins kept, short window;
   security-class defects are fixed in both frontends (D4, exception 2).
4. **Hosts.** Source-pool discovery, the nested javac in the annotation processor, Elci's native image and the
   Web Image (preview) are proven by M1 before lowering work.
5. **Cold-start cost.** Mitigation: blob cache (D3); M1's E6 gate.
6. **Platform drift.** Mitigation: the supported-javac rule and the blob digest (D3); I4's two-axis check.
7. **Migration churn for users.** The dialect removal touches most hand-written examples. Mitigation: M1 rewrites
   every in-repo and example source, and the release notes give each rewrite.

## Implementation milestones

Each milestone is one or more commits on PR #267's branch, reviewed (Claude reviewer and Codex) before the next
starts. "Harness gate" is D10's set. Every gate's commands, toolchains, counts and seeds are pinned in the
milestone's evidence; a skipped check fails the gate.

| ID | Scope | Entry gate | Exit criteria | Invariants / tests |
|---|---|---|---|---|
| **M0** | Freeze B0 and the corpus: in-repo `julc-examples` validators, the 16 stdlib libraries, ledger sources, `verification/blaster/fixtures`, `verification/c7/fixtures`, every compiler/stdlib/testkit test source, the `.diffcase` set, G0, the G1 fixed-seed corpus, the external `julc-examples`, Elci's smoke and `custom-library`. Build the D13 ledger's reference rows, the I2a rejection inventory, and D6's scan of `.equals` call sites that `JULC0087` would reject. Freeze D4's accepted-divergence list with each pin's ID. Add this ADR as a step of ADR-062, and D4's preview-tracking sentence to `SECURITY.md` | This ADR accepted; the integration's full run green | `adr/evidence/068-javac-frontend-baseline.md` with B0's SHA, the pinned commands, and the generated ledger and inventory | I1, I2a, I7, I11 baselines |
| **M1** | `JavacFrontend` (D3) and D5, run as a **gate before the JavaParser frontend** on every compile: a javac error rejects, nothing else changes. D6's comparison rules in both frontends (D4 exception 1, re-freezing the reference as B1); the JuLC collections' `byte[]` value equality on the JVM (D6). D6's dialect rewrite of every in-repo migration source; the operand-order issue filed. Hosts: JVM, Gradle plugin, annotation processor, testkit, native CLI, playground, `julc-wasm` (a stdlib-using and a `@RequiresSigner` validator in the real image), Elci native shape, each built without `--add-exports` | M0 | Every migration case accepted by both is byte-identical to B1 (I1a); every migration case javac rejects is a "javac rejection" row whose rewrite is accepted and costs no more, in size and budget, than the order-correct lowering of its dialect original (D4); negative fixtures stay rejected; warm compile ≤ 2× on the corpus, cold recorded with a threshold set in review; one rejection test per Java 22–25 feature; the negative control; each host smoke passes | I1, I2b, I2c, I4, I6, I10 |
| **M2** | D7 behind `frontend=JAVAC`; `JavacFacts`; the stub generator's attribute check | M1 | I11's registry equality and independent encoding tests on the whole corpus; #284 and #286 cases agree; `LedgerTypeProvider` equal | I3, I11, I5 (Elci types) |
| **M3** | Port the non-loop lowering, in order: literals, names, static constants (with D14); calls (stdlib, library, instance dispatch, D6's comparison rules); records, fields, object creation; locals, `if`/`else`, `return`; `instanceof`; `switch` with ADR-067 M1 guards and the ported `JavaConstants`; lambdas; whole-library lowering and Elci's specialization (D8); entry points and `@Param`; debug metadata. Then the subset validator on javac trees, with D6's equality rules and D11's ADR-067 milestones | M2 | Per construct: I1b ledger rows for every covered method, I1a for fully covered programs, all classified; the harness gate on covered programs; I13's binding check over the corpus; the rejection inventory passes for ported constructs; ADR-067 and ADR-062 revisions | I1, I2a, I3, I7, I9, I12, I13 |
| **M4** | Port loop lowering: for-each, `while`, `break`, accumulators, from B0's lowering (which includes the loop-break continuation fix) | M3 | I1 ledger rows for every loop method; the loop regression tests, including the loop-break continuation suite; T21 and T23 cases agree; the harness gate including G0's loop shapes | I1, I3, I13 |
| **M5** | Default `frontend=JAVAC` | M4; **zero `JULC0084` on the corpus; I1 ledger complete** (every case has a javac row) | Full `./gradlew build`; external `julc-examples` and `julc-helloworld`; Elci's release smoke against a local snapshot; native and Wasm smokes; a cost and size report against M0; docs and release notes | All |
| **M6** | Delete JavaParser (D12) | M5 | No `com.github.javaparser` on any class path; equivalence gates for the lint rules and MCP output, `TestDiscovery` (`julc check`), `JavaMetadataExtractor` and the `julc-verification` resolvers, against M5's outputs on the corpus; full build; harness gate; Elci coordinated | I8 |

## Verification strategy

- **Independent oracle.** javac/JVM is the reference for meaning (ADR-063). The JavaParser frontend at B0 is the
  reference for bytes, PIR and acceptance (I1, D13); neither is derived from the javac frontend.
- **The D13 ledger** records every difference with its class and evidence; observation differences always need a
  JVM-agreeing `.diffcase` or a policy decision.
- **Rejections:** the I2a inventory by code and position; javac's rejections by key (I2b); one test per I2c
  predicate and per Java 22–25 feature.
- **Binding:** I13's PIR binding check; ADR-060's lint and independence tests; a negative control that binds one
  library method to another must fail I1b, I13's check and the harness.
- **Encodings:** I11's registry equality plus independent Data and blueprint-schema tests.
- **Hosts:** each target is built and run, not inferred (M1), without `--add-exports`.
- **Determinism:** shipped blobs across hosts, and regenerated blobs across two JDK 25 patch releases (I4).
- **Downstream:** Elci and the external examples (M5).

## Open questions

1. **The platform blob's breadth.** D3 derives it from what attribution loads. Should it instead be the whole
   exported `java.base` API (about 3.9 MB raw, 0.4 MB brotli in Wasm), with I2 rejecting the rest? Lean: the
   derived set, for size; I10's rule is the policy either way. Decide by M1's review.
2. **Language level.** *Decided in r2:* `-source 21` (D3).
3. **Hosted lowering in the user's javac** (ADR-065 option B). Lean: defer; reconsider after M6.
4. **When ADR-061 decides its representation,** is it inserted between javac trees and PIR for statements (D2)?
   Lean: yes, as ADR-061's own migration.

## Revision history

- **r1** (2026-10-30): initial proposal, after the ADR-065 spike and consultation on the seam.
- **r2** (2026-10-31, responds to the reviews of `ba481784`: the review comment F1–F21 and Codex's F22–F35):
  - F1, F2, F22 → D6's `compareTo` rule is a behaviour change closing #146 for the `OP 0` form, with sequential
    `let`s, matched on the resolved `BigInteger.compareTo`, the literal zero only, twelve-form tests; operators
    evaluate left to right (issue filed in M1); ADR-066's `REDISCOVER_146`, T13 and `TRACE_IN_COMPARISON` stated.
  - F3, F25 → equality by JLS §15.21: constant-string `==` kept, other reference `==` `JULC0086`, array `.equals`
    and implicit record `equals` over arrays `JULC0087`; remedy `ByteStringLib.equals`; T3–T5 cited.
  - F4 → `-source 21` (open question 2 decided) and I2c's per-kind predicates with Java 22–25 rejection tests.
  - F5, F27 → I13 (binder hygiene and a PIR binding check) and I12 (validated input: nominal dispatch, no
    fallbacks, no error types); I1b is alpha-equivalence; "Hygiene today" added.
  - F6, F28 → I10 includes `julc-verification`'s annotation types; other annotations are `cant.resolve` with a
    hint; M1 smokes a `@RequiresSigner` validator.
  - F7, F29 → D3's ownership check before javac (`JULC0081` for API packages), stdlib libraries as sources only,
    and D8's per-host source pools, precedence and duplicate rules.
  - F8, F34 → D3's supported-javac rule (feature 25, `JULC0089`, version and blob digest recorded) and I4's
    two-axis determinism check.
  - F9 → D4 freezes the JavaParser frontend and accepts its exposure until M5; D11 puts ADR-067's remaining
    milestones in M3.
  - F10, F24 → D11: the supported javac is the oracle; ADR-067 I9, the outcome matrix and M2b revised; the parity
    suite retargeted; retired codes listed in M3.
  - F11 → I9 and D12: `JavaConstants` is ported, not deleted.
  - F12, F31 → I11 covers class-file types (`PermittedSubclasses`, kept attributes, a build-time order check),
    sibling-nested permitted classes, special ledger layouts by FQCN, and independent encoding tests.
  - F13 → Context records the maintainer's decision and maps ADR-065's criteria to gates (E6's 2× restored); this
    ADR adds the ADR-062 step.
  - F14, F35 → milestone gates pin commands and counts; M5 entry needs zero `JULC0084` and a complete ledger; M6
    gets equivalence gates; ADR-066's mutation checks sequenced; the parity suite must not skip.
  - F15 → I7 columns are character offsets.
  - F16 → D3 derives the blob from attribution's class loading; open question 1 restated.
  - F17, F30 → I5 lists Elci's surface; D8 keeps schemes and specialization as PIR-type environments, with tests;
    FQCN library order (I4).
  - F18 → counts over `.java` files; `julc-playground`'s dependency added to D12.
  - F19, F33 → D9 separates complete units from REPL expressions and defines typed wrappers; implicit imports and
    `@NewType` `of(...)` added to the dialect.
  - F20 → I6 covers image builds and a missing `jdk.compiler` (`JULC0089`).
  - F21 → D5's hint covers `doesnt.exist`; I2a keeps the position gate; the option is marked experimental.
  - F23 → D14: constant-variable `@Param` fields are rejected (`JULC0088`), closing #293.
  - F26 → D13, the frozen I1 ledger with evidence per class; I1 rewritten around it.
  - F32 → I2a's rejection inventory covers uncoded rejections.
  - Also: B0 moves to the #267 head after the integration merges (ADR-067 F85, ADR-066's last round, the loop-break
    continuation fix and its harness), so M4 ports the corrected loop lowering and its entry gate no longer
    waits for #254/#255.
- **r3** (2026-10-31, responds to the reviews of `9f8f0a95`: the review comment F36–F44 and Codex's F50–F59):
  - F50 → D6 normalizes zero-left forms by reversing the operator and gives the explicit mapping table; tests cover
    `a > b`, `a = b` and `a < b` for all twelve forms.
  - F51 → D6's constant-string exception is narrowed to literals, constant `String` variables and string `+`
    outside guards; other constant-string comparisons are `JULC0086`, with overflow and conditional tests.
  - F36, F52 → `JULC0087` is defined on the static type after substitution, through records, sealed variants,
    containers and type arguments, conservatively for unresolved types; the `@NewType` remedy and an M0 call-site
    scan are added.
  - F37, F53 → D3's ownership check works by provenance: bundled sources are accepted by digest (JuLC's own
    tests compile them as roots), and other sources may not replace or extend a stub or bundled name or package.
  - F38, F57 → D8: each entry point keeps today's pool and class loader (testkit `SourceDiscovery`,
    `compile`/`compileMethod` class-loader scans, Gradle's whole source directory); Elci's supplied units are
    roots; I10's pool wording fixed; roundtrip and multimodule scenarios on the javac frontend.
  - F39, F54 → D13 separates semantic observations from budgets and adds the classes "UPLC-only semantic fix",
    "Accepted by decision", "Diagnostic change", "Provenance change" and "Reference change"; negative fixtures
    are never rewritten, and M1's rewrite gate applies to migration cases only.
  - F40, F56, F58 → D9: one shared typed-wrapper service for both REPLs (`ExpressionEvaluator` and the CLI's
    `ExpressionWrapper`); REPL integer arithmetic is stated as JuLC's unbounded semantics (T6), with tests for
    `:type`, `:pir` and an overflow case.
  - F41 → D4 adds a security exception: such fixes go into both frontends as reference changes, so releases
    between M1 and M5 are allowed.
  - F42 → I6 allows listed native-image configuration entries naming internal classes, checked per JDK; code
    references and `--add-exports`/`--add-opens` stay forbidden.
  - F43 → D4 exception 1: the `compareTo` rule goes into both frontends at M1, and M1 gates that rewritten sources
    cost no more than their dialect originals.
  - F55 → I12 requires elements only for the tree kinds that denote declarations, and non-error types for
    expressions.
  - F59 → D3 defines the supported builds and makes a patch-to-patch output difference release-blocking, with a
    versioned decision; D11's retargeted suite keeps the javac 21.0.3 comparison.
  - F44 → the `compareTo` rule is in both frontends (no per-frontend profile needed); D14's remedy fixed;
    `getSourceVersions()`; the blob is derived from in-repo sources only; §15.21 needs a primitive operand; I13
    covers duplicated continuations; I10's stubs exclude compiler-facing stdlib classes; M0 adds this ADR to
    ADR-062.
- **r4** (2026-10-31, responds to the reviews of `4327d528`: the review comment F60–F65 and Codex's F70–F76):
  - F60, F74 → D6's minimal binding (none for `<`, `<=`, `==`, `!=`; one `let` on `a` for `>`/`>=` when both
    operands are non-trivial); M1's gate measures against the order-correct lowering of the dialect original; the
    JavaParser-side copy matches through JuLC's receiver typing behind the javac gate (D4).
  - F61, F70 → the constant-string rule recurses through constant initializers and alias chains; string `+` with a
    non-`String` operand is `JULC0090` (#296); the operand-order issue is #295.
  - F62, F71 → `JULC0087` covers every equality-using operation, `java.util.List`/`Map` included; the stop rule is
    an explicit, tested value-equality allow-list; JuLC's JVM collections compare `byte[]` by value from M1.
  - F63 → bundled sources are the shipped `plutus-sources` manifest (16 entries, digests).
  - F64 → security-class covers both directions; maintainers classify under `SECURITY.md`.
  - F65 → "diagnostic change" cannot relax I2a; finite-path recursion; `OP 0L` keeps today's lowering.
  - F72 → I12 distinguishes `NEW_CLASS` (constructor element) from `NEW_ARRAY` (array type, no element).
  - F73 → negative fixtures are marked permanent or scheduled for acceptance in M0's inventory.
  - F75 → CLI, playground and Wasm keep their actual scans and class loaders, with external source-JAR tests.
  - F76 → an empty `JavaLibraryProvider` builds no `JavacTask` and keeps today's metadata and empty exports.
- **r5** (2026-10-31, responds to the reviews of `5c32a668`: the review comment F80–F83 and Codex's F90–F94):
  - F82 → maintainer decision (2026-10-31): the known public divergences stay open on the default frontend until
    M5, listed in D4 and frozen in M0; exception 2 covers security-class defects newly found during M1–M5; M0 adds
    the preview-tracking sentence to `SECURITY.md`.
  - F80 → an empty `JavaLibraryProvider` builds a task with no units over the stubs (one path for ledger
    metadata), with a `LedgerTypeProvider` equality test.
  - F83, F91 → D6 specifies the JVM collection change across `JulcArrayList`, `JulcAssocMap` and `JulcArrayImpl`
    (membership, lookup, delete, duplicates, `equals`, `hashCode`, mutation), with contract tests and a
    Compatibility entry.
  - F81, F93 → the equality-operation table is derived from "the JVM implementation compares elements or keys"
    (`lookup` and `TypedMapLib` added, `indexOf` dropped, plain `JulcMap.insert` excluded); the allow-list names
    `PlutusData`'s variants, and an unlisted type with `equals` is rejected; class-file constants use
    `getConstantValue()`; I10 says all of julc-core is stubbed.
  - F90 → the JavaParser-side `compareTo` matcher requires a nominal `java.math.BigInteger` receiver and argument,
    with `@NewType` and shadowed-name tests.
  - F92 → `JULC0087` in generic library methods is checked at instantiation; producer-owned types use their
    language's equality (Data equality for Elci); `GenericExportsTest`'s specializations stay accepted.
  - F94 → `JULC0090` exempts guard constant concatenation computed by ADR-067's evaluator.
- **r6** (2026-10-31, responds to the reviews of `448505e5`: the review comment F100–F103 and Codex's F110–F114):
  - F100 (and Codex's F90, resolved differently) → the JavaParser-side `compareTo` matcher matches `IntegerType`,
    with the equivalence argument: that frontend already lowers every such `compareTo` to the integer comparison
    (a declared `@NewType` `compareTo` is ignored today, T31), so only #146's evaluation count changes; nominal
    matching stays in the javac frontend.
  - F101 → per-method summaries of compared type variables, propagated through calls and lambdas, checked at Java
    call sites; two-level chain tests.
  - F102, F112 → the exemption is decided by who requested the instantiation (a non-Java producer's
    `LibraryRequest.Instantiate`), kept with the request, not by the erased PIR environment; Java call sites are
    checked nominally (`Hash` rejected, `PubKeyHash` accepted).
  - F110 → nested JuLC collections: the on-chain `contains` defect is filed (#297) and fixed in the javac frontend
    in M3 by encoding the target by its static type, with differential tests.
  - F111 → the `JULC0090` exemption is limited to guards that are constant as a whole; concatenation inside a
    non-constant guard is `JULC0090`.
  - F113 → the empty provider is a metadata-only task on which `parse()`/`analyze()` are never called; the launcher
    claim is corrected.
  - F103, F114 → `TypedMapLib.remove` added and the table checked against every supported signature; the
    `PlutusData` wording fixed; M0's row gains the accepted-divergence list and the `SECURITY.md` sentence; a new
    shape of an accepted row belongs to that row.
- **r7** (2026-10-31, responds to the reviews of `e4eee805`: the review comment's approval with P3s F120–F121, and
  Codex's F130–F132):
  - F130 → the frozen-frontend `compareTo` rule applies where that frontend's dispatch has already selected the
    integer instance handler, after static-library dispatch, so the `Shadow` case keeps today's behaviour; tests
    for it and for a chained accessor that stays rejected.
  - F131, F120 → summaries are comparison obligations (operation and container, compared type expression over the
    type variables), substituted through calls and lambdas; `member` against `direct` at `byte[]` and a callee
    introducing `Optional<T>` are tested.
  - F132 → nested-collection acceptance is bounded by Data encodability; `JulcArray` inside a `JulcList` or
    `JulcMap` is `JULC0091`.
  - F121 → the metadata-only task is wrapped so `parse()`/`analyze()` cannot be called, with a surface test.
- **r8** (2026-10-31, responds to the reviews of `a7109518`: the review comment's F125 and Codex's F140–F141):
  - F125, F141 → `JULC0091` covers every Data-encoded position, checked recursively (record and variant components,
    `Optional`/tuple arguments, collection elements, keys and values, `JulcArray` elements, boundary types);
    `dataEncodable` is not relied on; arrays of Data-encodable types stay supported; #298 filed and, as a newly
    found divergence, fixed in both frontends at M1 (D4 exception 2).
  - F140 → Java calls to generic templates and template-to-template calls stay rejected as ADR-059 specifies, so
    only producer requests instantiate templates, and they are exempt; the comparison-obligation summaries become
    a requirement on a future ADR that lifts ADR-059's restriction, not part of this one.
