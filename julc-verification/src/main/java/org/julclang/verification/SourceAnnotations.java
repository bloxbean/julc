package org.julclang.verification;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.frontend.javac.JavacSyntax;

import java.util.ArrayList;
import java.util.List;

/**
 * What the verification resolvers read of a source: its package, its imports, and the annotations on its top-level
 * types, as written. JavaParser or javac's parser reads it (ADR-068 M6) into this one model, so the resolvers do not
 * depend on either tree; {@link #DEFAULT_READER} is the one switch, and M6 deletes the JavaParser reading.
 * <p>
 * An annotation's {@link Annotation#text() text} is printed as JavaParser prints it, {@code @Name(a = v, b = w)}, with
 * each value as written, since it is the property's source fragment.
 */
final class SourceAnnotations {

    /** How a source is read. */
    enum Reader { JAVAPARSER, JAVAC }

    /** The reader {@link #read(String)} uses: JavaParser's until ADR-068 M5 makes javac the
     * default. The {@code julc.tools.reader} system property ({@code JAVAPARSER} or {@code JAVAC}) chooses it for every
     * tool, so that each module's tests run on either reading; it leaves at M6 with the JavaParser reading.
     */
    static final Reader DEFAULT_READER = Reader.valueOf(System.getProperty("julc.tools.reader", "JAVAPARSER"));

    /** A source: its package name ({@code ""} for none), its imports and its top-level types, in source order. */
    record Unit(String packageName, List<Import> imports, List<Type> types) {
    }

    /** An import, its name without {@code .*}. */
    record Import(String name, boolean isStatic, boolean isAsterisk) {
    }

    /** A top-level type and its annotations, in source order. */
    record Type(String name, List<Annotation> annotations) {
    }

    /**
     * An annotation.
     *
     * @param name       its name as written, {@code a.b.C} or {@code C}
     * @param normal     whether it has named members ({@code @A(x = 1)}, or {@code @A()})
     * @param members    its named members, in source order
     * @param single     a single-member annotation's value, else null
     * @param line       its 1-based line
     * @param column     its 1-based column, a character offset (ADR-068 I7)
     * @param text       as JavaParser prints it
     */
    record Annotation(String name, boolean normal, List<Member> members, Value single, int line, int column,
                      String text) {

        /** The last identifier of the name. */
        String identifier() {
            return name.substring(name.lastIndexOf('.') + 1);
        }
    }

    /** A named member. */
    record Member(String name, Value value) {
    }

    /**
     * A member's value.
     *
     * @param string  a string literal's value, else null
     * @param integer an {@code int} or {@code long} literal's value, written without a sign, else null
     * @param text    the value as JavaParser prints it: a name as {@code a.b}, a literal as written
     */
    record Value(String string, Long integer, String text) {
    }

    private SourceAnnotations() {
    }

    /** {@code source} read by {@link #DEFAULT_READER}. */
    static Unit read(String source) {
        return read(source, DEFAULT_READER);
    }

    /** {@code source} read by {@code reader}; a source it cannot read throws, as the resolvers did. */
    static Unit read(String source, Reader reader) {
        return switch (reader) {
            case JAVAPARSER -> readWithJavaParser(source);
            case JAVAC -> readWithJavac(source);
        };
    }

    // --- JavaParser ---

    private static Unit readWithJavaParser(String source) {
        CompilationUnit unit = JavaSourceParser.parseCompilationUnit(source);
        String packageName = unit.getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
        var imports = unit.getImports().stream()
                .map(i -> new Import(i.getNameAsString(), i.isStatic(), i.isAsterisk())).toList();
        var types = unit.getTypes().stream()
                .map(t -> new Type(t.getNameAsString(),
                        t.getAnnotations().stream().map(SourceAnnotations::annotation).toList()))
                .toList();
        return new Unit(packageName, imports, types);
    }

    private static Annotation annotation(AnnotationExpr annotation) {
        var members = new ArrayList<Member>();
        Value single = null;
        boolean normal = annotation instanceof NormalAnnotationExpr;
        if (annotation instanceof NormalAnnotationExpr n) {
            n.getPairs().forEach(pair -> members.add(new Member(pair.getNameAsString(), value(pair.getValue()))));
        } else if (annotation instanceof SingleMemberAnnotationExpr s) {
            single = value(s.getMemberValue());
        }
        var begin = annotation.getBegin().orElse(null);
        return new Annotation(annotation.getNameAsString(), normal, List.copyOf(members), single,
                begin == null ? 1 : begin.line, begin == null ? 1 : begin.column, annotation.toString());
    }

