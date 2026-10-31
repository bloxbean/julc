# ADR-068 known issues and follow-ups

This file tracks known issues and deferred review findings for the javac migration (ADR-068, PR #267), so a later
iteration can pick them up.

Review policy since 2026-10-31, approved by the maintainer: a milestone closes when it introduces **no new
regression** and has **no open P0/P1**. Remaining P2/P3 findings, and known issues found along the way, are listed
here instead of blocking the milestone.

Each entry gives:
- an ID: the review finding or issue;
- where it was found;
- what is wrong;
- the planned fix, with the milestone that resolves it by construction where there is one.

## Pre-existing JuLC defects (found during the migration; open on the frozen JavaParser frontend until M5)

| ID | Found in | Problem | Plan |
|---|---|---|---|
| #296 | ADR-068 review (F61) | `"a" + 1` compiles but fails on-chain (`appendString` over an integer) | The javac frontend rejects it with `JULC0090` (M3); a decimal conversion could follow later |
| #297 | ADR-068 review (F110) | `contains` on a nested JuLC collection fails on-chain (target not Data-encoded) | Fixed in the javac frontend's lowering (M3): encode the target by its static type |
| #146 / T13 | ADR-062 | `compareTo` outside the `OP 0` form still evaluates operands twice | Fixed for `OP 0` in both frontends at M1; other forms remain, to be revisited after M3 |
| T3–T5, T29–T34 | ADR-062 / ADR-067 | Equality, record-member, two-parent and ledger-name divergences on the default frontend | Accepted until M5 (D4). The javac frontend rejects or fixes them (D6, D11) |
| `a` names | M0 (F301 work) | `a` used as a parameter name is accepted, while the same text as a local is rejected: a reading divergence | Disappears with javac reading (M3); confirm then |
| intersection cast | M0 | `(BigInteger & Serializable) a` compiles without resolving the intersection type (harmless; marked defensive in the rejection catalogue) | Decided by javac typing in M3 |
| `MathLib.divMod(...).first()` | M1c review (Codex r2) | `ClassCastException` on the JVM, while UPLC returns the quotient (raw `Tuple2` with Data components) | Investigate in the stdlib; M3's typed lowering may change it |
| for-each over an inline list | M1b review | `for (var x : JulcList.of(a, b))` loses the element type: `x` stays Data, so `acc.subtract(x)` fails on-chain (`SubtractInteger: expected integer`) though javac types `x` as `BigInteger`. A declared local (`JulcList<BigInteger> xs = ...`) works. Fails closed | M3 lowers from javac's types |
| `.equals` between unrelated types | M1b review (F751) | `PubKeyHash.equals(BigInteger)` is `false` on the JVM and fails on-chain (`UnBData: expected data`). Fails closed. Same family as T34 (`String.equals`, #292) | M3 lowers `equals` from javac's types; a comparison javac can prove false could be diagnosed |
| chained read on a new object | M1c review | `new Tuple2<>(x, y).second()` is rejected with `JULC0055` (also in the explicit form, as at B0) | M3 lowers from javac types; revisit then |
| #284, #286 (T33) on the default frontend | M2 | The saved pins still diverge on `JAVAPARSER`, as frozen. On `JAVAC` the names bind to the user's records, but no value can be observed before M3 | Keep both issues open until M3 shows the values agree and M5 makes that frontend the default |
| A varargs record component | M2 | The JavaParser frontend ignores `...`: `record R(BigInteger... xs)` registers one integer while Java's type is an array, and `@NewType record N(BigInteger... v)` is accepted. `byte...` is rejected as an unsupported primitive | The javac frontend rejects `byte...` as a record or `@NewType` component, and any other varargs component as an array. A varargs constructor that is not the canonical one changes nothing. A D13 row when M3 reaches the case |
| Unpaired surrogates | M2 (F823) | 7 of the 15 `reference-rejects` rows are unpaired surrogates: javac accepts them, and JuLC's reader rejects them. M0's inventory marks them permanent, and ADR-065 keeps the rejection, but that reader goes at M6 | M3 must reject these in the decoded value of a literal, with a code. The other 8 are escape shapes scheduled for acceptance |

## Deferred review findings (P2/P3)

| ID | Milestone | Finding | Plan |
|---|---|---|---|
| F604 | M1c | Diamond inference does not use a method-argument target; the wording says "as javac infers them" | Wording fix in flight in M1c round 3; parameter-type targets come with M3 |
| F710 (fallback) | M1c | `switch` expressions lose their result type in the frozen frontend, so `JULC0091`/#299 do not see through them | M1c round 3: preserve the type, or reject those shapes. Either way, the javac frontend's real types resolve it in M3 |
| F720 | M1c (Codex confirmation) | Pre-existing, also at B0: a second `switch` erases the "unknown" result type (`LinkedHashSet` merges the `UNKNOWN` sentinel with `DataType`), so a mixed-type switch value fed through another switch reaches `JulcList.of` / a diamond and fails on-chain (`MkCons`). Fails closed (the script rejects what the JVM accepts) | Resolved by javac types in M3; until then an accepted residual (a mixed-type switch inside a switch, then Data-encoded) |
| F721 | M1c (Codex confirmation) | Pre-existing: `Optional.of` builds `DataConstr` directly and encodes an `UNKNOWN`-typed value without the `JULC0012` check; fails on-chain at `MkCons` | M3 (javac types); or a one-line `requireDataEncodable` in `StdlibRegistry`'s `Optional.of`, if needed earlier |
| F722 | M1c (Codex confirmation) | Pre-existing: the raw-destination rule (F711) does not look through `switch` arms, so `Tuple2 t = switch … new Tuple2<>(x, y)` then `(BigInteger) t.second()` fails on-chain (`AddInteger: expected integer`). Fails closed | M3 (javac types); until then an accepted residual |
| F724 | M1c (Codex confirmation) | An unknown-valued multi-accumulator packing reports `JULC0012` at `0:0` (no position; `LoopBodyGenerator.java:231`) | Position it at M4's loop port |
| M1 pool simplification | M1a (F404) | The gate's pool is each entry point's resolved library list plus the bundled stdlib, not D8's per-entry-point scan | M3 replaces it with D8's pools |
| F530 note | M1a | `readJars` without a supported javac checks entry paths only (report and off modes, with a warning) | Revisit when the javac frontend becomes the default (M5) |
| Plugin test flake (broader) | M1c integration | `JulcPluginBuildLifecycleTest` failed intermittently under heavy file creation (a corpus recording beside it), not under CPU load alone: `compileJulc` was `UP_TO_DATE` after an output was deleted, or failed `@InputDirectory` validation after the source directory was. Reproduced on #267 without M1c. Controlled A/B under file churn: 3/20 failed with file watching, 0/20 with `--no-watch-fs`; that test now passes `--no-watch-fs` | Other plugin tests that change files between builds use the same runner shape and were not stress-tested; revisit if one flakes |
| Per-case observation of the `compareTo` rule | M1c (F730) | 5,626 of 7,596 byte-changed cases have no observation attached (`068-m1c-reference-b1.md`). V146 shows the rule is the only byte change, so they rest on the rule's own tests. `compare-evaluations.py` matches 12,826 evaluations only as groups | Pair old and new scripts through the ledger's case keys when M3 compares frontends; the same comparison is M3's gate |
| `DialectGate` | M1b review | The test helper that turns the gate off changes process-global state with one saved slot, so it is neither nesting-safe nor concurrency-safe. Current callers restore it | Removed with the dialect tests at M6; pass `CompilerOptions.setJavacGate` where a test needs it sooner |
| Explicit decode cost | M1b review | Seven rewrites and two Blaster fixtures grow by writing out a conversion the dialect made implicitly (985 → 1,039 bytes at `PV11_SAFE`, +3.3% CPU). M1's exit allows it, listed. Whether a cheaper lowering exists was not shown | Look at the decode and re-encode pair when M3 lowers typed calls |
| Dialect tests with no acceptance value | M1b review | Tests that compile with the gate off exercise the old spelling; they are not migration acceptance. `DialectRemovalAlternativesTest` pins the alternatives under `enforce` | Delete the dialect tests at M5, when the JavaParser frontend stops being default |
| External `ByteStringLibEvalTest` | M1 close | The test's wrapper source in `julc-examples` needs `import org.julclang.stdlib.annotation.OnchainLibrary;`. With it all 10 tests pass (checked in a scratch clone) | Add it in `julc-examples` before the release that carries the gate |
| Tasks outside `test` | M1b review | `wasmSmokeFull`, `pairCaseTest` and `onChainHarnessTest` are outside `test`, so a green suite says nothing about them; `wasmSmokeFull` failed under `enforce` while `test` was green | Run them at every milestone close; consider a task that aggregates them |
| wasm smoke flakiness | M1a | `wasmSmokeFull` timed out once at load average ~80 (a VM-only evaluation step) | Monitor; raise the timeout if it recurs |
| evaluations ledger size | M0 | The evaluations ledger is 6 MB (stored as gzip) and grows each milestone | Consider trimming its columns, or storing only the rows that differ, after M1 |
| F210 scope | M0 | The class-file rule for implicit `equals` without code is sound only for API stubs (guarded by `ApiRecordEqualsTest`) | Keep the guard; do not reuse the rule for user class files |
| ADR-067 revision | ADR-068 D11 | ADR-067's I9 oracle (javac 25), outcome matrix, M2b gate and the retirement of `Javac21ParityTest` are still to be written | With the M3 switch port |
| ADR-066 G1 | ADR-066 | M3 part 2 onward (guard and record-pattern generation) waits for the javac frontend's switch port | After M3 |
| Types of an unreferenced library | M2 | The reference registers every library it is given; javac reads the pool units a root references (D8). One corpus case differs: a blueprint names `com.myapp.Token` by its stable id because an unreferenced library declares a second `Token`; on javac it stays `Token`. Listed in `068/m2-accepted-differences.tsv` | The ADR revision gives a blueprint-name difference its D13 class; M3's I1 rows meet the same difference in `namedDefinitions` |
| The frontend option on hosts | M2 | D4 says the Gradle plugin, CLI and testkit expose `frontend`. Only `CompilerOptions` does: at M2 every host would get `JULC0084` | Add it with the first lowered construct (M3) |
| Ledger registered per compilation | M2 | Each `JavacProgram` reads the ledger stubs through its own task, about 1 ms warm (`068-m2-types.md`); the JavaParser path parses the ledger sources per compilation too, about 5.4 ms | Measure against E6 when M3 compiles whole programs; a process-wide snapshot of the PIR types is possible, since they hold no javac objects |
| Diagnostic parity of type rejections | M2 (F809, F833) | The corpus tool compares a rejection's message, not its anchor. Messages are the JavaParser path's, except that javac prints qualified names (`Unknown type: Integer (resolved to java.lang.Integer)`, `Got: java.math.BigInteger[]`, about 20 shapes in `shapes-m2.tsv`), and every rejection now has a position where that path had `<source>:0:0` | I2a's inventory gate in M3 compares code and position; decide then whether the name's spelling matters. Also: `record B<Item>(Item i)` says `Did you mean: Item?`; any `enum` is `cannot access java.lang.Enum.EnumDesc` (M1's blob) |
| Local types' identity | M2 (F801, F829) | Java gives a local type no qualified name, and javac's `getQualifiedName()` reports its simple name, which could be anything's. The registry keys a local type by its binary name (`P$1R`), which javac keeps unique, with a guard for the invariant. The reference keys it by the bare simple name, so the two differ in shape: two local records of one name are `Duplicate` there and two identities here. No corpus case has a local record, and `shapes-m2.tsv` pins the difference | I2 already answers whether local records stay, for lowered bodies: they stay rejected (`Unsupported statement: LocalRecordDeclarationStmt`). M3's subset port keeps that, and decides the unlowered bodies of generic methods |
| `@NewType` by spelling | M2 (F803, F806, F828) | The reference matches a marker annotation written `NewType` and compares the field's spelling, javac goes by identity. So `@NewType()` and `@org.julclang.stdlib.annotation.NewType` are `@NewType`s on javac and records on the reference (their Data differs), a user's own `@interface NewType` is the reverse, and `@NewType record N(java.math.BigInteger v)` is accepted here and rejected there. `JavacTypesTest` and `shapes-m2.tsv` pin each one. No corpus case has one | The ADR revision names these; M3 gives them D13 rows if a case appears |
| Names the reference rejects and javac accepts | M2 (F808) | A packageless top-level variant of a nested sum (the reference keys `C.V`, then `Ambiguous type`); `Ledger Delegatee.*` on demand; scoped names inside a library class (about 1,670 of 3,000 fuzzed providers are `Unknown type` or `Unsupported on-chain source type` there); local-versus-top-level `Duplicate`; spelled-out `ConstrData` provider differences, both ways | M3's I1 rows meet them; each needs a D13 class |
| Two variants of one simple name | M2 (F807) | `permits X.A, Y.A` gives two constructors named `A`. The registries are equal, but on the reference `new Y.A(bytes)` fails on-chain (`IData: expected integer`) and `switch` is `JULC0071` | M3 keys variants by identity, not by constructor name |
| Lowering assembly and its fork | M2 (F846) | The frontend fork sits at several `JulcCompiler` sites; `classpathLibraries`, named as a query, always throws on `JAVAC` and reads live `options` where the rest read the resolved `context`; steps 13 to 22 of `doCompileFromCus` are neutral assembly mixed with JavaParser lookups | **M3's first work**: one seam between discovery and assembly, forked on `context` |
| Model lifetime for specialization | M2 (F847) | `JavaLibraryProvider.specialize` runs at `materialize()`, long after construction, so on javac the provider must keep a live task, and `JavacProgram` has no thread discipline like `JavacMetadata.read` | **M3's first work**: use that lock, or single-thread confinement, stated before the port |
| `JavacFacts` surface | M2 (F848) | `trees()` is gone and `facts.registry().snapshot()` reads well now; still open: `(TypeElement) x.asElement()` at about 8 sites (add `identity(DeclaredType)`), and FQCN strings in three classes, where a typo is a silent miss | M3, in one constants class, before it adds dozens |
| javac quirks | M2 (F849) | `facts.components` and `facts.permitted` now complete a class first and every M2 caller uses them, so `JavacTypeRegistrar.complete()` is gone. `ArrayIdentityEquality.record()` still reads components itself, and is safe only because `getKind()` ran first | When M3 wires it in, through `JavacFacts` |
| Access around the door | M2 (F850) | `ArrayIdentityEquality(types, elements, trees)` and `ReplExpressionWrapper` use `Trees` directly; both predate M2 | M3: have them take `JavacFacts` |
| Inverted dependency | M2 (F851) | `JavacLibraryTypes` calls `JavacGate.metadata` with a forced `ENFORCE` option, so the permanent frontend depends on M1's scaffold | M3: move the shared metadata to `JavacFrontend` |
| `JavaLibraryProvider` on `JAVAC` | M2 (F852) | Not a trap: `requireLowering()` guards every export method, and with no sources `gather` and `specialize` exit before a null. It fails closed, though `Operation` and `Codec` requests get `JULC0084` needlessly | M3: split the constructor into two sources rather than growing the early `return` |
| Layouts and API marks | M2 (F853) | Hash FQCNs live in `TypeRegistry`, other layouts in `LedgerLayouts`. `LedgerTypeProvider(CompilerOptions)` is not marked experimental, as `CompilerFrontend` is | M3: one home; mark it, removed at M6 |
| `JULC0084`'s wording | M2 (F854) | At M2 "the method 'f' of V" reads as if `f` were the problem, and the provider variant has no position | M3: the default branch passes the tree kind and location |
| One-caller code | M2 (F855) | `TolerantJavacTypes` now shares `JavacProgram.read`; the package-private `JavacProgram.analyze(JavacFrontend, ...)` still has one caller | M3: drop it if tests do not need it |
| Illustrative doc blocks | M1b | 16 whole-unit doc blocks stay invalid alone on purpose (WRONG/CORRECT pairs, compact source files, cross-file examples, sketches); 408 fragments were scanned only by the BigInteger-operator heuristic | Compile docs' examples in a test (as `ValueOrientedExamplesTest` does) when the docs are next reworked |

## Future stdlib enhancements (after ADR-068 is complete)

Nice-to-have additions, not part of the migration. Pick these up once M6 is done.

| Item | What | Notes |
|---|---|---|
| `JulcList.foldl` | A real instance method beside `map`: `<A> A foldl(BiFunction<A, T, A> fn, A initial)` | D6 removed the undeclared `ListsLib.foldl`. Needs `BiFunction` in the platform blob (only `Function`, `Predicate` and `Supplier` are there, so the digest changes and the native and Web Image hosts are rebuilt), a JVM implementation, `TypeMethodRegistry` dispatch over the existing on-chain code, and JVM/UPLC agreement tests. Additive |
| `JulcList.zip` | The same, for `zip` | Decide its result type first, probably `JulcList<Tuple2<A, B>>` |

Vavr (https://vavr.io/) is the inspiration for these additions to `JulcList` and `JulcMap`, for naming and API shape. Vavr
itself is not supported on-chain: `Try` cannot work, because a UPLC failure stops the whole evaluation, and its other types
would duplicate `JulcList`, `JulcMap` and `Optional`. Each addition needs JVM and UPLC agreement tests.

## Closed here

Entries move here, with the commit that resolves them, once they are fixed.

| ID | Resolved by |
|---|---|
| F723 | `7eb301f0`: the release note also covers a single-typed `switch` followed by a method call |
| F401 | `ed07fb87`: `JavacProvenance` on `CompileResult`. ADR-068 r9 withdraws the note's `preamble.compiler`, which CIP-57 closes |
| E6 cold start | M1 close: about 1.6× and +150 ms; threshold 2× (ADR-068, "M1: closed") |
| Elci native shape | M1 close: JVM CLI and native images (GraalVM 25.0.2, 25.3.4.1) pass against the snapshot |
| F731, F732 | `compare-ledgers.py` reports a run only the reference has as `lost observation`, keeps each part of a difference, and calls a change a fix only if more levels agree with the JVM. The committed diff is unchanged, so no regression was hidden |
