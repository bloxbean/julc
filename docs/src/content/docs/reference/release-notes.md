---
title: "Release Notes"
description: "JuLC release notes and migration guidance"
---

## Upcoming preview: a source name can never resolve to a compiler-generated binder (ADR-060)

The compiler generates variables around your code for switch dispatch, loops, the
validator wrapper, `@Param` decoding and the list and map builders. Several of
those names were legal Java identifiers, so a source variable with the same name
silently resolved to the compiler's variable instead:

- `BigInteger __match_tag = ...;` read inside a switch arm gave the constructor tag;
- a `@Param` named `datum__` compared the spent datum with itself, so any parameter
  value was accepted;
- a variable named `xs__`, `__acc_tuple` or `loop__forEach__0` read inside a loop saw
  the loop's remaining list, accumulator tuple or recursive function;
- a `@Param` named `redeemer__`, `scriptContextData`, `ctxFields__` or `tag__`, or a
  pair of `@Param`s `cfg__raw` and `cfg`, read the wrapper's value.

Every generated name now begins with `#`, which no Java identifier can contain, and
switch-dispatch names are also chosen away from every name a branch uses
([ADR-060](https://github.com/bloxbean/julc/blob/main/adr/060-generated-binder-namespace.md)).
The same change fixes adjacent silent miscompiles:

- **Compound assignment in loops** dropped the operator: `count += 1` over three items
  returned 1 and `while (k < 3) { k += 1; }` never terminated. `+=`, `-=`, `*=`, `/=`
  and `%=` on integers, and `+=` on strings, now mean `x = x op y`. `&=`, `|=`, `^=`
  and the shift operators are rejected (`JULC0052`), because lowering `&=` as `&&`
  would skip a check Java always evaluates.
- **Overloaded methods**: the last declaration ran for every call, so `check(BigInteger)`
  next to `check(long)` used the wrong body. Overloads are rejected (`JULC0054`) unless
  they compile to identical code, like the stdlib's two `integerToByteString` forms.
- **Declarations with several variables**, `BigInteger a = x, b = y;`, bound only the
  first, so a later `b` could read a field `b`. They are rejected (`JULC0053`); fields
  may still declare several variables.
- **Methods now have their own namespace**, as in Java: a local, parameter, field or
  `@Param` may share a method's name (`BigInteger fee = fee(a); return fee(fee);`
  failed at run time, and a `@Param limit` next to a method `limit(...)` gave the
  wrong decision). `x.name()` or `x.name` on a value whose type is not known used to
  call whatever was named `name`; it is now rejected (`JULC0055`). A static field
  initializer that calls a method of its class now gets an explicit error (`JULC0057`).
- **Fields updated in loops**: a loop assignment to a static field or `@Param` rebinds
  the name only inside that method, so another method reading the field saw the
  original value (a helper returned 0 after `count += 1` three times). Such an update
  is rejected (`JULC0056`) when the field is final or another method reads it; updating
  a field read only in the same method keeps working. A loop-body local that shadows a
  field and is reassigned was threaded out of the loop as if it were the field; it is
  now a local.

**Migration.** Programs that use none of these shapes keep byte-identical scripts
and hashes at every optimization level; this was checked on 1,432 in-repo corpus
rows (all levels, with and without source maps, including `@Param` and
multi-validator programs) and the 344 external example artifacts. A recompiled script that used an affected shape changes its bytes, hash
and budget, and its behaviour now matches Java: review it and redeploy if the old
behaviour mattered. Deployed scripts never change. Code that is now rejected must
be rewritten as the diagnostic describes. PIR and UPLC text output, such as the
`.uplc` file from `julc build` and the playground pipeline view, now shows `#`
names; this is cosmetic. Code that drives `PirGenerator` directly must register a
method with `SymbolTable.declareMethod`: a method registered as a variable with
`SymbolTable.define` is no longer callable. Diagnostic codes `JULC0044`–`JULC0051`
are reserved for the generic backend work.

## Upcoming preview: builder binders no longer capture user variables (#186)

A variable referenced inside a lambda or argument could silently resolve to an
internal variable of the list, map or loop code the compiler generated around it.
For example, `outer.any(x -> inner.any(y -> y.equals(x)))` compared each inner
element with itself and returned true whenever `inner` was non-empty, so a
validator could accept a transaction it should reject. Affected names were the
builders' internal binders: `x`, `acc`, `lst`, `go` and `h` for `any`, `all`,
`find` and `foldl`; `x_map`, `acc_map`, `x_flt` and `acc_flt` for `map` and
`filter`; the `*_get`, `*_c`, `go_*`, `ps_*`, `xs_*`, `*__ao` and `*__f` binders of
`get`, `contains`, `reverse`, `concat`, `take`, `drop`, `size`, `keys`, `values`,
map lookups, `ListsLib.contains`, `MapLib.member/lookup/delete` and
`Value.assetOf`; and the statement binders `_if`, `_forEach`, `_while`,
`_nested`, `__t` and `_u`. Instance and static `ListsLib` forms were both affected.

Each generated binder is now renamed only when a caller term actually refers to
that name, so evaluation order is unchanged. Scripts without such a reference
keep byte-identical output at every optimization level. Recompiling an affected
script changes its bytes, hash and budget; review it and redeploy if its
behaviour mattered. Deployed scripts never change. The generated names #186 did
not reach, such as the loop and switch binders, are covered by ADR-060 below.

## Upcoming preview: release regression gates and profile freeze (#121)

The proposed pre17 [hash-stability policy](/reference/hash-stability/) freezes
existing optimization profiles, with explicit, reviewed exceptions for correctness
fixes. It distinguishes script-hash changes from network execution-cost changes.

Developer-node gates now support an explicitly configured native Haskell CLI/socket
as well as an existing DevKit container. New Value/Array and G1/G2 MSM regressions
pin script hashes, sizes and execution budgets and check confirmed node spends.
Normal builds run only the offline regressions; node tests remain opt-in.
This test/documentation change does not alter compiler output or deployed scripts.
The documentation build dependencies are also updated to resolve known advisories.

## Upcoming preview: decode boolean and String switch fields (#166)

Switch case-pattern access such as `case Sum s -> s.enabled() ? ONE : ZERO`
now decodes a boolean record field to native Bool. Previously the cached field
remained raw Data and evaluation failed. String fields had the same missing decode.
DataMatch field extraction now reuses the shared decoder used by other field-access
paths rather than maintaining a separate incomplete type table (ADR-051).

This is separate from the loop/switch validation fixes. Constructor encodings,
branch selection, field order and #162's rejection of case-pattern reassignment
are unchanged. Only the selected arm's bound fields are decoded, strictly before
its body; malformed Bool/String fields can now fail there even when unused.
The existing decoder's permissive Bool tag handling is unchanged; this is not a
new strict Data-validation boundary.

Recompiling affected switches changes script bytes, hashes and budgets at **all
optimization levels**, including NONE and BASELINE. Recompile and reassess affected
scripts before deployment. Existing deployed scripts and ledger encodings do not
change; switches without these field types retain their existing decoding.

## Upcoming preview: preserve loop state across enclosing branches (#161)

A loop inside an `if` at method level or inside a switch-expression arm now
preserves its accumulator updates for statements following the branch. Previously
`total = 0; if (condition) { for (...) { total = total.add(x); } } return total;`
could silently return 0. The fix reuses existing continuation lowering with a
shared join lambda for loop-containing branches, including
for while loops, nested branches, multiple accumulators and break. Conditions and
branches keep their evaluation order; early return/yield still short-circuit.
Continuation-bearing branches also rename local and instanceof pattern bindings
internally to protect join arguments when a local shadows a class field updated
by the other branch, and to prevent capture in the existing inline return/yield
lowering. Diagnostics display the original source names.

The validation follow-up also accepts this pattern when the switch expression
is inside an outer loop body. Previously the outer-loop guard conservatively
rejected arm-local guarded-loop updates. It now validates the selector and leaves
arm bodies to their ownership guard and nested loops' own checks. Switch arms
still cannot update enclosing variables, and conditional updates to actual
loop-body locals remain unsupported. This validation-only change adds no lowering
or encoding change for previously accepted programs.

Recompiled sources with loop-containing branches or affected name captures may
change script bytes, hashes,
size and execution budgets at **all optimization levels**, including NONE and
BASELINE. The following code is bound once outside the branches: sequential
guarded loops grow linearly instead of exponentially duplicating their tails.
Join calls add execution overhead; reassess budgets as well as size. Recompile and
reassess affected scripts before deployment; existing deployed scripts do not
change. Public APIs, ledger encoding and the restrictions below remain unchanged.

This was a missed case in the older discarded-result path: #79 added continuations
for returns and #137 extended them to yields. #157 explicitly left this case open.

## Upcoming preview: reject lost conditional loop-local updates (#155)

The compiler now rejects assignments inside an `if`/`else` branch to a variable
declared in the loop body outside that branch. Previously the update could be
silently lost. This applies to for-each and while loops at every optimization
level, including nested and break-aware forms. The diagnostic identifies the
variable and source location, and suggests a conditional initializer, moving
the declaration before the loop (resetting it each iteration if necessary), or
declaring it inside the branch when its value is only needed there.

Switch-expression arms also reject updates to enclosing variables, including
updates made by nested loops to either body locals or outer accumulators, with
or without an enclosing `if`. Declare the accumulator inside the arm and yield
its result instead. This conservative rule also rejects previously correct code
that updated an enclosing variable but consumed the update only via `yield` inside
the arm; move the accumulator declaration into the arm to preserve that behavior.

Reassignment of a switch case-pattern binding is also rejected (#162), even if
the arm only yields the record itself. Previously field projections could read
the original record after reassignment. Copy the binding to a fresh arm-local
accumulator instead. Supported `instanceof` binding reassignment is unchanged.

The separate enclosing-if/loop miscompile is fixed by #161, described above.

This is a conservative restriction, including constant conditions and dead updates.
This validation alone preserves accepted programs' generated bytes; the separate
#161 lowering fix may change them. Existing deployed scripts are
unchanged. See [conditional loop state](/best-practices/conditionals/#conditional-loop-state)
for supported patterns.

## Upcoming preview: independent compiler and evaluation costs (#153)

`pv11-costed` now needs no compiler cost profile. Its list-to-array rule uses
structural eligibility; its transformations and the default `pv11-safe` level
are unchanged. Omitting an optional profile preserves generated script bytes,
hashes and source maps.

The immutable evaluation snapshot is now named `plutus-v3-pv11-costs-v1`, with
neutral Java constants `OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1` (and
`_ID`/`_PARAMETER_HASH`). The old ID remains accepted and the old constants are
deprecated aliases to the same snapshot. The parameters and hash are unchanged.
See the [profile catalog](/reference/cost-model-profiles/) for the exact upstream mapping.

Optional compiler profile settings remain accepted and validated for compatibility,
but no longer appear as dependencies in compilation reports. Reports now also
carry the compiler build version; target, optimization level and applied rules
remain available. Benchmark reports independently retain the model actually
used for evaluation. Runtime callers continue supplying cost parameters through
the VM API or `ProtocolParamsSupplier`; no named profile is required there.

## Upcoming preview: typed BLS12-381 values and multi-scalar multiplication (ADR-047)

BLS12-381 values now have their own types: `JulcG1` and `JulcG2` for points,
`JulcMlResult` for a Miller-loop result (all in `org.julclang.core.types`).
Every `Builtins.bls12_381_*` method and every `BlsLib` method now takes and
returns these types wherever it took or returned a point or a Miller-loop
result as `byte[]`; only `g1Compress`/`g2Compress` produce bytes and only
`g1Uncompress`/`g2Uncompress` consume them (messages, domain separation tags
and scalars keep their `byte[]`/`BigInteger` types). A compressed byte string where a
point is required, a G2 point where a G1 point is required, a point compared
with `==` or placed in a datum, redeemer, record or list is rejected at compile
time (`JULC0041`); a point at a validator or `compileMethod` boundary is
`JULC0042`. Compress at the boundary and uncompress on the other side.

Multi-scalar multiplication is now callable from source on the PV11 target:
`BlsLib.g1MultiScalarMul(JulcScalars, JulcG1Points)` and the G2 form take the
native lists built by `Builtins.scalars(...)`, `Builtins.g1Points(...)`,
`Builtins.g2Points(...)` (literal or runtime elements) or decoded from Data
lists by `Builtins.scalarsFromList(JulcList<BigInteger>)`,
`Builtins.g1PointsFromCompressed(JulcList<byte[]>)` and the G2 form. Every
scalar is validated first (512 bytes of two's complement), the lists are
zipped to the shorter one, and the empty sum is the identity. On the pinned
PV11 costs one MSM call is smaller than the manual `g1ScalarMul`/`g1Add` chain
from three points and cheaper in CPU from seven (about 322 million to enter
plus 25 million per point against 77 million per point); nothing rewrites one
into the other. An element of the wrong type in `scalars(...)`, or a Data list
of the wrong element type in a converter, is `JULC0041`.

**Migration.** A local, helper parameter or return type that named a BLS value
as `byte[]` no longer compiles (`JULC0041`, at the initializer, the helper
call or the `return`): declare it as `JulcG1`/`JulcG2`/`JulcMlResult` or use
`var`. The same check covers the two branches of a conditional (`b ? p : q`
with a G2 `q` where a G1 is required), declarations inside a loop body and
assignments to a loop accumulator or a loop-body local; a native accumulator
must be its loop's only accumulator (a multi-accumulator loop packs its
accumulators as Data). Two loop-body shapes that compiled with their update
silently dropped are handled: an assignment inside a bare nested block now
updates the accumulator, and an assignment in expression position or to an
undeclared variable is rejected. Code that used `var` for BLS values compiles unchanged and keeps its
bytes; the compressed encodings are still `byte[]`. The two
`bls12_381_*_multiScalarMul` signatures changed from `PlutusData` to the typed
lists (the old ones compiled but could not evaluate successfully).
No program in the example corpus or the golden suites uses the BLS surface, so
no existing script changes bytes or hash at any level.

## Upcoming preview: array literals and literal folding (ADR-046)

`JulcArray.of(a, b, ...)` writes a PV11 array down. On-chain it is
`JulcList.of(a, b, ...).toArray()`: the elements are Data-encoded as the
declared element type requires (declare it: `JulcArray<BigInteger> t =
JulcArray.of(...)`) and the list is converted with `ListToArray`, so `get` and
`length` behave exactly as on an array converted from a list.

At `pv11-safe` (the default) and `pv11-costed`, an array whose elements are all
literals (integers, byte strings, strings, booleans, nested list literals)
becomes one UPLC array constant; `length()` on it folds to a constant and
`get(i)` with a literal index folds to the element, decode included
(`JulcArray.of(1, 2, 3).get(1)` is `2`: 42 → 6 bytes, 1,238,594 → 16,100 CPU).
A runtime index keeps the access over the embedded constant (a three-entry fee
table indexed by a runtime tier: 57 → 46 bytes, 1,435,338 → 625,598 CPU on a
valid tier and 809,740 CPU less on an invalid one, which fails exactly as
before). The same folds apply to `list.toArray()`
and `JulcArray.fromList(list)` over a `JulcList.of` literal. An index outside
the array, literal or runtime, fails at `IndexArray` with the same text as
before. Nothing changes at `none`/`baseline`; the optimization report records
`pv11.o10.array-literal-fold`, and the rule can be switched off with
`CompilerOptions.disableOptimizationRule`.

This change is additive for the example corpus and every golden suite: no
program there contains an array constant or converts a list literal to an
array, so those scripts keep their bytes and hashes at every level. A program
of your own that already spells `JulcList.of(...).toArray()` or
`JulcArray.fromList(JulcList.of(...))` does change at `pv11-safe` (it gains the
array constant). A native Value cannot be an array element (`JULC0041`, as for
every Data-backed container). Spell a negative literal element as
`new BigInteger("-5")`. `var t = JulcArray.of(...)` infers the element type from
the elements, as javac does; elements of different types under `var` are rejected
with `JULC0012` (declare `JulcArray<T>`).

## Upcoming preview: native Value literals and literal folding (ADR-045)

`Builtins` gains three native Value literal producers on the PV11 target:
`emptyValue()` (a UPLC Value constant), `singletonValue(policyId, tokenName,
quantity)` and `lovelaceValue(quantity)` (inserts into the empty Value, with
`insertCoin`'s rules: a zero quantity yields the empty Value, a non-zero
quantity needs keys of at most 32 bytes and a quantity in the signed 128-bit
range). They replace the `unValueData(mapData(mkNilPairData()))` idiom for new
code; the idiom keeps working and keeps its bytes. They are intrinsics rather
than `NativeValueLib` methods so that programs which do not call them keep
their bytes at every level.

At `pv11-safe` (the default) and `pv11-costed`, a call of `insertCoin`,
`lookupCoin`, `union`, `contains`, `scale`, `toData` or `fromData` whose arguments
are all literals (constants, or locals bound once to a literal) is folded at
compile time into the Value, integer, boolean or Data it evaluates to, by the
same code the VM runs. A requirement such as one NFT plus two ADA becomes one
constant in the script (40 → 7 bytes and 883,863 → 16,100 CPU for a literal
lookup); the runtime part of a check (`fromData(minted)`, `contains`) stays. A
literal call the builtin would reject (a 33-byte key, an overflow, a negative
quantity under `contains`) is left as written and fails at runtime with the
same text. No algebraic identity is applied and nothing changes at
`none`/`baseline`. The fold only fires when the literal is not larger than the
call it replaces as it stands in the script, so `toData(Builtins.emptyValue())`
stays a call and a literal local shared by several calls is never copied into
them (the calls stay, the local keeps its one constant). The optimization report
records `pv11.o14.value-literal-fold`, and the rule can be switched off with
`CompilerOptions.disableOptimizationRule`.

This change is additive: no program compiled before it contains a Value
literal, so existing scripts keep their bytes and hashes at every level.
Spell a negative literal quantity as `new BigInteger("-5")`
(`BigInteger.valueOf(-5)` compiles to a runtime subtraction).

## Upcoming preview: automatic sharing of repeated record field projections (ADR-044)

`PV11_SAFE` (the default) and `PV11_COSTED` now share a repeated projection of
the same record field of the same variable (`txInfo.outputs()` on a `TxInfo`
local or parameter, `b.amount()` on a cast local, `out.value()` on a loop item)
once per scope when that projection is already the first non-trivial
evaluation of the scope, and share the record's decoded fields list when two
or more different fields of one variable are projected. The compiler binds the
projection (or the fields list) as one strict `let` and reuses it at every
later occurrence, including occurrences inside a loop body and inside
`compareTo`, which evaluates its receiver twice on its own. A projection of a
projection (`b.inner().x()` and `b.inner().y()`) shares the inner record first.
The output for the motivating shape is byte-identical to writing
`BigInteger amount = b.amount();` yourself. The optimization report records
`pv11.o15.projection-sharing`.

Nothing observable on-chain changes: the shared projection already ran first on
every path, so results, traces, the failure point and the failure text are
identical on Java, Truffle and Scalus for well-formed and malformed input, at
every level. Budgets move, on failing paths too (a failing path that reaches
only the leading site pays the binding, one that fails inside the shared unit
pays a few machine steps less), so off-chain tests that pin a budget need
refreshing. A path that reaches a later occurrence saves the whole projection (about
462,000 CPU for an integer field at the first position, 1,058,000 CPU for a raw
field at the sixth, 279,000 CPU for the fields list); a path that reaches none,
such as an untaken branch or an empty loop, pays at most one lambda, one
application and one variable lookup (48,000 CPU) per shared binding. A
projection is never hoisted above a trace, an `error`, a saturated call or a
projection of another variable, and projections in exclusive branches of one
conditional are left exactly as written. A validator that projects
`ctx.txInfo().outputs()` twice drops from 429 to 380 bytes and from 11.6 million
to 8.8 million CPU on the path that uses both.

Earlier safe-profile rules (Case on booleans, pairs and integers, list Case
loops) already moved most `pv11-safe` hashes; this is the first whose trigger
shape is in most validators since the safe profile became the default: any
program that projects one field of one variable twice in leading position, or
two fields of one variable, compiles to a different (smaller) script. Deployed scripts are unaffected; recompiling changes the
script hash and therefore the script address. Tests or deployments that pin a
hash need to be refreshed. `NONE`/`BASELINE` retain historical bytes.

One costed-only consequence: because the shared list projection is a variable,
`pv11-costed` now also promotes `b.items().get(0)` beside `b.items().get(1)`
to a PV11 array (ADR-043), with ADR-043's `IndexArray` failure text for an
out-of-range index on such sites.

Each PIR-to-PIR rule can be switched off on its own for review and measurement:
`new CompilerOptions().disableOptimizationRule("pv11.o15.projection-sharing")`
(also `pv11.o8.value-sharing` and `pv11.o9.list-to-array`); any other id fails
with `JULC0043`. The optimization level remains the supported rollout control,
and the Gradle plugin and CLI do not expose the switch.

## Upcoming preview: list-to-array promotion at `pv11-costed` (ADR-043)

`PV11_COSTED` (opt-in; `julc { optimization = 'pv11-costed' }`, no cost profile
required) now converts a `JulcList` variable that its scope indexes at two
or more `get` sites, or at a `get` site inside a loop body, to a PV11 array once
(`ListToArray`) and rewrites those sites to `IndexArray`. Every other use of the
list (for-each, `size`, `head`, passing it to a helper) is untouched, the array
binding is placed at the innermost sub-term that contains every index site (so a
path on which the binding is not placed, such as a branch whose sibling holds all
the sites, pays nothing), and for two sites in one expression the output is
byte-identical to writing `list.toArray()` by hand at that point. The
optimization report records `pv11.o9.list-to-array`. `PV11_SAFE` (the default),
`BASELINE` and `NONE` keep their bytes and hashes.

The recursive `get` costs about 620,000 CPU plus 683,000 CPU per index step and
60 FLAT bytes per site; the array form costs 49,000 + 24,838 CPU per element
once plus 312,000 CPU per site. Two sites at indexes 0 and 1 save CPU for lists
of up to 44 elements, sites at indexes 3 and 7 for up to 291, and loops
multiply the saving: a WingRiders-shaped request loop with sixteen requests
drops from 349.6 million to 81.9 million CPU. A path that converts but then
indexes less than twice pays at most the conversion plus one binding
(97,000 + 24,838·n CPU per list): an empty loop, or an untaken branch when both
branches index. The compiler assumes a promoted list is indexed at least twice
per evaluation; this is why the rule is confined to the costed profile.

Failure contract (costed profile only): a promoted `get` with an index outside
`0..length-1` now fails at the `IndexArray` builtin instead of inside the
recursive traversal. Both fail after the index is evaluated and before any other
effect, so results, traces and the failure point are unchanged; only the
off-chain text and the failing path's budget (smaller) differ. Because the typed
boundary does not range-check integers, a redeemer- or datum-supplied index
reaches `get` unchecked and a validator compiled at `pv11-costed` itself
reports `IndexArray: index I out of bounds for array of size N` (Scalus:
`… indexArray: index I out of bounds for array of length N`) instead of
`HeadList: empty list` (index equal to the length) or `TailList: empty list`
(negative or larger). You see it wherever the costed artifact is evaluated: the
Gradle plugin or annotation processor with `julc.optimization=pv11-costed`,
`julc build --optimization pv11-costed` followed by `julc eval`, direct
`JulcCompiler` use, and the MCP tools. The testkit's `JulcEval`,
`MethodEvaluator` and `ValidatorTest` compile at the default level and never
show it. On-chain a failure is a failure. Tests that assert on that text under
the costed profile need updating; nothing changes at the default level.
`MultiIndexArray` remains illegal at PV11.

A `JulcList` variable is promoted only when the compiler can prove it holds a
list on every path: a value decoded at the typed boundary, a list built by a list
operation, a helper parameter that every call in the program passes such a list,
or a helper result whose body returns one. A local bound to an unchecked cast
such as `(JulcList<T>) (Object) somePlutusData`, every alias of it, a callback
lambda's list-typed parameter (which holds the raw element), and any helper
parameter or result such a value can reach are never promoted, so a program that
never indexes on some path keeps accepting on that path at the costed level. An
earlier preview build trusted helper parameters by their declared type; review
showed that a callback's element passed into a helper could then turn acceptance
into a `ListToArray` failure. That is fixed, and no released artifact was built
at the costed level.

## Upcoming preview: automatic sharing of repeated native Value conversions (ADR-042)

`PV11_SAFE` (the default) and `PV11_COSTED` now share a repeated native Value
conversion of the same variable, `NativeValueLib.fromData(x)` or
`Builtins.unValueData(x)`, once per scope when that conversion is already the
first non-trivial evaluation of the scope. The compiler binds it as one strict
`let` and reuses the bound value at every later occurrence, including
occurrences inside a loop body, which hoists a per-iteration conversion out of
the loop. The output for the motivating shape is byte-identical to writing the
`JulcValue` binding by hand. The optimization report records
`pv11.o8.value-sharing`.

Nothing observable changes: the conversion that is shared already ran first on
every path, so results, traces, the failure point and the failure text are
identical on Java, Truffle and Scalus for valid, malformed and non-canonical
input. A path that reaches a second occurrence saves one full conversion
(about 513,000 CPU for even a one-token Value, more for larger Values); a path
that reaches none, such as an untaken branch or an empty loop, pays at most one
lambda, one application and one variable lookup (48,000 CPU). A conversion is
never hoisted above a trace, an `error`, a saturated call or another partial
conversion, and conversions in exclusive branches of one conditional are left
exactly as written; binding `JulcValue` explicitly remains the recommended style
and applies at every level.

Only safe-profile output of programs that convert one `Data` variable at least
twice changes bytes and hash; no shipped example uses native Values, and
`ValuesLib` is untouched. `NONE`/`BASELINE` retain historical bytes. `ValueData`
sinking, adjacent conversion cancellation and Data-side (`ValuesLib`) sharing
remain deferred (ADR-032 O8/O15).

## Upcoming preview: integer Case dispatch for sealed-interface switches (ADR-041)

`PV11_SAFE` (the default) and `PV11_COSTED` now dispatch every `switch` on a
sealed interface with two or more constructors through one PV11 integer `Case`
on the decoded constructor tag instead of an `equalsInteger` chain. Branch `i`
runs for tag `i`; field decoding, branch bodies, traces and evaluation order are
unchanged, and only the selected branch is evaluated. The dispatch cost is now
flat instead of growing with the constructor's position: in the ADR-041
fixtures a five-way switch selecting its last constructor saves about 710,000
CPU and 33 FLAT bytes, and a two-way switch saves 116,000 to 265,000 CPU and 12
bytes (8 bytes on the ADR-038 on-chain validator). Single-constructor
switches and `instanceof` chains are untouched. The optimization report records
`pv11.o5.case-integer`.

Failure contract: a constructor tag outside `0..n-1` now fails at Case selection
instead of reaching the chain's terminal `error`. Both fail at the same point
with no branch effects and no traces; only the off-chain failure text and the
failing path's budget differ. The strict typed boundary already rejects invalid
tags on datum, redeemer and nested typed fields before user logic runs, so
validators observe no change. The change is observable where no boundary runs:
`JulcCompiler.compileMethod`, and therefore the testkit's `JulcEval` and
`MethodEvaluator`, now report `Case: tag T out of range for N branches` (Scalus:
`Case index T out of bounds for N branches`) for a sealed argument with an
out-of-range constructor tag instead of `Error term encountered`; the same
applies to direct-PIR consumers of `compilePirToProgram` and to sealed values a
program casts from raw `PlutusData` without a typed boundary. Tests that assert
on that failure text need updating. Both the Java and Truffle VMs now
range-check integer Case scrutinees over the full integer domain before
narrowing, so tags beyond the `int` range fail as the same machine error.

Recompiling an eligible validator with a safe profile changes its script bytes
and hash. `NONE`/`BASELINE` retain historical bytes; deployed scripts and ledger
Data encodings are unchanged. The decompiler recovers the new dispatch as the
same `DataMatch` HIR node. ADR-041 also closes the O6 unit Case question as
rejected: JuLC emits no `ChooseUnit`, and a census of shipped artifacts shows no
typed-unit statement population worth a new PIR surface.

## Upcoming preview: conditional `yield` in switch case blocks

A `yield` inside an `if`/`else` branch of a switch-expression case block was
silently discarded when statements followed the conditional: the block lowered
as "evaluate the `if`, then run the rest", so a guard such as
`if (cond) { yield false; } yield true;` always produced `true`
([#137](https://github.com/bloxbean/julc/issues/137)). The frontend now applies
the same fall-through continuation lowering that #79 introduced for early
`return`, so a `yield` exits its owning switch expression and the trailing
statements only run for branches that fall through. A `yield` inside a nested
switch expression still belongs to that inner switch.

Two diagnostics accompany the fix: a case block whose paths do not all end in a
`yield` is rejected instead of falling off the end, and a `yield` inside a
for-each or while loop body is rejected like `return` already was.

This is frontend statement lowering, so recompiling a source that uses the
affected shape changes its script bytes and hash under every profile, including
`NONE` and `BASELINE`. Sources that do not use the shape keep their bytes.
Deployed scripts and ledger Data encodings are unchanged. The shipped stdlib and
examples were scanned and do not use the shape. The two Blaster controlled-mint
verification fixtures do; their locked artifacts and counterexample binding are
refreshed for the corrected lowering with the same property outcomes.

## Upcoming preview: `serialiseData` compiled without a force wrapper

`Builtins.serialiseData` and `ByteStringLib.serialiseData` previously compiled
to a forced `SerialiseData` builtin. `SerialiseData` is monomorphic and takes
its Data argument directly, so the extra force made every generated program
that reached the call fail at evaluation on the Java, Truffle and Scalus
backends ([#132](https://github.com/bloxbean/julc/issues/132)). The compiler
now emits the builtin without the type-instantiation force.

Recompiling a program that calls `serialiseData` changes its script bytes and
hash under every profile; programs that do not call it keep their bytes. The
CBOR serialization format, VM behavior and ledger Data encodings are unchanged,
and already deployed scripts are not modified. Any execution path that reached
the old forced builtin failed, so the new hash only replaces scripts that could
not have succeeded on that path.

Two follow-ups guard against regression: an exhaustive comparison of all 102
`DefaultFun` force counts against the shared builtin metadata (PR #134), and a
Java-source regression matrix for `serialiseData` under `BASELINE` and
`PV11_SAFE` on Java and Scalus with exact CBOR and BLAKE2b-256 vectors
(PR #135).

## Upcoming preview: lossless Data map decoding

CBOR decoding now preserves Plutus Data map entry order and duplicate keys,
including nested maps and Data constants read from serialized FLAT programs.
Previously, the decoder could collapse `{1:10,1:20}` to `{1:20}` before evaluation.
Encoding and compiler lowering are unchanged. Re-encoding affected decoded data
now retains entries that older versions lost; stored scripts are not modified.

Incomplete map pairs, misplaced map breaks and impossible declared map lengths
are rejected. This is a focused decoder correction, not a general strict-CBOR or
resource-limits change. Converting an already deduplicated external cbor-java
`Map` cannot recover its missing entries; use `PlutusDataCborDecoder.decode` when
starting from bytes. See ADR-037 for design and validation scope.

## Upcoming preview: typed native Pair Case lowering

`PV11_SAFE` (the default) and `PV11_COSTED` now destructure proven native pairs
from strict constructor-boundary checks with a single PV11 `Case`. The compiler
requires both tag/fields projections of the same once-bound `UnConstrData`
result; it leaves field decoding and failure/trace order unchanged. Arbitrary
pair values, aliases, map traversal and Data-encoded tuples are outside this rule.
The optimization report records `pv11.o4.case-pair`.

Recompiling an eligible validator with a safe profile changes its script bytes
and hash. NONE/BASELINE retain historical bytes; deployed scripts and ledger
Data encodings are unchanged. The bounded rule has passed independent correctness
review; JuLC remains experimental. `compilePirToProgram` also applies this rule
in safe profiles, so eligible caller-supplied PIR can produce new bytes/hashes.
ADR-038 extends the same rule to sealed-interface switch decomposition during
UPLC lowering. This preserves the integer tag-dispatch chain and selected-field
decoding, including errors in unused fields. The measured switch fixtures save
10 bytes per site compared with the previous safe output; NONE/BASELINE remain
byte-identical. Direct PIR DataMatch callers also receive this safe-profile
change. The switch extension passed its own independent correctness review in
PR #129. The decompiler follow-up [#130](https://github.com/bloxbean/julc/issues/130)
recovers native pair and verified legacy constructor decomposition as explicit
HIR `DataMatch`, preserving raw fields, ordered integer tag tests and the final
fallback. Readable output uses decomposition and if/else dispatch; it does not
invent Java record schemas. FLAT-erased bindings are recovered from de Bruijn
indices. External exhaustive HIR visitors must handle the new `DataMatch` node;
the compiler's output bytes and costs are unaffected by this decompiler change.

## Upcoming preview: stable typed formal-verification API v1

JuLC now provides a stable construction API for exact-artifact formal
verification. API version 1 uses canonical property schema 1 and supports
typed composition over compiler-projected contract data, four standard Plutus
purposes (spending, minting, rewarding, and certifying), transaction context,
authorization, certificates, multi-asset values, governance data, and reviewed
raw-data adapters.

`julc verify dsl-init` emits only schema 1. The unreleased E.2–E.4 milestone
formats previously numbered 1 through 10 have been removed from public
generation and replay rather than carried as compatibility aliases. Regenerate
those experimental workspaces with the current CLI. Historical certificates
remain hash-bound records of their original runs, but they are not accepted as
current replay inputs. New semantic vocabulary requires a new schema rather
than changing schema-1 meanings.

`@RequiresSigner`, `@ControlledMint`, and the complete
`@RequiresSigner + @PreservesValue + @Monotonic` stateful profile now lower to
the same canonical DSL IR used by explicit Java specifications. The former
annotation-specific duplicate Lean security formulas have been removed. These
annotations remain UPLC-neutral: they state properties and do not add checks to
validator execution.

The stable API covers documented Java construction and canonical property
semantics. It does not promise solver termination, complete CardanoLedgerApi
coverage, general compiler correctness, or that a verified validator is safe
under every unspecified property. Voting/proposing validator selection,
parameter-derived authorities, arbitrary Lean in the DSL, and temporal
verification are not included. The E.5 bounded temporal experiment did not
meet its solver-calibration gate and was not promoted.

See the [formal verification guide](/guides/formal-verification/) for annotation
and DSL workflows, local/Docker backends, exact certificate scope, outcome
classification, and CI guidance.

## Upcoming preview: explicit PV11 compiler target

JuLC compilation now resolves one explicit, immutable compiler profile:
`plutus-v3-pv11-uplc-1.1.0`. The profile covers the Plutus language, ledger
protocol version, and UPLC version together. Existing compile entry points
select this named profile by default; unknown profiles, future protocol
versions, and a `latest` alias are rejected rather than interpreted as PV11.

`CompileResult` now has a public `target()` record component containing this
provenance. Compatibility constructors preserve existing constructor call
sites by assigning the documented PV11 target, but code that inspects the
record shape, uses record patterns, or depends on generated record equality or
string representation must account for the additional component. Compiler
target plumbing and validation alone do not change generated UPLC, FLAT bytes,
or script hashes.

Testkit operations that retain a `CompileResult` now pass its exact ledger
target to the selected VM. With the Java VM, this means source-based validator
and method evaluation uses explicit PV11 semantics and costs instead of the
legacy language-only path, which defaults to PV10 when no protocol-aware cost
model is configured. A caller may also provide an explicit evaluation target;
it must match `CompileResult.target()` or evaluation fails before VM execution.

The Scalus adapter now implements JuLC's explicit-target VM SPI. Scalus 1.1.0
has no certified target in JuLC, so `ValidatorTest.evaluate(result, ...)` with
Scalus as the only provider throws `UnsupportedOperationException` at the
backend-capability gate. This prevents a testkit rejection assertion from
mistaking an unevaluated profile for a rejected script. For a Scalus
compatibility cross-check, evaluate `result.program()` through the language-only overload.
The Java VM supports both forms, although the raw `Program` form cannot carry
compiler-target provenance. See
[ADR-033's certification evidence](https://github.com/bloxbean/julc/blob/main/adr/033-scalus-protocol-aware-ledger-target-evaluation.md#certification-evidence).

The CLI, Gradle plugin, annotation processor, JRL compiler, and MCP tools accept
and report the same stable profile ID. Supporting a later protocol version will
add and verify a separate pinned profile; it will not silently change the PV11
default.

## Upcoming preview: Scalus protocol-aware evaluation (ADR-033)

The Scalus adapter now has atomic per-language configurations, target-bound
V1/V2/V3 VM construction, and one validated explicit-target pipeline. Public
explicit-target calls remain fail-closed because six reason-coded upstream
Scalus 1.1.0 divergences prevent V3/PV10 and V3/PV11 certification.

Cardano Client Lib continues to supply current protocol-parameter cost arrays
for V1, V2, and V3. Configured Scalus language-only evaluation now passes all
three arrays to `MachineParams.fromCostModels` with the matching language and
protocol. Compared with `main`, this changes configured V1/V2 transaction
evaluation from Scalus's bundled mainnet defaults to the current live model.
Scalus consumes the mapped live V1/V2 prices; its 1.1.0 V1/V2
adapters still reference-fill PV11-only builtin costs and ignore audited
Constr/Case positions, so that compatibility behavior is not a complete
ledger-parity claim. Pinned conformance vectors are test evidence only and are
never substituted for runtime protocol parameters.

A non-null `ExBudget` is now enforced; exceeding it returns
`EvalResult.BudgetExhausted`. Array and Value results now cross the Scalus
bridge. Unconfigured language-only V3 retains Scalus 1.1.0's PV11/E bundled
default, which differs from Java/Truffle's PV10 compatibility default. These VM
changes do not modify compiled UPLC, FLAT bytes, or script hashes.

## Upcoming preview: strict typed datum/redeemer boundaries

Typed validator boundaries now reject non-canonical `Data` before user code
runs. Records and variants require exact constructor tags and arities;
primitive, optional, list, map, nested, and productive recursive fields are
checked eagerly. Explicit `PlutusData` roots remain raw.

Typed, non-record entrypoint arguments now reach validator code in their
declared representation. In older compiler versions, a root declared as
`BigInteger`, `byte[]`, `String`, `boolean`, `List`, or `Map` could remain raw
`Data` at runtime even though the PIR type said otherwise. Strict-boundary
lowering fixes that compiler bug by decoding the root before invocation. Remove
workarounds such as an explicit `Builtins.unIData(redeemer)`, `unBData`,
`asList`, or `asMap` when the entrypoint argument is already declared with the
corresponding typed Java form. This change concerns transaction-time entrypoint
arguments; deployer-applied `@Param` fields are a separate pipeline.

A concrete record that implements a sealed interface cannot currently be used
as an independent typed datum/redeemer root or nested boundary field. Declare
the sealed interface instead. JuLC now rejects the concrete form at its Java
parameter because constructor lowering uses the variant's sum tag while the
record schema does not yet carry that nominal tag.

This is an intentional preview-language breaking change. Recompiling an
affected validator changes its UPLC and script hash. There is no permissive
mode: retain the old compiler to reproduce an old artifact. Before moving state
to a new script address, decode and canonically re-encode existing datums so
wrong-tag or trailing-field values accepted by a permissive-era script are not
carried to the strict address.

Strict traversal has a real execution cost. In the activation measurements, a
250-element `List<BigInteger>` guard used approximately 502 million CPU units
(about 2 million per element and about 5% of the 10-billion transaction
ceiling), compared with approximately 0.9 million for the legacy no-op path.
Measure production maximum-size inputs; use an explicitly raw `PlutusData`
boundary with a reviewed manual check when full traversal is unsuitable.

Blueprint compiler identities include `+boundary.strict-data-v1`, and formal
verification certificates record `boundarySemantics: strict-data-v1`. See the
[strict data boundary guide](../guides/strict-data-boundaries/) for the exact
coverage, cost guidance, raw-data escape, and migration checklist.

## Upcoming preview: purpose-indexed multi-validator blueprints

An explicit `@MultiValidator` with supported `SPEND`, `MINT`, `WITHDRAW`, or
`CERTIFY` entrypoints now publish one standard CIP-57 validator entry per
purpose. The titles are purpose-qualified—for example `Protocol.spend` and
`Protocol.mint`—while every entry retains byte-identical compiled code and the
same Cardano script hash because they describe one deployed script.

Artifact consumers must migrate from the unsuffixed multi-validator title to
the exact purpose-qualified entry. `julc verify` and `julc verify init` instead
take the base Java title plus `--purpose`. Normal single-purpose titles and
script bytes are unchanged. Every newly generated blueprint now records an
explicit purpose on its datum, redeemer, and parameter arguments, so its JSON
changes even for an ordinary single-purpose validator.

`CERTIFY` is emitted as CIP-57 `publish`, following Aiken's mapping of the
ledger certificate purpose; the JuLC source-level name remains `CERTIFY`.
Manual dispatch remains fail-closed. Single-purpose and multi-purpose `VOTE`
or `PROPOSE` validators now also fail strict blueprint generation: older
previews emitted incomplete purpose-free metadata for these single-purpose
validators, but the pinned CIP-57 vocabulary cannot name them truthfully. Use
the blueprint opt-out to compile without metadata when needed. See the
[purpose-indexed blueprint guide](../guides/purpose-indexed-blueprints/) for
examples and limitations.

## Upcoming preview: typed List-case lowering (#110)

`pv11-safe` (the default) and `pv11-costed` now use PV11 `Case List` for
eligible compiler-generated `for-each` loops. The compiler binds the raw head
and tail together and keeps element decoding at its original point. Nested
loops, records, accumulators, conditional `break`, and recursive methods retain
their source behavior.

The existing `NullList` guard remains: it preserves failures for unchecked casts
that carry a non-list value. Loops with an unconditional break and no tail use
retain their prior projection form to avoid increasing script size. Map/native
pair iteration, arbitrary `while` traversals and HOF builders are outside this
rule's scope.

`none` and `baseline` preserve their prior bytes, checked against fixtures from
commit `8c9f1f63`, including source-map compilation. This does not add a PV10
compiler target. Recompilation at `pv11-safe` can change script bytes and hashes;
existing deployed scripts are unaffected. Use `baseline` when reproducing
historical lowering, or retain the prior compiler version for exact reproduction
of its `pv11-safe` artifacts.

The new `PirTerm.ListMatch` variant is an internal compiler IR extension;
consumers with exhaustive switches over PIR must handle it. Java validator APIs
and ledger Data encodings are unchanged. See
[ADR-034](https://github.com/bloxbean/julc/blob/main/adr/034-typed-list-case-lowering.md)
and its [measured evidence](https://github.com/bloxbean/julc/blob/main/adr/evidence/034-list-case-measurements.md)
for scope, failure analysis, pinned budgets and hash migration.
The [on-chain validation](https://github.com/bloxbean/julc/blob/main/adr/evidence/034-list-case-onchain.md)
adds confirmed PV11 spending transactions, direct Haskell/Java budget agreement,
and malformed-input checks. This completes the bounded rule's node evidence gate
without enabling additional traversal families.

## Upcoming preview: target-aware PV11 optimizations

ADR-032 adds an explicit optimizer rollout boundary without adding another
compiler target. The legal output target remains
`plutus-v3-pv11-uplc-1.1.0`. After the initial opt-in review window and full
semantic, benchmark, repository, and hosted-verification gates, `pv11-safe`
is now the default and enables these reviewed rules:

- `JulcList.drop(...)` lowers directly to PV11 `DropList`;
- typed boolean conditionals lower to UPLC 1.1.0 `Case Bool`;
- successful all-literal `ExpModInteger` calls fold at compile time while
  invalid literal calls retain their exact runtime failure.

The experimental native Value surface now uses a distinct `JulcValue` type.
Conversion from and to ledger `Data` is explicit, and the compiler rejects
native/Data mixing at assignments, equality, Data-backed containers/records,
and external method or validator boundaries. These checks apply at every
optimization level, including `baseline`. This type correction adds no UPLC
cost by itself and does not automatically rewrite existing Data-encoded ledger
Value operations.

The Scalus backend is pinned to the stable 1.1.0 release. Focused PV11
cross-checks now cover `Case Bool` false/true branch order and confirm that an
unselected failing branch is not evaluated. Scalus remains a language-only
compatibility backend in JuLC; protocol/cost provenance still belongs to the
canonical target-aware evaluation path. Cross-backend budget tests therefore
configure one explicit cost profile on both VMs rather than treating Scalus's
version-dependent built-in default as ledger evidence.

Measurements use the immutable
`plutus-v3-pv11-costs-v1` cost profile (parameter SHA-256
`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).
Java and Truffle produced identical results, failures, traces, and ledger
budgets for these fixtures:

| Fixture | FLAT bytes | CPU | Memory |
|---|---:|---:|---:|
| one-element `drop` path | 162 → 109 | 3,886,301 → 2,311,844 | 17,931 → 10,864 |
| three-condition Bool fixture | 134 → 117 | 2,251,555 → 1,687,408 | 9,694 → 7,591 |
| three literal ExpMod calls | 42 → 6 | 3,406,498 → 16,100 | 3,407 → 200 |
| aggregate validator-like accepted path | 169 → 88 | 5,346,583 → 1,935,936 | 19,605 → 8,533 |

The aggregate fixture's script hash changes from
`3d1e9ac3561e68d3d0864705686adca6105ff5fc15f5a43e904de1f2` to
`2f2ea7f79a5dd083ed51e573dc247267ae7cb978ecc615692479c594`.
These are exact fixture results rather than general percentage guarantees.
Default recompilation now produces a new deployment artifact; it does not
change an already deployed script. Select `baseline` explicitly when
reproducing the earlier ADR-031 lowering and hash.

Raw `JulcVm` language-only evaluation overloads retain the deliberate PV10
compatibility default from ADR-030. Because default compiler output may now
contain PV11-only `Case Bool` or `DropList`, evaluate a `CompileResult` through
its `result.target().ledgerTarget()`. JuLC testkit and CLI evaluation propagate
that target automatically; MCP test/evaluate and the playground do the same.
The decompiler also recovers the PV11 `Case Bool` form as a Java conditional
only when its untyped UPLC scrutinee is provably Boolean, leaving ambiguous SOP
cases as switches.

Array promotion/folding, native Value algebra, BLS fusion, broader list traversal
rewrites beyond #110, and general conversion sharing remain explicitly deferred
(pair Case rewrites were later delivered by ADR-036/038, integer Case dispatch
by ADR-041, unit Case sequencing was rejected by ADR-041, and strict-prefix
native Value conversion sharing was delivered by ADR-042):
the current compiler lacks the typed literal, representation, or use-analysis
proof needed to preserve failures and strict evaluation. A future protocol
target starts with ADR-032 rules disabled and enables each rule only after its
legality and semantics are revalidated; cost-directed rules additionally need
a new pinned cost profile.

## Upcoming release: PV11 builtin contract correction

JuLC's current compiler targets the Plutus V3/PV11 feature set. This contract
was verified against Plutus 1.63.0.0
(`f92b7d7d82622a26caf456a6be33859f697e2cfc`), as shipped by cardano-node 11.0.1.

The released PV11 Batch 6 is exactly:

| Tags | Builtins | Specification |
|---|---|---|
| 87 | `ExpModInteger` | CIP-109 |
| 88 | `DropList` | CIP-132 |
| 89-91 | `LengthOfArray`, `ListToArray`, `IndexArray` | CIP-138 |
| 92-93 | BLS12-381 G1/G2 multi-scalar multiplication | CIP-133 |
| 94-100 | Native MaryEraValue operations | CIP-153 |

### `multiIndexArray` migration warning

Earlier JuLC previews exposed `Builtins.multiIndexArray` as if it were a PV11
builtin and could generate FLAT tag 101. Tag 101 is the future CIP-156 operation;
it is not part of PV11, and scripts containing it are not valid for that target.

The current compiler now rejects `multiIndexArray` with a compile-time diagnostic.
Recompile affected scripts and replace the call with repeated `IndexArray`
operations (for typed arrays, repeated `array.get(index)` calls). There is no
automatic bytecode migration for an already-generated tag-101 script.

The tag remains in JuLC's AST/FLAT support and Java VM for forward-development
experiments only. That experimental implementation does not make it ledger-valid
for PV11. It also retains JuLC's legacy `(array, indices)` argument order rather
than CIP-156's proposed indices-first signature, so it is not a conformant preview
of the future builtin.
