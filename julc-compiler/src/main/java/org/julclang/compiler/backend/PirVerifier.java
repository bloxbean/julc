package org.julclang.compiler.backend;

import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.error.DiagnosticInfo;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structural checks for producer-authored PIR before lowering (ADR-059).
 *
 * <p>This is a bounded contract, not a proof system. It checks binding hygiene, closure,
 * agreement between terms and declared types at the representation level, and the layout
 * invariants that {@code UplcGenerator} relies on without checking: Bool conditions,
 * lambda-valued recursive bindings, constructor and match layouts with dense tags, and
 * resolvable named types. Opaque Data provenance, list element encodings and semantic
 * equivalence remain producer obligations. Imported provider bodies are trusted and are
 * seen only through the types of their bindings.
 */
public final class PirVerifier {
    private final Map<String, PirType> namedTypes;
    private final List<PirType> unmatchable;
    private final List<PirType> unconstructible;
    private final boolean builtinCase;
    private final Map<String, PirType> scope = new HashMap<>();
    private String subject = "";

    /**
     * @param namedTypes      every named type definition visible to the producer
     * @param unmatchable     resolved imported types without an approved MATCH operation
     * @param unconstructible resolved imported types without an approved CONSTRUCT operation
     * @param builtinCase     whether the lowering profile supports {@code ListMatch}/{@code PairMatch}
     */
    public PirVerifier(Map<String, PirType> namedTypes, Collection<PirType> unmatchable,
                       Collection<PirType> unconstructible, boolean builtinCase) {
        this.namedTypes = Map.copyOf(namedTypes);
        this.unmatchable = List.copyOf(unmatchable);
        this.unconstructible = List.copyOf(unconstructible);
        this.builtinCase = builtinCase;
        this.subject = "named types";
        for (var entry : this.namedTypes.entrySet()) {
            var definition = entry.getValue();
            if (!(definition instanceof PirType.RecordType || definition instanceof PirType.SumType))
                throw structure("named type " + entry.getKey()
                        + " must be defined as a record or sum, not " + show(definition));
            checkType(definition);
            if (definition instanceof PirType.SumType sum) requireDenseTags(sum);
        }
    }

    /**
     * Check a producer term against its declared type.
     *
     * @param subject     the producer symbol named in diagnostics
     * @param term        the producer term
     * @param declared    the declared type
     * @param environment the types of the program-level symbols the term may reference
     */
    public void verify(String subject, PirTerm term, PirType declared, Map<String, PirType> environment) {
        this.subject = subject;
        scope.clear();
        scope.putAll(environment);
        checkType(declared);
        var actual = synth(term);
        if (!agree(declared, actual))
            throw mismatch("declared type " + show(declared) + " but the term has type " + show(actual));
    }

    /** Reject reserved binder names: a leading '.' or '__', a leading '$julc$', or any '#'. */
    public static boolean isReservedName(String name) {
        return name == null || name.isBlank() || name.startsWith(".") || name.startsWith("__")
                || name.startsWith("$julc$") || name.contains("#");
    }

    // ---- terms ----

    private PirType synth(PirTerm term) {
        return switch (term) {
            case PirTerm.Var v -> {
                var bound = scope.get(v.name());
                if (bound == null) throw binding("unbound symbol " + v.name());
                checkType(v.type());
                if (!agree(v.type(), bound))
                    throw mismatch("variable " + v.name() + " is annotated " + show(v.type())
                            + " but is bound to " + show(bound));
                yield bound;
            }
            case PirTerm.Const c -> constantType(c.value());
            case PirTerm.Builtin b -> builtin(b.fun(), List.of());
            case PirTerm.Lam l -> {
                requireBinder(l.param());
                checkType(l.paramType());
                var previous = bind(l.param(), l.paramType());
                var body = synth(l.body());
                restore(l.param(), previous);
                yield new PirType.FunType(l.paramType(), body);
            }
            case PirTerm.Let l -> {
                requireBinder(l.name());
                var value = synth(l.value());
                var previous = bind(l.name(), value);
                var body = synth(l.body());
                restore(l.name(), previous);
                yield body;
            }
            case PirTerm.LetRec r -> letRec(r);
            case PirTerm.App a -> application(a);
            case PirTerm.IfThenElse i -> {
                requireBool(synth(i.cond()), "if condition");
                yield join(synth(i.thenBranch()), synth(i.elseBranch()), "if branches");
            }
            case PirTerm.IntegerCase _ -> throw structure(
                    "IntegerCase is generated during lowering and must not appear in producer PIR");
            case PirTerm.PairMatch m -> {
                requireBuiltinCase("PairMatch");
                var scrutinee = synth(m.scrutinee());
                checkType(m.pairType());
                if (!agree(m.pairType(), scrutinee))
                    throw mismatch("PairMatch declares " + show(m.pairType()) + " but matches "
                            + show(scrutinee));
                requireBinder(m.firstName());
                requireBinder(m.secondName());
                var first = bind(m.firstName(), m.pairType().first());
                var second = bind(m.secondName(), m.pairType().second());
                var body = synth(m.body());
                restore(m.secondName(), second);
                restore(m.firstName(), first);
                yield body;
            }
            case PirTerm.ListMatch m -> listMatch(m);
            case PirTerm.DataConstr c -> dataConstr(c);
            case PirTerm.DataMatch m -> dataMatch(m);
            case PirTerm.Error e -> {
                checkType(e.type());
                yield e.type();
            }
            case PirTerm.Trace t -> {
                requireExact(synth(t.message()), BuiltinTyping.STRING, "trace message");
                yield synth(t.body());
            }
        };
    }

