package org.julclang.difftest;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.julclang.difftest.Adr068CallsHarnessTest.assertAgree;
import static org.julclang.difftest.Adr068CallsHarnessTest.assertBothDivergeAlike;
import static org.julclang.difftest.Adr068CallsHarnessTest.bool;
import static org.julclang.difftest.Adr068CallsHarnessTest.integer;
import static org.julclang.difftest.Adr068CallsHarnessTest.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 B4: a method with no parameters whose body may fail runs at each call, and only if it is called, as in Java. The JVM
 * is the model, on both frontends at all four levels.
 */
class Adr068DelayedMethodsTest {

    private static final String FAILS = "static long k() { return fail(BigInteger.ONE).longValue(); } ";
    private static final String TRACES = "static long k() { return saidL(\"K\", 1); } ";

    @Test
    void aFailingBodyUnderABranchNotTaken() {
        assertAgree(FAILS + "public static long m(boolean c) { return c ? k() : 2; }", bool(false));
        assertAgree(FAILS + "public static long m(boolean c) { return c ? k() : 2; }", bool(true));
        assertAgree(FAILS + "public static long m(boolean c) { if (c) { return k(); } return 2; }", bool(false));
        assertAgree(FAILS + "public static boolean m(boolean c) { return c && k() > 0; }", bool(false));
        assertAgree(FAILS + "public static boolean m(boolean c) { return c || k() > 0; }", bool(true));
    }

    @Test
    void aFailingBodyThatIsNeverCalled() {
        assertAgree(FAILS + "public static long m() { return 2; }");
    }

    @Test
    void calledTwice() {
        assertAgree(TRACES + "public static long m() { return k() + k(); }");
        assertAgree(TRACES + "public static long m(long a) { long x = k(); return x + x + a; }", integer(1));
        assertAgree(TRACES + "static long go(long n) { return n <= 0 ? 0 : k() + go(n - 1); } public static long m(long a) { return go(a); }", integer(3));
    }

    @Test
    void calledFromAnotherMethodWithNoParameters() {
        assertAgree(TRACES + "static long j() { return k() + 1; } public static long m(boolean c) { return c ? j() + j() : 2; }", bool(true));
        assertAgree(TRACES + "static long j() { return k() + 1; } public static long m(boolean c) { return c ? j() + j() : 2; }", bool(false));
        assertAgree(FAILS + "static long j() { return k() + 1; } static long i() { return j(); } public static long m(boolean c) { return c ? i() : 2; }", bool(false));
        assertAgree("static long j() { return k() + 1; } " + FAILS + "public static long m(boolean c) { return c ? j() : 2; }", bool(false));
    }

    @Test
    void aTraceRunsWhereJavaRunsIt() {
        assertAgree(TRACES + "public static long m(long a) { return saidL(\"L\", a) + k() + saidL(\"R\", a); }", integer(1));
        assertAgree(TRACES + "static long second(long a, long b) { return b; } public static long m(long a) { return second(saidL(\"L\", a), k()); }", integer(1));
    }

    @Test
    void aMethodThatReadsItselfRunsOnlyIfCalled() {
        assertAgree("static long k() { return k() + 1; } public static long m(boolean c) { return c ? k() : 2; }", bool(false));
        assertAgree("static long k() { return j(); } static long j() { return k(); } public static long m(boolean c) { return c ? k() : 2; }", bool(false));
    }

