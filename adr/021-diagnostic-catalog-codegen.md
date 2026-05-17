# ADR-021: Diagnostic Catalog, Generated Constants, and Full Compiler Coverage

**Date**: 2026-05-14
**Status**: Proposed

---

## Context

ADR-020 introduced `julc-compiler/src/main/resources/diagnostics.json` as a structured catalog of compiler diagnostic codes (currently 30 entries, `JULC0001`-`JULC0030`) plus a parallel hand-written `DiagnosticCodes.java` file (currently 9 string constants). The two have already drifted: only 9 of the 30 catalog codes have a Java constant, and many compiler failures still have no stable code at all.

The current state has two separate coverage problems:

1. **Catalog/codegen drift.** A code added to JSON can lack a Java handle, and a Java constant can lack a catalog entry. The existing `DiagnosticCatalogConsistencyTest` only catches Java references that do not exist in JSON.
2. **Partial compiler diagnostic coverage.** The 9 active coded diagnostics are not the whole compiler surface. `julc-compiler/src/main/java` still contains many user-visible `CompilerException`, `CompilerDiagnostic`, `collectError(...)`, and `enrichedError(...)` paths across parsing, subset validation, type registration/resolution, PIR generation, stdlib method lowering, UPLC generation, and compiler resource loading.
3. **CLI message/docs drift.** A user can see `[JULC0015] try/catch not supported` in CLI output and then find different wording in `/ai/diagnostics.json`.
4. **Inline string literals.** Existing call sites use string-valued constants or raw strings. Typos are silent; refactoring is by search/replace; there is no type-level handle for the message template, severity, category, or fix.

The previous narrow plan only migrated the currently coded subset. That would make the generated catalog safer but would not solve the larger product problem: AI agents and users still need stable explanations for every compiler failure. This ADR therefore combines code generation with a full compiler diagnostic coverage migration.

---

## Goals

1. **One authoritative catalog.** Every compiler diagnostic code lives in `diagnostics.json`; generated Java constants derive from it.
2. **Full compiler coverage.** Every user-visible compiler error/warning produced by `julc-compiler` has a stable `JULC####` code, template, severity, category, and fix guidance.
3. **Compile-time safety.** Compiler call sites reference generated `DiagnosticInfo` constants, not raw `JULC####` string literals.
4. **No CLI/docs drift.** The message emitted by the compiler comes from the same catalog template published to MCP/docs/AI consumers.
5. **IDE-friendly checkout.** Generated `DiagnosticCodes.java` is checked in so a fresh checkout has no missing symbols.
6. **JVM-independent docs build.** The docs pipeline continues to read JSON directly and does not need to run Gradle.
7. **No blind spots after migration.** CI fails when a new compiler error path is added without an explicit catalog-backed diagnostic.

---

## Decision

### Source of truth

**JSON remains the source of truth at `julc-compiler/src/main/resources/diagnostics.json`.** No move; no format change.

- YAML was considered and rejected. The public contract is already `julc.dev/ai/diagnostics.json`; AI/LLM consumers are JSON-fluent; Java and Node both already parse JSON without extra dependencies.
- Java-first authoring was considered and rejected for this project. JuLC publishes a public JSON catalog from a Node-based docs site, so Java-first would couple docs deploys to Gradle.

### Generated Java constants

`DiagnosticCodes.java` is generated from JSON and checked into git at the existing FQN:

`com.bloxbean.cardano.julc.compiler.error.DiagnosticCodes`

The generated file contains one `DiagnosticInfo` constant per catalog entry, sorted by constant name, plus `all()` and `find(String)` helpers. A `verifyDiagnosticCodes` Gradle task regenerates into a temporary location and fails if the checked-in source differs.

Checking in the generated file is intentional. It keeps IntelliJ usable before any Gradle task has run. The cost is an extra generated diff whenever diagnostics change.

### Catalog owns emitted templates

The catalog gains a `template` field. For compiler-emitted diagnostics, this is the exact emitted message template. Call sites supply contextual parameters; `DiagnosticInfo.format(...)` renders the final text.

Keep the existing `summary` field for docs/MCP compatibility:

- `title`: short label
- `summary`: human/AI explanation
- `template`: exact compiler-emitted message
- `fix`: canonical remediation
- `constant`: Java constant name
- `status`: coverage state

`summary` is not removed or repurposed.

