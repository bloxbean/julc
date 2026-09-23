package org.julclang.compiler.debug;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.debug.DebugMetadata;
import org.julclang.core.source.SourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.julclang.core.debug.DebugMetadata.Availability.AVAILABLE;

/** Compiler-owned declaration identities and final UPLC environment-slot proof. */
public final class DebugMetadataCollector {
    public record SourceInput(String id, String uri, String content, CompilationUnit compilationUnit) {}

    private static final List<DebugMetadata.Layout> LAYOUTS = List.of(
            new DebugMetadata.Layout("layout:integer", DebugMetadata.LayoutKind.INTEGER, List.of()),
            new DebugMetadata.Layout("layout:bytes", DebugMetadata.LayoutKind.BYTE_STRING, List.of()),
            new DebugMetadata.Layout("layout:string", DebugMetadata.LayoutKind.STRING, List.of()),
            new DebugMetadata.Layout("layout:boolean", DebugMetadata.LayoutKind.BOOLEAN, List.of()),
            new DebugMetadata.Layout("layout:unit", DebugMetadata.LayoutKind.UNIT, List.of()),
            new DebugMetadata.Layout("layout:data", DebugMetadata.LayoutKind.RAW_DATA, List.of()),
            new DebugMetadata.Layout("layout:opaque", DebugMetadata.LayoutKind.OPAQUE, List.of()));

    private static final class BindingDraft {
        final String id;
        final String name;
        final DebugMetadata.SourceRange range;
        final String scopeId;
        final DebugMetadata.BindingKind kind;
        final String declaredSpelling;
        final boolean materializable;
        String typeId;
        String unavailableReason;

        BindingDraft(String id, String name, DebugMetadata.SourceRange range, String scopeId,
                     DebugMetadata.BindingKind kind, String declaredSpelling, boolean materializable) {
            this.id = id;
            this.name = name;
            this.range = range;
            this.scopeId = scopeId;
            this.kind = kind;
            this.declaredSpelling = declaredSpelling;
            this.materializable = materializable;
            this.unavailableReason = materializable ? null
                    : "reassignment/loop lowering is not identity-proven";
            this.typeId = typeId(declaredSpelling);
        }

        DebugMetadata.Binding build() {
            return new DebugMetadata.Binding(id, name, range, scopeId, kind, declaredSpelling, typeId);
        }
    }

    private final List<DebugMetadata.Source> sources = new ArrayList<>();
    private final List<DebugMetadata.Scope> scopes = new ArrayList<>();
    private final IdentityHashMap<Node, BindingDraft> bindingsByNode = new IdentityHashMap<>();
    private final LinkedHashMap<String, BindingDraft> bindings = new LinkedHashMap<>();
    private final LinkedHashMap<String, DebugMetadata.TypeInfo> types = new LinkedHashMap<>();
    private final IdentityHashMap<Node, String> scopeIds = new IdentityHashMap<>();
    private final IdentityHashMap<CompilationUnit, SourceInput> sourceInputs = new IdentityHashMap<>();
    private final PirDebugProvenance provenance = new PirDebugProvenance();

    public DebugMetadataCollector(List<SourceInput> inputs) {
        int sourceOrdinal = 0;
        for (SourceInput input : inputs) {
            Objects.requireNonNull(input.content());
            sourceInputs.put(input.compilationUnit(), input);
            sources.add(new DebugMetadata.Source(input.id(), input.uri(),
                    DebugMetadata.sha256(input.content().getBytes(StandardCharsets.UTF_8)), input.content(),
                    lineStarts(input.content())));
            scan(input, sourceOrdinal++);
        }
    }

    public PirDebugProvenance provenance() {
        return provenance;
    }

    /** Record a deliberately unsupported declaration lowering without inventing a binder. */
    public void markUnsupported(Node declaration, String reason) {
        BindingDraft draft = bindingsByNode.get(declaration);
        if (draft != null && draft.unavailableReason == null) draft.unavailableReason = reason;
    }

    /** Associate a Java declaration with the exact PIR binder created for it. */
    public void associate(Node declaration, PirTerm binder, PirType physicalType) {
        BindingDraft draft = bindingsByNode.get(declaration);
        if (draft == null || !draft.materializable) return;
        String display = displayType(physicalType);
        draft.typeId = typeId(display);
        types.putIfAbsent(draft.typeId, new DebugMetadata.TypeInfo(draft.typeId, display));
        provenance.associate(binder, new PirDebugProvenance.Association(
                draft.id, draft.name, draft.scopeId, draft.typeId, layoutId(physicalType)));
    }

