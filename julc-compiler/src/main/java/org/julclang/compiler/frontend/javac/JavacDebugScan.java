package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.debug.DebugMetadataCollector;
import org.julclang.core.debug.DebugMetadata;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The scan side of debug metadata on javac's trees (ADR-068 I7): the declarations of a unit, their scopes and their ids, as
 * {@code DebugMetadataCollector}'s scan names them on the JavaParser frontend. A declaration is keyed by its javac
 * {@link Element}, which the lowering's {@code Terms} hands back with the binder it builds for it, so the association is by
 * identity and nothing is looked up by name afterwards (ADR-060).
 * <p>
 * Ranges are character offsets (I7). A local's, a field's and a for-each variable's range starts at its name, where the
 * reference's declarator starts, and a parameter's covers its type and name. A variable a pattern binds is no declaration
 * here, as it is none there.
 */
final class JavacDebugScan {

    /** What the reference's scan takes for the tree of a scope, and the kind each is. */
    private static final Set<Tree.Kind> SCOPE_KINDS = EnumSet.of(Tree.Kind.METHOD, Tree.Kind.LAMBDA_EXPRESSION,
            Tree.Kind.BLOCK, Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.WHILE_LOOP, Tree.Kind.CASE);

    private final JavacFacts facts;
    private final DebugMetadataCollector.Declarations into;
    private final String sourceId;
    private final CompilationUnitTree unit;
    private final String text;

    private JavacDebugScan(JavacFacts facts, DebugMetadataCollector.Declarations into, String sourceId,
                           CompilationUnitTree unit) {
        this.facts = facts;
        this.into = into;
        this.sourceId = sourceId;
        this.unit = unit;
        this.text = facts.source(unit);
    }

    /**
     * A collector over {@code sources}, the first of which is {@code root}'s text: its declarations are scanned. The others
     * are the libraries given; javac reads only those a program references (D8), and a compilation that reads one stops
     * before its metadata, so they are named and not scanned.
     */
    static DebugMetadataCollector collector(JavacFacts facts, CompilationUnitTree root,
                                            List<DebugMetadataCollector.SourceText> sources) {
        return DebugMetadataCollector.forSources(sources,
                into -> new JavacDebugScan(facts, into, sources.getFirst().id(), root).scan());
    }

