package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060: a compound assignment in a loop stores {@code target op value}. The lowering used to
 * drop the operator, so {@code count += 1} over three items returned 1 and
 * {@code while (k < 3) k += 1;} never terminated. Bitwise, shift and boolean compound operators are
 * rejected rather than lowered with different semantics. Every expected value is Java's.
 */
class CompoundAssignmentTest {
    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            class Compound {
            """;
    private static final List<OptimizationLevel> LEVELS = List.of(
            OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE);

    private static PlutusData ints(long... values) {
        var items = new PlutusData[values.length];
        for (int i = 0; i < values.length; i++) items[i] = PlutusData.integer(values[i]);
        return PlutusData.list(items);
    }

    /** Compiles {@code static BigInteger m(JulcList<BigInteger> xs)} and checks Java's result everywhere. */
    private static void returns(long expected, String body, PlutusData xs) {
        String source = HEADER + "static BigInteger m(JulcList<BigInteger> xs) { " + body + " } }";
        for (var level : LEVELS) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m").program();
            for (var provider : List.of("Java", "Scalus")) {
                var vm = CompilerTestVm.pv11(provider);
                var result = provider.equals("Scalus")
                        ? vm.evaluateWithArgs(program, List.of(xs))
                        : vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), List.of(xs),
                                new ExBudget(1_000_000_000L, 5_000_000L), EvalOptions.DEFAULT);
                var label = provider + "/" + level + ": " + body;
                var success = assertInstanceOf(EvalResult.Success.class, result, label);
                assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(expected))),
                        success.resultTerm(), label);
            }
        }
    }

    private static CompilerException rejected(String body) {
        String source = HEADER + "static BigInteger m(JulcList<BigInteger> xs) { " + body + " } }";
        return assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m"), body);
    }

    @Test
    void addAssignCountsEveryElement() {
        String body = "long count = 0; for (var x : xs) { count += 1; } return BigInteger.valueOf(count);";
        returns(3, body, ints(1, 2, 3));
        returns(0, body, ints());
        returns(6, "long total = 0; for (var x : xs) { total += x.longValue(); } return BigInteger.valueOf(total);",
                ints(1, 2, 3));
    }

    @Test
    void everyLoweredOperatorMatchesJava() {
        returns(94, "long r = 100; for (var x : xs) { r -= x.longValue(); } return BigInteger.valueOf(r);", ints(1, 2, 3));
        returns(24, "long p = 1; for (var x : xs) { p *= x.longValue(); } return BigInteger.valueOf(p);", ints(2, 3, 4));
        // Java integer division truncates toward zero and % takes the dividend's sign.
        returns(-33, "long q = -100; for (var x : xs) { q /= x.longValue(); } return BigInteger.valueOf(q);", ints(3));
        returns(-1, "long m = -7; for (var x : xs) { m %= x.longValue(); } return BigInteger.valueOf(m);", ints(2));
        returns(-3, "long q = 7; for (var x : xs) { q /= x.longValue(); } return BigInteger.valueOf(q);", ints(-2));
    }

    @Test
    void whileLoopTerminates() {
        returns(3, "long k = 0; while (k < 3) { k += 1; } return BigInteger.valueOf(k);", ints());
        returns(1004, """
                long k = 0; long s = 0;
                while (k < 10) { k += 1; s += k; if (k == 4) { break; } }
                return BigInteger.valueOf(s * 100 + k);""", ints());
    }

    @Test
    void branchesBreaksAndLoopLocals() {
        returns(21, """
                long pos = 0; long neg = 0;
                for (var x : xs) { if (x.compareTo(BigInteger.ZERO) > 0) { pos += 1; } else { neg += 1; } }
                return BigInteger.valueOf(pos * 10 + neg);""", ints(1, -2, 3));
        returns(2, """
                long c = 0;
                for (var x : xs) { if (x.equals(BigInteger.TEN)) { break; } c += 1; }
                return BigInteger.valueOf(c);""", ints(1, 2, 10, 4));
        returns(36, """
                long total = 0;
                for (var x : xs) { long y = 10; y += x.longValue(); total = total + y; }
                return BigInteger.valueOf(total);""", ints(1, 2, 3));
    }

    @Test
    void stringAddAssignConcatenates() {
        returns(3, """
                String s = "";
                for (var x : xs) { s += "a"; }
                return BigInteger.valueOf(Builtins.lengthOfByteString(Builtins.encodeUtf8(s)));""", ints(1, 2, 3));
    }

    @Test
    void bitwiseShiftAndBooleanOperatorsAreRejected() {
        for (var op : List.of("&=", "|=", "^=")) {
            var error = rejected("boolean ok = true; for (var x : xs) { ok " + op
                    + " x.compareTo(BigInteger.ZERO) > 0; } return ok ? BigInteger.ONE : BigInteger.ZERO;");
            assertEquals("JULC0052", error.diagnostics().getFirst().code(), op);
            assertTrue(error.diagnostics().getFirst().message().contains(op), error.getMessage());
        }
        for (var op : List.of("<<=", ">>=", ">>>=")) {
            var error = rejected("long k = 1; for (var x : xs) { k " + op + " 1; } return BigInteger.valueOf(k);");
            assertEquals("JULC0052", error.diagnostics().getFirst().code(), op);
        }
    }

    @Test
    void unsupportedTargetAndOperandTypesAreRejected() {
        var bool = rejected("boolean b = true; for (var x : xs) { b += true; } return BigInteger.ONE;");
        assertTrue(bool.getMessage().contains("Compound assignment += to 'b'"), bool.getMessage());
        var stringMinus = rejected("String s = \"\"; for (var x : xs) { s -= \"a\"; } return BigInteger.ONE;");
        assertTrue(stringMinus.getMessage().contains("Compound assignment -= to 's'"), stringMinus.getMessage());
        var stringOfInteger = rejected("String s = \"\"; for (var x : xs) { s += x; } return BigInteger.ONE;");
        assertTrue(stringOfInteger.getMessage().contains("needs a String operand"), stringOfInteger.getMessage());
    }

    @Test
    void outsideLoopItStaysImmutable() {
        rejected("long k = 0; k += 1; return BigInteger.valueOf(k);");
    }
}
