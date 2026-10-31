# ADR-068 M2: types from javac

M2 obtains types from javac instead of JavaParser, behind `CompilerOptions.frontend = JAVAC`
([ADR-068](../068-javac-frontend-migration.md) D7, I3, I5, I11). No program compiles through that frontend yet: lowering
is M3 and M4, so a compilation ends in `JULC0084`. What M2 establishes is the type model lowering will read.

Machine and toolchain as in M0's evidence. Base `fb7eb525`, branch `wip/068-m2`.

## What was delivered

| Piece | Where |
|---|---|
| `CompilerOptions.frontend` (`JAVAPARSER` by default, `JAVAC`, experimental) | `CompilerFrontend`, `CompilerOptions`, `CompilationContext` |
| `JavacFacts`: tree to `Element`, tree to `TypeMirror`, `TypeMirror` to `PirType` by identity, positions | `frontend/javac/JavacFacts.java` |
| D7: records, sealed interfaces, `@NewType` and the ledger registered from javac's model, into the same registries, in the same dependency order | `frontend/javac/JavacTypeRegistrar.java`, `TypeResolver` (`registerRecord`, `registerSealed`, `lookupNominal`, `registry`) |
| A program the frontend has read, ending in `JULC0084` | `frontend/javac/JavacProgram.java`, every `JulcCompiler` entry point |
| The ledger's types through the metadata-only task, a provider's through its units (D8, I5) | `frontend/javac/JavacLibraryTypes.java`, `JavaLibraryProvider`, `LedgerTypeProvider(CompilerOptions)` |
| Special layouts by FQCN (I11) | `frontend/javac/LedgerLayouts.java`, `TypeResolver.ledgerHashFqcns()` |
| The stub generator's attribute check (I11) | `tool/StubAttributes.java`, run by `generateJavacStubs` |
| `JULC0092` (`SEALED_PERMITS_REQUIRED`), I11's "asking for an explicit `permits` clause" | `diagnostics.json`, `JavacTypeRegistrar` |

`JavacFacts` holds what M2 uses. The binder names of I13 and the literal values and guard constants of I9 come with M3.

The JavaParser path changed only mechanically, and only so the two frontends share code: `TypeRegistrar.dependencyGroups`
and `unproductive` became public statics, `LibraryTypeDescriptions.deriveOperations` is static, and `JulcCompiler` has one
`classpathLibraries` helper for five copies of the same lines. The gate-off baseline below shows no byte moved.

### Rules worth stating

- **Identity.** A type is its `TypeElement`'s qualified name. Built-in types are matched by FQCN, and everything else by
  FQCN in the registries. Nothing resolves by simple name, so `TypeResolver`'s simple-name and alias paths are not used.
- **Tags (I11).** A source interface follows its `permits` clause; without one, the records nested in it in
  `ClassTree.getMembers()` order, and if javac's `getPermittedSubclasses()` is not exactly that list, `JULC0092`. A
  class-file interface follows `PermittedSubclasses`.
- **Fixed by FQCN (I11).** `Credential`'s tags are in `LedgerLayouts.FIXED_TAGS`; a stub that disagrees is
  `BrokenJulcBuildException`, not a new encoding. `Value`'s map layout is `LedgerLayouts.NOT_CONSTRUCTOR_DATA`; it is not
  inferred from the stub's `toPlutusData`. A hash type is its bytes, by `ledgerHashFqcns()`. A source record's layout
  still comes from the `toPlutusData` it declares, as on the source path.
- **Order.** Declarations are registered in source order, dependencies first, through the same
  `TypeRegistrar.dependencyGroups`, so which references of a recursive group are nominal comes out alike. Library units
  are in qualified-name order (I4).
- **Rejections.** A type rule keeps the source path's message. javac rejects first where it can (I2b).

## Commands

