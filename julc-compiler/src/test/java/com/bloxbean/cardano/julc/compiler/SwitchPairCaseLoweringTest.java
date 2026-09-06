package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("pair-case-backends")
class SwitchPairCaseLoweringTest {
    static final String SWITCH = """
            import java.math.BigInteger;
            @MintingValidator class SwitchPair {
                sealed interface Action permits Pay, Cancel {}
                record Pay(BigInteger amount, BigInteger unused) implements Action {}
                record Cancel() implements Action {}
                @Entrypoint static boolean validate(Action r, ScriptContext ctx) {
                    return switch (r) {
                        case Pay p -> p.amount().compareTo(BigInteger.ZERO) > 0;
                        case Cancel c -> true;
                    };
                }
            }
            """;
    static final String NESTED = """
            import java.math.BigInteger;
            @MintingValidator class NestedSwitchPair {
                sealed interface Node permits End, Link {}
                record End() implements Node {}
                record Link(BigInteger value, Node next) implements Node {}
                @Entrypoint static boolean validate(Node node, ScriptContext ctx) {
                    return switch (node) {
                        case End e -> true;
                        case Link l -> switch (l.next()) {
                            case End e -> l.value().compareTo(BigInteger.ZERO) > 0;
                            case Link inner -> inner.value().compareTo(l.value()) > 0;
                        };
                    };
                }
            }
            """;
    static final String SINGLE = """
            import java.math.BigInteger;
            @MintingValidator class SingleSwitchPair {
                sealed interface Action permits Only {}
                record Only(BigInteger value) implements Action {}
                @Entrypoint static boolean validate(Action r, ScriptContext ctx) {
                    return switch (r) { case Only p -> p.value().compareTo(BigInteger.ZERO) > 0; };
                }
            }
            """;
    static final List<String> SOURCES = List.of(SWITCH, NESTED, SINGLE);

