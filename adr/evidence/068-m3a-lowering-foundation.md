# ADR-068 M3a: the lowering's foundation

M3a is the first slice of M3 ([ADR-068](../068-javac-frontend-migration.md)). It is where javac starts to determine compiled
output: a program written in literals, names and static constants now compiles through `frontend=JAVAC`, to the reference's
FLAT bytes. Everything else stops at `JULC0084`, naming the construct and its position, and never falls back (I8).

The plan is [`068-m3-lowering-structure.md`](068-m3-lowering-structure.md). Machine and toolchain as in M0's evidence. Branch
`wip/068-m3a`, based at `a0527fb1`, with M2's close merged in at `baef03a7`.

This file states the code at `79b7e2ba`. The compiler tests, the I1 gate and `adr068Baseline` ran at that commit, which adds only a rule that rejects. `adr068Types`, `pairCaseTest`, `onChainHarnessTest` and the native image ran at `62c55a1f`, and the full suite at `904e9307`. The external examples and the Web Image ran at `d0cee64b`, the DevKit tests at `ee1c6b81`, and `wasmSmokeFull` at `8ff98e81`. `1ee4ce2f` moves script bytes after those, so they run again. Later commits change documentation only.

## What was delivered

| Piece | Where |
|---|---|
| The assembly seam (F846): statics, helpers, library lets, wrapper, `@Param` lambdas and backend take PIR and resolved types only. The JavaParser path calls the moved code, and `compileMethod`'s copy of the backend's passes is gone | `codegen/ValidatorAssembly.java` |
| The model's discipline (F847): one lock per program, a query returns JuLC's own values, a holder may keep a program and query later | `frontend/javac/JavacProgram.java` (its Javadoc states it), `JavacProgramLifetimeTest` |
| The walking skeleton: every `JulcCompiler` entry point reaches one fork, on `context` | `JulcCompiler.onJavac`, `frontend/javac/JavacCompilation.java`, `JavacDiscovery.java` |
| I2c at its first size: an allow-list of tree kinds, with contextual rules, over the whole root unit | `frontend/javac/subset/SubsetScan.java` |
| The lowering: one dispatch, an immutable `Frame`, a `Scope` keyed by element, one builder of source binders, and the families | `frontend/javac/lower/`: `Lowering`, `Frame`, `Scope` (and its `Binder`, which only a `Scope` makes), `Terms`, `Provenance`, `Literals`, `Names`, `Statements` |
| I13's check, on construction-time provenance: a read's element comes from javac and a binder's from the scope, so a wrong lookup fails it | `lower/BindingCheck.java`, `lower/Provenance.java`, run on every compilation under `julc.verifyBinderNamespace` |
| ADR-060's namespace check on this frontend | `JavacCompilation.verifyBinderNamespace` |
| D14: `JULC0088` is emitted. New: `JULC0093`, an unpaired surrogate in a literal; `JULC0096` and `JULC0097` on javac; and the subset's gate rules `JULC0094`, `JULC0095`, `JULC0098`, `JULC0099`, `JULC0100` and `JULC0101`, which run once, on javac's trees ([B2](068-m3a-reference-b2.md)) | `diagnostics.json`, `JavacDiscovery`, `SubsetScan`, `SubsetRules`, `subset/` |
| The I1 tool | `julc-difftest`: `adr068Lowering`, `Adr068Lowering`, `Adr068LoweringTest`, `PirCanonical`, and the tables `068/lowering-i1.tsv`, `068/lowering-i1-totals.tsv`, `068/lowering-reference.tsv`, `068/lowering-accepted-differences.tsv` |
| D10's frontend axis: a verdict is per frontend | `DiffConfig.frontend`, `UplcExecutor`'s cache key, `Adr068LoweringHarnessTest` |
| The three lints | In `JavacLoweringLintTest`, over attributed sources in every package under `lower/`, each with negative controls that must compile and make it fail. `TreeScanner` is banned from `lower/` on purpose: its Javadoc says why |

`JavacFacts` stays stateless (r10): binder names live in `Scope`, provenance and positions in `Terms`.

### Rules worth stating

- **What is lowered.** Integer, long, boolean and string literals; `new byte[]{...}` over integer literals; a name javac bound to a
  parameter, `@Param` or static field with an initializer; `BigInteger.ZERO`, `ONE`, `TWO`, `TEN` and `PlutusData.UNIT`;
  parentheses; a method body that is empty or a single `return`. A local declaration, like `if`, calls and operators, is a later
  slice. The API constants and `byte[]` literals are a reading of "literals, names, static constants" in the ADR's order; I1's
  coverage would be poor without them, and each rests on an element, not a spelling.
- **A folded minus.** javac folds a minus sign and a non-zero decimal literal into one literal: `-5`, `- 5`, `-/*c*/5`,
  `-2147483648`. `-0`, `-0x10`, `-017` and `-(5)` stay `UNARY_MINUS`, which the plan's note did not say. The reference lowers all of
  them as `0 - n`, so the folded ones keep that term. A negative value does not identify them, since `0xFFFFFFFF` is -1, so the
  test is the literal's first character as javac reads it, Unicode escapes included (`\u002d5`).
- **Scope and I13.** `Scope` is an immutable chain from `Element` to `Binder`, so a name cannot resolve by spelling. It is seeded
  with `@Param` and static binders before any body. A parameter shadowing a static takes `K'1`; the reference keeps `K`, and I1b
  compares up to alpha-equivalence, so this is not a difference.
- **Provenance.** A read has an element and a binder has a node and a name, per branch for a `DataMatch`, so a `LetRec`, a `ListMatch` and a
  `PairMatch` keep all their names. `StrictRecordEntrypoint` rebuilds an entry point's lambdas and every binder of its body, keeping
  positions out, as the reference does; it tells `Provenance` what it builds, and so does the assembly of the method binders.
  `BindingCheck` found the loss on its first run. A `Var` with no provenance fails the check unless no source name can look like it.
- **Rejection order.** `JavacCompilation`'s steps are the reference's, numbered as in `JulcCompiler`: the type registration moved
  out of `JavacProgram.analyze` so a missing validator annotation still comes first. The discovery rejections have the reference's
  code, words and position. The gate's rules come first, as they do on the default frontend. `SubsetScan` runs next, in the subset
  validator's place, so `JULC0084` comes before the discovery rejections in a source with both.
- **Annotations and types by identity.** `@Entrypoint`, `@Param` and the validator annotations are matched by `TypeElement`, as
  `@NewType` is (D7). An `Optional` datum is `PirType.OptionalType`, not the spelling `Optional...`, so a user's `OptionalThing` is a
  record and a qualified `java.util.Optional` is optional. A `@Param` rule on `BytesData` is about the API's type.
- **The subset's rules run once, at the gate.** `frontend/javac/SubsetRules.java` runs them on javac's trees after `analyze()` succeeds: from
  `JavacGate.check` for a compilation on the default frontend, and first of all from `JavacCompilation` on `frontend=JAVAC`. So there is one
  rule, with one code, one message and one position, and no reading of a spelling. A rule follows the gate's mode: `enforce`, the default,
  rejects; `report` writes it down, where `julc.javacGate.report` names a directory; `off` turns it off on the default frontend, as `CompilerOptions.setJavacGate` says. `JavacGate.Compiled`
  says what a compilation compiles, and is part of the gate's cache key, since one source reads differently as a validator and as a
  `compileMethod` target. The rules read the units javac read: a root, and a library the program references. A library unit nothing references
  is not read, so the rules say nothing of it. On `frontend=JAVAC` such a unit is not compiled (D8); on the default frontend the pool's
  library units are lowered, read or not. Annotations count by name here, as the default frontend reads them, so a user's own annotation
  of that name decides nothing in its favour.
