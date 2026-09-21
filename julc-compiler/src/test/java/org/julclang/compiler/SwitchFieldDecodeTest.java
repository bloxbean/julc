package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Typed decoding of the cached field bindings introduced by switch patterns. */
class SwitchFieldDecodeTest {
    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            class FieldDecode {
                sealed interface Action permits Sum, Skip {}
                record Sum(boolean enabled) implements Action {}
                record Skip() implements Action {}
            """;

    private static CompileResult compile(String source, OptimizationLevel level) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m");
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        return result;
    }

    private static EvalResult evaluate(String backend, CompileResult compiled, PlutusData... args) {
        var vm = CompilerTestVm.pv11(backend);
        return backend.equals("Scalus") ? vm.evaluateWithArgs(compiled.program(), List.of(args))
                : vm.evaluateWithArgs(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                        List.of(args), null, EvalOptions.DEFAULT);
    }

    private static void assertInteger(String backend, CompileResult compiled, long expected, PlutusData... args) {
        var result = evaluate(backend, compiled, args);
        var success = assertInstanceOf(EvalResult.Success.class, result, () -> result.toString());
        assertEquals(Term.const_(Constant.integer(expected)), success.resultTerm());
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void booleanSwitchField(OptimizationLevel level) {
        checkBooleanSwitchField("Java", level);
    }

    private static void checkBooleanSwitchField(String backend, OptimizationLevel level) {
        var compiled = compile(HEADER + """
                static BigInteger m(Action action) {
                    return switch (action) {
                        case Sum s -> s.enabled() ? BigInteger.ONE : BigInteger.ZERO;
                        case Skip s -> BigInteger.TEN;
                    };
                } }
                """, level);
        assertAll(
                () -> assertInteger(backend, compiled, 1, PlutusData.constr(0, PlutusData.constr(1))),
                () -> assertInteger(backend, compiled, 0, PlutusData.constr(0, PlutusData.constr(0))),
                () -> assertInteger(backend, compiled, 10, PlutusData.constr(1)));
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void stringSwitchField(OptimizationLevel level) {
        checkStringSwitchField("Java", level);
    }

    private static void checkStringSwitchField(String backend, OptimizationLevel level) {
        var compiled = compile("""
                import java.math.BigInteger;
                class FieldDecode {
                    sealed interface Action permits Named, Skip {}
                    record Named(String name) implements Action {}
                    record Skip() implements Action {}
                    static BigInteger m(Action action) {
                        return switch (action) {
                            case Named n -> n.name().equals("hello") ? BigInteger.ONE : BigInteger.ZERO;
                            case Skip s -> BigInteger.TEN;
                        };
                    }
                }
                """, level);
        assertInteger(backend, compiled, 1, PlutusData.constr(0, text("hello")));
        assertInteger(backend, compiled, 0, PlutusData.constr(0, text("")));
        assertInteger(backend, compiled, 0, PlutusData.constr(0, text("世界")));
        assertInteger(backend, compiled, 10, PlutusData.constr(1));
    }

    private static PlutusData text(String value) {
        return PlutusData.bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final String LOOP_EXAMPLE = """
            static BigInteger m(JulcList<BigInteger> xs, Action action) {
                BigInteger acc = BigInteger.ZERO;
                for (var x : xs) {
                    BigInteger step = switch (action) {
                        case Sum s -> {
                            BigInteger local = BigInteger.ZERO;
                            if (s.enabled().signum() > 0) {
                                for (var y : xs) { local = local.add(y); }
                            }
                            yield local;
                        }
                        case Skip s -> BigInteger.ZERO;
                    };
                    acc = acc.add(step);
                }
                return acc;
            } }
            """;

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void correctedPrExample(OptimizationLevel level) {
        var compiled = compile(HEADER.replace("boolean enabled", "BigInteger enabled") + LOOP_EXAMPLE, level);
        var xs = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
        assertInteger("Java", compiled, 18, xs, PlutusData.constr(0, PlutusData.integer(1)));
        assertInteger("Java", compiled, 0, xs, PlutusData.constr(0, PlutusData.integer(0)));
        assertInteger("Java", compiled, 0, xs, PlutusData.constr(1));
        assertInteger("Java", compiled, 0, PlutusData.list(), PlutusData.constr(0, PlutusData.integer(1)));
    }

    @Test
    void compositionsAndDecodeStrictnessOnJava() {
        checkCompositions("Java");
        checkDecodeStrictness("Java");
    }

    @Test
    @Tag("pair-case-backends")
    void fieldsOnOtherBackends() {
        for (String backend : List.of("Truffle", "Scalus")) {
            for (var level : OptimizationLevel.values()) {
                checkBooleanSwitchField(backend, level);
                checkStringSwitchField(backend, level);
            }
            checkCompositions(backend);
            checkDecodeStrictness(backend);
        }
    }

    private static void checkCompositions(String backend) {
        for (var level : OptimizationLevel.values()) {
            // Equivalent direct, instanceof, local-construction and nested-switch access paths.
            for (String body : List.of(
                    "static BigInteger m(Sum s) { return s.enabled() ? BigInteger.ONE : BigInteger.ZERO; }",
                    "static BigInteger m(Action action) { if (action instanceof Sum s) { "
                            + "return s.enabled() ? BigInteger.ONE : BigInteger.ZERO; } return BigInteger.TEN; }",
                    "static BigInteger m(Sum input) { Action action = new Sum(input.enabled()); "
                            + "return switch (action) { case Sum s -> s.enabled() ? BigInteger.ONE : BigInteger.ZERO; "
                            + "case Skip s -> BigInteger.TEN; }; }",
                    "static BigInteger m(Action action) { return switch (action) { case Sum s -> { "
                            + "Action inner = new Sum(s.enabled()); yield switch (inner) { "
                            + "case Sum t -> t.enabled() ? BigInteger.ONE : BigInteger.ZERO; "
                            + "case Skip t -> BigInteger.TEN; }; } case Skip s -> BigInteger.TEN; }; }")) {
                var compiled = compile(HEADER + body + "}", level);
                assertArrayEquals(UplcFlatEncoder.encodeProgram(compiled.program()),
                        UplcFlatEncoder.encodeProgram(compile(HEADER + body + "}", level).program()));
                for (int flag = 0; flag <= 1; flag++) {
                    assertInteger(backend, compiled, flag, PlutusData.constr(0, PlutusData.constr(flag)));
                }
            }
            var loop = compile(HEADER + LOOP_EXAMPLE.replace("s.enabled().signum() > 0", "s.enabled()"), level);
            for (var xs : List.of(PlutusData.list(),
                    PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)))) {
                assertInteger(backend, loop, xs.equals(PlutusData.list()) ? 0 : 18,
                        xs, PlutusData.constr(0, PlutusData.constr(1)));
                assertInteger(backend, loop, 0, xs, PlutusData.constr(0, PlutusData.constr(0)));
                assertInteger(backend, loop, 0, xs, PlutusData.constr(1));
            }
            var mixed = compile("""
                    import java.math.BigInteger;
                    class FieldDecode {
                        sealed interface Action permits Mixed, Skip {}
                        record Mixed(boolean enabled, String name, BigInteger amount, boolean extra) implements Action {}
                        record Skip() implements Action {}
                        static BigInteger m(Action action) {
                            return switch (action) {
                                case Mixed x -> x.enabled() && x.name().equals("世界") && !x.extra()
                                        ? x.amount() : BigInteger.ZERO;
                                case Skip x -> BigInteger.TEN;
                            };
                        }
                    }
                    """, level);
            assertInteger(backend, mixed, 42, PlutusData.constr(0, PlutusData.constr(1),
                    text("世界"), PlutusData.integer(42), PlutusData.constr(0)));
            assertInteger(backend, mixed, 0, PlutusData.constr(0, PlutusData.constr(0),
                    text("世界"), PlutusData.integer(42), PlutusData.constr(0)));
            assertInteger(backend, mixed, 10, PlutusData.constr(1));
        }
    }

    private record DecodeCase(PlutusData input, Long expected) {}

    private static void checkDecodeStrictness(String backend) {
        var bool = new PirType.BoolType();
        var string = new PirType.StringType();
        var branches = List.of(
                new PirTerm.MatchBranch("Fields", List.of("flag", "name"), List.of(bool, string),
                        new PirTerm.Trace(new PirTerm.Const(Constant.string("selected")),
                                new PirTerm.IfThenElse(new PirTerm.Var("flag", bool),
                                        new PirTerm.Const(Constant.integer(1)), new PirTerm.Const(Constant.integer(0))))),
                new PirTerm.MatchBranch("Skip", List.of(), List.of(),
                        new PirTerm.Trace(new PirTerm.Const(Constant.string("skip")),
                                new PirTerm.Const(Constant.integer(10)))));
        for (var level : OptimizationLevel.values()) {
            for (var fixture : List.of(
                    new DecodeCase(PlutusData.constr(0, PlutusData.constr(1), text("ok")), 1L),
                    new DecodeCase(PlutusData.constr(0, PlutusData.constr(0), text("")), 0L),
                    // Preserve the shared non-strict decoder's historical tag/arity contract.
                    new DecodeCase(PlutusData.constr(0, PlutusData.constr(2, PlutusData.integer(9)), text("ok")), 0L),
                    new DecodeCase(PlutusData.constr(0, PlutusData.constr(1, PlutusData.integer(9)), text("ok")), 1L),
                    new DecodeCase(PlutusData.constr(0, PlutusData.integer(1), text("ok")), null),
                    new DecodeCase(PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(9)), null),
                    new DecodeCase(PlutusData.constr(0, PlutusData.constr(1), PlutusData.bytes(new byte[]{(byte) 0xff})), null),
                    new DecodeCase(PlutusData.constr(0), null), new DecodeCase(PlutusData.constr(1), 10L))) {
                var input = fixture.input();
                var match = new PirTerm.DataMatch(new PirTerm.Trace(new PirTerm.Const(Constant.string("input")),
                        new PirTerm.Const(Constant.data(input))), branches);
                var result = evaluateProgram(backend, new JulcCompiler(null,
                        new CompilerOptions().setOptimizationLevel(level)).compilePirToProgram(match));
                boolean skip = input.equals(PlutusData.constr(1));
                if (fixture.expected() != null) {
                    var success = assertInstanceOf(EvalResult.Success.class, result, backend + "/" + level + "/" + input);
                    assertEquals(Term.const_(Constant.integer(fixture.expected())), success.resultTerm());
                    assertEquals(List.of("input", skip ? "skip" : "selected"), result.traces());
                } else {
                    assertInstanceOf(EvalResult.Failure.class, result, backend + "/" + level + "/" + input);
                    assertEquals(List.of("input"), result.traces());
                }
            }
            // Bool decoding must remain strict even when the body never reads the field.
            for (var field : List.of(PlutusData.constr(1), PlutusData.integer(1))) {
                var ignored = new PirTerm.DataMatch(new PirTerm.Const(Constant.data(PlutusData.constr(0, field))),
                        List.of(new PirTerm.MatchBranch("Ignored", List.of("flag"), List.of(bool),
                                new PirTerm.Trace(new PirTerm.Const(Constant.string("body")),
                                        new PirTerm.Const(Constant.integer(42))))));
                var result = evaluateProgram(backend, new JulcCompiler(null,
                        new CompilerOptions().setOptimizationLevel(level)).compilePirToProgram(ignored));
                if (field instanceof PlutusData.ConstrData) {
                    var success = assertInstanceOf(EvalResult.Success.class, result);
                    assertEquals(Term.const_(Constant.integer(42)), success.resultTerm());
                    assertEquals(List.of("body"), result.traces());
                } else {
                    assertInstanceOf(EvalResult.Failure.class, result);
                    assertTrue(result.traces().isEmpty());
                }
            }
        }
    }

    private static EvalResult evaluateProgram(String backend, Program program) {
        var vm = CompilerTestVm.pv11(backend);
        return backend.equals("Scalus") ? vm.evaluate(program)
                : vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                        List.of(), null, EvalOptions.DEFAULT);
    }

    @Test
    void dataMatchCannotPretendOpaqueNativeFieldsAreData() {
        for (var type : List.of(new PirType.NativeG1Type(), new PirType.NativeG2Type(),
                new PirType.NativeMlResultType(), new PirType.NativeValueType())) {
            var match = new PirTerm.DataMatch(new PirTerm.Const(Constant.data(PlutusData.constr(0, PlutusData.integer(0)))),
                    List.of(new PirTerm.MatchBranch("Invalid", List.of("x"), List.of(type), new PirTerm.Var("x", type))));
            for (var level : OptimizationLevel.values()) {
                assertThrows(CompilerException.class, () -> new JulcCompiler(null,
                        new CompilerOptions().setOptimizationLevel(level)).compilePirToProgram(match));
            }
        }
    }
}