    public DebugMetadata finish(CompileResult result, CompilerOptions options,
                                Map<Term.Lam, PirDebugProvenance.Association> emittedBinders,
                                Map<Term, SourceLocation> exactPositions) {
        Program program = result.program();
        String flatDigest = DebugMetadata.flatSha256(program);
        var artifact = new DebugMetadata.Artifact(
                "julc-" + result.optimizationReport().compilerVersion(),
                result.target().profileId(),
                optionsString(options),
                result.optimizationReport().toString(),
                flatDigest, flatDigest, program.versionString(), DebugMetadata.TRAVERSAL,
                DebugMetadata.occurrences(program.term()).size(), sourceManifestDigest(), List.of());

        var lowered = new ArrayList<DebugMetadata.LoweredBinding>();
        var points = new ArrayList<DebugMetadata.SuspensionPoint>();
        provenance.unavailableReasons().forEach((bindingId, reason) -> {
            BindingDraft draft = bindings.get(bindingId);
            if (draft != null && draft.unavailableReason == null) draft.unavailableReason = reason;
        });
        var emittedSourceBindings = emittedBinders.values().stream()
                .map(PirDebugProvenance.Association::bindingId).collect(Collectors.toSet());
        bindings.values().stream()
                .filter(binding -> binding.unavailableReason == null && !emittedSourceBindings.contains(binding.id))
                .forEach(binding -> binding.unavailableReason =
                        "declaration was not materialized by a proven final binder lowering");
        var occurrence = new int[]{0};
        walk(program.term(), List.of(), emittedBinders, exactPositions, lowered, points, occurrence);

        var metadata = new DebugMetadata(DebugMetadata.FORMAT, DebugMetadata.MAJOR, DebugMetadata.MINOR,
                List.of("anchored-lambda-occurrences-v1", "exact-environment-slots-v1",
                        "scalar-values-v1", "raw-data-v1"), artifact,
                sources, scopes, bindings.values().stream().map(BindingDraft::build).toList(),
                List.copyOf(types.values()), LAYOUTS, lowered, points);
        metadata.validateArtifact(program);
        return metadata;
    }

    private record LexicalBinder(String id, PirDebugProvenance.Association association) {}

    private void walk(Term term, List<LexicalBinder> lexical,
                      Map<Term.Lam, PirDebugProvenance.Association> emittedBinders,
                      Map<Term, SourceLocation> exactPositions,
                      List<DebugMetadata.LoweredBinding> lowered,
                      List<DebugMetadata.SuspensionPoint> points, int[] occurrence) {
        int id = occurrence[0]++;
        SourceLocation position = exactPositions.get(term);
        var context = position == null ? DebugMetadata.SourceContext.GENERATED : DebugMetadata.SourceContext.EXACT;
        DebugMetadata.SourceRange range = position == null ? null : range(position);
        String sourceId = range == null ? null : range.sourceId();

        var expected = lexical.stream().map(LexicalBinder::id).toList();
        var visible = new ArrayList<DebugMetadata.VisibleBinding>();
        var seenNames = new LinkedHashSet<String>();
        var visibleIds = new LinkedHashSet<String>();
        var scopeChain = new ArrayList<String>();
        for (int index = 0; index < lexical.size(); index++) {
            var association = lexical.get(index).association();
            if (association == null) continue;
            boolean shadowed = !seenNames.add(association.sourceName());
            if (!scopeChain.contains(association.scopeId())) scopeChain.add(association.scopeId());
            visible.add(new DebugMetadata.VisibleBinding(association.bindingId(), AVAILABLE,
                    new DebugMetadata.EnvironmentSlot(index + 1, lexical.get(index).id(), association.layoutId()),
                    null, shadowed));
            visibleIds.add(association.bindingId());
        }
        if (range != null) {
            bindings.values().stream()
                    .filter(binding -> binding.unavailableReason != null && !visibleIds.contains(binding.id))
                    .filter(binding -> isLexicallyVisible(binding, range))
                    .sorted(Comparator
                            .comparingInt((BindingDraft binding) -> scopeSpan(binding.scopeId))
                            .thenComparing((BindingDraft binding) -> -binding.range.startUtf16()))
                    .forEach(binding -> {
                        boolean shadowed = !seenNames.add(binding.name);
                        if (!scopeChain.contains(binding.scopeId)) scopeChain.add(binding.scopeId);
                        visible.add(new DebugMetadata.VisibleBinding(binding.id,
                                DebugMetadata.Availability.UNSUPPORTED_LOWERING, null,
                                binding.unavailableReason, shadowed));
                    });
        }
        points.add(new DebugMetadata.SuspensionPoint(id, DebugMetadata.termKind(term), context, sourceId, range,
                scopeChain, expected, visible, position == null ? "generated or unmapped term" : null));

        if (term instanceof Term.Lam lambda) {
            var association = emittedBinders.get(lambda);
            String loweredId = association == null ? "lowered:generated:" + id
                    : "lowered:" + association.bindingId() + ":" + id;
            lowered.add(new DebugMetadata.LoweredBinding(loweredId,
                    association == null ? null : association.bindingId(),
                    association == null ? "generated" : "source-binder",
                    association == null ? "layout:opaque" : association.layoutId(), id));
            var inner = new ArrayList<LexicalBinder>(lexical.size() + 1);
            inner.add(new LexicalBinder(loweredId, association));
            inner.addAll(lexical);
            walk(lambda.body(), List.copyOf(inner), emittedBinders, exactPositions, lowered, points, occurrence);
            return;
        }
        switch (term) {
            case Term.Apply value -> {
                walk(value.function(), lexical, emittedBinders, exactPositions, lowered, points, occurrence);
                walk(value.argument(), lexical, emittedBinders, exactPositions, lowered, points, occurrence);
            }
            case Term.Force value -> walk(value.term(), lexical, emittedBinders, exactPositions, lowered, points, occurrence);
            case Term.Delay value -> walk(value.term(), lexical, emittedBinders, exactPositions, lowered, points, occurrence);
            case Term.Constr value -> value.fields().forEach(child ->
                    walk(child, lexical, emittedBinders, exactPositions, lowered, points, occurrence));
            case Term.Case value -> {
                walk(value.scrutinee(), lexical, emittedBinders, exactPositions, lowered, points, occurrence);
                value.branches().forEach(child ->
                        walk(child, lexical, emittedBinders, exactPositions, lowered, points, occurrence));
            }
            default -> { }
        }
    }

