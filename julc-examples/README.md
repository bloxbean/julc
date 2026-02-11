# JuLC Examples

Example validators demonstrating JuLC features. Each example is a JUnit test that compiles and evaluates a Plutus V3 validator.

## Validator Examples

| Example | Description | Features Demonstrated |
|---------|-------------|----------------------|
| **VestingValidatorTest** | Time-locked vesting | Helper methods, boolean logic, if/else |
| **MultiSigValidatorTest** | Multi-signature authorization | List operations, for-each loops |
| **NFTMintingPolicyTest** | NFT minting policy | Minting validators, token operations |
| **RealisticVestingTest** | Full-featured vesting with stdlib | ContextsLib, signedBy, record types |
| **RealisticMintingTest** | Minting with authorization | Stdlib integration, minting context |
| **OutputValueCheckTest** | UTxO output validation | Value inspection, field access |
| **FeatureShowcaseTest** | All language features | Switch, sealed interfaces, records, loops |
| **DebugVestingTest** | Debugging with trace | Builtins.trace for debugging |
| **WithdrawValidatorTest** | Staking reward withdrawal | @WithdrawValidator, 2-param entrypoint |
| **VotingValidatorTest** | Governance voting (DRep) | @VotingValidator, Conway governance |

## Running Examples

```bash
# Run all examples
./gradlew :julc-examples:test

# Run a specific example
./gradlew :julc-examples:test --tests "*VestingValidatorTest"
```

## Writing Your Own

Each example follows the same pattern:

1. Define the validator source as a Java string with `@SpendingValidator`/`@MintingValidator`/etc.
2. Compile with `ValidatorTest.compile(source)` or `ValidatorTest.compile(source, stdlib::lookup)`
3. Build a mock `ScriptContext` as `PlutusData`
4. Evaluate with `ValidatorTest.evaluate(program, ctx)`
5. Assert results with `BudgetAssertions.assertSuccess(result)`

See `VestingValidatorTest.java` for the simplest starting point.
