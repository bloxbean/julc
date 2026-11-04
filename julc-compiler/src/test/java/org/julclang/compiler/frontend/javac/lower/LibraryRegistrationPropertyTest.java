package org.julclang.compiler.frontend.javac.lower;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-068 D8: the reference's registration, passes and final pass included, against the javac frontend's, over libraries of
 * two and three units whose methods call each other by class name, in every pool order. Each method calls at most one method
 * later in a random order of all of them, so no call recurses, and units may still call each other both ways. Where the
 * reference accepts, the bytes are its own at two levels and the value is the program's; where it rejects, so does javac,
 * with its message.
 */
class LibraryRegistrationPropertyTest {

    private static final String[] UNITS = {"A", "B", "C"};
    private static final int METHODS = 2;

    /** A method: what it adds, and the method it calls by class name, or -1. Methods are numbered unit * 2 + method. */
    record Body(int add, int callee) {
    }

    @Provide
    Arbitrary<List<Body>> graphs() {
        return Arbitraries.integers().between(0, 5 * 5 * 5 * 5 * 5 * 5 - 1).flatMap(seed -> Arbitraries.shuffle(0, 1, 2, 3, 4, 5)
                .map(order -> {
                    var bodies = new Body[UNITS.length * METHODS];
                    int s = seed;
                    for (int i = 0; i < order.size(); i++) {
                        int method = order.get(i);
                        int pick = s % 5;
                        s /= 5;
                        int later = order.size() - 1 - i;
                        int callee = pick < 2 || later == 0 ? -1 : order.get(i + 1 + (pick % later));
                        bodies[method] = new Body(method + 1, callee);
                    }
                    return List.of(bodies);
                }));
    }

    private static String unit(int u, List<Body> bodies) {
        var text = new StringBuilder("package com.lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n"
                + "@OnchainLibrary\npublic class " + UNITS[u] + " {\n");
        for (int m = 0; m < METHODS; m++) {
            var body = bodies.get(u * METHODS + m);
            String call = body.callee() < 0 ? "x" : UNITS[body.callee() / METHODS] + ".m" + body.callee() % METHODS + "(x)";
            text.append("    public static BigInteger m").append(m).append("(BigInteger x) { return ").append(call)
                    .append(".add(BigInteger.valueOf(").append(body.add()).append(")); }\n");
        }
        return text.append("}\n").toString();
    }

    private static long value(int method, long x, List<Body> bodies) {
        var body = bodies.get(method);
        return (body.callee() < 0 ? x : value(body.callee(), x, bodies)) + body.add();
    }

    private static String root(int units) {
        var text = new StringBuilder("import java.math.BigInteger;\n");
        for (int u = 0; u < units; u++) text.append("import com.lib.").append(UNITS[u]).append(";\n");
        text.append("class M { static BigInteger f(BigInteger a) { return a");
        for (int u = 0; u < units; u++) text.append(".add(").append(UNITS[u]).append(".m0(a))");
        return text.append("; } }\n").toString();
    }

    private static List<List<Integer>> permutations(List<Integer> items) {
        if (items.size() <= 1) return List.of(items);
        var result = new ArrayList<List<Integer>>();
        for (int i = 0; i < items.size(); i++) {
            var rest = new ArrayList<>(items);
            int first = rest.remove(i);
            for (var tail : permutations(rest)) {
                var order = new ArrayList<Integer>();
                order.add(first);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }

    @Property(tries = 25)
    void everyPoolOrderIsTheReferences(@ForAll("graphs") List<Body> bodies) {
        for (int units = 2; units <= 3; units++) {
            int count = units;
            // Two units: methods of the third are not called, so their calls are dropped for this program.
            var used = bodies.stream().map(body -> body.callee() >= count * METHODS ? new Body(body.add(), -1) : body).toList();
            var source = root(units);
            for (var order : permutations(new ArrayList<>(Arrays.asList(0, 1, 2).subList(0, units)))) {
                var pool = order.stream().map(u -> unit(u, used)).toList();
                compare(source, pool, used, units);
            }
        }
    }

    private static void compare(String source, List<String> pool, List<Body> bodies, int units) {
        byte[] expected = null;
        String rejected = null;
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.PV11_SAFE)) {
            try {
                var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAPARSER).setJavacGate(JavacGateMode.OFF)
                        .setOptimizationLevel(level)).compileMethod(source, "f", pool).program();
                expected = UplcFlatEncoder.encodeProgram(program);
            } catch (CompilerException e) {
                rejected = e.getMessage();
            }
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level)
                    .setFrontend(CompilerFrontend.JAVAC));
            if (rejected != null) {
                String message = rejected;
                var failure = assertThrows(CompilerException.class,
                        () -> compiler.compileMethod(source, "f", pool), () -> "the reference rejects: " + message);
                assertEquals(rejected, failure.getMessage(), pool::toString);
                return;
            }
            var program = compiler.compileMethod(source, "f", pool).program();
            assertArrayEquals(expected, UplcFlatEncoder.encodeProgram(program), () -> level + " " + pool);
            long x = 5;
            long java = x;
            for (int u = 0; u < units; u++) java += value(u * METHODS, x, bodies);
            var result = JulcVm.create().evaluateWithArgs(program, List.of(PlutusData.integer(x)));
            assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(java))),
                    assertInstanceOf(EvalResult.Success.class, result).resultTerm(), pool::toString);
        }
    }
}
