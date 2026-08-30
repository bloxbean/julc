package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
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
        vm = CompilerTestVm.pv11("Java");
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

    @Test
    void parameterUsedAlongsidePatternFieldOfSameNameInOneBranch() {
        // Both `amount` (the parameter) and `t.amount()` (the field) appear in the same branch
        // and must refer to different values.
        var src = """
                import java.math.BigInteger;

                public class Mix {
                    sealed interface Action permits Transfer, Withdraw {}
                    record Transfer(BigInteger amount) implements Action {}
                    record Withdraw(BigInteger fee) implements Action {}

                    public static BigInteger diff(Action action, BigInteger amount) {
                        return switch (action) {
                            case Transfer t -> amount.subtract(t.amount());  // param - field
                            case Withdraw w -> BigInteger.ZERO;
                        };
                    }
                }
                """;
        var compiled = compile(src, "diff");
        // param amount=99, Transfer(amount=5): 99 - 5 = 94
        assertEquals(BigInteger.valueOf(94),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(5))),
                        dataArg(PlutusData.integer(99))));
    }

    @Test
    void outerLocalNotShadowedByDestructuredField() {
        // A local declared before the switch, sharing a name with a field, must not be shadowed.
        var src = """
                import java.math.BigInteger;

                public class Local {
                    sealed interface Action permits Transfer, Withdraw {}
                    record Transfer(BigInteger amount) implements Action {}
                    record Withdraw(BigInteger fee) implements Action {}

                    public static BigInteger pick(Action action, BigInteger seed) {
                        BigInteger amount = seed.add(BigInteger.valueOf(1000));  // outer local 'amount'
                        return switch (action) {
                            case Transfer t -> amount;   // must read the outer local, not t.amount()
                            case Withdraw w -> BigInteger.ZERO;
                        };
                    }
                }
                """;
        var compiled = compile(src, "pick");
        // seed=1, outer amount = 1001; Transfer(amount=5) -> must be 1001, not 5
        assertEquals(BigInteger.valueOf(1001),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(5))),
                        dataArg(PlutusData.integer(1))));
    }

    @Test
    void nestedSwitchParameterNotShadowedByInnerField() {
        // Parameter 'amount' must survive both an outer and an inner destructuring of 'amount'.
        var src = """
                import java.math.BigInteger;

                public class NestedShadow {
                    sealed interface Outer permits A, B {}
                    record A(BigInteger amount) implements Outer {}
                    record B(BigInteger amount) implements Outer {}
                    sealed interface Inner permits C, D {}
                    record C(BigInteger amount) implements Inner {}
                    record D(BigInteger amount) implements Inner {}

                    public static BigInteger pick(Outer o, Inner i, BigInteger amount) {
                        return switch (o) {
                            case A a -> switch (i) {
                                case C c -> amount;   // must read the parameter, not a/c fields
                                case D d -> BigInteger.ZERO;
                            };
                            case B b -> BigInteger.ONE;
                        };
                    }
                }
                """;
        var compiled = compile(src, "pick");
        // A(amount=1), C(amount=2), param amount=99 -> must be 99
        assertEquals(BigInteger.valueOf(99),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(1))),
                        dataArg(PlutusData.constr(0, PlutusData.integer(2))),
                        dataArg(PlutusData.integer(99))));
    }

    @Test
    void userLocalCannotCollideWithInternalFieldBinding() {
        // Java permits the old underscore-only internal spelling as a source identifier.
        // A mutation back to "__pfield_" makes t.amount() resolve to this local and return 99.
        var src = """
                import java.math.BigInteger;

                public class InternalNameCollision {
                    sealed interface Action permits Transfer, Withdraw {}
                    record Transfer(BigInteger amount) implements Action {}
                    record Withdraw(BigInteger fee) implements Action {}

                    public static BigInteger check(Action action) {
                        return switch (action) {
                            case Transfer t -> {
                                BigInteger __pfield_t_0 = BigInteger.valueOf(99);
                                yield t.amount();
                            }
                            case Withdraw w -> BigInteger.ZERO;
                        };
                    }
                }
                """;
        var compiled = compile(src, "check");
        // Transfer(amount=5) must read the record field, not the same-looking source local.
        assertEquals(BigInteger.valueOf(5),
                evalInt(compiled,
                        dataArg(PlutusData.constr(0, PlutusData.integer(5)))));
    }
}
