---
title: "MCP closed-loop walkthrough"
description: "Real captured transcript of an AI agent compiling, fixing, and evaluating a JuLC vesting validator through the julc mcp server."
---

This is a verbatim transcript of a JSON-RPC session against `julc mcp` showing the full AI feedback loop: lint pre-flight → broken compile → diagnostic → substantive fix → clean compile → evaluate. Captured during Phase C6 of the AI-readiness rollout (ADR-020).

The session uses one stdio connection. Each step's request/response is summarized; the raw JSON-RPC stream is committed alongside this page at [`closed-loop-walkthrough.json`](/ai/transcripts/closed-loop-walkthrough.json).

## 1. `initialize` — handshake

```json
{"jsonrpc":"2.0","id":1,"method":"initialize",
 "params":{"protocolVersion":"2024-11-05","capabilities":{},
           "clientInfo":{"name":"e2e-v2","version":"0.1"}}}
```

Server replies with `julc-mcp v0.1.0-…` plus a *server instructions* preamble that all clients can read — it points to the starter pack and lists the critical type-class rule, the no-`return`-in-`while` rule, and the `Optional.of/empty` API.

## 2. `tools/list`

```
- julc_ping       — round-trip check
- julc_compile    — Compile JuLC source
- julc_lint       — Pre-compile JuLC lint
- julc_evaluate   — Evaluate a JuLC method
```

## 3. `julc_lint` — catches `Optional.mkNone()` before compile

The agent generated:

```java
public class V {
    static Object none() { return Optional.mkNone(); }
}
```

`julc_lint` returns a single finding:

```json
{
  "rule": "JULC-LINT-OPTIONAL-API",
  "level": "error",
  "message": "Optional.mkNone(...) is not a JuLC API. The real factories are Optional.of(x) and Optional.empty().",
  "suggestion": "Replace `Optional.mkNone()` with `Optional.empty()`."
}
```

Without this rule, the agent would have hit a generic "unknown method" compile error and not known which substitution to make.

## 4. `julc_compile` — first attempt fails on `return` inside a `while` loop

The agent's first cut at a vesting validator manually iterates to check the deadline and uses `return` inside `while`:

```java
@SpendingValidator
public class VestingV1 {
    record VestingDatum(byte[] beneficiary, BigInteger deadline) {}
    record VestingRedeemer() {}

    @Entrypoint
    public static boolean validate(VestingDatum d, VestingRedeemer r, ScriptContext ctx) {
        var i = BigInteger.ZERO;
        while (i.compareTo(d.deadline()) < 0) {
            if (i.equals(d.deadline())) return true;       // ← rejected
            i = i.add(BigInteger.ONE);
        }
        return false;
    }
}
```

Server returns:

```json
{
  "ok": false,
  "diagnostics": [
    {
      "level": "error",
      "message": "VestingV1.java:13:5: 'return' is not supported inside while loop body\n  Hint: Use 'break' to exit the loop early, or restructure to assign to a flag and return after the loop.",
      "line": 13,
      "column": 5
    }
  ]
}
```

(This site does not yet emit a `JULC####` code — see Phase C/E follow-up. The message + hint are still actionable; the agent has everything it needs to fix the code.)

## 5. `julc_compile` — substantive fix succeeds

The agent realizes the manual loop is wrong on two fronts: `return` inside `while` is rejected, and the loop is conceptually unnecessary — the validator should check the *transaction's signatories* and let the time check happen via the script's validity range. Idiomatic JuLC:

```java
@SpendingValidator
public class VestingV2 {
    record VestingDatum(byte[] beneficiary, BigInteger deadline) {}
    record VestingRedeemer() {}

    @Entrypoint
    public static boolean validate(VestingDatum d, VestingRedeemer r, ScriptContext ctx) {
        return ctx.txInfo().signatories().contains(PubKeyHash.of(d.beneficiary()));
    }
}
```

Server returns:

```json
{
  "ok": true,
  "diagnostics": [],
  "scriptSizeBytes": 162,
  "scriptSizeFormatted": "162 B"
}
```

The agent now has a working validator — and it's *idiomatic*: typed datum/redeemer records, `PubKeyHash.of(bytes)` factory (not `(PubKeyHash)(Object) bytes`), no manual loop, no `Optional.mkNone`. Total round-trips: lint (1) + compile-fail (1) + compile-clean (1) = 3 roundtrips, no human in the loop.

## 6. `julc_evaluate` — sanity-check a pure helper

For confidence, the agent ran a pure helper:

```java
import java.math.BigInteger;
public class M {
    public static BigInteger sq(BigInteger x) { return x.multiply(x); }
}
```

```json
{
  "name": "julc_evaluate",
  "arguments": {
    "source": "...",
    "method": "sq",
    "args": [{"int": 7}]
  }
}
```

Response:

```json
{
  "ok": true,
  "result": "49",
  "resultType": "integer",
  "cpu": 367797,
  "memory": 1734,
  "traces": []
}
```

Done. The closed loop works end-to-end.

## How to reproduce

1. Build the CLI: `./gradlew :julc-cli:installDist` from the repo root (or `brew install bloxbean/tap/julc`).
2. Configure your AI tool — see the [setup guide](/ai/) for Claude Code / Claude Desktop / Cursor / Continue snippets.
3. Or replay the raw transcript yourself:
   ```bash
   ( cat closed-loop-walkthrough.json; sleep 5 ) \
       | julc mcp
   ```
   (The JSON file is committed alongside this markdown for reproducibility.)

## What this demonstrates

- **The lint engine catches what the compiler accepts (or fails confusingly on).** `Optional.mkNone()` doesn't exist as JuLC API but is plausible-looking enough that LLMs frequently invent it. The lint rule fires before compile so the agent never wastes a compile cycle on it.
- **Compile diagnostics are actionable.** The `return`-inside-`while` error includes a `Hint:` and a clear file:line. Even without a `JULC####` code on every site (yet), the agent has enough to drive the fix.
- **The agent's fix is type-class idiomatic.** No raw PlutusData. Uses `PubKeyHash.of(...)`, typed records, `ctx.txInfo().signatories().contains(...)`. Matches every rule in the AI starter pack.
- **Evaluation closes the loop.** Agents can run pure helpers as a sanity check — no need to scaffold a test class.

For the full set of MCP tools and their schemas, run `julc mcp` and call `tools/list` (or look at the JSON returned by `julc_compile` etc. in your client of choice).