    /** The name a string unit has in debug metadata: its first class or interface's, as the reference names it. */
    static String uri(CompilationUnitTree unit, String fallback) {
        var first = new ArrayList<String>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                if (first.isEmpty() && (tree.getKind() == Tree.Kind.CLASS || tree.getKind() == Tree.Kind.INTERFACE)) {
                    first.add(tree.getSimpleName().toString());
                }
                return super.visitClass(tree, null);
            }
        }.scan(unit, null);
        return first.isEmpty() ? fallback : first.getFirst() + ".java";
    }

    private void scan() {
        var declarations = declarations(unit);
        int parameterOrdinal = 0;
        var paramFields = declarations.stream()
                .filter(variable -> facts.parent(variable) instanceof ClassTree)
                .filter(variable -> facts.hasAnnotation(facts.element(variable), JavacNames.PARAM))
                .sorted(Comparator.comparingLong(this::nameStart)).toList();
        for (var field : paramFields) {
            String name = field.getName().toString();
            into.binding(facts.element(field), sourceId + ":param:" + parameterOrdinal++ + ":" + name, name,
                    range(nameStart(field), declaratorEnd(field)), classScope(field),
                    DebugMetadata.BindingKind.PARAM_FIELD, spelling(field), true);
        }

        var methods = methods(unit);
        for (int methodOrdinal = 0; methodOrdinal < methods.size(); methodOrdinal++) {
            var method = methods.get(methodOrdinal);
            String methodId = sourceId + ":method:" + methodOrdinal + ":" + method.getName();
            String methodScope = scope(method, methodId);
            // As the reference's scan: a method that assigns anything presents none of its declarations.
            boolean materializable = !mutates(method);
            int ordinal = 0;
            for (var parameter : method.getParameters()) {
                into.binding(facts.element(parameter), methodId + ":decl:" + ordinal++, parameter.getName().toString(),
                        range(facts.start(parameter), facts.end(parameter)), methodScope,
                        DebugMetadata.BindingKind.PARAMETER, spelling(parameter), materializable);
            }
            var inMethod = declarations(method);
            var locals = inMethod.stream()
                    .filter(variable -> !(facts.parent(variable) instanceof MethodTree)
                            && !(facts.parent(variable) instanceof LambdaExpressionTree)
                            && facts.parent(variable).getKind() != Tree.Kind.BINDING_PATTERN)
                    .sorted(Comparator.comparingLong(this::nameStart)).toList();
            for (var local : locals) {
                into.binding(facts.element(local), methodId + ":decl:" + ordinal++, local.getName().toString(),
                        range(nameStart(local), declaratorEnd(local)), scope(local, methodId),
                        DebugMetadata.BindingKind.LOCAL, spelling(local), materializable);
            }
            var lambdaParameters = lambdas(method).stream()
                    .sorted(Comparator.comparingLong(facts::start))
                    .flatMap(lambda -> lambda.getParameters().stream()).toList();
            for (var parameter : lambdaParameters) {
                into.binding(facts.element(parameter), methodId + ":decl:" + ordinal++, parameter.getName().toString(),
                        range(facts.start(parameter), facts.end(parameter)), scope(parameter, methodId),
                        DebugMetadata.BindingKind.PARAMETER, spelling(parameter), materializable);
            }
        }
    }

    // --- What the unit declares ---

    private static List<VariableTree> declarations(Tree root) {
        var found = new ArrayList<VariableTree>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                found.add(tree);
                return super.visitVariable(tree, null);
            }
        }.scan(root, null);
        return found;
    }

    /** The methods a source writes, in source order: no constructor, and nothing javac generated. */
    private List<MethodTree> methods(Tree root) {
        var found = new ArrayList<MethodTree>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                if (tree.getReturnType() != null && !facts.isGenerated(tree)) found.add(tree);
                return super.visitMethod(tree, null);
            }
        }.scan(root, null);
        found.sort(Comparator.comparingLong(facts::start));
        return found;
    }

    private static List<LambdaExpressionTree> lambdas(Tree root) {
        var found = new ArrayList<LambdaExpressionTree>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
                found.add(tree);
                return super.visitLambdaExpression(tree, null);
            }
        }.scan(root, null);
        return found;
    }

    /**
     * Whether {@code method} assigns, compound-assigns, increments or decrements anything, in a lambda too. An annotation's
     * {@code name = value} is an assignment to javac and none to the reference, and is no update.
     */
    private static boolean mutates(MethodTree method) {
        var found = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitAnnotation(AnnotationTree tree, Void unused) {
                return null;
            }

            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null || found[0]) return null;
                switch (tree.getKind()) {
                    case ASSIGNMENT, MULTIPLY_ASSIGNMENT, DIVIDE_ASSIGNMENT, REMAINDER_ASSIGNMENT, PLUS_ASSIGNMENT,
                         MINUS_ASSIGNMENT, LEFT_SHIFT_ASSIGNMENT, RIGHT_SHIFT_ASSIGNMENT, UNSIGNED_RIGHT_SHIFT_ASSIGNMENT,
                         AND_ASSIGNMENT, XOR_ASSIGNMENT, OR_ASSIGNMENT, PREFIX_INCREMENT, PREFIX_DECREMENT,
                         POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                        found[0] = true;
                        return null;
                    }
                    default -> {
                        return super.scan(tree, null);
                    }
                }
            }
        }.scan(method, null);
        return found[0];
    }

    // --- Scopes ---

    private String classScope(VariableTree field) {
        var owner = (ClassTree) facts.enclosing(field, Tree.Kind.CLASS, Tree.Kind.INTERFACE);
        if (owner == null) throw new CompilerException("Debug @Param declaration has no class scope");
        long start = facts.start(owner);
        String ownerId = "class:" + sourceId + ":" + owner.getSimpleName() + ":" + start;
        return into.scope(owner, "scope:" + ownerId, null, sourceId, range(start, facts.end(owner)),
                DebugMetadata.ScopeKind.CLASS, ownerId);
    }

    /** The scope of {@code tree}: the nearest scope tree at or around it, declared with the scopes around it. */
    private String scope(Tree tree, String methodId) {
        Tree owner = tree;
        while (!SCOPE_KINDS.contains(owner.getKind())) {
            owner = facts.parent(owner);
            if (owner == null) throw new CompilerException("Debug declaration has no scope");
        }
        String existing = into.scopeOf(owner);
        if (existing != null) return existing;
        Tree around = facts.parent(owner);
        while (around != null && !SCOPE_KINDS.contains(around.getKind())) around = facts.parent(around);
        String parentId = around == null ? null : scope(around, methodId);
        long start = facts.start(owner);
        var kind = kind(owner);
        return into.scope(owner, "scope:" + sourceId + ":" + methodId + ":" + start + ":" + kind.name(), parentId, sourceId,
                range(start, facts.end(owner)), kind, methodId);
    }

    private static DebugMetadata.ScopeKind kind(Tree tree) {
        return switch (tree.getKind()) {
            case METHOD -> DebugMetadata.ScopeKind.METHOD;
            case LAMBDA_EXPRESSION -> DebugMetadata.ScopeKind.LAMBDA;
            case ENHANCED_FOR_LOOP, WHILE_LOOP -> DebugMetadata.ScopeKind.LOOP;
            case CASE -> DebugMetadata.ScopeKind.PATTERN;
            default -> DebugMetadata.ScopeKind.BLOCK;
        };
    }

    // --- Positions ---

    /**
     * How a declaration writes its type, as the reference prints it: {@code var} for an inferred local, nothing for a lambda's
     * parameter, and otherwise the type's structure, with no space in a type argument list and an array's brackets after
     * its element type wherever the source wrote them.
     */
    private String spelling(VariableTree variable) {
        if (!facts.isInferred(variable)) return spell(variable.getType());
        return facts.parent(variable) instanceof LambdaExpressionTree ? "" : "var";
    }

    private String spell(Tree type) {
        return switch (type) {
            case IdentifierTree identifier -> identifier.getName().toString();
            case MemberSelectTree select -> spell(select.getExpression()) + "." + select.getIdentifier();
            case PrimitiveTypeTree primitive -> primitive.getPrimitiveTypeKind().name().toLowerCase(Locale.ROOT);
            case ArrayTypeTree array -> spell(array.getType()) + "[]";
            case ParameterizedTypeTree parameterized -> spell(parameterized.getType()) + "<"
                    + String.join(",", parameterized.getTypeArguments().stream().map(this::spell).toList()) + ">";
            case WildcardTree wildcard -> switch (wildcard.getKind()) {
                case EXTENDS_WILDCARD -> "? extends " + spell(wildcard.getBound());
                case SUPER_WILDCARD -> "? super " + spell(wildcard.getBound());
                default -> "?";
            };
            default -> facts.text(type).replaceAll("\\s+", "");
        };
    }

    /** The type a declarator's name follows: under a C-style array declarator's brackets, which come after the name. */
    private static Tree elementType(Tree type) {
        return type instanceof ArrayTypeTree array ? elementType(array.getType()) : type;
    }

    /**
     * Where {@code variable}'s name starts: the first token after its modifiers and element type that is its name and is
     * followed by what may follow a declared name. Comments and string and character literals are skipped. With no such
     * token, the declaration's start.
     */
    private long nameStart(VariableTree variable) {
        long from = facts.isInferred(variable) ? facts.start(variable) : facts.end(elementType(variable.getType()));
        String name = variable.getName().toString();
        int at = (int) Math.max(from, facts.start(variable));
        int end = (int) facts.end(variable);
        while (at < end) {
            at = skipBlank(at, end);
            if (at >= end) break;
            char c = text.charAt(at);
            if (Character.isJavaIdentifierStart(c)) {
                int token = at;
                while (at < end && Character.isJavaIdentifierPart(text.charAt(at))) at++;
                int next = skipBlank(at, text.length());
                boolean declares = next >= text.length() || "=;,:)[".indexOf(text.charAt(next)) >= 0
                        && !text.startsWith("==", next);
                if (text.substring(token, at).equals(name) && declares) return token;
            } else if (c == '"' || c == '\'') {
                at = skipLiteral(at, end);
            } else {
                at++;
            }
        }
        return facts.start(variable);
    }

    /** Past the string, text block or character literal at {@code at}. */
    private int skipLiteral(int at, int end) {
        if (text.startsWith("\"\"\"", at)) {
            int close = text.indexOf("\"\"\"", at + 3);
            return close < 0 ? end : close + 3;
        }
        char quote = text.charAt(at);
        for (int i = at + 1; i < end; i++) {
            if (text.charAt(i) == '\\') i++;
            else if (text.charAt(i) == quote) return i + 1;
        }
        return end;
    }

    /**
     * Where the reference's declarator ends: javac's declaration of a field or a local holds the {@code ;} that ends it, or
     * the {@code ,} before the next declarator, which that does not.
     */
    private long declaratorEnd(VariableTree variable) {
        int end = (int) facts.end(variable);
        if (end <= 0 || text.charAt(end - 1) != ';' && text.charAt(end - 1) != ',') return end;
        end--;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        return end;
    }

    private int skipBlank(int at, int end) {
        while (at < end) {
            if (Character.isWhitespace(text.charAt(at))) {
                at++;
            } else if (text.startsWith("//", at)) {
                int line = text.indexOf('\n', at);
                at = line < 0 ? end : line + 1;
            } else if (text.startsWith("/*", at)) {
                int close = text.indexOf("*/", at + 2);
                at = close < 0 ? end : close + 2;
            } else {
                break;
            }
        }
        return at;
    }

    /**
     * {@code [start, end)} in the reference's form: begin line and column, and the column after the last character. Lines
     * are javac's, as the reference's are its parser's: a lone CR ends one, so a source with them gives the reference's
     * invalid range, which the metadata's validation refuses on both frontends.
     */
    private DebugMetadata.SourceRange range(long start, long end) {
        int from = (int) start;
        int last = (int) Math.max(start, end - 1);
        var lines = unit.getLineMap();
        long beginLine = lines.getLineNumber(from);
        long endLine = lines.getLineNumber(last);
        return new DebugMetadata.SourceRange(sourceId, from, Math.min(text.length(), last + 1), (int) beginLine,
                (int) (from - lines.getStartPosition(beginLine) + 1), (int) endLine,
                (int) (last - lines.getStartPosition(endLine) + 2));
    }
}
