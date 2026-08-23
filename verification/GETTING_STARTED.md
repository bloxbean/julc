# Getting started with `julc verify`

This guide starts with a new JuLC project and produces a formal-verification
certificate through either the local Lean/Blaster toolchain or Docker. The
annotation profiles generate the Lean property for you; no Lean knowledge is
required for the examples below.

## 1. Install JuLC

JuLC requires Java 25 or newer when using the JVM distribution. Install the
CLI with Homebrew:

```bash
brew install bloxbean/tap/julc
julc --version
```

Alternatively, download the CLI from the JuLC GitHub releases. For a checkout
of this repository, build and use the development JAR:

```bash
./gradlew :julc-cli:shadowJar
java -jar julc-cli/build/libs/julc.jar --version
```

The remaining examples use `julc`. When testing a development JAR, replace
`julc` with `java -jar /absolute/path/to/julc-cli/build/libs/julc.jar`.

## 2. Choose an execution backend

Both backends check the same generated theorem, exact UPLC, property IR, and
dependency pins.

### Docker backend

Install Docker and make sure its daemon is running:

```bash
docker version
```

No host Lean or Z3 installation is needed. JuLC builds its checksum-pinned
verification image on the first run; subsequent builds use Docker's layer
cache. Dependency acquisition is allowed online, then proof commands run in a
container with `--network none`. Only the verification workspace is mounted;
the Docker socket and host home directory are not mounted.

The embedded image currently supports Linux container targets `amd64` and
`arm64`.

### Local backend

Install:

- Git;
- Lean and Lake 4.24.0, normally through `elan`; and
- `xxd`.

Ensure the elan shims are on `PATH`:

```bash
export PATH="$HOME/.elan/bin:$PATH"
elan toolchain install leanprover/lean4:v4.24.0
lean --version
lake --version
git --version
xxd -h 2>&1 | head -n 1
```

The generated workspace contains `lean-toolchain`, so elan selects the pinned
Lean version. JuLC accepts system Z3 only at version 4.15.2; otherwise it
downloads the official archive into the workspace-local `.julc/tools` cache,
checks its pinned SHA-256, and rechecks its version. The first run also acquires
the pinned Lean dependencies, so it needs network access during acquisition.
The proof phase is then guarded against dependency downloads.

`--backend auto` prefers an exact local toolchain and otherwise uses Docker
when the Docker CLI is available. Use an explicit backend in CI and release
evidence so the environment is unambiguous.

## 3. Create a project

The basic template is the shortest path:

```bash
julc new signer-demo
cd signer-demo
```

It creates `julc.toml`, `src/`, `test/`, and installed compiler/stdlib sources.
Gradle and Maven templates are also available, but the one-command verifier
currently operates on the JuLC project layout rooted at `julc.toml` and `src/`.

## 4. Add an authorization property

Create `src/AuthorizedStateValidator.java`:

```java
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;

@RequiresSigner("datum.owner")
@SpendingValidator
class AuthorizedStateValidator {
    record Datum(byte[] owner) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return ContextsLib.signedBy(ctx.txInfo(), datum.owner());
    }
}
```

JuLC validates typed datum/redeemer roots before this method runs. Record tags
and arities, variants, primitive shapes, optionals, lists, maps, and productive
recursive values must match the declared Java type exactly. No strictness
annotation or compiler option is required. An explicitly raw `PlutusData`
boundary remains raw and must be validated manually by the contract.

The annotation states the property. It does not change compiler lowering or
make a vulnerable validator pass. JuLC resolves `datum.owner` through the
compiler-owned type model and checks that it is a supported byte-string/key-hash
field.

The scaffolded `AlwaysSucceeds` validator may remain in the project; the
`--validator` option selects one exact title. You can remove the starter later
if it is not needed.

For an explicit `@MultiValidator`, `--validator` is the base Java class name
and `--purpose` selects its exact interface. For example:

```bash
julc verify . --validator Protocol --purpose spending --backend docker
```

Its CIP-57 entry is named `Protocol.spend`; a minting interface is
`Protocol.mint`. Both entries bind to the same compiled program and script
hash. See the
[purpose-indexed blueprint guide](../docs/src/content/docs/guides/purpose-indexed-blueprints.md).

