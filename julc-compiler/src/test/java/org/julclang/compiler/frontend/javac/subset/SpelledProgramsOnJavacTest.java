package org.julclang.compiler.frontend.javac.subset;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Programs the JavaParser frontend reads by a name, on {@code frontend=JAVAC}, where a name is what javac bound: each one
 * stops at {@code JULC0084}, takes a rule both frontends share, or evaluates to the JVM's answer. It never compiles to
 * another answer. The JVM is the oracle: the sources run through javac here and a real class is called, so no expected
 * value in this file is written by hand.
 * <p>
 * A slice that ports a construct moves rows here from {@link Stops} to {@link Agrees}, which is the point of the table:
 * each program that starts to compile meets the JVM the moment it does. Every program of {@link SpelledReadingsTest} has a
 * row, and so has each program of the review's sources that a statement reaches.
 */
class SpelledProgramsOnJavacTest {

    /** What {@code frontend=JAVAC} does with a program. */
    private sealed interface Answer permits Stops, Rejects, Agrees, Views {
    }

    /** {@code JULC0084}, at this construct. */
    private record Stops(String construct) implements Answer {
    }

    /** A rule of the javac frontend. */
    private record Rejects(String code) implements Answer {
    }

    /** It compiles, and evaluates to what the JVM returns. */
    private record Agrees() implements Answer {
    }

    /** It compiles, to the reference's bytes, and the JVM throws: the one such row, D6's view. */
    private record Views() implements Answer {
    }

    private static Answer stops(String construct) {
        return new Stops(construct);
    }

    private static Answer rejects(String code) {
        return new Rejects(code);
    }

    private static final Answer AGREES = new Agrees();
    /**
     * What the reference says in words with no code: a call of a class that is no library, a type it cannot read, or a
     * statement it does not lower.
     */
    private static final Answer UNDEFINED = rejects(null);

