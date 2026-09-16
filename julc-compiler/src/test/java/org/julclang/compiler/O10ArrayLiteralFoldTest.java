package org.julclang.compiler;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.pir.ArrayLiteralFoldPass;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.FlatWriter;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.OptimizationCostProfile;
import org.julclang.vm.OptimizationCostProfiles;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.UplcVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.julclang.compiler.O10ArrayLiteralFixtures.FIXTURES;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-046 (O10): literal folding of the PV11 array builtins. Every fixture in
 * {@link O10ArrayLiteralFixtures} is compiled at every level with the rule switched off and on:
 * NONE and BASELINE never fold and stay byte-identical either way; at the safe and costed
 * profiles the folded program is observationally equivalent on Java, Truffle and Scalus for
 * every input (results, traces, failure text), never larger, and strictly smaller when a fold
 * fired. Direct-PIR cases pin the list-literal grammar, the decode folds over produced
 * elements only, the failure cases, the size objective and source positions.
 */
@Tag("pair-case-backends")
class O10ArrayLiteralFoldTest {

    private static final OptimizationCostProfile PROFILE = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
    private static final List<String> PROVIDERS = List.of("Java", "Truffle", "Scalus");
    private static final Set<DefaultFun> ARRAY_BUILTINS = Set.of(DefaultFun.ListToArray, DefaultFun.LengthOfArray, DefaultFun.IndexArray);
    private static final PirType DATA = new PirType.DataType();
    private static final PirType INT = new PirType.IntegerType();
    private static final DefaultUni DATA_UNI = new DefaultUni.Data();

    /** The value every successful fixture path must compute (folded or not). */
    private static final Map<String, Constant> EXPECTED = Map.ofEntries(
            Map.entry("LENGTH/run", Constant.integer(3)),
            Map.entry("GET_LITERAL/run", Constant.integer(2)),
            Map.entry("GET_RUNTIME/first", Constant.integer(1)),
            Map.entry("GET_RUNTIME/last", Constant.integer(3)),
            Map.entry("BYTES/run", Constant.integer(2)),
            Map.entry("STRING/run", Constant.bool(true)),
            Map.entry("BOOL/run", Constant.integer(0)),
            Map.entry("NESTED_LIST/run", Constant.integer(1)),
            Map.entry("EMPTY/run", Constant.integer(0)),
            Map.entry("LOCAL_LIST/run", Constant.integer(3)),
            Map.entry("LOCAL_LIST_ONCE/run", Constant.integer(3)),
            Map.entry("SHARED_ELEMENT/first", Constant.byteString(O10ArrayLiteralFixtures.B256_TWICE)),
            Map.entry("SHARED_ELEMENT/second", Constant.byteString(O10ArrayLiteralFixtures.B256_TWICE)),
            Map.entry("SHARED_ELEMENT_ONCE/run", Constant.byteString(O10ArrayLiteralFixtures.B256_BYTES)),
            Map.entry("ELEMENT_ONCE/run", Constant.byteString(O10ArrayLiteralFixtures.B32_BYTES)),
            Map.entry("WIDE_ELEMENT_ONCE/run", Constant.byteString(O10ArrayLiteralFixtures.B256_BYTES)),
            Map.entry("FROM_LIST/run", Constant.integer(2)),
            Map.entry("RUNTIME_ELEMENT/five", Constant.integer(6)),
            Map.entry("TRACE_AROUND/run", Constant.integer(3)),
            Map.entry("ERROR_ARM/pass", Constant.integer(1)),
            Map.entry("TWO_ARRAYS/run", Constant.integer(12)),
            Map.entry("HELPER_GET/run", Constant.integer(2)));