## 5. Build and test normally

```bash
julc build
julc check
```

This is a useful development check, but neither command is a formal proof.
`julc verify` performs its own exact build before generating the proof
workspace, so a separate `julc build` is not required for verification.

## 6. Verify with Docker

From the project root:

```bash
julc verify . \
  --validator AuthorizedStateValidator \
  --backend docker
```

The first Docker run can take several minutes because it builds the pinned
image and acquires exact Lean package commits. A successful run prints output
similar to:

```text
Preparing formal verification for AuthorizedStateValidator ...
  Resolving property and exact script artifact ... OK [64 ms] - julc.requires-signer/v1
  Generating hash-bound verification workspace ... OK [40 ms] - .../verification/authorized-state-validator
Running verification ...
  Validating workspace and runner plan ... OK [17 ms]
  Checking artifact, property, and generated-source hashes ... OK [55 ms]
  Selecting verification backend ... OK [0 ms] - docker
  Preparing Docker backend (first run may take several minutes) ... OK [294 ms] - sha256:...
  Acquiring pinned Lean dependencies ... OK [2.8 s]
  Building pinned Lean dependencies ... OK [2m 1s]
  Checking pinned dependency revisions ... OK [600 ms]
  Checking property non-vacuity ... DONE [12.6 s] - non-vacuous
  Proving required signer ... DONE [1.8 s] - SMT-VALID - required signer established

SMT-VALID: all-properties-established
Property: julc.requires-signer/v1 (datum.owner)
Workspace: .../verification/authorized-state-validator
Certificate: .../verification-result.json
```

Local and Docker runs show the same line-oriented stages and elapsed times, so
a long dependency build or proof does not look stalled. Tool output remains in
the hash-accounted files under `verification-results/`; on failure the progress
line points to the relevant log instead of mixing unauthenticated subprocess
output into the console result.

The certificate records `backend: docker`, the immutable built image ID, exact
artifact and script hashes, dependency commits, fuel, generated-source hashes,
and the classified properties.

## 7. Verify with the local toolchain

```bash
julc verify . \
  --validator AuthorizedStateValidator \
  --backend local
```

If the default output directory already contains a generated workspace, choose
one of these operations:

```bash
# Rebuild the contract and regenerate all generator-owned files.
julc verify . --validator AuthorizedStateValidator --backend local --force

# Rerun the already generated, hash-bound workspace without rebuilding it.
julc verify run verification/authorized-state-validator --backend local
```

You can also generate into a backend-specific directory:

```bash
julc verify . --validator AuthorizedStateValidator \
  --backend local --out-dir verification/authorized-local
```

Generated `.lake`, `.julc`, and result directories are workspace caches and
evidence. Do not copy a certificate to a different artifact and do not edit
generated Lean: manifest and source hashes make such changes fail preflight.

## 8. Read the result

The CLI prints the exact certificate path. With the default output it is:

```bash
jq '{outcome, reason, backend, backendIdentity, artifact, properties}' \
  verification/authorized-state-validator/verification-result.json
```

Result meanings and process exits are:

| Result | Exit | Meaning |
|---|---:|---|
| `SMT-VALID` | 0 | Blaster established every requested property under the recorded model and bounds. |
| `KERNEL-PROVED` | 0 | An admitted ordinary Lean proof was kernel-checked without relying on a Blaster result for that property. |
| `REFUTED` | 3 | Blaster found a context accepted by the exact artifact that violates the property. |
| `UNDETERMINED` | 2 | The requested result could not be classified as proof or refutation. |
| `COULD-NOT-EVALUATE` | 2 | Setup, support, fuel, vacuity, tamper, or another fail-closed condition prevented the claim. |

Malformed input or preflight failures use exit 1. Never treat exit 2, an empty
counterexample set, or compilation success as proof.

For `REFUTED`, the CLI prints the retained Blaster log path. For an
always-failing validator, the non-vacuity check reports
`COULD-NOT-EVALUATE/property-vacuous` and skips the main theorem rather than
recording a vacuous success.

