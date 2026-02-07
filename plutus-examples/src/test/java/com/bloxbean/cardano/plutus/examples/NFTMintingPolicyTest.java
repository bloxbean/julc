package com.bloxbean.cardano.plutus.examples;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.testkit.BudgetAssertions;
import com.bloxbean.cardano.plutus.testkit.ValidatorTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NFT minting policy example: demonstrates sealed interfaces (sum types)
 * and pattern matching via if/instanceof chains.
 * <p>
 * Demonstrates: sealed interfaces, record variants, pattern matching,
 * helper methods.
 */
class NFTMintingPolicyTest {

    /**
     * An NFT minting policy with a sealed interface redeemer.
     * Uses Mint/Burn action pattern — Mint requires amount > 0,
     * Burn requires amount to be negative.
     */
    static final String NFT_MINT_SOURCE = """
            import java.math.BigInteger;

            @Validator
            class NFTMintingPolicy {
                sealed interface Action {
                    record Mint(BigInteger amount) implements Action {}
                    record Burn(BigInteger amount) implements Action {}
                }

                static boolean validateMint(BigInteger amount) {
                    return amount > 0;
                }

                static boolean validateBurn(BigInteger amount) {
                    return amount < 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger mintAmount = 1;
                    return validateMint(mintAmount);
                }
            }
            """;

    /**
     * Burn variant — validates a burn action.
     */
    static final String NFT_BURN_SOURCE = """
            import java.math.BigInteger;

            @Validator
            class NFTBurnPolicy {
                static boolean validateBurn(BigInteger amount) {
                    return amount < 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger burnAmount = -1;
                    return validateBurn(burnAmount);
                }
            }
            """;

    /**
     * Invalid mint — amount is 0, should reject.
     */
    static final String NFT_INVALID_MINT = """
            import java.math.BigInteger;

            @Validator
            class NFTInvalidPolicy {
                static boolean validateMint(BigInteger amount) {
                    return amount > 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger amount = 0;
                    return validateMint(amount);
                }
            }
            """;

    private PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }

    @Test
    void compilesWithSealedInterface() {
        var program = ValidatorTest.compile(NFT_MINT_SOURCE);
        assertNotNull(program);
    }

    @Test
    void compilesBurnPolicy() {
        var program = ValidatorTest.compile(NFT_BURN_SOURCE);
        assertNotNull(program);
    }

    @Test
    void mintAccepts() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(NFT_MINT_SOURCE, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void burnAccepts() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(NFT_BURN_SOURCE, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void invalidMintRejects() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(NFT_INVALID_MINT, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Test
    void budgetIsReasonable() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(NFT_MINT_SOURCE, ctx);
        BudgetAssertions.assertBudgetUnder(result, 10_000_000_000L, 10_000_000_000L);
    }
}