- **What a field initializer may hold.** A static field of the compiled class is lowered, and so is a static field of a library's class. Nothing
  lowers any other field: a `@Param`, an instance field, a field of another type. Such a field holds only a literal, a `new byte[]{...}` of literals,
  an API constant, a constant variable, or `Boolean.valueOf` of a literal, which is D14's remedy; an initializer block is empty (`JULC0095`). A
  constant is what `getConstantValue()` says, so `1 / 0` is none. A constant reached through a dot is reached on a type, and `Boolean.valueOf` is
  an identifier or a select on a type, because any other expression before the dot would run. That list is closed and is not `SubsetScan`'s: the
  scan's grows as lowering learns a kind, and says nothing of code lowering never reads. D14's order holds: a `@Param` constant variable is
  `JULC0088`, not `JULC0095`.
- **An entry point returns `boolean` or `java.lang.Boolean`** (`JULC0094`), by identity, so a user's type named `Boolean` is another type.
- **A value reaches `boolean` through a cast only from a boolean** (`JULC0098`). The operand of a cast to `boolean` or `Boolean`, through parentheses
  and other casts, is `boolean` or `Boolean`. Data is not: a cast does not decode it. A cast has spellings with no cast tree, the calls lowered to their
  argument: `PlutusData.cast`, by the method's identity and by the call's resolved type, so `Boolean.class`, `boolean.class` and a `Class<Boolean>` variable
  are one case, and `X.fromPlutusData` and `BigInteger.valueOf`, by the spelling the lowering reads. The other calls lowered to their argument or receiver
  cannot yield a boolean: `intValue`, `longValue`, `value`, `hash`, `Builtins.toByteString` and the ledger's `of` have an API type that is not boolean, and a
  user type's method of those names is `JULC0101`. A target holds a boolean if it is one, has one as a bound, or is a `@NewType` of one. An intersection with
  `Boolean` is read too, though the platform's stubs stop one at javac today. What holds a `Boolean` in Data, a list, an Optional, a map, a tuple or a record, reads
  it with a decode, so it fails on anything else, whichever way the program was compiled (`BooleanCarriersTest`). A type variable of a validator's own method does not compile.
- **Names the JavaParser frontend reads by spelling are the gate's** (`JULC0100`, `JULC0101`). The javac frontend binds a name to its element, so it has neither
  rule. `JULC0100` is a rule on a name's use, read from javac's element: a name that frontend reads by its spelling binds, at every use and in every position, to what
  it reads, so no list of declarations stands between a program and the rule. `SpelledTypes.NAMES` holds `resolveClassType`'s cases, the ledger's hash names, `Math`, and the
  `java.lang` names the frontend compares; a test holds those to `TypeResolver`, and to `StdlibRegistry`'s keys, short or full. The stdlib's classes and the ledger's types at any
  depth come from the stubs and the bundled set, and the records, sealed interfaces and libraries of a compilation from javac's parser over every unit offered, so none of those is a list. `SpelledNameLintTest` fails on a
  comparison of a Java name with a capitalized literal in the frontend that is on no list and on no reviewed entry, which gives the reason it cannot diverge. A declaration of such a
  name stays rejected where it stands. A record does not take a `TypeMethodRegistry` type key, and two variants of a sealed interface do not share a simple name: neither has a use site to read.
  A plain class or interface is not registered, so it does not share a name with what is, and that is every class of a library unit, since `LibraryCompiler` compiles them all, annotated or not. What that frontend's resolver cannot reach, an inherited member or a static import, is written with its qualifier.
  A variable does not take the name of a type offered to a compilation: static dispatch reads `name.method()` as a class first. Only a class of a library unit gives a call to read, and the rule is the uniform one, over every class, record and sealed interface offered. That shape's lowering is as D4 has it, with the gate off (`ComparisonLoweringTest`).
  T33 closes at the gate: a class or a record `Value` is a type key like any other, a sealed interface is not, since its rows are `SumType`'s, and no top-level type of the unnamed package takes a name of the ledger, where the implicit import wins. A nested or packaged record of another ledger name stays legal.
  The sweep behind the lists read every name comparison of `pir/`, `resolve/`, `validate/`, `JulcCompiler`, `LibraryTypeDescriptions`, `JavaSourceIntrospector` and `StdlibRegistry`. Over `julc-corpus`, `m2-corpus-head`, `corpus-external`, `corpus-elci`, `ext-examples`, `julc-examples-cmp` and this repository, the rule rejects only fixtures written to test it. `SpelledBindingsTest` crosses every name with every binding and every position, with the same programs under a free name and the real types as controls; 47 mutants of the rule all fail it, and a no-op control passes. A method does not take a name read in place of its body: `SpelledMethods`' Javadoc lists each matcher, and the declaration it stands in for.
  That closes T31's explicit accessors and `equals`, and T33, on the default frontend: their `.diffcase`s are renamed and expect the rule.
  A `@NewType`'s component is read the same way, ahead of its generated accessor, so it does not take a name `TypeMethodRegistry` serves on the type it lowers to,
  unless that row lowers to the receiver, or `toPlutusData`. The rows are read, not listed: `value`, `intValue` and `longValue` lower to the receiver on an integer, `value` on bytes,
  and none on a string or a boolean. The no-argument fallback on bytes serves any other name, and a generated `hashCode` or `toString`, so a `@NewType` of `byte[]` names its component `value`.
  A plain record reads a field by name first, so this does not reach it, but for a receiver that is no name: a component named `equals` or `toPlutusData`.
- **A constructor holds no statement** (`JULC0099`). A value is built from its components alone, so a constructor's body does not run on-chain. With the gate off the default frontend drops such a body. The rule reads any constructor a source declares, compact, canonical or other, on a record or a class, however deep. What javac wrote has no source text and does not count: `super();`, a compact constructor's assignments, a generated constructor. So `R { }` holds nothing. An API type comes in as a class file and is not read: `068-followups.md` states what those constructors do.
- **A `+` of string literals stays a `+`.** `-XDallowStringFolding=false` keeps the tree as written, so `"a" + "b"` is `PLUS`, and
  `x + "c" + "d"` is two nodes. It is a hidden option, so the supported-javac check parses `"a" + "b"` once per compiler and requires
  `PLUS`, otherwise `JULC0089`. On the JVM that costs nothing measurable cold (javac's start moves into it: 225 ms to the first
  analysis against 245 ms at the base, over 8 runs each) and about 2 ms warm, once per compiler.
- **Varargs** are outside the subset on lowered methods; `byte...` has the reference's words.


## Commands

```sh
# M0 = /Users/satya/work/bloxbean/julc-068-m0/build

# The I1 gate. Every row is compared with adr/evidence/068/lowering-i1.tsv, in both directions.
./gradlew :julc-difftest:adr068Lowering -Padr068.corpus=<M0>/julc-corpus -Padr068.corpus.external=<M0>/corpus-external \
    -Padr068.corpus.elci=<M0>/corpus-elci -Padr068.corpus.head=<M0>/m2-corpus-head -Padr068.out=<dir> --offline --no-watch-fs

# -Padr068.freeze=true writes the row tables, and fails if a row lost coverage or a comparison, left the corpus or javac-rejects, or
# pins another digest or provenance in the same class, or if there is no table to protect, unless -Padr068.allowShrink=true. -Padr068.freezeReference=true writes lowering-reference.tsv, at a reference change.

# The default path against B2. It fails unless it writes the frozen files; -Padr068.freeze=true writes them, at a reference change.
./gradlew :julc-difftest:adr068Baseline -Padr068.corpus=<M0>/julc-corpus -Padr068.corpus.external=<M0>/corpus-external \
    -Padr068.corpus.elci=<M0>/corpus-elci -Padr068.external=<M0>/ext-examples -Padr068.out=<dir> --offline
```