## 9. Use another built-in profile

### Stateful spending

```java
@RequiresSigner("datum.owner")
@Monotonic(
    current = "datum.state",
    next = "redeemer.nextState",
    relation = Relation.GREATER_THAN)
@PreservesValue(output = OutputSelection.SINGLE_CONTINUING_OUTPUT)
@SpendingValidator
class StateMachine { /* validator implementation */ }
```

Run it with a fuel value appropriate for the artifact:

```bash
julc verify . --validator StateMachine --backend docker --fuel 3000
```

The v1 property includes signer membership, strict boundary decoding, exactly
one same-full-address continuing output, structural value and authority
preservation, successor/redeemer commitment, and strict state increase. See
[`c6/README.md`](c6/README.md) and its authorized fixture for a complete
implementation.

### Controlled mint or burn

```java
@ControlledMint(
    authority = "4a554c435f5645524946595f415554484f524954595f303030303031",
    tokenName = "4a554c43",
    quantity = 1,
    action = MintAction.MINT)
@MintingValidator
class ControlledTokenPolicy { /* validator implementation */ }
```

Use `MintAction.BURN` with the same positive magnitude to require the negative
on-chain quantity:

```bash
julc verify . --validator ControlledTokenPolicy --backend local --fuel 5000
```

The fixed authority and token are property literals, not values selected by an
untrusted redeemer. See [`c7/README.md`](c7/README.md) for mint, burn, refuted,
and vacuous examples.

All four annotation frontends now lower through the same closed DSL semantics:
`@RequiresSigner`, `@ControlledMint`, and the complete
`@RequiresSigner + @PreservesValue + @Monotonic` profile bind canonical DSL IR
before Lean generation. An annotation is concise syntax for a reviewed DSL
property, not a separate theorem implementation. Profile-specific proof names
and result messages remain for usability.

## 10. Stable typed verification DSL (API v1)

ADR-029 stabilizes the reviewed E.4 DSL surface as Java API version 1 and
canonical property schema 1. New `dsl-init` invocations generate schema 1 by
default, and the old milestone formats are not selectable. The builder
still executes trusted project Java in a bounded worker, and proof completion
is still subject to the recorded solver and execution bounds.

Start from an already buildable JuLC spending project whose datum has a
`byte[] seller` and `BigInteger price`:

```bash
julc verify dsl-init . --validator Sale \
  --package evidence --class SaleModel \
  --out build/verification-dsl/src/evidence/SaleModel.java

javac -cp julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/SaleModel.java \
  SellerPaymentSpec.java

julc verify dsl . --validator Sale \
  --spec-class evidence.SellerPaymentSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source SellerPaymentSpec.java --backend local --force
```

The generated model's `properties(...)` method, typed datum/redeemer wrappers,
ledger expressions, collection operations, authorization algebra, value
algebra, governance data, and reviewed schema-1 adapters are the stable
construction surface. Concrete `dsl.ir` node constructors, renderers, worker
protocols, and arbitrary Lean are not public construction APIs.

For an inline output datum, schema 1 can strictly decode it through the
compiler-owned datum type without a cast:

```java
var contract = new SaleModel();
var successor = contract.continuingOutputs().at(integer(0)).exists(output ->
    output.datum().whenInline(raw -> contract.decodeDatum(raw, next ->
        next.owner().eq(expectedOwner))));
```

Malformed data makes this expression false. The decoder target cannot be
selected through annotation text or a Lean name. For an exact one-element list,
prefer `outputs.whenSingleton(output -> ...)`; it is a closed typed pattern and
does not expand into a recursive count plus indexed lookup.

The property builder constructs typed symbolic expressions; it does not run the
contract. JuLC independently validates its bounded canonical AST, imports the
exact compiled UPLC, checks non-vacuity, and binds the generated Lean and
property IR into the certificate. Use `--backend docker` instead of `local` for
the proof workspace if desired; the Java property worker is still local and
trusted-source-only in E.3.

See [`dsl/README.md`](dsl/README.md) and [`e3/README.md`](e3/README.md) for the
property source and evidence controls.

## 11. Typed one-shot minting DSL

