package org.julclang.compiler.size;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.codegen.SharedBindings;
import org.julclang.compiler.uplc.UplcOptimizer;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.testkit.JvmCryptoProvider;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #325: the counter state machine of the issue, JuLC's {@code PlainCounter} against the hand-written Aiken
 * {@code counter.ak} (Aiken v1.1.23, stdlib v3.0.0, no traces) with the same checks.
 * <p>
 * It is a differential test and a benchmark. Every scenario runs on JuLC with both size rules of this branch on
 * ({@link SharedBindings#RULE}, o16, and {@link UplcOptimizer#SHARED_CLOSED_VALUES_RULE}, o17), with each off alone,
 * with both off, and on Aiken's script. Each script gets its own context: its own hash is the policy id and the script
 * credential, as it would be on chain.
 * <p>
 * <b>One genuine difference.</b> The two validators name the instance token differently: JuLC's
 * {@code ValuesLib.uniqueTokenName(seed)} is {@code blake2b_256(txId ++ integerToByteString(BE, 2, index))}, Aiken's is
 * {@code blake2b_256(serialiseData(seed))}. So each Create context names the token the way its script derives it, and
 * {@link #tokenNameDerivationIsTheOneDifference()} pins that a Create naming the token the other way is rejected by
 * the one and accepted by the other.
 * <p>
 * <b>o16 and the budget.</b> o16 keeps the budget of every run that reaches a handler, which every scenario here does:
 * {@link #sharedHandlerBindingsKeepEveryHandledRunsBudget()} holds it to that exactly. A run whose purpose has no
 * handler pays for the shared bindings before it fails at the dispatch ({@code SharedBindingsTest}).
 * <p>
 * One more difference no scenario reaches: Aiken's {@code spend(datum: Option<State>, ...)} checks the spent datum's
 * shape, JuLC's {@code (State) (Object) getInlineDatum(...)} does not. Every context here carries a well-formed datum.
 */
class CounterBenchmarkTest {

    /** The size target of this branch for {@code PlainCounter} at PV11_SAFE with both rules on (measured: 2,749). */
    private static final int MAX_JULC_FLAT_BYTES = 2_750;

    /** The PV11 cost model charges every CEK machine step the same: 16000 CPU and 100 memory. */
    private static final long STEP_CPU = 16_000;
    private static final long STEP_MEM = 100;

    /** What binding one shared value adds, at most: an application, a lambda, and the value (two forces and a builtin). */
    private static final int STEPS_PER_SHARED_VALUE = 5;

    private static final JvmCryptoProvider CRYPTO = new JvmCryptoProvider();

    private static final byte[] OWNER = filled(28, 0x11);
    private static final byte[] STRANGER = filled(28, 0x22);
    // An index above 255 makes the two-byte index of JuLC's token name matter.
    private static final TxOutRef SEED = new TxOutRef(new TxId(filled(32, 0xA1)), BigInteger.valueOf(259));
    private static final TxOutRef OTHER_SEED = new TxOutRef(new TxId(filled(32, 0xA2)), BigInteger.ZERO);
    private static final TxOutRef COUNTER_REF = new TxOutRef(new TxId(filled(32, 0xB2)), BigInteger.ONE);
    private static final TxOutRef OTHER_COUNTER_REF = new TxOutRef(new TxId(filled(32, 0xB3)), BigInteger.ZERO);
    private static final TxOutRef FEE_REF = new TxOutRef(new TxId(filled(32, 0xC3)), BigInteger.ZERO);
    private static final BigInteger COUNTER_LOVELACE = BigInteger.valueOf(5_000_000);
    private static final BigInteger WALLET_LOVELACE = BigInteger.valueOf(20_000_000);
    private static final BigInteger CHANGE_LOVELACE = BigInteger.valueOf(14_800_000);

    /** How a validator derives the instance token's name from the seed. */
    enum Naming {
        /** {@code ValuesLib.uniqueTokenName}: {@code blake2b_256(txId ++ integerToByteString(true, 2, index))}. */
        JULC,
        /** {@code blake2b_256(cbor.serialise(seed))}. */
        AIKEN;

        byte[] tokenName(TxOutRef seed) {
            return switch (this) {
                case JULC -> CRYPTO.blake2b_256(concat(seed.txId().hash(), twoByteIndex(seed.index())));
                case AIKEN -> CRYPTO.blake2b_256(PlutusDataCborEncoder.encode(seed.toPlutusData()));
            };
        }
    }

    record Script(String label, Program program, byte[] hash, Naming naming) {
        PolicyId policy() {
            return new PolicyId(hash);
        }

        Address address() {
            return new Address(new Credential.ScriptCredential(new ScriptHash(hash)), Optional.empty());
        }

        int flatSize() {
            return UplcFlatEncoder.encodeProgram(program).length;
        }
    }

    record Scenario(String name, boolean accepts, Function<Script, PlutusData> context) {}

    record Run(boolean accepted, ExBudget budget, List<String> traces, String error) {
        static Run of(EvalResult result) {
            return switch (result) {
                case EvalResult.Success s -> new Run(true, s.consumed(), s.traces(), null);
                case EvalResult.Failure f -> new Run(false, f.consumed(), f.traces(), f.error());
                case EvalResult.BudgetExhausted b -> new Run(false, b.consumed(), b.traces(), "budget exhausted");
            };
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("mint Create", true, s -> create(s, s.naming(), true, 1)),
            new Scenario("mint Burn", true, CounterBenchmarkTest::burn),
            new Scenario("spend Increment by 5", true, s -> increment(s, 5, 8)),
            new Scenario("spend Reset", true, s -> reset(s, OWNER)),
            new Scenario("spend Close", true, s -> close(s, true, COUNTER_LOVELACE)),
            new Scenario("mint Create unsigned", false, s -> create(s, s.naming(), false, 1)),
            new Scenario("mint Create minting 2", false, s -> create(s, s.naming(), true, 2)),
            new Scenario("spend Increment by 0", false, s -> increment(s, 0, 3)),
            new Scenario("spend Increment wrong datum", false, s -> increment(s, 5, 9)),
            new Scenario("spend Reset by non-owner", false, s -> reset(s, STRANGER)),
            new Scenario("spend Close without burn", false, s -> close(s, false, COUNTER_LOVELACE)),
            new Scenario("spend Close pays too little", false,
                    s -> close(s, true, COUNTER_LOVELACE.subtract(BigInteger.ONE))));

    private static JulcVm vm;
    private static Script julcOn;
    private static Script julcO16Off;
    private static Script julcO17Off;
    private static Script julcOff;
    private static Script aiken;
    private static List<Script> julcVariants;
    private static int aikenCborSize;
    /** Each scenario's run on each script, by scenario name, then script label. */
    private static final Map<String, Map<String, Run>> RUNS = new LinkedHashMap<>();

    @BeforeAll
    static void compileAndRunEverything() throws IOException {
        vm = CompilerTestVm.pv11();
        String source = resource("/size/PlainCounter.java.txt");
        julcOn = julc("JuLC on", source, new CompilerOptions());
        julcO16Off = julc("JuLC o16 off", source, new CompilerOptions().disableOptimizationRule(SharedBindings.RULE));
        julcO17Off = julc("JuLC o17 off", source,
                new CompilerOptions().disableOptimizationRule(UplcOptimizer.SHARED_CLOSED_VALUES_RULE));
        julcOff = julc("JuLC off", source, new CompilerOptions()
                .disableOptimizationRule(SharedBindings.RULE)
                .disableOptimizationRule(UplcOptimizer.SHARED_CLOSED_VALUES_RULE));
        julcVariants = List.of(julcOn, julcO16Off, julcO17Off, julcOff);
        aiken = aiken();

        for (var scenario : SCENARIOS) {
            var runs = new LinkedHashMap<String, Run>();
            for (var script : allScripts()) runs.put(script.label(), run(script, scenario.context().apply(script)));
            RUNS.put(scenario.name(), runs);
        }
    }

    @Test
    void everyScenarioIsAcceptedOrRejectedAsExpectedAndAikenAgrees() {
        var table = new StringBuilder(String.format("%n%-30s %-9s", "scenario", "expected"));
        for (var script : allScripts()) table.append(String.format(" %-13s", script.label()));
        for (var scenario : SCENARIOS) {
            table.append(String.format("%n%-30s %-9s", scenario.name(), verdict(scenario.accepts())));
            for (var script : allScripts()) table.append(String.format(" %-13s", verdict(run(scenario, script).accepted())));
        }
        System.out.println(table);

        for (var scenario : SCENARIOS) {
            for (var script : allScripts()) {
                var run = run(scenario, script);
                assertEquals(scenario.accepts(), run.accepted(),
                        scenario.name() + " on " + script.label() + (run.error() == null ? "" : ": " + run.error()));
            }
        }
    }

    @Test
    void theSharingRulesKeepOutcomesAndTraces() {
        for (var scenario : SCENARIOS) {
            var reference = run(scenario, julcOff);
            for (var variant : julcVariants) {
                var run = run(scenario, variant);
                assertEquals(reference.accepted(), run.accepted(), scenario.name() + " on " + variant.label());
                assertEquals(reference.traces(), run.traces(), scenario.name() + " on " + variant.label());
            }
        }
    }

    /**
     * o16 binds the methods both handlers need once, around the dispatch, and keeps what each handler runs: every
     * scenario reaches a handler, accepting or rejecting, and costs exactly what it does without o16, whether o17 is on
     * or off.
     */
    @Test
    void sharedHandlerBindingsKeepEveryHandledRunsBudget() {
        assertFalse(Arrays.equals(UplcFlatEncoder.encodeProgram(julcOn.program()),
                UplcFlatEncoder.encodeProgram(julcO16Off.program())), "o16 changes nothing in PlainCounter");
        for (var scenario : SCENARIOS) {
            assertEquals(run(scenario, julcO16Off).budget(), run(scenario, julcOn).budget(), scenario.name() + " (o17 on)");
            assertEquals(run(scenario, julcOff).budget(), run(scenario, julcO17Off).budget(), scenario.name() + " (o17 off)");
        }
    }

    /**
     * o17 binds repeated forced builtins and Z combinators once at the root. Each shared value costs a few machine steps
     * once; each forced-builtin use that runs saves one. The difference is machine steps only, never a builtin's cost.
     */
    @Test
    void sharedClosedValuesChangeTheBudgetByAFewMachineStepsOnly() {
        int shared = sharedClosedValues(julcOn.program());
        assertTrue(shared > 0, "o17 shares nothing in PlainCounter");
        int sharedWithoutO16 = sharedClosedValues(julcO16Off.program());
        assertEquals(0, sharedClosedValues(julcO17Off.program()));
        assertEquals(0, sharedClosedValues(julcOff.program()));
        long maxOverhead = (long) shared * STEPS_PER_SHARED_VALUE;
        long maxOverheadWithoutO16 = (long) sharedWithoutO16 * STEPS_PER_SHARED_VALUE;

        var table = new StringBuilder(String.format(
                "%no17 budget change (on minus off): %d shared values (%d with o16 off), at most %d (%d) steps added%n",
                shared, sharedWithoutO16, maxOverhead, maxOverheadWithoutO16));
        table.append(String.format("%-30s %12s %10s %8s %12s %10s %8s%n",
                "scenario", "dCPU", "dMem", "dSteps", "dCPU(o16off)", "dMem", "dSteps"));
        for (var scenario : SCENARIOS) {
            long withO16 = stepDelta(scenario, julcOn, julcO17Off);
            long withoutO16 = stepDelta(scenario, julcO16Off, julcOff);
            assertTrue(withO16 <= maxOverhead, scenario.name() + ": o17 adds " + withO16 + " steps");
            assertTrue(withoutO16 <= maxOverheadWithoutO16, scenario.name() + ": o17 adds " + withoutO16 + " steps (o16 off)");
            table.append(String.format("%-30s %12d %10d %8d %12d %10d %8d%n", scenario.name(),
                    withO16 * STEP_CPU, withO16 * STEP_MEM, withO16,
                    withoutO16 * STEP_CPU, withoutO16 * STEP_MEM, withoutO16));
        }
        System.out.println(table);
    }

    @Test
    void sizeBenchmark() {
        int on = julcOn.flatSize();
        int off = julcOff.flatSize();
        int aikenFlat = aiken.flatSize();
        System.out.printf("%nPlainCounter size (flat bytes, PV11_SAFE)%n");
        System.out.printf("  JuLC, o16 and o17 on : %5d  (%.2fx Aiken)%n", on, ratio(on, aikenFlat));
        System.out.printf("  JuLC, o16 off        : %5d%n", julcO16Off.flatSize());
        System.out.printf("  JuLC, o17 off        : %5d%n", julcO17Off.flatSize());
        System.out.printf("  JuLC, both off       : %5d  (%.2fx Aiken)%n", off, ratio(off, aikenFlat));
        System.out.printf("  Aiken v1.1.23        : %5d  (compiledCode %d bytes CBOR-wrapped)%n", aikenFlat, aikenCborSize);
        System.out.printf("  rules save %d bytes (%.1f%%)%n", off - on, 100.0 * (off - on) / off);

        assertTrue(on < off, "the rules save nothing");
        assertTrue(on <= MAX_JULC_FLAT_BYTES, "PlainCounter is " + on + " flat bytes, the target is " + MAX_JULC_FLAT_BYTES);
    }

    @Test
    void budgetTableForAcceptingScenarios() {
        var table = new StringBuilder(String.format("%nCPU / memory per accepting scenario%n%-24s", "scenario"));
        var scripts = List.of(julcOn, julcOff, aiken);
        for (var script : scripts) table.append(String.format(" %24s", script.label() + " cpu/mem"));
        table.append(String.format(" %14s", "on/Aiken cpu"));
        for (var scenario : SCENARIOS) {
            if (!scenario.accepts()) continue;
            table.append(String.format("%n%-24s", scenario.name()));
            for (var script : scripts) {
                var budget = run(scenario, script).budget();
                table.append(String.format(" %24s", budget.cpuSteps() + " / " + budget.memoryUnits()));
            }
            table.append(String.format(" %13.2fx", ratio(run(scenario, julcOn).budget().cpuSteps(),
                    run(scenario, aiken).budget().cpuSteps())));
        }
        System.out.println(table);
        for (var scenario : SCENARIOS) {
            if (scenario.accepts()) assertTrue(run(scenario, julcOn).budget().cpuSteps() > 0, scenario.name());
        }
    }

    /**
     * The one check the two validators make differently: the token name. A Create that names the token the way the
     * other compiler derives it is rejected by this one's validator and accepted by the other's.
     */
    @Test
    void tokenNameDerivationIsTheOneDifference() {
        assertFalse(Arrays.equals(Naming.JULC.tokenName(SEED), Naming.AIKEN.tokenName(SEED)));
        for (var variant : julcVariants) {
            assertFalse(run(variant, create(variant, Naming.AIKEN, true, 1)).accepted(), variant.label());
        }
        assertFalse(run(aiken, create(aiken, Naming.JULC, true, 1)).accepted(), aiken.label());
        System.out.printf("%nToken name of the seed: JuLC %s, Aiken %s%n",
                HexFormat.of().formatHex(Naming.JULC.tokenName(SEED)), HexFormat.of().formatHex(Naming.AIKEN.tokenName(SEED)));
    }

    // --- Scenario contexts ---

    /** Create: the seed is spent, {@code quantity} of the token is minted, and one output at the script holds one. */
    private static PlutusData create(Script s, Naming naming, boolean signed, long quantity) {
        byte[] name = naming.tokenName(SEED);
        var builder = ScriptContextTestBuilder.minting(s.policy())
                .redeemer(PlutusData.constr(0, SEED.toPlutusData(), PlutusData.bytes(OWNER)))
                .input(walletInput(SEED))
                .mint(Value.singleton(s.policy(), new TokenName(name), BigInteger.valueOf(quantity)))
                .output(counterOutput(s, name, state(0)))
                // A second minted token is left out of the outputs: only the minting check should fail.
                .output(walletOutput(OWNER, CHANGE_LOVELACE))
                .fee(BigInteger.valueOf(200_000));
        if (signed) builder.signer(OWNER);
        return builder.buildPlutusData();
    }

    /** Burn: two counters are closed together, so both of their tokens are burned. */
    private static PlutusData burn(Script s) {
        byte[] first = s.naming().tokenName(SEED);
        byte[] second = s.naming().tokenName(OTHER_SEED);
        var burned = Value.singleton(s.policy(), new TokenName(first), BigInteger.ONE.negate())
                .merge(Value.singleton(s.policy(), new TokenName(second), BigInteger.ONE.negate()));
        return ScriptContextTestBuilder.minting(s.policy())
                .redeemer(PlutusData.constr(1))
                .input(new TxInInfo(COUNTER_REF, counterOutput(s, first, state(3))))
                .input(new TxInInfo(OTHER_COUNTER_REF, counterOutput(s, second, state(7))))
                .mint(burned)
                .output(walletOutput(OWNER, COUNTER_LOVELACE.add(COUNTER_LOVELACE)))
                .signer(OWNER)
                .fee(BigInteger.valueOf(200_000))
                .buildPlutusData();
    }

    /** Increment by {@code by} from State{3, owner}; the continuing output's datum has {@code nextCount}. */
    private static PlutusData increment(Script s, long by, long nextCount) {
        byte[] name = s.naming().tokenName(SEED);
        return spending(s, PlutusData.constr(0, PlutusData.integer(by)))
                .output(counterOutput(s, name, state(nextCount)))
                .output(walletOutput(OWNER, CHANGE_LOVELACE))
                .buildPlutusData();
    }

    /** Reset by {@code caller}, who signs; the continuing output's datum is State{0, owner}. */
    private static PlutusData reset(Script s, byte[] caller) {
        byte[] name = s.naming().tokenName(SEED);
        return spending(s, PlutusData.constr(1, PlutusData.bytes(caller)))
                .output(counterOutput(s, name, state(0)))
                .output(walletOutput(OWNER, CHANGE_LOVELACE))
                .signer(caller)
                .buildPlutusData();
    }

    /** Close by the owner, who signs; the token is burned if {@code burns}, and the owner is paid {@code payout}. */
    private static PlutusData close(Script s, boolean burns, BigInteger payout) {
        byte[] name = s.naming().tokenName(SEED);
        var builder = spending(s, PlutusData.constr(2, PlutusData.bytes(OWNER)))
                // The payout is the owner's only output, so that it alone decides whether the owner is paid enough.
                .output(walletOutput(OWNER, payout))
                .signer(OWNER);
        if (burns) builder.mint(Value.singleton(s.policy(), new TokenName(name), BigInteger.ONE.negate()));
        return builder.buildPlutusData();
    }

    /** Spending the counter UTxO holding the token and State{3, owner}, with a fee input from the owner's wallet. */
    private static ScriptContextTestBuilder spending(Script s, PlutusData redeemer) {
        byte[] name = s.naming().tokenName(SEED);
        var counter = counterOutput(s, name, state(3));
        var datum = ((OutputDatum.OutputDatumInline) counter.datum()).datum();
        return ScriptContextTestBuilder.spending(COUNTER_REF, datum)
                .redeemer(redeemer)
                .input(walletInput(FEE_REF))
                .input(new TxInInfo(COUNTER_REF, counter))
                .fee(BigInteger.valueOf(200_000));
    }

    private static TxOut counterOutput(Script s, byte[] name, PlutusData datum) {
        var value = Value.lovelace(COUNTER_LOVELACE).merge(Value.singleton(s.policy(), new TokenName(name), BigInteger.ONE));
        return new TxOut(s.address(), value, new OutputDatum.OutputDatumInline(datum), Optional.empty());
    }

    private static TxInInfo walletInput(TxOutRef ref) {
        return new TxInInfo(ref, walletOutput(OWNER, WALLET_LOVELACE));
    }

    private static TxOut walletOutput(byte[] key, BigInteger lovelace) {
        var address = new Address(new Credential.PubKeyCredential(new PubKeyHash(key)), Optional.empty());
        return new TxOut(address, Value.lovelace(lovelace), new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    /** State{count, owner} = Constr 0 [I count, B owner]. */
    private static PlutusData state(long count) {
        return PlutusData.constr(0, PlutusData.integer(count), PlutusData.bytes(OWNER));
    }

    // --- Scripts ---

    private static Script julc(String label, String source, CompilerOptions options) {
        CompileResult result = new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compile(source);
        assertFalse(result.hasErrors(), label + ": " + result.diagnostics());
        var program = result.program();
        byte[] hash = HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(program));
        return new Script(label, program, hash, Naming.JULC);
    }

    /** Aiken's blueprint script: {@code compiledCode} is the FLAT program wrapped once in a CBOR bytestring. */
    private static Script aiken() throws IOException {
        String blueprint = resource("/size/aiken-counter-plutus.json");
        byte[] compiled = HexFormat.of().parseHex(firstJsonString(blueprint, "compiledCode"));
        aikenCborSize = compiled.length;
        // A Plutus V3 script hash is blake2b_224 of the language tag 0x03 and the serialised script.
        byte[] hash = CRYPTO.blake2b_224(concat(new byte[] {0x03}, compiled));
        assertArrayEquals(HexFormat.of().parseHex(firstJsonString(blueprint, "hash")), hash, "Aiken's script hash");
        var program = UplcFlatDecoder.decodeProgram(unwrapCborBytes(compiled));
        return new Script("Aiken", program, hash, Naming.AIKEN);
    }

    private static List<Script> allScripts() {
        var scripts = new ArrayList<>(julcVariants);
        scripts.add(aiken);
        return scripts;
    }

    /** The binders o17 puts around the program: {@code [(\#name. body) value]}, outermost first. */
    private static int sharedClosedValues(Program program) {
        int count = 0;
        Term term = program.term();
        while (term instanceof Term.Apply(Term.Lam(String name, Term body), Term value)
                && isSharedClosedValue(name, value)) {
            count++;
            term = body;
        }
        return count;
    }

    /** o17 names a shared Z combinator {@code #z} and a shared forced builtin {@code #} and the builtin's name. */
    private static boolean isSharedClosedValue(String name, Term value) {
        if (name.equals("#z")) return value instanceof Term.Lam;
        if (!name.startsWith("#")) return false;
        Term inner = value;
        while (inner instanceof Term.Force(Term forced)) inner = forced;
        return inner != value && inner instanceof Term.Builtin;
    }

    // --- Evaluation ---

    private static Run run(Script script, PlutusData context) {
        return Run.of(vm.evaluateWithArgs(script.program(), List.of(context)));
    }

    private static Run run(Scenario scenario, Script script) {
        var run = RUNS.get(scenario.name()).get(script.label());
        assertNotNull(run, scenario.name() + " on " + script.label());
        return run;
    }

    /** {@code with} minus {@code without}, in machine steps; fails unless the difference is whole machine steps. */
    private static long stepDelta(Scenario scenario, Script with, Script without) {
        var on = run(scenario, with).budget();
        var off = run(scenario, without).budget();
        long cpu = on.cpuSteps() - off.cpuSteps();
        long mem = on.memoryUnits() - off.memoryUnits();
        assertEquals(0, cpu % STEP_CPU, scenario.name() + ": CPU difference " + cpu + " is not whole machine steps");
        assertEquals(cpu / STEP_CPU * STEP_MEM, mem, scenario.name() + ": memory difference " + mem + " is not machine steps");
        return cpu / STEP_CPU;
    }

    // --- Helpers ---

    private static String verdict(boolean accepted) {
        return accepted ? "accept" : "reject";
    }

    private static double ratio(long a, long b) {
        return (double) a / b;
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /** {@code integerToByteString(true, 2, index)}: the index big-endian in exactly two bytes. */
    private static byte[] twoByteIndex(BigInteger index) {
        int i = index.intValueExact();
        return new byte[] {(byte) (i >>> 8), (byte) i};
    }

    /** The payload of a definite-length CBOR bytestring, which must be all of {@code cbor}. */
    private static byte[] unwrapCborBytes(byte[] cbor) {
        int header = cbor[0] & 0xff;
        int lengthBytes = switch (header) {
            case 0x58 -> 1;
            case 0x59 -> 2;
            case 0x5a -> 4;
            default -> {
                if (header < 0x40 || header > 0x57) throw new IllegalArgumentException("not a CBOR bytestring: " + header);
                yield 0;
            }
        };
        long length = lengthBytes == 0 ? header - 0x40 : 0;
        for (int i = 1; i <= lengthBytes; i++) length = (length << 8) | (cbor[i] & 0xff);
        assertEquals(cbor.length, 1 + lengthBytes + length, "CBOR bytestring length");
        return Arrays.copyOfRange(cbor, 1 + lengthBytes, cbor.length);
    }

    private static String firstJsonString(String json, String key) {
        var matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(json);
        assertTrue(matcher.find(), "no " + key + " in the blueprint");
        return matcher.group(1);
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = CounterBenchmarkTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