    private PirType letRec(PirTerm.LetRec r) {
        var names = new ArrayList<String>();
        for (var binding : r.bindings()) {
            requireBinder(binding.name());
            if (names.contains(binding.name()))
                throw binding("duplicate recursive binding " + binding.name());
            names.add(binding.name());
            if (!(binding.value() instanceof PirTerm.Lam))
                throw structure("recursive binding " + binding.name() + " must be a lambda");
        }
        // A recursive binding's type is declared by the annotation of its references.
        var declared = new LinkedHashMap<String, PirType>();
        for (var name : names) {
            PirType found = null;
            for (var binding : r.bindings()) {
                found = referenceType(binding.value(), name);
                if (found != null) break;
            }
            if (found == null) found = referenceType(r.body(), name);
            if (found != null) {
                checkType(found);
                declared.put(name, found);
            }
        }
        var previous = new LinkedHashMap<String, PirType>();
        for (var name : names) previous.put(name, scope.get(name));
        // Unreferenced bindings are typed by synthesis; bind referenced ones first.
        declared.forEach(scope::put);
        for (var binding : r.bindings()) {
            var actual = synth(binding.value());
            var expected = declared.get(binding.name());
            if (expected == null) scope.put(binding.name(), actual);
            else if (!agree(expected, actual))
                throw mismatch("recursive binding " + binding.name() + " is referenced as "
                        + show(expected) + " but has type " + show(actual));
        }
        var body = synth(r.body());
        previous.forEach(this::restore);
        return body;
    }

    private PirType application(PirTerm.App app) {
        var arguments = new ArrayList<PirTerm>();
        PirTerm head = app;
        while (head instanceof PirTerm.App a) {
            arguments.addFirst(a.argument());
            head = a.function();
        }
        PirType function;
        int consumed;
        if (head instanceof PirTerm.Builtin b) {
            // Every builtin is either monomorphic or polymorphic (BuiltinTypingTest).
            Integer polymorphic = BuiltinTyping.polymorphicArity(b.fun());
            int arity = polymorphic != null
                    ? polymorphic
                    : BuiltinTyping.monomorphic(b.fun()).parameters().size();
            int take = Math.min(arity, arguments.size());
            var argumentTypes = new ArrayList<PirType>();
            for (int i = 0; i < take; i++) argumentTypes.add(synth(arguments.get(i)));
            function = builtin(b.fun(), argumentTypes);
            consumed = take;
        } else {
            function = synth(head);
            consumed = 0;
        }
        for (int i = consumed; i < arguments.size(); i++) {
            var resolved = resolve(function);
            if (!(resolved instanceof PirType.FunType fn))
                throw mismatch("applies a value of type " + show(function) + " to an argument");
            var argument = synth(arguments.get(i));
            if (!agree(fn.paramType(), argument))
                throw mismatch("argument of type " + show(argument) + " does not match parameter "
                        + show(fn.paramType()));
            function = fn.returnType();
        }
        return function;
    }

