package org.julclang.compiler;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.OptimizationCostProfile;
import org.julclang.vm.OptimizationCostProfiles;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.ProtocolCapability;
import org.julclang.vm.UplcVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.julclang.compiler.O11BlsTypesFixtures.FIXTURES;
import static org.julclang.compiler.O11BlsTypesFixtures.IMPORTS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-047 (O11): the typed BLS12-381 surface. Every fixture in {@link O11BlsTypesFixtures}
 * is compiled at every level and evaluated on Java, Truffle and Scalus for every input: the
 * typed program agrees with the manual chain it replaces (the result is {@code true}), fails
 * exactly where the pinned builtin semantics say (scalar bounds, invalid encodings, the error
 * guard), keeps its trace order, and costs the same on every backend. The other tests pin the
 * compile-time isolation of the new types (the O7 codes {@code JULC0041}/{@code JULC0042}),
 * the shape of the native-list producers (constants or {@code MkCons} chains, never a Data
 * wrapper) and the lowering requirements.
 */
@Tag("pair-case-backends")
class O11BlsTypesTest {

    private static final OptimizationCostProfile PROFILE = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
    private static final List<String> PROVIDERS = List.of("Java", "Truffle", "Scalus");
    private static final String BUILTINS = "org.julclang.stdlib.Builtins";
    /** The Data encoders a native list producer must never insert. */
    private static final Set<DefaultFun> DATA_ENCODERS = Set.of(DefaultFun.IData, DefaultFun.BData, DefaultFun.ListData,
            DefaultFun.MapData, DefaultFun.ConstrData);

    /** The failure text (Java and Truffle) of every failing fixture input, by fixture/input. */
    private static final Map<String, String> FAILURE_PREFIX = Map.ofEntries(
            Map.entry("FROM_LISTS/one-scalar", "HeadList"),
            Map.entry("FROM_LISTS/not-an-integer", "UnIData"),
            Map.entry("POINTS_FROM_DATA/invalid-encoding", "Bls12_381_G1_uncompress"),
            Map.entry("POINTS_FROM_DATA/not-bytes", "UnBData"),
            Map.entry("POINTS_FROM_DATA/empty", "HeadList"),
            Map.entry("SCALAR_BOUND/max-plus-one", "Bls12_381_G1_multiScalarMul: multiScalarMul: scalar too large (513 bytes, max 512)"),
            Map.entry("SCALAR_BOUND/min-minus-one", "Bls12_381_G1_multiScalarMul: multiScalarMul: scalar too large (513 bytes, max 512)"),
            Map.entry("SCALAR_BEYOND_ZIP/beyond-bound", "Bls12_381_G1_multiScalarMul: multiScalarMul: scalar too large (513 bytes, max 512)"),
            Map.entry("TRACE_ORDER/fail", "Error term encountered"));
    /** ADR-043 (O9) promotes the repeatedly indexed boundary list at PV11_COSTED, so the chain's own get fails as IndexArray there. */
    private static final Map<String, String> COSTED_FAILURE_PREFIX = Map.of(
            "FROM_LISTS/one-scalar", "IndexArray: index 1 out of bounds for array of size 1",
            "POINTS_FROM_DATA/empty", "IndexArray: index 0 out of bounds for array of size 0");

