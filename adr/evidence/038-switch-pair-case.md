# ADR-038 / issue #125 validation evidence

## Reference and fixture provenance

Base: main `f27f1ca09429967706e6d42c673e56e58afdf270` (merged #128).
The 18 rows in `julc-compiler/src/test/resources/optimization/o4-switch-pre-change-bytes.txt`
were captured before production edits: three actual source validators, each at
NONE/BASELINE/PV11_SAFE with source maps off/on. A clean detached base worktree
then reproduced all rows byte for byte, using the fixture source constants in
`SwitchPairCaseLoweringTest` and its historical-byte test with equality required
also for safe output. The comparison ran fresh with all 35 Gradle tasks executed.
The normal test never regenerates fixtures or accepts missing goldens.

Pinned costs: `cardano-node-11.0.1-plutus-v3-pv11`, parameter SHA-256
`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`.
Java and Truffle use the explicit V3/PV11 target. Scalus is an independent
language-level result/error/trace cross-check, not a certified ledger backend.

## Incremental O4 measurements

These compare **previous PV11_SAFE** with new PV11_SAFE, not BASELINE with all
safe rules. Source maps off; tests additionally cover maps on and PV11_COSTED.

| Fixture / input | FLAT bytes before → after | CPU before → after | Memory before → after |
|---|---:|---:|---:|
| SwitchPair / Pay(7,9) | 224 → 214 | 5,741,464 → 5,249,577 | 24,052 → 22,688 |
| SwitchPair / Pay(0,9), rejected | 224 → 214 | 5,725,464 → 5,233,577 | 23,952 → 22,588 |
| SwitchPair / Cancel | 224 → 214 | 2,744,654 → 2,252,767 | 11,293 → 9,929 |
| NestedSwitchPair / Link(7,End) | 315 → 295 | 6,932,138 → 5,948,364 | 28,650 → 25,922 |
| SingleSwitchPair / Only(7) | 164 → 154 | 4,041,018 → 3,549,131 | 16,856 → 15,492 |

New safe hashes, respectively:

- SwitchPair: `140692d0051a2f9018e7e3e03437f3f18fc691eff974119ef47d7dd6`
- NestedSwitchPair: `899731f6aa1ce40cfcad4083aa2b83304efeb15ac3ff87096d8d8ea3`
- SingleSwitchPair: `c59229b7819fd0a8ca837f797f9e46c01d6e19543b12e41b72d07144`

All sampled malformed inputs rejected before dispatch retain identical budgets
against the previous safe program. Raw PIR tests bypass strict boundaries to
exercise missing fields, invalid field encodings, unknown tags, strict input
failure and selected-body failure inside DataMatch itself. Empty/singleton and
fieldless matches preserve their historical behavior: a singleton has no tag
check, and raw DataMatch does not enforce exact arity. Typed boundaries still do.

## Review and iteration

The production change leaves the dispatch and field extraction builders intact.
The only removed binder is `__match_pair`; tag/fields keep their order and body
indices are recomputed by normal lowering. Self-review identified that callers
of the public PIR API may reference this historical private name. A free-name
check now retains the old expansion in that case; a regression pins the behavior
and absence of an O4 provenance claim. Nested matches, same-name field/pattern
bindings, recursive captures and LetRec shadowing are covered.

Tests also pin profile gates, rule provenance without boundary O4 masking it,
direct-PIR compilation, source position inheritance and serialization/decompiler
smoke behavior. The decompiler continues its existing generic Case recovery;
this is not a claim of reconstructing the original Java switch exactly.

A source probe found an unrelated existing frontend limitation: in a switch
block, `if (condition) { yield x; } yield y;` produced `y` on the tested positive
path already under BASELINE. No frontend change is made in this issue. The
supported conditional-expression form is used to test branch guards. Likewise,
this issue does not introduce Java `when` pattern guards. This finding warrants
separate frontend investigation; it is not a regression caused by Pair Case.

## Direct Haskell-node gate

Run against an already running developer DevKit (no reset/restart):

```sh
JULC_E2E_CARDANO_CONTAINER=<running-container> ./gradlew \
  :julc-e2e-tests:switchPairCaseOnChainTest -Pe2e -PskipSigning=true
```

The task fails rather than silently skipping without the explicit environment.
It checks protocol 11.0 and the exact pinned V3 cost array, serializes and decodes
the artifact, and asserts two UnConstrData Pair Case sites in the safe spending
validator (boundary + switch), versus zero in BASELINE. Default output equals
explicit PV11_SAFE. Integer tag dispatch remains in the script.

Four valid transactions (Pay and Cancel at each profile) confirmed. Eight invalid
redeemer variants (wrong amount, non-constructor, malformed unused field, unknown
tag at each profile) failed in Java, the backend and direct Haskell evaluation.
Only the original valid signed bytes were submitted; invalid witness mutations
were evaluated and restored in finally. The first test run caught a fixture
mistake (a list wrapped as a constructor field); the fixture was corrected to
CCL's explicit constructor-with-fields API before the successful run.

The independent oracle is `cardano-cli conway transaction
calculate-plutus-script-cost online` against the running Haskell node socket.
The backend's evaluation response is only an additional cross-check: it uses
Scalus, despite its Ogmios-shaped envelope. Node/CLI identity is the same local
11.0.1/11.0.0.0 setup documented in ADR-034's direct-Haskell evidence.
The shared subprocess helper was extracted without changing the existing List
Case gate's behavior; that gate is rerun as a regression check.

| Profile / input | Bytes | CPU | Memory |
|---|---:|---:|---:|
| BASELINE / Pay | 327 | 10,398,076 | 39,712 |
| BASELINE / Cancel | 327 | 7,235,935 | 26,453 |
| PV11_SAFE / Pay | 251 | 6,859,673 | 28,039 |
| PV11_SAFE / Cancel | 251 | 4,261,776 | 16,883 |

Each row has exact Java/backend/direct-Haskell budget equality. These rows
include all safe rules, unlike the incremental table above. Transaction IDs
are recorded in `038-switch-pair-case-transactions.csv`.

## Remaining validation

Full build/conformance and Maven-local external examples are in progress.
Independent correctness review remains pending before merge.
