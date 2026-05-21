package com.bloxbean.julc.cli.check;

import com.bloxbean.cardano.julc.compiler.JavaSourceIntrospector;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers @Test-annotated static methods in test/ source files.
 */
public final class TestDiscovery {

    private TestDiscovery() {}

    public record TestMethod(String sourceFile, String className, String methodName, String source) {}

    /**
     * Scan a directory for test methods.
     * A test method is a static boolean method annotated with @Test.
     */
    public static List<TestMethod> discover(Path testDir) throws IOException {
        var results = new ArrayList<TestMethod>();

        if (!Files.isDirectory(testDir)) {
            return results;
        }

        try (Stream<Path> paths = Files.walk(testDir)) {
            paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            String source = Files.readString(p);
                            if (!JavaSourceIntrospector.mightContainTestAnnotation(source)) return;

                            CompilationUnit cu = parseTestSource(p, source);
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
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read " + p, e);
                        }
                    });
        }

        return results;
    }

    private static CompilationUnit parseTestSource(Path path, String source) {
        var configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        var result = new JavaParser(configuration).parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            var problems = result.getProblems().stream()
                    .map(problem -> problem.getMessage())
                    .toList();
            throw new IllegalArgumentException("Could not parse @Test candidate "
                    + path + ": " + problems);
        }
        return result.getResult().get();
    }
}