    /** What each program of {@link SpelledReadingsTest} does here. */
    private static final Map<String, Answer> SPELLED = Map.ofEntries(
            entry("a validator's nested class beside an unimported pool class of its name", UNDEFINED),
            entry("a nested class beside a pool class of the same package", UNDEFINED),
            entry("a nested class beside a class nested in a pool unit", UNDEFINED),
            entry("a nested class beside an annotated library", UNDEFINED),
            entry("a member reached through a qualifier that inherits it", AGREES),
            entry("the same in a type argument", AGREES),
            entry("an annotation type as a qualifier, beside a record of its name", stops("ANNOTATION_TYPE")),
            entry("a library of the root used from a pool unit, beside a pool library of its name", UNDEFINED),
            entry("a library record's type parameter beside a record of its name", UNDEFINED),
            entry("the same with an integer", UNDEFINED),
            entry("a generic method of a library compiled as the root", rejects("JULC0087")),
            entry("new with a class body", stops("a new with a class body")),
            entry("a guard reads an inherited type", AGREES),
            entry("a guard reads an inherited field", stops("METHOD_INVOCATION")),
            entry("a class of the unnamed package named like a package of the compilation", AGREES),
            entry("a record of the unnamed package beside a pool record of its name", AGREES),
            entry("a record of the unnamed package beside a library of its name", UNDEFINED),
            entry("a record of the unnamed package beside a @NewType of its name", stops("PLUS")),
            entry("instanceof through a widening cast", stops("TYPE_CAST")),
            entry("a datum read through its Optional", AGREES),
            entry("isPresent on an Optional of a record with that component", AGREES),
            entry("isEmpty on an Optional of a record with that component", AGREES),
            entry("an inherited default method of a @NewType of an integer", stops("METHOD_INVOCATION")),
            entry("an inherited default method of a @NewType of bytes", rejects("JULC0055")),
            entry("an inherited default method of a @NewType of a string", rejects("JULC0055")),
            entry("a static field read on a @NewType", rejects("JULC0055")),
            entry("an inherited constant read on a @NewType", rejects("JULC0055")),
            entry("clone on a byte array", stops("the method clone of a byte string")),
            entry("toString on a ledger hash", stops("the method toString of a byte string")),
            entry("a variant outside the interface, with instanceof", rejects("JULC0092")),
            entry("a variant outside the interface, with a switch", rejects("JULC0092")),
            entry("no variant in the interface", rejects("JULC0092")),
            entry("a switch statement in a root", stops("TYPE_CAST")),
            entry("a switch statement in a library", stops("TYPE_CAST")),
            entry("a pattern switch statement in a library", UNDEFINED),
            entry("a call of a generic sibling in a library", UNDEFINED),
            entry("a nested class of a library calling its outer class", stops("a call of g that is not a static method of this class")),
            entry("an operator a library cannot lower", stops("AND")),
            entry("instanceof on a qualified variant, a member holding its last name", AGREES),
            entry("instanceof on a qualified variant, an import holding its last name", AGREES),
            entry("instanceof on a variant whose name nothing else holds", AGREES),
            entry("instanceof on a sibling variant of a qualified one", AGREES),
            entry("a permits clause listing a variant outside the interface", AGREES),
            entry("a member reached through its owner", AGREES),
            entry("a validator's nested class beside a plain library class", UNDEFINED),
            entry("a second top-level class beside a plain library class", UNDEFINED),
            entry("an inherited member class beside a plain library class", UNDEFINED),
            entry("a nested class beside the plain library class the unit imports", UNDEFINED),
            entry("a nested class beside a plain class nested in a library", stops("METHOD_INVOCATION")),
            entry("a nested class beside a plain library class, by method", stops("METHOD_INVOCATION")),
            entry("a qualifier beside a plain library class of its name", AGREES),
            entry("a static-imported plain class beside a same-package class", AGREES),
            entry("a member through a record qualifier that inherits it", AGREES),
            entry("a member through a record qualifier of a sealed interface", AGREES),
            entry("a nested record implementing an annotation of the interface's name", stops("ANNOTATION_TYPE")),
            entry("a nested record implementing a sealed interface of the interface's name", rejects("JULC0092")),
            entry("a library's static field that runs at script start", rejects("JULC0095")));

    /**
     * One program.
     *
     * @param oracle    a unit only the JVM reads, whose {@code J.run()} gives the answer; null to call {@code m()}
     * @param arguments what the script is applied to
     * @param validator whether {@code root} compiles as a validator: {@code J.run()} is then its accept or reject
     */
    private record Case(String label, Answer answer, String root, List<String> pool, String oracle, List<PlutusData> arguments,
                        boolean validator) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static PlutusData integer(long value) {
        return PlutusData.integer(value);
    }

    private static final String QUALIFIED = """
            import java.math.BigInteger;
            class P {
              sealed interface T permits X { }
              record X(BigInteger v) implements T { }
              sealed interface S { record Y(BigInteger v) implements S { } record X(BigInteger v) implements S { } }
              static BigInteger m(S s) {
                if (s instanceof S.X x) { return x.v(); }
                return BigInteger.ZERO;
              }
            }
            """;

    private static String oracle(String body) {
        return "class J { static Object run() { return " + body + "; } }";
    }

    private static Case call(String label, String root, String oracle, PlutusData... arguments) {
        return new Case(label, AGREES, root, List.of(), oracle, List.of(arguments), false);
    }

