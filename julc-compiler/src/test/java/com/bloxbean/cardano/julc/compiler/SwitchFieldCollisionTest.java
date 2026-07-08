package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for issue #47 — switch field-name collision.
 * <p>
 * Inside a {@code switch} case, the compiler destructures the matched variant's fields into
 * scope by their bare names. A previous optimization reused any in-scope variable matching the
 * accessed field name, so accessing a field on a <em>different</em> record that happened to
 * share a field name silently returned the destructured field instead — a check-bypass
 * miscompilation (a limit/authorization comparison could compile to {@code x <= x}, always true).
 * <p>
 * These tests pin the correct behavior: {@code var.field()} always reads {@code var}'s own
 * record, and the legitimate reuse for the pattern variable's own fields still works.
 */
class SwitchFieldCollisionTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create("Java");
    }

    private Term compile(String src, String method) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(src, method);
        assertFalse(result.hasErrors(), () -> "compile failed: " + result.diagnostics());
        return result.program().term();
    }

    private static Term dataArg(PlutusData d) {
        return Term.const_(Constant.data(d));
    }

    private BigInteger evalInt(Term compiled, Term... args) {
        Term t = compiled;
        for (Term a : args) t = Term.apply(t, a);
        var r = vm.evaluate(Program.plutusV3(t));
        assertInstanceOf(EvalResult.Success.class, r, () -> "expected success: " + r);
        var val = ((Term.Const) ((EvalResult.Success) r).resultTerm()).value();
        return ((Constant.IntegerConst) val).value();
    }

    private boolean evalBool(Term compiled, Term... args) {
        Term t = compiled;
        for (Term a : args) t = Term.apply(t, a);
        var r = vm.evaluate(Program.plutusV3(t));
        assertInstanceOf(EvalResult.Success.class, r, () -> "expected success: " + r);
        var val = ((Term.Const) ((EvalResult.Success) r).resultTerm()).value();
        return ((Constant.BoolConst) val).value();
    }

    @Test
    void fieldAccessOnOtherRecordReadsThatRecord_notTheDestructuredField() {
        var src = """
                import java.math.BigInteger;

                public class Bug {
                    sealed interface Action permits Transfer, Withdraw {}
                    record Transfer(BigInteger amount) implements Action {}
                    record Withdraw(BigInteger fee) implements Action {}
                    record Box(BigInteger amount) {}   // SAME field name 'amount'

                    public static BigInteger check(Action action, Box box) {
                        return switch (action) {
                            case Transfer t -> box.amount();   // must read Box's amount, not Transfer's
                            case Withdraw w -> BigInteger.ZERO;
                        };
                    }
                }
                """;
        var compiled = compile(src, "check");
        // Transfer(amount=5), Box(amount=99) -> must be 99
        assertEquals(BigInteger.valueOf(99),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(5))),
                        dataArg(PlutusData.constr(0, PlutusData.integer(99)))));
    }

    @Test
    void limitCheckAgainstOtherRecordIsNotBypassed() {
        var src = """
                import java.math.BigInteger;

                public class Bug {
                    sealed interface Action permits Transfer, Withdraw {}
                    record Transfer(BigInteger amount) implements Action {}
                    record Withdraw(BigInteger fee) implements Action {}
                    record Limit(BigInteger amount) {}   // SAME field name

                    // true iff the transfer is within the allowed limit
                    public static boolean withinLimit(Action action, Limit limit) {
                        return switch (action) {
                            case Transfer t -> t.amount().compareTo(limit.amount()) <= 0;
                            case Withdraw w -> true;
                        };
                    }
                }
                """;
        var compiled = compile(src, "withinLimit");
        // Transfer(1000) vs Limit(10): 1000 > 10 -> must be false (check must NOT be bypassed)
        assertFalse(evalBool(compiled,
                dataArg(PlutusData.constr(0, PlutusData.integer(1000))),
                dataArg(PlutusData.constr(0, PlutusData.integer(10)))));
        // Transfer(5) vs Limit(10): 5 <= 10 -> true
        assertTrue(evalBool(compiled,
                dataArg(PlutusData.constr(0, PlutusData.integer(5))),
                dataArg(PlutusData.constr(0, PlutusData.integer(10)))));
    }

    @Test
    void patternVariableOwnFieldAccessStillWorks() {
        // The legitimate reuse: t.amount() inside `case Transfer t` reads Transfer's own field.
        var src = """
                import java.math.BigInteger;

                public class Ok {
                    sealed interface Action permits Transfer, Withdraw {}
                    record Transfer(BigInteger amount) implements Action {}
                    record Withdraw(BigInteger fee) implements Action {}

                    public static BigInteger amountOf(Action action) {
                        return switch (action) {
                            case Transfer t -> t.amount();
                            case Withdraw w -> w.fee();
                        };
                    }
                }
                """;
        var compiled = compile(src, "amountOf");
        assertEquals(BigInteger.valueOf(42),
                evalInt(compiled, dataArg(PlutusData.constr(0, PlutusData.integer(42)))));
        assertEquals(BigInteger.valueOf(7),
                evalInt(compiled, dataArg(PlutusData.constr(1, PlutusData.integer(7)))));
    }

    @Test
    void bothPatternFieldAndOtherRecordFieldUsedInSameBranch() {
        // t.amount() (destructured reuse) AND box.amount() (extraction) in one branch.
        var src = """
                import java.math.BigInteger;

                public class Mix {
                    sealed interface Action permits Transfer, Withdraw {}
                    record Transfer(BigInteger amount) implements Action {}
                    record Withdraw(BigInteger fee) implements Action {}
                    record Box(BigInteger amount) {}

                    public static BigInteger sum(Action action, Box box) {
                        return switch (action) {
                            case Transfer t -> t.amount().add(box.amount());
                            case Withdraw w -> BigInteger.ZERO;
                        };
                    }
                }
                """;
        var compiled = compile(src, "sum");
        // Transfer(amount=5) + Box(amount=99) = 104 (distinguishes: bug would give 5+5=10 or 99+99=198)
        assertEquals(BigInteger.valueOf(104),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(5))),
                        dataArg(PlutusData.constr(0, PlutusData.integer(99)))));
    }

    @Test
    void nestedSwitchWithSharedFieldNames() {
        // Inner switch destructures 'amount' too; outer box.amount() must still read box.
        var src = """
                import java.math.BigInteger;

                public class Nested {
                    sealed interface Outer permits A, B {}
                    record A(BigInteger amount) implements Outer {}
                    record B(BigInteger amount) implements Outer {}
                    sealed interface Inner permits C, D {}
                    record C(BigInteger amount) implements Inner {}
                    record D(BigInteger amount) implements Inner {}
                    record Box(BigInteger amount) {}

                    public static BigInteger pick(Outer o, Inner i, Box box) {
                        return switch (o) {
                            case A a -> switch (i) {
                                case C c -> box.amount();   // must read box, not a or c
                                case D d -> a.amount();     // must read a (outer pattern var)
                            };
                            case B b -> b.amount();
                        };
                    }
                }
                """;
        var compiled = compile(src, "pick");
        // A(amount=1), C(amount=2), Box(amount=99) -> box.amount() = 99
        assertEquals(BigInteger.valueOf(99),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(1))),
                        dataArg(PlutusData.constr(0, PlutusData.integer(2))),
                        dataArg(PlutusData.constr(0, PlutusData.integer(99)))));
        // A(amount=7), D(...), Box(...) -> a.amount() = 7
        assertEquals(BigInteger.valueOf(7),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(7))),
                        dataArg(PlutusData.constr(1, PlutusData.integer(2))),
                        dataArg(PlutusData.constr(0, PlutusData.integer(99)))));
    }

    @Test
    @Disabled("Separate known limitation from #47: destructured field names are injected into the "
            + "general namespace, so a *bare* reference to a same-named method parameter resolves to "
            + "the field instead. #47 fixes only qualified var.field() access. Tracked for a follow-up "
            + "fix (stop injecting bare field names into the symbol-table namespace). This test flips "
            + "green when that lands.")
    void fieldNameShadowsMethodParameterOfSameName() {
        // Regression for the previously-documented narrower case: a parameter named like a
        // destructured field must not be shadowed by the field binding.
        var src = """
                import java.math.BigInteger;

                public class Shadow {
                    sealed interface Action permits Transfer, Withdraw {}
                    record Transfer(BigInteger amount) implements Action {}
                    record Withdraw(BigInteger fee) implements Action {}

                    // parameter 'amount' collides with Transfer's field 'amount'
                    public static BigInteger check(Action action, BigInteger amount) {
                        return switch (action) {
                            case Transfer t -> amount;   // must read the parameter, not t's field
                            case Withdraw w -> BigInteger.ZERO;
                        };
                    }
                }
                """;
        var compiled = compile(src, "check");
        // Transfer(amount=5), param amount=99 -> must be 99
        assertEquals(BigInteger.valueOf(99),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(5))),
                        dataArg(PlutusData.integer(99))));
    }
}
