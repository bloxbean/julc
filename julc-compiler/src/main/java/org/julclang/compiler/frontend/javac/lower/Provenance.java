package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.codegen.StrictRecordEntrypoint;
import org.julclang.compiler.pir.PirTerm;

import javax.lang.model.element.Element;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * I13's construction-time provenance: which source element each {@code Var} reads and each binder binds, recorded as the
 * term is built, without changing {@link PirTerm}. Keys are identities, because equal terms in two places are two reads.
 * <p>
 * A binder is keyed by the node that binds it and its name, since one node can bind several: a {@code LetRec}, a
 * {@code ListMatch}, a {@code PairMatch}. A {@code DataMatch} binds per branch, so there the node is the
 * {@link PirTerm.MatchBranch}, and two branches can bind one name to two elements.
 */
public final class Provenance {

    private final IdentityHashMap<PirTerm, Element> reads = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Map<String, Element>> binders = new IdentityHashMap<>();

    /** {@code read} reads {@code element}. */
    public void read(PirTerm.Var read, Element element) {
        reads.put(read, element);
    }

    /** {@code node} binds {@code name} for {@code element}. */
    public void binder(Object node, String name, Element element) {
        binders.computeIfAbsent(node, key -> new HashMap<>()).put(name, element);
    }

    /** What {@code read} was built for, or null. */
    public Element readOf(PirTerm.Var read) {
        return reads.get(read);
    }

    /** What {@code node} binds {@code name} for, or null. */
    public Element binderOf(Object node, String name) {
        var names = binders.get(node);
        return names == null ? null : names.get(name);
    }

    /** How many reads and binders are recorded. */
    public int size() {
        return reads.size() + binders.values().stream().mapToInt(Map::size).sum();
    }

    /** {@code copy} stands for {@code original}, with the same binders under the same names. */
    public void rebuilt(PirTerm original, PirTerm copy) {
        var names = binders.get(original);
        if (names != null) binders.put(copy, names);
        if (original instanceof PirTerm.DataMatch match && copy instanceof PirTerm.DataMatch rebuilt) {
            for (int i = 0; i < match.branches().size(); i++) {
                var branch = binders.get(match.branches().get(i));
                if (branch != null) binders.put(rebuilt.branches().get(i), branch);
            }
        }
    }

    /** What the strict transform of {@code function} must tell this table: its copies, and its new reads of a parameter. */
    public StrictRecordEntrypoint.Rebuilds rebuildsOf(PirTerm function) {
        return new StrictRecordEntrypoint.Rebuilds() {
            @Override
            public void rebuilt(PirTerm original, PirTerm copy) {
                Provenance.this.rebuilt(original, copy);
            }

            @Override
            public void parameterRead(PirTerm.Var read) {
                for (PirTerm cursor = function; cursor instanceof PirTerm.Lam lambda; cursor = lambda.body()) {
                    if (lambda.param().equals(read.name())) reads.put(read, binderOf(lambda, lambda.param()));
                }
            }
        };
    }
}
