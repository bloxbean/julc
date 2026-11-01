package org.julclang.compiler.codegen;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the assembly tells a frontend of the binders it builds, so that I13's provenance reaches them. */
class ValidatorAssemblyFrontendTest {

    private static final PirType INT = new PirType.IntegerType();

    private static PirTerm one() {
        return new PirTerm.Const(Constant.integer(BigInteger.ONE));
    }

    private static PirTerm self(String name) {
        return new PirTerm.Lam("x", INT, new PirTerm.App(new PirTerm.Var(name, new PirType.FunType(INT, INT)), new PirTerm.Var("x", INT)));
    }

    /** Each method is told once with the term that binds it, and two binders on one node are told apart by name. */
    @Test
    void everyMethodBinderIsReported() {
        var told = new ArrayList<String>();
        var frontend = new ValidatorAssembly.Frontend(new IdentityHashMap<>(), null, null, term -> { }, null,
                (name, binding) -> told.add(name + " " + binding.getClass().getSimpleName()));
        var methods = List.of(
                new ValidatorAssembly.Method("P.m", one()),
                new ValidatorAssembly.Method("P.loop", self("P.loop")),
                new ValidatorAssembly.Method("P.even", self("P.odd")),
                new ValidatorAssembly.Method("P.odd", self("P.even")));
        ValidatorAssembly.method(new ValidatorAssembly.MethodProgram("P.m", INT, List.of(), methods, List.of(), List.of(), List.of()),
                CompilationContext.resolve(new CompilerOptions()), frontend);
        assertEquals(List.of("P.even LetRec", "P.loop LetRec", "P.m Let", "P.odd LetRec"), told.stream().sorted().toList());
    }
}