### Coverage states

Each catalog entry gets:

```json
"constant": "TRY_CATCH_UNSUPPORTED",
"status": "emitted"
```

Allowed `status` values:

| Status | Meaning |
|---|---|
| `emitted` | `julc-compiler` currently emits this code from at least one production path. |
| `planned` | Catalog/docs entry exists, but no compiler path emits it yet. This is temporary during migration only. |
| `lintOnly` | The issue is reported by CLI/MCP lint, not by compiler runtime diagnostics. |
| `internal` | Compiler/environment/resource failure. Still coded, but not generally actionable user source guidance. |

After this ADR is implemented, user-source compiler failures should be `emitted`, not `planned`. Any remaining `planned` entries need an explicit rationale in the catalog entry, such as `"notes": "Reserved for future lint-to-compiler migration"`.

### Placeholder style

Use indexed `{0}`, `{1}`, `{2}` placeholders rendered with `java.text.MessageFormat`.

Authoring rule: apostrophes in templates must be doubled (`can''t`) because `MessageFormat` treats `'` as an escape character. CI parses every template and verifies placeholders.

### Typed API

Add a hand-written record:

```java
package com.bloxbean.cardano.julc.compiler.error;

import java.text.MessageFormat;

public record DiagnosticInfo(
        String code,
        String constant,
        CompilerDiagnostic.Level level,
        String category,
        String template,
        String fix) {

    public String format(Object... args) {
        return (args == null || args.length == 0)
                ? template
                : MessageFormat.format(template, args);
    }
}
```

Use the existing `CompilerDiagnostic.Level` for now. A separate top-level `Severity` enum is unnecessary churn for this refactor.

Diagnostic emission helpers should cover every production path:

```java
public void errorWithCode(Node node, DiagnosticInfo info, Object... args);
public void errorWithCode(Node node, DiagnosticInfo info, String suggestionOverride, Object... args);
public void warningWithCode(Node node, DiagnosticInfo info, Object... args);
public CompilerException validationError(DiagnosticInfo info, Node node, Object... args);
public CompilerException validationError(DiagnosticInfo info, String suggestionOverride, Node node, Object... args);
```

The default suggestion is `info.fix()`. Callers may override it when the existing source-specific hint is more precise.

---

## Full Coverage Plan

### Coverage inventory

Audit every diagnostic/error creation point in `julc-compiler/src/main/java`, including:

| Area | Current creation patterns |
|---|---|
| Compiler facade | `validationError(...)`, direct `new CompilerDiagnostic(...)`, direct `new CompilerException(String)` |
| Subset validation | private `error(...)` / `warning(...)` helpers |
| PIR generation | `collectError(...)`, `enrichedError(...)`, direct `CompilerException` |
| Loop PIR helpers | `gen.enrichedError(...)` |
| Type registration | `errorAt(...)`, `CompilerException`, duplicate/circular/newtype paths |
| Type resolution/imports | `CompilerException` for duplicate, unknown, ambiguous, unsupported type paths |
| Stdlib/type method registry | wrong-arity `CompilerException` paths |
| UPLC generation | unsupported/unbound/mutual recursion paths |
| Compiler resources | ledger source index/resource failures |

The inventory output should be committed as a test fixture or markdown table, for example:

`julc-compiler/src/test/resources/diagnostic-coverage.txt`

Each row maps:

```text
source file | line/pattern | current message family | catalog constant | status | notes
```

This gives reviewers a concrete checklist and prevents "we think we covered it" from becoming the acceptance criterion.

### Initial code mapping

The 9 currently coded diagnostics become typed `status: "emitted"` entries:

| Code | Constant |
|---|---|
| `JULC0008` | `ENTRYPOINT_WRONG_PARAMETER_COUNT` |
| `JULC0009` | `ENTRYPOINT_MISSING` |
| `JULC0010` | `VALIDATOR_ANNOTATION_MISSING` |
| `JULC0013` | `PARAM_RAW_PLUTUS_DATA` |
| `JULC0015` | `TRY_CATCH_UNSUPPORTED` |
| `JULC0016` | `THROW_UNSUPPORTED` |
| `JULC0017` | `NULL_UNSUPPORTED` |
| `JULC0018` | `C_STYLE_FOR_UNSUPPORTED` |
| `JULC0019` | `DO_WHILE_UNSUPPORTED` |

