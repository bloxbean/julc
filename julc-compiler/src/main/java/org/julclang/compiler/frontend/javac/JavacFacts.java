package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticInfo;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one door to javac's attribution (ADR-068 D2): a stateless service over javac's own tree, never a copy of it. It
 * answers tree -> {@link Element}, tree -> {@link TypeMirror}, {@code TypeMirror} -> {@link PirType}, a tree's parent
 * and its position. Queries are keyed by the {@link Tree}; a {@link TreePath} never leaves this class. Identity is
 * nominal (I12): a type is what its {@link TypeElement} says, never what its simple name suggests, so a user type
 * shadows a ledger or built-in name exactly as in Java (#284, #286).
 * <p>
 * One instance serves one javac task, or one query of a {@link JavacMetadata}: model objects must not outlive it.
 */
public final class JavacFacts {

    /** A fragment's limit, as the JavaParser frontend's source positions have it. */
    private static final int FRAGMENT_LIMIT = 100;

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final TypeRegistry registry;
    /** Every tree of the units javac read, to its path. Empty over a task with no units. */
    private final Map<Tree, TreePath> paths = new IdentityHashMap<>();
    /** The pool units javac read (D8). */
    private final Set<CompilationUnitTree> libraries = Collections.newSetFromMap(new IdentityHashMap<>());

    private JavacFacts(Trees trees, Elements elements, Types types, TypeRegistry registry) {
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.registry = registry;
    }

    /** Facts over an attributed task. */
    static JavacFacts of(JavacFrontend.Attribution attribution, TypeRegistry registry) {
        var facts = new JavacFacts(attribution.trees(), attribution.elements(), attribution.types(), registry);
        attribution.roots().forEach(facts::index);
        attribution.libraries().forEach(facts::index);
        facts.libraries.addAll(attribution.libraries());
        return facts;
    }

    /**
     * Records every tree's path, once per unit. javac shares a few subtrees between a record's field and its generated
     * members; such a tree keeps its first path, and any of them gives the same element, type and position.
     */
    private void index(CompilationUnitTree unit) {
        new TreeScanner<Void, TreePath>() {
            @Override
            public Void scan(Tree tree, TreePath parent) {
                if (tree == null) return null;
                var path = new TreePath(parent, tree);
                paths.putIfAbsent(tree, path);
                return tree.accept(this, path);
            }
        }.scan(unit, null);
    }

    /** Facts over a task with no compilation units: its stubs have no trees. */
    static JavacFacts of(Elements elements, Types types, TypeRegistry registry) {
        return new JavacFacts(null, elements, types, registry);
    }

    public Elements elements() {
        return elements;
    }

    public Types types() {
        return types;
    }

    /** The registries types resolve against. */
    public TypeRegistry registry() {
        return registry;
    }

    /** The path of {@code tree}: one that is in no unit javac read is an internal error (I12). */
    private TreePath path(Tree tree) {
        TreePath path = paths.get(tree);
        if (path == null) throw new IllegalStateException("not a tree of a unit javac read: " + tree.getKind());
        return path;
    }

    /** The element {@code tree} denotes; a tree of a kind with no element is a caller's error (I12). */
    public Element element(Tree tree) {
        Element element = trees.getElement(path(tree));
        if (element == null) throw new IllegalStateException("javac attributed no element to " + tree.getKind());
        return element;
    }

    /** {@link #element}, for a tree that may denote nothing, such as a name of an annotation's element. */
    public Element elementOrNull(Tree tree) {
        return trees.getElement(path(tree));
    }

    /** The unit that declares {@code element}, or null for what has no tree. */
    public CompilationUnitTree unitOf(Element element) {
        Tree tree = trees.getTree(element);
        return tree == null ? null : path(tree).getCompilationUnit();
    }

    /** The attributed type of {@code tree}; an error type never reaches JuLC (I12). */
    public TypeMirror type(Tree tree) {
        TypeMirror type = trees.getTypeMirror(path(tree));
        if (type == null || type.getKind() == TypeKind.ERROR) {
            throw new IllegalStateException("javac attributed no type to " + tree.getKind());
        }
        return type;
    }

    /** The tree that contains {@code tree}, or null for a compilation unit. */
    public Tree parent(Tree tree) {
        TreePath parent = path(tree).getParentPath();
        return parent == null ? null : parent.getLeaf();
    }

    /** The nearest tree around {@code tree} of one of {@code kinds}, or null. */
    public Tree enclosing(Tree tree, Tree.Kind... kinds) {
        var wanted = EnumSet.copyOf(List.of(kinds));
        for (TreePath path = path(tree).getParentPath(); path != null; path = path.getParentPath()) {
            if (wanted.contains(path.getLeaf().getKind())) return path.getLeaf();
        }
        return null;
    }

    /** Whether {@code element} is declared in a pool unit javac read: a library's, not a root's and not a stub's. */
    public boolean inLibraryUnit(Element element) {
        var path = trees == null ? null : trees.getPath(element);
        return path != null && libraries.contains(path.getCompilationUnit());
    }

    /** Whether a source declares {@code method}: an accessor a record writes out. javac's own has no tree, nor has a stub's. */
    public boolean declaredInSource(ExecutableElement method) {
        return trees != null && trees.getTree(method) != null;
    }

    /** The initializer of a variable a source declares, or null: a stub has none. */
    public ExpressionTree initializer(VariableElement variable) {
        return trees != null && trees.getTree(variable) instanceof VariableTree declaration ? declaration.getInitializer() : null;
    }

    /** The declaration of a variable a source declares, or null: a stub has none. */
    public VariableTree declaration(VariableElement variable) {
        return trees != null && trees.getTree(variable) instanceof VariableTree declaration ? declaration : null;
    }

    /** The declaration of a method a source declares, or null. */
    public MethodTree declaration(ExecutableElement method) {
        return trees != null && trees.getTree(method) instanceof MethodTree declaration ? declaration : null;
    }

    /** The unit {@code tree} stands in. */
    public CompilationUnitTree unitOfTree(Tree tree) {
        return path(tree).getCompilationUnit();
    }

    /** Whether the class {@code owner} is declared around {@code tree}, at any depth. */
    public boolean encloses(Element owner, Tree tree) {
        for (TreePath path = path(tree).getParentPath(); path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof ClassTree && owner.equals(trees.getElement(path))) return true;
        }
        return false;
    }

    /** The {@code JULC0087} predicate, over this task's types and source records (ADR-068 D6). */
    public ArrayIdentityEquality arrayEquality() {
        return new ArrayIdentityEquality(types, elements, trees);
    }

    /** The table of operations that compare elements or keys (ADR-068 D6). */
    public EqualityOperations equalityOperations() {
        return new EqualityOperations(types, elements);
    }

    /** Whether Java's {@code equals} can hold between two static types (ADR-068 D6). */
    public EqualityRelation equalityRelation() {
        return new EqualityRelation(types, elements);
    }

    /** The sealed interfaces of {@code variant} the registry holds as sums, in declaration order. */
    public List<TypeElement> sealedParents(TypeElement variant) {
        var parents = new ArrayList<TypeElement>();
        for (var type : variant.getInterfaces()) {
            var parent = typeElement(type);
            if (parent.getKind() == ElementKind.INTERFACE && parent.getModifiers().contains(Modifier.SEALED)
                    && registry().lookupNominal(identity(parent)).orElse(null) instanceof PirType.SumType) {
                parents.add(parent);
            }
        }
        return parents;
    }

    /** The class a declared type names. */
    public TypeElement typeElement(TypeMirror type) {
        return (TypeElement) types.asElement(type);
    }

    /** A record's components. Reading them does not complete a class by itself, so this does. */
    public List<? extends RecordComponentElement> components(TypeElement record) {
        record.getKind();
        return record.getRecordComponents();
    }

    /** A sealed type's permitted subclasses, in order, completed; an unresolved one never reaches JuLC (I12). */
    public List<TypeElement> permitted(TypeElement sealed) {
        sealed.getKind();
        var permitted = new ArrayList<TypeElement>();
        for (TypeMirror type : sealed.getPermittedSubclasses()) {
            if (type.getKind() == TypeKind.ERROR) throw new IllegalStateException("an error type reached JuLC: " + type);
            permitted.add(typeElement(type));
        }
        return permitted;
    }

    /**
     * The records nested in {@code tree} that a sealed interface with no permits clause takes as its variants, in member
     * order, as the JavaParser frontend reads them. A record implementing another type of the interface's simple name is
     * one too: it has a tag there, so it must not drop out here and shift the others silently. It makes this list differ
     * from {@link #permitted}, which is {@code JULC0092}.
     */
    public List<TypeElement> nestedVariants(ClassTree tree, TypeElement sealed) {
        var nested = new ArrayList<TypeElement>();
        for (Tree member : tree.getMembers()) {
            if (member instanceof ClassTree nestedTree && nestedTree.getKind() == Tree.Kind.RECORD) {
                var record = (TypeElement) element(nestedTree);
                if (record.getInterfaces().stream().anyMatch(i -> typeElement(i).getSimpleName().contentEquals(sealed.getSimpleName()))) {
                    nested.add(record);
                }
            }
        }
        return nested;
    }

    /** Whether {@code element} carries the annotation named {@code annotation}, by identity. */
    public boolean hasAnnotation(Element element, String annotation) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (identity(mirror.getAnnotationType()).equals(annotation)) return true;
        }
        return false;
    }

    /** The identity of a declared type: {@link #identity(TypeElement)} of its class. */
    public String identity(DeclaredType type) {
        return identity((TypeElement) type.asElement());
    }

    /**
     * A type's identity, the key of every registry: its qualified name. Java gives a local type none, and javac reports
     * its simple name there, which could be anything's: so a local type, and a type nested in one, is its binary name
     * ({@code P$1R}), which javac keeps unique. That is not the JavaParser frontend's key, the bare simple name.
     */
    public String identity(TypeElement type) {
        for (Element e = type; e instanceof TypeElement || e instanceof ExecutableElement || e instanceof VariableElement;
             e = e.getEnclosingElement()) {
            if (e instanceof TypeElement t && (t.getNestingKind() == NestingKind.LOCAL
                    || t.getNestingKind() == NestingKind.ANONYMOUS)) {
                return elements.getBinaryName(type).toString();
            }
        }
        return type.getQualifiedName().toString();
    }

    /**
     * The PIR type of {@code type} (ADR-068 D7): built-in types by identity, then the registry by identity, and nothing
     * by simple name. A rejection is the JavaParser frontend's, with its names as javac prints them, located at
     * {@code where}.
     *
     * @param where the tree that writes {@code type}, or null for a stub, which has none
     */
    public PirType pirType(TypeMirror type, Tree where) {
        return pirType(type, where, false);
    }

    /**
     * The PIR type of a value javac typed {@code type}, which no source wrote: as {@link #pirType}, but where javac
     * inferred a type argument from a primitive ({@code T} is {@code Integer} in {@code JulcList.of(1)}) that is the
     * primitive's type. Written in a declaration the same type is a rejection, as in the reference.
     */
    public PirType valueType(TypeMirror type, Tree where) {
        return pirType(type, where, true);
    }

    /** The method a binder of {@code method} stands for: a binder goes by class and name, so interchangeable overloads share the first's. */
    public ExecutableElement binderMethod(ExecutableElement method) {
        return ElementFilter.methodsIn(method.getEnclosingElement().getEnclosedElements()).stream()
                .filter(other -> other.getSimpleName().equals(method.getSimpleName()))
                .filter(other -> other.getModifiers().contains(Modifier.STATIC)).findFirst().orElse(method);
    }

    /** The PIR function type of {@code method}, a lambda's worth of arrows per parameter. A stub's has no trees to name. */
    public PirType methodType(ExecutableElement method) {
        MethodTree tree = trees == null ? null : trees.getTree(method);
        var parameters = method.getParameters();
        var argumentTypes = new ArrayList<PirType>();
        for (int i = 0; i < parameters.size(); i++) {
            argumentTypes.add(tree == null ? pirType(parameters.get(i).asType(), null) : parameterType(tree.getParameters().get(i)));
        }
        PirType type = pirType(method.getReturnType(), tree == null ? null : tree.getReturnType());
        for (int i = argumentTypes.size() - 1; i >= 0; i--) type = new PirType.FunType(argumentTypes.get(i), type);
        return type;
    }

    private PirType pirType(TypeMirror type, Tree where, boolean inferred) {
        return switch (type.getKind()) {
            case BOOLEAN -> new PirType.BoolType();
            case INT, LONG -> new PirType.IntegerType();
            case VOID -> new PirType.UnitType();
            case BYTE, SHORT, CHAR, FLOAT, DOUBLE -> throw rejection("Unsupported primitive type: " + type
                    + ". Supported types: boolean, int, long", where);
            case ARRAY -> array((ArrayType) type, where);
            case DECLARED -> declared((DeclaredType) type, where, inferred);
            case TYPEVAR -> throw rejection(registry.unknownTypeMessage(type.toString(), type.toString()), where);
            case ERROR -> throw new IllegalStateException("an error type reached JuLC: " + type);
            default -> throw rejection("Cannot resolve type: " + type + ". Supported types: BigInteger,"
                    + " String, boolean, byte[], List<T>, Map<K,V>, records, sealed interfaces", where);
        };
    }

    /** Whether {@code type} is {@code boolean} or {@code java.lang.Boolean}, by identity. */
    public boolean isBoolean(TypeMirror type) {
        return type.getKind() == TypeKind.BOOLEAN
                || type instanceof DeclaredType declared && identity(declared).equals(JavacNames.BOOLEAN);
    }

    /**
     * The PIR type of a method's parameter. A varargs parameter's Java type is an array, so {@code byte...} would read as
     * {@code byte[]}: it is not one, and its element's type is what the subset rejects.
     */
    public PirType parameterType(VariableTree parameter) {
        var type = element(parameter).asType();
        if (element(parameter).getEnclosingElement() instanceof ExecutableElement method && method.isVarArgs()
                && method.getParameters().getLast().equals(element(parameter)) && type instanceof ArrayType array
                && array.getComponentType().getKind() == TypeKind.BYTE) {
            return pirType(array.getComponentType(), parameter.getType());
        }
        return pirType(type, parameter.getType());
    }

    /** A rejection at {@code where}. */
    public CompilerException rejection(String message, Tree where) {
        if (where == null) return new CompilerException(message);
        var at = location(where);
        return new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, message,
                at.fileName(), at.line(), at.column())));
    }

    /** {@link #rejection(String, Tree)}, with the reference's suggestion. */
    public CompilerException rejection(String message, String suggestion, Tree where) {
        var at = location(where);
        return new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, message,
                at.fileName(), at.line(), at.column(), suggestion, null)));
    }

    /** The rejection the reference raises while generating, whose position and suggestion are in its text. */
    public CompilerException locatedRejection(String message, String suggestion, Tree where) {
        var at = location(where);
        return new CompilerException(at.fileName() + ":" + at.line() + ":" + at.column() + ": " + message + "\n  Hint: " + suggestion);
    }

    /** {@code e}, at {@code where} if it is one diagnostic with no position. */
    public CompilerException located(CompilerException e, Tree where) {
        if (e.diagnostics().size() != 1 || e.diagnostics().getFirst().line() > 0) return e;
        var diagnostic = e.diagnostics().getFirst();
        var at = location(where);
        return new CompilerException(diagnostic.message(), new CompilerDiagnostic(diagnostic.level(), diagnostic.message(),
                at.fileName(), at.line(), at.column(), diagnostic.suggestion(), diagnostic.code()));
    }

    /** A coded rejection at {@code where}, with the catalogue's message and fix. */
    public CompilerException rejection(DiagnosticInfo info, Tree where, Object... arguments) {
        var at = location(where);
        return new CompilerException(List.of(new CompilerDiagnostic(info.level(), info.format(arguments),
                at.fileName(), at.line(), at.column(), info.fix(), info.code())));
    }

    private PirType array(ArrayType type, Tree where) {
        if (type.getComponentType().getKind() == TypeKind.BYTE) return new PirType.ByteStringType();
        throw rejection("Arrays are not supported except byte[]. Use List<T> instead. Got: " + type, where);
    }

    private PirType declared(DeclaredType type, Tree where, boolean inferred) {
        var element = (TypeElement) type.asElement();
        String identity = identity(type);
        var arguments = type.getTypeArguments();
        String simple = element.getSimpleName().toString();
        if (inferred && (identity.equals(JavacNames.BOXED_INTEGER) || identity.equals(JavacNames.BOXED_LONG))) {
            return new PirType.IntegerType();
        }
        return switch (identity) {
            case JavacNames.BIG_INTEGER -> new PirType.IntegerType();
            case "java.lang.String" -> new PirType.StringType();
            case "java.lang.Boolean" -> new PirType.BoolType();
            case JavacNames.PLUTUS_DATA, JavacNames.PLUTUS_DATA + ".ConstrData", JavacNames.PLUTUS_DATA + ".MapData", JavacNames.PLUTUS_DATA + ".ListData",
                 JavacNames.PLUTUS_DATA + ".IntData", JavacNames.PLUTUS_DATA + ".BytesData" -> new PirType.DataType();
            case JavacNames.TYPES + "JulcValue" -> new PirType.NativeValueType();
            case JavacNames.TYPES + "JulcG1" -> new PirType.NativeG1Type();
            case JavacNames.TYPES + "JulcG2" -> new PirType.NativeG2Type();
            case JavacNames.TYPES + "JulcMlResult" -> new PirType.NativeMlResultType();
            case JavacNames.TYPES + "JulcScalars" -> new PirType.NativeListType(new PirType.IntegerType());
            case JavacNames.TYPES + "JulcG1Points" -> new PirType.NativeListType(new PirType.NativeG1Type());
            case JavacNames.TYPES + "JulcG2Points" -> new PirType.NativeListType(new PirType.NativeG2Type());
            case "java.util.List", JavacNames.TYPES + "JulcList" -> new PirType.ListType(argument(arguments, 0, simple, where, inferred));
            case JavacNames.TYPES + "JulcArray" -> new PirType.ArrayType(argument(arguments, 0, simple, where, inferred));
            case "java.util.Map", JavacNames.TYPES + "JulcMap" -> new PirType.MapType(argument(arguments, 0, simple, where, inferred),
                    argument(arguments, 1, simple, where, inferred));
            case JavacNames.TUPLE2 -> new PirType.RecordType("Tuple2", List.of(
                    new PirType.Field("first", argument(arguments, 0, simple, where, inferred)),
                    new PirType.Field("second", argument(arguments, 1, simple, where, inferred))));
            case JavacNames.TUPLE3 -> new PirType.RecordType("Tuple3", List.of(
                    new PirType.Field("first", argument(arguments, 0, simple, where, inferred)),
                    new PirType.Field("second", argument(arguments, 1, simple, where, inferred)),
                    new PirType.Field("third", argument(arguments, 2, simple, where, inferred))));
            case JavacNames.TYPES + "AssetEntry" -> new PirType.RecordType("AssetEntry", List.of(
                    new PirType.Field("policyId", new PirType.ByteStringType()),
                    new PirType.Field("tokenName", new PirType.ByteStringType()),
                    new PirType.Field("amount", new PirType.IntegerType())));
            case "java.util.Optional" -> new PirType.OptionalType(argument(arguments, 0, simple, where, inferred));
            default -> registry.lookupNominal(identity)
                    .orElseThrow(() -> rejection(registry.unknownTypeMessage(simple, identity), where));
        };
    }

    /**
     * A Data-encoded container's type argument, or Data when raw: {@code JULC0091} if it is a native array. An inferred
     * type was written nowhere, so that rule is for where the value is Data-encoded, which reports it, and a type JuLC has
     * no representation for ({@code JulcList.of(1, "a")} is a {@code JulcList<Serializable>}) is Data, as a raw container's
     * is: every element of a list is Data-encoded by its own type.
     */
    private PirType argument(List<? extends TypeMirror> arguments, int index, String container, Tree where, boolean inferred) {
        if (arguments.isEmpty()) return new PirType.DataType();
        Tree written = typeArgument(where, index);
        if (inferred) {
            try {
                return pirType(arguments.get(index), written, true);
            } catch (CompilerException e) {
                return new PirType.DataType();
            }
        }
        PirType type = pirType(arguments.get(index), written);
        var array = PirType.nativeArrayIn(type);
        if (array != null) {
            throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable("A type argument of " + container, array,
                    written == null ? null : location(written));
        }
        return type;
    }

    /** The tree that writes type argument {@code index} of the type {@code where} writes: a rejection anchors there (I2a). */
    private static Tree typeArgument(Tree where, int index) {
        Tree written = where instanceof VariableTree variable ? variable.getType() : where;
        return written instanceof ParameterizedTypeTree type && index < type.getTypeArguments().size()
                ? type.getTypeArguments().get(index) : where;
    }

    /**
     * The source position of {@code tree}: line from javac's {@link LineMap}, column a character offset (I7), and the
     * fragment of the source it spans. The fragment is the one provenance rule (ADR-068 D13): the text as written, with
     * each run of white space one space, and past {@value #FRAGMENT_LIMIT} characters cut to 97 and {@code ...}, as the
     * JavaParser frontend cuts its own. A tree javac generated has no text, and so an empty fragment.
     */
    public SourceLocation location(Tree tree) {
        var unit = path(tree).getCompilationUnit();
        var positions = trees.getSourcePositions();
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        String file = fileName(unit);
        if (start < 0) return new SourceLocation(file, 0, 0, "");
        LineMap lines = unit.getLineMap();
        long line = lines.getLineNumber(start);
        return new SourceLocation(file, (int) line, (int) (start - lines.getStartPosition(line) + 1),
                end < 0 ? "" : fragment(unit, start, end));
    }

    private static String text(CompilationUnitTree unit) {
        try {
            return unit.getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fragment(CompilationUnitTree unit, long start, long end) {
        String text = written(unit, start, end);
        return text.length() > FRAGMENT_LIMIT ? text.substring(0, FRAGMENT_LIMIT - 3) + "..." : text;
    }

    private static String written(CompilationUnitTree unit, long start, long end) {
        return text(unit).substring((int) start, (int) end).strip().replaceAll("\\s+", " ");
    }

    /** The text of {@code tree} as written, with each run of white space one space: what a message quotes. */
    public String text(Tree tree) {
        return end(tree) < 0 ? tree.toString() : written(path(tree).getCompilationUnit(), start(tree), end(tree));
    }

    /** Whether a unit declares {@code element}. An API type comes in as a class file, so its members are not. */
    public boolean isDeclaredInSource(Element element) {
        return trees.getTree(element) != null;
    }

    /** Whether javac generated {@code tree}: it has no source text, as a default constructor has none. */
    public boolean isGenerated(Tree tree) {
        return end(tree) < 0;
    }

    /** Whether {@code declaration} writes no type: a {@code var}, or an inferred parameter of a lambda. */
    public boolean isInferred(VariableTree declaration) {
        return declaration.getType() == null || isGenerated(declaration.getType());
    }

    /**
     * The first {@code count} characters of {@code tree}'s source as javac reads them: a Unicode escape is the character it
     * denotes, and a backslash begins one only after an even number of backslashes (JLS 3.3). What a token is spelled with
     * can only be read here; javac's trees keep its value.
     */
    public String leadingSource(Tree tree, int count) {
        String text = text(path(tree).getCompilationUnit());
        var read = new StringBuilder();
        int backslashes = 0;
        try {
            for (int at = (int) start(tree); read.length() < count && at < text.length(); at++) {
                char c = text.charAt(at);
                if (c == '\\' && backslashes % 2 == 0 && at + 1 < text.length() && text.charAt(at + 1) == 'u') {
                    at++;
                    while (text.charAt(at + 1) == 'u') at++;
                    c = (char) Integer.parseInt(text.substring(at + 1, at + 5), 16);
                    at += 4;
                    backslashes = 0;
                } else {
                    backslashes = c == '\\' ? backslashes + 1 : 0;
                }
                read.append(c);
            }
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw rejection("Cannot read the source of this literal: " + e.getMessage(), tree);
        }
        return read.toString();
    }

    /** The start offset of {@code tree} in its unit, or -1. */
    long start(Tree tree) {
        return trees.getSourcePositions().getStartPosition(path(tree).getCompilationUnit(), tree);
    }

    /** The end offset of {@code tree} in its unit, or -1 for a tree javac generated. */
    long end(Tree tree) {
        return trees.getSourcePositions().getEndPosition(path(tree).getCompilationUnit(), tree);
    }

    private static String fileName(CompilationUnitTree unit) {
        var source = unit.getSourceFile();
        String name = source instanceof JavacSourceFile file ? file.displayName() : source.getName();
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
