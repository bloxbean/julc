package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacPlatform;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names the JavaParser frontend reads by spelling cannot drift from {@link SpelledTypes}. The lint parses, with javac,
 * {@code pir}, {@code resolve}, {@code validate}, every other source that reads a JavaParser node, and {@code StdlibRegistry},
 * and finds every comparison with a capitalized string literal: an {@code equals}, {@code startsWith}, {@code endsWith} or
 * {@code contains} on a name, a {@code case} label, an annotation looked up by name, and a {@code Set.of} or {@code List.of}
 * of names. Each literal is on one of {@link SpelledTypes}' lists, or on {@link #REVIEWED} with the reason it cannot diverge.
 * {@code frontend/javac} binds by element, so it is not read.
 */
class SpelledNameLintTest {

    private static final Path COMPILER = Path.of("src/main/java/org/julclang/compiler");
    private static final Path FRONTEND = COMPILER.resolve("frontend");
    private static final Path STDLIB_REGISTRY = Path.of("../julc-stdlib/src/main/java/org/julclang/stdlib/StdlibRegistry.java");

    /** A name, or the qualified form of one: its last capitalized segment is what is compared. */
    private static final Pattern NAME = Pattern.compile("\\.?(?:[a-z][a-z0-9]*\\.)*[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*");

    private static final Set<String> COMPARISONS = Set.of("equals", "startsWith", "endsWith", "contains", "containsKey");
    private static final Set<String> ANNOTATION_LOOKUPS = Set.of("getAnnotationByName", "hasAnnotation", "mightContainAnnotation");
    private static final Set<String> CONSTANTS = Set.of("of", "asList");

    /** A literal that is no name read in place of a declaration, with the reason. */
    private static final Map<String, String> REVIEWED = reviewed();

    private static Map<String, String> reviewed() {
        var reviewed = new TreeMap<String, String>();
        for (String purpose : List.of("DEFAULT", "MINT", "SPEND", "WITHDRAW", "CERTIFY", "VOTE", "PROPOSE")) {
            reviewed.put(purpose, "a constant of @Entrypoint's purpose, an element of type Purpose that javac binds");
        }
        for (String legacy : List.of("Validator", "MintingPolicy")) {
            reviewed.put(legacy, "a legacy annotation, read only to reject the class that carries it");
        }
        reviewed.put("Test", "test discovery: decides which methods a test run calls, never a lowering");
        for (String constant : List.of("ZERO", "ONE", "TWO", "TEN", "UNIT")) {
            reviewed.put(constant, "read only under the qualifier BigInteger or PlutusData, which the rule binds");
        }
        for (String scheme : List.of("Array", "JulcOptional")) {
            reviewed.put(scheme, "a type of LibraryTypeDescriptions' scheme, compared after a Java type was mapped to it");
        }
        for (String functional : List.of("Function", "BiFunction", "UnaryOperator", "BinaryOperator", "Predicate", "BiPredicate",
                "Consumer", "BiConsumer", "Supplier", "Runnable")) {
            reviewed.put(functional, "SubsetValidator's functional types, read only to reject a call through such a variable");
        }
        return reviewed;
    }

    private record Comparison(String literal, String where) {
    }

