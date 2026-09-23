# Developer-node regression tests

These tests use an **already-running**, developer-owned Yaci DevKit (testnet magic 42).
They never download, start, stop or reset a node/container. Opt-in tests fund test accounts
through the admin API and submit transactions; use a disposable development network.
The existing HTTP endpoints are `http://localhost:8080/api/v1/` (backend) and
`http://localhost:10000` (admin). The CLI/socket must access that same chain.

## Offline tests

```sh
./gradlew :julc-e2e-tests:onChainHarnessTest
```

This also runs in the normal `check`/`build`. It checks launcher argument construction,
diagnostic compatibility, and native-constant semantics, hashes and pinned-model budgets.
It does not invoke Docker, a CLI, or HTTP services.

## Direct Haskell and confirmed-spend gates

Choose exactly one launcher. No personal installation path is assumed.

For an existing DevKit container:

```sh
export JULC_E2E_CARDANO_CONTAINER=your-running-devkit-container
unset JULC_E2E_CARDANO_CLI JULC_E2E_CARDANO_SOCKET
```

The established container layout is `/app/cardano-bin/cardano-cli` with socket
`/clusters/nodes/default/node/node.sock`. Docker must already be available on PATH.

For a native POSIX installation, supply your installed **Haskell cardano-cli** executable
and the socket of your running Haskell cardano-node:

```sh
unset JULC_E2E_CARDANO_CONTAINER
export JULC_E2E_CARDANO_CLI=/absolute/path/to/cardano-cli
export JULC_E2E_CARDANO_SOCKET=/absolute/path/to/node.sock
```

These are placeholders, not files installed by the tests. Native mode uses `/dev/stdin`;
it is not a Windows-native launcher. Paths containing spaces work; no shell expansion
or command string is used. Partial or mixed configuration fails before funding.

From the repository root:

```sh
./gradlew :julc-e2e-tests:listCaseOnChainTest \
  :julc-e2e-tests:switchPairCaseOnChainTest \
  :julc-e2e-tests:nativeConstantsOnChainTest -Pe2e
```

The gates require active protocol 11.0 and the exact `plutus-v3-pv11-costs-v1` model.
They compare Java, backend and direct Haskell results/budgets, submit the original valid
signed transactions, and confirm spends. Invalid witness variants are evaluated only,
never submitted. A failed request or unrelated budget exhaustion is not a passing negative
test. These tasks cannot reuse a cached pass and fail if expected cases are missing/skipped.

Ordinary backend-only integration tests are separate and need no CLI configuration:

```sh
./gradlew :julc-e2e-tests:test -Pe2e --rerun-tasks
```

## Regression expectations

`src/test/resources/native-constants-pv11.properties` pins script hash, FLAT bytes, CPU and
memory for Value/Array literals and G1/G2 MSM at baseline, safe and costed levels. The same
fixtures and expectations are used offline and on-chain. There is no auto-update mode.
Investigate drift first: compiler/configuration changes can alter bytes, while a network
cost-model change can alter budgets without altering bytes. Do not refresh pins merely
to make tests pass. Record the reason, affected artifacts and independent node evidence
for review; see [ADR-052](../adr/052-pre17-profile-freeze-and-release-regressions.md).
