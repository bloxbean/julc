package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Task 4.1: Helper Method Support
 */
class HelperMethodTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = CompilerTestVm.pv11();
    }

    private BigInteger evalInteger(Term term) {
        var result = vm.evaluate(org.julclang.core.Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.IntegerConst) val).value();
    }

    private boolean evalBool(Term term) {
        var result = vm.evaluate(org.julclang.core.Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.BoolConst) val).value();
    }

    @Test
    void helperCalledFromEntrypoint() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x.add(x);
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return double_(redeemer).equals(BigInteger.TEN);
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void multipleHelpers() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger add(BigInteger a, BigInteger b) {
                    return a.add(b);
                }

                static BigInteger sub(BigInteger a, BigInteger b) {
                    return a.subtract(b);
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return add(redeemer, ctx) == sub(redeemer, ctx);
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperCallsHelper() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x.add(x);
                }

                static BigInteger quadruple(BigInteger x) {
                    return double_(double_(x));
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return quadruple(redeemer).equals(BigInteger.valueOf(40));
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void multiParamHelper() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger addThree(BigInteger a, BigInteger b, BigInteger c) {
                    return a.add(b).add(c);
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return addThree(redeemer, ctx, BigInteger.ONE).compareTo(BigInteger.ZERO) > 0;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperWithBoolReturn() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static boolean isPositive(BigInteger x) {
                    return x.compareTo(BigInteger.ZERO) > 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isPositive(redeemer) && isPositive(ctx);
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperWithIfElse() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger abs(BigInteger x) {
                    if (x.compareTo(BigInteger.ZERO) < 0) {
                        return BigInteger.ZERO.subtract(x);
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
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperUsedInLetBinding() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x.add(x);
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    var d = double_(redeemer);
                    return d.compareTo(ctx) > 0;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperMethodNoParams() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger magicNumber() {
                    return BigInteger.valueOf(42);
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer == magicNumber();
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperInTernary() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x.add(x);
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer.compareTo(BigInteger.ZERO) > 0 ? double_(redeemer) == ctx : false;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void compileOnlyNonEntrypointMethods() {
        // Entrypoint method should not appear as a helper Let binding
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return redeemer == ctx;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperWithLocalVariable() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger compute(BigInteger x) {
                    var doubled = x.add(x);
                    var tripled = doubled.add(x);
                    return tripled;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return compute(redeemer).compareTo(ctx) > 0;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }

    @Test
    void helperAsNestedArgument() {
        var source = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                static BigInteger add(BigInteger a, BigInteger b) {
                    return a.add(b);
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return add(add(redeemer, BigInteger.ONE), add(ctx, BigInteger.TWO)).equals(BigInteger.TEN);
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
    }
}