    private PirType builtin(DefaultFun fun, List<PirType> arguments) {
        if (BuiltinTyping.UNSUPPORTED.contains(fun))
            throw structure("builtin " + fun + " is not supported in producer PIR");
        var signature = BuiltinTyping.monomorphic(fun);
        if (signature != null) {
            for (int i = 0; i < arguments.size(); i++)
                if (!agree(signature.parameters().get(i), arguments.get(i)))
                    throw mismatch("builtin " + fun + " argument " + (i + 1) + " has type "
                            + show(arguments.get(i)) + " but requires "
                            + show(signature.parameters().get(i)));
            PirType result = signature.result();
            for (int i = signature.parameters().size() - 1; i >= arguments.size(); i--)
                result = new PirType.FunType(signature.parameters().get(i), result);
            return result;
        }
        Integer arity = BuiltinTyping.polymorphicArity(fun);
        if (arity == null) throw structure("builtin " + fun + " has no verifier signature");
        if (arguments.size() < arity)
            throw structure("polymorphic builtin " + fun + " must be applied to all " + arity
                    + " arguments");
        var a = arguments;
        return switch (fun) {
            case IfThenElse -> {
                requireBool(a.get(0), "IfThenElse condition");
                yield join(a.get(1), a.get(2), "IfThenElse branches");
            }
            case ChooseUnit -> {
                requireExact(a.get(0), BuiltinTyping.UNIT, "ChooseUnit argument");
                yield a.get(1);
            }
            case Trace -> {
                requireExact(a.get(0), BuiltinTyping.STRING, "Trace message");
                yield a.get(1);
            }
            case FstPair, SndPair -> {
                if (!(resolve(a.get(0)) instanceof PirType.PairType pair))
                    throw mismatch(fun + " requires a pair but received " + show(a.get(0)));
                yield fun == DefaultFun.FstPair ? pair.first() : pair.second();
            }
            case ChooseList -> {
                requireList(a.get(0), fun);
                yield join(a.get(1), a.get(2), "ChooseList branches");
            }
            case NullList -> {
                requireList(a.get(0), fun);
                yield BuiltinTyping.BOOL;
            }
            case HeadList -> elementView(requireList(a.get(0), fun));
            case TailList -> requireList(a.get(0), fun);
            case DropList -> {
                requireExact(a.get(0), BuiltinTyping.INT, "DropList count");
                yield requireList(a.get(1), fun);
            }
            case MkCons -> {
                var list = requireList(a.get(1), fun);
                var element = elementView(list);
                if (!agree(element, a.get(0)))
                    throw mismatch("MkCons element of type " + show(a.get(0))
                            + " does not match list element " + show(element) + " of "
                            + show(list));
                yield list;
            }
            case ChooseData -> {
                if (!isDataRepresented(a.get(0)))
                    throw mismatch("ChooseData requires Data but received " + show(a.get(0)));
                var result = a.get(1);
                for (int i = 2; i <= 5; i++) result = join(result, a.get(i), "ChooseData branches");
                yield result;
            }
            case LengthOfArray -> {
                requireArray(a.get(0), fun);
                yield BuiltinTyping.INT;
            }
            case ListToArray -> {
                if (!(resolve(a.get(0)) instanceof PirType.ListType list)
                        || resolve(list.elemType()) instanceof PirType.PairType)
                    throw mismatch("ListToArray requires a Data list but received " + show(a.get(0)));
                yield new PirType.ArrayType(list.elemType());
            }
            case IndexArray -> {
                var array = requireArray(a.get(0), fun);
                requireExact(a.get(1), BuiltinTyping.INT, "IndexArray index");
                yield rawView(array.elemType());
            }
            default -> throw structure("builtin " + fun + " has no verifier rule");
        };
    }

    private PirType listMatch(PirTerm.ListMatch m) {
        requireBuiltinCase("ListMatch");
        var scrutinee = synth(m.scrutinee());
        if (!(resolve(scrutinee) instanceof PirType.ListType list)
                || resolve(list.elemType()) instanceof PirType.PairType)
            throw mismatch("ListMatch requires a Data list but matches " + show(scrutinee));
        var nil = synth(m.nilBranch());
        requireBinder(m.headName());
        requireBinder(m.tailName());
        var head = bind(m.headName(), rawView(list.elemType()));
        var tail = bind(m.tailName(), scrutinee);
        var cons = synth(m.consBranch());
        restore(m.tailName(), tail);
        restore(m.headName(), head);
        return join(nil, cons, "ListMatch branches");
    }

