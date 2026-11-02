package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lambdas at the list methods that apply them: each program compiles to the reference's bytes at every level, with and
 * without a source map, and a rejection is the reference's. Evaluation against the JVM, and what differs from the reference
 * on purpose, is {@code Adr068LambdasHarnessTest}'s; this is I1 and I2a on one screen.
 */
class JavacLambdasTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import java.util.Optional;
            import java.util.function.Function;
            import java.util.function.Predicate;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.core.types.Tuple2;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.TokenName;
            import org.julclang.ledger.TxInfo;
            import org.julclang.stdlib.Builtins;
            """;

    private static String source(String members) {
        return IMPORTS + "class C {\n    " + members + "\n}\n";
    }

    private static void same(String members) {
        LoweringFixture.assertSameMethodAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    private static void rejected(String members) {
        LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    private static CompilerException onJavac(String members) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
        return assertThrows(CompilerException.class, () -> compiler.compileMethod(source(members), "m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(JulcList<BigInteger> xs, BigInteger k) { return xs.any(x -> x.equals(k)); }",
            "static boolean m(JulcList<BigInteger> xs, BigInteger k) { return xs.all(x -> x.compareTo(k) > 0); }",
            "static long m(JulcList<BigInteger> xs) { return xs.filter(x -> x.signum() > 0).size(); }",
            "static JulcList<BigInteger> m(JulcList<BigInteger> xs, BigInteger k) { return xs.map(x -> x.add(k)); }",
            "static boolean m(JulcList<BigInteger> xs) { return PlutusData.cast(xs.find(x -> x.signum() > 0), Optional.class).isPresent(); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> true); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(y -> y.signum() > 0); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any((y) -> y.signum() > 0); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.tail().any(x -> x.signum() > 0); }",
            "static boolean m(JulcMap<BigInteger, BigInteger> a) { return a.keys().any(x -> x.signum() > 0); }",
            "static boolean m(JulcMap<BigInteger, BigInteger> a) { return a.values().all(x -> x.signum() > 0); }",
    })
    void theFiveMethods(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(JulcList<String> xs) { return xs.any(s -> s.equals(\"a\")); }",
            "static JulcList<String> m(JulcList<String> xs) { return xs.map(s -> s + \"!\"); }",
            "static boolean m(JulcList<byte[]> xs, byte[] k) { return xs.any(p -> Builtins.equalsByteString(p, k)); }",
            "static JulcList<byte[]> m(JulcList<byte[]> xs) { return xs.map(p -> p); }",
            "static JulcList<Boolean> m(JulcList<Boolean> xs) { return xs.map(b -> !b); }",
            "static boolean m(JulcList<Boolean> xs) { return xs.any(b -> b); }",
            "static boolean m(JulcList<PlutusData> xs, PlutusData k) { return xs.any(y -> Builtins.equalsData(y, k)); }",
            "static JulcList<PlutusData> m(JulcList<PlutusData> xs) { return xs.map(y -> Builtins.iData(Builtins.unIData(y).add(BigInteger.ONE))); }",
            "static JulcList<PlutusData> m(JulcList<PlutusData> xs) { return xs.map((PlutusData y) -> Builtins.iData(Builtins.unIData(y).add(BigInteger.ONE))); }",
            "static JulcList<PlutusData> m(JulcList<PlutusData> xs) { return xs.filter((PlutusData y) -> Builtins.unIData(y).compareTo(BigInteger.TWO) > 0); }",
            "static JulcList<PlutusData> m(JulcList<PlutusData> xs) { return xs.map(x -> Builtins.mkNilData()); }",
            "static boolean m(JulcList<PubKeyHash> xs, byte[] k) { return xs.any(p -> Builtins.equalsByteString(p.hash(), k)); }",
            "static boolean m(JulcList<PubKeyHash> xs, PubKeyHash k) { return xs.any(p -> p.equals(k)); }",
            "static JulcList<byte[]> m(JulcList<PubKeyHash> xs) { return xs.map(p -> p.hash()); }",
            "static boolean m(TxInfo i, PubKeyHash k) { return i.signatories().any(s -> s.equals(k)); }",
            "static boolean m(JulcList<TokenName> xs, byte[] k) { return xs.any(t -> Builtins.equalsByteString(t.name(), k)); }",
            "static boolean m(JulcList<PubKeyHash> xs, JulcList<byte[]> ys) { return xs.any(p -> ys.any(q -> Builtins.equalsByteString(p.hash(), q))); }",
            "record R(BigInteger a, BigInteger b) { } static JulcList<BigInteger> m(JulcList<R> xs) { return xs.map(r -> r.a()); }",
            "record R(BigInteger a, BigInteger b) { } static JulcList<R> m(JulcList<R> xs) { return xs.map(r -> new R(r.b(), r.a())); }",
            "record R(BigInteger a, BigInteger b) { } static JulcList<R> m(JulcList<BigInteger> xs, BigInteger k) { return xs.map(x -> new R(x, k)); }",
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } "
                    + "static JulcList<BigInteger> m(JulcList<S> xs) { return xs.map(s -> { if (s instanceof S.A a) { return a.v(); } return BigInteger.ZERO; }); }",
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } "
                    + "static JulcList<S> m(JulcList<BigInteger> xs) { return xs.map(x -> new S.A(x)); }",
            "static JulcList<Optional<BigInteger>> m(JulcList<BigInteger> xs) { return xs.map(x -> Optional.of(x)); }",
            "static boolean m(JulcList<Optional<BigInteger>> xs) { return xs.any(o -> o.isPresent()); }",
            "static JulcList<BigInteger> m(JulcList<Tuple2<BigInteger, BigInteger>> xs) { return xs.map(t -> t.second()); }",
            "static JulcList<Tuple2<BigInteger, BigInteger>> m(JulcList<BigInteger> xs) { return xs.map(x -> new Tuple2<>(x, x)); }",
    })
    void eachElementType(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map(x -> { var y = x.add(x); return y.multiply(y); }); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.all(x -> { if (x.signum() > 0) { return true; } return false; }); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { long a = 1; if (x.signum() > 0) { return a > 0; } else { return a < 0; } }); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { if (x.signum() > 0) { Builtins.trace(\"a\", Builtins.iData(x)); } return true; }); }",
            "static JulcList<BigInteger> m(JulcList<BigInteger> xs, BigInteger k) { var j = k.add(k); return xs.map(x -> x.add(j)); }",
            "static final BigInteger K = BigInteger.TEN; static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map(x -> x.add(K)); }",
            "static final BigInteger K = BigInteger.TEN; static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map(K -> K.add(K)); }",
            "static final BigInteger K = BigInteger.TEN; static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map(x -> { var K = x.add(x); return K; }); }",
            "static BigInteger x() { return BigInteger.ONE; } static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map(x -> x.add(x())); }",
            "static boolean h(BigInteger x, BigInteger k) { return x.equals(k); } static boolean m(JulcList<BigInteger> xs, BigInteger k) { return xs.any(x -> h(x, k)); }",
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } "
                    + "static JulcList<BigInteger> m(JulcList<BigInteger> xs, S s) { if (s instanceof S.A a) { return xs.map(x -> x.add(a.v())); } return xs; }",
            "static boolean m(JulcList<BigInteger> xs, BigInteger k) { return xs.any(x -> xs.all(y -> y.compareTo(x.add(k)) < 0)); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(a -> xs.any(b -> xs.any(c -> a.add(b).equals(c)))); }",
            "static JulcList<JulcList<BigInteger>> m(JulcList<BigInteger> xs) { return xs.map(x -> xs.filter(y -> y.equals(x))); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> x.signum() > 0) && xs.all(x -> x.signum() > 0); }",
            "static long m(JulcList<BigInteger> xs) { if (xs.any(x -> x.signum() < 0)) { return 1; } return xs.filter(y -> y.signum() > 0).size(); }",
            "static long m(JulcList<BigInteger> xs) { boolean a = xs.any(y -> y.signum() > 0); long y = 3; if (a) { return y; } return y + 1; }",
            "static long m(JulcList<BigInteger> xs) { var ys = xs.filter(x -> x.signum() > 0); return ys.size(); }",
            "static long m(JulcList<BigInteger> xs) { return xs.filter(x -> x.signum() > 0).map(y -> y.add(y)).size(); }",
            "static boolean m(JulcList<BigInteger> xs) { xs.any(x -> x.signum() > 0); return true; }",
    })
    void bodiesCapturesAndNames(String members) {
        same(members);
    }

    /** A {@code @Param} read in a lambda, in a validator. */
    @Test
    void aValidatorWithAParameterInTheLambda() {
        LoweringFixture.assertSameAsReference(IMPORTS + """
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.Param;
                import org.julclang.stdlib.annotation.SpendingValidator;
                @SpendingValidator
                class V {
                    @Param static BigInteger limit;
                    @Entrypoint
                    static boolean validate(JulcList<BigInteger> xs, ScriptContext ctx) {
                        return xs.any(x -> x.compareTo(limit) > 0);
                    }
                }
                """);
    }

    /**
     * With a source map, an entry point's boolean {@code return} fails rather than returning false. A lambda's is no
     * {@code return} of the method, but the reference's flag stays on inside it: a lambda that returns false fails the script
     * there and only there, and one that returns anything else is applied as a boolean. So this program accepts, as Java does,
     * with no map on either frontend and with one on this frontend alone.
     */
    @Test
    void aLambdasReturnIsNoReturnOfTheEntryPoint() {
        String validator = IMPORTS + """
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData datum, PlutusData redeemer, PlutusData ctx) {
                        JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE, BigInteger.TWO);
                        return xs.filter(x -> { return false; }).isEmpty();
                    }
                }
                """;
        assertTrue(accepts(validator, CompilerFrontend.JAVAC, true));
        assertTrue(accepts(validator, CompilerFrontend.JAVAC, false));
        assertTrue(accepts(validator, CompilerFrontend.JAVAPARSER, false));
        assertFalse(accepts(validator, CompilerFrontend.JAVAPARSER, true), "the reference with a map: on the Closed by M5 list");
    }

    private static boolean accepts(String validator, CompilerFrontend frontend, boolean sourceMap) {
        var options = new CompilerOptions().setFrontend(frontend).setSourceMapEnabled(sourceMap);
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compile(validator);
        var ref = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
        var context = PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(0),
                PlutusData.constr(1, ref, PlutusData.constr(0, PlutusData.integer(1))));
        return CompilerTestVm.pv11("Java").evaluateWithArgs(result.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), List.of(context),
                new ExBudget(1_000_000_000L, 5_000_000L), EvalOptions.DEFAULT) instanceof EvalResult.Success;
    }

    // --- Rejections ---

    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean h(BigInteger x) { return true; } static boolean m(JulcList<BigInteger> xs) { return xs.any(C::h); }",
            "static boolean h(BigInteger x) { return true; } static boolean m(JulcList<BigInteger> xs) { return xs.filter(C::h).isEmpty(); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(BigInteger.ONE::equals); }",
            "static long m(BigInteger a) { Function<BigInteger, BigInteger> f = x -> x; return f.apply(a).longValue(); }",
            "static boolean m(BigInteger a) { Predicate<BigInteger> p = x -> true; return p.test(a); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> x.bitLength() > 0); }",
            "static BigInteger m(JulcList<BigInteger> xs) { return xs.find(x -> true).add(BigInteger.ONE); }",
            "static BigInteger m(JulcList<BigInteger> xs) { var r = xs.find(x -> true); return r.add(BigInteger.ONE); }",
            "record R(BigInteger a) { } static BigInteger m(JulcList<R> xs) { return xs.find(r -> true).a(); }",
            "static BigInteger m(JulcList<BigInteger> xs) { return xs.map(x -> x).head().add(BigInteger.ONE); }",
            "static boolean m(JulcList<BigInteger> xs, BigInteger k) { return xs.map(x -> x).any(y -> y.compareTo(k) > 0); }",
            "static boolean m(BigInteger a) { var xs = JulcList.of(a); return xs.any(x -> x.signum() > 0); }",
            "static BigInteger m(BigInteger a, BigInteger k) { var y = JulcList.of(a).head(); if (y.compareTo(k) > 0) { return y; } return k; }",
            "static long m(JulcList<BigInteger> xs) { return xs.any(x -> { int x2 = 1, y = 2; return true; }) ? 1 : 0; }",
            "static long m(JulcList<BigInteger> xs) { var ys = xs.map(x -> JulcArray.of(x)); return ys.size(); }",
            "static long m(JulcList<BigInteger> xs) { var ys = xs.map(x -> { return JulcArray.of(x); }); return ys.size(); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { l: if (true) { return true; } return false; }); }",
    })
    void rejectionsAreTheReferences(String members) {
        rejected(members);
    }

    @Test
    void aLambdaThatHoldsWhatNothingLowersStops() {
        assertEquals("WHILE_LOOP", ((NotPorted) onJavac("static boolean m(JulcList<BigInteger> xs) { "
                + "return xs.any(x -> { var y = x; while (y.signum() > 0) { y = y.subtract(BigInteger.ONE); } return true; }); }")).construct());
    }

    /** {@code JULC0087} reads a body as it reads a method. */
    @Test
    void theSubsetsRulesReadABody() {
        assertEquals("JULC0087", onJavac("static boolean m(JulcList<byte[]> xs, byte[] k) { return xs.any(x -> x.equals(k)); }")
                .diagnostics().getFirst().code());
        assertEquals("JULC0086", onJavac("static boolean m(JulcList<BigInteger> xs, BigInteger k) { return xs.any(x -> x == k); }")
                .diagnostics().getFirst().code());
        assertEquals("JULC0061", onJavac("static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { var y = x; y = x; return true; }); }")
                .diagnostics().getFirst().code());
    }

    /** {@code var f = x -> x} and an undeclared {@code ListsLib} higher-order method are javac's to reject (D6). */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m() { var f = x -> x; return 1; }",
            "static boolean m(JulcList<BigInteger> xs) { return org.julclang.stdlib.lib.ListsLib.any(xs, x -> true); }",
            "static BigInteger m(JulcList<BigInteger> xs) { return org.julclang.stdlib.lib.ListsLib.foldl((a, b) -> a, BigInteger.ZERO, xs); }",
    })
    void javacRejectsWhatD6Removed(String members) {
        var failure = onJavac(members);
        assertEquals("JULC0085", failure.diagnostics().getFirst().code(), failure::getMessage);
    }

    /** The rule names the interface's method: any other call of a functional local is a rejection of the local's type. */
    @Test
    void anotherMethodOfAFunctionalLocalIsRejectedAtItsType() {
        var failure = onJavac("static boolean m(BigInteger a) { Function<BigInteger, BigInteger> f = x -> x; var g = f.andThen(f); return true; }");
        assertTrue(failure.getMessage().contains("Unknown type: Function"), failure::getMessage);
    }

    /** Only a local goes through the reference's rule on a call: a parameter is rejected at its type. */
    @Test
    void aParameterOfAFunctionalTypeIsRejectedAtItsType() {
        var failure = onJavac("static boolean m(Predicate<BigInteger> p, BigInteger a) { return p.test(a); }");
        assertTrue(failure.getMessage().contains("Unknown type: Predicate"), failure::getMessage);
    }

    /** What the reference types by what is written and javac by the element: a member the receiver's reading cannot find lowers. */
    @Test
    void aWrittenTypeIsWhatTheBodySees() {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
        assertFalse(compiler.compileMethod(source("static boolean m(BigInteger a) { var xs = JulcList.of(a); return xs.any((BigInteger x) -> x.signum() > 0); }"), "m")
                .hasErrors());
    }

    /** The type of a local that holds a function is no type of the subset: the reference's rejection, with javac's name. */
    @Test
    void aLocalOfAFunctionalTypeIsRejectedAtItsType() {
        var failure = onJavac("static boolean m(BigInteger a) { Predicate<BigInteger> p = x -> true; return true; }");
        assertFalse(failure instanceof NotPorted, failure::getMessage);
        assertTrue(failure.getMessage().contains("Unknown type: Predicate"), failure::getMessage);
    }
}
