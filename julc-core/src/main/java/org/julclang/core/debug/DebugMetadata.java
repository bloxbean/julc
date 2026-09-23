package org.julclang.core.debug;

import org.julclang.core.Constant;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.flat.UplcFlatEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, transport-independent Java debug sidecar. The sidecar is deliberately separate
 * from {@link Term}, FLAT and CBOR: attaching it cannot change an executable artifact.
 *
 * <p>All occurrence ids use {@value #TRAVERSAL} preorder traversal, counting a shared object
 * once for every structural occurrence. Environment slots are one-based de Bruijn indices.</p>
 */
public record DebugMetadata(
        String format,
        int major,
        int minor,
        List<String> requiredCapabilities,
        Artifact artifact,
        List<Source> sources,
        List<Scope> scopes,
        List<Binding> bindings,
        List<TypeInfo> types,
        List<Layout> layouts,
        List<LoweredBinding> loweredBindings,
        List<SuspensionPoint> suspensionPoints) {

    public static final String FORMAT = "julc-java-debug";
    public static final int MAJOR = 1;
    public static final int MINOR = 1;
    public static final String TRAVERSAL = "uplc-preorder-v1";
    public static final Set<String> SUPPORTED_CAPABILITIES = Set.of(
            "anchored-lambda-occurrences-v1", "exact-environment-slots-v1",
            "scalar-values-v1", "raw-data-v1");

    public static final int MAX_SOURCES = 128;
    public static final int MAX_SCOPES = 16_384;
    public static final int MAX_BINDINGS = 65_536;
    public static final int MAX_TYPES = 16_384;
    public static final int MAX_LAYOUTS = 16_384;
    public static final int MAX_LOWERED_BINDINGS = 65_536;
    public static final int MAX_OCCURRENCES = 2_000_000;
    public static final int MAX_SUSPENSION_POINTS = MAX_OCCURRENCES;
    public static final int MAX_PARAMETERS = 256;
    public static final int MAX_TOTAL_SOURCE_CHARS = 4_000_000;
    public static final int MAX_TEXT_CHARS = 65_536;

    public DebugMetadata {
        Objects.requireNonNull(format, "format");
        requiredCapabilities = List.copyOf(requiredCapabilities);
        Objects.requireNonNull(artifact, "artifact");
        sources = List.copyOf(sources);
        scopes = List.copyOf(scopes);
        bindings = List.copyOf(bindings);
        types = List.copyOf(types);
        layouts = List.copyOf(layouts);
        loweredBindings = List.copyOf(loweredBindings);
        suspensionPoints = List.copyOf(suspensionPoints);
    }

    /** Validate all bounded tables, references, versions and acyclic ownership graphs. */
    public void validate() {
        if (!FORMAT.equals(format)) throw new ValidationException("Unknown debug metadata format: " + format);
        if (major != MAJOR) throw new ValidationException("Unsupported debug metadata major version: " + major);
        if (minor != MINOR) throw new ValidationException("Unsupported debug metadata minor version: " + minor);
        for (String capability : requiredCapabilities) {
            if (!SUPPORTED_CAPABILITIES.contains(capability)) {
                throw new ValidationException("Unsupported required debug capability: " + capability);
            }
        }
        bounded("sources", sources.size(), MAX_SOURCES);
        bounded("scopes", scopes.size(), MAX_SCOPES);
        bounded("bindings", bindings.size(), MAX_BINDINGS);
        bounded("types", types.size(), MAX_TYPES);
        bounded("layouts", layouts.size(), MAX_LAYOUTS);
        bounded("loweredBindings", loweredBindings.size(), MAX_LOWERED_BINDINGS);
        bounded("occurrences", artifact.occurrenceCount(), MAX_OCCURRENCES);
        bounded("suspensionPoints", suspensionPoints.size(), MAX_SUSPENSION_POINTS);
        bounded("parameterDigests", artifact.parameterDigests().size(), MAX_PARAMETERS);
        if (requiredCapabilities.size() != new HashSet<>(requiredCapabilities).size()) {
            throw new ValidationException("Duplicate required debug capability");
        }
        if (!requiredCapabilities.contains("anchored-lambda-occurrences-v1")) {
            throw new ValidationException("Missing required anchored lambda occurrence capability");
        }
        if (!TRAVERSAL.equals(artifact.traversal())) {
            throw new ValidationException("Unsupported debug traversal: " + artifact.traversal());
        }
        if (artifact.occurrenceCount() < 1) throw new ValidationException("Artifact has no term occurrences");
        requireSha256("artifact.flatSha256", artifact.flatSha256());
        requireSha256("artifact.unparameterizedFlatSha256", artifact.unparameterizedFlatSha256());
        requireSha256("artifact.sourceManifestSha256", artifact.sourceManifestSha256());
        artifact.parameterDigests().forEach(digest -> requireSha256("artifact.parameterDigest", digest));
        boundedText("artifact.compilerIdentity", artifact.compilerIdentity());
        boundedText("artifact.targetId", artifact.targetId());
        boundedText("artifact.options", artifact.options());
        boundedText("artifact.optimizationProvenance", artifact.optimizationProvenance());

        var sourceIds = uniqueIds("source", sources.stream().map(Source::id).toList());
        var scopeIds = uniqueIds("scope", scopes.stream().map(Scope::id).toList());
        var bindingIds = uniqueIds("binding", bindings.stream().map(Binding::id).toList());
        var typeIds = uniqueIds("type", types.stream().map(TypeInfo::id).toList());
        var layoutIds = uniqueIds("layout", layouts.stream().map(Layout::id).toList());
        var loweredIds = uniqueIds("lowered binding", loweredBindings.stream().map(LoweredBinding::id).toList());

        long totalSourceChars = 0;
        for (Source source : sources) {
            boundedText("source.uri", source.uri());
            requireSha256("source.contentSha256", source.contentSha256());
            if (source.content() != null
                    && !sha256(source.content().getBytes(StandardCharsets.UTF_8)).equals(source.contentSha256())) {
                throw new ValidationException("Source digest mismatch: " + source.id());
            }
            if (source.content() != null) {
                totalSourceChars += source.content().length();
                if (totalSourceChars > MAX_TOTAL_SOURCE_CHARS) {
                    throw new ValidationException("source content exceeds limit " + MAX_TOTAL_SOURCE_CHARS);
                }
            }
            if (source.lineStarts().isEmpty() || source.lineStarts().getFirst() != 0) {
                throw new ValidationException("Source line table must start at zero: " + source.id());
            }
            int previous = -1;
            for (int start : source.lineStarts()) {
                if (start < 0 || start <= previous || source.content() != null && start > source.content().length()) {
                    throw new ValidationException("Invalid line table for source: " + source.id());
                }
                previous = start;
            }
            if (source.content() != null && !source.lineStarts().equals(lineStarts(source.content()))) {
                throw new ValidationException("Source line table does not match content: " + source.id());
            }
        }
        if (!artifact.sourceManifestSha256().equals(sourceManifestSha256(sources))) {
            throw new ValidationException("Debug metadata source manifest mismatch");
        }
        var scopesById = index(scopes, Scope::id);
        for (Scope scope : scopes) {
            requireReference("scope source", scope.sourceId(), sourceIds);
            if (scope.parentId() != null) {
                requireReference("parent scope", scope.parentId(), scopeIds);
                Scope parent = scopesById.get(scope.parentId());
                if (!parent.sourceId().equals(scope.sourceId()) || !contains(parent.range(), scope.range())) {
                    throw new ValidationException("Scope is outside its parent: " + scope.id());
                }
            }
            validateRange(scope.range(), sourceIds, sources);
            if (!scope.sourceId().equals(scope.range().sourceId())) {
                throw new ValidationException("Scope range source mismatch: " + scope.id());
            }
            rejectScopeCycle(scope.id(), scopesById);
        }
        for (Binding binding : bindings) {
            requireReference("binding scope", binding.scopeId(), scopeIds);
            requireReference("binding type", binding.typeId(), typeIds);
            validateRange(binding.declarationRange(), sourceIds, sources);
            boundedText("binding.name", binding.name());
            boundedText("binding.declaredSpelling", binding.declaredSpelling());
            Scope bindingScope = scopesById.get(binding.scopeId());
            if (!contains(bindingScope.range(), binding.declarationRange())) {
                throw new ValidationException("Binding declaration is outside its scope: " + binding.id());
            }
        }
        for (Layout layout : layouts) {
            if (layout.kind() == null) throw new ValidationException("Missing layout kind: " + layout.id());
            for (String child : layout.childLayoutIds()) requireReference("child layout", child, layoutIds);
        }
        var layoutsById = index(layouts, Layout::id);
        for (Layout layout : layouts) rejectLayoutCycle(layout.id(), layoutsById, new HashSet<>(), new HashSet<>());
        var loweredOccurrences = new HashSet<Integer>();
        for (LoweredBinding lowered : loweredBindings) {
            if (lowered.sourceBindingId() != null) {
                requireReference("source binding", lowered.sourceBindingId(), bindingIds);
            }
            requireReference("lowered layout", lowered.layoutId(), layoutIds);
            boundedText("lowered.generatedRole", lowered.generatedRole());
            if (lowered.lambdaOccurrenceId() < 0
                    || lowered.lambdaOccurrenceId() >= artifact.occurrenceCount()) {
                throw new ValidationException("Lowered binding lambda occurrence is out of range: " + lowered.id());
            }
            if (!loweredOccurrences.add(lowered.lambdaOccurrenceId())) {
                throw new ValidationException("Duplicate lowered binding lambda occurrence: "
                        + lowered.lambdaOccurrenceId());
            }
        }
        var loweredById = index(loweredBindings, LoweredBinding::id);
        var bindingsById = index(bindings, Binding::id);
        var occurrenceIds = new HashSet<Integer>();
        for (SuspensionPoint point : suspensionPoints) {
            if (!occurrenceIds.add(point.occurrenceId())) {
                throw new ValidationException("Duplicate suspension occurrence: " + point.occurrenceId());
            }
            if (point.occurrenceId() < 0 || point.occurrenceId() >= artifact.occurrenceCount()) {
                throw new ValidationException("Suspension occurrence out of range: " + point.occurrenceId());
            }
            if (point.termKind() == null || point.context() == null) {
                throw new ValidationException("Incomplete suspension point: " + point.occurrenceId());
            }
            if (!Set.of("var", "delay", "lam", "apply", "const", "force", "error", "builtin", "constr", "case")
                    .contains(point.termKind())) {
                throw new ValidationException("Unknown suspension term kind: " + point.termKind());
            }
            if (point.context() == SourceContext.EXACT && (point.sourceId() == null || point.range() == null)) {
                throw new ValidationException("Exact suspension point has no source range: " + point.occurrenceId());
            }
            if (point.sourceId() == null ^ point.range() == null) {
                throw new ValidationException("Incomplete suspension source: " + point.occurrenceId());
            }
            if (point.range() != null) {
                validateRange(point.range(), sourceIds, sources);
                if (!point.sourceId().equals(point.range().sourceId())) {
                    throw new ValidationException("Suspension source mismatch: " + point.occurrenceId());
                }
            }
            if (point.scopeChain().size() != new HashSet<>(point.scopeChain()).size()) {
                throw new ValidationException("Duplicate suspension scope: " + point.occurrenceId());
            }
            for (String scopeId : point.scopeChain()) requireReference("suspension scope", scopeId, scopeIds);
            if (point.expectedBinderIds().size() != new HashSet<>(point.expectedBinderIds()).size()) {
                throw new ValidationException("Duplicate expected lowered binding at occurrence "
                        + point.occurrenceId());
            }
            for (String loweredId : point.expectedBinderIds()) {
                requireReference("expected lowered binding", loweredId, loweredIds);
            }
            var visible = new HashSet<String>();
            for (VisibleBinding value : point.visibleBindings()) {
                requireReference("visible binding", value.bindingId(), bindingIds);
                if (!visible.add(value.bindingId())) {
                    throw new ValidationException("Duplicate visible binding at occurrence " + point.occurrenceId());
                }
                if (value.slot() != null) {
                    if (value.availability() != Availability.AVAILABLE) {
                        throw new ValidationException("Unavailable binding has an environment slot at occurrence "
                                + point.occurrenceId());
                    }
                    if (value.slot().index() < 1 || value.slot().index() > point.expectedBinderIds().size()) {
                        throw new ValidationException("Environment slot out of range at occurrence " + point.occurrenceId());
                    }
                    requireReference("slot lowered binding", value.slot().loweredBindingId(), loweredIds);
                    requireReference("slot layout", value.slot().layoutId(), layoutIds);
                    String expected = point.expectedBinderIds().get(value.slot().index() - 1);
                    if (!expected.equals(value.slot().loweredBindingId())) {
                        throw new ValidationException("Environment slot does not match lexical vector at occurrence "
                                + point.occurrenceId());
                    }
                    LoweredBinding lowered = loweredById.get(value.slot().loweredBindingId());
                    if (!value.slot().layoutId().equals(lowered.layoutId())
                            || !value.bindingId().equals(lowered.sourceBindingId())) {
                        throw new ValidationException("Environment slot provenance mismatch at occurrence "
                                + point.occurrenceId());
                    }
                } else if (value.availability() == Availability.AVAILABLE) {
                    throw new ValidationException("Available binding has no environment slot at occurrence "
                            + point.occurrenceId());
                } else if (value.reason() == null || value.reason().isBlank()) {
                    throw new ValidationException("Unavailable binding has no reason at occurrence "
                            + point.occurrenceId());
                }
                Binding binding = bindingsById.get(value.bindingId());
                if (!point.scopeChain().contains(binding.scopeId())) {
                    throw new ValidationException("Visible binding scope is absent at occurrence "
                            + point.occurrenceId());
                }
            }
        }
    }

    /** Validate this sidecar against the exact canonical program it names. */
    public void validateArtifact(Program program) {
        validate();
        String digest = flatSha256(program);
        if (!artifact.flatSha256().equals(digest)) {
            throw new ValidationException("Debug metadata artifact digest mismatch");
        }
        if (!artifact.uplcVersion().equals(program.versionString())) {
            throw new ValidationException("Debug metadata UPLC version mismatch");
        }
        var occurrences = occurrences(program.term());
        if (occurrences.size() != artifact.occurrenceCount()) {
            throw new ValidationException("Debug metadata occurrence count mismatch");
        }
        for (SuspensionPoint point : suspensionPoints) {
            if (!termKind(occurrences.get(point.occurrenceId())).equals(point.termKind())) {
                throw new ValidationException("Debug metadata occurrence kind mismatch at " + point.occurrenceId());
            }
        }
        validateParameterApplications(program);
        validateLexicalEvidence(program);
    }

    /**
     * Independently prove that every lowered binder is anchored to one actual lambda occurrence
     * and that each suspension vector is the exact innermost-first lexical lambda ancestry of the
     * decoded program. Lambda display names are deliberately ignored.
     */
    private void validateLexicalEvidence(Program program) {
        var binderAtOccurrence = new HashMap<Integer, String>();
        for (LoweredBinding lowered : loweredBindings) {
            String previous = binderAtOccurrence.put(lowered.lambdaOccurrenceId(), lowered.id());
            if (previous != null) {
                throw new ValidationException("Duplicate lambda occurrence anchor: "
                        + lowered.lambdaOccurrenceId());
            }
        }
        var pointsAtOccurrence = new HashMap<Integer, SuspensionPoint>();
        suspensionPoints.forEach(point -> pointsAtOccurrence.put(point.occurrenceId(), point));

        var pending = new ArrayDeque<VerificationFrame>();
        var lexical = new ArrayDeque<String>();
        pending.push(new VerificationFrame(program.term(), false));
        int occurrenceId = 0;
        int lambdaCount = 0;
        while (!pending.isEmpty()) {
            VerificationFrame frame = pending.pop();
            if (frame.leaveLambda()) {
                if (lexical.isEmpty()) throw new ValidationException("Invalid lexical verifier state");
                lexical.removeFirst();
                continue;
            }
            if (occurrenceId >= MAX_OCCURRENCES) {
                throw new ValidationException("program occurrences exceed limit " + MAX_OCCURRENCES);
            }
            Term term = frame.term();
            int currentOccurrence = occurrenceId++;
            SuspensionPoint point = pointsAtOccurrence.get(currentOccurrence);
            if (point != null) verifyLexicalPoint(point, lexical);

            if (term instanceof Term.Var variable) {
                int index = variable.name().index();
                if (index < 1 || index > lexical.size()) {
                    throw new ValidationException("Invalid de Bruijn index " + index
                            + " at occurrence " + currentOccurrence);
                }
            }
            switch (term) {
                case Term.Lam lambda -> {
                    String loweredId = binderAtOccurrence.get(currentOccurrence);
                    if (loweredId == null) {
                        throw new ValidationException("Missing lowered binder anchor for lambda occurrence "
                                + currentOccurrence);
                    }
                    lambdaCount++;
                    lexical.addFirst(loweredId);
                    pending.push(new VerificationFrame(null, true));
                    pending.push(new VerificationFrame(lambda.body(), false));
                }
                case Term.Apply apply -> {
                    pending.push(new VerificationFrame(apply.argument(), false));
                    pending.push(new VerificationFrame(apply.function(), false));
                }
                case Term.Force force -> pending.push(new VerificationFrame(force.term(), false));
                case Term.Delay delay -> pending.push(new VerificationFrame(delay.term(), false));
                case Term.Constr constr -> {
                    for (int i = constr.fields().size() - 1; i >= 0; i--) {
                        pending.push(new VerificationFrame(constr.fields().get(i), false));
                    }
                }
                case Term.Case caseTerm -> {
                    for (int i = caseTerm.branches().size() - 1; i >= 0; i--) {
                        pending.push(new VerificationFrame(caseTerm.branches().get(i), false));
                    }
                    pending.push(new VerificationFrame(caseTerm.scrutinee(), false));
                }
                default -> { }
            }
            if ((long) occurrenceId + pending.size() > MAX_OCCURRENCES) {
                throw new ValidationException("program occurrences exceed limit " + MAX_OCCURRENCES);
            }
        }
        if (occurrenceId != artifact.occurrenceCount()) {
            throw new ValidationException("Lexical verification occurrence count mismatch");
        }
        if (lambdaCount != loweredBindings.size()) {
            throw new ValidationException("Lowered binder anchors do not form a bijection with program lambdas");
        }
    }

    private static void verifyLexicalPoint(SuspensionPoint point, ArrayDeque<String> lexical) {
        if (point.expectedBinderIds().size() != lexical.size()) {
            throw new ValidationException("Lexical binder depth mismatch at occurrence " + point.occurrenceId());
        }
        int index = 0;
        for (String actual : lexical) {
            if (!actual.equals(point.expectedBinderIds().get(index++))) {
                throw new ValidationException("Lexical binder vector mismatch at occurrence "
                        + point.occurrenceId());
            }
        }
        for (VisibleBinding visible : point.visibleBindings()) {
            if (visible.slot() == null) continue;
            String actual = lexicalAt(lexical, visible.slot().index());
            if (!visible.slot().loweredBindingId().equals(actual)) {
                throw new ValidationException("Environment slot does not match decoded lexical ancestry at occurrence "
                        + point.occurrenceId());
            }
        }
    }

    private static String lexicalAt(ArrayDeque<String> lexical, int oneBasedIndex) {
        int index = 1;
        for (String binder : lexical) {
            if (index++ == oneBasedIndex) return binder;
        }
        throw new ValidationException("Environment slot exceeds decoded lexical ancestry");
    }

    private void validateParameterApplications(Program program) {
        Term stripped = program.term();
        for (int i = artifact.parameterDigests().size() - 1; i >= 0; i--) {
            if (!(stripped instanceof Term.Apply apply)
                    || !(apply.argument() instanceof Term.Const constant)
                    || !(constant.value() instanceof Constant.DataConst data)) {
                throw new ValidationException("Applied parameter wrapper " + i
                        + " is not Apply(_, Const(Data))");
            }
            String actualDigest = sha256(PlutusDataCborEncoder.encode(data.value()));
            if (!actualDigest.equals(artifact.parameterDigests().get(i))) {
                throw new ValidationException("Applied parameter digest mismatch at index " + i);
            }
            stripped = apply.function();
        }
        Program unparameterized = new Program(program.major(), program.minor(), program.patch(), stripped);
        if (!flatSha256(unparameterized).equals(artifact.unparameterizedFlatSha256())) {
            throw new ValidationException("Unparameterized artifact digest mismatch after parameter stripping");
        }
    }

    private record VerificationFrame(Term term, boolean leaveLambda) {}

    /** A deterministic digest used to bind the complete sidecar to a session. */
    public String canonicalDigest() {
        var text = new StringBuilder(format).append('|').append(major).append('|').append(minor)
                .append('|').append(requiredCapabilities).append('|').append(artifact);
        sources.forEach(value -> text.append('|').append(value));
        scopes.forEach(value -> text.append('|').append(value));
        bindings.forEach(value -> text.append('|').append(value));
        types.forEach(value -> text.append('|').append(value));
        layouts.forEach(value -> text.append('|').append(value));
        loweredBindings.forEach(value -> text.append('|').append(value));
        suspensionPoints.forEach(value -> text.append('|').append(value));
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public DebugMetadata withAppliedArtifact(Program program, int addedOuterApplications,
                                             List<String> parameterDigests) {
        if (addedOuterApplications < 0) throw new IllegalArgumentException("Negative parameter count");
        if (addedOuterApplications != parameterDigests.size()) {
            throw new ValidationException("Parameter application count does not match its digests");
        }
        if (!artifact.parameterDigests().isEmpty()) {
            throw new ValidationException("Debug metadata is already bound to applied parameters");
        }
        var shifted = suspensionPoints.stream().map(point -> new SuspensionPoint(
                Math.addExact(point.occurrenceId(), addedOuterApplications), point.termKind(), point.context(),
                point.sourceId(), point.range(), point.scopeChain(), point.expectedBinderIds(),
                point.visibleBindings(), point.unavailableReason())).toList();
        var shiftedLowered = loweredBindings.stream().map(lowered -> new LoweredBinding(
                lowered.id(), lowered.sourceBindingId(), lowered.generatedRole(), lowered.layoutId(),
                Math.addExact(lowered.lambdaOccurrenceId(), addedOuterApplications))).toList();
        var appliedArtifact = new Artifact(
                artifact.compilerIdentity(), artifact.targetId(), artifact.options(), artifact.optimizationProvenance(),
                flatSha256(program), artifact.unparameterizedFlatSha256(), program.versionString(), TRAVERSAL,
                occurrences(program.term()).size(), artifact.sourceManifestSha256(), List.copyOf(parameterDigests));
        var result = new DebugMetadata(format, major, minor, requiredCapabilities, appliedArtifact,
                sources, scopes, bindings, types, layouts, shiftedLowered, shifted);
        result.validateArtifact(program);
        return result;
    }

    public static String flatSha256(Program program) {
        return sha256(UplcFlatEncoder.encodeProgram(program));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Canonical digest that binds source identities, paths and contents to an artifact. */
    public static String sourceManifestSha256(List<Source> sources) {
        var text = new StringBuilder();
        sources.forEach(source -> text.append(source.id()).append('\0').append(source.uri()).append('\0')
                .append(source.contentSha256()).append('\n'));
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<Term> occurrences(Term root) {
        var result = new ArrayList<Term>();
        var pending = new ArrayDeque<Term>();
        pending.push(root);
        while (!pending.isEmpty()) {
            if (result.size() >= MAX_OCCURRENCES) {
                throw new ValidationException("program occurrences exceed limit " + MAX_OCCURRENCES);
            }
            Term term = pending.pop();
            result.add(term);
            pushChildren(term, pending, result.size());
        }
        return List.copyOf(result);
    }

    public static String termKind(Term term) {
        return switch (term) {
            case Term.Var ignored -> "var";
            case Term.Delay ignored -> "delay";
            case Term.Lam ignored -> "lam";
            case Term.Apply ignored -> "apply";
            case Term.Const ignored -> "const";
            case Term.Force ignored -> "force";
            case Term.Error ignored -> "error";
            case Term.Builtin ignored -> "builtin";
            case Term.Constr ignored -> "constr";
            case Term.Case ignored -> "case";
        };
    }

    public record Artifact(String compilerIdentity, String targetId, String options,
                           String optimizationProvenance, String flatSha256,
                           String unparameterizedFlatSha256, String uplcVersion, String traversal,
                           int occurrenceCount, String sourceManifestSha256,
                           List<String> parameterDigests) {
        public Artifact {
            Objects.requireNonNull(compilerIdentity);
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(options);
            Objects.requireNonNull(optimizationProvenance);
            Objects.requireNonNull(flatSha256);
            Objects.requireNonNull(unparameterizedFlatSha256);
            Objects.requireNonNull(uplcVersion);
            Objects.requireNonNull(traversal);
            Objects.requireNonNull(sourceManifestSha256);
            parameterDigests = List.copyOf(parameterDigests);
        }
    }

    public record Source(String id, String uri, String contentSha256, String content, List<Integer> lineStarts) {
        public Source {
            Objects.requireNonNull(id);
            Objects.requireNonNull(uri);
            Objects.requireNonNull(contentSha256);
            lineStarts = List.copyOf(lineStarts);
        }
    }

    public record SourceRange(String sourceId, int startUtf16, int endUtf16,
                              int startLine, int startColumn, int endLine, int endColumn) {
        public SourceRange { Objects.requireNonNull(sourceId); }
    }

    public enum ScopeKind { CLASS, METHOD, LAMBDA, BLOCK, LOOP, PATTERN }

    public record Scope(String id, String parentId, String sourceId, SourceRange range,
                        ScopeKind kind, String owningMethodId) {
        public Scope {
            Objects.requireNonNull(id);
            Objects.requireNonNull(sourceId);
            Objects.requireNonNull(range);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(owningMethodId);
        }
    }

    public enum BindingKind { PARAMETER, LOCAL, PATTERN, CAPTURED_ORIGIN, PARAM_FIELD }

    public record Binding(String id, String name, SourceRange declarationRange, String scopeId,
                          BindingKind kind, String declaredSpelling, String typeId) {
        public Binding {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(declarationRange);
            Objects.requireNonNull(scopeId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(declaredSpelling);
            Objects.requireNonNull(typeId);
        }
    }

    public record TypeInfo(String id, String displayName) {
        public TypeInfo { Objects.requireNonNull(id); Objects.requireNonNull(displayName); }
    }

    public enum LayoutKind { INTEGER, BYTE_STRING, STRING, BOOLEAN, UNIT, RAW_DATA, OPAQUE }

    public record Layout(String id, LayoutKind kind, List<String> childLayoutIds) {
        public Layout {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            childLayoutIds = List.copyOf(childLayoutIds);
        }
    }

    public record LoweredBinding(String id, String sourceBindingId, String generatedRole, String layoutId,
                                 int lambdaOccurrenceId) {
        public LoweredBinding {
            Objects.requireNonNull(id);
            Objects.requireNonNull(generatedRole);
            Objects.requireNonNull(layoutId);
        }
    }

    public enum SourceContext { EXACT, AMBIGUOUS, GENERATED }

    public enum Availability {
        AVAILABLE, NOT_YET_BOUND, NOT_MATERIALIZED, UNSUPPORTED_LOWERING,
        UNSUPPORTED_REPRESENTATION, AMBIGUOUS, REPRESENTATION_MISMATCH
    }

    public record EnvironmentSlot(int index, String loweredBindingId, String layoutId) {
        public EnvironmentSlot {
            Objects.requireNonNull(loweredBindingId);
            Objects.requireNonNull(layoutId);
        }
    }

    public record VisibleBinding(String bindingId, Availability availability,
                                 EnvironmentSlot slot, String reason, boolean shadowed) {
        public VisibleBinding {
            Objects.requireNonNull(bindingId);
            Objects.requireNonNull(availability);
        }
    }

    public record SuspensionPoint(int occurrenceId, String termKind, SourceContext context,
                                  String sourceId, SourceRange range, List<String> scopeChain,
                                  List<String> expectedBinderIds, List<VisibleBinding> visibleBindings,
                                  String unavailableReason) {
        public SuspensionPoint {
            Objects.requireNonNull(termKind);
            Objects.requireNonNull(context);
            scopeChain = List.copyOf(scopeChain);
            expectedBinderIds = List.copyOf(expectedBinderIds);
            visibleBindings = List.copyOf(visibleBindings);
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        public ValidationException(String message) { super(message); }
    }

    private static void pushChildren(Term term, ArrayDeque<Term> pending, int visited) {
        int childCount = switch (term) {
            case Term.Lam ignored -> 1;
            case Term.Force ignored -> 1;
            case Term.Delay ignored -> 1;
            case Term.Apply ignored -> 2;
            case Term.Constr value -> value.fields().size();
            case Term.Case value -> Math.addExact(1, value.branches().size());
            default -> 0;
        };
        if ((long) visited + pending.size() + childCount > MAX_OCCURRENCES) {
            throw new ValidationException("program occurrences exceed limit " + MAX_OCCURRENCES);
        }
        switch (term) {
            case Term.Lam value -> pending.push(value.body());
            case Term.Apply value -> {
                pending.push(value.argument());
                pending.push(value.function());
            }
            case Term.Force value -> pending.push(value.term());
            case Term.Delay value -> pending.push(value.term());
            case Term.Constr value -> {
                for (int i = value.fields().size() - 1; i >= 0; i--) pending.push(value.fields().get(i));
            }
            case Term.Case value -> {
                for (int i = value.branches().size() - 1; i >= 0; i--) pending.push(value.branches().get(i));
                pending.push(value.scrutinee());
            }
            default -> { }
        }
    }

    private static void bounded(String table, int size, int maximum) {
        if (size > maximum) throw new ValidationException(table + " exceeds limit " + maximum);
    }

    private static void boundedText(String label, String value) {
        if (value == null || value.length() > MAX_TEXT_CHARS) {
            throw new ValidationException(label + " exceeds text limit " + MAX_TEXT_CHARS);
        }
    }

    private static Set<String> uniqueIds(String table, List<String> ids) {
        var result = new HashSet<String>();
        for (String id : ids) {
            if (id == null || id.isBlank()) throw new ValidationException("Blank " + table + " id");
            if (id.length() > MAX_TEXT_CHARS) {
                throw new ValidationException(table + " id exceeds text limit " + MAX_TEXT_CHARS);
            }
            if (!result.add(id)) throw new ValidationException("Duplicate " + table + " id: " + id);
        }
        return result;
    }

    private static List<Integer> lineStarts(String content) {
        var result = new ArrayList<Integer>();
        result.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') result.add(i + 1);
        }
        return List.copyOf(result);
    }

    private static <T> Map<String, T> index(List<T> values, java.util.function.Function<T, String> id) {
        var result = new LinkedHashMap<String, T>();
        for (T value : values) result.put(id.apply(value), value);
        return result;
    }

    private static void requireReference(String label, String id, Set<String> ids) {
        if (id == null || !ids.contains(id)) throw new ValidationException("Dangling " + label + ": " + id);
    }

    private static void requireSha256(String label, String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new ValidationException("Invalid " + label);
        }
    }

    private static void validateRange(SourceRange range, Set<String> sourceIds, List<Source> sources) {
        requireReference("range source", range.sourceId(), sourceIds);
        if (range.startUtf16() < 0 || range.endUtf16() < range.startUtf16()
                || range.startLine() < 1 || range.startColumn() < 1
                || range.endLine() < range.startLine() || range.endColumn() < 1
                || range.endLine() == range.startLine() && range.endColumn() < range.startColumn()) {
            throw new ValidationException("Invalid source range in " + range.sourceId());
        }
        Source source = sources.stream().filter(value -> value.id().equals(range.sourceId())).findFirst().orElseThrow();
        if (source.content() != null && range.endUtf16() > source.content().length()) {
            throw new ValidationException("Source range exceeds content in " + range.sourceId());
        }
    }

    private static boolean contains(SourceRange outer, SourceRange inner) {
        return outer.sourceId().equals(inner.sourceId())
                && inner.startUtf16() >= outer.startUtf16()
                && inner.endUtf16() <= outer.endUtf16();
    }

    private static void rejectScopeCycle(String id, Map<String, Scope> scopes) {
        var seen = new HashSet<String>();
        String current = id;
        while (current != null) {
            if (!seen.add(current)) throw new ValidationException("Scope cycle at " + id);
            Scope scope = scopes.get(current);
            current = scope == null ? null : scope.parentId();
        }
    }

    private static void rejectLayoutCycle(String id, Map<String, Layout> layouts,
                                          Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new ValidationException("Layout cycle at " + id);
        for (String child : layouts.get(id).childLayoutIds()) rejectLayoutCycle(child, layouts, visiting, visited);
        visiting.remove(id);
        visited.add(id);
    }
}
