package com.bloxbean.cardano.julc.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JulcCompilerTest {

    private final JulcCompiler compiler = new JulcCompiler();

    @Test
    void compilesSimpleValidator() {
        var source = """
            import java.math.BigInteger;

            @SpendingValidator
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

            @SpendingValidator
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

            @SpendingValidator
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
            @SpendingValidator
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
            @SpendingValidator
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
    void compilesBasicMintingValidator() {
        var source = """
            import java.math.BigInteger;

            @MintingValidator
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
    void rejectsLegacyValidatorAnnotation() {
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
        var ex = assertThrows(CompilerException.class, () -> compiler.compile(source));
        assertTrue(ex.getMessage().contains("Use @SpendingValidator instead"));
    }

    @Test
    void rejectsLegacyMintingPolicyAnnotation() {
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
        var ex = assertThrows(CompilerException.class, () -> compiler.compile(source));
        assertTrue(ex.getMessage().contains("Use @MintingValidator instead"));
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
    void rejectsValidatorThatAlsoDeclaresOnchainLibrary() {
        var source = """
            import java.math.BigInteger;

            @OnchainLibrary
            @SpendingValidator
            class Confused {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;

        var ex = assertThrows(CompilerException.class, () -> compiler.compile(source));

        assertTrue(ex.getMessage().contains("must not combine @OnchainLibrary"));
        assertTrue(ex.getMessage().contains("@SpendingValidator"));
    }

    @Test
    void rejectsLibraryThatCombinesOnchainLibraryAndValidatorAnnotation() {
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

            @OnchainLibrary
            @MintingValidator
            class ConfusedLib {
                @Entrypoint
                static boolean validate(BigInteger a, BigInteger b) {
                    return true;
                }
            }
            """;

        var ex = assertThrows(CompilerException.class,
                () -> compiler.compile(validatorSource, java.util.List.of(librarySource)));

        assertTrue(ex.getMessage().contains("must not combine @OnchainLibrary"));
        assertTrue(ex.getMessage().contains("@MintingValidator"));
    }

    @Test
    void compilesBooleanLogic() {
        var source = """
            @SpendingValidator
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
    void rejectsMintingValidatorWithPolicyNamedClassAnd3Params() {
        var source = """
            import java.math.BigInteger;

            @MintingValidator
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

    // --- Static field initializer tests ---

    @Test
    void compilesStaticFieldInitializer() {
        var source = """
            import java.math.BigInteger;

            @SpendingValidator
            class StaticFieldValidator {
                static BigInteger THRESHOLD = BigInteger.valueOf(42);

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer == THRESHOLD;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void compilesStaticBooleanField() {
        var source = """
            import java.math.BigInteger;

            @SpendingValidator
            class StaticBoolValidator {
                static boolean ALLOW = true;

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return ALLOW;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void compilesMultipleStaticFields() {
        var source = """
            import java.math.BigInteger;

            @SpendingValidator
            class MultiStaticValidator {
                static BigInteger MIN = BigInteger.valueOf(10);
                static BigInteger MAX = BigInteger.valueOf(100);

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer >= MIN && redeemer <= MAX;
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void staticFieldUsedInHelperMethod() {
        var source = """
            import java.math.BigInteger;

            @SpendingValidator
            class HelperStaticValidator {
                static BigInteger LIMIT = BigInteger.valueOf(50);

                static boolean isWithinLimit(BigInteger x) {
                    return x <= LIMIT;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isWithinLimit(redeemer);
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }
}