## The I1 tool

For each case the tool compiles with the reference (the JavaParser frontend, gate off) and with `frontend=JAVAC`:

- **I1a.** Where both accept: FLAT digest and size at all four levels, with and without a source map; diagnostics; `@Param` infos; and
  each located term's line, column and file at all four levels.
- **I1b.** Every method the reference lowers of the root unit, lowered on its own on javac, so a method is compared even where another
  stops or rejects the program. Library methods are in the denominator only: nothing attempts them yet. Term and type must agree up to
  alpha-equivalence: `PirCanonical` replaces bound names by their depth and spells out every constant, and is exhaustive over
  `PirTerm`, `Constant` and `PlutusData` by the compiler.
- **The reference is held still.** `lowering-reference.tsv` freezes, for every case of this corpus, each level's cell, the diagnostics, a
  digest of the `@Param` infos, of the source map's positions, and of each method's binder, type and canonical term. Every cell of
  every row is checked as the row runs, so a change of the frozen frontend is `REFERENCE-DRIFT`, which fails. The table is a record of
  the reference and a prefilter for its drift, and nothing more: it holds one source-map setting per case, positions at one level, and no
  root-alone cells, so a run still compiles the reference, and nothing may read the table in place of that. A cache of the reference side
  would have to keep all eight cells.
- **A crash or a failed I13 check fails the gate.** On every path into the javac frontend, only a `CompilerException` is a rejection, by type: `Adr068Lowering.onJavac` and `attempts`. Anything else stops the run, so no accepted entry can read it as a row.

M2's lessons are its shape:

- Each case gets a class, and every row must equal the committed table, in both directions. So a case that moves class, gains or loses
  a covered method, changes its first construct, or leaves the corpus fails. What the gate writes must then equal the committed files
  byte for byte, totals included.
- No class passes on what it did not compare. A row holds a digest of its whole detail, however little is shown. `DIFFERENT-javac-accepts`
  holds javac's four cells, and a program with a library must equal the reference's program of the root alone
  (`DIFFERENT-root-alone` otherwise, which no D13 class can explain). `javac-rejects` and `both-reject-javac-first` hold javac's
  message and position.
- Every `DIFFERENT` row must be listed once by key in `lowering-accepted-differences.tsv`, with a D13 class that can explain it. A stale entry fails, and `NEEDS A DECISION` is no class.
- A freeze refuses to lose cases, covered methods or a class's comparisons without `-Padr068.allowShrink=true`, and to write with no table to protect. Classes are ranked by what they compare: `Adr068Lowering.rank`. A rank does not say everything a freeze must refuse, so `Adr068LoweringTest.shrinks` also counts any move out of `javac-rejects` or `DIFFERENT-javac-rejects`, where a rejection went, a move into a listed class from another listed class or from `both-reject`, and a changed digest or provenance on a row whose class stays, since that row now pins something else. A run refuses `adr/evidence/068` as its output directory, where it would compare its tables with themselves.

The corpus is M2's, the frozen recordings and the head recording, plus M3a's fixtures, because the recorded corpus holds few of the
slice's shapes in a program it covers, and the rule fixtures and `.diffcase`s of B2.

| Class | Cases | Meaning |
|---|---:|---|
| `i1a-identical` | 145 | Both accept: FLAT at every level with and without a source map, positions at every level, diagnostics, `@Param` infos, and every method's term and type. 143 programs and 2 empty providers |
| `not-ported` | 9,617 | The reference accepts; `JULC0084`. M3 and M4's worklist |
| `reference-rejects-not-ported` | 496 | The reference rejects; `JULC0084` stands in front of the rule that will reject it |
| `javac-rejects` | 5,359 | The reference accepts; javac rejects: M0's dialect sources, each with a rewrite (M1) |
| `both-reject-javac-first` | 1,288 | Both reject, javac first (I2b) |
| `both-reject` | 49 | Both reject with one code and message, and one position where the reference gave one |
| `ledger-source` | 38 | A ledger file's row, which compares nothing itself |
| `unreplayable` | 17 | A custom lookup or target, as in the ledger |
| `DIFFERENT-javac-accepts` | 19 | Listed below |
| `DIFFERENT-rejection` | 10 | Listed below |
| `DIFFERENT-javac-rejects` | 10 | Listed below |
| `DIFFERENT-bytes` | 4 | Listed below |
| `DIFFERENT-provenance` | 2 | Listed below |
| Total | 17,054 | |

### Coverage

| | Covered | Of | |
|---|---:|---:|---|
| Programs the reference accepts | 145 | 15,137 | 0.96% |
| Their distinct scripts | 85 | | |
| Programs of more than one method | 11 | | |
| Methods the reference lowers, library methods included | 320 | 172,155 | 0.19% |
| Distinct covered methods | 160 | | |

**What a number means.** M5's gate is zero `JULC0084`, and this is where that starts: under 1% of programs. Most covered programs are
`return true` or `return x`.
Of the 125 distinct recorded sources among them, 71 only return `true` or `false`, 42 return a string literal, and 7 return a name; 5
declare a `@Param`, one a static field, one a `byte[]` literal, and one reads an API constant.
Static fields, `@Param` and most literal shapes are in the recorded corpus only a handful of times, so those rest on M3a's fixtures
and tests. `first-construct` in the totals is the worklist: what stops the most methods.

| First construct | Cases |
|---|---:|
| `VARIABLE` | 5,938 |
| `LESS_THAN_EQUAL` | 2,173 |
| `METHOD_INVOCATION` | 1,122 |
| `GREATER_THAN` | 225 |
| `IF` | 215 |
| `SWITCH_EXPRESSION` | 178 |
| `EXPRESSION_STATEMENT` | 40 |
| `the exports of library sources` | 35 |
| `EQUAL_TO` | 33 |
| `CONDITIONAL_AND` | 30 |
| `CONDITIONAL_EXPRESSION` | 20 |

`VARIABLE` is a local declaration. `LESS_THAN_EQUAL` is `a.compareTo(b) <= 0`, nearly all of it one family of ADR-060 tests that compile
one small validator under many names, so that count says little about variety. `the exports of library sources` is a provider's.

### The differences

All listed, by key, in [`068/lowering-accepted-differences.tsv`](068/lowering-accepted-differences.tsv).

