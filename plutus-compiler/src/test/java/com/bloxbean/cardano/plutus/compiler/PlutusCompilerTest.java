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
}