    @Test
    void historicalBytesAndCompleteValidatorsAcrossBackends() throws IOException {
        var end = PlutusData.constr(0);
        var link = PlutusData.constr(1, PlutusData.integer(7), end);
        var scenarios = List.of(
                List.of(new Scenario(PlutusData.constr(0, PlutusData.integer(7), PlutusData.integer(9)), true),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(0), PlutusData.integer(9)), false),
                        new Scenario(PlutusData.constr(1), true),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[0])), false),
                        new Scenario(PlutusData.constr(1, PlutusData.integer(7)), false)),
                List.of(new Scenario(end, true), new Scenario(link, true),
                        new Scenario(PlutusData.constr(1, PlutusData.integer(2), link), true),
                        new Scenario(PlutusData.constr(1, PlutusData.integer(9), link), false),
                        new Scenario(PlutusData.constr(1, PlutusData.integer(2), PlutusData.constr(3)), false),
                        new Scenario(PlutusData.constr(1, PlutusData.integer(2),
                                PlutusData.constr(1, PlutusData.bytes(new byte[0]), end)), false)),
                List.of(new Scenario(PlutusData.constr(0, PlutusData.integer(7)), true),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(-1)), false),
                        new Scenario(PlutusData.constr(0, PlutusData.bytes(new byte[0])), false),
                        new Scenario(PlutusData.constr(0, PlutusData.integer(7), end), false)));
        for (int i = 0; i < SOURCES.size(); i++) {
            var inputs = new ArrayList<>(scenarios.get(i));
            inputs.add(new Scenario(PlutusData.integer(0), false));
            inputs.add(new Scenario(PlutusData.constr(99), false));
            if (i != 1) inputs.add(new Scenario(PlutusData.constr(0), false));
            for (var level : OptimizationLevel.values()) {
                for (boolean maps : List.of(false, true)) {
                    var compiled = PairCaseLoweringTest.compile(SOURCES.get(i), level, maps);
                    assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
                    var program = compiled.program();
                    var bytes = UplcFlatEncoder.encodeProgram(program);
                    assertArrayEquals(bytes, UplcFlatEncoder.encodeProgram(
                            PairCaseLoweringTest.compile(SOURCES.get(i), level, maps).program()));
                    var old = golden(i, level.pv11SafeRulesEnabled() ? OptimizationLevel.PV11_SAFE : level, maps);
                    if (!level.pv11SafeRulesEnabled()) assertArrayEquals(UplcFlatEncoder.encodeProgram(old), bytes);
                    else {
                        assertTrue(bytes.length < UplcFlatEncoder.encodeProgram(old).length, i + "/" + maps);
                        assertNotEquals(JulcScriptAdapter.scriptHash(old), JulcScriptAdapter.scriptHash(program));
                    }
                    for (var input : inputs) {
                        EvalResult javaResult = null;
                        for (String provider : List.of("Java", "Truffle", "Scalus")) {
                            var before = evaluate(old, input.data(), provider);
                            var after = evaluate(program, input.data(), provider);
                            String label = i + "/" + level + "/" + maps + "/" + provider + "/" + input;
                            assertEquals(input.success(), after.isSuccess(), label);
                            assertEquivalent(before, after, label);
                            if (provider.equals("Java")) javaResult = after;
                            if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), label);
                            if (level.pv11SafeRulesEnabled() && !provider.equals("Scalus")) {
                                assertTrue(after.budgetConsumed().cpuSteps() <= before.budgetConsumed().cpuSteps(), label);
                                assertTrue(after.budgetConsumed().memoryUnits() <= before.budgetConsumed().memoryUnits(), label);
                            }
                            if (level == OptimizationLevel.PV11_SAFE && !maps && provider.equals("Java")) {
                                System.out.println("SWITCH_COST " + i + " " + input + " " + before.budgetConsumed() + " -> " + after.budgetConsumed());
                            }
                        }
                    }
                    if (level == OptimizationLevel.PV11_SAFE && !maps) System.out.println("SWITCH_ARTIFACT " + i + " "
                            + UplcFlatEncoder.encodeProgram(old).length + " -> " + bytes.length + " "
                            + JulcScriptAdapter.scriptHash(old) + " -> " + JulcScriptAdapter.scriptHash(program));
                }
            }
        }
    }

    @Test
    void sourceDefaultsBranchGuardsAndOuterVariablesWorkOnBothMethodEntryPoints() {
        String source = """
                import java.math.BigInteger;
                class SwitchMethod {
                    sealed interface Action permits Pay, Cancel {}
                    record Pay(BigInteger amount, BigInteger unused) implements Action {}
                    record Cancel() implements Action {}
                    static BigInteger run(Action action, BigInteger amount) {
                        return switch (action) {
                            case Pay p -> p.amount().compareTo(BigInteger.ZERO) > 0 ? p.amount().add(amount) : amount;
                            default -> amount;
                        };
                    }
                }
                """;
        for (var level : List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level));
            for (var compiled : List.of(compiler.compileMethod(source, "run"), compiler.compileMethod(source, "run", List.of()))) {
                assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
                assertEquals(level.pv11SafeRulesEnabled(), compiled.optimizationReport().appliedRules().contains("pv11.o4.case-pair"));
                for (int amount : List.of(-3, 0, 7)) {
                    for (String provider : List.of("Java", "Truffle", "Scalus")) {
                        var vm = CompilerTestVm.pv11(provider);
                        var args = List.of(PlutusData.constr(0, PlutusData.integer(amount), PlutusData.integer(1)), PlutusData.integer(100));
                        var result = provider.equals("Scalus") ? vm.evaluateWithArgs(compiled.program(), args)
                                : vm.evaluateWithArgs(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), args, null, EvalOptions.DEFAULT);
                        assertEquals(Term.const_(Constant.integer(100 + Math.max(0, amount))), ((EvalResult.Success) result).resultTerm());
                        var cancelArgs = List.of(PlutusData.constr(1), PlutusData.integer(100));
                        var cancel = provider.equals("Scalus") ? vm.evaluateWithArgs(compiled.program(), cancelArgs)
                                : vm.evaluateWithArgs(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), cancelArgs, null, EvalOptions.DEFAULT);
                        assertEquals(Term.const_(Constant.integer(100)), ((EvalResult.Success) cancel).resultTerm());
                    }
                }
            }
        }
    }

    record Scenario(PlutusData data, boolean success) {}

    static void assertEquivalent(EvalResult before, EvalResult after, String label) {
        assertEquals(before.getClass(), after.getClass(), label);
        assertEquals(before.traces(), after.traces(), label);
        if (before instanceof EvalResult.Success b && after instanceof EvalResult.Success a) assertEquals(b.resultTerm(), a.resultTerm(), label);
        if (before instanceof EvalResult.Failure b && after instanceof EvalResult.Failure a) assertEquals(b.error(), a.error(), label);
    }

    private static EvalResult evaluate(Program program, PlutusData redeemer, String provider) {
        var vm = CompilerTestVm.pv11(provider);
        var context = PlutusData.constr(0, PlutusData.integer(0), redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        return provider.equals("Scalus") ? vm.evaluateWithArgs(program, List.of(context))
                : vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), List.of(context), null, EvalOptions.DEFAULT);
    }

    static Program golden(int fixture, OptimizationLevel level, boolean maps) throws IOException {
        String id = fixture + "-" + level + "-" + maps;
        try (var input = SwitchPairCaseLoweringTest.class.getResourceAsStream("/optimization/o4-switch-pre-change-bytes.txt")) {
            assertNotNull(input);
            String hex = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(id + " ")).findFirst().orElseThrow().substring(id.length() + 1);
            return UplcFlatDecoder.decodeProgram(HexFormat.of().parseHex(hex));
        }
    }
}
