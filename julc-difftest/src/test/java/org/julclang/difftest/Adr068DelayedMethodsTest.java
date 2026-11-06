package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;

import static org.julclang.difftest.Adr068CallsHarnessTest.assertAgree;
import static org.julclang.difftest.Adr068CallsHarnessTest.bool;
import static org.julclang.difftest.Adr068CallsHarnessTest.integer;
import static org.julclang.difftest.Adr068CallsHarnessTest.list;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    /** An operand built on a read is bound as the read is, so what it is built on runs once, and in order. */
    @ParameterizedTest
    @ValueSource(strings = {"k().abs().abs().longValue()", "k().negate().abs().longValue()", "k().add(a).abs().longValue()", "k().add(a).signum()",
            "k().add(a).compareTo(a)", "a.compareTo(k().add(a))", "a.max(k().add(a)).longValue()", "a.min(k().negate()).longValue()",
            "k().add(a).max(a).longValue()", "k().max(j()).max(k().min(j())).abs().longValue()", "k().add(j()).max(j().add(k())).longValue()",
            "said(\"R\", a).max(k().add(a)).longValue()", "said(\"R\", a).add(k()).abs().longValue()", "k().add(said(\"A\", a)).abs().longValue()",
            "said(\"R\", a).compareTo(k().negate())", "(a.signum() > 0 ? k() : j()).abs().longValue()"})
    void anOperandBuiltOnADelayedReadRunsOnce(String body) {
        assertAgree(TRACED + "public static long m(BigInteger a) { return " + body + "; }", integer(1));
        assertAgree(TRACED + "public static long m(BigInteger a) { return " + body + "; }", integer(-20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Math.abs(q() + 1L)", "Math.max(q() + 1L, a)", "Math.max(a, q() + 1L)", "Math.min(q() * 2L, r() + 1L)", "Math.abs(Math.abs(q()))",
            "Math.max(saidL(\"L\", a), q() + 1L)"})
    void anOperandBuiltOnADelayedReadRunsOnceInAMathDelegate(String body) {
        assertAgree("static long q() { return saidL(\"Q\", 3); } static long r() { return saidL(\"R\", -4); } "
                + "public static long m(long a) { return " + body + "; }", integer(1));
    }

    private static final String FAILING = "static BigInteger k() { return fail(BigInteger.ONE); } static BigInteger j() { return said(\"J\", BigInteger.TWO); } ";

    /** Binding such an operand moves no failure: it fails where it ran before, after what ran ahead of it. */
    @ParameterizedTest
    @ValueSource(strings = {"k().add(a).abs().longValue()", "k().negate().signum()", "a.max(k().negate()).longValue()", "a.compareTo(k().add(a))",
            "j().add(a).max(k().add(a)).longValue()", "said(\"R\", a).max(k().add(a)).longValue()", "said(\"R\", a).add(k()).abs().longValue()",
            "j().add(k()).abs().longValue()", "k().add(j()).abs().longValue()"})
    void aBuiltOperandFailsAsItRuns(String body) {
        assertAgree(FAILING + "public static long m(BigInteger a) { return " + body + "; }", integer(1));
    }

    /** ... and an operand not reached is not run: the binding sits where the handler's term does. */
    @ParameterizedTest
    @ValueSource(strings = {"c ? k().add(a).abs().longValue() : 2", "c ? 2 : a.max(k().negate()).longValue()", "c && k().negate().signum() > 0 ? 1 : 2",
            "!c || k().add(a).compareTo(a) > 0 ? 1 : 2", "a.max(c ? k().add(a) : a).longValue()"})
    void aBuiltOperandNotReachedIsNotRun(String body) {
        String method = FAILING + "public static long m(boolean c, BigInteger a) { return " + body + "; }";
        assertAgree(method, bool(false), integer(1));
        assertAgree(method, bool(true), integer(1));
    }

    /** No delayed read among the operands: such an operand is bound too, and runs once, in order (#146). */
    @ParameterizedTest
    @ValueSource(strings = {"said(\"R\", a).abs().longValue()", "said(\"R\", a).signum()", "said(\"R\", a).compareTo(b)", "said(\"R\", a).max(b).longValue()",
            "b.max(said(\"A\", a)).longValue()", "said(\"R\", a).add(b).abs().longValue()", "said(\"R\", a).add(b).signum()"})
    void anOperandThatIsNoDelayedReadRunsOnce(String body) {
        assertAgree("public static long m(BigInteger a, BigInteger b) { return " + body + "; }", integer(3), integer(1));
        assertAgree("public static long m(BigInteger a, BigInteger b) { return " + body + "; }", integer(-3), integer(1));
    }

    /**
     * #146: one expression nests a handler 24 deep around a traced operand. Each level reads the level below once, so the
     * script is linear and the trace shows once; reading it twice per level would make 2^24 copies.
     */
    @ParameterizedTest
    @ValueSource(strings = {"%s.abs()", "%s.max(b)", "b.min(%s)", "BigInteger.valueOf(%s.signum())", "BigInteger.valueOf(%s.compareTo(b))",
            "(%s.compareTo(b) == -1 ? a : b)", "((%s.compareTo(b) == -1 || b.compareTo(a) == -1) ? a : b)", "%s.add(b).abs().max(a)"})
    void aNestOfHandlersIsLinear(String level) {
        String expression = "said(\"K\", a)";
        for (int i = 0; i < 24; i++) expression = level.formatted(expression);
        String method = "public static BigInteger m(BigInteger a, BigInteger b) { return " + expression + "; }";
        assertAgree(method, integer(3), integer(-1));
        assertAgree(method, integer(-2), integer(1));
    }

    /**
     * Constant arithmetic is not bound, so it folds where it is read: a handler of it compiles to the bytes of the same
     * handler of the folded literal, at every level that folds (#306 review F1).
     */
    @ParameterizedTest
    @ValueSource(strings = {"BigInteger.valueOf(Math.abs(1 + 2))|BigInteger.valueOf(Math.abs(3))",
            "BigInteger.ONE.add(BigInteger.TWO).abs()|BigInteger.valueOf(3).abs()",
            "BigInteger.valueOf(Math.max(1 + 2, 2 - 5))|BigInteger.valueOf(Math.max(3, -3))",
            "BigInteger.valueOf(BigInteger.TWO.multiply(BigInteger.TEN).signum())|BigInteger.valueOf(BigInteger.valueOf(20).signum())",
            "BigInteger.valueOf(BigInteger.valueOf(-5).compareTo(BigInteger.ONE.add(BigInteger.ONE)))|BigInteger.valueOf(BigInteger.valueOf(-5).compareTo(BigInteger.TWO))"})
    void constantArithmeticFoldsLikeItsLiteral(String pair) {
        var sides = pair.split("\\|");
        var src = "import java.math.BigInteger;\nclass Folds { static BigInteger m() { return %s; } }";
        for (var level : List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)) {
            var arithmetic = UplcFlatEncoder.encodeProgram(compiler(level).compileMethod(src.formatted(sides[0]), "m").program());
            var literal = UplcFlatEncoder.encodeProgram(compiler(level).compileMethod(src.formatted(sides[1]), "m").program());
            assertArrayEquals(literal, arithmetic, sides[0] + " at " + level);
        }
    }

    private static final String DECODED = "Builtins.unIData(Builtins.iData(BigInteger.ONE))";

    private static String chain(int depth, String first, String link) {
        return chain(first, Collections.nCopies(depth, link));
    }

    private static String chain(String first, List<String> links) {
        var methods = new StringBuilder("static BigInteger k0() { return " + first + "; } ");
        for (int i = 1; i <= links.size(); i++) {
            methods.append("static BigInteger k").append(i).append("() { return ").append(links.get(i - 1).formatted("k" + (i - 1) + "()")).append("; } ");
        }
        return methods + "public static BigInteger m(BigInteger a) { return k" + links.size() + "(); }";
    }

    /** A level of a chain reads the level below once, so the chain is linear: at this depth a doubling is past any budget. */
    @ParameterizedTest
    @ValueSource(strings = {"%s.abs()", "%s.max(BigInteger.ONE)", "%s.min(BigInteger.ONE)", "BigInteger.ONE.max(%s)",
            "BigInteger.valueOf(%s.signum())", "BigInteger.valueOf(%s.compareTo(BigInteger.ONE))", "BigInteger.valueOf(BigInteger.ONE.compareTo(%s))"})
    void aChainOfDelayedMethodsIsLinear(String link) {
        assertAgree(chain(24, DECODED, link), integer(1));
    }

    private static final List<String> BUILT_LINKS = List.of("%s.abs().abs()", "%s.negate().abs()", "%s.add(BigInteger.ONE).abs()",
            "%s.add(BigInteger.ONE).max(BigInteger.ONE)", "%s.max(BigInteger.ONE).max(BigInteger.TWO)", "BigInteger.ONE.max(%s.add(BigInteger.ONE))",
            "BigInteger.valueOf(%s.add(BigInteger.ONE).signum())", "BigInteger.valueOf(%s.negate().signum())",
            "BigInteger.valueOf(%s.add(BigInteger.ONE).compareTo(BigInteger.ONE))", "BigInteger.valueOf(BigInteger.ONE.compareTo(%s.negate()))");

    /** So is a chain whose operands are built on the level below. */
    @Test
    void aChainOfBuiltOperandsIsLinear() {
        for (var link : BUILT_LINKS) assertAgree(chain(24, DECODED, link), integer(1));
    }

    @Provide
    Arbitrary<List<String>> mixedLinks() {
        return Arbitraries.of(BUILT_LINKS).list().ofMinSize(16).ofMaxSize(28);
    }

    @Property(tries = 12)
    void aMixOfBuiltOperandsIsLinear(@ForAll("mixedLinks") List<String> links) {
        assertAgree(chain(DECODED, links), integer(1));
    }

    private static final String SUM = "sealed interface S permits A, B { } record A(BigInteger x) implements S { } record B(BigInteger y) implements S { } ";

    private static final TagTable TAGS = TagTable.of(new TagTable.Entry("Case$A", "Case$S", 0), new TagTable.Entry("Case$B", "Case$S", 1));

    private static DiffRunner.Report runSum(String members, PlutusData... args) {
        return Adr068CallsHarnessTest.RUNNER.run(new DiffCase("m3b", Adr068CallsHarnessTest.program(members), "Case", "m", List.of(args), TAGS, null));
    }

    private static String instanceChain(int depth, String link) {
        var methods = new StringBuilder(SUM + "static S k0() { return new A(" + DECODED + "); } ");
        for (int i = 1; i <= depth; i++) {
            methods.append("static S k").append(i).append("() { ").append(link.formatted("k" + (i - 1) + "()")).append(" } ");
        }
        return methods + "public static boolean m(BigInteger a) { if (k" + depth + "() instanceof A q) { return true; } return false; }";
    }

    /** An {@code if} on a pattern reads its operand once, in the test, so a chain of them is linear too. */
    @ParameterizedTest
    @ValueSource(strings = {"if (%s instanceof A q) { return q; } return new B(BigInteger.ONE);",
            "if (%s instanceof A q) { return new A(q.x().abs()); } else { return new B(BigInteger.ONE); }",
            "if (%s instanceof B q) { return q; } return new A(BigInteger.TWO);"})
    void aChainOfPatternsIsLinear(String link) {
        assertAgree(runSum(instanceChain(24, link), integer(1)));
    }

    private static final String KS = SUM + "static S k() { return new A(said(\"K\", BigInteger.TEN)); } static S j() { return new B(said(\"J\", BigInteger.TWO)); } "
            + "static S pick(S s) { return s; } static S fails() { return new A(fail(BigInteger.ONE)); } ";

    /** Once, in order, and as the branches run: an exit and no exit are two shapes, on both frontends. */
    @ParameterizedTest
    @ValueSource(strings = {"if (k() instanceof A q) { return q.x(); } return a;", "if (j() instanceof A q) { return q.x(); } return a;",
            "if (k() instanceof A q) { return q.x(); } else { return a; }", "if (j() instanceof B q) { return q.y(); } else { return a; }",
            "if (pick(k()) instanceof A q) { return q.x(); } return a;", "if (fails() instanceof A q) { return q.x(); } return a;",
            "if (k() instanceof A p) { if (j() instanceof B q) { return p.x().add(q.y()); } } return a;",
            "if (j() instanceof A p) { return p.x(); } else if (k() instanceof A q) { return q.x(); } return a;",
            "if (a.signum() < 0) { if (fails() instanceof A q) { return q.x(); } } return a;",
            "if (k() instanceof A q) { said(\"T\", q.x()); } return a;", "if (j() instanceof A q) { said(\"T\", q.x()); } return a;",
            "if (k() instanceof A q) { said(\"T\", q.x()); } else { said(\"E\", a); } return a;",
            "if (j() instanceof A q) { said(\"T\", q.x()); } else { said(\"E\", a); } return a;",
            "if (fails() instanceof A q) { said(\"T\", q.x()); } return a;", "if (a.signum() < 0) { if (fails() instanceof A q) { said(\"T\", q.x()); } } return a;"})
    void aPatternReadsItsOperandOnce(String body) {
        assertAgree(runSum(KS + "public static BigInteger m(BigInteger a) { " + body + " }", integer(1)));
        assertAgree(runSum(KS + "public static BigInteger m(BigInteger a) { " + body + " }", integer(-1)));
    }

    /** No read in the operand: it is bound too, so it runs once for the test and the binding (#146). */
    @Test
    void aPatternOnAnOperandThatIsNoReadRunsOnce() {
        assertAgree(runSum(KS + "static S made(BigInteger v) { return new A(said(\"M\", v)); } "
                + "public static BigInteger m(BigInteger a) { if (made(a) instanceof A q) { return q.x(); } return a; }", integer(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Math.abs(%s)", "Math.max(%s, 1L)", "Math.min(%s, 1L)", "Math.max(1L, %s)", "Math.min(1L, %s)", "Math.abs(%s + 1L)",
            "Math.max(%s + 1L, 1L)", "Math.min(1L, %s - 1L)", "Math.abs(Math.abs(%s))"})
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

    private static String tree(String type, String first, int depth) {
        var methods = new StringBuilder("static " + type + " f0(" + type + " x) { return " + first + "; } ");
        for (int i = 1; i <= depth; i++) {
            methods.append("static ").append(type).append(" f").append(i).append("(").append(type).append(" x) { return f").append(i - 1).append("(f").append(i - 1).append("(x)); } ");
        }
        return methods.toString();
    }

    /** A body that cannot fail and does much is delayed: a method not called costs the script nothing, at any depth. */
    @ParameterizedTest
    @ValueSource(ints = {6, 16, 17, 18, 20, 36})
    void aTreeOfHashesNotCalledDoesNotRun(int depth) {
        var methods = tree("byte[]", "Builtins.blake2b_256(x)", depth) + "static byte[] k() { return f" + depth + "(new byte[]{1}); } ";
        assertAgree(methods + "public static BigInteger m(BigInteger a) { return a; }", integer(5));
        assertAgree(methods + "public static long m(boolean c) { return c ? k().length : 0L; }", bool(false));
    }

    @ParameterizedTest
    @ValueSource(ints = {14, 15, 18, 36})
    void aTreeOfBranchesNotCalledDoesNotRun(int depth) {
        assertAgree(tree("BigInteger", "x.compareTo(BigInteger.ONE) < 0 ? x : x", depth)
                + "static BigInteger k() { return f" + depth + "(BigInteger.ONE); } public static BigInteger m(BigInteger a) { return a; }", integer(5));
    }

    /** Calls are work too, with no builtin among them. */
    @ParameterizedTest
    @ValueSource(ints = {14, 16, 18, 36})
    void aTreeOfCallsNotCalledDoesNotRun(int depth) {
        assertAgree(tree("BigInteger", "x", depth) + "static BigInteger k() { return f" + depth + "(BigInteger.ONE); } public static BigInteger m(BigInteger a) { return a; }", integer(5));
    }

    /** A shared value doubles per constructor, so what compares it is the work, whatever the few terms that built it. */
    @ParameterizedTest
    @ValueSource(ints = {12, 14, 16})
    void aSharedValueComparedNotCalledDoesNotRun(int depth) {
        var built = new StringBuilder("public record D0(BigInteger v) { } ");
        for (int i = 1; i <= depth; i++) built.append("public record D").append(i).append("(D").append(i - 1).append(" a, D").append(i - 1).append(" b) { } ");
        built.append("static boolean k() { D0 t0 = new D0(BigInteger.ONE); ");
        for (int i = 1; i <= depth; i++) built.append("D").append(i).append(" t").append(i).append(" = new D").append(i).append("(t").append(i - 1).append(", t").append(i - 1).append("); ");
        built.append("return t").append(depth).append(".equals(t").append(depth).append("); } ");
        assertAgree(built + "public static BigInteger m(BigInteger a) { return a; }", integer(5));
        assertAgree(built + "public static long m(boolean c) { return c ? 1 : 2; }", bool(false));
    }

    /** A string in a record is encoded byte by byte, so a long one read many times is past the limit, not called. */
    @ParameterizedTest
    @ValueSource(ints = {16, 100, 400, 1000})
    void longStringsInRecordsNotCalledDoNotRun(int length) {
        int fields = 240;
        var types = new StringBuilder();
        var values = new StringBuilder();
        for (int i = 0; i < fields; i++) {
            types.append(i == 0 ? "" : ", ").append("String f").append(i);
            values.append(i == 0 ? "" : ", ").append("c");
        }
        assertAgree("public record Rc(" + types + ") { } static Rc g(String c) { return new Rc(" + values + "); } static boolean k() { String c = \"" + "a".repeat(length)
                + "\"; Rc r0 = g(c); Rc r1 = g(c); Rc r2 = g(c); Rc r3 = g(c); return true; } public static BigInteger m(BigInteger a) { return a; }", integer(5));
    }

    /** A branch is work with no builtin in it, so a long body under a tree of calls is past the limit. */
    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100, 200})
    void aTreeOverLongBranchesNotCalledDoesNotRun(int length) {
        var branches = "true";
        for (int i = 0; i < length; i++) branches = "(c ? " + branches + " : false)";
        var methods = new StringBuilder("static boolean f0(boolean c) { return " + branches + "; } ");
        for (int i = 1; i <= 8; i++) {
            methods.append("static boolean f").append(i).append("(boolean c) { return f").append(i - 1).append("(c) ? f").append(i - 1).append("(c) : false; } ");
        }
        assertAgree(methods + "static boolean k() { return f8(true); } public static BigInteger m(BigInteger a) { return a; }", integer(5));
    }

    /** A hash of a long input is much work for one application. */
    @Test
    void aFewHashesOfALongInputNotCalledDoNotRun() {
        var doubled = new StringBuilder("static byte[] y() { byte[] a0 = Builtins.blake2b_256(Builtins.emptyByteString()); ");
        for (int i = 1; i <= 9; i++) doubled.append("byte[] a").append(i).append(" = Builtins.appendByteString(a").append(i - 1).append(", a").append(i - 1).append("); ");
        doubled.append("return a9; } ");
        assertAgree(doubled + tree("byte[]", "Builtins.blake2b_256(Builtins.appendByteString(x, y()))", 9)
                + "static byte[] k() { return f9(new byte[]{1}); } public static BigInteger m(BigInteger a) { return a; }", integer(5));
    }

    /** Past the limit a body is delayed, so it runs when called and as often as it is called, as in Java. */
    @Test
    void aBodyPastTheLimitRunsWhenCalled() {
        var methods = tree("BigInteger", "x.add(BigInteger.ONE)", 8) + "static BigInteger k() { return f8(BigInteger.ONE); } ";
        assertAgree(methods + "public static BigInteger m() { return k().add(k()); }");
        assertAgree(methods + "public static long m(boolean c) { return c ? k().longValue() : 2; }", bool(true));
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

    private static JulcCompiler compiler(OptimizationLevel level) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level)
                );
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aMethodUsedOnlyInALibrary(OptimizationLevel level) {
        var vm = JulcVm.create("Java");
        var f = compiler(level).compileMethod(ROOT, "f", List.of(LIBRARY)).program();
        var notTaken = assertInstanceOf(EvalResult.Success.class, vm.evaluateWithArgs(f, List.<PlutusData>of(bool(false))));
        assertEquals(Term.const_(Constant.integer(2)), notTaken.resultTerm());
        assertInstanceOf(EvalResult.Failure.class, vm.evaluateWithArgs(f, List.<PlutusData>of(bool(true))));
        var g = compiler(level).compileMethod(ROOT, "g", List.of(LIBRARY)).program();
        assertEquals(List.of("T", "T"), assertInstanceOf(EvalResult.Success.class, vm.evaluateWithArgs(g, List.<PlutusData>of())).traces());
    }
}