The trusted Java property workflow supports minting. Generate a purpose-aware
model, compose the reviewed minting expressions in a specification, and select
the minting interface explicitly:

```bash
julc verify dsl-init . --validator TokenPolicy --purpose minting \
  --package evidence --class TokenPolicyModel \
  --out build/verification-dsl/src/evidence/TokenPolicyModel.java

javac -cp julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/TokenPolicyModel.java \
  OneShotMintSpec.java

julc verify dsl . --validator TokenPolicy --purpose minting \
  --spec-class evidence.OneShotMintSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source OneShotMintSpec.java --backend docker --fuel 5000 --force
```

The JuLC JAR entry is required when using the GraalVM native CLI because the
trusted property builder still executes in a bounded child JVM. It is harmless
with the JVM CLI. Use `;` instead of `:` as the classpath separator on Windows.

The generated certificate binds the canonical schema-1 DSL hash, capability inventory,
selected blueprint entry, exact shared UPLC/hash, generated Lean, domain
bridge, dependency pins, and execution bounds. Mint association-list order and
duplicates are preserved; duplicate or malformed entries for the current
policy cannot disappear behind first-match lookup. See
[`e4a/README.md`](e4a/README.md) for a complete specification and local/Docker
commands.

## 12. Compositional, rewarding, and certifying DSL

The compositional DSL accepts freely composed guarantees built only from the
reviewed typed nodes. Each property keeps the exact-execution and optional
ledger-domain premise in a generator-owned theorem envelope; user code cannot
insert either premise or raw Lean. A rewarding specification can therefore
combine existing signer predicates with the new duplicate-preserving raw
withdrawal traversal:

```java
var contract = new RewardingModel();
var ownMinimum = contract.context().txInfo().withdrawals().existsEntry(
        (credential, amount) -> credential.eq(contract.rewardingCredential().typed())
                .and(new IntegerExpr(amount.node()).ge(integer(1_000_000))));
var authorized = contract.context().txInfo().signatories()
        .contains(keyHash(AUTHORITY));

return contract.properties(
        property("reward.authorized-minimum",
                DslDomain.VALID_REWARDING_V3_PINNED,
                contract.redeemer().isPresent()
                        .and(authorized)
                        .and(ownMinimum)));
```

Generate and verify it with an explicit purpose:

```bash
julc verify dsl-init . --validator Rewards --purpose rewarding \
  --package evidence --class RewardingModel \
  --out build/verification-dsl/src/evidence/RewardingModel.java

javac -cp julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/RewardingModel.java RewardingSpec.java

julc verify dsl . --validator Rewards --purpose rewarding \
  --spec-class evidence.RewardingSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source RewardingSpec.java --backend local --fuel 5000 --force
```

The withdrawal meaning is structural association-list existence. Duplicate
credentials are neither collapsed nor summed. A satisfying duplicate is
enough for `exists`; choose a stronger reviewed formula if a policy needs a
different duplicate rule. See [`e4b/README.md`](e4b/README.md) for generic
multi-property composition and [`e4c/README.md`](e4c/README.md) for the full
rewarding controls and Docker command.

A certifying specification uses the same compositional schema-1 pipeline. The
selected `publish` interface supplies the current certificate and its ledger
index; user property code cannot replace either root:

```java
var contract = new CertifyingModel();
var currentCertificate = contract.context().txInfo().certificates().containsAt(
        contract.certificateIndex(), contract.certificate());
var authorized = contract.context().txInfo().signatories()
        .contains(keyHash(AUTHORITY));

return contract.properties(
        property("certificate.authorized-update",
                DslDomain.VALID_CERTIFYING_V3_PINNED,
                contract.redeemer().isPresent()
                        .and(contract.certificate().isKind(TxCertKind.UPDATE_DREP))
                        .and(currentCertificate)
                        .and(authorized)));
```

Generate the purpose-specific metamodel and verify it with:

```bash
julc verify dsl-init . --validator Certificates --purpose certifying \
  --package evidence --class CertifyingModel \
  --out build/verification-dsl/src/evidence/CertifyingModel.java

javac -cp julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/CertifyingModel.java CertifyingSpec.java

julc verify dsl . --validator Certificates --purpose certifying \
  --spec-class evidence.CertifyingSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source CertifyingSpec.java --backend local --fuel 5000 --force
```

