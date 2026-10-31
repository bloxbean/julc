# ADR-068 M2: types from javac

M2 obtains types from javac instead of JavaParser, behind `CompilerOptions.frontend = JAVAC`
([ADR-068](../068-javac-frontend-migration.md) D7, I3, I5, I11). No program compiles through that frontend yet: lowering
is M3 and M4, so a compilation ends in `JULC0084`. What M2 establishes is the type model lowering will read.

Machine and toolchain as in M0's evidence. Base `fb7eb525`, branch `wip/068-m2`. This file states the code at `19b4b555`,
after the three reviews of `3f49c97b`; each result below was rerun there.

## What was delivered

| Piece | Where |
|---|---|
| `CompilerOptions.frontend` (`JAVAPARSER` by default, `JAVAC`, experimental) | `CompilerFrontend`, `CompilerOptions`, `CompilationContext` |
| A frontend-neutral registry: FQCN maps, name-based registration, `lookupNominal`, `snapshot`, with no JavaParser import and no lookup by simple name | `resolve/TypeRegistry.java`; `TypeResolver extends TypeRegistry` keeps the JavaParser and simple-name code |
| `JavacFacts`: tree to `Element`, tree to `TypeMirror`, `TypeMirror` to `PirType` by identity, parent, enclosing and position. Stateless, keyed by the tree; a `TreePath` stays inside it | `frontend/javac/JavacFacts.java` |
| D7: records, sealed interfaces, `@NewType` and the ledger registered from javac's model, in the same dependency order | `frontend/javac/JavacTypeRegistrar.java`, `resolve/TypeGroups.java` |
| A program the frontend has read, ending in `JULC0084` | `frontend/javac/JavacProgram.java`, every `JulcCompiler` entry point |
| The ledger's types through the metadata-only task, a provider's through its units (D8, I5) | `frontend/javac/JavacLibraryTypes.java`, `backend/LibraryOperations.java`, `JavaLibraryProvider`, `LedgerTypeProvider(CompilerOptions)` |
| Special layouts by FQCN (I11) | `frontend/javac/LedgerLayouts.java`, `TypeRegistry.ledgerHashFqcns()` |
| The stub generator's attribute check (I11) | `tool/StubAttributes.java`, run by `generateJavacStubs` |
| `JULC0092` (`SEALED_PERMITS_REQUIRED`), I11's "asking for an explicit `permits` clause" | `diagnostics.json`, `JavacTypeRegistrar` |

`JavacFacts` stays stateless. Binder names, guard constants and a type-variable environment belong to lowering; the ADR
revision records that.

The JavaParser path changed only mechanically, so the two frontends share code. The FQCN maps moved up to `TypeRegistry`, the
dependency groups and productivity rule to `TypeGroups`, the operations rules to `backend/LibraryOperations`, and `JulcCompiler`
has one `classpathLibraries` helper for five copies of the same lines. That is code the D4 freeze covers; the maintainer accepted
this reading for the split, and the gate-off baseline below is the proof that no byte moved.

### Rules worth stating

- **Identity.** A type is its `TypeElement`'s qualified name. A local type, and a type nested in one, is its binary name
  (`P$1R`), because javac reports a local type's simple name as its qualified name, and that can be anyone's. Built-in types are
  matched by FQCN, and everything else by FQCN in the registry. `JavacFacts`, `JavacProgram` and `JavacTypeRegistrar` are typed on
  `TypeRegistry`, which has no simple-name lookup, so the compiler enforces I12 here, not discipline. `JavacSeamTest` states it.
- **Tags (I11).** A source interface follows its `permits` clause. Without one, the records nested in it in
  `ClassTree.getMembers()` order, counting each whose `implements` clause names a type of the interface's simple name, as the
  JavaParser frontend counts them; if javac's `getPermittedSubclasses()` is not exactly that list, `JULC0092`. A class-file
  interface follows `PermittedSubclasses`.
