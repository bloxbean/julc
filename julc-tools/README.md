# JuLC tools

Transport-neutral services shared by the playground server and WebAssembly images.
This module was renamed from `julc-playground-core` under
[ADR-057](../adr/057-julc-wasm-distribution.md); it is not published as a JVM library.

`ToolsService` and `ExpressionEvaluator` expose compiler functionality.
`EvaluationPreparation` resolves script language, protocol, budget and request-local
cost model; `UplcToolsService` adds mock-transaction preparation and debugger actions.
The raw-argument preparation and detailed Data JSON codec do not depend on Cardano
Client Lib. The full module still depends on it for transaction and blueprint tooling.

The detailed JSON codec preserves the old parser's behavior, including duplicate map
key replacement, except for the developer-approved correction retaining exact large
constructor tags instead of overflowing signed `long`. JSON inputs affected by that
defect may produce different parameterized script bytes/hashes. Core representations,
compiler lowering and VM semantics are unchanged.

Run `./gradlew :julc-tools:test :julc-playground:test :julc-wasm:test` after changes.
Examples, scenario catalogues and their resources belong to `julc-playground`.
