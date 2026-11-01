package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.JulcCompiler.ScriptPurpose;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.error.DiagnosticInfo;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.julclang.compiler.error.DiagnosticCodes.ENTRYPOINT_MISSING;
import static org.julclang.compiler.error.DiagnosticCodes.ENTRYPOINT_WRONG_PARAMETER_COUNT;
import static org.julclang.compiler.error.DiagnosticCodes.VALIDATOR_ANNOTATION_MISSING;

/**
 * Finds what a program declares for a compilation, on javac's trees, by the reference's rules and with its rejections, in
 * its order (ADR-068 I2a): the validator class and its purpose, the {@code @Param} and static fields, the entry points.
 * The reference's message for a legacy annotation has no counterpart: {@code @Validator} does not resolve, so javac rejects
 * it, unless a user declares one, and then it is that user's annotation. Annotations are matched by identity: a user's own
 * {@code @Entrypoint} is not JuLC's (as {@code @NewType}, D7). No lowering happens here.
 */
final class JavacDiscovery {

    /** An entry method, its purpose as named by {@code @Entrypoint} and that purpose's tag; DEFAULT's is -1. */
    record EntryPoint(MethodTree method, String purposeName, int tag) {
    }

    /** A {@code @Param} field. */
    record ParamField(VariableTree tree, VariableElement element, PirType type) {
    }

    private static final String DEFAULT_PURPOSE = "DEFAULT";

    /** Purpose name to ScriptInfo tag. */
    private static final Map<String, Integer> PURPOSE_TAGS = Map.of(
            "MINT", 0, "SPEND", 1, "WITHDRAW", 2, "CERTIFY", 3, "VOTE", 4, "PROPOSE", 5);

    private static final String SPENDING_ENTRYPOINT_PARAMS_SUGGESTION =
            "Use (Redeemer, ScriptContext) for no-datum spending validators, or "
                    + "(Datum, Redeemer, ScriptContext) when the script receives an inline datum.";
    private static final String NON_SPENDING_ENTRYPOINT_PARAMS_SUGGESTION =
            "Use exactly (Redeemer, ScriptContext) for non-spending validator entrypoints.";

    private final JavacFacts facts;

    JavacDiscovery(JavacFacts facts) {
        this.facts = facts;
    }

    // --- Classes ---

