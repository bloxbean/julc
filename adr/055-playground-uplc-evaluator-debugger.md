# ADR-055: UPLC evaluator and debugger in the playground

- Status: Implemented on `feat/playground-uplc-debugger` (stacked on ADR-054, `feat/playground-wasm`); independent review pending
- Date: 2026-09-16

Distribution update: [ADR-057](057-julc-wasm-distribution.md) moves these services to
`julc-tools` and introduces raw-argument evaluation and worker-local session descriptors.
The playground keeps this ADR's stateless REST debug contract through an adapter.

## Context / Problem

The playground (ADR-054) compiles and tests JuLC source on the server or in the browser. Developers also need to
work with scripts they did not write in JuLC, or already compiled: a validator from another language, a script
found on chain, a blueprint from another project. Today they cannot use the playground to:

- read such a script as UPLC;
- run it against a realistic `ScriptContext` without building a transaction by hand;
- see its cost;
- step through its evaluation to find out why it fails.

JuLC already has most of the parts: FLAT/CBOR decoding, the decompiler, the CEK machine with budgets and traces,
the V3 ledger model and a V1/V2 context down-converter. Four pieces are missing:

- **Printing.** The UPLC printer prints decoded programs on one line, and its names are not scope-correct
  (`(lam i0 (lam i0 [i2 i1]))`).
- **Stepping.** The CEK machine can only run to completion.
- **Mock transactions.** There is no transaction model with defaults, and no endpoint that evaluates arbitrary
  compiled code.
- **V1/V2 contexts.** The V1/V2 context builder is package-private.

## Goals and non-goals

Goals:

- A **UPLC** mode next to the Contract playground. The user pastes a compiled script as CBOR hex (double, single
  or raw FLAT), a `plutus.json` blueprint, a text envelope, or UPLC text. The page shows the script's details,
  indented UPLC, and an optional decompiled Java preview.
- Evaluation against a **mock transaction** that is complete by default for each purpose (spend, mint, reward,
  certify, vote, propose), so users only edit what their script checks. The result shows accept or reject,
  CPU and memory against protocol limits, the script fee, traces, and the exact script context.
- A **step debugger**:
  - Step, step back, over, out, continue, and a timeline scrubber with trace and failure markers.
  - Line, trace and builtin breakpoints.
  - The current term highlighted in the UPLC view.
  - Environment (with names matching the UPLC view), continuation frames and budget per step.
- Everything runs in the browser engine (no backend) and on the server, with identical JSON (ADR-054 invariant 1).

Non-goals:

- Changing the semantics or cost of normal evaluation.
- Importing real transactions from a chain indexer.
- Multi-script transaction evaluation.
- Editing environment values mid-run, and conditional breakpoints.
- Profiling charts.
- Editing UPLC text and re-encoding it.
- Share links.

## Invariants

1. **Stepping is evaluation.** Stepping to completion with `CekMachine.step()` gives the same value, budget,
   traces and failed term as `CekMachine.evaluate`. `evaluate` keeps its loop; no per-step overhead is added to
   normal evaluation.
2. **Printing round-trips.** Pretty-printed UPLC parses back to a program with the same FLAT encoding at every
   layout width, and renaming only changes names, never de Bruijn indices.
3. **Script hashes are exact.** The hash is `blake2b-224(language tag || single-CBOR script)` over the pasted
   bytes. It matches cardano-client-lib and the blueprint generator for every wrapping.
4. **Debugging is deterministic.** Every debug request replays from the script and transaction, so any step
   gives the same snapshot on either engine. A cached session only makes this faster.
5. **Same contract on both engines.** The new `/api/uplc/*` requests are covered by the parity fixtures on the JVM
   (dispatcher against Javalin) and in Node.js (WebAssembly against the JVM).

## Decision

