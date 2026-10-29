# ADR-062: JVM–UPLC semantic consistency programme

**Status:** Accepted (2026-10-28; umbrella; design only). Each step below needs its own ADR before implementation.
**Date:** 2026-10-23
**Label:** `semantic-consistency` (this ADR, its sub-ADRs, and their implementation PRs and issues)
**Relates to:** [ADR-061](https://github.com/bloxbean/julc/pull/203) (control-flow correctness and semantic
validation, proposed in PR #203), [ADR-048](048-loop-conditional-local-updates.md),
[ADR-050](050-if-loop-continuations.md), [ADR-056](056-language-neutral-compiler-backend.md),
[ADR-058](058-playground-java-source-debugger.md), [ADR-060](060-generated-binder-namespace.md)

## Context and problem

A JuLC contract is Java source, and that source runs in two ways:

1. **As UPLC.** JuLC compiles it, and the result is evaluated by a VM. This is what goes on-chain.
2. **On the JVM.** javac compiles it, and it runs against the off-chain implementations of `julc-stdlib`,
   `julc-ledger-api` and `julc-core`. The testing guide recommends this for debugging with IDE breakpoints
   ("Direct Java calls", `docs/src/content/docs/guides/testing-guide.md` §11) and for testing stdlib calls
   "directly as Java ... without compiling to UPLC" (the same guide, "Direct Java Tests for Stdlib").

Users reasonably expect the two to agree. They often don't, and the disagreements come from two independent
sources:

- **The lowering diverges from Java's meaning.** Control flow is the visible case. ADR-048 added guards that
  reject updates the loop lowering cannot preserve; ADR-050 added shared continuations for one branch/loop
  interaction; ADR-061 records shared-field state lost across calls and callbacks. Each fix covered the
  scenarios someone thought of. Correctness still lives in several pattern-specific lowering paths
  (`LoopBodyGenerator` has separate single-accumulator, multi-accumulator and break-aware generators).
- **The off-chain implementations diverge from on-chain semantics.** They are a second, hand-written
  implementation of the same functions. Some are missing, some only work because of an on-chain
  representation, and some compare values differently.

Fixing these one scenario at a time has not converged, because the number of combinations grows faster than
anyone can enumerate them. This ADR proposes a programme to make the two executions agree by construction
where possible and to find the remaining disagreements mechanically. It indexes the sub-ADRs and keeps the
catalogue of known divergences.

## Goals and non-goals

**Goals**

- Make UPLC evaluation the semantics of record, and treat the JVM execution as a checked convenience.
- Find JVM/UPLC disagreements with generated programs and inputs instead of hand-picked scenarios.
- Give each builtin a single implementation of its semantics that both executions use.
- For every divergence in the catalogue, decide one of: make the two agree, reject the construct at compile
  time with an actionable diagnostic, or document it as an allowed divergence that tests account for
  explicitly.
- Sequence the work so that evidence (the differential harness) exists before any lowering is rewritten.

**Non-goals**

- Full Java semantics. JuLC stays a subset; constructs outside it are rejected.
- Replacing ADR-061. ADR-061 remains the governing design for control flow and the mutation policy. This
  programme supplies its evidence infrastructure (step S1) and schedules its migration (step S4).
- Changing the shared PIR backend or its contract (ADR-056, ADR-059).
- Claiming production safety. Agreement on generated tests is evidence, not proof.

## Catalogue of divergences

Each entry has a stable ID. "Verified" means confirmed by reading the code at `ebaf028c` (origin/main on
2026-10-23); the cited location is the evidence. "Reproduced" means a saved `julc-difftest` case (ADR-063
M1) shows the divergence at every optimization level. "To confirm" is a hypothesis for the harness to
reproduce or refute. "Fixed for #N" means the change that closes issue #N makes JuLC agree with javac or
reject the shape, and the entry's saved case now expects `AGREE`. New entries take the next free ID and
are never renumbered.

| ID | Area | JVM behaviour | UPLC behaviour | Status | Owner |
|---|---|---|---|---|---|
| T1 | Loop and branch state | Every update is visible after its join | Pattern-specific lowering; known failures #155 (local updated in an `if` lost) and #161 (exponential size) led to ADR-048/050 guards | Verified (history) | S4 / ADR-061 |
| T2 | Shared field state across calls, callbacks and recursion | Field updates persist within one evaluation | Updates bound lexically per invocation; ADR-061 table shows `h(3) + h(3)` = 9 on the JVM and 6 in JuLC | Contained on `main`: the probe is now rejected with `JULC0056` (ADR-060), reproduced as a negative case | S4 / ADR-061 |
| T3 | `==` on `BigInteger` | Reference comparison; equal values are `==` only when the same object (`BigInteger.valueOf` caches small values) | `EqualsInteger`, structural (`PirGenerator.generateEquality`) | Reproduced | S5 |
| T4 | `==` on records, sealed types and Data | Reference comparison | `EqualsData`, structural (`PirGenerator.generateEquality`) | Verified | S5 |
| T5 | `==` on `byte[]`, and `equals` of user records with `byte[]` components | Reference comparison; a record's generated `equals` compares array components by reference | `EqualsByteString` and `EqualsData`, structural. Ledger types such as `PubKeyHash` override `equals` with `Arrays.equals`, so they agree | Reproduced (both forms) | S5 |
| T6 | `int` and `long` | 32- and 64-bit two's-complement overflow | Unbounded integers (`TypeResolver` maps `INT`, `LONG` to `IntegerType`) | Reproduced | S5 |
| T7 | `any` and `all` short-circuiting | `JulcArrayList.any` returns at the first match | `PirHofBuilders.any` is a `foldl` over the whole list, so the predicate runs on every element. A later element that fails or traces makes the results differ, not just the cost | Reproduced (JVM true, UPLC fails) | S5 |
| T8 | Builtins missing off-chain | 40 builtin methods in `Builtins.java` throw `UnsupportedOperationException`: BLS12-381 (including MSM and the native point and scalar lists), the array builtins (`dropList`, `listToArray`, `lengthOfArray`, `indexArray`, `multiIndexArray`) and the native Value builtins (`insertCoin` … `lovelaceValue`) | Implemented by the VM | Verified | S2 |
| T9 | On-chain-only casts | `(byte[])(Object) pk.hash()` in `AddressLib.credentialHash` throws `ClassCastException`, because `hash()` is a `PubKeyHash` record. The same `(T)(Object)` pattern appears in `ContextsLib`, `MapLib` and `ValuesLib` | A no-op: both are the same representation | Reproduced (AddressLib); to confirm (others) | S2 |
| T10 | Off-chain map key equality | `JulcAssocMap` looks keys up with `Objects.equals`, so `byte[]` keys, or records with `byte[]` components, are not found | `EqualsData`, structural. The class comment claims it matches `EqualsData`; it does not for these keys | Reproduced | S2 |
| T11 | `BigInteger.mod` with a negative modulus | Throws `ArithmeticException` | `ModInteger` succeeds; the result takes the divisor's sign. A zero modulus fails on both sides, so it agrees. (`/` and `%` lower to `QuotientInteger` and `RemainderInteger`, which match Java's truncation) | Reproduced | S5 |
| T12 | Failure and trace model | Exceptions of several kinds; traces wherever the off-chain stub writes them. `ContextsLib.trace` does nothing off-chain, although its section comment says it prints | One error outcome; traces as an ordered log, including the prefix before a failure | Reproduced (`ContextsLib.trace`); to confirm (failure categories) | S1, S2, S3 |
| T13 | Budget and size limits | Unbounded | A validator over the execution budget or script-size limit fails on-chain while its JVM test passes | Verified (by design) | S3 |
| T14 | Target profiles | No notion of protocol version or target | Builtin availability and costs differ by `CompilerTarget` (Plutus language, protocol version and UPLC version): for example, a PV10 target lacks builtins that a PV11 target has. Optimization levels such as `BASELINE` and `PV11_SAFE` are separate from target legality | To confirm (which tests pass off-chain for unavailable builtins) | S3 |
| T15 | Unused failing expressions | `var x = a / 0;` throws even when `x` is unused | An optimizer may drop an unused binding. `BuiltinSemantics` records totality for this purpose; whether every level keeps a non-total unused binding is not established | Refuted for an unused `BigInteger.divide` by zero, which fails at every level as on the JVM; other shapes not examined | S1 |
| T16 | Boundary conversion | Tests pass natural Java values (the `JulcEval` proxy takes `long`, `ArgConverter`) or build a `ScriptContext` off-chain | The script decodes Data at its boundary under strict-boundary rules | To confirm (that both decode identically for every supported boundary type) | S1, S3 |
| T17 | Strings | UTF-16 `String` | UTF-8 bytestrings; `Builtins.encodeUtf8(PlutusData)` passes `BytesData` through off-chain ("On-chain, strings are ByteStrings") | To confirm (behaviour of invalid UTF-8 and of string methods) | S2 |
| T18 | Off-chain crypto | A pluggable `CryptoProvider` (`JvmCryptoProvider` in the testkit), a separate implementation | VM builtins, conformance-tested | To confirm (edge cases: malformed keys or signatures, where the chain fails and the JVM may return `false`) | S2 |
| T19 | Callbacks capturing mutable state | Lambdas observe and update enclosing state per Java's rules | See T2; ADR-061 proposes forbidding writes to enclosing bindings | Verified (ADR-061) | S4 / ADR-061 |
| T20 | Rejections without a diagnostic code | — | `continue`, `return` inside `while`, reassignment outside a loop, `JulcMap.of` in contract code, `break` in a for-each that updates nothing, and a loop inside a loop with `break` are rejected with uncoded messages. `Undefined variable: JulcMap` and "break statement outside of a loop" (the `break` is inside one) are misleading | Reproduced (ADR-063 M1); #257 | S5 |
| T21 | `while` with `break` and a conditional update | The loop runs its bounded iterations and returns | Never terminates: a taken `if` branch that updates a variable, in a `while` body that contains `break`, runs until the budget is exhausted, at every optimization level, even when the `break` is never taken. The for-each forms of the saved reproducers agree; another for-each shape gives a wrong value (T23) | Reproduced (found by the ADR-063 M2 nightly campaign; three saved cases); #254 | S4 / ADR-061; targeted fix first |
| T22 | String literals with escapes | The literal `"a\nb"` holds a line break | `PirGenerator` lowered a `StringLiteralExpr` with JavaParser's `getValue()`, the source text with its escapes, so the script held a backslash and an `n`, and every string constant with an escape had different bytes on-chain (fixed: `StringLiterals.decode`) | Fixed for #256 (found by the ADR-063 r4 trace-payload case): a literal compiles to javac's string or is a compile error. JuLC rejects some shapes javac accepts: an unpaired surrogate, a Unicode-escaped backslash before a raw one, and (until T24) several `u` | S5; targeted fix first |
| T23 | For-each with `break` and an `if`/`else` assigning different variables | The loop returns the updated values | Returns a wrong value at every optimization level, even when the `break` is never taken (JVM 2, UPLC 29). An `if`/`else` that assigns one variable agrees, and so do an `if` without `else`, a `break` before the `if`/`else`, and no `break`. Probably the lowering behind T21 | Reproduced (found by the ADR-063 r4 exhaustive corpus; two saved cases, four exact pins since ADR-063 r5); #255 | S4 / ADR-061; targeted fix first |
| T24 | Unicode escapes in source | javac translates Unicode escapes before anything else (JLS 21 §3.3): in a comment an escape can be a line terminator or the comment's end, and in a name it is the character it denotes | JuLC's parsers used JavaParser's default configuration, which leaves escapes untranslated, so the script could be a different program from the classes the JVM tests run, or JuLC rejected source javac compiles (fixed: every parser comes from `JavaSourceParser`, which translates escapes first and maps positions back to the source as written) | Fixed for #268 (found while fixing T22; one saved case): JuLC reads a source as javac does or rejects it. JuLC rejects some sources javac accepts: an escape with non-ASCII digits, a Unicode-escaped backslash before a raw one, and an unpaired surrogate | S5; targeted fix first |
| T25 | Integer literals | A hexadecimal, octal or binary `int` literal denotes the two's-complement value of its 32 bits, so `0xFFFFFFFF` is -1 (JLS 21 §3.10.1); `0777L` is 511; underscores and non-decimal `long` literals are allowed; a `byte[]` element must be in -128 to 127 | `PirGenerator` decoded literals by hand: a top-bit-set `int` literal was unsigned (4294967295), an octal `long` literal was decimal (777), underscores or `0xFFL` were rejected with a `NumberFormatException` message, and `byte[]` elements were narrowed silently (`0x100` became 0) | Resolved (found while fixing T24; one saved case; fixed for #270: `IntegerLiterals` follows §3.10.1, and a literal javac rejects is `JULC0070`) | S5; targeted fix first |

