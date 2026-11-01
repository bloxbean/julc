package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.LedgerLayouts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Object creation and member reads (ADR-068 I11, I12). What {@code new} builds is decided by the {@link TypeElement} of the
 * constructor javac bound: a variant's tag, field types and encoding are that element's, and a simple name decides nothing,
 * so two variants of one simple name each have their own tag.
 * <p>
 * <b>Evaluation order.</b> Each argument once, from left to right, and then the value: a {@code DataConstr} applies
 * {@code MkCons} to its fields in order, so a failing argument stops the ones after it, and the fields are encoded where
 * they stand. An identity ({@code @NewType}, a ledger hash) is its argument. {@code x.length} evaluates {@code x} once.
 * <p>
 * <b>Generation order</b> is the reference's, for I2a: the check that a value has no native part goes before its arguments.
 * The reference reads a record through an accessor, so {@code r.a} is {@code JULC0055}; {@link CallTargets} lowers
 * {@code r.a()}, and a read on {@code new R(x)} itself stays rejected there (I2).
 */
final class Records {

    /** The reference takes these two qualifiers for the API's by their spelling, so a type of the name elsewhere is not its rejection. */
    private static final Set<String> API_CONSTANT_OWNERS = Set.of("BigInteger", "PlutusData");

    private final JavacFacts facts;
    private final Typing typing;
    private final Lowering lowering;
    private final Names names;
    private final Tuples tuples;

    Records(JavacFacts facts, Typing typing, Lowering lowering, Names names) {
        this.facts = facts;
        this.typing = typing;
        this.lowering = lowering;
        this.names = names;
        this.tuples = new Tuples(facts, typing);
    }

    PirTerm creation(NewClassTree tree, Frame frame) {
        var constructor = (ExecutableElement) facts.element(tree);
        var type = (TypeElement) constructor.getEnclosingElement();
        boolean record = type.getKind() == ElementKind.RECORD;
        if (record && !isCanonical(constructor, type)) throw NotPorted.at(facts, tree, "a constructor that is not canonical");
        String identity = facts.identity(type);
        var nominal = facts.registry().lookupNominal(facts.identity(type)).orElse(null);
        var arguments = tree.getArguments();
        if (record && facts.hasAnnotation(type, JavacNames.NEW_TYPE)) {
            if (!sealedParents(type).isEmpty()) throw NotPorted.at(facts, tree, "a new of a @NewType that is a variant");
            return lowering.expr(arguments.getFirst(), frame);
        }
        if (nominal instanceof PirType.ByteStringType && arguments.size() == 1) {
            return lowering.expr(arguments.getFirst(), frame);
        }
        if (identity.equals(JavacNames.BIG_INTEGER) && arguments.size() == 1) return integer(tree);
        if (!sealedParents(type).isEmpty()) return variant(tree, type, frame);
        if (Tuples.isTuple(identity)) return tuple(tree, type, frame);
        if (LedgerLayouts.NOT_CONSTRUCTOR_DATA.contains(identity)) {
            // On the JVM its Data is a map; the reference builds a constructor, which every reader of a Value fails on.
            throw NotPorted.at(facts, tree, "a new of a ledger type whose Data is no constructor");
        }
        if (record && nominal instanceof PirType.RecordType recordType) {
            return constructed(tree, type, recordType, 0, frame);
        }
        String name = name(type);
        throw facts.locatedRejection("Cannot construct non-record type: " + name,
                "Only record types can be constructed on-chain. Define " + name + " as a record.", tree);
    }

    /** Whether {@code constructor} takes {@code record}'s components. {@code Elements} answers this only from a source. */
    private boolean isCanonical(ExecutableElement constructor, TypeElement record) {
        var components = facts.components(record);
        var parameters = constructor.getParameters();
        if (components.size() != parameters.size()) return false;
        var types = facts.types();
        for (int i = 0; i < components.size(); i++) {
            if (!types.isSameType(types.erasure(components.get(i).asType()), types.erasure(parameters.get(i).asType()))) return false;
        }
        return true;
    }

    private static String name(TypeElement type) {
        return type.getSimpleName().toString();
    }

    /** {@code new BigInteger("12345")}, a constant; a text block is no string literal to the reference. */
    private PirTerm integer(NewClassTree tree) {
        var argument = tree.getArguments().getFirst();
        if (!(argument instanceof LiteralTree literal && literal.getKind() == Tree.Kind.STRING_LITERAL
                && !facts.leadingSource(literal, 3).equals("\"\"\""))) {
            throw facts.locatedRejection("new BigInteger() requires a string literal argument",
                    "Use new BigInteger(\"12345\") or BigInteger.valueOf(n) for integer constants.", tree);
        }
        try {
            return new PirTerm.Const(Constant.integer(new BigInteger((String) literal.getValue())));
        } catch (NumberFormatException e) {
            throw NotPorted.at(facts, tree, "a new BigInteger of a string that is no integer");
        }
    }

