# ADR-068 M3 Group 4a: subset rules and rejections on javac trees

Branch `wip/068-g4a`, from `b71373e3` (Group 3, frozen). M3's last item: "the subset validator on javac trees, with D6's
equality rules and D11's ADR-067 milestones", with the follow-up rows that name Group 4 or M3's close, and F904, the
maintainer's decision on an entry point's last parameter. `DelayedMethodPass` is a parallel slice's (4b) and is untouched.

## Delivered

| Item | Before | Now | Where |
|---|---|---|---|
| F904: an entry point's last parameter | Not enforced; both frontends pass the context's Data undecoded to whatever the parameter is | `JULC0103` unless the last parameter is `ScriptContext` or `PlutusData`, by identity. A rule on javac's trees, so it holds on **both** frontends: at the gate on `JAVAPARSER` (under `enforce`, D15), first of all on `JAVAC`. An entry point with a count its purpose does not take keeps the count's rejection (`JULC0008`), so the rule never overtakes the count. It does come before the reference's other rejections of a source that has both (D13's two errors; see the rows below) | `SubsetRules.requireEntrypointSignatures`, `JavacDiscovery.takesParameterCount` |
| The reference validator's rules (`SubsetValidator`) | Uncoded words, or `JULC0084` (`synchronized`, `this`, `super`, inheritance, arrays, array access, `null` outside a `case`) | Each with a code, the reference's words and its position: `JULC0104` `synchronized`, `JULC0107` `this`/`super`, `JULC0109` inheritance, `JULC0014` an array, `JULC0108` array access, `JULC0017` `null`, `JULC0020` floating point, `JULC0105` method references, `JULC0106` a call through a functional local, `JULC0004` `break` outside a loop | `SubsetScan` |
| Rules in code nothing lowers | `break`, `for`, `do`, `try`, `throw`, floating point; the rest stopped at `JULC0084` at the first unread statement | One walk in source order of every rule the reference's validator has, before that stop: the above, plus `JULC0052`, `JULC0053` and `JULC0059` | `SubsetScan.requireNoRejectedStatement` |
| Statements (M3c's row) | Uncoded: `Unsupported statement`, `Variable must be initialized`, `Unsupported instanceof pattern`, `may not return a value`, `Undefined variable`, `Method must have a body` | `JULC0110`, `JULC0002`, `JULC0077`, `JULC0006`, `JULC0011`, `JULC0001` | `Statements`, `TypeTests`, `Names`, `Records`, `Calls`, `CallTargets`, `LibraryRegistration` |
| Loops (M4's row) | Uncoded: `return` in a for-each, the two `yield`s, a conditional update of a loop-body local, an assignment at a position with no lowering, the loop-body statements, an undeclared assignment, a compound assignment's type, the unary operators | `JULC0111`, `JULC0112`, `JULC0113`, `JULC0114`, `JULC0115`, `JULC0116`, `JULC0117`, `JULC0119` | `LoopRules`, `LoopBody`, `Lowering`, `Operators` |
| Switch arms | Uncoded: a pattern variable reassigned, an arm that updates an enclosing variable | `JULC0120`, `JULC0121` | `ArmUpdates` |
| Operators | Shifts, `&`, `\|`, `^` outside a guard and `~`, unary `+`: `JULC0084` | The reference's `Unsupported operator: X` and `Unsupported unary operator: X`, with the reference's operator names, as `JULC0118` and `JULC0119` | `Operators`, `SubsetScan.WHERE_LOWERED` |
| Calls | Uncoded: a template called from its own class, a generic library method called from Java | `JULC0024`, `JULC0122` | `CallTarget.Rejected` now carries its catalogue entry |
| D11: ADR-067 M3 | `new`, `instanceof` and `case` of a record of two sealed parents stopped at `JULC0084` | `JULC0079` at the record's declaration, on `JAVAC` | `RecordDeclarations` |
| D11: ADR-067 M3b | A record's members other than `toPlutusData` and accessors (`JULC0101`) and a constructor's statements (`JULC0099`) lowered or stopped where read | `JULC0080` at the member, on `JAVAC`: a method, `equals`, `hashCode`, `toString`, a compact or other constructor, a static field, an initializer block | `RecordDeclarations` |
| F946: rejection order in `SubsetScan` | Threw at the first error | Collects every rule's error and goes on past the subtree that broke it, in lowered code and in code nothing lowers (every node of a block, once each); the compilation fails with all of them, and the warnings found before, in the order found. A block's statements are read in source order, so a statement not ported stops the scan where it stands, after what came before it (I8); where an error came first, the errors are the rejection, with no `JULC0084` | `SubsetScan.check`, `SubsetScan.scan` |
| `JULC0087`'s words | "compares an array by identity" for every rejection, a raw receiver and an unread operand included | The template takes a reason: "compares an array by identity" where a path reaches an array; "cannot be shown to compare by value" for a raw collection, a type with no rule, a type variable and an operand of no readable type | `CompilerTypeDiagnostics.equalityUnread`, `EqualityRules` |

## What I2a means by the first error (F946)

A source with one error gives the same code, words and position on both frontends, or javac's diagnostic first (I2b), or a
change this ADR names. A source with several is D13's "two errors in one compilation": each frontend collects the subset's
errors, in its own order (the reference's visitor reads a class's members before its annotations, and a method's body before
its parameters; javac's scan reads source order), and with the first removed both must give the next alike. That is the
decision r12 recorded; this slice makes the javac side collect, as the reference does, so a user sees every subset error of a
unit at once.

## Codes

New: `JULC0103`–`JULC0122`. Activated (planned before, emitted now, on `JAVAC` only): `JULC0001`, `JULC0002`, `JULC0004`,
`JULC0006`, `JULC0011`, `JULC0014`, `JULC0020`, `JULC0024`, `JULC0077`, `JULC0079`, `JULC0080`. Every template is the
reference's words, so the catalogue's message is what the reference prints, now with a code and a position; a hint the reference
printed in its text is the catalogue's fix. Four planned templates were re-worded to the reference's words before they were
emitted (`JULC0004`, `JULC0006`, `JULC0077`; `JULC0001`, `JULC0002`, `JULC0011`, `JULC0014`, `JULC0020` and `JULC0024` already
matched). No reference rejection's words change: the reference does not read these entries.

`rejection-sites.tsv` lists each new code, its reference site (`U-*`), and that it is emitted on `frontend=JAVAC`. `JULC0103` is
the gate's.

`LoweringFixture.assertSameRejection` holds every test that compares the frontends to I2a's rule: where the reference's error
has no code and javac's has one, javac's must have the reference's words, without the position and hint the reference wrote
into its text, and the reference's position, from its diagnostic or from that text. Any other difference fails as before.

## F904: an entry point's last parameter

**The rule.** An `@Entrypoint` method of a validator takes `ScriptContext` or `PlutusData` as its last parameter, by the
type's identity (`org.julclang.ledger.ScriptContext`, `org.julclang.core.PlutusData`; a user's own `ScriptContext`, and a
subtype of `PlutusData` such as `ConstrData`, are other types). Anything else is `JULC0103`, at the parameter:

> Entrypoint validate of V takes java.math.BigInteger as its last parameter; the last parameter is the script context,
> ScriptContext or PlutusData

with the fix "Declare the last parameter as ScriptContext, or as PlutusData to keep the context as Data. Read other values from
the redeemer or the datum."

**Why.** Both frontends lower the last parameter alike: the wrapper passes the script context's Data undecoded. A parameter
typed `BigInteger` fails where it is read as an integer; one typed as a record of Data fields reads the context's fields as
that record. The JVM test of such a validator is not a test of the script.

**Where.** `SubsetRules`, next to `JULC0094`, so the rule is one rule with one code, message and position on both frontends
(D15): on the default frontend from the gate, which follows the gate's mode, and first of all on `frontend=JAVAC`. The
reference with the gate off is unchanged (`SubsetRulesTest.theReferenceWithTheGateOffIsUnchanged`), so I1's reference rows do
not move; the rule is a Policy rejection, not a reference change.

**Order.** The parameter-count rule (`JULC0008`) is the lowering's on both frontends and comes later than the gate. So the rule
reads only an entry point with a count its purpose takes: two, or three for a spending entry point (a `@SpendingValidator`, or
a `@MultiValidator`'s `Purpose.SPEND`). `SubsetRulesTest.aWrongCountIsTheCountsRejection` holds five shapes to the count's
rejection on both frontends.

**Fixtures.** 170 in-repo entry points with two or three parameters declared their last parameter `BigInteger`, `int`,
`long` or `boolean` (commit `0f65db9c`). Where the body does not read it (150, by script), it is now `PlutusData`, with its
import. Where the body read it as a second value (20: `HelperMethodTest`, `JulcCompilerTest`, `CrossMethodTypeInferenceTest`,
`LoopDesugarTest`, `GoldenUplcTest` and `JulcAnnotationProcessorTest`), the value moves to the datum position of the spending
entry point, `(BigInteger datum, BigInteger redeemer, PlutusData ctx)`, and the body reads it there; the golden
`simple-validator` is regenerated for that one source. Two guides (`testing-guide.md`, `library-developer-guide.md`) follow. The 18 negative originals
(`adr068/negative-originals`) are never rewritten (D13): javac rejects each first, so the rule does not reach them. The frozen
external examples have none.

**Proposed ADR text (for the maintainer to apply).**

- **The decision, r16 (maintainer, 2026-11-03).** "An entry point's last parameter is `ScriptContext` or `PlutusData`
  (`JULC0103`). Both frontends lower the last parameter alike, with no decode, so a parameter of another type read the
  context as that type: a defect of code both frontends use. By this decision it is a subset rule on javac's trees
  (`SubsetRules`, D15), a Policy rejection on both frontends, and not exception 2's reference change: it stands outside D4's
  clause on the JavaParser frontend's misreadings (r11) by this decision, and the reference with the gate off keeps its
  reading, so I1 records the rows as Policy rejections and the reference is not re-frozen. Under the gate's `report` and `off`
  modes the default frontend does not enforce it, until M6 removes that frontend; that exposure is accepted. An entry point
  with a count its purpose does not take keeps the count's rejection (`JULC0008`). The in-repo fixtures that wrote
  `validate(BigInteger, BigInteger)` declare the context."
- D15's table gains: `| JULC0103 | An entry point's last parameter is ScriptContext or PlutusData, by identity; an entry point
  with a count its purpose does not take keeps the count's rejection | Both, by the maintainer's decision (r16); the gate's
  modes apply on the default frontend |`.
- Compatibility, "Source that stops compiling", gains: "an entry point whose last parameter is not `ScriptContext` or
  `PlutusData` (`JULC0103`), on both frontends under the default gate: the wrapper passes the script context's Data to that
  parameter undecoded, so the script did not run what the method's JVM test ran".
- D13: the rows below, as Policy rejections (F904) and, where the source has another error, Diagnostic changes (two errors).
- r16, the rest: "Group 4a: the subset's rejections on javac trees, with a code for each the reference made uncoded (I2a,
  `JULC0104`–`JULC0122` and eleven activated codes); `SubsetScan` collects every error (F946, I2a's first error as D13's
  two-errors rule says); `JULC0087` names its reason; ADR-067 M3 and M3b on `frontend=JAVAC` (`JULC0079`, `JULC0080`, D11).
  `JULC0080` reverses F908: `R { }`, accepted on `frontend=JAVAC` from M3d, is a record member ADR-067 D9 rejects. A declared
  accessor or `toPlutusData` stays `JULC0101`, and a constructor with a statement `JULC0099`, ahead of D9's single diagnostic,
  by D15 (r12)."
- D11's last bullet: "ADR-067 M3 (two sealed parents, `JULC0079`) and M3b (record members, `JULC0080`) are on the javac frontend
  (Group 4a). M2a and M2b (record patterns) remain: `JULC0076` stands for a record pattern until they are lowered."
- **ADR-067 D6's wording (`JULC0079`).** D6 counts "only sealed interfaces registered as JuLC sum types". On `frontend=JAVAC`
  `JavacTypeRegistrar` registers every sealed interface, a generic one and one that permits a `final` class included (the
  latter as a sum with a variant that is not a record), so `RecordDeclarations` counts every sealed interface, and the text
  should say so: "On `frontend=JAVAC`, every sealed interface is registered, so every sealed parent counts." This rejects a
  record of a generic sealed parent and a sealed parent that permits a class, which the reference compiles; it fails closed and
  no corpus row reaches it. Aligning the check with the reference's narrower registration would read the reference's rule into
  the javac frontend, which D7 replaced, so the text is the change proposed.

## D11: which of ADR-067's codes stay

M3e's table stands: `JULC0005`, `JULC0071`, `JULC0073`, `JULC0082` and (but for one guard shape) `JULC0083` are javac's
diagnostics on `frontend=JAVAC`; `JULC0072`, `JULC0074`, `JULC0075` and `JULC0076` stay as subset rules. This slice adds
`JULC0079` and `JULC0080` on `frontend=JAVAC`. On the default frontend neither runs: D11 puts ADR-067's remaining milestones
on the javac frontend, and the exposure until M5 is accepted (D4).

`JULC0080` runs after the rules both frontends share, so a declared `toPlutusData` or accessor stays `JULC0101`, and a
constructor with a statement stays `JULC0099` (a non-canonical constructor, which must call `this(...)`, is always the latter).

## Follow-up rows

| Row | State |
|---|---|
| Uncoded rejections of statements (M3c) | Closed: each has its code (above) |
| Uncoded rejections of M4 | Closed: each has its code (above) |
| Rejection order in `SubsetScan` (F946) | Closed: collected, with I2a's meaning above |
| `JULC0087`'s words for what it cannot read | Closed: the template takes its reason |
| The order of rejections in code nothing lowers (M4 confirmation) | Closed: one walk in source order of every rule the reference's validator has |
| A label in code nothing lowers (M4 confirmation) | Open, narrowed: the rejected statements in a labelled block are read first now; `String z = null;` alone is `JULC0017`. `done: while (...) { continue; }` and `a: { while (...) { break a; } }` stay `JULC0084` where the reference accepts. Owner: the loop slice, with ADR-061 |
| What the reference goes on to past a rejection (M4 confirmation) | Open: an update's rejection in an iterable or a condition is not collected by `LoopRules`. D13's two errors cover it; no corpus row reaches it. Owner: the loop slice |
| Scan broader than lowering (M3a, M3b) | Narrowed: the reference validator's rules now reject with codes where the scan used to stop. A kind lowering does not reach stays a stop, by I8 |
| An entry point's last parameter (F904) | Closed by the maintainer's decision: `JULC0103` |
| ADR-067 revision (D11) | Open for the document: ADR-067's I9 oracle, outcome matrix and `Javac21ParityTest` retirement. Owner: the ADR text |

## Not-ported rows of I1, and their owners

At `b71373e3` I1 has 42 `not-ported` and 19 `reference-rejects-not-ported` rows; after this slice, 35 and 5.

| Construct | Rows before | Now | Owner if open |
|---|---|---|---|
| `inheritance` (3), `this` (`IDENTIFIER`), `SYNCHRONIZED`, `NEW_ARRAY`, `ARRAY_ACCESS`, `LEFT_SHIFT`, `BITWISE_COMPLEMENT`, `NULL_LITERAL`, `CHAR_LITERAL`'s neighbour | 14 `reference-rejects-not-ported` | The reference's rejections with their codes: Diagnostic change rows, and `both-reject` for `JULC0017`, which both frontends code alike | |
| `a new of a variant of two sealed interfaces` | 3 | `JULC0079`, Policy rejection (D11) | |
| `METHOD_INVOCATION`, `CONDITIONAL_AND`, `NEW_CLASS` over a record that declares a method or `equals` | 5 | `JULC0080` (D11): Policy rejection where the reference accepted, Diagnostic change where it rejected | |
| `an equals between unrelated types` | 25 | 23: two sources stop first at `JULC0103` | M5: the maintainer's decision keeps it a stop because of `UVerifyProxy.java:70` |
| `an expression of type java.lang.Object` | 15 | 13 | The typing of a conditional or `switch` of mixed arms, and of a raw tuple's components (follow-ups "A `switch` as a compared operand" and "`var` of a `switch` of mixed arms"): a typing decision, M5 |
| `VARIABLE` | 2 | 2 | A local the reference reads and javac's typing stops at (the "chained read" decision, I2d): M5 |
| `CHAR_LITERAL`, `a static call not qualified by a simple class name` | 2 | 2 | Literals and calls: M5's zero-`JULC0084` gate |

## Rows moved, by class

I1 (`adr068Lowering`, the arguments of `freeze.sh lowering`, out to scratch, never frozen) on the code of `701f9300` and again at
`47dde88e` (identical tables), against the
table at `b71373e3`: 17,098 cases, **389 rows moved**, 368 of them a "shrink" in the tool's sense (a row leaving
`i1a-identical` or `both-reject`), which the freeze takes with `-Padr068.allowShrink=true` and this section's reasons.

| Rows | From | To | D13 class |
|---|---|---|---|
| 212 | `both-reject` (uncoded) | `DIFFERENT-rejection` | Diagnostic change, new code (I2a): `JULC0113` 103, `JULC0121` 43, `JULC0120` 36, `JULC0011` 9, and one or two each of the rest. Each was checked by script: the reference's words (without the position and hint it writes into its text) are javac's, and its position is javac's where it gives one |
| 13 | `reference-rejects-not-ported` | `DIFFERENT-rejection` | Diagnostic change: 9 new codes (above), 4 `JULC0080` (ADR-067 D9 replaces the reference's `Undefined variable` and `JULC0055` for a record member) |
| 1 | `reference-rejects-not-ported` | `both-reject` | None: `null`, `JULC0017` on both |
| 80 | `i1a-identical` | `DIFFERENT-javac-rejects` | Policy rejection: 79 `JULC0103` (F904), 1 `JULC0080` (D11) |
| 7 | `not-ported` | `DIFFERENT-javac-rejects` | Policy rejection: 3 `JULC0079`, 4 `JULC0080` (D11) |
| 6 | `DIFFERENT-javac-accepts` (D8's unread units) | `DIFFERENT-rejection` | Diagnostic change (F904 and D8): the root's entry point also takes a `BigInteger` last. These **replace** their entries in `lowering-accepted-differences.tsv` |
| 2 | `DIFFERENT-rejection` | `DIFFERENT-rejection` | Already accepted, class unchanged: `68878d7e`, two errors (the reference's `Undefined variable: q`, javac's `JULC0110` for the bare block, now coded); `e01a0161`, `JULC0077` now coded, with the source's words since the review (F5) |
| 68 | `DIFFERENT-javac-rejects` | `DIFFERENT-javac-rejects` | Class unchanged; the TSV **replaces** each entry, whose reason named the old code: 63 `JULC0086` and 2 `JULC0095` are `JULC0103` first (two errors; with the context declared the review's run gives `JULC0086` again on 43, `JULC0095` on both, and on 20 the body reads the context, F904's own target); 2 `JULC0087` have the new words; 1 `JULC0087` is `JULC0080` |

`068/g4a-accepted-differences.tsv` holds 386 entries in the tool's format (231 Diagnostic change, 155 Policy rejection): 312
the integrator adds and 74 that replace an entry of the same key (the six D8 rows and the 68 above), all named at its head.

**`JULC0103` comes before other rejections.** Besides the count, a source with a context of another type and another error
gives `JULC0103` first: the reference's coded `JULC0015` and `JULC0018` (`b254026b`, `f7c2867b`), floating point, a
`@MultiValidator` that mixes purposes or repeats one, mutual recursion (`9eed01ad`), and on `frontend=JAVAC` `JULC0096` and
`JULC0097`. Each is D13's two errors; with the context declared the reference's error is back on both frontends (the review's
rewrite run). No byte changed: no row moved into or out of
`DIFFERENT-bytes`, `DIFFERENT-pir` or `i1a-identical` but by a rejection, and the reference did not move (no `REFERENCE-DRIFT`).

Totals after: `i1a-identical` 9,424 (−80), `both-reject` 306, `DIFFERENT-rejection` 253, `DIFFERENT-javac-rejects` 254,
`DIFFERENT-javac-accepts` 21, `not-ported` 35, `reference-rejects-not-ported` 5; the rest unchanged.

**The 25 rows of F904.** The follow-up estimated 25; the run moves 79 rows, from 78 distinct sources, all recorded test
fixtures of the M0 corpus (the corpus is frozen, so its rows keep the old sources; the in-repo fixtures are rewritten). None is
an external example, a `.diffcase`, a stdlib library or a Blaster or C7 fixture.

## Tests run

Every Gradle run went through `heavy.sh`, `--offline --no-watch-fs`, JDK 25.0.2, with its limit started once the lock was held.

| Run | At | Result |
|---|---|---|
| `:julc-compiler:test` | `0ee13635` | 6,709+ tests, 0 failures (`BUILD SUCCESSFUL`, 6m 24s) |
| `:julc-difftest:test :julc-annotation-processor:test :julc-testkit:test :julc-examples:test :julc-playground:test :julc-blueprint:test :julc-cli:test :julc-verification:test` | `0ee13635` | difftest 2,116 tests, 3 failures, each a harness row that pinned what D9 or I2a now changes (fixed at `6da0a704`, rerun below); cli 472, annotation processor 30, testkit 237, examples 100, playground 46, blueprint 26, verification 93: 0 failures |
| The three difftest classes, `Adr068RejectionSitesTest` and `JavacSubsetCodesTest` | `47dde88e` | Records harness 45, reads harness 252, lowering tool 67, rejection sites 2, `JavacSubsetCodesTest` 57: 0 failures |
| I1, `adr068Lowering` into scratch | `701f9300`'s code, and again at `47dde88e` | 389 rows moved, all classified (above); the two runs' tables are identical |

New tests: `SubsetRulesTest` (F904 in both directions: twelve types, every purpose, a datum, a multi-validator, words and
position, the count's precedence, the accepted spellings at every level against the reference's bytes, a user's own
`ScriptContext`, the reference with the gate off), `EntrypointContextPropertyTest` (jqwik, 120 tries, fixed seed: purpose by count
by last type, both frontends), `JavacSubsetCodesTest` (each new code against the reference's words and position, the nearest
accepted shapes at every level against the reference's bytes, F946's collection, `JULC0079`, `JULC0080`, `JULC0087`'s words), and
`LoweringFixture`'s I2a comparison, which every existing test that compares the frontends now runs through.

No evaluation is new: every rule here rejects, and what each lets through compiles to the reference's bytes at all four levels,
so the JVM comparison of those programs is the reference's own.

### After the review (round two)

| Run | At | Result |
|---|---|---|
| `:julc-compiler:test` | `1e740825` | `BUILD SUCCESSFUL`, 0 failures |
| `:julc-difftest:test` | `1e740825` | `BUILD SUCCESSFUL`, 0 failures |
| I1 into scratch | `1e740825` | Against the run at `47dde88e`, one row's detail moved: `e01a0161`, `JULC0077` now with the source's words (F5); its class, `DIFFERENT-rejection`, is the one the table already accepts. F3 and F4 move no corpus row |
| Docs build | | Not run: `docs/` has no `node_modules`, and the build cannot install offline. The pages are Markdown in the existing structure |

## Mutants

Applied one at a time to the new lines, each run with its tests under a 5-minute limit.

| Mutant | Line | Result |
|---|---|---|
| M1 | F904: the context check never rejects | Killed (22 failures) |
| M2 | F904: any count is taken | Killed |
| M3 | F904: any `PlutusData` subtype is the context | Killed |
| M4 | The unlowered walk misses `null` | Killed |
| M5 | The scan stops at its first error | Killed |
| M6 | Two sealed parents allowed | Killed |
| M7 | A record's static field allowed | Killed |
| M8 | A user's own `Object` superclass coded | Killed |
| M9 | `JULC0087`'s unread case worded as an array | Killed, after a test of a type variable and a raw argument was added (it survived the first round) |
| M10 | A bare array initializer passes the scan | Survived: equivalent. Lowering stops at the same construct with the same `JULC0084`, and in code nothing lowers the statement stops first |
| M11 | `>>` named `RIGHT_SHIFT`, not the reference's `SIGNED_RIGHT_SHIFT` | Killed |
| M12 | The unlowered walk reads what javac wrote | Killed |
| M13 | A compact constructor named as any canonical one | Killed, after a test of the words was added (it survived the first round) |
| M14 | Unary `+` named `UNARY_PLUS` | Killed |

Round two, on the review's lines, each killed by a test failure: N1 (the functional rule by name dropped, 6 failures), N4 (two
declarators reported twice), N5 (`JULC0077` back to javac's tree text), N7 (the old order, every statement checked before any is
read: 2 failures), N8 (code nothing lowers stops at its first error).

## Not done

- **I1 not frozen** (by instruction): `g4a-accepted-differences.tsv` holds the entries, with six replacements, and the freeze
  needs `-Padr068.allowShrink=true` for the 368 rows this section explains.
- **ADR text** is proposed above, not applied. **ADR-067's revision** (I9's oracle, the outcome matrix, `Javac21ParityTest`) is
  not written.
- **ADR-067 M2a and M2b** (record patterns) are not lowered: `JULC0076` stands. That is lowering, not a subset rule.
- **Uncoded rejections that remain** on `JAVAC` (types, records, `new BigInteger`, `PlutusData.cast`, the discovery's): listed in
  the follow-ups with their plan.
- **The loop slice's two confirmation rows** (a label in code nothing lowers; what the reference goes on to past a rejection):
  narrowed, not closed.
- The gates outside `test` (`pairCaseTest`, `onChainHarnessTest`, `wasmSmokeFull`, the parity suite, rediscovery, Blaster) were
  not run: no byte changes, and the rules reject only.

## ADR deviations and decisions to confirm

- **`JULC0080` rejects what the reference runs.** ADR-067 D9, which D11 puts on `frontend=JAVAC` in M3, rejects every record member
  but a nested type. The reference compiles such a record where nothing reads the member, and M3d accepted `R { }` on `JAVAC`
  (F908). Five corpus rows become Policy rejections, and harness rows that agreed now reject on `JAVAC`. This follows the accepted
  ADRs; it is the one place this slice makes `JAVAC` read less than the reference, and it fails closed.
- **F904 at the gate.** The rule is the gate's on the default frontend, so under `report` or `off` it does not hold there (D15's
  modes), and the reference with the gate off is unchanged. 170 in-repo fixtures are rewritten.
- **New codes for old words.** Every coded rejection keeps the reference's words exactly; the catalogue's planned templates for
  `JULC0004`, `JULC0006` and `JULC0077` were re-worded to them before being emitted.

## Where a reviewer should look

- `SubsetScan`: the collection in `scan`/`check` (a rule's error is collected and its subtree skipped; a stop after an error yields
  the errors), and `requireNoRejectedStatement`'s walk of code nothing lowers.
- `SubsetRules.requireEntrypointSignatures` with `JavacDiscovery.takesParameterCount`: the count guard is what keeps the reference's
  order.
- `RecordDeclarations`: what counts as written (`isGenerated`), and its place after `JULC0101`/`JULC0099`.
- `LoweringFixture.error(reference, javac)`: the I2a comparison every frontend test now uses; it accepts only a coded javac error
  with the reference's words, and its position where the reference gives one.
- `g4a-accepted-differences.tsv`: the 231 Diagnostic change rows were classified by script on the full texts; the 14 by hand are
  the two-errors and D8 rows.