| Rows | Class | D13 class | What |
|---:|---|---|---|
| 8 | `DIFFERENT-javac-accepts` | Accepted by decision | The ADR-065 I2c escape shapes M0 scheduled. The reference's reader rejects each; `Adr068LoweringHarnessTest` runs each on the JVM and through JAVAC at every level, and they agree. They are in-code cases, not saved `.diffcase` files, which D13 asks for |
| 4 | `DIFFERENT-javac-accepts` | Accepted by decision | D8's precedence: a root and a pool unit declare one name, so the root's wins and the shadowed unit is not read. The reference says `Duplicate record type`, and M0 marks that row permanent. In `9cf0e1d4` and `b24bd9d4` the two are in one package, so Java refuses the pair in one build and no JVM run of it exists. On all four javac's program equals the reference's of the root alone, at every level, and the gate compares that |
| 4 | `DIFFERENT-javac-accepts` | Accepted by decision (ADR r11, D8) | A rejection of a library unit the root does not reference. javac does not read it, so `frontend=JAVAC` does not compile it: `Library source must not contain validator annotation`, or the role conflict. javac's program equals the reference's of the root alone, at every level |
| 2 | `DIFFERENT-javac-accepts` | Accepted by decision (ADR r11, D13) | A lone surrogate in a comment, which cannot reach a script. M0's inventory marks these permanent; the rule is on literals. The JVM agrees (`Adr068LoweringHarnessTest`) |
| 1 | `DIFFERENT-javac-accepts` | Accepted by decision (ADR r11, D7) | The `@Param` rule on `BytesData` is about the API's type, and a user record of that name is not it |
| 10 | `DIFFERENT-javac-rejects` | Policy rejection (ADR r11) | A pin of a gate rule (B2): 4 `.diffcase`s of `JULC0095`, 4 fixtures of `JULC0094`, `JULC0098` and `JULC0099`, and the 2 `.diffcase`s of `JULC0099` for a compact and a secondary constructor. The reference compiles them with the gate off, and on the default frontend the gate rejects them with the same code, words and position as here (`SubsetRulesTest`) |
| 2 | `DIFFERENT-rejection` | Diagnostic change (ADR r11) | `reject-record-static-field.diffcase` and `reject-record-canonical-constructor.diffcase`: uncoded with the gate off, and `JULC0095` and `JULC0099`, the gate's rules, on javac and at the gate |
| 5 | `DIFFERENT-rejection` | Diagnostic change | A lone surrogate in a literal: uncoded in the reader, now `JULC0093` at the literal |
| 3 | `DIFFERENT-rejection` | Diagnostic change | F809: a type rejection with javac's spelling of a name (`java.lang.String`), and a position the reference lacks. Uncoded both ways; the code comes with the subset port |
| 2 | `DIFFERENT-bytes` | Semantic fix (ADR r11, D7) | A type is what it resolves to. A user record `OptionalThing` is a record, whose Data on the JVM is `Constr 0 [int]`; the reference reads it as optional and takes the field alone. A qualified `java.util.Optional` is optional; the reference needs a datum. Evaluated at every level in `Adr068TypeIdentityTest`, against `ValueMapping`'s Data. No `.diffcase` until records are ported |
| 1 | `DIFFERENT-bytes` | Semantic fix | #286, T33: `M2Sealed` nests a user record `PolicyId`. The reference reads it as a ledger hash, so its datum check expects bytes; javac binds the record. M2's fixture; the saved pin `diverge-t33-packageless-pubkeyhash-record` needs records to run, so this is not yet observed against the JVM |
| 1 | `DIFFERENT-bytes` | Cost change | D8: the reference binds a library it is given and the root does not use, and at `NONE` keeps the unused `let`: 44 against 37 bytes, CPU 1,143,542 against 1,095,542, memory 4,829 against 4,529. The other levels agree, semantic observations are equal, and no budget grows |
| 2 | `DIFFERENT-provenance` | Provenance change | For a source with no file, the reference names the file after its first class or interface, the javac frontend after its primary type |

**Provenance (I7).** Of the 145 identical rows, 86 have equal source maps and 57 differ only in a fragment's text, by M2's rule (the source as written, where the reference prints its own tree); every line and column is equal. 2 are providers, which have no map.

## Results

| Check | Result |
|---|---|
| Byte identity against the reference | 145 rows (143 programs and 2 empty providers), at all four levels, with and without a source map. The 4 `DIFFERENT-bytes` rows are classified above |
| I1b | 320 methods equal the reference's term and type up to alpha-equivalence, 160 of them distinct: 203 in `i1a-identical` rows and 117 in programs stopped elsewhere |
| I13's binding check | Runs on every compilation in tests, the gate included, and on each method lowered on its own, over construction-time provenance: the read's element from javac, the binder's from the scope. No violation at the head, and a failure fails the gate. A wrong lookup fails it (`aWrongLookupFailsTheCheck`, and mutant J19); a `Var` with no provenance fails it unless it is `#`-named or a method binder |
| ADR-060 | `GeneratedBinderNameLintTest`, `BinderNameIndependenceTest` and `julc.verifyBinderNamespace` stay green, the last on this frontend too |
| Evaluation against the JVM | `Adr068LoweringHarnessTest`, 58 tests. 46 run a covered program on the JVM and, at all four levels, through both frontends on the VM: each agrees with the JVM, and value, traces, cost and script size agree between the frontends. None of the 46 is a validator or has a `@Param`, and `PlutusData.UNIT` is not among them; of the corpus's 696 rows with harness inputs, 0 are covered yet. `Adr068TypeIdentityTest` evaluates the two identity fixtures as validators, at all four levels |
| D14 | `@Param` constant variables are `JULC0088`, with tests for each kind, instance fields and multiple declarators; a `@Param` javac cannot fold compiles to the reference's bytes |
| The three lints | In `JavacLoweringLintTest`, each with negative controls that make it fail |
| The external `julc-examples` at `22b4e00f`, default frontend and gate mode, against `ff3cef58` and `d0cee64b` | 446 tests, 0 failures, 68 skipped, on both: the skips are its DevKit tests, which a scratch clone turns off through their own assumption. So the gate's rules read every source it compiles, and none rejects. Of the 271 tests that record an evaluation, each has the same outcomes, errors and traces on both. By FLAT digest, tests evaluated 24 of the 67 external cases the Value methods moved. The other 43 compile and are not evaluated as compiled here |
| The gate's determinism | 2 threads and the default write the same bytes, at `ff3cef58`: the gate's byte comparison passes at both |
| The default path, gate off, against B2 | `adr068Baseline` now fails unless it writes [B2](068-m3a-reference-b2.md)'s files. At `62c55a1f` it passes: every file equal, 11,425 cases |
| M2's gate, `adr068Types` | 0 rows moved. The 8 pins of B2 are added to `types-m2.tsv`, each an `equal` row. The schemas of the 4 rule fixtures, and one layout, are newer than B1's table, so each is compared with the reference this run computes |


### Images

`-XDallowStringFolding=false` and its check are in every host, since the check runs in the supported-javac check. The native image was built at `62c55a1f`, and the Web Image at `d0cee64b`, before the last rules.

| Host | Result |
|---|---|
| Native CLI, `GRAALVM_HOME=<25.0.2> ./gradlew :julc-cli:nativeCompile` | Builds, 108,045,000 bytes. `julc build` compiles a new project through the gate (`AlwaysSucceeds`, 96 bytes), and the rules run in it: `PlutusData.cast` to `Boolean`, a type named `Boolean`, a type parameter of that name and a declared `fromPlutusData` fail the build with `JULC0098`, `JULC0100` and `JULC0101` |
| Web Image, `:julc-wasm:wasmGateProbe` (GraalVM 25.3, javac 25.0.4.1.1) | Passes, and its lines equal the JVM's: `supported=true`, `FeeGate` accepted at 204 bytes, `OwnerGate` at 269, the dialect `JULC0085`. 26,963,453 bytes |

The probe failed at first, at the base too: `NoSuchFieldError: variables`. It read the class path's jars without depending on them, so in a fresh tree `julc-tools`' jar was missing, and with it JavaParser's reflection metadata, with nothing said. `f2e5f472` fixes that. No shipped image was affected: `wasmImageFull` takes the jars as inputs.

### Tests

Counted from JUnit XML.