    /** The tag is the variant's place in what javac says the interface permits, which is the order the registry took. */
    private PirTerm variant(NewClassTree tree, TypeElement variant, Frame frame) {
        if (variant.getKind() != ElementKind.RECORD) throw NotPorted.at(facts, tree, "a new of a variant that is not a record");
        var parents = sealedParents(variant);
        if (parents.size() > 1) throw NotPorted.at(facts, tree, "a new of a variant of two sealed interfaces");
        var parent = parents.getFirst();
        var sum = (PirType.SumType) facts.registry().lookupNominal(facts.identity(parent)).orElseThrow();
        return constructed(tree, variant, sum, facts.permitted(parent).indexOf(variant), frame);
    }

    /** The sealed interfaces of {@code variant} the registry holds as sums, in declaration order. */
    private List<TypeElement> sealedParents(TypeElement variant) {
        var parents = new ArrayList<TypeElement>();
        for (var type : variant.getInterfaces()) {
            var parent = facts.typeElement(type);
            if (parent.getKind() == ElementKind.INTERFACE && parent.getModifiers().contains(Modifier.SEALED)
                    && facts.registry().lookupNominal(facts.identity(parent)).orElse(null) instanceof PirType.SumType) {
                parents.add(parent);
            }
        }
        return parents;
    }

    private PirTerm tuple(NewClassTree tree, TypeElement type, Frame frame) {
        String name = name(type);
        var written = tuples.written(tree);
        requireNoNativePart(name, written, tree);
        var fields = fields(name, tree, frame);
        var tuple = tuples.type(name, written, tree, fields, frame);
        requireNoNativePart(name, tuple, tree);
        requireEncodable(tree, tuple.fields(), fields);
        return typing.keep(new PirTerm.DataConstr(0, tuple, fields), tuple);
    }

    private PirTerm constructed(NewClassTree tree, TypeElement type, PirType dataType, int tag, Frame frame) {
        String name = name(type);
        requireNoNativePart(name, dataType, tree);
        var fields = fields(name, tree, frame);
        requireEncodable(tree, dataType instanceof PirType.SumType sum ? sum.constructors().get(tag).fields()
                : ((PirType.RecordType) dataType).fields(), fields);
        return new PirTerm.DataConstr(tag, dataType, fields);
    }

    /**
     * A component of type Data is not encoded, and {@code Builtins.tailList} and {@code unListData} leave a list where Java
     * has a {@code ListData}: the reference puts it in the field as it is, which fails where Java goes on.
     */
    private void requireEncodable(NewClassTree tree, List<PirType.Field> components, List<PirTerm> fields) {
        for (int i = 0; i < fields.size(); i++) {
            var argument = tree.getArguments().get(i);
            if (components.get(i).type() instanceof PirType.DataType
                    && (typing.ofTerm(fields.get(i)) instanceof PirType.ListType || typing.ofTerm(fields.get(i)) instanceof PirType.MapType)) {
                throw NotPorted.at(facts, argument, "a Data component given a native list");
            }
        }
    }

    /** {@code JULC0091} for an argument holding a native array: the declared type does not show one under a diamond. */
    private List<PirTerm> fields(String name, NewClassTree tree, Frame frame) {
        var fields = new ArrayList<PirTerm>();
        for (var argument : tree.getArguments()) {
            var field = lowering.expr(argument, frame);
            var array = nativeArrayIn(argument, field);
            if (array != null) {
                var at = facts.location(argument);
                throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable("Argument '" + at.fragment() + "' of new " + name, array, at);
            }
            fields.add(field);
        }
        return fields;
    }

    private PirType.ArrayType nativeArrayIn(ExpressionTree argument, PirTerm field) {
        try {
            return PirType.nativeArrayIn(typing.of(argument, field));
        } catch (NotPorted e) {
            return null;
        }
    }

    private void requireNoNativePart(String name, PirType type, Tree tree) {
        if (facts.registry().containsNativeOpaque(type)) {
            throw CompilerTypeDiagnostics.nativeTypeMismatch("Data construction for '" + name + "'", type, new PirType.DataType(),
                    facts.location(tree));
        }
    }

    // --- Member reads ---

    /**
     * {@code x.name}: an API constant, or the length of a byte string. Whatever else, the qualifier is lowered first, as in
     * the reference, so a type or a package is {@code Undefined variable} and a variable's member is {@code JULC0055}.
     */
    PirTerm member(MemberSelectTree tree, Frame frame) {
        if (tree.getIdentifier().contentEquals("class")) throw NotPorted.at(facts, tree);
        var constant = names.constant(tree);
        if (constant != null) return constant;
        var expression = tree.getExpression();
        if (expression instanceof IdentifierTree qualifier
                && (facts.element(qualifier) instanceof TypeElement || facts.element(qualifier) instanceof PackageElement)) {
            if (API_CONSTANT_OWNERS.contains(qualifier.getName().toString())) throw NotPorted.at(facts, tree);
            throw facts.rejection("Undefined variable: " + qualifier.getName(), qualifier);
        }
        var scope = lowering.expr(expression, frame);
        if (facts.type(expression).getKind() == TypeKind.ARRAY && typing.of(expression, scope) instanceof PirType.ByteStringType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.LengthOfByteString), scope);
        }
        throw CompilerTypeDiagnostics.unresolvedMember(tree.getIdentifier().toString(), facts.location(tree));
    }
}
