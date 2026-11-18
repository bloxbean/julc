package org.julclang.compiler.codegen;

import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
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
import java.util.function.Consumer;

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
 *   <li>types are written as their structure, which decides how values are encoded, without the names that spell a
 *       class: a record as its fields' types, a sum as its constructors' tags and fields' types. Constructor and field
 *       names are left out.</li>
 * </ul>
 * So moving a library class to another package, renaming it, or reordering imports or library sources leaves the
 * order as it was. Changing what a method does can move it, as it changes the bytes anyway.
 * Methods in a recursive group key each other's calls with a fixed token.
 * <p>
 * Methods whose keys are equal are bound in the order the program first uses them ({@link FirstUseRanks}, ADR-078),
 * not by their names: the method with the lower first-use rank is taken first.
 */
final class LibraryBindingOrder {

    private LibraryBindingOrder() {
    }

    /**
     * The methods in binding order: the first is bound innermost, the last outermost, so each method's dependencies
     * come after it.
     */
    static List<LibraryMethod> innermostFirst(Collection<LibraryMethod> methods) {
        return innermostFirst(methods, FirstUseRanks.Roots.NONE, null);
    }

    /**
     * The methods in binding order, ties between equal content keys broken by first use from {@code roots}
     * (ADR-078 D2).
     *
     * @param observer told each ranking step, or null (ADR-078 D5's instrumentation)
     */
    static List<LibraryMethod> innermostFirst(Collection<LibraryMethod> methods, FirstUseRanks.Roots roots,
                                              Consumer<FirstUseRanks.Selection> observer) {
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
        var ranks = FirstUseRanks.of(roots, byName, keys, dependencies, observer);
        Comparator<String> canonical = Comparator.<String, String>comparing(keys::get)
                .thenComparingInt(ranks::get).thenComparing(Comparator.naturalOrder());

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
     * {@link #children}: the one child order of this class, which {@link FirstUseRanks}'s walk uses too (ADR-078).
     */
    private static void describe(PirTerm term, LibraryMethod method, Map<String, String> keys, Set<String> libraryMethods,
                                 List<String> scope, ArrayDeque<Step> pending, StringBuilder text) {
        switch (term) {
            case PirTerm.Var variable -> text.append(variable(variable.name(), method, keys, libraryMethods, scope))
                    .append(type(variable.type()));
            case PirTerm.Const constant -> text.append("(c ").append(constant(constant.value())).append(')');
            case PirTerm.Builtin builtin -> text.append("(b ").append(builtin.fun().name()).append(')');
            case PirTerm.Error error -> text.append("(error ").append(type(error.type())).append(')');
            case PirTerm.Lam lam -> text.append("(lam ").append(type(lam.paramType()));
            case PirTerm.Let _ -> text.append("(let");
            case PirTerm.LetRec rec -> text.append("(letrec ").append(rec.bindings().size());
            case PirTerm.App _ -> text.append("(app");
            case PirTerm.IfThenElse _ -> text.append("(if");
            case PirTerm.Trace _ -> text.append("(trace");
            case PirTerm.DataConstr constr -> text.append("(constr ").append(constr.tag()).append(' ')
                    .append(type(constr.dataType())).append(' ').append(constr.fields().size());
            case PirTerm.DataMatch match -> {
                text.append("(match ").append(match.branches().size());
                for (var branch : match.branches()) {
                    text.append(' ').append(branch.bindings().size()).append(branch.patternVar() == null ? "" : "+");
                    for (var bindingType : branch.bindingTypes()) text.append(type(bindingType));
                }
            }
            case PirTerm.ListMatch _ -> text.append("(listmatch");
            case PirTerm.PairMatch match -> text.append("(pairmatch ").append(type(match.pairType()));
            case PirTerm.IntegerCase integerCase -> text.append("(case ").append(integerCase.branches().size());
        }
        var children = new ArrayList<Step>();
        children(term, child -> children.add(new Step.Visit(child)), (names, body) -> bound(children, names, body));
        // A form with parts closes after them; the parts run in order. A recursive group's names are bound for each of
        // its parts in turn, which gives every part the same scope as binding them once around all of them.
        if (!children.isEmpty()) children.add(new Step.Write(")"));
        for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
    }

    /**
     * Reports {@code term}'s children in the order {@link #describe} walks them: {@code plain} for a child under no new
     * binder, {@code bound} for one under the binders a lambda, let, match or recursive group introduces.
     */
    static void children(PirTerm term, Consumer<PirTerm> plain, FirstUseRanks.Bound bound) {
        switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> {
            }
            case PirTerm.Lam lam -> bound.child(List.of(lam.param()), lam.body());
            case PirTerm.Let let -> {
                plain.accept(let.value());
                bound.child(List.of(let.name()), let.body());
            }
            case PirTerm.LetRec rec -> {
                var names = rec.bindings().stream().map(PirTerm.Binding::name).toList();
                for (var binding : rec.bindings()) bound.child(names, binding.value());
                bound.child(names, rec.body());
            }
            case PirTerm.App app -> {
                plain.accept(app.function());
                plain.accept(app.argument());
            }
            case PirTerm.IfThenElse ite -> {
                plain.accept(ite.cond());
                plain.accept(ite.thenBranch());
                plain.accept(ite.elseBranch());
            }
            case PirTerm.Trace trace -> {
                plain.accept(trace.message());
                plain.accept(trace.body());
            }
            case PirTerm.DataConstr constr -> constr.fields().forEach(plain);
            case PirTerm.DataMatch match -> {
                plain.accept(match.scrutinee());
                for (var branch : match.branches()) {
                    var names = new ArrayList<>(branch.bindings());
                    if (branch.patternVar() != null) names.add(branch.patternVar());
                    bound.child(names, branch.body());
                }
            }
            case PirTerm.ListMatch match -> {
                plain.accept(match.scrutinee());
                plain.accept(match.nilBranch());
                bound.child(List.of(match.headName(), match.tailName()), match.consBranch());
            }
            case PirTerm.PairMatch match -> {
                plain.accept(match.scrutinee());
                bound.child(List.of(match.firstName(), match.secondName()), match.body());
            }
            case PirTerm.IntegerCase integerCase -> {
                plain.accept(integerCase.scrutinee());
                integerCase.branches().forEach(plain);
            }
        }
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

    /**
     * A type's structure, without the names that spell a class: a record is its fields' types, a sum its constructors'
     * tags and fields' types, and a reference to a named type only its kind. Two types that encode differently are
     * written differently. Types nest as deeply as the source declares them, so this recurses.
     */
    private static String type(PirType type) {
        return switch (type) {
            case null -> "_";
            case PirType.IntegerType _ -> "I";
            case PirType.ByteStringType _ -> "B";
            case PirType.StringType _ -> "S";
            case PirType.BoolType _ -> "O";
            case PirType.UnitType _ -> "U";
            case PirType.DataType _ -> "D";
            case PirType.NativeValueType _ -> "V";
            case PirType.NativeG1Type _ -> "G1";
            case PirType.NativeG2Type _ -> "G2";
            case PirType.NativeMlResultType _ -> "ML";
            case PirType.NativeListType list -> "NL<" + type(list.elemType()) + ">";
            case PirType.ListType list -> "L<" + type(list.elemType()) + ">";
            case PirType.PairType pair -> "P<" + type(pair.first()) + "," + type(pair.second()) + ">";
            case PirType.MapType map -> "M<" + type(map.keyType()) + "," + type(map.valueType()) + ">";
            case PirType.OptionalType optional -> "Opt<" + type(optional.elemType()) + ">";
            case PirType.ArrayType array -> "A<" + type(array.elemType()) + ">";
            case PirType.FunType function -> "F<" + type(function.paramType()) + "," + type(function.returnType()) + ">";
            case PirType.RecordType record -> "R<" + fields(record.fields()) + ">";
            case PirType.SumType sum -> {
                var text = new StringBuilder("Sum<");
                for (var constructor : sum.constructors()) {
                    text.append(constructor.tag()).append('(').append(fields(constructor.fields())).append(')');
                }
                yield text.append('>').toString();
            }
            case PirType.NamedTypeRef reference -> "Ref<" + reference.kind() + ">";
        };
    }

    private static String fields(List<PirType.Field> fields) {
        var text = new StringBuilder();
        for (var field : fields) text.append(type(field.type())).append(';');
        return text.toString();
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
