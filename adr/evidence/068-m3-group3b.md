# ADR-068 M3 Group 3b: entry points, `@Param`, contract schema and debug metadata

Branch `wip/068-g3b`, from `17bf4ff1` (M4 frozen). Group 3a, library units and the source pool, is a parallel slice; a
program that calls a library still stops at `a library unit`.

## Delivered

| Item | State before | On `frontend=JAVAC` now | Where |
|---|---|---|---|
| Validator kinds, entry points, datum, redeemer and `ScriptContext` decoding, `@Param` (D14: `JULC0088`, read on the compiled class alone) | Ported with the assembly split (M3a) | Unchanged; the schema and debug metadata below read the same discovery | `JavacDiscovery`, `JavacCompilation`, `ValidatorAssembly` |
| `compileContract` | Compiled, then `JULC0084` `a contract schema` | The reference's schema: purposes, interfaces ordered by tag, datum and redeemer by position, an `Optional` datum bare, `@Param`s, named definitions, and the manual-dispatch rejection after the bytes | `JavacContractSchema` |
| `compileForDebug` | Compiled, then `JULC0084` `debug metadata` | Debug metadata: declarations scanned from javac's trees under the reference's ids, keyed by element; each associated with the binder `Terms` builds for it, at the reference's sites (a method's and a lambda's parameter, a local, a `@Param`) | `JavacDebugScan`, `Terms.declared`, `DebugMetadataCollector.forSources` |
| A ledger type's `fromPlutusData` | `JULC0084` (`on a ledger type`, `on a ledger hash`) | The argument once, checked whole as the JVM's decoder reads it, then decoded as the type holds it: a hash's bytes, a record's Data. `Value`, whose Data is a map and whose type here is a record of it, stays a stop: `fromPlutusData on a ledger type whose Data is no constructor` | `Calls.fromData`, `Terms.checkedDecode`, `StrictBoundaryGenerator.forLedgerDecoding` |
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

I1 at `7ddd5b3f`, against the M4 table, into scratch (`-Padr068.out`, no freeze): 17,096 cases. 29 rows moved, the 27 of this
slice and 2 new cases. No other row moved, so no byte changed elsewhere: the strict boundary builds what it did.

| Rows | From | To | D13 class |
|---|---|---|---|
| 14 `compileContract` | `not-ported`, `a contract schema` | `i1a-identical` (2 with `fragment` provenance, the rest `same`) | None needed |
| 1 `compileContract` (`68dd2d17`) | `reference-rejects-not-ported` | `both-reject`, the manual-dispatch message at the reference's position | None needed |
| 4 `compileForDebug` | `not-ported`, `debug metadata` | `i1a-identical` | None needed |
| 6 `fromPlutusData on a ledger type`, 1 `on a ledger hash` | `not-ported` | `DIFFERENT-bytes` | Semantic fix |
| 1 (`5468f431`, `M3bWrapperReads.hashFromData`) | `not-ported` 2/6 | `DIFFERENT-pir` 2/6, the method that reads a hash from Data | Semantic fix |
| 2 new: `diverge-068-ledger-from-data-refused`, `diverge-068-ledger-hash-from-data` | none | `REFERENCE-DRIFT`, "no committed row" | The freeze writes their reference rows; each is the same Semantic fix |

The 8 `DIFFERENT-*` rows are classified in `068/g3b-accepted-differences.tsv`. The check costs bytes and budget on the inputs
the JVM accepts too: 43 to 219 bytes at `NONE` for one `Credential`, 1,123 to 1,299 for `M3bStatics`. That is the price of
failing where the JVM throws, and a review decision of D13's Cost change kind, folded into the fix's row.

## Tests

| Test | Module | What it holds |
|---|---|---|
| `JavacContractAndDebugTest` | julc-compiler | Bytes, schema and debug metadata equal to the reference's at all four levels, for a spending validator with an `Optional` datum, `@Param`s, helpers, a lambda and a `switch`; a minting validator with a loop; a purpose-indexed multi-validator; the manual-dispatch rejection's message and position; a renamed local not shown |
| `JavacLedgerFromDataTest` | julc-compiler | 20 inputs at each relaxation and its refusal, against the JVM's decoder called by reflection, at all four levels |
| `Adr068LedgerFromDataPropertyTest` | julc-difftest | jqwik, fixed seeds: random well-formed and broken Data of every ledger type with a `fromPlutusData`, against the JVM on both frontends at all four levels. `JAVAC` agrees on every run; the reference's only disagreement is returning where the JVM throws, counted. A value read back (`toPlutusData`) is the JVM's |
| `diverge-068-ledger-from-data-refused`, `diverge-068-ledger-hash-from-data` | julc-difftest | The two Semantic-fix `.diffcase`s, run on `JAVAC` by `Adr068SavedCasesOnJavacTest` |
| `JavacCallsTest`, `JavacStopsTest`, `JavacTypesTest` | julc-compiler | The retired stops are gone; a hash from Data reads as its bytes; contract and debug compile |

## Tests run

`:julc-compiler:test` on the touched classes, at the last code commit: 409 tests, 0 failures, 0 skipped
(`JavacContractAndDebugTest` 13, `JavacLedgerFromDataTest` 17, `DebugCompileResultTest` 6, `ContractSchemaTest` 13,
`JavacTypesTest` 146, `JavacCallsTest` 64, `JavacStopsTest` 138, `JavacLoweringLintTest` 10, `GeneratedBinderNameLintTest` 1,
`BinderNameIndependenceTest` 1).

## Mutants

Not run. The batch is written (`scratchpad/g3b/mutants.py`, 14 mutants and a control, each under a 5-minute limit, the batch
under one lock): the three relaxations and the exact `Optional`, the check and the decode, the declarator's end and name,
annotation assignments, a renamed binder, the `Optional` datum, the interfaces' order, and the `@Param`'s and a local's
association.

## Not run

- I1 (`adr068Lowering`), so no row is classified and `g3b-accepted-differences.tsv` is not written.
- `Adr068LedgerFromDataPropertyTest` and `Adr068SavedCasesOnJavacTest` (julc-difftest): written, never compiled or run.
- The mutants above.
- The full `julc-compiler`, `julc-difftest` and other module suites, `adr068Baseline` (the frozen frontend's gate-off bytes;
  `DebugMetadataCollector` and `StrictBoundaryGenerator` are shared and changed, though their strict and JavaParser paths
  build what they did), `pairCaseTest`, `onChainHarnessTest`.
- A corpus-wide comparison of schemas and debug metadata between the frontends (the I1 tool compares only the compile result).
