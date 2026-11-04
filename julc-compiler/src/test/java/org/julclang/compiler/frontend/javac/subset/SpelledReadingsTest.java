package org.julclang.compiler.frontend.javac.subset;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JulcCompiler;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Whole programs the JavaParser frontend reads by a name, or past a declaration, each through the default gate. A program
 * that is rejected pins the rule that stops it, and one that compiles pins its value, which is Java's: the JVM agrees by
 * reading these sources. The other tests of this package read the gate; these read the compiler's answer.
 */
class SpelledReadingsTest {

    /** What a compilation gives: the first diagnostic's code, or a value. */
    sealed interface Outcome permits Rejected, Evaluates {
    }

    record Rejected(String code) implements Outcome {
    }

    record Evaluates(long value) implements Outcome {
    }

    private static Outcome rejected(String code) {
        return new Rejected(code);
    }

    private static Outcome rejectedUncoded() {
        return new Rejected(null);
    }

    private static Outcome value(long value) {
        return new Evaluates(value);
    }

    record Program(String label, Outcome outcome, String root, List<String> pool) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static Program program(String label, Outcome outcome, String root, String... pool) {
        return new Program(label, outcome, root, List.of(pool));
    }

    private static CompileResult compile(Program program) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAPARSER));
        return program.root().contains("@Entrypoint") ? compiler.compile(program.root(), program.pool())
                : compiler.compileMethod(program.root(), "m", program.pool());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("programs")
    void theCompilersAnswer(Program program) {
        switch (program.outcome()) {
            case Rejected(var code) -> {
                var thrown = assertThrows(CompilerException.class, () -> compile(program), program.label());
                assertEquals(code, thrown.diagnostics().getFirst().code(), thrown.getMessage());
            }
            case Evaluates(var value) -> {
                var result = compile(program);
                assertFalse(result.hasErrors(), program.label());
                var evaluation = CompilerTestVm.pv11("Java").evaluateWithArgs(result.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                        List.of(), new ExBudget(1_000_000_000L, 5_000_000L), EvalOptions.DEFAULT);
                assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(value))),
                        assertInstanceOf(EvalResult.Success.class, evaluation, program.label()).resultTerm(), program.label());
            }
        }
    }

    static Stream<Program> programs() {
        return Stream.of(
                program("a validator's nested class beside an unimported pool class of its name", rejected("JULC0100"), """
                package demo;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.*;
                @MintingValidator
                class V {
                  static class Rules { static boolean allowed() { return false; } }
                  @Entrypoint static boolean validate(PlutusData redeemer, PlutusData ctx) { return Rules.allowed(); }
                }
                """,
                """
                package demo.util;
                public class Rules { public static boolean allowed() { return true; } }
                """),
                program("a nested class beside a pool class of the same package", rejected("JULC0100"), """
                package demo;
                class P {
                  static class Foo { static long f() { return 9; } }
                  static long m() { return Foo.f(); }
                }
                """,
                """
                package demo;
                public class Foo { public static long f() { return 7; } }
                """),
                program("a nested class beside a class nested in a pool unit", rejected("JULC0100"), """
                package demo;
                class P {
                  static class Foo { static long f() { return 9; } }
                  static long m() { return Foo.f(); }
                }
                """,
                """
                package lib;
                public class Holder { public static class Foo { public static long f() { return 7; } } }
                """),
                program("a nested class beside an annotated library", rejected("JULC0100"), """
                package demo;
                class P {
                  static class Foo { static long f() { return 9; } }
                  static long m() { return Foo.f(); }
                }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Foo { public static long f() { return 7; } }
                """),
                program("a member reached through a qualifier that inherits it", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                class P {
                  static BigInteger m() { Sub.Foo f = new Sub.Foo(BigInteger.ONE, BigInteger.TWO); return f.a(); }
                }
                interface Base { record Foo(BigInteger a, BigInteger b) { } }
                record Sub(BigInteger x) implements Base { }
                record Foo(BigInteger b, BigInteger a) { }
                """),
                program("the same in a type argument", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                class P {
                  interface Base { record Foo(BigInteger a, BigInteger b) { } }
                  record Sub(BigInteger x) implements Base { }
                  record Foo(BigInteger b, BigInteger a) { }
                  static BigInteger m() {
                    JulcList<Sub.Foo> xs = JulcList.of(new Sub.Foo(BigInteger.ONE, BigInteger.TWO));
                    return xs.head().a();
                  }
                }
                """),
                program("an annotation type as a qualifier, beside a record of its name", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                class P {
                  @interface Outer { record Foo(BigInteger a, BigInteger b) { } }
                  static BigInteger m() { Outer.Foo f = new Outer.Foo(BigInteger.ONE, BigInteger.TWO); return f.a(); }
                }
                record Outer(BigInteger x) { record Foo(BigInteger b, BigInteger a) { } }
                """),
                program("a library of the root used from a pool unit, beside a pool library of its name", rejected("JULC0100"), """
                package demo;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                class P { static long m() { return Q.g(); } }
                @OnchainLibrary class Lib { static long f() { return 9; } }
                """,
                """
                package demo;
                public class Q { public static long g() { return Lib.f(); } }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Lib { public static long f() { return 7; } }
                """),
                program("a library record's type parameter beside a record of its name", rejected("JULC0100"), """
                import java.math.BigInteger;
                import lib.L;
                class P {
                  record Q(BigInteger b, BigInteger a) { }
                  static BigInteger m() { L.Box<Q> b = new L.Box<Q>(new Q(BigInteger.ONE, BigInteger.TWO)); return b.v().a(); }
                }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L {
                  public record T(java.math.BigInteger a) { }
                  public record Box<T>(T v) { }
                }
                """),
                program("the same with an integer", rejected("JULC0100"), """
                import java.math.BigInteger;
                import lib.L;
                class P {
                  static BigInteger m() { L.Box<BigInteger> b = new L.Box<BigInteger>(BigInteger.TEN); BigInteger x = b.v(); return x; }
                }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L {
                  public record T(java.math.BigInteger a) { }
                  public record Box<T>(T v) { }
                }
                """),
                program("a generic method of a library compiled as the root", rejected("JULC0100"), """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class P {
                  record T(BigInteger a) { }
                  static <T> boolean g(T x, T y) { return x.equals(y); }
                  static boolean m() { return g(BigInteger.ONE, BigInteger.ONE); }
                }
                """),
                program("new with a class body", rejected("JULC0100"), """
                import java.math.BigInteger;
                class P {
                  static BigInteger m() {
                    BigInteger x = new BigInteger("5") { @Override public BigInteger add(BigInteger o) { return BigInteger.ZERO; } };
                    return x.add(BigInteger.ONE);
                  }
                }
                """),
                program("a guard reads an inherited type", rejected("JULC0100"), """
                import java.math.BigInteger;
                class P implements B {
                  sealed interface S { record A(BigInteger v) implements S { } record C(BigInteger v) implements S { } }
                  static long m() {
                    S s = new S.A(BigInteger.ONE);
                    return switch (s) {
                      case S.A a when Q.K > 1 && a.v().signum() > 0 -> 1;
                      case S.A a -> 2;
                      case S.C c -> 3;
                    };
                  }
                }
                class Q { static final int K = 1; }
                interface B { class Q { static final int K = 5; } }
                """),
                program("a guard reads an inherited field", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.*;
                class Outer {
                  static final int K = 1;
                  interface B { int K = 5; }
                  sealed interface S { record A(BigInteger v) implements S { } record C(BigInteger v) implements S { } }
                  @MintingValidator
                  static class V implements B {
                    @Entrypoint static boolean validate(S s, PlutusData ctx) {
                      return switch (s) { case S.A a when K > 1 && a.v().signum() > 0 -> true; case S.A a -> false; case S.C c -> false; };
                    }
                  }
                  static boolean jvm() { return V.validate(new S.A(BigInteger.ONE), PlutusData.UNIT); }
                }
                """),
                program("a class of the unnamed package named like a package of the compilation", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                import lib.*;
                class P { static BigInteger m() { Foo f = new Foo(BigInteger.ONE, BigInteger.TWO); return f.a(); } }
                """,
                """
                import java.math.BigInteger;
                public class demo { public record Foo(BigInteger b, BigInteger a) { } }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public record Foo(BigInteger a, BigInteger b) { }
                """),
                program("a record of the unnamed package beside a pool record of its name", rejected("JULC0100"), """
                import java.math.BigInteger;
                class P { static BigInteger m() { Foo f = new Foo(BigInteger.ONE, BigInteger.TWO); return f.a(); } }
                record Foo(BigInteger b, BigInteger a) { }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public record Foo(BigInteger a, BigInteger b) { }
                """),
                program("a record of the unnamed package beside a library of its name", rejected("JULC0100"), """
                class P { static long m() { return Foo.f(); } }
                record Foo(long a) { static long f() { return 9; } }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Foo { public static long f() { return 7; } }
                """),
                program("a record of the unnamed package beside a @NewType of its name", rejected("JULC0100"), """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.NewType;
                class P { static long m() { return N.of(5); } }
                record N(long a) { static long of(long x) { return x + 1; } }
                class Other { @NewType record N(BigInteger v) { } }
                """),
                program("instanceof through a widening cast", rejected("JULC0100"), """
                import java.math.BigInteger;
                class P {
                  sealed interface S { record A(BigInteger v) implements S { } record C(BigInteger v) implements S { } }
                  record R(BigInteger v) { }
                  static BigInteger m() {
                    R r = new R(BigInteger.TEN);
                    if ((Object) r instanceof S.A a) { return a.v(); }
                    return BigInteger.ZERO;
                  }
                }
                """),
                program("a datum read through its Optional", rejected("JULC0101"), """
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.*;
                @SpendingValidator
                class V {
                  record Datum(boolean isPresent) { }
                  @Entrypoint
                  static boolean validate(Optional<Datum> datum, PlutusData redeemer, PlutusData ctx) {
                    if (!datum.isPresent()) { return true; }
                    return false;
                  }
                }
                """),
                program("isPresent on an Optional of a record with that component", rejected("JULC0101"), """
                import java.util.Optional;
                class P {
                  record H(boolean isPresent) { }
                  static boolean m() { Optional<H> o = Optional.of(new H(false)); return o.isPresent(); }
                }
                """),
                program("isEmpty on an Optional of a record with that component", rejected("JULC0101"), """
                import java.util.Optional;
                class P {
                  record H(boolean isEmpty) { }
                  static boolean m() { Optional<H> o = Optional.empty(); return o.isEmpty(); }
                }
                """),
                program("an inherited default method of a @NewType of an integer", rejected("JULC0101"), """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.NewType;
                class P {
                  interface I { default BigInteger abs() { return BigInteger.valueOf(42); } }
                  @NewType record N(BigInteger value) implements I { }
                  static BigInteger m() { N n = new N(BigInteger.valueOf(-5)); return n.abs(); }
                }
                """),
                program("an inherited default method of a @NewType of bytes", rejected("JULC0101"), """
                import org.julclang.stdlib.annotation.NewType;
                class P {
                  interface I { default byte[] append(byte[] x) { return x; } }
                  @NewType record N(byte[] value) implements I { }
                  static byte[] m() { N n = new N(new byte[]{1}); return n.append(new byte[]{2}); }
                }
                """),
                program("an inherited default method of a @NewType of a string", rejected("JULC0101"), """
                import org.julclang.stdlib.annotation.NewType;
                class P {
                  interface I { default int length() { return 42; } }
                  @NewType record N(String value) implements I { }
                  static long m() { N n = new N("ab"); return n.length(); }
                }
                """),
                program("a static field read on a @NewType", rejected("JULC0101"), """
                import org.julclang.stdlib.annotation.NewType;
                class P {
                  @NewType record N(byte[] value) { static final int length = 7; }
                  static long m() { N n = new N(new byte[]{1, 2, 3}); return n.length; }
                }
                """),
                program("an inherited constant read on a @NewType", rejected("JULC0101"), """
                import org.julclang.stdlib.annotation.NewType;
                class P {
                  interface I { int length = 7; }
                  @NewType record N(byte[] value) implements I { }
                  static long m() { N n = new N(new byte[]{1, 2, 3}); return n.length; }
                }
                """),
                program("clone on a byte array", rejected("JULC0101"), """
                class P { static byte[] m() { byte[] b = new byte[]{1, 2}; byte[] c = b.clone(); return c; } }
                """),
                program("toString on a ledger hash", rejected("JULC0101"), """
                import org.julclang.ledger.PubKeyHash;
                class P {
                  record R(PubKeyHash k) { }
                  static boolean m() {
                    R r = new R(new PubKeyHash(new byte[]{1}));
                    String s = r.k().toString();
                    return s.equals(r.k().toString());
                  }
                }
                """),
                program("a variant outside the interface, with instanceof", rejected("JULC0092"), """
                import java.math.BigInteger;
                class P {
                  sealed interface S { record A(BigInteger v) implements S { } }
                  record B(BigInteger v) implements S { }
                  static BigInteger m() {
                    S s = new B(BigInteger.TEN);
                    if (s instanceof S.A x) { return x.v(); }
                    return BigInteger.ZERO;
                  }
                }
                """),
                program("a variant outside the interface, with a switch", rejected("JULC0092"), """
                import java.math.BigInteger;
                class P {
                  sealed interface S { record A(BigInteger v) implements S { } }
                  record B(BigInteger v) implements S { }
                  static long m() {
                    S s = new B(BigInteger.TEN);
                    return switch (s) { case S.A x -> 1; default -> 2; };
                  }
                }
                """),
                program("no variant in the interface", rejected("JULC0092"), """
                import java.math.BigInteger;
                class P {
                  sealed interface Foo { BigInteger a(); }
                  record Z(BigInteger x, BigInteger a) implements Foo { }
                  static class Other { record Foo(BigInteger a, BigInteger b) { } }
                  static BigInteger m() { Foo f = new Z(BigInteger.ONE, BigInteger.TWO); return f.a(); }
                }
                """),
                program("a switch statement in a root", rejectedUncoded(), """
                class P { static long f(long x) { switch ((int) x) { case 1: return 100; } return 7; } static long m() { return f(1); } }
                """),
                program("a switch statement in a library", rejectedUncoded(), """
                import lib.L;
                class P { static long m() { return L.f(1); } }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L {
                  public static long f(long x) {
                    switch ((int) x) { case 1: return 100; }
                    return 7;
                  }
                }
                """),
                program("a pattern switch statement in a library", rejectedUncoded(), """
                import java.math.BigInteger;
                import lib.L;
                class P { static long m() { return L.f(new L.S.A(BigInteger.ONE)); } }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;
                @OnchainLibrary
                public class L {
                  public sealed interface S { record A(BigInteger v) implements S { } record B(BigInteger v) implements S { } }
                  public static long f(S s) {
                    switch (s) { case S.A a -> { return 100; } case S.B b -> { } }
                    return 7;
                  }
                }
                """),
                program("a call of a generic sibling in a library", rejectedUncoded(), """
                import java.math.BigInteger;
                import lib.L;
                class P { static boolean m() { return L.h(BigInteger.ONE); } }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L {
                  static <T> boolean g(T a) { return true; }
                  public static boolean h(java.math.BigInteger x) { return g(x); }
                }
                """),
                program("a nested class of a library calling its outer class", rejectedUncoded(), """
                import lib.L.In;
                class P { static long m() { return In.h(); } }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L {
                  static long g() { return 5; }
                  public static class In { public static long h() { return g(); } }
                }
                """),
                program("an operator a library cannot lower", rejectedUncoded(), """
                import lib.L;
                class P { static long m() { return L.f(7); } }
                """,
                """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L { public static long f(long x) { return x & 3; } }
                """),
                program("instanceof on a qualified variant, a member holding its last name", value(10), """
                import java.math.BigInteger;
                class P {
                  sealed interface T permits X { }
                  record X(BigInteger v) implements T { }
                  sealed interface S { record Y(BigInteger v) implements S { } record X(BigInteger v) implements S { } }
                  static BigInteger m() {
                    S s = new S.X(BigInteger.TEN);
                    if (s instanceof S.X x) { return x.v(); }
                    return BigInteger.ZERO;
                  }
                }
                """),
                program("instanceof on a qualified variant, an import holding its last name", value(10), """
                package demo;
                import java.math.BigInteger;
                import lib.T.X;
                class P {
                  sealed interface S { record Y(BigInteger v) implements S { } record X(BigInteger v) implements S { } }
                  static BigInteger m() {
                    S s = new S.X(BigInteger.TEN);
                    if (s instanceof S.X x) { return x.v(); }
                    return BigInteger.ZERO;
                  }
                  static X keep(X x) { return x; }
                }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public sealed interface T { record X(BigInteger v) implements T { } }
                """),
                program("instanceof on a variant whose name nothing else holds", value(10), """
                import java.math.BigInteger;
                class P {
                  sealed interface T permits Z { }
                  record Z(BigInteger v) implements T { }
                  sealed interface S { record Y(BigInteger v) implements S { } record X(BigInteger v) implements S { } }
                  static BigInteger m() {
                    S s = new S.X(BigInteger.TEN);
                    if (s instanceof S.X x) { return x.v(); }
                    return BigInteger.ZERO;
                  }
                }
                """),
                program("instanceof on a sibling variant of a qualified one", value(0), """
                import java.math.BigInteger;
                class P {
                  sealed interface T permits X { }
                  record X(BigInteger v) implements T { }
                  sealed interface S { record Y(BigInteger v) implements S { } record X(BigInteger v) implements S { } }
                  static BigInteger m() {
                    S s = new S.Y(BigInteger.TEN);
                    if (s instanceof S.X x) { return x.v(); }
                    return BigInteger.ZERO;
                  }
                }
                """),
                program("a permits clause listing a variant outside the interface", value(2), """
                import java.math.BigInteger;
                class P {
                  sealed interface S permits A, B { }
                  record A(BigInteger v) implements S { }
                  record B(BigInteger v) implements S { }
                  static long m() {
                    S s = new B(BigInteger.TEN);
                    return switch (s) { case A x -> 1; default -> 2; };
                  }
                }
                """),
                program("a member reached through its owner", value(1), """
                package demo;
                import java.math.BigInteger;
                class P {
                  static BigInteger m() { Base.Foo f = new Base.Foo(BigInteger.ONE, BigInteger.TWO); return f.a(); }
                }
                interface Base { record Foo(BigInteger a, BigInteger b) { } }
                record Sub(BigInteger x) implements Base { }
                record Foo(BigInteger b, BigInteger a) { }
                """),
                program("a validator's nested class beside a plain library class", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;
                @SpendingValidator
                public class V {
                    static class Policy { static boolean allow(BigInteger r) { return false; } }
                    @Entrypoint
                    public static boolean validate(BigInteger redeemer, ScriptContext ctx) { return Policy.allow(redeemer); }
                }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public class Policy { public static boolean allow(BigInteger r) { return true; } }
                """),
                program("a second top-level class beside a plain library class", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;
                @SpendingValidator
                public class V {
                    @Entrypoint
                    public static boolean validate(BigInteger redeemer, ScriptContext ctx) { return Policy.allow(redeemer); }
                }
                class Policy { static boolean allow(BigInteger r) { return false; } }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public class Policy { public static boolean allow(BigInteger r) { return true; } }
                """),
                program("an inherited member class beside a plain library class", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;
                @SpendingValidator
                public class V implements Rules {
                    @Entrypoint
                    public static boolean validate(BigInteger redeemer, ScriptContext ctx) { return Policy.allow(redeemer); }
                }
                interface Rules { class Policy { static boolean allow(BigInteger r) { return false; } } }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public class Policy { public static boolean allow(BigInteger r) { return true; } }
                """),
                program("a nested class beside the plain library class the unit imports", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;
                import lib.Policy;
                @SpendingValidator
                public class V {
                    static class Policy { static boolean allow(BigInteger r) { return false; } }
                    @Entrypoint
                    public static boolean validate(BigInteger redeemer, ScriptContext ctx) { return Policy.allow(redeemer); }
                }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public class Policy { public static boolean allow(BigInteger r) { return true; } }
                """),
                program("a nested class beside a plain class nested in a library", rejected("JULC0100"), """
                import java.math.BigInteger;
                class P {
                    static class Helper { static BigInteger f(BigInteger x) { return BigInteger.valueOf(9); } }
                    static BigInteger m() { return Helper.f(BigInteger.ONE); }
                }
                """,
                """
                package lib;
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Lib {
                    public static BigInteger g(BigInteger x) { return x; }
                    public static class Helper { public static BigInteger f(BigInteger x) { return BigInteger.valueOf(7); } }
                }
                """),
                program("a nested class beside a plain library class, by method", rejected("JULC0100"), """
                import java.math.BigInteger;
                class P {
                    static class Helper { static BigInteger f(BigInteger x) { return BigInteger.valueOf(9); } }
                    static BigInteger m() { return Helper.f(BigInteger.ONE); }
                }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public class Helper { public static BigInteger f(BigInteger x) { return BigInteger.valueOf(7); } }
                """),
                program("a qualifier beside a plain library class of its name", rejected("JULC0100"), """
                import java.math.BigInteger;
                import lib.*;
                class P {
                    static class Q { record A(BigInteger x, BigInteger y) { } }
                    static BigInteger m() { Q.A a = new Q.A(BigInteger.ONE, BigInteger.TWO); return a.x(); }
                }
                """,
                """
                package lib;
                import java.math.BigInteger;
                public class Q { public record A(BigInteger y, BigInteger x) { } }
                """),
                program("a static-imported plain class beside a same-package class", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                import static lib.Outer.Lib;
                class P {
                    static BigInteger m() { return Lib.f(BigInteger.ONE); }
                }
                """,
                """
                package lib;
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Outer {
                    public static BigInteger g(BigInteger x) { return x; }
                    public static class Lib { public static BigInteger f(BigInteger x) { return BigInteger.valueOf(7); } }
                }
                """,
                """
                package demo;
                import java.math.BigInteger;
                public class Lib { public static BigInteger f(BigInteger x) { return BigInteger.valueOf(9); } }
                """),
                program("a member through a record qualifier that inherits it", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                class P {
                    interface B { record Foo(BigInteger b, BigInteger a) { } }
                    record Q(BigInteger z) implements B { }
                    record Foo(BigInteger a, BigInteger b) { }
                    static BigInteger m() { Q.Foo f = new Q.Foo(BigInteger.ONE, BigInteger.TWO); return f.a(); }
                }
                """),
                program("a member through a record qualifier of a sealed interface", rejected("JULC0100"), """
                package demo;
                import java.math.BigInteger;
                class P {
                    sealed interface B permits Q { record Foo(BigInteger b, BigInteger a) { } }
                    record Q(BigInteger z) implements B { }
                    record Foo(BigInteger a, BigInteger b) { }
                    static BigInteger m() { Q.Foo f = new Q.Foo(BigInteger.ONE, BigInteger.TWO); return f.a(); }
                }
                """),
                program("a nested record implementing an annotation of the interface's name", rejected("JULC0092"), """
                import java.math.BigInteger;
                class P {
                    interface Other { @interface S { } }
                    sealed interface S {
                        record B(BigInteger y) implements Other.S {
                            public Class<? extends java.lang.annotation.Annotation> annotationType() { return Other.S.class; }
                        }
                        record A(BigInteger x) implements S { }
                    }
                    static boolean m(S s) { if (s instanceof S.A a) { return true; } return false; }
                }
                """),
                program("a nested record implementing a sealed interface of the interface's name", rejected("JULC0092"), """
                import java.math.BigInteger;
                class P {
                    interface Other { sealed interface S permits P.S.B { } }
                    sealed interface S {
                        record B(BigInteger y) implements Other.S { }
                        record A(BigInteger x) implements S { }
                    }
                    static boolean m(S s) { if (s instanceof S.A a) { return true; } return false; }
                }
                """),
                program("a library's static field that runs at script start", rejected("JULC0095"), """
                import java.math.BigInteger;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;
                @MintingValidator
                class V {
                    @Entrypoint static boolean validate(BigInteger redeemer, ScriptContext ctx) { return L.twice(redeemer).signum() >= 0; }
                }
                """,
                """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class L {
                    static BigInteger BAD = BigInteger.ONE.divide(BigInteger.ZERO);
                    static BigInteger twice(BigInteger x) { return x.add(x); }
                }
                """));
    }
}