Existing catalog entries that already match real compiler failures should also be migrated during this ADR, not deferred:

| Existing code | Error family |
|---|---|
| `JULC0001` | method must have body |
| `JULC0004` | break outside loop |
| `JULC0005` | non-exhaustive switch |
| `JULC0006` | method may not return on all paths |
| `JULC0007` | switch requires sealed interface |
| `JULC0011` | undefined variable |
| `JULC0012` | cannot resolve/unknown/ambiguous type |
| `JULC0014` | arrays unsupported |
| `JULC0020` | floating-point unsupported |
| `JULC0023` | more than 2 mutually recursive bindings |
| `JULC0024` | unknown method |
| `JULC0025` | stdlib method wrong arity |
| `JULC0026` | `@NewType` wrong field count |
| `JULC0027` | unsupported `@NewType` field type |
| `JULC0028` | parse failure |
| `JULC0029` | duplicate type |
| `JULC0030` | circular type dependency |

If an actual compiler error does not fit an existing code, add a new code after `JULC0030`. Do not overload a vague existing code to avoid growing the catalog.

### Internal/environment diagnostics

Some compiler failures are not source-code mistakes, for example missing bundled ledger source resources or inconsistent compiler internals. These should still get stable codes so support/debug output is searchable, but they should be categorized separately:

- `INTERNAL`
- `RESOURCE`
- `CONFIG`

Examples likely needing new codes:

- ledger source index missing
- ledger source listed but not bundled
- failed to read bundled source
- ambiguous on-chain library class
- unsupported bare field accessor / unbound variable in UPLC generation
- unsupported statement/expression families that are internal fallthroughs

These codes can use fixes like "This is likely a JuLC compiler bug or packaging issue; report the diagnostic code and source snippet."

### Do not hide grouped errors

Some paths already collect multiple diagnostics and then throw `new CompilerException(diagnostics)`. Those wrapper throws do not need their own code. The individual diagnostics inside the list do.

The consistency tests should distinguish:

- allowed wrapper throws: `new CompilerException(diagnostics)`
- not allowed after migration: `new CompilerException("...")` for user-visible compiler failures unless routed through a coded helper

---

## Implementation

### Files to modify

| File | Change |
|---|---|
| `julc-compiler/src/main/resources/diagnostics.json` | Add `constant`, `status`, and `template` fields. Mark all migrated compiler diagnostics as `status: "emitted"`. Add new codes beyond `JULC0030` for uncovered compiler/resource/internal failures. Keep `summary` for docs/MCP compatibility. |
| `julc-compiler/build.gradle` | Add `generateDiagnosticCodes` and `verifyDiagnosticCodes` tasks using Groovy `JsonSlurper`. Wire `check.dependsOn verifyDiagnosticCodes`. |
| `julc-compiler/src/main/java/.../error/DiagnosticCodes.java` | Generated and checked in. Contains one `DiagnosticInfo` per catalog entry, sorted by constant, plus `all()` and `find(String)`. |
| `julc-compiler/src/main/java/.../error/DiagnosticInfo.java` | New hand-written record. |
| `julc-compiler/src/main/java/.../error/DiagnosticCollector.java` | Add typed error/warning overloads. Existing string overloads may remain temporarily but are not used by migrated production code. |
| `julc-compiler/src/main/java/.../JulcCompiler.java` | Migrate `validationError(...)`, parse errors, param errors, multi-validator validation errors, and other direct compiler-facade errors to typed diagnostics. |
| `julc-compiler/src/main/java/.../validate/SubsetValidator.java` | Migrate all subset errors/warnings, not only `JULC0015`-`JULC0019`. |
| `julc-compiler/src/main/java/.../pir/*.java` | Migrate `collectError(...)`, `enrichedError(...)`, loop helper errors, unknown method, wrong arity, unsupported statement/expression, switch, lambda, newtype constructor, and return-in-loop errors. |
| `julc-compiler/src/main/java/.../resolve/*.java` | Migrate type registration, type resolution, import ambiguity, ledger resource, and library method registry errors. |
| `julc-compiler/src/main/java/.../uplc/*.java` | Migrate UPLC generator errors. |
| `julc-compiler/src/test/java/.../error/DiagnosticCatalogConsistencyTest.java` | Replace the one-way drift check with schema, generated-source, template, and full production coverage checks. |
| `julc-compiler/src/test/resources/diagnostic-coverage.txt` | Add the audited map from compiler error families to catalog constants. |
| `CONTRIBUTING.md` or `julc-compiler/README.md` | Document how to edit diagnostics and require running `generateDiagnosticCodes`. |