`TxCertKind` is a closed enum covering the 11 pinned Conway certificate
constructors. `containsAt` is ordered indexed membership: negative and
out-of-range indices are false, and duplicates are retained. Under
`VALID_CERTIFYING_V3_PINNED` this membership is already a ledger-domain fact;
the certificate-kind and authority clauses are the additional policy. See
[`e4d/README.md`](e4d/README.md) for the complete local/Docker evidence matrix.

## 13. Generic contract types and collections

ADR-022 introduced the datum and redeemer API generated from the selected
compiler-owned contract schema. It is now part of the single public schema 1:

```bash
julc verify dsl-init . --validator CollectionGate --purpose spending \
  --out build/verification-dsl/src/evidence/CollectionGateModel.java
```

For a contract with nested records, optionals, lists, maps, and a sealed
redeemer, a generated-model-only property can look like:

```java
var contract = new CollectionGateModel();
var guarantee = contract.datum().exists(datum ->
        contract.context().txInfo().signatories()
                .contains(datum.config().owner())
                .and(datum.config().minimum().isPresent()
                        .or(datum.config().minimum().isEmpty()))
                .and(datum.config().values().exactlyOne(v -> v.gt(integer(0))))
                .and(contract.redeemer().exists(action ->
                        action.whenUse(use -> datum.config().balances()
                                .lookupFirst(use.key()).isPresent()))));

return contract.properties(property("collections.authorized",
        DslDomain.NONE, guarantee));
```

Compile and run it through the same bounded trusted worker used by earlier DSL
milestones:

```bash
javac -cp julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/CollectionGateModel.java \
  CollectionGateSpec.java

julc verify dsl . --validator CollectionGate --purpose spending \
  --spec-class evidence.CollectionGateSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source CollectionGateSpec.java --backend local --fuel 2000 --force
```

Lists and maps retain order and duplicates. `lookupFirst` returns the first raw
matching map entry, `lookupAll` returns every match in order, and
`structurallyEquals` compares the encoded ordered structure rather than a
unique-key/extensional mathematical map. Negative or out-of-range list indices
return an empty option. Wrong tags, arities, fields, element types, binder
types, or forged nominal IDs fail in parent-process admission.

The compiler currently erases source `@NewType` identity from its contract
schema, so the generated model sees the underlying representation and does not claim
nominal newtype separation. See [`e4e/README.md`](e4e/README.md) for type
controls and [`e4f/README.md`](e4f/README.md) for reproducible local/Docker
evidence.

## 14. Typed non-value transaction context

The schema-1 model includes the closed ledger-context surface together with the
compiler-projected contract model. Generate it with:

```bash
julc verify dsl-init . --validator LedgerGate --purpose spending \
  --out build/verification-dsl/src/evidence/LedgerGateModel.java
```

A property can compose the generated contract datum/redeemer API with ordered
reference inputs, strict guarded ledger variants, and duplicate-preserving
witness maps:

```java
var contract = new LedgerGateModel();
var tx = contract.context().txInfo();

var referenceShape = tx.referenceInputs().at(integer(0)).exists(input ->
        input.resolved().datum().isInline()
                .and(input.resolved().address()
                        .paymentCredential().isScript())
                .and(input.resolved().referenceScript().isEmpty()));

var datumWitnessPresent = tx.datums().existsEntry((hash, raw) -> bool(true));

return contract.properties(
        property("ledger.reference-shape",
                DslDomain.VALID_SPENDING_V3_PINNED, referenceShape),
        property("ledger.datum-witness",
                DslDomain.VALID_SPENDING_V3_PINNED, datumWitnessPresent));
```

Compile the generated model and trusted specification, then run the same
bounded worker/proof pipeline:

```bash
javac -cp julc.jar -d build/verification-dsl/classes \
  build/verification-dsl/src/evidence/LedgerGateModel.java \
  LedgerGateSpec.java

julc verify dsl . --validator LedgerGate --purpose spending \
  --spec-class evidence.LedgerGateSpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source LedgerGateSpec.java --backend local --fuel 5000 --force
```

