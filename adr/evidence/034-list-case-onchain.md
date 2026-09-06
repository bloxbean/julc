# ADR-034 / #110: on-chain List Case evidence

## Plan and scope review

Base: `main` at `99a69705`; branch `test/110-list-case-onchain-evidence`.
The compiler-generated for-each lowering is already implemented in PR #123.
This milestone completes the outstanding node evidence gate, not a new traversal
family or optimization. ADR-034 remains the governing design, read in full.

Affected module: `julc-e2e-tests`, plus ADR evidence and tracker bookkeeping.
No compiler/VM/encoder changes are planned. Existing NONE/BASELINE goldens,
malformed/decode/trace tests and Java/Truffle/Scalus suites remain the regression
oracle for the already-reviewed implementation.

### Test design

- Compile one spending validator with a typed integer-list redeemer and integer
  expected-result datum, under BASELINE and default PV11_SAFE.
- Traverse the redeemer, accumulating a sum and stopping at zero. The result must
  equal the datum, so non-empty successful scenarios depend on visiting the loop.
- Check O3 rule provenance and the serialized guarded List Case shape, excluding
  Pair/Bool cases from the site count. Check baseline has no guarded List Case.
- Require an actual protocol 11.0 local developer node. Use a fresh funded test
  account and exact transaction-hash UTXO lookup, never an unrelated fallback.
  No reset or restart of the developer devnet.
- Exercise empty, singleton, multiple elements and early termination. Compare
  Java transaction-evaluation ExUnits with Ogmios/Haskell for the same transaction,
  then submit the exact successful transaction and await confirmation.
- Evaluate rejection and malformed redeemers against both backends without
  submitting invalid transactions. Check these are script failures, not transport
  or missing-input failures. A later malformed typed element may fail at the
  strict input boundary before traversal; do not claim break skips that boundary.
- Record artifact hash/size, cases, budgets, transaction IDs and exact test counts.

### Invariants and review

Preserve lowering scope, strict element decoding, guard placement, branch order,
lexical scope and source semantics. Empty input alone cannot establish List Case
execution because NullList bypasses it; at least singleton and multi-element
successes must confirm. A script merely containing a Case is insufficient:
inspect the guarded native-list shape and use a supplied, non-empty list whose
sum is essential to the acceptance result. Exact-budget agreement is required;
transaction confirmation alone would establish only budget sufficiency.

Independent approval of PR #123 is historical. This test/evidence addition needs
its own review before merge; implementer review must not be described as new
independent compiler approval. If the node is unavailable or tests are skipped,
the release gate stays open.

## Validation-driven plan refinement

The first runs confirmed all eight valid transactions and matched Java budgets
against the Yaci Store evaluation API. Rejection responses then exposed
`scalus.uplc.eval` errors: that endpoint is Scalus-backed, not Ogmios/Haskell.
DevKit companion mode has a running Haskell cardano-node 11.0.1 after bootstrap,
but no running Ogmios. Do not treat endpoint names as evaluator provenance.

Use the installed Haskell `cardano-cli conway transaction
calculate-plutus-script-cost online` command with the running node socket to
obtain independent Haskell budgets before each successful submission. The
Scalus-backed endpoint remains an additional differential check. This adds no
service restart or devnet reset. Require an explicit developer container name
for the opt-in test, and retain full command/environment evidence.

## Results

Final direct-Haskell gate run, 2026-09-06 12:09 UTC: **2 parameterized tests
passed, zero failures/errors/skips**. Each profile executes four successful
spends and four invalid-input probes: eight confirmed spends and eight rejected
variants in total. Invalid transactions were evaluated only, never submitted.

Environment: running developer Docker DevKit, companion bootstrap followed by
Haskell `cardano-node 11.0.1` and `cardano-cli 11.0.0.0`, git revision
`97036a66bcf8c89f687ae57a048eecc0389977ef`, linux-aarch64. Live protocol 11.0,
network magic 42, node socket `/clusters/nodes/default/node/node.sock`.
The test asserts the live V3 cost array equals the pinned
`CARDANO_NODE_11_0_1_PLUTUS_V3_PV11` profile. No devnet reset/restart occurred.

BASELINE: **329 FLAT bytes**, zero guarded List Case sites,
hash `cb7fa59acd1a1fa9d0a26f6476d689d8c60f6d3d462436e5f8e3cafd`.
Default PV11_SAFE: **285 FLAT bytes**, exactly one guarded List Case site,
hash `39a96019fb49c18eb6d00179ea544a20c4fa3a346dbbc70c3c1ff53a`.
Inspection runs on the serialized/redecoded program; default and explicit safe
compilation produce identical bytes.

