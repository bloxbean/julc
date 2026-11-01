package org.julclang.compiler.frontend.javac.subset;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacGate;
import org.julclang.compiler.frontend.javac.JavacPlatform;
import org.julclang.compiler.frontend.javac.JavacResult;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every name the JavaParser frontend reads by spelling, crossed with every way a program can bind a name and every position
 * a name is used in: nothing compiles through the default gate unless the name binds to the type that frontend reads.
 * Javac decides what a name binds to, so each program is checked to reach the rule, by its code: a program javac itself
 * rejects would pass this test for the wrong reason. The controls are the same programs under a name no list holds, and the
 * real types.
 */
class SpelledBindingsTest {

    private static final String FREE_NAME = "Quux";
    private static final String PACKAGE = "package demo;\n";
    private static final String MEMBERS = "{ static final long K = 1; static long f(long x) { return x; } }";
    /** A record, whose accessor and component a variable of it is used through. */
    private static final String HOLDER = "record H(long K) { long f(long x) { return x; } static H make() { return new H(1); } }";

    /** A type binding: how it declares {@code %s}, in place of the class header {@code header}. */
    private enum TypeBinding {
        NESTED,
        TOP_LEVEL_SAME_UNIT,
        TOP_LEVEL_OTHER_UNIT,
        IMPORTED_LIBRARY,
        STATIC_IMPORTED,
        INHERITED;
    }

    /** A variable binding: {@code use} is an expression of type {@code long} in the scope of {@code %s}. */
    private enum VariableBinding {
        LOCAL("class P { %2$s static long m() { H %1$s = H.make(); return %3$s; } }", ""),
        PARAMETER("class P { %2$s static long g(H %1$s) { return %3$s; } static long m() { return g(H.make()); } }", ""),
        FIELD("class P { %2$s static final H %1$s = H.make(); static long m() { return %3$s; } }", ""),
        LAMBDA("class P { %2$s static long m() { java.util.function.Function<H, Long> f = %1$s -> %3$s; return 1; } }", ""),
        PATTERN("class P { %2$s static long m() { Object o = H.make(); if (o instanceof H %1$s) { return %3$s; } return 0; } }", ""),
        SWITCH_PATTERN("class P { sealed interface S permits H { } %2$s static long m(S s) { return switch (s) { case H %1$s -> %3$s; }; } }",
                " implements S"),
        INHERITED("class P implements B { %2$s static long m() { return %3$s; } } interface B { P.H %1$s = P.H.make(); }", ""),
        STATIC_IMPORTED("import static lib.Holder.%1$s; class P { static long m() { return %3$s; } }", "");

        final String program;
        final String holderSuffix;

        VariableBinding(String program, String holderSuffix) {
            this.program = program;
            this.holderSuffix = holderSuffix;
        }

        String source(String name, String use) {
            String holder = this == STATIC_IMPORTED ? "" : HOLDER.replace("record H(long K)", "record H(long K)" + holderSuffix);
            return PACKAGE + program.formatted(name, holder, use.replace("%s", name));
        }

        List<JavacFrontend.Unit> pool(String name) {
            if (this != STATIC_IMPORTED) return List.of();
            return List.of(JavacFrontend.Unit.of("""
                    package lib;
                    public class Holder {
                        public static final class H {
                            public final long K = 1;
                            public long K() { return K; }
                            public long f(long x) { return x; }
                        }
                        public static final H %s = new H();
                    }
                    """.formatted(name)));
        }
    }

    /** The positions a name of a variable takes. */
    private static final List<String> VARIABLE_USES = List.of("%s.K", "%s.f(1)", "%s.K()", "(%s).f(1)");

    /** A position a type's name takes, and the header the type needs to stand there. */
    private record TypeUse(String label, String header, String code) {
        static final String CLASS = "class %s";
    }

    private static final List<TypeUse> TYPE_USES = List.of(
            new TypeUse("qualifier of a field", TypeUse.CLASS, "static long u() { return %s.K; }"),
            new TypeUse("qualifier of a method", TypeUse.CLASS, "static long u() { return %s.f(1); }"),
            new TypeUse("type of a variable", TypeUse.CLASS, "static long u() { %s v = null; return 1; }"),
            new TypeUse("type of a parameter", TypeUse.CLASS, "static long u(%s v) { return 1; }"),
            new TypeUse("result type", TypeUse.CLASS, "static %s u() { return null; }"),
            new TypeUse("new", TypeUse.CLASS, "static Object u() { return new %s(); }"),
            new TypeUse("cast", TypeUse.CLASS, "static Object u(Object o) { return (%s) o; }"),
            new TypeUse("instanceof", TypeUse.CLASS, "static boolean u(Object o) { return o instanceof %s; }"),
            new TypeUse("pattern", TypeUse.CLASS, "static boolean u(Object o) { return o instanceof %s v; }"),
            new TypeUse("method reference", TypeUse.CLASS, "static java.util.function.Function<Long, Long> u() { return %s::f; }"),
            new TypeUse("class literal", TypeUse.CLASS, "static Object u() { return %s.class; }"),
            new TypeUse("generic argument", TypeUse.CLASS, "static long u(java.util.List<%s> v) { return 1; }"),
            new TypeUse("bound", TypeUse.CLASS, "static <T extends %s> long u(T v) { return 1; }"),
            new TypeUse("annotation", "@interface %s", "@%s static long u() { return 1; }"),
            new TypeUse("throws", "class %s extends RuntimeException", "static long u() throws %s { return 1; }"),
            new TypeUse("extends", TypeUse.CLASS, "static class Sub extends %s { }"),
            new TypeUse("implements", "interface %s", "static class Sub implements %s { }"));

    private static Set<String> identities() {
        var identities = new HashSet<>(JavacPlatform.shared().stubIdentities());
        identities.addAll(JavacFrontend.shared().bundled().digests().keySet());
        return identities;
    }