    private static List<Comparison> comparisons() throws IOException {
        var files = new ArrayList<Path>();
        files.add(STDLIB_REGISTRY);
        try (Stream<Path> walk = Files.walk(COMPILER)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                if (!file.startsWith(FRONTEND) && (isLowering(file) || Files.readString(file).contains("com.github.javaparser"))) files.add(file);
            }
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        var task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null,
                fileManager.getJavaFileObjectsFromPaths(files));
        var found = new ArrayList<Comparison>();
        var positions = Trees.instance(task).getSourcePositions();
        for (CompilationUnitTree unit : task.parse()) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                    String method = methodName(call.getMethodSelect());
                    boolean compares = COMPARISONS.contains(method);
                    if (compares || ANNOTATION_LOOKUPS.contains(method) || CONSTANTS.contains(method)) {
                        call.getArguments().forEach(this::record);
                        if (compares && call.getMethodSelect() instanceof MemberSelectTree select) record(select.getExpression());
                    }
                    return super.visitMethodInvocation(call, unused);
                }

                @Override
                public Void visitCase(CaseTree tree, Void unused) {
                    for (var label : tree.getLabels()) {
                        if (label instanceof ConstantCaseLabelTree constant) record(constant.getConstantExpression());
                    }
                    return super.visitCase(tree, unused);
                }

                private void record(ExpressionTree tree) {
                    if (tree instanceof LiteralTree literal && literal.getValue() instanceof String text && NAME.matcher(text).matches()) {
                        long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree));
                        found.add(new Comparison(text, unit.getSourceFile().getName() + ":" + line));
                    }
                }
            }.scan(unit, null);
        }
        assertTrue(diagnostics.getDiagnostics().isEmpty(), () -> "cannot lint: " + diagnostics.getDiagnostics());
        return found;
    }

    private static boolean isLowering(Path file) {
        return Stream.of("pir", "resolve", "validate").anyMatch(directory -> file.startsWith(COMPILER.resolve(directory)));
    }

    private static String methodName(ExpressionTree select) {
        return select instanceof MemberSelectTree member ? member.getIdentifier().toString() : select.toString();
    }

    private static String lastName(String literal) {
        return literal.substring(literal.lastIndexOf('.') + 1);
    }

    /** Every name on one of the rule's lists, in this build's environment. */
    private static Set<String> spelled() {
        var identities = new HashSet<>(JavacPlatform.shared().stubIdentities());
        identities.addAll(JavacFrontend.shared().bundled().digests().keySet());
        var reading = SpelledTypes.Reading.of(identities, Set.of(), Set.of());
        var names = new HashSet<>(SpelledTypes.NAMES.keySet());
        names.addAll(SpelledTypes.ANNOTATIONS.keySet());
        names.addAll(reading.libraries().keySet());
        names.addAll(reading.ledger().keySet());
        return names;
    }

    @Test
    void everyComparedNameIsOnAListOrReviewed() throws IOException {
        var spelled = spelled();
        var found = comparisons();
        assertTrue(found.size() > 100, "the lint saw too few comparisons: " + found.size());
        var unlisted = found.stream().filter(c -> !spelled.contains(lastName(c.literal())) && !REVIEWED.containsKey(lastName(c.literal())))
                .map(c -> c.where() + " " + c.literal()).collect(Collectors.toCollection(TreeSet::new));
        assertTrue(unlisted.isEmpty(), () -> "a name read by spelling that SpelledTypes does not hold:\n" + String.join("\n", unlisted));
    }

    @Test
    void noReviewedNameIsStale() throws IOException {
        var compared = comparisons().stream().map(c -> lastName(c.literal())).collect(Collectors.toSet());
        var stale = new TreeSet<>(REVIEWED.keySet());
        stale.removeAll(compared);
        assertEquals(Set.of(), stale);
    }

    @Test
    void noReviewedNameIsAlsoSpelled() {
        var both = new TreeSet<>(REVIEWED.keySet());
        both.retainAll(spelled());
        assertEquals(Set.of(), both);
    }

    /** {@code StdlibRegistry}'s keys take a short or a full class name, and either reaches a call by the qualifier's spelling. */
    @Test
    void everyClassOfTheRegistryIsOnAList() {
        var spelled = spelled();
        var classes = StdlibRegistry.defaultRegistry().allRegisteredClassNames();
        assertTrue(classes.containsAll(List.of("Math", "JulcList", "java.util.Optional", "org.julclang.stdlib.Builtins")), classes.toString());
        var unlisted = classes.stream().filter(name -> !spelled.contains(lastName(name))).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(), unlisted);
    }

    /** Every list's type is one this environment declares, so a list cannot hold a typo. */
    @Test
    void everyListedTypeExists() {
        var platform = JavacPlatform.shared();
        var missing = Stream.concat(SpelledTypes.NAMES.values().stream(), SpelledTypes.ANNOTATIONS.values().stream())
                .filter(identity -> !platform.declares(identity)).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(), missing);
    }

    /** The names {@code AccumulatorTypeAnalyzer} matches are {@code SpelledMethods}' own. */
    @Test
    void theAccumulatorCallsAreTheAnalyzersOwn() throws IOException {
        var source = Files.readString(COMPILER.resolve("pir/AccumulatorTypeAnalyzer.java"));
        var names = new TreeSet<String>();
        var calls = Pattern.compile("\"([a-z][A-Za-z]+)\"").matcher(source);
        while (calls.find()) names.add(calls.group(1));
        assertEquals(names, new TreeSet<>(SpelledMethods.ACCUMULATOR_CALLS));
    }
}
