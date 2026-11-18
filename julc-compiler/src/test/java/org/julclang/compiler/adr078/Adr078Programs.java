package org.julclang.compiler.adr078;

import org.julclang.compiler.adr078.Adr078Fixtures.Variant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * ADR-078 D5's generated programs and their rewrites, shared by {@code LibraryTieOrderPropertyTest} and the pre21
 * recorder ({@link #main}). A program is a model: library classes holding groups of methods with identical bodies
 * (equal content keys), callers of them, and a target that reaches some of them, so some twins are reached and some
 * are not. A rewrite renders the same model with fresh method and class names, other packages, and another order of
 * library sources and imports. Calls between classes go one way only, so every library-source order compiles (L-C4).
 * Twins are written identically, locals included, and nothing is folded, so the rewrites stay inside what ADR-078's
 * tie rule covers.
 */
public final class Adr078Programs {

    private Adr078Programs() {
    }

    /** ADR-078 D5: the base seed and the number of rewrites per program. */
    public static final long SEED = 78;
    public static final int REWRITES = 40;
    public static final int PROGRAMS = 3;

    private static final List<String> PACKAGES = List.of("p.one", "org.julclang.m", "zzz.y", "com.example.deep.pkg", "aaa.b");

    /** A method: its id, its class, the constant it adds, and the methods it calls (ids). */
    record Method(int id, int owner, int constant, List<Integer> calls) {
    }

    /** A program model: its classes' method ids, the methods, and the methods the target calls. */
    record Model(int classes, List<Method> methods, List<Integer> targetCalls) {
    }

    /** One rendering: a name for every method and class, a package for every class, and the order of sources. */
    record Naming(Map<Integer, String> methods, Map<Integer, String> classes, Map<Integer, String> packages,
                  List<Integer> sourceOrder, List<Integer> importOrder, String label) {
    }

    static Model model(Random random) {
        int classes = 2 + random.nextInt(2);
        var methods = new ArrayList<Method>();
        // Twin groups: two or three methods with the same body, in one class or across classes.
        int groups = 2 + random.nextInt(2);
        for (int g = 0; g < groups; g++) {
            int members = 2 + random.nextInt(2);
            int constant = 10 + g;
            for (int m = 0; m < members; m++) methods.add(new Method(methods.size(), random.nextInt(classes), constant, List.of()));
        }
        // Callers: each calls one or two methods of a class with a higher index, or of its own class declared before it.
        int callers = 2 + random.nextInt(3);
        for (int c = 0; c < callers; c++) {
            int owner = random.nextInt(classes);
            var calls = new ArrayList<Integer>();
            for (var candidate : List.copyOf(methods)) {
                if ((candidate.owner() > owner || candidate.owner() == owner) && random.nextInt(3) == 0 && calls.size() < 2) {
                    calls.add(candidate.id());
                }
            }
            methods.add(new Method(methods.size(), owner, 20 + c, calls));
        }
        // The target reaches some methods: twins and callers; the rest stay unread.
        var targetCalls = new ArrayList<Integer>();
        for (var method : methods) if (random.nextInt(2) == 0) targetCalls.add(method.id());
        if (targetCalls.isEmpty()) targetCalls.add(methods.getLast().id());
        return new Model(classes, methods, targetCalls);
    }

    static Naming baseNaming(Model model) {
        var methods = new LinkedHashMap<Integer, String>();
        for (var method : model.methods()) methods.put(method.id(), "m" + method.id());
        var classes = new LinkedHashMap<Integer, String>();
        var packages = new LinkedHashMap<Integer, String>();
        var order = new ArrayList<Integer>();
        for (int c = 0; c < model.classes(); c++) {
            classes.put(c, "Lib" + (char) ('A' + c));
            packages.put(c, "q");
            order.add(c);
        }
        return new Naming(methods, classes, packages, order, order, "base");
    }

    /** A rewrite: fresh names (half of the time in reversed name order), packages, and permuted orders. */
    static Naming rewrite(Model model, Random random, int index) {
        var used = new HashSet<String>();
        var methods = new LinkedHashMap<Integer, String>();
        boolean reverse = random.nextBoolean();
        var fresh = new ArrayList<String>();
        for (int i = 0; i < model.methods().size(); i++) fresh.add(freshName(random, used, "m"));
        Collections.sort(fresh);
        if (reverse) Collections.reverse(fresh);
        var ids = new ArrayList<Integer>();
        for (var method : model.methods()) ids.add(method.id());
        Collections.shuffle(ids, random);
        for (int i = 0; i < ids.size(); i++) methods.put(ids.get(i), fresh.get(i));
        var classes = new LinkedHashMap<Integer, String>();
        var packages = new LinkedHashMap<Integer, String>();
        for (int c = 0; c < model.classes(); c++) {
            classes.put(c, freshName(random, used, "K"));
            packages.put(c, PACKAGES.get(random.nextInt(PACKAGES.size())));
        }
        var sourceOrder = new ArrayList<Integer>(classes.keySet());
        Collections.shuffle(sourceOrder, random);
        var importOrder = new ArrayList<Integer>(classes.keySet());
        Collections.shuffle(importOrder, random);
        return new Naming(methods, classes, packages, sourceOrder, importOrder,
                "rewrite " + index + (reverse ? " (reversed names)" : ""));
    }

    private static String freshName(Random random, Set<String> used, String prefix) {
        while (true) {
            var name = new StringBuilder(prefix);
            for (int i = 0; i < 5; i++) name.append((char) ('a' + random.nextInt(26)));
            if (used.add(name.toString())) return name.toString();
        }
    }

    static Variant render(Model model, Naming naming) {
        var libraries = new ArrayList<String>();
        for (int c : naming.sourceOrder()) {
            var body = new StringBuilder();
            for (var method : model.methods()) {
                if (method.owner() != c) continue;
                var expression = new StringBuilder("x * " + method.constant() + " + 1");
                for (int callee : method.calls()) expression.append(" + ").append(call(model, naming, c, callee)).append("(x)");
                body.append("    public static long ").append(naming.methods().get(method.id())).append("(long x) { return ")
                        .append(expression).append("; }\n");
            }
            var imports = new StringBuilder();
            for (int other : naming.importOrder()) {
                if (other != c) imports.append("import ").append(naming.packages().get(other)).append('.').append(naming.classes().get(other)).append(";\n");
            }
            libraries.add("package " + naming.packages().get(c) + ";\n\n" + imports
                    + "import org.julclang.stdlib.annotation.OnchainLibrary;\n\n@OnchainLibrary\npublic class "
                    + naming.classes().get(c) + " {\n" + body + "}\n");
        }
        var imports = new StringBuilder();
        for (int c : naming.importOrder()) imports.append("import ").append(naming.packages().get(c)).append('.').append(naming.classes().get(c)).append(";\n");
        var expression = new StringBuilder("x");
        for (int callee : model.targetCalls()) expression.append(" + ").append(call(model, naming, -1, callee)).append("(x)");
        String target = "package v;\n\n" + imports + "\nclass T {\n    static long f(long x) { return " + expression + "; }\n}\n";
        return new Variant(naming.label(), target, "f", libraries);
    }

    private static String call(Model model, Naming naming, int from, int callee) {
        var method = model.methods().get(callee);
        String name = naming.methods().get(callee);
        return method.owner() == from ? name : naming.classes().get(method.owner()) + "." + name;
    }

    /** Every program and its rewrites: base first. */
    public static List<List<Variant>> programs() {
        var programs = new ArrayList<List<Variant>>();
        for (int p = 0; p < PROGRAMS; p++) {
            var random = new Random(SEED + p);
            var model = model(random);
            var variants = new ArrayList<Variant>();
            variants.add(render(model, baseNaming(model)));
            for (int i = 0; i < REWRITES; i++) variants.add(render(model, rewrite(model, random, i)));
            programs.add(variants);
        }
        return programs;
    }

    /**
     * Prints, per program and mode, how many rewrites compile and how many give the base's bytes: how
     * {@code adr078/pre21-programs.txt} was produced on the pre21 class path.
     */
    public static void main(String[] args) {
        var programs = programs();
        for (int p = 0; p < programs.size(); p++) {
            for (var mode : Adr078Fixtures.modes()) {
                var base = Adr078Fixtures.compile(programs.get(p).getFirst(), mode);
                int compiled = 0, same = 0;
                for (var variant : programs.get(p)) {
                    var outcome = Adr078Fixtures.compile(variant, mode);
                    if (outcome.compiled()) compiled++;
                    if (outcome.toString().equals(base.toString())) same++;
                }
                System.out.println("program " + p + " | " + mode + " = " + compiled + "/" + programs.get(p).size()
                        + " compile, " + same + " give the base's bytes");
            }
        }
    }
}
