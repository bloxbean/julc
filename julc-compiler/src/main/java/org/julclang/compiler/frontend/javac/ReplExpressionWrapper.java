package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import org.julclang.compiler.error.CompilerDiagnostic;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The REPLs' typed expression wrapper (ADR-068 D9), shared by the CLI REPL and the playground's expression
 * evaluator. JuLC's dialect typed integer literals as {@code BigInteger}; this wrapper asks javac for the types
 * instead:
 * <ol>
 *   <li>it attributes an expression as {@code var v = (expr);} in a probe method, so that javac gives it its
 *       standalone type: a {@code return} to {@code Object} would be a target type, and a conditional would take it.
 *       A block of statements has no such form, so it is the body of a probe returning {@code Object}, and javac
 *       types each returned expression; one with no type of its own there, such as a conditional, is rejected;</li>
 *   <li>it then generates {@code static R __eval() { ... }} with {@code R} the input's type, or, when that is
 *       {@code int} or {@code long}, {@code R = BigInteger} with each returned expression wrapped in
 *       {@code BigInteger.valueOf(...)};</li>
 *   <li>a javac rejection of the probe is the REPL's error, its lines counted from the input's first line.</li>
 * </ol>
 * Integer arithmetic stays JuLC's: it is unbounded on-chain (I9, ADR-062 T6), so {@code 2147483647 + 1} evaluates
 * to 2147483648 where the JVM gives -2147483648. The wrapper fixes only the types javac checks.
 */
public final class ReplExpressionWrapper {

    public static final String CLASS_NAME = "__Repl";
    public static final String METHOD_NAME = "__eval";

    /**
     * A wrapped input: the source to compile and its return type, or the diagnostics that reject it.
     *
     * @param source     the compilation unit, or null when rejected
     * @param returnType the method's return type as written in the source, or null when rejected
     * @param errors     javac's diagnostics, lines relative to the input
     */
    public record Wrapped(String source, String returnType, List<CompilerDiagnostic> errors) {
        public Wrapped {
            errors = List.copyOf(errors);
        }

        public boolean isAccepted() {
            return errors.isEmpty();
        }
    }

    private ReplExpressionWrapper() {
    }

    /**
     * Wraps {@code input}, an expression or a block of statements ending in a {@code return}.
     *
     * @param input   the user's input
     * @param imports the single-type and on-demand imports of the wrapper, such as {@code java.math.BigInteger} or
     *                {@code org.julclang.ledger.*}
     * @param pool    library sources the input may call (D8)
     */
    public static Wrapped wrap(String input, List<String> imports, List<String> pool) {
        String trimmed = input.strip();
        boolean block = trimmed.contains(";");
        String header = header(imports);
        String variable = unusedName(trimmed);
        String prefix = "var " + variable + " = (";
        String body = block ? trimmed : prefix + trimmed + ");";
        String opening = "class " + CLASS_NAME + " {\n    static " + (block ? "Object" : "void") + " " + METHOD_NAME
                + "() {\n";
        String probe = header + opening + body + "\n    }\n}\n";
        int bodyStart = header.length() + opening.length();
        int bodyLine = (int) header.lines().count() + 3;
        var attribution = JavacFrontend.shared().attribute(List.of(JavacFrontend.Unit.of(probe)),
                pool.stream().map(JavacFrontend.Unit::of).toList());
        if (!attribution.result().isAccepted()) {
            return new Wrapped(null, null, attribution.result().diagnostics().stream()
                    .map(d -> relocate(d, bodyLine, block ? 0 : prefix.length())).toList());
        }
        CompilationUnitTree unit = attribution.roots().getFirst();
        if (!isOnlyTheProbeMethod(unit)) return rejected("the input must be one expression or one block of statements");
        var returns = block ? returns(attribution, unit) : List.<TreePath>of();
        var inputTypes = block
                ? returns.stream().map(attribution.trees()::getTypeMirror).toList()
                : List.of(attribution.trees().getElement(probeVariable(unit)).asType());
        var types = new LinkedHashSet<String>();
        boolean integral = !inputTypes.isEmpty();
        for (TypeMirror type : inputTypes) {
            if (type.toString().equals("java.lang.Object")) {
                return rejected(block
                        ? "a returned expression has no type of its own here (a conditional, for example);"
                                + " assign it to a local of its type and return that"
                        : "the input's type Object is not an on-chain type");
            }
            switch (type.getKind()) {
                case INT, LONG, SHORT, BYTE -> types.add("BigInteger");
                default -> {
                    integral = false;
                    String printed = print(type, imports);
                    if (printed == null) return rejected("the input's type " + type + " is not an on-chain type");
                    types.add(printed);
                }
            }
        }
        if (types.size() != 1) {
            return rejected(types.isEmpty() ? "the input returns no value"
                    : "the input's return statements have different types: " + String.join(", ", types));
        }
        String returnType = types.iterator().next();
        String finalBody = block ? body : "return (" + trimmed + ");";
        if (integral && !block) {
            finalBody = "return BigInteger.valueOf((" + trimmed + "));";
        } else if (integral) {
            // Wrap each returned integer, from the last, by its offsets in the probe.
            var positions = attribution.trees().getSourcePositions();
            var sorted = new ArrayList<>(returns);
            sorted.sort(Comparator.comparingLong((TreePath p) -> positions.getStartPosition(unit, p.getLeaf())).reversed());
            var sb = new StringBuilder(body);
            for (var r : sorted) {
                int s = (int) positions.getStartPosition(unit, r.getLeaf()) - bodyStart;
                int e = (int) positions.getEndPosition(unit, r.getLeaf()) - bodyStart;
                sb.replace(s, e, "BigInteger.valueOf(" + sb.substring(s, e) + ")");
            }
            finalBody = sb.toString();
        }
        String source = header + "class " + CLASS_NAME + " {\n    static " + returnType + " " + METHOD_NAME
                + "() {\n" + finalBody + "\n    }\n}\n";
        return new Wrapped(source, returnType, List.of());
    }