## Decision

Adopt a five-step programme. Every step has its own ADR, its own acceptance gate, and the
`semantic-consistency` label.

| Step | What | Sub-ADR | Depends on |
|---|---|---|---|
| **S1** | Differential JVM/UPLC test harness: a runner that executes the same source both ways, generators for programs and inputs, shrinking, and coverage statistics. Test-only | [ADR-063](063-differential-jvm-uplc-test-harness.md) | — |
| **S2** | One implementation of builtin semantics. Move each builtin's semantics into shared classes in `julc-core`, following the precedent of `NativeValueSemantics` and `ArraySemantics`, and have the VM and the off-chain `Builtins` both use them. Remove the T8 stubs, replace the T9 casts with code that is correct on the JVM, fix T10, and add a CI-enforced conformance list: every public stdlib and builtin method has a differential property | to be written | S1 |
| **S3** | Testkit dual-run. `ValidatorTest`, `ContractTest` and `JulcEval` take their verdict from UPLC. Where a JVM run is possible they run it too, and report any disagreement as a JuLC defect rather than as a failure of the user's test. Opt-in for JuLC's own suites first, then for users, then the default. Budget and size are reported with every verdict (T13) | to be written | S1, S2 |
| **S4** | Control flow, as designed by ADR-061: one typed representation with explicit joins, migrated construct by construct behind a compiler option, with S1 as the evidence gate for each migration | ADR-061 | S1 |
| **S5** | Close the remaining catalogue entries at their source, one decision per entry: make the executions agree (for example short-circuiting `any`, `all` and `find` on-chain for T7), reject the construct (for example `==` on reference types, T3–T5), or document it as an allowed divergence. Add the functional APIs that make loops optional: short-circuiting `any`/`all`/`find` and list functions that javac code can call | one ADR per decision group | S1 |

