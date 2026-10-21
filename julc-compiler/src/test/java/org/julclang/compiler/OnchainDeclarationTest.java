package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060: on-chain methods and locals are bound by name. Overloads that are not interchangeable
 * on-chain and multi-variable declarations used to compile to the wrong program: the last overload
 * ran for every call ({@code check(BigInteger)} vs {@code check(long)} returned false), and only the
 * first declared variable was bound ({@code BigInteger a = ONE, b = TWO; a.add(b)} read a static
 * field {@code b} and returned 11). Both are now rejected. A loop assignment to a static field or
 * {@code @Param} rebinds it only inside the assigning method; it is rejected when the field is final
 * or another method reads it, where that differs from Java.
 */
class OnchainDeclarationTest {
    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ByteStringLib;
            class Decl {
            """;

    private static CompileResult compileMethod(String members) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(HEADER + members + " }", "m");
    }

    private static String rejectedCode(Runnable compile) {
        return assertThrows(CompilerException.class, compile::run).diagnostics().getFirst().code();
    }

    private static Term evaluate(CompileResult result, PlutusData... args) {
        var eval = CompilerTestVm.pv11().evaluateWithArgs(result.program(), List.of(args));
        return assertInstanceOf(EvalResult.Success.class, eval, eval.toString()).resultTerm();
    }

    @Test
    void overloadsWithDifferentBodiesAreRejected() {
        assertEquals("JULC0054", rejectedCode(() -> compileMethod("""
                static boolean check(BigInteger a) { return a.compareTo(BigInteger.TEN) > 0; }
                static boolean check(long a) { return a < 0; }
                static boolean m(BigInteger v) { return check(v); }""")));
        assertEquals("JULC0054", rejectedCode(() -> compileMethod("""
                static BigInteger add(BigInteger a) { return a.add(BigInteger.ONE); }
                static BigInteger add(BigInteger a, BigInteger b) { return a.add(b); }
                static BigInteger m(BigInteger v) { return add(v, v); }""")));
    }

    @Test
    void interchangeableOverloadsAreAccepted() {
        var result = compileMethod("""
                static long next(long a) { return a + 1; }
                static long next(int a) { return a + 1; }
                static long m(long v) { return next(v); }""");
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(42))), evaluate(result, PlutusData.integer(41)));
    }

    @Test
    void stdlibIntegerToByteStringOverloadsStillCompile() {
        var result = compileMethod("""
                static boolean m(long v) {
                    return Builtins.equalsByteString(ByteStringLib.integerToByteString(true, 4, v),
                            ByteStringLib.integerToByteString(true, 4, BigInteger.valueOf(v)));
                }""");
        assertEquals(new Term.Const(Constant.bool(true)), evaluate(result, PlutusData.integer(258)));
    }

    @Test
    void validatorHelperOverloadsAreRejected() {
        String validator = """
                import java.math.BigInteger;
                @SpendingValidator
                class OverloadedHelpers {
                    static boolean ok(BigInteger a) { return a.signum() > 0; }
                    static boolean ok(long a) { return a > 100; }
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return ok(Builtins.unIData(redeemer));
                    }
                }""";
        assertEquals("JULC0054", rejectedCode(() -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(validator)));
        String helperNamedLikeEntrypoint = """
                import java.math.BigInteger;
                @SpendingValidator
                class SharedName {
                    static boolean validate(BigInteger a) { return a.signum() > 0; }
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return validate(Builtins.unIData(redeemer));
                    }
                }""";
        assertEquals("JULC0054",
                rejectedCode(() -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(helperNamedLikeEntrypoint)));
    }

    @Test
    void libraryOverloadsAreRejected() {
        String library = """
                package com.example.lib;
                import java.math.BigInteger;
                @OnchainLibrary
                public class Limits {
                    public static boolean within(BigInteger a) { return a.compareTo(BigInteger.TEN) < 0; }
                    public static boolean within(long a) { return a < 1000; }
                }""";
        String validator = """
                import java.math.BigInteger;
                import com.example.lib.Limits;
                @SpendingValidator
                class UsesLimits {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return Limits.within(Builtins.unIData(redeemer));
                    }
                }""";
        assertEquals("JULC0054", rejectedCode(
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(validator, List.of(library))));
    }

    @Test
    void multipleDeclaratorsAreRejected() {
        assertEquals("JULC0053", rejectedCode(() -> compileMethod("""
                static final BigInteger b = BigInteger.TEN;
                static BigInteger m(BigInteger unused) { BigInteger a = BigInteger.ONE, b = BigInteger.TWO; return a.add(b); }""")));
        assertEquals("JULC0053", rejectedCode(() -> compileMethod("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger total = BigInteger.ZERO;
                    for (var x : xs) { BigInteger d = x, e = x; total = total.add(d).add(e); }
                    return total;
                }""")));
    }

    @Test
    void fieldAndParamAssignmentsInLoopsAreRejected() {
        // A loop assignment rebound the name only inside the method, so readK() still saw 0 (Java: 3).
        for (var update : List.of("K += 1;", "K = K + 1;"))
            assertEquals("JULC0056", rejectedCode(() -> compileMethod("""
                    static long K = 0;
                    static long readK() { return K; }
                    static long m(JulcList<BigInteger> xs) { for (var x : xs) { %s } return readK(); }""".formatted(update))));
        assertEquals("JULC0056", rejectedCode(() -> compileMethod("""
                static final BigInteger LIMIT = BigInteger.TEN;
                static BigInteger m(JulcList<BigInteger> xs) { for (var x : xs) { LIMIT = LIMIT.add(x); } return LIMIT; }""")));
        String validator = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                @SpendingValidator
                class ParamUpdate {
                    @Param BigInteger limit;
                    static boolean within(BigInteger v) { return v.compareTo(limit) <= 0; }
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        JulcList<PlutusData> items = Builtins.unListData(redeemer);
                        for (var item : items) { limit = limit.add(Builtins.unIData(item)); }
                        return within(BigInteger.valueOf(50));
                    }
                }""";
        assertEquals("JULC0056", rejectedCode(() -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(validator)));
    }

    @Test
    void fieldUpdatedAndReadOnlyInOneMethodMatchesJava() {
        // The loop rebinds the field within the method, which is Java's result while no other
        // method reads it.
        var result = compileMethod("""
                static long K = 0;
                static long m(JulcList<BigInteger> xs) { for (var x : xs) { K += 1; } return K; }""");
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(3))), evaluate(result,
                PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3))));
    }

    @Test
    void localShadowingAFieldIsStillALocal() {
        var result = compileMethod("""
                static long K = 5;
                static long m(JulcList<BigInteger> xs) {
                    long total = 0;
                    for (var x : xs) { long K = 1; K += 1; total = total + K; }
                    return total + K;
                }""");
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(11))), evaluate(result,
                PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3))));
    }

    @Test
    void multiVariableFieldsStillWork() {
        var result = compileMethod("""
                static final BigInteger A = BigInteger.ONE, B = BigInteger.TWO;
                static BigInteger m(BigInteger v) { return v.add(A).add(B); }""");
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(13))), evaluate(result, PlutusData.integer(10)));
    }
}
