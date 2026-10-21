package org.julclang.compiler.backend;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.codegen.StrictBoundaryGenerator;
import org.julclang.compiler.codegen.ValidatorWrapper;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.Constant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a {@link ValidatorProgram} (ADR-059). Every descriptor, including one with a
 * single handler, dispatches on the ScriptInfo tag through the shared
 * {@link ValidatorWrapper#wrapMultiValidator}: unconfigured purposes fail and only the
 * selected handler's datum and redeemer are decoded.
 */
final class ValidatorCompiler {
    private static final String HANDLER_PREFIX = "$julc$handler$";
    private static final String PARAMETER_PREFIX = "$julc$param$";
    private static final String CONTEXT_TYPE_ID = "org.julclang.ledger.ScriptContext";

    private ValidatorCompiler() {}

    /** A handler's boundary signature: an optional datum, the redeemer and the context. */
    private record Shape(PirType datum, PirType redeemer, PirType context) {}

    static ValidatorResult compile(ValidatorProgram program, CompilationContext context) {
        var capabilities = BackendContract.capabilities(context);
        String subject = "validator " + program.identity();
        capabilities.requireRevision(program.revision(), BackendContract.REVISION_2, subject);
        capabilities.require(program.requiredCapabilities(), subject);
        if (program.identity().isBlank()) throw invalid(subject, "the validator identity must not be blank");
        CompilerBackend.requireTarget(program.target(), context, subject);
        capabilities.require(Set.of(boundaryCapability(program.boundary(), subject)), subject);
        if (program.handlers().isEmpty()) throw invalid(subject, "a validator needs at least one handler");
        if (!program.parameters().isEmpty())
            capabilities.require(Set.of(BackendCapability.VALIDATOR_PARAMETERS), subject);

        var handlers = new EnumMap<ContractSchema.Purpose, ValidatorProgram.Handler>(ContractSchema.Purpose.class);
        for (var handler : program.handlers()) {
            capabilities.require(Set.of(BackendContract.purposeCapability(handler.purpose())), subject);
            if (handlers.putIfAbsent(handler.purpose(), handler) != null)
                throw invalid(subject, "purpose " + handler.purpose() + " has more than one handler");
            if (handler.symbol().isBlank())
                throw invalid(subject, "the " + handler.purpose() + " handler symbol must not be blank");
            if (handler.purpose() == ContractSchema.Purpose.SPEND)
                capabilities.require(Set.of(datumCapability(handler.datum())), subject);
            else if (handler.datum() != DatumProfile.ABSENT)
                throw invalid(subject, "only spending handlers receive a datum; the "
                        + handler.purpose() + " handler must use DatumProfile.ABSENT");
        }

        var unit = ProgramUnit.prepare(subject, capabilities, program.namedTypes(), program.imports(),
                program.definitions());
        var namedTypes = unit.namedTypes();
        var uncheckable = uncheckable(unit.uncheckable());
        requireParameters(program.parameters(), namedTypes, uncheckable, subject);
        var ordered = handlers.values().stream()
                .sorted(Comparator.comparingInt(h -> BackendContract.ledgerTag(h.purpose())))
                .toList();
        var shapes = new EnumMap<ContractSchema.Purpose, Shape>(ContractSchema.Purpose.class);
        for (var handler : ordered)
            shapes.put(handler.purpose(), shape(handler, program.parameters(), namedTypes, uncheckable, subject));

        var verifier = unit.verifier(BackendContract.builtinCaseLowering(context));
        unit.verifyDefinitions(verifier);
        for (var handler : ordered)
            verifier.verify(handler.symbol(), handler.term(), handler.type(), unit.environment());

        var bindings = new LinkedHashMap<String, PirTerm>();
        var invocations = new LinkedHashMap<Integer, PirTerm>();
        var parameterCounts = new LinkedHashMap<Integer, Integer>();
        var datumOptional = new LinkedHashMap<Integer, Boolean>();
        var datumTypes = new LinkedHashMap<Integer, PirType>();
        var redeemerTypes = new LinkedHashMap<Integer, PirType>();
        var handlerAbis = new ArrayList<ValidatorAbi.HandlerAbi>();
        for (var handler : ordered) {
            int tag = BackendContract.ledgerTag(handler.purpose());
            var shape = shapes.get(handler.purpose());
            String name = HANDLER_PREFIX + handler.purpose().name().toLowerCase(Locale.ROOT);
            bindings.put(name, handler.term());
            // Every handler receives all decoded parameters as leading arguments.
            PirTerm invocation = new PirTerm.Var(name, handler.type());
            for (int i = 0; i < program.parameters().size(); i++)
                invocation = new PirTerm.App(invocation, new PirTerm.Var(
                        PARAMETER_PREFIX + i, program.parameters().get(i).type()));
            invocations.put(tag, adaptUnits(invocation, shape, namedTypes));
            parameterCounts.put(tag, shape.datum() != null ? 3 : 2);
            datumOptional.put(tag, handler.datum() == DatumProfile.OPTIONAL);
            if (shape.datum() != null) datumTypes.put(tag, shape.datum());
            redeemerTypes.put(tag, shape.redeemer());
            handlerAbis.add(new ValidatorAbi.HandlerAbi(handler.purpose(), tag, handler.symbol(),
                    handler.datum(), shape.datum(), shape.redeemer()));
        }
        PirTerm wrapped = new ValidatorWrapper(namedTypes).wrapMultiValidator(
                invocations, parameterCounts, datumOptional, datumTypes, redeemerTypes);
        // Deployment parameters are outer lambdas in ABI order with the Java @Param shape:
        // each raw Data argument is decoded once, when it is applied.
        for (int i = program.parameters().size() - 1; i >= 0; i--) {
            var parameter = program.parameters().get(i);
            String raw = PARAMETER_PREFIX + i + "$raw";
            wrapped = new PirTerm.Lam(raw, new PirType.DataType(), new PirTerm.Let(PARAMETER_PREFIX + i,
                    PirHelpers.wrapDecode(new PirTerm.Var(raw, new PirType.DataType()), parameter.type()),
                    wrapped));
        }
        var lowered = PirBackend.lower(unit.link(bindings, wrapped), context, null, true);
        var abi = new ValidatorAbi(program.identity(), context.target(), program.boundary(),
                program.parameters(), handlerAbis, namedTypes);
        return new ValidatorResult(lowered, abi);
    }

    /** Check {@code parameters -> [datum ->] redeemer -> context -> Bool} for the handler's role. */
    private static Shape shape(ValidatorProgram.Handler handler, List<Parameter> parameters,
                               Map<String, PirType> namedTypes, List<PirType> uncheckable, String subject) {
        var arguments = new ArrayList<PirType>();
        PirType result = handler.type();
        while (result instanceof PirType.FunType fn) {
            arguments.add(fn.paramType());
            result = fn.returnType();
        }
        boolean hasDatum = handler.purpose() == ContractSchema.Purpose.SPEND
                && handler.datum() != DatumProfile.ABSENT;
        int expected = parameters.size() + (hasDatum ? 1 : 0) + 2;
        String role = handler.purpose() + " handler " + handler.symbol();
        if (arguments.size() != expected || !(result instanceof PirType.BoolType))
            throw invalid(subject, role + " must have type "
                    + (parameters.isEmpty() ? "" : "parameters -> ")
                    + (hasDatum ? "datum -> " : "") + "redeemer -> context -> Bool, but has type "
                    + PirVerifier.show(handler.type()));
        for (int i = 0; i < parameters.size(); i++)
            if (!arguments.get(i).equals(parameters.get(i).type()))
                throw invalid(subject, role + " argument " + (i + 1) + " must have the type of parameter "
                        + parameters.get(i).name() + ", " + PirVerifier.show(parameters.get(i).type()));
        int offset = parameters.size();
        PirType datum = hasDatum ? arguments.get(offset) : null;
        PirType redeemer = arguments.get(offset + (hasDatum ? 1 : 0));
        PirType context = arguments.getLast();
        if (!isContext(context, namedTypes))
            throw invalid(subject, role + " context argument must be Data or the ledger ScriptContext, not "
                    + PirVerifier.show(context));
        if (handler.datum() == DatumProfile.OPTIONAL && !(resolve(datum, namedTypes) instanceof PirType.OptionalType))
            throw invalid(subject, role + " uses DatumProfile.OPTIONAL, so its datum argument must be an"
                    + " Optional type, not " + PirVerifier.show(datum));
        if (datum != null) requireBoundary(datum, namedTypes, uncheckable, subject, role + " datum");
        requireBoundary(redeemer, namedTypes, uncheckable, subject, role + " redeemer");
        return new Shape(datum, redeemer, context);
    }

    /**
     * Parameters are decoded, not validated, on-chain (as Java {@code @Param}); their types must
     * have a Data decoding. Validate parameter Data off-chain with {@link BoundaryPrograms}.
     */
    private static void requireParameters(List<Parameter> parameters, Map<String, PirType> namedTypes,
                                          List<PirType> uncheckable, String subject) {
        var names = new java.util.HashSet<String>();
        for (var parameter : parameters) {
            String what = "parameter " + parameter.name();
            if (parameter.name().isBlank()) throw invalid(subject, "a parameter name must not be blank");
            if (!names.add(parameter.name())) throw invalid(subject, what + " is declared more than once");
            if (resolve(parameter.type(), namedTypes) instanceof PirType.UnitType)
                throw invalid(subject, what + " has type Unit, which decodes as raw Data; use Data or a record");
            requireBoundary(parameter.type(), namedTypes, uncheckable, subject, what);
        }
    }

    /**
     * Unit boundary values are checked as {@code Constr 0 []} Data, but Julc's shared decoder
     * passes that Data through. Give the handler the native unit it declares instead.
     */
    private static PirTerm adaptUnits(PirTerm invocation, Shape shape, Map<String, PirType> namedTypes) {
        boolean unitDatum = shape.datum() != null && resolve(shape.datum(), namedTypes) instanceof PirType.UnitType;
        boolean unitRedeemer = resolve(shape.redeemer(), namedTypes) instanceof PirType.UnitType;
        if (!unitDatum && !unitRedeemer) return invocation;
        var unit = new PirTerm.Const(Constant.unit());
        PirTerm body = invocation;
        if (shape.datum() != null)
            body = new PirTerm.App(body, unitDatum ? unit : new PirTerm.Var("$julc$datum", shape.datum()));
        body = new PirTerm.App(body, unitRedeemer ? unit : new PirTerm.Var("$julc$redeemer", shape.redeemer()));
        body = new PirTerm.App(body, new PirTerm.Var("$julc$context", shape.context()));
        body = new PirTerm.Lam("$julc$context", shape.context(), body);
        body = new PirTerm.Lam("$julc$redeemer", shape.redeemer(), body);
        if (shape.datum() != null) body = new PirTerm.Lam("$julc$datum", shape.datum(), body);
        return body;
    }

    /**
     * Types whose strict boundary check would misjudge valid Data: imported descriptions
     * without BOUNDARY, and bundled ledger types such as the map-encoded {@code Value} (whose
     * PIR record form the checker would treat as constructor data) and records containing them.
     */
    static List<PirType> uncheckable(List<PirType> imported) {
        var all = new ArrayList<>(LedgerContext.UNCHECKABLE);
        for (var type : imported) if (!all.contains(type)) all.add(type);
        return all;
    }

    static void requireBoundary(PirType type, Map<String, PirType> namedTypes, List<PirType> uncheckable,
                                String subject, String what) {
        if (containsNative(type, namedTypes, new java.util.HashSet<>()))
            throw invalid(subject, what + " has a native type, which has no Data boundary encoding: "
                    + PirVerifier.show(type));
        var special = find(type, namedTypes, uncheckable, new java.util.HashSet<>());
        if (special != null)
            throw invalid(subject, what + " contains " + PirVerifier.show(special) + ", whose Data "
                    + "encoding the strict boundary cannot check (for example the map-encoded ledger "
                    + "Value); use Data for that part or an approved type");
        try {
            new StrictBoundaryGenerator(namedTypes).ensureSupported(type);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw invalid(subject, what + ": " + e.getMessage());
        }
    }

    private static boolean containsNative(PirType type, Map<String, PirType> namedTypes, Set<String> visiting) {
        return switch (type) {
            case PirType.NamedTypeRef ref -> visiting.add(ref.stableId())
                    && namedTypes.containsKey(ref.stableId())
                    && containsNative(namedTypes.get(ref.stableId()), namedTypes, visiting);
            case PirType.RecordType r -> r.fields().stream()
                    .anyMatch(f -> containsNative(f.type(), namedTypes, visiting));
            case PirType.SumType s -> s.constructors().stream().flatMap(c -> c.fields().stream())
                    .anyMatch(f -> containsNative(f.type(), namedTypes, visiting));
            case PirType.ListType l -> containsNative(l.elemType(), namedTypes, visiting);
            case PirType.MapType m -> containsNative(m.keyType(), namedTypes, visiting)
                    || containsNative(m.valueType(), namedTypes, visiting);
            case PirType.OptionalType o -> containsNative(o.elemType(), namedTypes, visiting);
            default -> PirType.containsNativeOpaque(type);
        };
    }

    /** The first component of {@code type} equal to one of {@code targets}, or null. */
    private static PirType find(PirType type, Map<String, PirType> namedTypes, List<PirType> targets,
                                Set<String> visiting) {
        if (targets.contains(type)) return type;
        return switch (type) {
            case PirType.NamedTypeRef ref -> visiting.add(ref.stableId()) && namedTypes.containsKey(ref.stableId())
                    ? find(namedTypes.get(ref.stableId()), namedTypes, targets, visiting) : null;
            case PirType.RecordType r -> r.fields().stream()
                    .map(f -> find(f.type(), namedTypes, targets, visiting))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            case PirType.SumType s -> s.constructors().stream().flatMap(c -> c.fields().stream())
                    .map(f -> find(f.type(), namedTypes, targets, visiting))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            case PirType.ListType l -> find(l.elemType(), namedTypes, targets, visiting);
            case PirType.MapType m -> {
                var key = find(m.keyType(), namedTypes, targets, visiting);
                yield key != null ? key : find(m.valueType(), namedTypes, targets, visiting);
            }
            case PirType.OptionalType o -> find(o.elemType(), namedTypes, targets, visiting);
            default -> null;
        };
    }

    private static boolean isContext(PirType type, Map<String, PirType> namedTypes) {
        var resolved = resolve(type, namedTypes);
        return resolved instanceof PirType.DataType || resolved.equals(LedgerContext.TYPE);
    }

    private static PirType resolve(PirType type, Map<String, PirType> namedTypes) {
        return type instanceof PirType.NamedTypeRef ref
                ? namedTypes.getOrDefault(ref.stableId(), type)
                : type;
    }

    private static BackendCapability boundaryCapability(String boundary, String subject) {
        try {
            return new BackendCapability("boundary." + boundary);
        } catch (IllegalArgumentException e) {
            throw invalid(subject, "unknown boundary policy " + boundary);
        }
    }

    private static BackendCapability datumCapability(DatumProfile profile) {
        return switch (profile) {
            case REQUIRED -> BackendCapability.SPEND_DATUM_REQUIRED;
            case OPTIONAL -> BackendCapability.SPEND_DATUM_OPTIONAL;
            case ABSENT -> BackendCapability.SPEND_DATUM_ABSENT;
        };
    }

    private static BackendException invalid(String subject, String detail) {
        return new BackendException(DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR, subject, subject, detail);
    }

    /** Bundled ledger type facts, loaded on first use. */
    private static final class LedgerContext {
        static final Map<String, LibraryType> TYPES = new LedgerTypeProvider().types();
        static final PirType TYPE = TYPES.get(CONTEXT_TYPE_ID).representation();
        static final List<PirType> UNCHECKABLE = TYPES.values().stream()
                .filter(t -> t.representation() instanceof PirType.RecordType
                        || t.representation() instanceof PirType.SumType)
                .filter(t -> !t.supports(LibraryType.Operation.BOUNDARY))
                .map(LibraryType::representation)
                .toList();
    }
}
