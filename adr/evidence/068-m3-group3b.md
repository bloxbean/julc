# ADR-068 M3 Group 3b: entry points, `@Param`, contract schema and debug metadata

Branch `wip/068-g3b`, from `17bf4ff1` (M4 frozen). Group 3a, library units and the source pool, is a parallel slice; a
program that calls a library still stops at `a library unit`.

## Delivered

| Item | State before | On `frontend=JAVAC` now | Where |
|---|---|---|---|
| Validator kinds, entry points, datum, redeemer and `ScriptContext` decoding, `@Param` (D14: `JULC0088`, read on the compiled class alone) | Ported with the assembly split (M3a) | Unchanged; the schema and debug metadata below read the same discovery | `JavacDiscovery`, `JavacCompilation`, `ValidatorAssembly` |
| `compileContract` | Compiled, then `JULC0084` `a contract schema` | The reference's schema: purposes, interfaces ordered by tag, datum and redeemer by position, an `Optional` datum bare, `@Param`s, named definitions, and the manual-dispatch rejection after the bytes | `JavacContractSchema` |
| `compileForDebug` | Compiled, then `JULC0084` `debug metadata` | Debug metadata: declarations scanned from javac's trees under the reference's ids, keyed by element; each associated with the binder `Terms` builds for it, at the reference's sites (a method's and a lambda's parameter, a local, a `@Param`) | `JavacDebugScan`, `Terms.declared`, `DebugMetadataCollector.forSources` |
| A ledger type's `fromPlutusData` | `JULC0084` (`on a ledger type`, `on a ledger hash`) | The argument once, checked whole as the JVM's decoder reads it, then decoded as the type holds it: a hash's bytes, a map's pairs, a record's Data | `Calls.fromData`, `Terms.checkedDecode`, `StrictBoundaryGenerator.forLedgerDecoding` |
| Constructor checks of API types | Open | Decided: nothing to lower (below) | `068-followups.md` |
| An entry point's last parameter (F904) | Open | Not done: a maintainer's decision is owed (below) | `068-followups.md` |

### The decoding check

The JVM's ledger decoders (`PlutusDataHelper`) are laxer than the strict boundary of a datum or a redeemer: a constructor's
fields past its declared ones are not read, nor the fields of a `Bool` or of an empty `Optional`. A present `Optional` must
have exactly one. `StrictBoundaryGenerator.forLedgerDecoding` is the strict check with those three relaxations, and only
those; its strict mode, which the reference's boundary uses, builds the same terms as before. So the script accepts what
the JVM decodes, and fails where the JVM throws.

What it accepts it does not rebuild: Data with a field the decoder ignored keeps it, as the reference's identity does, so
`equals`, `toPlutusData` and serialisation see it on-chain and not in Java. The ledger never builds such Data. It is a
follow-up row, on both frontends.

### Debug metadata

The collector is split: its core takes declarations keyed by any identity (`Declarations`), and the JavaParser scan is
unchanged, so the reference's metadata is too (`DebugCompileResultTest`). Four differences from the reference remain, none
in a value: a string source's `uri` is its file name where the reference writes its path under the working directory (which
makes the reference's depend on the host, I4); a library given and not read is named and not scanned (D8); a lambda parameter
decoded from Data, which neither frontend shows, keeps its spelling's type; and a local I13 renames because it shadows a field
is not shown, since an association matches the binder's name.

### Constructor checks of API types

`PubKeyHash` and the other hashes check for null and copy the array; `IntData` checks for null; `MapData` and `ListData`
copy the list. A script has no null and its byte strings are immutable, so none of these has an on-chain counterpart, and
none reads a value's content. Nothing to lower. A length check added to an API type later needs its lowering then.

### An entry point's last parameter (F904)

Both frontends lower the last parameter alike: the wrapper passes the context's Data undecoded. A parameter typed
`BigInteger` fails where it is read as an integer; one typed as a record of Data fields reads the context's fields as that
record. A rule naming `ScriptContext` or `PlutusData` would be a Policy rejection, which needs a decision of the ADR (D13), and
moves the 25 rows of fixtures that write `validate(BigInteger, BigInteger)`. Not done here; the decision is the maintainer's.

## Rows moved, by class

To be filled from I1 (see "Not run").

## Tests

| Test | Module | What it holds |
|---|---|---|
| `JavacContractAndDebugTest` | julc-compiler | Bytes, schema and debug metadata equal to the reference's at all four levels, for a spending validator with an `Optional` datum, `@Param`s, helpers, a lambda and a `switch`; a minting validator with a loop; a purpose-indexed multi-validator; the manual-dispatch rejection's message and position; a renamed local not shown |
| `JavacLedgerFromDataTest` | julc-compiler | 20 inputs at each relaxation and its refusal, against the JVM's decoder called by reflection, at all four levels |
| `Adr068LedgerFromDataPropertyTest` | julc-difftest | jqwik, fixed seeds: random well-formed and broken Data of every ledger type with a `fromPlutusData`, against the JVM on both frontends at all four levels. `JAVAC` agrees on every run; the reference's only disagreement is returning where the JVM throws, counted. A value read back (`toPlutusData`) is the JVM's |
| `diverge-068-ledger-from-data-refused`, `diverge-068-ledger-hash-from-data` | julc-difftest | The two Semantic-fix `.diffcase`s, run on `JAVAC` by `Adr068SavedCasesOnJavacTest` |
| `JavacCallsTest`, `JavacStopsTest`, `JavacTypesTest` | julc-compiler | The retired stops are gone; a hash from Data reads as its bytes; contract and debug compile |

## Mutants

To be filled (see "Not run").

## Not run

To be filled at the report.