    private static Value value(Expression expression) {
        String string = expression instanceof StringLiteralExpr literal ? literal.asString() : null;
        Long integer = null;
        if (expression instanceof LongLiteralExpr literal) {
            try {
                integer = literal.asNumber().longValue();
            } catch (RuntimeException ignored) { }
        } else if (expression instanceof IntegerLiteralExpr literal) {
            try {
                integer = literal.asNumber().longValue();
            } catch (RuntimeException ignored) { }
        }
        return new Value(string, integer, expression.toString());
    }

    // --- javac ---

    private static Unit readWithJavac(String source) {
        var syntax = JavacSyntax.read(source);
        if (syntax.hasErrors()) {
            throw new IllegalArgumentException("Could not parse Java source: " + syntax.errorMessages());
        }
        var unit = syntax.unit();
        String packageName = unit.getPackageName() == null ? "" : dotted(unit.getPackageName());
        var imports = new ArrayList<Import>();
        for (ImportTree i : unit.getImports()) {
            Tree name = i.getQualifiedIdentifier();
            boolean asterisk = name instanceof MemberSelectTree select && select.getIdentifier().contentEquals("*");
            String text = asterisk ? dotted(((MemberSelectTree) name).getExpression()) : dotted(name);
            imports.add(new Import(text, i.isStatic(), asterisk));
        }
        var types = new ArrayList<Type>();
        for (Tree declaration : unit.getTypeDecls()) {
            if (!(declaration instanceof ClassTree type)) continue;
            types.add(new Type(type.getSimpleName().toString(), type.getModifiers().getAnnotations().stream()
                    .map(a -> annotation(syntax, a)).toList()));
        }
        return new Unit(packageName, List.copyOf(imports), List.copyOf(types));
    }

    private static Annotation annotation(JavacSyntax syntax, AnnotationTree annotation) {
        String name = dotted(annotation.getAnnotationType());
        var arguments = annotation.getArguments();
        // javac reads @A(v) as one argument that is not an assignment, and @A(x = v) as assignments.
        boolean single = arguments.size() == 1 && !(arguments.getFirst() instanceof AssignmentTree);
        boolean normal = !single && (!arguments.isEmpty() || hasParentheses(syntax, annotation));
        var members = new ArrayList<Member>();
        Value singleValue = null;
        var printed = new ArrayList<String>();
        if (single) {
            singleValue = value(syntax, arguments.getFirst());
            printed.add(singleValue.text());
        } else {
            for (ExpressionTree argument : arguments) {
                var assignment = (AssignmentTree) argument;
                var member = new Member(dotted(assignment.getVariable()), value(syntax, assignment.getExpression()));
                members.add(member);
                printed.add(member.name() + " = " + member.value().text());
            }
        }
        String text = "@" + name + (single || normal ? "(" + String.join(", ", printed) + ")" : "");
        return new Annotation(name, normal, List.copyOf(members), singleValue, syntax.line(annotation),
                syntax.column(annotation), text);
    }

    /** {@code @A()}: javac gives it no argument, as {@code @A}, and JavaParser reads it as a normal annotation. */
    private static boolean hasParentheses(JavacSyntax syntax, AnnotationTree annotation) {
        return syntax.source(annotation).endsWith(")");
    }

    private static Value value(JavacSyntax syntax, ExpressionTree expression) {
        String written = syntax.source(expression);
        if (expression instanceof LiteralTree literal) {
            Object v = literal.getValue();
            // javac folds a minus into an int or long literal; JavaParser reads a unary minus, which is not a literal.
            boolean signed = written.startsWith("-");
            Long integer = !signed && (v instanceof Integer || v instanceof Long) ? ((Number) v).longValue() : null;
            // A text block is a string literal to javac; JavaParser reads it as a different expression.
            String string = v instanceof String s && !written.startsWith("\"\"\"") ? s : null;
            return new Value(string, integer, written);
        }
        // Anything else as a printer lays it out, as JavaParser's does: `a + b`, whatever the spacing written.
        String name = dottedOrNull(expression);
        return new Value(null, null, name != null ? name : expression.toString());
    }

    private static String dotted(Tree tree) {
        String name = dottedOrNull(tree);
        return name != null ? name : tree.toString();
    }

    private static String dottedOrNull(Tree tree) {
        return switch (tree) {
            case IdentifierTree id -> id.getName().toString();
            case MemberSelectTree select -> {
                String qualifier = dottedOrNull(select.getExpression());
                yield qualifier == null ? null : qualifier + "." + select.getIdentifier();
            }
            case null, default -> null;
        };
    }
}
