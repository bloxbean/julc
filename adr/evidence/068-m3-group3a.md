# ADR-068 M3 Group 3a: libraries and Elci

Group 3a ports D8's whole-library lowering to `frontend=JAVAC`, with Elci's `JavaLibraryProvider` (exports, schemes,
specialization) and each host's pool. Branch `wip/068-g3a`, from `17bf4ff1` (M4 frozen). Entry points, `@Param`, contract
schemas and debug metadata are Group 3b's.

## The rules

| Rule | Where |
|---|---|
| The units lowered as libraries are what the reference compiles as libraries, of what javac read: each pool unit the host offered, and a root whose text the host offered in the pool too. A bundled source the host did not offer is read for its types alone (D8) | `JavacFrontend.analyze` (`Attribution.offered`), `JavacProgram.offered` |
| Every static method of every class (not interface, record or enum, at any depth, outer first) of those units is lowered; a generic one is a template. A class's static fields are bound around each of its methods, under the field's own name, as the reference does | `JavacLibraries` |
| Units lower in qualified-name order (I4). The library `let`s follow the reference's registration over the host's order: a unit's methods register as they lower, a call by class name of a method not registered yet fails the unit for that pass, the unit keeps what it registered and is tried again while a pass makes progress, and then the first unit that still fails is `Undefined variable` of the class name. So a qualified call of a method declared later in the same class, or of a nested class from its outer class, and a cycle of such calls between units, are rejected as on the reference | `LibraryRegistration` |
| A library call goes to the stdlib lookup first; failing that, to the library binder. A unit with no binder (a bundled source the host did not give, a library compiled as a root that the host did not also give) is the reference's `Undefined variable`, with no position, as the reference throws it | `CallTargets.qualifiedStatic`, `Calls.library` |
| A primitive argument where a library of the unnamed package takes Data is the reference's warning; in a library it rejects the class, as the reference's generator holds warnings with its errors | `Calls.narrowerArguments`, `JavacLibraries.lowerUnit` |
| Library terms are in no source map (the reference drops its library generators' maps) | `Terms.unlocated` |
| A library's static field holds `JULC0095`'s closed list on both frontends | `SubsetRules.library` |
| Step 2 scans each library unit after the root; step 3 rejects a validator annotation, or a role conflict, in a library unit; library units' names count for the binder-namespace check; library `let`s are bound for I13's check | `JavacCompilation`, `JavacLibraries.scan`, `requireNoValidators`, `ValidatorAssembly.bindLibraries` |
| `JavaLibraryProvider` on `JAVAC`: the units are roots compiled as libraries; each public static method of a class is an export, a scheme when generic (an unbounded type parameter only), or unsupported with the reference's reason; overloads of one name need one source signature; a specialization lowers the generic method with its type variables bound, by element, to the producer's PIR types | `JavacLibraryExports`, `JavacFacts.withTypeVariables` |
| Hosts: on `JAVAC` a host gives its whole pool, the reference's resolved sources first and in their order, so the `let`s match | `LibrarySourceResolver.librariesFor`; `JulcCompiler.compile(String)`/`compileMethod(String)`, testkit `SourceDiscovery`/`JulcEval`, annotation processor, CLI `build` |

## Hosts (D8)

| Host | Pool on `JAVAC` | Adapter test |
|---|---|---|
| Gradle plugin | Every non-validator file of the source directory, as on the reference (unchanged) | `JulcPluginFrontendTest.aLibraryOfTheSourceDirectoryCompilesAsOnTheReference` |
| Annotation processor | Same-project and class-loader libraries, whole | `JulcAnnotationProcessorFrontendTest.aLibraryOfThePoolCompilesAsOnTheReference` |
| Testkit | `SourceDiscovery`'s pool, whole; `JulcEval.forClass` on `JAVAC` uses it | `FrontendOptionTest.aLibraryOfThePoolRunsOnJavacAsOnTheReference` |
| `JulcCompiler.compile(String)`, `compileMethod(String)` | The class path's scan, whole | `JavacLibrariesTest.theClassPathIsThePoolWhereTheCallerNamesNone` |
| CLI `build` | The project's and class path's libraries, whole | `BuildCommandFrontendTest.aProjectLibraryBuildsAsOnTheReference` |
| Elci's `JavaLibraryProvider` | Its units are the roots, each compiled as a library; empty input is the metadata-only task | `JavacExportsTest` |
| Playground, `julc-wasm`, REPL | No frontend option yet: they compile on the default frontend | Not applicable until they expose it |

## What moved

**I1** over the 17,094 cases (`adr068Lowering`, at the code of `754769bd`; the run's table is in the scratch output, not frozen),
against the M4 table:

| From | To | Rows |
|---|---|---|
| `not-ported` | `i1a-identical` | 1,534 |
| `reference-rejects-not-ported` | `both-reject` | 17 |
| `not-ported` | `DIFFERENT-javac-rejects` | 7 |
| `not-ported` | `DIFFERENT-bytes` | 1 |
| `reference-rejects-not-ported` | `DIFFERENT-rejection` | 1 |
| `not-ported` | `not-ported`, at another construct | 1,749 (1,725 from `a library unit` to `TYPE_CAST`) |

Totals: `i1a-identical` 5,956 → 7,490; `not-ported` 3,647 → 2,105; covered methods 17,475 → 104,806 (library methods are
counted now). First constructs: `a library unit` 3,334 → 0, `the exports of library sources` 35 → 0, `a call of a library
unit's own method` 10 → 0, `a library method` 23 → 4 (a unit the reference is given and javac does not read), `TYPE_CAST`
315 → 2,144. Elci's corpus: 4 of 4 identical; the stdlib's: 10 identical, 3 not ported, 4 the reference rejects.

Every row that already agreed still agrees: no row left `i1a-identical`, and no accepted row changed class.

The nine new `DIFFERENT` rows, each classified in `068/g3a-accepted-differences.tsv`:

| Class | Rows | Why |
|---|---|---|
| Policy rejection (D15, `JULC0095`) | 5 | A library's static field holds the closed list on `JAVAC`, as at the gate; the reference ran with the gate off |
| Policy rejection (D6, `JULC0086`) | 2 | `==` on references, in programs a library stopped before |
| Cost change (D8, falls) | 1 | An unreferenced pool unit is no `let` on `JAVAC` |
| Diagnostic change (two errors) | 1 | `JULC0095` in a library before the root's `JULC0059` |

**Why most library programs still stop.** Whole-library lowering reaches every method of a library a program reads. `ValuesLib`,
`MapLib`, `ContextsLib`, `AddressLib` and `OutputLib` hold `(T)(Object)` casts (`ValuesLib.java:43` among them), and casts are not
ported, so 1,725 rows moved from `a library unit` to `TYPE_CAST`. That is the cast slice's.

**MPF.** `MerklePatriciaForestry.java:40` holds `(int) ByteStringLib.at(...)`, so all 72 rows stop at `TYPE_CAST` on `JAVAC`: no
bytes or budgets to compare yet. The reference's 72 rows are unchanged (`G3aMpfProbeTest`, scratch). Rerun when casts lower.

## Tests

At `326315f9`, then the two difftest fixtures below:

| Module | Tests | Result |
|---|---|---|
| julc-compiler (`:test`, whole) | 6,524 | 0 failed |
| julc-stdlib (`:test`, whole) | 570 | 0 failed |
| julc-testkit (`:test`, whole) | 237 | 0 failed |
| julc-cli (`:test`, whole) | 472 | 0 failed |
| julc-annotation-processor (`:test`, whole) | 30 | 0 failed |
| julc-difftest (`:test`, whole) | 2,098 | 2 failed, both expecting the old stop: `Adr068DelayedMethodsTest.aLibraryStopsOnJavac` is now `aLibraryOnJavacIsTheReferences` (bytes equal at every level), and `Adr068LoweringToolTest`'s varargs helper reads the caller's attempt, since library methods are attempted first. Both pass after |
| julc-gradle-plugin `JulcPluginFrontendTest` | 5 | 0 failed (the module's other tests were not run) |

`JulcAnnotationProcessorFrontendTest.theOptionReachesTheCompilation` and `BuildCommandFrontendTest.theOptionReachesTheCompilation`
failed already at M4's gate, since loops lower; they use a cast now.

New tests: `JavacLibrariesTest` (bytes against the reference at every level, with and without a map, in either pool order;
registration; cycles and forward calls; bundled-only units; overloads; a library as a root; the warning; `JULC0095`;
`compile(String)`), `JavacExportsTest` (exports, materialized bytes, schemes, unsupported, types and definitions, and eleven
specializations, against the reference's provider; rejected requests by code; the empty provider), `StdlibLibrariesPropertyTest`
(jqwik: `MathLib` and `ByteStringLib` methods against their Java bodies on random inputs, both frontends), one adapter test per host.

## Mutation evidence

21 mutants of the new lines, each in both directions where the rule has two (the script and its log are in the scratch
directory), run with `JavacLibrariesTest` and `JavacExportsTest`, 5 minutes each, with a no-op control before and after that
survived. 20 killed:

| Killed | Mutants |
|---|---|
| Registration | no dependency check; registration in name order |
| Binders | an unbound library given a binder; an offered library left unbound; every library bound; bundled sources taken as offered; a root never a library; library `let`s not bound for I13 |
| Fields and maps | a library field not held to `JULC0095`; no field `let`s; library terms located |
| The warning | a warning for any package; no warning; a library warning that does not reject |
| Steps | a library holding a validator; class methods kept across classes |
| Exports | an overload conflict ignored; type variables unbound in a specialization; a template's bound ignored |

One survives: the export's check that its source description equals its compiled type. The provider describes and compiles
from one javac model, so for a supported type they cannot disagree; it stays as the reference's own check.

A first control run without `--rerun` failed I13 in three `JavacLibrariesTest` tests; it did not reproduce in four controls with
`--rerun`, nor in the full runs. Recorded, not explained.

## Not done

- `.diffcase` files for the semantic rows: none of the nine new rows is a semantic difference, and D13's "two limits of a
  `.diffcase`" says D8's rows, which need a pool, cannot be saved.
- jqwik properties cover `MathLib` and `ByteStringLib`, not every stdlib library: the others reach a cast.
- `./gradlew test` over every module, julc-gradle-plugin's whole suite, `pairCaseTest`, `onChainHarnessTest`, `wasmSmokeFull`,
  the native CLI, the external examples and DevKit were not run.
- The table is not frozen (the integrator freezes with `g3a-accepted-differences.tsv`).
- Playground, `julc-wasm` and the REPL have no frontend option yet, so their pools on `JAVAC` are not exercised.

## ADR points for the reviewer

- **I4 and the `let` order.** Lowering, and so the first rejection, follows qualified-name order. The `let`s follow the reference's
  registration over the host's order, as the coordinator's brief asked ("library lets as the reference builds them, so bytes match
  the reference where I1 compares"). Read literally, I4 would put the `let`s in qualified-name order too and move the bytes of
  every program with two independent library methods (follow-ups, "A library's registration order").
- **What the reference cannot register** is kept on `JAVAC`: a forward call by class name inside a library, a nested class's call
  of its outer class by name, a cycle of such calls. Stricter than Java on both frontends (follow-ups).
- **The resolving hosts** use the reference's resolution (`LibrarySourceResolver.resolve`, a JavaParser reading) on `JAVAC` to order
  the pool, and only to order it: a rejection there is ignored, and javac reads the whole pool. M6 must replace it.
