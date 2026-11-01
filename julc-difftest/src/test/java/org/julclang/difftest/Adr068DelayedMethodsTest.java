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

    /** The family of T13: these handlers duplicate their receiver, whatever it is, so a delayed read there runs twice. */
    @ParameterizedTest
    @ValueSource(strings = {"k().signum()", "k().compareTo(a)"})
    void anOperandTheHandlerDuplicatesRunsTwice(String body) {
        assertBothDivergeAlike("static BigInteger k() { return said(\"K\", BigInteger.TEN); } public static long m(BigInteger a) { return " + body + "; }", integer(1));
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