```sh
# The corpus: M0's frozen recordings, plus a recording of this head, whose sources M1 rewrote into Java. The frozen
# recordings are mostly dialect, which javac rejects, so without the head they would say little about accepted sources.
./gradlew :julc-compiler:test :julc-compiler:pairCaseTest :julc-stdlib:test :julc-testkit:test :julc-difftest:test \
    :julc-examples:test --offline --continue --rerun -Pjulc.corpus.record=build/m2/corpus-head

# Registry equality, encodings and blueprints (Adr068TypesTest, Adr068EncodingsTest). Writes under -Padr068.out.
# -Padr068.freeze=true writes the reference from the JavaParser frontend instead of reading adr/evidence/068/.
./gradlew :julc-difftest:adr068Types -Padr068.corpus=<M0 recording> -Padr068.corpus.external=<M0 external> \
    -Padr068.corpus.elci=<M0 Elci> -Padr068.corpus.head=build/m2/corpus-head -Padr068.out=<dir> --offline

# The default path against B1.
./gradlew :julc-difftest:adr068Baseline -Padr068.corpus=<M0 recording> -Padr068.corpus.external=<M0 external> \
    -Padr068.corpus.elci=<M0 Elci> -Padr068.external=<ext-examples> -Padr068.out=<dir> --offline
```

## Results