    private static String header(List<String> imports) {
        var sb = new StringBuilder();
        var seen = new LinkedHashSet<String>();
        for (String imp : imports) {
            if (seen.add(imp)) sb.append("import ").append(imp).append(";\n");
        }
        if (!seen.contains("java.math.BigInteger")) sb.append("import java.math.BigInteger;\n");
        return sb.append('\n').toString();
    }

    /** An identifier that does not occur in {@code input} (D9). */
    private static String unusedName(String input) {
        String name = "__v";
        while (input.contains(name)) name += "_";
        return name;
    }

    /** Whether the unit declares only the wrapper's class and its probe method, so the input did not close them. */
    private static boolean isOnlyTheProbeMethod(CompilationUnitTree unit) {
        if (unit.getTypeDecls().size() != 1 || !(unit.getTypeDecls().getFirst() instanceof ClassTree wrapper)) return false;
        return wrapper.getMembers().stream()
                .filter(member -> !(member instanceof MethodTree method && method.getName().contentEquals("<init>")))
                .count() == 1;
    }

    /** The path of the probe method's {@code var}, the first statement of an expression's probe. */
    private static TreePath probeVariable(CompilationUnitTree unit) {
        var wrapper = (ClassTree) unit.getTypeDecls().getFirst();
        var method = wrapper.getMembers().stream().filter(member -> member instanceof MethodTree m
                && m.getName().contentEquals(METHOD_NAME)).map(MethodTree.class::cast).findFirst().orElseThrow();
        var path = new TreePath(new TreePath(new TreePath(new TreePath(unit), wrapper), method), method.getBody());
        return new TreePath(path, method.getBody().getStatements().getFirst());
    }

    /** The returned expressions of the probe method, outside lambdas and local classes. */
    private static List<TreePath> returns(JavacFrontend.Attribution attribution, CompilationUnitTree unit) {
        var found = new ArrayList<TreePath>();
        new TreePathScanner<Void, Void>() {
            private int depth;

            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if (!node.getName().contentEquals(METHOD_NAME) || depth > 0) return null;
                depth++;
                super.visitMethod(node, unused);
                depth--;
                return null;
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                return getCurrentPath().getParentPath().getLeaf() instanceof CompilationUnitTree
                        ? super.visitClass(node, unused) : null;
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                return null;
            }

            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                ExpressionTree expression = node.getExpression();
                if (expression != null) found.add(new TreePath(getCurrentPath(), expression));
                return null;
            }
        }.scan(unit, null);
        return found;
    }

    /**
     * A type as source text under {@code imports}: simple names for {@code java.lang}, imported and on-demand
     * imported types, qualified names otherwise; null for a type with no source form here (a type variable,
     * {@code null}, an intersection).
     */
    static String print(TypeMirror type, List<String> imports) {
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, DOUBLE, FLOAT -> type.toString();
            case ARRAY -> {
                String component = print(((ArrayType) type).getComponentType(), imports);
                yield component == null ? null : component + "[]";
            }
            case DECLARED -> {
                var declared = (DeclaredType) type;
                String name = name((TypeElement) declared.asElement(), Set.copyOf(imports));
                var args = new ArrayList<String>();
                for (TypeMirror arg : declared.getTypeArguments()) {
                    String printed = print(arg, imports);
                    if (printed == null) yield null;
                    args.add(printed);
                }
                yield args.isEmpty() ? name : name + "<" + String.join(", ", args) + ">";
            }
            default -> null;
        };
    }

    private static String name(TypeElement type, Set<String> imports) {
        Element enclosing = type.getEnclosingElement();
        if (enclosing.getKind() != ElementKind.PACKAGE) {
            return name((TypeElement) enclosing, imports) + "." + type.getSimpleName();
        }
        String pkg = ((PackageElement) enclosing).getQualifiedName().toString();
        String fqcn = type.getQualifiedName().toString();
        boolean visible = pkg.equals("java.lang") || pkg.isEmpty() || imports.contains(fqcn)
                || imports.contains(pkg + ".*");
        return visible ? type.getSimpleName().toString() : fqcn;
    }

    /** A probe diagnostic with its line counted from the input's first line, and its column on that line. */
    private static CompilerDiagnostic relocate(JavacDiagnostic d, int bodyLine, int firstLineShift) {
        int line = d.line() < bodyLine ? 0 : (int) (d.line() - bodyLine + 1);
        int column = (int) d.column();
        if (line == 1) column = Math.max(1, column - firstLineShift);
        return new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, d.message(), "<input>", line, line == 0 ? 0 : column,
                d.hint(), d.code());
    }

    private static Wrapped rejected(String message) {
        return new Wrapped(null, null, List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR,
                "Cannot evaluate: " + message, "<input>", 0, 0)));
    }
}
