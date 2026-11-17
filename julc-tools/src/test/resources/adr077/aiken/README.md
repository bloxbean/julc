# Aiken oracle fixtures for `vm.applyParams` (ADR-077 I4)

Made on 2026-11-16 with **Aiken v1.1.23+8949565** (`aiken --version`), outside this repository, then copied here.
`AikenOracleTest` compares `vm.applyParams` with them.

## Project

`aiken new julc/params_oracle`, then the stdlib dependency removed from `aiken.toml` (no network) and
`validators/placeholder.ak` replaced by `validators/oracle.ak` (both copied here). `aiken build` wrote `plutus.json`
(Plutus V3, two validators: `oracle.oracle.else` with parameters `owner: ByteArray`, `deadline: Int`,
`config: Config`; `oracle.anydata.else` with `anything: Data`, `limit: Option<Int>`).

## Applications

Each file in `out/` is the blueprint `aiken blueprint apply` wrote:

```
NO_COLOR=1 aiken blueprint apply -i <in> -o <out> -m oracle -v <validator> <CBOR hex>
```

The CBOR values were written by hand, in Plutus's canonical Data encoding (non-empty arrays indefinite, empty ones
definite, bytes over 64 in 64-byte chunks):

| out file | in | validator | CBOR value |
|---|---|---|---|
| oracle-1.json | plutus.json | oracle | `581c` + `aa`×28 (owner) |
| oracle-2.json | out/oracle-1.json | oracle | `1b0000018bcfe56800` (1700000000000) |
| oracle-3.json | out/oracle-2.json | oracle | `d8799f581c` + `bb`×28 + `059f010203ffd87a9f0affff` (Config) |
| owner-short.json | plutus.json | oracle | `4101` |
| anydata-dupmap.json | plutus.json | anydata | `a201020103` (a map with a repeated key) |
| anydata-dupmap-some7.json | out/anydata-dupmap.json | anydata | `d8799f07ff` (Some 7) |
| anydata-maxtag.json | plutus.json | anydata | `d866821bffffffffffffffff80` (Constr 2^64-1 []) |
| anydata-tag2p63.json | plutus.json | anydata | `d866821b800000000000000080` (Constr 2^63 []) |
| anydata-bytes65.json | plutus.json | anydata | `5f5840` + `00`×64 + `4100ff` (65 zero bytes) |
| anydata-int2p80.json | plutus.json | anydata | `c24b0100000000000000000000` (2^80) |
| anydata-nested.json | plutus.json | anydata | `d87a9f9f01a1400580ffd87980ff` |

Two applications with values that are **not** in the canonical encoding, kept as evidence that Aiken keeps a value's
own encoding (so `vm.applyParams`, which refuses such values and scripts, cannot be compared with them):

| out file | CBOR value |
|---|---|
| anydata-noncanonical-input.json | `d87a9f9f01a1400580ffd8799fffff` (an empty field list written `9fff`) |
| anydata-definite-list.json | `8101` (a definite list) |

`out/refusals.txt` lists applications Aiken refused (its schema check, `aiken::blueprint::apply::mismatch`, exit 1),
with these values: `refuse-owner-int` `01` to oracle (in plutus.json); `refuse-deadline-bytes` `4100` (in
oracle-1.json); `refuse-config-mode2` the Config with `d87b9f0aff` as mode, `refuse-config-tags-bytes` with tags
`9f4101ff`, `refuse-config-3fields` without the mode field (all in oracle-2.json; the last one made Aiken v1.1.23 stop
with "Whoops! You found a bug in the Aiken compiler", exit 101, and write nothing); `refuse-limit-bytes`
`d8799f4101ff` and `refuse-limit-index2` `d87b80` to anydata (in anydata-dupmap.json).
