# ADR-068 M3 Group 3b: entry points, `@Param`, contract schema and debug metadata

Branch `wip/068-g3b`, from `17bf4ff1` (M4 frozen), with M4 as pushed (`6990d25e`) merged in after the review. Group 3a,
library units and the source pool, is a parallel slice; a program that calls a library still stops at `a library unit`.

## Delivered

| Item | State before | On `frontend=JAVAC` now | Where |
|---|---|---|---|
| Validator kinds, entry points, datum, redeemer and `ScriptContext` decoding, `@Param` (D14: `JULC0088`, read on the compiled class alone) | Ported with the assembly split (M3a) | Unchanged; the schema and debug metadata below read the same discovery | `JavacDiscovery`, `JavacCompilation`, `ValidatorAssembly` |
| `compileContract` | Compiled, then `JULC0084` `a contract schema` | The reference's schema: purposes, interfaces ordered by tag, datum and redeemer by position, an `Optional` datum bare, `@Param`s, named definitions, and the manual-dispatch rejection after the bytes | `JavacContractSchema` |
| `compileForDebug` | Compiled, then `JULC0084` `debug metadata` | Debug metadata: declarations scanned from javac's trees under the reference's ids, keyed by element; each associated with the binder `Terms` builds for it, at the reference's sites (a method's and a lambda's parameter, a local, a `@Param`) | `JavacDebugScan`, `Terms.declared`, `DebugMetadataCollector.forSources` |
| A ledger type's `fromPlutusData` | `JULC0084` (`on a ledger type`, `on a ledger hash`) | The argument once, checked whole for exactly the type's shape, the strict boundary's check, then decoded as the type holds it. A hash is its decode. One dispatcher per program. A type whose shape holds a map stops: `fromPlutusData on a ledger type that holds a map` | `Calls.fromData`, `Terms.checkedDecode`, `JavacFacts.shapeChecks`, `StrictBoundaryGenerator.shared` |
| Constructor checks of API types | Open | Decided: nothing to lower (below) | `068-followups.md` |
| An entry point's last parameter (F904) | Open | Not done: a maintainer's decision is owed (below) | `068-followups.md` |

### The decoding check

The check is the strict boundary's, the one a datum and a redeemer take: Data passes only with exactly the type's shape.
The JVM's ledger decoders (`PlutusDataHelper`) ignore a constructor's fields past its declared ones and the fields of a
`Bool` or of an empty `Optional`, and build their value without them; such Data fails on-chain. So where the script returns,
the Data is the JVM value's own, and every read, `equals` and serialisation of it is the JVM's; where the JVM throws, the
script fails. On Data with a part the JVM ignores, the JVM returns and the script fails: a fail-closed row, a **Policy
rejection (D13): a ledger type decodes only the exact shape on-chain**, held by `JavacLedgerFromDataTest`,
`Adr068LedgerFromDataPropertyTest` and `Adr068LedgerFromDataMutationTest`. No I1 row shows it, since I1 does not evaluate.

