package com.bloxbean.cardano.plutus.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlutusCompilerTest {

    private final PlutusCompiler compiler = new PlutusCompiler();

    @Test
    void compilesSimpleValidator() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class SimpleValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer == ctx;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        assertEquals(1, result.program().major());
        assertEquals(1, result.program().minor()); // V3
    }

    @Test
    void compilesValidatorWithIfElse() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class CheckValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    if (redeemer > 0) {
                        return true;
                    } else {
                        return false;
                    }
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void compilesValidatorWithLetBinding() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class LetValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    var sum = redeemer + ctx;
                    return sum == 100;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
    }

    @Test
    void rejectsInvalidSubset() {
        var source = """
            @Validator
            class BadValidator {
                @Entrypoint
                static boolean validate(int a, int b) {
                    try { return true; } catch (Exception e) { return false; }
                }
            }
            """;
        assertThrows(CompilerException.class, () -> compiler.compile(source));
    }

    @Test
    void rejectsMissingEntrypoint() {
        var source = """
            @Validator
            class NoEntrypoint {
                static boolean validate(int a) { return true; }
            }
            """;
        assertThrows(CompilerException.class, () -> compiler.compile(source));
    }

    @Test
    void rejectsMissingValidator() {
        var source = """
            class NotAValidator {
                @Entrypoint
                static boolean validate(int a) { return true; }
            }
            """;
        assertThrows(CompilerException.class, () -> compiler.compile(source));
    }

    @Test
    void compilesMintingPolicy() {
        var source = """
            import java.math.BigInteger;

            @MintingPolicy
            class SimpleMint {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
    }

    @Test
    void compilesSpendingValidator() {
        var source = """
            import java.math.BigInteger;

            @SpendingValidator
            class SpendTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer == ctx;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void compilesMintingValidator() {
        var source = """
            import java.math.BigInteger;

            @MintingValidator
            class MintTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void compilesWithdrawValidator() {
        var source = """
            import java.math.BigInteger;

            @WithdrawValidator
            class WithdrawTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void compilesCertifyingValidator() {
        var source = """
            import java.math.BigInteger;

            @CertifyingValidator
            class CertTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void compilesVotingValidator() {
        var source = """
            import java.math.BigInteger;

            @VotingValidator
            class VoteTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void compilesProposingValidator() {
        var source = """
            import java.math.BigInteger;

            @ProposingValidator
            class ProposeTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void deprecatedValidatorStillWorks() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class DeprecatedTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void deprecatedMintingPolicyStillWorks() {
        var source = """
            import java.math.BigInteger;

            @MintingPolicy
            class DeprecatedMintTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void rejectsLibraryWithNewValidatorAnnotation() {
        var validatorSource = """
            import java.math.BigInteger;

            @SpendingValidator
            class MainValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var librarySource = """
            import java.math.BigInteger;

            @WithdrawValidator
            class BadLib {
                @Entrypoint
                static boolean validate(BigInteger a, BigInteger b) {
                    return true;
                }
            }
            """;
        assertThrows(CompilerException.class,
                () -> compiler.compile(validatorSource, java.util.List.of(librarySource)));
    }

    @Test
    void compilesBooleanLogic() {
        var source = """
            @Validator
            class BoolValidator {
                @Entrypoint
                static boolean validate(boolean a, boolean b) {
                    return a && b || !a;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
    }

    @Test
    void rejectsMintingValidatorWith3Params() {
        var source = """
            import java.math.BigInteger;

            @MintingValidator
            class BadMint {
                @Entrypoint
                static boolean validate(BigInteger datum, BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> compiler.compile(source));
        assertTrue(ex.getMessage().contains("2 parameters"));
        assertTrue(ex.getMessage().contains("@SpendingValidator"));
    }

    @Test
    void rejectsMintingPolicyWith3Params() {
        var source = """
            import java.math.BigInteger;

            @MintingPolicy
            class BadMintPolicy {
                @Entrypoint
                static boolean validate(BigInteger datum, BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> compiler.compile(source));
        assertTrue(ex.getMessage().contains("2 parameters"));
        assertTrue(ex.getMessage().contains("@SpendingValidator"));
    }
}