1. **`julc-core`: `UplcNames` and `UplcPrettyPrinter`** (new public API)
   - `UplcNames.uniquify(Program)` rebuilds a program with unique, scope-correct binder names:
     - meaningful names are kept and deduplicated (`ctx`, `ctx_2`);
     - generated `i<n>` names become `i_1, i_2, …`;
     - names are sanitized to the UPLC grammar, and keywords get a `_` suffix;
     - out-of-scope variables become `free_<index>`.
   - `UplcPrettyPrinter.print(Program, Options)` returns `PrettyUplc(text, terms, spans, ids)`:
     - output is width-aware and indented, with application chains flattened to `[f a b]`;
     - indentation is capped for very deep programs, and constants can optionally be truncated;
     - every term gets a stable pre-order id and a 1-based span.
   - Both are iterative (explicit stacks), because the browser WebAssembly stack is small. `UplcPrinter.printConstant`
     becomes package-private so constants print identically.
2. **`julc-vm-java`: stepwise CEK machine**
   - `CekMachine.start(Term)` resets the state and charges startup exactly as `evaluate` does.
   - `step()` performs one `compute` or `returnValue` transition, calling the same private methods as `evaluate`
     and re-attaching the failed term to budget exhaustion in the same way.
   - `isDone()` and `result()` report completion.
   - Read-only accessors for tools: `isComputing()`, `currentTerm()`, `currentEnvironment()`, `currentValue()`,
     `stackDepth()`, `frames(max)` (innermost first), `traceCount()`, plus `CekEnvironment.size()`.
   - `JavaVmProvider.startStepping(program, target, args, budget, options)` returns a `SteppingEvaluation`
     (`step`, `isFinished`, `steps`, `result`, `machine`, `costTracker`). It shares preparation (protocol profile,
     cost model, validation) and result mapping with `evaluateWithArgs`, so a finished stepping evaluation carries
     the same `EvalResult`.
3. **`julc-cardano-client-lib`.** `V1V2ScriptContextBuilder` becomes public, with
   `build(PlutusLanguage, TxInfo, ScriptPurpose)`, so tools can produce V1/V2 contexts from the V3 ledger model.
4. **`julc-playground-core`: `UplcToolsService`**. It is transport-neutral and returns `ServiceResult` like
   `PlaygroundService`.
   - **`ScriptDecoder`** accepts hex (with or without `0x` and whitespace), blueprint JSON (with a validator
     picker), text envelopes and UPLC text. It unwraps double or single CBOR and raw FLAT, decodes with PV11 limits,
     applies parameters, and computes the hash with BouncyCastle `Blake2bDigest`.
     - Language selection, in order: the user's choice, then a blueprint hash match, then an envelope or blueprint
       hint, then program version 1.1.0 (V3), then the newest builtin the script uses, then V2 with a warning.
   - **`MockTransaction` and `MockContextBuilder`** build the V3 `TxInfo` and `ScriptInfo`, or the V1/V2 context.
     - `$self` stands for the script hash.
     - Credentials: `key:`/`script:` hashes and bech32 addresses via cardano-client-lib.
     - Values with assets; datums: none, hash (added to the witness map) and inline.
     - Certificates, withdrawals, votes, proposals, validity interval, signatories and treasury fields.
     - The redeemers map is filled in for the chosen purpose.
     - Data inputs can be UPLC data notation, detailed-schema JSON, CBOR hex, or a plain integer.
   - **Evaluation arguments:** `[ctx]` for V3, `[datum, redeemer, ctx]` for V1/V2 spending, `[redeemer, ctx]`
     otherwise. A V3 script is accepted only if it returns unit.
   - **`decode`** returns script details, uniquified pretty UPLC, and the double-CBOR code with parameters applied.
   - **`decompile`** runs `JulcDecompiler` on the program; errors, including stack overflow, are returned as messages.
   - **`evaluate`** returns status, acceptance, CPU and memory, traces, the failed term's span, the result, the
     last builtin calls, the context as pretty Data and CBOR, the hash and the language.
   - **`debug`** is stateless; one request carries the script, transaction, action and breakpoints.
     - Actions: `timeline`, `goto`, `continue`, `over`, `out`.
     - The timeline counts steps (capped at 10M) and records trace steps and the failure step.
     - The session is keyed by the request inputs. It moves forward on the cached machine and replays from step 0
       to go back.
     - A snapshot contains:
       - the phase and the current term's span;
       - the returned value;
       - up to 60 environment entries, named from the lexical binder chain of the pretty-printed program;
       - up to 40 frames;
       - cumulative and per-step budget, traces and the stop reason.
     - Line breakpoints trigger on terms that begin a line, not on their sub-terms on the same line.