    private PirType dataConstr(PirTerm.DataConstr c) {
        checkType(c.dataType());
        // UplcGenerator encodes fields from this type and does not resolve named references:
        // a named reference would leave every field unencoded.
        if (c.dataType() instanceof PirType.NamedTypeRef ref)
            throw structure("DataConstr must carry the resolved definition of " + ref.stableId()
                    + ", not a named reference; fields are encoded from its declared field types");
        var resolved = resolve(c.dataType());
        requireTransparent(resolved, unconstructible, "construct");
        var fields = new ArrayList<PirType>();
        for (var field : c.fields()) fields.add(synth(field));
        List<PirType.Field> layout;
        switch (resolved) {
            case PirType.RecordType record -> {
                if (c.tag() != 0)
                    throw structure("record " + record.name() + " is constructed with tag "
                            + c.tag() + " but records use tag 0");
                layout = record.fields();
            }
            case PirType.SumType sum -> {
                requireDenseTags(sum);
                if (c.tag() < 0 || c.tag() >= sum.constructors().size())
                    throw structure("sum " + sum.name() + " has no constructor with tag " + c.tag());
                layout = sum.constructors().get(c.tag()).fields();
            }
            case PirType.OptionalType _ -> {
                if (c.tag() == 0 && fields.size() == 1) {
                    if (!isDataRepresented(fields.getFirst()))
                        throw mismatch("an Optional payload is stored without encoding and must "
                                + "already be Data-represented, not " + show(fields.getFirst()));
                    return c.dataType();
                }
                if (c.tag() == 1 && fields.isEmpty()) return c.dataType();
                throw structure("Optional values are tag 0 with one field or tag 1 with none");
            }
            default -> throw structure("DataConstr type must be a record, sum or Optional, not "
                    + show(c.dataType()));
        }
        if (layout.size() != fields.size())
            throw structure("constructor with tag " + c.tag() + " of " + show(c.dataType())
                    + " has " + layout.size() + " fields but " + fields.size() + " were supplied");
        for (int i = 0; i < layout.size(); i++) {
            var declared = layout.get(i).type();
            if (!isDataEncodable(declared))
                throw structure("field " + layout.get(i).name() + " of " + show(c.dataType())
                        + " has type " + show(declared) + ", which has no Data encoding");
            if (!agree(declared, fields.get(i)))
                throw mismatch("field " + layout.get(i).name() + " requires " + show(declared)
                        + " but received " + show(fields.get(i)));
        }
        return c.dataType();
    }

    private PirType dataMatch(PirTerm.DataMatch m) {
        var scrutinee = synth(m.scrutinee());
        var resolved = resolve(scrutinee);
        requireTransparent(resolved, unmatchable, "match");
        List<PirType.Constructor> constructors = switch (resolved) {
            case PirType.RecordType record ->
                    List.of(new PirType.Constructor(record.name(), 0, record.fields()));
            case PirType.SumType sum -> {
                requireDenseTags(sum);
                yield sum.constructors();
            }
            default -> throw structure("DataMatch scrutinee must have a record or sum type, not "
                    + show(scrutinee));
        };
        if (m.branches().size() != constructors.size())
            throw structure("DataMatch over " + show(scrutinee) + " needs exactly one branch per "
                    + "constructor in tag order: expected " + constructors.size() + " but found "
                    + m.branches().size());
        PirType result = null;
        for (int i = 0; i < constructors.size(); i++) {
            var constructor = constructors.get(i);
            var branch = m.branches().get(i);
            if (!constructor.name().equals(branch.constructorName()))
                throw structure("DataMatch branch " + i + " is named " + branch.constructorName()
                        + " but constructor tag " + i + " is " + constructor.name()
                        + " (branches are selected by position)");
            if (branch.bindings().size() > constructor.fields().size())
                throw structure("DataMatch branch " + constructor.name() + " binds "
                        + branch.bindings().size() + " fields but the constructor has "
                        + constructor.fields().size());
            if (branch.bindingTypes().size() != branch.bindings().size())
                throw structure("DataMatch branch " + constructor.name()
                        + " must give one binding type per binding");
            var saved = new LinkedHashMap<String, PirType>();
            for (int j = 0; j < branch.bindings().size(); j++) {
                var name = branch.bindings().get(j);
                var type = branch.bindingTypes().get(j);
                requireBinder(name);
                checkType(type);
                var field = constructor.fields().get(j);
                if (!isDataEncodable(type))
                    throw structure("DataMatch binding " + name + " has type " + show(type)
                            + ", which cannot be decoded from a field");
                if (!elementAgree(field.type(), type))
                    throw mismatch("DataMatch binding " + name + " has type " + show(type)
                            + " but field " + field.name() + " has type " + show(field.type()));
                if (!saved.containsKey(name)) saved.put(name, scope.get(name));
                scope.put(name, type);
            }
            if (branch.patternVar() != null) {
                requireBinder(branch.patternVar());
                if (!saved.containsKey(branch.patternVar()))
                    saved.put(branch.patternVar(), scope.get(branch.patternVar()));
                scope.put(branch.patternVar(), scrutinee);
            }
            var body = synth(branch.body());
            saved.forEach(this::restore);
            result = result == null ? body : join(result, body, "DataMatch branches");
        }
        return result;
    }

