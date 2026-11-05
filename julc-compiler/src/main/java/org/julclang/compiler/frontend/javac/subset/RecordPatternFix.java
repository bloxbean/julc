package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PatternTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.frontend.javac.JavacFacts;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The fix that {@code JULC0076} and {@code JULC0077} give for a record pattern: the same match written with the record's
 * own names. {@code case A(var amount)} on {@code record A(BigInteger v)} gets "write {@code case A a}, then use
 * {@code a.v()} for {@code amount}", which a developer can copy.
 * <p>
 * A pattern can also test a field: {@code case W(A x)} on {@code record W(S inner)} matches only when {@code inner} is an
 * {@code A}. Replacing it with accessors alone would drop that test, so the fix then says that the test remains to be
 * written instead of offering a rewrite to copy.
 */
public final class RecordPatternFix {

    private static final String START = "Bind the record and read its fields: write `";

    private RecordPatternFix() {}

    /** The fix for a {@code case} label, or null when the catalogue's fix is all that can be said. */
    public static String ofCase(JavacFacts facts, DeconstructionPatternTree pattern) {
        return of(facts, pattern, "case ");
    }

    /** The fix for {@code expression instanceof pattern}, which JuLC binds only as the whole condition of an {@code if}. */
    public static String ofInstanceof(JavacFacts facts, DeconstructionPatternTree pattern, String expression) {
        String fix = of(facts, pattern, expression + " instanceof ");
        return fix == null ? null : fix + " JuLC binds the variable only when the `instanceof` is the whole condition of an `if`.";
    }

    private static String of(JavacFacts facts, DeconstructionPatternTree pattern, String prefix) {
        if (!(facts.types().asElement(facts.type(pattern.getDeconstructor())) instanceof TypeElement record)) return null;
        var components = record.getRecordComponents();
        var nested = pattern.getNestedPatterns();
        if (components.size() != nested.size()) return null;
        // A raw generic record reads its fields as Object: the accessors would not keep the pattern's types.
        if (!record.getTypeParameters().isEmpty() && !(pattern.getDeconstructor() instanceof ParameterizedTypeTree)) return null;

        String simpleName = record.getSimpleName().toString();
        String written = facts.location(pattern.getDeconstructor()).fragment();
        String typeName = written.isEmpty() ? simpleName : written;
        String binding = unusedName(simpleName.substring(0, 1).toLowerCase(Locale.ROOT) + simpleName.substring(1),
                namesAround(facts, pattern));
        String start = START + prefix + typeName + " " + binding + "`";

        var reads = new ArrayList<String>();
        var tests = new ArrayList<String>();
        for (int i = 0; i < nested.size(); i++) {
            String read = "`" + binding + "." + components.get(i).getSimpleName() + "()`";
            if (readsWithoutATest(facts, nested.get(i), components.get(i))) {
                reads.add(read + " for `" + ((BindingPatternTree) nested.get(i)).getVariable().getName() + "`");
            } else {
                tests.add("that " + read + " matches `" + facts.location(nested.get(i)).fragment() + "`");
            }
        }
        if (!tests.isEmpty()) {
            return start + ", then check " + String.join(" and ", tests)
                    + ": the pattern tests this, so a value that does not match must go to what the next case does.";
        }
        return reads.isEmpty() ? start + "." : start + ", then use " + String.join(", ", reads) + ".";
    }

    /** Whether {@code pattern} takes every value of the field: {@code var}, or a type the field's type always fits. */
    private static boolean readsWithoutATest(JavacFacts facts, PatternTree pattern, RecordComponentElement component) {
        if (!(pattern instanceof BindingPatternTree binding)) return false;
        if (facts.location(binding).fragment().startsWith("var ")) return true;
        var types = facts.types();
        return types.isSubtype(types.erasure(component.asType()), types.erasure(facts.type(binding.getVariable())));
    }

    /** {@code wanted}, or with a number after it when that is a keyword or would clash with a name already in the class. */
    private static String unusedName(String wanted, Set<String> taken) {
        String name = wanted;
        for (int n = 2; taken.contains(name) || SourceVersion.isKeyword(name, SourceVersion.RELEASE_21); n++) name = wanted + n;
        return name;
    }

    /** Every variable declared in the class around {@code tree}: a pattern variable may not repeat a local or a field. */
    private static Set<String> namesAround(JavacFacts facts, Tree tree) {
        var names = new HashSet<String>();
        var scope = facts.enclosing(tree, Tree.Kind.CLASS, Tree.Kind.RECORD, Tree.Kind.INTERFACE, Tree.Kind.ENUM);
        if (scope != null) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree variable, Void unused) {
                    names.add(variable.getName().toString());
                    return super.visitVariable(variable, unused);
                }
            }.scan(scope, null);
        }
        return names;
    }
}
