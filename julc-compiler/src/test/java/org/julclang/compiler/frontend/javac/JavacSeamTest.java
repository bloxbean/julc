package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.compiler.resolve.TypeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D2, I12: what the javac frontend's seam allows. It holds a {@link TypeRegistry}, which has no lookup by simple
 * name, so {@link TypeResolver}'s simple-name paths are out of reach by the compiler's rules, not by discipline (F840).
 * Attribution is asked by tree; a {@code TreePath} stays inside {@link JavacFacts} (F841).
 */
class JavacSeamTest {

    private static final Path MAIN = Path.of("src/main/java/org/julclang/compiler");
    private static final Set<String> BY_SIMPLE_NAME = Set.of("resolveNameToType", "lookupRecord", "lookupSumType",
            "lookupSumTypeForVariant", "isNewType", "isRegistered", "resolveName", "resolveClassName",
            "resolveTypeWithScope", "getNewTypeNames");

    private static JavacProgram read(String source) {
        return JavacProgram.analyze(List.of(JavacFrontend.Unit.of(source)), List.of());
    }

    @Test
    void theRegistryHasNoLookupByName() {
        var methods = Arrays.stream(TypeRegistry.class.getDeclaredMethods()).map(Method::getName).toList();
        assertTrue(methods.stream().noneMatch(BY_SIMPLE_NAME::contains), methods.toString());
        // TypeResolver is where they are, so this list is live: a name that moved would not be checked.
        var resolver = Arrays.stream(TypeResolver.class.getDeclaredMethods()).map(Method::getName).toList();
        assertTrue(resolver.containsAll(Set.of("resolveNameToType", "lookupRecord", "lookupSumType", "isNewType", "isRegistered")));
    }

    @Test
    void theFrontendHoldsARegistryNotAResolver() throws Exception {
        assertEquals(TypeRegistry.class, JavacProgram.class.getMethod("registry").getReturnType());
        assertEquals(TypeRegistry.class, JavacFacts.class.getMethod("registry").getReturnType());
        assertEquals(TypeRegistry.class, read("class P {}").registry().getClass(), "not a TypeResolver behind the type");
    }

    @Test
    void noFrontendSourceNamesTheResolverOrJavaParser() throws IOException {
        var offenders = new ArrayList<String>();
        try (Stream<Path> sources = Files.list(MAIN.resolve("frontend/javac"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                if (text.contains("com.github.javaparser") || text.contains("TypeResolver")) offenders.add(source.toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolve/TypeRegistry.java", "resolve/TypeGroups.java", "backend/LibraryOperations.java"})
    void theNeutralClassesImportNoJavaParser(String file) throws IOException {
        assertFalse(Files.readString(MAIN.resolve(file)).contains("com.github.javaparser"), file);
    }

    /** F840's three probes, as they read through the seam: identity, and no way to ask by simple name. */
    @Test
    void aUserHashNameBindsTheUsersRecord() {
        var registry = read("record PubKeyHash(byte[] h) {}\nclass P {}").registry();
        assertInstanceOf(PirType.RecordType.class, registry.lookupNominal("PubKeyHash").orElseThrow());
    }

    @Test
    void twoNestedRecordsOfOneNameAreTwoIdentities() {
        var registry = read("class A { record Foo(byte[] a) {} } class B { record Foo(boolean b) {} } class P {}").registry();
        assertEquals("a", ((PirType.RecordType) registry.lookupNominal("A.Foo").orElseThrow()).fields().getFirst().name());
        assertEquals("b", ((PirType.RecordType) registry.lookupNominal("B.Foo").orElseThrow()).fields().getFirst().name());
        assertTrue(registry.lookupNominal("Foo").isEmpty(), "a simple name is not an identity");
    }

    @Test
    void aNestedUserCredentialIsItsOwnIdentity() {
        var registry = read("class P { sealed interface Credential permits A {} record A() implements Credential {} }").registry();
        assertInstanceOf(PirType.SumType.class, registry.lookupNominal("P.Credential").orElseThrow());
        assertEquals(2, ((PirType.SumType) registry.lookupNominal("org.julclang.ledger.Credential").orElseThrow()).constructors().size());
        assertTrue(registry.lookupNominal("Credential").isEmpty());
    }

    @Test
    void attributionIsAskedByTree() {
        var exposed = new ArrayList<String>();
        for (Method method : JavacFacts.class.getMethods()) {
            for (Class<?> type : method.getParameterTypes()) {
                if (type.getSimpleName().equals("TreePath")) exposed.add(method.getName());
            }
            if (method.getReturnType().getSimpleName().equals("TreePath")) exposed.add(method.getName());
        }
        assertEquals(List.of(), exposed);
    }

    @Test
    void parentsAndEnclosingTreesComeFromTheTree() {
        var program = read("""
                class P {
                    static boolean m(boolean a) {
                        boolean b = a;
                        return b;
                    }
                }
                """);
        var facts = program.facts();
        var local = new VariableTree[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                if (tree.getName().contentEquals("b")) local[0] = tree;
                return super.visitVariable(tree, null);
            }
        }.scan(program.roots().getFirst(), null);
        assertEquals(Tree.Kind.BLOCK, facts.parent(local[0]).getKind());
        var method = assertInstanceOf(MethodTree.class, facts.enclosing(local[0], Tree.Kind.METHOD));
        assertEquals("m", method.getName().toString());
        var type = assertInstanceOf(ClassTree.class, facts.enclosing(local[0], Tree.Kind.CLASS, Tree.Kind.RECORD));
        assertEquals("P", type.getSimpleName().toString());
        assertNull(facts.enclosing(local[0], Tree.Kind.ENUM));
        assertNull(facts.parent(program.roots().getFirst()));
    }

    @Test
    void aTreeOfNoReadUnitIsAnInternalError() {
        var other = read("class Q {}").roots().getFirst();
        var e = assertThrows(IllegalStateException.class, () -> read("class P {}").facts().element(other));
        assertTrue(e.getMessage().contains("not a tree of a unit javac read"), e.getMessage());
    }

    /** F844: the fragment is what the user wrote, on one line, cut as the JavaParser frontend cuts its own. */
    @Test
    void aFragmentIsTheSourceAsWritten() {
        var program = read("""
                class V {
                    static boolean m(boolean a) {
                        var   b
                            = a;
                        return b;
                    }
                    static boolean longest(boolean first, boolean second, boolean third, boolean fourth, boolean fifth, boolean sixth) {
                        return first;
                    }
                }
                """);
        var facts = program.facts();
        var fragments = new ArrayList<String>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                if (tree.getName().contentEquals("b")) fragments.add(facts.location(tree).fragment());
                return super.visitVariable(tree, null);
            }

            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                if (tree.getName().contentEquals("longest")) fragments.add(facts.location(tree).fragment());
                return super.visitMethod(tree, null);
            }

            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                fragments.add(facts.location(tree).fragment());
                return super.visitClass(tree, null);
            }
        }.scan(program.roots().getFirst(), null);
        String wholeClass = fragments.get(0);
        assertEquals(100, wholeClass.length());
        assertTrue(wholeClass.startsWith("class V { static boolean m(boolean a) { var b = a;"), wholeClass);
        assertTrue(wholeClass.endsWith("..."), wholeClass);
        assertFalse(wholeClass.contains("super()"), "javac's synthesized constructor is not in the text");
        assertEquals("var b = a;", fragments.get(1));
        assertEquals(100, fragments.get(2).length());
        assertTrue(fragments.get(2).endsWith("..."));
    }
}