    // ---- types ----

    private PirType constantType(Constant constant) {
        return switch (constant) {
            case Constant.IntegerConst _ -> BuiltinTyping.INT;
            case Constant.ByteStringConst _ -> BuiltinTyping.BYTES;
            case Constant.StringConst _ -> BuiltinTyping.STRING;
            case Constant.BoolConst _ -> BuiltinTyping.BOOL;
            case Constant.UnitConst _ -> BuiltinTyping.UNIT;
            case Constant.DataConst _ -> BuiltinTyping.DATA;
            case Constant.Bls12_381_G1Element _ -> BuiltinTyping.G1;
            case Constant.Bls12_381_G2Element _ -> BuiltinTyping.G2;
            case Constant.Bls12_381_MlResult _ -> BuiltinTyping.ML;
            case Constant.ValueConst _ -> BuiltinTyping.VALUE;
            case Constant.PairConst pair ->
                    new PirType.PairType(constantType(pair.first()), constantType(pair.second()));
            case Constant.ListConst list -> {
                var element = universeType(list.elemType());
                for (var value : list.values())
                    if (!constantType(value).equals(element))
                        throw mismatch("list constant of " + list.elemType()
                                + " contains an element of another type");
                if (element.equals(BuiltinTyping.DATA)) yield BuiltinTyping.DATA_LIST;
                if (element.equals(BuiltinTyping.DATA_PAIR)) yield BuiltinTyping.PAIR_LIST;
                yield new PirType.NativeListType(element);
            }
            case Constant.ArrayConst array -> {
                var element = universeType(array.elemType());
                if (!element.equals(BuiltinTyping.DATA))
                    throw structure("array constants must contain Data; PIR has no native array type");
                for (var value : array.values())
                    if (!(value instanceof Constant.DataConst))
                        throw mismatch("array constant contains a non-Data element");
                yield new PirType.ArrayType(BuiltinTyping.DATA);
            }
        };
    }

    private PirType universeType(DefaultUni universe) {
        return switch (universe) {
            case DefaultUni.Integer _ -> BuiltinTyping.INT;
            case DefaultUni.ByteString _ -> BuiltinTyping.BYTES;
            case DefaultUni.String _ -> BuiltinTyping.STRING;
            case DefaultUni.Unit _ -> BuiltinTyping.UNIT;
            case DefaultUni.Bool _ -> BuiltinTyping.BOOL;
            case DefaultUni.Data _ -> BuiltinTyping.DATA;
            case DefaultUni.Bls12_381_G1_Element _ -> BuiltinTyping.G1;
            case DefaultUni.Bls12_381_G2_Element _ -> BuiltinTyping.G2;
            case DefaultUni.Bls12_381_MlResult _ -> BuiltinTyping.ML;
            case DefaultUni.ProtoValue _ -> BuiltinTyping.VALUE;
            case DefaultUni.Apply(DefaultUni.Apply(DefaultUni.ProtoPair _, var first), var second) ->
                    new PirType.PairType(universeType(first), universeType(second));
            default -> throw structure("constant universe " + universe + " is not supported");
        };
    }

    /** The list's type if the value is a builtin list: a Data list, a pair list or a native list. */
    private PirType requireList(PirType type, DefaultFun fun) {
        var resolved = resolve(type);
        if (resolved instanceof PirType.ListType || resolved instanceof PirType.MapType
                || resolved instanceof PirType.NativeListType)
            return type;
        throw mismatch(fun + " requires a list but received " + show(type));
    }

    private PirType.ArrayType requireArray(PirType type, DefaultFun fun) {
        if (resolve(type) instanceof PirType.ArrayType array) return array;
        throw mismatch(fun + " requires an array but received " + show(type));
    }

    /** The runtime type of one element of a builtin list. */
    private PirType elementView(PirType list) {
        return switch (resolve(list)) {
            case PirType.ListType l when resolve(l.elemType()) instanceof PirType.PairType pair ->
                    new PirType.PairType(rawView(pair.first()), rawView(pair.second()));
            case PirType.ListType l -> rawView(l.elemType());
            case PirType.MapType _ -> BuiltinTyping.DATA_PAIR;
            case PirType.NativeListType l -> l.elemType();
            default -> throw mismatch("expected a list but received " + show(list));
        };
    }