| Task | Result |
|---|---|
| `./gradlew test --offline --continue --rerun --no-watch-fs` | 13,834 tests, 0 failures, 0 errors, 530 skipped. M2's close had 12,923 |
| `:julc-compiler:pairCaseTest --rerun` | 71 tests, 0 failures |
| `onChainHarnessTest --rerun` | 16 tests, 0 failures |
| `:julc-wasm:wasmSmokeFull --rerun` (GraalVM 25.3.4.1), at `8ff98e81` | `BUILD SUCCESSFUL`; `wasmImageFull` rebuilt; 56 `ok` lines, no `not ok`. It runs the default frontend |
| `adr068Lowering` | `BUILD SUCCESSFUL`; 0 rows moved, 0 shrunk |
| `adr068Types` | `BUILD SUCCESSFUL`; 0 rows moved |
| `adr068Baseline`, gate off | `BUILD SUCCESSFUL`; every frozen file byte-identical to B2's |

M3a's first full run, before this review round, had 4 failures of 13,040, none a flake. One was a regression of this slice: on a runtime with no compiler API, `JavacCompilation` failed to link outside the guard, so the host got `NoClassDefFoundError` and not `JULC0089` (I6). `JavacGateEnvironmentTest` caught it; the guard now wraps the call from `JulcCompiler`. The other three were `Adr068LoweringToolTest`: a wrong expectation, and a tool label that called a source javac rejected "a library method", which had inflated that line of the worklist by thousands.

## Mutation evidence

Each mutation breaks one behaviour in the source, in a throwaway worktree, then runs, with `--no-watch-fs` and cleared XML: the
`frontend.javac` tests with `GeneratedBinderNameLintTest`, the harness test, and the I1 gate. It is reverted after. A gate cell is
`moved rows`.

The tables below ran at `fbc37706`, and D16, D19 and L11 again at `b9de05ad`, each pass after a no-op control through the same script (at the last, unit 0/476, harness 0/58, gate moved 0). None of those 65 survives, but that says only that much: a review's own mutants found what they missed, and each now fails. They are in [The review round](#the-review-round), with what ran again at this head.

The numbering has no D5. None was dropped: the list as first written goes from D4 to D6.

The control is there because an earlier pass misled. It ran at a commit where `JavacGateEnvironmentTest` already failed, so every mutation seemed to fail a unit test. Comparing two passes showed each count off by one, and four mutants had in fact survived: with no provenance recorded I13's check passes everything; the raw-return test stopped at an earlier boundary and never reached the return check; no entry point returned anything but a boolean under a source map; and the scan hid lowering's own guard. Each now has a test (`LoweringAloneTest`, and two others). The controlled pass then found two more, which also have tests: nothing tested the native check at `compileMethod`'s boundary, and the array guard was tested only with a shape the mutant still caught. D16, D19 and L11 show their last run, after those tests.

**Literals**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| L1: folded minus kept as a negative constant, not 0 - n | 11 | 8 | 1 |
| L2: a negative value taken for a folded sign (0xFFFFFFFF) | 7 | 4 | 1 |
| L3: boolean literal inverted | 17 | 3 | 127 |
| L4: string literal altered | 1 | 2 | 7 |
| L5: a negated byte element loses its sign | 1 | 1 | 1 |
| L6: text block accepted | 1 | 0 | 0 |
| L7: a long literal truncated to int | 3 | 3 | 1 |
| L8: first byte element dropped | 3 | 3 | 3 |
| L9: folded negation doubly negative | 11 | 8 | 1 |
| L10: a Unicode-escaped sign or quote not read | 3 | 1 | 0 |
| L11: array literal check relaxed in lowering | 3 | 0 | 0 |

**Names and constants**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| N1: a name found by its simple name | 2 | 0 | 0 |
| N2: no mark on a clash (I13) | 4 | 0 | 0 |
| N3: not the smallest k | 4 | 0 | 0 |
| N4: constant matched by simple name | 1 | 0 | 0 |
| N5: a variable qualifier accepted | 1 | 0 | 0 |
| N6: any spelling of the constant accepted | 2 | 0 | 0 |
| N7: ZERO is one | 1 | 1 | 2 |
| N8: TEN is two | 2 | 3 | 1 |
| N9: UNIT has tag 1 | 1 | 0 | 2 |
| N10: undefined field reported as not ported | 2 | 0 | 0 |
| N11: parentheses not lowered | 6 | 2 | 1 |

**Statements**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| S1: entrypoint return guard off | 11 | 0 | 94 |
| S2: guard on non-boolean return | 3 | 0 | 0 |
| S3: return position lost | 8 | 0 | 33 |
| S4: method position lost | 84 | 0 | 57 |
| S5: native boundary unchecked | 1 | 0 | 0 |
| S6: parameters reversed | 17 | 1 | 114 |
| S7: empty body not unit | 2 | 0 | 23 |
| S8: return; not unit | 1 | 0 | 1 |

**Discovery and coordination**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| D1: D14 removed | 6 | 0 | 0 |
| D2: spending parameter count unchecked | 2 | 0 | 0 |
| D3: datum never bound | 1 | 0 | 5 |
| D4: optional datum ignored | 1 | 0 | 3 |
| D6: role conflict accepted | 1 | 0 | 1 |
| D7: banned @Param type accepted | 1 | 0 | 8 |
| D8: purpose tags swapped | 1 | 0 | 9 |
| D9: entrypoint by name | 16 | 0 | 13 |
| D10: compileMethod target may be an interface | 2 | 0 | 1 |
| D11: a read library ignored | 1 | 0 | 7 |
| D12: instance methods lowered as helpers | 0 | 0 | 1 |
| D13: @Param initializer lowered as a static | 4 | 0 | 0 |
| D14: overload check removed | 2 | 0 | 0 |
| D15: mixed purposes accepted | 1 | 0 | 1 |
| D16: native value at compileMethod boundary | 3 | 0 | 0 |
| D17: provenance lost over the strict rebuild | 1 | 0 | 1 |
| D18: @Param not in scope | 9 | 0 | 20 |
| D19: attempts ignore the guard (tool) | 0 | 0 | 8 |
| D20: method bound under a wrong name | 84 | 56 | 63 |
| D21: validator statics not bound | 3 | 0 | 3 |

**The allow-list**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| X1: the allow-list off | 27 | 0 | 3,590 |
| X2: float accepted where not lowered | 5 | 0 | 1 |
| X3: inheritance accepted | 2 | 0 | 3 |
| X4: this accepted where not lowered | 2 | 0 | 1 |
| X5: lone surrogate accepted | 3 | 0 | 5 |
| X6: any statement in unlowered code | 1 | 0 | 5,900 |
| X7: any array in unlowered code | 4 | 0 | 0 |
| X8: generated constructor scanned | 179 | 56 | 10,257 |
| X9: java.lang.Object by spelling | 2 | 0 | 0 |

**Assembly**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| A1: statics bound in reverse | 2 | 1 | 24 |
| A2: @Param lambdas reversed | 0 | 0 | 1,136 |

**Binding check**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| B1: binding check off | 6 | 0 | 0 |
| B2: no provenance recorded | 1 | 0 | 0 |

**Model lifetime**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| E1: model queries not exclusive | 1 | 0 | 0 |

**Rejection anchors**

| Mutation | Unit tests | Harness | Gate rows moved |
|---|---:|---:|---:|
| F1: JULC0091 anchored at the declaration | 0 | 0 | 13 |