Measured at `9d970d4a` (with the evidence tools' one later message fix). Generated files are in `adr/evidence/068/`.

| Exit criterion (ADR-068 M2) | Result |
|---|---|
| I11's registry equality on the whole corpus | Yes. Of the frozen 11,417 cases, 5 differ, all T33, as #284 and #286 say they should. Details below |
| `LedgerTypeProvider` equal | Yes: 34 types and 79 named definitions, equal, and `types()` in the same order |
| Independent encoding tests: ledger types | Yes: all 74 (67 records and variants, 7 hash types) against the ledger's own `toPlutusData`, frozen |
| Independent encoding tests: corpus types | 887 of 893 layouts equal the Data of a compiled, evaluated program. 1 differs (T33), and 5 have no reference. See below |
| Blueprint schemas | 4,249 of 4,250 interfaces equal the frozen reference; the other is a D8 difference, classified below |
| Stub generator's attribute check | Yes, in the generator: a blob that fails is not written |
| #284 and #286 cases agree | The names bind to the user's records in all 7 saved cases. The values cannot be observed before M3 |
| Default path unchanged | Yes: gate off, 11,410 of 11,417 cases are identical to B1; the other 7 are M1b's rewritten Blaster fixtures |

### Registry equality (I11)

For each case the tool reads the JavaParser path as `JulcCompiler` runs it (the ledger, its aliases, the libraries, the root)
and compares records, sealed interfaces, every variant's sealed parents, the `@NewType` set and the parameterized sums,
deep, including registration order. Every case compares the ledger's registrations too. javac reads the case twice: all
units as roots, which compares everything the reference registers; and as D8 has it, the root with the libraries on the
source path, whose every declaration must be in that registry, equal. A provider case also compares `types()` and
`namedDefinitions()`.

Over the frozen corpus ([`068/types-m2-totals.tsv`](068/types-m2-totals.tsv) is the union below):

| Class | Frozen 11,417 | With the head, 17,034 | Meaning |
|---|---:|---:|---|
| `equal` | 4,822 | 10,281 | javac accepts; the registries are equal |
| `equal-tolerant` | 6,493 | 6,632 | javac rejects the source, the dialect in method bodies; its declarations, read tolerantly, are equal (limits below) |
| `equal-reference-rejects-lowering` | 5 | 6 | Registries equal. The reference's provider then fails while lowering (`JULC0054`, or a stdlib library without its neighbours); the javac path has no lowering yet, so that rejection moves to M3 |
| `ledger-source` | 38 | 38 | A ledger file's row. The ledger is compared in every other row, and once through `LedgerTypeProvider` |
| `both-reject` | 4 | 12 | The same message from both: `JULC0091`, the `@NewType` rules, `Unknown type`, a recursive group with no base |
| `both-reject-tolerant` | 3 | 3 | javac rejects first; read tolerantly, the same type rejection |
| `both-reject-javac-first` | 28 | 38 | javac's own rejection (I2b): lexical errors, syntax, `duplicate class`, an unresolved name |
| `reference-rejects` | 15 | 15 | JuLC's reader rejects what javac accepts: ADR-065 I2c's escape shapes and unpaired surrogates, scheduled for acceptance in M0's inventory. No reference exists to compare with |
| `javac-rejects-unreadable` | 4 | 4 | javac rejects and its model cannot be read: 2 sources in `org.julclang.ledger` (`JULC0081`, before javac), and 2 M0 sources whose `permits ProofStep.Branch` names a top-level record, which M1b rewrote |
| `DIFFERENT` | 5 | 5 | Classified in [`068/m2-accepted-differences.tsv`](068/m2-accepted-differences.tsv): the T33 pins |

No case is `DIFFERENT-order`, `DIFFERENT-d8`, `DIFFERENT-provider`, `DIFFERENT-tolerant` or `DIFFERENT-javac-type-rejects`,
and no case hits `JULC0092`. The tool fails on any difference the accepted file does not list, and on a stale entry. Two
runs write identical files. Per part, over the union: every Blaster (7), C7 (6), G0 (376), G1 (123) and Elci (4) case is
`equal`, and the external examples are 100 `equal` and 2 `equal-tolerant`.

The five differences are the source path's defect: it drops a top-level user record whose name a ledger type has (#284, four
pins), and keeps a user `PubKeyHash` out of its record registry by a simple-name check (#286). javac registers the user's
declaration.

### Independent encodings (I11)

**Ledger types** (`Adr068LedgerEncodingTest`, in the ordinary `test` task). A real Java value of every ledger record and
variant is built by reflection, with a distinct value in every leaf, and encoded by the ledger's hand-written
`toPlutusData`. The result is frozen in `julc-difftest/src/test/resources/adr068/ledger-encodings-b1.tsv` (74 rows). The
javac registry, read from the stubs, must give the same Data through `Adr068Encodings.encode`, which takes tags, order and
field types only from the registry and matches fields by name. The JavaParser registry goes through the same encoder as
the encoder's control. `Value` is a map by `LedgerLayouts` (`aValueIsAMap`).

**Corpus types** (`Adr068EncodingsTest`). For every user record, `@NewType` and sealed variant in a source javac accepts, a
sample value is written as Java. With `-Padr068.freeze=true`, the JavaParser frontend compiles a method returning it, into
the case's own class or, for a fixture that does not compile alone, into its declarations alone, and the Java VM evaluates
it; the digest of that Data is frozen in [`068/encodings-b1.tsv`](068/encodings-b1.tsv). Otherwise the javac registry's Data
is compared with the frozen digest. A layout the reference never saw is a failure, so a wrong layout cannot hide as a new key.

| Outcome | Layouts | |
|---|---:|---|
| Equal | 887 | |
| Different | 1 | T33, #286: `record PubKeyHash(byte[] h)`. The reference's Data is the bytes; javac's registry gives `Constr 0 [bytes]`, which is what the JVM's record is |
| No reference | 5 | The reference produces no Data: 4 rows of a `sealed interface U extends S` fixture, which it rejects (`class inheritance is not supported on-chain`), and T33's user `Value`, which fails at run time |
| No sample | 2 | `NativeBox` and `Bad.Box`: a native `JulcValue` or `JulcG1` component, which has no Data encoding; both are rejection fixtures |

120 further layouts appear only in sources javac rejects, so they have registry rows and no Data row: such a source never
reaches the javac frontend.

**Blueprints.** The JavaParser frontend's `ContractSchema` is rebuilt with javac's types for every datum, redeemer and
`@Param`, and javac's named definitions, and `SchemaGenerator` renders both. The frozen file is
[`068/blueprints-b1.tsv`](068/blueprints-b1.tsv). The one difference is a library the root never references that declares a
second `Token`: the reference registers it, so the blueprint names `com.myapp.Token` by its stable id, and javac does not
read that unit (D8), so the name stays `Token`. The schema's shape is the same. It is listed, and in the follow-ups.

### Build-time check (I11)

`JavacPlatformTool stubs` now runs `StubAttributes.check`. It compares every stub with its class: `Record` (components,
descriptors, signatures, annotations), `Signature`, `PermittedSubclasses` and `RuntimeVisibleAnnotations`, on the class and on
every field and method kept, across all 253 classes. For the 12 sealed ledger types it compares `PermittedSubclasses` with
the order javac's parser reads from the ledger's sources, without attribution. The blob's bytes did not change (sha256
`3d16a357…`). `StubAttributesTest` shows each break found on broken stubs.

### #284 and #286

`Adr068NameCasesTest` runs the six `diverge-t33-*` pins and `agree-nested-rational`. For each one: the user's record is
registered under its identity with its own components in source order, `lookupNominal` returns it, every `new` and accessor
javac binds is the user's, none reaches `org.julclang.ledger`, and a compilation ends in `JULC0084`.