    @Test
    void typedBlsProgramsAgreeWithTheirManualChainsOnEveryBackend() {
        for (var fixture : FIXTURES) {
            for (var level : OptimizationLevel.values()) {
                String label = fixture.name() + "/" + level;
                var result = compile(fixture.source(), fixture.method(), level, false);
                assertFalse(result.hasErrors(), label + " " + result.diagnostics());
                var bytes = UplcFlatEncoder.encodeProgram(result.program());
                assertArrayEquals(bytes, UplcFlatEncoder.encodeProgram(compile(fixture.source(), fixture.method(), level, false).program()),
                        label + " determinism");
                assertFalse(result.hasErrors(), label);
                compile(fixture.source(), fixture.method(), level, true); // source maps compile too
                for (var input : fixture.inputs()) {
                    EvalResult javaResult = null;
                    for (String provider : PROVIDERS) {
                        String inputLabel = label + "/" + provider + "/" + input;
                        var evaluated = evaluate(result.program(), input.args(), provider);
                        assertEquals(input.success(), evaluated.isSuccess(), inputLabel + " " + evaluated);
                        if (evaluated instanceof EvalResult.Success success) {
                            assertEquals(Term.const_(Constant.bool(true)), success.resultTerm(), inputLabel);
                        } else if (!provider.equals("Scalus")) {
                            var key = fixture.name() + "/" + input.name();
                            var prefix = level == OptimizationLevel.PV11_COSTED && COSTED_FAILURE_PREFIX.containsKey(key)
                                    ? COSTED_FAILURE_PREFIX.get(key) : FAILURE_PREFIX.get(key);
                            var error = ((EvalResult.Failure) evaluated).error();
                            assertNotNull(prefix, inputLabel + " has no pinned failure text: " + error);
                            assertTrue(error.startsWith(prefix), inputLabel + " " + error);
                        }
                        if (fixture.name().equals("TRACE_ORDER")) {
                            assertEquals(List.of("before", "after"), evaluated.traces(), inputLabel);
                        } else {
                            assertEquals(List.of(), evaluated.traces(), inputLabel);
                        }
                        if (provider.equals("Java")) javaResult = evaluated;
                        else assertEquals(javaResult.budgetConsumed(), evaluated.budgetConsumed(), inputLabel);
                        if (level == OptimizationLevel.PV11_SAFE && provider.equals("Java")) {
                            System.out.println("BLS_TYPED_COST " + fixture.name() + " " + input + " " + evaluated.budgetConsumed()
                                    + (evaluated instanceof EvalResult.Failure f ? " " + f.error() : ""));
                        }
                    }
                }
                if (level == OptimizationLevel.PV11_SAFE) {
                    System.out.println("BLS_TYPED_ARTIFACT " + fixture.name() + " " + bytes.length + " " + JulcScriptAdapter.scriptHash(result.program()));
                }
            }
        }
    }

