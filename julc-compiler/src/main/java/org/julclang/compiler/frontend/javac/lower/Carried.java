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
import java.util.ArrayList;
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
    private final List<Element> elements;
    private final Tree loop;

    Carried(JavacFacts facts, Terms terms, List<Element> elements, Tree loop) {
        this.facts = facts;
        this.terms = terms;
        this.elements = elements;
        this.loop = loop;
    }

    boolean isSingle() {
        return elements.size() == 1;
    }

    /** The type of the value the loop hands on. */
    PirType type(Frame frame) {
        return isSingle() ? binder(frame, 0).type() : TUPLE;
    }

    /** The accumulator of a loop with one. */
    Binder only(Frame frame) {
        return binder(frame, 0);
    }

    /** Whether assigning {@code element} is an update of what this carries. */
    boolean holds(Element element) {
        return elements.contains(element);
    }

    private Binder binder(Frame frame, int index) {
        return frame.scope().lookup(elements.get(index));
    }

    /** The accumulators as {@code frame} holds them, as one value. */
    PirTerm value(Frame frame) {
        if (isSingle()) return read(frame, 0);
        PirTerm result = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData), new PirTerm.Const(Constant.unit()));
        for (int i = elements.size() - 1; i >= 0; i--) {
            var encoded = encoded(read(frame, i), binder(frame, i).type());
            result = new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), encoded), result);
        }
        return result;
    }

    private PirTerm read(Frame frame, int index) {
        return terms.var(elements.get(index), binder(frame, index));
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

    /** {@code value}, what the loop or a branch left, bound for {@code rest}: {@code frame}'s binders hold it there. */
    PirTerm rebound(PirTerm value, PirTerm rest, Frame frame) {
        return isSingle() ? terms.let(binder(frame, 0), value, rest) : unpacked(value, frame, rest);
    }

    /** {@code body} with each accumulator bound to what {@code tuple} holds of it. */
    PirTerm unpacked(PirTerm tuple, Frame frame, PirTerm body) {
        // The body and every later accessor are in the tuple binder's scope, so it takes a name neither reads.
        var avoid = PirHelpers.freeVariables(body);
        var binders = new ArrayList<Binder>();
        for (int i = 0; i < elements.size(); i++) binders.add(binder(frame, i));
        binders.forEach(binder -> avoid.add(binder.name()));
        var tupleVar = terms.generated(PirHelpers.hygienicName("#__t", avoid), TUPLE);
        PirTerm result = body;
        for (int i = binders.size() - 1; i >= 0; i--) {
            PirTerm accessor = tupleVar;
            for (int j = 0; j < i; j++) accessor = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), accessor);
            accessor = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), accessor);
            result = terms.unpacked(binders.get(i), accessor, result);
        }
        return terms.generatedLet(tupleVar.name(), tuple, result);
    }

    /** The name of each accumulator in {@code frame}, which a generated binder over them avoids. */
    List<String> names(Frame frame) {
        var names = new ArrayList<String>();
        for (int i = 0; i < elements.size(); i++) names.add(binder(frame, i).name());
        return names;
    }
}