    private void scan(SourceInput input, int sourceOrdinal) {
        int parameterOrdinal = 0;
        var parameterFields = input.compilationUnit().findAll(FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotationByName("Param").isPresent())
                .flatMap(field -> field.getVariables().stream())
                .sorted(Comparator.comparingInt(node -> offset(input, node.getRange().orElse(null), true)))
                .toList();
        for (VariableDeclarator declaration : parameterFields) {
            addBinding(input, declaration,
                    input.id() + ":param:" + parameterOrdinal++ + ":" + declaration.getNameAsString(),
                    declaration.getNameAsString(), classScope(input, declaration),
                    DebugMetadata.BindingKind.PARAM_FIELD, declaration.getTypeAsString(), true);
        }

        var methods = input.compilationUnit().findAll(MethodDeclaration.class).stream()
                .sorted(Comparator.comparingInt(node -> offset(input, node.getRange().orElse(null), true)))
                .toList();
        for (int methodOrdinal = 0; methodOrdinal < methods.size(); methodOrdinal++) {
            MethodDeclaration method = methods.get(methodOrdinal);
            String methodId = input.id() + ":method:" + methodOrdinal + ":" + method.getNameAsString();
            String methodScope = scope(input, method, methodId);
            // JuLC lowers reassignment and loop state through generated accumulator binders. Until
            // that transformation has an identity-preserving proof, none of the declarations in
            // such a method may be presented as its current Java value. This deliberately broad
            // exclusion avoids resolving assignment targets by name.
            boolean hasMutation = !method.findAll(AssignExpr.class).isEmpty()
                    || method.findAll(UnaryExpr.class).stream().anyMatch(DebugMetadataCollector::isMutation);
            int ordinal = 0;
            for (Parameter parameter : method.getParameters()) {
                addBinding(input, parameter, methodId + ":decl:" + ordinal++, parameter.getNameAsString(),
                        methodScope, DebugMetadata.BindingKind.PARAMETER, parameter.getTypeAsString(), !hasMutation);
            }
            var declarations = method.findAll(VariableDeclarator.class).stream()
                    .sorted(Comparator.comparingInt(node -> offset(input, node.getRange().orElse(null), true)))
                    .toList();
            for (VariableDeclarator declaration : declarations) {
                String ownerScope = scope(input, declaration, methodId);
                addBinding(input, declaration, methodId + ":decl:" + ordinal++, declaration.getNameAsString(),
                        ownerScope, DebugMetadata.BindingKind.LOCAL, declaration.getTypeAsString(), !hasMutation);
            }
            var lambdaParameters = method.findAll(LambdaExpr.class).stream()
                    .sorted(Comparator.comparingInt(node -> offset(input, node.getRange().orElse(null), true)))
                    .flatMap(lambda -> lambda.getParameters().stream()).toList();
            for (Parameter parameter : lambdaParameters) {
                String ownerScope = scope(input, parameter, methodId);
                addBinding(input, parameter, methodId + ":decl:" + ordinal++, parameter.getNameAsString(),
                        ownerScope, DebugMetadata.BindingKind.PARAMETER, parameter.getTypeAsString(), !hasMutation);
            }
        }
        if (sourceOrdinal == 0 && scopes.isEmpty()) {
            throw new CompilerException("Java debug metadata found no method scopes");
        }
    }