    /** Every declared class-like type of {@code unit}, in source order, the outer before what it holds; none anonymous. */
    private static List<ClassTree> types(CompilationUnitTree unit) {
        var types = new ArrayList<ClassTree>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                if (!tree.getSimpleName().isEmpty()) types.add(tree);
                return super.visitClass(tree, null);
            }
        }.scan(unit, null);
        return types;
    }

    private static boolean isClassOrInterface(ClassTree tree) {
        return tree.getKind() == Tree.Kind.CLASS || tree.getKind() == Tree.Kind.INTERFACE;
    }

    /** The class {@code compileMethod} compiles: the first that is not an interface. */
    ClassTree firstClass(CompilationUnitTree unit) {
        return types(unit).stream().filter(tree -> tree.getKind() == Tree.Kind.CLASS).findFirst()
                .orElseThrow(() -> new CompilerException("No class found in source"));
    }

    /**
     * The class a compilation of {@code unit} compiles, or null if it has none, which that compilation goes on to report.
     * Annotations count by name here, as the JavaParser frontend reads them, so that a user's own annotation of that
     * name decides nothing in its favour.
     */
    ClassTree compiledClass(CompilationUnitTree unit, JavacGate.Compiled compiled) {
        if (compiled == JavacGate.Compiled.METHOD) {
            return types(unit).stream().filter(tree -> tree.getKind() == Tree.Kind.CLASS).findFirst().orElse(null);
        }
        return types(unit).stream().filter(JavacDiscovery::isClassOrInterface)
                .filter(type -> JavacNames.VALIDATORS.stream().anyMatch(name -> carries(type, simpleName(name))))
                .findFirst().orElse(null);
    }

    /** Whether a declaration has an annotation of the simple name {@code name}, whichever type that resolved to. */
    boolean carries(Tree declaration, String name) {
        return facts.element(declaration).getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().asElement().getSimpleName().contentEquals(name));
    }

    static String simpleName(String identity) {
        return identity.substring(identity.lastIndexOf('.') + 1);
    }

    /** The annotated validator class. */
    ClassTree validatorClass(CompilationUnitTree unit) {
        var types = types(unit);
        for (var type : types) rejectRoleConflict(type);
        ClassTree validator = null;
        for (var type : types) {
            if (!isClassOrInterface(type) || validatorAnnotations(type).isEmpty()) continue;
            if (validator != null) {
                throw CompilerTypeDiagnostics.entrypointNotUnique("This compilation unit", "validator class", facts.location(type));
            }
            validator = type;
        }
        if (validator != null) return validator;
        throw validationError(VALIDATOR_ANNOTATION_MISSING,
                "Add exactly one validator annotation at the class level, such as "
                        + "@SpendingValidator, @MintingValidator, @WithdrawValidator, "
                        + "@CertifyingValidator, @VotingValidator, @ProposingValidator, or @MultiValidator.",
                null, " (e.g. @SpendingValidator, @MintingValidator)");
    }

    private void rejectRoleConflict(ClassTree type) {
        var element = facts.element(type);
        var validators = validatorAnnotations(type);
        if (facts.hasAnnotation(element, JavacNames.ONCHAIN_LIBRARY) && !validators.isEmpty()) {
            var annotations = String.join(", ", validators.stream().map(simple -> "@" + simple).toList());
            throw new CompilerException("Class " + facts.identity((TypeElement) element)
                    + " must not combine @OnchainLibrary with validator annotation(s): " + annotations);
        }
    }

    /** The simple names of the validator annotations on {@code type}, in source order. */
    private List<String> validatorAnnotations(ClassTree type) {
        var found = new ArrayList<String>();
        for (AnnotationMirror mirror : facts.element(type).getAnnotationMirrors()) {
            var annotation = facts.identity(mirror.getAnnotationType());
            if (JavacNames.VALIDATORS.contains(annotation)) {
                found.add(annotation.substring(annotation.lastIndexOf('.') + 1));
            }
        }
        return found;
    }

    ScriptPurpose purpose(ClassTree type) {
        var found = validatorAnnotations(type);
        if (found.size() > 1) {
            throw new CompilerException("Class " + type.getSimpleName()
                    + " has multiple validator annotations: " + found
                    + ". Only one validator annotation is allowed per class.");
        }
        return switch (found.getFirst()) {
            case "SpendingValidator" -> ScriptPurpose.SPENDING;
            case "MintingValidator" -> ScriptPurpose.MINTING;
            case "WithdrawValidator" -> ScriptPurpose.WITHDRAW;
            case "CertifyingValidator" -> ScriptPurpose.CERTIFYING;
            case "VotingValidator" -> ScriptPurpose.VOTING;
            case "ProposingValidator" -> ScriptPurpose.PROPOSING;
            case "MultiValidator" -> ScriptPurpose.MULTI;
            default -> throw new IllegalStateException(found.getFirst());
        };
    }

    // --- Members ---

    List<VariableTree> fields(ClassTree type) {
        var fields = new ArrayList<VariableTree>();
        for (Tree member : type.getMembers()) {
            if (member instanceof VariableTree field) fields.add(field);
        }
        return fields;
    }

    /** The methods written in {@code type}, in source order; a constructor is not one. */
    List<MethodTree> methods(ClassTree type) {
        var methods = new ArrayList<MethodTree>();
        for (Tree member : type.getMembers()) {
            if (member instanceof MethodTree method && method.getReturnType() != null) methods.add(method);
        }
        return methods;
    }

    /** Whether a declaration is static: the element's modifiers, which hold what Java implies, as for an interface's field. */
    boolean isStatic(Tree declaration) {
        return facts.element(declaration).getModifiers().contains(Modifier.STATIC);
    }

    /** Whether the compilation lowers {@code field}: a static field of the compiled class, other than a {@code @Param}. */
    boolean isLowered(VariableTree field, ClassTree compiled) {
        return facts.parent(field) == compiled && isStatic(field) && !carries(field, simpleName(JavacNames.PARAM));
    }

    /** Whether compiling a library lowers {@code field}: a static field of a class, other than a {@code @Param}. */
    boolean isLoweredInALibrary(VariableTree field) {
        return facts.parent(field) instanceof ClassTree owner && owner.getKind() == Tree.Kind.CLASS && isStatic(field)
                && !carries(field, simpleName(JavacNames.PARAM));
    }

    boolean isParam(VariableTree field) {
        return facts.hasAnnotation(facts.element(field), JavacNames.PARAM);
    }

    boolean isEntrypoint(MethodTree method) {
        return facts.hasAnnotation(facts.element(method), JavacNames.ENTRYPOINT);
    }

    /**
     * The {@code @Param} fields of {@code type}. The banned Data subtypes are collected, as the reference collects them;
     * a field Java treats as a constant is rejected (D14): javac would fold it, so the JVM program would not be the script
     * that receives the applied parameter.
     */
    List<ParamField> paramFields(ClassTree type, List<CompilerDiagnostic> diagnostics) {
        var result = new ArrayList<ParamField>();
        for (var field : fields(type)) {
            if (!isParam(field)) continue;
            var element = (VariableElement) facts.element(field);
            var at = facts.location(field);
            if (element.asType() instanceof DeclaredType declared
                    && JavacNames.BANNED_PARAM_TYPES.contains(facts.identity(declared))) {
                var info = DiagnosticCodes.PARAM_RAW_PLUTUS_DATA;
                var diagnostic = new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR,
                        info.format(field.getType().toString()), "<source>", at.line(), at.column(), info.fix(), info.code());
                if (!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
                continue;
            }
            var pirType = facts.pirType(element.asType(), field.getType());
            var array = PirType.nativeArrayIn(pirType);
            if (array != null) {
                throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable("@Param field '" + field.getName() + "'", array,
                        facts.location(field.getType()));
            }
            if (element.getConstantValue() != null) {
                throw CompilerTypeDiagnostics.paramConstantVariable(field.getName().toString(), at);
            }
            result.add(new ParamField(field, element, pirType));
        }
        return result;
    }

    /** The static fields with an initializer that are not {@code @Param}: each is a {@code let} around the program. */
    List<VariableTree> staticFields(ClassTree type) {
        var result = new ArrayList<VariableTree>();
        for (var field : fields(type)) {
            if (!isStatic(field) || isParam(field) || field.getInitializer() == null) continue;
            result.add(field);
        }
        return result;
    }

    // --- Entry points ---

    /** The {@code @Entrypoint} method of a single-purpose validator. */
    MethodTree entrypoint(ClassTree type) {
        MethodTree entrypoint = null;
        for (var method : methods(type)) {
            if (!isEntrypoint(method)) continue;
            if (entrypoint != null) {
                throw CompilerTypeDiagnostics.entrypointNotUnique(type.getSimpleName().toString(), "@Entrypoint method",
                        facts.location(method));
            }
            entrypoint = method;
        }
        if (entrypoint != null) return entrypoint;
        throw validationError(ENTRYPOINT_MISSING, "Annotate exactly one public static validator method with @Entrypoint.",
                type, type.getSimpleName());
    }

    /** An entry point is static. A method with no body is the lowering's rejection, as in the reference. */
    void requireStaticEntrypoint(MethodTree method, ClassTree type) {
        if (method.getBody() != null && !isStatic(method)) {
            throw CompilerTypeDiagnostics.entrypointNotStatic(method.getName().toString(), type.getSimpleName().toString(),
                    facts.location(method));
        }
    }

    /** The purpose {@code @Entrypoint} names; a marker annotation is the default. */
    private String purposeName(MethodTree method) {
        for (AnnotationMirror mirror : facts.element(method).getAnnotationMirrors()) {
            if (!facts.identity(mirror.getAnnotationType()).equals(JavacNames.ENTRYPOINT)) continue;
            for (Map.Entry<? extends Element, ? extends AnnotationValue> pair : mirror.getElementValues().entrySet()) {
                if (pair.getKey().getSimpleName().contentEquals("purpose")
                        && pair.getValue().getValue() instanceof VariableElement constant) {
                    return constant.getSimpleName().toString();
                }
            }
        }
        return DEFAULT_PURPOSE;
    }

    /** The entry points of a {@code @MultiValidator}, with its rules: see the reference's {@code findMultiEntrypoints}. */
    List<EntryPoint> multiEntrypoints(ClassTree type) {
        var name = type.getSimpleName();
        var entrypoints = new ArrayList<EntryPoint>();
        for (var method : methods(type)) {
            if (!isEntrypoint(method)) continue;
            String purposeName = purposeName(method);
            entrypoints.add(new EntryPoint(method, purposeName,
                    purposeName.equals(DEFAULT_PURPOSE) ? -1 : PURPOSE_TAGS.get(purposeName)));
        }
        if (entrypoints.isEmpty()) {
            throw validationError(ENTRYPOINT_MISSING,
                    "Add a public static @Entrypoint method. For auto-dispatch, annotate each entrypoint with purpose=Purpose.<KIND>.",
                    type, "@MultiValidator " + name);
        }
        boolean hasDefault = entrypoints.stream().anyMatch(e -> e.purposeName().equals(DEFAULT_PURPOSE));
        boolean hasExplicit = entrypoints.stream().anyMatch(e -> !e.purposeName().equals(DEFAULT_PURPOSE));
        if (hasDefault && hasExplicit) {
            throw new CompilerException("@MultiValidator " + name + " mixes DEFAULT and explicit purposes. "
                    + "Either use a single @Entrypoint (manual dispatch) or "
                    + "annotate all @Entrypoint methods with explicit purposes.");
        }
        if (hasDefault) {
            // Manual dispatch: exactly 1 entrypoint, 2 params
            if (entrypoints.size() > 1) {
                throw new CompilerException("@MultiValidator " + name
                        + " has multiple @Entrypoint methods with DEFAULT purpose. "
                        + "Use explicit purposes (e.g. Purpose.MINT) for auto-dispatch, "
                        + "or use a single @Entrypoint for manual dispatch.");
            }
            var method = entrypoints.getFirst().method();
            int count = method.getParameters().size();
            if (count != 2) {
                throw validationError(ENTRYPOINT_WRONG_PARAMETER_COUNT,
                        "Use exactly (Redeemer, ScriptContext) for manual @MultiValidator dispatch.", method,
                        "@MultiValidator manual dispatch", "2", " (redeemer, scriptContext)", count, name, method.getName(), "");
            }
            return entrypoints;
        }
        // Auto-dispatch: check for duplicates and param counts
        var seen = new HashSet<String>();
        for (var entry : entrypoints) {
            if (!seen.add(entry.purposeName())) {
                throw new CompilerException("Duplicate purpose " + entry.purposeName() + " in @MultiValidator " + name);
            }
            var method = entry.method();
            int count = method.getParameters().size();
            if (entry.purposeName().equals("SPEND")) {
                if (count < 2 || count > 3) {
                    throw validationError(ENTRYPOINT_WRONG_PARAMETER_COUNT, SPENDING_ENTRYPOINT_PARAMS_SUGGESTION, method,
                            "@Entrypoint(purpose=Purpose.SPEND)", "2 or 3", "", count, name, method.getName(), "");
                }
            } else if (count != 2) {
                throw validationError(ENTRYPOINT_WRONG_PARAMETER_COUNT, NON_SPENDING_ENTRYPOINT_PARAMS_SUGGESTION, method,
                        "@Entrypoint(purpose=Purpose." + entry.purposeName() + ")", "2", " (redeemer, scriptContext)",
                        count, name, method.getName(), "");
            }
        }
        return entrypoints;
    }

    void requireEntrypointParameters(MethodTree entrypoint, ScriptPurpose purpose, ClassTree type) {
        int count = entrypoint.getParameters().size();
        if (purpose == ScriptPurpose.SPENDING) {
            // Spending validators: 2 params (redeemer, ctx) or 3 params (datum, redeemer, ctx)
            if (count < 2 || count > 3) {
                throw validationError(ENTRYPOINT_WRONG_PARAMETER_COUNT, SPENDING_ENTRYPOINT_PARAMS_SUGGESTION, entrypoint,
                        "@SpendingValidator", "2 or 3", " (datum, redeemer, scriptContext)", count,
                        type.getSimpleName(), entrypoint.getName(), "");
            }
        } else if (count != 2) {
            // Non-spending validators: 2 params (redeemer, scriptContext)
            String annotation = switch (purpose) {
                case MINTING -> "@MintingValidator";
                case WITHDRAW -> "@WithdrawValidator";
                case CERTIFYING -> "@CertifyingValidator";
                case VOTING -> "@VotingValidator";
                case PROPOSING -> "@ProposingValidator";
                default -> "@" + purpose.name();
            };
            String hint = count == 3 ? " Did you mean @SpendingValidator?" : "";
            throw validationError(ENTRYPOINT_WRONG_PARAMETER_COUNT, NON_SPENDING_ENTRYPOINT_PARAMS_SUGGESTION, entrypoint,
                    annotation, "2", " (redeemer, scriptContext)", count, type.getSimpleName(), entrypoint.getName(),
                    "." + hint);
        }
    }

    /** {@link DiagnosticInfo#format} takes the names as javac holds them too: each is printed as text. */
    private CompilerException validationError(DiagnosticInfo info, String suggestion, Tree tree, Object... args) {
        var printable = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            printable[i] = args[i] instanceof CharSequence text ? text.toString() : args[i];
        }
        String message = info.format(printable);
        int line = 0;
        int column = 0;
        if (tree != null) {
            var at = facts.location(tree);
            line = at.line();
            column = at.column();
        }
        return new CompilerException(message, new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, message, "<source>",
                line, column, suggestion, info.code()));
    }
}
