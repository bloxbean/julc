package org.julclang.compiler.backend;

import org.julclang.compiler.codegen.StrictBoundaryGenerator;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Julc-built closed PIR for approved imported-type operations and Data codecs (ADR-059,
 * #183). Constructors use {@code DataConstr}, projections use the Java field-access lowering,
 * strict decoders use {@link StrictBoundaryGenerator} and encoders use
 * {@link PirHelpers#wrapEncode}. Only operations a type description approves are produced.
 */
public final class TypeOperations {
    /** One materialized operation: its binding name, closed term and type. */
    public record Materialized(String name, PirTerm term, PirType type) {}

    private final Map<String, LibraryType> types;
    private final Map<String, PirType> namedDefinitions;

    /**
     * @param types            the provider's type descriptions with approved operations
     * @param namedDefinitions named definitions referenced by their representations
     */
    public TypeOperations(Map<String, LibraryType> types, Map<String, PirType> namedDefinitions) {
        this.types = types;
        this.namedDefinitions = namedDefinitions;
    }

    /** Materialize an approved operation, or reject it with JULC0051. */
    public Materialized operation(LibraryRequest.Operation request) {
        var type = types.get(request.type());
        if (type == null) throw unsupported(request, "no type description with this identity");
        if (!type.supports(request.operation()))
            throw unsupported(request, "the provider does not approve " + request.operation()
                    + " for " + type.identity() + " (approved: " + type.operations() + ")");
        var representation = type.representation();
        String name = type.identity() + "#" + request.operation().name().toLowerCase(Locale.ROOT)
                + (request.member() == null ? "" : "." + request.member());
        return switch (request.operation()) {
            case CONSTRUCT -> construct(request, type, name);
            case PROJECT -> project(request, type, name);
            case EQUALS -> {
                requireNoMember(request);
                var a = new PirTerm.Var("left", representation);
                var b = new PirTerm.Var("right", representation);
                yield new Materialized(name,
                        new PirTerm.Lam("left", representation, new PirTerm.Lam("right", representation,
                                equality(a, b, representation))),
                        new PirType.FunType(representation, new PirType.FunType(representation, BOOL)));
            }
            case ENCODE -> {
                requireNoMember(request);
                yield new Materialized(name, encoder(representation), new PirType.FunType(representation, DATA));
            }
            case DECODE_STRICT -> {
                requireNoMember(request);
                yield new Materialized(name, decoder(representation), new PirType.FunType(DATA, representation));
            }
            case MATCH -> throw unsupported(request,
                    "MATCH is a permission for producer DataMatch terms, not a materialized operation");
            case BOUNDARY -> throw unsupported(request,
                    "BOUNDARY is a permission for validator datum, redeemer and parameter types");
        };
    }

    /** Materialize a Data codec whose nominal leaves approve it, or reject it with JULC0051. */
    public Materialized codec(LibraryRequest.Codec request) {
        var required = request.direction() == LibraryRequest.Codec.Direction.ENCODE
                ? LibraryType.Operation.ENCODE
                : LibraryType.Operation.DECODE_STRICT;
        requireCodecLeaves(request, request.type(), required);
        String name = "julc.codec#" + request.direction().name().toLowerCase(Locale.ROOT) + "#"
                + fingerprint(request.type());
        return request.direction() == LibraryRequest.Codec.Direction.ENCODE
                ? new Materialized(name, encoder(request.type()), new PirType.FunType(request.type(), DATA))
                : new Materialized(name, decoder(request.type()), new PirType.FunType(DATA, request.type()));
    }

    private Materialized construct(LibraryRequest.Operation request, LibraryType type, String name) {
        var representation = type.representation();
        if (type.newType()) {
            requireNoMember(request);
            return new Materialized(name, new PirTerm.Lam("value", representation,
                    new PirTerm.Var("value", representation)), new PirType.FunType(representation, representation));
        }
        return switch (representation) {
            case PirType.RecordType record -> {
                requireNoMember(request);
                yield constructor(name, 0, record, record.fields());
            }
            case PirType.SumType sum -> {
                if (request.member() == null) throw unsupported(request, "name the constructor to build");
                var constructor = sum.constructors().stream()
                        .filter(c -> c.name().equals(request.member())).findFirst()
                        .orElseThrow(() -> unsupported(request, "no constructor " + request.member()));
                yield constructor(name, constructor.tag(), sum, constructor.fields());
            }
            default -> throw unsupported(request, "only records, sums and newtypes are constructed");
        };
    }

    private static Materialized constructor(String name, int tag, PirType type, List<PirType.Field> fields) {
        var arguments = new ArrayList<PirTerm>();
        for (int i = 0; i < fields.size(); i++)
            arguments.add(new PirTerm.Var("field" + i, fields.get(i).type()));
        PirTerm term = new PirTerm.DataConstr(tag, type, arguments);
        PirType result = type;
        for (int i = fields.size() - 1; i >= 0; i--) {
            term = new PirTerm.Lam("field" + i, fields.get(i).type(), term);
            result = new PirType.FunType(fields.get(i).type(), result);
        }
        return new Materialized(name, term, result);
    }

    private Materialized project(LibraryRequest.Operation request, LibraryType type, String name) {
        var representation = type.representation();
        if (type.newType()) {
            if (request.member() != null && type.fields().stream().noneMatch(f -> f.name().equals(request.member())))
                throw unsupported(request, "no field " + request.member());
            return new Materialized(name, new PirTerm.Lam("value", representation,
                    new PirTerm.Var("value", representation)), new PirType.FunType(representation, representation));
        }
        if (!(representation instanceof PirType.RecordType record))
            throw unsupported(request, "only records and newtypes have projections");
        if (request.member() == null) throw unsupported(request, "name the field to project");
        int index = -1;
        for (int i = 0; i < record.fields().size(); i++)
            if (record.fields().get(i).name().equals(request.member())) index = i;
        if (index < 0) throw unsupported(request, "no field " + request.member());
        var fieldType = record.fields().get(index).type();
        // The same field-access lowering the Java frontend emits for record.field().
        PirTerm fields = call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, new PirTerm.Var("value", record)));
        for (int i = 0; i < index; i++) fields = call(DefaultFun.TailList, fields);
        var projected = PirHelpers.wrapDecode(call(DefaultFun.HeadList, fields), fieldType);
        return new Materialized(name, new PirTerm.Lam("value", record, projected),
                new PirType.FunType(record, fieldType));
    }

    private PirTerm encoder(PirType type) {
        return new PirTerm.Lam("value", type, PirHelpers.wrapEncode(new PirTerm.Var("value", type), resolve(type)));
    }

    private PirTerm decoder(PirType type) {
        var data = new PirTerm.Var("data", DATA);
        var check = new StrictBoundaryGenerator(namedDefinitions).check(data, type);
        return new PirTerm.Lam("data", DATA, new PirTerm.IfThenElse(check,
                PirHelpers.wrapDecode(data, resolve(type)), new PirTerm.Error(type)));
    }

    /** Java {@code equals} semantics by representation. */
    private static PirTerm equality(PirTerm a, PirTerm b, PirType type) {
        return switch (type) {
            case PirType.IntegerType _ -> call(DefaultFun.EqualsInteger, a, b);
            case PirType.ByteStringType _ -> call(DefaultFun.EqualsByteString, a, b);
            case PirType.StringType _ -> call(DefaultFun.EqualsString, a, b);
            case PirType.BoolType _ -> new PirTerm.IfThenElse(a, b,
                    new PirTerm.IfThenElse(b, bool(false), bool(true)));
            default -> call(DefaultFun.EqualsData, a, b);
        };
    }

    /** Structural leaves must be Data-encodable; nominal leaves must approve the operation. */
    private void requireCodecLeaves(LibraryRequest request, PirType type, LibraryType.Operation required) {
        switch (type) {
            case PirType.IntegerType _, PirType.ByteStringType _, PirType.StringType _, PirType.BoolType _,
                 PirType.DataType _ -> {}
            case PirType.ListType list -> {
                if (list.elemType() instanceof PirType.PairType)
                    throw unsupported(request, "pair lists have no Data encoding; use a Map type");
                requireCodecLeaves(request, list.elemType(), required);
            }
            case PirType.MapType map -> {
                requireCodecLeaves(request, map.keyType(), required);
                requireCodecLeaves(request, map.valueType(), required);
            }
            case PirType.OptionalType optional -> requireCodecLeaves(request, optional.elemType(), required);
            case PirType.NamedTypeRef ref -> requireApproved(request, types.get(ref.stableId()), ref.stableId(), required);
            case PirType.RecordType _, PirType.SumType _ -> requireApproved(request, types.values().stream()
                    .filter(t -> t.representation().equals(type)).findFirst().orElse(null),
                    PirVerifier.show(type), required);
            default -> throw unsupported(request, PirVerifier.show(type) + " has no Data codec");
        }
    }

    private static void requireApproved(LibraryRequest request, LibraryType type, String what,
                                        LibraryType.Operation required) {
        if (type == null) throw unsupported(request, what + " is not a type described by this provider");
        if (!type.supports(required))
            throw unsupported(request, type.identity() + " does not approve " + required);
    }

    private PirType resolve(PirType type) {
        return type instanceof PirType.NamedTypeRef ref
                ? namedDefinitions.getOrDefault(ref.stableId(), type)
                : type;
    }

    private static void requireNoMember(LibraryRequest.Operation request) {
        if (request.member() != null)
            throw unsupported(request, request.operation() + " does not take a member");
    }

    /** A stable content fingerprint of a structural type, for binding names. */
    private static String fingerprint(PirType type) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(type.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PirTerm call(DefaultFun fun, PirTerm... arguments) {
        PirTerm term = new PirTerm.Builtin(fun);
        for (var argument : arguments) term = new PirTerm.App(term, argument);
        return term;
    }

    private static PirTerm bool(boolean value) {
        return new PirTerm.Const(Constant.bool(value));
    }

    private static BackendException unsupported(LibraryRequest request, String detail) {
        return new BackendException(DiagnosticCodes.BACKEND_UNSUPPORTED_REQUEST, request.describe(),
                request.describe(), detail);
    }

    private static final PirType BOOL = new PirType.BoolType();
    private static final PirType DATA = new PirType.DataType();
}