A type whose shape reaches a map stops (`Value`, `TxOut`, `TxInInfo`, `TxInfo`, `ScriptContext`, `Committee`,
`GovernanceAction`, `ProposalProcedure`): a map may repeat a key, and the JVM's map does not encode back to the Data it read
(`encodeJulcMap`, and `Value.toPlutusData`'s ADA-first order), and a `Value` is a record of its map here while its Data is
the map.

A hash's decode is its check, `unBData`, with the JVM's verdict and value. The other types share one dispatcher per
program, bound around the whole program under a name of its own, so a datum's checker, which binds its own, does not
capture it. `StrictBoundaryGenerator`'s strict terms are the ones it built at B4; `shared` is additive.

### Debug metadata

The collector is split: its core takes declarations keyed by any identity (`Declarations`), and the JavaParser scan is
unchanged, so the reference's metadata is too (`DebugCompileResultTest`, and `adr068Baseline` below). A binder I13 marks
`name'k`, a local that shadows a field, is its declaration's (`PirDebugProvenance` matches the mark), so it is shown under its
source name. Two differences from the reference remain, none in a value: a string source's `uri` is its file name, where the reference
writes its path under the working directory, which depends on the host (I4, the coordinator's decision); and a library given
and not read is named as the reference names it, by its first class, and not scanned (D8). A lambda parameter decoded from
Data, which neither frontend shows, keeps its spelling's type. A declarator is read as the reference reads it: its name past
comments and string and character literals, under a C-style array's brackets, before a `,` or `;`; a type is spelled as the
reference prints it, with no space in a type argument list. The name search passes over an annotation whole, its argument
list and element-value pairs included, over a text block's escaped quotes, and a declarator ends before a comment that
precedes its `,`. A range that reaches past a line end of a lone CR is refused with the reference's `ValidationException`; a
lone CR after every range is not. A name written with a Unicode escape is a follow-up row.

**Who reads the `uri`.** The source debugger (`julc-tools` `SourceDebugService`, which the playground and `julc-wasm`'s source
debug use, and `JavaLocalsService` with `DebugRuntimeIndex`) finds a point's source by its id and UTF-16 offsets. The `uri`
is only validated as bounded text and hashed into the source manifest's digest. The Truffle debugger (`julc-vm-truffle`)
reads the source map's file name, which both frontends give as `<Class>.java`, not debug metadata. So a bare file name
resolves where a path did. `JavacDebugMetadataTest` (julc-tools) builds the debugger's runtime index over `JAVAC` metadata.

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

I1 against the M4 table as pushed, into scratch (`-Padr068.out`, no freeze), 17,096 cases, at `2f094945`, the last code. 29
rows moved: the 27 of this slice and 2 new cases. No other row moved, and no byte changed outside the slice.

| Rows | From | To | D13 class |
|---|---|---|---|
| 14 `compileContract` | `not-ported`, `a contract schema` | `i1a-identical` | None needed |
| 1 `compileContract` (manual dispatch) | `reference-rejects-not-ported` | `both-reject`, the reference's message and position | None needed |
| 4 `compileForDebug` | `not-ported`, `debug metadata` | `i1a-identical` | None needed |
| 6 `on a ledger type`, 1 `on a ledger hash` | `not-ported` | `DIFFERENT-bytes` | Semantic fix, with the Policy rejection above in its text |
| 1 (`M3bWrapperReads.hashFromData`) | `not-ported` 2/6 | `DIFFERENT-pir` 2/6 | Semantic fix |
| 2 new: `diverge-068-ledger-from-data-refused`, `diverge-068-ledger-hash-from-data` | none | `REFERENCE-DRIFT`, "no committed row" | The freeze writes their reference rows; each is the same Semantic fix |

`068/g3b-accepted-differences.tsv` holds the 8. Bytes at `NONE`, reference / `JAVAC`: `Credential` 46 / 226 (`06c0423a`,
`1118febc`), 43 / 224 (`718238a9`), `M3bStatics` 1,123 / 1,304, a `PubKeyHash` 47 / 49, the largest probe 43 / 588.

A schema's location text is javac's (a `@Param`'s is its whole declaration, from its annotation, a line above the name
where the annotation stands alone; an entry point's fragment is on one line): a **Diagnostic change (D13)**, printed only in `SchemaGenerator`'s and `BlueprintGenerator`'s exception messages. No
I1 row compares it.

## The check's cost, per ledger type

One decode in a method that returns `true` (`compileMethod`), well-formed input, at `2f094945`. The reference's identity is
15 bytes at `NONE` and 10 at `PV11_COSTED` for every type, 160,100 CPU. "Relaxed" is the review's measure of the earlier
check that this one replaces.

| Type | `NONE` bytes | `PV11_COSTED` bytes | `PV11_COSTED` CPU | Relaxed `NONE` / `PV11_COSTED` |
|---|---|---|---|---|
| `PubKeyHash`, `ScriptHash`, `TxId`, the other hashes | 17 | 15 | 260,242 | 72 / 59 |
| `Credential` | 196 | 141 | 2,446,571 | 176 / 129 |
| `TxOutRef`, `GovernanceActionId` | 178 | 140 | 3,525,127 | 168 / 134 (`TxOutRef`) |
| `Address` | 560 | 391 | 9,966,685 | 497 / 355 |
| `StakingCredential` | 385 | 273 | 5,645,786 | |
| `Interval` | 421 | 292 | 11,951,004 | |
| `TxCert` | 1,460 | 977 | 6,072,291 | |
| `TxOut`, `ScriptContext` | stop | stop | | 1,001 / 725, 5,684 / 4,102 |

Two decodes of `Address` and two of `TxOutRef` in one method add under 40 bytes over one of each
(`decodesShareOneDispatcher`).

**Where the rest of the cost is.** The review's list, read against the code:

| Waste | Where | Done |
|---|---|---|
| `unBData` twice for a hash, inside a dispatcher | `JAVAC`'s path | Removed: a hash is its decode |
| A dispatcher per call site | `JAVAC`'s path | Removed: one per program |
| A dead `tailList` that still runs | The relaxed mode only | Gone with it: in the strict check the tail is read |
| A recursive dispatcher (a Z-combinator) and an `if id == k` chain where no type recurses | `StrictBoundaryGenerator`, shared with a datum's and a redeemer's boundary | Not changed: it moves the reference's bytes. A follow-up row with the sizes above, for the optimization work after M6 |
| A sum's alternatives checked one by one though their fields are alike, and `or(x, false)` | The same | The same |

## The reference did not move

`adr068Baseline`, gate off, at `2f094945`, into scratch: every row of `corpus-b4`, `ledger-b4`, its budget and semantic files
and the rejection inventory is unchanged. It fails only on 2 added rows, this slice's saved cases, which the integrator's
freeze adds.

## Schemas and debug metadata over the corpus

`adr068ContractDebug` (`Adr068ContractDebugCorpusTest`) at `2f094945`: 66 contract cases and 10 debug cases; 30 compilations
compiled on both frontends with equal bytes, and **0 differences** in what they describe. The rest are the I1 picture: 3
rejected by both, the others stopped on `JAVAC`.

## Tests run

Each module whole with `--rerun`, at `2f094945` (julc-compiler's count includes `a868f8b3`'s two tests, written before the run):

| Module | Tests | Failures | Skipped |
|---|---|---|---|
| julc-compiler | 6,549 | 0 | 0 |
| julc-difftest | 2,104 | 0 | 0 |
| julc-tools | 48 | 0 | 0 |
| julc-testkit | 236 | 0 | 0 |
| julc-annotation-processor | 29 | 0 | 0 |
| julc-gradle-plugin | 50 | 0 | 0 |

`Adr068LedgerFromDataMutationTest` takes the review's single-node changes: 6,693 inputs over the 33 ledger types with a
JVM decoder, at all four levels on both frontends. 5,684 are of the 9 types that hold a map, which stop. On the other 1,009,
with `Adr068LedgerFromDataPropertyTest`'s 600 tries: 457 the JVM decodes exactly, and `JAVAC` returns with the JVM's value
read back; 228 the JVM decodes by ignoring a part, and `JAVAC` fails; 924 the JVM refuses, and `JAVAC` fails. The reference
returns where the JVM throws in 3,696 runs. `JavacLedgerFromDataTest` pins the rule on a `TxOutRef` comparison.

## Mutants

Each under a 5-minute limit, the batch under one lock, with a clean control before each batch. Of the code after the review,
19 mutants and 2 controls: 16 killed, 3 survive as equivalent today:

| Mutant | Killed by, or why it survives |
|---|---|
| No check | `aShapeCheckBesideARedeemersCheckIsItsOwn` and 14 more |
| A hash checked through the dispatcher | `aHashsDecodeIsItsCheck` |
| A map-holding type decoded; a map not sought in a record or a sum | `aTypeThatHoldsAMapStops` |
| The dispatcher not bound; every check at id 0 | 20 failures each |
| A declarator's end before `,`; a literal not skipped; the name sought after the whole array type; a type spelled by its text | `declaratorsAreTheReferences` |
| A lone CR accepted; a library named by its index; a marked binder not matched; an annotation's `name = value` as an update | Their own tests |
| No decode after the check | Survives: every type that reaches the check is a record or a sum, whose decode is its Data |
| A map not sought through a named type | Survives: no ledger type reaches a map only through a recursive reference |
| The shared dispatcher under the datum's name | Survives: a datum's checker is bound around its own check, not around the entry point's body, so the names do not meet today. The name keeps it so |

## After the confirmation

Six mutants of the scan's last lines (an annotation not skipped, its arguments not skipped, a text block's escape ignored, a
comment before a `,` kept, a lone CR refused everywhere, never refused) and a control: all six killed by
`JavacContractAndDebugTest`. A `switch` over a ledger decode and a name with a Unicode escape are follow-up rows.

## Not run

- The 2 new cases' reference rows, which only a freeze writes.
