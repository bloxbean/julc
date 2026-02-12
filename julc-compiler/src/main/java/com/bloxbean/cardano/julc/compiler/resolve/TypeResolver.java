package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VarType;
import com.github.javaparser.ast.type.VoidType;

import java.util.*;

/**
 * Resolves Java types to PIR types.
 */
public class TypeResolver {

    // Map of known record types (name -> PirType.RecordType)
    private final Map<String, PirType.RecordType> recordTypes = new LinkedHashMap<>();
    // Map of known sum types (sealed interface name -> PirType.SumType)
    private final Map<String, PirType.SumType> sumTypes = new LinkedHashMap<>();
    // Map of variant name -> its enclosing SumType (for constructor tag lookup)
    private final Map<String, PirType.SumType> variantToSumType = new LinkedHashMap<>();

    private static final Set<String> LEDGER_HASH_TYPES = Set.of(
            "PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId", "TokenName", "DatumHash", "TxId");

    // Types not yet registered as RecordType/SumType — resolved as opaque DataType
    private static final Set<String> LEDGER_DATA_TYPES = Set.of(
            "StakingCredential", "ScriptPurpose",
            "Vote", "Voter", "DRep", "Delegatee",
            "GovernanceActionId", "GovernanceAction", "ProposalProcedure",
            "TxCert", "Rational", "ProtocolVersion", "Committee");

    public void registerRecord(RecordDeclaration rd) {
        var name = rd.getNameAsString();
        if (recordTypes.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate record type: '" + name + "'. "
                    + "A record with this name is already registered.");
        }
        var fields = new ArrayList<PirType.Field>();
        for (var param : rd.getParameters()) {
            fields.add(new PirType.Field(param.getNameAsString(), resolve(param.getType())));
        }
        var recordType = new PirType.RecordType(name, fields);
        recordTypes.put(name, recordType);
    }

    /** Check if a type name is already registered (as record or sealed interface). */
    public boolean isRegistered(String name) {
        return recordTypes.containsKey(name) || sumTypes.containsKey(name);
    }

    /**
     * Register a ledger record type directly (not from a JavaParser RecordDeclaration).
     * Used by {@link LedgerTypeRegistry} to pre-register ledger types with known schemas.
     */
    public void registerLedgerRecord(String name, List<PirType.Field> fields) {
        var recordType = new PirType.RecordType(name, fields);
        recordTypes.put(name, recordType);
    }

    /**
     * Register a ledger sum type (sealed interface) directly.
     * Used by {@link LedgerTypeRegistry} to pre-register ledger types with known schemas.
     */
    public void registerLedgerSumType(String name, List<PirType.Constructor> constructors) {
        var sumType = new PirType.SumType(name, constructors);
        sumTypes.put(name, sumType);
        for (var ctor : constructors) {
            variantToSumType.put(ctor.name(), sumType);
        }
    }

    public void registerSealedInterface(ClassOrInterfaceDeclaration decl) {
        var interfaceName = decl.getNameAsString();
        if (sumTypes.containsKey(interfaceName)) {
            throw new IllegalArgumentException("Duplicate sealed interface: '" + interfaceName + "'. "
                    + "A sealed interface with this name is already registered.");
        }
        var constructors = new ArrayList<PirType.Constructor>();
        int tag = 0;
        for (var permittedType : decl.getPermittedTypes()) {
            var variantName = permittedType.getNameAsString();
            // Look up the record type for this variant (must already be registered)
            var recordType = recordTypes.get(variantName);
            List<PirType.Field> fields = recordType != null ? recordType.fields() : List.of();
            constructors.add(new PirType.Constructor(variantName, tag, fields));
            tag++;
        }
        var sumType = new PirType.SumType(interfaceName, constructors);
        sumTypes.put(interfaceName, sumType);
        // Register each variant → sum type mapping
        for (var ctor : constructors) {
            variantToSumType.put(ctor.name(), sumType);
        }
    }

    public Optional<PirType.SumType> lookupSumType(String name) {
        return Optional.ofNullable(sumTypes.get(name));
    }

    public Optional<PirType.SumType> lookupSumTypeForVariant(String variantName) {
        return Optional.ofNullable(variantToSumType.get(variantName));
    }

    public PirType resolve(Type type) {
        if (type instanceof PrimitiveType pt) {
            return switch (pt.getType()) {
                case BOOLEAN -> new PirType.BoolType();
                case INT, LONG -> new PirType.IntegerType();
                default -> throw new IllegalArgumentException("Unsupported primitive type: " + pt);
            };
        }
        if (type instanceof VoidType) {
            return new PirType.UnitType();
        }
        if (type instanceof ClassOrInterfaceType ct) {
            return resolveClassType(ct);
        }
        if (type instanceof VarType) {
            // 'var' declarations - type will be inferred by PirGenerator from initializer
            return new PirType.DataType(); // placeholder, PirGenerator overrides this
        }
        // byte[] is represented as ArrayType
        if (type instanceof com.github.javaparser.ast.type.ArrayType at) {
            if (at.getComponentType() instanceof PrimitiveType pt &&
                    pt.getType() == PrimitiveType.Primitive.BYTE) {
                return new PirType.ByteStringType();
            }
            throw new IllegalArgumentException("Arrays are not supported; use List. Got: " + at);
        }
        throw new IllegalArgumentException("Cannot resolve type: " + type);
    }

    private PirType resolveClassType(ClassOrInterfaceType ct) {
        var name = ct.getNameAsString();

        // Built-in Java types
        return switch (name) {
            case "BigInteger" -> new PirType.IntegerType();
            case "String" -> new PirType.StringType();
            case "Boolean" -> new PirType.BoolType();
            case "PlutusData", "ConstrData", "MapData", "ListData", "IntData", "BytesData" -> new PirType.DataType();
            case "List" -> {
                PirType elemType = new PirType.DataType();
                var listArgs = ct.getTypeArguments();
                if (listArgs.isPresent() && !listArgs.get().isEmpty()) {
                    elemType = resolve(listArgs.get().get(0));
                }
                yield new PirType.ListType(elemType);
            }
            case "Map" -> {
                PirType keyType = new PirType.DataType();
                PirType valType = new PirType.DataType();
                var mapArgs = ct.getTypeArguments();
                if (mapArgs.isPresent()) {
                    var args = mapArgs.get();
                    if (args.size() >= 1) keyType = resolve(args.get(0));
                    if (args.size() >= 2) valType = resolve(args.get(1));
                }
                yield new PirType.MapType(keyType, valType);
            }
            case "Optional" -> {
                PirType elemType = new PirType.DataType();
                var optArgs = ct.getTypeArguments();
                if (optArgs.isPresent() && !optArgs.get().isEmpty()) {
                    elemType = resolve(optArgs.get().get(0));
                }
                yield new PirType.OptionalType(elemType);
            }
            default -> {
                // Check ledger hash types -> ByteStringType
                if (LEDGER_HASH_TYPES.contains(name)) yield new PirType.ByteStringType();
                // Check registered record types (includes ledger records from LedgerTypeRegistry)
                if (recordTypes.containsKey(name)) yield recordTypes.get(name);
                // Check registered sum types (sealed interfaces, including ledger sum types)
                if (sumTypes.containsKey(name)) yield sumTypes.get(name);
                // Check remaining ledger data types -> opaque DataType
                if (LEDGER_DATA_TYPES.contains(name)) yield new PirType.DataType();
                throw new IllegalArgumentException("Unknown type: " + name);
            }
        };
    }

    public Optional<PirType.RecordType> lookupRecord(String name) {
        return Optional.ofNullable(recordTypes.get(name));
    }
}
