package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.Term;
import com.bloxbean.cardano.plutus.vm.EvalResult;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Task 4.1: Helper Method Support
 */
class HelperMethodTest {

    static PlutusVm vm;

    @BeforeAll
    static void setUp() {
        vm = PlutusVm.create();
    }

    private BigInteger evalInteger(Term term) {
        var result = vm.evaluate(com.bloxbean.cardano.plutus.core.Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.IntegerConst) val).value();
    }

    private boolean evalBool(Term term) {
        var result = vm.evaluate(com.bloxbean.cardano.plutus.core.Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.BoolConst) val).value();
    }

    @Test
    void helperCalledFromEntrypoint() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x + x;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return double_(redeemer) == 10;
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void multipleHelpers() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger add(BigInteger a, BigInteger b) {
                    return a + b;
                }

                static BigInteger sub(BigInteger a, BigInteger b) {
                    return a - b;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return add(redeemer, ctx) == sub(redeemer, ctx);
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperCallsHelper() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x + x;
                }

                static BigInteger quadruple(BigInteger x) {
                    return double_(double_(x));
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return quadruple(redeemer) == 40;
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void multiParamHelper() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger addThree(BigInteger a, BigInteger b, BigInteger c) {
                    return a + b + c;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return addThree(redeemer, ctx, 1) > 0;
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperWithBoolReturn() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static boolean isPositive(BigInteger x) {
                    return x > 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isPositive(redeemer) && isPositive(ctx);
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperWithIfElse() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger abs(BigInteger x) {
                    if (x < 0) {
                        return 0 - x;
                    } else {
                        return x;
                    }
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return abs(redeemer) == abs(ctx);
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperUsedInLetBinding() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x + x;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    var d = double_(redeemer);
                    return d > ctx;
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperMethodNoParams() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger magicNumber() {
                    return 42;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer == magicNumber();
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperInTernary() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x + x;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer > 0 ? double_(redeemer) == ctx : false;
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void compileOnlyNonEntrypointMethods() {
        // Entrypoint method should not appear as a helper Let binding
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer == ctx;
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperWithLocalVariable() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger compute(BigInteger x) {
                    var doubled = x + x;
                    var tripled = doubled + x;
                    return tripled;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return compute(redeemer) > ctx;
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperAsNestedArgument() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger add(BigInteger a, BigInteger b) {
                    return a + b;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return add(add(redeemer, 1), add(ctx, 2)) == 10;
                }
            }
            """;
        var result = new PlutusCompiler().compile(source);
        assertNotNull(result.program());
    }
}