    @Test
    void safeProfileFoldsLiteralArraysAndStaysObservationallyEquivalentOnEveryBackend() {
        for (var fixture : FIXTURES) {
            for (var level : OptimizationLevel.values()) {
                String label = fixture.name() + "/" + level;
                var off = compile(fixture, level, false, ArrayLiteralFoldPass.RULE);
                var on = compile(fixture, level, false);
                assertFalse(off.hasErrors(), label + " " + off.diagnostics());
                assertFalse(on.hasErrors(), label + " " + on.diagnostics());
                var offBytes = UplcFlatEncoder.encodeProgram(off.program());
                var onBytes = UplcFlatEncoder.encodeProgram(on.program());
                assertArrayEquals(onBytes, UplcFlatEncoder.encodeProgram(compile(fixture, level, false).program()), label + " determinism");
                assertFalse(off.optimizationReport().appliedRules().contains(ArrayLiteralFoldPass.RULE), label);
                boolean expectRule = level.pv11SafeRulesEnabled() && fixture.folds();
                assertEquals(expectRule, on.optimizationReport().appliedRules().contains(ArrayLiteralFoldPass.RULE), label);
                assertEquals(fixture.callsBefore(), countCalls(off.pirTerm()), label + " calls with the rule off");
                assertEquals(level.pv11SafeRulesEnabled() ? fixture.callsAfter() : fixture.callsBefore(),
                        countCalls(on.pirTerm()), label + " calls with the rule on");
                if (!level.pv11SafeRulesEnabled()) {
                    assertArrayEquals(offBytes, onBytes, label + " NONE/BASELINE are not touched");
                } else if (expectRule) {
                    assertTrue(onBytes.length < offBytes.length, label + " " + offBytes.length + " -> " + onBytes.length);
                    assertNotEquals(JulcScriptAdapter.scriptHash(off.program()), JulcScriptAdapter.scriptHash(on.program()), label);
                } else {
                    assertArrayEquals(offBytes, onBytes, label + " nothing to fold");
                }
                var mapped = compile(fixture, level, true);
                assertEquals(expectRule, mapped.optimizationReport().appliedRules().contains(ArrayLiteralFoldPass.RULE), label + " maps");
                // ADR-032: the unreleased MultiIndexArray is never emitted, folded or not.
                assertFalse(mentions(off.program().term(), DefaultFun.MultiIndexArray), label);
                assertFalse(mentions(on.program().term(), DefaultFun.MultiIndexArray), label);
                for (var input : fixture.inputs()) {
                    EvalResult javaResult = null;
                    for (String provider : PROVIDERS) {
                        String inputLabel = label + "/" + provider + "/" + input;
                        var before = evaluate(off.program(), input.args(), provider);
                        var after = evaluate(on.program(), input.args(), provider);
                        assertEquals(input.success(), before.isSuccess(), inputLabel + " " + before);
                        assertEquals(input.success(), after.isSuccess(), inputLabel + " " + after);
                        assertEquals(before.traces(), after.traces(), inputLabel);
                        // A fold removes evaluation on every path, failing ones included.
                        assertTrue(after.budgetConsumed().cpuSteps() <= before.budgetConsumed().cpuSteps(), inputLabel);
                        assertTrue(after.budgetConsumed().memoryUnits() <= before.budgetConsumed().memoryUnits(), inputLabel);
                        if (before instanceof EvalResult.Success b) {
                            var a = assertInstanceOf(EvalResult.Success.class, after, inputLabel);
                            assertEquals(b.resultTerm(), a.resultTerm(), inputLabel);
                            var expected = EXPECTED.get(fixture.name() + "/" + input.name());
                            assertNotNull(expected, inputLabel + " has no expected value");
                            assertEquals(Term.const_(expected), a.resultTerm(), inputLabel);
                        } else {
                            var b = (EvalResult.Failure) before;
                            var a = assertInstanceOf(EvalResult.Failure.class, after, inputLabel);
                            // The array is a constant either way, so the failing builtin and its text are the same.
                            assertEquals(b.error(), a.error(), inputLabel);
                            // Every failing array fixture fails at IndexArray (the error guard fixture at its error).
                            if (provider.equals("Java") && !fixture.name().equals("ERROR_ARM")) {
                                assertTrue(a.error().startsWith("IndexArray: index"), inputLabel + " " + a.error());
                            }
                        }
                        if (provider.equals("Java")) javaResult = after;
                        if (!provider.equals("Java")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), inputLabel);
                        if (level == OptimizationLevel.PV11_SAFE && provider.equals("Java")) {
                            System.out.println("ARRAY_LITERAL_COST " + fixture.name() + " " + input + " "
                                    + before.budgetConsumed() + " -> " + after.budgetConsumed());
                        }
                    }
                }
                if (level == OptimizationLevel.PV11_SAFE) {
                    System.out.println("ARRAY_LITERAL_ARTIFACT " + fixture.name() + " " + offBytes.length + " -> " + onBytes.length + " "
                            + JulcScriptAdapter.scriptHash(off.program()) + " -> " + JulcScriptAdapter.scriptHash(on.program()));
                }
            }
        }
    }

    /**
     * The list-literal grammar, the three builtins on literals, the decode folds over produced
     * elements only, the failure cases, the switch, positions and the size objective.
     */
    @Test
    void directPirListLiteralsDecodesFailuresPositionsAndObjective() {
        var one = intElement(1);
        var two = intElement(2);
        var list = cons(one, cons(two, nil()));
        var array = new Constant.ArrayConst(DATA_UNI, List.of(Constant.data(PlutusData.integer(1)), Constant.data(PlutusData.integer(2))));

        // ListToArray of a list literal, bare or through a once-bound local; a runtime element blocks.
        var toArray = app(builtin(DefaultFun.ListToArray), list);
        assertEquals(new PirTerm.Const(array), lower(toArray, OptimizationLevel.PV11_SAFE));
        assertSame(toArray, lower(toArray, OptimizationLevel.BASELINE));
        assertSame(toArray, lowerWith(context(OptimizationLevel.PV11_SAFE, ArrayLiteralFoldPass.RULE), toArray));
        assertEquivalent(toArray);
        var viaLocal = new PirTerm.Let("xs", list, app(builtin(DefaultFun.LengthOfArray), app(builtin(DefaultFun.ListToArray), new PirTerm.Var("xs", DATA))));
        assertEquals(new PirTerm.Const(Constant.integer(2)), assertInstanceOf(PirTerm.Let.class, lower(viaLocal, OptimizationLevel.PV11_SAFE)).body());
        assertEquivalent(viaLocal);
        // A local nested in the list literal stands at the call site as a reference, not as a copy
        // of its constant (the review's finding): shared with a runtime use it blocks the
        // conversion; used twice inside the literal it still blocks (the array would hold two
        // copies against the one binding that dies); used once it is credited and the conversion folds.
        var bytesType = new PirType.ByteStringType();
        var wide = constant(Constant.byteString(new byte[64]));
        var sharedElements = new PirTerm.Lam("i", INT, new PirTerm.Let("b", wide,
                app(builtin(DefaultFun.AppendByteString),
                        app(builtin(DefaultFun.UnBData), app(builtin(DefaultFun.IndexArray),
                                app(builtin(DefaultFun.ListToArray), cons(app(builtin(DefaultFun.BData), new PirTerm.Var("b", bytesType)),
                                        cons(app(builtin(DefaultFun.BData), new PirTerm.Var("b", bytesType)), nil()))),
                                new PirTerm.Var("i", INT))),
                        new PirTerm.Var("b", bytesType))));
        assertSame(sharedElements, lower(sharedElements, OptimizationLevel.PV11_SAFE));
        var twiceInside = new PirTerm.Let("b", wide, app(builtin(DefaultFun.ListToArray),
                cons(app(builtin(DefaultFun.BData), new PirTerm.Var("b", bytesType)), cons(app(builtin(DefaultFun.BData), new PirTerm.Var("b", bytesType)), nil()))));
        assertSame(twiceInside, lower(twiceInside, OptimizationLevel.PV11_SAFE));
        assertEquivalent(twiceInside);
        var onceInside = new PirTerm.Let("b", wide, app(builtin(DefaultFun.LengthOfArray), app(builtin(DefaultFun.ListToArray),
                cons(app(builtin(DefaultFun.BData), new PirTerm.Var("b", bytesType)), nil()))));
        assertEquals(new PirTerm.Const(Constant.integer(1)), assertInstanceOf(PirTerm.Let.class, lower(onceInside, OptimizationLevel.PV11_SAFE)).body());
        assertEquivalent(onceInside);
        // The objective over a credited single-element literal, by element width: the array
        // constant carries the element as CBOR Data (a header per element, chunked in FLAT) while
        // the chain carries the raw constant plus its wrapping; the decision matches the encoder.
        for (int width : List.of(1, 32, 64, 128, 200, 255, 256, 512)) {
            var element = Constant.byteString(new byte[width]);
            var chain = cons(app(builtin(DefaultFun.BData), constant(element)), nil());
            var conversion = new PirTerm.Let("b", constant(element), app(builtin(DefaultFun.ListToArray),
                    cons(app(builtin(DefaultFun.BData), new PirTerm.Var("b", bytesType)), nil())));
            var arrayConst = new Constant.ArrayConst(DATA_UNI, List.of(Constant.data(PlutusData.bytes(new byte[width]))));
            var chainBits = bits(Term.apply(Term.builtin(DefaultFun.ListToArray), Term.apply(Term.apply(Term.builtin(DefaultFun.MkCons),
                    Term.apply(Term.builtin(DefaultFun.BData), Term.const_(element))), Term.apply(Term.builtin(DefaultFun.MkNilData), Term.const_(Constant.unit())))));
            boolean fits = bits(Term.const_(arrayConst)) <= chainBits;
            assertEquals(fits, assertInstanceOf(PirTerm.Let.class, lower(conversion, OptimizationLevel.PV11_SAFE)).body() instanceof PirTerm.Const, "width " + width);
            assertEquals(fits, lower(app(builtin(DefaultFun.ListToArray), chain), OptimizationLevel.PV11_SAFE) instanceof PirTerm.Const, "bare width " + width);
            System.out.println("ARRAY_LITERAL_OBJECTIVE bytes element width=" + width + " array=" + bits(Term.const_(arrayConst)) + " chain=" + chainBits + " folds=" + fits + " (bits)");
        }
        var runtime = new PirTerm.Lam("v", INT, app(builtin(DefaultFun.ListToArray),
                cons(app(builtin(DefaultFun.IData), new PirTerm.Var("v", INT)), nil())));
        assertSame(runtime, lower(runtime, OptimizationLevel.PV11_SAFE));
        // Every wrapEncode element form: bytes, string, bool, nested list, a Data constant.
        var mixed = app(builtin(DefaultFun.ListToArray), cons(app(builtin(DefaultFun.BData), constant(Constant.byteString(new byte[]{7}))),
                cons(app(builtin(DefaultFun.BData), app(builtin(DefaultFun.EncodeUtf8), constant(Constant.string("hé")))),
                cons(PirHelpers.wrapEncode(constant(Constant.bool(true)), new PirType.BoolType()),
                cons(app(builtin(DefaultFun.ListData), cons(one, nil())),
                cons(constant(Constant.data(PlutusData.constr(3, PlutusData.integer(9)))), nil()))))));
        var mixedArray = assertInstanceOf(PirTerm.Const.class, lower(mixed, OptimizationLevel.PV11_SAFE));
        assertEquals(new Constant.ArrayConst(DATA_UNI, List.of(
                Constant.data(PlutusData.bytes(new byte[]{7})),
                Constant.data(PlutusData.bytes("hé".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                Constant.data(PlutusData.constr(1)),
                Constant.data(PlutusData.list(PlutusData.integer(1))),
                Constant.data(PlutusData.constr(3, PlutusData.integer(9))))), mixedArray.value());
        assertEquivalent(mixed);

        // LengthOfArray and IndexArray on an array literal; out-of-range and huge indexes stay and fail identically.
        assertEquals(new PirTerm.Const(Constant.integer(2)), lower(app(builtin(DefaultFun.LengthOfArray), constant(array)), OptimizationLevel.PV11_SAFE));
        assertEquals(new PirTerm.Const(Constant.data(PlutusData.integer(2))),
                lower(app(builtin(DefaultFun.IndexArray), constant(array), constant(Constant.integer(1))), OptimizationLevel.PV11_SAFE));
        for (var index : List.of(BigInteger.TWO, BigInteger.valueOf(-1), BigInteger.ONE.shiftLeft(63))) {
            var access = app(builtin(DefaultFun.IndexArray), constant(array), constant(Constant.integer(index)));
            assertSame(access, lower(access, OptimizationLevel.PV11_SAFE), index.toString());
            assertEquivalent(access);
        }

        // Decodes fold only over an element this pass produced; a Data constant already in the
        // program, bare or through a literal local, is never touched, and a mismatched shape stays.
        var decodedAccess = app(builtin(DefaultFun.UnIData), app(builtin(DefaultFun.IndexArray), constant(array), constant(Constant.integer(0))));
        assertEquals(new PirTerm.Const(Constant.integer(1)), lower(decodedAccess, OptimizationLevel.PV11_SAFE));
        assertEquivalent(decodedAccess);
        var bareDecode = app(builtin(DefaultFun.UnIData), constant(Constant.data(PlutusData.integer(5))));
        assertSame(bareDecode, lower(bareDecode, OptimizationLevel.PV11_SAFE));
        var localDecode = new PirTerm.Let("d", constant(Constant.data(PlutusData.integer(5))), app(builtin(DefaultFun.UnIData), new PirTerm.Var("d", DATA)));
        assertSame(localDecode, lower(localDecode, OptimizationLevel.PV11_SAFE));
        var bytesArray = new Constant.ArrayConst(DATA_UNI, List.of(Constant.data(PlutusData.bytes(new byte[]{(byte) 0xff}))));
        var mismatched = app(builtin(DefaultFun.UnIData), app(builtin(DefaultFun.IndexArray), constant(bytesArray), constant(Constant.integer(0))));
        var loweredMismatch = assertInstanceOf(PirTerm.App.class, lower(mismatched, OptimizationLevel.PV11_SAFE));
        assertInstanceOf(PirTerm.Const.class, loweredMismatch.argument(), "the access folds, the decode stays");
        assertEquivalent(mismatched);
        var invalidUtf8 = app(builtin(DefaultFun.DecodeUtf8), app(builtin(DefaultFun.UnBData),
                app(builtin(DefaultFun.IndexArray), constant(bytesArray), constant(Constant.integer(0)))));
        var loweredUtf8 = assertInstanceOf(PirTerm.App.class, lower(invalidUtf8, OptimizationLevel.PV11_SAFE));
        assertTrue(isBuiltin(loweredUtf8.function(), DefaultFun.DecodeUtf8));
        assertInstanceOf(PirTerm.Const.class, loweredUtf8.argument(), "the byte string decode folds, the UTF-8 decode stays");
        assertEquivalent(invalidUtf8);
        var boolArray = new Constant.ArrayConst(DATA_UNI, List.of(Constant.data(PlutusData.constr(1)), Constant.data(PlutusData.integer(4))));
        var boolAccess = PirHelpers.wrapDecode(app(builtin(DefaultFun.IndexArray), constant(boolArray), constant(Constant.integer(0))), new PirType.BoolType());
        assertEquals(new PirTerm.Const(Constant.bool(true)), lower(boolAccess, OptimizationLevel.PV11_SAFE));
        assertEquivalent(boolAccess);
        var notConstr = PirHelpers.wrapDecode(app(builtin(DefaultFun.IndexArray), constant(boolArray), constant(Constant.integer(1))), new PirType.BoolType());
        assertInstanceOf(PirTerm.App.class, lower(notConstr, OptimizationLevel.PV11_SAFE));
        assertEquivalent(notConstr);

        // Positions: the call's location moves to the literal.
        var positions = new IdentityHashMap<PirTerm, SourceLocation>();
        var at = new SourceLocation("Arr.java", 4, 2, "length");
        var length = app(builtin(DefaultFun.LengthOfArray), constant(array));
        positions.put(length, at);
        var located = new ArrayLiteralFoldPass(context(OptimizationLevel.PV11_SAFE), positions).lower(length);
        assertEquals(new PirTerm.Const(Constant.integer(2)), located.term());
        assertEquals(at, located.positions().get(located.term()));

        // The objective in bits: a list decode of a produced element folds exactly when the list
        // constant is not longer than the decode of the Data constant; measured across sizes.
        for (int items = 0; items <= 12; items++) {
            var elems = new ArrayList<PlutusData>();
            for (int i = 0; i < items; i++) elems.add(PlutusData.integer(100 + i));
            var element = PlutusData.list(elems.toArray(PlutusData[]::new));
            var holder = new Constant.ArrayConst(DATA_UNI, List.of(Constant.data(element)));
            var access = app(builtin(DefaultFun.UnListData), app(builtin(DefaultFun.IndexArray), constant(holder), constant(Constant.integer(0))));
            var listConst = new Constant.ListConst(DATA_UNI, elems.stream().map(Constant::data).toList());
            boolean folds = bits(Term.const_(listConst)) <= bits(Term.apply(Term.builtin(DefaultFun.UnListData), Term.const_(Constant.data(element))));
            assertEquals(folds, lower(access, OptimizationLevel.PV11_SAFE) instanceof PirTerm.Const, "unListData/" + items);
            System.out.println("ARRAY_LITERAL_OBJECTIVE items=" + items + " unListData=" + folds);
        }
        // An array literal is always shorter than the list chain it replaces: the chain holds the
        // same constants plus a wrapping and a cons per element.
        for (int n = 0; n <= 8; n++) {
            PirTerm chain = nil();
            var values = new ArrayList<Constant>();
            for (int i = n - 1; i >= 0; i--) { chain = cons(intElement(1000 + i), chain); values.add(0, Constant.data(PlutusData.integer(1000 + i))); }
            var literal = new Constant.ArrayConst(DATA_UNI, values);
            assertTrue(bits(Term.const_(literal)) < bits(program(app(builtin(DefaultFun.ListToArray), chain))), "n=" + n);
            assertEquals(new PirTerm.Const(literal), lower(app(builtin(DefaultFun.ListToArray), chain), OptimizationLevel.PV11_SAFE), "n=" + n);
        }
    }

    /**
     * `var` infers the element type of an array literal from its elements, as javac does: the
     * access decodes and the value is usable as its Java type, on every level and through a
     * chained access. Elements of different types (javac would infer a common supertype the
     * subset cannot represent) are rejected with JULC0012; the explicit declaration and the
     * empty literal are unchanged. The first version typed the elements as Data, so
     * `increment(a.get(0))` compiled and failed at runtime (the maintainer's review).
     */
    @Test
    void varLocalOfAnArrayLiteralInfersTheElementType() {
        var source = O10ArrayLiteralFixtures.IMPORTS + """
                class VarArray {
                    static BigInteger increment(BigInteger x) {
                        return x.add(BigInteger.ONE);
                    }
                    static BigInteger inferred() {
                        var a = JulcArray.of(BigInteger.valueOf(7));
                        return increment(a.get(0));
                    }
                    static boolean strings() {
                        var s = JulcArray.of("ab", "cde");
                        return s.get(1).equals("cde");
                    }
                    static boolean bools() {
                        var f = JulcArray.of(true, false);
                        return f.get(0) && !f.get(1);
                    }
                    static BigInteger nested() {
                        var rows = JulcArray.of(JulcList.of(BigInteger.ONE, BigInteger.TWO), JulcList.of(BigInteger.valueOf(3)));
                        return rows.get(1).get(0);
                    }
                    static BigInteger typed() {
                        JulcArray<BigInteger> t = JulcArray.of(BigInteger.valueOf(7));
                        return t.get(0);
                    }
                    static BigInteger chained(BigInteger x) {
                        return JulcArray.of(x, BigInteger.ONE).get(0).add(BigInteger.ONE);
                    }
                    static BigInteger emptyLength() {
                        var e = JulcArray.of();
                        return BigInteger.valueOf(e.length());
                    }
                }
                """;
        // compileMethod compiles the whole class, so the rejected shape lives in its own class.
        var mixedSource = O10ArrayLiteralFixtures.IMPORTS + """
                class MixedArray {
                    static PlutusData mixed() {
                        var m = JulcArray.of(BigInteger.ONE, new byte[]{1});
                        return m.get(0);
                    }
                }
                """;
        for (var level : OptimizationLevel.values()) {
            assertEquals(Term.const_(Constant.integer(8)), result(source, "inferred", level, List.of()), level.toString());
            assertEquals(Term.const_(Constant.bool(true)), result(source, "strings", level, List.of()), level.toString());
            assertEquals(Term.const_(Constant.bool(true)), result(source, "bools", level, List.of()), level.toString());
            assertEquals(Term.const_(Constant.integer(3)), result(source, "nested", level, List.of()), level.toString());
            assertEquals(Term.const_(Constant.integer(7)), result(source, "typed", level, List.of()), level.toString());
            assertEquals(Term.const_(Constant.integer(6)), result(source, "chained", level, List.of(PlutusData.integer(5))), level.toString());
            assertEquals(Term.const_(Constant.integer(0)), result(source, "emptyLength", level, List.of()), level.toString());
        }
        var mixed = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationCostProfile(PROFILE)).compileMethod(mixedSource, "mixed"));
        assertEquals("JULC0012", mixed.diagnostics().getFirst().code());
        assertTrue(mixed.getMessage().contains("JulcArray<T>"), mixed.getMessage());
    }

    private static Term result(String source, String method, OptimizationLevel level, List<PlutusData> args) {
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level).setOptimizationCostProfile(PROFILE)).compileMethod(source, method);
        assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
        return assertInstanceOf(EvalResult.Success.class, evaluate(compiled.program(), args, "Java")).resultTerm();
    }

    /** The producer's requirement is the PV11 builtin under both spellings of the class name. */
    @Test
    void arrayLiteralRequiresListToArrayUnderBothClassNames() {
        var registry = StdlibRegistry.defaultRegistry();
        for (var name : List.of("JulcArray", "org.julclang.core.types.JulcArray")) {
            assertEquals(Set.of(DefaultFun.ListToArray), registry.requirements(name, "of").builtins(), name);
            assertEquals(Set.of(DefaultFun.ListToArray), registry.requirements(name, "fromList").builtins(), name);
        }
        assertTrue(registry.requirements("org.julclang.core.types.JulcArray", "nothing").isEmpty());
    }

    /** The target check runs before any lowering: a pre-PV11 target fails closed with JULC0031. */
    @Test
    void nonPv11TargetFailsClosedBeforeLowering() {
        var pv10 = new CompilerTarget(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), UplcVersion.V1_1_0);
        for (var fixture : List.of(FIXTURES.get(0), FIXTURES.get(1))) {
            var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setTarget(pv10)).compileMethod(fixture.source(), fixture.method()));
            assertEquals("JULC0031", error.diagnostics().getFirst().code(), fixture.name());
        }
    }

    // --- helpers ---

    /** Whether a UPLC term mentions the builtin anywhere. */
    private static boolean mentions(Term term, DefaultFun fun) {
        return switch (term) {
            case Term.Builtin b -> b.fun() == fun;
            case Term.Apply a -> mentions(a.function(), fun) || mentions(a.argument(), fun);
            case Term.Lam l -> mentions(l.body(), fun);
            case Term.Force f -> mentions(f.term(), fun);
            case Term.Delay d -> mentions(d.term(), fun);
            case Term.Constr c -> c.fields().stream().anyMatch(f -> mentions(f, fun));
            case Term.Case c -> mentions(c.scrutinee(), fun) || c.branches().stream().anyMatch(b -> mentions(b, fun));
            default -> false;
        };
    }

    private static int bits(Term term) {
        var writer = new FlatWriter();
        new UplcFlatEncoder(writer).writeTerm(term);
        return writer.bitLength();
    }

    /** The UPLC of a PIR term at BASELINE, for size comparisons. */
    private static Term program(PirTerm term) {
        return new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE)).compilePirToProgram(term).term();
    }

    static PirTerm builtin(DefaultFun fun) { return new PirTerm.Builtin(fun); }
    static PirTerm constant(Constant value) { return new PirTerm.Const(value); }
    static PirTerm nil() { return app(builtin(DefaultFun.MkNilData), constant(Constant.unit())); }
    static PirTerm cons(PirTerm head, PirTerm tail) { return app(builtin(DefaultFun.MkCons), head, tail); }
    static PirTerm intElement(long value) { return app(builtin(DefaultFun.IData), constant(Constant.integer(value))); }

    static PirTerm app(PirTerm function, PirTerm... args) {
        PirTerm term = function;
        for (var arg : args) term = new PirTerm.App(term, arg);
        return term;
    }

    private static boolean isBuiltin(PirTerm term, DefaultFun fun) {
        return term instanceof PirTerm.Builtin b && b.fun() == fun;
    }

    private static PirTerm lower(PirTerm term, OptimizationLevel level) {
        return lowerWith(context(level), term);
    }

    private static PirTerm lowerWith(CompilationContext context, PirTerm term) {
        return new ArrayLiteralFoldPass(context, Map.of()).lower(term).term();
    }

    private static CompilationContext context(OptimizationLevel level, String... disabledRules) {
        var options = new CompilerOptions().setOptimizationLevel(level).setOptimizationCostProfile(PROFILE);
        for (var rule : disabledRules) options.disableOptimizationRule(rule);
        return CompilationContext.resolve(options);
    }

    /** BASELINE (never folded) and safe-profile programs of a closed term agree on every backend. */
    private static void assertEquivalent(PirTerm term) {
        var baseline = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE)).compilePirToProgram(term);
        var safe = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE)).compilePirToProgram(term);
        EvalResult javaResult = null;
        for (String provider : PROVIDERS) {
            var before = evaluate(baseline, List.of(), provider);
            var after = evaluate(safe, List.of(), provider);
            assertEquals(before.getClass(), after.getClass(), provider + " " + before + " vs " + after);
            assertEquals(before.traces(), after.traces(), provider);
            if (before instanceof EvalResult.Success b) assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), provider);
            if (before instanceof EvalResult.Failure b) assertEquals(b.error(), ((EvalResult.Failure) after).error(), provider);
            if (provider.equals("Java")) javaResult = after;
            if (!provider.equals("Java")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), provider);
        }
    }

    /** Array builtin call sites in user code: outermost application spines headed by one of the three builtins. */
    static int countCalls(PirTerm term) {
        int[] count = {0};
        walkUser(term, false, (t, inFunctionPosition) -> {
            if (t instanceof PirTerm.App && !inFunctionPosition) {
                PirTerm head = t;
                while (head instanceof PirTerm.App a) head = a.function();
                if (head instanceof PirTerm.Builtin b && ARRAY_BUILTINS.contains(b.fun())) count[0]++;
            }
        });
        return count[0];
    }

    private interface Visit { void accept(PirTerm term, boolean inFunctionPosition); }

    private static void walkUser(PirTerm term, boolean inFunctionPosition, Visit visit) {
        visit.accept(term, inFunctionPosition);
        switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> { }
            case PirTerm.Lam l -> walkUser(l.body(), false, visit);
            case PirTerm.Let l -> {
                if (!l.name().contains(".")) walkUser(l.value(), false, visit);
                walkUser(l.body(), false, visit);
            }
            case PirTerm.LetRec r -> {
                r.bindings().forEach(b -> { if (!b.name().contains(".")) walkUser(b.value(), false, visit); });
                walkUser(r.body(), false, visit);
            }
            case PirTerm.App a -> { walkUser(a.function(), true, visit); walkUser(a.argument(), false, visit); }
            case PirTerm.IfThenElse i -> { walkUser(i.cond(), false, visit); walkUser(i.thenBranch(), false, visit); walkUser(i.elseBranch(), false, visit); }
            case PirTerm.Trace t -> { walkUser(t.message(), false, visit); walkUser(t.body(), false, visit); }
            case PirTerm.DataConstr c -> c.fields().forEach(f -> walkUser(f, false, visit));
            case PirTerm.DataMatch m -> { walkUser(m.scrutinee(), false, visit); m.branches().forEach(b -> walkUser(b.body(), false, visit)); }
            case PirTerm.ListMatch m -> { walkUser(m.scrutinee(), false, visit); walkUser(m.nilBranch(), false, visit); walkUser(m.consBranch(), false, visit); }
            case PirTerm.PairMatch m -> { walkUser(m.scrutinee(), false, visit); walkUser(m.body(), false, visit); }
            case PirTerm.IntegerCase c -> { walkUser(c.scrutinee(), false, visit); c.branches().forEach(b -> walkUser(b, false, visit)); }
        }
    }

    static CompileResult compile(O10ArrayLiteralFixtures.Fixture fixture, OptimizationLevel level, boolean maps, String... disabledRules) {
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(maps).setOptimizationCostProfile(PROFILE);
        for (var rule : disabledRules) options.disableOptimizationRule(rule);
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(fixture.source(), fixture.method());
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