21 of them fail unit tests only: L6, L11, N1, N2, N3, N4, N5, N6, N10, S2, S5, D1, D2, D13, D14, D16, X7, X9, B1, B2, E1. That is the limit of the corpus and fixtures: a rule that rejects (D1, D2, the scan's array rule) has no accepted row to move, I13's check (B1, B2, N2, N3) speaks only when something is wrong, and E1 is about threads. They rest on the test alone. D12, D19, A2, F1 fail the gate alone: A2 is in shared assembly, where comparing the frontends sees nothing and the reference's cells against B1 see it; F1 is a rejection's anchor, and D12 a method only the gate's I1b compares.

### The review round

Every row below ran at `3da56ad3` or later, after a no-op control through the same script (unit 0/547, tests 0/117, gate moved 0, shrunk 0, and again at `8ff98e81`: unit 0/556). A mutant a test already kills skips the gate, which takes 5 to 12 minutes under this machine's load, so its gate cell says -. Unit is the `frontend.javac` tests with the lint, `FrozenPathOrderTest` and `ValidatorAssemblyFrontendTest`. Tool is `Adr068LoweringToolTest`, `PirCanonicalTest`, `Adr068TypeIdentityTest` and the harness.

**What the review's mutants did, then and now.** Each is one source change.

| Mutant | At `f3752147` | Now |
|---|---|---|
| The validators of the 4 D8 rows return false | Gate green | Fails: 4 rows `DIFFERENT-root-alone` |
| `M2Sealed` miscompiled at `PV11_COSTED` only | Gate green | Fails: 1 row moves, on its digest |
| A port of `a + b` with an I13 violation | Gate green, moved 0 | Fails: the gate stops with `I13: 'b', built for a, resolves to a binder of b` |
| Control: the same port, correct | 3 rows moved | 3 rows moved |
| The frozen frontend reads another program for 3 sources with no B1 cell | Gate green | Fails: 3 rows `REFERENCE-DRIFT` |
| No positions but at the default level | Gate moved 0 | Fails: 145 rows moved, `DIFFERENT-provenance` |
| A folded negation loses its position | Survived | Fails: 1 row, and `M3aNames` holds the shape |
| A `BigInteger` constant loses its position | Survived | Fails: 1 row |

**Tables, tampered.** Each fails the gate.

| Change | Where it fails |
|---|---|
| The row table reversed | The byte comparison: moved 0 |
| A totals line tampered | The byte comparison: moved 0 |
| A fake line before a real row, one key twice | The byte comparison: moved 0 |
| A `NONE` cell of a `not-ported` row of the reference table | `REFERENCE-DRIFT`: 1 row |
| A freeze with no table | Refused, and nothing is written |

Garbage in the accepted table's class cell, `NEEDS A DECISION`, a duplicate key, a class that cannot explain its row, and a move to a class that compares less are tool tests, each with its mutant below.

**New mutants.**

| Mutation | Unit | Tool and harness | Gate |
|---|---:|---:|---|
| J9: the one-entry rule off | 1 | 0 | - |
| J10: the one-class rule off | 1 | 0 | - |
| J11: the static rule off | 1 | 0 | - |
| J12: the folding flag dropped: the check must refuse every compilation | 447 | 82 | - |
| J13: the folding check passes anything | 1 | 0 | - |
| J14: an escaped backslash read as an escape | 4 | 0 | - |
| J15: byte varargs read as byte[] | 2 | 0 | - |
| J16: qualified `this` not read | 1 | 0 | - |
| J17: static read from the tree | 1 | 0 | - |
| J18: the read recorded from the binder: the check loses its second source | 1 | 0 | - |
| J19: a wrong lookup: the outermost binder of the spelling | 7 | 1 | - |
| J20: a copy loses its binders | 3 | 1 | - |
| J21: a copy of a match loses its branches | 1 | 0 | - |
| J22: a new read of a parameter has no provenance | 1 | 1 | - |
| J23: a Var with no provenance passes | 1 | 0 | - |
| J24: method binders not told | 1 | 0 | - |
| J25: the lambda chain not told | 2 | 1 | - |
| K1: canonical: Data erased | 0 | 1 | - |
| K2: canonical: a list loses its universe | 0 | 1 | - |
| K3: canonical: a Value erased | 0 | 1 | - |
| K4: canonical: a duplicate name restored in the wrong order | 0 | 2 | - |
| K5: tool: accepted with methods not lowered | 0 | 1 | - |
| K6: tool: a method type not compared | 0 | 1 | - |
| K7: tool: diagnostics not compared | 0 | 1 | - |
| K8: tool: @Param infos not compared | 0 | 1 | - |
| K9: tool: positions not compared | 0 | 2 | - |
| K10: tool: javac cells not pinned | 0 | 1 | - |
| K11: tool: root alone not compared | 0 | 1 | - |
| K12: tool: the row digest off | 0 | 3 | - |
| K13: tool: an I13 failure read as rejected | 0 | 1 | - |
| K14: tool: reference not held | 0 | 3 | - |
| K15: tool: one source-map setting | 0 | 1 | - |
| K16: tool: a move down is no shrink | 0 | 1 | - |
| K17: tool: an entry with no D13 class passes | 0 | 1 | - |
| K18: tool: a class that cannot explain its row | 0 | 1 | - |
| K19: tool: a case listed twice | 0 | 1 | - |
| R7: debug compilation: the source map before the libraries | 1 | 0 | - |
| R8: compileMethod writes the backend stage lines | 1 | 0 | - |

J12 drops the flag, and the check then refuses every compilation (`JULC0089`), which is the point of it.

**The subset's rules at the gate.** These replace the earlier mutants of the two per-frontend rules, whose code is gone. Each ran at `66cb2b1b` in a throwaway worktree, with `--no-watch-fs` and cleared XML, over `SubsetRulesTest`, `SubsetRulesAtEveryEntryTest`, `JavacGateTest`, `JavacGateReviewTest` and `JavacWalkingSkeletonTest`, after a no-op control through the same script (0 of 212). The first pass left G57: nothing read a library's lowered declaration of two types, so `SubsetRulesTest` gained that test, and it then fails. Two checks of `Boolean.valueOf`, on its parameter count and type, could not fail under any source, since a boolean literal only resolves to the one overload, so they were removed.

| Mutation | Tests failing, of 212 or 213 |
|---|---:|
| G1: entry-point rule not run | 33 |
| G2: initializer rule not run on a root | 66 |
| G3: cast rule not run on a root | 29 |
| G4: library units not read | 16 |
| G5: initializer rule not run on a library | 17 |
| G6: cast rule not run on a library | 11 |
| G7: a library unit as a root | 2 |
| G8: Boolean by spelling | 5 |
| G9: annotation by identity | 1 |
| G10: compileMethod read as a validator | 46 |
| G11: JavaLibraryProvider read as a validator | 1 |
| G12: what is compiled not in the key | 1 |
| G13: the gate does not run the rules | 110 |
| G14: a rule ignores the mode | 1 |
| G15: a rule loses its position | 78 |
| G16: the javac frontend skips the rules: validator | 37 |
| G17: the javac frontend skips the rules: method | 43 |
| G18: the scan before the rules | 23 |
| G19: a method read as a validator on javac | 43 |
| G20: no rule for a library on javac | 1 |
| G21: the first type, not class | 1 |
| G22: one validator annotation | 5 |
| G23: another class lowered | 6 |
| G24: a @Param lowered | 16 |
| G25: an instance field lowered | 39 |
| G26: a library record lowered | 2 |
| G27: a library instance field lowered | 13 |
| G28: a library @Param lowered | 1 |
| G29: a constant field is no constant | 4 |
| G30: null allowed | 1 |
| G31: any unary of a literal | 2 |
| G32: a minus of anything | 1 |
| G33: any array | 1 |
| G34: any name | 1 |
| G35: any qualifier | 5 |
| G36: no API constant | 4 |
| G37: no qualified constant | 2 |
| G38: a qualifier with no element | 2 |
| G39: valueOf on any qualifier | 1 |
| G40: any method of Boolean | 1 |
| G41: any valueOf | 4 |
| G42: valueOf of anything | 2 |
| G43: blocks not read | 20 |
| G44: method bodies read as initializer blocks | 126 |
| G45: locals read as fields | 11 |
| G46: lowered fields read as not lowered | 1 |
| G47: only the primitive | 2 |
| G48: stops at the first cast | 5 |
| G49: stops at parentheses | 2 |
| G50: no Data | 3 |
| G51: no boolean | 8 |
| G52: no subtype of Data | 2 |
| G53: casts inside a cast not read | 1 |
| G54: Boolean by spelling, in JavacFacts | 5 |
| G55: the declaration's common type: @Param | 1 |
| G56: the declaration's common type: statics | 2 |
| G57: the declaration's common type: a library | 1 |

**The constructor rule.** Each ran at `7afd2b03` over `SubsetRulesTest` and `SubsetRulesAtEveryEntryTest`, after a no-op control through the same script (0 of 222).

| Mutation | Tests failing, of 222 |
|---|---:|
| H1: the rule not run on a root | 34 |
| H2: the rule not run on a library | 14 |
| H3: what javac wrote counts | 106 |
| H4: any method read as a constructor | 105 |
| H5: only the first statement read | 44 |
| H6: a type inside a method not read | 1 |
| H7: methods read in place of constructors | 106 |
| H8: the constructor not named | 1 |
| H9: the rule at the body, not the constructor | 1 |

**The cast rule, the names, and a freeze.** Each ran at `10523d58` or `e39a9621` over `SubsetRulesTest` and `SubsetRulesAtEveryEntryTest`, or `Adr068LoweringToolTest` for the T rows, after a no-op control through the same script (0 of 388, and 0 of 57). One test failed the control at `10523d58`, so it is taken out of those counts, and I12, M12 and M13, which only it failed, ran again with tests of their own. An intersection with `Boolean` has no mutant, since the platform's stubs stop one at javac. A check that a generated method was exempt could not fail, since such a method has no tree here, so it was removed. `FZ-RULE` is the gate run for T20: with `requireBooleanResults` removed and the 2 `JULC0094` rows deleted from the accepted table, `-Padr068.freeze=true` is refused, moved 2, shrunk 2, and no table is written.

| Mutation | Tests failing |
|---|---:|
| I1: calls lowered to their argument not read | 41 |
| I2: the operand stops at such a call | 2 |
| I3: Data allowed | 24 |
| I4: cast only by spelling | 2 |
| I5: cast only by identity | 1 |
| I6: fromPlutusData not read | 1 |
| I7: valueOf not read | 1 |
| I8: a bound of a type variable not read | 1 |
| I9: a @NewType of a boolean not read | 2 |
| I10: a call inside a call not read | 1 |
| I11: a cast inside a cast not read | 1 |
| I12: any cast method by name | 1 |
| N1: a type name not read | 76 |
| N2: a type parameter not read | 51 |
| N3: Boolean left out | 43 |
| N4: a hash name left out | 1 |
| N5: a nested type not read | 106 |
| M1: fromPlutusData not read | 33 |
| M2: toPlutusData not read | 1 |
| M3: parameters not counted | 1 |
| M4: a sealed interface not read | 1 |
| M5: a @NewType instance method not read | 36 |
| M6: a @NewType of not read | 31 |
| M7: equals on a record not read | 29 |
| M8: an explicit accessor not read | 29 |
| M9: a record Value not read | 2 |
| M11: a type inside a method not read | 4 |
| M12: an API method through a variable read | 1 |
| M13: a call on a type read | 1 |
| M14: a call through a variable not read | 4 |
| M15: a library not read for spellings | 11 |
| M16: the gate has no spelling rules | 134 |
| M17: the method rule not run | 54 |
| M18: the type rule not run | 109 |
| D1: an anonymous class read as lowered | 1 |
| T20: leaving a listed rejection is no shrink | 1 |
| T21: a move among listed classes is no shrink | 1 |
| T22: a move from both-reject is no shrink | 1 |
| T23: a newer row taken as equal | 1 |
| T24: a newer row not compared | 1 |

**A `@NewType`'s component.** Each ran over `SubsetRulesTest` and `SubsetRulesAtEveryEntryTest`, after a no-op control through the same script (0 of 443).

| Mutation | Tests failing, of 443 |
|---|---:|
| P1: components not read | 58 |
| P2: a plain record read as a @NewType | 5 |
| P3: toPlutusData not read | 3 |
| P4: a row that lowers to the receiver rejected | 6 |
| P5: the bytes fallback not read | 32 |
| P6: the registry not read | 45 |
| P7: an integer not read | 43 |
| P8: a string not read | 4 |
| P9: bytes not read | 38 |
| P10: a boolean not read | 1 |
| P11: a generated call on bytes not read | 3 |
| P12: the accessor read as another call | 1 |
| P13: another call on any @NewType | 1 |
| P14: every row without an argument lowers to the receiver | 37 |
| P15: no row is served | 46 |

**The gate's own tool, for a freeze.** Each is a change to the tool, with a no-op control through the same script (0 of 53). These are its negative controls for what a compare or a freeze could miss. T8 and T9 survived the first pass, since the injected program also moved the row's coverage, so the two tests now change only what compiles, and each then fails. K25's arm is asked directly: on a whole row `byteDifference` meets a program one side rejects first, as a cell.

| Mutation | `Adr068LoweringToolTest` failing, of 53 |
|---|---:|
| T1: F980: javac cells at one map setting | 1 |
| T2: F980: the root alone at one map setting | 1 |
| T3: F981: a move out of javac-rejects is no shrink | 1 |
| T4: F982: a changed digest is no shrink | 1 |
| T5: F982: a changed provenance is no shrink | 1 |
| T6: F983: a provider cell by the printer | 1 |
| T7: F983: a specialization by the printer | 1 |
| T8: F984: a diagnostics difference before the cells | 1 |
| T9: F984: a params difference before the cells | 1 |
| T10: F991: a rejection by simple name | 5 |
| T11: F991: only IllegalStateException fails | 2 |
| T12: F991: what was thrown is not kept | 1 |
| T18: F991: an IllegalArgumentException of a program read as a rejection | 1 |
| T19: F991: a provider's crash read as a rejection | 1 |
| T13: F992: the evidence directory taken as output | 1 |
| T14: K21: a rejection position not compared | 1 |
| T15: K22: a method only javac lowered not reported | 1 |
| T16: K23: located term sets not compared | 1 |
| T17: K25: one side with a map not reported | 1 |

The freeze of this round showed the shrink at work: the 19 rows only javac accepts took a new digest when they gained the map cells, and all 19 were in `lowering-i1-shrunk.tsv`, so that freeze needed `-Padr068.allowShrink=true`.

**Key-based Value methods.** After a no-op control (stdlib 0/155, compiler 0/317), the 27 mutants of `lovelaceOf`, `add`, `subtract`, `negate`, `geqMultiAsset`, `leq` and `eq` each fail `ValuesLibByKeyTest` or a test beside it, including a read by position on both paths, a second implementation of `lovelaceOf`, a key returned twice at both levels, presence by amount, and reversed order. The comparisons' ten include a side's keys skipped, each way, an absent key not read as 0, and the second pass the same way as the first.

**The older mutants.** All 71 ran again at `8ff98e81` or later, or at `3da56ad3` for the seven whose anchors moved, and none survives. They did not run again after that: `lower/` and `codegen/` have not changed since `8ff98e81`, and the rules' mutants are above. D12, A2, D19 and F1 fail the gate alone, as they did. Their counts differ a little from the tables above, since tests and fixtures were added; the tables above are kept as what `fbc37706` showed.

### The gate's own failure modes

These ran before the review round. Each simulation is one source change, and every row below fails the gate. What the round added is in [The review round](#the-review-round).

| Simulation | Unit tests | Harness | Rows moved | Rows that lost coverage |
|---|---:|---:|---:|---:|
| Reject everything | 181 | 56 | 10,301 | 143 |
| Reject sources holding `JulcList` | 1 | 0 | 7,518 | 3 |
| Reject sources holding `sealed` | 2 | 0 | 3,148 | 11 |
| Reject sources holding `static final` | 22 | 3 | 1,457 | 2 |
| The frozen reference changes | 17 | 3 | 2,698 | 128 |
| Reject a covered construct with a JuLC-looking error | 20 | 3 | 135 | 126 |

On the tables and inputs:

| Change | Outcome |
|---|---|
| Control: untouched | passes; moved 0, shrunk 0 |
| A stale accepted entry | fails; moved 0, shrunk 0 |
| An accepted entry removed: an unlisted difference | fails; moved 0, shrunk 0 |
| Every accepted entry removed | fails; moved 0, shrunk 0 |
| A frozen row removed | fails; moved 1, shrunk 0 |
| A phantom row: covered and never observed | fails; moved 1, shrunk 1 |
| A frozen row with another class | fails; moved 1, shrunk 0 |
| The head recording left out: the corpus shrinks | fails; moved 5,617, shrunk 5,617 |
| A freeze that loses coverage, the differences unchanged | Compare mode fails on 3 rows; the freeze is refused and the table is untouched; with `-Padr068.allowShrink=true` it is written |

A first attempt at that last row proved nothing: it simulated reject-everything, and the accepted-differences check failed first, so the
shrink check never ran. The row above changes coverage alone, and fails at the shrink check's line.


## What this does not establish

- **A program that computes anything.** A covered program returns a literal, a name or a constant. No operator, call, local or branch has
  been lowered, so byte identity here says nothing about those, and most of what can go wrong (evaluation order, state, exits) is ahead.
- **Most of the corpus.** 99% of programs stop at `JULC0084`, and the recorded corpus holds one covered static field and five `@Param`
  cases. Those rest on M3a's fixtures and tests, which are hand-written and so see only what their author thought of.
- **I2a's inventory.** It is not run on this frontend. `JULC0084` stands in front of `SubsetValidator`'s rules, so a source the subset
  rejects is rejected, usually not with the reference's code. Discovery's rejections are compared in `JavacRejectionsTest` and in the
  gate's `both-reject` rows, whose count shows how few there are yet.
- **Rejection order in general.** The steps are in the reference's order, and `SubsetScan` takes the subset validator's place, but a
  source with two rejections is tested only where a test has one.
- **I13 under pressure.** No continuation is duplicated yet and no element is bound twice, so the check has run on easy terms: the shadowing it sees is a parameter over a field. A `LetRec`, `ListMatch`, `PairMatch` and `DataMatch` are hand-built in `ScopeTest`, since nothing lowers one yet.
- **F847 in use.** Nothing holds a program past a compilation yet, since specialization is not ported. A later thread's query is tested, and
  exclusion is shown by a blocked thread, but no real provider does this.
- **#284 and #286's values.** The pins need records, so they still end in `JULC0084`. `M2Sealed`'s datum and the two identity fixtures are evaluated as validators; a `.diffcase` for each waits for records.
- **D13's evidence for the accepted shapes.** They run on the JVM in `Adr068LoweringHarnessTest`, as in-code cases, not as saved `.diffcase` files. The D8 rows' oracle is the reference's program of the root alone, which the gate compares; two of them cannot have a JVM run at all.
- **Fragments.** Lines and columns are compared at every level; a fragment's text is counted, not compared.
- **Images and hosts.** The native CLI and the Web Image run the gate with the self-check of `-XDallowStringFolding=false`, below. `wasmSmokeFull` runs the default frontend, so the lowering has not run in a native or Web image, and no host exposes the option. Elci was not rebuilt.
- **JDK patch releases.** Two JDK builds were not compared. javac's rejection text is now in the rows' digests, so a release that rewords one moves those rows.
- **DevKit end-to-end tests** ran at `ee1c6b81` on `bloxbean/yaci-cli:0.12.0`, 0 failures counted from JUnit XML: the external `julc-examples` at `22b4e00f`, 446 tests with 11 skipped, and its 54 integration tests on the node with none skipped; `:julc-e2e-tests:test -Pe2e`, 10; `listCaseOnChainTest`, 2; `switchPairCaseOnChainTest`, 2; `nativeConstantsOnChainTest`, 12. By a source scan, which may undercount, 9 integration classes (22 tests) deploy a script that calls `ValuesLib.lovelaceOf`, and none deploys a script that directly calls `add`, `subtract`, `negate`, `eq`, `geq`, `leq` or `geqMultiAsset`, so those rest on VM evaluation alone. A later commit that moves script bytes must run these again; a rule that only rejects does not. `1ee4ce2f` moves bytes, so they run again after it.

## What the next slice inherits

- **Add to `Lowering.expr`** and to `SubsetScan.ALLOWED` together, and allow a kind where lowering reaches it, not everywhere: the scan's Javadoc says how. A field that nothing lowers stays with `UnloweredInitializers`' closed list, whatever lowering learns.
- **A constructor holds no statement.** `JULC0099` runs at the gate, so it stays true as expression statements lower, and `SubsetScan` has nothing to learn for it. A constructor that runs needs a decision and a lowering of its own.
- **`SubsetScan` must end as `SubsetValidator`'s rules with their codes** (I2c). Today it stops some things the reference accepts, such as a
  loop in an instance method, because it fails closed.
- **I13's per-method table** comes with the first duplicated continuation: `Scope.bind` chooses from what is visible, and no element is
  bound twice yet.
- **`requireNoMethodCall`** has no javac side, since calls are `JULC0084`. Port it with calls, at step 11b.
- **Calls bind to their element.** `SpelledMethods` lists what the JavaParser frontend reads by spelling ahead of a method's body. The calls slice binds a call to the element javac resolved, so none of those matchers comes across, and `JULC0098`'s three calls lowered to their argument are its to keep.
- **Statements.** `block` handles an empty body or one `return`, and says so; `generateStatements` is next. Keep the `#_` and `#_if` lets.
- **Parameter names at the boundary.** `ValidatorAssembly.Parameter` has a source name and a binder name, because the strict transform
  builds on the binder. `Provenance` follows it, and a test builds the shape the locals slice will. Records will exercise that path for real.
- **The tables move with every construct.** Regenerate them with `-Padr068.freeze=true` and read the diff: a row may only gain, and `Adr068LoweringTest.shrinks` says what a gain is. `lowering-i1.tsv` is the head's table, not a ledger of rows, so a freeze rewrites it and git keeps the history; ADR r11 should say so beside D13's "rows are never edited". `lowering-reference.tsv` moves only with a reference change.
- New known issues are in [`068-followups.md`](068-followups.md).
