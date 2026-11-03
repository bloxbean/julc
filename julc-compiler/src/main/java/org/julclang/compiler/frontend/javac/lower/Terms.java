package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.codegen.StrictBoundaryGenerator;
import org.julclang.compiler.debug.DebugMetadataCollector;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.lower.Scope.Binder;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * The only code that builds a {@code Var}, {@code Lam} or {@code Let} for a source element (ADR-068 I13), and the only recorder of
 * source positions (I7). Both go in side tables keyed by term identity, written while the term is built: {@link PirTerm}
 * carries neither, and nothing is renamed or located afterwards.
 */
public final class Terms {

    private final JavacFacts facts;
    private final boolean sourceMaps;
    /** Whether a term's position is recorded now: not in a library, whose positions the reference keeps nowhere. */
    private boolean locating = true;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Provenance provenance = new Provenance();
    private DebugMetadataCollector debug;
    private StrictBoundaryGenerator shapes;

    Terms(JavacFacts facts, CompilationContext context) {
        this.facts = facts;
        this.sourceMaps = context.isSourceMapEnabled();
    }

    /**
     * A read of {@code binder}, for {@code element}: what javac bound the name to, not what the scope answered, so I13's
     * check compares two independent sources and a wrong lookup fails it.
     */
    PirTerm.Var var(Element element, Binder binder) {
        var read = new PirTerm.Var(binder.name(), binder.type());
        provenance.read(read, element);
        return read;
    }

    /**
     * A read of a static method's binder, for {@code element}. Unlike a variable's, the binder's name is a function of the
     * method's element, with a dot no variable can have, so no scope can capture it (ADR-060), and an overload is
     * {@code JULC0054} unless it is interchangeable.
     */
    PirTerm.Var method(ExecutableElement element, String binder, PirType type) {
        var read = new PirTerm.Var(binder, type);
        provenance.read(read, facts.binderMethod(element));
        return read;
    }

    PirTerm.Lam lam(Binder binder, PirTerm body) {
        var lambda = new PirTerm.Lam(binder.name(), binder.type(), body);
        provenance.binder(lambda, binder.name(), binder.element());
        return lambda;
    }

    /** {@code let binder = value in body}, a local's or a pattern variable's. */
    PirTerm.Let let(Binder binder, PirTerm value, PirTerm body) {
        var binding = new PirTerm.Let(binder.name(), value, body);
        provenance.binder(binding, binder.name(), binder.element());
        return binding;
    }

    /**
     * {@code let binder = decoded(data) in body}: a value that came as Data, read under one name by what follows, which
     * shadows the binder that read it (ADR-060).
     *
     * @param type what {@code data} holds, once decoded
     */
    PirTerm.Let decoded(Element element, Binder data, Binder binder, PirType type, PirTerm body) {
        return let(binder, PirHelpers.wrapDecode(var(element, data), type), body);
    }

    /**
     * An {@code if} that ran for its effect, then {@code rest}. {@code rest} holds the terms of whatever follows, a
     * builder's among them, so the name is chosen to miss what they read (ADR-060).
     */
    PirTerm.Let afterEffect(PirTerm effect, PirTerm rest) {
        return new PirTerm.Let(PirHelpers.hygienicName("#_if", PirHelpers.freeVariables(rest)), effect, rest);
    }

    /**
     * {@code let #fromData = data in if check #fromData then #fromData decoded as type else error}: Data of exactly
     * {@code type}'s shape, as {@code type} holds it. The check is the program's one dispatcher, bound by
     * {@link #bindShapeChecks}. Nothing in the {@code let}'s scope is built elsewhere, so the name is fixed (ADR-060).
     */
    PirTerm.Let checkedDecode(PirTerm data, PirType type) {
        if (shapes == null) shapes = facts.shapeChecks();
        var read = generated("#fromData", new PirType.DataType());
        return generatedLet("#fromData", data,
                new PirTerm.IfThenElse(shapes.checkShared(read, type), PirHelpers.wrapDecode(read, type), new PirTerm.Error(type)));
    }

    /** {@code program} under the one dispatcher of its shape checks, if it has any. */
    public PirTerm bindShapeChecks(PirTerm program) {
        return shapes == null ? program : shapes.bindShared(program);
    }

    /** What {@code data} decodes to as {@code type}, for a type whose decode reads all of it. */
    PirTerm decode(PirTerm data, PirType type) {
        return PirHelpers.wrapDecode(data, type);
    }

    /** {@code let binder = what data decodes to in body}: an accumulator out of the tuple it travels in. */
    PirTerm.Let unpacked(Binder binder, PirTerm data, PirTerm body) {
        return let(binder, PirHelpers.wrapDecode(data, binder.type()), body);
    }

    /** Rejects a type that has no decoding from Data, as a loop's element would when {@code LoopDesugarer} builds it. */
    void requireDecodes(PirType type) {
        PirHelpers.wrapDecode(new PirTerm.Const(Constant.unit()), type);
    }

    /** {@code let x = x in rest}, for the variable {@code read} reads: one more binder of what that read was built for. */
    PirTerm.Let rebound(PirTerm.Var read, PirTerm rest) {
        var value = new PirTerm.Var(read.name(), read.type());
        var binding = new PirTerm.Let(read.name(), value, rest);
        var element = provenance.readOf(read);
        if (element != null) {
            provenance.read(value, element);
            provenance.binder(binding, read.name(), element);
        }
        return binding;
    }