    /** A Data-encoded element read without decoding: Data-represented types keep their type. */
    private PirType rawView(PirType type) {
        return isDataRepresented(type) ? type : BuiltinTyping.DATA;
    }

    private PirType resolve(PirType type) {
        if (type instanceof PirType.NamedTypeRef ref) {
            var definition = namedTypes.get(ref.stableId());
            if (definition == null) throw structure("unresolved named type " + ref.stableId());
            return definition;
        }
        return type;
    }

    /** Named references resolve with the right kind; components are checked recursively. */
    private void checkType(PirType type) {
        switch (type) {
            case PirType.NamedTypeRef ref -> {
                var definition = namedTypes.get(ref.stableId());
                if (definition == null) throw structure("unresolved named type " + ref.stableId());
                boolean kindMatches = ref.kind() == PirType.NamedKind.RECORD
                        ? definition instanceof PirType.RecordType
                        : definition instanceof PirType.SumType;
                if (!kindMatches)
                    throw structure("named type " + ref.stableId() + " is referenced as a "
                            + ref.kind() + " but defined as " + show(definition));
            }
            case PirType.ListType l -> checkType(l.elemType());
            case PirType.NativeListType l -> checkType(l.elemType());
            case PirType.ArrayType a -> checkType(a.elemType());
            case PirType.OptionalType o -> checkType(o.elemType());
            case PirType.MapType m -> {
                checkType(m.keyType());
                checkType(m.valueType());
            }
            case PirType.PairType p -> {
                checkType(p.first());
                checkType(p.second());
            }
            case PirType.FunType f -> {
                checkType(f.paramType());
                checkType(f.returnType());
            }
            case PirType.RecordType r -> r.fields().forEach(f -> checkType(f.type()));
            case PirType.SumType s -> s.constructors().forEach(c -> c.fields().forEach(f -> checkType(f.type())));
            default -> {}
        }
    }

    /** Positional DataMatch and tag-indexed DataConstr agree only for dense, ordered tags. */
    private void requireDenseTags(PirType.SumType sum) {
        for (int i = 0; i < sum.constructors().size(); i++)
            if (sum.constructors().get(i).tag() != i)
                throw structure("sum " + sum.name() + " constructor " + sum.constructors().get(i).name()
                        + " has tag " + sum.constructors().get(i).tag() + " at position " + i
                        + "; constructor tags must be 0..n-1 in declaration order");
    }

    private void requireTransparent(PirType resolved, List<PirType> opaqueTypes, String operation) {
        for (var opaque : opaqueTypes)
            if (opaque.equals(resolved))
                throw structure("imported type " + show(resolved) + " has no approved " + operation
                        + " operation; request one from its provider");
    }

    /**
     * Types agree when they are equal after named-type resolution, or through a documented
     * Data view: Data and Data-represented types at the top level; Data and any Data-encodable
     * type in Data-encoded positions; and maps as pair lists.
     */
    private boolean agree(PirType expected, PirType actual) {
        return agree(expected, actual, 0);
    }

    private boolean agree(PirType expected, PirType actual, int depth) {
        if (depth > 64) return false;
        if (expected instanceof PirType.NamedTypeRef a && actual instanceof PirType.NamedTypeRef b
                && a.stableId().equals(b.stableId()))
            return true;
        var e = resolve(expected);
        var a = resolve(actual);
        if (e.equals(a)) return true;
        if (e instanceof PirType.DataType) return isDataRepresented(a);
        if (a instanceof PirType.DataType) return isDataRepresented(e);
        return switch (e) {
            case PirType.ListType l when a instanceof PirType.ListType m ->
                    elementAgree(l.elemType(), m.elemType(), depth + 1);
            case PirType.ListType l when a instanceof PirType.MapType m ->
                    pairListAgree(l, m, depth + 1);
            case PirType.MapType m when a instanceof PirType.ListType l ->
                    pairListAgree(l, m, depth + 1);
            case PirType.MapType m when a instanceof PirType.MapType n ->
                    elementAgree(m.keyType(), n.keyType(), depth + 1)
                            && elementAgree(m.valueType(), n.valueType(), depth + 1);
            case PirType.ArrayType x when a instanceof PirType.ArrayType y ->
                    elementAgree(x.elemType(), y.elemType(), depth + 1);
            case PirType.OptionalType x when a instanceof PirType.OptionalType y ->
                    elementAgree(x.elemType(), y.elemType(), depth + 1);
            case PirType.NativeListType x when a instanceof PirType.NativeListType y ->
                    agree(x.elemType(), y.elemType(), depth + 1);
            case PirType.PairType x when a instanceof PirType.PairType y ->
                    agree(x.first(), y.first(), depth + 1) && agree(x.second(), y.second(), depth + 1);
            case PirType.FunType x when a instanceof PirType.FunType y ->
                    agree(x.paramType(), y.paramType(), depth + 1)
                            && agree(x.returnType(), y.returnType(), depth + 1);
            case PirType.RecordType x when a instanceof PirType.RecordType y ->
                    x.name().equals(y.name()) && fieldsAgree(x.fields(), y.fields(), depth + 1);
            case PirType.SumType x when a instanceof PirType.SumType y ->
                    x.name().equals(y.name()) && constructorsAgree(x, y, depth + 1);
            default -> false;
        };
    }

