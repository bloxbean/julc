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
| Hosts give both frontends what their resolution finds (`LibrarySourceResolver.resolve`), so a unit, a root included, is a library on `JAVAC` exactly where it is one on the reference (round 2, review F2); javac also reads the bundled stdlib for its types. Directory listings that decide an order are sorted (F5) | `JulcCompiler`, testkit `SourceDiscovery`/`JulcEval`, annotation processor, CLI `build`, Gradle plugin |

## Hosts (D8)

| Host | Pool on `JAVAC` | Adapter test |
|---|---|---|
| Gradle plugin | Every non-validator file of the source directory, in name order (round 2) | `JulcPluginFrontendTest.aLibraryOfTheSourceDirectoryCompilesAsOnTheReference` |
| Annotation processor | Same-project and class-loader libraries, as resolved | `JulcAnnotationProcessorFrontendTest.aLibraryOfThePoolCompilesAsOnTheReference` |
| Testkit | `SourceDiscovery`'s pool, as resolved; `JulcEval.forClass` on `JAVAC` uses it | `FrontendOptionTest.aLibraryOfThePoolRunsOnJavacAsOnTheReference` |
| `JulcCompiler.compile(String)`, `compileMethod(String)` | The class path's scan, as resolved | `JavacLibrariesTest.theClassPathIsThePoolWhereTheCallerNamesNone` |
| CLI `build` | The project's and class path's libraries, as resolved | `BuildCommandFrontendTest.aProjectLibraryBuildsAsOnTheReference` |
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

**The I13 failure that was reported once, explained.** It was not a failure of the code. The mutant tool rewrote one shared
`mut.log` and read failures from the Gradle XML reports after a whole batch had run, so the failures it showed for the control were
those of the batch's last mutant, M20 ("library `let`s not bound for I13"), whose failure is exactly "resolves to a binder of a
generated name" in the three tests whose library methods call a method of their own class by its simple name. The control itself
had failed on a fixture of `JavacExportsTest` (a recursive template in a class with a static field). Checked: `JavacLibrariesTest`
and its neighbours run 17 times without `--rerun` (14 on a warm daemon, 3 with `--no-daemon`) and 20 times in shuffled order in
one JVM, with no failure. The tool now reads each mutant's own report before the next one runs, so a control's failures are its own.

## Round 2: review fixes

After M4's merge (`25e383ad`), the review's findings, in new commits (`5e90734e`, `acd6ee32`, `c36b9c1f`, `0832ef9f`):

| Finding | Fix | Test |
|---|---|---|
| F2 (P0): a root lowered as a library where the reference would not | Every host gives both frontends what `LibrarySourceResolver.resolve` finds; `librariesFor` is gone. The reference lets two binders of one name reach `DelayedMethodPass` (a library copy of a root's method and the root's own); the pass now lets a binder hide an outer delayed or total name of its own name (`let`, lambda, pair, list and Data matches, `letrec`), on both frontends | `DelayedMethodPassTest`; `JavacLibrariesTest`: a may-fail static field through `compileMethod`, validator and testkit (value 58 at every level, both frontends); p27's bytes equal the reference's |
| F1 (P1): NPE in `LibraryRegistration` | The reference's whole retry loop and final pass: passes while one makes progress, then each unit left once more in order, the first that still fails being `Undefined variable`; a class's field calls are registered before its methods | `LibraryRegistrationPropertyTest` (jqwik: random call graphs of six methods over two and three units, every unit order, bytes against the reference at `NONE` and `PV11_SAFE`, value against a model of the JVM); `JavacLibrariesTest`'s final-pass rows in both orders |
| F3: a concrete overload of a generic name | Any static generic overload of a name makes the name a template's: "Generic library method … cannot be called from Java yet"; an unqualified generic call in a library is the reference's `Unknown method` unless a specialization binds it | `JavacLibrariesTest`, `JavacExportsTest` |
| F4: a root's static fields that call a library | A class's field calls come before its methods in registration; a field waiting on a later unit is retried, a cycle is the reference's message | `JavacLibrariesTest` |
| F5: unsorted listings | `CompileJulcTask.collectJavaFiles`, `ProjectScanner`, `scanFileSystemSources`, `SourceDiscovery`'s listing are sorted by name. I1 replays explicit lists, so no corpus byte moves; plugin-built example bytes were not measured | Plugin and testkit suites |
| F6: cheap diagnostics | `try` and `throw` give the reference's `TRY_CATCH_UNSUPPORTED` and `THROW_UNSUPPORTED`; `float`/`double` the reference's message; duplicate type ownership in the provider the reference's `IllegalArgumentException`; a rejected template one unsupported export; `JULC0084`'s fix names the default frontend. The rest are follow-ups | `JavacRejectionsTest`, `JavacLiteralsTest`, `JavacExportsTest` |

## Round 2: casts

