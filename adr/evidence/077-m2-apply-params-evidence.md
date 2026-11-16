# ADR-077 M2: evidence for `vm.applyParams`

Date: 2026-11-16. Branch `feat/077-m2-apply-params`. This records what was actually run, and nothing more: the corpus
check of the canonical-bytes rule, the real-blueprints check, the oracles and the mutation checks.

## What was checked

D6a's rule: every CBOR wrapper is definite and minimal with nothing after it, and the FLAT program decodes and encodes
back to the same bytes. Each script below was given to `ApplyParams.apply` with no values (`partial: true`,
`checkSchema: false`), and its language from the blueprint preamble, the text envelope or the toolchain. Every
declared parameter schema of every blueprint was also compiled by `DataSchemaChecker` (D6b's schema pass).

The runner was a scratch program outside the repository (`Corpus.java`, on the `julc-tools` test classpath), run at
commit `4c4e6790` and again after review round 1 at `fe2b8c37` with the same results. The two refusals below, and the
Aiken outputs, are also asserted by `AikenOracleTest`.

## Results

| Source | Scripts | Passed | Refused | Parameter schemas compiled (outside D6b's domain) |
|---|--:|--:|--:|---|
| JuLC `0.1.0-pre21-rc2-8724e2c-SNAPSHOT` (external `julc-examples` build, `plutus.json`, all validators) | 60 | 60 | 0 | 83 (0) |
| JuLC, this branch (`JulcBlueprintScalusTest`'s six-parameter validator) | 1 | 1 | 0 | 6 (0) |
| Aiken v1.1.23 `aiken build` (the `adr077/aiken` project; `julc-compiler` size fixture `aiken-counter-plutus.json`) | 2 + 3 | 5 | 0 | 5 (0), 0 |
| Aiken v1.1.23 `aiken blueprint apply`, canonical values (`adr077/aiken/out`, 11 blueprints × 2 entries) | 22 | 22 | 0 | all (0) |
| Aiken v1.1.23 `aiken blueprint apply`, non-canonical values (`8101`, and fields written `9fff`); the files' other, unapplied entries pass | 2 | 0 | 2 | (0) |
| Aiken v1.1.0 `aiken build` of the same project, and `blueprint apply` (owner; Constr 2^64-1) | 2 + 4 | 6 | 0 | (0) |
| Aiken v1.0.29-alpha `aiken build` of a Plutus V2 project in the old validator syntax, and `blueprint apply` (owner; Constr 2^64-1) | 2 + 4 | 6 | 0 | (0) |
| PlutusTx V2 sum validator (`V2_SUM_SCRIPT_CBOR`, `V2RealTxBudgetTest`, from an on-chain V2 transaction) | 1 | 1 | 0 | — |
| Scalus 1.1.0 `applyArg` of three values to the Aiken, PlutusTx and JuLC scripts (`cborEncoded` and `doubleCborHex`) | 6 | 6 | 0 | — |
| Scalus 1.1.0, generated values on the JuLC script (`JulcBlueprintScalusTest`, 240 applications) | 240 | 240 (byte-equal to `vm.applyParams`) | 0 | — |

The two refusals are the expected ones: Aiken keeps a value's own CBOR encoding inside the applied data constant
(`8101`, a definite list; `d8799fff`, empty fields written indefinite), so the FLAT re-encoding differs from the input
("The script's FLAT encoding is not canonical: decoding it and encoding it again differs from byte 73"). The same
values in the canonical encoding (`9f01ff`, `d87980`) give scripts that pass and equal `vm.applyParams`' output. After
review round 1, the script's data constants are read by the operation's own canonical reader, so the error names the
constant: "The script's data constant number 1 is not in the canonical encoding Plutus writes (expected 9f01ff)".

Other observations:
- Aiken v1.1.0 and v1.0.29-alpha apply constructor index 2^64 - 1 (`d866821bffffffffffffffff80`), and their output
  passes the rule.
- Aiken v1.1.23 crashed ("Whoops! You found a bug in the Aiken compiler", exit 101) on a `Config` value with three
  fields instead of four; it wrote nothing. `vm.applyParams` reports a parameter mismatch for the same value.

## Real blueprints (Verification, "Schema check (I5)")

A scratch program (`RealBlueprints.java`) found every `applyParams(...)` call in `julc-examples`' tests (78 calls in
21 test classes), classified each argument (`PlutusData.bytes` → bytes, `PlutusData.integer` → int, list), and applied
values of those kinds with `vm.applyParams` (`checkSchema: true`, `partial: true`) to every entry of `julc-examples`'
generated blueprint whose title is the tested validator's class name (or that name and a purpose).
- 73 calls matched 30 entries, 111 entry checks: all accepted, `schemaChecked: true`, `remainingParams: 0` (every call
  applies all of its validator's parameters).
- 5 calls (`WingRidersBenchmarkTest`, `SourceMapDebugDemoTest`) test validators that are not in the blueprint.
- The parameter schemas of these blueprints are `ByteArray`, `Int` and `List<ByteArray>` without bounds, so the
  values' kinds decide the result; the values' contents were not taken from the tests.

Aiken: `AikenOracleTest` (fixtures) and `AikenLiveTest` (opt-in, runs when `aiken` is on the PATH: builds the fixture
project in a temporary directory and applies 8 generated conforming value lists with `aiken blueprint apply`, one
value per process, compared at I4's layers; it ran and passed with Aiken v1.1.23). Values from Aiken projects' own
tests (for example the stdlib's) were not collected.

## Mutation checks

Each rule was broken in the source, the named tests run, and the source restored. "Killed" means at least one test
failed.

Round 0 (head `e523de35`), `julc-tools` params tests, 91 mutations: 89 killed, 2 survived, both redundant guards:
- A24, "ScriptDecoder warnings are errors": its only reachable warning (trailing bytes) is refused by the
  canonical-bytes rule first. (Review round 1 removed the ScriptDecoder call; see below.)
- A32/A32c, either duplicate-member guard of `StrictJson` alone; both together (A32b) are killed.

Killed in round 0, by group:
- D6a: language required and exact; `script.language` and `script.params` empty; no UPLC text; blueprint xor envelope;
  envelope `type` and `plutusVersion` recognised and cross-checked; blueprint hash checked, its other-language message,
  and its format; several validators need a selection; duplicate titles; no numeric index; titles required;
  `script.validator` only for blueprints; wrapper minimal, definite and with nothing after it; FLAT round trip; no bytes
  after the program; strict hex; more and fewer values; no `parameters` field declares zero; builtin top-level refused;
  `checkSchema` honoured; trailing JSON documents; value format required; `auto` refused; one shape per object;
  unknown members; integer tokens for `int` and `constructor`; the constructor bound in JSON and on decoded values;
  `fields` required; even-length hex; map entry shape; canonical `cbor` values; `uplc` values confined.
- D6b: annotations are strings; `$ref` siblings; local, existing, single-token references; `~` escapes; nested builtin
  types; unknown `dataType`; keywords on the wrong type; unknown keywords; malformed `enum`, non-negative and integer
  keyword values; `multipleOf` positive; constructor `index` and `fields` mandatory; non-empty applicators; cycles must
  consume; no cycle through `not`; consuming edges allowed; `minimum`, `exclusiveMinimum`, `maximum`,
  `exclusiveMaximum`, `multipleOf`; byte length in bytes, `minLength`, `maxLength`, `enum` and its case; list
  `minItems`, `maxItems`, positional arity, `uniqueItems`; map `minItems`, `maxItems`; constructor index and field count;
  `allOf`, `anyOf`, `oneOf`, `not`; children checked; the work bound.

Round 1 (head `fe2b8c37` plus the live Aiken test), the changed rules, 19 mutations, all killed: strict request strings
(julc-wasm `ApiTest`); script constants through the stack-safe canonical reader; the canonical comparison and
no-trailing-bytes rule of the CBOR reader; unlimited JSON nesting; the iterative encoder's breaks and map entry order
(julc-core differential test); empty `enum` allowed; no bound on a schema's `index`; `uplc` values end at the end of the
text, and comments at the end of their line; empty wrapper refused; `uplc` tags as unsigned 64-bit words, and their
bound; the `work-limit-exceeded` code; explaining spends no work; percent-encoded references refused; every declared
schema compiled first; an unapplied builtin parameter skipped.

Round 2 (review findings M2-C7 and M2-C8), 4 mutations, all killed: a `--` comment ends a number or byte string;
a constructor index may carry a sign; a negative index is refused; only ASCII hex digits are read.

## Not run

- The preprod transaction fixtures of `julc-cardano-client-lib` hold no witness scripts in their witness sets; their
  scripts are reference scripts inside the recorded ledger reads, which were not extracted.
- No PlutusTx or Plutarch blueprint, and no other on-chain scripts, were available offline beyond the one above.
- Values from Aiken projects' own tests.
