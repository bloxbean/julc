package org.julclang.cli.check;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.compiler.frontend.javac.SourceOrderScanner;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.julclang.compiler.frontend.javac.SourceReader;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers @Test-annotated static methods in test/ source files.
 * <p>
 * Two readers find the same methods (ADR-068 M6): JavaParser's, the default, and javac's parser. {@link #defaultReader()}
 * is the one switch between them; M6 deletes the JavaParser reading.
 */
public final class TestDiscovery {

    private TestDiscovery() {}

    public record TestMethod(String sourceFile, String className, String methodName, String source) {}
    /**
     * The reader this tool uses (ADR-068 M6), read when it is used: {@link SourceReader#configured()}, which fails with a
     * message naming the property for a value it does not know.
     */
    private static SourceReader defaultReader() {
        return SourceReader.configured();
    }

    /**
     * Scan a directory for test methods.
     * A test method is a static boolean method annotated with @Test.
     */
    public static List<TestMethod> discover(Path testDir) throws IOException {
        return discover(testDir, defaultReader());
    }

    /**
     * {@link #discover(Path)}, reading each source with {@code reader}.
     * <b>Internal</b> (ADR-068 M6): the reader is chosen here for the equivalence gates; this overload goes at M6.
     */
    public static List<TestMethod> discover(Path testDir, SourceReader reader) throws IOException {
        var results = new ArrayList<TestMethod>();

        if (!Files.isDirectory(testDir)) {
            return results;
        }

        try (Stream<Path> paths = Files.walk(testDir)) {
            paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            results.addAll(discover(p, Files.readString(p), reader));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read " + p, e);
                        }
                    });
        }

        return results;
    }

    /**
     * The test methods of one source, in source order: the classes and interfaces at any depth, the outer first, and
     * each one's own methods. Throws {@link IllegalArgumentException} when a source that names {@code @Test} cannot
     * be read.
     * <b>Internal</b> (ADR-068 M6): the reader is chosen here for the equivalence gates; this overload goes at M6.
     */
    public static List<TestMethod> discover(Path path, String source, SourceReader reader) {
        if (!JavaSourceIntrospector.mightContainTestAnnotation(source)) return List.of();
        return switch (reader) {
            case JAVAPARSER -> discoverWithJavaParser(path, source);
            case JAVAC -> discoverWithJavac(path, source);
        };
    }

    private static List<TestMethod> discoverWithJavaParser(Path p, String source) {
        var results = new ArrayList<TestMethod>();
        CompilationUnit cu = parseTestSource(p, source);
        if (cu == null) return results;
        for (var cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = cls.getNameAsString();
            for (var method : cls.getMethods()) {
                if (JavaSourceIntrospector.hasAnnotation(method, "Test")
                        && method.isPublic()
                        && method.isStatic()
                        && "boolean".equals(method.getTypeAsString())) {
                    results.add(new TestMethod(
                            p.toString(), className, method.getNameAsString(), source));
                }
            }
        }
        return results;
    }

    private static CompilationUnit parseTestSource(Path path, String source) {
        var result = JavaSourceParser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // Read as javac reads it, a source with no Test method only mentions the annotation: skip it.
            if (JavaSourceIntrospector.declaresNoTestMethod(source)) return null;
            var problems = result.getProblems().stream()
                    .map(problem -> problem.getMessage())
                    .toList();
            throw new IllegalArgumentException("Could not parse @Test candidate "
                    + path + ": " + problems);
        }
        return result.getResult().get();
    }

    /**
     * javac's reading: the class and interface declarations (not records, enums or annotation types, and no anonymous
     * class), with their explicit modifiers, as written, in JavaParser's order. A source javac's parser rejects is an
     * error, unless its only errors are of a later Java and no method in it carries {@code @Test}: then it only mentions
     * the annotation, as the JavaParser reading's lenient fallback decided.
     */
    private static List<TestMethod> discoverWithJavac(Path p, String source) {
        var syntax = JavacSyntax.read(source);
        var results = new ArrayList<TestMethod>();
        new SourceOrderScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree cls, Void unused) {
                boolean classOrInterface = cls.getKind() == Tree.Kind.CLASS || cls.getKind() == Tree.Kind.INTERFACE;
                if (classOrInterface && !cls.getSimpleName().isEmpty()) {
                    for (Tree member : cls.getMembers()) {
                        if (member instanceof MethodTree method && isTest(method)) {
                            results.add(new TestMethod(p.toString(), cls.getSimpleName().toString(),
                                    method.getName().toString(), source));
                        }
                    }
                }
                return super.visitClass(cls, unused);
            }
        }.scan(syntax.unit(), null);
        if (syntax.hasErrors()) {
            // Later Java in a source with no Test method only mentions the annotation: skip it, as the project scan does.
            if (syntax.onlyLanguageLevelErrors() && results.isEmpty() && !declaresTestMethod(syntax)) return List.of();
            throw new IllegalArgumentException("Could not parse @Test candidate " + p + ": " + syntax.errorMessages());
        }
        return results;
    }

    /** Whether any method, of any type or none, carries an annotation named {@code Test}. */
    private static boolean declaresTestMethod(JavacSyntax syntax) {
        boolean[] found = {false};
        new SourceOrderScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                if (method.getModifiers().getAnnotations().stream().anyMatch(TestDiscovery::isTestAnnotation)) found[0] = true;
                return super.visitMethod(method, unused);
            }
        }.scan(syntax.unit(), null);
        return found[0];
    }

    private static boolean isTest(MethodTree method) {
        var flags = method.getModifiers().getFlags();
        return flags.contains(Modifier.PUBLIC) && flags.contains(Modifier.STATIC)
                && method.getReturnType() instanceof PrimitiveTypeTree type
                && type.getPrimitiveTypeKind() == TypeKind.BOOLEAN
                && method.getModifiers().getAnnotations().stream().anyMatch(TestDiscovery::isTestAnnotation);
    }

    private static boolean isTestAnnotation(AnnotationTree annotation) {
        String name = annotation.getAnnotationType().toString();
        return name.substring(name.lastIndexOf('.') + 1).equals("Test");
    }
}