    private static final SpelledTypes.Reading READING = SpelledTypes.Reading.of(identities(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

    /** Every name read as one type, and every name of the ledger. */
    private static Stream<String> spelledNames() {
        var names = new TreeSet<>(SpelledTypes.NAMES.keySet());
        names.addAll(READING.libraries().keySet());
        names.addAll(READING.ledger().keySet());
        return names.stream();
    }

    private static JavacResult gate(String source, List<JavacFrontend.Unit> pool) {
        return JavacGate.check("compileMethod", List.of(JavacFrontend.Unit.of(source)), pool, new CompilerOptions()
                .setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.REPORT));
    }

    private static String code(String source, List<JavacFrontend.Unit> pool) {
        var result = gate(source, pool);
        return result.isAccepted() ? "accepted" : result.compilerDiagnostics().getFirst().code()
                + " " + result.compilerDiagnostics().getFirst().message();
    }

    private static void assertBoundToSomethingElse(String source, List<JavacFrontend.Unit> pool, String label, List<String> wrong) {
        String code = code(source, pool);
        if (!code.startsWith("JULC0100 ")) wrong.add(label + ": " + code + "\n" + source);
    }

    private static void assertNoneWrong(List<String> wrong) {
        assertTrue(wrong.isEmpty(), () -> wrong.size() + " wrong, the first: " + wrong.getFirst());
    }

    /** The unit of a type binding and the root that uses it. */
    private record Program(String root, List<JavacFrontend.Unit> pool) {
    }

    private static Program typeBound(TypeBinding binding, String header, String code, String name) {
        String declaration = header.formatted(name);
        boolean members = !header.startsWith("@") && !header.startsWith("interface");
        String body = members ? MEMBERS : "{ }";
        String use = code.replace("%s", name);
        return switch (binding) {
            case NESTED -> new Program(PACKAGE + "class P { static " + declaration + " " + body + " " + use
                    + " static long m() { return 1; } }", List.of());
            case TOP_LEVEL_SAME_UNIT -> new Program(PACKAGE + "class P { " + use + " static long m() { return 1; } } "
                    + declaration + " " + body, List.of());
            case TOP_LEVEL_OTHER_UNIT -> new Program(PACKAGE + "class P { " + use + " static long m() { return 1; } }",
                    List.of(unit("demo", "public " + declaration + " " + body.replace("static", "public static"))));
            case IMPORTED_LIBRARY -> new Program(PACKAGE + "import lib." + name + "; class P { " + use
                    + " static long m() { return 1; } }",
                    List.of(unit("lib", "public " + declaration + " " + body.replace("static", "public static"))));
            case STATIC_IMPORTED -> new Program(PACKAGE + "import static lib.Holder." + name + "; class P { " + use
                    + " static long m() { return 1; } }",
                    List.of(unit("lib", "public class Holder { public static " + declaration + " "
                            + body.replace("static", "public static") + " }")));
            case INHERITED -> new Program(PACKAGE + "class P implements B { " + use + " static long m() { return 1; } } "
                    + "interface B { " + declaration + " " + body + " }", List.of());
        };
    }

    private static JavacFrontend.Unit unit(String pkg, String text) {
        return JavacFrontend.Unit.of("package " + pkg + ";\n" + text);
    }

    private static final List<String> SAMPLE = List.of("BigInteger", "Math", "List", "PlutusData", "Tuple2", "PubKeyHash",
            "ListsLib", "Builtins", "Interval", "PubKeyCredential");

    /** An annotation's name is read only for {@link SpelledTypes#ANNOTATIONS}, so a ledger name is free there. */
    private static boolean readsTheName(String name, TypeUse use) {
        return !use.header().startsWith("@") || !READING.ledger().containsKey(name) || SpelledTypes.NAMES.containsKey(name);
    }

    @ParameterizedTest
    @MethodSource("spelledNames")
    void noTypeBindingOfASpelledName(String name) {
        var wrong = new ArrayList<String>();
        for (var binding : TypeBinding.values()) {
            var program = typeBound(binding, TypeUse.CLASS, "static long u() { return %s.f(1); }", name);
            assertBoundToSomethingElse(program.root(), program.pool(), name + " " + binding, wrong);
        }
        assertNoneWrong(wrong);
    }

    @ParameterizedTest
    @MethodSource("spelledNames")
    void noVariableBindingOfASpelledName(String name) {
        var wrong = new ArrayList<String>();
        for (var binding : VariableBinding.values()) {
            assertBoundToSomethingElse(binding.source(name, "%s.f(1)"), binding.pool(name), name + " " + binding, wrong);
        }
        assertNoneWrong(wrong);
    }

    @ParameterizedTest
    @MethodSource("spelledNames")
    void noTypeParameterTakesASpelledName(String name) {
        String source = PACKAGE + "class P { static <%1$s> long g(%1$s v) { return 1; } static long m() { return 1; } }".formatted(name);
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void everyPositionOfATypeBinding() {
        var wrong = new ArrayList<String>();
        for (String name : SAMPLE) {
            for (var use : TYPE_USES) {
                if (!readsTheName(name, use)) continue;
                for (var binding : TypeBinding.values()) {
                    var program = typeBound(binding, use.header(), use.code(), name);
                    assertBoundToSomethingElse(program.root(), program.pool(), name + " " + use.label() + " " + binding, wrong);
                }
            }
        }
        assertNoneWrong(wrong);
    }

    @Test
    void everyPositionOfAVariableBinding() {
        var wrong = new ArrayList<String>();
        for (String name : SAMPLE) {
            for (String use : VARIABLE_USES) {
                for (var binding : VariableBinding.values()) {
                    assertBoundToSomethingElse(binding.source(name, use), binding.pool(name), name + " " + use + " " + binding, wrong);
                }
            }
        }
        assertNoneWrong(wrong);
    }

    @Test
    void aTypeParameterInEveryPositionItTakes() {
        var wrong = new ArrayList<String>();
        for (String name : SAMPLE) {
            for (String body : List.of("%s v = null; return 1;", "Object o = (%s) null; return 1;",
                    "java.util.List<%s> v = null; return 1;")) {
                String source = PACKAGE + "class P { static <%1$s> long g() { ".formatted(name) + body.replace("%s", name)
                        + " } static long m() { return 1; } }";
                assertBoundToSomethingElse(source, List.of(), name + " " + body, wrong);
            }
        }
        assertNoneWrong(wrong);
    }

    // --- The controls ---

    /** Javac accepted it, and this rule said nothing: an inherited field's initializer is another rule's. */
    private static boolean compilesPastThisRule(String source, List<JavacFrontend.Unit> pool) {
        String code = code(source, pool);
        return !code.startsWith("JULC0100 ") && !code.startsWith("JULC0085 ");
    }

    /** The same programs, under a name no list holds. */
    @Test
    void everyProgramCompilesUnderAFreeName() {
        var wrong = new ArrayList<String>();
        for (var use : TYPE_USES) {
            for (var binding : TypeBinding.values()) {
                if (binding == TypeBinding.STATIC_IMPORTED) continue;
                var program = typeBound(binding, use.header(), use.code(), FREE_NAME);
                if (!compilesPastThisRule(program.root(), program.pool())) {
                    wrong.add(use.label() + " " + binding + ": " + code(program.root(), program.pool()) + "\n" + program.root());
                }
            }
        }
        for (var binding : VariableBinding.values()) {
            for (String use : VARIABLE_USES) {
                if (!compilesPastThisRule(binding.source(FREE_NAME, use), binding.pool(FREE_NAME))) {
                    wrong.add(use + " " + binding + ": " + code(binding.source(FREE_NAME, use), binding.pool(FREE_NAME)));
                }
            }
        }
        assertNoneWrong(wrong);
    }

    /** What an annotation is called decides nothing unless it is one of JuLC's. */
    @Test
    void aUserAnnotationOfALedgerNameIsLeftAlone() {
        assertEquals("accepted", code("class P { @interface Interval { } @Interval static long m() { return 1; } }", List.of()));
    }

    // --- Qualified forms ---

    /** {@code resolveClassType} and {@code new} read the last name, so a qualifier does not change what is read. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static class Outer { static class Math { static int abs(int x) { return -5; } } } static boolean m() { return Outer.Math.abs(-5) == 5; }",
            "static class Outer { record BigInteger(long a) { } } static Outer.BigInteger g; static long m() { return 1; }",
            "static class Outer { record BigInteger(long a) { } } static long m() { return new Outer.BigInteger(1).a(); }",
            "static class Outer { record List(long a) { } } static long g(Outer.List v) { return v.a(); } static long m() { return 1; }",
            "static class Outer { record Tuple2(long a) { } } static long m() { return new P.Outer.Tuple2(1).a(); }",
            "record H(long a) { } H BigInteger = new H(1); long g() { return this.BigInteger.a(); } static long m() { return 1; }",
            "record H(long a) { static final long ONE = 1; } static final H BigInteger = new H(1); static long m() { return P.BigInteger.a(); }"})
    void aQualifiedNameBindsToo(String members) {
        String source = "class P { " + members + " }";
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> source + ": " + code(source, List.of()));
    }

    /** {@code resolveTypeWithScope} reads a qualified ledger name through its qualifier, so it stands on that. */
    @Test
    void aQualifiedLedgerNameGoesThroughItsQualifier() {
        assertEquals("accepted", code("class P { static class Outer { record Interval(long a) { } } static long m() { return new Outer.Interval(1).a(); } }", List.of()));
    }

    private static Stream<Map.Entry<String, String>> oneTypeNames() {
        return Stream.concat(SpelledTypes.NAMES.entrySet().stream(), READING.libraries().entrySet().stream())
                .sorted(Map.Entry.comparingByKey());
    }

    /** Each name, bound to the type the frontend reads, by an import and by its qualified name. */
    @ParameterizedTest
    @MethodSource("oneTypeNames")
    void theRealTypeOfEachNameIsAccepted(Map.Entry<String, String> name) {
        String imported = "import " + name.getValue() + ";\nclass P { static long u(" + name.getKey()
                + " v) { return 1; } static long m() { return 1; } }";
        assertEquals("accepted", code(imported, List.of()), imported);
        String qualified = "class P { static long u(" + name.getValue() + " v) { return 1; } static long m() { return 1; } }";
        assertEquals("accepted", code(qualified, List.of()), qualified);
    }

    @ParameterizedTest
    @MethodSource("oneTypeNames")
    void aVariableOfAnotherNameLeavesTheRealTypeAlone(Map.Entry<String, String> name) {
        String source = "import " + name.getValue() + ";\nclass P { static long m(" + name.getKey() + " some) { return 1; } }";
        assertEquals("accepted", code(source, List.of()), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long m() { return Math.abs(-5L); }",
            "static java.math.BigInteger m() { return java.math.BigInteger.ONE; }",
            "static org.julclang.core.PlutusData m() { return org.julclang.core.PlutusData.UNIT; }",
            "static long m(org.julclang.ledger.Value v) { return 1; }",
            "static long m(org.julclang.ledger.Credential.PubKeyCredential c) { return 1; }",
            "static long m(org.julclang.ledger.Delegatee.Vote v) { return 1; }"})
    void theRealTypesInUse(String method) {
        String source = "class P { " + method + " }";
        assertEquals("accepted", code(source, List.of()), source);
    }

    @Test
    void aCallOfTheBuiltinsOwnMethodIsLeftAlone() {
        assertEquals("accepted", code("""
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.Builtins;
                class P { static long m() { PlutusData d = Builtins.mkNilData(); return 1; } }
                """, List.of()));
    }

    /** A type of that name in the ledger, reached by an import, and a member of its sealed interface. */
    @Test
    void aLedgerNameOnItsOwnType() {
        String source = """
                import org.julclang.ledger.*;
                class P {
                    static long m(Value v, Credential.PubKeyCredential c, Delegatee.Vote w, Vote x) { return 1; }
                }
                """;
        assertEquals("accepted", code(source, List.of()));
    }

    /** Nested or packaged, that frontend registers the record, and reads it ahead of the implicit import. */
    @ParameterizedTest
    @ValueSource(strings = {"Rational", "Constitution", "Address", "TxOut"})
    void aRecordOfALedgerNameThatIsNestedOrPackaged(String name) {
        String nested = "class P { record %1$s(long a) { } static long m() { return new %1$s(1).a(); } }".formatted(name);
        assertEquals("accepted", code(nested, List.of()), nested);
        String packaged = "package demo;\nrecord %1$s(long a) { } class P { static long m() { return new %1$s(1).a(); } }".formatted(name);
        assertEquals("accepted", code(packaged, List.of()), packaged);
        String sealed = "class P { sealed interface %1$s permits A { } record A() implements %1$s { } static long m(%1$s s) { return 1; } }"
                .formatted(name);
        assertEquals("accepted", code(sealed, List.of()), sealed);
    }

    private static Stream<String> ledgerNames() {
        return READING.ledger().keySet().stream().sorted();
    }

    /** The implicit ledger import wins in the unnamed package, whatever the kind of the type. */
    @ParameterizedTest
    @MethodSource("ledgerNames")
    void noTopLevelTypeOfTheUnnamedPackageTakesALedgerName(String name) {
        var wrong = new ArrayList<String>();
        for (String declaration : List.of("record %s(long a) { }", "sealed interface %s permits A { } record A() implements %<s { }",
                "class %s { }", "interface %s { }", "@interface %s { }")) {
            String source = declaration.formatted(name) + " class P { static long m() { return 1; } }";
            assertBoundToSomethingElse(source, List.of(), source, wrong);
        }
        var library = JavacFrontend.Unit.of("""
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class %s { public static long f(long x) { return x; } }
                """.formatted(name));
        assertBoundToSomethingElse("class P { static long m() { return %s.f(1); } }".formatted(name), List.of(library), name + " library", wrong);
        assertNoneWrong(wrong);
    }

    @Test
    void aClassOfTheUnnamedPackageUnderItsOwnNameIsLeftAlone() {
        assertEquals("accepted", code("class Ledger { static long m() { return 1; } }", List.of()));
        assertEquals("accepted", code("record Foo(long a) { } class P { static long m() { return 1; } }", List.of()));
    }

    /** A nested type's owner is a type, so the rule of the unnamed package is not its rule. */
    @Test
    void aNestedClassOfTheUnnamedPackageIsNotTopLevel() {
        assertEquals("accepted", code("class P { static class Credential { } static long m() { return 1; } }", List.of()));
    }

    @Test
    void aPackagedTypeOfALedgerNameIsNotInTheUnnamedPackage() {
        assertEquals("accepted", code("package demo;\nclass Credential { } class P { static long m() { return 1; } }", List.of()));
    }

    @Test
    void aLibraryOfAnotherUnitTakesALedgerName() {
        var library = JavacFrontend.Unit.of("""
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Interval {
                    public static long f(long x) { return x; }
                }
                """);
        assertEquals("accepted", code("import lib.Interval;\nclass P { static long m() { return Interval.f(1); } }", List.of(library)));
    }

    @Test
    void aLibraryInTheRootDoesNotTakeALedgerName() {
        String source = """
                import org.julclang.stdlib.annotation.OnchainLibrary;
                class P {
                    @OnchainLibrary static class Interval { static boolean always() { return false; } }
                    static boolean m() { return Interval.always(); }
                }
                """;
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void anUnusedVariableOfASpelledNameIsNoUse() {
        assertEquals("accepted", code("class P { record H(long a) { } static long m() { H Math = new H(1); return 1; } }", List.of()));
    }

    /** An API type of a spelled name is still another type: the frontend reads the name, not the qualifier. */
    @ParameterizedTest
    @ValueSource(strings = {
            "class P { static long m(org.julclang.core.DefaultUni.String v) { return 1; } }",
            "class P { static long m(org.julclang.core.DefaultUni.Integer v) { return 1; } }",
            "import org.julclang.core.DefaultUni.String;\nclass P { static long m(String v) { return 1; } }"})
    void anApiMemberOfASpelledNameIsAnotherType(String source) {
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void anImportAloneIsNoUseOfAnApiMember() {
        assertEquals("accepted", code("import org.julclang.core.DefaultUni.String;\nclass P { static long m() { return 1; } }", List.of()));
    }

    /** A package is no type in that name's place, and {@code resolveClassType} reads the last name. */
    @Test
    void aPackageOfASpelledNameIsNoBinding() {
        var library = JavacFrontend.Unit.of("package lib.Math;\npublic class Holder { }");
        assertEquals("accepted", code("class P { static long m(lib.Math.Holder h) { return 1; } }", List.of(library)));
    }

    /** A type's namespace is not a method's: {@code Math(1)} calls a method. */
    @Test
    void aMethodOfASpelledNameIsNoBinding() {
        assertEquals("accepted", code("class P { static long Math(long x) { return x; } static long m() { return Math(1); } }", List.of()));
        assertEquals("accepted", code("class P { record H(long Math) { } static long m(H h) { return h.Math(); } }", List.of()));
    }

    /** A variable and the real type under one name: a type position still reads the type. */
    @Test
    void aTypePositionBesideAVariableBindsTheType() {
        assertEquals("accepted", code("""
                import java.math.BigInteger;
                class P { record H(long a) { } static BigInteger m() { H BigInteger = new H(1); BigInteger other = null; return other; } }
                """, List.of()));
    }

    // --- A plain type beside a registered one ---

    @Test
    void aPlainClassDoesNotShareARecordsName() {
        String source = """
                class P {
                    static class Foo { Foo(long a) { } long a() { return 9; } }
                    static class Other { record Foo(long a) { } }
                    static long m() { Foo f = new Foo(5); return f.a(); }
                }
                """;
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    /** That frontend registers a library's records whether or not javac reads the unit. */
    @Test
    void aPlainClassDoesNotShareTheNameOfARecordOfAnUnreadUnit() {
        var unread = JavacFrontend.Unit.of("package lib;\npublic class Lib { public record Foo(long a) { } }");
        String source = "class P { static class Foo { long a() { return 9; } } static long m() { Foo f = null; return f.a(); } }";
        assertTrue(code(source, List.of(unread)).startsWith("JULC0100 "), () -> code(source, List.of(unread)));
    }

    @Test
    void aPlainInterfaceDoesNotShareASealedInterfacesName() {
        String source = """
                class P {
                    interface Foo { }
                    static class Other { sealed interface Foo permits A { } record A() implements Foo { } }
                    static long m(Foo f) { return 1; }
                }
                """;
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void aPlatformTypeDoesNotShareARecordsName() {
        String source = """
                import java.util.Set;
                class P {
                    static class Other { record Set(long a) { } }
                    static long m(Set<Long> s) { return 1; }
                }
                """;
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void aPlainClassDoesNotShareALibrarysName() {
        var library = JavacFrontend.Unit.of("""
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Lib { public static long f(long x) { return 7; } }
                """);
        String source = """
                import lib.Lib;
                class P {
                    static class Lib { static long f(long x) { return 9; } }
                    static long m() { return Lib.f(1); }
                }
                """;
        assertTrue(code(source, List.of(library)).startsWith("JULC0100 "), () -> code(source, List.of(library)));
        assertEquals("accepted", code("import lib.Lib;\nclass P { static long m() { return Lib.f(1); } }", List.of(library)));
    }

    private static final String TWO_READINGS = """
            class P {
                record H(long abs) { long f(long x) { return 3; } }
                static long m() { H %s = new H(3); return %<s.f(-1); }
            }
            """;

    /** Every class of a library unit is compiled, so a call through a variable of its name reads the class's method. */
    @ParameterizedTest
    @ValueSource(strings = {
            "package lib; public class Helper { public static long f(long x) { return 7; } }",
            "package lib; public class Outer { public static class Helper { public static long f(long x) { return 7; } } }",
            "package lib; import org.julclang.stdlib.annotation.OnchainLibrary; @OnchainLibrary public class Helper { public static long f(long x) { return 7; } }"})
    void aVariableDoesNotTakeTheNameOfAClassOfAnUnreadLibraryUnit(String library) {
        var pool = List.of(JavacFrontend.Unit.of(library));
        String source = TWO_READINGS.formatted("Helper");
        var options = new CompilerOptions().setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.OFF);
        assertFalse(new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(source, "m", List.of(library)).hasErrors());
        assertTrue(code(source, pool).startsWith("JULC0100 "), () -> code(source, pool));
    }

    /** The rule is one rule: nothing but a library unit's class diverges today, and any type offered is its name. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static class Helper { }",
            "record Helper(long a) { }",
            "sealed interface Helper permits A { } record A() implements Helper { }",
            "static class Outer { static class Helper { } }"})
    void aVariableDoesNotTakeTheNameOfATypeOfItsUnit(String declaration) {
        String source = "class P { " + declaration + " record H(long v) { } static long m() { H Helper = new H(3); return Helper.v(); } }";
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void aVariableBindingOfAnOfferedNameInEveryForm() {
        var pool = List.of(JavacFrontend.Unit.of("package other;\npublic class Offered { }"));
        var wrong = new ArrayList<String>();
        for (var binding : VariableBinding.values()) {
            var units = new ArrayList<>(binding.pool("Offered"));
            units.addAll(pool);
            assertBoundToSomethingElse(binding.source("Offered", "%s.f(1)"), units, binding.name(), wrong);
        }
        assertNoneWrong(wrong);
    }

    /** A variable's plain read goes through the symbol table, but one rule is simpler than two, and no real code writes it. */
    @Test
    void aPlainReadOfAVariableOfAnOfferedNameIsTheSameRule() {
        String source = "class P { record Foo(long a) { } static long m() { long Foo = 1; return Foo + 1; } }";
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void aVariableOfAnotherNameIsLeftAlone() {
        assertEquals("accepted", code("class P { static class Helper { } record H(long v) { } static long m() { H helper = new H(3); return helper.v(); } }", List.of()));
    }

    /** A field of another type goes through its qualifier, which is no reading of the name. */
    @Test
    void aFieldSelectedByQualifierIsNotThisRule() {
        assertEquals("accepted", code("""
                class P {
                    static class Helper { }
                    record H(long Helper) { }
                    static long m() { H h = new H(1); return h.Helper(); }
                }
                """, List.of()));
        assertEquals("accepted", code("""
                class P {
                    static class Helper { }
                    record H(long Helper) { }
                    static long m() { H h = new H(1); return h.Helper; }
                }
                """, List.of()));
    }

    @Test
    void aClassOfALibraryUnitIsReadAsItself() {
        var library = JavacFrontend.Unit.of("package lib;\npublic class Helper { public static long f(long x) { return 7; } }");
        assertEquals("accepted", code("import lib.Helper;\nclass P { static long m() { return Helper.f(1); } }", List.of(library)));
    }

    @Test
    void aPlainClassDoesNotShareTheNameOfAClassOfALibraryUnit() {
        var library = JavacFrontend.Unit.of("package lib;\npublic class Helper { public static long f(long x) { return 7; } }");
        String source = "class P { static class Helper { static long f(long x) { return 9; } } static long m() { return Helper.f(1); } }";
        assertTrue(code(source, List.of(library)).startsWith("JULC0100 "), () -> code(source, List.of(library)));
    }

    /** A class of a library unit reads its own helper: the frontend compiles both. */
    @Test
    void aLibraryUnitReadsItsOwnClasses() {
        var library = JavacFrontend.Unit.of("""
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Lib {
                    static class Helper { static long g(long x) { return x; } }
                    public static long f(long x) { return Helper.g(x); }
                }
                """);
        assertEquals("accepted", code("import lib.Lib;\nclass P { static long m() { return Lib.f(1); } }", List.of(library)));
    }

    /** The units {@code JavaLibraryProvider} compiles are its roots, and they are libraries. */
    @Test
    void aRootOfALibraryCompilationIsALibraryUnit() {
        String source = """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Lib {
                    static class Helper { static long g(long x) { return x; } }
                    public static long f(long x) { return Helper.g(x); }
                }
                """;
        var result = JavacGate.check("JavaLibraryProvider", List.of(JavacFrontend.Unit.of(source)), List.of(),
                new CompilerOptions().setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.REPORT));
        assertTrue(result.isAccepted(), () -> result.compilerDiagnostics().toString());
    }

    @Test
    void aTypeParameterDoesNotShareARecordsName() {
        String source = "class P { record Foo(long a) { } static <Foo> boolean g(Foo x, Foo y) { return x.equals(y); } static long m() { return 1; } }";
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    /** Specialization binds a library's generic method, and a compilation of libraries is what specializes it. */
    @Test
    void aLibrarysMethodTypeParameterIsBoundWhenItIsSpecialized() {
        var result = JavacGate.check("JavaLibraryProvider", List.of(JavacFrontend.Unit.of("""
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class L {
                    record T(long a) { }
                    static <T> boolean g(T ignored) { return true; }
                }
                """)), List.of(), new CompilerOptions().setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.REPORT));
        assertTrue(result.isAccepted(), () -> result.compilerDiagnostics().toString());
    }

    /** Nothing specializes a record's type parameter, or a method of a library that is compiled as a root. */
    @Test
    void noOtherTypeParameterIsBound() {
        var library = JavacFrontend.Unit.of("""
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L {
                    public record T(long a) { }
                    public record Box<T>(T v) { }
                }
                """);
        String box = "import lib.L;\nclass P { static long m(L.Box<Long> b) { return 1; } }";
        assertTrue(code(box, List.of(library)).startsWith("JULC0100 "), () -> code(box, List.of(library)));
        String root = """
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class P {
                    record T(long a) { }
                    static <T> boolean g(T ignored) { return true; }
                    static long m() { return 1; }
                }
                """;
        assertTrue(code(root, List.of()).startsWith("JULC0100 "), () -> code(root, List.of()));
    }

    @Test
    void anImportAloneIsNoUse() {
        var library = JavacFrontend.Unit.of("package lib;\npublic class Holder { public static final long BigInteger = 1; }");
        assertEquals("accepted", code("import static lib.Holder.BigInteger;\nclass P { static long m() { return 1; } }", List.of(library)));
    }

    // --- What the frontend's resolver does not hold ---

    @Test
    void anInheritedRecordIsWrittenWithItsQualifier() {
        var library = JavacFrontend.Unit.of("package lib;\npublic record Foo(long a, long b) { }");
        String inherited = """
                package demo;
                import lib.Foo;
                class P implements B { static long m() { Foo f = new Foo(1, 2); return f.a(); } }
                interface B { record Foo(long b, long a) { } }
                """;
        assertTrue(code(inherited, List.of(library)).startsWith("JULC0100 "), () -> code(inherited, List.of(library)));
        String qualified = """
                package demo;
                class P implements B { static long m() { B.Foo f = new B.Foo(1, 2); return f.a(); } }
                interface B { record Foo(long b, long a) { } }
                """;
        assertEquals("accepted", code(qualified, List.of(library)));
    }

    /** A class of a library unit is registered, so a static import of one is outside the resolver as well. */
    @Test
    void aStaticImportedClassIsWrittenWithItsQualifier() {
        var program = typeBound(TypeBinding.STATIC_IMPORTED, TypeUse.CLASS, "static long u() { return %s.f(1); }", FREE_NAME);
        assertTrue(code(program.root(), program.pool()).startsWith("JULC0100 "), () -> code(program.root(), program.pool()));
        String qualified = PACKAGE + "import lib.Holder;\nclass P { static long u() { return Holder.%s.f(1); } static long m() { return 1; } }"
                .formatted(FREE_NAME);
        assertEquals("accepted", code(qualified, program.pool()));
    }

    @Test
    void aStaticImportedRecordIsWrittenWithItsQualifier() {
        var holder = JavacFrontend.Unit.of("package lib;\npublic class Holder { public record Foo(long b, long a) { } }");
        var sameNamed = JavacFrontend.Unit.of("package demo;\nrecord Foo(long a, long b) { }");
        String source = """
                package demo;
                import static lib.Holder.Foo;
                class P { static long m() { Foo f = new Foo(1, 2); return f.a(); } }
                """;
        assertTrue(code(source, List.of(holder, sameNamed)).startsWith("JULC0100 "), () -> code(source, List.of(holder, sameNamed)));
    }

    @Test
    void aMemberTypeInScopeOrImportedIsLeftAlone() {
        var holder = JavacFrontend.Unit.of("package lib;\npublic class Holder { public record Foo(long b, long a) { } }");
        assertEquals("accepted", code("import lib.Holder.Foo;\nclass P { static long m(Foo f) { return 1; } }", List.of(holder)));
        assertEquals("accepted", code("import lib.Holder.*;\nclass P { static long m(Foo f) { return 1; } }", List.of(holder)));
        assertEquals("accepted", code("class P { record Foo(long a) { } static long m(Foo f) { return 1; } }", List.of()));
        assertEquals("accepted", code("class P { static class Q { record Foo(long a) { } static long g(Foo f) { return 1; } } static long m() { return 1; } }", List.of()));
    }

    // --- A sealed interface's variants ---

    @Test
    void twoVariantsOfOneInterfaceDoNotShareAName() {
        String source = """
                class P {
                    sealed interface S permits A.X, B.X { }
                    static class A { record X(long v) implements S { } }
                    static class B { record X(long v) implements S { } }
                    static long m() { return 1; }
                }
                """;
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void variantsOfTwoInterfacesMayShareAName() {
        assertEquals("accepted", code("""
                class P {
                    sealed interface S { record X(long v) implements S { } }
                    sealed interface T { record X(long v) implements T { } }
                    static long m() { return 1; }
                }
                """, List.of()));
    }

    /** The ledger's own type, written out, beside a record of that name. */
    @Test
    void aQualifiedLedgerTypeBesideARecordOfItsName() {
        assertEquals("accepted", code("class P { record Rational(long a) { } static long m(org.julclang.ledger.Rational r) { return 1; } }", List.of()));
        assertEquals("accepted", code("class P { record Vote(long a) { } static long m(org.julclang.ledger.Delegatee.Vote v) { return 1; } }", List.of()));
    }

    @Test
    void anAnnotationBesideARecordOfItsNameIsLeftAlone() {
        assertEquals("accepted", code("""
                import org.julclang.stdlib.annotation.*;
                class P {
                    static class Other { record Param(long a) { } }
                    @Param static long p = 1;
                    static long m() { return p; }
                }
                """, List.of()));
    }

    @Test
    void anApiClassDoesNotShareARecordsName() {
        String source = """
                import org.julclang.core.Constant;
                class P {
                    static class Other { record Constant(long a) { } }
                    static long m(Constant c) { return 1; }
                }
                """;
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    @Test
    void aPlainClassUnderItsOwnNameIsLeftAlone() {
        assertEquals("accepted", code("class P { static class Foo { } static long m(Foo f) { return 1; } }", List.of()));
    }

    /** The same simple name twice among Data types is the frontend's own ambiguity to report. */
    @Test
    void aRecordBesideARecordOfItsNameIsNotThisRule() {
        assertEquals("accepted", code("class P { record Foo(long a) { } static class Other { record Foo(long a) { } } static long m() { return 1; } }",
                List.of()));
    }

    // --- A type key, and the datum's text ---

    @ParameterizedTest
    @MethodSource("typeKeys")
    void aRecordDoesNotTakeATypeKey(String key) {
        String source = "class P { record %s(long a) { } static long m() { return 1; } }".formatted(key);
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    private static Stream<String> typeKeys() {
        var keys = new TreeSet<>(SpelledMethods.REGISTRY.typeKeys());
        assertTrue(keys.containsAll(List.of("ListType", "IntegerType", "Value")), keys.toString());
        return keys.stream();
    }

    /** A class that is a variant is lowered as a record of its name, so it reads the same rows. */
    @ParameterizedTest
    @MethodSource("typeKeys")
    void aClassDoesNotTakeATypeKey(String key) {
        String source = "package demo; class P { sealed interface S permits %1$s { } static final class %1$s implements S { } static long m() { return 1; } }"
                .formatted(key);
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    /** A sum's rows are {@code SumType}'s, whatever the interface is called, and a plain interface has no lowering. */
    @ParameterizedTest
    @MethodSource("typeKeys")
    void anInterfaceOfATypeKeyNameIsLeftAlone(String key) {
        String sealed = "package demo; class P { sealed interface %1$s permits A { } record A(long size) implements %1$s { } static long m() { return 1; } }"
                .formatted(key);
        assertEquals("accepted", code(sealed, List.of()), sealed);
        String plain = "package demo; class P { interface %s { } static long m() { return 1; } }".formatted(key);
        assertEquals("accepted", code(plain, List.of()), plain);
    }

    private static final String VALIDATOR = """
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.*;
            @SpendingValidator
            class V {
                record OptionalThing(long a) { }
                @Entrypoint
                static boolean validate(%s datum, PlutusData redeemer, PlutusData context) { return true; }
            }
            """;

    private static JavacResult validator(String datum) {
        return JavacGate.check("compile", List.of(JavacFrontend.Unit.of("import java.util.Optional;\n" + VALIDATOR.formatted(datum))),
                List.of(), new CompilerOptions().setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.REPORT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OptionalThing", "java.util.Optional<OptionalThing>"})
    void theDatumsTextAgreesWithItsType(String datum) {
        var result = validator(datum);
        assertFalse(result.isAccepted(), datum);
        assertEquals("JULC0100", result.compilerDiagnostics().getFirst().code(), datum);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PlutusData", "Optional<OptionalThing>", "Optional<PlutusData>"})
    void aDatumWhoseTextAgrees(String datum) {
        assertTrue(validator(datum).isAccepted(), datum);
    }

    /** With two parameters the first is the redeemer, which nothing reads by its text. */
    @Test
    void aRedeemerIsNotReadByItsText() {
        var result = JavacGate.check("compile", List.of(JavacFrontend.Unit.of("""
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.*;
                @SpendingValidator
                class V {
                    record OptionalThing(long a) { }
                    @Entrypoint
                    static boolean validate(OptionalThing redeemer, PlutusData context) { return true; }
                }
                """)), List.of(), new CompilerOptions().setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.REPORT));
        assertTrue(result.isAccepted());
    }

    // --- Annotations ---

    private static Stream<String> annotationNames() {
        return SpelledTypes.ANNOTATIONS.keySet().stream().sorted();
    }

    @ParameterizedTest
    @MethodSource("annotationNames")
    void noUserAnnotationTakesAnAnnotationsName(String name) {
        String simple = "class P { @interface %1$s { } @%1$s static long u() { return 1; } static long m() { return 1; } }".formatted(name);
        assertTrue(code(simple, List.of()).startsWith("JULC0100 "), () -> code(simple, List.of()));
        String qualified = "class P { @P.%1$s static long u() { return 1; } @interface %1$s { } static long m() { return 1; } }".formatted(name);
        assertTrue(code(qualified, List.of()).startsWith("JULC0100 "), () -> code(qualified, List.of()));
    }

    /** Only an annotation's position reads that name: a record {@code Param} is a record. */
    @ParameterizedTest
    @MethodSource("annotationNames")
    void aRecordOfAnAnnotationsNameIsLeftAlone(String name) {
        String source = "class P { record %1$s(long a) { } static long m(%1$s r) { return r.a(); } }".formatted(name);
        assertEquals("accepted", code(source, List.of()), source);
    }

    // --- Whole compilations, each of which reaches a lowering of the name's spelling with the gate off ---

    private static String rejection(String source) {
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m"));
        return thrown.diagnostics().getFirst().code();
    }

    private static boolean compilesWithTheGateOff(String source) {
        return !new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.OFF)).compileMethod(source, "m").hasErrors();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static class Math { static long abs(long x) { return -5; } } static boolean m() { return Math.abs(-5) == 5; }",
            "record H(long x) { static final long ONE = 0; } static boolean m() { H BigInteger = new H(0); return BigInteger.ONE == 1; }",
            "static class Builtins { static long iData(long x) { return 7; } } static long m() { return Builtins.iData(3); }",
            "static class MathLib { static long abs(long x) { return 7; } } static long m() { return MathLib.abs(-3); }",
            "record H(long v) { long abs(long x) { return 9; } } static long m() { H MathLib = new H(3); return MathLib.abs(-1); }",
            "record H(boolean always) { } static boolean m() { H Interval = new H(false); return Interval.always(); }",
            "record H(long v) { long abs(long x) { return 3; } } static long g(H Math) { return Math.abs(9); } static long m() { return g(new H(1)); }",
            "static class Rational { Rational(long a, long b) { } long numerator() { return 9; } } static long m() { Rational r = new Rational(5, 6); return r.numerator(); }",
            "static class Foo { Foo(long a) { } long a() { return 9; } } static class Other { record Foo(long a) { } } static long m() { Foo f = new Foo(5); return f.a(); }",
            "record OptionalType(boolean isPresent) { } static boolean m() { return new OptionalType(true).isPresent(); }",
            "@interface Param { } @Param static long p = 5; static long m() { return p; }",
            "sealed interface S permits A.X, B.X { } static class A { record X(long v) implements S { } } static class B { record X(long v) implements S { } } static long m() { S s = new A.X(1); return switch (s) { case B.X b -> 5; default -> 7; }; }"})
    void aWholeCompilationThatOtherwiseCompiles(String members) {
        String source = "class P { " + members + " }";
        assertTrue(compilesWithTheGateOff(source), source);
        assertEquals("JULC0100", rejection(source), source);
    }

    @Test
    void aCallOfAnAccumulatorsNameThatOtherwiseCompiles() {
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.Builtins;
                class P {
                    static PlutusData tailList(PlutusData x) { return x; }
                    static BigInteger m() {
                        PlutusData cursor = Builtins.iData(BigInteger.ONE);
                        long i = 0;
                        while (i < 2) { cursor = tailList(cursor); i = i + 1; }
                        return Builtins.unIData(cursor);
                    }
                }
                """;
        assertTrue(compilesWithTheGateOff(source), source);
        assertEquals("JULC0101", rejection(source), source);
    }

    // --- Only the gate has this rule ---

    /** The javac frontend binds a name to its element: its answer for these sources is its own lowering's, never this rule. */
    @ParameterizedTest
    @ValueSource(strings = {
            "class P { static class Math { static int abs(int x) { return -5; } } static boolean m() { return Math.abs(-5) == 5; } }",
            "class P { record H(int x) { static final int ONE = 0; } static boolean m() { H BigInteger = new H(0); return BigInteger.ONE == 1; } }"})
    void theJavacFrontendHasNoSuchRule(String source) {
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        try {
            compiler.compileMethod(source, "m");
        } catch (CompilerException e) {
            assertNotEquals("JULC0100", e.diagnostics().getFirst().code(), e.getMessage());
        }
        var wrong = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m"));
        assertEquals("JULC0100", wrong.diagnostics().getFirst().code());
    }

    /** {@code JavaLibraryProvider}'s roots are libraries, so a variable there meets the names of their classes too. */
    @Test
    void aVariableOfAClassOfALibraryRoot() {
        String source = """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Lib {
                    record H(long v) { }
                    static class Helper { }
                    public static long f() { H Helper = new H(1); return Helper.v(); }
                }
                """;
        var result = JavacGate.check("JavaLibraryProvider", List.of(JavacFrontend.Unit.of(source)), List.of(),
                new CompilerOptions().setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.REPORT));
        assertEquals("JULC0100", result.compilerDiagnostics().getFirst().code());
    }

    /** Every class of a library root is registered, so a type parameter does not take its name. */
    @Test
    void aTypeParameterOfAClassOfALibraryRoot() {
        var result = JavacGate.check("JavaLibraryProvider", List.of(JavacFrontend.Unit.of("""
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class L {
                    static class Helper { }
                    record Box<Helper>(Helper v) { }
                }
                """)), List.of(), new CompilerOptions().setJavacGate(org.julclang.compiler.frontend.javac.JavacGateMode.REPORT));
        assertEquals("JULC0100", result.compilerDiagnostics().getFirst().code());
    }

    /** The bundled sources are offered with the pool, though no name of theirs outside the implicit imports is on a list. */
    @Test
    void aVariableDoesNotTakeTheNameOfABundledUnit() {
        String source = "class P { record H(long v) { } static long m() { H TestContextLib = new H(1); return TestContextLib.v(); } }";
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> code(source, List.of()));
    }

    /** A constant the class itself declares, read in a guard, is the frontend's reading too. */
    @Test
    void aGuardReadsAConstantOfAnEnclosingClass() {
        assertEquals("accepted", code("""
                class Outer {
                    static final int K = 5;
                    sealed interface S { record A(long v) implements S { } record C(long v) implements S { } }
                    static class V {
                        static final int L = 5;
                        static long m(S s) { return switch (s) { case S.A a when K > L || a.v() > 0 -> 1; case S.A a -> 2; case S.C c -> 3; }; }
                    }
                }
                """, List.of()));
    }

    @Test
    void aGuardReadsAnImportedMemberType() {
        var holder = JavacFrontend.Unit.of("package lib;\npublic class Holder { public static class Q { public static final int K = 5; } }");
        assertEquals("accepted", code("""
                import lib.Holder.Q;
                class P {
                    sealed interface S { record A(long v) implements S { } record C(long v) implements S { } }
                    static long m(S s) { return switch (s) { case S.A a when Q.K > 1 && a.v() > 0 -> 1; case S.A a -> 2; case S.C c -> 3; }; }
                }
                """, List.of(holder)));
    }

    /** The operand of {@code instanceof}, in every position. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean f(S s) { return s instanceof S.A; }",
            "static boolean f(S s) { return s instanceof S.A a; }",
            "static boolean f(S.A a) { return a instanceof S.A; }",
            "static boolean f(S s) { var t = s; return t instanceof S.C c; }"})
    void anOperandOfTheVariantOrItsInterface(String member) {
        String source = "class P { sealed interface S { record A(long v) implements S { } record C(long v) implements S { } } " + member + " static long m() { return 1; } }";
        assertEquals("accepted", code(source, List.of()), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean f(Object o) { return o instanceof S.A; }",
            "static boolean f(Object o) { return o instanceof S.A a; }",
            "static <T> boolean f(T t) { return t instanceof S.A; }",
            "static boolean f(Record r) { return r instanceof S.A; }"})
    void anyOtherOperandOfAVariant(String member) {
        String source = "class P { sealed interface S { record A(long v) implements S { } record C(long v) implements S { } } " + member + " static long m() { return 1; } }";
        assertTrue(code(source, List.of()).startsWith("JULC0100 "), () -> source + ": " + code(source, List.of()));
    }

    @Test
    void instanceofOfWhatIsNoVariantIsNotThisRule() {
        assertEquals("accepted", code("class P { record R(long a) { } static boolean f(Object o) { return o instanceof R; } static long m() { return 1; } }", List.of()));
    }

    /** A library in the pool runs with the gate off, so the rule is what stops the wrong method. */
    @Test
    void aWholeCompilationThroughAVariableOfALibrarysName() {
        String library = "package lib; public class Helper { public static long f(long x) { return 7; } }";
        String source = TWO_READINGS.formatted("Helper");
        var wrong = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m", List.of(library)));
        assertEquals("JULC0100", wrong.diagnostics().getFirst().code());
    }

    /** The message names the spelling, what the frontend reads and what the program holds. */
    @Test
    void theMessageSaysWhatEachSideIs() {
        var result = gate("class P { record H(long a) { long abs(long x) { return x; } } static long g(H Math) { return Math.abs(1); } static long m() { return 1; } }", List.of());
        var message = result.compilerDiagnostics().getFirst().message();
        assertTrue(message.contains("Math") && message.contains("java.lang.Math") && message.contains("the parameter Math"), message);
        assertTrue(result.compilerDiagnostics().getFirst().line() > 0, message);
    }

}