### Principles

- **P1. UPLC is the semantics of record.** A test verdict comes from the compiled script. The JVM execution is
  for breakpoints, coverage and speed, and is never the sole evidence that a contract works.
- **P2. Agree, reject, or document.** Every supported construct either agrees with the JVM on the documented
  input domain, is rejected at compile time with an actionable diagnostic, or is listed as an allowed
  divergence with a catalogue ID. Nothing is silently different.
- **P3. No normalization.** Tests never hide a difference by normalizing it away: failures, traces and their
  order are observations. An allowed divergence is matched by a narrow, reviewed rule, and every match is
  counted and reported.
- **P4. One implementation per semantic fact.** Builtin semantics exist once and are shared, as
  `NativeValueSemantics`, `ArraySemantics` and `ExpModIntegerSemantics` already are. A second implementation
  is allowed only as an independent oracle in tests, never as a production path.
- **P5. Evidence before rewrites.** No lowering change in S4 or S5 starts until S1 can reproduce the divergence
  it addresses and can detect a deliberate regression of the fix.
- **P6. Visible compatibility.** A change that alters script bytes, hashes or costs, or rejects programs that
  compiled before, is announced in release notes with migration guidance, and goes through an opt-in release
  before it becomes the default.

### Tracking

- The label `semantic-consistency` marks every PR and issue in the programme. ADR-061 and PR #203 belong to
  it as well.
