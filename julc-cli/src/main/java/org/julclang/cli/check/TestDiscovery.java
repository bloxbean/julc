package org.julclang.cli.check;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.frontend.javac.JavacSyntax;
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
 * Two readers find the same methods (ADR-068 M6): JavaParser's, the default, and javac's parser. {@link #DEFAULT_READER}
 * is the one switch between them; M6 deletes the JavaParser reading.
 */
public final class TestDiscovery {

    private TestDiscovery() {}

    public record TestMethod(String sourceFile, String className, String methodName, String source) {}


    /** The reader this tool uses (ADR-068 M6): {@link SourceReader#configured()}. */
    public static final SourceReader DEFAULT_READER = SourceReader.configured();

    /**
     * Scan a directory for test methods.
     * A test method is a static boolean method annotated with @Test.
     */
    public static List<TestMethod> discover(Path testDir) throws IOException {
        return discover(testDir, DEFAULT_READER);
    }

    /** {@link #discover(Path)}, reading each source with {@code reader}. */
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
     * class), with their explicit modifiers, as written. A source javac's parser rejects is an error, as JavaParser's
     * strict reading is; javac's reading is the lenient one that reading falls back to, so there is no third.
     */
    private static List<TestMethod> discoverWithJavac(Path p, String source) {
        var syntax = JavacSyntax.read(source);
        if (syntax.hasErrors()) {
            throw new IllegalArgumentException("Could not parse @Test candidate " + p + ": " + syntax.errorMessages());
        }
        var results = new ArrayList<TestMethod>();
        new TreeScanner<Void, Void>() {
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
        return results;
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