    private boolean elementAgree(PirType expected, PirType actual) {
        return elementAgree(expected, actual, 0);
    }

    private boolean elementAgree(PirType expected, PirType actual, int depth) {
        var e = resolve(expected);
        var a = resolve(actual);
        if (e.equals(a)) return true;
        if (e instanceof PirType.DataType) return isDataEncodable(a);
        if (a instanceof PirType.DataType) return isDataEncodable(e);
        return agree(expected, actual, depth);
    }

    private boolean pairListAgree(PirType.ListType list, PirType.MapType map, int depth) {
        return resolve(list.elemType()) instanceof PirType.PairType pair
                && elementAgree(map.keyType(), pair.first(), depth)
                && elementAgree(map.valueType(), pair.second(), depth);
    }

    private boolean fieldsAgree(List<PirType.Field> x, List<PirType.Field> y, int depth) {
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++)
            if (!x.get(i).name().equals(y.get(i).name())
                    || !agree(x.get(i).type(), y.get(i).type(), depth))
                return false;
        return true;
    }

    private boolean constructorsAgree(PirType.SumType x, PirType.SumType y, int depth) {
        if (x.constructors().size() != y.constructors().size()) return false;
        for (int i = 0; i < x.constructors().size(); i++) {
            var a = x.constructors().get(i);
            var b = y.constructors().get(i);
            if (!a.name().equals(b.name()) || a.tag() != b.tag()
                    || !fieldsAgree(a.fields(), b.fields(), depth))
                return false;
        }
        return true;
    }

    /** Values represented at runtime as Data. */
    private boolean isDataRepresented(PirType type) {
        return switch (resolve(type)) {
            case PirType.DataType _, PirType.RecordType _, PirType.SumType _, PirType.OptionalType _ -> true;
            default -> false;
        };
    }

    /** Types with a Data encoding for fields, list elements and boundaries. */
    private boolean isDataEncodable(PirType type) {
        return switch (resolve(type)) {
            case PirType.IntegerType _, PirType.ByteStringType _, PirType.StringType _,
                 PirType.BoolType _, PirType.DataType _, PirType.RecordType _,
                 PirType.SumType _ -> true;
            case PirType.OptionalType o -> isDataEncodable(o.elemType());
            case PirType.ListType l -> !(resolve(l.elemType()) instanceof PirType.PairType)
                    && isDataEncodable(l.elemType());
            case PirType.MapType m -> isDataEncodable(m.keyType()) && isDataEncodable(m.valueType());
            default -> false;
        };
    }

    private PirType join(PirType first, PirType second, String what) {
        if (!agree(first, second))
            throw mismatch(what + " disagree: " + show(first) + " and " + show(second));
        return resolve(first) instanceof PirType.DataType ? second : first;
    }

    private void requireBool(PirType type, String what) {
        requireExact(type, BuiltinTyping.BOOL, what);
    }

    private void requireExact(PirType type, PirType required, String what) {
        if (!resolve(type).equals(required))
            throw mismatch(what + " must be " + show(required) + " but is " + show(type));
    }

    private void requireBuiltinCase(String form) {
        if (!builtinCase)
            throw structure(form + " requires the PV11 safe lowering profile (capability "
                    + BackendCapability.PIR_BUILTIN_CASE + "); select optimization level pv11-safe");
    }

    // ---- scope ----

    private void requireBinder(String name) {
        if (isReservedName(name))
            throw binding("binder name " + (name == null ? "null" : "'" + name + "'")
                    + " is reserved or blank");
    }

    private PirType bind(String name, PirType type) {
        return scope.put(name, type);
    }

    private void restore(String name, PirType previous) {
        if (previous == null) scope.remove(name);
        else scope.put(name, previous);
    }

    /** The annotation of the first free occurrence of {@code name}, respecting shadowing. */
    private static PirType referenceType(PirTerm term, String name) {
        return switch (term) {
            case PirTerm.Var v -> v.name().equals(name) ? v.type() : null;
            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> null;
            case PirTerm.Lam l -> l.param().equals(name) ? null : referenceType(l.body(), name);
            case PirTerm.Let l -> first(referenceType(l.value(), name),
                    l.name().equals(name) ? null : referenceType(l.body(), name));
            case PirTerm.LetRec r -> {
                if (r.bindings().stream().anyMatch(b -> b.name().equals(name))) yield null;
                PirType found = null;
                for (var binding : r.bindings()) found = first(found, referenceType(binding.value(), name));
                yield first(found, referenceType(r.body(), name));
            }
            case PirTerm.App a -> first(referenceType(a.function(), name), referenceType(a.argument(), name));
            case PirTerm.IfThenElse i -> first(referenceType(i.cond(), name),
                    first(referenceType(i.thenBranch(), name), referenceType(i.elseBranch(), name)));
            case PirTerm.Trace t -> first(referenceType(t.message(), name), referenceType(t.body(), name));
            case PirTerm.DataConstr c -> {
                PirType found = null;
                for (var field : c.fields()) found = first(found, referenceType(field, name));
                yield found;
            }
            case PirTerm.DataMatch m -> {
                PirType found = referenceType(m.scrutinee(), name);
                for (var branch : m.branches())
                    if (!branch.bindings().contains(name) && !name.equals(branch.patternVar()))
                        found = first(found, referenceType(branch.body(), name));
                yield found;
            }
            case PirTerm.ListMatch m -> first(first(referenceType(m.scrutinee(), name),
                            referenceType(m.nilBranch(), name)),
                    m.headName().equals(name) || m.tailName().equals(name)
                            ? null : referenceType(m.consBranch(), name));
            case PirTerm.PairMatch m -> first(referenceType(m.scrutinee(), name),
                    m.firstName().equals(name) || m.secondName().equals(name)
                            ? null : referenceType(m.body(), name));
            case PirTerm.IntegerCase c -> {
                PirType found = referenceType(c.scrutinee(), name);
                for (var branch : c.branches()) found = first(found, referenceType(branch, name));
                yield found;
            }
        };
    }

    private static PirType first(PirType a, PirType b) {
        return a != null ? a : b;
    }

    // ---- diagnostics ----

    private BackendException binding(String detail) {
        return failure(DiagnosticCodes.PIR_INVALID_BINDING, detail);
    }

    private BackendException mismatch(String detail) {
        return failure(DiagnosticCodes.PIR_TYPE_MISMATCH, detail);
    }

    private BackendException structure(String detail) {
        return failure(DiagnosticCodes.PIR_UNSUPPORTED_STRUCTURE, detail);
    }

    private BackendException failure(DiagnosticInfo diagnostic, String detail) {
        return new BackendException(diagnostic, subject, subject, detail);
    }

    static String show(PirType type) {
        return switch (type) {
            case PirType.IntegerType _ -> "Int";
            case PirType.ByteStringType _ -> "Bytes";
            case PirType.StringType _ -> "String";
            case PirType.BoolType _ -> "Bool";
            case PirType.UnitType _ -> "Unit";
            case PirType.DataType _ -> "Data";
            case PirType.NativeValueType _ -> "NativeValue";
            case PirType.NativeG1Type _ -> "G1";
            case PirType.NativeG2Type _ -> "G2";
            case PirType.NativeMlResultType _ -> "MlResult";
            case PirType.NativeListType l -> "NativeList " + show(l.elemType());
            case PirType.ListType l -> "List " + show(l.elemType());
            case PirType.PairType p -> "Pair " + show(p.first()) + " " + show(p.second());
            case PirType.MapType m -> "Map " + show(m.keyType()) + " " + show(m.valueType());
            case PirType.OptionalType o -> "Optional " + show(o.elemType());
            case PirType.ArrayType a -> "Array " + show(a.elemType());
            case PirType.FunType f -> "(" + show(f.paramType()) + " -> " + show(f.returnType()) + ")";
            case PirType.RecordType r -> "record " + r.name();
            case PirType.SumType s -> "sum " + s.name();
            case PirType.NamedTypeRef n -> n.stableId();
        };
    }
}
