package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.JulcVm;
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
        vm = JulcVm.create();
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

            sealed interface Shape permits Circle, Rect, Triangle {}
            record Circle(long radius) implements Shape {}
            record Rect(long w, long h) implements Shape {}
            record Triangle(long a, long b, long c) implements Shape {}

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Shape s = (Shape) redeemer;
                    return switch (s) {
                        case Circle c -> c.radius() > 0;
                        case Rect r -> r.w() > 0;
                    };
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("not exhaustive") || ex.getMessage().contains("Missing"),
                "Should report missing case. Got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Triangle"),
                "Should mention Triangle as missing. Got: " + ex.getMessage());
    }

    @Test
    void testAllCasesCoveredNoError() {
        // Switch with all 3 cases — should compile and evaluate correctly
        var source = """
            import java.math.BigInteger;

            sealed interface Action permits Mint, Burn, Transfer {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}
            record Transfer(long amt) implements Action {}

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) redeemer;
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
    void testDefaultBranchCoversAll() {
        // Switch with 1 explicit case + default — should compile OK
        var source = """
            import java.math.BigInteger;

            sealed interface Action permits Mint, Burn, Transfer {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}
            record Transfer(long amt) implements Action {}

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) redeemer;
                    return switch (a) {
                        case Mint m -> m.amt() > 0;
                        default -> true;
                    };
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.hasErrors(), "Default branch should make it exhaustive. Got: " + result.diagnostics());
    }

    @Test
    void testMultipleMissingCases() {
        // Switch with 1 of 3 cases — should report 2 missing
        var source = """
            import java.math.BigInteger;

            sealed interface Shape permits Circle, Rect, Triangle {}
            record Circle(long radius) implements Shape {}
            record Rect(long w, long h) implements Shape {}
            record Triangle(long a, long b, long c) implements Shape {}

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Shape s = (Shape) redeemer;
                    return switch (s) {
                        case Circle c -> c.radius() > 0;
                    };
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("Rect") && ex.getMessage().contains("Triangle"),
                "Should list both missing cases. Got: " + ex.getMessage());
    }

    @Test
    void testTwoCaseSealedExhaustive() {
        // Two-case sealed interface with both cases covered
        var source = """
            import java.math.BigInteger;

            sealed interface Bool permits True, False {}
            record True() implements Bool {}
            record False() implements Bool {}

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Bool b = (Bool) redeemer;
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

            sealed interface Action permits Mint, Burn {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) redeemer;
                    return switch (a) {
                        case Mint m -> m.amt() > 0;
                    };
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("Burn"),
                "Error should mention 'Burn' as missing. Got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Action"),
                "Error should mention the sealed interface name. Got: " + ex.getMessage());
    }

    @Test
    void testNonSealedSwitchNoCheck() {
        // Switch on a non-sealed type should not trigger exhaustiveness check
        // (Non-sealed types don't use generateSwitchExpr — they'd be a compile error already)
        // This is a compile-success test for a normal sealed interface with all cases
        var source = """
            import java.math.BigInteger;

            sealed interface Action permits Mint, Burn {}
            record Mint(long amt) implements Action {}
            record Burn(long amt) implements Action {}

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Action a = (Action) redeemer;
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

            sealed interface Op permits Add, Sub, Mul {}
            record Add(long a, long b) implements Op {}
            record Sub(long a, long b) implements Op {}
            record Mul(long a, long b) implements Op {}

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Op op = (Op) redeemer;
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