5. **Transports**
   - `POST /api/uplc/{decode,decompile,evaluate,debug}` is served by `PlaygroundDispatcher` (browser engine) and by
     `UplcController` (server, inside `CompilationSandbox` with the existing rate limiters).
   - A Web Image substitution makes `BlsConstantValidator.getInstance()` return null, so UPLC text parsing does not
     pull native BLS into the image. BLS evaluation keeps the ADR-054 message.
6. **Frontend**
   - A **Contract | UPLC** mode switch; the UPLC page is loaded lazily and stays mounted.
   - **Script bar:** paste area, examples, "From Contract", detail chips (wrapping, language override, version,
     size, copyable hash, builtins, warnings) and a parameter editor.
   - **Viewer:** read-only Monaco with UPLC highlighting and gutter breakpoints. It highlights the current term
     (strong on its first line, faint on the rest) and the failed term. There are also a decompiled Java tab and a
     Details tab, where clicking a builtin sets a builtin breakpoint.
   - **Mock transaction editor:**
     - Purpose segmented control, redeemer and datum always visible.
     - Collapsible sections with counts and "+ Add": inputs, outputs, signers (wallet chips), validity, mint,
       reference inputs, fee.
     - "More fields" adds withdrawals, certificates, votes, proposals, datum witnesses and treasury.
     - Demo wallets have recognizable key hashes (Alice, Bob, Carol).
   - **Result:** banner (accepted, rejected or failed), CPU and memory meters against a protocol preset, fee
     estimate, traces, result, last builtins, "Debug from failure", and a Script context tab.
   - **Debugger panel:** controls, scrubber with markers, and State, Environment (filter, click to expand), Frames
     (click to reveal the term) and Traces/Breakpoints panes.
   - **Keyboard:** ⌘/Ctrl+Enter run, F5 debug or continue, F8 continue, F10 over, ⇧F10 back, F11 step, ⇧F11 out.
   - The script, transaction, breakpoints and preset are saved in localStorage. Results are marked stale when their
     inputs change.
   - The Contract tab's Compiled view has "Open in UPLC evaluator".

## Alternatives considered

- **A separate debugger VM or an instrumented copy of the CEK machine.** Rejected: two implementations of
  the semantics and cost model would drift. `step()` reuses the private transitions of the real machine.
- **Record a full execution trace and scrub it on the client.** Rejected: snapshots include environments and
  frames, so memory grows with steps × environment size. Replaying on demand keeps memory bounded and the protocol
  stateless.
- **Server-side debug sessions with ids.** Rejected: it adds lifecycle, expiry and concurrency handling. The
  browser engine runs one request at a time anyway, and the replay cache gives interactive speed.
- **Reuse `UplcPrinter`, or the decompiler's `ScopedNames`.** Rejected: the printer has no layout or spans, and
  its names are not unique. `ScopedNames` fixes names for Java output, not for UPLC text that must round-trip.
- **Build contexts with the existing scenario builder.** Rejected: it covers spend and mint scenarios for JuLC
  contracts, not arbitrary purposes, V1/V2 layouts or governance fields.

## Affected modules

`julc-core` (new `UplcNames`, `UplcPrettyPrinter`; package-private `printConstant`), `julc-vm-java`
(`CekMachine` stepping API, `CekEnvironment.size`, `JavaVmProvider.startStepping`, `SteppingEvaluation`),
`julc-cardano-client-lib` (public `V1V2ScriptContextBuilder.build`), `julc-playground-core`,
`julc-playground-wasm`, `julc-playground` (controller, routes, frontend). No compiler, stdlib or ledger model
changes.