- **Fixed by FQCN (I11).** `Credential`'s tags are in `LedgerLayouts.FIXED_TAGS`; a stub that disagrees is
  `BrokenJulcBuildException`, not a new encoding. `Value` is `LedgerLayouts.NOT_CONSTRUCTOR_DATA`; it is not inferred from the
  stub's `toPlutusData`. A hash type is its bytes, by `ledgerHashFqcns()`. A source record's layout still comes from the
  `toPlutusData` it declares, as on the source path.
- **Order.** Declarations are registered in source order, dependencies first, through the same `TypeGroups.dependencyGroups`, so
  which references of a recursive group are nominal comes out alike. Library units are in qualified-name order (I4).
- **Rejections.** A type rule keeps the source path's text, with names as javac prints them
  (`Unknown type: Integer (resolved to java.lang.Integer)`), and every rejection has a position, where that path had
  `<source>:0:0`. javac rejects first where it can (I2b).
- **Positions.** A location's fragment is the source as written, each run of white space one space, cut at 100 characters as the
  JavaParser frontend cuts its own. It is the one rule M3's provenance rows point at.
- **API names.** D3's ownership check sees top-level names. A nested class can take an API type's qualified name too
  (`class java { static class math { record BigInteger(...) {} } }`), so the registrar rejects that with `JULC0081`, at any depth.

## Commands

```sh
# The corpus: M0's frozen recordings, plus a recording of the head, whose sources M1 rewrote into Java. The frozen recordings are
# mostly dialect, which javac rejects, so without the head they would say little about accepted sources. The evidence's head
# recording is kept at <M0>/m2-corpus-head (11,881 cases). M0 = /Users/satya/work/bloxbean/julc-068-m0/build.

# Registry equality, encodings and blueprints (Adr068TypesTest, Adr068EncodingsTest). This reproduces the committed files; every
# row is compared with them, so these inputs are the gate's, and a run on other recordings reports its new rows as moved.
./gradlew :julc-difftest:adr068Types -Padr068.corpus=<M0>/julc-corpus -Padr068.corpus.external=<M0>/corpus-external \
    -Padr068.corpus.elci=<M0>/corpus-elci -Padr068.corpus.head=<M0>/m2-corpus-head -Padr068.out=<dir> --offline --no-watch-fs

# -Padr068.freeze=true writes the reference from the JavaParser frontend instead of reading adr/evidence/068/.

# The default path against B1.
./gradlew :julc-difftest:adr068Baseline -Padr068.corpus=<M0>/julc-corpus -Padr068.corpus.external=<M0>/corpus-external \
    -Padr068.corpus.elci=<M0>/corpus-elci -Padr068.external=<M0>/ext-examples -Padr068.out=<dir> --offline
```

## Results

Generated files are in `adr/evidence/068/`.