- This ADR is the index. Each sub-ADR links back here, and each catalogue entry names its owner. When a step
  resolves an entry, the entry keeps its ID and records the resolution and the PR.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Keep fixing scenarios as they are reported | The history in T1 shows this does not converge; each fix leaves the next combination open |
| Drop JVM execution and test only through UPLC | Loses IDE debugging, which users rely on. ADR-058's source debugger may eventually make the JVM run unnecessary; until then, demote it (P1) rather than remove it |
| Have the stdlib call the VM module for off-chain builtins | Adds a runtime dependency from `julc-stdlib` on `julc-vm-java`, which it does not have today. Shared semantics in `julc-core` follows existing precedent instead |
| Write a reference interpreter for JuLC Java as the oracle | javac and the JVM already define Java's meaning independently of JuLC. An interpreter is justified only for constructs where Java and JuLC deliberately differ, and it must not reuse the lowering (ADR-061) |
| Prove the lowering correct first | Too broad as a first step; ADR-061 keeps scoped formal work as its last milestone |

## Consequences

- S1 and S2 change no compiled output, so script bytes and hashes do not change.
- S4 and S5 can change script bytes, hashes and costs, and S5 may reject programs that compile today. P6
  applies to both.
- S3 makes some existing user tests fail where they pass today, wherever the JVM and UPLC already disagree.
  That is the intended outcome, but it must ship opt-in first, with messages that explain the divergence and
  name its catalogue ID.
