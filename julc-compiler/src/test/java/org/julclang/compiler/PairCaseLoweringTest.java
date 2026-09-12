package org.julclang.compiler;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@Tag("pair-case-backends")
class PairCaseLoweringTest {
    static final String RECORD = """
            import java.math.BigInteger;
            import java.util.Optional;
            @MintingValidator class PairRecord {
                record Redeemer(BigInteger amount, boolean approved, Optional<BigInteger> limit) {}
                @Entrypoint static boolean validate(Redeemer r, ScriptContext ctx) { return r.approved(); }
            }
            """;
    static final String SUM = """
            import java.math.BigInteger;
            @MintingValidator class PairSum {
                sealed interface Action permits Pay, Cancel {}
                record Pay(BigInteger amount) implements Action {}
                record Cancel() implements Action {}
                @Entrypoint static boolean validate(Action r, ScriptContext ctx) { return true; }
            }
            """;
    static final String NESTED = """
            import java.math.BigInteger;
            import java.util.List;
            @MintingValidator class PairNested {
                record Item(BigInteger value) {}
                record Redeemer(List<Item> items) {}
                @Entrypoint static boolean validate(Redeemer r, ScriptContext ctx) { return true; }
            }
            """;

    @Test
    void historicalBytes() throws IOException {
        var sources = List.of(RECORD, SUM, NESTED);
        for (int i = 0; i < sources.size(); i++) {
            for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
                for (boolean maps : List.of(false, true)) {
                    var compiled = compile(sources.get(i), level, maps);
                    assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
                    var bytes = UplcFlatEncoder.encodeProgram(compiled.program());
                    assertArrayEquals(bytes, UplcFlatEncoder.encodeProgram(compile(sources.get(i), level, maps).program()));
                    if (!level.pv11SafeRulesEnabled()) {
                        assertEquals(golden(i + "-" + level + "-" + maps), HexFormat.of().formatHex(bytes));
                    }
                    assertEquals(level.pv11SafeRulesEnabled(), compiled.optimizationReport().appliedRules()
                            .contains("pv11.o4.case-pair"));
                }
            }
        }
    }

    @Test
    void dataEncodedPairLikeRecordIsNotMatchedAsNativePair() {
        String source = """
                class PairLike {
                    record Tuple(long first, long second) {}
                    static long sum(Tuple pair) { return pair.first() + pair.second(); }
                }
                """;
        for (var level : List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level));
            // Exercise both public compileMethod paths, including the no-discovery overload.
            for (var compiled : List.of(compiler.compileMethod(source, "sum"),
                    compiler.compileMethod(source, "sum", List.of()))) {
                assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
                assertFalse(compiled.optimizationReport().appliedRules().contains("pv11.o4.case-pair"));
                for (String provider : List.of("Java", "Truffle", "Scalus")) {
                    var vm = CompilerTestVm.pv11(provider);
                    var args = List.of(PlutusData.constr(0, PlutusData.integer(3), PlutusData.integer(9)));
                    var result = provider.equals("Scalus") ? vm.evaluateWithArgs(compiled.program(), args)
                            : vm.evaluateWithArgs(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                                    args, null, EvalOptions.DEFAULT);
                    assertEquals(Term.const_(Constant.integer(12)), ((EvalResult.Success) result).resultTerm());
                }
            }
        }
    }

    @Test
    void aggregateRulesAgreeWithIndependentHostModel() {
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.lib.MathLib;
                @MintingValidator class AggregatePair {
                    record Redeemer(JulcList<BigInteger> values, long dropCount, BigInteger minimum) {}
                    @Entrypoint static boolean validate(Redeemer r, ScriptContext ctx) {
                        BigInteger sum = BigInteger.ZERO;
                        for (BigInteger x : r.values().drop(r.dropCount())) { sum = sum.add(x); }
                        if (sum.compareTo(r.minimum()) < 0) { return false; }
                        return sum.add(MathLib.expMod(BigInteger.TWO, BigInteger.valueOf(5), BigInteger.valueOf(13)))
                            .compareTo(BigInteger.valueOf(10)) >= 0;
                    }
                }
                """;
        var random = new Random(111);
        var inputs = new ArrayList<Scenario>();
        for (int n = 0; n < 50; n++) {
            int drop = random.nextInt(12) - 2;
            int minimum = random.nextInt(15) - 7;
            long sum = 0;
            var values = new ArrayList<PlutusData>();
            for (int j = 0; j < n % 10; j++) {
                int value = random.nextInt(11) - 5;
                values.add(PlutusData.integer(value));
                if (j >= Math.max(0, drop)) sum += value;
            }
            inputs.add(new Scenario(PlutusData.constr(0, PlutusData.list(values.toArray(PlutusData[]::new)),
                    PlutusData.integer(drop), PlutusData.integer(minimum)), sum >= minimum && sum + 6 >= 10));
        }
        // Strict boundary validation must reject even an element that drop would skip.
        inputs.add(new Scenario(PlutusData.constr(0, PlutusData.list(PlutusData.bytes(new byte[0])),
                PlutusData.integer(1), PlutusData.integer(0)), false));
        var baseline = compile(source, OptimizationLevel.BASELINE, false);
        assertFalse(baseline.hasErrors(), baseline.diagnostics().toString());
        for (var level : List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)) {
            var compiled = compile(source, level, false);
            assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
            assertTrue(compiled.optimizationReport().appliedRules().containsAll(List.of(
                    "pv11.o1.drop-list", "pv11.o2.case-bool", "pv11.o3.case-list", "pv11.o4.case-pair",
                    "pv11.o13.exp-mod-literal-fold")), compiled.optimizationReport().toString());
            assertTrue(UplcFlatEncoder.encodeProgram(compiled.program()).length < UplcFlatEncoder.encodeProgram(baseline.program()).length);
            for (var input : inputs) {
                EvalResult javaResult = null;
                for (String provider : List.of("Java", "Truffle", "Scalus")) {
                    var vm = CompilerTestVm.pv11(provider);
                    var before = evaluate(vm, provider, baseline.program(), input.redeemer());
                    var after = evaluate(vm, provider, compiled.program(), input.redeemer());
                    assertEquals(input.success(), after.isSuccess(), input.toString());
                    assertEquals(before.getClass(), after.getClass());
                    assertEquals(before.traces(), after.traces());
                    if (before instanceof EvalResult.Failure b && after instanceof EvalResult.Failure a) assertEquals(b.error(), a.error());
                    if (provider.equals("Java")) javaResult = after;
                    if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed());
                    if (!provider.equals("Scalus")) {
                        assertTrue(after.budgetConsumed().cpuSteps() <= before.budgetConsumed().cpuSteps());
                        assertTrue(after.budgetConsumed().memoryUnits() <= before.budgetConsumed().memoryUnits());
                    }
                }
            }
        }
    }

    record Scenario(PlutusData redeemer, boolean success) {}

    @Test
    void completeValidatorsAcrossBackends() throws IOException {
        var yes = PlutusData.constr(1);
        var none = PlutusData.constr(1);
        var validRecord = PlutusData.constr(0, PlutusData.integer(9), yes, none);
        var scenarios = List.of(
                List.of(new Scenario(validRecord, true),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(9), yes,
                                PlutusData.constr(0, PlutusData.integer(4))), true),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(9), PlutusData.constr(0), none), false),
                        new Scenario(PlutusData.constr(0, PlutusData.bytes(new byte[0]), yes, none), false),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(9), PlutusData.constr(2), none), false),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(9), PlutusData.constr(1, yes), none), false),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(9), yes, PlutusData.constr(0)), false),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(9), yes,
                                PlutusData.constr(0, PlutusData.bytes(new byte[0]))), false),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(9), yes, none, none), false)),
                List.of(new Scenario(PlutusData.constr(0, PlutusData.integer(9)), true),
                        new Scenario(PlutusData.constr(1), true),
                        new Scenario(PlutusData.constr(2), false),
                        new Scenario(PlutusData.constr(0, PlutusData.bytes(new byte[0])), false),
                        new Scenario(PlutusData.constr(1, PlutusData.integer(9)), false)),
                List.of(new Scenario(PlutusData.constr(0, PlutusData.list()), true),
                        new Scenario(PlutusData.constr(0, PlutusData.list(PlutusData.constr(0, PlutusData.integer(9)))), true),
                        new Scenario(PlutusData.constr(0, PlutusData.list(PlutusData.constr(1, PlutusData.integer(9)))), false),
                        new Scenario(PlutusData.constr(0, PlutusData.list(PlutusData.constr(0, PlutusData.bytes(new byte[0])))), false),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(9)), false)));
        var sources = List.of(RECORD, SUM, NESTED);
        for (int i = 0; i < sources.size(); i++) {
            var all = new ArrayList<>(scenarios.get(i));
            all.add(new Scenario(PlutusData.integer(0), false));
            all.add(new Scenario(PlutusData.constr(99), false));
            all.add(new Scenario(PlutusData.constr(0), false));
            for (var level : List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)) {
                for (boolean maps : List.of(false, true)) {
                    var old = UplcFlatDecoder.decodeProgram(HexFormat.of().parseHex(golden(i + "-PV11_SAFE-" + maps)));
                    var compiled = compile(sources.get(i), level, maps);
                    assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
                    var program = compiled.program();
                    if (level.pv11SafeRulesEnabled()) {
                        assertTrue(compiled.optimizationReport().appliedRules().contains("pv11.o4.case-pair"));
                        assertTrue(UplcFlatEncoder.encodeProgram(program).length < UplcFlatEncoder.encodeProgram(old).length);
                        assertNotEquals(JulcScriptAdapter.scriptHash(old), JulcScriptAdapter.scriptHash(program));
                    }
                    for (var scenario : all) {
                        EvalResult javaResult = null;
                        for (String provider : List.of("Java", "Truffle", "Scalus")) {
                            var vm = CompilerTestVm.pv11(provider);
                            var before = evaluate(vm, provider, old, scenario.redeemer());
                            var after = evaluate(vm, provider, program, scenario.redeemer());
                            String label = i + "/" + level + "/" + maps + "/" + provider + "/" + scenario;
                            assertEquals(scenario.success(), after.isSuccess(), label);
                            assertEquals(before.getClass(), after.getClass(), label);
                            assertEquals(before.traces(), after.traces(), label);
                            if (before instanceof EvalResult.Success b && after instanceof EvalResult.Success a) {
                                assertEquals(b.resultTerm(), a.resultTerm(), label);
                            } else if (before instanceof EvalResult.Failure b && after instanceof EvalResult.Failure a) {
                                assertEquals(b.error(), a.error(), label);
                            }
                            if (provider.equals("Java")) javaResult = after;
                            if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), label);
                            if (level.pv11SafeRulesEnabled() && !provider.equals("Scalus")) {
                                assertTrue(after.budgetConsumed().cpuSteps() <= before.budgetConsumed().cpuSteps(), label);
                                assertTrue(after.budgetConsumed().memoryUnits() <= before.budgetConsumed().memoryUnits(), label);
                            }
                            if (level == OptimizationLevel.PV11_SAFE && !maps && provider.equals("Java")) {
                                System.out.println("PAIR_COST " + i + " " + scenario + " " + before.budgetConsumed() + " -> " + after.budgetConsumed());
                            }
                        }
                    }
                    if (level == OptimizationLevel.PV11_SAFE && !maps) {
                        System.out.println("PAIR_ARTIFACT " + i + " " + UplcFlatEncoder.encodeProgram(old).length
                                + " -> " + UplcFlatEncoder.encodeProgram(program).length + " " + JulcScriptAdapter.scriptHash(program));
                    }
                }
            }
        }
    }

    private static EvalResult evaluate(JulcVm vm, String provider, Program program, PlutusData redeemer) {
        var context = PlutusData.constr(0, PlutusData.integer(0), redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        return provider.equals("Scalus") ? vm.evaluateWithArgs(program, List.of(context))
                : vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                        List.of(context), null, EvalOptions.DEFAULT);
    }

    static String golden(String id) throws IOException {
        try (var input = PairCaseLoweringTest.class.getResourceAsStream("/optimization/o4-pre-change-bytes.txt")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(id + " ")).findFirst().orElseThrow().substring(id.length() + 1);
        }
    }

    static CompileResult compile(String source, OptimizationLevel level, boolean maps) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(level).setSourceMapEnabled(maps)
                .setOptimizationCostProfile(OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11)).compile(source);
    }
}
