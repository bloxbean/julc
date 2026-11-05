package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * {@code JULC0056} (ADR-060): a static field or {@code @Param} is not shared state on-chain. A loop's update rebinds it only
 * in the rest of the method's current run, and every other run and method sees its initial value. That is Java's answer
 * only where the method runs once per evaluation, an entry method that nothing calls, with the loop outside any lambda, and
 * no other method reads the field. {@link #require} rejects what the update shows, as it lowers, and {@link #lowered} what
 * the methods show, after each one, so every pair of an update and a read is seen in whatever order they lower. A read is
 * what javac bound a simple name to, so a local, a parameter or a pattern variable that shadows the field is none, and a
 * read through the class name is {@code Undefined variable} first. A call is by binder, which overloads share. javac rejects
 * an update of a {@code final} field first (D11).
 * <p>
 * A compilation holds one of these, as it holds one of the reference's generator: its state is what has lowered so far.
 */
public final class FieldUpdates {

    private record Update(MethodTree method, VariableElement field, SourceLocation location) {
    }

    /** What a lowered method names: variables and methods, by element, and the binders of what it calls. */
    private record Lowered(MethodTree method, Set<Element> reads, Set<String> calls) {
    }

    private final JavacFacts facts;
    private final Set<MethodTree> entries = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Update> updates = new ArrayList<>();
    private final List<Lowered> lowered = new ArrayList<>();

    public FieldUpdates(JavacFacts facts) {
        this.facts = facts;
    }

    /** The methods the program runs once per evaluation: the entry points, or the {@code compileMethod} target. */
    public void entries(Collection<MethodTree> methods) {
        entries.addAll(methods);
    }

    /** Rejects what the source shows of an update of {@code field} at {@code at}. */
    public void require(VariableElement field, Tree at) {
        String name = field.getSimpleName().toString();
        var location = facts.location(at);
        var method = (MethodTree) facts.enclosing(at, Tree.Kind.METHOD);
        if (method == null) {
            throw CompilerTypeDiagnostics.fieldAssignment(name, "a static field initializer cannot update a field", location);
        }
        if (!entries.contains(method)) {
            throw CompilerTypeDiagnostics.fieldAssignment(name, "'" + method.getName()
                    + "' is not an entry method, and each call of it starts from the field's initial value", location);
        }
        if (facts.enclosing(at, Tree.Kind.LAMBDA_EXPRESSION) != null) {
            throw CompilerTypeDiagnostics.fieldAssignment(name,
                    "the loop is in a lambda, and each run of it starts from the field's initial value", location);
        }
        updates.add(new Update(method, field, location));
    }

    /** Rejects what {@code method}, now lowered, shows with the methods before it. */
    public void lowered(MethodTree method) {
        lowered.add(named(method));
        for (var update : updates) {
            String field = update.field().getSimpleName().toString();
            String updater = update.method().getName().toString();
            for (var other : lowered) {
                String name = other.method().getName().toString();
                if (other.calls().contains(binderOf(facts.element(update.method())))) {
                    throw CompilerTypeDiagnostics.fieldAssignment(field, (other.method() == update.method()
                            ? "'" + updater + "' calls itself" : "'" + name + "' calls '" + updater + "'")
                            + ", and each call starts from the field's initial value", update.location());
                }
                if (other.method() != update.method() && other.reads().contains(update.field())) {
                    throw CompilerTypeDiagnostics.fieldAssignment(field, "'" + name
                            + "' reads it, and sees only the field's initial value", update.location());
                }
            }
        }
    }

    private Lowered named(MethodTree method) {
        var reads = new HashSet<Element>();
        var calls = new HashSet<String>();
        for (var name : SubsetWalk.all(method, IdentifierTree.class)) {
            var element = facts.elementOrNull(name);
            if (element instanceof ExecutableElement called) calls.add(binderOf(called));
            else if (element != null) reads.add(element);
        }
        return new Lowered(method, reads, calls);
    }

    private String binderOf(Element method) {
        return facts.identity((TypeElement) method.getEnclosingElement()) + "." + method.getSimpleName();
    }
}
