package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-062 T21 (#254) and T23 (#255): in a loop with {@code break}, a branch that does not break continues
 * with the statements after its {@code if}, then with the next iteration. The lowering used to send such a
 * branch straight to the next iteration, so the statements after the {@code if} were skipped or run on
 * the wrong values, or a {@code while} never terminated. javac is the oracle: each shape runs on the JVM
 * and on UPLC at every optimization level, on inputs that take and skip each branch and the break.
 */
class LoopBreakContinuationTest {

    private static String source(String[] shape) {
        // An optional third element declares class members, such as a field a local may shadow.
        return "import java.math.BigInteger;\nimport org.julclang.core.types.JulcList;\npublic class Loop { "
                + (shape.length > 2 ? shape[2] : "")
                + " public static BigInteger m(BigInteger a, BigInteger b, JulcList<BigInteger> xs) {\n" + shape[1] + "\n} }\n";
    }

    /** Loop bodies, each named by the shape it covers. */
    private static final List<String[]> SHAPES = List.of(
            new String[]{"while: an update, then a break (T21)", """
                    BigInteger v0 = a; BigInteger v1 = b; long i = 0;
                    while (i < 1) { if (a.compareTo(BigInteger.ZERO) < 0) { v1 = v0; } if (b.compareTo(BigInteger.ZERO) > 0) { break; } i = i + 1; }
                    return v1;"""},
            new String[]{"for-each: a split if/else, then a break (T23)", """
                    BigInteger v0 = a; BigInteger v1 = b;
                    for (var x0 : xs) { if (x0.compareTo(b) < 0) { v0 = v0.add(BigInteger.ONE); } else { v1 = v1.add(x0); } if (x0.compareTo(b) > 0) { break; } }
                    return v0.add(v1.multiply(BigInteger.valueOf(3)));"""},
            new String[]{"one accumulator: break, else an update, then a statement", """
                    BigInteger s = a;
                    for (var x : xs) { if (x.compareTo(b) > 0) { break; } else { s = s.add(x); } s = s.multiply(BigInteger.TWO); }
                    return s;"""},
            new String[]{"one accumulator: an update, else break, then a statement", """
                    BigInteger s = a;
                    for (var x : xs) { if (x.compareTo(b) <= 0) { s = s.add(x); } else { break; } s = s.multiply(BigInteger.TWO); }
                    return s;"""},
            new String[]{"two accumulators: break, else an update, then a statement", """
                    BigInteger s = a; BigInteger n = BigInteger.ZERO;
                    for (var x : xs) { if (x.compareTo(b) > 0) { break; } else { s = s.add(x); } n = n.add(BigInteger.ONE); }
                    return s.add(n.multiply(BigInteger.valueOf(100)));"""},
            new String[]{"one accumulator: an update, then a break", """
                    BigInteger s = a;
                    for (var x : xs) { if (x.compareTo(BigInteger.ZERO) < 0) { s = s.add(x); } if (x.compareTo(b) > 0) { break; } }
                    return s;"""},
            new String[]{"two accumulators: a break, then a split if/else last", """
                    BigInteger s = a; BigInteger n = BigInteger.ZERO;
                    for (var x : xs) { if (x.compareTo(b) > 0) { break; } if (x.compareTo(BigInteger.ZERO) < 0) { s = s.add(x); } else { n = n.add(x); } }
                    return s.add(n.multiply(BigInteger.valueOf(100)));"""},
            new String[]{"two accumulators: a break, then an if without else last", """
                    BigInteger s = a; BigInteger n = BigInteger.ZERO;
                    for (var x : xs) { if (x.compareTo(b) > 0) { break; } n = n.add(BigInteger.ONE); if (x.compareTo(BigInteger.ZERO) < 0) { s = s.add(x); } }
                    return s.add(n.multiply(BigInteger.valueOf(100)));"""},
            new String[]{"one accumulator: a nested break that can fall through, then a statement", """
                    BigInteger s = a;
                    for (var x : xs) { if (x.compareTo(BigInteger.ZERO) > 0) { if (x.compareTo(b) > 0) { break; } s = s.add(x); } s = s.multiply(BigInteger.TWO); }
                    return s;"""},
            new String[]{"one accumulator: a nested break that can fall through, last", """
                    BigInteger s = a;
                    for (var x : xs) { s = s.multiply(BigInteger.TWO); if (x.compareTo(BigInteger.ZERO) > 0) { if (x.compareTo(b) > 0) { break; } s = s.add(x); } }
                    return s;"""},
            new String[]{"two accumulators: a nested break that can fall through, then a statement", """
                    BigInteger s = a; BigInteger n = BigInteger.ZERO;
                    for (var x : xs) { if (x.compareTo(BigInteger.ZERO) > 0) { if (x.compareTo(b) > 0) { break; } s = s.add(x); } n = n.add(BigInteger.ONE); }
                    return s.add(n.multiply(BigInteger.valueOf(100)));"""},
            new String[]{"a scan for a forbidden item behind an outer check", """
                    boolean clean = true;
                    for (var x : xs) { if (x.compareTo(BigInteger.ZERO) > 0) { if (x.compareTo(b) > 0) { clean = false; break; } } }
                    return clean ? BigInteger.ONE : BigInteger.ZERO;"""},
            new String[]{"two accumulators: three ifs in sequence, two of them breaking", """
                    BigInteger s = a; BigInteger n = b;
                    for (var x : xs) { if (x.compareTo(BigInteger.ZERO) < 0) { s = s.add(x); } else { n = n.add(x); } if (x.compareTo(b) > 0) { break; } else { s = s.add(BigInteger.ONE); } if (n.compareTo(BigInteger.TEN) > 0) { break; } n = n.add(BigInteger.ONE); }
                    return s.add(n.multiply(BigInteger.valueOf(100)));"""},
            new String[]{"while, one accumulator: break, else an update, then a statement", """
                    BigInteger s = a; long i = 0;
                    while (i < 3) { if (s.compareTo(b) > 0) { break; } else { s = s.add(BigInteger.ONE); } i = i + 1; }
                    return s;"""},
            new String[]{"while, two accumulators: an update, else break, then a statement", """
                    BigInteger s = a; long i = 0;
                    while (i < 4) { if (s.compareTo(b) <= 0) { s = s.add(BigInteger.TWO); } else { break; } i = i + 1; }
                    return s.add(BigInteger.valueOf(i * 100));"""},
            // Review of the fix: the statements after the if are generated in their own scope, so a
            // declaration there does not change how a branch reads a field of the same name.
            new String[]{"a declaration after the if does not retype a field a branch reads", """
                    BigInteger s = a;
                    for (var x : xs) { if (x.compareTo(b) > 0) { if (t.equals(BigInteger.TEN)) { s = BigInteger.ONE; break; } } String t = "hello"; s = s.add(BigInteger.valueOf(t.length())); }
                    return s;""", "static BigInteger t = BigInteger.TEN;"},
            // A branch local named like a field accumulator stays inside its branch, through a break...
            new String[]{"a branch local shadowing a field accumulator, then break", """
                    for (var x : xs) { if (x.compareTo(b) > 0) { BigInteger s = x; break; } s = s.add(BigInteger.ONE); }
                    return s;""", "static BigInteger s = BigInteger.TEN;"},
            // ...and without one.
            new String[]{"a branch local shadowing a field accumulator, no break", """
                    for (var x : xs) { if (x.compareTo(b) > 0) { BigInteger s = x; s = s.add(x); } s = s.add(BigInteger.ONE); }
                    return s;""", "static BigInteger s = BigInteger.TEN;"});

    private static final long[][] INPUTS = {{-4, 1}, {1, -3}, {-2, 1}, {2, 3}, {0, 10}, {5, 0}};
    private static final long[][] LISTS = {{0}, {-4, 3, 1, 2}, {-1, 2, 4}, {1, 2, 5, 1}, {-5, -6}, {}};
    private static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 14_000_000L);

    @Test
    void everyShapeReturnsWhatTheJvmReturns() throws Exception {
        var problems = new ArrayList<String>();
        for (var shape : SHAPES) {
            String source = source(shape);
            var classes = javac(source);
            for (var level : OptimizationLevel.values()) {
                var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                        new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m").program();
                for (int k = 0; k < INPUTS.length; k++) {
                    var xs = Arrays.stream(LISTS[k]).mapToObj(BigInteger::valueOf).toList();
                    // A fresh class for each input, so that a static field starts at its initial value.
                    Object expected = method(classes).invoke(null, BigInteger.valueOf(INPUTS[k][0]), BigInteger.valueOf(INPUTS[k][1]),
                            JulcList.of(xs.toArray(BigInteger[]::new)));
                    var args = List.<PlutusData>of(new PlutusData.IntData(BigInteger.valueOf(INPUTS[k][0])),
                            new PlutusData.IntData(BigInteger.valueOf(INPUTS[k][1])),
                            new PlutusData.ListData(xs.stream().map(v -> (PlutusData) new PlutusData.IntData(v)).toList()));
                    var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, args, BUDGET);
                    var actual = result instanceof EvalResult.Success ok ? ok.resultTerm() : result;
                    if (!new Term.Const(Constant.integer((BigInteger) expected)).equals(actual)) {
                        problems.add(shape[0] + " at " + level + " on a=" + INPUTS[k][0] + " b=" + INPUTS[k][1]
                                + " xs=" + xs + ": JVM " + expected + ", UPLC " + actual);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void chainedBreakingIfsGrowTheScriptLinearly() {
        // Each if that breaks binds what follows it once, as a join point, instead of copying it into
        // both branches, so n such ifs cost O(n) bytes, not O(2^n).
        var sizes = new ArrayList<Integer>();
        for (int n = 1; n <= 8; n++) {
            var body = new StringBuilder("BigInteger s = a; BigInteger t = b;\nfor (var x : xs) {\n");
            for (int i = 0; i < n; i++) {
                body.append("if (x.compareTo(BigInteger.valueOf(").append(i).append(")) > 0) { break; } else { s = s.add(x); }\n");
                body.append("t = t.add(BigInteger.ONE);\n");
            }
            body.append("}\nreturn s.add(t);");
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source(new String[]{"", body.toString()}), "m").program();
            sizes.add(UplcFlatEncoder.encodeProgram(program).length);
        }
        int step = sizes.get(1) - sizes.get(0);
        for (int n = 2; n < sizes.size(); n++) {
            assertTrue(sizes.get(n) - sizes.get(n - 1) <= 2 * step, "script sizes " + sizes);
        }
    }

    private static Path javac(String source) throws Exception {
        var dir = Files.createTempDirectory("julc-loop-break-");
        var file = dir.resolve("Loop.java");
        Files.writeString(file, source);
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "-cp", System.getProperty("java.class.path"), "-d", dir.toString(), file.toString());
        assertEquals(0, status, errors::toString);
        return dir;
    }

    private static Method method(Path classes) throws Exception {
        var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, LoopBreakContinuationTest.class.getClassLoader());
        return Arrays.stream(loader.loadClass("Loop").getMethods()).filter(m -> m.getName().equals("m")).findFirst().orElseThrow();
    }
}
