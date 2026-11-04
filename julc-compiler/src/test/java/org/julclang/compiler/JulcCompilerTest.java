package org.julclang.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JulcCompilerTest {

    private final JulcCompiler compiler = new JulcCompiler();

    /** The JavaParser frontend, which compiles every library unit it is given (ADR-068 D8); selected explicitly from M5. */
    private static JulcCompiler reference() {
        return new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAPARSER));
    }

    @Test
    void compilesSimpleValidator() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class SimpleValidator {
                @Entrypoint
                static boolean validate(BigInteger datum, BigInteger redeemer, PlutusData ctx) {
                    return datum.equals(redeemer);
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class CheckValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
                    if (redeemer.compareTo(BigInteger.ZERO) > 0) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class LetValidator {
                @Entrypoint
                static boolean validate(BigInteger datum, BigInteger redeemer, PlutusData ctx) {
                    var sum = datum.add(redeemer);
                    return sum.equals(BigInteger.valueOf(100));
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
    }

    @Test
    void rejectsInvalidSubset() {
        var source = """
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;
            @SpendingValidator
            class BadValidator {
                @Entrypoint
                static boolean validate(int a, PlutusData b) {
                    try { return true; } catch (Exception e) { return false; }
                }
            }
            """;
        assertThrows(CompilerException.class, () -> compiler.compile(source));
    }

    @Test
    void rejectsMissingEntrypoint() {
        var source = """
            import org.julclang.stdlib.annotation.SpendingValidator;
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
            import org.julclang.stdlib.annotation.Entrypoint;
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MintingValidator;
            import org.julclang.core.PlutusData;

            @MintingValidator
            class SimpleMint {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class SpendTest {
                @Entrypoint
                static boolean validate(BigInteger datum, BigInteger redeemer, PlutusData ctx) {
                    return datum.equals(redeemer);
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MintingValidator;
            import org.julclang.core.PlutusData;

            @MintingValidator
            class MintTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.WithdrawValidator;
            import org.julclang.core.PlutusData;

            @WithdrawValidator
            class WithdrawTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.CertifyingValidator;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.core.PlutusData;

            @CertifyingValidator
            class CertTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.VotingValidator;
            import org.julclang.core.PlutusData;

            @VotingValidator
            class VoteTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.ProposingValidator;
            import org.julclang.core.PlutusData;

            @ProposingValidator
            class ProposeTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.core.PlutusData;

            @Validator
            class DeprecatedTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.core.PlutusData;

            @MintingPolicy
            class DeprecatedMintTest {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class MainValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
                    return true;
                }
            }
            """;
        var librarySource = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;

            @WithdrawValidator
            class BadLib {
                @Entrypoint
                static boolean validate(BigInteger a, PlutusData b) {
                    return true;
                }
            }
            """;
        assertThrows(CompilerException.class,
                () -> reference().compile(validatorSource, java.util.List.of(librarySource)));
        // ADR-068 D8 (r11): javac, the default from M5, reads only the units a program references, so a library nothing
        // references is not compiled and its annotation is not rejected; JavacLibraryRolesTest holds the referenced case.
        assertFalse(compiler.compile(validatorSource, java.util.List.of(librarySource)).hasErrors());
    }

    @Test
    void rejectsValidatorThatAlsoDeclaresOnchainLibrary() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @OnchainLibrary
            @SpendingValidator
            class Confused {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class MainValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
                    return true;
                }
            }
            """;
        var librarySource = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;

            @OnchainLibrary
            @MintingValidator
            class ConfusedLib {
                @Entrypoint
                static boolean validate(BigInteger a, PlutusData b) {
                    return true;
                }
            }
            """;

        var ex = assertThrows(CompilerException.class,
                () -> reference().compile(validatorSource, java.util.List.of(librarySource)));

        assertTrue(ex.getMessage().contains("must not combine @OnchainLibrary"));
        assertTrue(ex.getMessage().contains("@MintingValidator"));
        // ADR-068 D8 (r11): javac, the default from M5, reads only the units a program references, so a library nothing
        // references is not compiled and its annotation is not rejected; JavacLibraryRolesTest holds the referenced case.
        assertFalse(compiler.compile(validatorSource, java.util.List.of(librarySource)).hasErrors());
    }

    @Test
    void compilesBooleanLogic() {
        var source = """
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;
            @SpendingValidator
            class BoolValidator {
                @Entrypoint
                static boolean validate(boolean a, boolean b, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MintingValidator;
            import org.julclang.core.PlutusData;

            @MintingValidator
            class BadMint {
                @Entrypoint
                static boolean validate(BigInteger datum, BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MintingValidator;
            import org.julclang.core.PlutusData;

            @MintingValidator
            class BadMintPolicy {
                @Entrypoint
                static boolean validate(BigInteger datum, BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class StaticFieldValidator {
                static BigInteger THRESHOLD = BigInteger.valueOf(42);

                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
                    return redeemer.equals(THRESHOLD);
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class StaticBoolValidator {
                static boolean ALLOW = true;

                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class MultiStaticValidator {
                static BigInteger MIN = BigInteger.valueOf(10);
                static BigInteger MAX = BigInteger.valueOf(100);

                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
                    return redeemer.compareTo(MIN) >= 0 && redeemer.compareTo(MAX) <= 0;
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class HelperStaticValidator {
                static BigInteger LIMIT = BigInteger.valueOf(50);

                static boolean isWithinLimit(BigInteger x) {
                    return x.compareTo(LIMIT) <= 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
                    return isWithinLimit(redeemer);
                }
            }
            """;
        var result = compiler.compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }
}
