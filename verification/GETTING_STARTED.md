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

## 10. Experimental typed seller-payment DSL

ADR-016 E.3 adds an opt-in typed Java DSL vertical slice. It is experimental,
executes trusted project Java in a bounded worker, and currently accepts only
the reviewed seller-paid-at-least shape. Start from an already buildable JuLC
spending project whose datum has a `byte[] seller` and `BigInteger price`:

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
  --seller-field seller --price-field price \
  --source SellerPaymentSpec.java --backend local --force
```

The property builder constructs typed symbolic expressions; it does not run the
contract. JuLC independently validates its bounded canonical AST, imports the
exact compiled UPLC, checks non-vacuity, and binds the generated Lean and
property IR into the certificate. Use `--backend docker` instead of `local` for
the proof workspace if desired; the Java property worker is still local and
trusted-source-only in E.3.

See [`dsl/README.md`](dsl/README.md) and [`e3/README.md`](e3/README.md) for the
property source and evidence controls.

## 11. Experimental typed one-shot minting DSL

ADR-018 E.4a extends the trusted Java property workflow to minting. Generate a
purpose-aware model, compile it with a specification using
`MintingDsl.oneShotPropertySet`, and select the minting interface explicitly:

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

The generated certificate binds the schema-2 DSL hash, capability inventory,
selected blueprint entry, exact shared UPLC/hash, generated Lean, domain
bridge, dependency pins, and execution bounds. Mint association-list order and
duplicates are preserved; duplicate or malformed entries for the current
policy cannot disappear behind first-match lookup. See
[`e4a/README.md`](e4a/README.md) for a complete specification and local/Docker
commands.

## 12. Experimental compositional, rewarding, and certifying DSL

ADR-019 schema 3 accepts freely composed guarantees built only from the
reviewed typed nodes. Each property keeps the exact-execution and optional
ledger-domain premise in a generator-owned theorem envelope; user code cannot
insert either premise or raw Lean. A rewarding specification can therefore
combine existing signer predicates with the new duplicate-preserving raw
withdrawal traversal:

```java
var contract = new RewardingModel();
var ownMinimum = contract.context().txInfo().withdrawals().exists(entry ->
        entry.credential().eq(contract.rewardingCredential())
                .and(entry.amount().ge(integer(1_000_000))));
var authorized = contract.context().txInfo().signatories()
        .contains(keyHash(AUTHORITY));

return DslPropertySet.composed(DslPurpose.REWARDING,
        property("reward.authorized-minimum",
                DslDomain.VALID_REWARDING_V3_PINNED,
                contract.redeemerStrictlyDecodes()
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

A certifying specification uses the same compositional schema-3 pipeline. The
selected `publish` interface supplies the current certificate and its ledger
index; user property code cannot replace either root:

```java
var contract = new CertifyingModel();
var currentCertificate = contract.context().txInfo().certificates().containsAt(
        contract.certificateIndex(), contract.certificate());
var authorized = contract.context().txInfo().signatories()
        .contains(keyHash(AUTHORITY));

return DslPropertySet.composed(DslPurpose.CERTIFYING,
        property("certificate.authorized-update",
                DslDomain.VALID_CERTIFYING_V3_PINNED,
                contract.redeemerStrictlyDecodes()
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

## 13. Fuel, recursion, and reruns

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

## 14. What the certificate does and does not claim

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