Use `--backend docker` to isolate Lean/Blaster. The GraalVM native CLI uses the
same command but still needs an installed child JVM and the JuLC JAR on
`--spec-classpath` to execute trusted Java property code.

Input/list and witness/redeemer map order and duplicates remain observable.
`lookupFirst` is not `lookupAll`; continuing outputs use the complete address
of the first resolved own input. Inline datum and redeemer payloads remain
opaque raw `Data`. Schema 1 additionally permits strict
decode through the compiler-owned datum type, but still permits neither
unchecked casting nor raw-data equality. See [`e4g/README.md`](e4g/README.md) for the
reproducible local/Docker/native evidence and current limitations.

## 15. Compositional authorization

The schema-1 typed contract and ledger surface includes distinct authorization
relations. Generate the model with:

```bash
julc verify dsl-init . --validator Treasury --purpose spending \
  --out build/verification-dsl/src/evidence/TreasuryModel.java
```

For a fixed three-member committee, “exactly two approved keys and no other
signer” is written as two visibly separate constraints:

```java
var contract = new TreasuryModel();
var auth = contract.authorization();
var committee = auth.authorities(
        auth.fixed("41".repeat(28)),
        auth.fixed("11".repeat(28)),
        auth.fixed("22".repeat(28)));

return contract.properties(property("treasury.two-of-three-approved-only",
        DslDomain.VALID_SPENDING_V3_PINNED,
        committee.exactlySigned(2)
                .and(committee.noUnexpectedSigners())));
```

`exactlySigned(2)` counts distinct committee members that signed; it does not
exclude an outsider. `noUnexpectedSigners()` adds that independent allow-list
constraint. Reordering and duplicates do not affect authorization, while raw
list operations elsewhere retain order and duplicates. A compiler-owned
`byte[]` field can become an authority only through
`auth.fromContractBytes(field)`, and a generated `List<byte[]>` wrapper offers
`asAuthorities()`. Dynamic lists may be empty, so prefer a positive threshold
when empty `allSigned()` would be unintended.

At the current pinned Blaster/Z3 revision, the retained positive SMT evidence
discharges `exactlySigned(2)` quickly, while the combined threshold plus
`noUnexpectedSigners()` query did not finish within a ten-minute calibration
window. The allow-list relation is still admitted and kernel-tested, but such
an undetermined run is `COULD-NOT-EVALUATE`, not successful verification.

Applied `@Param` values are not accepted as authority roots yet. A parameter
declaration in a blueprint does not identify the value used to produce a
deployed script. This remains fail-closed until preflight can reconstruct
parameter application and match both exact UPLC bytes and the script hash.

Fixed key hashes containing byte `00` are also rejected in this experimental
slice. The pinned Blaster UPLC-constant and Lean property-literal paths do not
yet agree for that byte. This is a solver limitation, not a Cardano key-hash
rule; JuLC fails closed instead of silently changing the authority identity.

Compile and invoke `julc verify dsl` as in the preceding examples. Reproducible positive,
refuted, vacuous, exact-VM, and kernel controls are in
[`e4h/README.md`](e4h/README.md).

## 16. Certificate payloads

Schema 1 includes guarded payload access for all 11 pinned V3 transaction
certificate constructors and the nested `Delegatee` and `DRep` sums. Generate
a certifying model with:

```bash
julc verify dsl-init . --validator Certificates --purpose certifying \
  --out build/verification-dsl/src/evidence/CertificatesModel.java
```

Payloads are available only inside the matching constructor eliminator:

```java
var contract = new CertificatesModel();
var expectedPool = LedgerExpressions.publicKeyHash(bytes("41".repeat(28)));

return contract.properties(property("certificate.pool-retirement-bound",
        DslDomain.VALID_CERTIFYING_V3_PINNED,
        contract.certificate().whenPoolRetire((pool, epoch) ->
                pool.eq(expectedPool).and(epoch.le(integer(100))))));
```

Transaction-list traversal uses the same guarded surface:

```java
contract.context().txInfo().certificates().exists(candidate ->
        candidate.whenDelegStaking((credential, delegatee) ->
                delegatee.whenStakeVote((pool, drep) ->
                        pool.eq(expectedPool).and(drep.isAlwaysAbstain()))));
```

Wrong tags, arities, payload kinds, forged fields, and projections outside a
matching guard fail closed. DRep and cold/hot committee credentials use
role-specific Java wrappers while retaining the pinned credential encoding.
The retained positive solver fixture proves a DRep-registration deposit
constraint; nested credentials and pool-retirement payloads are additionally
covered by Lean kernel controls and exact-VM tests. See
[`e4i/README.md`](e4i/README.md).

## 17. Multi-asset value algebra

Schema 1 keeps output `Value`, transaction `MintValue`, and checked `ValueDelta`
roles distinct. Generate the model with:

```bash
julc verify dsl-init . --validator Sale --purpose spending \
  --out build/verification-dsl/src/evidence/SaleModel.java
```

The API makes four different meanings visible. They are not interchangeable:

```java
var contract = new SaleModel();
var policy = LedgerExpressions.currencySymbol(bytes("11".repeat(28)));
var token = LedgerExpressions.tokenName(bytes("746f6b656e"));
var firstOutput = contract.context().txInfo().outputs().at(integer(0));

var guarantee = firstOutput.exists(output ->
        output.value().rawPolicies().exists(entry ->
                entry.whenWellFormed((actualPolicy, tokens) ->
                        actualPolicy.eq(policy)))
        // Exact pinned V2.valueOf: first matching policy/token entry.
        .and(output.value().quantityFirst(policy, token).ge(integer(10)))
        // All matching duplicates are summed; absence/malformed data is none.
        .and(output.value().quantitySumStrict(policy, token)
                .exists(quantity -> new IntegerExpr(quantity.node())
                        .ge(integer(10))))
        // Ignores ordering and zero-sum decomposition after strict validation.
        .and(output.value().extensionallyEquals(output.value()))
        // Observes the exact ordered nested association-list representation.
        .and(output.value().structurallyEquals(output.value())));

return contract.properties(property("sale.multi-asset-payment",
        DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
```

Strict-summed absence is `none`; an explicitly encoded zero is `some 0`.
Malformed policy/token entries also fail closed. Extensional equality and
pointwise order treat an absent asset as zero only after validating the entire
raw value. Checked add, negate, and scale operations return `ValueDeltaOption`;
they never claim the result is a ledger-valid output value.

Payment aggregation scope is explicit:

```java
var fullAddressPayment = contract.context().txInfo().outputs()
        .toAddress(expectedAddress).valueProduced();
var paymentCredentialOnly = contract.context().txInfo().outputs()
        .toPaymentCredential(expectedCredential).valueProduced();
```

The second relation is weaker because it ignores the staking credential.
Selected-list aggregates preserve every input/output occurrence; whole-context
`valueSpent()`, `valueProduced()`, raw `mint()`, and `isBalanced()` retain the
pinned CardanoLedgerApi meanings. A balance-only result is marked
`domain-implied`, and transaction-local payment evidence records
`globalMultiInputLinkageModeled: false`.

Compile the generated model and trusted specification, then invoke
`julc verify dsl` exactly as in the preceding sections. See
[`e4j/README.md`](e4j/README.md) for the reproducible positive, refuted,
vacuous, kernel, VM, Docker, and native controls and the current solver bounds.

## 18. Typed governance transaction data

Schema 9 lets an existing spending, minting, rewarding, or certifying property
inspect `TxInfo` voters, votes, proposals, action identifiers, protocol
versions, and reviewed governance-action payloads. Generate the model with:

```bash
julc verify dsl-init . --validator MyValidator --purpose spending \
  --out verification/GovernanceModel.java
```

Use `context().txInfo().votes()` for duplicate-preserving nested maps and
`context().txInfo().proposals()` for ordered proposals. Access a raw proposal
action only through `actionStrict()`. `ChangedParameters` and `Quorum` remain
inaccessible, and voting/proposing validator selection is still unsupported.
See [`e4k/README.md`](e4k/README.md) for executable evidence and the proposal-
validity domain limitation.