| Cast | `JAVAC` | Where |
|---|---|---|
| `(T) x`, `(T)(Object) x` to a reference type | The operand's term, as the reference; a view as a map decodes what is not a map yet (`UnMapData`), once | `Casts.cast` |
| `PlutusData.cast(x, T.class)` | The same rule; the second argument must be a class literal | `Casts.plutusDataCast` |
| `(int)` of a `long` | Java's value: `((x + 2^31) mod 2^32) - 2^31`, folded for a constant | `Casts.narrowToInt` |
| `(long)` of an `int`, a cast to the same type | The operand | `Casts.primitive` |
| `(byte)`, `(short)`, `(char)` | `JULC0084` "a cast to …" (I8): no representation yet | `Casts.primitive` |
| What a cast's operand is read as | The cast's type, unless that is Data, then the operand's, as the reference reads it | `CallTargets.seen` |
| A raw receiver through a cast (`ValuesLib.java:43`) | Accepted in the stdlib, where the reference accepts it; `JULC0087` elsewhere (D6) | `EqualityRules.isRawReceiver` |

**The narrowing (a decision for the coordinator).** The reference erases `(int)`, so `(int) 2147483648L` is `2147483648` there and
`-2147483648` on the JVM and on `JAVAC`. That is a Semantic fix (T6's narrowing, D4's cap), saved as
`diverge-068-narrowing-cast.diffcase` and already in the corpus as `diverge-t6-narrowing-cast-in-if`; `JavacCastsTest` compares
with the JVM at every level, at the boundaries (`0`, `±1`, `Integer.MAX_VALUE` and `MIN_VALUE` and one past each, `2^32`, `2^32 + 5`, `-2^32 - 5`,
`Long.MAX_VALUE`, `Long.MIN_VALUE`). Its price is
three builtins per non-constant narrowing: in MerklePatriciaForestry's `nibble`, where the value is always in range, 26 bytes
and about 1.05M CPU and 2.4k memory per read. If the cost is not wanted, the alternative is to keep the reference's erasure and
reject `(int)` of a non-constant `long` (I8), not to erase it silently.

### What moved

I1 over the 17,094 cases at `0832ef9f`'s code (scratch output `i1-6`, not frozen), against the M4 table:

| From | To | Rows |
|---|---|---|
| `not-ported` | `i1a-identical` | 3,509 |
| `reference-rejects-not-ported` | `both-reject` | 78 |
| `not-ported` | `DIFFERENT-javac-rejects` | 36 |
| `DIFFERENT-pir` | `DIFFERENT-bytes` | 33 |
| `not-ported` | `DIFFERENT-bytes` | 13 |
| `reference-rejects-not-ported` | `DIFFERENT-rejection` | 2 |
| `both-reject-javac-first` | `both-reject` | 2 |
| `not-ported` | `DIFFERENT-pir` | 1 |

Totals: `i1a-identical` 5,956 → 9,465; `not-ported` 3,647 → 88. First constructs left: `a contract schema` 34, `an equals between
unrelated types` 22, `an expression of type java.lang.Object` 11, `fromPlutusData` on a ledger type or hash 7, `debug metadata` 4,
and 10 others; `a library unit`, `TYPE_CAST` and `a library method` are 0. No row left `i1a-identical`, and no accepted row changed
class. One row is `REFERENCE-DRIFT`: the new `.diffcase`, which has no frozen reference row yet (the integrator's
`freezeReference`), not a change of the reference.

`068/g3a-accepted-differences.tsv` classifies all 85 `DIFFERENT` rows not in the frozen table:

| Class | Rows | Why |
|---|---|---|
| Policy rejection (D6, `JULC0087`) | 28 | `.equals` on `byte[]`, which M0's scan lists, in programs a cast or a library stopped before |
| Semantic fix (F1042) and Cost change (F1042) | 15 + 18 | The frozen reasons of the `DIFFERENT-pir` rows (`callbackHelper`, `helperCallers`); they compile with their libraries now, so the difference reaches the bytes |
| Cost change (casts, grows) | 11 | MerklePatriciaForestry's `(int)` keeps Java's value: the narrowing above |
| Policy rejection (D15, `JULC0095`) | 5 | As in round 1 |
| Policy rejection (D6, `JULC0086`) | 3 | `==` on references |
| Diagnostic change (two errors) | 2 | The reference's first error and javac's scan's differ |
| Semantic fix (T6 narrowing) | 1 | `diverge-t6-narrowing-cast-in-if` |
| Cost change (a written hash type in a lambda) | 1 | Follow-up row of that name |
| Cost change (D8, falls) | 1 | As in round 1 |

**MPF** (`G3aMpfProbeTest`, scratch, 18 rows at each of four levels): every row has the reference's result (all `Success`).
Bytes are equal in 33 of 72 rows; the validator is 26 bytes larger at every level. Budgets are equal in 48: the six paths that
read a nibble differ at every level, by 1,053,514 CPU and 2,410 memory per read (`doFork` 21,294,630 → 22,348,144 CPU at
`NONE`; `excluding(leaf,leaf)` reads two).

### Tests