    @Test
    void aTotalBodyStaysShared() {
        assertAgree("static long k() { return 3; } public static long m() { return k() + k(); }");
        assertAgree("static byte[] k() { return Builtins.appendByteString(new byte[]{1}, new byte[]{2}); } public static long m() { return Builtins.lengthOfByteString(k()); }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"take", "drop"})
    void aCountRunsOnceAndInOrder(String method) {
        var list = list(integer(1), integer(2));
        assertAgree(TRACES + "public static long m(JulcList<BigInteger> a) { return a." + method + "(k()).size(); }", list);
        assertAgree(FAILS + "public static long m(JulcList<BigInteger> a) { return a." + method + "(k()).size(); }", list);
    }

    @Test
    void aModulusRunsOnceAndInOrder() {
        assertAgree("static BigInteger k() { return said(\"K\", BigInteger.TEN); } public static BigInteger m(BigInteger a) { return a.mod(k()); }", integer(7));
        assertAgree("static BigInteger k() { return said(\"K\", BigInteger.TEN); } public static BigInteger m(BigInteger a) { return said(\"R\", a).mod(k()); }", integer(7));
        assertAgree("static BigInteger k() { return said(\"K\", BigInteger.TEN); } public static BigInteger m(BigInteger a) { return k().mod(said(\"A\", a)); }", integer(7));
        assertAgree("static BigInteger k() { return said(\"K\", BigInteger.TEN); } static BigInteger j() { return said(\"J\", BigInteger.TWO); }"
                + " public static BigInteger m() { return k().mod(j()); }");
    }

    @Test
    void aReceiverRunsOnceAndInOrder() {
        assertAgree("static JulcList<BigInteger> k() { return JulcList.of(said(\"K\", BigInteger.ONE)); } public static long m(long n) { return k().take(saidL(\"N\", n)).size(); }", integer(1));
        assertAgree("static JulcList<BigInteger> k() { return JulcList.of(said(\"K\", BigInteger.ONE)); } public static long m(long n) { return k().drop(saidL(\"N\", n)).size(); }", integer(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {">", ">="})
    void aComparisonRunsEachOperandOnceAndInOrder(String op) {
        assertAgree(TRACES + "public static boolean m(long a) { return k() " + op + " saidL(\"R\", a); }", integer(1));
        assertAgree(TRACES + "public static boolean m(long a) { return saidL(\"L\", a) " + op + " k(); }", integer(1));
        assertAgree(TRACES + "static long j() { return saidL(\"J\", 2); } public static boolean m() { return k() " + op + " j(); }");
        assertAgree("static BigInteger k() { return said(\"K\", BigInteger.TEN); } public static boolean m(BigInteger a) { return k().compareTo(said(\"R\", a)) " + op + " 0; }", integer(1));
    }

    /** {@code compareTo} reads its argument once, outside the {@code OP 0} form too. */
    @Test
    void aComparedArgumentRunsOnce() {
        assertAgree("static BigInteger k() { return said(\"K\", BigInteger.TEN); } public static long m(BigInteger a) { return a.compareTo(k()); }", integer(1));
    }

    private static final String TRACED = "static BigInteger k() { return said(\"K\", BigInteger.TEN); } static BigInteger j() { return said(\"J\", BigInteger.TWO); } ";

    /** T13's handlers read an operand twice, and a delayed read among their operands is bound once. */
    @ParameterizedTest
    @ValueSource(strings = {"k().abs().longValue()", "k().signum()", "k().compareTo(a)", "a.compareTo(k())", "k().min(a).longValue()",
            "k().max(a).longValue()", "a.min(k()).longValue()", "a.max(k()).longValue()", "k().max(j()).longValue()",
            "k().min(j()).longValue()", "k().compareTo(j())"})
    void aDelayedReadRunsOnceInAHandlerThatDuplicatesIt(String body) {
        assertAgree(TRACED + "public static long m(BigInteger a) { return " + body + "; }", integer(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Math.abs(q())", "Math.max(q(), a)", "Math.max(a, q())", "Math.min(q(), a)", "Math.min(a, q())",
            "Math.max(q(), r())", "Math.min(q(), r())"})
    void aDelayedReadRunsOnceInAMathDelegate(String body) {
        assertAgree("static long q() { return saidL(\"Q\", 3); } static long r() { return saidL(\"R\", -4); } "
                + "public static long m(long a) { return " + body + "; }", integer(1));
    }

    /** The argument is bound, and the receiver runs ahead of it, as in Java: it is bound too, so it runs once. */
    @ParameterizedTest
    @ValueSource(strings = {"said(\"R\", a).max(k()).longValue()", "said(\"R\", a).min(k()).longValue()", "said(\"R\", a).compareTo(k())"})
    void aReceiverWithAnEffectRunsAheadOfABoundArgument(String body) {
        assertAgree(TRACED + "public static long m(BigInteger a) { return " + body + "; }", integer(1));
    }

    /** No delayed read among the operands: T13's documented behaviour, which this does not touch. */
    @ParameterizedTest
    @ValueSource(strings = {"said(\"R\", a).abs().longValue()", "said(\"R\", a).signum()", "said(\"R\", a).compareTo(b)", "said(\"R\", a).max(b).longValue()",
            "b.max(said(\"A\", a)).longValue()"})
    void anOperandThatIsNoDelayedReadRunsAsItDid(String body) {
        assertBothDivergeAlike("public static long m(BigInteger a, BigInteger b) { return " + body + "; }", integer(3), integer(1));
    }

    private static String chain(int depth, String first, String link) {
        var methods = new StringBuilder("static BigInteger k0() { return " + first + "; } ");
        for (int i = 1; i <= depth; i++) {
            methods.append("static BigInteger k").append(i).append("() { return ").append(link.formatted("k" + (i - 1) + "()")).append("; } ");
        }
        return methods + "public static BigInteger m(BigInteger a) { return k" + depth + "(); }";
    }

    /** A level of a chain reads the level below once, so the chain is linear: at this depth a doubling is past any budget. */
    @ParameterizedTest
    @ValueSource(strings = {"%s.abs()", "%s.max(BigInteger.ONE)", "%s.min(BigInteger.ONE)", "BigInteger.ONE.max(%s)",
            "BigInteger.valueOf(%s.signum())", "BigInteger.valueOf(%s.compareTo(BigInteger.ONE))", "BigInteger.valueOf(BigInteger.ONE.compareTo(%s))"})
    void aChainOfDelayedMethodsIsLinear(String link) {
        assertAgree(chain(24, "Builtins.unIData(Builtins.iData(BigInteger.ONE))", link), integer(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Math.abs(%s)", "Math.max(%s, 1L)", "Math.min(%s, 1L)", "Math.max(1L, %s)", "Math.min(1L, %s)"})
    void aChainThroughMathIsLinear(String link) {
        var methods = new StringBuilder("static long k0() { return Builtins.unIData(Builtins.iData(BigInteger.ONE)).longValue(); } ");
        for (int i = 1; i <= 24; i++) {
            methods.append("static long k").append(i).append("() { return ").append(link.formatted("k" + (i - 1) + "()")).append("; } ");
        }
        assertAgree(methods + "public static long m(long a) { return k24(); }", integer(1));
    }

    /** Whether a body stays shared is read once per function, and a body that overflows a byte string is delayed. */
    private static final String OVERFLOW = """
            static byte[] big() {
                byte[] h = Builtins.%s(Builtins.emptyByteString());
                byte[] a1 = Builtins.appendByteString(h, h); byte[] a2 = Builtins.appendByteString(a1, a1); byte[] a3 = Builtins.appendByteString(a2, a2);
                byte[] a4 = Builtins.appendByteString(a3, a3); byte[] a5 = Builtins.appendByteString(a4, a4); byte[] a6 = Builtins.appendByteString(a5, a5);
                byte[] a7 = Builtins.appendByteString(a6, a6); byte[] a8 = Builtins.appendByteString(a7, a7); byte[] a9 = Builtins.appendByteString(a8, a8);
                byte[] a10 = Builtins.appendByteString(a9, a9); byte[] a11 = Builtins.appendByteString(a10, a10); byte[] a12 = Builtins.appendByteString(a11, a11);
                byte[] a13 = Builtins.appendByteString(a12, a12);
                return a13;
            }
            """;

    @ParameterizedTest
    @ValueSource(strings = {"sha2_256", "sha3_256", "blake2b_256", "blake2b_224", "keccak_256", "ripemd_160"})
    void aBodyThatOverflowsABuiltinIsNotReadAsTotal(String hash) {
        assertAgree(OVERFLOW.formatted(hash) + "public static BigInteger m(BigInteger a) { return a; }", integer(5));
        assertAgree(OVERFLOW.formatted(hash) + "public static long m(boolean c) { return c ? big().length : 0; }", bool(false));
    }

    @Test
    void aTreeOfCallsCompiles() {
        var methods = new StringBuilder("static BigInteger f0(BigInteger x) { return x.add(BigInteger.ONE); } ");
        for (int i = 1; i <= 36; i++) {
            methods.append("static BigInteger f").append(i).append("(BigInteger x) { return f").append(i - 1).append("(f").append(i - 1).append("(x)); } ");
        }
        assertAgree(methods + "static BigInteger k() { return f36(BigInteger.ONE); } public static BigInteger m(boolean c) { return c ? k() : BigInteger.TWO; }", bool(false));
    }

    /** It runs, so what the script accepts and rejects is Java's. */
    @ParameterizedTest
    @ValueSource(strings = {"k().signum()", "k().compareTo(a)", "a.compareTo(k())", "k().abs().longValue()", "k().min(a).longValue()"})
    void anOperandTheHandlerDuplicatesFailsOnce(String body) {
        assertAgree("static BigInteger k() { return fail(BigInteger.ONE); } public static long m(BigInteger a) { return " + body + "; }", integer(1));
    }

    // --- A library unit stops on the javac frontend until library lowering ---

    private static final String LIBRARY = """
            package demo;

            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.OnchainLibrary;

            @OnchainLibrary
            public class Lib {
                static long k() { return Builtins.unIData(Builtins.error()).longValue(); }
                static long t() { return Builtins.unIData(Builtins.trace("T", Builtins.iData(1))).longValue(); }
                public static long f(boolean c) { return c ? k() : 2; }
                public static long g() { return t() + t(); }
            }
            """;

    private static final String ROOT = """
            import demo.Lib;

            public class Case {
                public static long f(boolean c) { return Lib.f(c); }
                public static long g() { return Lib.g(); }
            }
            """;

    private static JulcCompiler compiler(CompilerFrontend frontend, OptimizationLevel level) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(frontend).setOptimizationLevel(level)
                .setJavacGate(JavacGateMode.OFF));
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aMethodUsedOnlyInALibrary(OptimizationLevel level) {
        var vm = JulcVm.create("Java");
        var f = compiler(CompilerFrontend.JAVAPARSER, level).compileMethod(ROOT, "f", List.of(LIBRARY)).program();
        var notTaken = assertInstanceOf(EvalResult.Success.class, vm.evaluateWithArgs(f, List.<PlutusData>of(bool(false))));
        assertEquals(Term.const_(Constant.integer(2)), notTaken.resultTerm());
        assertInstanceOf(EvalResult.Failure.class, vm.evaluateWithArgs(f, List.<PlutusData>of(bool(true))));
        var g = compiler(CompilerFrontend.JAVAPARSER, level).compileMethod(ROOT, "g", List.of(LIBRARY)).program();
        assertEquals(List.of("T", "T"), assertInstanceOf(EvalResult.Success.class, vm.evaluateWithArgs(g, List.<PlutusData>of())).traces());
    }

    @Test
    void aLibraryStopsOnJavac() {
        var thrown = assertThrows(CompilerException.class,
                () -> compiler(CompilerFrontend.JAVAC, OptimizationLevel.PV11_SAFE).compileMethod(ROOT, "f", List.of(LIBRARY)));
        assertTrue(thrown.diagnostics().stream().anyMatch(d -> "JULC0084".equals(d.code())), thrown::getMessage);
    }
}
