package com.bloxbean.cardano.julc.compiler.codegen;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates eager, strict checks for typed datum/redeemer roots.
 *
 * <p>The checker is deliberately derived from {@link PirType}. It does not read
 * Java source or CIP-57. A single type-id dispatcher handles nominal recursion,
 * including mutual recursion, while local list/map walkers recurse over the
 * raw container representation.</p>
 */
public final class StrictBoundaryGenerator {
    private static final String CHECK = "__julc-boundary-check";
    private static final PirType BOOL = new PirType.BoolType();
    private static final PirType DATA = new PirType.DataType();
    private static final PirType INTEGER = new PirType.IntegerType();

    private final Map<String, PirType> namedDefinitions;
    private final LinkedHashMap<PirType, Integer> typeIds = new LinkedHashMap<>();

    public StrictBoundaryGenerator(Map<String, PirType> namedDefinitions) {
        this.namedDefinitions = Map.copyOf(namedDefinitions);
    }

    /** Opaque Data has no declared shape and therefore needs no generated guard. */
    public boolean requiresGuard(PirType type) {
        return !(resolveNamed(type) instanceof PirType.DataType);
    }

    /** Validate that the complete reachable type graph can be checked strictly. */
    public void ensureSupported(PirType type) {
        typeIds.clear();
        register(type);
    }

    /** Build a Bool term which eagerly validates {@code data} as {@code rootType}. */
    public PirTerm check(PirTerm data, PirType rootType) {
        typeIds.clear();
        int rootId = register(rootType);
        return withDispatcher(applyCheck(rootId, data));
    }

    /**
     * Check a boundary while reusing the outer decode passed to the entrypoint.
     * Primitive, list, and map roots are forced/traversed from {@code decoded};
     * constructor-encoded roots retain their raw Data representation.
     */
    public PirTerm checkPrepared(PirTerm data, PirTerm decoded, PirType rootType) {
        typeIds.clear();
        PirType type = resolveNamed(rootType);
        int rootId = register(type);
        PirTerm rootCheck = switch (type) {
            case PirType.IntegerType _ ->
                    builtin2(DefaultFun.EqualsInteger, decoded, decoded);
            case PirType.ByteStringType _ ->
                    builtin2(DefaultFun.EqualsByteString, decoded, decoded);
            case PirType.StringType _ ->
                    builtin2(DefaultFun.EqualsString, decoded, decoded);
            case PirType.ListType list -> checkListItems(decoded, list.elemType(), rootId);
            case PirType.MapType map ->
                    checkMapEntries(decoded, map.keyType(), map.valueType(), rootId);
            default -> applyCheck(rootId, data);
        };
        return withDispatcher(rootCheck);
    }

    /**
     * Check a record root from its already bound constructor pair, raw fields,
     * and final list tail. The entrypoint decodes these same raw field bindings,
     * so strictness and ordinary top-level projections do not walk the root a
     * second time.
     */
    public PirTerm checkRecordComponents(
            PirTerm pair,
            List<PirTerm> rawFields,
            PirTerm remainingFields,
            PirType.RecordType recordType) {
        if (rawFields.size() != recordType.fields().size()) {
            throw new IllegalArgumentException("Record boundary field count does not match its type");
        }
        typeIds.clear();
        register(recordType);
        var tag = builtin1(DefaultFun.FstPair, pair);
        PirTerm fieldsValid = builtin1(DefaultFun.NullList, remainingFields);
        for (int i = rawFields.size() - 1; i >= 0; i--) {
            int fieldId = register(recordType.fields().get(i).type());
            fieldsValid = and(applyCheck(fieldId, rawFields.get(i)), fieldsValid);
        }
        return withDispatcher(and(equalsInteger(tag, 0), fieldsValid));
    }

    private PirTerm withDispatcher(PirTerm rootCheck) {

        var idVar = new PirTerm.Var("__boundary-type", INTEGER);
        var dataVar = new PirTerm.Var("__boundary-data", DATA);
        PirTerm dispatch = bool(false);
        var nodes = new ArrayList<>(typeIds.entrySet());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            var node = nodes.get(i);
            dispatch = new PirTerm.IfThenElse(
                    equalsInteger(idVar, node.getValue()),
                    checkNode(node.getKey(), node.getValue(), dataVar),
                    dispatch);
        }

