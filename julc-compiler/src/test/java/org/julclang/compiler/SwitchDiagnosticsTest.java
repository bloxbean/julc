package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-067 D4: the compile-time rules JuLC owns for switch expressions, one test per matrix row. javac 21.0.3 rejects
 * each rejected program too (the parity suite in julc-difftest checks that); the colon-form and parameterized-selector
 * rows reject legal Java on purpose. Accepted neighbours check that each rule is no wider than Java's.
 */
class SwitchDiagnosticsTest {

    private static final String S = SwitchGuardTest.S;

    private static String probe(String members) {
        return SwitchGuardTest.probe(members);
    }

    private static String switchOver(String arms) {
        return probe(S + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) {
                """ + arms + """
                        };
                    }
                """);
    }

    /** JuLC rejects the program with {@code code} and a message containing {@code fragment}. */
    private static CompilerDiagnostic rejected(String source, String code, String fragment) {
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m"), source);
        var diagnostic = error.diagnostics().stream().filter(d -> d.level() == CompilerDiagnostic.Level.ERROR)
                .findFirst().orElseThrow();
        assertEquals(code, diagnostic.code(), diagnostic.message());
        assertTrue(diagnostic.message().contains(fragment), diagnostic.message());
        return diagnostic;
    }

    private static void accepted(String source) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m");
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
    }

    // ---- JULC0005: exhaustiveness, guarded labels excluded

    @Test
    void onlyAGuardedArmIsNotExhaustive() {
        rejected(switchOver("""
                            case A x when x.v().signum() > 0 -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), "JULC0005", "Missing cases: A");
        accepted(switchOver("""
                            case A x when x.v().signum() > 0 -> BigInteger.ONE;
                            default -> BigInteger.ZERO;
                """));
        accepted(switchOver("""
                            case A x when true -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """));
    }

    @Test
    void missingVariantWithoutGuards() {
        rejected(switchOver("""
                            case A x -> BigInteger.ONE;
                """), "JULC0005", "Missing cases: B");
    }

    // ---- JULC0071: dominance

    @Test
    void guardedArmAfterSameUnguardedPattern() {
        rejected(switchOver("""
                            case A x -> BigInteger.ONE;
                            case A x when x.v().signum() > 0 -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), "JULC0071", "dominated by 'case A x'");
    }

    @Test
    void armAfterWhenTrueOnSamePattern() {
        rejected(switchOver("""
                            case A x when true -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), "JULC0071", "dominated");
    }

    @Test
    void secondUnguardedArmForAVariant() {
        rejected(switchOver("""
                            case A x -> BigInteger.ONE;
                            case A y -> BigInteger.TWO;
                            case B z -> BigInteger.ZERO;
                """), "JULC0071", "dominated");
    }

    @Test
    void defaultBeforeAPatternLabel() {
        rejected(switchOver("""
                            default -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), "JULC0071", "the default label");
    }

    @Test
    void unguardedArmAfterGuardedOneIsNotDominated() {
        accepted(switchOver("""
                            case A x when x.v().signum() > 0 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """));
    }

    // ---- JULC0072: guard type and constant false

    @Test
    void constantFalseGuards() {
        rejected(switchOver("""
                            case A x when false -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), "JULC0072", "constant expression with value false");
        rejected(probe(S + """
                    static final boolean OFF = false;
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when OFF -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """), "JULC0072", "value false");
        rejected(probe(S + """
                    static final boolean OFF = false;
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when Probe.OFF -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """), "JULC0072", "value false");
        rejected(probe(S + """
                    static BigInteger m(BigInteger a) {
                        final boolean on = false;
                        S s = new A(a);
                        return switch (s) { case A x when on -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """), "JULC0072", "value false");
        rejected(switchOver("""
                            case A x when 1 == 2 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), "JULC0072", "value false");
    }

    @Test
    void nonConstantVariablesAreNotConstant() {
        // Not final, or not a primitive or String: not a constant variable (JLS 4.12.4).
        accepted(probe(S + """
                    static boolean OFF = false;
                    static final Boolean BOXED = false;
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        BigInteger first = switch (s) { case A x when OFF -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                        BigInteger second = switch (s) { case A x when BOXED -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                        return first.add(second);
                    }
                """));
    }

    @Test
    void nonBooleanGuards() {
        rejected(switchOver("""
                            case A x when 3 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), "JULC0072", "is numeric");
        rejected(switchOver("""
                            case A x when x.v() -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), "JULC0072", "must have type boolean or Boolean");
    }

    // ---- JULC0072: the guard's Java type, established from the source (F28, F52; M1 decision: fail closed)

    @Test
    void guardsWhoseJavaTypeIsNotBoolean() {
        rejected(probe(S + """
                    static BigInteger m(BigInteger a, PlutusData d) {
                        S s = new A(a);
                        return switch (s) { case A x when d -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """).replace("import java.math.BigInteger;", "import java.math.BigInteger;\nimport org.julclang.core.PlutusData;"),
                "JULC0072", "not boolean");
        rejected(switchOver("""
                            case A x when Builtins.iData(x.v()) -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), "JULC0072", "boolean");
        rejected(switchOver("""
                            case A x when (Object) true -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), "JULC0072", "not boolean");
        rejected(switchOver("""
                            case A x when (true ? true : 0) -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), "JULC0072", "not boolean");
        rejected(switchOver("""
                            case A x when (x.v().signum() > 0 ? true : 0) -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), "JULC0072", "not boolean");
    }

    @Test
    void booleanGuardsTypedByDeclarationOrReturnType() {
        accepted(probe(S + """
                    static Boolean big(BigInteger v) { return v.compareTo(BigInteger.TEN) > 0; }
                    static BigInteger m(BigInteger a, boolean flag, JulcList<Boolean> fs) {
                        S s = new A(a);
                        Boolean boxed = flag;
                        return switch (s) {
                            case A x when flag -> BigInteger.ONE;
                            case A x when boxed -> BigInteger.TWO;
                            case A x when big(x.v()) && !flag -> BigInteger.TEN;
                            case A x when fs.head() -> BigInteger.valueOf(3);
                            case A x when fs.get(0) -> BigInteger.valueOf(4);
                            case A x when (flag ? big(x.v()) : true) -> BigInteger.valueOf(5);
                            default -> BigInteger.ZERO;
                        };
                    }
                """));
    }

    // ---- JULC0072: constant guards by JLS 15.29 (F54, F29)

    @Test
    void constantGuardsFollowJls1529() {
        // Constant true: the label is unguarded, so it covers A.
        for (String guard : new String[]{"(boolean) true", "(1 << 1) == 2", "\"a\" == \"a\"",
                "((true ? 2147483647 : 0L) + 1) > 0", "(int) 3L == 3", "'a' < 'b'", "(byte) 200 == -56"}) {
            accepted(switchOver("""
                                case A x when %s -> BigInteger.ONE;
                                case B y -> BigInteger.ZERO;
                    """.formatted(guard)));
        }
        // Constant false: rejected.
        for (String guard : new String[]{"(boolean) false", "\"a\" == \"b\"", "((true ? 2147483647 : 0) + 1) > 0"}) {
            rejected(switchOver("""
                                case A x when %s -> BigInteger.ONE;
                                case A x -> BigInteger.TWO;
                                case B y -> BigInteger.ZERO;
                    """.formatted(guard)), "JULC0072", "value false");
        }
        // A constant JuLC does not model exactly is rejected, never classified (known over-rejection: javac accepts).
        rejected(switchOver("""
                            case A x when 1.5 > 1.0 -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), "JULC0072", "cannot evaluate exactly");
    }

    @Test
    void qualifiedConstantNeedsATypeQualifier() {
        // F50: 'Probe' is a local here, which obscures the class, so Probe.ON is the record component (false in Java),
        // never the constant field. It used to fold to true and run the first arm; now it is not folded, and since
        // JuLC cannot type a component read as a field, the guard fails closed.
        rejected(probe(S + """
                    record Flags(boolean ON) {}
                    static final boolean ON = true;
                    static BigInteger m(BigInteger a) {
                        Flags Probe = new Flags(false);
                        S s = new A(a);
                        return switch (s) { case A x when Probe.ON -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """), "JULC0072", "Probe.ON");
        // With the class as the qualifier, Probe.ON is the constant true: the label is unguarded and covers A.
        accepted(probe(S + """
                    static final boolean ON = true;
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when Probe.ON -> BigInteger.ONE; case B y -> BigInteger.ZERO; };
                    }
                """));
    }

    // ---- JULC0073: effectively final, by declaration

    @Test
    void guardReadsLoopUpdatedLocal() {
        rejected(probe(S + """
                    static BigInteger m(BigInteger a) {
                        BigInteger lim = BigInteger.ZERO;
                        while (lim.compareTo(BigInteger.TEN) < 0) {
                            lim = lim.add(BigInteger.ONE);
                        }
                        S s = new A(a);
                        return switch (s) { case A x when x.v().compareTo(lim) > 0 -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """), "JULC0073", "'lim'");
    }

    @Test
    void forEachVariableAndSiblingDeclarationsAreEffectivelyFinal() {
        // The for-each variable is effectively final; a sibling block's reassigned 'lim' is another variable.
        accepted(probe(S + """
                    static BigInteger m(BigInteger a) {
                        JulcList<BigInteger> xs = JulcList.of(a, a);
                        BigInteger acc = BigInteger.ZERO;
                        for (var k : xs) {
                            S s = new A(k);
                            acc = acc.add(switch (s) { case A x when k.signum() > 0 -> BigInteger.ONE; default -> BigInteger.ZERO; });
                        }
                        if (a.signum() > 0) {
                            BigInteger lim = BigInteger.ZERO;
                            while (lim.compareTo(BigInteger.TEN) < 0) {
                                lim = lim.add(BigInteger.ONE);
                            }
                            return acc.add(lim);
                        }
                        if (a.signum() < 0) {
                            BigInteger lim = BigInteger.ONE;
                            S s = new A(a);
                            return acc.add(switch (s) { case A x when x.v().compareTo(lim) > 0 -> BigInteger.ONE; default -> BigInteger.ZERO; });
                        }
                        return acc;
                    }
                """));
    }

    @Test
    void sameNameReassignedInAnotherMethodIsAnotherVariable() {
        // Block locals are renamed apart within a method (ADR-060), so the case that needs the resolved declaration,
        // not the name, is a same-named variable of another method.
        accepted(probe(S + """
                    static BigInteger count(BigInteger n) {
                        BigInteger lim = BigInteger.ZERO;
                        while (lim.compareTo(n) < 0) {
                            lim = lim.add(BigInteger.ONE);
                        }
                        return lim;
                    }
                    static BigInteger m(BigInteger a) {
                        BigInteger lim = count(a);
                        S s = new A(a);
                        return switch (s) { case A x when x.v().compareTo(lim) >= 0 -> BigInteger.ONE; default -> BigInteger.ZERO; };
                    }
                """));
    }

    // ---- JULC0074: pattern variable introduced by a guard

    @Test
    void patternVariableIntroducedOnlyWhenFalseIsAccepted() {
        // F29: !(o instanceof A a2) introduces a2 when false, so it is not in scope in the arm (JLS 6.3.1).
        accepted(probe(S + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        S o = new B(a);
                        return switch (s) { case A x when !(o instanceof A a2) -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """));
        rejected(probe(S + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        S o = new B(a);
                        return switch (s) { case A x when !(o instanceof A a2) || a2.v().signum() > 0 -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """), "JULC0074", "'a2'");
    }

    // ---- guard instanceof: the operand's family (F51)

    @Test
    void guardInstanceofAcrossFamiliesOrOnAnUnknownType() {
        String t = """
                    sealed interface T permits C, D {}
                    record C(BigInteger w) implements T {}
                    record D(BigInteger w) implements T {}
                """;
        rejected(probe(S + t + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when (Object) x instanceof C -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), "JULC0072", "not established as T");
        rejected(probe(S + t + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when x instanceof C -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), "JULC0072", "incompatible types");
        rejected(probe(S + t + """
                    static BigInteger m(BigInteger a, PlutusData d) {
                        S s = new A(a);
                        return switch (s) { case A x when d instanceof C -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """).replace("import java.math.BigInteger;", "import java.math.BigInteger;\nimport org.julclang.core.PlutusData;"),
                "JULC0072", "not established as T");
    }

    @Test
    void guardIntroducesPatternVariable() {
        rejected(probe(S + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        S t = new B(a);
                        return switch (s) { case A x when t instanceof B b -> b.v(); case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """), "JULC0074", "'b'");
    }

    // ---- JULC0075: colon form (#283, I16)

    @Test
    void colonGroupThatFallsThrough() {
        rejected(switchOver("""
                            case A x: x.v().negate();
                            default: yield BigInteger.TWO;
                """), "JULC0075", "fall through");
    }

    @Test
    void colonGroupsWithSeveralLabels() {
        rejected(probe("""
                    sealed interface X permits X0, Y0 {}
                    record X0() implements X {}
                    record Y0() implements X {}
                    static BigInteger m(BigInteger a) {
                        X x = new X0();
                        return switch (x) {
                            case X0 p: case Y0 q: yield BigInteger.valueOf(3);
                        };
                    }
                """), "JULC0075", "several labels");
    }

    @Test
    void lastColonGroupMustYield() {
        rejected(switchOver("""
                            case A x: yield BigInteger.ONE;
                            case B y: y.v();
                """), "JULC0075", "fall through");
    }

    @Test
    void severalPatternsInOneLabel() {
        // Not Java 21 (javac: illegal fall-through from a pattern); JavaParser's JAVA_21 grammar rejects it already.
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(switchOver("""
                            case A x, B y -> BigInteger.ONE;
                """), "m"));
        assertTrue(error.getMessage().contains("Parse error"), error.getMessage());
    }

    @Test
    void colonGroupEndingInIfWithoutElse() {
        // F34: legal Java that falls through when the condition is false.
        rejected(switchOver("""
                            case A x: if (x.v().signum() > 0) { yield BigInteger.ONE; }
                            default: yield BigInteger.TWO;
                """), "JULC0075", "fall through");
    }

    @Test
    void mixedCaseKinds() {
        // F57, both orderings (javac: different case kinds used in the switch).
        rejected(switchOver("""
                            case A x when x.v().signum() > 0 -> BigInteger.ONE;
                            default: yield BigInteger.TWO;
                """), "JULC0075", "mixes colon groups and arrow rules");
        rejected(switchOver("""
                            case A x: yield BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), "JULC0075", "mixes colon groups and arrow rules");
    }

    @Test
    void colonGroupLocalsHaveJavaScope() {
        // F55: a colon group's local is in scope in a nested switch and in the later groups.
        rejected(switchOver("""
                            case A x:
                                BigInteger y = x.v();
                                S t = new B(y);
                                yield switch (t) { case A p -> BigInteger.ONE; case B y -> BigInteger.TWO; };
                            default: yield BigInteger.ZERO;
                """), "JULC0083", "as a local variable");
        rejected(switchOver("""
                            case A x:
                                BigInteger k = x.v();
                                yield BigInteger.ONE;
                            case B k: yield BigInteger.TWO;
                """), "JULC0083", "'k'");
        rejected(switchOver("""
                            case A x:
                                BigInteger lim = BigInteger.ZERO;
                                while (lim.compareTo(BigInteger.TEN) < 0) { lim = lim.add(BigInteger.ONE); }
                                S t = new A(lim);
                                yield switch (t) { case A p when p.v().compareTo(lim) > 0 -> BigInteger.ONE; default -> BigInteger.TWO; };
                            default: yield BigInteger.ZERO;
                """), "JULC0073", "'lim'");
        rejected(switchOver("""
                            case A x:
                                final boolean off = false;
                                S t = new A(x.v());
                                yield switch (t) { case A p when off -> BigInteger.ONE; case A p -> BigInteger.TWO; case B q -> BigInteger.ZERO; };
                            default: yield BigInteger.ZERO;
                """), "JULC0072", "value false");
    }

    @Test
    void qualifiedPatternTypeOfAnotherDeclaration() {
        // F56: Other.A is another record named A, on the guarded and the unguarded lowering.
        String other = """
                    static final class Other {
                        sealed interface U permits A {}
                        record A(BigInteger v) implements U {}
                    }
                """;
        rejected(probe(S + other + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case Other.A x -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), "JULC0076", "not applicable: Other.A");
        rejected(probe(S + other + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case Other.A x when x.v().signum() > 0 -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), "JULC0076", "not applicable: Other.A");
        accepted(probe(S + other + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case Probe.A x when x.v().signum() > 0 -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """));
    }

    @Test
    void recordPatternsAreRejectedUntilM2a() {
        // F35: interim, so no record-pattern label is ever dropped silently.
        rejected(switchOver("""
                            case A(var v) -> v;
                            default -> BigInteger.ZERO;
                """), "JULC0076", "not yet supported");
        rejected(switchOver("""
                            case A(var v) when v.signum() > 0 -> v;
                            case A x -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), "JULC0076", "not yet supported");
    }

    @Test
    void colonGroupsThatYieldAreAccepted() {
        accepted(switchOver("""
                            case A x: { yield BigInteger.ONE; }
                            case B y:
                                if (y.v().signum() > 0) { yield BigInteger.ONE; } else { yield BigInteger.TWO; }
                """));
    }

    // ---- JULC0076: applicability and the total type pattern

    @Test
    void totalTypePattern() {
        rejected(switchOver("""
                            case A x -> BigInteger.ONE;
                            case S t -> BigInteger.TWO;
                """), "JULC0076", "unsupported: total type pattern");
    }

    @Test
    void patternForAnotherType() {
        rejected(probe(S + """
                    sealed interface T permits C, D {}
                    record C(BigInteger w) implements T {}
                    record D(BigInteger w) implements T {}
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x -> BigInteger.ONE; case C c -> BigInteger.TWO; default -> BigInteger.ZERO; };
                    }
                """), "JULC0076", "not applicable: C is not a permitted variant of S");
    }

    // ---- JULC0082: duplicate default

    @Test
    void twoDefaultLabels() {
        rejected(switchOver("""
                            case A x -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                            default -> BigInteger.TEN;
                """), "JULC0082", "more than one default");
    }

    // ---- JULC0083: pattern-variable conflicts

    @Test
    void patternVariableNamedLikeParameterLocalOrOuterPatternVariable() {
        rejected(switchOver("""
                            case A a -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), "JULC0083", "as a parameter");
        rejected(probe(S + """
                    static BigInteger m(BigInteger a) {
                        BigInteger y = a;
                        S s = new A(a);
                        return switch (s) { case A x -> x.v(); case B y -> y.v(); };
                    }
                """), "JULC0083", "as a local variable");
        rejected(probe(S + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) {
                            case A x -> switch (s) { case A x -> BigInteger.ONE; case B y -> BigInteger.TWO; };
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """), "JULC0083", "as a pattern variable");
    }

    @Test
    void patternVariableMayShadowAFieldAndReuseASiblingArmsName() {
        accepted(probe(S + """
                    static BigInteger x = BigInteger.TEN;
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x -> x.v(); case B x -> x.v().negate(); };
                    }
                """));
    }

    // ---- JULC0007: selector type

    @Test
    void selectorThatIsNotASealedInterface() {
        rejected(probe("""
                    record R1(BigInteger x) {}
                    static BigInteger m(BigInteger a) {
                        R1 r = new R1(a);
                        return switch (r) { case R1 q -> q.x(); };
                    }
                """), "JULC0007", "sealed interface");
    }

    @Test
    void parameterizedSealedSelector() {
        rejected(probe("""
                    sealed interface G<X> permits GC, GD {}
                    record GC(BigInteger c) implements G<String> {}
                    record GD(BigInteger d) implements G<BigInteger> {}
                    static BigInteger m(BigInteger a) {
                        G<BigInteger> g = new GD(a);
                        return switch (g) { case GD d -> d.d(); };
                    }
                """), "JULC0007", "type parameters");
    }

    // ---- ADR-048 (I11): guards update nothing

    @Test
    void guardCannotAssign() {
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(probe(S + """
                    static BigInteger m(BigInteger a) {
                        BigInteger t = BigInteger.ZERO;
                        JulcList<BigInteger> xs = JulcList.of(a);
                        for (var k : xs) {
                            S s = new A(k);
                            t = switch (s) { case A x when (t = k).signum() > 0 -> BigInteger.ONE; default -> BigInteger.ZERO; };
                        }
                        return t;
                    }
                """), "m"));
        assertTrue(error.getMessage().contains("'t'"), error.getMessage());
    }
}
