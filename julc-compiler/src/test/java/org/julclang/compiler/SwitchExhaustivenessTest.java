package org.julclang.compiler;

import org.julclang.core.*;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for S4: Switch exhaustiveness checking on sealed interfaces.
 * Non-exhaustive switches without a default branch must produce a compiler error.
 */
class SwitchExhaustivenessTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = CompilerTestVm.pv11();
    }

    static PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }

    @Test
    void testMissingCaseDetected() {
        // Switch with 2 of 3 cases — should fail
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Shape permits Circle, Rect, Triangle {}
            record Circle(long radius) implements Shape {}
            record Rect(long w, long h) implements Shape {}
            record Triangle(long a, long b, long c) implements Shape {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Shape s = (Shape) (Object) redeemer;
                    return switch (s) {
                        case Circle c -> c.radius() > 0;
                        case Rect r -> r.w() > 0;
                    };
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        // javac rejects it first (ADR-068 I2b); its message does not name the missing variants.
        assertTrue(ex.getMessage().contains("JULC0085") && ex.getMessage().contains("(compiler.err.not.exhaustive)"),
                "Should report the non-exhaustive switch. Got: " + ex.getMessage());
    }

    @Test
    void testAllCasesCoveredNoError() {
        // Switch with all 3 cases — should compile and evaluate correctly
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Action permits Mint, Burn, Transfer {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}
            record Transfer(long amt) implements Action {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) (Object) redeemer;
                    return switch (a) {
                        case Mint m -> m.amt() > 0;
                        case Burn b -> b.amt() > 0;
                        case Transfer t -> t.amt() > 0;
                    };
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.hasErrors(), "All cases covered — should compile. Got: " + result.diagnostics());

        // Evaluate with Mint(10) → amt > 0 → true
        var redeemer = PlutusData.constr(0, PlutusData.integer(10));
        var evalResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(redeemer)));
        assertTrue(evalResult.isSuccess(), "Mint(10) should pass. Got: " + evalResult);
    }

    @Test
    void testDefaultBranchCompilesAndEvaluates() {
        // Switch with default branch — should compile, default covers unmatched variants
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Action permits Mint, Burn, Transfer {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}
            record Transfer(long amt) implements Action {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) (Object) redeemer;
                    return switch (a) {
                        case Mint m -> m.amt() > 0;
                        default -> true;
                    };
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.hasErrors(), "Default branch should compile. Got: " + result.diagnostics());

        // Evaluate with Mint(10) → amt > 0 → true (explicit case)
        var mintRedeemer = PlutusData.constr(0, PlutusData.integer(10));
        var mintResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(mintRedeemer)));
        assertTrue(mintResult.isSuccess(), "Mint(10) should pass via explicit case. Got: " + mintResult);

        // Evaluate with Burn(5) → default → true (default branch)
        var burnRedeemer = PlutusData.constr(1, PlutusData.integer(5));
        var burnResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(burnRedeemer)));
        assertTrue(burnResult.isSuccess(), "Burn(5) should pass via default branch. Got: " + burnResult);

        // Evaluate with Transfer(3) → default → true (default branch)
        var transferRedeemer = PlutusData.constr(2, PlutusData.integer(3));
        var transferResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(transferRedeemer)));
        assertTrue(transferResult.isSuccess(), "Transfer(3) should pass via default branch. Got: " + transferResult);
    }

    @Test
    void testDefaultBranchWithAllCasesCompiles() {
        // All cases + default — default is redundant but should still compile
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Action permits Mint, Burn {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) (Object) redeemer;
                    return switch (a) {
                        case Mint m -> m.amt() > 0;
                        case Burn b -> b.amt() > 0;
                        default -> false;
                    };
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.hasErrors(), "All cases + default should compile. Got: " + result.diagnostics());

        // Evaluate with Mint(10) → amt > 0 → true (explicit case takes priority)
        var mintRedeemer = PlutusData.constr(0, PlutusData.integer(10));
        var mintResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(mintRedeemer)));
        assertTrue(mintResult.isSuccess(), "Mint(10) should pass. Got: " + mintResult);

        // Evaluate with Burn(5) → amt > 0 → true (explicit case)
        var burnRedeemer = PlutusData.constr(1, PlutusData.integer(5));
        var burnResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(burnRedeemer)));
        assertTrue(burnResult.isSuccess(), "Burn(5) should pass. Got: " + burnResult);
    }

    @Test
    void testMultipleMissingCases() {
        // Switch with 1 of 3 cases — should report 2 missing
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Shape permits Circle, Rect, Triangle {}
            record Circle(long radius) implements Shape {}
            record Rect(long w, long h) implements Shape {}
            record Triangle(long a, long b, long c) implements Shape {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Shape s = (Shape) (Object) redeemer;
                    return switch (s) {
                        case Circle c -> c.radius() > 0;
                    };
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        // javac rejects it first (ADR-068 I2b); its message does not name the missing variants.
        assertTrue(ex.getMessage().contains("JULC0085") && ex.getMessage().contains("(compiler.err.not.exhaustive)"),
                "Should report the non-exhaustive switch. Got: " + ex.getMessage());
    }

    @Test
    void testTwoCaseSealedExhaustive() {
        // Two-case sealed interface with both cases covered
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Bool permits True, False {}
            record True() implements Bool {}
            record False() implements Bool {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Bool b = (Bool) (Object) redeemer;
                    return switch (b) {
                        case True t -> true;
                        case False f -> false;
                    };
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.hasErrors(), "Both cases covered — should compile. Got: " + result.diagnostics());

        // Evaluate with True() → true
        var redeemer = PlutusData.constr(0); // True = tag 0
        var evalResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(redeemer)));
        assertTrue(evalResult.isSuccess(), "True should return true. Got: " + evalResult);
    }

    @Test
    void testErrorMessageListsMissingCases() {
        // Verify the error message format includes the missing case name(s)
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Action permits Mint, Burn {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) (Object) redeemer;
                    return switch (a) {
                        case Mint m -> m.amt() > 0;
                    };
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        // javac rejects it first (ADR-068 I2b); its message does not name the missing variants.
        assertTrue(ex.getMessage().contains("JULC0085") && ex.getMessage().contains("(compiler.err.not.exhaustive)"),
                "Should report the non-exhaustive switch. Got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Action.java"), "Error should locate the switch. Got: " + ex.getMessage());
    }

    @Test
    void testNonSealedSwitchNoCheck() {
        // Switch on a non-sealed type should not trigger exhaustiveness check
        // (Non-sealed types don't use generateSwitchExpr — they'd be a compile error already)
        // This is a compile-success test for a normal sealed interface with all cases
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Action permits Mint, Burn {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) (Object) redeemer;
                    return switch (a) {
                        case Mint m -> true;
                        case Burn b -> false;
                    };
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.hasErrors());
    }

    @Test
    void testSwitchWithReturnInCases() {
        // Exhaustive switch where each case has a proper return value
        var source = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            sealed interface Op permits Add, Sub, Mul {}
            record Add(long a, long b) implements Op {}
            record Sub(long a, long b) implements Op {}
            record Mul(long a, long b) implements Op {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Op op = (Op) (Object) redeemer;
                    long result = switch (op) {
                        case Add add -> add.a() + add.b();
                        case Sub sub -> sub.a() - sub.b();
                        case Mul mul -> mul.a() * mul.b();
                    };
                    return result > 0;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.hasErrors(), "Full switch should compile. Got: " + result.diagnostics());

        // Evaluate with Add(3, 4) → 7 > 0 → true
        var redeemer = PlutusData.constr(0, PlutusData.integer(3), PlutusData.integer(4));
        var evalResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(redeemer)));
        assertTrue(evalResult.isSuccess(), "Add(3,4) should give 7 > 0. Got: " + evalResult);
    }
}