| Exit criterion (ADR-068 M2) | Result |
|---|---|
| I11's registry equality on the whole corpus | Yes. Of the 11,417 frozen cases, 5 differ, all T33, as #284 and #286 say they should. Case counts overstate what was compared: see the distinct definitions below |
| `LedgerTypeProvider` equal | Yes: 34 types and 79 named definitions, equal, and `types()` in the same order |
| Independent encoding tests: ledger types | Yes: all 74 (67 records and variants, 7 hash types), `Value` included, against the ledger's own `toPlutusData`, frozen |
| Independent encoding tests: corpus types | 894 of 902 sample rows equal the Data of a compiled, evaluated program; 3 differ (T33, #286), and 5 have no reference. See below |
| Blueprint schemas | 4,251 of 4,253 interfaces equal the frozen reference; the other 2 are classified below |
| Stub generator's attribute check | Yes, in the generator: a blob that fails is not written |
| #284 and #286 cases agree | Not as written. The names bind to the user's records in all 7 saved cases; the values cannot be observed before M3. The ADR revision restates this criterion |
| Default path unchanged | Yes: gate off, 11,410 of 11,417 cases are identical to B1; the other 7 are M1b's rewritten Blaster fixtures |

### Registry equality (I11)

For each case the tool reads the JavaParser path as `JulcCompiler` runs it (the ledger, its aliases, the libraries, the root) and
compares records, sealed interfaces, every variant's sealed parents, the `@NewType` set and the parameterized sums, deep, including
registration order. Every case compares the ledger's registrations too. javac reads the case twice: all units as roots, which
compares everything the reference registers; and as D8 has it, the root with the libraries on the source path, which must equal
the reference over exactly the units javac read, in both directions. A provider case also compares `types()` and
`namedDefinitions()`.

The gate is on every row ([`068/types-m2.tsv`](068/types-m2.tsv)): a case that moves to another class fails, and so does one that is
missing or new. `equal-tolerant` rows hold javac's verdict on the case, so a new false rejection moves its row. Beyond that, every
`DIFFERENT`, `reference-rejects` and `javac-rejects-unreadable` row is listed by key, with its reason, in
[`068/m2-accepted-differences.tsv`](068/m2-accepted-differences.tsv), and a stale entry fails too.

| Class | Frozen recordings, 11,420 | With the head, 17,037 | Meaning |
|---|---:|---:|---|
| `equal` | 4,824 | 10,283 | javac accepts; the registries are equal |
| `equal-tolerant` | 6,493 | 6,632 | javac rejects the source, the dialect in method bodies; its declarations, read tolerantly, are equal (limits below). Each row keeps javac's verdict |
| `equal-reference-rejects-lowering` | 5 | 6 | Registries equal. The reference's provider then fails while lowering (`JULC0054`, or a stdlib library without its neighbours); the javac path has no lowering yet, so that rejection moves to M3. Provider `types()` are not compared in these |
| `ledger-source` | 38 | 38 | A ledger file's row, which compares nothing itself. The ledger is compared in every other row, and once through `LedgerTypeProvider` |
| `both-reject` | 4 | 12 | The same message from both: `JULC0091`, the `@NewType` rules, `Unknown type`, a recursive group with no base |
| `both-reject-tolerant` | 3 | 3 | javac rejects first; read tolerantly, the same type rejection |
| `both-reject-javac-first` | 28 | 38 | javac's own rejection, or `JULC0081` before it (I2b): lexical errors, syntax, `duplicate class`, an unresolved name. Anything else here is `DIFFERENT` |
| `reference-rejects` | 15 | 15 | JuLC's reader rejects what javac accepts, so there is no reference to compare with. 8 are escape shapes scheduled for acceptance. 7 are unpaired surrogates, which M0's inventory marks permanent, and ADR-065 keeps that rejection: M3 must reject them in the decoded value. Listed by key |
| `javac-rejects-unreadable` | 4 | 4 | javac rejects and its model cannot be read: 2 sources in `org.julclang.ledger` (`JULC0081`, before javac), and 2 M0 sources whose `permits ProofStep.Branch` names a top-level record, which M1b rewrote. Listed by key |
| `DIFFERENT` | 6 | 6 | Classified in `m2-accepted-differences.tsv`: the five T33 pins, and with the fixtures one more |

3 of the cases are the fixtures in `julc-difftest/src/test/resources/adr068/m2-fixtures/`, shapes no recording held: a String
`@NewType`, tuples, a nested record that is no variant, and a nested user type named like a hash. No case is `DIFFERENT-order`,
`DIFFERENT-d8`, `DIFFERENT-provider`, `DIFFERENT-tolerant`, `DIFFERENT-rejection` or `DIFFERENT-javac-type-rejects`, and no case hits
`JULC0092`. Per part, over the union: every Blaster (7), C7 (6), G0 (376), G1 (123) and Elci (4) case is `equal`, and the external
examples are 100 `equal` and 2 `equal-tolerant`.

**What a case count means.** Most cases declare nothing of their own, so they compare only the ledger. Over the union
([`068/types-m2-totals.tsv`](068/types-m2-totals.tsv)):

| Class | Cases | No user declaration | Records / variants / sums / `@NewType` | Fields | Distinct definitions |
|---|---:|---:|---|---:|---:|
| `equal` | 10,283 | 6,678 | 941 / 6,635 / 3,460 / 34 | 8,687 | 1,077 |
| `equal-tolerant` | 6,632 | 4,674 | 187 / 3,727 / 1,841 / 27 | 3,845 | 264 |

The review counted, at `3f49c97b`, user types in provider `types()` in 9 cases, and 459 D8 comparisons with user declarations out of 3,555. Nothing is compared for
`ledger-source`, `reference-rejects`, `both-reject-javac-first` or `javac-rejects-unreadable`; `both-reject` and `both-reject-tolerant`
compare a message.

The six differences are the source path's defect. It drops a top-level user record whose name a ledger type has (#284, four pins),
and reads a user type named like a hash as bytes (#286, a pin and the `M2Sealed` fixture). javac registers the user's declaration.

### Independent encodings (I11)

**Ledger types** (`Adr068LedgerEncodingTest`, in the ordinary `test` task). A real Java value of every ledger record and variant is
built by reflection, with a distinct value in every leaf, and encoded by the ledger's hand-written `toPlutusData`. The result is
frozen in `julc-difftest/src/test/resources/adr068/ledger-encodings-b1.tsv` (74 rows). The javac registry, read from the stubs, must
give the same Data through `Adr068Encodings.encode`, which takes tags, order and field types from the registry, matches fields by
name, and checks at each position that the sample fits the type the registry gives. `Value` goes the same way: its `inner` type is
the registry's, and that its Data is the map, not a constructor, is the frontend's own description (`LibraryType.operations`). The
JavaParser registry through the same encoder is the encoder's control.

**Corpus types** (`Adr068EncodingsTest`). For every user record, `@NewType` and sealed variant in a source javac accepts, a sample
value is written as Java. With `-Padr068.freeze=true`, the JavaParser frontend compiles a method returning it, into the case's own
class or, for a fixture that does not compile alone, into its declarations alone, and the Java VM evaluates it; the digest of that
Data is frozen in [`068/encodings-b1.tsv`](068/encodings-b1.tsv). Otherwise the javac registry's Data is compared with the frozen
digest. A layout the reference never saw fails, and so does a frozen row not observed, so a false rejection cannot shrink the set.

| Outcome | Rows | |
|---|---:|---|
| Equal | 894 | 223 distinct Data digests over all rows, so many rows repeat one shape |
| Different | 3 | T33, #286: the pin's `record PubKeyHash(byte[] h)`, and `M2Sealed`'s nested `PolicyId` and a record holding it. The reference's Data is the bytes; javac's registry gives `Constr 0 [bytes]`, which is what the JVM's record is |
| No reference | 5 | The reference produces no Data: 4 rows of a `sealed interface U extends S` fixture, which it rejects (`class inheritance is not supported on-chain`), and T33's user `Value`, which fails at run time |
| No sample | 2 | `NativeBox` and `Bad.Box`: a native `JulcValue` or `JulcG1` component, which has no Data encoding; both are rejection fixtures |

33 further definitions, variants included, appear only in sources javac rejects (25 layouts as the rows above count them, with a variant under its sum): such a source never reaches the javac frontend, so these have registry rows and no Data row. `AssetEntry` has no
on-chain constructor on the reference, so no program produces its Data; `JavacTypesTest` checks its layout, written out.

**Blueprints.** The JavaParser frontend's `ContractSchema` is rebuilt with javac's types for every datum, redeemer and `@Param`, and
javac's named definitions, and `SchemaGenerator` renders both. The frozen file is [`068/blueprints-b1.tsv`](068/blueprints-b1.tsv),
1,016 distinct schemas. Two rows differ. One is `M2Sealed`'s datum, T33 as above. The other is a library the root never references
that declares a second `Token`: the reference registers it, so the blueprint names `com.myapp.Token` by its stable id, and javac does
not read that unit (D8), so the name stays `Token`. The schema's shape is the same. The ADR revision gives that its D13 class.

### Differences no corpus case shows

`Adr068ShapesTest`, in the ordinary `test` task, reads 340 declaration shapes through both frontends and compares each with the
frozen `shapes-m2.tsv`. 203 are equal, 100 reject in both, and these differ, each pinned there and in `JavacTypesTest`:

| Source | JavaParser | javac | Class |
|---|---|---|---|
| A user type named like a ledger type or hash, top-level or nested | Binds the ledger's | Binds the user's | T33 |
| `@NewType()`, `@org.julclang.stdlib.annotation.NewType` | A record, `Constr` Data | A `@NewType`, the bare value | F803, F828: javac goes by identity; the reference matches one spelling |
| A user's own `@interface NewType` on one field | A `@NewType` | A record | F803, same |
| `@NewType record N(java.math.BigInteger v)`, `java.lang.String` | Rejected, by comparing the spelling | Accepted | F806: the same type; the ADR revision names it |
| `record R(BigInteger... xs)` | One integer | Rejected, as an array | The reference misreads it; a D13 row at M3 |
| A local record | Keyed by its simple name | Keyed by its binary name | Identity; no corpus case |
| A nested record implementing another type of the interface's name | Takes a tag | `JULC0092` | F800: I11 |
| An implicit-permits interface with a non-record variant | Registers the records | `JULC0092` | I11 |

### Build-time check (I11)

`JavacPlatformTool stubs` now runs `StubAttributes.check`. It compares every stub with its class: `Record` (components, descriptors,
signatures, annotations), `Signature`, `PermittedSubclasses` and `RuntimeVisibleAnnotations`, on the class and on every field and
method kept, across all 253 classes. For the 12 sealed ledger types it compares `PermittedSubclasses` with the order javac's parser
reads from the ledger's sources, without attribution. The blob's bytes did not change (sha256 `3d16a357…`). `StubAttributesTest` shows
each break found on broken stubs, and `aReadOverAMovedCredentialStubFails` shows a read over a moved `Credential` stub is a broken
build.

### #284 and #286

`Adr068NameCasesTest` runs the six `diverge-t33-*` pins and `agree-nested-rational`. For each one: the user's record is registered
under its identity with its own components in source order, `lookupNominal` returns it, every `new` and accessor javac binds is the
user's, none reaches `org.julclang.ledger`, and a compilation ends in `JULC0084`. So the binding is shown, not the value.

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

All at `19b4b555`, counted from the JUnit XML of every module; the commits after it change documentation only.

| Task | Result |
|---|---|
| `./gradlew test --offline --continue --rerun` | 12,920 tests, 0 failures, 0 errors, 530 skipped; `BUILD SUCCESSFUL in 10m 8s`. M1's close had 12,745 |
| `:julc-compiler:pairCaseTest --rerun` | 71 tests, 0 failures |
| `onChainHarnessTest --rerun` | 16 tests, 0 failures |
| `:julc-wasm:wasmSmokeFull --rerun` (GraalVM 25.3.4.1) | `BUILD SUCCESSFUL`; `wasmImageFull` rebuilt; 56 `ok` lines, no `not ok` |
| `adr068Types`, the command above | `BUILD SUCCESSFUL`; 0 rows moved, 5 listed Data and blueprint differences. A freeze and the two checks after it wrote identical tables |
| `adr068Baseline`, gate off | As under Default path |
| `verifyDiagnosticCodes`, `verifyJavacPlatform`, `checkJavacPlatformDerivation` | `BUILD SUCCESSFUL` |

### Cost

Measured once, warm, in a test JVM, on a one-record source, before the review's changes: `JavacFrontend.attribute` about 1 ms, and with
the ledger and the unit's types registered about 2 ms, so about 1 ms per compilation. The JavaParser path's parsing and registering of
the ledger is about 5.4 ms. There is no gate on this at M2.

## Mutation evidence

Each mutation breaks one behaviour in the source, runs the checks and is reverted. Every run recompiles with `--no-watch-fs`, clears the
XML first and reruns: `JavacTypesTest`, `JavacSeamTest`, `JavacStubsTest`, `Adr068LedgerEncodingTest`, `Adr068NameCasesTest` and
`Adr068ShapesTest` (172 tests), then, where marked, both corpus tools. The stub mutations run the generator. 62 mutations, none survives.

"Rows" counts rows of `types-m2.tsv` that moved, and "Data" the encoding and blueprint rows that differ, beyond the 5 classified. In
every corpus run the tools fail, as the outer `BUILD` line shows.

| Mutation | Unit tests failing | Rows moved | Data, blueprints |
|---|---:|---:|---:|
| Implicit-permits tags reversed | 6 | 18 | 17 |
| `permits` tags reversed | 4 | 4,666 | 1,660 |
| Ledger (class-file) tags reversed | 6 | 16,921 | 29 |
| Record fields reversed | 21 | 16,924 | 1,204 |
| `@NewType` registered as a record | 15 | 51 | 50 |
| Type arguments dropped | 18 | 16,927 | 369 |
| Recursive groups not predeclared | 4 | 40 | 46 |
| A hash name by simple name, at any depth (#286's defect) | 11 | 1 | 3 |
| Hash types not bytes | 10 | 16,922 | 47 |
| `Value`'s `inner` an integer (`value-wrong`) | 6, including both `Value` tests | 16,921 | 3 |
| `Value` described as a constructor (`value-not-special`) | 5, including both `Value` tests | 31 | 0 |
| `LedgerLayouts.NOT_CONSTRUCTOR_DATA` empty | 5 | 31 | 1 |
| `TxOut.address` retyped (`addr-wrong`) | 5, including the ledger Data test | 16,921 | 0 |
| The "no finite base" rule removed (`rej-recursive-base`) | 3 | 2 | 0 |
| A type rule's text changed (`rej-msg`) | 2 | 2 | 0 |
| `@NewType` arity rule, unsupported-type rule, `JULC0091` rule removed (three runs) | 1, 3, 3 | 2, 2, 5 | 1, 1, 1 |
| **Reject everything** | 154 | 10,320 | 5,150 |
| **Reject sources containing `JulcList`** | 78 | 7,733 | 2,193 |
| **Reject sources containing `JulcMap`** | 75 | 3,642 | 454 |
| **Reject sources containing `sealed`** | 17 | 3,165 | 1,361 |
| Library units not registered (`no-libs`) | 2 | 27 | 50 |
| A String `@NewType` as a record; as bytes (two runs) | 3, 3 | 1, 1 | 5, 5 |

Unit-only mutations, each with at least one failure:

| Mutations | Unit tests failing |
|---|---:|
| `BigInteger`, `String`, `Boolean`, `PlutusData`, `ConstrData`, `Optional`, `JulcG1`, `JulcG2`, `JulcMlResult` by simple name (nine runs) | 1 each |
| `Boolean`, `MapData`, `AssetEntry`, `JulcG2`, `JulcMlResult`, `JulcScalars`, `JulcG1Points`, `JulcG2Points`, `Tuple3` mapped wrongly (nine runs) | 2 each |
| `Tuple2`, `LONG`, `JulcArray` mapped wrongly (three runs) | 3, 3, 6 |
| The nested-record filter removed; nested records counted by identity (F800's defect) | 1; 2 |
| The `JULC0092` check removed | 6 |
| Local types keyed by the qualified name (F801's defect) | 4 |
| Varargs read on any constructor; open through `@NewType` (F802's two defects) | 2; 2 |
| The API-name check removed | 3 |
| The ledger looked up by name (F804's defect) | 5 |
| `requireFixedTags` not called | 1 |
| Rejections without a position | 15 |
| Fragment not collapsed; not cut | 1; 1 |

Stub generator, four runs: dropping `Record`, `Signature`, runtime-visible annotations or `PermittedSubclasses` fails `generateJavacStubs`,
naming each class.

Three things to read from this. The four simulations are the review's: before this round each passed the corpus tools except on a floor,
and now each fails on every row it moves. The corpus still has blind spots: no case shadows a built-in name, hits `JULC0092`, or has a local
record, so those rest on unit tests and `shapes-m2.tsv`. And one early run had a mutant survive for a bad reason: it left the package lookup in
place, which completes the classes, so the mutation changed nothing. The row above is the faithful one.

## What this does not establish

- **No program compiled through javac.** On the javac frontend every compilation ends in `JULC0084`. Nothing here observes a value, a
  trace or a script from it. That #284 and #286 agree with the JVM is inferred from the binding; M3 observes it.
- **The javac side of the encoding comparison is a test encoder, not lowering.** `Adr068Encodings.encode` takes tags, order and field
  types from the registry under test, and is compared with Data from a real compilation and VM run. So it tests the registry. Whether M3's
  lowering uses the registry correctly is M3's I1.
- **The JavaParser frontend's Data is the reference, not the truth.** Where that frontend is wrong about a type (T33), the comparison shows
  a difference, and the JVM decides; each one is listed. Where both are wrong alike, nothing here sees it, except for ledger types, which
  also meet the ledger's own `toPlutusData`.
- **The tolerant tier reads rejected sources.** Its cases have the dialect's implicit imports added, and javac's model is read despite errors
  in method bodies. No compilation does this; `TolerantJavacTypes` exists for this evidence only, so it is reported apart.
- **Blueprints take the entry points from the reference.** Which methods and parameters form the interface comes from the JavaParser
  frontend's schema; only the types come from javac. Finding entry points on javac is M3.
- **Rejections compare messages, not anchors.** The corpus tool compares a rejection's text. Position parity is I2a's gate, in M3.
- **Elci was not rebuilt or run.** Its four recorded `JavaLibraryProvider` cases compare by types, and `LedgerTypeProvider` by equality;
  `ElciCompiler` calls `new LedgerTypeProvider()` and `new JavaLibraryProvider(...)`, which keep their signatures. No Elci binary ran
  against this branch.
- **The head recording is one development recording.** It is kept at `<M0>/m2-corpus-head`, and the gate reads it. It was made in two passes
  (julc-compiler, julc-stdlib, julc-testkit and julc-examples first, then julc-difftest, in which two `Adr068RejectionSitesTest` tests failed
  because `JULC0092` was not yet catalogued; neither compiles a corpus source). Tests added after it are not in it, so a fresh recording has
  more cases and reports them as moved. That is the gate working: it says the table needs regenerating with `-Padr068.freeze=true`.
- **DevKit end-to-end tests were not run.** Nothing here changes a script on the default frontend.

## Deferred to M3

- The subset validator on javac trees (I2c), and every rejection it owns. `JULC0084` stands in front of them, so a source the subset rejects
  is not accepted: it is not compiled at all.
- The unpaired-surrogate rejection: 7 of the 15 `reference-rejects` rows are permanent, and M3 must reject them in the decoded value.
- D8's per-host pools. `compile(String)` and `compileMethod(String)` already give javac the whole class-path scan as pool; the other hosts'
  adapters come with M3.
- The option on the Gradle plugin, CLI and testkit (D4): at M2 each would only report `JULC0084`.
- M3's first work: the seam between discovery and assembly (F846), and the javac model's lifetime for specialization (F847).
- New known issues are in [`068-followups.md`](068-followups.md).

## Senior review

Codex read M2 after the three reviews and found two identity holes they missed. Neither was executable, as no program
compiles through `JAVAC` yet, but M3 would have inherited both.

| Finding | Problem | Fix | Checks |
|---|---|---|---|
| F860 | `lookupNominal` found a non-record variant's constructor by simple name, so in `permits X.A, Y.A` the class `Y.A` took the record `X.A`'s field | `registerSealed` keeps each variant's constructor by identity. That was the only simple-name path left in `TypeRegistry` | `aNonRecordVariantDoesNotTakeTheShapeOfASameNamedRecord`, in both `permits` orders. Restoring the lookup fails it |
| F861 | The nested ownership check knew only stub names, so `class org { ... class MathLib { abs(...) } }` took a bundled library's qualified name and signature | The check also covers bundled types, and is `JULC0081` | `aNestedTypeCannotTakeABundledLibrarysName`, and `theGenuineBundledLibraryIsNotItsOwnForgery` as the positive control. Removing the check, or applying it to top-level types, fails exactly one of them |
