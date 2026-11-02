package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.lower.Scope.Binder;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.Element;
import java.util.List;

/**
 * The accumulators of a loop, as one value: one accumulator is itself, and several travel as a tuple, a list of Data, each
 * encoded by its binder's type. What a loop's lowering asks of this is the same either way, so the reference's single
 * and multi-accumulator bodies are one body here.
 * <p>
 * <b>Evaluation order.</b> Reading the value reads variables. Unpacking reads the tuple once, under a strict {@code let},
 * and then each accumulator, first to last, each with its decode, which is total on what packing built.
 */
final class Carried {

    /** The type of a tuple of accumulators. */
    static final PirType TUPLE = new PirType.ListType(new PirType.DataType());

    private final JavacFacts facts;
    private final Terms terms;
    private final List<Binder> binders;
    private final Tree loop;

    /** @param binders what is carried: the type of each is what it travels at, which a body may read at another */
    Carried(JavacFacts facts, Terms terms, List<Binder> binders, Tree loop) {
        this.facts = facts;
        this.terms = terms;
        this.binders = binders;
        this.loop = loop;
    }

    boolean isSingle() {
        return binders.size() == 1;
    }

    /** The type of the value the loop hands on. */
    PirType type() {
        return isSingle() ? binders.getFirst().type() : TUPLE;
    }

    /** The accumulator of a loop with one. */
    Binder only() {
        return binders.getFirst();
    }

    /** Whether assigning {@code element} is an update of what this carries. */
    boolean holds(Element element) {
        return binders.stream().anyMatch(binder -> binder.element().equals(element));
    }

    /** The accumulators, as one value. */
    PirTerm value() {
        if (isSingle()) return read(only());
        PirTerm result = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData), new PirTerm.Const(Constant.unit()));
        for (int i = binders.size() - 1; i >= 0; i--) {
            var binder = binders.get(i);
            result = new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), encoded(read(binder), binder.type())), result);
        }
        return result;
    }

    private PirTerm read(Binder binder) {
        return terms.var(binder.element(), binder);
    }

    /** A value with no type to encode by has no position of its own, so it takes the loop's (F724). */
    private PirTerm encoded(PirTerm value, PirType type) {
        try {
            return PirHelpers.wrapEncode(value, type);
        } catch (CompilerException e) {
            var diagnostic = e.diagnostics().size() == 1 ? e.diagnostics().getFirst() : null;
            if (diagnostic == null || diagnostic.line() > 0) throw e;
            if (DiagnosticCodes.TYPE_RESOLUTION_FAILED.code().equals(diagnostic.code())) {
                throw CompilerTypeDiagnostics.unknownTypeEncoded(facts.location(loop));
            }
            throw e;
        }
    }

    /** {@code value}, what the loop or a branch left, bound for {@code rest}, where these binders hold it. */
    PirTerm rebound(PirTerm value, PirTerm rest) {
        return isSingle() ? terms.let(only(), value, rest) : unpacked(value, rest);
    }

    /** {@code body} with each accumulator bound to what {@code tuple} holds of it. */
    PirTerm unpacked(PirTerm tuple, PirTerm body) {
        // The body and every later accessor are in the tuple binder's scope, so it takes a name the body does not read.
        var tupleVar = terms.generated(PirHelpers.hygienicName("#__t", PirHelpers.freeVariables(body)), TUPLE);
        PirTerm result = body;
        for (int i = binders.size() - 1; i >= 0; i--) {
            PirTerm accessor = tupleVar;
            for (int j = 0; j < i; j++) accessor = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), accessor);
            accessor = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), accessor);
            result = terms.unpacked(binders.get(i), accessor, result);
        }
        return terms.generatedLet(tupleVar.name(), tuple, result);
    }

    /** The name of each accumulator. */
    List<String> names() {
        return binders.stream().map(Binder::name).toList();
    }
}