    /**
     * Tells I13's table what a loop built by {@code LoopDesugarer} binds: its accumulator, the read of it the loop ends with,
     * and a for-each's variable. The desugarer takes these by name, so each is found where it builds them.
     *
     * @param accumulator the loop's accumulator, or null if it carries a generated one
     * @param item        a for-each's variable, or null for a {@code while}
     */
    void loop(PirTerm.LetRec loop, Binder accumulator, Binder item) {
        var function = (PirTerm.Lam) loop.bindings().getFirst().value();
        if (item != null) {
            function = (PirTerm.Lam) function.body();
            var variable = itemLet(((PirTerm.IfThenElse) function.body()).elseBranch(), item.name());
            provenance.binder(variable, item.name(), item.element());
        }
        if (accumulator == null) return;
        provenance.binder(function, accumulator.name(), accumulator.element());
        var test = (PirTerm.IfThenElse) function.body();
        provenance.read((PirTerm.Var) (item != null ? test.thenBranch() : test.elseBranch()), accumulator.element());
    }

    private static PirTerm.Let itemLet(PirTerm term, String item) {
        return switch (term) {
            case PirTerm.Let let when let.name().equals(item) -> let;
            case PirTerm.App call -> itemLet(call.argument(), item);
            case PirTerm.ListMatch match -> itemLet(match.consBranch(), item);
            default -> throw new IllegalStateException("No binder of " + item + " in a loop: " + term.getClass().getSimpleName());
        };
    }

    /** A branch of a {@code DataMatch} that binds {@code bindings} and, to the scrutinee, the pattern variable of {@code binder}. */
    PirTerm.MatchBranch branch(String constructor, List<String> bindings, List<PirType> types, PirTerm body, Binder binder) {
        var branch = new PirTerm.MatchBranch(constructor, bindings, types, body, binder.name());
        provenance.binder(branch, binder.name(), binder.element());
        return branch;
    }

    /** A branch that binds no name, with the field types a {@code default} placed in it leaves undecoded. */
    PirTerm.MatchBranch generatedBranch(String constructor, List<PirType> types, PirTerm body) {
        return new PirTerm.MatchBranch(constructor, List.of(), types, body);
    }

    /** A branch that binds {@code bindings}, and {@code scrutinee} to the value matched: each a {@code #} name. */
    PirTerm.MatchBranch guardedBranch(String constructor, List<String> bindings, List<PirType> types, PirTerm body, String scrutinee) {
        return new PirTerm.MatchBranch(constructor, bindings, types, body, generatedName(scrutinee));
    }

    /** A read of a binder the lowering generated, which no source name can be (ADR-060). */
    PirTerm.Var generated(String name, PirType type) {
        return new PirTerm.Var(generatedName(name), type);
    }

    /** {@code let name = value in body}, for a generated name. */
    PirTerm.Let generatedLet(String name, PirTerm value, PirTerm body) {
        return new PirTerm.Let(generatedName(name), value, body);
    }

    /** A lambda over a generated name. */
    PirTerm.Lam generatedLam(String name, PirType type, PirTerm body) {
        return new PirTerm.Lam(generatedName(name), type, body);
    }

    private static String generatedName(String name) {
        if (!PirHelpers.isGeneratedName(name)) throw new IllegalArgumentException("Not a generated name: " + name);
        return name;
    }

    /** A binder the assembly built for {@code element}: a static field's, a {@code @Param}'s or a method's. */
    public void assembled(PirTerm binding, String name, Element element) {
        provenance.binder(binding, name, element);
    }

    /** Debug metadata this compilation collects ({@code compileForDebug}), told of binders as they are built. */
    public void collectDebugMetadata(DebugMetadataCollector collector) {
        this.debug = collector;
    }

    /**
     * Tells debug metadata, if collected, that {@code binding} is the binder built for the declaration of {@code element},
     * holding a {@code type}. Called where the reference associates one: a method's and a lambda's parameter, a local, a
     * {@code @Param}. A binder I13 marked ({@code name'k}) is still the declaration's, and is shown under its source name.
     */
    public void declared(Element element, PirTerm binding, PirType type) {
        if (debug != null) debug.associate(element, binding, type);
    }

    /** Tells debug metadata, if collected, that the declaration of {@code element} has no binder it can show. */
    void undeclared(Element element, String reason) {
        if (debug != null) debug.markUnsupported(element, reason);
    }

    /** Records where {@code term} came from, when this compilation has a source map. */
    <T extends PirTerm> T at(Tree tree, T term) {
        if (sourceMaps && locating) positions.put(term, facts.location(tree));
        return term;
    }

    /**
     * {@code lowering}, with no position recorded: the reference lowers a library with a generator of its own, whose source
     * map it drops, so a library's terms are in no map.
     */
    public <T> T unlocated(Supplier<T> lowering) {
        boolean was = locating;
        locating = false;
        try {
            return lowering.get();
        } finally {
            locating = was;
        }
    }

    /** The PIR term to Java source location map the backend takes. */
    public IdentityHashMap<PirTerm, SourceLocation> positions() {
        return positions;
    }

    /** I13's construction-time provenance. */
    public Provenance provenance() {
        return provenance;
    }
}