    /** A wrong group, a byte string, Data or a Data list where a typed value is required, and a typed value where Data is required. */
    @Test
    void misuseIsRejectedAtCompileTimeWithTheNativeIsolationCodes() {
        record Bad(String name, String body, String code, String fragment) {}
        var cases = List.of(
                new Bad("bytes into add", "static JulcG1 m(byte[] a, byte[] dst) { return BlsLib.g1Add(a, Builtins.bls12_381_G1_hashToGroup(a, dst)); }",
                        "JULC0041", "requires G1"),
                new Bad("G2 into G1 add", "static JulcG1 m(byte[] dst) { JulcG2 q = Builtins.bls12_381_G2_hashToGroup(dst, dst); JulcG1 p = Builtins.bls12_381_G1_hashToGroup(dst, dst); return Builtins.bls12_381_G1_add(p, q); }",
                        "JULC0041", "requires G1"),
                new Bad("Miller result into G1 add", "static JulcG1 m(byte[] dst) { JulcG1 p = Builtins.bls12_381_G1_hashToGroup(dst, dst); JulcG2 q = Builtins.bls12_381_G2_hashToGroup(dst, dst); return BlsLib.g1Add(BlsLib.millerLoop(p, q), p); }",
                        "JULC0041", "requires G1"),
                new Bad("G1 held as byte[]", "static byte[] m(byte[] dst) { byte[] p = Builtins.bls12_381_G1_hashToGroup(dst, dst); return p; }",
                        "JULC0041", "G1"),
                new Bad("G1 compared with ==", "static boolean m(byte[] dst) { JulcG1 p = Builtins.bls12_381_G1_hashToGroup(dst, dst); return p == p; }",
                        "JULC0041", "G1"),
                new Bad("G1 in a Data list", "static boolean m(byte[] dst) { JulcList<JulcG1> ps = JulcList.of(Builtins.bls12_381_G1_hashToGroup(dst, dst)); return true; }",
                        "JULC0041", "G1"),
                new Bad("G1 at the boundary", "static boolean m(JulcG1 p) { return true; }", "JULC0042", "G1"),
                new Bad("scalars at the boundary", "static boolean m(JulcScalars s) { return true; }", "JULC0042", "NativeList[Integer]"),
                new Bad("Data list as scalars", "static JulcG1 m(JulcList<BigInteger> s, byte[] dst) { return Builtins.bls12_381_G1_multiScalarMul(s, Builtins.g1Points(Builtins.bls12_381_G1_hashToGroup(dst, dst))); }",
                        "JULC0041", "received List[Integer], but requires NativeList[Integer]"),
                new Bad("G2 in g1Points", "static JulcG1Points m(byte[] dst) { return Builtins.g1Points(Builtins.bls12_381_G2_hashToGroup(dst, dst)); }",
                        "JULC0041", "requires G1"),
                new Bad("points where scalars are required", "static JulcG1 m(byte[] dst) { JulcG1Points ps = Builtins.g1Points(Builtins.bls12_381_G1_hashToGroup(dst, dst)); return Builtins.bls12_381_G1_multiScalarMul(ps, ps); }",
                        "JULC0041", "requires NativeList[Integer]"),
                new Bad("G1 points where G2 points are required", "static JulcG2 m(byte[] dst) { JulcG1Points ps = Builtins.g1Points(Builtins.bls12_381_G1_hashToGroup(dst, dst)); return Builtins.bls12_381_G2_multiScalarMul(Builtins.scalars(BigInteger.ONE), ps); }",
                        "JULC0041", "requires NativeList[G2]"),
                new Bad("G1 where Data is required", "static boolean m(byte[] dst, PlutusData d) { return Builtins.equalsData(Builtins.bls12_381_G1_hashToGroup(dst, dst), d); }",
                        "JULC0041", "G1"),
                new Bad("G1 in a record", "record Box(JulcG1 p) {}\n static boolean m(byte[] dst) { var box = new Box(Builtins.bls12_381_G1_hashToGroup(dst, dst)); return true; }",
                        "JULC0041", "requires Data"));
        for (var bad : cases) {
            var error = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(IMPORTS + "class Bad {\n" + bad.body() + "\n}\n", "m"),
                    bad.name());
            var diagnostic = error.diagnostics().getFirst();
            assertEquals(bad.code(), diagnostic.code(), bad.name() + ": " + diagnostic.message());
            assertTrue(diagnostic.message().contains(bad.fragment()), bad.name() + ": " + diagnostic.message());
        }
        // The typed values pass between user methods and out of compileMethod (a native constant result, as for JulcValue).
        var typed = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(IMPORTS + """
                class Good {
                    static JulcG1 m(byte[] dst) { return Builtins.bls12_381_G1_hashToGroup(dst, dst); }
                }
                """, "m");
        assertFalse(typed.hasErrors());
        assertInstanceOf(Term.Const.class, assertInstanceOf(EvalResult.Success.class,
                evaluate(typed.program(), List.of(O11BlsTypesFixtures.DST), "Java")).resultTerm());
    }

    /** Literal producers are native list constants or {@code MkCons} chains over one; the converters decode element by element. No Data wrapper is ever inserted. */
    @Test
    void producersLowerToNativeListConstantsOrConsChainsWithoutDataWrappers() {
        var literal = pir("static JulcScalars m() { return Builtins.scalars(BigInteger.ONE, BigInteger.TWO); }");
        assertTrue(constants(literal).contains(new Constant.ListConst(DefaultUni.INTEGER, List.of(Constant.integer(1), Constant.integer(2)))),
                literal.toString());
        assertFalse(mentions(literal, DefaultFun.MkCons), "all-literal scalars are one constant: " + literal);
        var emptyPoints = pir("static JulcG1Points m() { return Builtins.g1Points(); }");
        assertTrue(constants(emptyPoints).contains(new Constant.ListConst(DefaultUni.BLS12_381_G1, List.of())), emptyPoints.toString());
        var runtime = pir("static JulcScalars m(BigInteger x) { return Builtins.scalars(x, BigInteger.ONE); }");
        assertTrue(mentions(runtime, DefaultFun.MkCons), runtime.toString());
        assertTrue(constants(runtime).contains(new Constant.ListConst(DefaultUni.INTEGER, List.of())), "the chain ends in the empty native list");
        var points = pir("static JulcG1Points m(byte[] dst) { return Builtins.g1Points(Builtins.bls12_381_G1_hashToGroup(dst, dst)); }");
        assertTrue(mentions(points, DefaultFun.MkCons) && constants(points).contains(new Constant.ListConst(DefaultUni.BLS12_381_G1, List.of())), points.toString());
        var scalarsFromList = pir("static JulcScalars m(JulcList<BigInteger> xs) { return Builtins.scalarsFromList(xs); }");
        assertTrue(mentions(scalarsFromList, DefaultFun.UnIData) && mentions(scalarsFromList, DefaultFun.MkCons)
                && mentions(scalarsFromList, DefaultFun.NullList), scalarsFromList.toString());
        var g1FromCompressed = pir("static JulcG1Points m(JulcList<byte[]> xs) { return Builtins.g1PointsFromCompressed(xs); }");
        assertTrue(mentions(g1FromCompressed, DefaultFun.UnBData) && mentions(g1FromCompressed, DefaultFun.Bls12_381_G1_uncompress), g1FromCompressed.toString());
        var g2FromCompressed = pir("static JulcG2Points m(JulcList<byte[]> xs) { return Builtins.g2PointsFromCompressed(xs); }");
        assertTrue(mentions(g2FromCompressed, DefaultFun.UnBData) && mentions(g2FromCompressed, DefaultFun.Bls12_381_G2_uncompress), g2FromCompressed.toString());
        for (var term : List.of(literal, emptyPoints, runtime, points, scalarsFromList, g1FromCompressed, g2FromCompressed)) {
            for (var encoder : DATA_ENCODERS) assertFalse(mentions(term, encoder), encoder + " in " + term);
        }
        // Inference reads the native list type off the producer, so the typed builtin accepts it directly.
        var msm = pir("static JulcG1 m(byte[] dst) { return Builtins.bls12_381_G1_multiScalarMul(Builtins.scalars(BigInteger.ONE), Builtins.g1Points(Builtins.bls12_381_G1_hashToGroup(dst, dst))); }");
        assertTrue(mentions(msm, DefaultFun.Bls12_381_G1_multiScalarMul));
    }

    /** MSM needs the PV11 builtins; point lists need BLS constants; scalars need nothing beyond the base builtins. A pre-PV11 target fails closed. */
    @Test
    void requirementsNameThePv11BuiltinsAndBlsConstantsAndAPrePv11TargetFailsClosed() {
        var registry = StdlibRegistry.defaultRegistry();
        assertEquals(LoweringRequirements.NONE, registry.requirements(BUILTINS, "scalars"));
        assertEquals(LoweringRequirements.NONE, registry.requirements(BUILTINS, "scalarsFromList"));
        assertEquals(LoweringRequirements.capability(ProtocolCapability.BLS_CONSTANTS), registry.requirements(BUILTINS, "g1Points"));
        assertEquals(LoweringRequirements.capability(ProtocolCapability.BLS_CONSTANTS), registry.requirements(BUILTINS, "g2Points"));
        assertEquals(new LoweringRequirements(Set.of(DefaultFun.Bls12_381_G1_uncompress), Set.of(ProtocolCapability.BLS_CONSTANTS)),
                registry.requirements(BUILTINS, "g1PointsFromCompressed"));
        assertEquals(new LoweringRequirements(Set.of(DefaultFun.Bls12_381_G2_uncompress), Set.of(ProtocolCapability.BLS_CONSTANTS)),
                registry.requirements(BUILTINS, "g2PointsFromCompressed"));
        assertEquals(LoweringRequirements.builtin(DefaultFun.Bls12_381_G1_multiScalarMul), registry.requirements(BUILTINS, "bls12_381_G1_multiScalarMul"));

        var pv10 = new CompilerTarget(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), UplcVersion.V1_1_0);
        var msm = FIXTURES.getFirst();
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setTarget(pv10)).compileMethod(msm.source(), msm.method()));
        assertEquals("JULC0031", error.diagnostics().getFirst().code());
    }

    // --- helpers ---

    private static PirTerm pir(String body) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(IMPORTS + "class P {\n" + body + "\n}\n", "m");
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        return result.pirTerm();
    }

    private static boolean mentions(PirTerm term, DefaultFun fun) {
        return nodes(term).stream().anyMatch(t -> t instanceof PirTerm.Builtin b && b.fun() == fun);
    }

    private static List<Constant> constants(PirTerm term) {
        return nodes(term).stream().filter(t -> t instanceof PirTerm.Const).map(t -> ((PirTerm.Const) t).value()).toList();
    }

    /** The user's own nodes: library bindings (names with a dot) are skipped, as every method of an imported library is bound. */
    private static List<PirTerm> nodes(PirTerm term) {
        var out = new ArrayList<PirTerm>();
        collect(term, out);
        return out;
    }

    private static void collect(PirTerm term, List<PirTerm> out) {
        out.add(term);
        switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> { }
            case PirTerm.Lam l -> collect(l.body(), out);
            case PirTerm.Let l -> { if (!l.name().contains(".")) collect(l.value(), out); collect(l.body(), out); }
            case PirTerm.LetRec r -> { r.bindings().forEach(b -> { if (!b.name().contains(".")) collect(b.value(), out); }); collect(r.body(), out); }
            case PirTerm.App a -> { collect(a.function(), out); collect(a.argument(), out); }
            case PirTerm.IfThenElse i -> { collect(i.cond(), out); collect(i.thenBranch(), out); collect(i.elseBranch(), out); }
            case PirTerm.Trace t -> { collect(t.message(), out); collect(t.body(), out); }
            case PirTerm.DataConstr c -> c.fields().forEach(f -> collect(f, out));
            case PirTerm.DataMatch m -> { collect(m.scrutinee(), out); m.branches().forEach(b -> collect(b.body(), out)); }
            case PirTerm.ListMatch m -> { collect(m.scrutinee(), out); collect(m.nilBranch(), out); collect(m.consBranch(), out); }
            case PirTerm.PairMatch m -> { collect(m.scrutinee(), out); collect(m.body(), out); }
            case PirTerm.IntegerCase c -> { collect(c.scrutinee(), out); c.branches().forEach(b -> collect(b, out)); }
        }
    }

    static CompileResult compile(String source, String method, OptimizationLevel level, boolean maps) {
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(maps).setOptimizationCostProfile(PROFILE);
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(source, method);
    }

    private static EvalResult evaluate(Program program, List<PlutusData> args, String provider) {
        var vm = CompilerTestVm.pv11(provider);
        if (provider.equals("Scalus")) {
            return args.isEmpty() ? vm.evaluate(program) : vm.evaluateWithArgs(program, args);
        }
        return args.isEmpty()
                ? vm.evaluate(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT)
                : vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), args, null, EvalOptions.DEFAULT);
    }
}
