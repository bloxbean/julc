# ADR-077 M2: corpus check of `vm.applyParams`' canonical-bytes rule

Date: 2026-11-16. Branch `feat/077-m2-apply-params`. This records what was actually run, and nothing more.

## What was checked

D6a's rule: every CBOR wrapper is definite and minimal with nothing after it, and the FLAT program decodes and encodes
back to the same bytes. Each script below was given to `ApplyParams.apply` with no values (`partial: true`,
`checkSchema: false`), and its language from the blueprint preamble, the text envelope or the toolchain. Every
declared parameter schema of every blueprint was also compiled by `DataSchemaChecker` (D6b's schema pass).

The runner was a scratch program outside the repository (`Corpus.java`, on the `julc-tools` test classpath at commit
`4c4e6790`). The two refusals below, and the Aiken outputs, are also asserted by `AikenOracleTest`.

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
values in the canonical encoding (`9f01ff`, `d87980`) give scripts that pass and equal `vm.applyParams`' output.

Other observations:
- Aiken v1.1.0 and v1.0.29-alpha apply constructor index 2^64 - 1 (`d866821bffffffffffffffff80`), and their output
  passes the rule.
- Aiken v1.1.23 crashed ("Whoops! You found a bug in the Aiken compiler", exit 101) on a `Config` value with three
  fields instead of four; it wrote nothing. `vm.applyParams` reports a parameter mismatch for the same value.

## Not run

- The preprod transaction fixtures of `julc-cardano-client-lib` hold no witness scripts in their witness sets; their
  scripts are reference scripts inside the recorded ledger reads, which were not extracted.
- No PlutusTx or Plutarch blueprint, and no other on-chain scripts, were available offline beyond the one above.
- `julc-examples`' own tests were not run to collect the exact values they apply; the schemas were compiled, and
  conforming and one-difference values were checked on the JuLC validator of `JulcBlueprintScalusTest` instead.