## Compatibility

- Additive public API only. `CekMachine.evaluate` and `JavaVmProvider.evaluate*` behave as before; the existing VM
  suites and conformance tests pass unchanged.
- Existing playground routes and JSON are unchanged. The Contract mode is the default; `?mode=uplc` opens the new
  page.
- `julc-playground-core` now depends on `julc-decompiler`, `julc-cardano-client-lib`, BouncyCastle and
  Jackson. cardano-client-lib stays pinned to 0.8.0-pre5 (ADR-054).

## Risks

- Going back replays from step 0. For scripts with millions of steps this can take a moment per action; the step
  counter shows progress and the replay cap is 10M steps.
- The server keeps one cached debug session per service instance, and debug requests are serialized. With several
  users debugging at once, sessions replace each other: results stay correct (invariant 4), but each request
  replays. The existing rate limiters and the 30 s sandbox timeout still apply.
- The environment is truncated to 60 entries and values to 240 characters. Very large data constants are
  summarized.
- The decompiled Java is a reading aid; it can fail, or produce code that does not compile, for scripts from
  other compilers.
- Mock transactions are synthetic. Scripts that check exact ledger invariants (for example, balanced values) may
  need edits the defaults do not anticipate.

## Implementation milestones

1. `UplcNames` and `UplcPrettyPrinter` with round-trip, span and deep-nesting tests.
2. Stepwise `CekMachine` with equivalence tests against `evaluate`.
3. `UplcToolsService` decode, decompile and evaluate; `MockTransaction`; public V1/V2 builder.
4. Debug sessions and breakpoints; routes; dispatcher and Node.js parity fixtures; Web Image substitution.
5. Frontend UPLC page.
6. Browser verification and documentation.

## Verification strategy

- `:julc-core:test`, `UplcPrettyPrinterTest`:
  - unique names, deduplication and sanitizing;
  - 300 random programs round-trip through `UplcParser` with identical FLAT at widths 20, 60 and 200;
  - span positions, layout breaking and truncation;
  - a 200,000-deep program.
- `:julc-vm-java:test`, `SteppingEvaluationTest`:
  - stepping and `evaluate` agree on the Plutus conformance programs, with and without a tight budget;
  - state after each step, the failure term and traces, startup budget exhaustion, and rejected programs.
  - The existing VM suites also pass.
- `:julc-playground-core:test`, `UplcToolsServiceTest`:
  - hashes agree across wrappings, and with cardano-client-lib for a 4.7 KB on-chain V3 script and a V2 envelope;
  - blueprint validator selection and hash-based language detection;
  - parameters, invalid input, data notations;
  - signed spend accepted and rejected, the V3 unit rule, V2 datum/redeemer arguments, minting with `$self`;
  - debug timeline, goto and going back, continue to a trace, line or error, over and out, decompile.
- `:julc-playground-wasm:test` and `wasmSmoke`: 39 fixtures, 14 of them UPLC (decode, decompile, evaluate, debug),
  identical on Javalin, the dispatcher and the WebAssembly engine.
- Chrome, server and browser engines, dev server and static build:
  - Signed-spend example: rejected without the owner signer, accepted with it, with the same budget on both
    engines.
  - Every purpose evaluates with default mocks. The V2 example accepts or rejects as the redeemer changes. The
    on-chain script decodes, evaluates and debugs.
  - Debugger: step, back, over, out; a trace breakpoint stops at the trace step; a gutter breakpoint stops at the
    same step on both engines; continuing reaches the failure. The environment shows the parameter and the failed
    signer check.
  - "Open in UPLC evaluator" keeps the Contract tab's script hash.

## Open questions

- Import a transaction by hash from an indexer to seed the mock transaction.
- A browser test of the UPLC page in CI.
- Cost attribution per term (a profiler view) on top of the stepping API.