### Files not modified

- `docs/scripts/generate-catalog.mjs` remains JSON-based. It may pass through the new fields without logic changes.
- `docs/.gitignore` remains unchanged.
- `processResources` version filtering for `@julcVersion@` remains unchanged.
- `ExplainDiagnosticTool`, `JulcResources`, `DiagnosticFormatter`, `ReplEngine`, and `DiagnosticDto` should not require API changes if `summary` is preserved and fields are additive.

### Generated file shape

```java
// GENERATED FROM julc-compiler/src/main/resources/diagnostics.json
// DO NOT EDIT - run `./gradlew :julc-compiler:generateDiagnosticCodes` and commit.
package com.bloxbean.cardano.julc.compiler.error;

public final class DiagnosticCodes {
    private DiagnosticCodes() {}

    public static final DiagnosticInfo ENTRYPOINT_WRONG_PARAMETER_COUNT = new DiagnosticInfo(
            "JULC0008",
            "ENTRYPOINT_WRONG_PARAMETER_COUNT",
            CompilerDiagnostic.Level.ERROR,
            "VALIDATOR",
            "@Entrypoint method has wrong parameter count: expected {0}, found {1}",
            "Adjust the entrypoint signature to match the validator annotation.");

    public static java.util.List<DiagnosticInfo> all() { ... }
    public static java.util.Optional<DiagnosticInfo> find(String code) { ... }
}
```

### Migration sequence

1. **Audit coverage first.** Generate the coverage inventory for every compiler error/diagnostic creation point.
2. **Normalize schema.** Add `constant`, `status`, and `template` to existing catalog entries while preserving `summary`.
3. **Add missing codes.** Add new catalog entries for any real compiler error family not covered by `JULC0001`-`JULC0030`.
4. **Add `DiagnosticInfo`.**
5. **Add Gradle codegen/verify tasks.**
6. **Generate and commit `DiagnosticCodes.java`.**
7. **Add typed emission helpers.**
8. **Migrate current active coded paths.** Convert the 9 already-coded diagnostics to `DiagnosticInfo`.
9. **Migrate high-value user-source paths.** Parse, validator shape, subset validation, type resolution, type registration, PIR generation, stdlib arity, switch/lambda/newtype/loop errors.
10. **Migrate internal/resource paths.** Ledger resource loading, ambiguous library class, UPLC/internal fallthrough errors.
11. **Tighten tests.** Only after migration, enable full production-source checks that prevent uncoded compiler errors from reappearing.
12. **Update contributor docs.**

This can be one PR if the migration is manageable. If it becomes too large, split it into two PRs with a hard rule: PR 1 may add codegen and `status`, but PR 2 must complete coverage before ADR-021 is considered accepted. Do not leave `planned` entries for compiler paths indefinitely.

---

## Consistency and Coverage Tests

Extend `DiagnosticCatalogConsistencyTest` to assert:

1. Every catalog entry has valid `code`, `constant`, `status`, `severity`, `category`, `title`, `summary`, `template`, and `fix`.
2. `code` matches `JULC\\d{4}` and is unique.
3. `constant` matches `[A-Z][A-Z0-9_]*` and is unique.
4. `severity` maps to `CompilerDiagnostic.Level`.
5. `category` exists in the top-level `categories` object.
6. Every catalog entry appears in generated `DiagnosticCodes.java`.
7. Every generated constant exactly matches its catalog entry.
8. Every `status: "emitted"` entry is referenced from production compiler source.
9. No raw `"JULC####"` string literals appear in `julc-compiler/src/main/java` except generated `DiagnosticCodes.java`.
10. Every `template` parses with `MessageFormat`.
11. Template placeholder indexes are contiguous from `{0}` to max placeholder, unless a template intentionally reuses an index and documents it.
12. Production source does not add user-visible `new CompilerException("...")`, `new CompilerDiagnostic(...)`, `collectError("...")`, or `enrichedError("...")` paths unless they flow through a `DiagnosticInfo`.
13. The coverage inventory file references only constants that exist in generated `DiagnosticCodes.java`.
14. Every non-wrapper production error creation point appears in the coverage inventory.