## 19. Reviewed raw-data adapters

Schema 1 is the API-v1 default and provides closed reviewed operations for the pinned validity range,
strict treasury optionals, changed-parameter integer keys, and governance
quorum. Generate the schema-1 model explicitly:

```bash
julc verify dsl-init . --validator MyValidator --purpose spending \
  --out verification/MyContractModel.java
```

The generated model exposes expressions such as:

```java
var tx = contract.context().txInfo();
var guarantee = contract.datum().exists(datum ->
    tx.validityRangeReviewed().contains(datum.deadline()))
    .and(tx.currentTreasuryStrict().whenPresent(amount -> amount.ge(integer(0))))
    .and(tx.treasuryDonationStrict().isAbsent());
```

Use `decoderValid()` and `canonicalEncoding()` as different validity-range
claims. Treasury expressions distinguish present, absent, and malformed.
Changed-parameter values and arbitrary raw `Data` remain inaccessible. See
[`e4l/README.md`](e4l/README.md) for the executable evidence and the pinned
treasury-model discrepancy.

## 20. Fuel, recursion, and reruns

`--fuel` bounds exact UPLC preprocessing/execution in the generated obligation.
An `SMT-VALID` certificate covers only successful paths completing within that
recorded bound. Do not increase fuel merely to force a desired classification;
choose and review it as part of the verification profile.

`--recursive-depth` is separate. It controls generated recursive-schema
experiments and defaults to 4; it does not turn a bounded example into an
unbounded theorem and does not mean validator failure when reached.

For a reproducible CI invocation, use an explicit backend, fuel, output path,
and `--force`, then archive `verification-result.json` and its referenced logs:

```bash
julc verify . --validator AuthorizedStateValidator \
  --backend docker --fuel 1000 \
  --out-dir verification/ci-authorized --force
```

## 21. What the certificate does and does not claim

For an annotation profile, a successful certificate means:

> For the exact recorded UPLC artifact, successful execution within the
> recorded fuel bound implies the versioned generated security property.

It does not mean:

- every security property of the contract was checked;
- the compiler is generally semantics-preserving;
- the transaction satisfies every Cardano ledger-validity rule;
- fuel-exhausted execution paths are covered; or
- all possible JuLC/Plutus builtins are symbolically supported.

Unsupported schema shapes or builtins fail closed. For a property outside the
three annotation profiles, `julc verify init` can generate an artifact-bound
workspace, but specializing that property still requires reviewed Lean:

```bash
julc build
julc verify init . --validator MyValidator --purpose spending
julc verify run verification/my-validator --backend docker
```

An untouched custom workspace intentionally reports
`COULD-NOT-EVALUATE/property-not-specialized`; workspace compilation alone is
not verification.

For a multi-validator, repeat `verify init` with a distinct output directory
for each supported purpose. The manifest records both the base
`validatorTitle` and selected `blueprintEntryTitle`; generated workspaces from
the same script have identical `compiledCodeSha256` and `cardanoScriptHash`.

## Troubleshooting

- **Expected local Lean 4.24.0:** run from the generated workspace with elan on
  `PATH`, and confirm `lean --version` selects 4.24.0.
- **Docker daemon is not available:** confirm `docker version` shows a server,
  not only the client.
- **Output directory is not empty:** use `--force` to regenerate or
  `julc verify run` to rerun the existing workspace.
- **`REFUTED` on malformed datum/redeemer:** confirm the certificate artifact
  records `boundarySemantics: strict-data-v1`. Regenerate workspaces produced
  by an older compiler; a deliberately raw `PlutusData` root still requires a
  manual shape check.
- **Unsupported builtin or schema:** generation may stop with exit 1, while an
  already generated workspace can classify an unsupported runner condition as
  `COULD-NOT-EVALUATE`. Neither is proof; do not remove the check or reinterpret
  it as an assumption.
- **Vacuous property:** demonstrate a successful execution path; increasing
  fuel is appropriate only when evidence shows the intended path exhausted
  the existing recorded bound.