    /** The script's context for a spending validator, with its datum. */
    private static PlutusData spending(PlutusData maybeDatum) {
        var ref = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
        return PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(0), PlutusData.constr(1, ref, maybeDatum));
    }

    private static final String OPTIONAL_DATUM = """
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
            """;

    private static final Case ABSENT_DATUM = new Case("an Optional's own method, not a component of its name, when absent", AGREES,
            OPTIONAL_DATUM, List.of(), oracle("V.validate(java.util.Optional.empty(), org.julclang.core.PlutusData.UNIT, org.julclang.core.PlutusData.UNIT)"),
            List.of(spending(PlutusData.constr(1))), true);

    private static final String INTEGER_IMPORT = "import java.math.BigInteger;\n";

    private static final String LIST = "org.julclang.core.types.JulcList.of(java.math.BigInteger.ONE, java.math.BigInteger.TWO)";
    private static final PlutusData ELEMENTS = PlutusData.list(integer(1), integer(2));
    /** D6: {@code PlutusData.cast} is an unchecked view, and Java throws where the script reads the {@code Optional}. */
    private static final Answer AGREES_NOT = new Views();

    private static String lambdas(String members) {
        return "import java.math.BigInteger;\nimport java.util.Optional;\nimport org.julclang.core.PlutusData;\nimport org.julclang.core.types.JulcList;\nclass P {\n"
                + members + "}\n";
    }

    private static String onList(String call) {
        return oracle(call.formatted(LIST));
    }

    /** The programs of this slice that can run now, each over a sealed value passed as Data. */
    private static Stream<Case> runnable() {
        return Stream.of(
                new Case("a widening cast over a parameter", stops("TYPE_CAST"), """
                        import java.math.BigInteger;
                        class P {
                          sealed interface S { record A(BigInteger v) implements S { } record C(BigInteger v) implements S { } }
                          record R(BigInteger v) { }
                          static BigInteger m(R r) {
                            if ((Object) r instanceof S.A a) { return a.v(); }
                            return BigInteger.ZERO;
                          }
                        }
                        """, List.of(), null, List.of(), false),
                call("a qualified variant beside a member record of its name", QUALIFIED,
                        oracle("P.m(new P.S.X(BigInteger.TEN))").replace("class J", INTEGER_IMPORT + "class J"),
                        PlutusData.constr(1, integer(10))),
                call("the sibling of a qualified variant", QUALIFIED,
                        oracle("P.m(new P.S.Y(BigInteger.TEN))").replace("class J", INTEGER_IMPORT + "class J"),
                        PlutusData.constr(0, integer(10))),
                call("a qualified variant whose name nothing else holds", QUALIFIED.replace("permits X", "permits Z").replace("record X(BigInteger v) implements T", "record Z(BigInteger v) implements T"),
                        oracle("P.m(new P.S.X(BigInteger.TEN))").replace("class J", INTEGER_IMPORT + "class J"),
                        PlutusData.constr(1, integer(10))),
                ABSENT_DATUM,
                new Case("an Optional's own method, not a component of its name, when present", AGREES, OPTIONAL_DATUM, List.of(),
                        oracle("V.validate(java.util.Optional.of(new V.Datum(true)), org.julclang.core.PlutusData.UNIT, org.julclang.core.PlutusData.UNIT)"),
                        List.of(spending(PlutusData.constr(0, PlutusData.constr(0, PlutusData.constr(1))))), true),
                new Case("the same with a component of another name", AGREES, OPTIONAL_DATUM.replace("Datum(boolean isPresent)", "Datum(boolean flag)"),
                        List.of(), oracle("V.validate(java.util.Optional.of(new V.Datum(true)), org.julclang.core.PlutusData.UNIT, org.julclang.core.PlutusData.UNIT)"),
                        List.of(spending(PlutusData.constr(0, PlutusData.constr(0, PlutusData.constr(1))))), true),
                call("a lambda's parameter named like a stdlib class", lambdas("""
                          static boolean m(JulcList<BigInteger> xs) { return xs.any(Builtins -> Builtins.signum() > 0); }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a lambda's parameter named like its own type", lambdas("""
                          static boolean m(JulcList<BigInteger> xs) { return xs.any(BigInteger -> BigInteger.signum() > 0); }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a lambda's parameter named like the list library", lambdas("""
                          static boolean m(JulcList<BigInteger> xs) { return xs.map(ListsLib -> ListsLib.add(ListsLib)).size() > 0; }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a lambda's parameter named like a static field", lambdas("""
                          static final BigInteger K = BigInteger.TEN;
                          static boolean m(JulcList<BigInteger> xs) { return xs.any(K -> K.signum() > 1); }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a lambda's parameter named like a method it calls", lambdas("""
                          static boolean positive(BigInteger a) { return a.signum() > 0; }
                          static boolean m(JulcList<BigInteger> xs) { return xs.all(positive -> positive(positive)); }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a lambda's local named like a stdlib class", lambdas("""
                          static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { var JulcList = x.add(x); return JulcList.signum() > 0; }); }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a static method named like a list method", lambdas("""
                          static boolean any(BigInteger a) { return a.signum() < 0; }
                          static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> any(x)); }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a record's component named like a list method", lambdas("""
                          record R(JulcList<BigInteger> map) { }
                          static boolean m(JulcList<BigInteger> xs) { var r = new R(xs); return r.map().any(x -> x.signum() > 0); }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a record's component named hash, in a lambda", lambdas("""
                          record R(byte[] hash) { }
                          static boolean m(JulcList<BigInteger> xs) { var r = new R(new byte[]{1}); return xs.any(x -> r.hash().length > 0); }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a variant's name in a lambda, beside a parameter of that name", lambdas("""
                          sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } }
                          static boolean m(JulcList<BigInteger> xs, S s) { return xs.any(A -> { if (s instanceof S.A v) { return true; } return false; }); }
                        """), oracle("P.m(%s, new P.S.A(java.math.BigInteger.ONE))".formatted(LIST)), ELEMENTS, PlutusData.constr(0, integer(1))),
                new Case("a lambda in parentheses", stops("a function that is no lambda at the call"), lambdas("""
                          static boolean m(JulcList<BigInteger> xs) { return xs.any((x -> x.signum() > 0)); }
                        """), List.of(), null, List.of(), false),
                new Case("a lambda's parameter written var", stops("a lambda parameter written var"), lambdas("""
                          static boolean m(JulcList<BigInteger> xs) { return xs.any((var x) -> x.signum() > 0); }
                        """), List.of(), null, List.of(), false),
                new Case("find read through a view of an Optional", AGREES_NOT, lambdas("""
                          static boolean m(JulcList<BigInteger> xs) { return PlutusData.cast(xs.find(x -> true), Optional.class).isPresent(); }
                        """), List.of(), null, List.of(ELEMENTS), false),
                call("find at its element", lambdas("""
                          static boolean m(JulcList<BigInteger> xs) { BigInteger r = xs.find(x -> x.signum() > 0); return r.signum() > 0; }
                        """), onList("P.m(%s)"), ELEMENTS),
                call("a local named like a library method's class", """
                        import java.math.BigInteger;
                        class P {
                          static boolean m(BigInteger a, BigInteger b) { BigInteger Shadow = a; return Shadow.compareTo(b) > 0; }
                        }
                        """, oracle("P.m(java.math.BigInteger.TEN, java.math.BigInteger.ONE)"), integer(10), integer(1)));
    }

    private static Stream<Case> programs() {
        var spelled = SpelledReadingsTest.programs().map(program -> {
            var run = program.root().equals(OPTIONAL_DATUM) ? ABSENT_DATUM : null;
            return new Case(program.label(), SPELLED.get(program.label()), program.root(), program.pool(),
                    run == null ? null : run.oracle(), run == null ? List.of() : run.arguments(), program.root().contains("@Entrypoint"));
        });
        return Stream.concat(spelled, runnable());
    }

    private static CompileResult compile(Case program) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
        return program.validator() ? compiler.compile(program.root(), program.pool())
                : compiler.compileMethod(program.root(), "m", program.pool());
    }

    /** Every program of the other table has a row here, so a program added there has an answer on this frontend. */
    @Test
    void aRowPerProgram() {
        var labels = SpelledReadingsTest.programs().map(SpelledReadingsTest.Program::label).collect(Collectors.toSet());
        assertEquals(labels, SPELLED.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("programs")
    void itStopsOrAgrees(Case program, @TempDir Path directory) throws Exception {
        switch (program.answer()) {
            case Stops(var construct) -> assertEquals(construct,
                    assertThrows(NotPorted.class, () -> compile(program), program.label()).construct());
            case Rejects(var code) -> {
                var failure = assertThrows(CompilerException.class, () -> compile(program), program.label());
                assertFalse(failure instanceof NotPorted, failure::getMessage);
                // A rejection in the reference's words alone, such as Undefined variable, can have no diagnostic.
                assertEquals(code, failure.diagnostics().isEmpty() ? null : failure.diagnostics().getFirst().code(), failure::getMessage);
            }
            case Agrees ignored -> assertEquals(jvm(program, directory), script(program), program.label());
            case Views ignored -> assertEquals("true", script(program), program.label());
        }
    }

    /** Evaluation meets the JVM on this many programs: a table of stops alone would prove nothing. */
    @Test
    void somethingRuns() {
        assertTrue(programs().filter(program -> program.answer() instanceof Agrees).count() >= 20);
    }

    // --- The script ---

    private static String script(Case program) {
        var result = compile(program);
        assertFalse(result.hasErrors(), program.label());
        var evaluation = CompilerTestVm.pv11("Java").evaluateWithArgs(result.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                program.arguments(), new ExBudget(1_000_000_000L, 5_000_000L), EvalOptions.DEFAULT);
        if (program.validator()) return Boolean.toString(evaluation instanceof EvalResult.Success);
        var term = assertInstanceOf(Term.Const.class, assertInstanceOf(EvalResult.Success.class, evaluation, program.label()).resultTerm());
        return switch (term.value()) {
            case Constant.BoolConst value -> Boolean.toString(value.value());
            case Constant.IntegerConst value -> value.value().toString();
            default -> term.value().toString();
        };
    }

    // --- The JVM ---

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern PUBLIC_TYPE = Pattern.compile("(?m)^\\s*public\\s+(?:sealed\\s+)?(?:class|record|interface)\\s+(\\w+)");
    private static final Pattern FIRST_CLASS = Pattern.compile("(?m)^\\s*(?:public\\s+)?class\\s+(\\w+)");

    /** What javac and the JVM say: a real compilation, a real class, a real call. */
    private static String jvm(Case program, Path directory) throws Exception {
        var units = new ArrayList<>(List.of(program.root()));
        units.addAll(program.pool());
        if (program.oracle() != null) units.add(program.oracle());
        var files = new ArrayList<Path>();
        for (int i = 0; i < units.size(); i++) files.add(written(units.get(i), directory.resolve("src" + i)));
        var classes = Files.createDirectory(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, manager, null, List.of("-proc:none", "-Xlint:none", "-d", classes.toString(), "-classpath",
                    System.getProperty("java.class.path")), null, manager.getJavaFileObjectsFromPaths(files));
            assertTrue(task.call(), "javac rejects " + program.label());
        }
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, SpelledProgramsOnJavacTest.class.getClassLoader())) {
            String prefix = PACKAGE.matcher(program.root()).results().map(found -> found.group(1) + ".").findFirst().orElse("");
            var first = FIRST_CLASS.matcher(program.root());
            assertTrue(first.find(), program.label());
            var target = program.oracle() != null ? loader.loadClass("J") : loader.loadClass(prefix + first.group(1));
            var method = target.getDeclaredMethod(program.oracle() != null ? "run" : "m");
            method.setAccessible(true);
            return String.valueOf(method.invoke(null));
        }
    }

    private static Path written(String unit, Path root) throws Exception {
        var pack = PACKAGE.matcher(unit).results().map(found -> found.group(1)).findFirst().orElse("");
        var type = PUBLIC_TYPE.matcher(unit).results().map(found -> found.group(1)).findFirst().orElse("Unit");
        var directory = Files.createDirectories(root.resolve(pack.replace('.', '/')));
        return Files.writeString(directory.resolve(type + ".java"), unit);
    }
}
