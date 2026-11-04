# ADR-068 M5: cost and size against M0

This is M5's exit criterion "a cost and size report against M0" for [ADR-068](../068-javac-frontend-migration.md). It was
measured on `wip/068-m5c` at `91ab9f47`, where javac is the default frontend, and is brought up to `wip/068-m5d` with the
range-aware narrowing of r18 (section 3b). That change moved 11 I1 rows and no others, so the figures below are `91ab9f47`'s
with those 11 rows' change applied. The report has these parts:

- the I1 corpus, compared case by case with the frozen reference (JavaParser at B4/B5);
- the growth that D13 asks the review to decide on, including follow-up O8;
- r18's narrowing, with `MpfRegistryValidator` before and after;
- the 43 external `julc-examples` validators, compared with M0 and with the `0.1.0-pre20` release;
- Elci and `julc-helloworld`.

Script size is the FLAT size in bytes (`UplcFlatEncoder.encodeProgram(...).length`, the blueprint's `sizeBytes`). The default
level is `PV11_SAFE`. A delta is always javac minus the reference.

## Sources and commands

| Item | Value |
|---|---|
| Head | `91ab9f47` in a detached worktree (`julc-068-m5x`), no tracked change |
| Snapshot | `0.1.0-pre20-adr068m5c-91ab9f47-SNAPSHOT` in `~/.m2` |
| I1 tables | `adr/evidence/068/lowering-i1.tsv`, `lowering-reference.tsv`, `lowering-accepted-differences.tsv` (committed) |
| Full row detail | `lowering-i1-differences.txt` from the M5b review's I1 run (`<M0>/work/m5b-review/i1/`). Its `lowering-i1.tsv` and `lowering-reference.tsv` are byte-identical to the committed tables, so each row's detail is the one the committed digest pins. The committed table cuts the detail at 160 characters. |
| Budgets | `adr068Recheck` (`Adr068BytesRecheckTest`) over the 396 `DIFFERENT-bytes` keys on `m5-corpus-head-3`. It ran with a probe edit, which was not committed. The edit also reports memory, CPU and memory at `PV11_SAFE`, and the fee per run (`<M0>/work/m5x/cost/recheck-memory-fee-probe.patch`). On the 310 keys it shares with M5a's last recheck run (`<M0>/work/m5a-fix/all5`), the probe's budget column (before its additions) and class are identical, row for row. |
| External | `<M0>/work/examples-javac/results/hashes-*.tsv` (built at the M5 integration head `4d403623`). This head's own build is `<M0>/work/m5x/cost/hashes-m5c.tsv`. The pre20 build is `hashes-pre20.tsv`. The M0 sizes are `ledger-b0.tsv` (`external`, `compileContract`, `PV11_SAFE`). |

`<M0>` is `/Users/satya/work/bloxbean/julc-068-m0/build`. Every Gradle command ran through `<M0>/heavy.sh` under a time limit:

```sh
# Budgets with memory and fee (probe patch applied, then reverted)
./gradlew :julc-difftest:adr068Recheck --offline -Padr068.corpus.head=<M0>/m5-corpus-head-3 \
    -Padr068.recheck.keys=<M0>/work/m5x/cost/bytes-keys.tsv -Padr068.out=<M0>/work/m5x/cost/recheck2
# External, pre20: a clone of julc-examples-javac at 22b4e00f (M0's commit, which pins 0.1.0-pre20)
./gradlew clean compileJava --offline
# External, this head: the same clone at d415b1d (the migrated sources), default frontend
./gradlew clean compileJava --offline -I adr/evidence/041-local-examples.init.gradle \
    -Djulc.localVersion=0.1.0-pre20-adr068m5c-91ab9f47-SNAPSHOT
```

The analysis scripts are `sizes.py`, `dist.py` and `ext_m0.py` in `<M0>/work/m5x/cost/`.

## 1. The I1 corpus

There are 24,167 cases. Both frontends accept 14,932 of them:

| Row class | Cases |
|---|---|
| `i1a-identical` | 14,405 |
| `DIFFERENT-bytes` | 385 |
| `DIFFERENT-diagnostics` | 78 |
| `DIFFERENT-provenance` | 52 |
| `DIFFERENT-pir` | 12 |

Only `DIFFERENT-bytes` rows can differ in script or budget. The other classes are byte-identical at every level and map setting
(`Adr068Lowering.classify`). The 11 rows r18 moved are among the `DIFFERENT-pir` rows. Two `DIFFERENT-bytes` rows are `JavaLibraryProvider` pools, which have no program size.

### Size, at `PV11_SAFE` without a source map

| Outcome | Cases |
|---|---|
| Byte-identical (the 14,547 rows that are not `DIFFERENT-bytes`) | 14,547 |
| `DIFFERENT-bytes`, same size | 39 (the hash differs, the size does not; 2 of them are the providers) |
| Smaller on javac | 25 (−118 bytes in all; median −2, min −18) |
| Larger on javac | 321 (+11,021 bytes in all; median +8, p90 +124, max +967) |

- **Aggregate:** 7,813,516 bytes on the reference, +10,903 on javac (+0.14%), over 14,892 sized cases.
- **Growth by bytes:** +1..+3: 59; +4..+9: 183; +10..+29: 35; +30..+99: 6; ≥ +100: 38.
- **Shrink by bytes:** −1..−3: 14; −4..−9: 9; ≤ −10: 2.
- **By percentage:** of the 346 rows that change size, 43 grow by 0–1%, 136 by 1–5%, 62 by 5–10%, 13 by 10–25% and 67 by more
  than 25%. 14 shrink by 1–5% and 11 by more than 5%. The percentages are large because many cases are small test programs
  (median reference size 100–300 bytes).
- **At other settings:** NONE has 318 larger and 50 smaller; BASELINE 321 and 25; PV11_COSTED 287 and 59; PV11_SAFE with a map 318
  and 53.
- **At any setting:** 324 rows grow somewhere, 93 shrink somewhere, and 40 do both.

### CPU and memory (`DIFFERENT-bytes` rows; every other both-accept case is byte-identical, so its budgets are equal)

The probe ran, at `91ab9f47`, 368 of what were then 396 rows on recorded, fixed and random inputs. Of the other 28, 18 are not in
the head recording (external, `.diffcase`, `m2`/`m3a`/`m3b` fixtures), 8 have no input, and 2 were compiled only by hand. 11 of
those 18 are the external MPF casts, which r18 made byte-identical to the reference, so their budgets are the reference's. So the
tables below stand for today's 385 rows, of which 17 did not run.

| D13 class | Budget grows only | Shrinks only | Grows on some runs, shrinks on others | Equal |
|---|---|---|---|---|
| Semantic fix | 259 | 20 | 19 | 1 |
| Cost change | 16 | 24 | 21 | 3 |
| Policy rejection at run time | 5 | 0 | 0 | 0 |

Among the rows that grow, the largest relative CPU growth at `PV11_SAFE` is:

| Largest CPU growth | Rows |
|---|---|
| ≤ 0% | 10 |
| 0–1% | 1 |
| 1–5% | 34 |
| 5–10% | 25 |
| 10–25% | 42 |
| > 25% | 208 |

The mean fee delta per run at `PV11_SAFE` (mainnet prices: 0.0577 lovelace per memory unit, 0.0000721 per CPU step) is:

| D13 class | Rows that ran | Higher fee | Lower fee | Range of the per-row mean |
|---|---|---|---|---|
| Semantic fix | 299 | 269 | 16 | −2,434 to +12,761 lovelace |
| Cost change | 64 | 33 | 13 | −139 to +1,181 lovelace |
| Policy rejection at run time | 5 | 5 | 0 | +25 to +93 lovelace |

### The 10 largest growths and shrinks (`PV11_SAFE`, no map)

| Case | Reference, bytes | javac, bytes | Delta | D13 class | What |
|---|---|---|---|---|---|
| `60b217d4d6` | 10 | 977 | +967 | Semantic fix | `TxCert.fromPlutusData(d)` checks the exact shape on-chain (r15, Group 3b, both halves); the reference's identity read accepts Data the JVM refuses. Mean fee +1,328 lovelace |
| `c97a5e7d1b` | 11 | 972 | +961 | Semantic fix | The same (r15, Group 3b) |
| `c53c19f2df` | 10 | 391 | +381 | Semantic fix | The same, another ledger type |
| `ffb44c5761` | 34 | 415 | +381 | Semantic fix | The same (D6, ledger `fromPlutusData`), `compile` entry |
| `6b73e8b0d9` | 11 | 387 | +376 | Semantic fix | r15, Group 3b |
| `eec6f9fbaa` | 10 | 346 | +336 | Semantic fix | r15, Group 3b, both halves |
| `ada81f8ecf` | 11 | 342 | +331 | Semantic fix | r15, Group 3b, both halves |
| `03bbf847df` | 10 | 292 | +282 | Semantic fix | r15, Group 3b, both halves (CPU +28.2M per run at most) |
| `9235432848` | 11 | 287 | +276 | Semantic fix | r15, Group 3b |
| `6e36cc7bfd` | 10 | 273 | +263 | Semantic fix | r15, Group 3b, both halves |

All 10 largest growths are one family: a ledger type's `fromPlutusData` now validates the shape where the reference was an identity
read. The family has 48 rows, +87 to +967 bytes each. No external validator calls a ledger type's `fromPlutusData`: their bytes
are unchanged (section 3).

| Case | Reference, bytes | javac, bytes | Delta | D13 class | What |
|---|---|---|---|---|---|
| `e4471d8982` | 78 | 60 | −18 | Semantic fix | Spelled bindings (`SpelledBindingsTest#aWholeCompilationThatOtherwiseCompiles`) |
| `1bdbeb1c0e` | 152 | 142 | −10 | Semantic fix | A qualified `java.util.Optional` is an `Optional` (D7, r11) |
| `212f10cf5b` | 15 | 6 | −9 | Semantic fix | A user's annotation named `Param` is not JuLC's `@Param` (D15) |
| `88ffa240f8` | 150 | 141 | −9 | Semantic fix | Spelled bindings (`aCallOfAnAccumulatorsNameThatOtherwiseCompiles`) |
| `6eb078fe1e` | 236 | 228 | −8 | Semantic fix | D15's spelled names |
| `78cd902561` | 50 | 43 | −7 | Semantic fix | Spelled bindings |
| `87d5442823` | 33 | 26 | −7 | Semantic fix | A local of a library's name is the local (T33) |
| `b1ad117b6e` | 35 | 28 | −7 | Semantic fix | `SpelledReadingsTest#theCompilersAnswer` |
| `e6b96fbb88` | 70 | 63 | −7 | Semantic fix | `SpelledReadingsTest#theCompilersAnswer` |
| `3db2a2e3ae` | 54 | 48 | −6 | Cost change | A constant guard reading a local through `new` (CPU −220,049 at most) |

### Growth by family (rows that grow in size at some setting, or in budget)

The size delta is at `PV11_SAFE`. The fee is the per-row mean at `PV11_SAFE`, with the number of rows that ran in brackets.

| D13 class | Family | Rows | Size delta, bytes (min..max, sum) | Mean fee delta, lovelace (min..max) |
|---|---|---|---|---|
| Cost change | A list element is decoded by the calling convention (F1042, Group 3a) | 18 | +5..+5, +90 | +28.8 (18) |
| Cost change | M5a, `Adr068LambdasHarnessTest` (O8) | 8 | +0..+9, +30 | −133.7..+119.3 (8) |
| Cost change | M5a, `JavacStopsTest` (O8) | 5 | +1..+9, +20 | −139.3..+114.9 (5) |
| Cost change | "D13, grows, accepted" (inline or mapped element in a tuple, `for (var i : JulcList.of(x))`) | 3 | +5..+16, +32 | +38.0..+1,180.8 (3) |
| Cost change | M5a, `Adr068LoopRepresentationsTest` (O8) | 2 | +2..+11, +13 | +0.0..+341.4 (2) |
| Cost change | M5a, generated lambda pipeline `78e19ea88b` (O8) | 1 | +8 | 0 (1) |
| Cost change | M5a, `Adr068ReadsHarnessTest` (O8) | 1 | 0 | −66.7 (1) |
| Cost change | "A written hash type in a lambda" (`ee920e1e80`) | 1 | +2 | +41.5 (1) |
| Semantic fix | Generated lambda pipelines (r13, D10) | 100 | +1..+17, +702 | +50.0..+12,761.1 (100) |
| Semantic fix | `Adr068LambdasHarnessTest` | 33 | +2..+17, +203 | −2,434.0..+7,045.3 (33) |
| Semantic fix | Ledger `fromPlutusData` checks the shape (r15 Group 3b; D6) | 57 | +0..+967, +9,134 | +9.3..+1,901.8 (56) |
| Semantic fix | `Adr068LoopRepresentationsTest` | 17 | +2..+14, +149 | +15.1..+3,768.1 (17) |
| Semantic fix | F1042 | 15 | +4..+5, +61 | +38.3..+403.9 (15) |
| Semantic fix | D4, "Closed by M5" | 10 | +2..+30, +110 | +5.1..+3,649.1 (10) |
| Semantic fix | 21 smaller families | 53 | −10..+38 | −117.2..+1,800.6 |
| Policy rejection at run time | M3f decision 1 | 5 | +7..+9, +43 | +24.9..+92.8 (5) |

The full table, one line per family, is `<M0>/work/m5x/cost/families.md`.

## 2. Growth and D13's review decision

**Every growth on a both-accept case has an accepted row.** The I1 gate fails on an unlisted `DIFFERENT` row, and it passes on
this table. D13 (r17) asks a review decision for a growth only in "Cost change" (and "Representation only", which has no
growing row). A growth under "Semantic fix" or "Policy rejection at run time" is the cost of the JVM's answer, and D13 does not
require a cost decision for it. Those two classes account for 286 of the 324 rows that grow in size, and for 283 of the rows that
grow in budget. The growing Semantic fix families are in section 1.

**Growing Cost change rows: 39 (38 grow in size; `840703e182` only in memory, by at most 2 units), all decided.** At `91ab9f47` there
were 50. r18's narrowing took the 11 `(int)` rows off the list (section 3b), and the maintainer's delegation accepted the rest at r18.

| Decision | Rows |
|---|---|
| "D13, grows, accepted", from before this report | 3 |
| O8's M5a rows, accepted at r18 | 17 |
| The 19 rows below, accepted at r18 | 19 |

Each of the 36 reasons accepted at r18 now ends "(D13, grows, accepted r18: values equal; decode-on-demand follow-up)".

The 19 rows, which had no written review decision until r18, are:

- **The 18 "list element is decoded by the calling convention (F1042)" rows:** `1db248cd0c`, `320c82c98b`, `4a329090c0`,
  `5eb317b997`, `820d212c23`, `8a92cf3af4`, `91ffffbe66`, `98edb2006c`, `a0d6413ce5`, `b99944a577`, `b9bfcaee25`, `bf81a28c88`,
  `cccc492d87`, `db5ae50bad`, `e2568e9bcc`, `ee5091f098`, `f91278d35f`, `fa09130a79`. Each is +5 bytes at every level. At
  `PV11_SAFE` each costs up to +211,866 CPU and +1,064 memory per run (mean fee +28.8 lovelace). At `PV11_COSTED`, CPU goes down
  by up to −816,839, because the helper promotes.
- **`ee920e1e80`:** `(PubKeyHash sig) -> ...` decodes by the written type. +2 bytes at `PV11_SAFE`, +96,000 CPU and +600 memory
  per run (+41.5 lovelace).

These rows were accepted at M3 Group 3a and in the follow-ups, but none of their reasons said "accepted" or named a decision on the
growth. They are the same mechanism as O8 (decoding a binder by its Java type, whether or not a read needs it), so r18's decision
covers them by name.

## 3. Follow-up O8: the 17 growing Cost change rows

The size columns are at NONE, BASELINE, PV11_SAFE and PV11_COSTED, then the largest value with a map. CPU and memory are per
run at PV11_SAFE (min..max), with the largest growth as a percentage of the reference. The fee is the mean per run, then the
largest per-run growth against the reference's fee for that run.

| Case | Program | Size, bytes | CPU | Memory | Fee, lovelace | Recommendation |
|---|---|---|---|---|---|---|
| `65dca2625e` | `JulcList.of(a, a).any(x -> { var y = x; while (...) { y = x; } ... })` | +14/+11/+11/+11 (map +14) | +1,022,210 (+6.7%) | +4,640 (+7.4%) | +341.4; max +341.4 of 4,705 | Accept; optimise (decode on demand) |
| `2d3aacc7c2` | `xs.any(c ? x -> true : x -> false)` | +9 at every level | 0..+503,720 (+8.1%) | 0..+2,660 (+9.5%) | +119.3; max +189.8 of 2,061 | Accept; optimise |
| `8a68425b00` | The same program (`JavacStopsTest`) | +8/+9/+9/+9 | 0..+503,720 (+8.1%) | 0..+2,660 (+9.5%) | +114.9; max +189.8 of 2,061 | Accept; optimise |
| `304a120229` | A list parameter passed to a helper that does not read it | +4 at every level | 0..+529,665 (+3.2%) | 0..+2,660 (+3.7%) | +115.5; max +191.7 of 5,377 | Accept; optimise |
| `f10abed784` | `xs.map((var x) -> x)` | +7/+4/+4/+4 (map +7) | 0..+500,215 (+5.1%) | 0..+2,320 (+5.5%) | +113.7; max +169.9 of 3,131 | Accept; optimise (an identity map re-encodes) |
| `d3574b6033` | `xs.find(x -> x.signum() > 5)` that never finds | +9 at every level | +243,262 (+24.3%) | +691 (+13.7%) | +57.4 | Accept (only failing runs: no input reaches a value) |
| `46584a6559` | `for (Long x : JulcList.of(z)) { s = s + 1; }` | +2/+2/+4/+4 | +100,744 (+6.1%) | +532 (+6.3%) | +38.0 | Accept; optimise (the loop variable is unread) |
| `501ea12750` | `boolean J = (true \|\| J)`, a local hiding a static field | +1 at every level | +48,000 (+33%) | +300 (+30%) | +20.8 of 68 | Accept (one identity `let` on a 68-lovelace program) |
| `b16038ebc9` | `PlutusData.cast(...)` to a map through a cast | +2 at every level | +56,623 (+3.0%) | +232 (+2.7%) | +17.5 | Accept |
| `dbd486160d` | `PlutusData.cast(x, JulcMap.class)` in `map` | +4/+2/+2/+2 (map +4) | +52,744 (+1.3%) | +232 (+1.3%) | +17.2 | Accept |
| `dee223e22e` | The same, on `JulcList<PlutusData>` (shared departure from the JVM, `ALIKE`) | +2 at every level | +32,000..+52,744 (+2.4%) | +200..+232 (+2.8%) | +13.9 | Accept |
| `53f9d3f6d9` | `PlutusData.cast(x, JulcMap.class).isEmpty()` in `any` | +2 at NONE and with a map; 0 otherwise | 0 at PV11_SAFE (+48,000 at NONE) | 0 (+300 at NONE) | 0 | Accept |
| `cb9b793967` | `xs.any(x -> x.equals(k))` | +2 at every level | −4,643,825..0 | 0..+1,500 (+6.2%) | **−139.3** | Accept: net cheaper |
| `fe023c9c7b` | The same program (`Adr068LambdasHarnessTest`) | +3/+2/+2/+2 | −4,643,825..0 | 0..+1,500 (+6.2%) | **−133.7** | Accept: net cheaper |
| `840703e182` | `JulcList.of(v).head().equals(v)` on a `String` | 0 | −933,626..−915,953 (−44%) | 0..+2 | **−66.7** | Accept: net cheaper |
| `78e19ea88b` | A generated lambda pipeline | +12/+12/+8/+8 | 0 | 0 | 0 | Accept: size only |
| `421462347c` | A loop over a Data map, read the other way (`ALIKE`) | +2 at every level | 0 | 0 | 0 | Accept: size only |

**Decided at r18 (the maintainer's delegation): accept all 17 as Cost change rows**, as this report recommended. Each one's semantic observations equal the reference's, or depart from the
JVM exactly as the reference does (the two `ALIKE` rows). Three of the 17 are cheaper in fee, and two grow only in size. The
largest growth is +341 lovelace per run (+7%), on a test program.

Accepting them is not a reason to leave the cost. Twelve of the 17 have one cause, the same as the 19 undecided rows of section 2
and the 3 "D13, grows, accepted" rows: a binder is decoded by its Java type when it is bound, not when a read needs it. Examples
are `xs.any(c ? x -> true : x -> false)`, an identity `map`, an unread loop variable, and a list given to a helper that does not
read it. The fix is one representation-aware optimisation, now the follow-up "Decode on demand", marked cost priority:

- an `UnIData`/`UnListData` that only flows back into `IData`/`ListData`, or into nothing, folds away;
- or a binder keeps its Data form until its first typed read.

That one change would remove most of the growth of 34 accepted Cost change rows: the 12 O8 rows, 18 F1042 rows, 3 "accepted"
rows and `ee920e1e80`. It would likely remove part of the 100 lambda-pipeline Semantic fix rows too. Order it before any release
that advertises cost parity.

## 3b. r18: a cast that is shown in range has no wrap

At `91ab9f47`, `(int)` of a `long` always lowered to Java's wrap, three builtins. `MerklePatriciaForestry.nibble` writes
`(int) ByteStringLib.at(...)`, whose value is 0..255, so the wrap never changed it, and it was the only growth in this report that a
deployed external validator paid at run time. `NarrowingElisionPass` now removes a wrap where the operand's value is shown to be in
range (the rules are in D6 and in the class's Javadoc). The invariant is the same value as Java, with the wrap omitted only where it
is provably a no-op.

**`MpfRegistryValidator`, script size (FLAT bytes).** Each cell is at the default frontend, without and with a source map. After r18,
the bytes and the hash equal JavaParser's at all 8 settings.

| Level | JavaParser | javac at `91ab9f47` | javac at r18 |
|---|---|---|---|
| NONE | 4,149; 4,156 | 4,175; 4,182 | 4,149; 4,156 |
| BASELINE | 3,638; 4,156 | 3,664; 4,182 | 3,638; 4,156 |
| PV11_SAFE (default) | 2,975; 3,493 | 3,001; 3,519 | **2,975**; 3,493 |
| PV11_COSTED | 2,975; 3,493 | 3,001; 3,519 | 2,975; 3,493 |

**Budget per call of `MerklePatriciaForestry`'s methods, at PV11_SAFE on the pinned PV11 costs.** The fee uses mainnet prices.

| Call | `91ab9f47`: CPU, memory, fee | r18, and JavaParser: CPU, memory, fee | Saved |
|---|---|---|---|
| `nibble` (one read) | 3,814,709; 12,618; 1,003 | 3,287,952; 11,413; 896 | 526,757 CPU; 1,205 memory; 107 lovelace |
| `doBranch` (one step, two reads) | 12,548,171; 49,812; 3,779 | 11,494,657; 47,402; 3,564 | 1,053,514 CPU; 2,410 memory; 215 lovelace |
| `doFork` (one step, two reads) | 14,767,507; 56,010; 4,297 | 13,713,993; 53,600; 4,081 | 1,053,514 CPU; 2,410 memory; 216 lovelace |
| `doFork` (a six-nibble prefix, seven reads) | 26,080,082; 95,100; 7,368 | 22,392,783; 86,665; 6,615 | 3,687,299 CPU; 8,435 memory; 753 lovelace |

So one read cost about 527k CPU and 1.2k memory. The "1.05M CPU per nibble read" this report and the rows' reasons used to give was
one `doFork`, which reads two. At NONE, `doFork` is back to the 21,294,630 CPU the reference gives (it was 22,348,144).

**I1.** Exactly the 11 external MPF rows moved, all from `DIFFERENT-bytes` to `DIFFERENT-pir`. Every level's bytes, size, diagnostics and
params now equal the reference's, with and without a map, so values and budgets are the reference's. Only `nibble`'s PIR differs,
one digest in all 11 rows, because the tool observes a method before the pass runs. They are "Representation only" in the table, and
against r17's rows they are a Cost change that shrinks. None grew and no value changed. The table froze with `allowShrink`, since D13's
tool counts a move to a lower class as a loss. No reference row moved: the JavaParser frontend never builds the wrap, and a term with
no wrap comes back as the same object.

**Tests.** `NarrowingElisionPassTest` (each rule at its boundaries; every binder form's scope; identity, positions and debug
binders), `JavacCastsTest` (a removed wrap gives the reference's bytes at every level, a kept one does not, each form against
Java's value) and `Adr068NarrowingHarnessTest` (the JVM against both frontends at four levels: the `nibble` shape on generated
bytes, each byte's value, the operand's order, trace and failure, and the wraps that stay).

**The scratch probe.** `<M0>/work/m5d/probe/Mpf.java`, on the sources copied from `julc-examples-javac` to `<M0>/work/m5d/mpf-src`. The
`91ab9f47` column swaps in the `0.1.0-pre20-adr068m5c-91ab9f47-SNAPSHOT` compiler jar from `~/.m2`.

## 4. External examples: the 43 `julc-examples` validators

Sizes are FLAT bytes at `PV11_SAFE` from the annotation processor. The sources are:

- **pre20:** `0.1.0-pre20` (the release) on the M0 sources (`22b4e00f`, unmigrated);
- **M0:** the same sources recorded at M0's snapshot (`cbdcbddb`, JavaParser at B0), from `ledger-b0.tsv`;
- **JavaParser now:** the reference frontend at the M5 integration head, on the unmigrated sources (`hashes-base-jp`). The
  migrated sources give the same bytes on it (`hashes-bi-jp`);
- **javac at r18:** `91ab9f47`'s default frontend on the migrated sources (`d415b1d`), with section 3b's `MpfRegistryValidator`. The plugin passed
  `-Ajulc.frontend=javac`, and `verifyJulcLocalArtifacts` showed the snapshot's plugin and artifacts.

### Headline

| Comparison | Same | Smaller | Larger | Total bytes |
|---|---|---|---|---|
| javac at r18 against JavaParser now (the frontend's own effect) | 43 | 0 | 0 | 70,902 → 70,902 (0) |
| javac at r18 against M0 | 6 | 22 | 15 | 71,627 → 70,902 (−725, −1.01%) |
| javac at r18 against pre20 | 6 | 21 | 16 | 71,455 → 70,902 (−553, −0.77%) |

At `91ab9f47` the first row read 42 equal and 1 larger (+26 bytes): `MpfRegistryValidator`, from the `(int)` cast of section 3b.

All 43 hashes (or `cborHex` digests) at `91ab9f47` equal the M5 integration head's javac build (`hashes-bi-javac`). r18 changes one
of them, `MpfRegistryValidator`, whose bytes now equal JavaParser's and pre20's. The other 42 hold on I1's external part, which moved
only the MPF rows. The annotation-processor build of all 43 was not repeated at r18. The 23 migrated validators keep their M0 bytes on
JavaParser.

The M0 → now and pre20 → now differences come from code both frontends share, not from the javac frontend. That code is the
reference changes B1–B5 (for example B2's key-based `ValuesLib.lovelaceOf`, B4's delayed methods) and the commits between pre20
and B0. They have not been bisected per validator. The largest are:

| Validator | Change against M0 |
|---|---|
| `CfProxyValidator` | −204 |
| `CfAuctionValidator` | −189 |
| `LinkedListValidator` | −173 |
| `CfPaymentSplitterValidator` | +82 |
| `TokenDistributionValidator` | +80 |
| `VestingValidator` | +79 |
| `OutputCheckValidator` | +75 |

### Per validator

| Validator | pre20 | M0 (B0) | JavaParser now | javac at r18 | Against pre20 | Against M0 |
|---|---|---|---|---|---|---|
| AuctionValidator | 669 | 669 | 651 | 651 | −18 (−2.7%) | −18 (−2.7%) |
| BlsAggregateVaultValidator | 334 | 334 | 334 | 334 | 0 | 0 |
| BlsVaultValidator | 288 | 288 | 288 | 288 | 0 | 0 |
| CfAnonymousDataValidator | 1812 | 1816 | 1768 | 1768 | −44 (−2.4%) | −48 (−2.6%) |
| CfAtomicTxValidator | 325 | 325 | 325 | 325 | 0 | 0 |
| CfAuctionValidator | 3255 | 3267 | 3078 | 3078 | −177 (−5.4%) | −189 (−5.8%) |
| CfBetValidator | 2220 | 2220 | 2227 | 2227 | +7 (+0.3%) | +7 (+0.3%) |
| CfCrowdfundValidator | 1924 | 1924 | 1914 | 1914 | −10 (−0.5%) | −10 (−0.5%) |
| CfEscrowValidator | 1626 | 1630 | 1683 | 1683 | +57 (+3.5%) | +53 (+3.3%) |
| CfFactoryValidator | 2454 | 2458 | 2409 | 2409 | −45 (−1.8%) | −49 (−2.0%) |
| CfHtlcValidator | 618 | 618 | 585 | 585 | −33 (−5.3%) | −33 (−5.3%) |
| CfIdentityValidator | 1558 | 1558 | 1630 | 1630 | +72 (+4.6%) | +72 (+4.6%) |
| CfLotteryValidator | 3585 | 3585 | 3449 | 3449 | −136 (−3.8%) | −136 (−3.8%) |
| CfPaymentSplitterValidator | 736 | 736 | 818 | 818 | +82 (+11.1%) | +82 (+11.1%) |
| CfPriceBetValidator | 2043 | 2051 | 1979 | 1979 | −64 (−3.1%) | −72 (−3.5%) |
| CfProductValidator | 1208 | 1212 | 1189 | 1189 | −19 (−1.6%) | −23 (−1.9%) |
| CfProxyValidator | 4362 | 4378 | 4174 | 4174 | −188 (−4.3%) | −204 (−4.7%) |
| CfScriptLogicV1 | 1613 | 1613 | 1507 | 1507 | −106 (−6.6%) | −106 (−6.6%) |
| CfScriptLogicV2 | 1564 | 1564 | 1549 | 1549 | −15 (−1.0%) | −15 (−1.0%) |
| CfSimpleTransferValidator | 257 | 257 | 261 | 261 | +4 (+1.6%) | +4 (+1.6%) |
| CfSimpleWalletValidator | 1250 | 1254 | 1228 | 1228 | −22 (−1.8%) | −26 (−2.1%) |
| CfStorageValidator | 1347 | 1355 | 1306 | 1306 | −41 (−3.0%) | −49 (−3.6%) |
| CfTokenTransferValidator | 1523 | 1527 | 1520 | 1520 | −3 (−0.2%) | −7 (−0.5%) |
| CfVaultValidator | 1431 | 1435 | 1445 | 1445 | +14 (+1.0%) | +10 (+0.7%) |
| CfVestingValidator | 496 | 496 | 486 | 486 | −10 (−2.0%) | −10 (−2.0%) |
| CfWalletFundsValidator | 1278 | 1282 | 1330 | 1330 | +52 (+4.1%) | +48 (+3.7%) |
| Cip68Nft | 2064 | 2064 | 2076 | 2076 | +12 (+0.6%) | +12 (+0.6%) |
| CollateralLoan | 1732 | 1732 | 1678 | 1678 | −54 (−3.1%) | −54 (−3.1%) |
| EscrowValidator | 1136 | 1136 | 1123 | 1123 | −13 (−1.1%) | −13 (−1.1%) |
| GuardedMinting | 123 | 123 | 127 | 127 | +4 (+3.3%) | +4 (+3.3%) |
| LinkedListValidator | 4048 | 4048 | 3875 | 3875 | −173 (−4.3%) | −173 (−4.3%) |
| MpfRegistryValidator | 2975 | 2975 | 2975 | **2975** (3001 at `91ab9f47`) | 0 | 0 |
| MultiSigMinting | 556 | 556 | 556 | 556 | 0 | 0 |
| MultiSigTreasury | 423 | 423 | 423 | 423 | 0 | 0 |
| OneShotMintPolicy | 690 | 690 | 649 | 649 | −41 (−5.9%) | −41 (−5.9%) |
| OutputCheckValidator | 816 | 816 | 891 | 891 | +75 (+9.2%) | +75 (+9.2%) |
| SwapOrder | 1612 | 1612 | 1663 | 1663 | +51 (+3.2%) | +51 (+3.2%) |
| TokenDistributionValidator | 1012 | 1012 | 1092 | 1092 | +80 (+7.9%) | +80 (+7.9%) |
| UVerifyFeePot | 1911 | 1911 | 1938 | 1938 | +27 (+1.4%) | +27 (+1.4%) |
| UVerifyProxy | 3199 | 3243 | 3241 | 3241 | +42 (+1.3%) | −2 (−0.1%) |
| UVerifyV1 | 7671 | 7723 | 7660 | 7660 | −11 (−0.1%) | −63 (−0.8%) |
| VestingValidator | 807 | 807 | 886 | 886 | +79 (+9.8%) | +79 (+9.8%) |
| WhitelistTreasuryValidator | 904 | 904 | 916 | 916 | +12 (+1.3%) | +12 (+1.3%) |

In the I1 corpus, the external part (M0's recording of the unmigrated sources) has:

- 43 `i1a-identical` rows;
- 11 `DIFFERENT-pir` rows, all the MPF `(int)` cast: since r18 their bytes equal the reference's;
- 46 `DIFFERENT-javac-rejects` rows (`byte[].equals`, which the migration rewrites);
- 2 `javac-rejects` rows.

## 5. Elci and `julc-helloworld`

- **Elci:** the release smoke against the local snapshot is identical to M0's: 2, 12 and 6 tests passing, and 42
  (`<M0>/work/m5x/elci-smoke2.log`).
- **`julc-helloworld`:** migrated mechanically (`<M0>/work/m5x/helloworld-migration.diff`). 4 of 4 tests pass, and the script is equal
  on both frontends.

These ran at `91ab9f47`. r18 moved no Elci row in I1, and `julc-helloworld`'s sources write no `(int)`.

## 6. Open items

1. **Follow-up, decode on demand (cost priority):** one optimisation for the binder-decode growth shared by O8, F1042, the "accepted"
   rows and probably part of the lambda-pipeline Semantic fixes. It is the largest cost left for users.
2. **A wrap that stays:** a parameter, arithmetic or a branch keeps it, about 527k CPU and 107 lovelace per cast. Nothing external pays
   it today. See the follow-ups.
3. **The ledger `fromPlutusData` shape check:** +87 to +967 bytes and up to about +28M CPU per call, on 57 Semantic fix rows. It
   is correct by decision (r15), and the release notes say to decode a ledger type once, not inside a loop.
4. **Not measured:** budgets of 17 rows the probe could not run. The M0 → now drift of the external validators on JavaParser has
   not been bisected.
