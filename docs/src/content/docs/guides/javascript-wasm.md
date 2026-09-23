---
title: "JavaScript & WebAssembly"
description: "Use experimental JuLC WebAssembly from plain HTML and JavaScript to compile contracts, evaluate Plutus scripts, and debug locally."
---

Use JuLC from a JavaScript application without a Java backend. The SDK runs the
compiler and/or VM in a browser Web Worker, so your UI does not perform compilation
on its main thread. **Plain HTML and JavaScript work: React and Svelte are not required.**

:::caution[Experimental — API version 0]
The WebAssembly distribution and JavaScript API are experimental. APIs and bundle
formats may change. This is tooling for development and testing, not a guarantee
that a contract is safe to deploy. Review and independently test generated scripts
before using them with real funds. BLS12-381 evaluation is not supported in Wasm.
:::

## What you need

- A browser capable of running the bundle's WebAssembly GC and exception-handling
  instructions, with JavaScript modules and Web Workers enabled.
- A static HTTP/HTTPS server for your application and the extracted bundle. Do not
  open the HTML file directly with `file://`.
- No Java, GraalVM, Node.js, Cardano node, wallet, or blockchain connection is required
  **in the browser**. Node.js is used by the repository's build/test tooling, not by
  end users of this page's demo.

This API compiles and evaluates locally. It does **not** build, sign, or submit real
Cardano transactions. The examples use mock transaction contexts, not ledger state.

## Download a release bundle

