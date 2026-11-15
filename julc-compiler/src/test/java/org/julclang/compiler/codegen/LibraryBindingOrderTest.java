package org.julclang.compiler.codegen;

import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-075 (issue #328): the order {@link LibraryBindingOrder} binds library methods in is a function of the methods
 * alone. It does not depend on the order they are given in, on their classes' names or packages, or on the names of
 * their locals; and every method is bound inside the methods it calls.
 */
class LibraryBindingOrderTest {

    private static final PirType INT = new PirType.IntegerType();
    private static final PirType FUN = new PirType.FunType(INT, INT);

    /** A method of {@code owner} that adds {@code constant} to its argument and passes it to each of {@code calls}. */
    private static LibraryMethod method(String owner, String name, int constant, List<String> calls, String local) {
        PirTerm body = new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                new PirTerm.Var(local, INT)), new PirTerm.Const(Constant.integer(BigInteger.valueOf(constant))));
        for (String call : calls) body = new PirTerm.App(new PirTerm.Var(call, FUN), body);
        return new LibraryMethod(owner, name, FUN, new PirTerm.Lam(local, INT, body));
    }

    /** A random set of methods whose calls form a DAG, with some identical bodies and some self-calls. */
    private static List<LibraryMethod> randomLibrary(Random random, String packageName, String localName) {
        int count = 2 + random.nextInt(12);
        var names = new ArrayList<String>();
        for (int i = 0; i < count; i++) names.add(packageName + ".C" + (i % 3) + ".m" + i);
        var methods = new ArrayList<LibraryMethod>();
        for (int i = 0; i < count; i++) {
            var calls = new ArrayList<String>();
            for (int j = i + 1; j < count; j++) if (random.nextInt(3) == 0) calls.add(names.get(j));
            if (random.nextInt(8) == 0) calls.add(names.get(i)); // a recursive method
            String qualified = names.get(i);
            int dot = qualified.lastIndexOf('.');
            methods.add(method(qualified.substring(0, dot), qualified.substring(dot + 1), random.nextInt(3), calls, localName));
        }
        return methods;
    }

    /** The order as positions of the methods' indexes, which do not mention names. */
    private static List<Integer> positions(List<LibraryMethod> order, List<LibraryMethod> methods) {
        var index = new HashMap<String, Integer>();
        for (int i = 0; i < methods.size(); i++) index.put(methods.get(i).qualifiedName(), i);
        return order.stream().map(m -> index.get(m.qualifiedName())).toList();
    }

    @Test
    void theOrderDoesNotDependOnTheOrderTheMethodsAreGivenIn() {
        var random = new Random(328);
        for (int trial = 0; trial < 500; trial++) {
            var methods = randomLibrary(random, "com.lib", "x");
            var expected = LibraryBindingOrder.innermostFirst(methods);
            for (int shuffle = 0; shuffle < 5; shuffle++) {
                var given = new ArrayList<>(methods);
                Collections.shuffle(given, random);
                assertEquals(expected, LibraryBindingOrder.innermostFirst(given), "trial " + trial);
            }
        }
    }

    @Test
    void everyMethodIsBoundInsideTheMethodsItCalls() {
        var random = new Random(3280);
        for (int trial = 0; trial < 500; trial++) {
            var methods = randomLibrary(random, "com.lib", "x");
            var order = LibraryBindingOrder.innermostFirst(methods);
            assertEquals(methods.size(), order.size());
            Map<String, Integer> position = new HashMap<>();
            for (int i = 0; i < order.size(); i++) position.put(order.get(i).qualifiedName(), i);
            for (var method : methods) {
                for (var other : methods) {
                    if (other == method) continue;
                    if (PirHelpers.containsVarRef(method.body(), other.qualifiedName())) {
                        // Innermost first: a dependency comes after (outside) the method that calls it.
                        assertTrue(position.get(other.qualifiedName()) > position.get(method.qualifiedName()),
                                method.qualifiedName() + " calls " + other.qualifiedName());
                    }
                }
            }
        }
    }

    @Test
    void movingOrRenamingTheClassesAndTheirLocalsKeepsTheOrder() {
        for (long seed = 0; seed < 300; seed++) {
            var methods = randomLibrary(new Random(seed), "com.lib", "x");
            var moved = randomLibrary(new Random(seed), "zzz.other.place", "renamedLocal");
            var expected = positions(LibraryBindingOrder.innermostFirst(methods), methods);
            var actual = positions(LibraryBindingOrder.innermostFirst(moved), moved);
            // Equal bodies are told apart by name, so only libraries whose bodies all differ must keep their order.
            if (distinctBodies(methods)) assertEquals(expected, actual, "seed " + seed);
        }
    }

    private static boolean distinctBodies(List<LibraryMethod> methods) {
        // Two methods with the same constant and the same calls have the same content key.
        var seen = new HashSet<String>();
        for (var method : methods) {
            var body = method.body().toString().replace(method.className(), "");
            for (var other : methods) body = body.replace(other.qualifiedName(), "#");
            if (!seen.add(body)) return false;
        }
        return true;
    }

    @Test
    void methodsThatCallEachOtherAreBoundInACanonicalOrder() {
        var a = method("p.A", "a", 1, List.of("p.B.b"), "x");
        var b = method("p.B", "b", 2, List.of("p.A.a"), "x");
        var c = method("p.C", "c", 3, List.of("p.A.a"), "x");
        var expected = LibraryBindingOrder.innermostFirst(List.of(a, b, c));
        assertEquals(expected, LibraryBindingOrder.innermostFirst(List.of(c, b, a)));
        assertEquals(expected, LibraryBindingOrder.innermostFirst(List.of(b, c, a)));
        assertEquals(3, expected.size());
    }
}
