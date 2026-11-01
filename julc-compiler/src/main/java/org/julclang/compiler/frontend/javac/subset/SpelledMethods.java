package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.Set;

/**
 * A subset rule ({@code JULC0101}): a program does not declare a method under a name the JavaParser frontend reads by its
 * spelling ahead of the method's body. Each row is a matcher of {@code PirGenerator}'s method calls, which stands in for
 * the call without lowering the body. A method javac generates is exempt.
 * <table>
 *   <caption>The matchers, and the declaration each one stands in for</caption>
 *   <tr><td>{@code tryFromPlutusDataIdentity}</td><td>{@code fromPlutusData} of one parameter, on any type</td></tr>
 *   <tr><td>the {@code of} lookup in {@code JulcCompiler}</td><td>{@code of} of one parameter, on a {@code @NewType}</td></tr>
 *   <tr><td>{@code TypeMethodRegistry}, on the type a {@code @NewType} lowers to</td><td>an instance method of a {@code @NewType}</td></tr>
 *   <tr><td>{@code .hash()} and the no-argument fallback on bytes</td><td>an instance method of a {@code @NewType}</td></tr>
 *   <tr><td>a record field read by its accessor's name</td><td>an explicit accessor</td></tr>
 *   <tr><td>{@code TypeMethodRegistry}'s {@code equals}</td><td>{@code equals} of one parameter, on a record or a sealed interface</td></tr>
 *   <tr><td>{@code TypeMethodRegistry}'s named row for a record {@code Value}</td><td>{@code assetOf}, {@code containsPolicy}, {@code isEmpty}, {@code lovelaceOf}, on a record {@code Value}</td></tr>
 *   <tr><td>the {@code toPlutusData} fallback</td><td>{@code toPlutusData} of no parameters, on any type</td></tr>
 *   <tr><td>{@code tryBigIntegerConstant}, {@code tryPlutusDataCast}, the {@code of} lookup</td><td>a call of a declared {@code valueOf}, {@code cast} or {@code of} through a variable of the spelled name: the types of those names are {@code JULC0100}'s</td></tr>
 * </table>
 */
public final class SpelledMethods {

    private static final Set<String> VALUE_METHODS = Set.of("assetOf", "containsPolicy", "isEmpty", "lovelaceOf");
    private static final Set<String> SPELLED_CALLS = Set.of("valueOf", "cast", "of");

    private SpelledMethods() {
    }

    public static void check(JavacFacts facts, CompilationUnitTree unit) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                var method = (ExecutableElement) facts.element(tree);
                if (method.getKind() == ElementKind.METHOD && !facts.isGenerated(tree) && isReadBySpelling(facts, method)) {
                    throw reject(facts, method, tree);
                }
                return super.visitMethod(tree, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                if (tree.getMethodSelect() instanceof MemberSelectTree select
                        && SPELLED_CALLS.contains(select.getIdentifier().toString())
                        && select.getExpression() instanceof IdentifierTree qualifier
                        && facts.element(qualifier) instanceof VariableElement
                        && facts.isDeclaredInSource(facts.element(select))) {
                    throw reject(facts, facts.element(select), tree);
                }
                return super.visitMethodInvocation(tree, unused);
            }
        }.scan(unit, null);
    }

    private static RuntimeException reject(JavacFacts facts, Element method, com.sun.source.tree.Tree where) {
        return CompilerTypeDiagnostics.methodNameReadBySpelling(
                method.getEnclosingElement().getSimpleName() + "." + method, facts.location(where));
    }

    private static boolean isReadBySpelling(JavacFacts facts, ExecutableElement method) {
        String name = method.getSimpleName().toString();
        int parameters = method.getParameters().size();
        if (name.equals("fromPlutusData") && parameters == 1 || name.equals("toPlutusData") && parameters == 0) return true;
        var owner = (TypeElement) method.getEnclosingElement();
        boolean instance = !method.getModifiers().contains(Modifier.STATIC);
        boolean equals = instance && name.equals("equals") && parameters == 1;
        if (owner.getKind() == ElementKind.INTERFACE) return equals && owner.getModifiers().contains(Modifier.SEALED);
        if (owner.getKind() != ElementKind.RECORD) return false;
        if (isNewType(owner)) return instance || name.equals("of") && parameters == 1;
        return equals || instance && parameters == 0 && isComponent(facts, owner, name)
                || instance && owner.getSimpleName().contentEquals("Value") && VALUE_METHODS.contains(name);
    }

    private static boolean isComponent(JavacFacts facts, TypeElement record, String name) {
        return facts.components(record).stream().anyMatch(component -> component.getSimpleName().contentEquals(name));
    }

    /** By simple name, as the JavaParser frontend reads an annotation. */
    static boolean isNewType(Element element) {
        return element.getAnnotationMirrors().stream().anyMatch(mirror ->
                mirror.getAnnotationType().asElement().getSimpleName().contentEquals("NewType"));
    }
}