Open the [JuLC GitHub releases page](https://github.com/bloxbean/julc/releases) and
expand **Assets** on a release that contains the bundles below. Replace `<version>`
with that release's artifact version. Older releases may not contain these assets.

| Artifact | Use it for |
| --- | --- |
| `julc-wasm-<version>.tar.gz` | **Full SDK:** compile contracts, Run Test, Quick Eval, decompile, evaluate and debug scripts, and create mock transactions. Use this for the HTML quick start below. |
| `julc-vm-wasm-<version>.tar.gz` | **VM-only SDK:** decode, hash, pretty-print, evaluate and debug existing scripts. No compiler, decompiler, or mock-transaction helpers. |
| `julc-wasm-<version>-SHA256SUMS.txt` | SHA-256 checksums for the release archives. Verify the entry for the archive you download before extracting it. |
| `julc-playground-static-<version>.tar.gz` | Complete ready-made playground UI. Choose this if you want the playground rather than an SDK for your own application. |

You need only **one** SDK variant, not both. There is no npm package to install for
this distribution yet. Keep bundles from different releases/variants separate.

For the full SDK, extract **all** files into a `wasm` directory next to your HTML:

```sh
mkdir -p julc-demo/wasm
tar -xzf 'julc-wasm-<version>.tar.gz' -C julc-demo/wasm
```

Do not rename the generated files or copy only the `.wasm` file. The manifest,
launcher, worker, SDK and support files belong together. The directory includes:

```text
julc-demo/
  index.html                  # create this below
  wasm/
    engine.json
    julc-wasm.js               # import this SDK entry point
    julc-wasm.d.ts
    julc-models.d.ts           # generated record types
    julc-worker.js
    rest-adapter.js
    julc-runtime.js
    julc-<hash>.js             # julc-vm-<hash>.js in the VM-only bundle
    julc-<hash>.js.wasm
```

For a Vite/React/Svelte project, put the bundle in `public/wasm/` and point `baseUrl`
at the served directory. The SDK itself has no dependency on those frameworks.

## Quick test: compile a contract from plain HTML

Save this as `julc-demo/index.html`. It checks and compiles a signer-check contract,
then evaluates it against a mock context containing that signer. The Java text is
the **contract source**, while the host application is ordinary JavaScript.

This example uses a synthetic key hash, not a wallet or private key. It is an SDK
demonstration, not a complete application or a deployment recommendation.

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>JuLC Wasm quick test</title>
  </head>
  <body>
    <h1>JuLC Wasm — experimental</h1>
    <button id="run" disabled>Loading SDK…</button>
    <pre id="output" aria-live="polite"></pre>

    <script type="module">
      import { createJulc, isFullClient } from './wasm/julc-wasm.js';

      const button = document.querySelector('#run');
      const output = document.querySelector('#output');
      // For display only: JSON.stringify cannot serialize bigint directly.
      const display = value => JSON.stringify(value,
        (_, v) => typeof v === 'bigint' ? v.toString() : v, 2);
      const source = `
@SpendingValidator
class SimpleSpending {
    @Param static byte[] authorizedSigner;

    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, authorizedSigner);
    }
}`;

      let julc;
      try {
        julc = await createJulc({
          baseUrl: new URL('./wasm/', location.href)
        });
        if (!isFullClient(julc)) throw new Error('Download the full SDK for compilation');
        button.textContent = 'Compile and test';
        button.disabled = false;
        output.textContent = display(julc.features());

        button.addEventListener('click', async () => {
          button.disabled = true;
          try {
            const checked = await julc.compiler.check({source, language: 'java'});
            if (!checked.valid) {
              output.textContent = display(checked);
              return;
            }
            const compiled = await julc.compiler.compile({
              source, language: 'java', blueprint: true
            });
            // Compilation diagnostics may be returned without an exception.
            if (!compiled.compiledCode) {
              output.textContent = display(compiled.diagnostics);
              return;
            }
            const signer = 'aa'.repeat(28);
            const test = await julc.compiler.evaluate({
              source, language: 'java',
              paramValues: {authorizedSigner: signer},
              scenario: {signers: [signer]},
              datum: {}
            });
            output.textContent = display({
              compiledBytes: compiled.scriptSizeBytes,
              unparameterizedScriptHash: compiled.scriptHash,
              parameters: compiled.params,
              test
            });
          } catch (error) {
            output.textContent = display({
              message: error.message, code: error.code,
              status: error.status, body: error.body
            });
          } finally {
            button.disabled = false;
          }
        });
        window.addEventListener('pagehide', () => julc.dispose(), {once: true});
      } catch (error) {
        julc?.dispose();
        button.textContent = 'SDK unavailable';
        output.textContent = error.message;
      }
    </script>
  </body>
</html>
```

Serve the directory with any static web server. For example, if Python 3 is installed:

```sh
cd julc-demo
python3 -m http.server 8000 --bind 127.0.0.1
```

Open `http://127.0.0.1:8000/` and click **Compile and test**. Expect a compiled script
and `test.success: true`, with CPU/memory consumption. Change `scenario.signers` to
`[]` to test rejection; this should produce `success: false`, not necessarily throw.

`compiler.compile` does not bind the contract's `@Param` values. Its script/hash in
this example are **unparameterized**. `compiler.evaluate` binds them for its test.
For parameterized bytes and a hash to use elsewhere, see [Apply parameters](#apply-parameters).

## Client setup and lifecycle

The following snippets assume a live client named `julc` created with `createJulc`.
Use the full variant for compiler and transaction helpers; the `vm` examples work
with either bundle.

```javascript
import { createJulc, isFullClient } from './wasm/julc-wasm.js';

const julc = await createJulc({
  baseUrl: new URL('./wasm/', location.href),
  timeoutMs: 30_000,       // pending-request timeout; terminates the worker
  loadTimeoutMs: 120_000  // worker initialization timeout
});
console.log(julc.version(), julc.features());
console.log('Can compile:', isFullClient(julc));

// Reuse the client for operations. When the application no longer needs it:
// julc.dispose();
```

Each client owns one worker. Use `await` for operations; `version()`, `features()`,
`dispose()` and a debug session's `isOpen()` are synchronous. A disposed or timed-out
client cannot be reused: create a new client and reopen any debug sessions.

Advanced `createJulc` options are `manifest` (an already-loaded `engine.json`),
`workerFactory` (custom worker creation), and `catalogue` (the playground adapter's
static catalogue). Ordinary SDK consumers need none of these. This is a browser
worker SDK; plain Node.js does not provide its default `Worker` implementation.
The repository's Node smoke tests supply a worker shim.

## Supported APIs

This is the experimental **version-0** application API. Every operation below takes
one request object and returns a Promise, unless indicated otherwise. Exact nested
request/response types ship as `julc-wasm.d.ts` and `julc-models.d.ts` in the bundle.
Use those declarations from the **same release** as your engine.

### VM operations — both variants

| Method | Request | Result |
| --- | --- | --- |
| `vm.decode` | `{script}` | `{ok, error, info, uplcText}`; `info` contains language, hash, parameterized `compiledCode`, sizes, builtins and warnings. |
| `vm.hash` | `{script}` | `{scriptHash}` after applying any script parameters. |
| `vm.prettyPrint` | `{script, width?}` | `{uplcText}`; width defaults to 100. |
| `vm.evaluate` | `{script, args?, target?, costModel?, maxCpu?, maxMem?}` | Evaluation result: `ok`, `status`, `accepted`, budget, traces, result text, failure information, resolved target and cost model. |
| `vm.debug` | Same request as `vm.evaluate` | A worker-bound debug-session proxy. |

`script` is a **ScriptInput object**, not just a string:

```javascript
const script = {
  script: '(program 1.1.0 (lam x x))',
  // params: [ ...Data inputs applied in order... ],
  // language: 'V3',  // optional override; otherwise inferred
  // validator: 'validator title or index' // optional blueprint selection
};
const decoded = await julc.vm.decode({script});
if (!decoded.ok) throw new Error(decoded.error);
console.log(decoded.info.scriptHash, decoded.uplcText);
```

Supported script text includes UPLC notation, CBOR/FLAT hex, a text envelope, or a
CIP-57 blueprint. `validator` selects a blueprint entry; otherwise the decoder
selects its default entry. A script's `params` are applied before `args` supplied
to evaluation.

### Compiler operations — full variant only

| Method | Request | Result |
| --- | --- | --- |
| `compiler.check` | `{source, language}` | Source/metadata check: `valid`, contract name/purpose, parameter, datum and redeemer metadata, and diagnostics. Not a replacement for compilation. |
| `compiler.compile` | `{source, language, librarySource?, blueprint?}` | `compiledCode`, `scriptHash`, `uplcText`, `pirText`, `blueprintJson`, sizes, parameters and diagnostics. Blueprint generation defaults to enabled. |
| `compiler.evaluate` | `{source, language, librarySource?, paramValues?, scenario?, datum?, redeemer?}` | Compile-and-test result: `success`, `budgetCpu`, `budgetMem`, traces, error and diagnostics. This is the playground's Run Test operation. |
| `compiler.evalExpression` | `{expression}` | Quick Eval result: `success`, result text, type, budget, traces, error and UPLC text. |

For Java contracts, set `language: 'java'` as in the quick start. The API compiles
JuLC's supported Java subset, **not JavaScript and not arbitrary Java applications**.
`librarySource` supplies optional on-chain library source alongside the contract.
`blueprintJson` is JSON **text**, so use `JSON.parse` if you need an object.

For Run Test, `paramValues` and `datum` map field names to textual values.
`scenario` accepts `{signers, validRangeAfter, validRangeBefore}`; time bounds are
`bigint` when supplied. `redeemer` is `{variant, fields}`, with a numeric variant
index and a map of field names to textual values. Use `compiler.check` to inspect
the contract's metadata. These source-level test inputs are different from raw
Data arguments or a complete mock transaction.

```javascript
const expression = await julc.compiler.evalExpression({expression: '1 + 2'});
console.log(expression.success, expression.result); // true, "3"
```

### Transaction and decompiler helpers — full variant only

| Method | Request | Result |
| --- | --- | --- |
| `uplc.decompile` | `{script}` | `{ok, error, javaSource, summary}`; a preview, not guaranteed reconstruction of the original source. |
| `uplc.defaultTransaction` | `{purpose?, scriptHash?}` or no argument | Editable mock transaction; defaults to `spend` and the evaluated script's hash via `$self`. |
| `uplc.evaluateTransaction` | `{script, transaction?, target?, costModel?, maxCpu?, maxMem?}` | Evaluation result including the generated script context. |
| `uplc.debugTransaction` | Same request as `uplc.evaluateTransaction` | Debug-session proxy, or an error result if legacy transaction preparation fails. Check `ok` before using session methods. |

Purposes are `spend`, `mint`, `reward`, `certify`, `vote`, and `propose`. An explicit
`scriptHash` is 28-byte hex. Transaction requests also accept the legacy
`protocolVersion` field; new applications should use `target.protocol`, not both.
The VM-only bundle has neither the `compiler` nor the `uplc` group.

### Java-source debugger — full variant only

`sourceDebug.open` compiles a separate source-debug artifact and returns a worker-bound
session with the same stepping methods listed below. Its request accepts `source`,
optional `librarySource`, source parameters in declaration order, a mock `transaction`,
`target`, `costModel`, `maxCpu`, and `maxMem`. The result includes the captured source,
generated UPLC, compiled code, hash, size, executable Java lines, diagnostics, timeline,
and initial snapshot.

This is deliberately a different build from `compiler.compile`: the UPLC optimizer is
disabled to retain exact source locations. Its bytes, hash and execution budget may
differ from normal compilation. Do not deploy that artifact or use its budget as an
estimate for a normally compiled contract.

Breakpoints accept `javaLines` in addition to formatted-UPLC `lines`. A snapshot's
`javaLocation` and UPLC `span` refer to the same current term when both exist. Source-map
coverage is partial, so a Java breakpoint can be unbound. Environment entries remain
the **UPLC environment** in this milestone; reliable Java names, types and lexical
scopes require separate versioned debug metadata and are not inferred or guessed.

```javascript
const transaction = await julc.uplc.defaultTransaction({purpose: 'spend'});
const session = await julc.sourceDebug.open({
  source: javaSource,
  params: [],
  transaction,
  target: {language: 'PlutusV3', protocol: 11}
});
if (!session.ok) throw new Error(session.error);
try {
  const paused = await session.continue({javaLines: [12]});
  console.log(paused.snapshot.javaLocation, paused.snapshot.span);
} finally {
  await session.close();
}
```

### Client and session methods

| Method | Purpose |
| --- | --- |
| `createJulc(options?)` | Importable factory; initializes a client asynchronously. |
| `isFullClient(client)` | Importable synchronous capability/type guard. |
| `julc.version()` | Synchronous JuLC version string, distinct from the manifest's content hash. |
| `julc.features()` | Synchronous `{api, variant, groups, defaultProtocol, bls}` capabilities. |
| `julc.dispose()` | Synchronously marks the client closed and terminates its worker; all sessions are lost. |
| `session.step()` | Advance one machine transition. |
| `session.goto(step)` | Replay to an absolute step, supplied as `bigint`. |
| `session.continue(breakpoints?)` | Continue to a breakpoint or termination/limit. |
| `session.over(breakpoints?)` | Step over the current term. |
| `session.out(breakpoints?)` | Step out of the current frame. |
| `session.snapshot()` | Inspect the current step. |
| `session.close()` | Discard this session; does not dispose the client or other sessions. |
| `session.isOpen()` | Synchronous local session-lifetime check. |

Session actions return `{ok, error, timeline, snapshot, target, costModelId}`.
`timeline` may be `null`. Breakpoints accept `lines` (formatted-UPLC number array),
`javaLines` (Java number array for a source-debug session), `builtins` (name array),
`onTrace` and `onError`. Snapshot fields include step, phase, source span in formatted
UPLC, optional exact Java location, environment, frames, CPU/memory, traces and
terminal status. Ordinary `vm.debug` and `uplc.debugTransaction` sessions have no
Java-source association.

The runtime also exposes the low-level `debug.act({sessionId, action, step,
breakpoints})` and `debug.close({sessionId})` protocol. Actions include `timeline`,
`goto`, `continue`, `over`, and `out`. Prefer the session proxy in applications.
`julc.rest(method, path, body)` is the playground's compatibility adapter, not the
typed application API; its legacy JSON numbers can lose integer precision. These
low-level/compatibility methods are not part of the exported typed client interface.

## Evaluate and debug an existing script

This example returns unit after receiving one Data argument, and works with either
bundle. Replace `script.script` with your own compiled script and supply the Data
arguments it expects. `vm.evaluate` does **not** construct a ScriptContext for you.

```javascript
const request = {
  script: {script: '(program 1.1.0 (lam x (con unit ())))'},
  args: [{int: 9007199254740993n}],
  target: {language: 'PlutusV3', protocol: 11},
  maxCpu: 10_000_000_000n,
  maxMem: 14_000_000n
};
const evaluated = await julc.vm.evaluate(request);
console.log(evaluated.status, evaluated.accepted, evaluated.cpu, evaluated.mem);

const session = await julc.vm.debug(request);
try {
  console.log((await session.step()).snapshot);
  const end = await session.continue();
  if (!end.ok) throw new Error(end.error);
  console.log(end.snapshot.status, end.snapshot.cpu, end.snapshot.traces);
  console.log((await session.goto(0n)).snapshot); // replay from the start
} finally {
  await session.close();
}
```

Evaluation `status` is `success`, `failure`, or `budgetExhausted`. `accepted` is a
separate script-result check: for example, a V3 computation returning an integer
can evaluate successfully but is not an accepted V3 validator result. Even
`accepted: true` is **not proof a real transaction would be accepted by the ledger**.

## Apply parameters

For the quick start's compiled `SimpleSpending`, bind its signer parameter before
using the script hash. The following assumes `compiled` is a successful compile
result from that source and `signer` is the same 28-byte hex string:

```javascript
const bound = await julc.vm.decode({
  script: {
    script: compiled.compiledCode,
    language: 'V3',
    params: [{bytes: signer}]
  }
});
if (!bound.ok) throw new Error(bound.error);
console.log(bound.info.compiledCode, bound.info.scriptHash);

const script = {script: bound.info.compiledCode, language: 'V3'};
const transaction = await julc.uplc.defaultTransaction({purpose: 'spend'});
transaction.signatories = [signer];
const result = await julc.uplc.evaluateTransaction({script, transaction});
console.log(result.ok, result.status, result.accepted);
```

Apply parameters in declaration order, using the contract's expected Data encoding.
Do not apply them again to already-parameterized bytes. A different parameter value
generally produces different script bytes and a different hash.

## Data, integers, and evaluation configuration

### Exact integers and Data

Java `long` and `BigInteger` positions are JavaScript **`bigint`**, including budgets,
debug steps and cost-model parameters. Small `int` positions (line numbers, widths,
protocol numbers and redeemer variant indexes) remain JavaScript **`number`**.
Signed-long fields reject values outside `-2^63` through `2^63 - 1`.

Data input has two forms, accepted in script parameters and raw arguments:

```javascript
const structured = {constructor: 0n, fields: [{int: 9007199254740993n}]};
const text = {format: 'json', value: '{"int":9007199254740993}'};
// Other text formats: 'uplc' (e.g. 'I 42'), 'cbor' (hex), or 'auto'.
```

Structured forms are `{int: bigint}`, `{bytes: hexString}`, `{list: Data[]}`,
`{map: [{k: Data, v: Data}]}`, and `{constructor: bigint, fields: Data[]}`.
Constructor tags must be between `0n` and `(1n << 64n) - 1n`. Detailed JSON parsing
preserves legacy duplicate-map-key replacement; use CBOR/UPLC text if you need to
preserve duplicate map entries. Returned Data values remain formatted **text** in
version 0, rather than structured Data objects.

Pass plain objects and `bigint` directly to SDK methods; **do not JSON.stringify
requests yourself**. For display/storage you may stringify bigint as decimal text,
as the HTML example does, but that is not an automatic round-trip serializer.
`BigInt(9007199254740993)` is already too late: the Number literal was rounded.
Use `9007199254740993n` or `BigInt('9007199254740993')` instead.

### Language, protocol and cost model

For `vm.evaluate`, `vm.debug`, `uplc.evaluateTransaction` and `uplc.debugTransaction`:

- Language is inferred by the existing script decoder unless overridden with
  `script.language` or `target.language`. Do not label every script V3: a V2 text
  envelope should remain V2. `target.language`, if provided, takes precedence.
- `target.protocol` defaults to **11**; **10** is also accepted.
- Omitted `costModel` uses the provider's built-in model for the resolved target.
- A named profile is supplied as `costModel: '<profile-id>'`; see
  [Cost Model Profiles](/reference/cost-model-profiles/). A profile must match the
  resolved target. An explicit array uses
  `{target: {language: 'PlutusV3', protocol: 11}, parameters: bigint[]}`.
- Results echo `target` and `costModelId`. A debug session retains its original
  arguments, target, budget and request-local cost configuration during replay.

Budget defaults are 10,000,000,000 CPU and 14,000,000 memory units. These are tooling
defaults, **not a live query of network protocol parameters**. The fields above do
not configure `compiler.evaluate` or Quick Eval, which have their own service request types.

## Results and errors

There are two things to check:

1. **Operation results:** inspect `valid`, compilation `diagnostics`/`compiledCode`,
   `success`, or VM `ok`/`status`/`accepted`, depending on the method. Compilation
   diagnostics and failed script evaluations commonly return normally.
2. **Rejected promises:** request/service failures carry `message`, `code`, `status`
   and `body`. Initialization or browser transport errors may be ordinary `Error`
   objects without those fields.

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `invalid-request` | Invalid arguments, integer range, target or cost-model mismatch. |
| 404 | `not-found` | Missing/closed session, or an operation on a disposed client. |
| 422 | `blueprint-error` | Blueprint-generation validation failed during compilation. |
| 500 | `internal` | Internal service/engine failure. |
| 408 | `timeout` | SDK request timeout; the worker is terminated and all sessions are invalidated. |

After a timeout, stale session methods reject with `not-found`; create a new client
and session to start again. Long-running compilation cannot block the UI thread,
but it can exceed the worker's request timeout.

## Hosting and limitations

- Serve the SDK and worker on the same origin as the application for the simplest
  setup. Serve JavaScript with a JavaScript MIME type and `.wasm` as
  `application/wasm`. Avoid SPA fallback pages being served instead of missing assets.
- Deploy the complete bundle together. Hashed launcher/module names protect their
  pairing, but do not mix an SDK, manifest, or worker from different releases.
- Strict Content Security Policies must allow the worker, Wasm compilation and the
  generated/runtime JavaScript bootstrap (which currently uses dynamic evaluation).
  Review this with your application's security policy rather than disabling CSP globally.
- The first load downloads a sizeable engine; the VM-only bundle is smaller. Reuse
  a client when practical and call `dispose()` during application teardown.
- Native BLS12-381 functionality is unavailable. Check `julc.features().bls`; use a
  supported JVM backend when a script needs BLS operations.
- Very deeply nested programs can exceed the browser's Wasm stack. Compiler success,
  local evaluation and a generated blueprint do not certify deployment safety.

For building the bundles yourself, see the repository's
[Wasm README](https://github.com/bloxbean/julc/blob/main/julc-wasm/README.md) and
[playground build guide](https://github.com/bloxbean/julc/blob/main/julc-playground/BUILD_FROM_SOURCE.md).
The distribution contract is recorded in
[ADR-057](https://github.com/bloxbean/julc/blob/main/adr/057-julc-wasm-distribution.md).
