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

    private static final OptimizationCostProfile PROFILE = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
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
            Map.entry("TRACE_ORDER/fail", "Error term encountered"),
            Map.entry("CONS_RECURSION/invalid-encoding", "Bls12_381_G1_uncompress"),
            Map.entry("CONS_RECURSION/not-bytes", "UnBData"),
            Map.entry("CONS_RECURSION/not-an-integer", "UnIData"),
            Map.entry("VALIDATE_ONCE/infinity", "Error term encountered"),
            Map.entry("VALIDATE_ONCE/invalid-encoding", "Bls12_381_G1_uncompress"));
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
                            assertEquals(Term.const_(Constant.bool(input.value())), success.resultTerm(), inputLabel);
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

    /**
     * A wrong group, a byte string, Data or a Data list where a typed value is required, a typed
     * value where Data is required, an ill-typed native list element, and the same-class helper
     * parameter and return routes; the branches of a conditional; loop-local declarations and
     * loop assignments (PR #150 review); the validator boundary; a lambda's own return is exempt.
     */
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
                        "JULC0041", "requires Data"),
                // G2 and Miller-result shapes
                new Bad("bytes into g2Add", "static JulcG2 m(byte[] a, byte[] dst) { return BlsLib.g2Add(a, Builtins.bls12_381_G2_hashToGroup(a, dst)); }",
                        "JULC0041", "requires G2"),
                new Bad("G1 into G2 add", "static JulcG2 m(byte[] dst) { JulcG1 p = Builtins.bls12_381_G1_hashToGroup(dst, dst); JulcG2 q = Builtins.bls12_381_G2_hashToGroup(dst, dst); return Builtins.bls12_381_G2_add(q, p); }",
                        "JULC0041", "requires G2"),
                new Bad("G2 held as byte[]", "static byte[] m(byte[] dst) { byte[] q = Builtins.bls12_381_G2_hashToGroup(dst, dst); return q; }",
                        "JULC0041", "G2"),
                new Bad("Miller result at the boundary", "static boolean m(JulcMlResult r) { return true; }", "JULC0042", "MlResult"),
                new Bad("G2 points at the boundary", "static boolean m(JulcG2Points ps) { return true; }", "JULC0042", "NativeList[G2]"),
                // native list producers: element types
                new Bad("bytes literal in scalars", "static JulcScalars m() { return Builtins.scalars(new byte[]{1}); }", "JULC0041", "requires Integer"),
                new Bad("bytes variable in scalars", "static JulcScalars m(byte[] dst) { return Builtins.scalars(BigInteger.ONE, dst); }", "JULC0041", "requires Integer"),
                new Bad("Data in scalars", "static JulcScalars m(PlutusData d) { return Builtins.scalars(d); }", "JULC0041", "requires Integer"),
                new Bad("byte list into scalarsFromList", "static JulcScalars m(JulcList<byte[]> xs) { return Builtins.scalarsFromList(xs); }",
                        "JULC0041", "received List[ByteString], but requires List[Integer]"),
                new Bad("integer list into g1PointsFromCompressed", "static JulcG1Points m(JulcList<BigInteger> xs) { return Builtins.g1PointsFromCompressed(xs); }",
                        "JULC0041", "received List[Integer], but requires List[ByteString]"),
                // same-class helpers: parameters and returns
                new Bad("G1 into a byte[] helper parameter", "static boolean h(byte[] b) { return true; }\n static boolean m(byte[] dst) { return h(Builtins.bls12_381_G1_hashToGroup(dst, dst)); }",
                        "JULC0041", "h argument 1"),
                new Bad("Data list into a native helper parameter", "static JulcG1 f(JulcScalars s, JulcG1Points ps) { return Builtins.bls12_381_G1_multiScalarMul(s, ps); }\n static JulcG1 m(JulcList<BigInteger> xs, byte[] dst) { return f(xs, Builtins.g1Points(Builtins.bls12_381_G1_hashToGroup(dst, dst))); }",
                        "JULC0041", "received List[Integer], but requires NativeList[Integer]"),
                new Bad("G1 returned as byte[]", "static byte[] m(byte[] dst) { return Builtins.bls12_381_G1_hashToGroup(dst, dst); }",
                        "JULC0041", "Return value"),
                new Bad("byte[] returned as G1 from a helper", "static JulcG1 h(byte[] b) { return b; }\n static boolean m(byte[] dst) { return BlsLib.g1Equal(h(dst), h(dst)); }",
                        "JULC0041", "requires G1"),
                // conditional branches (PR #150 review): both orders, native/Data, native/bytes, scalar list/point list, native list/Data list
                new Bad("G2 in the else branch of a G1 conditional", "static byte[] m(boolean b, byte[] dst) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); var q = Builtins.bls12_381_G2_hashToGroup(dst, dst); return BlsLib.g1Compress(b ? p : q); }",
                        "JULC0041", "Conditional else branch received G2, but requires G1"),
                new Bad("G1 in the else branch of a G2 conditional", "static byte[] m(boolean b, byte[] dst) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); var q = Builtins.bls12_381_G2_hashToGroup(dst, dst); return BlsLib.g1Compress(b ? q : p); }",
                        "JULC0041", "Conditional else branch received G1, but requires G2"),
                new Bad("Data in the else branch of a G1 conditional", "static byte[] m(boolean b, byte[] dst, PlutusData d) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); return BlsLib.g1Compress(b ? p : d); }",
                        "JULC0041", "Conditional else branch received Data, but requires G1"),
                new Bad("G1 in the else branch of a Data conditional", "static byte[] m(boolean b, byte[] dst, PlutusData d) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); return BlsLib.g1Compress(b ? d : p); }",
                        "JULC0041", "Conditional else branch received G1, but requires Data"),
                new Bad("bytes in the else branch of a G1 conditional", "static byte[] m(boolean b, byte[] dst) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); return BlsLib.g1Compress(b ? p : dst); }",
                        "JULC0041", "Conditional else branch received ByteString, but requires G1"),
                new Bad("a point list in the else branch of a scalars conditional", "static JulcG1 m(boolean b, byte[] dst) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); var s = Builtins.scalars(BigInteger.ONE); var ps = Builtins.g1Points(p); return BlsLib.g1MultiScalarMul(b ? s : ps, ps); }",
                        "JULC0041", "Conditional else branch received NativeList[G1], but requires NativeList[Integer]"),
                new Bad("scalars in the else branch of a point-list conditional", "static JulcG1 m(boolean b, byte[] dst) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); var s = Builtins.scalars(BigInteger.ONE); var ps = Builtins.g1Points(p); return BlsLib.g1MultiScalarMul(s, b ? ps : s); }",
                        "JULC0041", "Conditional else branch received NativeList[Integer], but requires NativeList[G1]"),
                new Bad("a Data list in the else branch of a scalars conditional", "static JulcG1 m(boolean b, byte[] dst, JulcList<BigInteger> xs) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); var s = Builtins.scalars(BigInteger.ONE); return BlsLib.g1MultiScalarMul(b ? s : xs, Builtins.g1Points(p)); }",
                        "JULC0041", "Conditional else branch received List[Integer], but requires NativeList[Integer]"),
                new Bad("a mixed conditional as a G1 initializer", "static boolean m(boolean b, byte[] dst) { var p = Builtins.bls12_381_G1_hashToGroup(dst, dst); var q = Builtins.bls12_381_G2_hashToGroup(dst, dst); JulcG1 r = b ? p : q; return BlsLib.g1Equal(r, r); }",
                        "JULC0041", "Conditional else branch received G2, but requires G1"),
                // loop-local declarations (PR #150 review): plain, nested and break-aware loops; bytes as well as the wrong group
                new Bad("G2 into a loop-local G1 declaration", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { boolean ok = true; for (var x : xs) { JulcG1 p = Builtins.bls12_381_G2_hashToGroup(dst, dst); ok = BlsLib.g1Equal(p, p); } return ok; }",
                        "JULC0041", "Variable 'p' initializer received G2, but requires G1"),
                new Bad("G2 into a nested loop-local G1 declaration", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { boolean ok = true; for (var x : xs) { for (var y : xs) { JulcG1 p = Builtins.bls12_381_G2_hashToGroup(dst, dst); ok = BlsLib.g1Equal(p, p); } } return ok; }",
                        "JULC0041", "Variable 'p' initializer received G2, but requires G1"),
                new Bad("G2 into a break-aware loop-local G1 declaration", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { boolean ok = true; for (var x : xs) { JulcG1 p = Builtins.bls12_381_G2_hashToGroup(dst, dst); ok = BlsLib.g1Equal(p, p); if (ok) { break; } } return ok; }",
                        "JULC0041", "Variable 'p' initializer received G2, but requires G1"),
                new Bad("bytes into a loop-local G1 declaration", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { boolean ok = true; for (var x : xs) { JulcG1 p = dst; ok = BlsLib.g1Equal(p, p); } return ok; }",
                        "JULC0041", "Variable 'p' initializer received ByteString, but requires G1"),
                // loop assignments (PR #150 review): a single accumulator, before a break, a loop-body local; a native
                // accumulator among several is rejected earlier, at the Data pack of the multi-accumulator loop
                new Bad("G2 assigned to a G1 accumulator", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { JulcG1 acc = Builtins.bls12_381_G1_hashToGroup(dst, dst); for (var x : xs) { acc = Builtins.bls12_381_G2_hashToGroup(dst, dst); } return BlsLib.g1Equal(acc, acc); }",
                        "JULC0041", "Assignment to 'acc' received G2, but requires G1"),
                new Bad("G2 assigned to a G1 accumulator among several", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { JulcG1 acc = Builtins.bls12_381_G1_hashToGroup(dst, dst); BigInteger n = BigInteger.ZERO; for (var x : xs) { n = n.add(x); acc = Builtins.bls12_381_G2_hashToGroup(dst, dst); } return BlsLib.g1Equal(acc, acc) && n.equals(BigInteger.ZERO); }",
                        "JULC0041", "Data encoding received G1, but requires Data"),
                new Bad("G2 assigned to a G1 accumulator before a break", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { JulcG1 acc = Builtins.bls12_381_G1_hashToGroup(dst, dst); for (var x : xs) { if (x.equals(BigInteger.ONE)) { acc = Builtins.bls12_381_G2_hashToGroup(dst, dst); break; } } return BlsLib.g1Equal(acc, acc); }",
                        "JULC0041", "Assignment to 'acc' received G2, but requires G1"),
                new Bad("G2 assigned to a G1 accumulator among several before a break", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { JulcG1 acc = Builtins.bls12_381_G1_hashToGroup(dst, dst); BigInteger n = BigInteger.ZERO; for (var x : xs) { if (x.equals(BigInteger.ONE)) { n = n.add(x); acc = Builtins.bls12_381_G2_hashToGroup(dst, dst); break; } } return BlsLib.g1Equal(acc, acc) && n.equals(BigInteger.ZERO); }",
                        "JULC0041", "Data encoding received G1, but requires Data"),
                new Bad("G2 assigned to a loop-body G1 local", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { boolean ok = true; for (var x : xs) { JulcG1 p = Builtins.bls12_381_G1_hashToGroup(dst, dst); p = Builtins.bls12_381_G2_hashToGroup(dst, dst); ok = BlsLib.g1Equal(p, p); } return ok; }",
                        "JULC0041", "Assignment to 'p' received G2, but requires G1"),
                new Bad("bytes assigned to a G1 accumulator", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { JulcG1 acc = Builtins.bls12_381_G1_hashToGroup(dst, dst); for (var x : xs) { acc = dst; } return BlsLib.g1Equal(acc, acc); }",
                        "JULC0041", "Assignment to 'acc' received ByteString, but requires G1"),
                // a bare nested block in the loop body is spliced into it (PR #150 review round three)
                new Bad("G2 assigned to a G1 accumulator inside a nested block", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { JulcG1 acc = Builtins.bls12_381_G1_hashToGroup(dst, dst); for (var x : xs) { { acc = Builtins.bls12_381_G2_hashToGroup(dst, dst); } } return BlsLib.g1Equal(acc, acc); }",
                        "JULC0041", "Assignment to 'acc' received G2, but requires G1"),
                new Bad("G2 into a loop-local G1 declaration inside a nested block", "static boolean m(JulcList<BigInteger> xs, byte[] dst) { boolean ok = true; for (var x : xs) { { JulcG1 p = Builtins.bls12_381_G2_hashToGroup(dst, dst); ok = BlsLib.g1Equal(p, p); } } return ok; }",
                        "JULC0041", "Variable 'p' initializer received G2, but requires G1"),
                // incremental native lists (#240): the element and the list are typed like every other native argument
                new Bad("G2 point consed onto a G1 list", "static JulcG1Points m(byte[] dst) { return Builtins.g1PointsCons(Builtins.bls12_381_G2_hashToGroup(dst, dst), Builtins.g1PointsEmpty()); }",
                        "JULC0041", "Builtins.g1PointsCons argument 1 received G2, but requires G1"),
                new Bad("G1 point consed onto a G2 list", "static JulcG2Points m(byte[] dst) { return Builtins.g2PointsCons(Builtins.bls12_381_G1_hashToGroup(dst, dst), Builtins.g2PointsEmpty()); }",
                        "JULC0041", "Builtins.g2PointsCons argument 1 received G1, but requires G2"),
                new Bad("a G1 list under a G2 cons", "static JulcG2Points m(byte[] dst) { return Builtins.g2PointsCons(Builtins.bls12_381_G2_hashToGroup(dst, dst), Builtins.g1PointsEmpty()); }",
                        "JULC0041", "Builtins.g2PointsCons argument 2 received NativeList[G1], but requires NativeList[G2]"),
                new Bad("compressed bytes consed as a point", "static JulcG1Points m(byte[] b) { return Builtins.g1PointsCons(b, Builtins.g1PointsEmpty()); }",
                        "JULC0041", "Builtins.g1PointsCons argument 1 received ByteString, but requires G1"),
                new Bad("Data consed as a point", "static JulcG1Points m(PlutusData d) { return Builtins.g1PointsCons(d, Builtins.g1PointsEmpty()); }",
                        "JULC0041", "Builtins.g1PointsCons argument 1 received Data, but requires G1"),
                new Bad("a Data list where a native point list is required", "static JulcG1Points m(JulcList<byte[]> xs, byte[] dst) { return Builtins.g1PointsCons(Builtins.bls12_381_G1_hashToGroup(dst, dst), xs); }",
                        "JULC0041", "Builtins.g1PointsCons argument 2 received List[ByteString], but requires NativeList[G1]"),
                new Bad("a raw Data list where a native point list is required", "static JulcG1Points m(PlutusData xs, byte[] dst) { return Builtins.g1PointsCons(Builtins.bls12_381_G1_hashToGroup(dst, dst), Builtins.unListData(xs)); }",
                        "JULC0041", "but requires NativeList[G1]"),
                new Bad("scalars where a point list is required", "static JulcG1Points m(byte[] dst) { return Builtins.g1PointsCons(Builtins.bls12_381_G1_hashToGroup(dst, dst), Builtins.scalarsEmpty()); }",
                        "JULC0041", "Builtins.g1PointsCons argument 2 received NativeList[Integer], but requires NativeList[G1]"),
                new Bad("a point list under a scalars cons", "static JulcScalars m() { return Builtins.scalarsCons(BigInteger.ONE, Builtins.g1PointsEmpty()); }",
                        "JULC0041", "Builtins.scalarsCons argument 2 received NativeList[G1], but requires NativeList[Integer]"),
                new Bad("a Data integer list under a scalars cons", "static JulcScalars m(JulcList<BigInteger> xs) { return Builtins.scalarsCons(BigInteger.ONE, xs); }",
                        "JULC0041", "Builtins.scalarsCons argument 2 received List[Integer], but requires NativeList[Integer]"),
                new Bad("bytes consed as a scalar", "static JulcScalars m(byte[] b) { return Builtins.scalarsCons(b, Builtins.scalarsEmpty()); }",
                        "JULC0041", "Builtins.scalarsCons argument 1 received ByteString, but requires Integer"),
                new Bad("Data consed as a scalar", "static JulcScalars m(PlutusData d) { return Builtins.scalarsCons(Builtins.headList(d), Builtins.scalarsEmpty()); }",
                        "JULC0041", "Builtins.scalarsCons argument 1 received Data, but requires Integer"),
                new Bad("a point consed as a scalar", "static JulcScalars m(byte[] dst) { return Builtins.scalarsCons(Builtins.bls12_381_G1_hashToGroup(dst, dst), Builtins.scalarsEmpty()); }",
                        "JULC0041", "Builtins.scalarsCons argument 1 received G1, but requires Integer"),
                new Bad("the empty G2 list returned as a G1 list", "static JulcG1Points m() { return Builtins.g2PointsEmpty(); }",
                        "JULC0041", "requires NativeList[G1]"),
                new Bad("a recursive helper returning the wrong group", "static JulcG1Points h(JulcList<byte[]> xs) { if (xs.isEmpty()) return Builtins.g1PointsEmpty(); return Builtins.g2PointsCons(BlsLib.g2Uncompress(xs.head()), Builtins.g2PointsEmpty()); }\n static JulcG1Points m(JulcList<byte[]> xs) { return h(xs); }",
                        "JULC0041", "received NativeList[G2], but requires NativeList[G1]"),
                new Bad("an empty G1 list into a G2 accumulator", "static boolean m(JulcList<byte[]> xs) { JulcG2Points acc = Builtins.g1PointsEmpty(); for (var b : xs) { acc = Builtins.g2PointsCons(BlsLib.g2Uncompress(b), acc); } return true; }",
                        "JULC0041", "received NativeList[G1], but requires NativeList[G2]"),
                new Bad("mixed empty lists in a conditional", "static JulcG1 m(boolean b) { return BlsLib.g1MultiScalarMul(Builtins.scalarsEmpty(), b ? Builtins.g1PointsEmpty() : Builtins.g2PointsEmpty()); }",
                        "JULC0041", "Conditional else branch received NativeList[G2], but requires NativeList[G1]"),
                new Bad("native lists compared with ==", "static boolean m(byte[] dst) { JulcG1Points ps = Builtins.g1PointsCons(Builtins.bls12_381_G1_hashToGroup(dst, dst), Builtins.g1PointsEmpty()); return ps == Builtins.g1PointsEmpty(); }",
                        "JULC0041", "NativeList[G1]"),
                new Bad("a native list in a Data list", "static boolean m() { JulcList<JulcG1Points> xs = JulcList.of(Builtins.g1PointsEmpty()); return true; }",
                        "JULC0041", "NativeList[G1]"),
                new Bad("a native list as Data (equalsData)", "static boolean m(PlutusData d) { return Builtins.equalsData(Builtins.scalarsEmpty(), d); }",
                        "JULC0041", "NativeList[Integer]"),
                new Bad("the Data-list nullList on a native list", "static boolean m() { return Builtins.nullList(Builtins.g1PointsEmpty()); }",
                        "JULC0041", "received NativeList[G1], but requires Data"),
                new Bad("the Data-list headList on a native list", "static PlutusData m() { return Builtins.headList(Builtins.scalarsCons(BigInteger.ONE, Builtins.scalarsEmpty())); }",
                        "JULC0041", "received NativeList[Integer], but requires Data"),
                new Bad("the Data-list mkCons onto a native list", "static PlutusData m(PlutusData d) { return Builtins.mkCons(d, Builtins.g1PointsEmpty()); }",
                        "JULC0041", "received NativeList[G1], but requires Data"),
                new Bad("a native list in a record", "record Box(JulcG1Points ps) {}\n static boolean m() { var box = new Box(Builtins.g1PointsEmpty()); return true; }",
                        "JULC0041", "requires Data"),
                new Bad("a native list accumulator among several (the Data pack)", "static boolean m(JulcList<byte[]> xs) { JulcG1Points acc = Builtins.g1PointsEmpty(); BigInteger n = BigInteger.ZERO; for (var b : xs) { acc = Builtins.g1PointsCons(BlsLib.g1Uncompress(b), acc); n = n.add(BigInteger.ONE); } return n.equals(BigInteger.ZERO); }",
                        "JULC0041", "Data encoding received NativeList[G1], but requires Data"));
        for (var bad : cases) {
            var error = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(IMPORTS + "class Bad {\n" + bad.body() + "\n}\n", "m"),
                    bad.name());
            var diagnostic = error.diagnostics().getFirst();
            assertEquals(bad.code(), diagnostic.code(), bad.name() + ": " + diagnostic.message());
            assertTrue(diagnostic.message().contains(bad.fragment()), bad.name() + ": " + diagnostic.message());
        }
        // An assignment the loop body generators do not bind is rejected, never lowered to its right-hand side
        // with the update dropped: in expression position, and to a target with no declaration in scope
        // (PR #150 review round three).
        var inExpression = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(IMPORTS + """
                class InExpression {
                    static boolean m(JulcList<BigInteger> xs, byte[] dst) {
                        JulcG1 acc = Builtins.bls12_381_G1_hashToGroup(dst, dst);
                        for (var x : xs) {
                            acc = BlsLib.g1Neg(acc);
                            BlsLib.g2Compress(acc = Builtins.bls12_381_G2_hashToGroup(dst, dst));
                        }
                        return BlsLib.g1Equal(acc, acc);
                    }
                }
                """, "m"));
        assertTrue(inExpression.getMessage().contains("Assignment to 'acc' is not supported at this position"), inExpression.getMessage());
        var undeclared = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(IMPORTS + """
                class Undeclared {
                    static boolean m(JulcList<BigInteger> xs, byte[] dst) {
                        boolean ok = true;
                        for (var x : xs) {
                            undeclared = Builtins.bls12_381_G2_hashToGroup(dst, dst);
                            ok = true;
                        }
                        return ok;
                    }
                }
                """, "m"));
        assertTrue(undeclared.getMessage().contains("Assignment to undeclared variable 'undeclared'"), undeclared.getMessage());
        // A validator entrypoint cannot take a point or a native list either (the strict boundary has no decoder for them).
        var validator = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(IMPORTS + """
                @SpendingValidator
                class BlsDatumValidator {
                    @Entrypoint
                    static boolean validate(JulcG1 datum, JulcScalars redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """));
        assertEquals("JULC0042", validator.diagnostics().getFirst().code(), validator.getMessage());
        // A lambda's return is not the method's: a native-typed method may use a boolean lambda inside.
        var lambdaInside = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(IMPORTS + """
                class LambdaInside {
                    static JulcG1 m(JulcList<BigInteger> xs, byte[] dst) {
                        JulcList<BigInteger> kept = xs.filter(x -> { return x.compareTo(BigInteger.ZERO) > 0; });
                        return BlsLib.g1MultiScalarMul(Builtins.scalarsFromList(kept), Builtins.g1Points(Builtins.bls12_381_G1_hashToGroup(dst, dst)));
                    }
                }
                """, "m");
        assertFalse(lambdaInside.hasErrors(), lambdaInside.diagnostics().toString());
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

    /**
     * #240: the empty forms are exactly the empty native list constant of their universe (the
     * one {@code g1Points()} is), at every level; a cons is one {@code MkCons} of the element
     * over the list, bound once and typed by the bound {@code Var}, so a one-element cons over
     * the empty form serializes to the same bytes as the one-element literal; no Data encoder
     * appears; each cons adds exactly one {@code MkCons}.
     */
    @Test
    void incrementalProducersLowerToTheEmptyConstantAndOneMkConsPerElement() {
        var empties = Map.of(
                "static JulcScalars m() { return Builtins.scalarsEmpty(); }", DefaultUni.INTEGER,
                "static JulcG1Points m() { return Builtins.g1PointsEmpty(); }", DefaultUni.BLS12_381_G1,
                "static JulcG2Points m() { return Builtins.g2PointsEmpty(); }", DefaultUni.BLS12_381_G2);
        var literalEmpties = Map.of(
                DefaultUni.INTEGER, "static JulcScalars m() { return Builtins.scalars(); }",
                DefaultUni.BLS12_381_G1, "static JulcG1Points m() { return Builtins.g1Points(); }",
                DefaultUni.BLS12_381_G2, "static JulcG2Points m() { return Builtins.g2Points(); }");
        for (var empty : empties.entrySet()) {
            for (var level : OptimizationLevel.values()) {
                var program = compile(IMPORTS + "class P {\n" + empty.getKey() + "\n}\n", "m", level, false).program();
                if (level != OptimizationLevel.NONE) {
                    assertEquals(Term.const_(new Constant.ListConst(empty.getValue(), List.of())), program.term(), empty.getKey() + " " + level);
                }
                var literal = compile(IMPORTS + "class P {\n" + literalEmpties.get(empty.getValue()) + "\n}\n", "m", level, false).program();
                assertArrayEquals(UplcFlatEncoder.encodeProgram(literal), UplcFlatEncoder.encodeProgram(program), empty.getKey() + " " + level);
            }
        }
        // A cons: Let #__native_<name> = MkCons element list in #__native_<name>, the Var typed as the native list.
        var cons = pir("static JulcG1Points m(byte[] dst) { return Builtins.g1PointsCons(Builtins.bls12_381_G1_hashToGroup(dst, dst), Builtins.g1PointsEmpty()); }");
        var binding = nodes(cons).stream().filter(t -> t instanceof PirTerm.Let l && l.name().equals("#__native_g1PointsCons"))
                .map(t -> (PirTerm.Let) t).findFirst().orElseThrow(() -> new AssertionError(cons.toString()));
        var outer = assertInstanceOf(PirTerm.App.class, binding.value());
        var inner = assertInstanceOf(PirTerm.App.class, outer.function());
        assertEquals(DefaultFun.MkCons, assertInstanceOf(PirTerm.Builtin.class, inner.function()).fun());
        assertTrue(mentions(inner.argument(), DefaultFun.Bls12_381_G1_hashToGroup), inner.argument().toString());
        assertEquals(new PirTerm.Const(new Constant.ListConst(DefaultUni.BLS12_381_G1, List.of())), outer.argument());
        assertEquals(new PirTerm.Var("#__native_g1PointsCons", new PirType.NativeListType(new PirType.NativeG1Type())), binding.body());
        // One element over the empty form is the one-element literal, byte for byte (binder names are not serialized).
        var sameAsLiteral = Map.of(
                "static JulcG1Points m(byte[] dst) { return Builtins.g1PointsCons(Builtins.bls12_381_G1_hashToGroup(dst, dst), Builtins.g1PointsEmpty()); }",
                "static JulcG1Points m(byte[] dst) { return Builtins.g1Points(Builtins.bls12_381_G1_hashToGroup(dst, dst)); }",
                "static JulcG2Points m(byte[] dst) { return Builtins.g2PointsCons(Builtins.bls12_381_G2_hashToGroup(dst, dst), Builtins.g2PointsEmpty()); }",
                "static JulcG2Points m(byte[] dst) { return Builtins.g2Points(Builtins.bls12_381_G2_hashToGroup(dst, dst)); }",
                "static JulcScalars m(BigInteger x) { return Builtins.scalarsCons(x, Builtins.scalarsEmpty()); }",
                "static JulcScalars m(BigInteger x) { return Builtins.scalars(x); }");
        for (var pair : sameAsLiteral.entrySet()) {
            for (var level : OptimizationLevel.values()) {
                assertArrayEquals(
                        UplcFlatEncoder.encodeProgram(compile(IMPORTS + "class P {\n" + pair.getValue() + "\n}\n", "m", level, false).program()),
                        UplcFlatEncoder.encodeProgram(compile(IMPORTS + "class P {\n" + pair.getKey() + "\n}\n", "m", level, false).program()),
                        pair.getKey() + " " + level);
            }
        }
        // Each cons is one MkCons; a recursive helper conses once per call; none of them encodes Data.
        var nested = pir("static JulcScalars m(BigInteger x) { return Builtins.scalarsCons(x, Builtins.scalarsCons(BigInteger.ONE, Builtins.scalarsCons(x, Builtins.scalarsEmpty()))); }");
        assertEquals(3, nodes(nested).stream().filter(t -> t instanceof PirTerm.Builtin b && b.fun() == DefaultFun.MkCons).count(), nested.toString());
        assertEquals(1, constants(nested).stream().filter(c -> c.equals(new Constant.ListConst(DefaultUni.INTEGER, List.of()))).count(), nested.toString());
        var recursive = pir("""
                static JulcG1Points collect(PlutusData xs) {
                    if (Builtins.nullList(xs)) return Builtins.g1PointsEmpty();
                    return Builtins.g1PointsCons(Builtins.bls12_381_G1_uncompress(Builtins.unBData(Builtins.headList(xs))), collect(Builtins.tailList(xs)));
                }
                static JulcG1Points m(PlutusData xs) { return collect(Builtins.unListData(xs)); }""");
        assertEquals(1, nodes(recursive).stream().filter(t -> t instanceof PirTerm.Builtin b && b.fun() == DefaultFun.MkCons).count(), recursive.toString());
        for (var term : List.of(cons, nested, recursive)) {
            for (var encoder : DATA_ENCODERS) assertFalse(mentions(term, encoder), encoder + " in " + term);
        }
        // 2·G, the second point of the #240 fixtures, is G + G.
        var doubled = compile(IMPORTS + """
                class Doubled {
                    static boolean m(byte[] g, byte[] d) { return BlsLib.g1Equal(BlsLib.g1Uncompress(d), BlsLib.g1Add(BlsLib.g1Uncompress(g), BlsLib.g1Uncompress(g))); }
                }
                """, "m", OptimizationLevel.BASELINE, false);
        assertEquals(Term.const_(Constant.bool(true)), assertInstanceOf(EvalResult.Success.class, evaluate(doubled.program(),
                List.of(PlutusData.bytes(O11BlsTypesFixtures.G1_GENERATOR), PlutusData.bytes(O11BlsTypesFixtures.G1_DOUBLE)), "Java")).resultTerm());
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
        // #240: the empty point lists emit the BLS list constant; scalars and a cons (one MkCons) need nothing more.
        assertEquals(LoweringRequirements.NONE, registry.requirements(BUILTINS, "scalarsEmpty"));
        assertEquals(LoweringRequirements.NONE, registry.requirements(BUILTINS, "scalarsCons"));
        assertEquals(LoweringRequirements.capability(ProtocolCapability.BLS_CONSTANTS), registry.requirements(BUILTINS, "g1PointsEmpty"));
        assertEquals(LoweringRequirements.capability(ProtocolCapability.BLS_CONSTANTS), registry.requirements(BUILTINS, "g2PointsEmpty"));
        assertEquals(LoweringRequirements.NONE, registry.requirements(BUILTINS, "g1PointsCons"));
        assertEquals(LoweringRequirements.NONE, registry.requirements(BUILTINS, "g2PointsCons"));
        assertEquals(LoweringRequirements.builtin(DefaultFun.Bls12_381_G1_multiScalarMul), registry.requirements(BUILTINS, "bls12_381_G1_multiScalarMul"));
        assertEquals(LoweringRequirements.builtin(DefaultFun.Bls12_381_G2_multiScalarMul), registry.requirements(BUILTINS, "bls12_381_G2_multiScalarMul"));

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
            case PirTerm.Let l -> { if (!isLibraryMethod(l.name())) collect(l.value(), out); collect(l.body(), out); }
            case PirTerm.LetRec r -> { r.bindings().forEach(b -> { if (!isLibraryMethod(b.name())) collect(b.value(), out); }); collect(r.body(), out); }
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

    /**
     * Stdlib method bindings are qualified by their package. Since ADR-060 the fixture's own
     * methods are qualified too, by the default-package fixture class, so a dot alone no longer
     * separates library code from user code.
     */
    private static boolean isLibraryMethod(String binder) {
        return binder.startsWith("org.julclang.");
    }
}
