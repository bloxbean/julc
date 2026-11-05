package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.frontend.javac.JavacFacts;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The fix that {@code JULC0076} and {@code JULC0077} give for a record pattern: the same match written with the record's
 * own names, so that a developer can copy it. {@code case A(var amount)} on {@code record A(BigInteger v)} gets
 * "write {@code case A a}, then use {@code a.v()} for {@code amount}".
 */
public final class RecordPatternFix {

    private RecordPatternFix() {}

    /**
     * The fix for {@code pattern}, where {@code prefix} is the source before the record's name, such as {@code "case "}.
     * It is null when javac's types do not name the record's components, so that the caller keeps the catalogue's fix.
     */
    public static String of(JavacFacts facts, DeconstructionPatternTree pattern, String prefix) {
        if (!(facts.types().asElement(facts.type(pattern.getDeconstructor())) instanceof TypeElement record)) return null;
        var components = record.getRecordComponents();
        var nested = pattern.getNestedPatterns();
        if (components.size() != nested.size()) return null;

        String name = record.getSimpleName().toString();
        String binding = unusedName(name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1), namesAround(facts, pattern));
        var reads = new ArrayList<String>();
        for (int i = 0; i < nested.size(); i++) {
            String read = "`" + binding + "." + components.get(i).getSimpleName() + "()`";
            reads.add(nested.get(i) instanceof BindingPatternTree variable
                    ? read + " for `" + variable.getVariable().getName() + "`"
                    : read + " and match that value the same way");
        }
        String start = "Bind the record and read its fields: write `" + prefix + name + " " + binding + "`";
        return reads.isEmpty() ? start + "." : start + ", then use " + String.join(", ", reads) + ".";
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
