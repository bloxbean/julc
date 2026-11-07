package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-070 D5 (review F9, F11) in the compiler alone: a list method's function that is not a lambda written at the call is
 * bound once, after the receiver, to a variable of a function type; a lambda written at the call is not bound. The harness
 * ({@code Adr068LambdasHarnessTest}) shows the order and the single evaluation against the JVM.
 */
class Adr070ChosenFunctionTest {

    private static final String SOURCE = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            class C {
                static JulcList<BigInteger> pick(JulcList<BigInteger> xs) { return xs; }
                %s
            }
            """;

    private static PirTerm pir(String members) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(OptimizationLevel.NONE))
                .compileMethod(SOURCE.formatted(members), "m").pirTerm();
    }

    /** Every subterm, depth first, the term itself first. */
    private static List<Object> subterms(Object term) {
        var found = new ArrayList<>();
        collect(term, found);
        return found;
    }

    private static void collect(Object node, List<Object> found) {
        if (node instanceof PirTerm || node instanceof PirTerm.Binding) {
            found.add(node);
            for (var component : node.getClass().getRecordComponents()) {
                try {
                    collect(component.getAccessor().invoke(node), found);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        } else if (node instanceof List<?> list) {
            list.forEach(item -> collect(item, found));
        }
    }

    private static List<PirTerm.Let> lets(PirTerm term, String base) {
        return subterms(term).stream().filter(t -> t instanceof PirTerm.Let let && let.name().startsWith(base)).map(PirTerm.Let.class::cast).toList();
    }

    @Test
    void aChosenFunctionIsBoundAfterTheReceiverToAFunctionTypedVariable() {
        for (String method : List.of("any", "all", "filter", "find", "map")) {
            String function = method.equals("map") ? "c ? x -> x.add(BigInteger.ONE) : x -> x" : "c ? x -> x.signum() > 0 : x -> false";
            String call = "pick(xs)." + method + "(" + function + ")";
            String body = switch (method) {
                case "any", "all" -> "static boolean m(JulcList<BigInteger> xs, boolean c) { return " + call + "; }";
                case "find" -> "static boolean m(JulcList<BigInteger> xs, boolean c) { BigInteger r = " + call + "; return r.signum() > 0; }";
                default -> "static long m(JulcList<BigInteger> xs, boolean c) { return " + call + ".size(); }";
            };
            var term = pir(body);
            var receivers = lets(term, "#receiver");
            assertEquals(1, receivers.size(), method);
            var arguments = lets(receivers.getFirst().body(), "#argument");
            assertEquals(1, arguments.size(), method + ": the function is bound inside the receiver's binding");
            assertInstanceOf(PirTerm.IfThenElse.class, arguments.getFirst().value(), method);
            String name = arguments.getFirst().name();
            var reads = subterms(arguments.getFirst().body()).stream()
                    .filter(t -> t instanceof PirTerm.Var v && v.name().equals(name)).map(PirTerm.Var.class::cast).toList();
            assertFalse(reads.isEmpty(), method);
            for (var read : reads) {
                var type = assertInstanceOf(PirType.FunType.class, read.type(), method);
                assertEquals(method.equals("map") ? new PirType.DataType() : new PirType.BoolType(), type.returnType(), method);
            }
        }
    }

    @Test
    void aLambdaWrittenAtTheCallIsNotBound() {
        var term = pir("static boolean m(JulcList<BigInteger> xs) { return pick(xs).any(x -> x.signum() > 0); }");
        assertTrue(lets(term, "#argument").isEmpty());
        assertTrue(lets(term, "#receiver").isEmpty());
    }
}