| Input / expected result | BASELINE CPU | PV11_SAFE CPU | BASELINE memory | PV11_SAFE memory |
|---|---:|---:|---:|---:|
| `[]` / 0 | 4,783,546 | 3,843,301 | 19,083 | 15,578 |
| `[7]` / 7 | 8,278,360 | 6,013,008 | 33,151 | 25,176 |
| `[2,3,4]` / 9 | 15,267,988 | 10,352,422 | 61,287 | 44,372 |
| `[2,0,99]` / 2 | 13,062,214 | 9,037,271 | 52,289 | 38,073 |

Every row matches **Java = direct Haskell CLI = configured backend evaluator**
exactly, and the same signed, phase-2-valid bytes confirm on the running node.
These compare complete profiles, including other enabled safe rules, and are
**not O3-only savings**. Confirmation IDs and locking transaction IDs are in
[the transaction record](034-list-case-onchain-transactions.csv).

For datum 7, all three evaluators reject `[8]` with explicit error, integer 7
with `unListData`, and `[bytes(00)]` / `[0,bytes(00)]` with `unIData`. Haskell
assertions require a script evaluation error with the expected `Caused by`
expression, not just a nonzero process exit. These variants reuse the valid
transaction context for phase-2 cost evaluation; their altered witnesses are
not re-signed or submitted. They do not claim phase-1 validity. The later bad
item after zero is rejected by the strict typed boundary, before traversal.

## Reproduction and review

```sh
JULC_E2E_CARDANO_CONTAINER=your-running-devkit-container \
  ./gradlew :julc-e2e-tests:listCaseOnChainTest -Pe2e -PskipSigning=true
```

The selected container must provide the DevKit cardano-cli path and node socket
above and serve the local backend/admin endpoints used by `E2ETestBase`.
The test launches only short-lived CLI queries, no node services. It funds a
fresh test account with the existing faucet and spends only its own exact
locking outputs. CLI arguments use ProcessBuilder without shell interpolation;
output goes to a temporary file to avoid pipe backpressure, with a 30-second
process timeout and cleanup.

The dedicated task always reruns because chain state is not a Gradle input. It
requires explicit `-Pe2e` and a container name, and fails if either profile is
missing or any test is skipped. The existing `:julc-e2e-tests:test -Pe2e` excludes
this extra-environment tag and keeps its prior backend-only setup. Its existing
`BudgetChainValidationTest` was run without the container environment variable:
2 tests passed, zero failures/errors/skips, including a confirmed spending tx.

Self-review corrected three evidence risks: a generic Case count could include
Bool/Pair cases; a backend branded as Ogmios can actually use Scalus; and an
external-state test must not reuse cached results or count skips as completion.
The final test checks the exact guarded list shape, calls Haskell CLI directly,
and enforces fresh, non-skipped execution. Historical reports that inferred
Haskell identity only from the backend endpoint name require separate provenance
verification; this run does not retroactively establish their evaluator identity.

This is implementer review of new test/evidence code. The independent compiler
review of PR #123 remains historical; new independent review is pending before
merging this evidence addition. No compiler, VM or encoder implementation changes
were needed.

## Regression validation

Focused regression command passed, 14 JUnit tests in total (with additional
fixture matrices inside them):

```sh
./gradlew :julc-compiler:test --tests '*O3ListCaseLoweringTest' \
  :julc-benchmark:test --tests '*O3ListCaseBenchmarkTest' --tests '*ListCaseSemanticsTest' \
  :julc-decompiler:test --tests '*ListCaseRecognitionTest' -PskipSigning=true
./gradlew build -PskipSigning=true --rerun-tasks
```

The fresh full build passed in 4m 22s; all 218 tasks executed. Default-build
results: **10,283 passed, 531 existing/profile-inapplicable skips, zero failures
or errors** (10,814 cases). Java and Truffle each passed all 999 PV11 conformance
cases. The separate node gate and existing E2E smoke results above are additional
opt-in runs, not included in this default-build count. Documentation build passed
with 32 pages.

No production Java files, generated encoding rules, library APIs or dependencies
changed. The test-only Gradle task is excluded from default check/build because
it requires the externally managed developer node. No review finding required
widening ADR-034 or changing its compiler implementation.
