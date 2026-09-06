package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.decompiler.DecompileOptions;
import com.bloxbean.cardano.julc.decompiler.JulcDecompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwitchPairCaseRecognitionTest {
    @Test
    void freshlyCompiledSwitchSurvivesSerializationAndGenericCaseDecompilation() {
        String source = """
                class SwitchPair {
                    sealed interface Action permits Pay, Cancel {}
                    record Pay(long amount) implements Action {}
                    record Cancel() implements Action {}
                    static long run(Action action) {
                        return switch (action) { case Pay p -> p.amount(); case Cancel c -> 0; };
                    }
                }
                """;
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "run");
            assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
            assertEquals(level.pv11SafeRulesEnabled(), compiled.optimizationReport().appliedRules().contains("pv11.o4.case-pair"));
            var decoded = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(compiled.program()));
            var decompiled = JulcDecompiler.decompile(decoded, DecompileOptions.defaults());
            assertNotNull(decompiled.hir());
            assertTrue(hasMatch(decompiled.hir()), level.toString());
            assertTrue(decompiled.javaSource().contains("unConstrData"), decompiled.javaSource());
            assertTrue(decompiled.javaSource().contains("if ("), decompiled.javaSource());
            for (var input : List.of(PlutusData.constr(0, PlutusData.integer(7)), PlutusData.constr(1),
                    PlutusData.constr(99), PlutusData.constr(0), PlutusData.constr(0, PlutusData.bytes(new byte[0])))) {
                ConstructorDispatchTest.equivalent(Term.apply(decoded.term(), Term.const_(Constant.data(input))));
            }
            assertFalse(decompiled.javaSource().isBlank());
        }
    }
    @Test
    void nestedAndSingletonSourcesKeepCaptureAndDoNotInventChecks() {
        String nested = """
                class Nested {
                    sealed interface Action permits Pay, Cancel {}
                    record Pay(long amount) implements Action {}
                    record Cancel() implements Action {}
                    static long run(Action a, Action b) {
                        return switch (a) {
                            case Pay outer -> switch (b) {
                                case Pay inner -> outer.amount() + inner.amount();
                                case Cancel c -> outer.amount();
                            };
                            case Cancel c -> 0;
                        };
                    }
                }
                """;
        String singleton = """
                class Single {
                    sealed interface Action permits Only {}
                    record Only() implements Action {}
                    static long run(Action a) { return switch (a) { case Only o -> 7; }; }
                }
                """;
        for (String source : List.of(nested, singleton)) {
            for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
                var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level))
                        .compileMethod(source, "run");
                assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
                var term = compiled.program().term();
                assertTrue(hasMatch(UplcLifter.lift(term)), level + source);
                term = Term.apply(term, Term.const_(Constant.data(PlutusData.constr(0, PlutusData.integer(7)))));
                if (source.equals(nested)) term = Term.apply(term, Term.const_(Constant.data(PlutusData.constr(0, PlutusData.integer(9)))));
                ConstructorDispatchTest.equivalent(term);
            }
        }
    }

    private static boolean hasMatch(HirTerm h) {
        return switch (h) {
            case HirTerm.DataMatch _ -> true;
            case HirTerm.Lambda l -> hasMatch(l.body());
            case HirTerm.Let l -> hasMatch(l.value()) || hasMatch(l.body());
            case HirTerm.If i -> hasMatch(i.thenBranch()) || hasMatch(i.elseBranch());
            default -> false;
        };
    }

}
