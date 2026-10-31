package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.resolve.TypeResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * A program the javac frontend has read: attributed units and the types registered from them (ADR-068 D7). javac
 * attributes the roots and, through the source path, the pool units they reference (D8); the library units are in
 * qualified-name order (I4).
 * <p>
 * Lowering comes with ADR-068 M3 and M4, so {@link #notPorted} is where a compilation ends for now ({@code JULC0084}):
 * nothing here hands a program to the JavaParser frontend (I8).
 */
public final class JavacProgram {

    private final JavacFacts facts;
    private final JavacTypeRegistrar declarations;
    private final List<CompilationUnitTree> roots;
    private final List<CompilationUnitTree> libraries;

    private JavacProgram(JavacFacts facts, JavacTypeRegistrar declarations, List<CompilationUnitTree> roots,
                         List<CompilationUnitTree> libraries) {
        this.facts = facts;
        this.declarations = declarations;
        this.roots = roots;
        this.libraries = libraries;
    }

    /**
     * Reads {@code roots} with {@code pool} on the source path, and registers the ledger and the attributed units'
     * types. A javac error, or a rejected type, is a {@link CompilerException}.
     */
    public static JavacProgram analyze(List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        return analyze(JavacFrontend.shared(), roots, pool);
    }

    static JavacProgram analyze(JavacFrontend frontend, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        var attribution = frontend.attribute(roots, pool);
        if (!attribution.result().isAccepted()) throw new CompilerException(attribution.result().compilerDiagnostics());
        var facts = JavacFacts.of(attribution, new TypeResolver());
        var registrar = new JavacTypeRegistrar(facts);
        registrar.registerLedger(frontend.platform());
        var units = new ArrayList<>(attribution.libraries());
        units.addAll(attribution.roots());
        try {
            registrar.registerSources(units);
        } catch (CompilerException e) {
            if (!e.diagnostics().isEmpty()) throw e;
            // As the source path reports a type it cannot resolve.
            throw new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, e.getMessage(),
                    "<source>", 0, 0)));
        }
        return new JavacProgram(facts, registrar, attribution.roots(), attribution.libraries());
    }

    public JavacFacts facts() {
        return facts;
    }

    /** The registries, filled from javac's model. */
    public TypeResolver types() {
        return facts.registry();
    }

    /** What was registered, in registration order: the ledger's declarations, then the units'. */
    JavacTypeRegistrar declarations() {
        return declarations;
    }

    public List<CompilationUnitTree> roots() {
        return roots;
    }

    /** The pool units javac read, in qualified-name order. */
    public List<CompilationUnitTree> libraries() {
        return libraries;
    }

    /** {@code JULC0084}, naming the first method of the roots: lowering is not ported yet (ADR-068 D4). */
    public CompilerException notPorted() {
        for (CompilationUnitTree unit : roots) {
            for (Tree declaration : unit.getTypeDecls()) {
                if (declaration instanceof ClassTree type) {
                    var method = firstMethod(type);
                    if (method != null) {
                        return notPorted("the method '" + method.getName() + "' of " + type.getSimpleName(), unit, method);
                    }
                }
            }
        }
        var unit = roots.getFirst();
        return notPorted("this compilation unit", unit, unit);
    }

    private static MethodTree firstMethod(ClassTree type) {
        for (Tree member : type.getMembers()) {
            if (member instanceof MethodTree method && method.getBody() != null
                    && method.getReturnType() != null) return method;
            if (member instanceof ClassTree nested) {
                var found = firstMethod(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private CompilerException notPorted(String construct, CompilationUnitTree unit, Tree tree) {
        var info = DiagnosticCodes.FRONTEND_CONSTRUCT_NOT_PORTED;
        var location = facts.location(unit, tree);
        return new CompilerException(List.of(new CompilerDiagnostic(info.level(), info.format(construct),
                location.fileName(), location.line(), location.column(), info.fix(), info.code())));
    }

    /** {@code JULC0084} for a provider's lowering, which has no method to point at. */
    public static CompilerException notPorted(String construct) {
        var info = DiagnosticCodes.FRONTEND_CONSTRUCT_NOT_PORTED;
        return new CompilerException(List.of(new CompilerDiagnostic(info.level(), info.format(construct), "<source>", 0, 0,
                info.fix(), info.code())));
    }
}
