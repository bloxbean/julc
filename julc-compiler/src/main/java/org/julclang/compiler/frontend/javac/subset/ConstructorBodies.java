package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;

import javax.lang.model.element.ElementKind;

/**
 * A subset rule ({@code JULC0099}): a constructor holds no statement. A value is built from its components alone, so a
 * constructor's body never runs on-chain. That goes for a compact, canonical or other constructor, of a record or a class,
 * however deep it is declared. What javac wrote into a body has no source text, and does not count: a {@code super();}, a
 * compact constructor's field assignments, and all of a generated constructor. So {@code R { }} holds nothing.
 */
public final class ConstructorBodies {

    private ConstructorBodies() {
    }

    public static void check(JavacFacts facts, CompilationUnitTree unit) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                var method = facts.element(tree);
                if (method.getKind() == ElementKind.CONSTRUCTOR
                        && tree.getBody().getStatements().stream().anyMatch(statement -> !facts.isGenerated(statement))) {
                    throw CompilerTypeDiagnostics.constructorBody(method.toString(), facts.location(tree));
                }
                return super.visitMethod(tree, unused);
            }
        }.scan(unit, null);
    }
}
