package com.bloxbean.cardano.julc.examples;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Voting validator example: demonstrates a Conway-era governance voting validator.
 * <p>
 * A voting validator (DRep script) controls how a DRep credential votes on
 * governance actions. It takes 2 parameters: (redeemer, scriptContext).
 * <p>
 * These examples demonstrate the @VotingValidator annotation and validator
 * compilation. Like other examples, they use constant expressions to demonstrate
 * compilation features while remaining evaluable.
 */
class VotingValidatorTest {

    /**
     * A voting validator that checks if a vote is authorized.
     * Uses constant comparison to demonstrate the compilation pattern.
     */
    static final String AUTHORIZED_VOTER = """
            import java.math.BigInteger;

            @VotingValidator
            class AuthorizedVoter {
                static boolean isAuthorized(BigInteger credential, BigInteger expected) {
                    return credential == expected;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isAuthorized(42, 42);
                }
            }
            """;

    /**
     * A voting validator that rejects — credential mismatch.
     */
    static final String REJECTING_VOTER = """
            import java.math.BigInteger;

            @VotingValidator
            class RejectingVoter {
                static boolean isAuthorized(BigInteger credential, BigInteger expected) {
                    return credential == expected;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isAuthorized(99, 42);
                }
            }
            """;

    /**
     * A voting validator with conditional logic based on vote type.
     * Demonstrates if/else branching in a governance context.
     */
    static final String CONDITIONAL_VOTER_YES = """
            import java.math.BigInteger;

            @VotingValidator
            class ConditionalVoterYes {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger voteType = BigInteger.valueOf(1);
                    if (voteType == 1) {
                        return true;
                    } else if (voteType == 2) {
                        return true;
                    } else {
                        return false;
                    }
                }
            }
            """;

    /**
     * Conditional voter that rejects — abstain vote type.
     */
    static final String CONDITIONAL_VOTER_ABSTAIN = """
            import java.math.BigInteger;

            @VotingValidator
            class ConditionalVoterAbstain {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger voteType = BigInteger.valueOf(3);
                    if (voteType == 1) {
                        return true;
                    } else if (voteType == 2) {
                        return true;
                    } else {
                        return false;
                    }
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
    void compilesAuthorizedVoter() {
        var program = ValidatorTest.compile(AUTHORIZED_VOTER);
        assertNotNull(program);
    }

    @Test
    void compilesRejectingVoter() {
        var program = ValidatorTest.compile(REJECTING_VOTER);
        assertNotNull(program);
    }

    @Test
    void compilesConditionalVoterYes() {
        var program = ValidatorTest.compile(CONDITIONAL_VOTER_YES);
        assertNotNull(program);
    }

    @Test
    void compilesConditionalVoterAbstain() {
        var program = ValidatorTest.compile(CONDITIONAL_VOTER_ABSTAIN);
        assertNotNull(program);
    }

    @Test
    void authorizedVoterAccepts() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(AUTHORIZED_VOTER, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void rejectingVoterRejects() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(REJECTING_VOTER, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Test
    void conditionalVoterYesAccepts() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(CONDITIONAL_VOTER_YES, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void conditionalVoterAbstainRejects() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(CONDITIONAL_VOTER_ABSTAIN, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Test
    void budgetIsReasonable() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(AUTHORIZED_VOTER, ctx);
        BudgetAssertions.assertBudgetUnder(result, 10_000_000_000L, 10_000_000_000L);
    }
}
