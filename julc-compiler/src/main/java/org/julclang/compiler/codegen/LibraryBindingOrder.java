package org.julclang.compiler.codegen;

import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;
import org.julclang.core.Constant;
import org.julclang.core.cbor.PlutusDataCborEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order in which a program binds the library methods it uses (issue #328, ADR-075).
 * <p>
 * Each method is bound by a {@code let} around the validator, a method's dependencies outside it. Several orders
 * satisfy that. The order used to be the one the methods were found in, and that followed the order of the
 * validator's {@code import} declarations and of the library sources a caller passed. Neither has any meaning in Java,
 * yet each changed the script's bytes and so its hash.
 * <p>
 * The order is now a function of the methods alone. Among the methods whose dependencies are already bound, the next
 * one bound is the one with the smallest <em>content key</em>, and the qualified name decides only between methods
 * whose keys are equal. A method's content key is a digest of its body in which:
 * <ul>
 *   <li>a variable bound inside the body is its de Bruijn index, so the names of locals and parameters do not count;</li>
 *   <li>a call of another library method is that method's content key, so neither its name, its class nor its package
 *       counts, and a call of the method itself is a fixed token;</li>
 *   <li>types and constructor names, which spell class names, are left out.</li>
 * </ul>
 * So moving a library class to another package, renaming it, or reordering imports or library sources leaves the
 * order, and the script, as they were. Changing what a method does can move it, as it changes the bytes anyway.
 * Methods in a recursive group key each other's calls with a fixed token; their qualified names then decide between
 * them where their bodies agree.
 */
final class LibraryBindingOrder {

    private LibraryBindingOrder() {
    }

    /**
     * The methods in binding order: the first is bound innermost, the last outermost, so each method's dependencies
     * come after it.
     */
    static List<LibraryMethod> innermostFirst(Collection<LibraryMethod> methods) {
        var byName = new LinkedHashMap<String, LibraryMethod>();
        for (var method : methods) byName.put(method.qualifiedName(), method);

        var dependencies = new LinkedHashMap<String, Set<String>>();
        for (var method : byName.values()) {
            var uses = new LinkedHashSet<String>();
            for (var other : byName.keySet()) {
                if (!other.equals(method.qualifiedName()) && PirHelpers.containsVarRef(method.body(), other)) {
                    uses.add(other);
                }
            }
            dependencies.put(method.qualifiedName(), uses);
        }

        var keys = contentKeys(byName, dependencies);
        Comparator<String> canonical = Comparator.<String, String>comparing(keys::get).thenComparing(Comparator.naturalOrder());

        // Kahn's algorithm, dependencies first, taking the canonically smallest ready method each time.
        var bound = new LinkedHashSet<String>();
        var order = new ArrayList<LibraryMethod>();
        var remaining = new ArrayList<>(byName.keySet());
        remaining.sort(canonical);
        while (!remaining.isEmpty()) {
            String next = null;
            for (String name : remaining) {
                if (bound.containsAll(dependencies.get(name))) {
                    next = name;
                    break;
                }
            }
            if (next == null) {
                // A recursive group with no method ready: bind the rest in canonical order.
                for (String name : remaining) order.add(byName.get(name));
                break;
            }
            remaining.remove(next);
            bound.add(next);
            order.add(byName.get(next));
        }
        Collections.reverse(order);
        return order;
    }

    /**
     * The content key of every method. A method's key is computed after the keys of the methods it calls, so that
     * each call can be keyed by its target; a call that cannot be, inside a recursive group, is keyed by a token.
     */
    private static Map<String, String> contentKeys(Map<String, LibraryMethod> byName, Map<String, Set<String>> dependencies) {
        var keys = new HashMap<String, String>();
        var pending = new ArrayList<>(byName.keySet());
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (var iterator = pending.iterator(); iterator.hasNext(); ) {
                String name = iterator.next();
                if (keys.keySet().containsAll(dependencies.get(name))) {
                    keys.put(name, key(byName.get(name), keys, byName.keySet()));
                    iterator.remove();
                    progressed = true;
                }
            }
            if (!progressed) {
                // The rest call each other: key them with the calls inside the group unresolved.
                var group = Map.copyOf(keys);
                for (String name : pending) keys.put(name, key(byName.get(name), group, byName.keySet()));
                pending.clear();
            }
        }
        return keys;
    }

    /** Pending work of the pre-order walk: a term, a token to write, or a change of the variables in scope. */
    private sealed interface Step {
        record Visit(PirTerm term) implements Step {}

        record Write(String token) implements Step {}

        record Bind(List<String> names) implements Step {}

        record Unbind(int count) implements Step {}
    }

    /** The digest of {@code method}'s body as {@link LibraryBindingOrder} describes it, walked without recursion. */
    private static String key(LibraryMethod method, Map<String, String> keys, Set<String> libraryMethods) {
        var text = new StringBuilder();
        var scope = new ArrayList<String>();
        var pending = new ArrayDeque<Step>();
        pending.push(new Step.Visit(method.body()));
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case Step.Write write -> text.append(write.token());
                case Step.Bind bind -> scope.addAll(bind.names());
                case Step.Unbind unbind -> {
                    for (int i = 0; i < unbind.count(); i++) scope.removeLast();
                }
                case Step.Visit visit -> describe(visit.term(), method, keys, libraryMethods, scope, pending, text);
            }
        }
        return digest(text.toString());
    }

    /**
     * Writes {@code term}'s own token and schedules its children, each under the binders it sees, in the order of
     * {@link PirHelpers#mapChildren}.
     */
    private static void describe(PirTerm term, LibraryMethod method, Map<String, String> keys, Set<String> libraryMethods,
                                 List<String> scope, ArrayDeque<Step> pending, StringBuilder text) {
        var children = new ArrayList<Step>();
        switch (term) {
            case PirTerm.Var variable -> text.append(variable(variable.name(), method, keys, libraryMethods, scope));
            case PirTerm.Const constant -> text.append("(c ").append(constant(constant.value())).append(')');
            case PirTerm.Builtin builtin -> text.append("(b ").append(builtin.fun().name()).append(')');
            case PirTerm.Error _ -> text.append("(error)");
            case PirTerm.Lam lam -> {
                text.append("(lam");
                bound(children, List.of(lam.param()), lam.body());
            }
            case PirTerm.Let let -> {
                text.append("(let");
                children.add(new Step.Visit(let.value()));
                bound(children, List.of(let.name()), let.body());
            }
            case PirTerm.LetRec rec -> {
                text.append("(letrec ").append(rec.bindings().size());
                var names = rec.bindings().stream().map(PirTerm.Binding::name).toList();
                children.add(new Step.Bind(names));
                for (var binding : rec.bindings()) children.add(new Step.Visit(binding.value()));
                children.add(new Step.Visit(rec.body()));
                children.add(new Step.Unbind(names.size()));
            }
            case PirTerm.App app -> {
                text.append("(app");
                children.add(new Step.Visit(app.function()));
                children.add(new Step.Visit(app.argument()));
            }
            case PirTerm.IfThenElse ite -> {
                text.append("(if");
                children.add(new Step.Visit(ite.cond()));
                children.add(new Step.Visit(ite.thenBranch()));
                children.add(new Step.Visit(ite.elseBranch()));
            }
            case PirTerm.Trace trace -> {
                text.append("(trace");
                children.add(new Step.Visit(trace.message()));
                children.add(new Step.Visit(trace.body()));
            }
            case PirTerm.DataConstr constr -> {
                text.append("(constr ").append(constr.tag()).append(' ').append(constr.fields().size());
                for (var field : constr.fields()) children.add(new Step.Visit(field));
            }
            case PirTerm.DataMatch match -> {
                text.append("(match ").append(match.branches().size());
                children.add(new Step.Visit(match.scrutinee()));
                for (var branch : match.branches()) {
                    var names = new ArrayList<>(branch.bindings());
                    if (branch.patternVar() != null) names.add(branch.patternVar());
                    text.append(' ').append(branch.bindings().size()).append(branch.patternVar() == null ? "" : "+");
                    bound(children, names, branch.body());
                }
            }
            case PirTerm.ListMatch match -> {
                text.append("(listmatch");
                children.add(new Step.Visit(match.scrutinee()));
                children.add(new Step.Visit(match.nilBranch()));
                bound(children, List.of(match.headName(), match.tailName()), match.consBranch());
            }
            case PirTerm.PairMatch match -> {
                text.append("(pairmatch");
                children.add(new Step.Visit(match.scrutinee()));
                bound(children, List.of(match.firstName(), match.secondName()), match.body());
            }
            case PirTerm.IntegerCase integerCase -> {
                text.append("(case ").append(integerCase.branches().size());
                children.add(new Step.Visit(integerCase.scrutinee()));
                for (var branch : integerCase.branches()) children.add(new Step.Visit(branch));
            }
        }
        // A form with parts closes after them; the parts run in order.
        if (!children.isEmpty()) children.add(new Step.Write(")"));
        for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
    }

    private static void bound(List<Step> children, List<String> names, PirTerm body) {
        children.add(new Step.Bind(names));
        children.add(new Step.Visit(body));
        children.add(new Step.Unbind(names.size()));
    }

    private static String variable(String name, LibraryMethod method, Map<String, String> keys, Set<String> libraryMethods,
                                   List<String> scope) {
        int index = scope.lastIndexOf(name);
        if (index >= 0) return "(v " + (scope.size() - 1 - index) + ")";
        if (name.equals(method.qualifiedName())) return "(self)";
        if (libraryMethods.contains(name)) {
            String key = keys.get(name);
            return key == null ? "(group)" : "(call " + key + ")";
        }
        return "(free " + name.length() + ":" + name + ")";
    }

    /** A constant as a string that only an equal constant has. */
    private static String constant(Constant constant) {
        return switch (constant) {
            case Constant.IntegerConst c -> "i" + c.value();
            case Constant.ByteStringConst c -> "x" + HexFormat.of().formatHex(c.value());
            case Constant.StringConst c -> "s" + c.value().length() + ":" + c.value();
            case Constant.UnitConst _ -> "u";
            case Constant.BoolConst c -> c.value() ? "T" : "F";
            case Constant.DataConst c -> "d" + HexFormat.of().formatHex(PlutusDataCborEncoder.encode(c.value()));
            case Constant.ListConst c -> "l" + c.elemType() + list(c.values());
            case Constant.ArrayConst c -> "a" + c.elemType() + list(c.values());
            case Constant.PairConst c -> "p[" + constant(c.first()) + "," + constant(c.second()) + "]";
            case Constant.Bls12_381_G1Element c -> "g1" + HexFormat.of().formatHex(c.value());
            case Constant.Bls12_381_G2Element c -> "g2" + HexFormat.of().formatHex(c.value());
            case Constant.Bls12_381_MlResult c -> "ml" + HexFormat.of().formatHex(c.value());
            case Constant.ValueConst c -> {
                var text = new StringBuilder("v[");
                for (var entry : c.entries()) {
                    text.append(HexFormat.of().formatHex(entry.policyId())).append('{');
                    for (var token : entry.tokens()) {
                        text.append(HexFormat.of().formatHex(token.tokenName())).append('=').append(token.quantity()).append(';');
                    }
                    text.append('}');
                }
                yield text.append(']').toString();
            }
        };
    }

    private static String list(List<Constant> values) {
        var text = new StringBuilder("[");
        for (var value : values) text.append(constant(value)).append(',');
        return text.append(']').toString();
    }

    private static String digest(String text) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
