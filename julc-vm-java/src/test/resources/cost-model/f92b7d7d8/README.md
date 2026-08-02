# Pinned V1/V2 cost-model vectors

These vectors target cardano-node 11.0.1 / Plutus 1.63.0.0 at
commit `f92b7d7d82622a26caf456a6be33859f697e2cfc`.

They were produced by looking up every constructor of the pinned
`PlutusLedgerApi.V1.ParamName` and `V2.ParamName` enumerations, in declaration
order, in the matching builtin and CEK JSON files. The two 332-value PV11
vectors use model D. `plutus-v1-pv5-A.json` and `plutus-v2-pv7-A.json` are the
166- and 175-value historical prefixes from model A and price every builtin
reachable by V1/V2 before PV9. They are golden test inputs for JuLC's
parser/defaults; later Plutus `master` is not the compatibility target.