| Case | JVM | JavaParser (pinned, frozen until M5) | javac frontend |
|---|---|---|---|
| `packageless-rational`, `-wildcard` | 7 | 5 | Binds the user's `Rational(denominator, numerator)`; value not observable before M3 |
| `packageless-pubkeyhash-record` (#286) | the bytes | fails | Binds the user's `PubKeyHash(h)`, a record, not bytes |
| `top-level-user-value-on-local`, `-on-constructor` | `true` | fails | Binds the user's `Value(isEmpty)` |
| `user-value-accessor-on-constructor` | `true` | fails | Binds `Probe.Value`; the difference there is in a handler, so it is M3's |
| `agree-nested-rational` | agrees | agrees | Binds `Probe.Rational` |

So neither issue closes yet: `JAVAPARSER` stays the default, and frozen, until M5.

### Default path

`adr068Baseline`, gate off, over M0's recordings of `cbdcbddb`, compared with `adr/evidence/068/*-b1*`:

| File | Result |
|---|---|
| `ledger-b0-semantic.tsv`, `ledger-b0-budget.tsv`, `ledger-b0-instantiations.tsv`, `rejection-inventory-b0.tsv` | byte-identical to B1 |
| `ledger-b0.tsv`, `corpus-b0.tsv` | 11,410 cases identical; the other 7 are the Blaster fixtures, under new case keys because M1b rewrote their sources, as at M1's close |
| `totals-b0.tsv` | One line differs: rejection coverage has 147 sites, not 146. The new one is `JULC0092`, marked not reachable from the reference |

### Tests

| Task | Result |
|---|---|
| `./gradlew test --offline --continue --rerun` | 12,826 tests, 0 failures, 0 errors, 530 skipped (JUnit XML of every module); `BUILD SUCCESSFUL in 10m 50s`, and the same totals at the evidence commit (11m 21s), after the mutation runs. M1's close had 12,745 |
| `:julc-compiler:pairCaseTest --rerun` | 71 tests, 0 failures |
| `onChainHarnessTest --rerun` | 16 tests, 0 failures |
| `:julc-wasm:wasmSmokeFull --rerun` (GraalVM 25.3.4.1) | `BUILD SUCCESSFUL`; `wasmImageFull` rebuilt; 56 `ok` lines, none failing |
| `verifyDiagnosticCodes`, `verifyJavacPlatform`, `checkJavacPlatformDerivation` | `BUILD SUCCESSFUL` |

### Cost

Measured once, warm, in a test JVM, on a one-record source: `JavacFrontend.attribute` about 1 ms, and with the ledger and the
unit's types registered about 2 ms, so about 1 ms per compilation. The JavaParser path's parsing and registering of the
ledger is about 5.4 ms. There is no gate on this at M2.

## Mutation evidence

Each mutation breaks one behaviour in the source, runs the checks and is reverted. The stub mutations run the generator.
The others run `JavacTypesTest`, `JavacStubsTest`, `Adr068LedgerEncodingTest` and `Adr068NameCasesTest` with `--rerun`, then
both corpus tools. "Corpus" lists what the tools report beyond the unmutated run's 5 and 1 and 1.

| Mutation | Unit tests that fail | Corpus |
|---|---|---|
| Implicit-permits tags reversed | 4 | 14 more `DIFFERENT`, 4 `DIFFERENT-tolerant`, 6 unknown layouts |
| `permits` tags reversed | 2 | 2,901 more `DIFFERENT`, 1,765 tolerant, 681 more blueprints, 25 more Data, 477 unknown layouts |
| Ledger (class-file) tags reversed | 5 | Every row: 10,287 more `DIFFERENT`, 6,632 tolerant, 27 more blueprints |
| Record fields reversed | 17 | Every row; 165 more blueprints, 4 more Data, 512 unknown layouts |
| `@NewType` registered as a record | 8 | 25 more `DIFFERENT`, 21 tolerant, 6 more blueprints, 20 unknown layouts |
| A hash type resolved by simple name too (#286's defect) | 2, including the saved #286 pin | None |
| Hash types not bytes | 6 | Every row; 26 more blueprints, 2 unknown layouts |
| `Value` not special | 3 | 2 more `DIFFERENT`, 29 `DIFFERENT-provider` |
| A built-in resolved by simple name (`Optional`) | 1 | None |
| The `JULC0092` check removed | 4 | None |
| Type arguments dropped | 8 | Every row; 40 more blueprints, 10 unknown layouts |
| Recursive groups not predeclared | 2 | 23 `DIFFERENT-javac-type-rejects` |
| Stubs drop `Record`, `Signature`, runtime-visible annotations or `PermittedSubclasses` (four runs) | `generateJavacStubs` fails, naming each class | Not reached |

Three mutations pass the corpus tools and fail only unit tests: no corpus case shadows a built-in name, hits `JULC0092`, or
uses a user type named like a hash as a field type. So those three rest on the unit tests and the saved pins. The stale-result
warning applies here too: an earlier mutation run read results Gradle's file watching had left stale, so the results above come
from runs that clear the XML first, and the two whose unit results still looked stale were rerun with `--no-watch-fs` and a
recompile.

## What this does not establish

- **No program compiled through javac.** On the javac frontend every compilation ends in `JULC0084`. Nothing here observes a
  value, a trace or a script from it. That #284 and #286 now agree with the JVM is inferred from the binding; M3 observes it.
- **The javac side of the encoding comparison is a test encoder, not lowering.** `Adr068Encodings.encode` takes tags, order
  and field types from the registry under test, and is compared with Data from a real compilation and VM run. So it tests
  the registry. Whether M3's lowering uses the registry correctly is M3's I1.
- **The JavaParser frontend's Data is the reference, not the truth.** Where that frontend is wrong about a type (T33), the
  comparison shows a difference, and the JVM decides; each one is listed below. Where both are wrong alike, nothing here
  sees it, except for ledger types, which also meet the ledger's own `toPlutusData`.
- **The tolerant tier reads rejected sources.** Its cases have the dialect's implicit imports added, and javac's model is
  read despite errors in method bodies. No compilation does this; `TolerantJavacTypes` exists for this evidence only. It
  compares declarations of sources the javac frontend rejects, which is why it is reported apart.
- **Data covers accepted sources.** Layouts that appear only in sources javac rejects have their registries compared, not
  their Data: such a source never reaches the javac frontend.
- **Blueprints take the entry points from the reference.** Which methods and parameters form the interface comes from the
  JavaParser frontend's schema; only the types come from javac. Finding entry points on javac is M3.
- **Rejections compare messages, not anchors.** The corpus tool compares a rejection's text. Position parity is I2a's gate,
  in M3.
- **Elci was not rebuilt or run.** Its four recorded `JavaLibraryProvider` cases compare by types, and `LedgerTypeProvider`
  by equality; `ElciCompiler` calls `new LedgerTypeProvider()` and `new JavaLibraryProvider(...)`, which keep their
  signatures. No Elci binary ran against this branch.
- **The head recording is not frozen, and was made in two passes.** It lives in this worktree's `build/`. The command above
  regenerates its kind, but this one was recorded during development: julc-compiler, julc-stdlib, julc-testkit and
  julc-examples first, then julc-difftest, in which two `Adr068RejectionSitesTest` tests failed because `JULC0092` was not
  yet catalogued (fixed since; neither compiles a corpus source). Tests added after it are not in it. The frozen
  `encodings-b1.tsv` and `blueprints-b1.tsv` hold what it produced.
- **DevKit end-to-end tests were not run.** Nothing here changes a script on the default frontend.

## Deferred to M3

- `JavacFacts`' binder names (I13) and literal values and guard constants (I9), and `TYPEVAR` to the specialization
  environment (D8): each comes with the lowering that reads it. A type variable is `Unknown type` for now, as a record
  component's is on the source path.
- The subset validator on javac trees (I2c), and every rejection it owns. `JULC0084` stands in front of them, so a source
  the subset rejects is not accepted: it is not compiled at all.
- D8's per-host pools. `compile(String)` and `compileMethod(String)` already give javac the whole class-path scan as pool;
  the other hosts' adapters come with M3.
- The option on the Gradle plugin, CLI and testkit (D4): at M2 each would only report `JULC0084`.
- New known issues are in [`068-followups.md`](068-followups.md).
