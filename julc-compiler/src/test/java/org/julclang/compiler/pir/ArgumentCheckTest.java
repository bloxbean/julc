package org.julclang.compiler.pir;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-068 B3: the check a throwing method's argument takes, and that a constant that cannot fail takes none. */
class ArgumentCheckTest {

    private static final PirType INT = new PirType.IntegerType();
    private static final PirTerm X = new PirTerm.Var("x", INT);
    private static final PirTerm Y = new PirTerm.Var("y", INT);

    private static PirTerm constant(long value) {
        return new PirTerm.Const(Constant.integer(value));
    }

    private static PirTerm mod(PirTerm receiver, PirTerm argument) {
        return PirHelpers.failingBelow(1, receiver, INT, argument, INT, (a, b) -> PirHelpers.builtinApp2(DefaultFun.ModInteger, a, b));
    }

    private static PirTerm effect(String name) {
        return PirHelpers.builtinApp2(DefaultFun.AddInteger, new PirTerm.Var(name, INT), constant(1));
    }

    @Test
    void aConstantThatPassesTakesNoCheck() {
        assertEquals(PirHelpers.builtinApp2(DefaultFun.ModInteger, X, constant(1)), mod(X, constant(1)));
        assertInstanceOf(PirTerm.App.class, mod(effect("x"), constant(10)));
    }

    @Test
    void aConstantThatFailsKeepsTheCheck() {
        assertInstanceOf(PirTerm.IfThenElse.class, mod(X, constant(0)));
        assertInstanceOf(PirTerm.IfThenElse.class, mod(X, constant(-1)));
    }

    @Test
    void trivialOperandsAreNotBound() {
        var check = assertInstanceOf(PirTerm.IfThenElse.class, mod(X, Y));
        assertEquals(PirHelpers.builtinApp2(DefaultFun.LessThanInteger, Y, constant(1)), check.cond());
        assertInstanceOf(PirTerm.Error.class, check.thenBranch());
        assertEquals(PirHelpers.builtinApp2(DefaultFun.ModInteger, X, Y), check.elseBranch());
    }

    @Test
    void theReceiverIsBoundBeforeTheArgument() {
        var receiver = assertInstanceOf(PirTerm.Let.class, mod(effect("x"), effect("y")));
        assertEquals(effect("x"), receiver.value());
        var argument = assertInstanceOf(PirTerm.Let.class, receiver.body());
        assertEquals(effect("y"), argument.value());
        assertInstanceOf(PirTerm.IfThenElse.class, argument.body());
    }

    @Test
    void aBinderAvoidsTheNamesOfTheOperands() {
        var used = new PirTerm.Var("#receiver", INT);
        var receiver = assertInstanceOf(PirTerm.Let.class, mod(effect("x"), PirHelpers.builtinApp2(DefaultFun.AddInteger, used, constant(1))));
        assertNotEquals("#receiver", receiver.name());
    }

    private static PirTerm term(String expression, OptimizationLevel level) {
        var source = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                class P {
                    static BigInteger x(BigInteger a, long n, JulcList<BigInteger> l) { return BigInteger.valueOf(%s); }
                }
                """.formatted(expression);
        return new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "x", List.of()).pirTerm();
    }

    private static boolean fails(PirTerm term) {
        return term.toString().contains("Error[");
    }

    /** As the frontend reads these, each argument is a constant: the method's term has no check. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"a.mod(BigInteger.TEN).longValue()", "a.mod(BigInteger.TWO).longValue()", "a.mod(BigInteger.ONE).longValue()",
            "a.mod(BigInteger.valueOf(7)).longValue()", "a.mod(new BigInteger(\"7\")).longValue()", "l.take(3).size()", "l.drop(3).size()",
            "l.take(0).size()", "l.drop(0).size()"})
    void aConstantArgumentReadsAsOne(String expression) {
        assertFalse(fails(term(expression, OptimizationLevel.BASELINE)), expression);
        assertFalse(fails(term(expression, OptimizationLevel.PV11_SAFE)), expression);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"a.mod(a).longValue()", "a.mod(BigInteger.valueOf(n)).longValue()", "a.mod(BigInteger.ZERO).longValue()",
            "l.take(n).size()", "l.drop(n).size()", "l.take(-1).size()", "l.drop(-1).size()", "l.take(n + 1).size()"})
    void anythingElseIsChecked(String expression) {
        assertTrue(fails(term(expression, OptimizationLevel.BASELINE)), expression);
        assertTrue(fails(term(expression, OptimizationLevel.PV11_SAFE)), expression);
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aNegativeCountFailsAtEveryLevel(OptimizationLevel level) {
        assertTrue(fails(term("l.drop(n).size()", level)), level::name);
    }
}