- Test time grows. ADR-063 bounds the cost on pull requests and moves large campaigns to scheduled runs.

## Risks

- **A wrong oracle gives false confidence.** The JVM side depends on off-chain libraries that this programme
  says are unreliable (T8–T10, T17, T18). ADR-063 restricts the oracle to code whose JVM meaning is
  independent of those libraries until S2 makes them trustworthy, and lists every trusted component.
- **Generators that miss interactions.** Mitigated by coverage quotas and mutation checks (ADR-063).
- **Programme drift.** Work merged without its gate. Each sub-ADR states its gate, and review checks it against
  this index.

## Open questions

1. **Should `==` on reference types (T3–T5) be rejected, or only warned about?** Rejecting is consistent with P2
   but breaks contracts that rely on the on-chain structural meaning. Lean: a warning in the next release and a
   rejection one release later, with a quick fix to `.equals`.
2. **Should `any`, `all` and `find` short-circuit on-chain (T7)?** That changes costs and hashes, and usually
   lowers costs. Lean: yes, behind an optimization level first.
3. **Should `int` and `long` stay accepted (T6)?** Rejecting them breaks the `JulcEval` proxy style that uses
   `long` parameters. Lean: accept them only as boundary conversions in test APIs, and reject arithmetic on
   them in contract code.
4. **Where should shared builtin semantics live (S2)?** In `julc-core` next to `NativeValueSemantics`, or in a
   new small module? And does off-chain crypto keep the pluggable `CryptoProvider`, or use the same
   implementation as the VM? Lean: `julc-core`, and the VM's implementation, with the provider kept only for
   native acceleration.
5. **Should S3 dual-run eventually be on by default for users?** Lean: yes, after one opt-in release with a
   low false-positive rate.
6. **Should ADR-061 and this ADR merge?** Lean: no. ADR-061 is a design decision about the compiler; this ADR is
   a programme and a catalogue.

## Revision history

- **r1** (2026-10-23): initial proposal.
- **r2** (2026-10-23): catalogue statuses updated from ADR-063 M1 (PR #251): T3, T5, T6, T7, T9, T10, T11 and
  T12 reproduced; T2 contained by `JULC0056`; T15 refuted for the probed shape; new T20 (uncoded rejections).
- **r3** (2026-10-23): new T21 (silent non-termination of `while` with `break` and a conditional update), found by
  the ADR-063 M2 campaign; T20 gains two more uncoded rejections found while narrowing G0.
- **r4** (2026-10-28, responds to the ADR-063 review round r1 of `ac20ac2d`, which reviewed this ADR at
  `f3333d69` as context):
  - T11's domain is a negative modulus; a zero modulus fails on both sides, checked with the harness.
  - T14's example names compiler targets, not optimization levels.
  - New T22 (string literal escapes) and T23 (a wrong value from a for-each with `break` and an `if`/`else`
    assigning different variables), both found by the ADR-063 r4 changes.
  - T21's note on the for-each form is corrected.
  - Rows and history are in ID order.
- **Accepted** (2026-10-28): design accepted at `b75b9619` (review round r2). Issues filed for T20–T23 (#257, #254,
  #256, #255), tracked together in #258.
- **r5** (2026-10-30): new T24 (Unicode escapes not translated before parsing), found while fixing T22, #268. T22 and T24 marked "Fixed for #N", a status the catalogue preamble now defines.
- **r6** (2026-10-30): new T25 (integer literals not decoded as Java decodes them), found while fixing T24, #270.
