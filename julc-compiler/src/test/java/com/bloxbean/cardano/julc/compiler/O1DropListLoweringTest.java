package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.pir.TypeMethodRegistry;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class O1DropListLoweringTest {

    private static final String SOURCE = """
            import com.bloxbean.cardano.julc.core.PlutusData;
            import com.bloxbean.cardano.julc.core.types.JulcList;
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            class DropListSample {
                static PlutusData drop(PlutusData data, long count) {
                    JulcList<PlutusData> items = Builtins.unListData(data);
                    return Builtins.listData(items.drop(count));
                }
            }
            """;

    @Test
    void safeLevelEmitsNativeDropListAndStableRuleIdentity() {
        var baseline = compile(OptimizationLevel.BASELINE);
        var candidate = compile(OptimizationLevel.PV11_SAFE);

        assertEquals(0, builtinCount(baseline.program().term(), DefaultFun.DropList));
        assertEquals(1, builtinCount(candidate.program().term(), DefaultFun.DropList));
        assertFalse(baseline.optimizationReport().appliedRules()
                .contains("pv11.o1.drop-list"));
        assertTrue(candidate.optimizationReport().appliedRules()
                .contains("pv11.o1.drop-list"));
        assertTrue(candidate.scriptSizeBytes() < baseline.scriptSizeBytes());
    }

    @Test
    void defaultAndExplicitSafeUseNativeLoweringBytes() {
        var defaults = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(SOURCE, "drop");
        var safe = compile(OptimizationLevel.PV11_SAFE);

        assertArrayEquals(
                UplcFlatEncoder.encodeProgram(defaults.program()),
                UplcFlatEncoder.encodeProgram(safe.program()));
        assertEquals(1, builtinCount(defaults.program().term(), DefaultFun.DropList));
        assertEquals(OptimizationLevel.PV11_SAFE,
                defaults.optimizationReport().level());
    }

    @Test
    void capabilityRequirementExistsOnlyWhenNativeRuleIsSelected() {
        var registry = TypeMethodRegistry.defaultRegistry();
        var listType = new PirType.ListType(new PirType.DataType());
        var baseline = CompilationContext.resolve(new CompilerOptions()
                .setOptimizationLevel(OptimizationLevel.BASELINE));
        var safe = CompilationContext.resolve(new CompilerOptions()
                .setOptimizationLevel(OptimizationLevel.PV11_SAFE));

        assertTrue(registry.requirements(baseline, listType, "drop").isEmpty());
        assertEquals(java.util.Set.of(DefaultFun.DropList),
                registry.requirements(safe, listType, "drop").builtins());
    }

    private static CompileResult compile(OptimizationLevel level) {
        return new JulcCompiler(
                StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level))
                .compileMethod(SOURCE, "drop");
    }

    private static int builtinCount(Term term, DefaultFun target) {
        return switch (term) {
            case Term.Var _, Term.Const _, Term.Error() -> 0;
            case Term.Builtin(var fun) -> fun == target ? 1 : 0;
            case Term.Lam(_, var body) -> builtinCount(body, target);
            case Term.Delay(var body) -> builtinCount(body, target);
            case Term.Force(var body) -> builtinCount(body, target);
            case Term.Apply(var function, var argument) ->
                    builtinCount(function, target) + builtinCount(argument, target);
            case Term.Constr(_, var fields) -> fields.stream()
                    .mapToInt(field -> builtinCount(field, target)).sum();
            case Term.Case(var scrutinee, var branches) ->
                    builtinCount(scrutinee, target) + branches.stream()
                            .mapToInt(branch -> builtinCount(branch, target)).sum();
        };
    }
}
