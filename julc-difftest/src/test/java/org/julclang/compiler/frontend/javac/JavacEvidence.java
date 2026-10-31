package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Evidence tooling for ADR-068 M2: what the tools need of a program's trees, by name. Never a compilation path. */
public final class JavacEvidence {

    private JavacEvidence() {
    }

    /** The position of the closing brace of the unit's first class (the JavaParser frontend's method target), or -1. */
    public static int firstClassEnd(JavacProgram program) {
        var unit = program.roots().getFirst();
        var first = new ClassTree[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                if (first[0] == null && tree.getKind() == Tree.Kind.CLASS) first[0] = tree;
                return first[0] != null ? null : super.visitClass(tree, null);
            }
        }.scan(unit, null);
        if (first[0] == null) return -1;
        return (int) program.facts().trees().getSourcePositions().getEndPosition(unit, first[0]) - 1;
    }

    /**
     * The text of {@code unit} with what a type's Data does not depend on blanked out: every method, initializer and
     * field written in the source, so a probe compiles without the unit's method bodies. {@code insert} goes in at
     * {@code at}, a position of the original text; -1 inserts nothing.
     */
    public static String declarationsOnly(JavacProgram program, CompilationUnitTree unit, int at, String insert) throws IOException {
        var positions = program.facts().trees().getSourcePositions();
        var text = new StringBuilder(unit.getSourceFile().getCharContent(true));
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                for (Tree member : tree.getMembers()) {
                    boolean component = tree.getKind() == Tree.Kind.RECORD && member instanceof VariableTree variable
                            && !variable.getModifiers().getFlags().contains(Modifier.STATIC);
                    if (member instanceof ClassTree || component) continue;
                    long start = positions.getStartPosition(unit, member), end = positions.getEndPosition(unit, member);
                    if (start < 0 || end < 0) continue;
                    for (int i = (int) start; i < end; i++) {
                        if (text.charAt(i) != '\n') text.setCharAt(i, ' ');
                    }
                }
                return super.visitClass(tree, null);
            }
        }.scan(unit, null);
        return at < 0 ? text.toString() : text.insert(at, insert).toString();
    }

    /** The declared type of parameter {@code parameter} of a method {@code method} of a class of the root, or null. */
    public static TypeMirror parameterType(JavacProgram program, String method, String parameter) {
        for (var found : methods(program, method)) {
            for (VariableElement candidate : found.getParameters()) {
                if (candidate.getSimpleName().contentEquals(parameter)) return candidate.asType();
            }
        }
        return null;
    }

    /** The declared type of a field {@code field} of a class of the root, or null. */
    public static TypeMirror fieldType(JavacProgram program, String field) {
        var unit = program.roots().getFirst();
        var found = new TypeMirror[1];
        scan(unit, (tree, path) -> {
            if (tree instanceof VariableTree variable && variable.getName().contentEquals(field) && found[0] == null
                    && path.getParentPath().getLeaf() instanceof ClassTree) {
                found[0] = program.facts().element(path).asType();
            }
        });
        return found[0];
    }

    private static List<ExecutableElement> methods(JavacProgram program, String name) {
        var result = new ArrayList<ExecutableElement>();
        scan(program.roots().getFirst(), (tree, path) -> {
            if (tree instanceof MethodTree method && method.getName().contentEquals(name)) {
                result.add((ExecutableElement) program.facts().element(path));
            }
        });
        return result;
    }

    /** The registered type elements a sample needs: a top-level or nested type by identity, or null. */
    public static TypeElement type(JavacProgram program, String identity) {
        return program.facts().elements().getTypeElement(identity);
    }

    /** What the root's {@code new} expressions and method calls bind to: {@code new <class>}, {@code <class>.<method>}. */
    public static List<String> bindings(JavacProgram program) {
        var result = new ArrayList<String>();
        var facts = program.facts();
        scan(program.roots().getFirst(), (tree, path) -> {
            if (tree instanceof NewClassTree) {
                var constructor = (ExecutableElement) facts.element(path);
                result.add("new " + JavacFacts.identity((TypeElement) constructor.getEnclosingElement()));
            }
            if (tree instanceof MethodInvocationTree call) {
                var method = (ExecutableElement) facts.element(new TreePath(path, call.getMethodSelect()));
                result.add(JavacFacts.identity((TypeElement) method.getEnclosingElement()) + "." + method.getSimpleName());
            }
        });
        return result;
    }

    private interface Visitor {
        void visit(Tree tree, TreePath path);
    }

    private static void scan(CompilationUnitTree unit, Visitor visitor) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null) visitor.visit(tree, new TreePath(getCurrentPath(), tree));
                return super.scan(tree, null);
            }
        }.scan(unit, null);
    }
}