    private String classScope(SourceInput input, Node node) {
        ClassOrInterfaceDeclaration owner = node.findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new CompilerException("Debug @Param declaration has no class scope"));
        String existing = scopeIds.get(owner);
        if (existing != null) return existing;
        int start = offset(input, owner.getRange().orElse(null), true);
        String ownerId = "class:" + input.id() + ":" + owner.getNameAsString() + ":" + start;
        String id = "scope:" + ownerId;
        var ownerRange = owner.getRange().map(value -> range(input, value))
                .orElseThrow(() -> new CompilerException("Debug class scope has no source range"));
        scopes.add(new DebugMetadata.Scope(id, null, input.id(), ownerRange,
                DebugMetadata.ScopeKind.CLASS, ownerId));
        scopeIds.put(owner, id);
        return id;
    }

    private void addBinding(SourceInput input, Node node, String id, String name, String scopeId,
                            DebugMetadata.BindingKind kind, String spelling, boolean materializable) {
        if (bindingsByNode.containsKey(node) || node.getRange().isEmpty()) return;
        var draft = new BindingDraft("binding:" + id, name, range(input, node.getRange().orElseThrow()),
                scopeId, kind, spelling, materializable);
        bindingsByNode.put(node, draft);
        bindings.put(draft.id, draft);
        types.putIfAbsent(draft.typeId, new DebugMetadata.TypeInfo(draft.typeId, spelling));
    }

    private String scope(SourceInput input, Node node, String methodId) {
        Node owner = node;
        while (!(owner instanceof MethodDeclaration || owner instanceof LambdaExpr || owner instanceof BlockStmt
                || owner instanceof ForEachStmt || owner instanceof WhileStmt || owner instanceof SwitchEntry)) {
            owner = owner.getParentNode().orElseThrow(() -> new CompilerException("Debug declaration has no scope"));
        }
        String existing = scopeIds.get(owner);
        if (existing != null) return existing;
        Node parentOwner = owner.getParentNode().orElse(null);
        while (parentOwner != null && !(parentOwner instanceof MethodDeclaration || parentOwner instanceof LambdaExpr
                || parentOwner instanceof BlockStmt || parentOwner instanceof ForEachStmt
                || parentOwner instanceof WhileStmt || parentOwner instanceof SwitchEntry)) {
            parentOwner = parentOwner.getParentNode().orElse(null);
        }
        String parentId = parentOwner == null || parentOwner == owner ? null : scope(input, parentOwner, methodId);
        int start = offset(input, owner.getRange().orElse(null), true);
        String id = "scope:" + input.id() + ":" + methodId + ":" + start + ":" + scopeKind(owner).name();
        var range = owner.getRange().map(value -> range(input, value))
                .orElseThrow(() -> new CompilerException("Debug scope has no source range"));
        scopes.add(new DebugMetadata.Scope(id, parentId, input.id(), range, scopeKind(owner), methodId));
        scopeIds.put(owner, id);
        return id;
    }

    private static DebugMetadata.ScopeKind scopeKind(Node node) {
        if (node instanceof MethodDeclaration) return DebugMetadata.ScopeKind.METHOD;
        if (node instanceof LambdaExpr) return DebugMetadata.ScopeKind.LAMBDA;
        if (node instanceof ForEachStmt || node instanceof WhileStmt) return DebugMetadata.ScopeKind.LOOP;
        if (node instanceof SwitchEntry) return DebugMetadata.ScopeKind.PATTERN;
        return DebugMetadata.ScopeKind.BLOCK;
    }

    private DebugMetadata.SourceRange range(SourceLocation location) {
        if (location.fileName() == null) return null;
        List<SourceInput> matches = sourceInputs.values().stream()
                .filter(value -> location.fileName() != null && (value.uri().equals(location.fileName())
                        || value.uri().endsWith("/" + location.fileName())
                        || value.uri().endsWith("\\" + location.fileName())))
                .toList();
        // A missing or non-unique source identity is unmapped. Choosing the first source would
        // manufacture an exact-looking Java location for a different compilation unit.
        if (matches.size() != 1) return null;
        SourceInput input = matches.getFirst();
        int start = offset(input, new Position(location.line(), Math.max(1, location.column())));
        int end = Math.min(input.content().length(), start + 1);
        return new DebugMetadata.SourceRange(input.id(), start, end, location.line(), Math.max(1, location.column()),
                location.line(), Math.max(2, location.column() + 1));
    }

    private static boolean isMutation(UnaryExpr expression) {
        return switch (expression.getOperator()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        };
    }

    private boolean isLexicallyVisible(BindingDraft binding, DebugMetadata.SourceRange point) {
        DebugMetadata.Scope scope = scopes.stream().filter(value -> value.id().equals(binding.scopeId))
                .findFirst().orElse(null);
        if (scope == null || !scope.sourceId().equals(point.sourceId())) return false;
        return point.startUtf16() >= binding.range.endUtf16()
                && point.startUtf16() >= scope.range().startUtf16()
                && point.startUtf16() < scope.range().endUtf16();
    }

    private int scopeSpan(String scopeId) {
        return scopes.stream().filter(value -> value.id().equals(scopeId))
                .map(scope -> scope.range().endUtf16() - scope.range().startUtf16())
                .findFirst().orElse(Integer.MAX_VALUE);
    }

    private static DebugMetadata.SourceRange range(SourceInput input, Range range) {
        int start = offset(input, range.begin);
        int end = Math.min(input.content().length(), offset(input, range.end) + 1);
        return new DebugMetadata.SourceRange(input.id(), start, end, range.begin.line, range.begin.column,
                range.end.line, range.end.column + 1);
    }

    private static int offset(SourceInput input, Range range, boolean begin) {
        return range == null ? Integer.MAX_VALUE : offset(input, begin ? range.begin : range.end);
    }

    private static int offset(SourceInput input, Position position) {
        List<Integer> starts = lineStarts(input.content());
        int line = Math.max(1, Math.min(position.line, starts.size()));
        int start = starts.get(line - 1);
        return Math.min(input.content().length(), start + Math.max(0, position.column - 1));
    }

    private static List<Integer> lineStarts(String content) {
        var result = new ArrayList<Integer>();
        result.add(0);
        for (int i = 0; i < content.length(); i++) if (content.charAt(i) == '\n') result.add(i + 1);
        return List.copyOf(result);
    }

    private static String layoutId(PirType type) {
        return switch (type) {
            case PirType.IntegerType ignored -> "layout:integer";
            case PirType.ByteStringType ignored -> "layout:bytes";
            case PirType.StringType ignored -> "layout:string";
            case PirType.BoolType ignored -> "layout:boolean";
            case PirType.UnitType ignored -> "layout:unit";
            case PirType.DataType _, PirType.RecordType _, PirType.SumType _,
                    PirType.NamedTypeRef _ -> "layout:data";
            default -> "layout:opaque";
        };
    }

    private static String displayType(PirType type) {
        return switch (type) {
            case PirType.IntegerType ignored -> "BigInteger";
            case PirType.ByteStringType ignored -> "byte[]";
            case PirType.StringType ignored -> "String";
            case PirType.BoolType ignored -> "boolean";
            case PirType.UnitType ignored -> "void";
            case PirType.DataType ignored -> "PlutusData";
            case PirType.RecordType value -> value.name();
            case PirType.SumType value -> value.name();
            case PirType.NamedTypeRef value -> value.name();
            default -> type.toString();
        };
    }

    private static String typeId(String display) {
        String digest = DebugMetadata.sha256(display.getBytes(StandardCharsets.UTF_8));
        return "type:" + digest.substring(0, 20);
    }

    private String sourceManifestDigest() {
        var text = new StringBuilder();
        sources.forEach(source -> text.append(source.id()).append('\0').append(source.uri()).append('\0')
                .append(source.contentSha256()).append('\n'));
        return DebugMetadata.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String optionsString(CompilerOptions options) {
        return "target=" + options.getTarget().profileId()
                + ",optimization=" + options.getOptimizationLevel()
                + ",disabledRules=" + options.getDisabledOptimizationRules()
                + ",sourceMap=" + options.isSourceMapEnabled();
    }
}