        var checker = new PirTerm.Lam("__boundary-type", INTEGER,
                new PirTerm.Lam("__boundary-data", DATA, dispatch));
        return new PirTerm.LetRec(List.of(new PirTerm.Binding(CHECK, checker)), rootCheck);
    }

    private int register(PirType unresolved) {
        PirType type = resolveNamed(unresolved);
        Integer existing = typeIds.get(type);
        if (existing != null) return existing;

        int id = typeIds.size();
        typeIds.put(type, id); // register before children to close recursive cycles
        switch (type) {
            case PirType.RecordType record -> record.fields().forEach(f -> register(f.type()));
            case PirType.SumType sum -> sum.constructors().forEach(c ->
                    c.fields().forEach(f -> register(f.type())));
            case PirType.ListType list -> register(list.elemType());
            case PirType.MapType map -> {
                register(map.keyType());
                register(map.valueType());
            }
            case PirType.OptionalType optional -> register(optional.elemType());
            case PirType.IntegerType _, PirType.ByteStringType _, PirType.StringType _,
                    PirType.BoolType _, PirType.UnitType _, PirType.DataType _ -> { }
            case PirType.PairType _, PirType.ArrayType _, PirType.FunType _,
                    PirType.NativeValueType _ ->
                    throw new IllegalArgumentException(
                            "Unsupported strict boundary type " + type.getClass().getSimpleName());
            case PirType.NamedTypeRef _ -> throw new IllegalStateException("Named type was not resolved");
        }
        return id;
    }

    private PirType resolveNamed(PirType type) {
        if (!(type instanceof PirType.NamedTypeRef ref)) return type;
        PirType resolved = namedDefinitions.get(ref.stableId());
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "Unknown named strict boundary type '" + ref.stableId() + "'");
        }
        return resolved;
    }

    private PirTerm checkNode(PirType type, int nodeId, PirTerm data) {
        return switch (type) {
            case PirType.IntegerType _ -> forceInteger(data);
            case PirType.ByteStringType _ -> forceBytes(data);
            case PirType.StringType _ -> forceString(data);
            case PirType.BoolType _ -> checkBoolean(data);
            case PirType.UnitType _ -> checkConstructor(data, 0, List.of());
            case PirType.DataType _ -> bool(true);
            case PirType.RecordType record -> checkConstructor(
                    data, 0, record.fields().stream().map(PirType.Field::type).toList());
            case PirType.SumType sum -> checkSum(data, sum);
            case PirType.OptionalType optional -> checkOptional(data, optional.elemType());
            case PirType.ListType list -> checkList(data, list.elemType(), nodeId);
            case PirType.MapType map -> checkMap(data, map.keyType(), map.valueType(), nodeId);
            case PirType.PairType _, PirType.ArrayType _, PirType.FunType _,
                    PirType.NativeValueType _ ->
                    throw new IllegalArgumentException(
                            "Unsupported strict boundary type " + type.getClass().getSimpleName());
            case PirType.NamedTypeRef _ -> throw new IllegalStateException("Named type was not resolved");
        };
    }

    private PirTerm checkBoolean(PirTerm data) {
        var pairName = "__bool-pair";
        var pair = new PirTerm.Var(pairName, DATA);
        var tag = builtin1(DefaultFun.FstPair, pair);
        var fields = builtin1(DefaultFun.SndPair, pair);
        var empty = builtin1(DefaultFun.NullList, fields);
        var validTag = or(equalsInteger(tag, 0), equalsInteger(tag, 1));
        return new PirTerm.Let(pairName, builtin1(DefaultFun.UnConstrData, data),
                and(validTag, empty));
    }

    private PirTerm checkOptional(PirTerm data, PirType elementType) {
        var pairName = "__optional-pair";
        var pair = new PirTerm.Var(pairName, DATA);
        var tag = builtin1(DefaultFun.FstPair, pair);
        var fields = builtin1(DefaultFun.SndPair, pair);
        var some = and(equalsInteger(tag, 0), checkFields(fields, List.of(elementType), 0));
        var none = and(equalsInteger(tag, 1), checkFields(fields, List.of(), 0));
        return new PirTerm.Let(pairName, builtin1(DefaultFun.UnConstrData, data),
                or(some, none));
    }

    private PirTerm checkSum(PirTerm data, PirType.SumType sum) {
        var pairName = "__sum-pair";
        var pair = new PirTerm.Var(pairName, DATA);
        var tag = builtin1(DefaultFun.FstPair, pair);
        var fields = builtin1(DefaultFun.SndPair, pair);
        PirTerm alternatives = bool(false);
        var constructors = sum.constructors();
        for (int i = constructors.size() - 1; i >= 0; i--) {
            var constructor = constructors.get(i);
            var branch = and(equalsInteger(tag, constructor.tag()),
                    checkFields(fields,
                            constructor.fields().stream().map(PirType.Field::type).toList(), 0));
            alternatives = or(branch, alternatives);
        }
        return new PirTerm.Let(pairName, builtin1(DefaultFun.UnConstrData, data), alternatives);
    }

    private PirTerm checkConstructor(PirTerm data, int expectedTag, List<PirType> fields) {
        var pairName = "__record-pair";
        var pair = new PirTerm.Var(pairName, DATA);
        var tag = builtin1(DefaultFun.FstPair, pair);
        var fieldList = builtin1(DefaultFun.SndPair, pair);
        return new PirTerm.Let(pairName, builtin1(DefaultFun.UnConstrData, data),
                and(equalsInteger(tag, expectedTag), checkFields(fieldList, fields, 0)));
    }

    private PirTerm checkFields(PirTerm fields, List<PirType> expected, int index) {
        if (index == expected.size()) {
            return builtin1(DefaultFun.NullList, fields);
        }
        String headName = "__field-" + index;
        String tailName = "__fields-" + (index + 1);
        var head = new PirTerm.Var(headName, DATA);
        var tail = new PirTerm.Var(tailName, new PirType.ListType(DATA));
        return new PirTerm.Let(headName, builtin1(DefaultFun.HeadList, fields),
                new PirTerm.Let(tailName, builtin1(DefaultFun.TailList, fields),
                        and(applyCheck(register(expected.get(index)), head),
                                checkFields(tail, expected, index + 1))));
    }

    private PirTerm checkList(PirTerm data, PirType elementType, int nodeId) {
        return checkListItems(builtin1(DefaultFun.UnListData, data), elementType, nodeId);
    }

    private PirTerm checkListItems(PirTerm decodedItems, PirType elementType, int nodeId) {
        int elementId = register(elementType);
        String goName = "__boundary-list-" + nodeId;
        String itemsName = "__items-" + nodeId;
        var listType = new PirType.ListType(DATA);
        var items = new PirTerm.Var(itemsName, listType);
        var go = new PirTerm.Var(goName, new PirType.FunType(listType, BOOL));
        var head = builtin1(DefaultFun.HeadList, items);
        var tail = builtin1(DefaultFun.TailList, items);
        var body = new PirTerm.IfThenElse(
                builtin1(DefaultFun.NullList, items),
                bool(true),
                and(applyCheck(elementId, head), new PirTerm.App(go, tail)));
        var binding = new PirTerm.Binding(goName, new PirTerm.Lam(itemsName, listType, body));
        return new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(go, decodedItems));
    }

    private PirTerm checkMap(PirTerm data, PirType keyType, PirType valueType, int nodeId) {
        return checkMapEntries(builtin1(DefaultFun.UnMapData, data), keyType, valueType, nodeId);
    }

    private PirTerm checkMapEntries(
            PirTerm decodedEntries, PirType keyType, PirType valueType, int nodeId) {
        int keyId = register(keyType);
        int valueId = register(valueType);
        String goName = "__boundary-map-" + nodeId;
        String entriesName = "__entries-" + nodeId;
        var pairType = new PirType.PairType(DATA, DATA);
        var listType = new PirType.ListType(pairType);
        var entries = new PirTerm.Var(entriesName, listType);
        var go = new PirTerm.Var(goName, new PirType.FunType(listType, BOOL));
        var head = builtin1(DefaultFun.HeadList, entries);
        var tail = builtin1(DefaultFun.TailList, entries);
        var key = builtin1(DefaultFun.FstPair, head);
        var value = builtin1(DefaultFun.SndPair, head);
        var body = new PirTerm.IfThenElse(
                builtin1(DefaultFun.NullList, entries),
                bool(true),
                and(applyCheck(keyId, key),
                        and(applyCheck(valueId, value), new PirTerm.App(go, tail))));
        var binding = new PirTerm.Binding(goName, new PirTerm.Lam(entriesName, listType, body));
        return new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(go, decodedEntries));
    }

    private PirTerm forceInteger(PirTerm data) {
        String name = "__integer";
        var value = new PirTerm.Var(name, INTEGER);
        return new PirTerm.Let(name, builtin1(DefaultFun.UnIData, data),
                builtin2(DefaultFun.EqualsInteger, value, value));
    }

    private PirTerm forceBytes(PirTerm data) {
        String name = "__bytes";
        var value = new PirTerm.Var(name, new PirType.ByteStringType());
        return new PirTerm.Let(name, builtin1(DefaultFun.UnBData, data),
                builtin2(DefaultFun.EqualsByteString, value, value));
    }

    private PirTerm forceString(PirTerm data) {
        String name = "__string";
        var value = new PirTerm.Var(name, new PirType.StringType());
        var decoded = builtin1(DefaultFun.DecodeUtf8, builtin1(DefaultFun.UnBData, data));
        return new PirTerm.Let(name, decoded,
                builtin2(DefaultFun.EqualsString, value, value));
    }

    private PirTerm applyCheck(int typeId, PirTerm data) {
        var checkerType = new PirType.FunType(INTEGER,
                new PirType.FunType(DATA, BOOL));
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Var(CHECK, checkerType), integer(typeId)),
                data);
    }

    private static PirTerm and(PirTerm left, PirTerm right) {
        return new PirTerm.IfThenElse(left, right, bool(false));
    }

    private static PirTerm or(PirTerm left, PirTerm right) {
        return new PirTerm.IfThenElse(left, bool(true), right);
    }

    private static PirTerm equalsInteger(PirTerm value, int expected) {
        return builtin2(DefaultFun.EqualsInteger, value, integer(expected));
    }

    private static PirTerm integer(int value) {
        return new PirTerm.Const(new Constant.IntegerConst(BigInteger.valueOf(value)));
    }

    private static PirTerm bool(boolean value) {
        return new PirTerm.Const(new Constant.BoolConst(value));
    }

    private static PirTerm builtin1(DefaultFun fun, PirTerm argument) {
        return new PirTerm.App(new PirTerm.Builtin(fun), argument);
    }

    private static PirTerm builtin2(DefaultFun fun, PirTerm left, PirTerm right) {
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(fun), left), right);
    }
}