The source scan should ignore comments and generated files. It should also support static imports, or the coding convention should require qualified `DiagnosticCodes.CONSTANT` references. Prefer qualified references in migrated code because they are easier to scan reliably.

---

## Verification

- `./gradlew :julc-compiler:generateDiagnosticCodes` regenerates `DiagnosticCodes.java`; `git diff` is empty afterward.
- `./gradlew :julc-compiler:verifyDiagnosticCodes` passes; deliberate manual edits to `DiagnosticCodes.java` fail with a clear message.
- `./gradlew :julc-compiler:test` passes, including full diagnostic coverage tests.
- `./gradlew :julc-cli:test` passes, especially `ExplainDiagnosticToolTest`, `JulcResourcesTest`, `DiagnosticFormatterTest`, and MCP compile/lint tests.
- `./gradlew test` passes.
- `(cd docs && npm run build)` succeeds and `docs/dist/ai/diagnostics.json` contains the additive fields.
- Manual smoke: compile representative failures for parser, subset, validator annotation, type resolution, stdlib wrong arity, switch exhaustiveness, newtype, and internal/resource cases. Each should include a stable `[JULC####]` code.
- Negative checks:
  - Add a raw `"JULC0099"` literal in compiler production source: test fails.
  - Add `new CompilerException("new uncoded failure")` in production source: test fails.
  - Add `status: "emitted"` without a production reference: test fails.
  - Add a catalog template with a malformed apostrophe/placeholder: test fails.

---

## Current Implementation Status

The first implementation slice is intentionally limited to diagnostic catalog code generation and migration of the 9 diagnostics that already emitted stable `JULC####` codes before this ADR. The full coverage model described above is the desired end state, not fully implemented yet.

Not done yet:

- Full diagnostic coverage is not implemented yet. Many compiler error paths still do not emit stable `JULC####` codes.
- The strict CI rule for "no uncoded compiler errors" is intentionally not enabled yet. It should be enabled only after the full compiler diagnostic coverage migration is complete.
- The working tree currently contains unrelated AI/docs/assets changes alongside the diagnostic work, so review/merge should use careful PR splitting rather than treating the entire local diff as one diagnostic PR.

---

## Consequences

### Positive

- The catalog becomes a real compiler contract, not just partial docs.
- Every compiler failure shown to users/agents can be looked up by stable code.
- CLI output and `/ai/diagnostics.json` share the exact emitted template.
- Generated typed constants remove typo-prone raw code strings.
- CI blocks future uncoded compiler errors.
- Docs CI remains JVM-free.
- IntelliJ works from a fresh checkout because generated Java is checked in.

### Negative

- This is a larger migration than the original codegen-only plan.
- PR diffs may touch many compiler files plus JSON and generated Java.
- Some internal error families need new catalog codes and wording decisions.
- Contributors must run `generateDiagnosticCodes` after changing the catalog.
- `MessageFormat` apostrophe escaping is an authoring footgun, mitigated by tests.

### Neutral

- Lint-only diagnostics remain outside the compiler catalog unless they correspond to a real compiler diagnostic. Lint rules may continue to carry `JULC-LINT-*` IDs and optionally point to a `JULC####` diagnostic.
- `summary` remains a docs/MCP field even when `template` becomes the compiler-emitted field.

---

## Open Questions

1. **Exact number of new codes.** The audit should decide whether broad families like "unsupported expression" get one parameterized code or several specific codes. Prefer specific codes for common user mistakes; use broader internal codes for impossible/fallthrough paths.
2. **Strict qualified references.** The tests are simpler if production code references `DiagnosticCodes.X` instead of static imports. This ADR leans toward qualified references for diagnostics.
3. **Coverage inventory format.** A markdown table is review-friendly; a machine-readable JSON/CSV fixture is easier to test. Prefer machine-readable if the test will consume it.
4. **Internal/resource codes in public docs.** They should remain in `/ai/diagnostics.json`, but fixes should clearly say when the issue is likely a JuLC bug or packaging problem.

---

## Out of Scope

- Localisation/i18n.
- Splitting the catalog into multiple files. Revisit when the catalog becomes large enough to be painful.
- Migrating CLI/MCP lint rule IDs into the compiler diagnostic namespace.
- Changing the public shape of `CompilerDiagnostic` beyond attaching catalog-backed messages through existing fields.