New or extended: `JavacCastsTest` (reference bytes for reference casts, map views, `PlutusData.cast`, record casts through a
`var` and a typed local; the narrowing against the JVM; the stops; `ValuesLib` through `compileMethod`), `LibraryRegistrationPropertyTest`,
`DelayedMethodPassTest`, and `StdlibLibrariesPropertyTest` (jqwik, on random inputs against the Java bodies, both frontends:
`MathLib`, `ByteStringLib`, `ListsLib`, `CryptoLib` with the JVM crypto provider, `BitwiseLib`, `IntervalLib`; `MapLib` and
`ValuesLib` across the two frontends, since their Java bodies need the ledger's run-time types). The not-ported samples of the
host tests are a `(short)` cast now.

At `0832ef9f`, whole modules, one run (21 minutes):

| Module | Tests | Result |
|---|---|---|
| julc-compiler | 6,563 | 0 failed |
| julc-difftest (with the scratch `G3aMpfProbeTest`) | 2,103 | 0 failed |
| julc-stdlib | 570 | 0 failed |
| julc-cli | 472 | 0 failed |
| julc-testkit | 237 | 0 failed |
| julc-gradle-plugin | 51 | 0 failed |
| julc-annotation-processor | 30 | 0 failed |

### Mutation evidence

23 mutants of the round-2 lines, both directions where a rule has two, each run with `JavacLibrariesTest`, `JavacExportsTest`,
`JavacCastsTest`, `LibraryRegistrationPropertyTest`, `DelayedMethodPassTest`, `JavacRejectionsTest` and `JavacLiteralsTest`, five
minutes each, with a no-op control before and after (script and table in the scratch directory). The limit now starts once the
machine's lock is held: a control that waited on another worktree's run had timed out before Gradle started.

| Killed | Mutants |
|---|---|
| Narrowing | erased; a constant not narrowed; `(short)` as identity |
| Map views | a cast to a map never decoding; always decoding; `PlutusData.cast` never decoding |
| What a cast is read as | always its written type; always its operand's |
| Raw receivers | accepted outside the stdlib; rejected in it |
| Registration (F1, F4) | no retry passes; a silent final pass; no final pass; a class's field calls not ordered |
| Library targets (F2, F3) | an interface's static method as a library; a template only when the called overload is generic; an unqualified generic call accepted; one rejected inside a specialization; duplicate type ownership accepted |
| Diagnostics (F6) | `try` accepted by the scan |
| `DelayedMethodPass` (F2) | a `let` not hiding a delayed name; a lambda not hiding a delayed or total name |

Three survived at first and are killed by tests added for them: `PlutusData.cast` never decoding (a `PlutusData.cast(d,
JulcMap.class)` row), what a cast is read as when it is Data (`aVarOfACastToDataIsWhatWasCast`, the reference's rejection) and the
lambda's hiding (`DelayedMethodPassTest`'s parameter rows). One survives: `Casts.valuesOf` not reading through a cast that leaves
its value as it is. Java needs an `(Object)` step between Data and a map, so such a cast is the single value left, and `isMap`
then reads the term's type, which says the same; no program I could write that javac accepts tells them apart.

Both controls survived, on the final tests. The 22 killed and the one survivor are on the same tests.

## Not done

- `.diffcase` files for the semantic rows: round 1's nine are not semantic, and D13's "two limits of a `.diffcase`" says D8's
  rows, which need a pool, cannot be saved. Round 2's semantic row is `diverge-068-narrowing-cast.diffcase`.
- jqwik covers the stdlib libraries named above; `ContextsLib`, `AddressLib`, `OutputLib` and the rest are reached through I1's
  corpus, not by properties.
- `./gradlew test` over every module (round 2 ran the seven above whole), `pairCaseTest`, `onChainHarnessTest`, `wasmSmokeFull`,
  the native CLI, the external examples and DevKit were not run. Example bytes built by the Gradle plugin, which F5's sorting could
  move, were not measured.
- MPF's comparison is `G3aMpfProbeTest`, a scratch test kept out of the tree.
- The table is not frozen (the integrator freezes with `g3a-accepted-differences.tsv`).
- Playground, `julc-wasm` and the REPL have no frontend option yet, so their pools on `JAVAC` are not exercised.

## ADR points for the reviewer

- **I4 and the `let` order.** Lowering, and so the first rejection, follows qualified-name order. The `let`s follow the reference's
  registration over the host's order, as the coordinator's brief asked ("library lets as the reference builds them, so bytes match
  the reference where I1 compares"). Read literally, I4 would put the `let`s in qualified-name order too and move the bytes of
  every program with two independent library methods (follow-ups, "A library's registration order").
- **What the reference cannot register** is kept on `JAVAC`: a forward call by class name inside a library, a nested class's call
  of its outer class by name, a cycle of such calls. Stricter than Java on both frontends (follow-ups).
- **The resolving hosts** (round 2): both frontends compile as libraries what `LibrarySourceResolver.resolve` finds, a JavaParser
  reading, so a library copy of a root exists exactly where the reference makes one. M6 must replace that reading.
